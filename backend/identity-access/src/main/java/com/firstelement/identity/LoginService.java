package com.firstelement.identity;

import com.firstelement.security.TenantContext;
import com.firstelement.security.TenantContextHolder;
import com.firstelement.security.auth.LoginRateLimiter;
import com.firstelement.security.auth.PasswordVerifier;
import com.firstelement.security.config.SecurityProperties;
import com.firstelement.security.event.SecurityEvent;
import com.firstelement.security.event.SecurityEventPublisher;
import com.firstelement.security.event.SecurityEventType;
import com.firstelement.security.error.AuthenticationFailedException;
import com.firstelement.security.error.RateLimitedAuthenticationException;
import com.firstelement.security.session.BrowserSessionStore;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/**
 * Browser login, logout, and session revocation.
 *
 * <h2>The four properties FR-2.5 asks for</h2>
 *
 * <ol>
 *   <li><b>Constant-time password failure.</b> An unknown email costs the same bcrypt work
 *       as a wrong password ({@link PasswordVerifier#burnComparison()}), and both produce
 *       the identical {@code 401 INVALID_CREDENTIALS} body. Otherwise the login endpoint
 *       is a user-enumeration oracle. Ported unchanged from
 *       {@code burnPasswordComparison}/{@code DUMMY_PASSWORD_HASH}.</li>
 *   <li><b>Rate limiting.</b> Per address <em>and</em> per address+account, 10 attempts
 *       per 60 s by default — the legacy {@code @Throttle} value.</li>
 *   <li><b>CSRF on login.</b> Enforced by the filter chain, not here, so it cannot be
 *       forgotten by a future caller that adds another login-shaped endpoint.</li>
 *   <li><b>Immediate revocation.</b> Logout revokes the {@code sessions} row; the
 *       session filter re-reads it on the next request.</li>
 * </ol>
 *
 * <h2>What is never written anywhere</h2>
 *
 * <p>The submitted password, the password hash, the raw session token, and the email
 * address. {@code LOGIN_FAILED} carries the <em>user id</em> when the account exists and
 * an empty metadata map when it does not — so the audit row cannot be used to
 * reconstruct which emails exist.
 */
@Service
public class LoginService {

    private static final Logger log = LoggerFactory.getLogger(LoginService.class);

    private final UserRepository users;
    private final DatabaseBrowserSessionStore sessions;
    private final PasswordVerifier passwords;
    private final LoginRateLimiter rateLimiter;
    private final SecurityEventPublisher events;
    private final SecurityProperties security;

    public LoginService(UserRepository users,
                        DatabaseBrowserSessionStore sessions,
                        PasswordVerifier passwords,
                        LoginRateLimiter rateLimiter,
                        SecurityEventPublisher events,
                        SecurityProperties security) {
        this.users = users;
        this.sessions = sessions;
        this.passwords = passwords;
        this.rateLimiter = rateLimiter;
        this.events = events;
        this.security = security;
    }

    /**
     * The outcome of a successful login.
     *
     * <p>{@code rawSessionToken} is returned exactly once and is never persisted or
     * audited. {@code sessionId} is the {@code sessions.id}, which is what the security
     * event stream and an administrator's revocation reference — it is an opaque uuid, not
     * a credential.
     */
    public record LoginResult(String rawSessionToken, UUID sessionId, UserView user,
                              LocalDateTime expiresAt) {
    }

    /**
     * Verifies credentials and issues a session.
     *
     * @throws AuthenticationFailedException for every credential failure, indistinguishable
     *                                        to the caller
     * @throws RateLimitedAuthenticationException when the limiter refused the attempt
     */
    public LoginResult login(String email, String password, String userAgent, String ipAddress) {
        String normalized = PasswordVerifier.normalizeEmail(email);

        if (!PasswordVerifier.isAcceptableEmail(normalized)) {
            // Still spend the bcrypt work so a malformed address is not a fast-path probe.
            passwords.burnComparison();
            throw invalidCredentials();
        }

        LoginRateLimiter.Decision decision = rateLimiter.recordAttempt(ipAddress, normalized);
        if (!decision.allowed()) {
            publish(SecurityEvent.builder(SecurityEventType.LOGIN_RATE_LIMITED)
                .resourceType("Session")
                .reason("login-rate-limited")
                .metadata(Map.of("scope", decision.scope(), "retryAfterSeconds", decision.retryAfterSeconds()))
                .ipAddress(ipAddress)
                .correlationId(correlationId())
                .build());
            throw new RateLimitedAuthenticationException(decision.retryAfterSeconds());
        }

        Optional<UserRecord> maybeUser = users.findByEmail(normalized);
        if (maybeUser.isEmpty()) {
            // Unknown account: burn the same work, emit the same exception.
            passwords.burnComparison();
            throw invalidCredentials();
        }

        UserRecord user = maybeUser.get();
        if (!user.active() || !users.isOrganizationActive(user.organizationId())) {
            passwords.burnComparison();
            publishFailure(user, "account-inactive", ipAddress);
            throw invalidCredentials();
        }

        if (!passwords.matches(password, user.passwordHash())) {
            publishFailure(user, "bad-password", ipAddress);
            throw invalidCredentials();
        }

        BrowserSessionStore.IssuedSession issued = sessions.issue(user.id(), userAgent, ipAddress,
            security.getSessionTtlHours());
        if (issued == null) {
            throw invalidCredentials();
        }

        users.recordLogin(user.id(), LocalDateTime.now());
        rateLimiter.reset(ipAddress, normalized);

        // The project scope is recomputed by resolving the session we just issued, so
        // the returned principal is exactly what the next request will see. Building it
        // any other way could hand the client a wider scope than the filter will honour.
        TenantContext context = sessions.resolve(issued.rawToken())
            .map(com.firstelement.security.session.BrowserSession::tenantContext)
            .orElseThrow(() -> invalidCredentials());

        publish(SecurityEvent.builder(SecurityEventType.LOGIN_SUCCEEDED)
            .tenantContext(context)
            .resourceType("User")
            .resourceId(user.id().toString())
            .reason("credentials-verified")
            .ipAddress(ipAddress)
            .correlationId(correlationId())
            .build());

        return new LoginResult(issued.rawToken(), issued.sessionId(),
            UserView.from(context, user), issued.expiresAt());
    }

