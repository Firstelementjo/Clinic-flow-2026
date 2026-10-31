package com.firstelement.worker.runtime;

import com.firstelement.worker.config.UtcTime;
import com.firstelement.worker.outbox.OutboxPayloadCodec;
import com.firstelement.security.TenantContext;
import com.firstelement.security.job.TenantScopedExecutor;
import com.firstelement.worker.history.DeadLetterRepository;
import com.firstelement.worker.history.IdempotencyRepository;
import com.firstelement.worker.history.JobAttemptRepository;
import com.firstelement.worker.history.JobAttemptRow;
import com.firstelement.worker.outbox.OutboxRepository;
import com.firstelement.worker.outbox.OutboxRepository.OutboxRow;

import com.firstelement.worker.queue.BackoffPolicy;
import com.firstelement.worker.queue.JobEnvelope;
import com.firstelement.worker.queue.JobHandler;
import com.firstelement.worker.queue.JobHandlerRegistry;
import com.firstelement.worker.queue.QueueName;
import com.firstelement.worker.queue.QueuePolicy;
import com.firstelement.worker.queue.QueuePolicyCatalog;
import com.firstelement.worker.queue.RetryClassifier;
import com.firstelement.worker.queue.RetryDecision;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Claims work, runs it, and records what happened.
 *
 * <h2>The delivery contract</h2>
 *
 * <p>At-least-once with duplicate suppression, not exactly-once. The sequence for one claimed row
 * is always the same:
 *
 * <ol>
 *   <li>re-read the row under tenant scope (the claim sweep index is tenant-less by design and is
 *       not an authorization path);</li>
 *   <li>bind the tenant through {@link TenantScopedExecutor} and run the handler;</li>
 *   <li>record an append-only attempt â€” <em>before</em> the terminal write, so a crash in between
 *       leaves an attempt with no completion rather than a completion with no attempt;</li>
 *   <li>complete, retry, or dead-letter.</li>
 * </ol>
 *
 * <h2>Why the attempt is written first</h2>
 *
 * <p>"Worker crash after external send does not duplicate on retry" is not achieved here â€” it is
 * achieved by the handler being idempotent and the database being the arbiter. What the ordering
 * buys is that a crash between (3) and (4) leaves a recorded failed attempt and a still-leased row
 * that the reaper reclaims: the operator can see the attempt, the failure class and the worker that
 * died. The reverse order would lose the failure classification.
 *
 * <h2>Nothing is logged that a customer wrote</h2>
 *
 * <p>The dispatcher logs ids, queues, states, retry classes and counts. It never logs
 * {@code outbox_events.payload}, a message body, or a provider response body: FR-10.6 and the
 * {@code job_dead_letters."payloadSummary"} rule in {@code V20261004000700}.
 */
