package com.firstelement.crm.clinic.rbac;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/**
 * Multi-tenant Dental Clinic RBAC Guard, Role Seeder, Scope Enforcer, and Audit Logger.
 *
 * <p>Enforces the core rules from the specification:
 * <ol>
 *   <li><b>Multi-tenant isolation:</b> Every record belongs to a clinic. A user of Clinic A
 *       can never access any data of Clinic B. {@code platform_admin} has NO access to any
 *       clinic's medical or financial data.</li>
 *   <li><b>Separate doctor account per clinic:</b> The same person in two clinics has two
 *       distinct accounts scoped to each clinic.</li>
 *   <li><b>Multi-role union:</b> A user can hold multiple roles at once (e.g.,
 *       {@code clinic_admin} + {@code doctor}); effective permissions = union of all their
 *       roles' permissions.</li>
 *   <li><b>Custom & editable roles:</b> Each clinic is seeded with the 4 default clinic roles
 *       ({@code clinic_admin}, {@code receptionist}, {@code doctor}, {@code accountant}).
 *       {@code clinic_admin} ({@code roles.manage}) can edit them and create custom roles
 *       (e.g. {@code dental_assistant}) from the permission catalog.</li>
 *   <li><b>Least privilege & medical separation:</b> {@code clinic_admin} does NOT have
 *       {@code clinical.*} or {@code treatment_plans.view_full} by default; granting clinical
 *       access to an admin is an explicit permission assignment and is audited.</li>
 *   <li><b>Doctor patient-assignment scope:</b> Doctors only access patients assigned to them
 *       (via appointments or treatment plans) unless the clinic-level setting
 *       {@code doctor_sees_all_patients} (default {@code false}) is turned ON.</li>
 *   <li><b>Mandatory audit log:</b> Records {@code (who, when, what)} for editing a medical
 *       record, creating or cancelling an invoice, applying a discount, changing a role or
 *       permission, suspending/reactivating a user, and repeated failed logins.</li>
 * </ol>
 */
@Service
public class ClinicRbacGuardService {

    public static final String AUDIT_MEDICAL_RECORD_EDITED   = "MEDICAL_RECORD_EDITED";
    public static final String AUDIT_INVOICE_CREATED         = "INVOICE_CREATED";
    public static final String AUDIT_INVOICE_CANCELLED       = "INVOICE_CANCELLED";
    public static final String AUDIT_DISCOUNT_APPLIED        = "DISCOUNT_APPLIED";
    public static final String AUDIT_ROLE_PERMISSION_CHANGED = "ROLE_PERMISSION_CHANGED";
    public static final String AUDIT_CLINICAL_GRANT_TO_ADMIN = "CLINICAL_ACCESS_GRANTED_TO_ADMIN";
    public static final String AUDIT_USER_STATUS_CHANGED     = "USER_STATUS_CHANGED";
    public static final String AUDIT_REPEATED_FAILED_LOGIN   = "REPEATED_FAILED_LOGIN";

    private static final int FAILED_LOGIN_AUDIT_THRESHOLD = 3;

    // Per-clinic roles: clinicId -> (roleKey -> ClinicRoleDefinition)
    private final Map<UUID, Map<String, ClinicRbacModels.ClinicRoleDefinition>> clinicRoles = new ConcurrentHashMap<>();

    // Users: userId -> ClinicUserAccount
    private final Map<UUID, ClinicRbacModels.ClinicUserAccount> usersById = new ConcurrentHashMap<>();

    // Clinic setting: clinicId -> doctor_sees_all_patients (default false)
    private final Map<UUID, Boolean> doctorSeesAllPatientsByClinic = new ConcurrentHashMap<>();

    // Doctor -> assigned patientIds per clinic: clinicId -> (doctorUserId -> Set<patientId>)
    private final Map<UUID, Map<UUID, Set<UUID>>> doctorAssignedPatients = new ConcurrentHashMap<>();

    // Failed login tracker: email -> count
    private final Map<String, Integer> failedLoginCounts = new ConcurrentHashMap<>();

    // Immutable audit trail
    private final List<ClinicRbacModels.RbacAuditEntry> auditLog = new CopyOnWriteArrayList<>();

    // ------------------------------------------------------------------
    // 1. Clinic Seeding (4 Default Clinic Roles)
    // ------------------------------------------------------------------

