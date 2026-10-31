package com.firstelement.crm.clinic.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstelement.crm.CrmFixture;
import com.firstelement.crm.CrmIntegrationTestBase;
import com.firstelement.crm.clinic.ClinicScope;
import com.firstelement.crm.clinic.patient.PatientModels;
import com.firstelement.crm.clinic.patient.PatientService;
import com.firstelement.security.query.TenantQueryGuard;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Integration tests for Dental Clinic Slice S8:
 * Immutable / Tamper-Evident Audit Logging, Break-Glass Emergency Access, and Curated Tenant-Scoped Reports.
 */
@SpringBootTest(classes = com.firstelement.crm.CrmTestApplication.class)
class ClinicAuditServiceIntegrationTest extends CrmIntegrationTestBase {

    @Autowired
    private ClinicAuditService service;

    @Autowired
    private PatientService patients;

    @Autowired
    private CrmFixture fixture;

    @Autowired
    private TenantQueryGuard guard;

    @BeforeEach
    void resetDatabase() {
        fixture.reset();
    }

    private ClinicScope tenantAScope() {
        return ClinicScope.forRequestedProject(guard, fixture.adminAContext(), fixture.projectA1);
    }

    private ClinicScope tenantBScope() {
        return ClinicScope.forRequestedProject(guard, fixture.adminBContext(), fixture.projectB1);
    }

    private UUID createPatient(ClinicScope scope, String name, String phone) {
        String patientNo = "P-" + UUID.randomUUID().toString().substring(0, 8);
        PatientModels.PatientRow patient = patients.create(
                scope,
                patientNo,
                name,
                "First",
                "Last",
                LocalDate.of(1990, 1, 1),
                phone,
                "patient@example.com",
                "ar",
                null);
        return patient.id();
    }

