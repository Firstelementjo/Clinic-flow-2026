package com.firstelement.worker.history;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code job_dead_letters}: the operator lifecycle of a dead queue job.
 *
 * <p>Statement for statement with {@code ops/flyway/reference/job-and-idempotency-statements.sql}
 * §3, §3a and §3b.
 *
 * <h2>One row per job identity, not one per retry</h2>
 *
 * <p>{@code job_dead_letters_organizationId_queueName_jobId_key} means a job that dies, is replayed
 * and dies again updates ONE row: attempts accumulate and the status returns to OPEN because the
 * job is broken again. A queue that grows a second dead-letter row per retry is a queue nobody can
 * work through.
 *
 * <h2>A death is not a replay</h2>
 *
 * <p>{@code record()} deliberately does <em>not</em> touch {@code replayCount} or
 * {@code lastReplayedAt}; only the replay path does. Those columns are what an operator reads to
 * decide whether a job is worth replaying again, so counting a failure as a replay would report
 * replay activity nobody performed and drive the Phase-7 Admin decision on false data.
 *
 * <h2>payloadSummary is a summary</h2>
 *
 * <p>The migration states the rule: "this is the row an operator copies into a report, so it is
 * where FR-10.6 is enforced by convention." This repository therefore accepts a summary map and
 * never the outbox payload, and the dispatcher builds the summary from counts, provider, queue and
 * error class only.
 */
@Repository
public class DeadLetterRepository {

    /** §3 UPSERT THE DEAD LETTER — one row per (organization, queue, job). */
    private static final String UPSERT = """
        insert into job_dead_letters (
                "id", "organizationId", "projectId",
                "queueName", "jobId", "outboxEventId", "lastAttemptId",
                "reason", "payloadSummary", "attempts",
                "createdAt", "updatedAt")
        values (
                :id, :org, :project,
                :queueName, :jobId, :outboxEventId, :lastAttemptId,
                :reason, CAST(:payloadSummary AS jsonb), :attempts,
                (now() AT TIME ZONE 'UTC'), (now() AT TIME ZONE 'UTC'))
        on conflict ("organizationId", "queueName", "jobId") do update
           set "lastAttemptId"  = excluded."lastAttemptId",
               "outboxEventId"  = excluded."outboxEventId",
               reason           = excluded.reason,
               "payloadSummary" = excluded."payloadSummary",
               attempts         = job_dead_letters.attempts + excluded.attempts,
               "status"         = 'OPEN'::"DeadLetterStatus",
               "resolvedByUserId" = null,
               "resolvedAt"     = null,
               "resolutionNote" = null,
               "updatedAt"      = (now() AT TIME ZONE 'UTC')
        returning "id"
        """;

    /** §3a RESOLVE / DISCARD. The CHECK requires who and when. */
    private static final String RESOLVE = """
        update job_dead_letters
           set "status"          = cast(:status as "DeadLetterStatus"),
               "resolvedByUserId" = :userId,
               "resolvedAt"       = (now() AT TIME ZONE 'UTC'),
               "resolutionNote"   = :note,
               "updatedAt"        = (now() AT TIME ZONE 'UTC')
         where "id" = :id
           and "organizationId" = :org
        """;

    /** §3b MOVE TO REPLAYED. Idempotent: re-running it changes nothing. */
    private static final String MARK_REPLAYED = """
        update job_dead_letters
           set "status"          = 'REPLAYED'::"DeadLetterStatus",
               "replayCount"     = job_dead_letters."replayCount" + 1,
               "lastReplayedAt"  = (now() AT TIME ZONE 'UTC'),
               "updatedAt"       = (now() AT TIME ZONE 'UTC')
         where "id" = :id
           and "organizationId" = :org
           and "status" = 'OPEN'::"DeadLetterStatus"
        """;

    private static final String FIND = """
        select "id", "organizationId", "projectId", "queueName", "jobId", "outboxEventId",
               "lastAttemptId", "reason", "payloadSummary"::text as "payloadSummary", "attempts",
               "status"::text as "status", "resolvedByUserId", "resolvedAt", "resolutionNote",
               "replayCount", "lastReplayedAt", "createdAt", "updatedAt"
          from job_dead_letters
         where "organizationId" = :org
           and "queueName" = :queueName
           and "jobId" = :jobId
        """;

    /** The dead-letter queue an operator works through. Tenant-scoped. */
    private static final String OPEN_QUEUE = """
        select "id", "organizationId", "projectId", "queueName", "jobId", "outboxEventId",
               "lastAttemptId", "reason", "payloadSummary"::text as "payloadSummary", "attempts",
               "status"::text as "status", "resolvedByUserId", "resolvedAt", "resolutionNote",
               "replayCount", "lastReplayedAt", "createdAt", "updatedAt"
          from job_dead_letters
         where "organizationId" = :org
           and "status" = 'OPEN'::"DeadLetterStatus"
         order by "createdAt"
         limit :limit
        """;

