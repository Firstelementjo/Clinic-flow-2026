package com.firstelement.crm.clinic.rbac;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Domain models for multi-tenant clinic RBAC, custom roles, multi-role user assignments,
 * permission-scoped projections, and mandatory audit logging.
 */
public final class ClinicRbacModels {

    private ClinicRbacModels() {
    }

    public record ClinicRoleDefinition(
            UUID id,
            UUID clinicId,
            String roleKey,
            String displayName,
            boolean isPreset,
            Set<String> permissions) {
    }

    public record ClinicUserAccount(
            UUID userId,
            UUID clinicId,
            String email,
            String fullName,
            boolean suspended,
            Set<String> assignedRoleKeys) {
    }

    public record EffectiveRbacProfile(
            UUID userId,
            UUID clinicId,
            Set<String> roles,
            Set<String> effectivePermissions,
            String landingPath,
            boolean doctorSeesAllPatients) {
    }

    public record CreateRoleRequest(
            String roleKey,
            String displayName,
            Set<String> permissions) {
    }

    public record UpdateRolePermissionsRequest(
            Set<String> permissions,
            String reason) {
    }

    public record AssignUserRolesRequest(
            Set<String> roleKeys) {
    }

    public record ChangeUserStatusRequest(
            boolean suspended,
            String reason) {
    }

    public record RbacAuditEntry(
            UUID id,
            UUID clinicId,
            UUID actorUserId,
            String action,
            String targetType,
            String targetId,
            String details,
            Instant occurredAt) {
    }

    /**
     * Treatment plan projection filtered by caller's treatment_plans.* permissions.
     */
    public record ScopedTreatmentPlanView(
            UUID planId,
            UUID clinicId,
            UUID patientId,
            UUID doctorUserId,
            String status,
            BigDecimal plannedTotal,
            BigDecimal invoicedTotal,
            BigDecimal collectedTotal,
            String diagnosis,
            String clinicalNotes) {
    }
}
