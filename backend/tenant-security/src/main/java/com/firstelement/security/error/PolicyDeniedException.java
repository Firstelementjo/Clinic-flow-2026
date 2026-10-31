package com.firstelement.security.error;

import com.firstelement.security.permission.Permission;
import java.util.UUID;

/**
 * Denial because the target row, project, or tenant is outside the caller's scope.
 *
 * <h2>Why this is a 404 and not a 403</h2>
 *
 * <p>PROJECT_REQUIREMENTS FR-2.7 / §6: "Authorization failures must not disclose
 * the existence of another tenant's objects" and "problem details that never reveal
 * cross-tenant existence".
 *
 * <p>If a cross-tenant read returned {@code 403} while a nonexistent id returned
 * {@code 404}, the status code alone is an oracle: an attacker could enumerate
 * every customer id in the system by watching for 403. So this denial is reported
 * as {@code NOT_FOUND} with exactly the body a genuinely missing id produces. The
 * real reason is recorded in the security event stream, where operators can see it
 * and attackers cannot.
 *
 * <p>A capability/role denial with no object to leak is a different thing and is
 * reported as {@code 403 ACCESS_DENIED} by
 * {@link com.firstelement.security.error.SecurityExceptionAdvice}.
 */
public class PolicyDeniedException extends org.springframework.security.access.AccessDeniedException {

    private static final long serialVersionUID = 1L;

    private final String reason;
    private final UUID organizationId;
    private final UUID projectId;
    private final transient Permission permission;

    public PolicyDeniedException(String reason,
                                 UUID organizationId,
                                 UUID projectId,
                                 Permission permission) {
        // Deliberately carries no tenant, project, role, or permission detail in the
        // message: the exception may be rendered into a response body.
        super("Access denied");
        this.reason = reason;
        this.organizationId = organizationId;
        this.projectId = projectId;
        this.permission = permission;
    }

    public static PolicyDeniedException projectOutOfScope(String reason, UUID organizationId, UUID projectId) {
        return new PolicyDeniedException(reason, organizationId, projectId, null);
    }

    public static PolicyDeniedException tenantOutOfScope(String reason, UUID organizationId, UUID requested) {
        return new PolicyDeniedException(reason, organizationId, requested, null);
    }

    /** Internal, non-disclosing classification recorded on the security event. */
    public String reason() {
        return reason;
    }

    public UUID organizationId() {
        return organizationId;
    }

    public UUID projectId() {
        return projectId;
    }

    public Permission permission() {
        return permission;
    }
}