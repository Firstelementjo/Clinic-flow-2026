package com.firstelement.airag.principal;

import com.firstelement.security.Capability;
import com.firstelement.security.PrincipalType;
import com.firstelement.security.TenantContext;
import com.firstelement.security.UserRole;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Tenant contexts for the AI suites, built explicitly rather than through a session.
 *
 * <h2>Why this is not hiding a missing bean</h2>
 *
 * <p>It builds <em>values</em>, not beans. A {@link TenantContext} is a record the security boundary
 * produces from a real session; the suites construct the same record directly because the property under test
 * is what the orchestrator does <em>with</em> a resolved context, and routing it through the cookie filter
 * would add a second, unrelated thing that could fail. The permission and capability checks these suites
 * assert are exercised through the real {@code AuthorizationService} and {@code AiToolExecutionGuard}, so
 * the enforcement path is production code in both cases.
 */
public final class AiPrincipalScope {

    private AiPrincipalScope() {
    }

    /**
     * The agent principal: the machine identity a run acts as.
     *
     * <p>Holds {@code AI_RUN}, {@code KNOWLEDGE_RETRIEVE} and {@code AI_TOOL_EXECUTE} — and deliberately does
     * <b>not</b> hold anything that would let it send: a send is performed on a human's behalf under
     * {@code Permission.AI_SEND}, and {@code Capability.mayBeHeldBy} already refuses these capabilities for a
     * {@code CONNECTOR} or {@code SERVICE} principal.
     */
    public static TenantContext agent(UUID organizationId, List<UUID> projects) {
        return TenantContext.forMachine(PrincipalType.AI_AGENT, organizationId, "ai-rag-tests",
            projects, List.of(Capability.AI_RUN, Capability.KNOWLEDGE_RETRIEVE, Capability.AI_TOOL_EXECUTE));
    }

    /**
     * An agent principal with a narrower scope.
     *
     * <p>The cross-project negative uses this: a run whose principal is scoped to {@code PROJECT_A} asking
     * for a tool against {@code PROJECT_A2} must be refused, and that refusal has to be the guard's doing
     * rather than a missing membership in the fixture.
     */
    public static TenantContext agentScopedTo(UUID organizationId, UUID projectId) {
        return agent(organizationId, List.of(projectId));
    }

    /**
     * An agent with no capabilities at all, so a capability refusal is reachable.
     */
    public static TenantContext agentWithoutCapabilities(UUID organizationId, List<UUID> projects) {
        return TenantContext.forMachine(PrincipalType.AI_AGENT, organizationId, "ai-rag-tests-no-caps",
            projects, List.of());
    }

    /** A human administrator, who holds every permission through {@code EnumSet.allOf}. */
    public static TenantContext admin(UUID organizationId, UUID userId, List<UUID> projects) {
        return TenantContext.forUser(UUID.randomUUID(), userId, organizationId, UserRole.ADMIN, projects);
    }

    /**
     * A human employee.
     *
     * <p>Not every permission, so the {@code AI_SEND} denial is reachable: {@code RolePermissions} gives an
     * EMPLOYEE the conversation and knowledge permissions, and an AI-specific permission is exactly the kind
     * of grant an operator has to make deliberately.
     */
    public static TenantContext employee(UUID organizationId, UUID userId, List<UUID> projects) {
        return TenantContext.forUser(UUID.randomUUID(), userId, organizationId, UserRole.EMPLOYEE, projects);
    }

    /** A worker principal, which is what {@code Capability.DOCUMENT_INGEST} admits. */
    public static TenantContext worker(UUID organizationId, List<UUID> projects) {
        return TenantContext.forMachine(PrincipalType.WORKER, organizationId, "ai-rag-worker", projects,
            List.of(Capability.DOCUMENT_INGEST, Capability.KNOWLEDGE_RETRIEVE, Capability.AI_RUN,
                Capability.AI_TOOL_EXECUTE, Capability.FINANCE_AGGREGATE));
    }

    /** The capabilities an AI agent may hold. Used by the capability-boundary assertion. */
    public static Set<Capability> agentCapabilities() {
        return Set.of(Capability.AI_RUN, Capability.KNOWLEDGE_RETRIEVE, Capability.AI_TOOL_EXECUTE);
    }
}