    /**
     * Seeds the 4 default roles ({@code clinic_admin}, {@code receptionist}, {@code doctor},
     * {@code accountant}) with the exact preset permission mappings for a clinic.
     */
    public Map<String, ClinicRbacModels.ClinicRoleDefinition> seedClinicDefaultRoles(UUID clinicId) {
        Objects.requireNonNull(clinicId, "clinicId is required");
        doctorSeesAllPatientsByClinic.putIfAbsent(clinicId, Boolean.FALSE);

        return clinicRoles.computeIfAbsent(clinicId, id -> {
            Map<String, ClinicRbacModels.ClinicRoleDefinition> seeded = new LinkedHashMap<>();
            seeded.put(
                    ClinicPermissionCatalog.ROLE_CLINIC_ADMIN,
                    new ClinicRbacModels.ClinicRoleDefinition(
                            UUID.randomUUID(),
                            id,
                            ClinicPermissionCatalog.ROLE_CLINIC_ADMIN,
                            "Clinic Admin",
                            true,
                            new LinkedHashSet<>(ClinicPermissionCatalog.CLINIC_ADMIN_PERMISSIONS)));
            seeded.put(
                    ClinicPermissionCatalog.ROLE_RECEPTIONIST,
                    new ClinicRbacModels.ClinicRoleDefinition(
                            UUID.randomUUID(),
                            id,
                            ClinicPermissionCatalog.ROLE_RECEPTIONIST,
                            "Receptionist",
                            true,
                            new LinkedHashSet<>(ClinicPermissionCatalog.RECEPTIONIST_PERMISSIONS)));
            seeded.put(
                    ClinicPermissionCatalog.ROLE_DOCTOR,
                    new ClinicRbacModels.ClinicRoleDefinition(
                            UUID.randomUUID(),
                            id,
                            ClinicPermissionCatalog.ROLE_DOCTOR,
                            "Doctor",
                            true,
                            new LinkedHashSet<>(ClinicPermissionCatalog.DOCTOR_PERMISSIONS)));
            seeded.put(
                    ClinicPermissionCatalog.ROLE_ACCOUNTANT,
                    new ClinicRbacModels.ClinicRoleDefinition(
                            UUID.randomUUID(),
                            id,
                            ClinicPermissionCatalog.ROLE_ACCOUNTANT,
                            "Accountant",
                            true,
                            new LinkedHashSet<>(ClinicPermissionCatalog.ACCOUNTANT_PERMISSIONS)));
            return new ConcurrentHashMap<>(seeded);
        });
    }

    // ------------------------------------------------------------------
    // 2. User Account & Multi-Role Provisioning
    // ------------------------------------------------------------------

    /**
     * Registers a clinic-scoped user account with one or more roles (or a platform_admin).
     * Rule 2: A doctor in two clinics has two separate accounts (distinct userId per clinicId).
     * Rule 3: Effective permissions = union of all assigned roles' permissions.
     */
    public ClinicRbacModels.ClinicUserAccount registerUser(
            UUID userId,
            UUID clinicId,
            String email,
            String fullName,
            Set<String> roleKeys) {
        Objects.requireNonNull(userId, "userId is required");
        Objects.requireNonNull(roleKeys, "roleKeys is required");
        if (roleKeys.isEmpty()) {
            throw new IllegalArgumentException("At least one role must be assigned");
        }

        boolean isPlatformAdmin = roleKeys.contains(ClinicPermissionCatalog.ROLE_PLATFORM_ADMIN);
        if (!isPlatformAdmin) {
            if (clinicId == null) {
                throw new IllegalArgumentException("Every clinic user account must belong to a specific clinic");
            }
            seedClinicDefaultRoles(clinicId);
        }

        ClinicRbacModels.ClinicUserAccount account = new ClinicRbacModels.ClinicUserAccount(
                userId,
                clinicId,
                email,
                fullName,
                false,
                Collections.unmodifiableSet(new LinkedHashSet<>(roleKeys)));
        usersById.put(userId, account);
        return account;
    }

