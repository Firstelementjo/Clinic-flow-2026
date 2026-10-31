package com.firstelement.inbox;

import com.firstelement.security.TenantAuthentication;
import com.firstelement.security.TenantContext;
import com.firstelement.security.TenantContextHolder;
import java.util.function.Supplier;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Runs a block as a principal, with both bindings a real request would have.
 *
 * <p>A request reaches an annotated service with a populated {@code SecurityContext} <em>and</em>
 * a thread-bound {@link TenantContext}. A test that calls the service directly must establish both,
 * or {@code @RequirePermission} fails with "no credential" instead of "no grant".
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

    static void clear() {
        SecurityContextHolder.clearContext();
        TenantContextHolder.clear();
    }

    private static SecurityContext authentication(TenantContext context) {
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(new TenantAuthentication(context));
        return securityContext;
    }
}