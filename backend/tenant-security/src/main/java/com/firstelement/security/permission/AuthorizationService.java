package com.firstelement.security.permission;

import com.firstelement.security.TenantContext;
import com.firstelement.security.error.PolicyDeniedException;
import com.firstelement.security.event.SecurityEvent;
import com.firstelement.security.event.SecurityEventPublisher;
import com.firstelement.security.event.SecurityEventType;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/**
 * The authorization decision, in one place.
 *
 * <p>plan.md §0.4 forbids controller-only authorization. This service is the thing
 * services, repositories, jobs, exports, realtime subscription handlers and AI tool
 * dispatchers call; controllers call it too, but they are never the only caller.
 *
 * <h2>The decision, in order</h2>
 *
 * <ol>
 *   <li>Is the permission granted at all, for this principal's grant model? Role for
 *       {@code USER}, capability for everything else. No role on a machine, no
 *       capability on a human.</li>
 *   <li><b>Tenant boundary.</b> A supplied {@code organizationId} that differs from
 *       the principal's own is denied. No role and no capability crosses this.</li>
 *   <li><b>Project boundary.</b> A supplied {@code projectId} must be in the
 *       server-resolved {@code projectIds}. An {@code ADMIN} is not exempt: their
 *       project list is every project of their own organization and nothing more.</li>
 *   <li><b>Owner boundary.</b> Self-service permissions are additionally limited to
 *       the owner, with {@code ADMIN} override for the operational ones.</li>
 * </ol>
 *
 * <h2>Two denials, two shapes</h2>
 *
 * <ul>
 *   <li>{@link #check(TenantContext, Permission)} — no object involved, so the
 *       answer is {@code 403 ACCESS_DENIED}.</li>
 *   <li>{@link #check(TenantContext, Permission, PolicyResource)} — an object is
 *       involved. When the target is outside the tenant/project boundary the caller
 *       receives {@link PolicyDeniedException}, which the web layer renders as
 *       {@code 404} identical to a missing row so existence is never disclosed.</li>
 * </ul>
 *
 * <h2>Auditing</h2>
 *
 * <p>Every denial is published to the security stream. Publication is best-effort
 * and never throws, so a failing audit insert cannot be turned into a way to make
 * legitimate traffic fail.
 */
