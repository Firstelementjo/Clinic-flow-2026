package com.firstelement.security.session;

import java.util.Optional;
import java.util.UUID;

/**
 * The revocable server-side session store.
 *
 * <p>ADR-0001: opaque session tokens, not JWTs. "Opaque" and "only the SHA-256 is
 * stored" are not decoration — they are what make
 * {@link #revoke(UUID)} and {@link #revokeAllForUser(UUID)} take effect immediately
 * rather than whenever a token happens to expire. A stateless token cannot give
 * immediate revocation without a denylist that is a session table with extra steps.
 *
 * <p>A port, so {@code :backend:tenant-security} owns no persistence. The JDBC
 * implementation over the legacy {@code sessions} table lives in
 * {@code :backend:identity-access} and is the behavioural successor of
 * {@code apps/api/src/auth/session.service.ts}.
 *
 * <h2>Implementations must</h2>
 *
 * <ul>
 *   <li>Verify {@code revokedAt IS NULL} and {@code expiresAt > now()} on
 *       <b>every</b> resolve — that is what makes revocation immediate.</li>
 *   <li>Recompute the accessible project list server-side on every resolve.</li>
 *   <li>Return {@link Optional#empty()} rather than throwing for an unknown,
 *       expired, or revoked token, and never distinguish the three in the thrown
 *       type, timing, or log line.</li>
 * </ul>
 */
public interface BrowserSessionStore {

    /**
     * Issues a new session and returns the raw token exactly once.
     *
     * @param ttlHours session lifetime, from {@code firstelement.security.session-ttl-hours}
     * @return the raw cookie token; it is never persisted and never recoverable, or
     *         {@code null} when the user no longer exists
     */
    IssuedSession issue(UUID userId, String userAgent, String ipAddress, int ttlHours);

    /**
     * Resolves a raw cookie token to a live session and its recomputed scope.
     * Empty means "not usable" — for any reason.
     */
    Optional<BrowserSession> resolve(String rawToken);

    /** Best-effort {@code lastSeenAt} update. Implementations may throttle. */
    void touch(UUID sessionId);

    /**
     * Immediate revocation. A subsequent {@link #resolve} of this session's token must
     * return {@link Optional#empty()}.
     *
     * <p><b>The organization is a required argument, not an optimisation.</b> A session id
     * is a bearer-grade secret that an administrator names on purpose, so scoping it only
     * by id would let any tenant's administrator revoke any other tenant's session. The
     * scope comes from the caller's server-derived {@code TenantContext}, never from the
     * request. Making it part of the signature means a future caller cannot forget it: the
     * compiler refuses.
     *
     * <p>A session belonging to another organization is simply not a row this statement
     * matches, so it returns 0 — the same answer a nonexistent id gives. That is the
     * non-disclosure property the rest of the codebase holds to: a foreign id and an
     * invented one are indistinguishable, so the return value cannot be used to probe for
     * another tenant's session ids.
     *
     * @param organizationId the caller's organization; the row must belong to it
     * @return the number of sessions revoked (0 when already revoked, or not in scope)
     */
    int revoke(UUID sessionId, UUID organizationId);

    /** Used when an administrator deactivates a user: every session dies at once. */
    int revokeAllForUser(UUID userId);

    /** Issued token plus the ids needed for an audit row. Never logs the token. */
    record IssuedSession(String rawToken, UUID sessionId, java.time.LocalDateTime expiresAt) {
    }
}