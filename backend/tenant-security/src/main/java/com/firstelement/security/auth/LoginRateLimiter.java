package com.firstelement.security.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory sliding-window limiter for the login endpoint.
 *
 * <p>Deliberately narrow: it protects the credential-stuffing surface only, and its
 * limits are far below the global per-request limit. The legacy controller used
 * {@code @Throttle({default: {limit: 10, ttl: 60_000}})}, so the defaults here are the
 * same 10 attempts per 60 seconds and existing behaviour is preserved.
 *
 * <h2>Keying</h2>
 *
 * <p>Two independent buckets are checked per login attempt:
 *
 * <ul>
 *   <li><b>per address</b> — stops one host spraying many accounts;</li>
 *   <li><b>per account</b> — stops one account being sprayed from a botnet, which a pure
 *       per-address limit does not catch. Keyed on the normalized email alone, with no
 *       address component, precisely so that rotating source addresses does not reset
 *       it.</li>
 * </ul>
 *
 * <p>The account key is the normalized email, never the submitted password, and the key
 * is never logged or persisted.
 *
 * <h2>Known limitation, stated rather than hidden</h2>
 *
 * <p>This is per-process state. With more than one API replica the effective limit is
 * per replica. Moving the counters to Redis is an operations decision, recorded as a
 * follow-up; the control it provides is unchanged and it fails closed (a Redis outage
 * must not silently disable the limiter), so the migration is additive.
 */
public class LoginRateLimiter {

    private final int maxAttempts;
    private final Duration windowDuration;
    private final Map<String, Window> buckets = new ConcurrentHashMap<>();

    public LoginRateLimiter(int maxAttempts, Duration window) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("window must be a positive duration");
        }
        this.maxAttempts = maxAttempts;
        this.windowDuration = window;
    }

    /** A decision plus how long the caller should be asked to wait. */
    public record Decision(boolean allowed, int retryAfterSeconds, String scope) {
    }

    /**
     * Counts one attempt. Counting happens even for a rejected attempt, so a client
     * that keeps hammering stays blocked rather than resetting the window.
     */
    public Decision recordAttempt(String remoteAddress, String normalizedEmail) {
        Instant now = Instant.now();
        Decision addressDecision = evaluate("address", addressKey(remoteAddress), now);
        Decision accountDecision = evaluate("account", accountKey(normalizedEmail), now);
        if (!addressDecision.allowed()) {
            return addressDecision;
        }
        if (!accountDecision.allowed()) {
            return accountDecision;
        }
        return new Decision(true, 0, null);
    }

    /** Whether the caller is currently allowed to even attempt, without consuming. */
    public Decision currentState(String remoteAddress, String normalizedEmail) {
        Instant now = Instant.now();
        Decision addressDecision = evaluate("address", addressKey(remoteAddress), now);
        if (!addressDecision.allowed()) {
            return addressDecision;
        }
        return evaluate("account", accountKey(normalizedEmail), now);
    }

    /** Clears a bucket after a successful login so a legitimate user is not penalised. */
    public void reset(String remoteAddress, String normalizedEmail) {
        // Only the account bucket: clearing the address bucket would let a client that
        // just authenticated legitimately reset a host-level spray window.
        buckets.remove(accountKey(normalizedEmail));
    }

    /** Test/ops hook: drops expired windows. */
    public void evictExpired() {
        Instant now = Instant.now();
        buckets.entrySet().removeIf(entry -> entry.getValue().expiredAt(now));
    }

    /**
     * Test/ops hook: clears every bucket.
     *
     * <p>Exists because the limiter is per-process state: a test that asserts the limit
     * would otherwise be at the mercy of whatever ran before it, and an operator
     * unblocking a legitimate user needs the same operation. Never call this from a
     * request path.
     */
    public void resetAll() {
        buckets.clear();
    }

    int trackedBucketCount() {
        return buckets.size();
    }

    private Decision evaluate(String scope, String key, Instant now) {
        Window bucket = buckets.compute(key, (k, existing) -> {
            if (existing == null || existing.expiredAt(now)) {
                return new Window(1, now.plus(windowDuration));
            }
            existing.attempts++;
            return existing;
        });
        if (bucket.attempts > maxAttempts) {
            long retryAfter = Math.max(1, Duration.between(now, bucket.expiresAt).toSeconds());
            return new Decision(false, (int) Math.min(retryAfter, Integer.MAX_VALUE), scope);
        }
        return new Decision(true, 0, null);
    }

    private static String addressKey(String remoteAddress) {
        return "addr:" + (remoteAddress == null ? "unknown" : remoteAddress);
    }

    private static String accountKey(String normalizedEmail) {
        // No address component: the bucket must survive source-address rotation, which is
        // the cheapest way for a botnet to defeat a per-address limit.
        return "acct:" + (normalizedEmail == null ? "" : normalizedEmail);
    }

    private static final class Window {
        private int attempts;
        private final Instant expiresAt;

        Window(int attempts, Instant expiresAt) {
            this.attempts = attempts;
            this.expiresAt = expiresAt;
        }

        boolean expiredAt(Instant now) {
            return now.isAfter(expiresAt);
        }
    }
}