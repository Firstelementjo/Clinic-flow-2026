package com.firstelement.worker.runtime;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * The scheduler: sweep, reap, drain.
 *
 * <h2>Why the loop is not {@code @Scheduled}</h2>
 *
 * <p>A graceful drain has to stop claiming <em>before</em> the connections close, and it has to
 * wait for in-flight handlers. {@link SmartLifecycle} gives an explicit phase, so the dispatcher
 * stops before Spring closes the {@code DataSource} and Redis, and
 * {@code spring.lifecycle.timeout-per-shutdown-phase} bounds the wait. A {@code @Scheduled}
 * method keeps firing during shutdown, which is how a worker loses the last in-flight job.
 *
 * <h2>Drain semantics</h2>
 *
 * <ol>
 *   <li>{@link #stop()} sets the drain flag, so a sweep in progress finishes and no new claim is
 *       made.</li>
 *   <li>In-flight handlers are awaited up to {@code drainSeconds}. Whatever has not finished keeps
 *       its lease; the reaper returns those rows to {@code PENDING} after the lease expires, and
 *       the next worker picks them up. Nothing is lost, and nothing is double-sent as long as the
 *       handler is idempotent — which the contract requires.</li>
 *   <li>The loop shuts down. The connection pool closes afterwards.</li>
 * </ol>
 *
 * <h2>The reaper runs on its own schedule</h2>
 *
 * <p>Recovery time must not depend on there being work to claim. A pool of workers with an empty
 * queue still reaps, because the rows that need reaping are precisely the ones that are not
 * claimable.
 */
@Component
public class DispatcherLoop implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(DispatcherLoop.class);

    private final DispatcherProperties properties;
    private final OutboxDispatcher dispatcher;
    private final TenantDirectory tenants;
    private final WorkerMetrics metrics;

    private ScheduledExecutorService scheduler;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public DispatcherLoop(DispatcherProperties properties,
                          OutboxDispatcher dispatcher,
                          TenantDirectory tenants,
                          WorkerMetrics metrics) {
        this.properties = properties;
        this.dispatcher = dispatcher;
        this.tenants = tenants;
        this.metrics = metrics;
    }

    @Override
    public void start() {
        if (!properties.isEnabled()) {
            log.info("worker dispatcher is disabled (firstelement.worker.dispatcher.enabled=false); "
                + "this process serves management endpoints only");
            return;
        }
        scheduler = Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "fe-outbox-" + properties.getBatchSize());
            thread.setDaemon(true);
            return thread;
        });
        // A loop that has just started is not draining. `stop()` sets the flag and nothing else
        // clears it, so without this a restarted loop would claim nothing and health would report
        // OUT_OF_SERVICE for the rest of the process's life.
        dispatcher.endDrain();
        running.set(true);
        scheduler.scheduleWithFixedDelay(this::sweepSafely, 0, properties.getPollMillis(),
            TimeUnit.MILLISECONDS);
        scheduler.scheduleWithFixedDelay(this::reapSafely, properties.getReapMillis(),
            properties.getReapMillis(), TimeUnit.MILLISECONDS);
        tenants.logPopulation();
        log.info("worker dispatcher started worker={} batchSize={} leaseSeconds={} "
                + "pollMillis={} reapMillis={} maxInFlightPerTenant={}",
            dispatcher.workerId(), properties.getBatchSize(), properties.getLeaseSeconds(),
            properties.getPollMillis(), properties.getReapMillis(),
            properties.getMaxInFlightPerTenant());
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        log.info("worker dispatcher draining: no new claims; in-flight jobs keep their leases");
        dispatcher.beginDrain();
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(properties.getDrainSeconds(), TimeUnit.SECONDS)) {
                    log.warn("worker dispatcher drain timed out after {}s; unfinished jobs keep "
                        + "their leases and the reaper will return them to PENDING",
                        properties.getDrainSeconds());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            scheduler.shutdownNow();
        }
        log.info("worker dispatcher stopped");
    }

    @Override
    public void stop(Runnable callback) {
        try {
            stop();
        } finally {
            callback.run();
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    /**
     * {@code Integer.MAX_VALUE} so the dispatcher stops before the web server and the datasource:
     * {@code SmartLifecycle} stops in descending phase order, so the highest phase stops first.
     */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }

    /** One sweep. Synchronous, so a test can call it directly. */
    public OutboxDispatcher.SweepResult sweep() {
        OutboxDispatcher.SweepResult result = dispatcher.sweep();
        metrics.sweepCompleted(result.claimed());
        return result;
    }

    private void sweepSafely() {
        try {
            OutboxDispatcher.SweepResult result = sweep();
            if (result.claimed() > 0 || result.succeeded() > 0 || result.retried() > 0
                || result.deadLettered() > 0) {
                log.info("outbox sweep worker={} claimed={} ok={} retry={} dead={} tenants={}",
                    dispatcher.workerId(), result.claimed(), result.succeeded(), result.retried(),
                    result.deadLettered(), result.tenants().size());
            }
        } catch (RuntimeException e) {
            // A sweep that throws must not kill the schedule: a dead loop is a silent queue outage,
            // which is the one failure the admin dashboard would not show.
            log.error("outbox sweep failed; the schedule continues", e);
        }
    }

    private void reapSafely() {
        try {
            dispatcher.reap();
        } catch (RuntimeException e) {
            log.error("outbox lease reap failed; the schedule continues", e);
        }
    }
}