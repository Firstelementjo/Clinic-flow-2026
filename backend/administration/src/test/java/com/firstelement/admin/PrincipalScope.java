package com.firstelement.admin;

import com.firstelement.security.TenantAuthentication;
import com.firstelement.security.TenantContext;
import com.firstelement.security.TenantContextHolder;
import com.firstelement.security.UserRole;
import java.util.List;
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
 */
public final class PrincipalScope {

    private PrincipalScope() {
    }

    /** An administrator context for one of the fixture tenants, with every project in it. */
    public static TenantContext adminA() {
        return admin(AdminFixture.ORG_A, AdminFixture.ADMIN_A,
            List.of(AdminFixture.PROJECT_A, AdminFixture.PROJECT_A2));
    }

    public static TenantContext adminB() {
        return admin(AdminFixture.ORG_B, AdminFixture.ADMIN_B, List.of(AdminFixture.PROJECT_B));
    }

    /**
     * An employee context.
     *
     * <p>Used for the negative permission tests: the employee grant set is Phase 3's, unchanged, and
     * it holds none of the Admin permissions, so every Admin route must refuse it.
     */
    public static TenantContext employeeA() {
        return employee(AdminFixture.ORG_A, AdminFixture.EMPLOYEE_USER_A,
            List.of(AdminFixture.PROJECT_A));
    }

    private static TenantContext admin(UUID organizationId, UUID userId, List<java.util.UUID> projects) {
        return TenantContext.forUser(UUID.nameUUIDFromBytes(
            ("session:" + userId).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            userId, organizationId, UserRole.ADMIN, projects);
    }

    private static TenantContext employee(UUID organizationId, UUID userId,
                                          List<java.util.UUID> projects) {
        return TenantContext.forUser(UUID.nameUUIDFromBytes(
            ("session:" + userId).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            userId, organizationId, UserRole.EMPLOYEE, projects);
    }

    /** A machine principal, for the "a machine may not perform an attributable action" test. */
    public static TenantContext machineA() {
        return TenantContext.forMachine(
            com.firstelement.security.PrincipalType.WORKER, AdminFixture.ORG_A, "worker-under-test",
            List.of(AdminFixture.PROJECT_A), List.of(com.firstelement.security.Capability.AUDIT_READ));
    }

    public static <T> T call(TenantContext context, Supplier<T> work) {
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContextHolder.setContext(authentication(context));
        try {
            return TenantContextHolder.call(context, work);
        } finally {
            SecurityContextHolder.setContext(previous);
            TenantContextHolder.clear();
        }
    }

    /**
     * Runs a {@link Runnable} as a principal.
     *
     * <p>Present because almost every audited write in these suites returns {@code void}, and
     * {@code () -> writer.admin(...)} should not need a {@code return null;}.
     */
    public static void run(TenantContext context, Runnable work) {
        call(context, () -> {
            work.run();
            return null;
        });
    }

    public static void bind(TenantContext context) {
        SecurityContextHolder.setContext(authentication(context));
        TenantContextHolder.set(context);
    }

    public static void clear() {
        SecurityContextHolder.clearContext();
        TenantContextHolder.clear();
    }

    private static SecurityContext authentication(TenantContext context) {
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(new TenantAuthentication(context));
        return securityContext;
    }
}