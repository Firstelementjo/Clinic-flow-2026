package com.firstelement.crm.clinic.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstelement.crm.CrmFixture;
import com.firstelement.crm.CrmIntegrationTestBase;
import com.firstelement.crm.clinic.ClinicScope;
import com.firstelement.crm.clinic.audit.ClinicAuditModels;
import com.firstelement.crm.clinic.audit.ClinicAuditService;
import com.firstelement.crm.clinic.patient.PatientModels;
import com.firstelement.crm.clinic.patient.PatientService;
import com.firstelement.security.query.TenantQueryGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Integration tests for Dental Clinic Slice S9:
 * Governed AI Assistant, Emergency Kill Switch, Human-in-the-Loop Reviews, and PHI Redaction.
 */
@SpringBootTest(classes = com.firstelement.crm.CrmTestApplication.class)
class ClinicAiServiceIntegrationTest extends CrmIntegrationTestBase {

    @Autowired
    private ClinicAiService aiService;

    @Autowired
    private ClinicAuditService auditService;

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
                "Patient",
                name,
                LocalDate.of(1990, 5, 12),
                phone,
                "patient." + patientNo.toLowerCase() + "@example.com",
                "ar",
                null
        );
        return patient.id();
    }

    @Test
    @DisplayName("Emergency kill switch halts all proposal generation immediately and logs audit")
    void emergencyKillSwitchHaltsProposalGenerationAndLogsAudit() {
        ClinicScope scope = tenantAScope();
        UUID patientId = createPatient(scope, "Tariq Mansour", "+962791112233");
        UUID actorUserId = fixture.adminA;

        // 1. Initial state: kill switch is inactive
        ClinicAiModels.ClinicAiGovernanceRow gov = aiService.getGovernance(scope);
        assertThat(gov.killSwitchActive()).isFalse();

        // 2. Engage emergency kill switch
        ClinicAiModels.ClinicAiGovernanceRow updated = aiService.toggleKillSwitch(
                scope, true, "Suspected prompt injection anomaly", actorUserId, "Dr. Sarah", "ADMIN");
        assertThat(updated.killSwitchActive()).isTrue();
        assertThat(updated.killSwitchReason()).contains("Suspected prompt injection anomaly");

        // 3. Attempting generation fails with IllegalStateException
        assertThatThrownBy(() -> aiService.generateVisitSummaryDraft(
                scope, new ClinicAiModels.VisitSummaryDraftRequest(patientId, null), actorUserId, "Dr. Sarah", "DOCTOR"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("kill switch");

        // 4. Verify audit event for kill switch engagement
        List<ClinicAuditModels.ClinicAuditRow> audits = auditService.queryAuditLog(
                scope, null, null, "AI_KILL_SWITCH_ENGAGED", null, null, null, 10);
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).metadataJson()).contains("killSwitchActive\": true");

        // 5. Disengage kill switch and verify proposal generation resumes
        aiService.toggleKillSwitch(scope, false, "Emergency investigation concluded", actorUserId, "Dr. Sarah", "ADMIN");
        ClinicAiModels.ClinicAiProposalRow proposal = aiService.generateVisitSummaryDraft(
                scope, new ClinicAiModels.VisitSummaryDraftRequest(patientId, null), actorUserId, "Dr. Sarah", "DOCTOR");
        assertThat(proposal).isNotNull();
        assertThat(proposal.status()).isEqualTo("DRAFT");
    }

    @Test
    @DisplayName("Proposals are always generated in DRAFT status with PHI redaction and transparent citations")
    void proposalsAlwaysGeneratedInDraftStatusWithPhiRedacted() {
        ClinicScope scope = tenantAScope();
        UUID patientId = createPatient(scope, "Layla Al-Khatib", "+962785556677");
        UUID actorUserId = fixture.adminA;

        ClinicAiModels.ClinicAiProposalRow proposal = aiService.generateVisitSummaryDraft(
                scope, new ClinicAiModels.VisitSummaryDraftRequest(patientId, null), actorUserId, "Dr. Sarah", "DOCTOR");

        assertThat(proposal.id()).isNotNull();
        assertThat(proposal.proposalType()).isEqualTo("VISIT_SUMMARY");
        assertThat(proposal.status()).isEqualTo("DRAFT");
        assertThat(proposal.phiRedacted()).isTrue();
        assertThat(proposal.confidencePercent()).isEqualTo(92);
        assertThat(proposal.outputContent()).contains("\"isDraft\": true");
        assertThat(proposal.outputContent()).contains("\"humanApprovalRequired\": true");
        assertThat(proposal.transparentFactors()).contains("clinic_encounters");
    }

    @Test
    @DisplayName("Human review approves proposal, records approver, and prevents double-review")
    void humanReviewApprovesProposalAndRecordsAudit() {
        ClinicScope scope = tenantAScope();
        UUID patientId = createPatient(scope, "Zaid Omar", "+962791234567");
        UUID actorUserId = fixture.adminA;

        ClinicAiModels.ClinicAiProposalRow draft = aiService.generateVisitSummaryDraft(
                scope, new ClinicAiModels.VisitSummaryDraftRequest(patientId, null), actorUserId, "Dr. Sarah", "DOCTOR");

        // 1. Approve proposal
        ClinicAiModels.ClinicAiProposalRow reviewed = aiService.reviewProposal(
                scope, draft.id(), new ClinicAiModels.ReviewProposalRequest("APPROVED", null),
                actorUserId, "Dr. Sarah", "DOCTOR");

        assertThat(reviewed.status()).isEqualTo("APPROVED");
        assertThat(reviewed.approvedByUserId()).isEqualTo(actorUserId);
        assertThat(reviewed.approvedAt()).isNotNull();

        // 2. Double-review is blocked
        assertThatThrownBy(() -> aiService.reviewProposal(
                scope, draft.id(), new ClinicAiModels.ReviewProposalRequest("REJECTED", "Changed mind"),
                actorUserId, "Dr. Sarah", "DOCTOR"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already been reviewed");

        // 3. Review audit event exists
        List<ClinicAuditModels.ClinicAuditRow> audits = auditService.queryAuditLog(
                scope, null, null, "AI_PROPOSAL_APPROVED", null, null, null, 10);
        assertThat(audits).hasSize(1);
    }

    @Test
    @DisplayName("Human review rejects proposal with mandatory reason recording")
    void humanReviewRejectsProposalWithReason() {
        ClinicScope scope = tenantAScope();
        UUID patientId = createPatient(scope, "Hiba Nabulsi", "+962771113355");
        UUID actorUserId = fixture.adminA;

        ClinicAiModels.ClinicAiProposalRow draft = aiService.draftPatientMessage(
                scope, new ClinicAiModels.PatientMessageDraftRequest(patientId, "RECALL", "AR"),
                actorUserId, "Receptionist", "EMPLOYEE");

        ClinicAiModels.ClinicAiProposalRow rejected = aiService.reviewProposal(
                scope, draft.id(), new ClinicAiModels.ReviewProposalRequest("REJECTED", "Patient prefers telephone contact"),
                actorUserId, "Dr. Sarah", "DOCTOR");

        assertThat(rejected.status()).isEqualTo("REJECTED");
        assertThat(rejected.rejectionReason()).isEqualTo("Patient prefers telephone contact");
    }

    @Test
    @DisplayName("Recall prioritization includes transparent risk weights and overdue calculations")
    void recallPrioritizationIncludesTransparentRiskFactors() {
        ClinicScope scope = tenantAScope();
        UUID actorUserId = fixture.adminA;

        createPatient(scope, "Overdue Patient 1", "+962799990001");
        createPatient(scope, "Overdue Patient 2", "+962799990002");

        ClinicAiModels.ClinicAiProposalRow proposal = aiService.generateRecallPrioritization(
                scope, new ClinicAiModels.RecallPrioritizationRequest(90, 5), actorUserId, "Dr. Sarah", "DOCTOR");

        assertThat(proposal.status()).isEqualTo("DRAFT");
        assertThat(proposal.proposalType()).isEqualTo("RECALL_PRIORITIZATION");
        assertThat(proposal.transparentFactors()).contains("overdue_days");
        assertThat(proposal.transparentFactors()).contains("no_show_frequency");
    }

    @Test
    @DisplayName("Missing fields audit identifies uncompleted patient records and consents")
    void missingFieldAuditIdentifiesMissingConsentsAndContacts() {
        ClinicScope scope = tenantAScope();
        UUID patientId = createPatient(scope, "Incomplete Patient", "+962790001122");
        UUID actorUserId = fixture.adminA;

        ClinicAiModels.ClinicAiProposalRow proposal = aiService.auditMissingFields(
                scope, new ClinicAiModels.MissingFieldsAuditRequest(patientId), actorUserId, "Registrar", "EMPLOYEE");

        assertThat(proposal.status()).isEqualTo("DRAFT");
        assertThat(proposal.proposalType()).isEqualTo("MISSING_FIELDS");
        assertThat(proposal.outputContent()).contains("TREATMENT_CONSENT");
        assertThat(proposal.outputContent()).contains("EMERGENCY_CONTACT");
    }

    @Test
    @DisplayName("PHI minimization redaction sanitizes national IDs, phone numbers, and emails")
    void phiMinimizationRedactsSensitiveIdentifiers() {
        String input = "Patient national ID 9876543210 with phone +962791112233 and email layla.mansour@example.com reported tooth pain.";
        String redacted = ClinicAiService.redactPhi(input);

        assertThat(redacted).doesNotContain("9876543210");
        assertThat(redacted).doesNotContain("+962791112233");
        assertThat(redacted).doesNotContain("layla.mansour@example.com");

        assertThat(redacted).contains("[REDACTED_NATIONAL_ID]");
        assertThat(redacted).contains("[REDACTED_PHONE]");
        assertThat(redacted).contains("[REDACTED_EMAIL]");
    }

    @Test
    @DisplayName("Cross-tenant isolation prevents access or review of other tenant's AI proposals")
    void crossTenantIsolationEnforcedForAiProposalsAndGovernance() {
        ClinicScope scopeA = tenantAScope();
        ClinicScope scopeB = tenantBScope();

        UUID patientA = createPatient(scopeA, "Tenant A Patient", "+962791111111");
        ClinicAiModels.ClinicAiProposalRow proposalA = aiService.generateVisitSummaryDraft(
                scopeA, new ClinicAiModels.VisitSummaryDraftRequest(patientA, null), fixture.adminA, "Dr. Sarah", "DOCTOR");

        // Tenant B cannot see Tenant A's proposals in list
        List<ClinicAiModels.ClinicAiProposalRow> tenantBList = aiService.listProposals(
                scopeB, null, null, null, 10);
        assertThat(tenantBList).noneMatch(p -> p.id().equals(proposalA.id()));

        // Tenant B cannot fetch Tenant A's proposal directly
        assertThatThrownBy(() -> aiService.getProposal(scopeB, proposalA.id()))
                .isInstanceOf(NoSuchElementException.class);

        // Tenant B cannot review Tenant A's proposal
        assertThatThrownBy(() -> aiService.reviewProposal(
                scopeB, proposalA.id(), new ClinicAiModels.ReviewProposalRequest("APPROVED", null),
                fixture.adminB, "Dr. B", "DOCTOR"))
                .isInstanceOf(NoSuchElementException.class);
    }
}
