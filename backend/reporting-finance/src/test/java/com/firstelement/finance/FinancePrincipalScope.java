package com.firstelement.finance;

import com.firstelement.security.Capability;
import com.firstelement.security.PrincipalType;
import com.firstelement.security.TenantAuthentication;
import com.firstelement.security.TenantContext;
import com.firstelement.security.TenantContextHolder;
import com.firstelement.security.UserRole;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Runs a block as a principal, with both bindings a real request would have.
 *
 * <p>A request reaches an annotated service with a populated {@code SecurityContext} <em>and</em> a
 * thread-bound {@link TenantContext}. A test that calls the service directly must establish both, or
 * {@code @RequirePermission} fails with "no credential" instead of "no grant" — which reads as an
 * authorization bug and is really a test-setup bug.
 *
 * <p>The machine principal here is a {@code FINANCE_AGGREGATE} worker, which is the case the finance
 * policy is written around: it satisfies {@code ANALYTICS_READ_ORGANIZATION} through the capability
 * table and is therefore <em>not</em> an administrator, which is what keeps it out of rate-card
 * authoring and period close.
 */
final class FinancePrincipalScope {

    private FinancePrincipalScope() {
    }

    /** An administrator for tenant A with both of its projects. */
    static TenantContext adminA() {
        return admin(FinanceFixture.ORG_A, FinanceFixture.ADMIN_A,
            List.of(FinanceFixture.PROJECT_A, FinanceFixture.PROJECT_A2));
    }

    /**
     * A second administrator for tenant A.
     *
     * <p>Exists because {@code finance_adjustments_not_self_approved_check} requires the approver to
     * differ from the author, and a test that cannot produce two people cannot test that rule.
     */
    static TenantContext approverA() {
        return admin(FinanceFixture.ORG_A, FinanceFixture.APPROVER_A,
            List.of(FinanceFixture.PROJECT_A));
    }

    /**
     * An administrator for tenant A holding {@code PROJECT_A2} and not {@code PROJECT_A}.
     *
     * <p>The read-then-check case: the caller satisfies the permission, is in the snapshot's own
     * tenant, and is refused only because of the project the row belongs to. An employee would be
     * refused earlier by {@code @RequirePermission}, which would make such a test green for the wrong
     * reason.
     */
    static TenantContext adminAWithoutProjectA() {
        return admin(FinanceFixture.ORG_A, FinanceFixture.ADMIN_A,
            List.of(FinanceFixture.PROJECT_A2));
    }

    static TenantContext adminB() {
        return admin(FinanceFixture.ORG_B, FinanceFixture.ADMIN_B,
            List.of(FinanceFixture.PROJECT_B));
    }

    /** An employee: the negative for every finance route. */
    static TenantContext employeeA() {
        return user(FinanceFixture.ORG_A, FinanceFixture.EMPLOYEE_A,
            List.of(FinanceFixture.PROJECT_A));
    }

    /**
     * A worker holding {@code FINANCE_AGGREGATE}.
     *
     * <p>The interesting mixed case: it may compute aggregates and reconcile an open period, and it may
     * not author a rate card or lock a period. Both halves are asserted.
     */
    static TenantContext financeWorkerA() {
        return TenantContext.forMachine(PrincipalType.WORKER, FinanceFixture.ORG_A,
            "finance-rollup-worker", List.of(FinanceFixture.PROJECT_A, FinanceFixture.PROJECT_A2),
            List.of(Capability.FINANCE_AGGREGATE));
    }

    private static TenantContext admin(UUID organizationId, UUID userId, List<UUID> projects) {
        return user(organizationId, userId, projects, UserRole.ADMIN);
    }

    private static TenantContext user(UUID organizationId, UUID userId, List<UUID> projects) {
        return user(organizationId, userId, projects, UserRole.EMPLOYEE);
    }

    private static TenantContext user(UUID organizationId, UUID userId, List<UUID> projects,
                                      UserRole role) {
        return TenantContext.forUser(
            UUID.nameUUIDFromBytes(("session:" + userId).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            userId, organizationId, role, projects);
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

    static void run(TenantContext context, Runnable work) {
        call(context, () -> {
            work.run();
            return null;
        });
    }

    static void bind(TenantContext context) {
        SecurityContextHolder.setContext(authentication(context));
        TenantContextHolder.set(context);
    }

    static void clear() {
        SecurityContextHolder.clearContext();
        TenantContextHolder.clear();
    }

    /** The caller's effective permissions, for a decision asserted against the grant table. */
    static Set<com.firstelement.security.permission.Permission> effective(
            TenantContext context) {
        return new com.firstelement.security.permission.AuthorizationService(event -> { })
            .effectivePermissions(context);
    }

    private static SecurityContext authentication(TenantContext context) {
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(new TenantAuthentication(context));
        return securityContext;
    }
}
