package com.firstelement.worker.outbox;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The outbox claim lifecycle, statement for statement with
 * {@code ops/flyway/reference/outbox-claim.sql}.
 *
 * <h2>Why this class is a transcription and not an invention</h2>
 *
 * <p>Phase 5a fixed the claim predicate, the {@code ORDER BY}, the single-statement
 * claim-and-update shape and the UTC comparison <em>because</em> they are what
 * {@code outbox_events_claim_idx} and {@code outbox_events_expired_lease_idx} were built for.
 * Re-deriving them here would make the index the wrong index, so each method below names the
 * reference section it implements and keeps the predicate byte-equivalent.
 *
 * <h2>Every statement carries {@code "organizationId"} and, where a lease exists,
 * {@code "leaseOwner"}</h2>
 *
 * <p>A bare id is not authorization: a client-supplied or remembered id must be re-read under
 * tenant scope before it is acted on, and a worker that does not hold the lease cannot complete,
 * retry or dead-letter a row. The fixture asserts both (0 rows affected, row untouched).
 *
 * <h2>Time is UTC by construction</h2>
 *
 * <p>Every comparison uses {@code (now() AT TIME ZONE 'UTC')} because {@code now()} is
 * {@code timestamptz} and the stored columns are {@code timestamp without time zone}: resolving
 * through the session's zone would judge a lease against local time. Connections must also be UTC
 * (AGENTS.md); this module does not rely on that, it sidesteps it.
 */
@Repository
public class OutboxRepository {

    /** §1 ENQUEUE — inside the SAME transaction as the domain change. */
    static final String ENQUEUE = """
        insert into outbox_events (
                "id", "organizationId", "projectId",
                "aggregateType", "aggregateId", "eventType",
                "payload", "idempotencyKey", "nextAttemptAt",
                "correlationId", "causationId", "createdAt", "updatedAt")
        values (
                :id, :org, :project,
                :aggregateType, :aggregateId, :eventType,
                CAST(:payload AS jsonb), :idempotencyKey, (now() AT TIME ZONE 'UTC'),
                :correlationId, :causationId,
                (now() AT TIME ZONE 'UTC'), (now() AT TIME ZONE 'UTC'))
        on conflict ("organizationId", "idempotencyKey") do nothing
        returning "id", "organizationId", "attempts"
        """;

    /**
     * §2 CLAIM (canonical, tenant-scoped). The UPDATE and the SELECT are ONE statement so the
     * claim and the state change are atomic: there is no window in which a row is leased but not
     * owned. {@code FOR UPDATE SKIP LOCKED} is what lets two workers run side by side.
     */
    static final String CLAIM_TENANT = """
        update outbox_events o
           set "state"           = 'LEASED',
               "leaseOwner"      = :workerId,
               "leaseUntil"      = (now() AT TIME ZONE 'UTC') + make_interval(secs => :leaseSeconds),
               attempts          = o.attempts + 1,
               "lastAttemptAt"   = (now() AT TIME ZONE 'UTC'),
               "updatedAt"       = (now() AT TIME ZONE 'UTC')
          from (
                select "id"
                  from outbox_events
                 where "organizationId" = :org
                   and "state" = 'PENDING'
                   and "nextAttemptAt" <= (now() AT TIME ZONE 'UTC')
                   and ("leaseUntil" is null or "leaseUntil" < (now() AT TIME ZONE 'UTC'))
                 order by "nextAttemptAt", "createdAt", "id"
                 limit :batchSize
                   for update skip locked
               ) claimable
         where o."id" = claimable."id"
        returning o."id", o."organizationId", o."projectId", o."aggregateType", o."aggregateId",
                  o."eventType", o."payload", o.attempts, o."state", o."leaseUntil",
                  o."correlationId", o."causationId", o."idempotencyKey", o."replayCount",
                  o."lastReplayedAt", o."nextAttemptAt", o."lastError", o."deadLetteredAt",
                  o."deadLetterReason", o."deliveredAt", o."leaseOwner"
        """;

