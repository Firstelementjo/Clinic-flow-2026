package com.firstelement.airag.policy;

import com.firstelement.airag.config.AiRagProperties;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * The per-tenant run rate limit.
 *
 * <h2>What it is for, and what it is not</h2>
 *
 * <p>It exists to stop a <em>loop</em> — a webhook that re-triggers itself, a client that retries on every
 * 500, a runaway conversation that starts a run per incoming message. It is not a quota system: spend is
 * bounded by {@code ai_budgets}, tokens by {@code maxTokensPerRun}, and concurrency by the worker's lease.
 * A sliding-window counter is the right shape for "stop the loop" and the wrong shape for "charge the
 * customer", which is why the two are separate mechanisms with separate refusals.
 *
 * <h2>Why an in-process counter is honest here</h2>
 *
 * <p>Because the blast radius of a leak is one instance's share of a limit, and the limit it protects
 * ({@code runsPerMinutePerTenant = 60}) is far above any legitimate conversation rate. A distributed
 * counter would be a Redis round trip on the critical path of every run to enforce a bound that is already
 * enforced durably by the budget and the queue. The property "this limit is advisory across instances,
 * authoritative within one" is stated rather than pretended.
 *
 * <h2>Clock injection</h2>
 *
 * <p>The window uses {@link System#nanoTime()}, not wall-clock time, so a clock adjustment (NTP, a DST
 * change, a Riyadh host switching from UTC+3 to UTC+4) cannot open a window that was closed or close one
 * that was open. A rate limiter keyed on wall-clock time is a rate limiter that can be defeated by
 * changing the server's clock.
 */
@Component
public class AiRateLimiter {

    private final AiRagProperties properties;
    private final Map<UUID, Window> windows = new ConcurrentHashMap<>();

    public AiRateLimiter(AiRagProperties properties) {
        this.properties = properties;
    }

    /**
     * Counts one run attempt for a tenant, or refuses.
     *
     * <p>Counts <em>attempts</em>, not successes. A run that fails the budget gate has already been
     * refused, and counting only successes would let a client drive this loop by sending requests that
     * cannot succeed — which is exactly the shape of an abuse attempt.
     *
     * @throws AiRefusedException {@link AiRefusalReason#RATE_LIMITED}
     */
    public void acquire(UUID organizationId) {
        int limit = properties.getRunsPerMinutePerTenant();
        if (limit <= 0) {
            // A zero limit is "no AI at all", which is the same statement as the disabled switch and is
            // refused for the same reason family. Treated as disabled rather than as unlimited, because
            // reading 0 as unlimited is the error that turns a safety limit into an outage.
            throw AiRefusedException.of(AiRefusalReason.RATE_LIMITED,
                "the deployment run rate limit is zero");
        }
        if (organizationId == null) {
            throw AiRefusedException.of(AiRefusalReason.RATE_LIMITED, "no tenant to rate-limit against");
        }
        Window window = windows.computeIfAbsent(organizationId, ignored -> new Window());
        if (!window.tryAcquire(limit, 60_000_000_000L)) {
            throw AiRefusedException.of(AiRefusalReason.RATE_LIMITED,
                "more than " + limit + " AI runs in the last minute for this tenant");
        }
    }

    /** The count currently inside the window. Exposed for the operator read model. */
    public int observed(UUID organizationId) {
        Window window = windows.get(organizationId);
        return window == null ? 0 : window.observed();
    }

    /**
     * Forgets a tenant's window.
     *
     * <p>Called when a tenant is archived. Without it, {@code windows} grows one entry per tenant that
     * ever ran AI, forever, which is a slow leak in a long-lived process and the kind that only shows up
     * in a heap dump after an incident.
     */
    public void forget(UUID organizationId) {
        if (organizationId != null) {
            windows.remove(organizationId);
        }
    }

    /**
     * A 60-second sliding window, as an array of per-second buckets.
     *
     * <p>60 counters rather than a single "window start" timestamp: a fixed window lets a caller send its
     * whole allowance at 0.999s and again at 1.001s, which is 2x the intended rate and is trivially
     * reachable from a retry loop with jitter.
     */
    private static final class Window {

        private static final int BUCKETS = 60;

        private final long[] counts = new long[BUCKETS];
        private final long[] stamps = new long[BUCKETS];

        synchronized boolean tryAcquire(int limit, long windowNanos) {
            long now = System.nanoTime();
            long oldest = now - windowNanos;
            long total = 0L;
            for (int i = 0; i < BUCKETS; i++) {
                if (stamps[i] < oldest) {
                    counts[i] = 0L;
                    stamps[i] = 0L;
                }
                total += counts[i];
            }
            if (total >= limit) {
                return false;
            }
            int slot = (int) Math.floorMod(now / 1_000_000_000L, (long) BUCKETS);
            counts[slot]++;
            stamps[slot] = now;
            return true;
        }

        synchronized int observed() {
            long oldest = System.nanoTime() - 60_000_000_000L;
            long total = 0L;
            for (int i = 0; i < BUCKETS; i++) {
                if (stamps[i] >= oldest) {
                    total += counts[i];
                }
            }
            return (int) Math.min(Integer.MAX_VALUE, total);
        }
    }
}