package com.firstelement.identity;

import com.firstelement.security.TenantAuthentication;
import com.firstelement.security.TenantContext;
import com.firstelement.security.TenantContextHolder;
import java.util.function.Supplier;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Runs a block as a principal, with both bindings a real request would have.
 *
 * <p>A request reaches an annotated service with a populated
 * {@code SecurityContext} <em>and</em> a thread-bound {@link TenantContext}
 * ({@code TenantContextFilter}). A test that calls the same service directly has to
 * establish both, otherwise {@code @RequirePermission} fails for the wrong reason: Spring
 * Security's {@code getAuthentication()} throws when the context is empty, so the denial
 * would be "no credential" rather than "no grant" — which is exactly the kind of
 * confusion that leads to a "skip the check when there is no authentication" workaround.
 *
 * <p>Deliberately a test helper rather than production code. Production code gets these
 * bindings from the servlet filter chain or from {@code TenantScopedExecutor}.
 */
final class PrincipalScope {

    private PrincipalScope() {
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

    static void bind(TenantContext context) {
        SecurityContextHolder.setContext(authentication(context));
        TenantContextHolder.set(context);
    }

    private static SecurityContext authentication(TenantContext context) {
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(new TenantAuthentication(context));
        return securityContext;
    }
}