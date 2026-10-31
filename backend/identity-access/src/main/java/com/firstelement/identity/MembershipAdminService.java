package com.firstelement.identity;

import com.firstelement.security.TenantContext;
import com.firstelement.security.TenantContextHolder;
import com.firstelement.security.annotation.RequirePermission;
import com.firstelement.security.error.PolicyDeniedException;
import com.firstelement.security.event.SecurityEvent;
import com.firstelement.security.event.SecurityEventPublisher;
import com.firstelement.security.event.SecurityEventType;
import com.firstelement.security.permission.AuthorizationService;
import com.firstelement.security.permission.Permission;
import com.firstelement.security.permission.PolicyResource;
import com.firstelement.security.query.TenantQueryGuard;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Role and membership changes, and the session revocations they imply.
 *
 * <h2>Every command here is privileged, audited, and revocation-aware</h2>
 *
 * <ul>
 *   <li>{@code USER_MANAGE} for role changes and activation state.</li>
 *   <li>{@code MEMBERSHIP_MANAGE} for project memberships.</li>
 *   <li>Both are {@code ADMIN}-only grants; no machine principal holds either, so a
 *       worker can never escalate an account.</li>
 * </ul>
 *
 * <h2>Revocation is part of the change, not a follow-up</h2>
 *
 * <p>Changing a role or deactivating a user revokes every live session in the same call.
 * Without that, an employee demoted to {@code EMPLOYEE} keeps their {@code ADMIN} reach
 * until their cookie expires — which is precisely the "immediate session revocation"
 * requirement failing in the most damaging direction. It also means the next request
 * re-resolves the scope from the new role rather than from anything cached on the client.
 *
 * <h2>A self-lockout is refused</h2>
 *
 * <p>Demoting or deactivating the caller is rejected. Losing the last administrator of a
 * tenant is a one-way door, and an authenticated request is the only place that can notice
 * it cheaply.
 */
@Service
@Transactional
public class MembershipAdminService {

    private static final Logger log = LoggerFactory.getLogger(MembershipAdminService.class);

    private final UserRepository users;
    private final ProjectMembershipRepository memberships;
    private final LoginService login;
    private final TenantQueryGuard guard;
    private final AuthorizationService authorization;
    private final SecurityEventPublisher events;

    public MembershipAdminService(UserRepository users,
                                  ProjectMembershipRepository memberships,
                                  LoginService login,
                                  TenantQueryGuard guard,
                                  AuthorizationService authorization,
                                  SecurityEventPublisher events) {
        this.users = users;
        this.memberships = memberships;
        this.login = login;
        this.guard = guard;
        this.authorization = authorization;
        this.events = events;
    }

    /** Result of a membership change, with the effect made explicit. */
    public record MembershipChange(UUID userId, UUID projectId, boolean added, int sessionsRevoked) {
    }

    @RequirePermission(Permission.USER_MANAGE)
    public int changeRole(UUID targetUserId, com.firstelement.security.UserRole newRole, String ipAddress) {
        TenantContext caller = TenantContextHolder.get();
        authorization.check(caller, Permission.USER_MANAGE, PolicyResource.NONE);
        if (targetUserId.equals(caller.userId())) {
            throw new org.springframework.security.access.AccessDeniedException("Access denied");
        }
        var before = users.findInScope(guard, caller, targetUserId)
            .orElseThrow(() -> PolicyDeniedException.tenantOutOfScope("user-out-of-scope",
                caller.organizationId(), targetUserId));
        if (before.role() == newRole) {
            return 0;
        }
        int changed = users.changeRole(guard, caller, targetUserId, newRole);
        if (changed == 0) {
            throw PolicyDeniedException.tenantOutOfScope("user-out-of-scope",
                caller.organizationId(), targetUserId);
        }
        // The old role's reach must not survive the change.
        int revoked = login.revokeAllForUser(caller, targetUserId, ipAddress);
        publish(SecurityEvent.builder(SecurityEventType.ROLE_CHANGED)
            .tenantContext(caller)
            .resourceType("User")
            .resourceId(targetUserId.toString())
            .reason("role-changed")
            .metadata(Map.of("from", before.role().value(), "to", newRole.value(),
                "sessionsRevoked", revoked))
            .ipAddress(ipAddress)
            .correlationId(correlationId())
            .build());
        log.info("role changed user={} {} -> {} sessionsRevoked={}",
            targetUserId, before.role(), newRole, revoked);
        return revoked;
    }

