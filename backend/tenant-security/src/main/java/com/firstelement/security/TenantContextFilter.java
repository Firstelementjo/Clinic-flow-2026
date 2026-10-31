package com.firstelement.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Binds the authenticated {@link TenantContext} to the thread for the duration of the
 * request, and clears it in a {@code finally}.
 *
 * <h2>Why a thread binding at all</h2>
 *
 * <p>plan.md §0.4: "No controller-only authorization." A repository method, a report
 * aggregator, a pgvector query builder, and a job handler have no
 * {@code Authentication} parameter, yet must not run unscoped. They read the context
 * from here.
 *
 * <h2>Why the filter refuses to bind</h2>
 *
 * <p>Binding is unconditional on successful authentication: a request with no
 * {@code TenantAuthentication} gets no context, and any code that then calls
 * {@link TenantContextHolder#get()} fails loudly rather than reading the whole table.
 *
 * <h2>Order</h2>
 *
 * <p>{@link SecurityProperties#DEFAULT_FILTER_ORDER} + 1, i.e. immediately after
 * {@code SecurityFilterChain} and before the {@code DispatcherServlet}. Placing it
 * earlier would mean it ran before the session filter and could bind nothing; placing
 * it later would mean it ran after the controller and protected nothing.
 */
@Order(SecurityProperties.DEFAULT_FILTER_ORDER + 1)
public class TenantContextFilter extends OncePerRequestFilter {

    /** Request attribute holding the resolved context, for downstream web code. */
    public static final String TENANT_CONTEXT_ATTRIBUTE =
        TenantContextFilter.class.getName() + ".tenantContext";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            TenantContextHolder.from(authentication()).ifPresent(context -> {
                TenantContextHolder.set(context);
                request.setAttribute(TENANT_CONTEXT_ATTRIBUTE, context);
            });
            chain.doFilter(request, response);
        } finally {
            // Always cleared: a pooled servlet thread must never carry one request's
            // tenant into the next one.
            TenantContextHolder.clear();
        }
    }

    private Authentication authentication() {
        return SecurityContextHolder.getContext().getAuthentication();
    }
}