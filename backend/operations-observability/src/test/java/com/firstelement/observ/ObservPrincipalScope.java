package com.firstelement.observ;

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
public final class ObservPrincipalScope {

    private ObservPrincipalScope() {
    }

    /** An administrator context for one of the fixture tenants, with every project in it. */
    public static TenantContext adminA() {
        return admin(ObservFixture.ORG_A, ObservFixture.ADMIN_A,
            List.of(ObservFixture.PROJECT_A, ObservFixture.PROJECT_A2));
    }

    public static TenantContext adminB() {
        return admin(ObservFixture.ORG_B, ObservFixture.ADMIN_B,
            List.of(ObservFixture.PROJECT_B));
    }

    /**
     * An employee context.
     *
     * <p>Used for the negative permission tests: the employee grant set is Phase 3's, unchanged, and
     * it holds none of the operational permissions, so every operations route must refuse it.
     */
    public static TenantContext employeeA() {
        return employee(ObservFixture.ORG_A, ObservFixture.EMPLOYEE_A,
            List.of(ObservFixture.PROJECT_A));
    }

    private static TenantContext admin(UUID organizationId, UUID userId, List<UUID> projects) {
        return TenantContext.forUser(UUID.nameUUIDFromBytes(
            ("session:" + userId).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            userId, organizationId, UserRole.ADMIN, projects);
    }

    private static TenantContext employee(UUID organizationId, UUID userId, List<UUID> projects) {
        return TenantContext.forUser(UUID.nameUUIDFromBytes(
            ("session:" + userId).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            userId, organizationId, UserRole.EMPLOYEE, projects);
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
     * <p>Present because most audited writes return {@code void}, and {@code () -> writer.job(...)}
     * should not need a {@code return null;}.
     */
    public static void run(TenantContext context, Runnable work) {
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
