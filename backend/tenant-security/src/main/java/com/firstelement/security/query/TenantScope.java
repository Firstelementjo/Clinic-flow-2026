package com.firstelement.security.query;

import java.util.UUID;

/**
 * The authoritative tenant/project filter a query must apply.
 *
 * <p>Produced only by {@link TenantQueryGuard}, never built from request input. It is
 * the bridge between "what the caller asked for" and "what the caller may see", and
 * the difference between the two is where every tenant-isolation bug lives.
 *
 * @param organizationId always present; from the authenticated principal's own
 *                       {@code users."organizationId"}
 * @param projectId      {@code null} means "every project this principal may reach in
 *                       this tenant", which is already narrowed by the guard to the
 *                       principal's server-resolved {@code projectIds}
 */
public record TenantScope(UUID organizationId, UUID projectId) {

    public TenantScope {
        if (organizationId == null) {
            throw new IllegalArgumentException("TenantScope.organizationId must not be null");
        }
        if (projectId != null && projectId.equals(organizationId)) {
            throw new IllegalArgumentException(
                "TenantScope was given an organizationId as a projectId; scopes are not interchangeable");
        }
    }

    /** True when the scope names exactly one project. */
    public boolean isProjectScoped() {
        return projectId != null;
    }
}