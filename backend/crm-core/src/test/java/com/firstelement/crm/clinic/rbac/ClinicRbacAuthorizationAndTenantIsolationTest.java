package com.firstelement.crm.clinic.rbac;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

/**
 * Verifies the multi-tenant dental clinic RBAC specification:
 * <ul>
 *   <li>Tenant isolation (cross-clinic access denied & separate doctor accounts per clinic)</li>
 *   <li>platform_admin denied medical and financial data</li>
 *   <li>clinic_admin denied medical record by default and allowed after an explicit, audited grant</li>
 *   <li>receptionist denied clinical data, discounts, and invoice cancellation; sees treatment plan status only</li>
 *   <li>doctor limited to assigned patients unless doctor_sees_all_patients is ON</li>
 *   <li>accountant denied clinical data and clinic settings; sees treatment plan financial figures only</li>
 *   <li>multi-role user (clinic_admin + doctor) gets the union of permissions</li>
 *   <li>mandatory audit log for all sensitive operations</li>
 * </ul>
 */
class ClinicRbacAuthorizationAndTenantIsolationTest {

    private static final UUID CLINIC_A = UUID.randomUUID();
    private static final UUID CLINIC_B = UUID.randomUUID();

    private static final UUID PATIENT_ASSIGNED = UUID.randomUUID();
    private static final UUID PATIENT_UNASSIGNED = UUID.randomUUID();

    private ClinicRbacGuardService rbac;

    private UUID platformAdminId;
    private UUID clinicAdminAId;
    private UUID receptionistAId;
    private UUID doctorAId;
    private UUID accountantAId;
    private UUID doctorBId;

    @BeforeEach
    void setUp() {
        rbac = new ClinicRbacGuardService();
        rbac.seedClinicDefaultRoles(CLINIC_A);
        rbac.seedClinicDefaultRoles(CLINIC_B);

        platformAdminId = UUID.randomUUID();
        clinicAdminAId = UUID.randomUUID();
        receptionistAId = UUID.randomUUID();
        doctorAId = UUID.randomUUID();
        accountantAId = UUID.randomUUID();
        doctorBId = UUID.randomUUID();

        rbac.registerUser(platformAdminId, null, "owner@platform.local", "Platform Owner",
                Set.of(ClinicPermissionCatalog.ROLE_PLATFORM_ADMIN));
        rbac.registerUser(clinicAdminAId, CLINIC_A, "admin@clinic-a.local", "Clinic A Admin",
                Set.of(ClinicPermissionCatalog.ROLE_CLINIC_ADMIN));
        rbac.registerUser(receptionistAId, CLINIC_A, "desk@clinic-a.local", "Clinic A Reception",
                Set.of(ClinicPermissionCatalog.ROLE_RECEPTIONIST));
        rbac.registerUser(doctorAId, CLINIC_A, "dr.eman@clinic-a.local", "Dr. Eman (Clinic A)",
                Set.of(ClinicPermissionCatalog.ROLE_DOCTOR));
        rbac.registerUser(accountantAId, CLINIC_A, "finance@clinic-a.local", "Clinic A Accountant",
                Set.of(ClinicPermissionCatalog.ROLE_ACCOUNTANT));

        // Rule 2: Same doctor working in Clinic B has a separate account scoped to Clinic B
        rbac.registerUser(doctorBId, CLINIC_B, "dr.eman@clinic-b.local", "Dr. Eman (Clinic B)",
                Set.of(ClinicPermissionCatalog.ROLE_DOCTOR));

        rbac.assignPatientToDoctor(CLINIC_A, doctorAId, PATIENT_ASSIGNED);
    }

