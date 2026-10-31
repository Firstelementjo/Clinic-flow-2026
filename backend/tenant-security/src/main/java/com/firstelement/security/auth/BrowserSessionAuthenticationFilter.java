package com.firstelement.security.auth;

import com.firstelement.security.session.BrowserSessionStore;
import com.firstelement.security.session.SessionTokenCodec;
import com.firstelement.security.TenantAuthentication;
import com.firstelement.security.TenantContextHolder;
import com.firstelement.security.event.SecurityEvent;
import com.firstelement.security.event.SecurityEventPublisher;
import com.firstelement.security.event.SecurityEventType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Turns the {@code fe_session} cookie into an authenticated {@link TenantAuthentication}.
 *
 * <h2>Why the session is resolved on every request</h2>
 *
 * <p>Because revocation has to be immediate (FR-2.5): an administrator deactivating an
 * employee mid-shift must kill the session on the employee's <em>next</em> request,
 * not whenever a token expires. The legacy
 * {@code apps/api/src/auth/session.service.ts} documented the same reasoning and did
 * the same thing — it recomputed the accessible project list from
 * {@code project_memberships} on every resolve rather than trusting anything carried
 * in the cookie. This filter preserves that behaviour exactly, including the rule that
 * archived projects stay reachable to an {@code ADMIN} so an archive is not a one-way
 * door.
 *
 * <h2>What it never does</h2>
 *
 * <ul>
 *   <li>It never throws. A missing, unknown, expired, or revoked cookie leaves the
 *       request unauthenticated, and the catch-all {@code anyRequest().authenticated()}
 *       rule produces {@code 401}. Distinguishing the four cases would be a
 *       session-enumeration oracle.</li>
 *   <li>It never logs or audits the token value. The rejected-session event records the
 *       session id when one is resolvable, and nothing else.</li>
 * </ul>
 *
 * <p>Registered <em>inside</em> the Spring Security chain, immediately after
 * {@code AnonymousAuthenticationFilter}, so a rejected cookie cannot bind a
 * {@code TenantContext}.
 */
public class BrowserSessionAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(BrowserSessionAuthenticationFilter.class);

    private final BrowserSessionStore sessions;
    private final SecurityEventPublisher events;
    private final int touchIntervalSeconds;

    public BrowserSessionAuthenticationFilter(BrowserSessionStore sessions,
                                              SecurityEventPublisher events,
                                              int touchIntervalSeconds) {
        this.sessions = sessions;
        this.events = events;
        this.touchIntervalSeconds = touchIntervalSeconds;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Optional<String> cookie = readSessionCookie(request);
        if (cookie.isPresent()) {
            authenticate(cookie.get());
        }
        chain.doFilter(request, response);
    }

    private void authenticate(String rawToken) {
        try {
            sessions.resolve(rawToken).ifPresentOrElse(session -> {
                TenantAuthentication authentication = new TenantAuthentication(session.tenantContext());
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(authentication);
                SecurityContextHolder.setContext(context);
                if (shouldTouch(session)) {
                    sessions.touch(session.sessionId());
                }
            }, () -> reject(rawToken, "unknown-or-revoked"));
        } catch (RuntimeException e) {
            // A database outage must degrade to "not authenticated", never to a
            // bypass, and must never surface a stack trace to the client.
            log.error("Session resolution failed; treating the request as unauthenticated", e);
            reject(rawToken, "store-error");
        }
    }

    /**
     * The session must also survive the request it was resolved for. An existing
     * authentication on the thread (a previous filter, a test) is replaced so a stale
     * context cannot be reused when the cookie is now invalid.
     */
    private void reject(String rawToken, String reason) {
        SecurityContextHolder.clearContext();
        TenantContextHolder.clear();
        try {
            events.publish(SecurityEvent.builder(SecurityEventType.SESSION_REJECTED)
                .resourceType("Session")
                .reason(reason)
                .ipAddress(currentIp())
                .correlationId(MDC.get("correlationId"))
                .build());
        } catch (RuntimeException e) {
            log.error("Failed to publish SESSION_REJECTED", e);
        }
    }

    private boolean shouldTouch(com.firstelement.security.session.BrowserSession session) {
        if (session.lastSeenAt() == null) {
            return true;
        }
        return session.lastSeenAt()
            .plusSeconds(touchIntervalSeconds)
            .isBefore(java.time.LocalDateTime.now());
    }

    private String currentIp() {
        return org.slf4j.MDC.get("remoteIp");
    }

    /**
     * Reads the session cookie. Invalidated cookies (empty value) are treated as
     * absent, and a non-base64url value is still passed to the store so the failure
     * path stays uniform.
     */
    private Optional<String> readSessionCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        for (Cookie cookie : cookies) {
            if (SessionTokenCodec.COOKIE_NAME.equals(cookie.getName())) {
                String value = cookie.getValue();
                if (value == null || value.isBlank()) {
                    return Optional.empty();
                }
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }

    /** Exposed for the controller so the cookie name lives in exactly one place. */
    public static String cookieName() {
        return SessionTokenCodec.COOKIE_NAME;
    }
}