    /** §4 COMPLETE — terminal. Only the lease holder may finish it. */
    static final String COMPLETE = """
        update outbox_events
           set "state"       = 'SENT',
               "deliveredAt" = (now() AT TIME ZONE 'UTC'),
               "leaseOwner"  = null,
               "leaseUntil"  = null,
               "updatedAt"   = (now() AT TIME ZONE 'UTC')
         where "id" = :id
           and "organizationId" = :org
           and "state" = 'LEASED'
           and "leaseOwner" = :workerId
        """;

    /** §5 RETRYABLE FAILURE — back to PENDING with backoff + jitter. */
    static final String RETRY = """
        update outbox_events
           set "state"          = 'PENDING',
               "nextAttemptAt"  = (now() AT TIME ZONE 'UTC') + make_interval(secs => :delaySeconds),
               "leaseOwner"     = null,
               "leaseUntil"     = null,
               "lastError"      = :lastError,
               "updatedAt"      = (now() AT TIME ZONE 'UTC')
         where "id" = :id
           and "organizationId" = :org
           and "state" = 'LEASED'
           and "leaseOwner" = :workerId
        """;

    /** §6 DEAD LETTER — non-retryable, or attempts exhausted. */
    static final String DEAD_LETTER = """
        update outbox_events
           set "state"              = 'DEAD',
               "deadLetteredAt"     = (now() AT TIME ZONE 'UTC'),
               "deadLetterReason"   = :reason,
               "leaseOwner"         = null,
               "leaseUntil"         = null,
               "lastError"          = :lastError,
               "updatedAt"          = (now() AT TIME ZONE 'UTC')
         where "id" = :id
           and "organizationId" = :org
           and "state" = 'LEASED'
           and "leaseOwner" = :workerId
        """;

    /**
     * §7 REAP AN EXPIRED LEASE — the crashed-worker path. Repeatable and idempotent: re-running it
     * finds no eligible rows. The attempt that died still counts, so the next backoff is computed
     * from {@code attempts} and not from a reset.
     *
     * <p>This is a scheduling sweep across tenants and is therefore the one statement in this class
     * with no tenant predicate. It touches state and timing only, it is supported by the
     * tenant-less partial index {@code outbox_events_expired_lease_idx}, and every row it returns
     * carries its own {@code organizationId} so the reaper can report per tenant.
     */
    static final String REAP = """
        update outbox_events o
           set "state"          = 'PENDING',
               "leaseOwner"     = null,
               "leaseUntil"     = null,
               "lastError"      = coalesce(o."lastError", 'lease expired; worker presumed dead'),
               "nextAttemptAt"  = (now() AT TIME ZONE 'UTC'),
               "updatedAt"      = (now() AT TIME ZONE 'UTC')
          from (
                select "id"
                  from outbox_events
                 where "state" = 'LEASED'
                   and "leaseUntil" < (now() AT TIME ZONE 'UTC')
                 order by "leaseUntil"
                 limit :batchSize
                   for update skip locked
               ) expired
         where o."id" = expired."id"
        returning o."id", o."organizationId", o.attempts, o."leaseOwner"
        """;

    /** §8 REPLAY — explicit, audited, and never a mutation of history. */
    static final String REPLAY = """
        update outbox_events
           set "state"              = 'PENDING',
               "deadLetteredAt"     = null,
               "deadLetterReason"   = null,
               "nextAttemptAt"      = (now() AT TIME ZONE 'UTC'),
               "replayCount"        = "replayCount" + 1,
               "lastReplayedAt"     = (now() AT TIME ZONE 'UTC'),
               "replayedByUserId"   = :userId,
               "updatedAt"          = (now() AT TIME ZONE 'UTC')
         where "id" = :id
           and "organizationId" = :org
           and "state" = 'DEAD'
        """;

    /**
     * The re-read under tenant scope the claim contract requires. A claimed row MUST be re-read
     * this way before it is acted on: the scheduling indexes are tenant-less by design and are not
     * an authorization path.
     */
    static final String REREAD = """
        select "id", "organizationId", "projectId", "aggregateType", "aggregateId", "eventType",
               "payload", "attempts", "state", "leaseOwner", "leaseUntil", "idempotencyKey",
               "replayCount", "lastReplayedAt", "correlationId", "causationId", "nextAttemptAt",
               "lastError", "deadLetteredAt", "deadLetterReason", "deliveredAt"
          from outbox_events
         where "id" = :id
           and "organizationId" = :org
        """;