    @Test
    @DisplayName("1. Tenant isolation: a user of Clinic A is strictly denied access to any data of Clinic B, and platform_admin has no clinic data access")
    void tenantIsolationAndPlatformAdminBoundary() {
        // Clinic A admin cannot access Clinic B settings or invoices
        assertThatThrownBy(() -> rbac.requirePermission(
                clinicAdminAId, CLINIC_B, ClinicPermissionCatalog.CLINIC_SETTINGS_MANAGE))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Cross-clinic access denied");

        // Doctor in Clinic A cannot access Clinic B medical records, even with a separate account in Clinic B
        assertThatThrownBy(() -> rbac.viewMedicalRecord(doctorAId, CLINIC_B, PATIENT_ASSIGNED))
                .isInstanceOf(AccessDeniedException.class);

        // platform_admin can manage clinics/subscriptions, but has NO access to any clinic's medical or financial data
        rbac.requirePermission(platformAdminId, null, ClinicPermissionCatalog.CLINICS_MANAGE);
        rbac.requirePermission(platformAdminId, null, ClinicPermissionCatalog.SUBSCRIPTIONS_MANAGE);

        assertThatThrownBy(() -> rbac.requirePermission(
                platformAdminId, CLINIC_A, ClinicPermissionCatalog.INVOICES_VIEW))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("platform_admin has no access");

        assertThatThrownBy(() -> rbac.viewMedicalRecord(platformAdminId, CLINIC_A, PATIENT_ASSIGNED))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("2. clinic_admin is denied medical records by default, and allowed only after an explicit, audited permission grant")
    void clinicAdminDeniedMedicalRecordByDefaultAndAllowedAfterAuditedGrant() {
        // Default: clinic_admin does NOT hold clinical.view_record or clinical.write_record
        Set<String> defaultPerms = rbac.resolveEffectivePermissions(clinicAdminAId);
        assertThat(defaultPerms)
                .doesNotContain(
                        ClinicPermissionCatalog.CLINICAL_VIEW_HISTORY,
                        ClinicPermissionCatalog.CLINICAL_VIEW_RECORD,
                        ClinicPermissionCatalog.CLINICAL_WRITE_RECORD,
                        ClinicPermissionCatalog.TREATMENT_PLANS_VIEW_FULL);

        assertThatThrownBy(() -> rbac.viewMedicalRecord(clinicAdminAId, CLINIC_A, PATIENT_ASSIGNED))
                .isInstanceOf(AccessDeniedException.class);

        // Explicit grant of clinical.view_record to clinic_admin
        Set<String> widened = new LinkedHashSet<>(defaultPerms);
        widened.add(ClinicPermissionCatalog.CLINICAL_VIEW_RECORD);

        rbac.updateRolePermissions(
                clinicAdminAId,
                CLINIC_A,
                ClinicPermissionCatalog.ROLE_CLINIC_ADMIN,
                widened,
                "Clinic owner is a practicing dentist requiring chart review");

        // Now allowed
        rbac.viewMedicalRecord(clinicAdminAId, CLINIC_A, PATIENT_ASSIGNED);

        // Audit log must record both ROLE_PERMISSION_CHANGED and CLINICAL_ACCESS_GRANTED_TO_ADMIN
        List<ClinicRbacModels.RbacAuditEntry> audits = rbac.getAuditLogForClinic(CLINIC_A);
        assertThat(audits)
                .extracting(ClinicRbacModels.RbacAuditEntry::action)
                .contains(
                        ClinicRbacGuardService.AUDIT_ROLE_PERMISSION_CHANGED,
                        ClinicRbacGuardService.AUDIT_CLINICAL_GRANT_TO_ADMIN);
    }

    @Test
    @DisplayName("3. receptionist is denied clinical data, discounts, and invoice cancellation, and sees treatment plan STATUS only")
    void receptionistPermissionsAndBoundaries() {
        // Denied medical history & medical record
        assertThatThrownBy(() -> rbac.viewMedicalHistory(receptionistAId, CLINIC_A, PATIENT_ASSIGNED))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> rbac.viewMedicalRecord(receptionistAId, CLINIC_A, PATIENT_ASSIGNED))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> rbac.editMedicalRecord(
                receptionistAId, CLINIC_A, PATIENT_ASSIGNED, "enc-1", "Attempted note"))
                .isInstanceOf(AccessDeniedException.class);

