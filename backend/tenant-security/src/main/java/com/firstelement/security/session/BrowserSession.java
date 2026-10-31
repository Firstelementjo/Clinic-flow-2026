package com.firstelement.security.session;

import com.firstelement.security.TenantContext;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A live server-side browser session, as resolved from the {@code sessions} table.
 *
 * <p>Only the {@code tokenHash} ever reaches this object from storage — the raw
 * cookie value exists only on the inbound request and in the response that sets it.
 * The record therefore has no field that could leak a credential, which is why it is
 * safe to place in a security event's metadata.
 *
 * @param sessionId     {@code sessions.id}
 * @param tenantContext the scope recomputed from {@code users} and
 *                      {@code project_memberships} on <em>this</em> request. Never
 *                      cached in the cookie, so revoking a membership takes effect
 *                      on the next request.
 * @param userAgent     recorded at issue time for the Admin session list
 * @param ipAddress     recorded at issue time for the Admin session list
 * @param issuedAt      {@code sessions."createdAt"}
 * @param lastSeenAt    {@code sessions."lastSeenAt"}
 * @param expiresAt     {@code sessions."expiresAt"}
 */
public record BrowserSession(
        UUID sessionId,
        TenantContext tenantContext,
        String userAgent,
        String ipAddress,
        LocalDateTime issuedAt,
        LocalDateTime lastSeenAt,
        LocalDateTime expiresAt
) {
}