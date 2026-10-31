package com.firstelement.security.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The properties that keep ADR 0020's boundary true.
 *
 * <p>These are deliberately unit tests with no Spring and no database. The boundary ADR 0020 asks
 * for is a property of the <em>types</em>: a platform operator cannot be expressed as a tenant
 * principal, and an unelevated operator cannot write. Both are compile-time and constructor-time
 * facts, so a plain unit test is the right level to assert them at — an integration test would prove
 * them less directly, by exercising them through code that could be changed to route around them.
 */
class PlatformOperatorContextTest {

    private static final UUID OPERATOR = UUID.randomUUID();
    private static final UUID SESSION = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-12T10:00:00Z");

    private static PlatformOperatorContext with(Set<PlatformCapability> capabilities,
                                                boolean mfaSatisfied, Instant elevatedUntil) {
        return new PlatformOperatorContext(OPERATOR, "ops@firstelement.invalid", "Operations",
                SESSION, capabilities, mfaSatisfied, elevatedUntil);
    }

    @Test
    @DisplayName("reading is permitted once authenticated; writing is not, until elevated")
    void readingAndWritingAreSeparated() {
        PlatformOperatorContext operator = with(
                Set.of(PlatformCapability.READ_PLATFORM_OVERVIEW, PlatformCapability.CREATE_CLINIC),
                true, null);

        assertThat(operator.permits(PlatformCapability.READ_PLATFORM_OVERVIEW, NOW))
                .as("a survey of the platform needs no elevation window")
                .isTrue();

        assertThat(operator.permits(PlatformCapability.CREATE_CLINIC, NOW))
                .as("creating a clinic is a write and must require elevation")
                .isFalse();
    }

    @Test
    @DisplayName("an elevation window that has closed stops permitting writes")
    void elevationExpiresRatherThanPersisting() {
        PlatformOperatorContext operator = with(
                Set.of(PlatformCapability.CREATE_CLINIC), true, NOW.plusSeconds(900));

        assertThat(operator.permits(PlatformCapability.CREATE_CLINIC, NOW)).isTrue();
        assertThat(operator.permits(PlatformCapability.CREATE_CLINIC, NOW.plusSeconds(901)))
                .as("the window closes on time, not when the session happens to expire")
                .isFalse();
    }

    @Test
    @DisplayName("a session cannot be elevated before its second factor is satisfied")
    void elevationRequiresMfa() {
        assertThatThrownBy(() -> with(Set.of(PlatformCapability.CREATE_CLINIC), false,
                NOW.plusSeconds(900)))
                .as("the constructor is the enforcement point, not the service that happens to call it")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("second factor");
    }

    @Test
    @DisplayName("a capability the operator does not hold is refused whatever the elevation state")
    void unknownCapabilityIsAlwaysRefused() {
        PlatformOperatorContext elevated = with(
                Set.of(PlatformCapability.READ_PLATFORM_OVERVIEW), true, NOW.plusSeconds(900));

        assertThat(elevated.isElevated(NOW)).isTrue();
        assertThat(elevated.permits(PlatformCapability.REQUEST_DB_ACTION, NOW))
                .as("elevation is not a universal unlock")
                .isFalse();
    }

    @Test
    @DisplayName("there is no capability constant for raw SQL, shell or secret display")
    void forbiddenCapabilitiesDoNotExist() {
        // ADR 0020 forbids them. A forbidden constant that exists is one typo away from being
        // granted, so the real control is their absence, and absence is what this asserts.
        List<String> names = List.of(PlatformCapability.values()).stream()
                .map(Enum::name).toList();

        assertThat(names).noneMatch(n -> n.contains("SQL") || n.contains("SHELL")
                || n.contains("SECRET") || n.contains("EXECUTE"));
    }

    @Test
    @DisplayName("the audit label names the operator and carries no secret")
    void auditLabelIsSafe() {
        PlatformOperatorContext operator = with(Set.of(), true, null);
        assertThat(operator.auditActorLabel())
                .isEqualTo("platform-operator:ops@firstelement.invalid")
                .doesNotContain("token");
    }
}