package com.firstelement.security.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Login rate limiting.
 *
 * <p>The legacy controller used {@code @Throttle({default: {limit: 10, ttl: 60_000}})}; the
 * defaults here are identical so switching runtimes does not change the posture.
 */
class LoginRateLimiterTest {

    private static final String ADDRESS = "203.0.113.10";
    private static final String OTHER_ADDRESS = "203.0.113.11";

    private LoginRateLimiter limiter() {
        return new LoginRateLimiter(10, Duration.ofSeconds(60));
    }

    @Test
    @DisplayName("ten attempts pass and the eleventh is refused")
    void theLimitIsEnforced() {
        LoginRateLimiter limiter = limiter();
        for (int attempt = 1; attempt <= 10; attempt++) {
            assertThat(limiter.recordAttempt(ADDRESS, "user@example.test").allowed())
                .as("attempt %d", attempt).isTrue();
        }

        LoginRateLimiter.Decision denied = limiter.recordAttempt(ADDRESS, "user@example.test");
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterSeconds()).isBetween(1, 60);
        assertThat(denied.scope()).isEqualTo("address");
    }

    @Test
    @DisplayName("a refused attempt keeps the window open instead of resetting it")
    void hammeringDoesNotResetTheWindow() {
        LoginRateLimiter limiter = limiter();
        for (int i = 0; i < 10; i++) {
            limiter.recordAttempt(ADDRESS, "user@example.test");
        }
        for (int i = 0; i < 25; i++) {
            assertThat(limiter.recordAttempt(ADDRESS, "user@example.test").allowed()).isFalse();
        }
    }

    @Test
    @DisplayName("a botnet spraying one account is caught by the per-account bucket")
    void perAccountBucketCatchesABotnet() {
        LoginRateLimiter limiter = limiter();
        String account = "target@example.test";

        for (int i = 0; i < 10; i++) {
            assertThat(limiter.recordAttempt(ADDRESS + i, account).allowed()).isTrue();
        }
        LoginRateLimiter.Decision denied = limiter.recordAttempt(ADDRESS + 10, account);

        assertThat(denied.allowed()).isFalse();
        assertThat(denied.scope()).isEqualTo("account");
    }

    @Test
    @DisplayName("a successful login clears the account bucket but not the address window")
    void resetIsScopedToTheAccount() {
        LoginRateLimiter limiter = limiter();
        for (int i = 0; i < 10; i++) {
            limiter.recordAttempt(ADDRESS, "user@example.test");
        }
        assertThat(limiter.recordAttempt(ADDRESS, "user@example.test").allowed()).isFalse();

        limiter.reset(ADDRESS, "user@example.test");

        // The address window still has its 11 attempts, so a reset of one account cannot
        // be used to keep spraying from the same host.
        assertThat(limiter.recordAttempt(ADDRESS, "user@example.test").allowed()).isFalse();
        assertThat(limiter.recordAttempt(OTHER_ADDRESS, "user@example.test").allowed()).isTrue();
    }

    @Test
    @DisplayName("currentState answers without consuming an attempt")
    void currentStateDoesNotConsume() {
        LoginRateLimiter limiter = limiter();
        for (int i = 0; i < 10; i++) {
            limiter.recordAttempt(ADDRESS, "user@example.test");
        }

        assertThat(limiter.currentState(ADDRESS, "user@example.test").allowed()).isFalse();
        assertThat(limiter.currentState(ADDRESS, "user@example.test").allowed()).isFalse();
    }

    @Test
    @DisplayName("an empty window expiring frees the bucket")
    void windowsExpire() {
        LoginRateLimiter limiter = new LoginRateLimiter(2, Duration.ofMillis(40));
        assertThat(limiter.recordAttempt(ADDRESS, "user@example.test").allowed()).isTrue();
        assertThat(limiter.recordAttempt(ADDRESS, "user@example.test").allowed()).isTrue();
        assertThat(limiter.recordAttempt(ADDRESS, "user@example.test").allowed()).isFalse();

        try {
            Thread.sleep(60L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        assertThat(limiter.recordAttempt(ADDRESS, "user@example.test").allowed()).isTrue();
    }

    @Test
    @DisplayName("a null address or email still gets a bounded bucket rather than an NPE")
    void nullKeysAreTolerated() {
        LoginRateLimiter limiter = limiter();
        assertThat(limiter.recordAttempt(null, null).allowed()).isTrue();
        for (int i = 1; i <= 10; i++) {
            limiter.recordAttempt(null, null);
        }
        assertThat(limiter.recordAttempt(null, null).allowed()).isFalse();
    }

    @Test
    @DisplayName("invalid limiter configuration fails fast at construction")
    void invalidConfigurationIsRejected() {
        assertThatThrownBy(() -> new LoginRateLimiter(0, Duration.ofSeconds(60)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LoginRateLimiter(10, Duration.ZERO))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LoginRateLimiter(10, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("buckets are bounded enough to be sweepable")
    void bucketsAreEvictable() {
        LoginRateLimiter limiter = new LoginRateLimiter(5, Duration.ofMillis(20));
        for (int i = 0; i < 50; i++) {
            limiter.recordAttempt("10.0.0." + i, "user" + i + "@example.test");
        }
        assertThat(limiter.trackedBucketCount()).isEqualTo(100);

        try {
            Thread.sleep(40L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        limiter.evictExpired();
        assertThat(limiter.trackedBucketCount()).isZero();
    }
}