        // Can create invoice, but cannot apply discount or cancel invoice
        rbac.createInvoice(receptionistAId, CLINIC_A, "INV-100", new BigDecimal("150.00"));
        assertThatThrownBy(() -> rbac.applyDiscount(
                receptionistAId, CLINIC_A, "INV-100", new BigDecimal("20.00"), "Promo"))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> rbac.cancelInvoice(
                receptionistAId, CLINIC_A, "INV-100", "Wrong patient"))
                .isInstanceOf(AccessDeniedException.class);

        // Sees treatment plan STATUS only (diagnosis and financials stripped)
        ClinicRbacModels.ScopedTreatmentPlanView fullPlan = new ClinicRbacModels.ScopedTreatmentPlanView(
                UUID.randomUUID(),
                CLINIC_A,
                PATIENT_ASSIGNED,
                doctorAId,
                "IN_PROGRESS",
                new BigDecimal("1200.00"),
                new BigDecimal("400.00"),
                new BigDecimal("400.00"),
                "Irreversible pulpitis #16",
                "Endodontic therapy + Zirconia crown");

        ClinicRbacModels.ScopedTreatmentPlanView receptionistView = rbac.viewTreatmentPlan(receptionistAId, fullPlan);
        assertThat(receptionistView.status()).isEqualTo("IN_PROGRESS");
        assertThat(receptionistView.diagnosis()).isNull();
        assertThat(receptionistView.clinicalNotes()).isNull();
        assertThat(receptionistView.plannedTotal()).isNull();
    }

    @Test
    @DisplayName("4. doctor is limited to assigned patients unless doctor_sees_all_patients is ON")
    void doctorPatientAssignmentScopeAndClinicSetting() {
        // Assigned patient succeeds
        rbac.viewMedicalRecord(doctorAId, CLINIC_A, PATIENT_ASSIGNED);
        rbac.editMedicalRecord(doctorAId, CLINIC_A, PATIENT_ASSIGNED, "enc-1", "Completed RCT #16");

        // Unassigned patient is denied when doctor_sees_all_patients is OFF (default)
        assertThat(rbac.isDoctorSeesAllPatients(CLINIC_A)).isFalse();
        assertThatThrownBy(() -> rbac.viewMedicalRecord(doctorAId, CLINIC_A, PATIENT_UNASSIGNED))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("assigned patients only");
        assertThatThrownBy(() -> rbac.viewPatientInvoices(doctorAId, CLINIC_A, PATIENT_UNASSIGNED))
                .isInstanceOf(AccessDeniedException.class);

        // Turn ON clinic setting doctor_sees_all_patients -> doctor can now access all clinic patients
        rbac.setDoctorSeesAllPatients(clinicAdminAId, CLINIC_A, true);
        assertThat(rbac.isDoctorSeesAllPatients(CLINIC_A)).isTrue();

        rbac.viewMedicalRecord(doctorAId, CLINIC_A, PATIENT_UNASSIGNED);
        rbac.viewPatientInvoices(doctorAId, CLINIC_A, PATIENT_UNASSIGNED);
    }

    @Test
    @DisplayName("5. accountant is denied clinical data and clinic settings, sees treatment plan financials only, and can discount/cancel invoices with mandatory reason")
    void accountantPermissionsAndBoundaries() {
        // Denied clinical data and clinic settings
        assertThatThrownBy(() -> rbac.viewMedicalRecord(accountantAId, CLINIC_A, PATIENT_ASSIGNED))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> rbac.requirePermission(
                accountantAId, CLINIC_A, ClinicPermissionCatalog.CLINIC_SETTINGS_MANAGE))
                .isInstanceOf(AccessDeniedException.class);

        // Treatment plan: sees financial figures only, no clinical details
        ClinicRbacModels.ScopedTreatmentPlanView fullPlan = new ClinicRbacModels.ScopedTreatmentPlanView(
                UUID.randomUUID(),
                CLINIC_A,
                PATIENT_ASSIGNED,
                doctorAId,
                "IN_PROGRESS",
                new BigDecimal("1200.00"),
                new BigDecimal("400.00"),
                new BigDecimal("400.00"),
                "Irreversible pulpitis #16",
                "Endodontic therapy + Zirconia crown");

        ClinicRbacModels.ScopedTreatmentPlanView accountantView = rbac.viewTreatmentPlan(accountantAId, fullPlan);
        assertThat(accountantView.plannedTotal()).isEqualByComparingTo("1200.00");
        assertThat(accountantView.invoicedTotal()).isEqualByComparingTo("400.00");
        assertThat(accountantView.collectedTotal()).isEqualByComparingTo("400.00");
        assertThat(accountantView.diagnosis()).isNull();
        assertThat(accountantView.clinicalNotes()).isNull();

        // Can apply discount and cancel invoice (cancellation requires mandatory reason)
        rbac.applyDiscount(accountantAId, CLINIC_A, "INV-200", new BigDecimal("50.00"), "Insurance adjustment");
        assertThatThrownBy(() -> rbac.cancelInvoice(accountantAId, CLINIC_A, "INV-200", "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cancellation reason is mandatory");
        rbac.cancelInvoice(accountantAId, CLINIC_A, "INV-200", "Duplicate billing entry");
    }

    @Test
    @DisplayName("6. Multi-role user (clinic_admin + doctor) receives the exact union of both roles' permissions, and custom roles work")
    void multiRoleUnionAndCustomRoles() {
        UUID ownerDoctorId = UUID.randomUUID();
        rbac.registerUser(
                ownerDoctorId,
                CLINIC_A,
                "owner.dr@clinic-a.local",
                "Dr. Owner",
                Set.of(ClinicPermissionCatalog.ROLE_CLINIC_ADMIN, ClinicPermissionCatalog.ROLE_DOCTOR));

        Set<String> union = rbac.resolveEffectivePermissions(ownerDoctorId);
        assertThat(union)
                .containsAll(ClinicPermissionCatalog.CLINIC_ADMIN_PERMISSIONS)
                .containsAll(ClinicPermissionCatalog.DOCTOR_PERMISSIONS)
                .contains(
                        ClinicPermissionCatalog.CLINIC_SETTINGS_MANAGE,
                        ClinicPermissionCatalog.DISCOUNTS_APPLY,
                        ClinicPermissionCatalog.CLINICAL_WRITE_RECORD,
                        ClinicPermissionCatalog.TREATMENT_PLANS_MANAGE);

        // Custom role creation (e.g. "dental_assistant") from the permission catalog
        ClinicRbacModels.ClinicRoleDefinition assistantRole = rbac.createCustomRole(
                clinicAdminAId,
                CLINIC_A,
                "dental_assistant",
                "Dental Assistant",
                Set.of(
                        ClinicPermissionCatalog.PATIENTS_VIEW_ADMIN,
                        ClinicPermissionCatalog.APPOINTMENTS_VIEW,
                        ClinicPermissionCatalog.CLINICAL_VIEW_HISTORY,
                        ClinicPermissionCatalog.CLINICAL_VIEW_RECORD));
        assertThat(assistantRole.isPreset()).isFalse();
        assertThat(assistantRole.permissions()).containsExactlyInAnyOrder(
                ClinicPermissionCatalog.PATIENTS_VIEW_ADMIN,
                ClinicPermissionCatalog.APPOINTMENTS_VIEW,
                ClinicPermissionCatalog.CLINICAL_VIEW_HISTORY,
                ClinicPermissionCatalog.CLINICAL_VIEW_RECORD);
    }

    @Test
    @DisplayName("7. Mandatory audit log captures all 6 sensitive operation categories")
    void mandatoryAuditLogForAllSensitiveOperations() {
        rbac.editMedicalRecord(doctorAId, CLINIC_A, PATIENT_ASSIGNED, "ENC-55", "Updated diagnosis");
        rbac.createInvoice(receptionistAId, CLINIC_A, "INV-55", new BigDecimal("300.00"));
        rbac.applyDiscount(accountantAId, CLINIC_A, "INV-55", new BigDecimal("30.00"), "Loyalty 10%");
        rbac.cancelInvoice(accountantAId, CLINIC_A, "INV-55", "Reissued under new plan");
        rbac.setUserSuspended(clinicAdminAId, CLINIC_A, receptionistAId, true, "Extended leave");
        rbac.recordFailedLoginAttempt(CLINIC_A, "intruder@clinic-a.local");
        rbac.recordFailedLoginAttempt(CLINIC_A, "intruder@clinic-a.local");
        rbac.recordFailedLoginAttempt(CLINIC_A, "intruder@clinic-a.local");

        Map<String, Boolean> expectedActions = Map.of(
                ClinicRbacGuardService.AUDIT_MEDICAL_RECORD_EDITED, true,
                ClinicRbacGuardService.AUDIT_INVOICE_CREATED, true,
                ClinicRbacGuardService.AUDIT_DISCOUNT_APPLIED, true,
                ClinicRbacGuardService.AUDIT_INVOICE_CANCELLED, true,
                ClinicRbacGuardService.AUDIT_USER_STATUS_CHANGED, true,
                ClinicRbacGuardService.AUDIT_REPEATED_FAILED_LOGIN, true);

        List<String> loggedActions = rbac.getAuditLogForClinic(CLINIC_A).stream()
                .map(ClinicRbacModels.RbacAuditEntry::action)
                .toList();
        assertThat(loggedActions).containsAll(expectedActions.keySet());
    }
}
