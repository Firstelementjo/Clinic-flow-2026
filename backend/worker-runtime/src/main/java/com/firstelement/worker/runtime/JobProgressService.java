package com.firstelement.worker.runtime;

import com.firstelement.worker.config.UtcTime;
import com.firstelement.worker.outbox.OutboxRepository;
import com.firstelement.worker.outbox.OutboxRepository.OutboxRow;

import com.firstelement.worker.outbox.OutboxState;
import com.firstelement.worker.queue.QueueName;
import com.firstelement.worker.queue.QueuePolicy;
import com.firstelement.worker.queue.QueuePolicyCatalog;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * How far along one job is, for an operator watching a customer wait.
 *
 * <h2>Why this is derived and not stored</h2>
 *
 * <p>The Phase-5a schema has no progress column, and no migration was permitted in this phase.
 * Everything a progress view needs is already recorded — {@code outbox_events.attempts},
 * {@code state}, {@code nextAttemptAt}, {@code deadLetteredAt}, {@code replayCount}, plus the
 * append-only {@code job_attempts} history — so a stored percentage would be a second copy that can
 * disagree with the durable record. The state machine in
 * {@code V20261004000100__outbox.sql} is the source of truth and this class only presents it.
 *
 * <p>What this deliberately does <em>not</em> invent: a fraction-of-work percentage. A handler that
 * knows it is 40% through a document is the only thing that could report that truthfully, and no
 * handler has a place to report it yet (the attempt history is the extension point). Until then the
 * honest answer is "attempt 3 of 12, retrying in 90s", not a fabricated bar.
 */
@Service
public class JobProgressService {

    private final OutboxRepository outbox;
    private final com.firstelement.worker.history.JobAttemptRepository attempts;

    public JobProgressService(OutboxRepository outbox,
                              com.firstelement.worker.history.JobAttemptRepository attempts) {
        this.outbox = outbox;
        this.attempts = attempts;
    }

    /**
     * The progress of one outbox event, tenant-scoped.
     *
     * <p>An event belonging to another tenant is reported as absent, never as a denial: an
     * operator probing ids must not learn which ones exist elsewhere.
     */
    public Optional<JobProgress> progressFor(UUID organizationId, UUID outboxEventId) {
        Optional<OutboxRow> row = outbox.reread(organizationId, outboxEventId);
        if (row.isEmpty()) {
            return Optional.empty();
        }
        OutboxRow claimed = row.get();
        QueueName queue = QueueName.fromWireValue(claimed.eventType());
        QueuePolicy policy = queue == null ? null : QueuePolicyCatalog.of(queue);
        int attemptsMade = claimed.attempts() == null ? 0 : claimed.attempts();
        int maxAttempts = policy == null ? 0 : policy.maxAttempts();

        LocalDateTime now = UtcTime.now();
        Duration waitRemaining = claimed.nextAttemptAt() == null ? Duration.ZERO
            : nonNegative(Duration.between(now, claimed.nextAttemptAt()));

        return Optional.of(new JobProgress(
            claimed.id(),
            organizationId,
            claimed.projectId(),
            claimed.eventType(),
            claimed.aggregateType(),
            claimed.aggregateId(),
            claimed.state(),
            attemptsMade,
            maxAttempts,
            queue == null ? null : queue.queueClass(),
            claimed.leaseOwner(),
            claimed.leaseUntil(),
            waitRemaining,
            claimed.lastError(),
            claimed.deadLetteredAt(),
            claimed.deadLetterReason(),
            claimed.replayCount() == null ? 0 : claimed.replayCount(),
            claimed.lastReplayedAt(),
            claimed.deliveredAt(),
            remainingAttempts(claimed.state(), attemptsMade, maxAttempts),
            summary(claimed, policy, attemptsMade, maxAttempts, waitRemaining)));
    }

    /** The attempt history for one event; the drill-down behind the progress bar. */
    public List<com.firstelement.worker.history.JobAttemptRow> historyFor(UUID organizationId,
                                                                          UUID outboxEventId) {
        Optional<OutboxRow> row = outbox.reread(organizationId, outboxEventId);
        if (row.isEmpty()) {
            return List.of();
        }
        QueueName queue = QueueName.fromWireValue(row.get().eventType());
        if (queue == null) {
            return List.of();
        }
        return attempts.attemptsFor(organizationId, queue.wireValue(), outboxEventId.toString());
    }

    private static Duration nonNegative(Duration duration) {
        return duration.isNegative() ? Duration.ZERO : duration;
    }

    private static Integer remainingAttempts(OutboxState state, int attemptsMade, int maxAttempts) {
        if (state == OutboxState.SENT) {
            return 0;
        }
        if (state == OutboxState.DEAD) {
            return 0;
        }
        return Math.max(0, maxAttempts - attemptsMade);
    }

    private static String summary(OutboxRow row, QueuePolicy policy, int attemptsMade,
                                  int maxAttempts, Duration waitRemaining) {
        String budget = maxAttempts > 0 ? attemptsMade + "/" + maxAttempts : String.valueOf(attemptsMade);
        return switch (row.state()) {
            case SENT -> "delivered";
            case DEAD -> "dead-lettered after " + budget + " attempt(s): "
                + (row.deadLetterReason() == null ? "no reason recorded" : row.deadLetterReason());
            case LEASED -> "in flight with lease owner " + (row.leaseOwner() == null ? "unknown" : row.leaseOwner());
            case PENDING -> attemptsMade == 0
                ? "queued"
                : "attempt " + budget + ", retrying in " + Math.max(0L, waitRemaining.toSeconds()) + "s";
        };
    }

    /**
     * One job's observable progress.
     *
     * <p>There is no {@code percentComplete}: see the class note.
     *
     * @param remainingAttempts null when the event names no declared queue
     */
    public record JobProgress(UUID outboxEventId,
                              UUID organizationId,
                              UUID projectId,
                              String queue,
                              String aggregateType,
                              UUID aggregateId,
                              OutboxState state,
                              int attemptsMade,
                              int maxAttempts,
                              com.firstelement.worker.queue.QueueClass queueClass,
                              String leaseOwner,
                              LocalDateTime leaseUntil,
                              Duration waitRemaining,
                              String lastError,
                              LocalDateTime deadLetteredAt,
                              String deadLetterReason,
                              int replayCount,
                              LocalDateTime lastReplayedAt,
                              LocalDateTime deliveredAt,
                              Integer remainingAttempts,
                              String summary) {
    }
}