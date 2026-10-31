package com.firstelement.worker.runtime;

import com.firstelement.worker.queue.QueueName;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * Worker and queue metrics, published to the management port.
 *
 * <h2>FR-10.4/FR-10.5 ask for exactly these numbers</h2>
 *
 * <p>queue lag, outbox backlog, dead-letter growth, worker saturation, retry counts. They are
 * registered through Micrometer, so they appear under {@code /actuator/metrics} without a custom
 * endpoint and without exposing anything tenant-scoped: every counter here is a total or a queue
 * name. No organization id, no payload, no customer content is ever a metric tag.
 *
 * <h2>Gauges are sampled, counters are monotonic</h2>
 *
 * <p>Counters answer "how many" and must never go down, so reaping a lease and replaying a dead
 * letter are <em>not</em> decrements of anything: they are their own counters. The gauges answer
 * "how much is outstanding right now" and are read from the database lazily by
 * {@link QueueHealthService}.
 */
@Component
public class WorkerMetrics {

    private final MeterRegistry registry;
    private final Map<QueueName, Counter> succeeded = new EnumMap<>(QueueName.class);
    private final Map<QueueName, Counter> failed = new EnumMap<>(QueueName.class);
    private final Map<QueueName, Counter> retried = new EnumMap<>(QueueName.class);
    private final Map<QueueName, Counter> deadLettered = new EnumMap<>(QueueName.class);
    private final Counter claimed;
    private final Counter reaped;
    private final AtomicInteger jobsInFlight = new AtomicInteger();
    private final AtomicInteger lastSweepClaimed = new AtomicInteger();
    private final AtomicInteger lastSweepAtEpochSecond = new AtomicInteger();

    public WorkerMetrics(MeterRegistry registry) {
        this.registry = registry;
        for (QueueName queue : QueueName.values()) {
            succeeded.put(queue, Counter.builder("firstelement.worker.jobs")
                .description("Worker job attempts by outcome")
                .tag("queue", queue.wireValue())
                .tag("queueClass", queue.queueClass().name())
                .tag("outcome", "succeeded")
                .register(registry));
            failed.put(queue, Counter.builder("firstelement.worker.jobs")
                .description("Worker job attempts by outcome")
                .tag("queue", queue.wireValue())
                .tag("queueClass", queue.queueClass().name())
                .tag("outcome", "failed")
                .register(registry));
            retried.put(queue, Counter.builder("firstelement.worker.retries")
                .description("Retries scheduled with exponential backoff and jitter")
                .tag("queue", queue.wireValue())
                .register(registry));
            deadLettered.put(queue, Counter.builder("firstelement.worker.deadletters")
                .description("Jobs moved to the dead-letter queue")
                .tag("queue", queue.wireValue())
                .register(registry));
        }
        this.claimed = Counter.builder("firstelement.worker.claimed")
            .description("Outbox events claimed by this process")
            .register(registry);
        this.reaped = Counter.builder("firstelement.worker.leases.reaped")
            .description("Expired outbox leases reclaimed after a worker stopped answering")
            .register(registry);

        Gauge.builder("firstelement.worker.jobs.inflight", jobsInFlight,
                value -> value.get())
            .description("Jobs currently running in this process")
            .register(registry);
        Gauge.builder("firstelement.worker.sweep.claimed", lastSweepClaimed, AtomicInteger::get)
            .description("Outbox events claimed by the most recent sweep")
            .register(registry);
        Gauge.builder("firstelement.worker.sweep.epochSecond", lastSweepAtEpochSecond,
                AtomicInteger::get)
            .description("When the most recent sweep finished; a stale value means the loop stalled")
            .register(registry);
    }

    public void claimed(int count) {
        if (count > 0) {
            claimed.increment(count);
        }
    }

    public void succeeded(QueueName queue) {
        counter(succeeded, queue).increment();
    }

    public void failed(QueueName queue) {
        if (queue != null) {
            counter(failed, queue).increment();
        }
    }

    public void retried(QueueName queue) {
        if (queue != null) {
            counter(retried, queue).increment();
        }
    }

    public void deadLettered(QueueName queue) {
        if (queue != null) {
            counter(deadLettered, queue).increment();
        }
    }

    public void reaped(int count) {
        if (count > 0) {
            reaped.increment(count);
        }
    }

    public void sweepCompleted(int claimed) {
        lastSweepClaimed.set(claimed);
        lastSweepAtEpochSecond.set((int) (System.currentTimeMillis() / 1000L));
    }

    public void enteredJob() {
        jobsInFlight.incrementAndGet();
    }

    public void leftJob() {
        jobsInFlight.updateAndGet(value -> Math.max(0, value - 1));
    }

    public int jobsInFlight() {
        return jobsInFlight.get();
    }

    /** Whether the registry this class writes to is present; false only in a stripped test. */
    public boolean registryAvailable() {
        return registry != null;
    }

    private static <T> T counter(Map<QueueName, T> counters, QueueName queue) {
        T counter = counters.get(queue);
        if (counter == null) {
            throw new IllegalArgumentException("No counter is registered for queue " + queue);
        }
        return counter;
    }
}