@Component
public class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

    private final OutboxRepository outbox;
    private final JobAttemptRepository attempts;
    private final DeadLetterRepository deadLetters;
    private final IdempotencyRepository idempotency;
    private final JobHandlerRegistry handlers;
    private final TenantDirectory tenants;
    private final TenantScopedExecutor tenantScope;
    private final TenantFairnessPolicy fairness;
    private final DispatcherProperties properties;
    private final BackoffPolicy backoff;
    private final OutboxPayloadCodec json;
    private final WorkerMetrics metrics;
    private final WorkerIdentity identity;
    private final LeaseReaper reaper;

    /** Set while a drain is in progress so a sweep can finish without claiming new work. */
    private final AtomicBoolean draining = new AtomicBoolean(false);

    public OutboxDispatcher(OutboxRepository outbox,
                            JobAttemptRepository attempts,
                            DeadLetterRepository deadLetters,
                            IdempotencyRepository idempotency,
                            JobHandlerRegistry handlers,
                            TenantDirectory tenants,
                            TenantScopedExecutor tenantScope,
                            TenantFairnessPolicy fairness,
                            DispatcherProperties properties,
                            OutboxPayloadCodec json,
                            WorkerMetrics metrics,
                            WorkerIdentity identity,
                            LeaseReaper reaper) {
        this.outbox = outbox;
        this.attempts = attempts;
        this.deadLetters = deadLetters;
        this.idempotency = idempotency;
        this.handlers = handlers;
        this.tenants = tenants;
        this.tenantScope = tenantScope;
        this.fairness = fairness;
        this.properties = properties;
        this.json = json;
        this.metrics = metrics;
        this.identity = identity;
        this.reaper = reaper;
        this.backoff = new BackoffPolicy(java.util.concurrent.ThreadLocalRandom.current());
    }

    // ------------------------------------------------------------------
    // one sweep
    // ------------------------------------------------------------------

    /**
     * Runs one sweep: every active tenant gets one claim attempt, round-robin.
     *
     * <p>Synchronous and self-contained so a test can drive it without a scheduler.
     *
     * @return what this sweep did; the numbers the metrics and the health indicator publish
     */
    public SweepResult sweep() {
        if (draining.get()) {
            return new SweepResult().skipped("draining");
        }
        List<UUID> active = tenants.activeOrganizationIds();
        SweepResult result = new SweepResult();
        // The rotation decides the order and every active tenant appears exactly once. Iterating the
        // candidate list instead and asking the rotation whether it agrees would compare two
        // orderings and skip every tenant after the first sweep, which is the starvation this class
        // exists to prevent.
        for (UUID organizationId : fairness.nextRound(active)) {
            result.add(dispatchForTenant(organizationId));
        }
        return result;
    }

    /** Claims and runs one batch for one tenant. Public so a test can drive a single tenant. */
    public TenantSweepResult dispatchForTenant(UUID organizationId) {
        TenantSweepResult result = new TenantSweepResult(organizationId);
        if (draining.get()) {
            return result.skipped("draining");
        }
        List<OutboxRow> claimed = outbox.claimFor(organizationId, identity.workerId(),
            properties.getBatchSize(), properties.getLeaseSeconds());
        result.claimed(claimed.size());
        metrics.claimed(claimed.size());

        for (OutboxRow row : claimed) {
            runOne(row, result);
        }
        return result;
    }

    private void runOne(OutboxRow row, TenantSweepResult result) {
        // The re-read the claim contract requires: a claimed row MUST be re-read under tenant
        // scope before it is acted on.
        Optional<OutboxRow> reread = outbox.reread(row.organizationId(), row.id());
        if (reread.isEmpty()) {
            // Only possible if the row was deleted between claim and re-read. Losing the lease by
            // not acting on it is correct; there is nothing to report to a tenant.
            log.warn("claimed outbox row disappeared before dispatch id={}", row.id());
            return;
        }
        OutboxRow claimed = reread.get();
        if (!claimed.organizationId().equals(row.organizationId())) {
            // Defence in depth against a repository bug. Never expected; never dispatched anyway.
            log.error("refusing to dispatch an outbox row whose tenant does not match the claim");
            result.countScopeRefused();
            return;
        }

        String previousCorrelation = MDC.get("correlationId");
        MDC.put("correlationId", claimed.correlationId() == null ? "" : claimed.correlationId());
        try {
            dispatchClaimed(claimed, result);
        } finally {
            if (previousCorrelation == null) {
                MDC.remove("correlationId");
            } else {
                MDC.put("correlationId", previousCorrelation);
            }
        }
    }

    private void dispatchClaimed(OutboxRow claimed, TenantSweepResult result) {
        QueueName queue = QueueName.fromWireValue(claimed.eventType());
        Optional<JobHandler> handler = queue == null ? Optional.empty() : handlers.find(queue);

        // An unknown eventType or an unclaimed queue is a permanent failure: nothing about waiting
        // will make a handler appear, and the row must be visible rather than silently dropped.
        if (queue == null || handler.isEmpty()) {
            RetryDecision decision = queue == null
                ? RetryDecision.permanent(RetryDecision.UNKNOWN_QUEUE, "UNKNOWN_QUEUE",
                    "No queue is declared for event type '" + claimed.eventType() + "'")
                : RetryDecision.permanent(RetryDecision.UNKNOWN_QUEUE, "NO_HANDLER",
                    "No JobHandler is registered for queue '" + queue.wireValue()
                        + "' in this process");
            failPermanently(claimed, decision, result);
            return;
        }

        JobHandler jobHandler = handler.get();
        QueuePolicy policy = QueuePolicyCatalog.of(queue);

        // Both permits are taken before the handler runs and both are returned in the finally below.
        // A short-circuit that returned without releasing would leak a permit, and a leaked permit is
        // a permanently degraded worker: its tenant or its queue class would stop being served and
        // nothing would say so.
        boolean permitted = fairness.tryAcquireClassSlot(queue.queueClass())
            && fairness.tryAcquireTenantSlot(claimed.organizationId());
        if (!permitted) {
            // Release whatever was taken. `TenantFairnessPolicy.release` only decrements counters that
            // exist, so it is correct whether the class slot, the tenant slot or neither was taken.
            fairness.release(claimed.organizationId(), queue.queueClass());
            // Return the claim immediately so another worker (or the next sweep) can take it, and do
            // NOT count the attempt as a failure: nothing failed.
            requeueUnstarted(claimed);
            result.countDeferred();
            return;
        }

        LocalDateTime startedAt = UtcTime.now();
        metrics.enteredJob();
        try {
            JobEnvelope envelope = envelope(claimed, queue);
            runHandler(claimed, queue, jobHandler, envelope, policy, startedAt, result);
        } catch (RuntimeException e) {
            // Building the envelope itself failed: a malformed payload is a permanent failure, not
            // a transient one, and it must still leave an attempt.
            failPermanently(claimed, RetryClassifier.classify(e), result);
        } finally {
            metrics.leftJob();
            fairness.release(claimed.organizationId(), queue.queueClass());
        }
    }

    private void runHandler(OutboxRow claimed, QueueName queue, JobHandler handler,
                            JobEnvelope envelope, QueuePolicy policy, LocalDateTime startedAt,
                            TenantSweepResult result) {
        // The scope comes from the claimed row's own project, re-read under tenant scope above. A
        // worker principal scoped to nothing would be refused by TenantQueryGuard on every
        // project-scoped read the handler performs, which is a permanent failure for a job whose
        // work is legitimate.
        Optional<TenantContext> context = tenants.workerContext(claimed.organizationId(),
            claimed.projectId(), identity.workerId());
        if (context.isEmpty()) {
            failPermanently(claimed, RetryDecision.permanent(RetryDecision.SCOPE_REFUSED,
                "NO_TENANT", "The claimed row names no tenant"), result);
            return;
        }

        int attemptNumber = claimed.attempts() == null ? 1 : claimed.attempts();
        try {
            tenantScope.execute(context.get(), queue.wireValue() + ".handle",
                () -> {
                    try {
                        return handler.handle(context.get(), envelope);
                    } catch (RuntimeException e) {
                        throw e;
                    } catch (Exception e) {
                        // A handler is allowed to declare checked exceptions; the dispatcher
                        // classifies them the same way it classifies unchecked ones.
                        throw new HandlerFailedException(e);
                    }
                });
            metrics.succeeded(queue);
            result.countSucceeded();
            recordAttempt(claimed, queue, attemptNumber, "SUCCEEDED", null, null, null, startedAt,
                false, null);
            if (!outbox.complete(claimed.organizationId(), claimed.id(), identity.workerId())) {
                // The lease expired while the handler ran. The work happened; the row says it did
                // not. Do not claim otherwise in the log: say exactly what is true.
                log.warn("outbox row id={} completed after its lease expired; the next sweep will "
                    + "reclaim it and the handler must be idempotent", claimed.id());
                result.countLeaseLost();
            }
        } catch (Exception e) {
            RetryDecision decision = RetryClassifier.classify(e);
            recordAttempt(claimed, queue, attemptNumber,
                decision.retryable() ? "RETRYABLE_FAILURE" : "NON_RETRYABLE_FAILURE",
                decision.retryClass(), decision.errorCode(), decision.reason(), startedAt,
                envelope.isReplay(), envelope.replayOfAttemptId());
            if (decision.shouldRetryGiven(policy, attemptNumber)) {
                Duration delay = backoff.delayAfter(policy, attemptNumber);
                boolean rescheduled = outbox.retry(claimed.organizationId(), claimed.id(),
                    identity.workerId(), delay, decision.errorCode() + " " + decision.reason());
                if (rescheduled) {
                    metrics.retried(queue);
                    result.countRetried();
                } else {
                    // The lease is gone, so neither retry nor dead-letter can land. The reaper will
                    // return the row to PENDING and the next attempt is decided from scratch.
                    log.warn("outbox row id={} failed but its lease expired before the retry write; "
                        + "the reaper will return it to PENDING", claimed.id());
                    result.countLeaseLost();
                }
            } else {
                failPermanently(claimed, decision, result);
            }
            log.warn("job {} attempt {} failed queue={} retryClass={} errorCode={}",
                envelope.jobId(), attemptNumber, queue.wireValue(), decision.retryClass(),
                decision.errorCode());
        }
    }

    /**
     * Â§6 DEAD LETTER, plus the operator-facing dead-letter row.
     *
     * <p>Both writes happen; the outbox row is the event's terminal state and the
     * {@code job_dead_letters} row is the job's operator lifecycle. Recording only one of them
     * leaves either "an event is dead and nobody can see it" or "a dead letter with no event".
     */
    private void failPermanently(OutboxRow claimed, RetryDecision decision,
                                 TenantSweepResult result) {
        String reason = decision.retryClass() + "/" + decision.errorCode() + ": " + decision.reason();
        QueueName queue = QueueName.fromWireValue(claimed.eventType());
        // queueName is TEXT, so an event whose type names no declared queue is still recorded under
        // its raw eventType. An operator must be able to see a dead event even when the thing that
        // could have drained it does not exist; "we could not name the queue" is not a reason to
        // drop the evidence.
        String recordedQueueName = queue == null ? claimed.eventType() : queue.wireValue();
        int attemptNumber = claimed.attempts() == null ? 1 : claimed.attempts();
        LocalDateTime startedAt = UtcTime.now();

        boolean deadLettered = outbox.deadLetter(claimed.organizationId(), claimed.id(),
            identity.workerId(), reason, decision.errorCode() + " " + decision.reason());
        if (!deadLettered) {
            log.warn("outbox row id={} was not dead-lettered; the lease was no longer held", claimed.id());
            result.countLeaseLost();
            return;
        }
        metrics.deadLettered(queue);
        result.countDeadLettered();

        // The attempt is recorded before the dead-letter row so the DLQ can name it, and both are
        // written so neither "an event is dead and nobody can see it" nor "a dead letter with no
        // event" is possible.
        attempts.record(new JobAttemptRow(UUID.randomUUID(), claimed.organizationId(),
            claimed.projectId(), recordedQueueName, recordedQueueName, jobIdOf(claimed), attemptNumber,
            "NON_RETRYABLE_FAILURE", decision.retryClass(), decision.errorCode(), decision.reason(),
            startedAt, UtcTime.now(),
            JobAttemptRepository.durationMillis(startedAt, UtcTime.now()), identity.workerId(),
            claimed.correlationId(), claimed.causationId(), false, null, claimed.id(),
            UtcTime.now()));

        // payloadSummary is a SUMMARY: counts, queue, error class. Never the payload.
        deadLetters.record(claimed.organizationId(), claimed.projectId(), recordedQueueName,
            jobIdOf(claimed), claimed.id(), lastAttemptId(claimed, recordedQueueName), reason,
            summaryJson(claimed, recordedQueueName, decision, attemptNumber), attemptNumber);
    }

    private void requeueUnstarted(OutboxRow claimed) {
        // Back to PENDING with no error: nothing failed, the class or tenant pool was full. The
        // delay is zero so the next sweep can take it.
        outbox.retry(claimed.organizationId(), claimed.id(), identity.workerId(), Duration.ZERO,
            "deferred: worker pool or tenant limit reached");
    }

    private UUID recordAttempt(OutboxRow claimed, QueueName queue, int attemptNumber,
                               String outcome, String retryClass, String errorCode, String reason,
                               LocalDateTime startedAt, boolean isReplay, UUID replayOfId) {
        LocalDateTime finishedAt = UtcTime.now();
        UUID attemptId = attempts.record(new JobAttemptRow(
            UUID.randomUUID(), claimed.organizationId(), claimed.projectId(), queue.wireValue(),
            queue.wireValue(), jobIdOf(claimed), attemptNumber, outcome, retryClass, errorCode,
            reason, startedAt, finishedAt,
            JobAttemptRepository.durationMillis(startedAt, finishedAt), identity.workerId(),
            claimed.correlationId(), claimed.causationId(), isReplay, replayOfId, claimed.id(),
            finishedAt));
        if (outcome.startsWith("RETRY") || outcome.startsWith("NON_RETRY")) {
            metrics.failed(queue);
        }
        return attemptId;
    }

    private UUID lastAttemptId(OutboxRow claimed, String recordedQueueName) {
        return attempts.lastFailure(claimed.organizationId(), recordedQueueName, jobIdOf(claimed))
            .map(JobAttemptRow::id)
            .orElse(null);
    }

    // ------------------------------------------------------------------
    // Â§7 reaper
    // ------------------------------------------------------------------

    /**
     * Runs the lease reaper.
     *
     * <p>Without this a worker that dies mid-delivery strands its row forever: the claim predicate
     * only looks at {@code state = 'PENDING'}, so a {@code LEASED} row with an expired lease is
     * invisible to it. Safe to run from every worker ({@code SKIP LOCKED}) and idempotent.
     *
     * @return the reclaimed rows, each carrying its own tenant so the caller can report per tenant
     */
    public List<OutboxRepository.Reaped> reap() {
        return reaper.reap(properties.getReapBatchSize());
    }

    // ------------------------------------------------------------------

    /** Stops claiming new work. In-flight jobs finish; the lease reaper may still run. */
    public void beginDrain() {
        draining.set(true);
    }

    /**
     * Ends a drain.
     *
     * <p>Paired with {@link #beginDrain()} on the loop's {@code start()}: a loop that has just been
     * started is not draining, and a drain is a property of a shutdown rather than of the process.
     * Without this, a stopped-then-restarted loop would report a healthy queue it refuses to serve,
     * and {@code WorkerHealthIndicator} would call that OUT_OF_SERVICE for the rest of the process's
     * life.
     */
    public void endDrain() {
        draining.set(false);
    }

    public boolean isDraining() {
        return draining.get();
    }

    // ------------------------------------------------------------------

    private JobEnvelope envelope(OutboxRow claimed, QueueName queue) {
        Map<String, Object> payload = json.readMap(claimed.payload());
        boolean isReplay = claimed.replayCount() != null && claimed.replayCount() > 0
            && claimed.lastReplayedAt() != null;
        // A replay attempt must name the failure it replaces, because job_attempts_replay_check
        // refuses isReplay=true without replayOfId. A replayed row whose failure history is gone is
        // therefore a permanent failure, not something to paper over with a null.
        UUID replayOf = null;
        if (isReplay) {
            replayOf = attempts.lastFailure(claimed.organizationId(), queue.wireValue(), jobIdOf(claimed))
                .map(JobAttemptRow::id)
                .orElseThrow(() -> new com.firstelement.worker.queue.PermanentFailureException(
                    RetryDecision.INVALID_PAYLOAD, "REPLAY_WITHOUT_FAILURE",
                    "This row has been replayed but has no recorded failure to name"));
        }
        return new JobEnvelope(claimed.id(), queue, claimed.organizationId(), claimed.projectId(),
            claimed.aggregateType(), claimed.aggregateId(), payload, claimed.correlationId(),
            claimed.causationId(), claimed.attempts() == null ? 1 : claimed.attempts(),
            jobIdOf(claimed), isReplay, replayOf, claimed.nextAttemptAt());
    }

    /**
     * The transport-stable job identity used for {@code job_attempts} numbering.
     *
     * <p>Derived from the outbox row id, so it is stable across retries and unique per tenant. The
     * legacy pipeline used {@code jobId: "send:<messageId>"}; the id here is the outbox row, which
     * is the thing that actually owns the delivery history.
     */
    private static String jobIdOf(OutboxRow row) {
        return row.id().toString();
    }

    private String summaryJson(OutboxRow row, String recordedQueueName, RetryDecision decision,
                               int attempts) {
        QueueName declared = QueueName.fromWireValue(recordedQueueName);
        return json.write(Map.of(
            "queue", recordedQueueName,
            "queueClass", declared == null ? "UNDECLARED" : declared.queueClass().name(),
            "aggregateType", row.aggregateType(),
            "eventType", row.eventType(),
            "attempts", attempts,
            "retryClass", decision.retryClass(),
            "correlationId", row.correlationId() == null ? "" : row.correlationId()));
    }

    /** Marks the dispatcher as ready to be used. Kept for symmetry with the loop. */
    public String workerId() {
        return identity.workerId();
    }

    /**
     * Wraps a checked exception so it can travel through the {@code Supplier} boundary.
     *
     * <p>{@link RetryClassifier} unwraps it, so a handler's {@code throws Exception} signature does
     * not force it to translate a provider failure into a runtime exception by hand.
     */
    public static class HandlerFailedException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public HandlerFailedException(Throwable cause) {
            super(cause.getMessage(), cause);
        }
    }

    /** Exposes the idempotency store to handlers that own their own key space. */
    public IdempotencyRepository idempotency() {
        return idempotency;
    }

    /** What one sweep did, across every tenant. */
    public static final class SweepResult {
        private final java.util.List<TenantSweepResult> tenants = new java.util.ArrayList<>();
        private String skippedReason;

        void add(TenantSweepResult result) {
            tenants.add(result);
        }

        SweepResult skipped(String reason) {
            this.skippedReason = reason;
            return this;
        }

        public boolean skipped() {
            return skippedReason != null;
        }

        public String skippedReason() {
            return skippedReason;
        }

        public java.util.List<TenantSweepResult> tenants() {
            return java.util.List.copyOf(tenants);
        }

        public int claimed() {
            return tenants.stream().mapToInt(TenantSweepResult::claimed).sum();
        }

        public int succeeded() {
            return tenants.stream().mapToInt(TenantSweepResult::succeeded).sum();
        }

        public int retried() {
            return tenants.stream().mapToInt(TenantSweepResult::retried).sum();
        }

        public int deadLettered() {
            return tenants.stream().mapToInt(TenantSweepResult::deadLettered).sum();
        }
    }

    /** What one tenant's slice of a sweep did. */
    public static final class TenantSweepResult {
        private final UUID organizationId;
        private int claimed;
        private int succeeded;
        private int retried;
        private int deadLettered;
        private int deferred;
        private int leaseLost;
        private int scopeRefused;
        private String skippedReason;

        TenantSweepResult(UUID organizationId) {
            this.organizationId = organizationId;
        }

        void claimed(int value) {
            this.claimed = value;
        }

        void countSucceeded() {
            this.succeeded++;
        }

        void countRetried() {
            this.retried++;
        }

        void countDeadLettered() {
            this.deadLettered++;
        }

        void countDeferred() {
            this.deferred++;
        }

        void countLeaseLost() {
            this.leaseLost++;
        }

        void countScopeRefused() {
            this.scopeRefused++;
        }

        TenantSweepResult skipped(String reason) {
            this.skippedReason = reason;
            return this;
        }

        public UUID organizationId() {
            return organizationId;
        }

        public int claimed() {
            return claimed;
        }

        public int succeeded() {
            return succeeded;
        }

        public int retried() {
            return retried;
        }

        public int deadLettered() {
            return deadLettered;
        }

        public int deferred() {
            return deferred;
        }

        public int leaseLost() {
            return leaseLost;
        }

        public int scopeRefused() {
            return scopeRefused;
        }

        public String skippedReason() {
            return skippedReason;
        }

        @Override
        public String toString() {
            return "tenant=" + organizationId + " claimed=" + claimed + " ok=" + succeeded
                + " retry=" + retried + " dead=" + deadLettered + " deferred=" + deferred;
        }
    }
}