    public ClinicRbacModels.ClinicUserAccount getUserAccount(UUID userId) {
        ClinicRbacModels.ClinicUserAccount account = usersById.get(userId);
        if (account == null) {
            throw new AccessDeniedException("Unknown user principal");
        }
        if (account.suspended()) {
            throw new AccessDeniedException("User account is suspended");
        }
        return account;
    }

    /**
     * Computes the union of permissions across all roles assigned to the user in their clinic.
     */
    public Set<String> resolveEffectivePermissions(UUID userId) {
        ClinicRbacModels.ClinicUserAccount account = getUserAccount(userId);
        Set<String> union = new LinkedHashSet<>();

        for (String roleKey : account.assignedRoleKeys()) {
            if (ClinicPermissionCatalog.ROLE_PLATFORM_ADMIN.equals(roleKey)) {
                union.addAll(ClinicPermissionCatalog.PLATFORM_ADMIN_PERMISSIONS);
                continue;
            }
            if (account.clinicId() != null) {
                Map<String, ClinicRbacModels.ClinicRoleDefinition> roles = seedClinicDefaultRoles(account.clinicId());
                ClinicRbacModels.ClinicRoleDefinition def = roles.get(roleKey);
                if (def != null) {
                    union.addAll(def.permissions());
                }
            }
        }
        return Collections.unmodifiableSet(union);
    }

    public ClinicRbacModels.EffectiveRbacProfile getEffectiveProfile(UUID userId) {
        ClinicRbacModels.ClinicUserAccount account = getUserAccount(userId);
        Set<String> perms = resolveEffectivePermissions(userId);
        boolean doctorSeesAll = account.clinicId() != null
                && Boolean.TRUE.equals(doctorSeesAllPatientsByClinic.getOrDefault(account.clinicId(), Boolean.FALSE));
        return new ClinicRbacModels.EffectiveRbacProfile(
                account.userId(),
                account.clinicId(),
                account.assignedRoleKeys(),
                perms,
                ClinicPermissionCatalog.resolveLandingPath(perms),
                doctorSeesAll);
    }

    // ------------------------------------------------------------------
    // 3. Server-Side Authorization & Tenant Isolation Guard
    // ------------------------------------------------------------------

    /**
     * Checks whether the user holds the given permission key inside the target clinic.
     * Enforces:
     * 1. Account is active (not suspended)
     * 2. Tenant isolation: user's clinicId must match targetClinicId (Platform admin has NO clinic data access)
     * 3. Effective permission set contains {@code requiredPermission}
     */
    public void requirePermission(UUID actorUserId, UUID targetClinicId, String requiredPermission) {
        ClinicRbacModels.ClinicUserAccount actor = getUserAccount(actorUserId);
        Set<String> perms = resolveEffectivePermissions(actorUserId);

        // Platform-level check (clinics.manage, subscriptions.manage)
        if (ClinicPermissionCatalog.CLINICS_MANAGE.equals(requiredPermission)
                || ClinicPermissionCatalog.SUBSCRIPTIONS_MANAGE.equals(requiredPermission)) {
            if (!perms.contains(requiredPermission)) {
                throw new AccessDeniedException("Missing platform permission: " + requiredPermission);
            }
            return;
        }

        // Platform admin has NO access to any clinic's medical or financial data
        if (actor.clinicId() == null || actor.assignedRoleKeys().contains(ClinicPermissionCatalog.ROLE_PLATFORM_ADMIN)) {
            throw new AccessDeniedException("platform_admin has no access to clinic medical or financial data");
        }

        // Strict multi-tenant isolation: Clinic A user must never access Clinic B
        if (targetClinicId == null || !actor.clinicId().equals(targetClinicId)) {
            throw new AccessDeniedException("Cross-clinic access denied: record belongs to another clinic");
        }

        if (!perms.contains(requiredPermission)) {
            throw new AccessDeniedException("Missing required permission: " + requiredPermission);
        }
    }

    public void requireAnyPermission(UUID actorUserId, UUID targetClinicId, String... requiredPermissions) {
        ClinicRbacModels.ClinicUserAccount actor = getUserAccount(actorUserId);
        if (actor.clinicId() == null || !actor.clinicId().equals(targetClinicId)) {
            throw new AccessDeniedException("Cross-clinic access denied");
        }
        Set<String> perms = resolveEffectivePermissions(actorUserId);
        for (String req : requiredPermissions) {
            if (perms.contains(req)) {
                return;
            }
        }
        throw new AccessDeniedException("Missing required permission from: " + List.of(requiredPermissions));
    }

