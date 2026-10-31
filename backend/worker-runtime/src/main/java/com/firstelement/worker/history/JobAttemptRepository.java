package com.firstelement.worker.history;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code job_attempts}: append-only attempt history, statement for statement with
 * {@code ops/flyway/reference/job-and-idempotency-statements.sql} §1 and §5.
 *
 * <h2>Why the write is idempotent</h2>
 *
 * <p>The reference statement is a plain {@code INSERT}, and
 * {@code job_attempts_organizationId_queueName_jobId_attemptNumber_key} turns a duplicate into a
 * constraint violation. In production that is a real risk rather than a theoretical one: a worker
 * that crashes <em>after</em> inserting the attempt but before the outbox completion write will
 * re-run the same attempt number on restart, and crashing the process over a duplicate history row
 * would turn a recoverable situation into an unrecoverable one. The insert therefore uses
 * {@code ON CONFLICT DO NOTHING} and then re-reads, which is the same reservation shape Phase 4
 * found for {@code messages}: "do not SELECT-then-INSERT, that is the race."
 *
 * <h2>What is never written here</h2>
 *
 * <p>{@code errorMessage} is truncated to {@link JobAttemptRow#MAX_ERROR_MESSAGE} and callers must
 * pass an already-redacted reason. {@code payload} is not a column on this table by design.
 */
@Repository
public class JobAttemptRepository {

    private static final String INSERT = """
        insert into job_attempts (
                "id", "organizationId", "projectId",
                "queueName", "jobType", "jobId", "attemptNumber",
                "outcome", "retryClass", "errorCode", "errorMessage",
                "startedAt", "finishedAt", "durationMs",
                "workerId", "correlationId", "causationId",
                "isReplay", "replayOfId", "outboxEventId", "createdAt")
        values (
                :id, :org, :project,
                :queueName, :jobType, :jobId, :attemptNumber,
                cast(:outcome as "JobAttemptOutcome"), :retryClass, :errorCode, :errorMessage,
                :startedAt, :finishedAt, :durationMs,
                :workerId, :correlationId, :causationId,
                :isReplay, :replayOfId, :outboxEventId, (now() AT TIME ZONE 'UTC'))
        on conflict ("organizationId", "queueName", "jobId", "attemptNumber") do nothing
        returning "id"
        """;

    private static final String SELECT = """
        select "id", "organizationId", "projectId", "queueName", "jobType", "jobId", "attemptNumber",
               "outcome"::text as "outcome", "retryClass", "errorCode", "errorMessage",
               "startedAt", "finishedAt", "durationMs", "workerId", "correlationId", "causationId",
               "isReplay", "replayOfId", "outboxEventId", "createdAt"
          from job_attempts
         where "organizationId" = :org
           and "queueName" = :queueName
           and "jobId" = :jobId
         order by "attemptNumber"
        """;

    /**
     * The last failed attempt: what an operator replay names. Ordered by attempt number rather
     * than by time, because the numbering is the tenant-scoped uniqueness and therefore the only
     * ordering two workers agree on.
     */
    private static final String LAST_FAILURE = """
        select "id", "organizationId", "projectId", "queueName", "jobType", "jobId", "attemptNumber",
               "outcome"::text as "outcome", "retryClass", "errorCode", "errorMessage",
               "startedAt", "finishedAt", "durationMs", "workerId", "correlationId", "causationId",
               "isReplay", "replayOfId", "outboxEventId", "createdAt"
          from job_attempts
         where "organizationId" = :org
           and "queueName" = :queueName
           and "jobId" = :jobId
           and "outcome" in ('RETRYABLE_FAILURE', 'NON_RETRYABLE_FAILURE')
         order by "attemptNumber" desc
         limit 1
        """;

    /** Queue health for one tenant. Uses job_attempts_organizationId_queueName_outcome_createdAt_idx. */
    private static final String QUEUE_HEALTH = """
        select "queueName", "outcome"::text as "outcome", count(*) as "attempts",
               min("createdAt") as "firstAt", max("createdAt") as "lastAt"
          from job_attempts
         where "organizationId" = :org
           and "createdAt" >= :since
         group by "queueName", "outcome"
         order by "queueName", "outcome"
        """;

    /** Tenant-scoped; there is deliberately no cross-tenant attempt sweep in this schema. */
    private static final String TOTAL_ATTEMPTS = """
        select count(*)
          from job_attempts
         where "organizationId" = :org
           and "createdAt" >= :since
        """;

    private static final String TOTAL_FAILURES = """
        select count(*)
          from job_attempts
         where "organizationId" = :org
           and "createdAt" >= :since
           and "outcome" in ('RETRYABLE_FAILURE', 'NON_RETRYABLE_FAILURE')
        """;

    static final RowMapper<JobAttemptRow> ROW = new RowMapper<>() {
        @Override
        public JobAttemptRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new JobAttemptRow(
                rs.getObject("id", UUID.class),
                rs.getObject("organizationId", UUID.class),
                rs.getObject("projectId", UUID.class),
                rs.getString("queueName"),
                rs.getString("jobType"),
                rs.getString("jobId"),
                rs.getInt("attemptNumber"),
                rs.getString("outcome"),
                rs.getString("retryClass"),
                rs.getString("errorCode"),
                rs.getString("errorMessage"),
                rs.getObject("startedAt", LocalDateTime.class),
                rs.getObject("finishedAt", LocalDateTime.class),
                rs.getObject("durationMs", Integer.class),
                rs.getString("workerId"),
                rs.getString("correlationId"),
                rs.getString("causationId"),
                rs.getBoolean("isReplay"),
                rs.getObject("replayOfId", UUID.class),
                rs.getObject("outboxEventId", UUID.class),
                rs.getObject("createdAt", LocalDateTime.class));
        }
    };

    private final NamedParameterJdbcTemplate jdbc;

    public JobAttemptRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * §1/§2 RECORD AN ATTEMPT.
     *
     * @param isReplay true only for an operator replay; {@code replayOfId} is then required by
     *                 {@code job_attempts_replay_check}
     * @return the attempt row's id — the inserted one, or the existing one when the same attempt
     *         number was already recorded
     */
    public UUID record(JobAttemptRow attempt) {
        UUID id = attempt.id() == null ? UUID.randomUUID() : attempt.id();
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("org", attempt.organizationId())
            .addValue("project", attempt.projectId())
            .addValue("queueName", attempt.queueName())
            .addValue("jobType", attempt.jobType())
            .addValue("jobId", attempt.jobId())
            .addValue("attemptNumber", attempt.attemptNumber())
            .addValue("outcome", attempt.outcome())
            .addValue("retryClass", attempt.retryClass())
            .addValue("errorCode", attempt.errorCode())
            .addValue("errorMessage", truncate(attempt.errorMessage()))
            .addValue("startedAt", attempt.startedAt())
            .addValue("finishedAt", attempt.finishedAt())
            .addValue("durationMs", attempt.durationMs())
            .addValue("workerId", attempt.workerId())
            .addValue("correlationId", attempt.correlationId())
            .addValue("causationId", attempt.causationId())
            .addValue("isReplay", attempt.isReplay())
            .addValue("replayOfId", attempt.replayOfId())
            .addValue("outboxEventId", attempt.outboxEventId());
        List<UUID> inserted = jdbc.query(INSERT, parameters, (rs, rowNum) -> rs.getObject("id", UUID.class));
        if (!inserted.isEmpty()) {
            return inserted.get(0);
        }
        // A previous life already recorded this attempt number. Return that row so a replay can
        // still name it, rather than throwing on a duplicate the dispatcher has already survived.
        return findByNumber(attempt.organizationId(), attempt.queueName(), attempt.jobId(),
            attempt.attemptNumber()).map(JobAttemptRow::id).orElse(id);
    }

    /** Every attempt for one job identity, oldest first. */
    public List<JobAttemptRow> attemptsFor(UUID organizationId, String queueName, String jobId) {
        return jdbc.query(SELECT, new MapSqlParameterSource()
            .addValue("org", organizationId)
            .addValue("queueName", queueName)
            .addValue("jobId", jobId), ROW);
    }

    public Optional<JobAttemptRow> findByNumber(UUID organizationId, String queueName, String jobId,
                                                int attemptNumber) {
        return attemptsFor(organizationId, queueName, jobId).stream()
            .filter(row -> row.attemptNumber() == attemptNumber)
            .findFirst();
    }

    /** The most recent failed attempt: the row a replay names. */
    public Optional<JobAttemptRow> lastFailure(UUID organizationId, String queueName, String jobId) {
        return jdbc.query(LAST_FAILURE, new MapSqlParameterSource()
            .addValue("org", organizationId)
            .addValue("queueName", queueName)
            .addValue("jobId", jobId), ROW).stream().findFirst();
    }

    /** Queue health for one tenant over a window. */
    public List<QueueHealthRow> queueHealth(UUID organizationId, LocalDateTime since) {
        return jdbc.query(QUEUE_HEALTH, new MapSqlParameterSource()
            .addValue("org", organizationId)
            .addValue("since", since),
            (rs, rowNum) -> new QueueHealthRow(rs.getString("queueName"), rs.getString("outcome"),
                rs.getInt("attempts"), rs.getObject("firstAt", LocalDateTime.class),
                rs.getObject("lastAt", LocalDateTime.class)));
    }

    /** How many attempts this tenant has made in a window; drives the retry-rate gauge. */
    public int totalAttempts(UUID organizationId, LocalDateTime since) {
        return count(TOTAL_ATTEMPTS, organizationId, since);
    }

    /** How many of them failed. */
    public int totalFailures(UUID organizationId, LocalDateTime since) {
        return count(TOTAL_FAILURES, organizationId, since);
    }

    private int count(String sql, UUID organizationId, LocalDateTime since) {
        Integer value = jdbc.queryForObject(sql, new MapSqlParameterSource()
            .addValue("org", organizationId)
            .addValue("since", since), Integer.class);
        return value == null ? 0 : value;
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        String flattened = message.replaceAll("[\\r\\n\\t]", " ");
        return flattened.length() <= JobAttemptRow.MAX_ERROR_MESSAGE
            ? flattened
            : flattened.substring(0, JobAttemptRow.MAX_ERROR_MESSAGE) + "…";
    }

    /** Aggregated per queue and outcome; the shape the admin queue-health read model renders. */
    public record QueueHealthRow(String queueName, String outcome, int attempts,
                                 LocalDateTime firstAt, LocalDateTime lastAt) {
    }

    /** Convenience for the dispatcher: the elapsed millis of a completed attempt. */
    public static Integer durationMillis(LocalDateTime startedAt, LocalDateTime finishedAt) {
        if (startedAt == null || finishedAt == null) {
            return null;
        }
        long millis = Duration.between(startedAt, finishedAt).toMillis();
        return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, millis));
    }
}