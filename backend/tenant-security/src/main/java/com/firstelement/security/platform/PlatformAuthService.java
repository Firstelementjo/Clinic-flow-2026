package com.firstelement.security.platform;

import com.firstelement.security.auth.LoginRateLimiter;
import com.firstelement.security.auth.PasswordVerifier;
import com.firstelement.security.error.RateLimitedAuthenticationException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Platform operator authentication: password, then second factor, then a short elevation window.
 *
 * <h2>The shape of the handshake</h2>
 *
 * <ol>
 *   <li>{@link #beginLogin} — email and password. On success a session row is created carrying
 *       <em>no authority</em>: nothing has been confirmed as a second factor yet.</li>
 *   <li>{@link #completeLogin} — the TOTP code. The session becomes authenticated; reading is
 *       permitted.</li>
 *   <li>{@link #elevate} — with a stated reason. Writing becomes permitted, for a bounded window.</li>
 * </ol>
 *
 * <p>Authentication and authority are separate on purpose. A stolen operator cookie plus a password
 * gives an attacker the ability to read the platform; it does not give them the ability to create a
 * clinic. That is what ADR 0020 means by "just-in-time elevation rather than a standing super-admin",
 * and it is why {@link PlatformOperatorContext#permits} distinguishes the two.
 *
 * <h2>Failure is uniform</h2>
 *
 * <p>Unknown email, wrong password, wrong code and deactivated operator all produce the same
 * {@link AuthenticationFailed} and the same audit outcome. A console that distinguishes them is an
 * account-existence oracle on the highest-value credential in the product. The cost of that
 * uniformity is that {@link PasswordVerifier#burnComparison()} is spent on the unknown-email path,
 * exactly as the customer login does.
 */
public class PlatformAuthService {

    /** How long the password-only step survives before it must be restarted. */
    public static final Duration PENDING_SESSION_TTL = Duration.ofMinutes(5);

    /** How long an authenticated but unelevated session survives. */
    public static final Duration SESSION_TTL = Duration.ofHours(8);

    /**
     * How long an elevated window lasts. Short on purpose: an operator who walked away from the
     * console stops being able to write within minutes.
     */
    public static final Duration ELEVATION_WINDOW = Duration.ofMinutes(15);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final PlatformOperatorRepository operators;
    private final PlatformAuditRepository audit;
    private final PlatformSecretCodec codec;
    private final LoginRateLimiter rateLimiter;
    private final PasswordVerifier passwords = new PasswordVerifier();

    public PlatformAuthService(PlatformOperatorRepository operators, PlatformAuditRepository audit,
                               PlatformSecretCodec codec, LoginRateLimiter rateLimiter) {
        this.operators = operators;
        this.audit = audit;
        this.codec = codec;
        this.rateLimiter = rateLimiter;
    }

    /** Step one. Never returns authority, only a session that can be completed. */
    public record PendingLogin(UUID sessionId, String rawToken, Instant pendingExpiresAt) {
    }

    /** Step two. Carries the operator's capabilities and the current elevation state. */
    public record Authenticated(UUID sessionId, PlatformOperatorContext operator) {
    }

    /** Every refusal is this, so the caller cannot leak which check failed. */
    public static class AuthenticationFailed extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public AuthenticationFailed() {
            super("Authentication failed.");
        }
    }

    // ------------------------------------------------------------------
    // step 1: password
    // ------------------------------------------------------------------

    public PendingLogin beginLogin(String email, String password, String userAgent, String ipAddress,
                                   Instant now) {
        // Rate limited exactly as the customer login is, and for the same reason: this step performs a
        // cost-12 password comparison per request against the highest-value credential in the
        // product. The customer path gets a budget; before this, the platform path had none, so an
        // unauthenticated caller could loop over /api/v1/platform/auth/password and guess operator
        // passwords online, writing two audit rows and a pending session per attempt.
        //
        // Consulted BEFORE the account lookup, so flooding it cannot distinguish a known operator
        // address from an unknown one.
        LoginRateLimiter.Decision limit = rateLimiter.recordAttempt(
                ipAddress, PasswordVerifier.normalizeEmail(email));
        if (!limit.allowed()) {
            audit.record(PlatformAuditRepository.LOGIN_FAILED, null, null, null, null, "Operator", null,
                    "rate-limited", "scope=" + limit.scope(), PlatformAuditRepository.DENIED,
                    null, ipAddress);
            throw new RateLimitedAuthenticationException(limit.retryAfterSeconds());
        }

        Optional<PlatformOperatorRepository.Credentials> found =
                operators.findCredentialsByEmail(PasswordVerifier.normalizeEmail(email));

        if (found.isEmpty()) {
            // Spend the same work as a real comparison so this path cannot be timed apart from the
            // wrong-password path.
            passwords.burnComparison();
            audit.record(PlatformAuditRepository.LOGIN_FAILED, null, null, null, null, "Operator", null,
                    "unknown-email", null, PlatformAuditRepository.DENIED, null, ipAddress);
            throw new AuthenticationFailed();
        }

        PlatformOperatorRepository.Credentials credentials = found.get();
        if (!credentials.active() || !passwords.matches(password, credentials.passwordHash())) {
            audit.record(PlatformAuditRepository.LOGIN_FAILED, credentials.id(), null, null, null,
                    "Operator", null,
                    credentials.active() ? "wrong-password" : "inactive", null,
                    PlatformAuditRepository.DENIED, null, ipAddress);
            throw new AuthenticationFailed();
        }

        String rawToken = randomToken();
        UUID sessionId = operators.createPendingSession(
                credentials.id(), hash(rawToken), now.plus(PENDING_SESSION_TTL), userAgent, ipAddress);

        audit.record(PlatformAuditRepository.LOGIN, credentials.id(), sessionId, null, null,
                "Operator", credentials.id().toString(), "password-verified", null,
                PlatformAuditRepository.ALLOWED, null, ipAddress);

        return new PendingLogin(sessionId, rawToken, now.plus(PENDING_SESSION_TTL));
    }

    // ------------------------------------------------------------------
    // step 2: second factor
    // ------------------------------------------------------------------

    public Authenticated completeLogin(String rawToken, String code, Instant now) {
        // Guarded here rather than relying on the caller, for the same reason resolve() guards it:
        // a missing cookie reached hash() as null and produced a NullPointerException, which the
        // catch-all rendered as a 500 with a stack trace in the log. The contract for "no cookie"
        // is 401, and it is reachable by any client that simply omits the cookie -- which is exactly
        // what a cross-site request does when SameSite=Strict withholds it.
        if (rawToken == null || rawToken.isBlank()) {
            throw new AuthenticationFailed();
        }
        PlatformOperatorRepository.SessionRow session =
                operators.findSessionByTokenHash(hash(rawToken), now)
                        .filter(s -> !s.revoked())
                        .orElseThrow(AuthenticationFailed::new);

        if (session.mfaSatisfiedAt() != null) {
            // The handshake already completed on this token. Replaying it must not re-open the
            // elevation window, so a captured cookie cannot be used to re-elevate indefinitely.
            return new Authenticated(session.id(), contextFor(session, now));
        }

        PlatformOperatorRepository.Credentials credentials =
                credentialsOf(session.operatorId()).orElseThrow(AuthenticationFailed::new);

        if (!credentials.active()
                || !TotpVerifier.verify(codec.decrypt(credentials.totpSecretEnc()), code, now)) {
            // The pending session dies with the failure, so a correct code cannot be brute-forced
            // against it afterwards.
            operators.revokeSession(session.id());
            audit.record(PlatformAuditRepository.MFA_FAILED, credentials.id(), session.id(), null, null,
                    "Operator", credentials.id().toString(), "second-factor-rejected", null,
                    PlatformAuditRepository.DENIED, null, null);
            throw new AuthenticationFailed();
        }

        operators.markMfaSatisfied(session.id());
        operators.touchOperator(credentials.id());
        audit.record(PlatformAuditRepository.LOGIN, credentials.id(), session.id(), null, null,
                "Operator", credentials.id().toString(), "second-factor-verified", null,
                PlatformAuditRepository.ALLOWED, null, null);

        return new Authenticated(session.id(), contextFor(session, now));
    }

    // ------------------------------------------------------------------
    // step 3: elevation
    // ------------------------------------------------------------------

    /**
     * Opens the write window for an already-authenticated session.
     *
     * <p>A reason is mandatory because ADR 0020 makes every mutating action attributable, and a
     * cross-tenant write with no stated purpose is not attributable.
     */
    public PlatformOperatorContext elevate(UUID sessionId, String reason, String code, Instant now) {
        if (reason == null || reason.isBlank()) {
            throw new AuthenticationFailed();
        }
        PlatformOperatorRepository.SessionRow session =
                operators.findSessionById(sessionId).orElseThrow(AuthenticationFailed::new);

        // A pending session cannot be elevated. The database refuses it too; refusing here gives a
        // clean answer and keeps the reason in the audit trail.
        if (session.mfaSatisfiedAt() == null) {
            audit.record(PlatformAuditRepository.ELEVATION_REFUSED, session.operatorId(), sessionId,
                    null, null, "Operator", session.operatorId().toString(), "second-factor-not-satisfied",
                    null, PlatformAuditRepository.DENIED, null, null);
            throw new AuthenticationFailed();
        }

        // A fresh second factor, every time.
        //
        // Without this, the fifteen-minute window is the *only* limit on write authority, and
        // elevate() re-opens it on demand for as long as the session lives (eight hours). A cookie
        // captured after one successful handshake therefore bought permanent cross-tenant write, not
        // fifteen minutes of it -- which is the opposite of the property ADR 0020 sells, and the
        // opposite of what V20261012000000's own comment states: "the operator re-authorises with a
        // fresh second factor rather than holding a standing super-admin token".
        //
        // Re-verifying the code here, rather than trusting the earlier satisfaction, is what makes
        // the stolen-cookie argument hold: possessing the cookie is no longer sufficient, because
        // the attacker does not have the operator's authenticator.
        PlatformOperatorRepository.Credentials credentials =
                credentialsOf(session.operatorId()).orElseThrow(AuthenticationFailed::new);
        if (!credentials.active()
                || !TotpVerifier.verify(codec.decrypt(credentials.totpSecretEnc()), code, now)) {
            audit.record(PlatformAuditRepository.ELEVATION_REFUSED, session.operatorId(), sessionId,
                    null, null, "Operator", session.operatorId().toString(), "second-factor-rejected",
                    null, PlatformAuditRepository.DENIED, null, null);
            throw new AuthenticationFailed();
        }

        operators.elevate(sessionId, now.plus(ELEVATION_WINDOW), reason.trim());

        PlatformOperatorRepository.SessionRow refreshed =
                operators.findSessionById(sessionId).orElseThrow(AuthenticationFailed::new);
        PlatformOperatorContext context = contextFor(refreshed, now);

        audit.record(PlatformAuditRepository.ELEVATED, context.operatorId(), sessionId, null, null,
                "Operator", context.operatorId().toString(), reason.trim(),
                "window=" + ELEVATION_WINDOW, PlatformAuditRepository.ALLOWED, null, null);
        return context;
    }

    // ------------------------------------------------------------------
    // request-time resolution
    // ------------------------------------------------------------------

    /**
     * Resolves a platform session cookie to a context, or empty.
     *
     * <p>The elevation window is evaluated against {@code now} on every request rather than captured
     * in the cookie, so closing the window takes effect immediately instead of waiting for a token
     * to expire. A session whose second factor was never satisfied resolves to empty, so a
     * password-only cookie is not a usable credential.
     */
    public Optional<PlatformOperatorContext> resolve(String rawToken, Instant now) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        Optional<PlatformOperatorRepository.SessionRow> session =
                operators.findSessionByTokenHash(hash(rawToken), now)
                        .filter(s -> !s.revoked() && s.mfaSatisfiedAt() != null);
        if (session.isEmpty()) {
            return Optional.empty();
        }
        operators.touchSession(session.get().id());
        return Optional.of(contextFor(session.get(), now));
    }

    public void logout(UUID sessionId, PlatformOperatorContext operator) {
        operators.revokeSession(sessionId);
        if (operator != null) {
            audit.record(PlatformAuditRepository.LOGIN, operator.operatorId(), sessionId, null, null,
                    "Operator", operator.operatorId().toString(), "signed-out", null,
                    PlatformAuditRepository.ALLOWED, null, null);
        }
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    private PlatformOperatorContext contextFor(PlatformOperatorRepository.SessionRow session, Instant now) {
        PlatformOperatorRepository.Operator operator =
                operators.findById(session.operatorId()).orElseThrow(AuthenticationFailed::new);
        if (!operator.active()) {
            throw new AuthenticationFailed();
        }
        return new PlatformOperatorContext(operator.id(), operator.email(), operator.displayName(),
                session.id(), new java.util.LinkedHashSet<>(operators.capabilitiesOf(operator.id())),
                session.mfaSatisfiedAt() != null,
                session.elevatedUntil() != null && session.elevatedUntil().isAfter(now)
                        ? session.elevatedUntil() : null);
    }

    private Optional<PlatformOperatorRepository.Credentials> credentialsOf(UUID operatorId) {
        return operators.findById(operatorId)
                .flatMap(op -> operators.findCredentialsByEmail(
                        PasswordVerifier.normalizeEmail(op.email())));
    }

    public static String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JDK spec", e);
        }
    }

    private static String randomToken() {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        return HexFormat.of().formatHex(raw);
    }
}