    /** §5 read model: what is due right now, for one tenant. Uses the claim index. */
    static final String DUE_COUNT = """
        select count(*)
          from outbox_events
         where "organizationId" = :org
           and "state" = 'PENDING'
           and "nextAttemptAt" <= (now() AT TIME ZONE 'UTC')
           and ("leaseUntil" is null or "leaseUntil" < (now() AT TIME ZONE 'UTC'))
        """;

    static final String STATE_COUNTS = """
        select "state"::text as "state", count(*) as "count"
          from outbox_events
         where "organizationId" = :org
         group by "state"
        """;

    /** The oldest due event's age: the queue-lag signal FR-6.7 asks for. */
    static final String OLDEST_DUE = """
        select min("nextAttemptAt")
          from outbox_events
         where "organizationId" = :org
           and "state" = 'PENDING'
        """;

    /** Dead letters for one tenant, newest first. The operator's replay worklist. */
    static final String DEAD_LETTERS = """
        select "id", "organizationId", "projectId", "aggregateType", "aggregateId", "eventType",
               "payload", "attempts", "state", "leaseOwner", "leaseUntil", "idempotencyKey",
               "replayCount", "lastReplayedAt", "correlationId", "causationId", "nextAttemptAt",
               "lastError", "deadLetteredAt", "deadLetterReason", "deliveredAt"
          from outbox_events
         where "organizationId" = :org
           and "state" = 'DEAD'
         order by "deadLetteredAt" desc nulls last, "createdAt" desc
         limit :limit
        """;

    /** Leases that expired and are waiting to be reaped. An operator seeing rows here is seeing a
     *  worker that died. Uses the partial index. */
    static final String EXPIRED_LEASE_COUNT = """
        select count(*)
          from outbox_events
         where "state" = 'LEASED'
           and "leaseUntil" < (now() AT TIME ZONE 'UTC')
        """;