@Service
public class AuthorizationService {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationService.class);

    /** Self-service permissions: the only ones whose meaning includes "my own record". */
    private static final Set<Permission> OWNER_SCOPED = Set.of(Permission.EMPLOYEE_SET_OWN_AVAILABILITY);

    private final SecurityEventPublisher events;

    public AuthorizationService(SecurityEventPublisher events) {
        this.events = events == null ? event -> { } : events;
    }

    // ------------------------------------------------------------------
    // role / capability only
    // ------------------------------------------------------------------

    public boolean allows(TenantContext context, Permission permission) {
        return context != null && granted(context).contains(permission);
    }

    public void check(TenantContext context, Permission permission) {
        if (!allows(context, permission)) {
            throw denyRole(context, permission);
        }
    }

    // ------------------------------------------------------------------
    // role / capability plus object scope
    // ------------------------------------------------------------------

    /**
     * Full decision: grant, then tenant boundary, then project boundary, then owner.
     * Does not throw; see {@link #check} for the enforcing form.
     */
    public boolean allows(TenantContext context, Permission permission, PolicyResource resource) {
        if (context == null || permission == null || !granted(context).contains(permission)) {
            return false;
        }
        return withinScope(context, permission, resource);
    }

    /**
     * Enforcing form.
     *
     * @throws PolicyDeniedException when the grant exists but the target object is
     *                               outside the caller's tenant/project scope —
     *                               reported as "not found" to the client
     * @throws AccessDeniedException when the principal does not hold the permission
     */
    public void check(TenantContext context, Permission permission, PolicyResource resource) {
        if (context == null || permission == null || !granted(context).contains(permission)) {
            throw denyRole(context, permission);
        }
        if (withinScope(context, permission, resource)) {
            return;
        }
        throw denyScope(context, permission, resource);
    }

    /** Convenience for the very common "does this principal hold this at all" check. */
    public boolean allows(TenantContext context, Permission... permissions) {
        if (context == null) {
            return false;
        }
        for (Permission permission : permissions) {
            if (granted(context).contains(permission)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // inspection
    // ------------------------------------------------------------------

    /**
     * The effective permission set for the Admin inspector: the grant set, then the
     * boundaries that actually apply. Returned as an immutable sorted-by-declaration
     * set so two calls produce identical output.
     */
    public Set<Permission> effectivePermissions(TenantContext context) {
        if (context == null) {
            return Set.of();
        }
        Set<Permission> granted = granted(context);
        if (context.isUser() && (context.isAdmin() || context.isPlatformAdmin())) {
            // An ADMIN or Operator grant is organization-wide, so every grant is effective.
            return granted;
        }
        EnumSet<Permission> effective = EnumSet.noneOf(Permission.class);
        for (Permission permission : granted) {
            if (withinScope(context, permission, PolicyResource.NONE)) {
                effective.add(permission);
            }
        }
        return Collections.unmodifiableSet(effective);
    }

    /** Effective permissions against a concrete resource (e.g. one project). */
    public Set<Permission> effectivePermissions(TenantContext context, PolicyResource resource) {
        Set<Permission> granted = granted(context);
        if (context == null) {
            return Set.of();
        }
        EnumSet<Permission> effective = EnumSet.noneOf(Permission.class);
        for (Permission permission : granted) {
            if (withinScope(context, permission, resource)) {
                effective.add(permission);
            }
        }
        return Collections.unmodifiableSet(effective);
    }

    /**
     * A denial reason that never names the missing object. Safe to log and to place
     * on a security event; the client still sees only {@code 404 NOT_FOUND}.
     */
    public static String scopeReason(TenantContext context, PolicyResource resource) {
        if (context == null || resource == null) {
            return "no-scope-supplied";
        }
        if (resource.organizationId() != null
            && !resource.organizationId().equals(context.organizationId())) {
            return "organization-out-of-scope";
        }
        if (resource.projectId() != null && !context.hasProject(resource.projectId())) {
            return "project-out-of-scope";
        }
        if (resource.ownerUserId() != null
            && isOwnerScopedOwnerMismatch(context, resource)) {
            return "owner-out-of-scope";
        }
        return "scope-ok";
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    private Set<Permission> granted(TenantContext context) {
        return RolePermissions.forPrincipalType(
            context.principalType(), context.role(), context.capabilities(), context.isPlatformAdmin());
    }

    private boolean withinScope(TenantContext context, Permission permission, PolicyResource resource) {
        if (resource == null) {
            return true;
        }
        // Tenant boundary. Nothing crosses it, ever.
        if (resource.organizationId() != null
            && !resource.organizationId().equals(context.organizationId())) {
            return false;
        }
        // Project boundary. Membership is required even for an ADMIN.
        if (resource.projectId() != null && !context.hasProject(resource.projectId())) {
            return false;
        }
        // Owner boundary, only for permissions that are self-service by definition.
        if (resource.ownerUserId() != null && isOwnerScoped(permission)
            && !ownerAllowed(context, permission, resource)) {
            return false;
        }
        return true;
    }

    /**
     * The permissions whose meaning is "my own record". Everything else deliberately
     * ignores {@code PolicyResource.ownerUserId()}: an {@code employee:read} grant
     * must not silently narrow to "only the row I own".
     */
    private static boolean isOwnerScoped(Permission permission) {
        return OWNER_SCOPED.contains(permission);
    }

    private boolean ownerAllowed(TenantContext context, Permission permission, PolicyResource resource) {
        if (resource.ownerUserId().equals(context.userId())) {
            return true;
        }
        // An ADMIN may act on another user's self-service record (setting an
        // employee's availability on their behalf); nobody else may.
        return permission == Permission.EMPLOYEE_SET_OWN_AVAILABILITY && context.isAdmin();
    }

    /** Reason-only owner check, used by {@link #scopeReason} which has no permission. */
    private static boolean isOwnerScopedOwnerMismatch(TenantContext context, PolicyResource resource) {
        return !context.isAdmin() && !resource.ownerUserId().equals(context.userId());
    }

    private AccessDeniedException denyRole(TenantContext context, Permission permission) {
        publish(context, SecurityEventType.ACCESS_DENIED, "Permission",
            permission == null ? "unknown" : permission.value(),
            Map.of("scope", "grant", "permission", permission == null ? "unknown" : permission.value()));
        return new AccessDeniedException("Access denied");
    }

    private PolicyDeniedException denyScope(TenantContext context,
                                            Permission permission,
                                            PolicyResource resource) {
        String reason = scopeReason(context, resource);
        PolicyDeniedException denial = new PolicyDeniedException(reason,
            context == null ? null : context.organizationId(),
            resource == null ? null : resource.projectId(),
            permission);
        publish(context, SecurityEventType.AUTHORIZATION_DENIED, "PolicyResource",
            permission == null ? null : permission.value(),
            Map.of("scope", reason,
                "permission", permission == null ? "unknown" : permission.value()));
        return denial;
    }

    private void publish(TenantContext context, SecurityEventType type,
                         String resourceType, String reason, java.util.Map<String, Object> metadata) {
        try {
            events.publish(SecurityEvent.denial(type, context, resourceType, reason, metadata));
        } catch (RuntimeException e) {
            log.error("Failed to publish security event {} reason={}", type, reason, e);
        }
    }

    }