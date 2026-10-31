package com.firstelement.crm.clinic.treatment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstelement.crm.CrmFixture;
import com.firstelement.crm.CrmIntegrationTestBase;
import com.firstelement.crm.clinic.ClinicScope;
import com.firstelement.crm.clinic.patient.PatientService;
import com.firstelement.crm.error.ResourceNotFoundException;
import com.firstelement.security.query.TenantQueryGuard;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Integration tests for Dental Treatment Plans (TRT-01 to TRT-05, Slice S5).
 *
 * <p>Validates state machine transitions, approved plan immutability,
 * version forking, financial estimates, multi-visit stages, and tenant isolation.
 */
@SpringBootTest(classes = com.firstelement.crm.CrmTestApplication.class)
class TreatmentPlanServiceIntegrationTest extends CrmIntegrationTestBase {

    @Autowired
    private TreatmentPlanService service;

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

    private UUID newPatient(ClinicScope scope, String patientNo) {
        return patients.create(scope, patientNo, "Test Dental Patient", "Dental", "Patient", null,
                "+96279" + Math.abs(patientNo.hashCode() % 10000000), null, "ar", null).id();
    }

    @Test
    @DisplayName("creates treatment plan with financial estimates and multi-visit procedure items")
    void createsPlanWithFinancialEstimatesAndItems() {
        ClinicScope scope = tenantAScope();
        UUID patientId = newPatient(scope, "P-TRT-01");

        TreatmentPlanModels.CreatePlanRequest request = new TreatmentPlanModels.CreatePlanRequest(
                "Full Rehabilitation Plan",
                "Phased comprehensive dental care",
                fixture.projectA1.toString(),
                "PRIMARY",
                null,
                new BigDecimal("50.000"),
                new BigDecimal("200.000"),
                new BigDecimal("550.000")
        );

        UUID planId = service.createPlan(scope, patientId, request);
        assertThat(planId).isNotNull();

        // Add Stage 1 item (multi-visit endodontics on FDI tooth 16)
        UUID item1Id = service.addItem(scope, planId, new TreatmentPlanModels.CreatePlanItemRequest(
                1,
                "PHASE_1",
                2, // 2 visits
                null,
                "Root Canal Treatment",
                16,
                null,
                new BigDecimal("250.000"),
                new BigDecimal("20.000"),
                new BigDecimal("80.000"),
                new BigDecimal("150.000"),
                "Molar RCT 2 sessions"
        ));

        // Add Stage 2 item (single visit crown on FDI tooth 16)
        UUID item2Id = service.addItem(scope, planId, new TreatmentPlanModels.CreatePlanItemRequest(
                2,
                "PHASE_2",
                1,
                null,
                "Zirconia Crown",
                16,
                null,
                new BigDecimal("300.000"),
                BigDecimal.ZERO,
                new BigDecimal("100.000"),
                new BigDecimal("200.000"),
                "Post-endo restoration"
        ));

        TreatmentPlanModels.TreatmentPlanDetail detail = service.getPlanDetail(scope, planId);
        assertThat(detail.plan().title()).isEqualTo("Full Rehabilitation Plan");
        assertThat(detail.plan().version()).isEqualTo(1);
        assertThat(detail.plan().status()).isEqualTo("DRAFT");
        assertThat(detail.plan().totalDiscount()).isEqualByComparingTo("50.000");
        assertThat(detail.plan().insuranceEstimatedCoverage()).isEqualByComparingTo("200.000");
        assertThat(detail.plan().patientEstimatedPortion()).isEqualByComparingTo("550.000");

        assertThat(detail.items()).hasSize(2);
        assertThat(detail.items().get(0).id()).isEqualTo(item1Id);
        assertThat(detail.items().get(0).stage()).isEqualTo("PHASE_1");
        assertThat(detail.items().get(0).plannedVisits()).isEqualTo(2);
        assertThat(detail.items().get(0).completedVisits()).isEqualTo(0);
        assertThat(detail.items().get(0).toothFdi()).isEqualTo(16);

        assertThat(detail.items().get(1).id()).isEqualTo(item2Id);
        assertThat(detail.items().get(1).stage()).isEqualTo("PHASE_2");
        assertThat(detail.items().get(1).plannedVisits()).isEqualTo(1);
    }

