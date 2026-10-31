package com.firstelement.observ.jobs;

import com.firstelement.admin.audit.AuditAttribution;
import com.firstelement.security.query.ScopedWhere;
import com.firstelement.security.query.TenantScope;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The one query an operator runs to answer "what happened to this job?" — and it never selects the
 * outbox payload.
 *
 * <h2>Why the payload column is absent, not filtered</h2>
 *
 * <p>{@code V20261004000100} Q2 says the outbox payload is the delivery envelope and "never customer
 * prose", and {@code InboundWebhookIntakeService} documents the one documented exception: this schema
 * has no durable pre-conversation store, so an inbound provider payload waits in the tenant-scoped
 * queue row and therefore <em>does</em> carry customer text. That class is explicit that the text
 * "never reaches an admin read model".
 *
 * <p>So {@code payload} is not in this file at all. There is no value in this process to redact, log
 * by accident, or serialise into an operator response, and an operator does not need it: the
 * diagnostic value of a failed job is its queue, its attempt history, its error class and its
 * correlation id — not the message it was carrying.
 *
 * <h2>What "diagnose" means concretely</h2>
 *
 * <p>{@link #diagnose} returns, for one outbox event id and one tenant:
 *
 * <ol>
 *   <li>the event's own state, attempts, lease and dead-letter reason;</li>
 *   <li>every {@code job_attempts} row, in attempt-number order, each naming its worker, its retry
 *       class and its error code — <em>not</em> {@code errorMessage}, which is a provider string and
 *       is the single most likely place for a customer's words to appear;</li>
 *   <li>the {@code job_dead_letters} row, whose {@code payloadSummary} the dispatcher builds from
 *       counts, queue and error class and never from the payload;</li>
 *   <li>the {@code job_integration_events} rows for the same correlation id, which is how an operator
 *       follows one message across the whole pipeline.</li>
 * </ol>
 */
@Repository
public class JobDiagnosisRepository {

    /** The event itself. No payload column. */
    private static final String EVENT = """
        select o."id", o."organizationId", o."projectId", o."aggregateType", o."aggregateId",
               o."eventType", o.attempts, o."state"::text as "state", o."leaseOwner", o."leaseUntil",
               o."idempotencyKey", o."replayCount", o."lastReplayedAt", o."correlationId",
               o."causationId", o."nextAttemptAt", o."lastError", o."deadLetteredAt",
               o."deadLetterReason", o."deliveredAt", o."createdAt"
          from outbox_events o
         where o."organizationId" = :__fe_tenant_organization_id
           and o."id" = :id
        """;

    /**
     * Every attempt for the job identity, oldest first.
     *
     * <p>{@code errorCode} and {@code retryClass} are selected; {@code errorMessage} is not. The code
     * is a stable token an operator can search for; the message is free text from a provider or from
     * a handler, and it is the field most likely to contain something a customer said.
     */
    private static final String ATTEMPTS = """
        select a."id", a."queueName", a."jobType", a."jobId", a."attemptNumber",
               a."outcome"::text as "outcome", a."retryClass", a."errorCode", a."startedAt",
               a."finishedAt", a."durationMs", a."workerId", a."correlationId", a."isReplay",
               a."replayOfId", a."outboxEventId", a."createdAt"
          from job_attempts a
         where a."organizationId" = :__fe_tenant_organization_id
           and (cast(:eventId as uuid) is null or a."outboxEventId" = cast(:eventId as uuid))
           and (cast(:queueName as text) is null or a."queueName" = cast(:queueName as text))
           and (cast(:jobId as text) is null or a."jobId" = cast(:jobId as text))
         order by a."attemptNumber", a."createdAt"
         limit :limit
        """;

    /**
     * The dead-letter row for this job.
     *
     * <p>{@code payloadSummary} is safe to select: the dispatcher builds it from counts, queue and
     * error class, and {@code DeadLetterRepository}'s class note states that it "accepts a summary
     * map and never the outbox payload".
     */
    private static final String DEAD_LETTER = """
        select d."id", d."projectId", d."queueName", d."jobId", d."outboxEventId", d."lastAttemptId",
               d.reason, d."payloadSummary"::text as "payloadSummary", d.attempts,
               d."status"::text as "status", d."resolvedByUserId", d."resolvedAt",
               d."resolutionNote", d."replayCount", d."lastReplayedAt", d."createdAt"
          from job_dead_letters d
         where d."organizationId" = :__fe_tenant_organization_id
           and (cast(:eventId as uuid) is null or d."outboxEventId" = cast(:eventId as uuid))
         order by d."createdAt" desc
         limit :limit
        """;

    /**
     * The integration stream for one correlation id.
     *
     * <p>Correlation ids are global by construction, so this predicate is tenant-led: it can only ever
     * return this tenant's own rows for a correlation id it already knows.
     */
    private static final String INTEGRATION_EVENTS = """
        select j."id", j."component", j."componentType"::text as "componentType", j."eventType",
               j."outcome"::text as "outcome", j."queueName", j."jobId", j."outboxEventId",
               j."jobAttemptId", j."correlationId", j.detail, j."failureCode", j."latencyMs",
               j."occurredAt"
          from job_integration_events j
         where j."organizationId" = :__fe_tenant_organization_id
           and (cast(:eventId as uuid) is null or j."outboxEventId" = cast(:eventId as uuid))
           and (cast(:correlationId as text) is null
                or j."correlationId" = cast(:correlationId as text))
         order by j."occurredAt", j."id"
         limit :limit
        """;

    /** The worklist: dead and failing events for one tenant, oldest problem first. */
    private static final String WORKLIST = """
        select o."id", o."projectId", o."aggregateType", o."aggregateId", o."eventType", o.attempts,
               o."state"::text as "state", o."correlationId", o."deadLetteredAt",
               o."deadLetterReason", o."lastError", o."nextAttemptAt", o."createdAt",
               (select count(*) from job_dead_letters d
                 where d."organizationId" = o."organizationId"
                   and d."outboxEventId" = o."id" and d."status" = 'OPEN') as open_dead_letters
          from outbox_events o
         where o."organizationId" = :__fe_tenant_organization_id
           and o."state" in ('DEAD', 'LEASED')
           and (cast(:filterProjectId as uuid) is null
                or o."projectId" = cast(:filterProjectId as uuid))
           and (cast(:filterQueue as text) is null or o."eventType" = cast(:filterQueue as text))
         order by o."deadLetteredAt" asc nulls last, o."createdAt" asc
         limit :limit
        """;

    /**
     * Stranded leases for this tenant: a {@code LEASED} row whose lease has expired.
     *
     * <p>Tenant-scoped on purpose. {@code OutboxRepository.REAP} is a scheduling sweep across
     * tenants, which ADR-0008 §1 accepts because it touches state and timing only; this read model
     * deliberately re-adds the tenant predicate so the answer is always "my tenants", never
     * "everybody's", and so the index it uses
     * ({@code outbox_events_organizationId_projectId_state_createdAt_idx}) is the tenant-led one.
     */
    private static final String STRANDED_LEASES = """
        select o."id", o."projectId", o."eventType", o."leaseOwner", o."leaseUntil", o.attempts,
               o."lastError", o."createdAt",
               extract(epoch from ((now() AT TIME ZONE 'UTC') - o."leaseUntil"))::bigint
                 as lease_overdue_seconds
          from outbox_events o
         where o."organizationId" = :__fe_tenant_organization_id
           and o."state" = 'LEASED'
           and o."leaseUntil" < (now() AT TIME ZONE 'UTC')
           and (cast(:filterProjectId as uuid) is null
                or o."projectId" = cast(:filterProjectId as uuid))
         order by o."leaseUntil"
         limit :limit
        """;

    /** Queue-level rollup: attempts and outcomes per queue over a window. */
    private static final String QUEUE_ROLLUP = """
        select a."queueName" as queue, a."outcome"::text as outcome, count(*) as occurrences,
               max(a."createdAt") as last_at,
               sum(coalesce(a."durationMs", 0))::bigint as total_duration_ms
          from job_attempts a
         where a."organizationId" = :__fe_tenant_organization_id
           and a."createdAt" >= :since
         group by a."queueName", a."outcome"
         order by a."queueName", a."outcome"
         limit :limit
        """;

    /** A stranded lease, and how overdue it is. */
    public record StrandedLease(UUID outboxEventId,
                                UUID projectId,
                                String queue,
                                String leaseOwner,
                                LocalDateTime leaseUntil,
                                Integer attempts,
                                String lastError,
                                LocalDateTime createdAt,
                                Long leaseOverdueSeconds) {
    }

    /** One outbox event, without its payload. */
    public record EventRow(UUID id,
                           UUID organizationId,
                           UUID projectId,
                           String aggregateType,
                           UUID aggregateId,
                           String eventType,
                           Integer attempts,
                           String state,
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
                           LocalDateTime deliveredAt,
                           LocalDateTime createdAt) {
    }

    /**
     * One attempt.
     *
     * @param errorCode    a stable token; {@code errorMessage} is deliberately not selected
     * @param isReplay     true for the attempt a replay created
     * @param replayOfId   the failed attempt this replay replaces
     */
    public record AttemptRow(UUID id,
                             String queueName,
                             String jobType,
                             String jobId,
                             int attemptNumber,
                             String outcome,
                             String retryClass,
                             String errorCode,
                             LocalDateTime startedAt,
                             LocalDateTime finishedAt,
                             Integer durationMs,
                             String workerId,
                             String correlationId,
                             boolean isReplay,
                             UUID replayOfId,
                             UUID outboxEventId,
                             LocalDateTime createdAt) {
    }

    /** The dead-letter row for a job. */
    public record DeadLetterRow(UUID id,
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
                                LocalDateTime createdAt) {
    }

    /** One row of the job/integration stream. */
    public record IntegrationEventRow(UUID id,
                                      String component,
                                      String componentType,
                                      String eventType,
                                      String outcome,
                                      String queueName,
                                      String jobId,
                                      UUID outboxEventId,
                                      UUID jobAttemptId,
                                      String correlationId,
                                      String detail,
                                      String failureCode,
                                      Integer latencyMs,
                                      LocalDateTime occurredAt) {
    }

    /** One row of the operator worklist. */
    public record WorklistRow(UUID outboxEventId,
                              UUID projectId,
                              String aggregateType,
                              UUID aggregateId,
                              String queue,
                              Integer attempts,
                              String state,
                              String correlationId,
                              LocalDateTime deadLetteredAt,
                              String deadLetterReason,
                              String lastError,
                              LocalDateTime nextAttemptAt,
                              LocalDateTime createdAt,
                              int openDeadLetters) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public JobDiagnosisRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One outbox event, read under the tenant scope. Empty when it is another tenant's. */
    public java.util.Optional<EventRow> event(TenantScope scope, UUID eventId) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope).bindNamed(Map.of("id", eventId));
        return jdbc.query(EVENT, parameters, (rs, rowNum) -> new EventRow(
            rs.getObject("id", UUID.class),
            rs.getObject("organizationId", UUID.class),
            rs.getObject("projectId", UUID.class),
            rs.getString("aggregateType"),
            rs.getObject("aggregateId", UUID.class),
            rs.getString("eventType"),
            rs.getObject("attempts", Integer.class),
            rs.getString("state"),
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
            rs.getObject("deliveredAt", LocalDateTime.class),
            rs.getObject("createdAt", LocalDateTime.class))).stream().findFirst();
    }

    /** Every attempt for one job identity, oldest first. */
    public List<AttemptRow> attempts(TenantScope scope, UUID eventId, String queueName,
                                     String jobId, int limit) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope)
            .bindNamed(Map.of("limit", Math.max(1, limit)));
        parameters.addValue("eventId", eventId);
        parameters.addValue("queueName", queueName);
        parameters.addValue("jobId", jobId);
        return jdbc.query(ATTEMPTS, parameters, (rs, rowNum) -> new AttemptRow(
            rs.getObject("id", UUID.class),
            rs.getString("queueName"),
            rs.getString("jobType"),
            rs.getString("jobId"),
            rs.getInt("attemptNumber"),
            rs.getString("outcome"),
            rs.getString("retryClass"),
            rs.getString("errorCode"),
            rs.getObject("startedAt", LocalDateTime.class),
            rs.getObject("finishedAt", LocalDateTime.class),
            rs.getObject("durationMs", Integer.class),
            rs.getString("workerId"),
            rs.getString("correlationId"),
            rs.getBoolean("isReplay"),
            rs.getObject("replayOfId", UUID.class),
            rs.getObject("outboxEventId", UUID.class),
            rs.getObject("createdAt", LocalDateTime.class)));
    }

    /** The dead-letter rows for a job, newest first. */
    public List<DeadLetterRow> deadLetters(TenantScope scope, UUID eventId, int limit) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope)
            .bindNamed(Map.of("limit", Math.max(1, limit)));
        parameters.addValue("eventId", eventId);
        return jdbc.query(DEAD_LETTER, parameters, (rs, rowNum) -> new DeadLetterRow(
            rs.getObject("id", UUID.class),
            rs.getObject("projectId", UUID.class),
            rs.getString("queueName"),
            rs.getString("jobId"),
            rs.getObject("outboxEventId", UUID.class),
            rs.getObject("lastAttemptId", UUID.class),
            rs.getString("reason"),
            rs.getString("payloadSummary"),
            rs.getInt("attempts"),
            rs.getString("status"),
            rs.getObject("resolvedByUserId", UUID.class),
            rs.getObject("resolvedAt", LocalDateTime.class),
            rs.getString("resolutionNote"),
            rs.getObject("replayCount", Integer.class),
            rs.getObject("lastReplayedAt", LocalDateTime.class),
            rs.getObject("createdAt", LocalDateTime.class)));
    }

    /** The integration-stream rows for one correlation id. */
    public List<IntegrationEventRow> integrationEvents(TenantScope scope, UUID eventId,
                                                       String correlationId, int limit) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope)
            .bindNamed(Map.of("limit", Math.max(1, limit)));
        parameters.addValue("eventId", eventId);
        parameters.addValue("correlationId", correlationId);
        return jdbc.query(INTEGRATION_EVENTS, parameters, (rs, rowNum) -> new IntegrationEventRow(
            rs.getObject("id", UUID.class),
            rs.getString("component"),
            rs.getString("componentType"),
            rs.getString("eventType"),
            rs.getString("outcome"),
            rs.getString("queueName"),
            rs.getString("jobId"),
            rs.getObject("outboxEventId", UUID.class),
            rs.getObject("jobAttemptId", UUID.class),
            rs.getString("correlationId"),
            rs.getString("detail"),
            rs.getString("failureCode"),
            rs.getObject("latencyMs", Integer.class),
            rs.getObject("occurredAt", LocalDateTime.class)));
    }

    /** The worklist: dead and stranded events, oldest problem first. */
    public List<WorklistRow> worklist(TenantScope scope, UUID projectId, String queue, int limit) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope)
            .bindNamed(Map.of("limit", Math.max(1, limit)));
        parameters.addValue("filterProjectId", projectId);
        parameters.addValue("filterQueue", queue);
        return jdbc.query(WORKLIST, parameters, (rs, rowNum) -> new WorklistRow(
            rs.getObject("id", UUID.class),
            rs.getObject("projectId", UUID.class),
            rs.getString("aggregateType"),
            rs.getObject("aggregateId", UUID.class),
            rs.getString("eventType"),
            rs.getObject("attempts", Integer.class),
            rs.getString("state"),
            rs.getString("correlationId"),
            rs.getObject("deadLetteredAt", LocalDateTime.class),
            rs.getString("deadLetterReason"),
            rs.getString("lastError"),
            rs.getObject("nextAttemptAt", LocalDateTime.class),
            rs.getObject("createdAt", LocalDateTime.class),
            rs.getInt("open_dead_letters")));
    }

    /** Stranded leases for this tenant: a worker that died. */
    public List<StrandedLease> strandedLeases(TenantScope scope, UUID projectId, int limit) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope)
            .bindNamed(Map.of("limit", Math.max(1, limit)));
        parameters.addValue("filterProjectId", projectId);
        return jdbc.query(STRANDED_LEASES, parameters, (rs, rowNum) -> new StrandedLease(
            rs.getObject("id", UUID.class),
            rs.getObject("projectId", UUID.class),
            rs.getString("eventType"),
            rs.getString("leaseOwner"),
            rs.getObject("leaseUntil", LocalDateTime.class),
            rs.getObject("attempts", Integer.class),
            rs.getString("lastError"),
            rs.getObject("createdAt", LocalDateTime.class),
            rs.getObject("lease_overdue_seconds", Long.class)));
    }

    /** Attempts and outcomes per queue over a window. */
    public List<Map<String, Object>> queueRollup(TenantScope scope, LocalDateTime since, int limit) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope)
            .bindNamed(Map.of("since", since, "limit", Math.max(1, limit)));
        return jdbc.queryForList(QUEUE_ROLLUP, parameters);
    }

    /** The window default used when a caller does not choose one. */
    public static LocalDateTime defaultSince(int hours) {
        return AuditAttribution.now().minusHours(Math.max(1, Math.min(hours, 8760)));
    }
}