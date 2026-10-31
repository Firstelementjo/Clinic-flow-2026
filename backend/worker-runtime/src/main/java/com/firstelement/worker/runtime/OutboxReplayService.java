package com.firstelement.worker.runtime;

import com.firstelement.worker.config.UtcTime;
import com.firstelement.security.TenantContext;
import com.firstelement.security.event.SecurityEvent;
import com.firstelement.security.event.SecurityEventPublisher;
import com.firstelement.security.event.SecurityEventType;
import com.firstelement.worker.history.DeadLetterRepository;
import com.firstelement.worker.history.IdempotencyRepository;
import com.firstelement.worker.history.JobAttemptRepository;
import com.firstelement.worker.history.JobAttemptRow;
import com.firstelement.worker.outbox.OutboxRepository;
import com.firstelement.worker.outbox.OutboxRepository.OutboxRow;
import com.firstelement.worker.queue.QueueName;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The operator replay, and nothing else.
 *
 * <h2>What a replay is</h2>
 *
 * <p>§8 of {@code ops/flyway/reference/outbox-claim.sql}: "Replaying a DEAD event makes it
 * claimable again and records WHO and WHEN. It does not delete anything, does not reset attempts
 * (the backoff stays monotonic) and does not create a second outbox row: the same id is reused, so
 * a message cannot acquire two independent delivery histories."
 *
 * <p>Three properties follow and are each tested:
 *
 * <ol>
 *   <li><b>Safe to repeat.</b> The update only matches {@code state = 'DEAD'}, so a second replay
 *       of an already-replayed row is a no-op rather than a second increment.</li>
 *   <li><b>Audited.</b> {@code replayedByUserId} + {@code lastReplayedAt} on the event, a
 *       {@code BUSINESS_ACTION} row on the security/admin stream, a {@code REPLAYED} status on the
 *       dead letter, and a fresh {@code job_attempts} row with {@code isReplay = true} that names
 *       the attempt it replays. "Replays are never silent."</li>
 *   <li><b>Tenant-scoped.</b> The event is re-read under the operator's tenant before anything
 *       is written, and the user's id comes from the resolved context, never from a request
 *       body.</li>
 * </ol>
 *
 * <h2>Attempts are not reset</h2>
 *
 * <p>An event that exhausted twelve attempts and is replayed runs as attempt thirteen. If the cause
 * is still there it dead-letters again after one more try, which is the honest outcome: the operator
 * fixed something or they did not.
 */
@Service
public class OutboxReplayService {

    private static final Logger log = LoggerFactory.getLogger(OutboxReplayService.class);

    private final OutboxRepository outbox;
    private final JobAttemptRepository attempts;
    private final DeadLetterRepository deadLetters;
    private final IdempotencyRepository idempotency;
    private final SecurityEventPublisher events;

    public OutboxReplayService(OutboxRepository outbox,
                               JobAttemptRepository attempts,
                               DeadLetterRepository deadLetters,
                               IdempotencyRepository idempotency,
                               SecurityEventPublisher events) {
        this.outbox = outbox;
        this.attempts = attempts;
        this.deadLetters = deadLetters;
        this.idempotency = idempotency;
        this.events = events;
    }

    /** What a replay did. */
    public enum ReplayOutcome {
        /** The event was DEAD and is claimable again. */
        REPLAYED,
        /** The event was not DEAD. Nothing was written; replaying it twice is safe. */
        NOT_DEAD,
        /** The event does not exist in this tenant, or in any the operator may reach. */
        NOT_FOUND
    }

