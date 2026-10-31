package com.firstelement.identity.permission;

import com.firstelement.identity.ProjectMembershipRepository;
import com.firstelement.identity.UserRepository;
import com.firstelement.identity.UserView;
import com.firstelement.security.TenantContext;
import com.firstelement.security.permission.AuthorizationService;
import com.firstelement.security.permission.Permission;
import com.firstelement.security.permission.PolicyResource;
import com.firstelement.security.query.TenantQueryGuard;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Admin read model for "what can this person actually do?".
 *
 * <h2>Why this is not a role lookup</h2>
 *
 * <p>PROJECT_REQUIREMENTS §6 lists "effective permissions" as a required Admin surface,
 * and the admin control scenario in §8 is explicit: an operator "inspects effective
 * permissions for an employee". A role name answers none of the questions an operator
 * actually has — an employee belongs to two projects, so "can they read this customer's
 * phone number" depends on the project. This service returns the grant set <em>and</em>
 * the project scope, so the answer is complete.
 *
 * <h2>Tenancy</h2>
 *
 * <p>The target user must be inside the caller's organization. A user id from another
 * tenant produces the same non-disclosing denial as a nonexistent id
 * ({@code PolicyDeniedException}, rendered as {@code 404}). There is no path here that
 * resolves a user across tenants.
 *
 * <h2>What is never included</h2>
 *
 * <p>No password hash, no session token, no email address of another tenant, no connector
 * credential. The projection is {@link UserView}, which cannot carry a hash at all.
 */
@Service
@Transactional(readOnly = true)
public class EffectivePermissionService {

    private final UserRepository users;
    private final ProjectMembershipRepository memberships;
    private final TenantQueryGuard guard;
    private final AuthorizationService authorization;
    private final com.firstelement.security.event.SecurityEventPublisher events;

    public EffectivePermissionService(UserRepository users,
                                      ProjectMembershipRepository memberships,
                                      TenantQueryGuard guard,
                                      AuthorizationService authorization,
                                      com.firstelement.security.event.SecurityEventPublisher events) {
        this.users = users;
        this.memberships = memberships;
        this.guard = guard;
        this.authorization = authorization;
        this.events = events == null ? event -> { } : events;
    }

    /** The full inspector payload for one user, as seen from {@code caller}. */
    public PermissionInspection inspect(TenantContext caller, UUID targetUserId) {
        var target = users.findInScope(guard, caller, targetUserId)
            .orElseGet(() -> {
                // A cross-tenant user id and a nonexistent one produce the same denial, so
                // the reason recorded here is deliberately the same for both. The event
                // exists so an operator sees the probe; it carries the requested id, which
                // is the caller's own input.
                publish(caller, targetUserId);
                throw com.firstelement.security.error.PolicyDeniedException.tenantOutOfScope(
                    "user-out-of-scope", caller.organizationId(), targetUserId);
            });

        List<UUID> projectIds = memberships.resolveProjectIds(target.organizationId(),
            target.id(), target.role());

        TenantContext targetContext = TenantContext.forUser(
            caller.sessionId() == null ? UUID.randomUUID() : caller.sessionId(),
            target.id(), target.organizationId(), target.role(), projectIds);

        Set<Permission> granted = authorization.effectivePermissions(targetContext);

        List<ProjectSummary> projects = new ArrayList<>();
        for (UUID projectId : projectIds) {
            memberships.isArchived(target.organizationId(), projectId)
                .ifPresent(archived -> projects.add(new ProjectSummary(projectId, archived)));
        }

        return new PermissionInspection(
            UserView.from(targetContext, target),
            target.role().value(),
            target.active(),
            projectIds,
            projects,
            wireValues(granted),
            missingFrom(granted));
    }

    /**
     * The inspector's own subject: the caller's effective permissions.
     *
     * <p>Cheap, needs no database read, and available to any authenticated caller, so the
     * SPA can hide a control it knows will be refused. Hiding a control is UX; the server
     * remains the boundary.
     */
    public PermissionInspection inspectSelf(TenantContext caller) {
        Set<Permission> granted = authorization.effectivePermissions(caller);
        return new PermissionInspection(
            new UserView(caller.userId(), caller.organizationId(), null, null, caller.role(),
                null, true, null, List.of()),
            caller.role().value(),
            true,
            List.copyOf(caller.projectIds()),
            List.of(),
            wireValues(granted),
            missingFrom(granted));
    }

    /** Wire values, sorted, so two calls produce identical output. */
    private static List<String> wireValues(Set<Permission> permissions) {
        return permissions.stream().map(Permission::value).sorted().toList();
    }

    /**
     * Catalogue entries the principal does not hold.
     *
     * <p>Derived as catalogue-minus-granted, which is exactly the question an operator is
     * asking. The legacy catalogue does not cover every module — the inventory records
     * the clinic module shipping with no permission decorators at all — so this list is
     * "not granted", not "does not apply".
     */
    private static List<String> missingFrom(Set<Permission> granted) {
        List<String> missing = new ArrayList<>();
        for (Permission permission : Permission.values()) {
            if (!granted.contains(permission)) {
                missing.add(permission.value());
            }
        }
        missing.sort(String::compareTo);
        return List.copyOf(missing);
    }

    /** A project plus whether it is archived, so an operator can see why an admin keeps it. */
    public record ProjectSummary(UUID projectId, boolean archived) {
    }

    /**
     * The Admin read model.
     *
     * @param subject          the inspected user
     * @param role             their role
     * @param active           whether they may authenticate at all
     * @param projectIds       the server-resolved project scope
     * @param projects         per-project detail, including archived state
     * @param effectivePermissions sorted wire values they actually hold
     * @param missingPermissions   catalogue entries they do not hold
     */
    public record PermissionInspection(UserView subject,
                                       String role,
                                       boolean active,
                                       List<UUID> projectIds,
                                       List<ProjectSummary> projects,
                                       List<String> effectivePermissions,
                                       List<String> missingPermissions) {

        /** Convenience for tests and the permission matrix documentation. */
        public Set<String> effective() {
            return Set.copyOf(effectivePermissions);
        }
    }

    /** Kept for the caller that wants a per-project answer. */
    public Set<Permission> effectiveFor(TenantContext caller, UUID projectId) {
        return authorization.effectivePermissions(caller,
            PolicyResource.ofProject(caller.organizationId(), projectId));
    }

    private void publish(TenantContext caller, UUID requestedUserId) {
        try {
            events.publish(com.firstelement.security.event.SecurityEvent
                .denial(com.firstelement.security.event.SecurityEventType.AUTHORIZATION_DENIED,
                    caller, "User", "user-out-of-scope",
                    java.util.Map.of("surface", "permission-inspector",
                        "requestedUserId", requestedUserId.toString())));
        } catch (RuntimeException e) {
            // Best-effort: an audit failure must not change the authorization decision.
            org.slf4j.LoggerFactory.getLogger(EffectivePermissionService.class)
                .error("Failed to publish AUTHORIZATION_DENIED for the permission inspector", e);
        }
    }
}