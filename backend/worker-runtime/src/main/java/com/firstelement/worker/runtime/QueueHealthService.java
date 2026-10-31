package com.firstelement.worker.runtime;

import com.firstelement.worker.config.UtcTime;
import com.firstelement.security.TenantContext;
import com.firstelement.worker.history.DeadLetterRepository;
import com.firstelement.worker.history.JobAttemptRepository;
import com.firstelement.worker.outbox.OutboxRepository;
import com.firstelement.worker.queue.QueuePolicy;
import com.firstelement.worker.queue.QueuePolicyCatalog;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * FR-6.7's queue read model: outbox depth, queue lag, retry counts, dead letters, worker
 * saturation — with no database console.
 *
 * <h2>Every method is tenant-scoped and refuses an unscoped call</h2>
 *
 * <p>There is no "all tenants" overload. The dispatcher needs a process-wide view, and it gets one
 * by iterating tenants it was already given (see {@link TenantDirectory}); the read model for an
 * operator is always for one tenant, which is also the only thing a tenant-scoped operator may
 * see.
 *
 * <h2>The one cross-tenant number, and why it is safe</h2>
 *
 * <p>{@link #expiredLeaseCount()} counts leases waiting to be reaped across all tenants. It reads
 * {@code state} and timing only — the same exposure as the tenant-less partial index — and it is an
 * infrastructure-health signal ("a worker died somewhere"), not tenant data. It exposes no payload,
 * no tenant id and no aggregate.
 */
@Service
public class QueueHealthService {

    private final OutboxRepository outbox;
    private final JobAttemptRepository attempts;
    private final DeadLetterRepository deadLetters;
    private final DispatcherProperties properties;

    public QueueHealthService(OutboxRepository outbox,
                              JobAttemptRepository attempts,
                              DeadLetterRepository deadLetters,
                              DispatcherProperties properties) {
        this.outbox = outbox;
        this.attempts = attempts;
        this.deadLetters = deadLetters;
        this.properties = properties;
    }

    /** The full operator picture for one tenant. */
    public QueueHealth healthFor(TenantContext context) {
        if (context == null) {
            throw new IllegalStateException(
                "Queue health requires a TenantContext; refusing to answer for every tenant.");
        }
        UUID organizationId = context.organizationId();
        LocalDateTime since = UtcTime.now()
            .minusSeconds(properties.getFailureWindowSeconds());

        Map<String, Integer> stateCounts = outbox.stateCounts(organizationId);
        int due = outbox.dueCount(organizationId);
        Duration oldest = outbox.oldestPendingAge(organizationId);
        int attemptTotal = attempts.totalAttempts(organizationId, since);
        int failureTotal = attempts.totalFailures(organizationId, since);
        List<JobAttemptRepository.QueueHealthRow> queueHealth =
            attempts.queueHealth(organizationId, since);

        Map<String, QueuePolicy> policies = new LinkedHashMap<>();
        QueuePolicyCatalog.all().forEach((queue, policy) -> policies.put(queue.wireValue(), policy));

        return new QueueHealth(
            organizationId,
            stateCounts,
            due,
            oldest,
            attemptTotal,
            failureTotal,
            failureRate(attemptTotal, failureTotal),
            deadLetters.openCount(organizationId),
            queueHealth,
            policies);
    }

    /** Leases waiting to be reaped, across tenants. Timing/state only. */
    public int expiredLeaseCount() {
        return outbox.expiredLeaseCount();
    }

    /** Outbox depth for one tenant without the attempt history. Cheap enough for a gauge. */
    public int outboxDepth(TenantContext context) {
        return outbox.stateCounts(context.organizationId()).values().stream()
            .mapToInt(Integer::intValue).sum();
    }

    private static double failureRate(int attempts, int failures) {
        if (attempts <= 0) {
            // "Display no data distinctly from zero" (FR-7.6) is the same discipline here: an
            // empty window is not a 0% failure rate, it is no data.
            return -1d;
        }
        return (double) failures / (double) attempts;
    }

    /**
     * One tenant's queue health.
     *
     * @param failureRate {@code -1} when there is no data in the window, never {@code 0}
     */
    public record QueueHealth(UUID organizationId,
                              Map<String, Integer> outboxStateCounts,
                              int dueNow,
                              Duration oldestPendingAge,
                              int attemptsInWindow,
                              int failuresInWindow,
                              double failureRate,
                              int openDeadLetters,
                              List<JobAttemptRepository.QueueHealthRow> queueHealth,
                              Map<String, QueuePolicy> retryPolicy) {
    }
}