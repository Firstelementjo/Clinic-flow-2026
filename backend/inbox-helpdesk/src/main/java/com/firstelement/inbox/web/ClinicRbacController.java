package com.firstelement.inbox.web;

import com.firstelement.crm.clinic.rbac.ClinicPermissionCatalog;
import com.firstelement.crm.clinic.rbac.ClinicRbacGuardService;
import com.firstelement.crm.clinic.rbac.ClinicRbacModels;
import com.firstelement.security.TenantContext;
import com.firstelement.security.TenantContextHolder;
import com.firstelement.security.annotation.RequirePermission;
import com.firstelement.security.permission.Permission;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST Controller for the Multi-Tenant Dental Clinic RBAC Catalog, Preset & Custom Roles,
 * Multi-Role User Assignments, and Mandatory Audit Trail.
 */
@RestController
@RequestMapping("/api/v1/clinic/rbac")
public class ClinicRbacController {

    private final ClinicRbacGuardService rbacService;
    private final NamedParameterJdbcTemplate jdbc;

    @Autowired
    public ClinicRbacController(
            ClinicRbacGuardService rbacService,
            @Autowired(required = false) NamedParameterJdbcTemplate jdbc) {
        this.rbacService = rbacService;
        this.jdbc = jdbc;
    }

    public ClinicRbacController(ClinicRbacGuardService rbacService) {
        this(rbacService, null);
    }

    @GetMapping("/me")
    @RequirePermission(Permission.DOCTOR_READ)
    public Map<String, Object> getMyClinicRbacProfile() {
        TenantContext caller = TenantContextHolder.get();
        if (caller == null) {
            return Map.of(
                    "roles", List.of(),
                    "effectivePermissions", List.of(),
                    "doctorSeesAllPatients", false);
        }

        List<String> roles = new ArrayList<>();
        Set<String> effectivePermissions = new LinkedHashSet<>();
        boolean doctorSeesAllPatients = false;

        if (jdbc != null) {
            try {
                roles = jdbc.queryForList(
                        """
                        SELECT r."roleKey"
                          FROM "public"."clinic_user_roles" ur
                          JOIN "public"."clinic_roles" r ON r."id" = ur."roleId"
                         WHERE ur."organizationId" = :orgId
                           AND ur."userId" = :userId
                         ORDER BY r."roleKey"
                        """,
                        Map.of("orgId", caller.organizationId(), "userId", caller.userId()),
                        String.class);

                List<String> dbPerms = jdbc.queryForList(
                        """
                        SELECT DISTINCT rp."permissionKey"
                          FROM "public"."clinic_user_roles" ur
                          JOIN "public"."clinic_role_permissions" rp ON rp."roleId" = ur."roleId"
                         WHERE ur."organizationId" = :orgId
                           AND ur."userId" = :userId
                        """,
                        Map.of("orgId", caller.organizationId(), "userId", caller.userId()),
                        String.class);
                effectivePermissions.addAll(dbPerms);

                List<Boolean> flags = jdbc.queryForList(
                        """
                        SELECT "doctorSeesAllPatients"
                          FROM "public"."clinic_locations"
                         WHERE "organizationId" = :orgId
                         LIMIT 1
                        """,
                        Map.of("orgId", caller.organizationId()),
                        Boolean.class);
                if (!flags.isEmpty() && Boolean.TRUE.equals(flags.get(0))) {
                    doctorSeesAllPatients = true;
                }
            } catch (Exception ignored) {
                // Fall back to default role resolution if tables are not yet populated
            }
        }

        if (roles.isEmpty()) {
            String legacyRole = caller.role() != null ? caller.role().name() : "";
            if ("ADMIN".equals(legacyRole)) {
                roles = List.of(ClinicPermissionCatalog.ROLE_CLINIC_ADMIN);
            } else if ("CLINIC_STAFF".equals(legacyRole)) {
                roles = List.of(ClinicPermissionCatalog.ROLE_RECEPTIONIST);
            } else if ("EMPLOYEE".equals(legacyRole)) {
                roles = List.of(ClinicPermissionCatalog.ROLE_DOCTOR);
            }
        }
        for (String r : roles) {
            Set<String> preset = ClinicPermissionCatalog.DEFAULT_CLINIC_ROLES.get(r);
            if (preset != null) {
                effectivePermissions.addAll(preset);
            }
        }

        return Map.of(
                "roles", roles,
                "effectivePermissions", effectivePermissions,
                "doctorSeesAllPatients", doctorSeesAllPatients);
    }

    @GetMapping("/catalog")
    @RequirePermission(Permission.DOCTOR_READ)
    public Map<String, Object> getCatalog() {
        return Map.of(
                "permissions", ClinicPermissionCatalog.ALL_PERMISSION_KEYS,
                "defaultRoles", ClinicPermissionCatalog.DEFAULT_CLINIC_ROLES,
                "platformAdminPermissions", ClinicPermissionCatalog.PLATFORM_ADMIN_PERMISSIONS);
    }

    @GetMapping("/roles")
    @RequirePermission(Permission.DOCTOR_READ)
    public Map<String, ClinicRbacModels.ClinicRoleDefinition> listClinicRoles() {
        TenantContext caller = TenantContextHolder.get();
        UUID clinicId = resolveClinicId(caller);
        return rbacService.seedClinicDefaultRoles(clinicId);
    }

    @PostMapping("/roles")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission(Permission.CLINIC_MANAGE)
    public ClinicRbacModels.ClinicRoleDefinition createCustomRole(
            @RequestBody ClinicRbacModels.CreateRoleRequest request) {
        TenantContext caller = TenantContextHolder.get();
        UUID clinicId = resolveClinicId(caller);
        ensureAdminRegistered(caller, clinicId);
        return rbacService.createCustomRole(
                caller.userId(),
                clinicId,
                request.roleKey(),
                request.displayName(),
                request.permissions());
    }

    @PutMapping("/roles/{roleKey}/permissions")
    @RequirePermission(Permission.CLINIC_MANAGE)
    public ClinicRbacModels.ClinicRoleDefinition updateRolePermissions(
            @PathVariable("roleKey") String roleKey,
            @RequestBody ClinicRbacModels.UpdateRolePermissionsRequest request) {
        TenantContext caller = TenantContextHolder.get();
        UUID clinicId = resolveClinicId(caller);
        ensureAdminRegistered(caller, clinicId);
        return rbacService.updateRolePermissions(
                caller.userId(),
                clinicId,
                roleKey,
                request.permissions(),
                request.reason());
    }

    @GetMapping("/audit-log")
    @RequirePermission(Permission.CLINIC_MANAGE)
    public List<ClinicRbacModels.RbacAuditEntry> getClinicRbacAuditLog() {
        TenantContext caller = TenantContextHolder.get();
        UUID clinicId = resolveClinicId(caller);
        return rbacService.getAuditLogForClinic(clinicId);
    }

    private void ensureAdminRegistered(TenantContext caller, UUID clinicId) {
        try {
            rbacService.getUserAccount(caller.userId());
        } catch (Exception ignored) {
            rbacService.registerUser(
                    caller.userId(),
                    clinicId,
                    "admin@clinic.local",
                    "Clinic Admin",
                    Set.of(ClinicPermissionCatalog.ROLE_CLINIC_ADMIN));
        }
    }

    private static UUID resolveClinicId(TenantContext caller) {
        if (caller != null && !caller.projectIds().isEmpty()) {
            return caller.projectIds().iterator().next();
        }
        return caller != null ? caller.organizationId() : UUID.randomUUID();
    }
}