    @Test
    @DisplayName("AUD-01: Break-glass request validates mandatory emergency justification and defaults to 4 hours")
    void breakGlassRequestValidatesMandatoryReason() {
        ClinicScope scope = tenantAScope();
        UUID patientId = createPatient(scope, "Emergency Patient A", "+966500112233");

        // Fails with empty or short reason
        assertThatThrownBy(() -> service.requestBreakGlass(
                scope,
                fixture.staffA,
                "Dr. Sarah",
                "DOCTOR",
                new ClinicAuditModels.CreateBreakGlassRequest(patientId, "help", 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 5 characters");

        // Succeeds with valid detailed reason
        ClinicAuditModels.BreakGlassGrantRow grant = service.requestBreakGlass(
                scope,
                fixture.staffA,
                "Dr. Sarah",
                "DOCTOR",
                new ClinicAuditModels.CreateBreakGlassRequest(
                        patientId,
                        "Acute severe dental trauma requiring urgent full-chart historical review.",
                        0));

        assertThat(grant).isNotNull();
        assertThat(grant.id()).isNotNull();
        assertThat(grant.patientId()).isEqualTo(patientId);
        assertThat(grant.requestedByUserId()).isEqualTo(fixture.staffA);
        assertThat(grant.reviewStatus()).isEqualTo("PENDING");
        assertThat(grant.revokedAt()).isNull();
        assertThat(grant.isActive(Instant.now())).isTrue();
        // Default 4 hours duration
        Duration duration = Duration.between(grant.grantedAt(), grant.expiresAt());
        assertThat(duration.toHours()).isEqualTo(4);

        // Verification of active break-glass
        assertThat(service.hasActiveBreakGlass(scope, fixture.staffA, patientId)).isTrue();

        // Audit log created automatically
        List<ClinicAuditModels.ClinicAuditRow> audits = service.queryAuditLog(
                scope, patientId, null, "BREAK_GLASS_ACTIVATED", null, null, null, 10);
        assertThat(audits).hasSize(1);
        assertThat(audits.getFirst().resourceId()).isEqualTo(grant.id().toString());
    }

    @Test
    @DisplayName("AUD-02: Break-glass emergency access can be revoked early with reason")
    void breakGlassRevocationFlow() {
        ClinicScope scope = tenantAScope();
        UUID patientId = createPatient(scope, "Emergency Patient B", "+966500112234");

        ClinicAuditModels.BreakGlassGrantRow grant = service.requestBreakGlass(
                scope,
                fixture.staffA,
                "Dr. Sarah",
                "DOCTOR",
                new ClinicAuditModels.CreateBreakGlassRequest(
                        patientId,
                        "Emergency post-op hemorrhage requiring urgent procedure history.",
                        2));

        assertThat(service.hasActiveBreakGlass(scope, fixture.staffA, patientId)).isTrue();

        boolean revoked = service.revokeBreakGlass(
                scope,
                grant.id(),
                fixture.staffA,
                "Dr. Sarah",
                "DOCTOR",
                new ClinicAuditModels.RevokeBreakGlassRequest("Emergency treated and stabilized; access no longer required."));

        assertThat(revoked).isTrue();

        // No longer active
        assertThat(service.hasActiveBreakGlass(scope, fixture.staffA, patientId)).isFalse();

        Optional<ClinicAuditModels.BreakGlassGrantRow> reloaded = service.getBreakGlassGrant(scope, grant.id());
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().revokedAt()).isNotNull();
        assertThat(reloaded.get().revocationReason()).contains("stabilized");

        // Audit log created for revocation
        List<ClinicAuditModels.ClinicAuditRow> audits = service.queryAuditLog(
                scope, null, null, "BREAK_GLASS_REVOKED", null, null, null, 10);
        assertThat(audits).hasSize(1);
    }

    @Test
    @DisplayName("AUD-03: Break-glass requires compliance review by clinical director")
    void breakGlassComplianceReviewFlow() {
        ClinicScope scope = tenantAScope();
        UUID patientId = createPatient(scope, "Emergency Patient C", "+966500112235");

        ClinicAuditModels.BreakGlassGrantRow grant = service.requestBreakGlass(
                scope,
                fixture.staffA,
                "Dr. Sarah",
                "DOCTOR",
                new ClinicAuditModels.CreateBreakGlassRequest(
                        patientId,
                        "Immediate anaphylaxis protocol - allergy history needed.",
                        1));

        List<ClinicAuditModels.BreakGlassGrantRow> pending = service.listBreakGlassPendingReview(scope);
        assertThat(pending).extracting(ClinicAuditModels.BreakGlassGrantRow::id).contains(grant.id());

        // Flagging without notes throws exception
        assertThatThrownBy(() -> service.reviewBreakGlass(
                scope,
                grant.id(),
                fixture.adminA,
                "Dr. Chief Admin",
                "ADMIN",
                new ClinicAuditModels.ReviewBreakGlassRequest("FLAGGED", "   ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mandatory when flagging");

        // Approving with valid notes
        boolean reviewed = service.reviewBreakGlass(
                scope,
                grant.id(),
                fixture.adminA,
                "Dr. Chief Admin",
                "ADMIN",
                new ClinicAuditModels.ReviewBreakGlassRequest("APPROVED", "Verified clinical emergency chart notes."));

        assertThat(reviewed).isTrue();

        // No longer pending review
        List<ClinicAuditModels.BreakGlassGrantRow> pendingAfter = service.listBreakGlassPendingReview(scope);
        assertThat(pendingAfter).extracting(ClinicAuditModels.BreakGlassGrantRow::id).doesNotContain(grant.id());

        Optional<ClinicAuditModels.BreakGlassGrantRow> reloaded = service.getBreakGlassGrant(scope, grant.id());
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().reviewStatus()).isEqualTo("APPROVED");
        assertThat(reloaded.get().reviewedByUserId()).isEqualTo(fixture.adminA);
        assertThat(reloaded.get().reviewNotes()).contains("Verified clinical emergency");
    }

    @Test
    @DisplayName("AUD-04: Tamper-evident SHA-256 chain links consecutive audit records cryptographically")
    void tamperEvidentSha256AuditTrail() {
        ClinicScope scope = tenantAScope();
        UUID patientId = createPatient(scope, "Audit Chain Patient", "+966500112236");

        // Record a series of clinical events
        service.recordAuditEvent(scope, new ClinicAuditModels.RecordClinicAuditRequest(
                patientId, fixture.staffA, "Dr. Sarah", "DOCTOR",
                "READ_PATIENT_RECORD", "PATIENT", patientId.toString(), null, "127.0.0.1", "TestClient/1.0", null));

        service.recordAuditEvent(scope, new ClinicAuditModels.RecordClinicAuditRequest(
                patientId, fixture.staffA, "Dr. Sarah", "DOCTOR",
                "WRITE_CLINICAL_NOTE", "CLINICAL_NOTE", UUID.randomUUID().toString(), null, "127.0.0.1", "TestClient/1.0", "{\"note\": \"Exam completed\"}"));

        service.recordAuditEvent(scope, new ClinicAuditModels.RecordClinicAuditRequest(
                patientId, fixture.staffA, "Dr. Sarah", "DOCTOR",
                "VIEW_MEDIA", "MEDIA", UUID.randomUUID().toString(), null, "127.0.0.1", "TestClient/1.0", null));

        service.recordAuditEvent(scope, new ClinicAuditModels.RecordClinicAuditRequest(
                patientId, fixture.staffA, "Dr. Sarah", "DOCTOR",
                "SIGN_CLINICAL_NOTE", "CLINICAL_NOTE", UUID.randomUUID().toString(), null, "127.0.0.1", "TestClient/1.0", null));

        // Verify cryptographical integrity of the entire chain
        boolean valid = service.verifyAuditChainIntegrity(scope);
        assertThat(valid).isTrue();

        List<ClinicAuditModels.ClinicAuditRow> list = service.queryAuditLog(
                scope, patientId, null, null, null, null, null, 10);
        assertThat(list).hasSize(4);

        // Every row has a valid SHA-256 hex string (64 characters)
        for (ClinicAuditModels.ClinicAuditRow row : list) {
            assertThat(row.tamperHash()).matches("^[a-f0-9]{64}$");
        }
    }

    @Test
    @DisplayName("AUD-05: Multi-tenant isolation prevents cross-tenant access to audit logs or break-glass grants")
    void tenantIsolationBreakGlassAndAudit() {
        ClinicScope scopeA = tenantAScope();
        ClinicScope scopeB = tenantBScope();

        UUID patientA = createPatient(scopeA, "Tenant A Patient", "+966500112237");

        ClinicAuditModels.BreakGlassGrantRow grantA = service.requestBreakGlass(
                scopeA,
                fixture.staffA,
                "Dr. Sarah",
                "DOCTOR",
                new ClinicAuditModels.CreateBreakGlassRequest(
                        patientA, "Emergency access for tenant A patient.", 4));

        // Tenant B cannot see Tenant A's break-glass grants
        assertThat(service.getBreakGlassGrant(scopeB, grantA.id())).isEmpty();
        assertThat(service.listActiveBreakGlass(scopeB)).extracting(ClinicAuditModels.BreakGlassGrantRow::id)
                .doesNotContain(grantA.id());
        assertThat(service.hasActiveBreakGlass(scopeB, fixture.staffA, patientA)).isFalse();

        // Tenant B cannot query Tenant A's audit logs
        List<ClinicAuditModels.ClinicAuditRow> logsB = service.queryAuditLog(
                scopeB, null, null, null, null, null, null, 100);
        assertThat(logsB).isEmpty();
    }

    @Test
    @DisplayName("RPT-01 to RPT-05: Curated parameterized reports return clean aggregates and support audit export")
    void curatedReportsAndExportFlow() {
        ClinicScope scope = tenantAScope();
        UUID patientId = createPatient(scope, "Reporting Patient", "+966500112238");

        Instant from = Instant.now().minus(Duration.ofDays(7));
        Instant to = Instant.now();

        // 1. Appointment Productivity
        List<ClinicAuditModels.AppointmentProductivityRow> productivity = service.getAppointmentProductivity(
                scope, from, to, null);
        assertThat(productivity).isNotNull();

        // 2. Procedure Utilization
        List<ClinicAuditModels.ProcedureUtilizationRow> procedures = service.getProcedureUtilization(
                scope, from, to);
        assertThat(procedures).isNotNull();

        // 3. Financial Collections
        List<ClinicAuditModels.FinancialCollectionSummaryRow> collections = service.getFinancialCollectionSummary(
                scope, from, to);
        assertThat(collections).isNotNull();

        // 4. Patient Recall Retention
        List<ClinicAuditModels.PatientRetentionRecallRow> recall = service.getPatientRetentionRecall(
                scope, 180);
        assertThat(recall).isNotNull();
        assertThat(recall).extracting(ClinicAuditModels.PatientRetentionRecallRow::patientId).contains(patientId);

        // 5. Break-Glass Compliance
        List<ClinicAuditModels.BreakGlassComplianceRow> compliance = service.getBreakGlassCompliance(
                scope, from, to);
        assertThat(compliance).isNotNull();

        // 6. Export report with cryptographic signature and audit trail
        ClinicAuditModels.ClinicAuditExportView export = service.exportReport(
                scope,
                "APPOINTMENTS",
                from,
                to,
                fixture.adminA,
                "Alpha Admin",
                "ADMIN");

        assertThat(export).isNotNull();
        assertThat(export.exportType()).isEqualTo("APPOINTMENTS");
        assertThat(export.exportSignature()).matches("^[a-f0-9]{64}$");

        // Verify that DATA_EXPORTED audit event was logged
        List<ClinicAuditModels.ClinicAuditRow> exportAudits = service.queryAuditLog(
                scope, null, fixture.adminA, "DATA_EXPORTED", "REPORT", null, null, 10);
        assertThat(exportAudits).hasSize(1);
        assertThat(exportAudits.getFirst().resourceId()).isEqualTo("APPOINTMENTS");
    }
}
