package com.firstelement.exports;

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
 * <h2>Why both, and why this is not ceremony</h2>
 *
 * <p>A request reaches an annotated service with a populated {@code SecurityContext} <em>and</em> a
 * thread-bound {@link TenantContext}. A test that calls the service directly must establish both, or
 * {@code @RequirePermission} fails with "no credential" instead of "no grant" — which reads as an
 * authorization bug and is really a test-setup bug.
 *
 * <p>That distinction is not pedantic: an authorization test that fails for the wrong reason passes for the
 * wrong reason too. Every refusal asserted in this module's suites is a refusal produced by a principal the
 * grant table actually describes.
 *
 * <p>Modelled on the sibling finance harness, which arrived at the same shape independently.
 */
final class ExportsPrincipalScope {

    private ExportsPrincipalScope() {
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

    /**
     * A worker holding {@code EXPORT_WRITE}.
     *
     * <p>The interesting negative for creation: a machine principal reaches the grant table through
     * capabilities rather than a role, and this one has the analytics permissions a machine would hold.
     * It is still refused an export because {@code requestedByUserId} needs a person — an authorization
     * decision and a schema-truth decision, asserted separately.
     */
    static TenantContext worker(UUID organizationId, UUID projectId) {
        return TenantContext.forMachine(PrincipalType.WORKER, organizationId, "export-worker-test",
            List.of(projectId), List.of(Capability.EXPORT_WRITE));
    }

    /**
     * The caller's effective permissions, for a decision asserted against the grant table.
     *
     * <p>Built from a real {@link com.firstelement.security.permission.AuthorizationService} rather than a
     * hand-written set: a hand-written set would make an authorization assertion true by construction no
     * matter what {@code RolePermissions} said.
     */
    static Set<com.firstelement.security.permission.Permission> effective(TenantContext context) {
        return new com.firstelement.security.permission.AuthorizationService(event -> { })
            .effectivePermissions(context);
    }

    private static SecurityContext authentication(TenantContext context) {
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(new TenantAuthentication(context));
        return securityContext;
    }
}
