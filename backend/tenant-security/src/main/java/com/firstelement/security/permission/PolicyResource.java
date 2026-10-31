package com.firstelement.security.permission;

import java.util.UUID;

/**
 * The object half of an authorization decision.
 *
 * <p>A permission answers "may this principal do this kind of thing". This record
 * answers "to which thing". Both are required: an {@code EMPLOYEE} holds
 * {@code conversation:read}, so only project membership stops them from reading
 * another project's conversation.
 *
 * <p>All three fields are optional and each is enforced when present:
 *
 * <ul>
 *   <li>{@code organizationId} — the tenant boundary. No role crosses it.</li>
 *   <li>{@code projectId} — membership is required, including for an
 *       {@code ADMIN}, whose membership list covers their own organization.</li>
 *   <li>{@code ownerUserId} — self-service permissions such as
 *       {@code employee:set-own-availability}.</li>
 * </ul>
 *
 * <p>Deliberately a plain value with no client-supplied copy: callers pass ids they
 * already read from an authorized row, or omit them. Passing an id straight from a
 * request body is exactly the mistake {@code TenantQueryGuard} exists to prevent.
 */
public record PolicyResource(UUID organizationId, UUID projectId, UUID ownerUserId)
    implements java.io.Serializable {

    private static final long serialVersionUID = 1L;

    public static final PolicyResource NONE = new PolicyResource(null, null, null);

    public static PolicyResource ofOrganization(UUID organizationId) {
        return new PolicyResource(organizationId, null, null);
    }

    public static PolicyResource ofProject(UUID organizationId, UUID projectId) {
        return new PolicyResource(organizationId, projectId, null);
    }

    public static PolicyResource ofOwner(UUID ownerUserId) {
        return new PolicyResource(null, null, ownerUserId);
    }

    public PolicyResource withProject(UUID project) {
        return new PolicyResource(organizationId, project, ownerUserId);
    }

    public PolicyResource withOwner(UUID owner) {
        return new PolicyResource(organizationId, projectId, owner);
    }
}