package com.firstelement.worker.runtime;

import com.firstelement.worker.outbox.OutboxRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * §7 of {@code ops/flyway/reference/outbox-claim.sql}: return a crashed worker's rows to
 * {@code PENDING}.
 *
 * <h2>Why this is a component and not a dispatcher method</h2>
 *
 * <p>Two callers need it — the scheduler and the {@code outbox-maintenance} job — and wiring both
 * to the dispatcher would make the dispatcher depend on its own handler registry, which is a cycle.
 * A dedicated component also makes the statement's blast radius obvious: it is the only statement
 * in the module without a tenant predicate, and it is the only one that may run without a claim.
 *
 * <h2>Why it is not optional</h2>
 *
 * <p>The claim predicate only sees {@code state = 'PENDING'}. A worker that dies mid-delivery leaves
 * {@code state = 'LEASED'} with an expired lease, and without this statement that row is stranded
 * forever — invisible to every claim and to every read model except this one. The reference file
 * says it outright: "The lease reaper is not optional: without it a crashed worker's row is
 * stranded."
 *
 * <h2>Safe to run concurrently and repeatable</h2>
 *
 * <p>{@code FOR UPDATE SKIP LOCKED} inside the statement, and re-running it finds no eligible rows.
 * Every worker may run it, which is why the loop schedules it independently of the sweep.
 */
@Component
public class LeaseReaper {

    private static final Logger log = LoggerFactory.getLogger(LeaseReaper.class);

    private final OutboxRepository outbox;
    private final WorkerMetrics metrics;

    public LeaseReaper(OutboxRepository outbox, WorkerMetrics metrics) {
        this.outbox = outbox;
        this.metrics = metrics;
    }

    /**
     * Reclaims up to {@code batchSize} expired leases.
     *
     * @return the reclaimed rows, each carrying its own tenant so the caller can report per tenant
     */
    public List<OutboxRepository.Reaped> reap(int batchSize) {
        List<OutboxRepository.Reaped> reaped = outbox.reapExpiredLeases(batchSize);
        if (reaped.isEmpty()) {
            return reaped;
        }
        metrics.reaped(reaped.size());
        // One line per reclaimed row, because each one is a worker that stopped answering and an
        // operator needs to know which. Ids only: never a payload.
        reaped.forEach(row -> log.warn("reclaimed outbox row id={} tenant={} after attempt {} whose "
            + "lease owner {} stopped answering", row.id(), row.organizationId(), row.attempts(),
            row.previousLeaseOwner()));
        return reaped;
    }
}