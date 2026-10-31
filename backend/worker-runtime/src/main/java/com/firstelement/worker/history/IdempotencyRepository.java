package com.firstelement.worker.history;

import com.firstelement.worker.config.UtcTime;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code idempotency_records}: reserve a key <em>before</em> doing the work, complete it once.
 *
 * <p>Statement for statement with {@code ops/flyway/reference/job-and-idempotency-statements.sql}
 * §4, §4a, §4b and §4c.
 *
 * <h2>The ownership split this phase decided</h2>
 *
 * <p>Phase 5a left an open decision (its risk 2): Phase 4 stores reply idempotency keys in
 * {@code messages.metadata->>'idempotencyKey'}, and {@code idempotency_records} exists too. Two
 * stores for one key space means a retry could be suppressed by one and replayed by the other.
 *
 * <p>The split, from this phase:
 *
 * <ul>
 *   <li><b>{@code idempotency_records} owns everything asynchronous</b> — inbound provider events
 *       (scope {@link Scope#PROVIDER_EVENT}), outbound delivery requests
 *       ({@link Scope#OUTBOUND_DELIVERY}) and repeatable API writes
 *       ({@link Scope#API_REQUEST}). It is tenant-scoped, has an expiry, and can be purged.</li>
 *   <li><b>{@code messages.metadata} keeps reply keys</b>, because the key belongs to one message
 *       on one conversation and moving it would be a data migration on an existing table, which no
 *       phase so far has been permitted to perform.</li>
 * </ul>
 *
 * <p>Both stores are <em>independent</em>: this repository never reads or writes
 * {@code messages.metadata}, so they cannot disagree about a row.
 *
 * <h2>Duplicate suppression is reservation, not a prior SELECT</h2>
 *
 * <p>{@code INSERT ... ON CONFLICT DO NOTHING RETURNING} is the reservation: the winner inserts,
 * the loser gets no row back and re-reads through {@link #find}. A {@code SELECT}-then-
 * {@code INSERT} is the race Phase 4 already found and fixed on {@code messages}.
 */
@Repository
public class IdempotencyRepository {

    /** The scopes this schema anticipates. Text, not an enum: the vocabulary will grow. */
    public static final class Scope {
        private Scope() {
        }

        /** A webhook/inbound provider id was already accepted. */
        public static final String PROVIDER_EVENT = "PROVIDER_EVENT";

        /** An outbound send was already requested for this message. */
        public static final String OUTBOUND_DELIVERY = "OUTBOUND_DELIVERY";

        /** A mutating API call carried an {@code Idempotency-Key}. */
        public static final String API_REQUEST = "API_REQUEST";
    }

    /** §4 RESERVE. */
    private static final String RESERVE = """
        insert into idempotency_records (
                "id", "organizationId", "projectId",
                "scope", "idempotencyKey", "requestFingerprint",
                "state", "lockExpiresAt", "correlationId", "expiresAt", "createdAt", "updatedAt")
        values (
                :id, :org, :project,
                :scope, :key, :fingerprint,
                'IN_PROGRESS'::"IdempotencyState",
                (now() AT TIME ZONE 'UTC') + make_interval(secs => :lockSeconds),
                :correlationId, :expiresAt,
                (now() AT TIME ZONE 'UTC'), (now() AT TIME ZONE 'UTC'))
        on conflict ("organizationId", "scope", "idempotencyKey") do nothing
        returning "id", "state"::text as "state"
        """;

    /** §4a COMPLETE. */
    private static final String COMPLETE = """
        update idempotency_records
           set "state"            = 'COMPLETED'::"IdempotencyState",
               "responseStatus"   = :responseStatus,
               "responseBodyHash" = :responseBodyHash,
               "resourceType"     = :resourceType,
               "resourceId"       = :resourceId,
               "lockExpiresAt"    = null,
               "updatedAt"        = (now() AT TIME ZONE 'UTC')
         where "id" = :id
           and "organizationId" = :org
           and "state" = 'IN_PROGRESS'::"IdempotencyState"
        """;

    /** §4b TERMINAL FAILURE. */
    private static final String FAIL = """
        update idempotency_records
           set "state"           = 'FAILED'::"IdempotencyState",
               "resourceType"    = :resourceType,
               "lockExpiresAt"   = null,
               "updatedAt"       = (now() AT TIME ZONE 'UTC')
         where "id" = :id
           and "organizationId" = :org
           and "state" = 'IN_PROGRESS'::"IdempotencyState"
        """;

    /** §4c THE LOOKUP every retry starts with. One index probe; can never cross tenants. */
    private static final String FIND = """
        select "id", "state"::text as "state", "requestFingerprint", "responseStatus",
               "responseBodyHash", "resourceType", "resourceId", "lockExpiresAt", "expiresAt",
               "createdAt"
          from idempotency_records
         where "organizationId" = :org
           and "scope" = :scope
           and "idempotencyKey" = :key
        """;

    private final NamedParameterJdbcTemplate jdbc;

    public IdempotencyRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** §4. @return the new record, or empty when the key was already reserved. */
    public Optional<IdempotencyRecord> reserve(UUID organizationId, UUID projectId, String scope,
                                               String key, String requestFingerprint,
                                               Duration lockWindow, LocalDateTime expiresAt,
                                               String correlationId) {
        return jdbc.query(RESERVE, new MapSqlParameterSource()
            .addValue("id", UUID.randomUUID())
            .addValue("org", organizationId)
            .addValue("project", projectId)
            .addValue("scope", scope)
            .addValue("key", key)
            .addValue("fingerprint", requestFingerprint)
            .addValue("lockSeconds", Math.max(1L, lockWindow.toSeconds()))
            .addValue("correlationId", correlationId)
            .addValue("expiresAt", expiresAt),
            (rs, rowNum) -> new IdempotencyRecord(rs.getObject("id", UUID.class),
                rs.getString("state"), rs.getString("requestFingerprint"), null, null, null, null,
                null, null, null)).stream().findFirst();
    }

    /** §4a. @return true when this record was still IN_PROGRESS and is now COMPLETED. */
    public boolean complete(UUID organizationId, UUID recordId, int responseStatus,
                            String responseBodyHash, String resourceType, UUID resourceId) {
        return jdbc.update(COMPLETE, new MapSqlParameterSource()
            .addValue("id", recordId)
            .addValue("org", organizationId)
            .addValue("responseStatus", responseStatus)
            .addValue("responseBodyHash", responseBodyHash)
            .addValue("resourceType", resourceType)
            .addValue("resourceId", resourceId)) == 1;
    }

    /** §4b. @return true when this record was still IN_PROGRESS and is now FAILED. */
    public boolean fail(UUID organizationId, UUID recordId, String resourceType) {
        return jdbc.update(FAIL, new MapSqlParameterSource()
            .addValue("id", recordId)
            .addValue("org", organizationId)
            .addValue("resourceType", resourceType)) == 1;
    }

    /** §4c. Tenant-scoped by construction: the unique index leads with organizationId. */
    public Optional<IdempotencyRecord> find(UUID organizationId, String scope, String key) {
        return jdbc.query(FIND, new MapSqlParameterSource()
            .addValue("org", organizationId)
            .addValue("scope", scope)
            .addValue("key", key),
            (rs, rowNum) -> new IdempotencyRecord(rs.getObject("id", UUID.class),
                rs.getString("state"), rs.getString("requestFingerprint"),
                rs.getObject("responseStatus", Integer.class), rs.getString("responseBodyHash"),
                rs.getString("resourceType"), rs.getObject("resourceId", UUID.class),
                rs.getObject("lockExpiresAt", LocalDateTime.class),
                rs.getObject("expiresAt", LocalDateTime.class),
                rs.getObject("createdAt", LocalDateTime.class))).stream().findFirst();
    }

    /**
     * Whether a duplicate may take over.
     *
     * <p>An in-flight record whose lock has not expired means "already running" (HTTP 409). An
     * expired lock means the original caller died and the work may be taken over — otherwise a
     * crash between reserve and complete would suppress the operation forever.
     */
    public static boolean takeOverAllowed(IdempotencyRecord record) {
        if (record == null) {
            return true;
        }
        if (!"IN_PROGRESS".equals(record.state())) {
            return false;
        }
        return record.lockExpiresAt() == null || !record.lockExpiresAt().isAfter(UtcTime.now());
    }

    /** One {@code idempotency_records} row. */
    public record IdempotencyRecord(UUID id,
                                    String state,
                                    String requestFingerprint,
                                    Integer responseStatus,
                                    String responseBodyHash,
                                    String resourceType,
                                    UUID resourceId,
                                    LocalDateTime lockExpiresAt,
                                    LocalDateTime expiresAt,
                                    LocalDateTime createdAt) {
    }
}