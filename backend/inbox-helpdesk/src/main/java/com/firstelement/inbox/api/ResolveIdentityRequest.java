package com.firstelement.inbox.api;

import java.util.Map;
import java.util.UUID;

/**
 * {@code POST /api/v1/customers/resolve-identity} body.
 *
 * <p>{@code projectId} is a request: the service validates it against the caller's
 * server-resolved scope. There is deliberately no {@code organizationId} field — the tenant
 * always comes from the authenticated principal.
 */
public record ResolveIdentityRequest(UUID projectId,
                                     String platform,
                                     String externalId,
                                     String displayName,
                                     String phone,
                                     String email,
                                     String locale,
                                     Map<String, Object> rawProfile) {
}