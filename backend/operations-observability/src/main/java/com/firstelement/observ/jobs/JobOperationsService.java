package com.firstelement.observ.jobs;

import com.firstelement.admin.audit.AuditAttribution;
import com.firstelement.admin.maintenance.MaintenanceOutcome;
import com.firstelement.admin.maintenance.ReplaySafetyPolicy;
import com.firstelement.security.TenantContext;
import com.firstelement.security.query.TenantQueryGuard;
import com.firstelement.security.query.TenantScope;
import com.firstelement.worker.queue.QueueName;
import com.firstelement.worker.runtime.QueueHealthService;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The operator's job console: the queue health, the worklist, and one complete diagnosis of a single
 * event.
 *
 * <h2>What the acceptance gate asks and where it is answered</h2>
 *
 * <p>"An operator can diagnose a failed message, webhook, export, vector ingestion, or worker without
 * database access." {@link #diagnose} is the whole of that for a job: it returns the event's state
 * and history, every attempt with its retry class and error code, the dead-letter record, and the
 * integration-stream rows that share the correlation id — with the outbox payload and the attempt
 * error messages deliberately not selected, because those are the two fields most likely to carry
 * something a customer said.
 *
 * <h2>Writes go through the one allow-list</h2>
 *
 * <p>{@link #replay} and {@link #resolveDeadLetter} delegate to
 * {@code :backend:administration}'s {@code MaintenanceCommandService}. This module therefore has no
 * write of its own, no second permission decision and no second audit writer: the route here names
 * the permission at the HTTP edge, and the enforcement, the legal-hold awareness and the audit write
 * all happen in the one place that owns them.
 */
@Service
public class JobOperationsService {

    private final JobDiagnosisRepository jobs;
    private final QueueHealthService queueHealth;
    private final com.firstelement.admin.maintenance.MaintenanceCommandService maintenance;
    private final TenantQueryGuard guard;
    private final com.firstelement.security.permission.AuthorizationService authorization;

    public JobOperationsService(JobDiagnosisRepository jobs,
                                QueueHealthService queueHealth,
                                com.firstelement.admin.maintenance.MaintenanceCommandService
                                    maintenance,
                                TenantQueryGuard guard,
                                com.firstelement.security.permission.AuthorizationService
                                    authorization) {
        this.jobs = jobs;
        this.queueHealth = queueHealth;
        this.maintenance = maintenance;
        this.guard = guard;
        this.authorization = authorization;
    }

    /**
     * The read-side permission check, mirroring what the route declares.
     *
     * <p>{@code @RequirePermission} on {@code OperationsController} makes the declaration reviewable
     * in one place; this makes it unbypassable. The two writes ({@link #replay} and
     * {@link #resolveDeadLetter}) enforce through {@code MaintenanceCommandService} instead, which is
     * why there is exactly one authorization decision for an operator action on a dead letter.
     */
    private void authorizeRead(TenantContext caller) {
        if (caller == null) {
            throw new IllegalStateException(
                "An operations read requires a resolved TenantContext; refusing to run unscoped.");
        }
        authorization.check(caller, com.firstelement.security.permission.Permission.SYSTEM_HEALTH_READ,
            com.firstelement.security.permission.PolicyResource.NONE);
    }

    /** The queue health read model plus the tenant's stranded leases and worklist. */
    public QueueView queues(TenantContext caller, UUID projectId, String queue, Integer windowHours) {
        authorizeRead(caller);
        TenantScope scope = guard.scope(caller, projectId);
        int window = windowHours == null || windowHours < 1 ? 24 : Math.min(windowHours, 8760);
        LocalDateTime since = AuditAttribution.now().minusHours(window);
        QueueHealthService.QueueHealth health = queueHealth.healthFor(caller);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("scope", scope.isProjectScoped() ? "PROJECT" : "TENANT");
        payload.put("projectId", scope.projectId());
        payload.put("outboxStateCounts", health.outboxStateCounts());
        payload.put("dueNow", health.dueNow());
        payload.put("oldestPendingSeconds",
            health.oldestPendingAge() == null ? 0L : health.oldestPendingAge().toSeconds());
        payload.put("attemptsInWindow", health.attemptsInWindow());
        payload.put("failuresInWindow", health.failuresInWindow());
        // -1 is "no attempts in the window", which is not a 0% failure rate. Preserved rather than
        // coerced: FR-7.6's "no data is not zero" applies to an operator gauge too.
        payload.put("failureRate", health.failureRate());
        payload.put("openDeadLetters", health.openDeadLetters());
        payload.put("perQueue", jobs.queueRollup(scope, since,
            com.firstelement.observ.OperationsObservabilityModule.Limits.DEAD_LETTERS));
        payload.put("strandedLeases", jobs.strandedLeases(scope, scope.projectId(),
            com.firstelement.observ.OperationsObservabilityModule.Limits.DEAD_LETTERS));
        payload.put("worklist", jobs.worklist(scope, scope.projectId(), queue,
            com.firstelement.observ.OperationsObservabilityModule.Limits.DEAD_LETTERS));
        payload.put("replaySafety", ReplaySafetyPolicy.catalogue());
        payload.put("note", "strandedLeases are LEASED rows whose lease has expired: an operator "
            + "seeing one is seeing a worker that died. The reaper reclaims them on a schedule; this "
            + "is a read model, and there is deliberately no cross-tenant reap command.");
        return new QueueView(payload);
    }

    /** The worklist on its own, for a page that only wants the failures. */
    public List<JobDiagnosisRepository.WorklistRow> worklist(TenantContext caller, UUID projectId,
                                                             String queue, int limit) {
        authorizeRead(caller);
        TenantScope scope = guard.scope(caller, projectId);
        return jobs.worklist(scope, scope.projectId(), queue,
            Math.max(1, Math.min(limit,
                com.firstelement.observ.OperationsObservabilityModule.Limits.DEAD_LETTERS)));
    }

    /** Stranded leases on their own. */
    public List<JobDiagnosisRepository.StrandedLease> strandedLeases(TenantContext caller,
                                                                     UUID projectId, int limit) {
        authorizeRead(caller);
        TenantScope scope = guard.scope(caller, projectId);
        return jobs.strandedLeases(scope, scope.projectId(),
            Math.max(1, Math.min(limit,
                com.firstelement.observ.OperationsObservabilityModule.Limits.DEAD_LETTERS)));
    }

    /**
     * The complete diagnosis of one event.
     *
     * <p>Everything needed to answer "what happened and is replaying it safe?", and nothing that
     * carries customer content:
     *
     * <ul>
     *   <li>{@code payload} is not projected — for an inbound webhook it is the one place customer
     *       prose waits in this schema, and {@code InboundWebhookIntakeService} promises it never
     *       reaches an admin read model;</li>
     *   <li>{@code job_attempts."errorMessage"} is not projected — it is a free-text provider or
     *       handler string, and {@code errorCode} plus {@code retryClass} is the searchable part;</li>
     *   <li>{@code job_dead_letters."payloadSummary"} is projected because the dispatcher builds it
     *       from counts, queue and error class.</li>
     * </ul>
     *
     * <p>A cross-tenant id returns {@link Optional#empty()}, not a denial that would confirm the id
     * exists somewhere. The route turns the empty answer into {@code 404} exactly as a nonexistent id
     * would.
     */
    @Transactional(readOnly = true)
    public Optional<Diagnosis> diagnose(TenantContext caller, UUID eventId) {
        authorizeRead(caller);
        TenantScope scope = guard.tenantScope(caller);
        Optional<JobDiagnosisRepository.EventRow> event = jobs.event(scope, eventId);
        if (event.isEmpty()) {
            return Optional.empty();
        }
        JobDiagnosisRepository.EventRow row = event.get();
        QueueName queue = QueueName.fromWireValue(row.eventType());
        return Optional.of(new Diagnosis(
            row,
            jobs.attempts(scope, eventId, row.eventType(), eventId.toString(),
                com.firstelement.observ.OperationsObservabilityModule.Limits.ATTEMPTS_PER_JOB),
            jobs.deadLetters(scope, eventId,
                com.firstelement.observ.OperationsObservabilityModule.Limits.DEAD_LETTERS),
            jobs.integrationEvents(scope, eventId, row.correlationId(),
                com.firstelement.observ.OperationsObservabilityModule.Limits.JOB_EVENTS),
            new ReplayAssessment(
                ReplaySafetyPolicy.isReplaySafe(queue),
                ReplaySafetyPolicy.explain(queue),
                "DEAD".equals(row.state()),
                maintenance.purgeGate(caller, row.projectId()).refusalReason())));
    }

    /**
     * Replays one dead-lettered event.
     *
     * <p>A pure delegation. The allow-list, the replay-safety gate, the idempotency and the audit
     * write all happen in {@code :backend:administration}; this method exists so the operations route
     * reads as a route and the service graph shows the dependency rather than hiding it.
     */
    public MaintenanceOutcome replay(TenantContext caller, UUID outboxEventId) {
        return maintenance.replayDeadLetteredJob(caller, outboxEventId);
    }

    /** Resolves or discards a dead letter. A pure delegation, for the same reason. */
    public MaintenanceOutcome resolveDeadLetter(TenantContext caller, UUID deadLetterId,
                                                String status, String note) {
        return maintenance.resolveDeadLetter(caller, deadLetterId, status, note);
    }

    /** The queue health view. */
    public record QueueView(Map<String, Object> document) {
    }

    /**
     * One event, fully diagnosed.
     *
     * @param replayAssessment whether a replay is safe, whether it is needed, and why — the two
     *                        questions an operator has in that order
     */
    public record Diagnosis(JobDiagnosisRepository.EventRow event,
                            List<JobDiagnosisRepository.AttemptRow> attempts,
                            List<JobDiagnosisRepository.DeadLetterRow> deadLetters,
                            List<JobDiagnosisRepository.IntegrationEventRow> integrationEvents,
                            ReplayAssessment replayAssessment) {
    }

    /**
     * Whether to replay, and whether it would be safe.
     *
     * @param replaySafe       the handler for this queue has declared itself replay-safe
     * @param evidence         the idempotency that absorbs a duplicate, or the reason for the refusal
     * @param replayNeeded     the event is DEAD, so it is not already in flight
     * @param legalHoldGate    the ADR-0008 rule 5 answer, so an operator sees the whole picture
     */
    public record ReplayAssessment(boolean replaySafe,
                                   String evidence,
                                   boolean replayNeeded,
                                   String legalHoldGate) {

        /** One sentence combining the three, for a panel header. */
        public String summary() {
            if (!replayNeeded) {
                return "The event is not DEAD, so there is nothing to replay.";
            }
            return replaySafe
                ? "The event is DEAD and the handler for this queue is replay-safe: " + evidence
                : "The event is DEAD but replay is refused by default. " + evidence;
        }
    }
}