    @Test
    @DisplayName("validates FDI tooth boundaries (11-85 or null allowed)")
    void validatesFdiToothRange() {
        ClinicScope scope = tenantAScope();
        UUID patientId = newPatient(scope, "P-TRT-FDI");

        UUID planId = service.createPlan(scope, patientId, new TreatmentPlanModels.CreatePlanRequest(
                "FDI Test Plan", null, fixture.projectA1.toString(), "PRIMARY", null, null, null, null
        ));

        // Tooth FDI 99 is invalid
        assertThatThrownBy(() -> service.addItem(scope, planId, new TreatmentPlanModels.CreatePlanItemRequest(
                1, "PHASE_1", 1, null, "Invalid Tooth", 99, null, BigDecimal.TEN, null, null, null, null
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Tooth FDI number must be between 11 and 85");

        // Tooth FDI 10 is invalid
        assertThatThrownBy(() -> service.addItem(scope, planId, new TreatmentPlanModels.CreatePlanItemRequest(
                1, "PHASE_1", 1, null, "Invalid Tooth", 10, null, BigDecimal.TEN, null, null, null, null
        ))).isInstanceOf(IllegalArgumentException.class);

        // Null tooth FDI (general procedure like full mouth prophylaxis) is valid
        UUID generalItemId = service.addItem(scope, planId, new TreatmentPlanModels.CreatePlanItemRequest(
                1, "PHASE_1", 1, null, "Full Mouth Scaling", null, null, new BigDecimal("40.000"), null, null, null, "General"
        ));
        assertThat(generalItemId).isNotNull();
    }

    @Test
    @DisplayName("enforces legal state machine transitions and rejects illegal ones")
    void enforcesStateMachineTransitions() {
        ClinicScope scope = tenantAScope();
        UUID patientId = newPatient(scope, "P-TRT-SM");

        UUID planId = service.createPlan(scope, patientId, new TreatmentPlanModels.CreatePlanRequest(
                "State Machine Plan", null, fixture.projectA1.toString(), "PRIMARY", null, null, null, null
        ));

        // Initial state is DRAFT
        TreatmentPlanModels.TreatmentPlanHeader header = service.findPlan(scope, planId).orElseThrow();
        assertThat(header.status()).isEqualTo("DRAFT");

        // Illegal: DRAFT -> COMPLETED
        assertThatThrownBy(() -> service.updatePlanStatus(scope, planId,
                new TreatmentPlanModels.UpdatePlanStatusRequest("COMPLETED", "Premature completion")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Illegal treatment plan status transition");

        // Legal: DRAFT -> PRESENTED
        service.updatePlanStatus(scope, planId,
                new TreatmentPlanModels.UpdatePlanStatusRequest("PRESENTED", "Presented to patient with cost estimate"));
        assertThat(service.findPlan(scope, planId).orElseThrow().status()).isEqualTo("PRESENTED");

        // Legal: PRESENTED -> ACTIVE
        service.updatePlanStatus(scope, planId,
                new TreatmentPlanModels.UpdatePlanStatusRequest("ACTIVE", "Patient accepted and commenced treatment"));
        assertThat(service.findPlan(scope, planId).orElseThrow().status()).isEqualTo("ACTIVE");

        // Illegal: ACTIVE -> DRAFT (cannot un-activate)
        assertThatThrownBy(() -> service.updatePlanStatus(scope, planId,
                new TreatmentPlanModels.UpdatePlanStatusRequest("DRAFT", "Revert to draft")))
                .isInstanceOf(IllegalStateException.class);

        // Legal: ACTIVE -> COMPLETED
        service.updatePlanStatus(scope, planId,
                new TreatmentPlanModels.UpdatePlanStatusRequest("COMPLETED", "All planned procedures finished"));
        assertThat(service.findPlan(scope, planId).orElseThrow().status()).isEqualTo("COMPLETED");

        // Terminal: COMPLETED cannot transition anywhere
        assertThatThrownBy(() -> service.updatePlanStatus(scope, planId,
                new TreatmentPlanModels.UpdatePlanStatusRequest("ACTIVE", "Reopen plan")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("approved plans are immutable and require forking a new version to modify")
    void approvedPlansAreImmutableAndForkable() {
        ClinicScope scope = tenantAScope();
        UUID patientId = newPatient(scope, "P-TRT-IMMUTABLE");

        UUID v1PlanId = service.createPlan(scope, patientId, new TreatmentPlanModels.CreatePlanRequest(
                "Original Ortho Plan", "Initial assessment", fixture.projectA1.toString(), "PRIMARY",
                null, new BigDecimal("100.000"), new BigDecimal("300.000"), new BigDecimal("800.000")
        ));

        UUID item1 = service.addItem(scope, v1PlanId, new TreatmentPlanModels.CreatePlanItemRequest(
                1, "PHASE_1", 1, null, "Diagnostic Casts & Ceph", null, null,
                new BigDecimal("150.000"), null, null, null, "Pre-treatment records"
        ));

        UUID item2 = service.addItem(scope, v1PlanId, new TreatmentPlanModels.CreatePlanItemRequest(
                2, "PHASE_2", 12, null, "Clear Aligners", null, null,
                new BigDecimal("1050.000"), new BigDecimal("100.000"), new BigDecimal("300.000"), new BigDecimal("650.000"), "12 stages"
        ));

        // Approve and activate plan v1
        service.approvePlan(scope, v1PlanId, fixture.adminAContext().userId(),
                new TreatmentPlanModels.ApprovePlanRequest("Informed consent signed by patient"));

        TreatmentPlanModels.TreatmentPlanHeader v1Header = service.findPlan(scope, v1PlanId).orElseThrow();
        assertThat(v1Header.status()).isEqualTo("ACTIVE");
        assertThat(v1Header.approvedAt()).isNotNull();
        assertThat(v1Header.approvedByUserId()).isEqualTo(fixture.adminAContext().userId());

        // Attempting to add an item to approved plan v1 must fail
        assertThatThrownBy(() -> service.addItem(scope, v1PlanId, new TreatmentPlanModels.CreatePlanItemRequest(
                3, "PHASE_3", 1, null, "Retainer", null, null, new BigDecimal("100.000"), null, null, null, "Post-treatment"
        ))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("immutable. Fork a new version to modify");

        // Attempting to delete an item from approved plan v1 must fail
        assertThatThrownBy(() -> service.deleteItem(scope, v1PlanId, item1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("immutable");

        // Attempting to delete the approved plan itself must fail
        assertThatThrownBy(() -> service.deletePlan(scope, v1PlanId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Approved or active treatment plans cannot be deleted");

        // Clinician forks plan to v2 to modify treatment course
        UUID v2PlanId = service.forkNewVersion(scope, v1PlanId, new TreatmentPlanModels.ForkPlanVersionRequest(
                "Ortho Plan (Mid-Course Correction)", "Patient requested switch to ceramic brackets"
        ));
        assertThat(v2PlanId).isNotNull().isNotEqualTo(v1PlanId);

        TreatmentPlanModels.TreatmentPlanDetail v2Detail = service.getPlanDetail(scope, v2PlanId);
        assertThat(v2Detail.plan().version()).isEqualTo(2);
        assertThat(v2Detail.plan().parentPlanId()).isEqualTo(v1PlanId);
        assertThat(v2Detail.plan().status()).isEqualTo("DRAFT");
        assertThat(v2Detail.plan().title()).isEqualTo("Ortho Plan (Mid-Course Correction)");
        assertThat(v2Detail.plan().notes()).isEqualTo("Patient requested switch to ceramic brackets");

        // v2 inherits copies of original items
        assertThat(v2Detail.items()).hasSize(2);
        assertThat(v2Detail.items().get(0).procedureName()).isEqualTo("Diagnostic Casts & Ceph");
        assertThat(v2Detail.items().get(1).procedureName()).isEqualTo("Clear Aligners");

        // In v2 (which is DRAFT), items CAN be modified, added, and deleted freely!
        UUID item3 = service.addItem(scope, v2PlanId, new TreatmentPlanModels.CreatePlanItemRequest(
                3, "PHASE_2", 6, null, "Ceramic Brackets Adjustment", null, null,
                new BigDecimal("400.000"), null, null, null, "6 adjustments"
        ));
        assertThat(item3).isNotNull();

        TreatmentPlanModels.TreatmentPlanDetail v2Updated = service.getPlanDetail(scope, v2PlanId);
        assertThat(v2Updated.items()).hasSize(3);

        // Original plan v1 is still untouched, ACTIVE, and has exactly 2 items
        TreatmentPlanModels.TreatmentPlanDetail v1After = service.getPlanDetail(scope, v1PlanId);
        assertThat(v1After.plan().status()).isEqualTo("ACTIVE");
        assertThat(v1After.items()).hasSize(2);
    }

    @Test
    @DisplayName("records patient consent flag, registrar, and notes")
    void recordsPatientConsent() {
        ClinicScope scope = tenantAScope();
        UUID patientId = newPatient(scope, "P-TRT-CONSENT");

        UUID planId = service.createPlan(scope, patientId, new TreatmentPlanModels.CreatePlanRequest(
                "Consent Test Plan", null, fixture.projectA1.toString(), "PRIMARY", null, null, null, null
        ));

        service.recordConsent(scope, planId, fixture.adminAContext().userId(),
                new TreatmentPlanModels.RecordConsentRequest(true, "Patient signed digital informed consent form on tablet"));

        TreatmentPlanModels.TreatmentPlanHeader header = service.findPlan(scope, planId).orElseThrow();
        assertThat(header.patientConsent()).isTrue();
        assertThat(header.consentAt()).isNotNull();
        assertThat(header.consentByUserId()).isEqualTo(fixture.adminAContext().userId());
        assertThat(header.consentNotes()).isEqualTo("Patient signed digital informed consent form on tablet");
    }

    @Test
    @DisplayName("cancels treatment plan with recorded cancellation reason")
    void cancelsTreatmentPlanWithReason() {
        ClinicScope scope = tenantAScope();
        UUID patientId = newPatient(scope, "P-TRT-CANCEL");

        UUID planId = service.createPlan(scope, patientId, new TreatmentPlanModels.CreatePlanRequest(
                "To Be Cancelled", null, fixture.projectA1.toString(), "PRIMARY", null, null, null, null
        ));

        service.cancelPlan(scope, planId, new TreatmentPlanModels.CancelPlanRequest(
                "Patient relocated to another city"));

        TreatmentPlanModels.TreatmentPlanHeader header = service.findPlan(scope, planId).orElseThrow();
        assertThat(header.status()).isEqualTo("CANCELLED");
        assertThat(header.cancellationReason()).isEqualTo("Patient relocated to another city");
        assertThat(header.cancelledAt()).isNotNull();

        // Modifying cancelled plan fails
        assertThatThrownBy(() -> service.addItem(scope, planId, new TreatmentPlanModels.CreatePlanItemRequest(
                1, "PHASE_1", 1, null, "Test", 11, null, BigDecimal.TEN, null, null, null, null
        ))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot modify a cancelled treatment plan");
    }

    @Test
    @DisplayName("tenant isolation: Tenant B cannot read or modify Tenant A treatment plans")
    void enforcesTenantIsolation() {
        ClinicScope scopeA = tenantAScope();
        ClinicScope scopeB = tenantBScope();

        UUID patientA = newPatient(scopeA, "P-TRT-ISO-A");
        UUID planA = service.createPlan(scopeA, patientA, new TreatmentPlanModels.CreatePlanRequest(
                "Tenant A Confidential Plan", null, fixture.projectA1.toString(), "PRIMARY", null, null, null, null
        ));

        // Tenant B finds nothing
        assertThat(service.findPlan(scopeB, planA)).isEmpty();

        // Tenant B cannot get detail
        assertThatThrownBy(() -> service.getPlanDetail(scopeB, planA))
                .isInstanceOf(ResourceNotFoundException.class);

        // Tenant B cannot fork Tenant A's plan
        assertThatThrownBy(() -> service.forkNewVersion(scopeB, planA,
                new TreatmentPlanModels.ForkPlanVersionRequest("Intrusion Plan", "Malicious fork")))
                .isInstanceOf(ResourceNotFoundException.class);

        // Tenant B cannot add items to Tenant A's plan
        assertThatThrownBy(() -> service.addItem(scopeB, planA, new TreatmentPlanModels.CreatePlanItemRequest(
                1, "PHASE_1", 1, null, "Malicious Item", 11, null, BigDecimal.TEN, null, null, null, null
        ))).isInstanceOf(ResourceNotFoundException.class);
    }
}