    /** Revokes the caller's own session. Idempotent: a second logout still succeeds. */
    public int logout(UUID sessionId, TenantContext context, String ipAddress) {
        // Scoped by the caller's own organization: a session id presented at logout that
        // belongs to another tenant is not this caller's session and must not be revoked.
        int revoked = sessions.revoke(sessionId, context.organizationId());
        publish(SecurityEvent.builder(SecurityEventType.LOGOUT)
            .tenantContext(context)
            .resourceType("Session")
            .resourceId(sessionId == null ? null : sessionId.toString())
            .reason("user-logout")
            .ipAddress(ipAddress)
            .correlationId(correlationId())
            .build());
        return revoked;
    }

    /**
     * Revokes every session of a user. Used when an administrator deactivates them: the
     * session rows die at once rather than at expiry.
     *
     * @return how many live sessions were killed
     */
    public int revokeAllForUser(TenantContext context, UUID userId, String ipAddress) {
        int revoked = sessions.revokeAllForUser(userId);
        publish(SecurityEvent.builder(SecurityEventType.SESSION_REVOKED_ALL_FOR_USER)
            .tenantContext(context)
            .resourceType("Session")
            .resourceId(userId.toString())
            .reason("user-deactivated")
            .metadata(Map.of("revokedSessions", revoked))
            .ipAddress(ipAddress)
            .correlationId(correlationId())
            .build());
        return revoked;
    }

    /** Revokes one named session on an administrator's behalf. */
    public int revokeSession(TenantContext context, UUID sessionId, String ipAddress) {
        // The scope was available in `context` all along and was used only for the audit
        // event; the write itself went out unscoped. An administrator's authority is over
        // their own organization, so the session must belong to it.
        int revoked = sessions.revoke(sessionId, context.organizationId());
        publish(SecurityEvent.builder(SecurityEventType.SESSION_REVOKED)
            .tenantContext(context)
            .resourceType("Session")
            .resourceId(sessionId == null ? null : sessionId.toString())
            .reason("revoked-by-administrator")
            .ipAddress(ipAddress)
            .correlationId(correlationId())
            .build());
        return revoked;
    }

    public DatabaseBrowserSessionStore.SessionSummary[] liveSessions(UUID userId) {
        return sessions.listForUser(userId).toArray(new DatabaseBrowserSessionStore.SessionSummary[0]);
    }

    // ------------------------------------------------------------------

    private void publishFailure(UserRecord user, String reason, String ipAddress) {
        // organizationId comes from the users row the server already read, never from the
        // request: audit_logs."organizationId" is NOT NULL and there is no session yet.
        publish(SecurityEvent.builder(SecurityEventType.LOGIN_FAILED)
            .actorUserId(user.id())
            .organizationId(user.organizationId())
            .resourceType("User")
            .resourceId(user.id().toString())
            .reason(reason)
            .ipAddress(ipAddress)
            .correlationId(correlationId())
            .build());
    }

    private void publish(SecurityEvent event) {
        try {
            events.publish(event);
        } catch (RuntimeException e) {
            log.error("Failed to publish a login security event", e);
        }
    }

    private String correlationId() {
        return MDC.get("correlationId");
    }

    /** The one failure the login endpoint reveals, for every wrong input. */
    static com.firstelement.security.error.AuthenticationFailedException invalidCredentials() {
        return new com.firstelement.security.error.AuthenticationFailedException();
    }
}