    static final RowMapper<OutboxRow> ROW = new RowMapper<OutboxRow>() {
        @Override
        public OutboxRow mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
            return new OutboxRow(
                rs.getObject("id", UUID.class),
                rs.getObject("organizationId", UUID.class),
                rs.getObject("projectId", UUID.class),
                rs.getString("aggregateType"),
                rs.getObject("aggregateId", UUID.class),
                rs.getString("eventType"),
                rs.getString("payload"),
                rs.getObject("attempts", Integer.class),
                OutboxState.fromValue(rs.getString("state")),
                rs.getString("leaseOwner"),
                rs.getObject("leaseUntil", LocalDateTime.class),
                rs.getString("idempotencyKey"),
                rs.getObject("replayCount", Integer.class),
                rs.getObject("lastReplayedAt", LocalDateTime.class),
                rs.getString("correlationId"),
                rs.getString("causationId"),
                rs.getObject("nextAttemptAt", LocalDateTime.class),
                rs.getString("lastError"),
                rs.getObject("deadLetteredAt", LocalDateTime.class),
                rs.getString("deadLetterReason"),
                rs.getObject("deliveredAt", LocalDateTime.class));
        }
    };

    private final NamedParameterJdbcTemplate jdbc;

    public OutboxRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------
    // §1 enqueue
    // ------------------------------------------------------------------

    /**
     * Writes one intent. {@code ON CONFLICT DO NOTHING} is the duplicate suppression: a conflict
     * means "this exact work is already queued", which is not an error and the caller continues.
     *
     * @return the new row's identity, or empty when a row with the same
     *         {@code (organizationId, idempotencyKey)} already exists — which tells the caller
     *         whether it won the race
     */
    public Optional<Enqueued> enqueue(OutboxIntent intent) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("id", intent.id())
            .addValue("org", intent.organizationId())
            .addValue("project", intent.projectId())
            .addValue("aggregateType", intent.aggregateType())
            .addValue("aggregateId", intent.aggregateId())
            .addValue("eventType", intent.eventType())
            .addValue("payload", intent.payloadJson())
            .addValue("idempotencyKey", intent.idempotencyKey())
            .addValue("correlationId", intent.correlationId())
            .addValue("causationId", intent.causationId());
        List<Enqueued> rows = jdbc.query(ENQUEUE, parameters,
            (rs, rowNum) -> new Enqueued(rs.getObject("id", UUID.class),
                rs.getObject("organizationId", UUID.class), rs.getObject("attempts", Integer.class)));
        return rows.stream().findFirst();
    }

    // ------------------------------------------------------------------
    // §2 claim
    // ------------------------------------------------------------------

    /**
     * §2. One short transaction per batch. {@code leaseSeconds} comes from configuration; the
     * schema stores no policy.
     *
     * @param organizationId the tenant to claim for. Never {@code null}: the org-wide sweep is a
     *                       second code path and Phase 5 deliberately does not implement it, so a
     *                       {@code null} is refused rather than silently widening the scan.
     */
    public List<OutboxRow> claimFor(UUID organizationId, String workerId, int batchSize,
                                    int leaseSeconds) {
        if (organizationId == null) {
            throw new IllegalArgumentException(
                "Claiming is tenant-scoped; a null organizationId would use the tenant-less sweep "
                    + "index, which is a scheduling scan and not an authorization path.");
        }
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId is required: it is the lease owner");
        }
        if (batchSize < 1) {
            return List.of();
        }
        List<OutboxRow> rows = jdbc.query(CLAIM_TENANT, new MapSqlParameterSource()
            .addValue("org", organizationId)
            .addValue("workerId", workerId)
            .addValue("batchSize", batchSize)
            .addValue("leaseSeconds", leaseSeconds), ROW);
        return rows;
    }

    // ------------------------------------------------------------------
    // §4 / §5 / §6 terminal transitions
    // ------------------------------------------------------------------

    /** §4. @return true when this worker held the lease and completed the row. */
    public boolean complete(UUID organizationId, UUID outboxEventId, String workerId) {
        return jdbc.update(COMPLETE, new MapSqlParameterSource()
            .addValue("id", outboxEventId)
            .addValue("org", organizationId)
            .addValue("workerId", workerId)) == 1;
    }

    /** §5. @return true when this worker held the lease and rescheduled the row. */
    public boolean retry(UUID organizationId, UUID outboxEventId, String workerId,
                         Duration delay, String lastError) {
        return jdbc.update(RETRY, new MapSqlParameterSource()
            .addValue("id", outboxEventId)
            .addValue("org", organizationId)
            .addValue("workerId", workerId)
            .addValue("delaySeconds", delay.toSeconds())
            .addValue("lastError", lastError)) == 1;
    }

    /** §6. @return true when this worker held the lease and dead-lettered the row. */
    public boolean deadLetter(UUID organizationId, UUID outboxEventId, String workerId,
                              String reason, String lastError) {
        return jdbc.update(DEAD_LETTER, new MapSqlParameterSource()
            .addValue("id", outboxEventId)
            .addValue("org", organizationId)
            .addValue("workerId", workerId)
            .addValue("reason", reason)
            .addValue("lastError", lastError)) == 1;
    }

    // ------------------------------------------------------------------
    // §7 reaper
    // ------------------------------------------------------------------

    /** §7. @return the reclaimed rows, each carrying its own tenant. */
    public List<Reaped> reapExpiredLeases(int batchSize) {
        if (batchSize < 1) {
            return List.of();
        }
        return jdbc.query(REAP, new MapSqlParameterSource().addValue("batchSize", batchSize),
            (rs, rowNum) -> new Reaped(rs.getObject("id", UUID.class),
                rs.getObject("organizationId", UUID.class), rs.getObject("attempts", Integer.class),
                rs.getString("leaseOwner")));
    }

    // ------------------------------------------------------------------
    // §8 replay
    // ------------------------------------------------------------------

    /** §8. @return true when the row was DEAD and is now claimable again. Idempotent. */
    public boolean replay(UUID organizationId, UUID outboxEventId, UUID replayedByUserId) {
        return jdbc.update(REPLAY, new MapSqlParameterSource()
            .addValue("id", outboxEventId)
            .addValue("org", organizationId)
            .addValue("userId", replayedByUserId)) == 1;
    }

    // ------------------------------------------------------------------
    // reads
    // ------------------------------------------------------------------

    /** The mandatory tenant-scoped re-read before a claimed row is acted on. */
    public Optional<OutboxRow> reread(UUID organizationId, UUID outboxEventId) {
        return jdbc.query(REREAD, new MapSqlParameterSource()
            .addValue("id", outboxEventId)
            .addValue("org", organizationId), ROW).stream().findFirst();
    }

    /** §5 read model: how many events are due right now for one tenant. */
    public int dueCount(UUID organizationId) {
        Integer count = jdbc.queryForObject(DUE_COUNT,
            new MapSqlParameterSource().addValue("org", organizationId), Integer.class);
        return count == null ? 0 : count;
    }

    /** The admin outbox read model, tenant-scoped, newest-first friendly. */
    public Map<String, Integer> stateCounts(UUID organizationId) {
        Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (OutboxState state : OutboxState.values()) {
            counts.put(state.name(), 0);
        }
        jdbc.query(STATE_COUNTS, new MapSqlParameterSource().addValue("org", organizationId),
            rs -> {
                counts.put(rs.getString("state"), rs.getInt("count"));
            });
        return counts;
    }

    /** The oldest undelivered event: the queue-lag number FR-6.7 asks for. */
    public Duration oldestPendingAge(UUID organizationId) {
        LocalDateTime oldest = jdbc.queryForObject(OLDEST_DUE,
            new MapSqlParameterSource().addValue("org", organizationId), LocalDateTime.class);
        if (oldest == null) {
            return Duration.ZERO;
        }
        Duration age = Duration.between(oldest, LocalDateTime.now());
        return age.isNegative() ? Duration.ZERO : age;
    }

    /** Leases waiting to be reaped. An operator seeing rows here is seeing a worker that died. */
    public int expiredLeaseCount() {
        Integer count = jdbc.queryForObject(EXPIRED_LEASE_COUNT, new MapSqlParameterSource(),
            Integer.class);
        return count == null ? 0 : count;
    }

    /**
     * Dead-lettered events for one tenant, newest first — the operator's replay worklist.
     *
     * <p>Uses {@code outbox_events_organizationId_projectId_state_createdAt_idx} for the tenant and
     * state filter. {@code projectId} is not filtered here: an operator sees the whole tenant's
     * dead letters and drills down per project, which is what an unassigned queue needs.
     */
    public List<OutboxRow> deadLetters(UUID organizationId, int limit) {
        return jdbc.query(DEAD_LETTERS, new MapSqlParameterSource()
            .addValue("org", organizationId)
            .addValue("limit", Math.max(1, limit)), ROW);
    }

    /** One row of {@code outbox_events}, as the dispatcher and the read models see it. */
    public record OutboxRow(UUID id,
                            UUID organizationId,
                            UUID projectId,
                            String aggregateType,
                            UUID aggregateId,
                            String eventType,
                            String payload,
                            Integer attempts,
                            OutboxState state,
                            String leaseOwner,
                            LocalDateTime leaseUntil,
                            String idempotencyKey,
                            Integer replayCount,
                            LocalDateTime lastReplayedAt,
                            String correlationId,
                            String causationId,
                            LocalDateTime nextAttemptAt,
                            String lastError,
                            LocalDateTime deadLetteredAt,
                            String deadLetterReason,
                            LocalDateTime deliveredAt) {

    }

    /** What an enqueue returned: the row's identity, or nothing when it was already queued. */
    public record Enqueued(UUID id, UUID organizationId, int attempts) {
    }

    /** One lease the reaper reclaimed. */
    public record Reaped(UUID id, UUID organizationId, int attempts, String previousLeaseOwner) {
    }
}