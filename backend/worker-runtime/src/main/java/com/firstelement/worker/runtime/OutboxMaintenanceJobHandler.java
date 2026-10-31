package com.firstelement.worker.runtime;

import com.firstelement.security.TenantContext;
import com.firstelement.worker.queue.JobEnvelope;
import com.firstelement.worker.queue.JobHandler;
import com.firstelement.worker.queue.PermanentFailureException;
import com.firstelement.worker.queue.QueueName;
import com.firstelement.worker.queue.RetryDecision;
import org.springframework.stereotype.Component;

/**
 * The {@code outbox-maintenance} handler: the queue repairs itself on request.
 *
 * <h2>Why a job when the reaper is already a schedule</h2>
 *
 * <p>{@link LeaseReaper} on a timer is the always-on path. This handler is the <em>explicit</em>
 * one: an operator can enqueue a maintenance sweep for one tenant and see it in
 * {@code job_attempts}, which turns "the queue looks stuck" into an auditable, tenant-scoped
 * action. It also gives this module a job it can drain without depending on
 * {@code :backend:channels}, which is what lets the standalone worker process prove the whole
 * claim → lease → handler → attempt → complete path on its own.
 *
 * <h2>It never touches tenant content</h2>
 *
 * <p>It reports counts and lease state. The result string it returns is the only thing it writes,
 * and it is counts — never a payload, never a message body.
 */
@Component
public class OutboxMaintenanceJobHandler implements JobHandler {

    /** How many expired leases one maintenance pass reclaims. */
    private static final int REAP_BATCH = 100;


    private final LeaseReaper reaper;
    private final QueueHealthService queueHealth;

    public OutboxMaintenanceJobHandler(LeaseReaper reaper, QueueHealthService queueHealth) {
        this.reaper = reaper;
        this.queueHealth = queueHealth;
    }

    @Override
    public QueueName queue() {
        return QueueName.OUTBOX_MAINTENANCE;
    }

    @Override
    public String handle(TenantContext context, JobEnvelope job) {
        if (!context.organizationId().equals(job.organizationId())) {
            // Defence in depth. The dispatcher builds the context from the claimed row, so this can
            // only fire if a repository bug let a row be dispatched under another tenant.
            throw new PermanentFailureException(RetryDecision.SCOPE_REFUSED, "TENANT_MISMATCH",
                "Refusing to run queue maintenance for a tenant other than the claimed one");
        }

        int reaped = reaper.reap(REAP_BATCH).size();

        QueueHealthService.QueueHealth health = queueHealth.healthFor(context);
        return "reaped=" + reaped
            + " due=" + health.dueNow()
            + " pending=" + health.outboxStateCounts().getOrDefault("PENDING", 0)
            + " leased=" + health.outboxStateCounts().getOrDefault("LEASED", 0)
            + " dead=" + health.outboxStateCounts().getOrDefault("DEAD", 0);
    }
}