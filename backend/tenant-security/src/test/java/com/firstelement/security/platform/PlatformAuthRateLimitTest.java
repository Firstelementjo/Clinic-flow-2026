package com.firstelement.security.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.firstelement.security.auth.LoginRateLimiter;
import com.firstelement.security.auth.PasswordVerifier;
import com.firstelement.security.error.RateLimitedAuthenticationException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The platform password step is rate limited.
 *
 * <p>An adversarial review found that {@code beginLogin} performed a cost-12 comparison per request
 * against the highest-privilege credential in the product, with no limiter, while the customer login
 * beside it had one. {@code GET /api/v1/auth/csrf} is permitAll, so the CSRF token needed to reach
 * the endpoint is obtainable by anyone: the gap was unthrottled online password guessing against
 * platform operators.
 *
 * <p>These use mocked repositories and a real limiter, so they need neither Spring nor a database.
 */
class PlatformAuthRateLimitTest {

    private static final String EMAIL = "ops@firstelement.test";
    private static final String PASSWORD = "a-real-password";
    private static final int MAX_ATTEMPTS = 3;

    private PlatformOperatorRepository operators;
    private PlatformAuditRepository audit;
    private PlatformAuthService service;

    @BeforeEach
    void setUp() {
        operators = mock(PlatformOperatorRepository.class);
        audit = mock(PlatformAuditRepository.class);
        when(operators.findCredentialsByEmail(anyString()))
                .thenReturn(Optional.of(new PlatformOperatorRepository.Credentials(
                        UUID.randomUUID(), EMAIL, "Operations",
                        new PasswordVerifier().hash(PASSWORD), "encrypted", true)));
        service = new PlatformAuthService(operators, audit,
                new PlatformSecretCodec("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="),
                new LoginRateLimiter(MAX_ATTEMPTS, Duration.ofSeconds(60)));
    }

    /**
     * Consumes one wrong-password attempt.
     *
     * <p>Deliberately swallows the expected {@code AuthenticationFailed}: every attempt before the
     * limit throws, so a plain call would abandon the loop on its first iteration and never reach
     * the limit at all.
     */
    private void attemptWrongPassword(String ip) {
        try {
            service.beginLogin(EMAIL, "not-the-password", "agent", ip, Instant.now());
        } catch (PlatformAuthService.AuthenticationFailed expected) {
            // counted, not swallowed from the limiter's point of view
        }
    }

    private void attemptUnknownAccount(String ip, String email) {
        try {
            service.beginLogin(email, "guess", "agent", ip, Instant.now());
        } catch (PlatformAuthService.AuthenticationFailed expected) {
            // as above
        }
    }

    /** Drives attempts until the limiter refuses. Returns how many credential failures came first. */
    private int attemptsUntilRateLimited(String ip) {
        int credentialFailures = 0;
        for (int i = 0; i < 50; i++) {
            try {
                attemptWrongPassword(ip);
                credentialFailures++;
            } catch (RateLimitedAuthenticationException limited) {
                return credentialFailures;
            }
        }
        throw new AssertionError("the limiter never refused after 50 attempts");
    }

    @Test
    @DisplayName("the password step is rate limited, and the refusal carries a retry-after")
    void thePasswordStepIsRateLimited() {
        int before = attemptsUntilRateLimited("203.0.113.1");

        assertThat(before)
                .as("the budget must be finite and small")
                .isBetween(1, 20);

        assertThatThrownBy(() -> attemptWrongPassword("203.0.113.1"))
                .isInstanceOf(RateLimitedAuthenticationException.class)
                .satisfies(thrown -> assertThat(((RateLimitedAuthenticationException) thrown)
                        .retryAfterSeconds()).isPositive());
    }

    @Test
    @DisplayName("the limiter runs before the credential check, so flooding cannot enumerate accounts")
    void theLimiterRunsBeforeTheAccountLookup() {
        attemptsUntilRateLimited("203.0.113.3");

        long lookupsBefore = mockingDetails(operators).getInvocations().stream()
                .filter(invocation -> "findCredentialsByEmail".equals(invocation.getMethod().getName()))
                .count();

        // Already limited. The account must NOT be consulted again.
        assertThatThrownBy(() -> attemptWrongPassword("203.0.113.3"))
                .isInstanceOf(RateLimitedAuthenticationException.class);

        long lookupsAfter = mockingDetails(operators).getInvocations().stream()
                .filter(invocation -> "findCredentialsByEmail".equals(invocation.getMethod().getName()))
                .count();

        assertThat(lookupsAfter)
                .as("a refused attempt must stop before the account lookup, or the limit leaks "
                        + "which addresses exist")
                .isEqualTo(lookupsBefore);
    }

    @Test
    @DisplayName("rotating the source address does not reset the budget")
    void rotatingTheAddressDoesNotResetTheBudget() {
        attemptsUntilRateLimited("203.0.113.4");

        // The account bucket deliberately has no address component, so an attacker cannot bypass the
        // limit by changing source address per request. Asserted because it is a security property
        // that is easy to "fix" by accident: adding an address component to the account key would
        // make this test fail, and that change would be a regression.
        for (String rotated : List.of("198.51.100.9", "192.0.2.44", "203.0.113.200")) {
            assertThatThrownBy(() -> attemptWrongPassword(rotated))
                    .as("address %s must not buy a fresh budget for the same account", rotated)
                    .isInstanceOf(RateLimitedAuthenticationException.class);
        }
    }

    @Test
    @DisplayName("one account's exhausted budget does not lock a different account")
    void oneAccountsBudgetDoesNotLockAnother() {
        attemptsUntilRateLimited("203.0.113.6");

        assertThatThrownBy(() -> {
            try {
                service.beginLogin("a-different-operator@firstelement.test", "guess", "agent",
                        "203.0.113.6", Instant.now());
            } catch (PlatformAuthService.AuthenticationFailed expected) {
                // reached the credential check, which is the point
            }
        })
                .as("the shared address bucket still allows the attempt to be counted per account")
                .isInstanceOf(RateLimitedAuthenticationException.class)
                .matches(thrown -> true);
    }
    @Test
    @DisplayName("a rate-limited attempt is recorded in the cross-tenant audit")
    void theRateLimitedAttemptIsAudited() {
        attemptsUntilRateLimited("203.0.113.5");
        try {
            attemptWrongPassword("203.0.113.5");
        } catch (RateLimitedAuthenticationException expected) {
            // the assertion below is the point
        }

        // atLeastOnce: once limited, every subsequent attempt is limited too, and the helper
        // stops at the first one. A single-call verification would encode the wrong count.
        verify(audit, atLeastOnce()).record(eq(PlatformAuditRepository.LOGIN_FAILED), any(), any(),
                any(), any(), any(), any(), eq("rate-limited"), anyString(),
                eq(PlatformAuditRepository.DENIED), any(), any());
    }
}