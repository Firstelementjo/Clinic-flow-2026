package com.firstelement.security.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The single place that decides whether a session cookie carries {@code Secure}.
 *
 * <p>This decision was made twice and the copies diverged: the customer cookie consulted a private
 * helper in {@code SecurityConfiguration}, while the platform cookie inlined
 * {@code Boolean.TRUE.equals(...)}. {@code sessionCookieSecure} is a nullable {@code Boolean} with no
 * default, so the inlined copy resolved to {@code false} whenever the property was unset — which is
 * the default, and therefore production. The platform operator's cookie would have been issued
 * without {@code Secure} in the only environment where it matters.
 *
 * <p>The tests below are the control that was missing. The platform cookie's own attributes are
 * asserted over HTTP in {@code PlatformConsoleBoundaryHttpIntegrationTest}; this covers the policy,
 * so a third cookie cannot quietly invent a fourth answer.
 */
class SecurityPropertiesCookieSecureTest {

    private static SecurityProperties withSecure(Boolean value) {
        SecurityProperties properties = new SecurityProperties();
        properties.setSessionCookieSecure(value);
        return properties;
    }

    @Nested
    @DisplayName("unset means the environment decides")
    class Unset {

        @Test
        @DisplayName("unset in production means Secure")
        void unsetInProductionIsSecure() {
            assertThat(withSecure(null).resolvedSessionCookieSecure(true)).isTrue();
        }

        @Test
        @DisplayName("unset outside production is not Secure, so local HTTP keeps working")
        void unsetOutsideProductionIsNotSecure() {
            assertThat(withSecure(null).resolvedSessionCookieSecure(false)).isFalse();
        }

        @Test
        @DisplayName("an explicitly false value outside production is honoured")
        void explicitFalseIsHonoured() {
            assertThat(withSecure(Boolean.FALSE).resolvedSessionCookieSecure(false)).isFalse();
        }

        @Test
        @DisplayName("an explicit true is honoured even outside production")
        void explicitTrueIsHonoured() {
            assertThat(withSecure(Boolean.TRUE).resolvedSessionCookieSecure(false)).isTrue();
        }
    }

    @Test
    @DisplayName("turning Secure off in production is refused at startup, not accepted quietly")
    void explicitFalseInProductionIsRefused() {
        // This is the property whose absence caused the defect: an unset value is safe, an explicit
        // false in production must be a boot failure rather than a silent downgrade.
        assertThatThrownBy(() -> withSecure(Boolean.FALSE).resolvedSessionCookieSecure(true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("session-cookie-secure=false");
    }

    @Nested
    @DisplayName("a SameSite mode that needs it forces it on")
    class SameSiteForcesSecure {

        @Test
        @DisplayName("SameSite=None is secure even when explicitly disabled outside production")
        void noneForcesSecure() {
            SecurityProperties properties = withSecure(Boolean.FALSE);
            properties.setSessionCookieSameSite(SecurityProperties.SameSite.NONE);

            assertThat(properties.resolvedSessionCookieSecure(false))
                    .as("SameSite=None cookies are cross-site by definition and cannot be sent over "
                            + "plain HTTP; an explicit false must not override that")
                    .isTrue();
        }

        @Test
        @DisplayName("SameSite=Strict does not force Secure, so local HTTP still works")
        void strictDoesNotForceSecure() {
            SecurityProperties properties = withSecure(null);
            properties.setSessionCookieSameSite(SecurityProperties.SameSite.STRICT);

            assertThat(properties.resolvedSessionCookieSecure(false)).isFalse();
        }
    }
}