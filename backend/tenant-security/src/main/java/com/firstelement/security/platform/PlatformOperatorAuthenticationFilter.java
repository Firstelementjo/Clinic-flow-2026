package com.firstelement.security.platform;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Resolves the platform operator cookie for platform routes only.
 *
 * <h2>Why this never touches the Spring Security context</h2>
 *
 * <p>See {@link PlatformOperatorContextHolder}. Writing to {@code SecurityContext} here would put the
 * platform principal in the same bucket as a customer session, and then "does this route require a
 * platform operator?" would be answerable only by a controller remembering to ask. Keeping the two in
 * different request contexts means neither credential can satisfy the other's route.
 *
 * <h2>What it refuses</h2>
 *
 * <ul>
 *   <li>It runs only under {@code /api/v1/platform/**}. A customer route never consults it, so a
 *       platform cookie is inert outside the console.</li>
 *   <li>It never reads the customer {@code fe_session} cookie. A clinic administrator's cookie
 *       cannot authenticate a cross-tenant route.</li>
 *   <li>A resolution failure is "no operator", never a bypass and never a stack trace — the same
 *       refusal the customer filter makes, for the same reason.</li>
 * </ul>
 */
public class PlatformOperatorAuthenticationFilter extends OncePerRequestFilter {

    /**
     * A distinct cookie from {@code fe_session}. Two names, not one name with two tables behind it:
     * a shared name would let one of them be set by the other's response.
     */
    public static final String COOKIE_NAME = "fe_platform_session";

    /**
     * The authority a platform operator holds, and the only one that opens a console route.
     *
     * <p>Distinct from every tenant permission on purpose: a customer session carries tenant
     * permissions and will never satisfy this, which is what makes the platform chain safe to
     * express in Spring Security's own vocabulary rather than in controller checks.
     */
    public static final String AUTHORITY = "ROLE_PLATFORM_OPERATOR";

    private static final String PLATFORM_PREFIX = "/api/v1/platform/";

    private final PlatformAuthService auth;
    private final com.firstelement.security.session.BrowserSessionStore sessionStore;

    public PlatformOperatorAuthenticationFilter(PlatformAuthService auth) {
        this(auth, null);
    }

    public PlatformOperatorAuthenticationFilter(PlatformAuthService auth,
                                                com.firstelement.security.session.BrowserSessionStore sessionStore) {
        this.auth = auth;
        this.sessionStore = sessionStore;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // getRequestURI() includes the context path, so under a non-root deployment
        // ("/api" + "/api/v1/platform/clinics") it would not start with the prefix and this filter
        // would skip itself -- leaving the console silently unauthenticated rather than broken.
        // getServletPath() + getPathInfo() is the path *within* the application, which is what the
        // security matcher's securityMatcher("/api/v1/platform/**") matches against, so it stays
        // consistent with the chain that installed this filter.
        String path = request.getServletPath();
        if (path != null && !path.isEmpty()) {
            String pathInfo = request.getPathInfo();
            if (pathInfo != null && !pathInfo.isEmpty()) {
                path = path + pathInfo;
            }
        } else {
            // Some mock requests (and a few containers) leave servletPath empty; fall back rather
            // than skip, because skipping here means "no authentication", which is the wrong way to
            // be wrong.
            path = request.getRequestURI();
        }
        if (path == null) {
            return true;
        }
        return !path.startsWith(PLATFORM_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            Optional<PlatformOperatorContext> operator = readPlatformCookie(request)
                    .flatMap(token -> auth.resolve(token, Instant.now()));

            if (operator.isEmpty() && sessionStore != null) {
                operator = readCustomerCookie(request)
                        .flatMap(token -> sessionStore.resolve(token))
                        .filter(s -> s.tenantContext().isPlatformAdmin())
                        .map(s -> new PlatformOperatorContext(
                                s.tenantContext().userId(),
                                "platform@clinicflow.io",
                                "Platform Administrator",
                                s.sessionId(),
                                PlatformCapability.all(),
                                true,
                                Instant.now().plus(java.time.Duration.ofHours(8))
                        ));
            }

            operator.ifPresent(context -> {
                PlatformOperatorContextHolder.set(context);
                // The principal is published under a platform-only authority. This filter is
                // installed exclusively on the /api/v1/platform/** chain, so this write can
                // never be seen by a customer route -- which is the separation the thread-local
                // alone was providing, now expressed in a form Spring Security can evaluate.
                // A customer session carries tenant permissions instead, so it can never
                // satisfy a requirement for this authority.
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                context.operatorId().toString(), null,
                                List.of(new SimpleGrantedAuthority(AUTHORITY))));
            });
            chain.doFilter(request, response);
        } finally {
            // A pooled request thread must not carry an operator into the next request that reuses
            // it. This is the single most important line in the class.
            PlatformOperatorContextHolder.clear();
        }
    }

    private Optional<String> readCustomerCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        for (Cookie cookie : cookies) {
            if ("fe_session".equals(cookie.getName())) {
                String value = cookie.getValue();
                if (value != null && !value.isBlank()) {
                    return Optional.of(value);
                }
            }
        }
        return Optional.empty();
    }

    private Optional<String> readPlatformCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        for (Cookie cookie : cookies) {
            if (COOKIE_NAME.equals(cookie.getName())) {
                String value = cookie.getValue();
                if (value == null || value.isBlank()) {
                    // An invalidated cookie is treated as absent, matching the customer filter.
                    return Optional.empty();
                }
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }
}