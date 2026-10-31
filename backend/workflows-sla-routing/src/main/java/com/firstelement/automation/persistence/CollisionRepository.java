package com.firstelement.automation.persistence;

import com.firstelement.security.query.ScopedWhere;
import com.firstelement.security.query.TenantScope;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code collision_records}: the append-only record of a race.
 *
 * <h2>Why this table exists when routing explanations do not</h2>
 *
 * <p>The asymmetry is the whole justification, recorded in {@code V20261005040000}: "A collision is
 * not derivable from anything: it records an event that, by definition, left no trace in the rows it
 * raced for. Both messages in the conversation were written; nothing else can say they were written
 * against the same version." An explanation can be derived from history; a race cannot.
 *
 * <h2>The 5-minute bucket is the load-bearing decision</h2>
 *
 * <p>{@code collisionWindow} is {@code floor(epochSeconds / 300)}, stored rather than computed in the
 * index, and both unique indexes are keyed on it. "A timestamp would have to be <em>identical</em>
 * across two racing writers for a unique index to catch anything, and two writers never produce an
 * identical {@code now()} — the guarantee would be decorative." Storing it also lets an application
 * retry compute the same bucket and get a clean 0-row {@code ON CONFLICT} instead of a crash.
 *
 * <h2>Deduplication is not the point; counting once is</h2>
 *
 * <p>{@code ON CONFLICT DO NOTHING} means a retried collision record produces one row, which is what
 * "these two edits are the same incident" has to mean. A 0-row return is a normal outcome the caller
 * continues from, exactly as {@code OutboxEnqueuer.publish} treats a suppressed duplicate.
 *
 * <p>The bucket length itself is an open product decision (Phase 6a follow-up 4: five minutes is a
 * starting point, not a derived fact). It is stored per row, so changing it later is additive.
 */
@Repository
public class CollisionRepository {

    /** The stored bucket length. Named so a test and the repository cannot disagree. */
    public static final int WINDOW_MINUTES = 5;

    /** What to record. At most one of the typed pointers may be set. */
    public record NewCollision(UUID id,
                               String scope,
                               String resourceType,
                               UUID resourceId,
                               UUID conversationId,
                               UUID customerId,
                               UUID noteId,
                               String counterpartyId,
                               String counterpartyType,
                               Integer observedVersion,
                               String outcome,
                               String resolutionDetail,
                               UUID abortedRunId) {
    }

    private static final String INSERT = """
        insert into collision_records
            ("id", "organizationId", "projectId", "scope", "resourceType", "resourceId",
             "conversationId", "customerId", "noteId", "counterpartyId", "counterpartyType",
             "observedVersion", "collisionWindow", "outcome", "resolutionDetail",
             "abortedRunId", "observedAt", "correlationId", "causationId", "createdAt")
        values (:id, :__fe_tenant_organization_id, :__fe_tenant_project_id,
                cast(:scope as "CollisionScope"), :resourceType, :resourceId, :conversationId,
                :customerId, :noteId, :counterpartyId, :counterpartyType, :observedVersion,
                :collisionWindow, cast(:outcome as "CollisionResolution"), :resolutionDetail,
                :abortedRunId, (now() AT TIME ZONE 'UTC'), :correlationId, :causationId,
                (now() AT TIME ZONE 'UTC'))
        on conflict ("organizationId", "scope", "resourceId", "collisionWindow") do nothing
        returning "id"
        """;

    private static final String OTHER_TENANT_COUNT = """
        select count(*)
          from collision_records c
         where c."id" = :id and c."organizationId" <> :__fe_tenant_organization_id
        """;

    private static final String CONVERSATION_COUNT = """
        select count(*)
          from collision_records c
         where c."organizationId" = :%s
           and c."projectId" = :%s
           and c."conversationId" = :conversationId
        """;

    private final NamedParameterJdbcTemplate jdbc;

    public CollisionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Appends a collision, or returns empty when this window already has one for the resource.
     *
     * <p>Both the statement and the conflict target are the canonical ones
     * ({@code workflow-sla-statements.sql} sections 6c and 7).
     *
     * @param observedEpochSeconds the observation time as epoch seconds; the bucket is derived from
     *                              it so a retry computes the same value
     * @return the new row's id, or empty when this window already holds the incident
     */
    public Optional<UUID> record(TenantScope scope, NewCollision collision, long observedEpochSeconds,
                                 String correlationId, String causationId) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", collision.id());
        values.put("scope", collision.scope());
        values.put("resourceType", collision.resourceType());
        values.put("resourceId", collision.resourceId());
        values.put("counterpartyId", collision.counterpartyId());
        values.put("counterpartyType", collision.counterpartyType());
        values.put("outcome", collision.outcome());
        values.put("resolutionDetail", collision.resolutionDetail());
        values.put("collisionWindow", bucket(observedEpochSeconds));

        MapSqlParameterSource parameters = bind(scope, values);
        parameters.addValue("conversationId", collision.conversationId(), java.sql.Types.OTHER);
        parameters.addValue("customerId", collision.customerId(), java.sql.Types.OTHER);
        parameters.addValue("noteId", collision.noteId(), java.sql.Types.OTHER);
        parameters.addValue("observedVersion", collision.observedVersion(), java.sql.Types.INTEGER);
        parameters.addValue("abortedRunId", collision.abortedRunId(), java.sql.Types.OTHER);
        parameters.addValue("correlationId", correlationId, java.sql.Types.VARCHAR);
        parameters.addValue("causationId", causationId, java.sql.Types.VARCHAR);

        return jdbc.query(INSERT, parameters, (rs, rowNum) -> rs.getObject("id", UUID.class))
            .stream().findFirst();
    }

    /**
     * {@code floor(epochSeconds / 300)}. Public so a caller can explain a bucket to an operator.
     */
    public static long bucket(long observedEpochSeconds) {
        return Math.floorDiv(observedEpochSeconds, WINDOW_MINUTES * 60L);
    }

    /**
     * Section 12d: another tenant's collisions are never reachable.
     *
     * <p>A statement rather than only a repository predicate, because "tenant scope is mandatory" is
     * only credible if the query that proves it exists. ADR 0006 section 3 records the residual
     * application rule this cannot enforce in DDL: {@code collision_records.resourceId} is
     * deliberately not a foreign key (a collision must survive the raced-for row's deletion), so it is
     * a repository rule that a caller never supplies another tenant's id.
     */
    public int countOtherTenant(TenantScope scope, UUID collisionId) {
        Integer count = jdbc.queryForObject(OTHER_TENANT_COUNT,
            bind(scope, Map.of("id", collisionId)), Integer.class);
        return count == null ? 0 : count;
    }

    /** Whether a conversation has any recorded collision, for the unassigned explanation. */
    public boolean hasConversationCollision(TenantScope scope, UUID conversationId) {
        String sql = CONVERSATION_COUNT.formatted(ScopedWhere.ORGANIZATION_PARAM,
            ScopedWhere.PROJECT_PARAM);
        Integer count = jdbc.queryForObject(sql,
            bind(scope, Map.of("conversationId", conversationId)), Integer.class);
        return count != null && count > 0;
    }

    /**
     * Binds caller values together with the scope's tenant parameters.
     *
     * <p>{@link ScopedWhere#bindNamed} is the only sanctioned way: it writes the tenant values last and
     * refuses a caller parameter of the reserved name, so a filter string cannot smuggle a different
     * tenant into a statement that looks scoped.
     */
    private static MapSqlParameterSource bind(TenantScope scope, Map<String, ?> values) {
        return ScopedWhere.of(scope).bindNamed(values);
    }
}