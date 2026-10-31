package com.firstelement.identity.web;

import com.firstelement.identity.permission.EffectivePermissionService;
import com.firstelement.security.TenantContext;
import com.firstelement.security.TenantContextHolder;
import com.firstelement.security.annotation.RequirePermission;
import com.firstelement.security.permission.Permission;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/permissions} — the effective-permission inspector.
 *
 * <h2>Why it is an API the operator can trust</h2>
 *
 * <p>It answers "what can this person actually do" rather than "what role does this person
 * have", because a role does not determine reach: project membership does. The payload
 * carries both the grant set and the server-resolved project scope.
 *
 * <h2>Guards, in order</h2>
 *
 * <ol>
 *   <li>{@code @RequirePermission(PERMISSION_INSPECTOR_READ)} — method security, so the
 *       check lives on the method and applies to any caller, not only this controller.</li>
 *   <li>The cross-tenant case never reaches the service: the service resolves the target
 *       inside the caller's scope and raises the same non-disclosing
 *       {@code PolicyDeniedException} as a nonexistent id.</li>
 * </ol>
 *
 * <p>{@code /me} is available to any authenticated caller. Hiding an affordance is UX;
 * the server remains the boundary, so this read model can only ever under-report, never
 * over-grant.
 */
@RestController
@RequestMapping("/api/v1/permissions")
public class PermissionInspectorController {

    private final EffectivePermissionService permissions;

    public PermissionInspectorController(EffectivePermissionService permissions) {
        this.permissions = permissions;
    }

    /** The caller's own effective permissions. Requires only a live session. */
    @GetMapping("/me")
    public ResponseEntity<EffectivePermissionService.PermissionInspection> me() {
        return ResponseEntity.ok(permissions.inspectSelf(TenantContextHolder.get()));
    }

    /** Another user's effective permissions. Admin only. */
    @GetMapping("/{userId}")
    @RequirePermission(Permission.PERMISSION_INSPECTOR_READ)
    public ResponseEntity<EffectivePermissionService.PermissionInspection> inspect(
        @PathVariable java.util.UUID userId) {
        TenantContext caller = TenantContextHolder.get();
        return ResponseEntity.ok(permissions.inspect(caller, userId));
    }
}