    /**
     * Replays one dead-lettered event.
     *
     * @param context the authenticated operator; must be a USER principal because the audit row
     *                names a person
     */
    @Transactional
    public ReplayOutcome replay(TenantContext context, UUID outboxEventId) {
        // Tenant scope: a remembered id from a URL is a request, never authorization.
        Optional<OutboxRow> found = outbox.reread(context.organizationId(), outboxEventId);
        if (found.isEmpty()) {
            publish(context, "OutboxEvent", outboxEventId, "replay-noop", Map.of("outcome", ReplayOutcome.NOT_FOUND.name()));
            return ReplayOutcome.NOT_FOUND;
        }
        OutboxRow row = found.get();
        if (row.state() != com.firstelement.worker.outbox.OutboxState.DEAD) {
            log.info("replay requested for outbox row {} which is {}; no write performed",
                outboxEventId, row.state());
            publish(context, "OutboxEvent", outboxEventId, "replay-noop", Map.of("outcome",
                ReplayOutcome.NOT_DEAD.name(), "state", row.state().name()));
            return ReplayOutcome.NOT_DEAD;
        }

        QueueName queue = QueueName.fromWireValue(row.eventType());
        UUID userId = context.userId();
        if (userId == null) {
            // A machine principal cannot be the "who" of an audit row. Refuse rather than write a
            // dead letter that claims to have been resolved by nobody.
            throw new IllegalStateException(
                "An outbox replay must be performed by an authenticated user; a machine principal "
                    + "has no id to record in replayedByUserId.");
        }

        boolean replayed = outbox.replay(context.organizationId(), outboxEventId, userId);
        if (!replayed) {
            // Another operator replayed it between the read and the write. Idempotent by design.
            publish(context, "OutboxEvent", outboxEventId, "replay-noop",
                Map.of("outcome", ReplayOutcome.NOT_DEAD.name()));
            return ReplayOutcome.NOT_DEAD;
        }

        if (queue != null) {
            deadLetterId(context, queue, row).ifPresent(id ->
                deadLetters.markReplayed(context.organizationId(), id));
        }

        // The replay itself is an attempt, and it belongs in job_attempts with isReplay = true and
        // replayOfId naming the failure it replaces. The failed attempt is never modified.
        Optional<JobAttemptRow> lastFailure = queue == null
            ? Optional.empty()
            : attempts.lastFailure(context.organizationId(), queue.wireValue(), row.id().toString());
        UUID replayAttemptId = null;
        if (queue != null && lastFailure.isPresent()) {
            JobAttemptRow failure = lastFailure.get();
            replayAttemptId = attempts.record(new JobAttemptRow(
                UUID.randomUUID(), context.organizationId(), row.projectId(), queue.wireValue(),
                queue.wireValue(), row.id().toString(), failure.attemptNumber() + 1,
                "SKIPPED", null, "REPLAY_REQUESTED",
                "Operator requested a replay of attempt " + failure.attemptNumber(),
                UtcTime.now(), UtcTime.now(), 0, "operator", row.correlationId(),
                row.causationId(), true, failure.id(), row.id(), UtcTime.now()));
        }

        publish(context, "OutboxEvent", outboxEventId, "replay", Map.of(
            "outcome", ReplayOutcome.REPLAYED.name(),
            "queue", queue == null ? row.eventType() : queue.wireValue(),
            "aggregateType", row.aggregateType(),
            "attempts", String.valueOf(row.attempts()),
            "replayCount", String.valueOf((row.replayCount() == null ? 0 : row.replayCount()) + 1),
            "replayAttemptId", replayAttemptId == null ? "" : replayAttemptId.toString()));

        log.info("outbox replay id={} tenant={} queue={} attempts={} by operator user={}",
            outboxEventId, context.organizationId(), row.eventType(), row.attempts(), userId);
        return ReplayOutcome.REPLAYED;
    }

    /**
     * §3a RESOLVE / DISCARD a dead letter without replaying it.
     *
     * <p>The status change requires who and when — the database enforces it — so an operator can
     * never close a dead letter anonymously.
     */
    @Transactional
    public boolean resolve(TenantContext context, UUID deadLetterId, String status, String note) {
        if (!"RESOLVED".equals(status) && !"DISCARDED".equals(status)) {
            throw new IllegalArgumentException(
                "status must be RESOLVED or DISCARDED; DeadLetterStatus has no other terminal value");
        }
        UUID userId = context.userId();
        if (userId == null) {
            throw new IllegalStateException(
                "Resolving a dead letter requires an authenticated user; the CHECK constraint "
                    + "refuses an unattributable resolution and so must this service.");
        }
        boolean resolved = deadLetters.resolve(context.organizationId(), deadLetterId, status, userId,
            note);
        publish(context, "JobDeadLetter", deadLetterId,
            "deadletter-" + status.toLowerCase(java.util.Locale.ROOT), Map.of("status", status));
        return resolved;
    }

    /** Looks up the dead-letter row for an event, tenant-scoped. May be absent. */
    public Optional<UUID> deadLetterId(TenantContext context, QueueName queue, OutboxRow row) {
        return deadLetters.find(context.organizationId(), queue.wireValue(), row.id().toString())
            .map(DeadLetterRepository.DeadLetterRow::id);
    }

    private void publish(TenantContext context, String resourceType, UUID resourceId, String action,
                         Map<String, String> metadata) {
        try {
            // `reason` is not a persisted column: the audit writer stores the event TYPE in
            // `audit_logs."action"`, which for every one of these calls is "Admin.BusinessAction".
            // So the action label is copied into the metadata as well, otherwise a replay, a refused
            // replay and a dead-letter resolution would produce three indistinguishable rows.
            java.util.Map<String, Object> detail = new java.util.LinkedHashMap<>(metadata);
            detail.put("action", "outbox." + action);
            events.publish(SecurityEvent.builder(SecurityEventType.BUSINESS_ACTION)
                .tenantContext(context)
                .resourceType(resourceType)
                .resourceId(resourceId == null ? null : resourceId.toString())
                .reason("outbox." + action)
                .metadata(detail)
                .correlationId(org.slf4j.MDC.get("correlationId"))
                .build());
        } catch (RuntimeException e) {
            // The audit stream must never turn a successful replay into a failure, and must never
            // be the reason a customer message stays undelivered.
            log.error("Failed to publish the outbox replay audit event id={}", resourceId, e);
        }
    }

    /** The idempotency store, for the admin read models. */
    public IdempotencyRepository idempotency() {
        return idempotency;
    }
}