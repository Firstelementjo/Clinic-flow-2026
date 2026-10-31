package com.firstelement.worker.queue;

import java.time.Duration;
import java.util.random.RandomGenerator;
import java.util.function.DoubleSupplier;

/**
 * Exponential backoff with jitter.
 *
 * <h2>Why jitter is not decoration</h2>
 *
 * <p>Without it, a provider outage produces a synchronised stampede: every message that failed at
 * 10:00:00 failed together, so every retry comes back at 10:00:05, 10:00:15, 10:00:35 — and the
 * provider, which is already degraded, receives exactly the peak it cannot serve. Jitter spreads
 * the retries over the whole window.
 *
 * <p>The variant used here is <em>symmetric jitter</em> — {@code delay = computed ±
 * computed*jitterRatio} — because a full-jitter floor of zero would let a message whose provider
 * asked for a 30-second wait be retried after 200 ms. {@link #bounds(QueuePolicy, int)} publishes
 * the interval so a test asserts the property rather than a coin flip.
 *
 * <h2>Deterministic tests and operator replay</h2>
 *
 * <p>The randomness is injected, never a {@code static Random}. A test that asserts "attempt 3 waits
 * between 4s and 6s" needs the bounds, not a coin flip, and a caller that wants an exactly
 * predictable delay uses {@link #deterministic()}.
 */
public final class BackoffPolicy {

    /** No jitter. The shape used by operator replay and by the DB fixture. */
    private final DoubleSupplier random;

    public BackoffPolicy(RandomGenerator random) {
        if (random == null) {
            throw new IllegalArgumentException("a randomness source is required");
        }
        this.random = random::nextDouble;
    }

    private BackoffPolicy(DoubleSupplier random) {
        if (random == null) {
            throw new IllegalArgumentException("a randomness source is required");
        }
        this.random = random;
    }

    /** Deterministic: {@code jitterRatio = 0} and a neutral source. */
    public static BackoffPolicy deterministic() {
        return new BackoffPolicy(() -> 0.5d);
    }

    /** Deterministic jitter: a fixed source, for a fixture that wants a stable sample. */
    public static BackoffPolicy fixed(double source) {
        if (source < 0d || source > 1d) {
            throw new IllegalArgumentException("source must be within [0,1]");
        }
        return new BackoffPolicy(() -> source);
    }

    /**
     * The delay before attempt {@code nextAttemptNumber} (1-based; the first attempt waits 0).
     *
     * <p>The growth is {@code base * 2^(n-2)}, clamped to {@code max}. The clamp matters: without
     * it, attempt 12 of a customer-facing queue waits eleven days, which is not a retry, it is a
     * cancellation.
     *
     * @param attemptsSoFar attempts already made; recorded so a future policy can use it, and so
     *                      the call reads the same way as the attempt it follows
     */
    public Duration delay(QueuePolicy policy, int attemptsSoFar, int nextAttemptNumber) {
        return delay(policy.baseBackoff(), policy.maxBackoff(), policy.jitterRatio(),
            attemptsSoFar, nextAttemptNumber);
    }

    /** The delay after {@code attemptsSoFar} failures, i.e. before the next attempt. */
    public Duration delayAfter(QueuePolicy policy, int attemptsSoFar) {
        return delay(policy, attemptsSoFar, attemptsSoFar + 1);
    }

    private Duration delay(Duration base, Duration max, double jitterRatio,
                           int attemptsSoFar, int nextAttemptNumber) {
        if (nextAttemptNumber <= 1) {
            return Duration.ZERO;
        }
        long capped = exponentialCap(base, max, nextAttemptNumber);
        if (capped <= 0L) {
            return Duration.ZERO;
        }
        return applyJitter(capped, jitterRatio);
    }

    /**
     * The bounds a test can assert, without touching the randomness.
     *
     * @return {@code [lowest, highest]} for the wait before {@code nextAttemptNumber}
     */
    public Duration[] bounds(QueuePolicy policy, int nextAttemptNumber) {
        if (nextAttemptNumber <= 1) {
            return new Duration[] { Duration.ZERO, Duration.ZERO };
        }
        long capped = exponentialCap(policy.baseBackoff(), policy.maxBackoff(), nextAttemptNumber);
        double jitter = capped * policy.jitterRatio();
        return new Duration[] {
            Duration.ofMillis(Math.max(1L, Math.round(capped - jitter))),
            Duration.ofMillis(Math.round(capped + jitter)),
        };
    }

    private static long exponentialCap(Duration base, Duration max, int nextAttemptNumber) {
        long exponent = Math.max(0L, nextAttemptNumber - 2L);
        // The exponent is clamped before pow(): a silly attempt number must not turn into an
        // infinity that escapes the comparison below.
        double millis = base.toMillis() * Math.pow(2d, Math.min(exponent, 40d));
        return Math.min((long) millis, max.toMillis());
    }

    private Duration applyJitter(long cappedMillis, double jitterRatio) {
        if (jitterRatio <= 0d) {
            return Duration.ofMillis(cappedMillis);
        }
        double jitter = cappedMillis * jitterRatio;
        double offset = (random.getAsDouble() * 2d) - 1d;      // [-1, 1]
        return Duration.ofMillis(Math.max(1L, Math.round(cappedMillis + (offset * jitter))));
    }
}