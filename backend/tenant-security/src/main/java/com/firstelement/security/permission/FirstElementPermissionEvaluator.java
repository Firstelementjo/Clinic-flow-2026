package com.firstelement.security.permission;

import com.firstelement.security.TenantContext;
import com.firstelement.security.TenantScopedAuthentication;
import java.io.Serializable;
import java.util.Set;
import org.springframework.security.access.PermissionEvaluator;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * Adapts {@link AuthorizationService} to Spring Security's {@code PermissionEvaluator}
 * extension point, published as the bean {@code fePermissionEvaluator} and referenced
 * by {@link com.firstelement.security.annotation.RequirePermission}.
 *
 * <p>Accepts any {@link TenantScopedAuthentication} — the browser cookie session or the
 * optional integration bearer token. Any other {@code Authentication} (a test mock, a
 * {@code UserDetails}-based authentication) grants nothing: default deny means an
 * unrecognised principal shape is a denial, not a pass.
 */
@Component("fePermissionEvaluator")
public class FirstElementPermissionEvaluator implements PermissionEvaluator, Serializable {

    private static final long serialVersionUID = 1L;

    private final transient AuthorizationService authorization;

    public FirstElementPermissionEvaluator(AuthorizationService authorization) {
        this.authorization = authorization;
    }

    /**
     * {@code hasPermission(auth, target, permission)} — the target is the object being
     * acted on and may be a {@link Permission}, its wire value, or a
     * {@link PolicyResource}.
     */
    @Override
    public boolean hasPermission(Authentication authentication,
                                 Object targetDomainObject,
                                 Object targetPermission) {
        TenantContext context = contextOf(authentication);
        Permission permission = asPermission(targetPermission);
        if (context == null || permission == null) {
            return false;
        }
        PolicyResource resource = asResource(targetDomainObject);
        return authorization.allows(context, permission, resource);
    }

    /**
     * {@code hasPermission(auth, targetId, targetType, permission)}.
     *
     * <p>{@code targetType} must be the fully-qualified {@link PolicyResource} class name
     * or {@code "resource"}; anything else is denied rather than reinterpreted, so a typo
     * in an expression cannot quietly widen the check.
     */
    @Override
    public boolean hasPermission(Authentication authentication,
                                 Serializable targetId,
                                 String targetType,
                                 Object targetPermission) {
        TenantContext context = contextOf(authentication);
        Permission permission = asPermission(targetPermission);
        if (context == null || permission == null) {
            return false;
        }
        if (targetType == null || !(targetType.equals(PolicyResource.class.getName())
            || targetType.equals("resource"))) {
            return false;
        }
        PolicyResource resource = targetId instanceof PolicyResource declared
            ? declared
            : PolicyResource.NONE;
        return authorization.allows(context, permission, resource);
    }

    /** Resource-aware convenience for the templated meta-annotations. */
    public boolean allowsOn(Authentication authentication, Permission permission, PolicyResource resource) {
        return authorization.allows(contextOf(authentication), permission, resource);
    }

    // ------------------------------------------------------------------
    // expression-facing shortcuts
    // ------------------------------------------------------------------

    public boolean allows(Authentication authentication, Permission permission) {
        return authorization.allows(contextOf(authentication), permission);
    }

    /** Varargs form, so the templated annotation can pass an array literal. */
    public boolean allowsAny(Authentication authentication, Permission... permissions) {
        TenantContext context = contextOf(authentication);
        return context != null && permissions != null && permissions.length > 0
            && authorization.allows(context, permissions);
    }

    /** Effective permission values, for an inspector or a diagnostic endpoint. */
    public Set<String> effective(Authentication authentication) {
        Set<Permission> permissions = authorization.effectivePermissions(contextOf(authentication));
        return permissions.stream().map(Permission::value)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    // ------------------------------------------------------------------
    // coercion
    // ------------------------------------------------------------------

    private TenantContext contextOf(Authentication authentication) {
        if (authentication instanceof TenantScopedAuthentication scoped) {
            return scoped.tenantContext();
        }
        return null;
    }

    private Permission asPermission(Object value) {
        if (value instanceof Permission permission) {
            return permission;
        }
        if (value instanceof String text) {
            try {
                return Permission.fromValue(text);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return null;
    }

    private PolicyResource asResource(Object value) {
        if (value instanceof PolicyResource resource) {
            return resource;
        }
        if (value instanceof TenantContext context) {
            return PolicyResource.ofOrganization(context.organizationId());
        }
        return PolicyResource.NONE;
    }
}