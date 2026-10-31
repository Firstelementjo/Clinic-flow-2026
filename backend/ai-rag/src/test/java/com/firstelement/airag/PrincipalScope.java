package com.firstelement.airag;

import com.firstelement.security.TenantAuthentication;
import com.firstelement.security.TenantContext;
import com.firstelement.security.TenantContextHolder;
import java.util.function.Supplier;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Runs a block as a principal, with both bindings a real request would have.
 *
 * <p>Ported from {@code :backend:inbox-helpdesk}'s {@code PrincipalScope}, for the same reason it
 * exists there: a request reaches an annotated service with a populated {@code SecurityContext}
 * <b>and</b> a thread-bound {@link TenantContext}. A test that calls the service directly must
 * establish both, or {@code @RequirePermission} cannot evaluate at all.
 *
 * <p>That last part is not a convenience. Calling {@link com.firstelement.airag.drafts.AiDraftReadService}
 * with an empty {@code SecurityContext} fails with
 * {@code Failed to evaluate expression '@fePermissionEvaluator.allows(authentication, ...)'} — the
 * SpEL has no {@code authentication} variable to bind — which reads like a wiring defect in the route
 * and is in fact a missing principal in the test. Binding both makes the negative assertions about
 * permissions reach the real {@code AuthorizationService} rather than stopping at SpEL.
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

    private static SecurityContext authentication(TenantContext context) {
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(new TenantAuthentication(context));
        return securityContext;
    }
}