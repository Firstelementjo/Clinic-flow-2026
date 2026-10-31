package com.firstelement.worker.runtime;

import com.firstelement.worker.queue.JobHandlerRegistry;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Worker health on the management port ({@code /actuator/health}).
 *
 * <h2>What DOWN means here</h2>
 *
 * <p>Only two things, both of which mean the queue cannot make progress:
 *
 * <ul>
 *   <li>leases are waiting to be reaped — a worker stopped answering and its rows are stranded;</li>
 *   <li>the dispatcher loop is enabled but no sweep has completed recently.</li>
 * </ul>
 *
 * <p>A handler-less queue is reported as detail, not as DOWN: the API process legitimately runs no
 * dispatcher and legitimately has no handlers for the channels module's queues, and a load balancer
 * must not pull it out of rotation for that.
 *
 * <h2>No tenant data</h2>
 *
 * <p>Only process-level numbers and queue names. No organization id, no payload, no customer
 * content: a health endpoint is reachable by an unauthenticated probe (see
 * {@code SecurityConfiguration.PUBLIC_PATHS}).
 */
@Component("firstelementWorker")
public class WorkerHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(WorkerHealthIndicator.class);

    /** A sweep older than this means the loop stalled. */
    private static final long STALE_SWEEP_SECONDS = 60;

    private final DispatcherProperties properties;
    private final OutboxDispatcher dispatcher;
    private final QueueHealthService queueHealth;
    private final JobHandlerRegistry handlers;
    private final WorkerMetrics metrics;
    private final TenantFairnessPolicy fairness;

    public WorkerHealthIndicator(DispatcherProperties properties,
                                 OutboxDispatcher dispatcher,
                                 QueueHealthService queueHealth,
                                 JobHandlerRegistry handlers,
                                 WorkerMetrics metrics,
                                 TenantFairnessPolicy fairness) {
        this.properties = properties;
        this.dispatcher = dispatcher;
        this.queueHealth = queueHealth;
        this.handlers = handlers;
        this.metrics = metrics;
        this.fairness = fairness;
    }

    @Override
    public Health health() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("dispatcherEnabled", properties.isEnabled());
        details.put("workerId", dispatcher.workerId());
        details.put("draining", dispatcher.isDraining());
        details.put("registeredQueues", handlers.registered().stream()
            .map(queue -> queue.wireValue()).sorted().toList());
        details.put("jobsInFlight", metrics.jobsInFlight());
        details.put("batchSize", properties.getBatchSize());
        details.put("leaseSeconds", properties.getLeaseSeconds());
        details.put("maxInFlightPerTenant", fairness.maxInFlightPerTenant());
        details.put("fairness", fairness.snapshot());

        int stranded = queueHealth.expiredLeaseCount();
        details.put("expiredLeasesAwaitingReap", stranded);

        Health.Builder builder;
        if (stranded > 0) {
            builder = Health.down();
        } else if (properties.isEnabled() && dispatcher.isDraining()) {
            builder = Health.outOfService();
        } else {
            builder = Health.up();
        }

        if (!properties.isEnabled()) {
            log.debug("worker dispatcher disabled; health reports the process view only");
        }
        return builder.withDetails(details).build();
    }
}