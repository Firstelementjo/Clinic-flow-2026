package com.firstelement.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The invariants that make {@link TenantContext} usable as a security boundary.
 *
 * <p>These are the properties every other component relies on. If one of them silently
 * stops holding, a denial becomes a pass somewhere else.
 */
class TenantContextTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID SESSION = UUID.randomUUID();
    private static final UUID PROJECT_A = UUID.randomUUID();
    private static final UUID PROJECT_B = UUID.randomUUID();

    @Test
    @DisplayName("a user principal carries a role, a session, and a server-resolved project set")
    void userPrincipalShape() {
        TenantContext context = TenantContext.forUser(SESSION, USER, ORG, UserRole.EMPLOYEE,
            List.of(PROJECT_A, PROJECT_B));

        assertThat(context.principalType()).isEqualTo(PrincipalType.USER);
        assertThat(context.userId()).isEqualTo(USER);
        assertThat(context.organizationId()).isEqualTo(ORG);
        assertThat(context.role()).isEqualTo(UserRole.EMPLOYEE);
        assertThat(context.sessionId()).isEqualTo(SESSION);
        assertThat(context.isUser()).isTrue();
        assertThat(context.hasProject(PROJECT_A)).isTrue();
        assertThat(context.hasProject(UUID.randomUUID())).isFalse();
        assertThat(context.capabilities()).isEmpty();
    }

    @Test
    @DisplayName("the project set is a defensive copy and cannot be mutated after construction")
    void projectIdsAreImmutable() {
        java.util.List<UUID> source = new java.util.ArrayList<>(List.of(PROJECT_A));
        TenantContext context = TenantContext.forUser(SESSION, USER, ORG, UserRole.EMPLOYEE, source);

        source.add(PROJECT_B);

        assertThat(context.projectIds()).containsExactly(PROJECT_A);
        assertThatThrownBy(() -> context.projectIds().add(PROJECT_B))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("a user principal may not carry capabilities, and a machine principal may not carry a role")
    void theTwoGrantModelsCannotBeMixed() {
        assertThatThrownBy(() -> new TenantContext(PrincipalType.USER, USER, ORG, USER,
            UserRole.ADMIN, Set.of(), Set.of(Capability.MEMBERSHIP_MANAGE), SESSION))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("role, not capabilities");

        assertThatThrownBy(() -> new TenantContext(PrincipalType.WORKER, USER, ORG, USER,
            UserRole.ADMIN, Set.of(), Set.of(), SESSION))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("capabilities, not a user id");
    }

    @Test
    @DisplayName("organizationId is mandatory: there is no 'no tenant' context")
    void organizationIdIsMandatory() {
        assertThatThrownBy(() -> TenantContext.forUser(SESSION, USER, null, UserRole.ADMIN, List.of()))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("organizationId");
    }

    @Test
    @DisplayName("a capability the principal type may not hold is refused at construction")
    void capabilityTableIsEnforced() {
        // PROJECT_REQUIREMENTS 3.4: a workflow must not silently grant an AI agent more
        // access than the agent policy allows.
        assertThatThrownBy(() -> TenantContext.forMachine(PrincipalType.AI_AGENT, ORG,
            "agent-1", List.of(), List.of(Capability.MEMBERSHIP_MANAGE)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("may not be held by a AI_AGENT principal");

        assertThatThrownBy(() -> TenantContext.forMachine(PrincipalType.CONNECTOR, ORG,
            "whatsapp", List.of(), List.of(Capability.ORGANIZATION_MANAGE)))
            .isInstanceOf(IllegalArgumentException.class);

        // The permitted combination is accepted.
        TenantContext worker = TenantContext.forMachine(PrincipalType.WORKER, ORG,
            "worker-1", List.of(PROJECT_A), List.of(Capability.KNOWLEDGE_RETRIEVE));
        assertThat(worker.hasCapability(Capability.KNOWLEDGE_RETRIEVE)).isTrue();
        assertThat(worker.isMachine()).isTrue();
    }

    @Test
    @DisplayName("machine principal ids are namespaced by type so audit rows cannot collide")
    void machinePrincipalIdsAreNamespaced() {
        TenantContext worker = TenantContext.forMachine(PrincipalType.WORKER, ORG, "shared-name",
            List.of(), List.of(Capability.AI_RUN));
        TenantContext service = TenantContext.forMachine(PrincipalType.SERVICE, ORG, "shared-name",
            List.of(), List.of(Capability.AUDIT_READ));

        assertThat(worker.principalId()).isNotEqualTo(service.principalId());
        assertThat(worker.auditActorLabel()).startsWith("WORKER:");
        assertThat(service.auditActorLabel()).startsWith("SERVICE:");
    }

    @Test
    @DisplayName("a machine principal with no capability grant can see no project")
    void machinePrincipalHasNoImplicitProjectScope() {
        TenantContext context = TenantContext.forMachine(PrincipalType.SERVICE, ORG, "svc",
            List.of(), List.of());
        assertThat(context.projectIds()).isEmpty();
        assertThat(context.hasProject(PROJECT_A)).isFalse();
        assertThat(context.isAdmin()).isFalse();
    }

    @Test
    @DisplayName("TenantContextHolder binds, restores, and refuses to invent a scope")
    void threadBindingSemantics() {
        TenantContext outer = TenantContext.forUser(SESSION, USER, ORG, UserRole.ADMIN,
            List.of(PROJECT_A));
        TenantContext inner = outer.withProjects(List.of());

        assertThat(TenantContextHolder.isBound()).isFalse();
        assertThatThrownBy(TenantContextHolder::get)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Refusing to run unscoped");

        TenantContextHolder.set(outer);
        assertThat(TenantContextHolder.get()).isEqualTo(outer);

        AtomicReference<TenantContext> seen = new AtomicReference<>();
        TenantContextHolder.run(inner, () -> seen.set(TenantContextHolder.get()));
        assertThat(seen.get()).isEqualTo(inner);
        // The outer binding is restored, not clobbered.
        assertThat(TenantContextHolder.get()).isEqualTo(outer);

        TenantContextHolder.clear();
        assertThat(TenantContextHolder.isBound()).isFalse();

        // A nested call restores the previous value even when the body throws.
        TenantContextHolder.set(outer);
        assertThatThrownBy(() -> TenantContextHolder.run(inner, () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(TenantContextHolder.get()).isEqualTo(outer);
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("a bound context is resolved from either credential path's Authentication")
    void contextResolvesFromAuthentication() {
        TenantContext context = TenantContext.forUser(SESSION, USER, ORG, UserRole.EMPLOYEE,
            List.of(PROJECT_A));

        assertThat(TenantContextHolder.from(new TenantAuthentication(context))).contains(context);
        // Any other Authentication shape resolves to nothing, which the permission
        // evaluator treats as a denial.
        assertThat(TenantContextHolder.from(null)).isEmpty();
    }
}