    /** Deactivates a user and kills every live session immediately. */
    @RequirePermission(Permission.USER_MANAGE)
    public int deactivate(UUID targetUserId, String ipAddress) {
        TenantContext caller = TenantContextHolder.get();
        authorization.check(caller, Permission.USER_MANAGE, PolicyResource.NONE);
        if (targetUserId.equals(caller.userId())) {
            throw new org.springframework.security.access.AccessDeniedException("Access denied");
        }
        int changed = users.setActive(guard, caller, targetUserId, false);
        if (changed == 0) {
            throw PolicyDeniedException.tenantOutOfScope("user-out-of-scope",
                caller.organizationId(), targetUserId);
        }
        int revoked = login.revokeAllForUser(caller, targetUserId, ipAddress);
        publish(SecurityEvent.builder(SecurityEventType.USER_DEACTIVATED)
            .tenantContext(caller)
            .resourceType("User")
            .resourceId(targetUserId.toString())
            .reason("user-deactivated")
            .metadata(Map.of("sessionsRevoked", revoked))
            .ipAddress(ipAddress)
            .correlationId(correlationId())
            .build());
        return revoked;
    }

    @RequirePermission(Permission.MEMBERSHIP_MANAGE)
    public MembershipChange addMembership(UUID targetUserId, UUID projectId, String ipAddress) {
        TenantContext caller = TenantContextHolder.get();
        authorization.check(caller, Permission.MEMBERSHIP_MANAGE, PolicyResource.NONE);
        requireInScope(caller, targetUserId, projectId);

        int inserted = memberships.addMembership(guard, caller, targetUserId, projectId);
        // A membership change alters the reachable project set, so the existing sessions
        // must re-resolve it. Revoking makes that immediate.
        int revoked = login.revokeAllForUser(caller, targetUserId, ipAddress);
        publishMembership(caller, targetUserId, projectId, true, revoked, ipAddress);
        return new MembershipChange(targetUserId, projectId, true, revoked);
    }

    @RequirePermission(Permission.MEMBERSHIP_MANAGE)
    public MembershipChange removeMembership(UUID targetUserId, UUID projectId, String ipAddress) {
        TenantContext caller = TenantContextHolder.get();
        authorization.check(caller, Permission.MEMBERSHIP_MANAGE, PolicyResource.NONE);
        requireInScope(caller, targetUserId, projectId);

        int removed = memberships.removeMembership(guard, caller, targetUserId, projectId);
        int revoked = login.revokeAllForUser(caller, targetUserId, ipAddress);
        publishMembership(caller, targetUserId, projectId, false, revoked, ipAddress);
        return new MembershipChange(targetUserId, projectId, false, revoked);
    }

    /** Revokes one named session on an administrator's behalf. */
    @RequirePermission(Permission.SESSION_REVOKE)
    public int revokeSession(UUID sessionId, String ipAddress) {
        TenantContext caller = TenantContextHolder.get();
        authorization.check(caller, Permission.SESSION_REVOKE, PolicyResource.NONE);
        return login.revokeSession(caller, sessionId, ipAddress);
    }

    // ------------------------------------------------------------------

    /**
     * Both the user and the project must be inside the caller's organization.
     *
     * <p>Checked before the write, not by the statement: a project id from another tenant
     * must produce the same denial as a nonexistent one, and the {@code INSERT ... SELECT
     * FROM projects WHERE organizationId = ...} form would otherwise silently insert
     * nothing and report a confusing "already a member".
     */
    private void requireInScope(TenantContext caller, UUID targetUserId, UUID projectId) {
        if (users.findInScope(guard, caller, targetUserId).isEmpty()) {
            throw PolicyDeniedException.tenantOutOfScope("user-out-of-scope",
                caller.organizationId(), targetUserId);
        }
        if (memberships.findProjectInOrganization(caller.organizationId(), projectId).isEmpty()) {
            throw PolicyDeniedException.projectOutOfScope("project-out-of-scope",
                caller.organizationId(), projectId);
        }
    }

    private void publishMembership(TenantContext caller, UUID userId, UUID projectId,
                                  boolean added, int revoked, String ipAddress) {
        publish(SecurityEvent.builder(SecurityEventType.MEMBERSHIP_CHANGED)
            .tenantContext(caller)
            .resourceType("ProjectMembership")
            .resourceId(userId + ":" + projectId)
            .reason(added ? "membership-added" : "membership-removed")
            .metadata(Map.of("userId", userId.toString(), "projectId", projectId.toString(),
                "sessionsRevoked", revoked))
            .ipAddress(ipAddress)
            .correlationId(correlationId())
            .build());
    }

    private void publish(SecurityEvent event) {
        try {
            events.publish(event);
        } catch (RuntimeException e) {
            log.error("Failed to publish an identity-change security event", e);
        }
    }

    private String correlationId() {
        return MDC.get("correlationId");
    }
}