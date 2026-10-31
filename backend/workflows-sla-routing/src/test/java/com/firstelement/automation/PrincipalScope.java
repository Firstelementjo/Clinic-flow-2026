package com.firstelement.automation;

import com.firstelement.security.Capability;
import com.firstelement.security.PrincipalType;
import com.firstelement.security.TenantAuthentication;
import com.firstelement.security.TenantContext;
import com.firstelement.security.TenantContextHolder;
import com.firstelement.security.UserRole;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Runs a block as a principal, with both bindings a real request or job would have.
 *
 * <p>A request reaches an annotated service with a populated {@code SecurityContext} <em>and</em> a
 * thread-bound {@link TenantContext}. A test that calls the service directly must establish both, or
 * {@code @RequirePermission} fails with "no credential" instead of "no grant" — and the second failure
 * mode is the one that hides an authorization bug.
 *
 * <p>The scopes below mirror the fixture exactly. Tenant A's admin reaches both of tenant A's projects;
 * tenant B's admin reaches only its own, which is what makes a cross-project request a denial rather
 * than an empty result.
 */
final class PrincipalScope {

    private PrincipalScope() {
    }

    static TenantContext adminA() {
        return TenantContext.forUser(UUID.nameUUIDFromBytes("admin-a".getBytes(
                java.nio.charset.StandardCharsets.UTF_8)),
            WorkflowFixture.ADMIN_A, WorkflowFixture.ORG_A, UserRole.ADMIN,
            List.of(WorkflowFixture.PROJECT_A, WorkflowFixture.PROJECT_A2));
    }

    static TenantContext employeeA() {
        return TenantContext.forUser(UUID.nameUUIDFromBytes("employee-a".getBytes(
                java.nio.charset.StandardCharsets.UTF_8)),
            WorkflowFixture.EMPLOYEE_USER_A, WorkflowFixture.ORG_A, UserRole.EMPLOYEE,
            List.of(WorkflowFixture.PROJECT_A));
    }

    static TenantContext adminB() {
        return TenantContext.forUser(UUID.nameUUIDFromBytes("admin-b".getBytes(
                java.nio.charset.StandardCharsets.UTF_8)),
            WorkflowFixture.ADMIN_B, WorkflowFixture.ORG_B, UserRole.ADMIN,
            List.of(WorkflowFixture.PROJECT_B));
    }

    /**
     * A machine principal scoped to tenant A's first project, holding exactly the capabilities named.
     *
     * <p>Machine principals are the ones the capability grant table governs, so they are what a test
     * about {@code Permission} grants has to use: a human's permissions come from a role and never from
     * {@code Capability}. {@code TenantContext}'s own constructor is the guard here — it rejects a
     * capability the {@link PrincipalType} may not hold, and rejects a user id/role/session on a
     * machine — so a fixture cannot invent a principal that the taxonomy forbids.
     *
     * @param principalType the machine kind; both {@code AI_AGENT} and {@code WORKER} may hold
     *                      {@link Capability#AI_RUN} and {@link Capability#KNOWLEDGE_RETRIEVE}
     * @param capabilities  the explicit grant set; an empty array is a real principal, not a null
     */
    static TenantContext machine(PrincipalType principalType, String name,
                                 Collection<Capability> capabilities) {
        return TenantContext.forMachine(principalType, WorkflowFixture.ORG_A, name,
            List.of(WorkflowFixture.PROJECT_A), capabilities);
    }

    static TenantContext machine(PrincipalType principalType, String name,
                                 Capability... capabilities) {
        return machine(principalType, name, Arrays.asList(capabilities));
    }

    /** The legitimate sender: {@code forCapabilities} grants {@code AI_SEND} to exactly {@code AI_RUN}. */
    static TenantContext aiAgent() {
        return machine(PrincipalType.AI_AGENT, "workflow-agent", Capability.AI_RUN);
    }

    /**
     * The principal this defect is about: read-oriented retrieval, so it holds {@code AI_RUN_READ}
     * and nothing that authorizes a customer-visible write.
     */
    static TenantContext knowledgeRetriever() {
        return machine(PrincipalType.AI_AGENT, "knowledge-retriever",
            Capability.KNOWLEDGE_RETRIEVE);
    }

    static <T> T call(TenantContext context, Supplier<T> work) {
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContextHolder.setContext(authentication(context));
        try {
            return TenantContextHolder.call(context, work);
        } finally {
            SecurityContextHolder.setContext(previous);
            TenantContextHolder.clear();
        }
    }

    static void call(TenantContext context, Runnable work) {
        call(context, () -> {
            work.run();
            return null;
        });
    }

    private static SecurityContext authentication(TenantContext context) {
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(new TenantAuthentication(context));
        return securityContext;
    }
}