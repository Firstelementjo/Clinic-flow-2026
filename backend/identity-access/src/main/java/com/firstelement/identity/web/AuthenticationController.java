package com.firstelement.identity.web;

import com.firstelement.config.FirstElementProperties;
import com.firstelement.identity.LoginService;
import com.firstelement.identity.UserRepository;
import com.firstelement.identity.UserView;
import com.firstelement.security.TenantContext;
import com.firstelement.security.TenantContextHolder;
import com.firstelement.security.config.SecurityProperties;
import com.firstelement.security.query.TenantQueryGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/auth} — the browser credential surface.
 *
 * <h2>Cookie contract (ADR-0001, unchanged from the legacy runtime)</h2>
 *
 * <table>
 *   <caption>Session cookie attributes</caption>
 *   <tr><td>name</td><td>{@code fe_session} — the legacy {@code SESSION_COOKIE_NAME}</td></tr>
 *   <tr><td>{@code HttpOnly}</td><td>{@code true}. The token is never readable from JS, so
 *       an XSS cannot exfiltrate it.</td></tr>
 *   <tr><td>{@code Secure}</td><td>{@code true} in production, and forced rather than
 *       merely defaulted: the configuration refuses to start otherwise.</td></tr>
 *   <tr><td>{@code SameSite}</td><td>{@code Lax}, matching the legacy cookie and its
 *       comment — a bookmarked top-level navigation still works, cross-site POSTs do
 *       not.</td></tr>
 *   <tr><td>{@code Path}</td><td>{@code /}</td></tr>
 *   <tr><td>{@code Max-Age}</td><td>{@code session-ttl-hours} × 3600. The real boundary is
 *       the server-side {@code expiresAt}, re-checked on every request.</td></tr>
 * </table>
 *
 * <h2>There is no second browser credential path</h2>
 *
 * <p>No endpoint returns the raw token in a body and none accepts one in a header. The
 * optional OAuth2 resource-server chain in {@code :backend:tenant-security} is confined
 * to {@code /api/v1/integrations/**} and rejects a {@code USER} principal type outright.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthenticationController {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationController.class);

    private final LoginService login;
    private final UserRepository users;
    private final TenantQueryGuard guard;
    private final SecurityProperties security;
    private final FirstElementProperties app;

    public AuthenticationController(LoginService login,
                                    UserRepository users,
                                    TenantQueryGuard guard,
                                    SecurityProperties security,
                                    FirstElementProperties app) {
        this.login = login;
        this.users = users;
        this.guard = guard;
        this.security = security;
        this.app = app;
    }

    /** Login input. The password is never echoed back, logged, or audited. */
    public record LoginRequest(@Email @Size(max = 320) String email,
                               @NotBlank @Size(max = 200) String password) {
    }

    /** Session view. Carries the server-resolved scope so the SPA never has to guess. */
    public record SessionResponse(UUID userId,
                                  UUID organizationId,
                                  String email,
                                  String fullName,
                                  String role,
                                  String locale,
                                  boolean active,
                                  List<UUID> projectIds,
                                  String csrfHeaderName) {

        static SessionResponse of(UserView user, List<UUID> projectIds, String csrfHeader) {
            return new SessionResponse(user.id(), user.organizationId(), user.email(),
                user.fullName(), user.role().value(), user.locale(), user.active(),
                projectIds, csrfHeader);
        }
    }

    /**
     * {@code POST /api/v1/auth/login}
     *
     * <p>CSRF-protected by the filter chain (login-CSRF), rate limited, constant-time on
     * failure, audited on both outcomes.
     */
    @PostMapping("/login")
    public ResponseEntity<SessionResponse> login(@Valid @RequestBody LoginRequest body,
                                                HttpServletRequest request) {
        LoginService.LoginResult result = login.login(body.email(), body.password(),
            request.getHeader("User-Agent"), clientIp(request));

        ResponseCookie cookie = sessionCookie(result.rawSessionToken(),
            Duration.ofHours(security.getSessionTtlHours()));
        log.info("login succeeded principal={} correlationId={}",
            result.user().id(), MDC.get("correlationId"));
        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, cookie.toString())
            .body(SessionResponse.of(result.user(), result.user().projectIds(),
                security.getCsrfHeaderName()));
    }

    /**
     * {@code POST /api/v1/auth/logout}
     *
     * <p>Revokes the server-side row and clears the cookie. Idempotent: logging out twice
     * succeeds because the desired end state is already true.
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        TenantContext context = TenantContextHolder.get();
        login.logout(context.sessionId(), context, clientIp(request));
        return ResponseEntity.noContent()
            .header(HttpHeaders.SET_COOKIE, sessionCookie("", Duration.ZERO).toString())
            .build();
    }

    /**
     * {@code GET /api/v1/auth/me}
     *
     * <p>Reachable only with a live session; the catch-all rule denies it otherwise. The
     * {@code projectIds} returned are the ones the session filter computed on this very
     * request, so a membership revoked a second ago is already gone from the response.
     */
    @GetMapping("/me")
    public ResponseEntity<SessionResponse> me() {
        TenantContext context = TenantContextHolder.get();
        UserView view = users.findInScope(guard, context, context.userId())
            .map(user -> UserView.from(context, user))
            .orElseThrow(() -> new org.springframework.security.access.AccessDeniedException(
                "Access denied"));
        return ResponseEntity.ok(SessionResponse.of(view,
            List.copyOf(context.projectIds()), security.getCsrfHeaderName()));
    }

    /**
     * {@code GET /api/v1/auth/csrf}
     *
     * <p>Public, so a client can obtain a token before its first mutation — in particular
     * before {@code POST /api/v1/auth/login}, which is CSRF-protected on purpose. A token
     * is not a credential: without the session cookie it authorizes nothing.
     */
    @GetMapping("/csrf")
    public ResponseEntity<Map<String, String>> csrf(CsrfToken token) {
        return ResponseEntity.ok(Map.of(
            "headerName", token.getHeaderName(),
            "parameterName", token.getParameterName(),
            "token", token.getToken()));
    }

    // ------------------------------------------------------------------

    private ResponseCookie sessionCookie(String value, Duration maxAge) {
        return ResponseCookie.from(security.getSessionCookieName(), value)
            .httpOnly(true)
            .secure(cookieSecure())
            .sameSite(security.getSessionCookieSameSite().header())
            .path("/")
            .maxAge(maxAge)
            .build();
    }

    private boolean cookieSecure() {
        if (security.getSessionCookieSameSite().requiresSecureCookie()) {
            return true;
        }
        if (security.getSessionCookieSecure() != null) {
            return security.getSessionCookieSecure();
        }
        return app.getEnvironment() == FirstElementProperties.Environment.PRODUCTION;
    }

    /**
     * The client address for the audit row.
     *
     * <p>{@code X-Forwarded-For} is read because Spring's forwarded-header strategy has
     * already been configured; the value is an audit attribute only and never feeds an
     * authorization decision.
     */
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}