    // ------------------------------------------------------------------
    // 4. Doctor Patient-Assignment Scope & Clinic Setting
    // ------------------------------------------------------------------

    /**
     * Assigns a patient to a doctor within a clinic (via an appointment or treatment plan).
     */
    public void assignPatientToDoctor(UUID clinicId, UUID doctorUserId, UUID patientId) {
        doctorAssignedPatients
                .computeIfAbsent(clinicId, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(doctorUserId, k -> ConcurrentHashMap.newKeySet())
                .add(patientId);
    }

    public boolean isDoctorSeesAllPatients(UUID clinicId) {
        return Boolean.TRUE.equals(doctorSeesAllPatientsByClinic.getOrDefault(clinicId, Boolean.FALSE));
    }

    /**
     * Updates the clinic setting {@code doctor_sees_all_patients} (requires {@code clinic.settings.manage}).
     */
    public void setDoctorSeesAllPatients(UUID actorUserId, UUID clinicId, boolean enabled) {
        requirePermission(actorUserId, clinicId, ClinicPermissionCatalog.CLINIC_SETTINGS_MANAGE);
        doctorSeesAllPatientsByClinic.put(clinicId, enabled);
    }

    /**
     * Verifies whether a user can access a specific patient's record.
     *
     * <p>Rule: A doctor is limited to patients assigned to them (via appointments or treatment
     * plans) unless the clinic-level setting {@code doctor_sees_all_patients} (default OFF) is ON.
     */
    public void assertPatientScope(UUID actorUserId, UUID clinicId, UUID patientId) {
        ClinicRbacModels.ClinicUserAccount actor = getUserAccount(actorUserId);
        if (actor.clinicId() == null || !actor.clinicId().equals(clinicId)) {
            throw new AccessDeniedException("Cross-clinic patient access denied");
        }
        Set<String> perms = resolveEffectivePermissions(actorUserId);

        // If the user only has doctor-scoped appointment/patient access (no clinic-wide appointments.view),
        // enforce the assigned-patient check unless doctor_sees_all_patients is ON.
        boolean hasClinicWideAppointmentView = perms.contains(ClinicPermissionCatalog.APPOINTMENTS_VIEW);
        if (!hasClinicWideAppointmentView) {
            if (!isDoctorSeesAllPatients(clinicId) && !isPatientAssignedToDoctor(clinicId, actorUserId, patientId)) {
                throw new AccessDeniedException("Doctor is restricted to assigned patients only");
            }
        }
    }

    public boolean isPatientAssignedToDoctor(UUID clinicId, UUID doctorUserId, UUID patientId) {
        return doctorAssignedPatients
                .getOrDefault(clinicId, Map.of())
                .getOrDefault(doctorUserId, Set.of())
                .contains(patientId);
    }

    // ------------------------------------------------------------------
    // 5. Clinical Medical Record, Treatment Plan & Billing Operations
    // ------------------------------------------------------------------

    public void viewMedicalRecord(UUID actorUserId, UUID clinicId, UUID patientId) {
        requirePermission(actorUserId, clinicId, ClinicPermissionCatalog.CLINICAL_VIEW_RECORD);
        assertPatientScope(actorUserId, clinicId, patientId);
    }

    public void viewMedicalHistory(UUID actorUserId, UUID clinicId, UUID patientId) {
        requirePermission(actorUserId, clinicId, ClinicPermissionCatalog.CLINICAL_VIEW_HISTORY);
        assertPatientScope(actorUserId, clinicId, patientId);
    }

    public void editMedicalRecord(
            UUID actorUserId,
            UUID clinicId,
            UUID patientId,
            String recordId,
            String changeSummary) {
        requirePermission(actorUserId, clinicId, ClinicPermissionCatalog.CLINICAL_WRITE_RECORD);
        assertPatientScope(actorUserId, clinicId, patientId);
        recordAudit(
                clinicId,
                actorUserId,
                AUDIT_MEDICAL_RECORD_EDITED,
                "MEDICAL_RECORD",
                recordId,
                "patientId=" + patientId + "; summary=" + changeSummary);
    }

    /**
     * Returns a permission-filtered view of a treatment plan:
     * - {@code treatment_plans.view_full}: full clinical diagnosis + financials + status
     * - {@code treatment_plans.view_financials}: status + financial figures ONLY (no diagnosis/clinical notes)
     * - {@code treatment_plans.view_status}: status ONLY (no financials, no diagnosis/clinical notes)
     */
    public ClinicRbacModels.ScopedTreatmentPlanView viewTreatmentPlan(
            UUID actorUserId,
            ClinicRbacModels.ScopedTreatmentPlanView fullPlan) {
        Objects.requireNonNull(fullPlan, "fullPlan is required");
        ClinicRbacModels.ClinicUserAccount actor = getUserAccount(actorUserId);
        if (actor.clinicId() == null || !actor.clinicId().equals(fullPlan.clinicId())) {
            throw new AccessDeniedException("Cross-clinic treatment plan access denied");
        }

        Set<String> perms = resolveEffectivePermissions(actorUserId);
        if (perms.contains(ClinicPermissionCatalog.TREATMENT_PLANS_VIEW_FULL)) {
            assertPatientScope(actorUserId, fullPlan.clinicId(), fullPlan.patientId());
            return fullPlan;
        }
        if (perms.contains(ClinicPermissionCatalog.TREATMENT_PLANS_VIEW_FINANCIALS)) {
            return new ClinicRbacModels.ScopedTreatmentPlanView(
                    fullPlan.planId(),
                    fullPlan.clinicId(),
                    fullPlan.patientId(),
                    fullPlan.doctorUserId(),
                    fullPlan.status(),
                    fullPlan.plannedTotal(),
                    fullPlan.invoicedTotal(),
                    fullPlan.collectedTotal(),
                    null,
                    null);
        }
        if (perms.contains(ClinicPermissionCatalog.TREATMENT_PLANS_VIEW_STATUS)) {
            return new ClinicRbacModels.ScopedTreatmentPlanView(
                    fullPlan.planId(),
                    fullPlan.clinicId(),
                    fullPlan.patientId(),
                    fullPlan.doctorUserId(),
                    fullPlan.status(),
                    null,
                    null,
                    null,
                    null,
                    null);
        }
        throw new AccessDeniedException("Missing treatment_plans view permission");
    }

    public void createInvoice(UUID actorUserId, UUID clinicId, String invoiceId, BigDecimal amount) {
        requirePermission(actorUserId, clinicId, ClinicPermissionCatalog.INVOICES_CREATE);
        recordAudit(
                clinicId,
                actorUserId,
                AUDIT_INVOICE_CREATED,
                "INVOICE",
                invoiceId,
                "amount=" + amount);
    }

    public void applyDiscount(
            UUID actorUserId,
            UUID clinicId,
            String invoiceId,
            BigDecimal discountAmount,
            String reason) {
        requirePermission(actorUserId, clinicId, ClinicPermissionCatalog.DISCOUNTS_APPLY);
        if (reason == null || reason.trim().isEmpty()) {
            throw new IllegalArgumentException("Discount reason is mandatory");
        }
        recordAudit(
                clinicId,
                actorUserId,
                AUDIT_DISCOUNT_APPLIED,
                "INVOICE",
                invoiceId,
                "discountAmount=" + discountAmount + "; reason=" + reason.trim());
    }

    public void cancelInvoice(
            UUID actorUserId,
            UUID clinicId,
            String invoiceId,
            String mandatoryReason) {
        requirePermission(actorUserId, clinicId, ClinicPermissionCatalog.INVOICES_CANCEL);
        if (mandatoryReason == null || mandatoryReason.trim().isEmpty()) {
            throw new IllegalArgumentException("Cancellation reason is mandatory");
        }
        recordAudit(
                clinicId,
                actorUserId,
                AUDIT_INVOICE_CANCELLED,
                "INVOICE",
                invoiceId,
                "reason=" + mandatoryReason.trim());
    }

    public void viewPatientInvoices(UUID actorUserId, UUID clinicId, UUID patientId) {
        ClinicRbacModels.ClinicUserAccount actor = getUserAccount(actorUserId);
        if (actor.clinicId() == null || !actor.clinicId().equals(clinicId)) {
            throw new AccessDeniedException("Cross-clinic invoice access denied");
        }
        Set<String> perms = resolveEffectivePermissions(actorUserId);
        if (perms.contains(ClinicPermissionCatalog.INVOICES_VIEW)) {
            return;
        }
        if (perms.contains(ClinicPermissionCatalog.INVOICES_VIEW_OWN_PATIENTS)) {
            assertPatientScope(actorUserId, clinicId, patientId);
            return;
        }
        throw new AccessDeniedException("Missing invoice view permission");
    }

    // ------------------------------------------------------------------
    // 6. Role & Custom Role Management + Audited Clinical Grants
    // ------------------------------------------------------------------

    public ClinicRbacModels.ClinicRoleDefinition createCustomRole(
            UUID actorUserId,
            UUID clinicId,
            String roleKey,
            String displayName,
            Set<String> permissions) {
        requirePermission(actorUserId, clinicId, ClinicPermissionCatalog.ROLES_MANAGE);
        validatePermissionSet(permissions);

        Map<String, ClinicRbacModels.ClinicRoleDefinition> roles = seedClinicDefaultRoles(clinicId);
        String normalizedKey = roleKey.trim().toLowerCase();
        ClinicRbacModels.ClinicRoleDefinition created = new ClinicRbacModels.ClinicRoleDefinition(
                UUID.randomUUID(),
                clinicId,
                normalizedKey,
                displayName,
                false,
                Collections.unmodifiableSet(new LinkedHashSet<>(permissions)));
        roles.put(normalizedKey, created);

        recordAudit(
                clinicId,
                actorUserId,
                AUDIT_ROLE_PERMISSION_CHANGED,
                "ROLE",
                normalizedKey,
                "created custom role with permissions=" + permissions);
        return created;
    }

    /**
     * Updates a role's permissions.
     * Rule 5: Granting clinical access to {@code clinic_admin} is an explicit permission assignment
     * and must be audited.
     */
    public ClinicRbacModels.ClinicRoleDefinition updateRolePermissions(
            UUID actorUserId,
            UUID clinicId,
            String roleKey,
            Set<String> updatedPermissions,
            String reason) {
        requirePermission(actorUserId, clinicId, ClinicPermissionCatalog.ROLES_MANAGE);
        validatePermissionSet(updatedPermissions);

        Map<String, ClinicRbacModels.ClinicRoleDefinition> roles = seedClinicDefaultRoles(clinicId);
        String normalizedKey = roleKey.trim().toLowerCase();
        ClinicRbacModels.ClinicRoleDefinition existing = roles.get(normalizedKey);
        if (existing == null) {
            throw new IllegalArgumentException("Role not found: " + normalizedKey);
        }

        Set<String> newlyAdded = new LinkedHashSet<>(updatedPermissions);
        newlyAdded.removeAll(existing.permissions());

        ClinicRbacModels.ClinicRoleDefinition updated = new ClinicRbacModels.ClinicRoleDefinition(
                existing.id(),
                clinicId,
                existing.roleKey(),
                existing.displayName(),
                existing.isPreset(),
                Collections.unmodifiableSet(new LinkedHashSet<>(updatedPermissions)));
        roles.put(normalizedKey, updated);

        recordAudit(
                clinicId,
                actorUserId,
                AUDIT_ROLE_PERMISSION_CHANGED,
                "ROLE",
                normalizedKey,
                "updatedPermissions=" + updatedPermissions + "; reason=" + (reason != null ? reason : ""));

        // Explicit audit entry when sensitive clinical access is granted to clinic_admin
        boolean grantedClinical = newlyAdded.stream().anyMatch(ClinicPermissionCatalog::isSensitiveClinicalPermission);
        if (ClinicPermissionCatalog.ROLE_CLINIC_ADMIN.equals(normalizedKey) && grantedClinical) {
            recordAudit(
                    clinicId,
                    actorUserId,
                    AUDIT_CLINICAL_GRANT_TO_ADMIN,
                    "ROLE",
                    normalizedKey,
                    "explicit clinical grant=" + newlyAdded + "; reason=" + (reason != null ? reason : ""));
        }

        return updated;
    }

    public ClinicRbacModels.ClinicUserAccount assignUserRoles(
            UUID actorUserId,
            UUID clinicId,
            UUID targetUserId,
            Set<String> newRoleKeys) {
        requirePermission(actorUserId, clinicId, ClinicPermissionCatalog.USERS_MANAGE);
        ClinicRbacModels.ClinicUserAccount target = usersById.get(targetUserId);
        if (target == null || !clinicId.equals(target.clinicId())) {
            throw new AccessDeniedException("Target user does not belong to this clinic");
        }
        ClinicRbacModels.ClinicUserAccount updated = new ClinicRbacModels.ClinicUserAccount(
                target.userId(),
                target.clinicId(),
                target.email(),
                target.fullName(),
                target.suspended(),
                Collections.unmodifiableSet(new LinkedHashSet<>(newRoleKeys)));
        usersById.put(targetUserId, updated);

        recordAudit(
                clinicId,
                actorUserId,
                AUDIT_ROLE_PERMISSION_CHANGED,
                "USER_ROLES",
                targetUserId.toString(),
                "assignedRoles=" + newRoleKeys);
        return updated;
    }

    public ClinicRbacModels.ClinicUserAccount setUserSuspended(
            UUID actorUserId,
            UUID clinicId,
            UUID targetUserId,
            boolean suspended,
            String reason) {
        requirePermission(actorUserId, clinicId, ClinicPermissionCatalog.USERS_MANAGE);
        ClinicRbacModels.ClinicUserAccount target = usersById.get(targetUserId);
        if (target == null || !clinicId.equals(target.clinicId())) {
            throw new AccessDeniedException("Target user does not belong to this clinic");
        }
        ClinicRbacModels.ClinicUserAccount updated = new ClinicRbacModels.ClinicUserAccount(
                target.userId(),
                target.clinicId(),
                target.email(),
                target.fullName(),
                suspended,
                target.assignedRoleKeys());
        usersById.put(targetUserId, updated);

        recordAudit(
                clinicId,
                actorUserId,
                AUDIT_USER_STATUS_CHANGED,
                "USER",
                targetUserId.toString(),
                (suspended ? "SUSPENDED" : "REACTIVATED") + "; reason=" + (reason != null ? reason : ""));
        return updated;
    }

    public void recordFailedLoginAttempt(UUID clinicId, String email) {
        String key = (clinicId != null ? clinicId.toString() : "platform") + ":" + email.toLowerCase();
        int count = failedLoginCounts.merge(key, 1, Integer::sum);
        if (count >= FAILED_LOGIN_AUDIT_THRESHOLD) {
            recordAudit(
                    clinicId,
                    null,
                    AUDIT_REPEATED_FAILED_LOGIN,
                    "AUTH",
                    email,
                    "failedAttempts=" + count);
        }
    }

    // ------------------------------------------------------------------
    // 7. Audit Log Queries
    // ------------------------------------------------------------------

    public List<ClinicRbacModels.RbacAuditEntry> getAuditLogForClinic(UUID clinicId) {
        List<ClinicRbacModels.RbacAuditEntry> list = new ArrayList<>();
        for (ClinicRbacModels.RbacAuditEntry entry : auditLog) {
            if (Objects.equals(clinicId, entry.clinicId())) {
                list.add(entry);
            }
        }
        return Collections.unmodifiableList(list);
    }

    private void recordAudit(
            UUID clinicId,
            UUID actorUserId,
            String action,
            String targetType,
            String targetId,
            String details) {
        auditLog.add(new ClinicRbacModels.RbacAuditEntry(
                UUID.randomUUID(),
                clinicId,
                actorUserId,
                action,
                targetType,
                targetId,
                details,
                Instant.now()));
    }

    private static void validatePermissionSet(Set<String> permissions) {
        if (permissions == null || permissions.isEmpty()) {
            throw new IllegalArgumentException("Permissions set cannot be empty");
        }
        for (String key : permissions) {
            if (!ClinicPermissionCatalog.isValidPermissionKey(key)) {
                throw new IllegalArgumentException("Invalid permission key not in catalog: " + key);
            }
            if (ClinicPermissionCatalog.CLINICS_MANAGE.equals(key)
                    || ClinicPermissionCatalog.SUBSCRIPTIONS_MANAGE.equals(key)) {
                throw new IllegalArgumentException("Platform permissions cannot be assigned to a clinic role: " + key);
            }
        }
    }
}