    private static final String OPEN_COUNT = """
        select count(*)
          from job_dead_letters
         where "organizationId" = :org
           and "status" = 'OPEN'::"DeadLetterStatus"
        """;

    private final NamedParameterJdbcTemplate jdbc;

    public DeadLetterRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** §3. @return the dead-letter row's id (inserted or updated). */
    public UUID record(UUID organizationId, UUID projectId, String queueName, String jobId,
                       UUID outboxEventId, UUID lastAttemptId, String reason,
                       String payloadSummaryJson, int attempts) {
        UUID id = UUID.randomUUID();
        return jdbc.queryForObject(UPSERT, new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("org", organizationId)
            .addValue("project", projectId)
            .addValue("queueName", queueName)
            .addValue("jobId", jobId)
            .addValue("outboxEventId", outboxEventId)
            .addValue("lastAttemptId", lastAttemptId)
            .addValue("reason", reason)
            .addValue("payloadSummary", payloadSummaryJson == null ? "{}" : payloadSummaryJson)
            .addValue("attempts", Math.max(1, attempts)), UUID.class);
    }

    /** §3a. @return true when the row existed for this tenant. */
    public boolean resolve(UUID organizationId, UUID deadLetterId, String status, UUID userId,
                           String note) {
        return jdbc.update(RESOLVE, new MapSqlParameterSource()
            .addValue("id", deadLetterId)
            .addValue("org", organizationId)
            .addValue("status", status)
            .addValue("userId", userId)
            .addValue("note", note)) == 1;
    }

    /** §3b. @return true when the row was OPEN and is now REPLAYED. */
    public boolean markReplayed(UUID organizationId, UUID deadLetterId) {
        return jdbc.update(MARK_REPLAYED, new MapSqlParameterSource()
            .addValue("id", deadLetterId)
            .addValue("org", organizationId)) == 1;
    }

    public java.util.Optional<DeadLetterRow> find(UUID organizationId, String queueName,
                                                  String jobId) {
        return jdbc.query(FIND, new MapSqlParameterSource()
            .addValue("org", organizationId)
            .addValue("queueName", queueName)
            .addValue("jobId", jobId),
            (rs, rowNum) -> new DeadLetterRow(rs.getObject("id", UUID.class),
                rs.getObject("organizationId", UUID.class), rs.getObject("projectId", UUID.class),
                rs.getString("queueName"), rs.getString("jobId"),
                rs.getObject("outboxEventId", UUID.class),
                rs.getObject("lastAttemptId", UUID.class), rs.getString("reason"),
                rs.getString("payloadSummary"), rs.getInt("attempts"), rs.getString("status"),
                rs.getObject("resolvedByUserId", UUID.class),
                rs.getObject("resolvedAt", LocalDateTime.class), rs.getString("resolutionNote"),
                rs.getObject("replayCount", Integer.class),
                rs.getObject("lastReplayedAt", LocalDateTime.class),
                rs.getObject("createdAt", LocalDateTime.class),
                rs.getObject("updatedAt", LocalDateTime.class))).stream().findFirst();
    }

    /** The open dead letters for one tenant. */
    public List<DeadLetterRow> open(UUID organizationId, int limit) {
        return jdbc.query(OPEN_QUEUE, new MapSqlParameterSource()
            .addValue("org", organizationId)
            .addValue("limit", Math.max(1, limit)),
            (rs, rowNum) -> new DeadLetterRow(rs.getObject("id", UUID.class),
                rs.getObject("organizationId", UUID.class), rs.getObject("projectId", UUID.class),
                rs.getString("queueName"), rs.getString("jobId"),
                rs.getObject("outboxEventId", UUID.class),
                rs.getObject("lastAttemptId", UUID.class), rs.getString("reason"),
                rs.getString("payloadSummary"), rs.getInt("attempts"), rs.getString("status"),
                rs.getObject("resolvedByUserId", UUID.class),
                rs.getObject("resolvedAt", LocalDateTime.class), rs.getString("resolutionNote"),
                rs.getObject("replayCount", Integer.class),
                rs.getObject("lastReplayedAt", LocalDateTime.class),
                rs.getObject("createdAt", LocalDateTime.class),
                rs.getObject("updatedAt", LocalDateTime.class)));
    }

    public int openCount(UUID organizationId) {
        Integer count = jdbc.queryForObject(OPEN_COUNT,
            new MapSqlParameterSource().addValue("org", organizationId), Integer.class);
        return count == null ? 0 : count;
    }

    /** One operator-facing dead letter. */
    public record DeadLetterRow(UUID id,
                                UUID organizationId,
                                UUID projectId,
                                String queueName,
                                String jobId,
                                UUID outboxEventId,
                                UUID lastAttemptId,
                                String reason,
                                String payloadSummary,
                                int attempts,
                                String status,
                                UUID resolvedByUserId,
                                LocalDateTime resolvedAt,
                                String resolutionNote,
                                Integer replayCount,
                                LocalDateTime lastReplayedAt,
                                LocalDateTime createdAt,
                                LocalDateTime updatedAt) {
    }
}