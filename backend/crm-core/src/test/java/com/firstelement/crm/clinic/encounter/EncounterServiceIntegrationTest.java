package com.firstelement.crm.clinic.encounter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstelement.crm.CrmFixture;
import com.firstelement.crm.CrmIntegrationTestBase;
import com.firstelement.crm.clinic.ClinicScope;
import com.firstelement.crm.clinic.patient.PatientService;
import com.firstelement.security.TenantContext;
import com.firstelement.security.UserRole;
import com.firstelement.security.query.TenantQueryGuard;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

/**
 * Slice S3 Acceptance Tests: Encounters, Vitals, Digital Signatures, Immutable Note Amendments,
 * and Medical History & Systemic Conditions.
 */
@SpringBootTest(classes = com.firstelement.crm.CrmTestApplication.class)
class EncounterServiceIntegrationTest extends CrmIntegrationTestBase {

    @Autowired
    private EncounterRepository repository;

    @Autowired
    private EncounterService service;

    @Autowired
    private PatientService patients;

    @Autowired
    private CrmFixture fixture;

    @Autowired
    private TenantQueryGuard guard;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @BeforeEach
    void resetDatabase() {
        fixture.reset();
    }

    // ------------------------------------------------------------------
    // 1. Vitals recording and retrieval (CLN-01)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an encounter records vitals (BP, pulse, temperature) and retrieves them faithfully")
    void recordVitalsAndRetrieve() {
        ClinicScope scope = tenantAScope();
        TenantContext doctorCtx = fixture.adminAContext();
        UUID patientId = newPatient(scope, "P-301");
        UUID doctorId = seedDoctor("Dr Zaid");

        var createReq = new EncounterModels.CreateEncounterRequest(
                doctorId,
                null, // appointmentId
                null, // treatmentPlanId
                null, // treatmentPlanItemId
                "Routine dental checkup",
                "Visual exam and ultrasonic scaling",
                "K02.1 Dental caries",
                "Mild plaque on lower lingual surface",
                "Ultrasonic scaling",
                46,
                "LOWER_RIGHT_MOLAR",
                "COMPLETED",
                LocalDateTime.now(),
                30,
                fixture.projectA1.toString(),
                false,
                120, // bloodPressureSystolic
                80,  // bloodPressureDiastolic
                72,  // pulseRate
                BigDecimal.valueOf(36.8), // temperatureC
                List.of()
        );

        UUID encounterId = service.createEncounter(scope, patientId, createReq, doctorCtx);
        assertThat(encounterId).isNotNull();

        EncounterModels.EncounterDetail detail = service.getEncounterDetail(scope, encounterId, doctorCtx);
        assertThat(detail.encounter().id()).isEqualTo(encounterId);
        assertThat(detail.encounter().bloodPressureSystolic()).isEqualTo(120);
        assertThat(detail.encounter().bloodPressureDiastolic()).isEqualTo(80);
        assertThat(detail.encounter().pulseRate()).isEqualTo(72);
        assertThat(detail.encounter().temperatureC()).isEqualByComparingTo(BigDecimal.valueOf(36.8));
        assertThat(detail.encounter().toothFdi()).isEqualTo(46);
        assertThat(detail.encounter().isSigned()).isFalse();
        assertThat(detail.encounter().signatureHash()).isNull();
        assertThat(detail.amendments()).isEmpty();
    }

    // ------------------------------------------------------------------
    // 2. Unsigned encounters can be edited
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an unsigned draft encounter can have its diagnosis, notes and vitals updated")
    void unsignedEncounterCanBeEdited() {
        ClinicScope scope = tenantAScope();
        TenantContext doctorCtx = fixture.adminAContext();
        UUID patientId = newPatient(scope, "P-302");
        UUID doctorId = seedDoctor("Dr Sara");

        UUID encounterId = service.createEncounter(scope, patientId, new EncounterModels.CreateEncounterRequest(
                doctorId, null, null, null,
                "Initial consult", "Visual exam", "K05.0 Gingivitis", null, null, null, null,
                "IN_PROGRESS", LocalDateTime.now(), 20, fixture.projectA1.toString(), false,
                115, 75, 68, BigDecimal.valueOf(36.6), List.of()
        ), doctorCtx);

        var updateReq = new EncounterModels.UpdateDiagnosisRequest(
                "K05.1 Chronic periodontitis",
                "Patient reports gum bleeding during brushing",
                "Deep pocket depth on quadrant 1",
                "Root planing performed",
                16,
                "UPPER_RIGHT_MOLAR",
                125,
                82,
                75,
                BigDecimal.valueOf(36.9)
        );

        service.updateDiagnosis(scope, encounterId, updateReq, doctorCtx);

        EncounterModels.EncounterDetail updated = service.getEncounterDetail(scope, encounterId, doctorCtx);
        assertThat(updated.encounter().diagnosis()).isEqualTo("K05.1 Chronic periodontitis");
        assertThat(updated.encounter().clinicalNotes()).isEqualTo("Patient reports gum bleeding during brushing");
        assertThat(updated.encounter().examinationNotes()).isEqualTo("Deep pocket depth on quadrant 1");
        assertThat(updated.encounter().performedProcedures()).isEqualTo("Root planing performed");
        assertThat(updated.encounter().toothFdi()).isEqualTo(16);
        assertThat(updated.encounter().bloodPressureSystolic()).isEqualTo(125);
        assertThat(updated.encounter().bloodPressureDiastolic()).isEqualTo(82);
    }

    // ------------------------------------------------------------------
    // 3. Immutable signed notes: cannot be edited
    // ------------------------------------------------------------------

    @Test
    @DisplayName("once an encounter is digitally signed, it is immutable and updateDiagnosis throws IllegalStateException")
    void signedEncounterCannotBeEdited() {
        ClinicScope scope = tenantAScope();
        TenantContext doctorCtx = fixture.adminAContext();
        UUID patientId = newPatient(scope, "P-303");
        UUID doctorId = seedDoctor("Dr Maya");

        UUID encounterId = service.createEncounter(scope, patientId, new EncounterModels.CreateEncounterRequest(
                doctorId, null, null, null,
                "Composite filling", "Pit caries", "K02.0 Caries into enamel", "Restoration",
                "Class I composite restoration", 36, "LOWER_LEFT_MOLAR",
                "COMPLETED", LocalDateTime.now(), 30, fixture.projectA1.toString(), false,
                118, 76, 70, BigDecimal.valueOf(36.7), List.of()
        ), doctorCtx);

        // Sign the note
        String signatureHash = service.signEncounter(scope, encounterId, doctorCtx);
        assertThat(signatureHash).isNotBlank();
        assertThat(signatureHash).hasSize(64); // SHA-256 hex string

        EncounterModels.EncounterDetail signedDetail = service.getEncounterDetail(scope, encounterId, doctorCtx);
        assertThat(signedDetail.encounter().isSigned()).isTrue();
        assertThat(signedDetail.encounter().signedAt()).isNotNull();
        assertThat(signedDetail.encounter().signedByUserId()).isEqualTo(doctorCtx.userId());
        assertThat(signedDetail.encounter().signatureHash()).isEqualTo(signatureHash);

        // Any attempt to update diagnosis must fail with IllegalStateException
        var updateReq = new EncounterModels.UpdateDiagnosisRequest(
                "Tampered diagnosis", null, null, null, null, null, null, null, null, null
        );

        assertThatThrownBy(() -> service.updateDiagnosis(scope, encounterId, updateReq, doctorCtx))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("immutable");
    }

    // ------------------------------------------------------------------
    // 4. Double signing forbidden
    // ------------------------------------------------------------------

    @Test
    @DisplayName("cannot sign an already signed encounter")
    void cannotSignAlreadySignedEncounter() {
        ClinicScope scope = tenantAScope();
        TenantContext doctorCtx = fixture.adminAContext();
        UUID patientId = newPatient(scope, "P-304");
        UUID doctorId = seedDoctor("Dr Tariq");

        UUID encounterId = service.createEncounter(scope, patientId, new EncounterModels.CreateEncounterRequest(
                doctorId, null, null, null,
                "Extraction", "Mobility grade 3", "K04.0 Pulpitis", "Extracted", "Extraction under LA",
                48, null, "COMPLETED", LocalDateTime.now(), 20, fixture.projectA1.toString(), false,
                null, null, null, null, List.of()
        ), doctorCtx);

        service.signEncounter(scope, encounterId, doctorCtx);

        assertThatThrownBy(() -> service.signEncounter(scope, encounterId, doctorCtx))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already signed");
    }

    // ------------------------------------------------------------------
    // 5. Amendments require signed status
    // ------------------------------------------------------------------

    @Test
    @DisplayName("cannot append an amendment to an unsigned draft encounter")
    void cannotAddAmendmentToUnsignedEncounter() {
        ClinicScope scope = tenantAScope();
        TenantContext doctorCtx = fixture.adminAContext();
        UUID patientId = newPatient(scope, "P-305");
        UUID doctorId = seedDoctor("Dr Omar");

        UUID encounterId = service.createEncounter(scope, patientId, new EncounterModels.CreateEncounterRequest(
                doctorId, null, null, null,
                "Consult", null, "K02.0 Caries", null, null, null, null,
                "IN_PROGRESS", LocalDateTime.now(), 15, fixture.projectA1.toString(), false,
                null, null, null, null, List.of()
        ), doctorCtx);

        var amendReq = new EncounterModels.CreateAmendmentRequest(
                "Correcting tooth number",
                "Finding was actually on tooth 26 instead of 25."
        );

        assertThatThrownBy(() -> service.addAmendment(scope, encounterId, amendReq, doctorCtx))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Amendments can only be added to a signed clinical note");
    }

    // ------------------------------------------------------------------
    // 6. Signed encounters allow immutable amendments with audit trail
    // ------------------------------------------------------------------

    @Test
    @DisplayName("signed encounters accept multiple amendments and preserve complete audit history")
    void signedEncounterAllowsAmendmentsWithAuditTrail() {
        ClinicScope scope = tenantAScope();
        TenantContext doctorCtx = fixture.adminAContext();
        UUID patientId = newPatient(scope, "P-306");
        UUID doctorId = seedDoctor("Dr Huda");

        UUID encounterId = service.createEncounter(scope, patientId, new EncounterModels.CreateEncounterRequest(
                doctorId, null, null, null,
                "Root Canal Therapy", "Symptomatic apical periodontitis", "K04.1 Necrosis of pulp",
                "Instrumentation completed", "Access cavity preparation and canal instrumentation",
                21, null, "COMPLETED", LocalDateTime.now(), 45, fixture.projectA1.toString(), false,
                122, 78, 74, BigDecimal.valueOf(37.0), List.of()
        ), doctorCtx);

        service.signEncounter(scope, encounterId, doctorCtx);

        // Add first amendment
        UUID amend1 = service.addAmendment(scope, encounterId, new EncounterModels.CreateAmendmentRequest(
                "Post-op medication clarification",
                "Advised Ibuprofen 400mg TID after meals for 3 days if pain persists."
        ), doctorCtx);
        assertThat(amend1).isNotNull();

        // Add second amendment
        UUID amend2 = service.addAmendment(scope, encounterId, new EncounterModels.CreateAmendmentRequest(
                "Next visit scheduling note",
                "Obturation appointment scheduled for next Tuesday."
        ), doctorCtx);
        assertThat(amend2).isNotNull();

        EncounterModels.EncounterDetail detail = service.getEncounterDetail(scope, encounterId, doctorCtx);
        assertThat(detail.encounter().isSigned()).isTrue();
        assertThat(detail.amendments()).hasSize(2);

        EncounterModels.ClinicalNoteAmendmentRow first = detail.amendments().get(0);
        assertThat(first.id()).isEqualTo(amend1);
        assertThat(first.encounterId()).isEqualTo(encounterId);
        assertThat(first.amendedByUserId()).isEqualTo(doctorCtx.userId());
        assertThat(first.reason()).isEqualTo("Post-op medication clarification");
        assertThat(first.amendmentContent()).contains("Ibuprofen 400mg");
        assertThat(first.signatureHash()).isNotBlank();
        assertThat(first.signatureHash()).hasSize(64);

        EncounterModels.ClinicalNoteAmendmentRow second = detail.amendments().get(1);
        assertThat(second.id()).isEqualTo(amend2);
        assertThat(second.reason()).isEqualTo("Next visit scheduling note");
        assertThat(second.amendmentContent()).contains("Obturation appointment");
        assertThat(second.signatureHash()).isNotBlank();
    }

    // ------------------------------------------------------------------
    // 7. Medical history save and retrieve with systemic alerts
    // ------------------------------------------------------------------

    @Test
    @DisplayName("medical history saves allergies, medications and systemic risk flags and retrieves accurately")
    void saveAndRetrieveMedicalHistory() {
        ClinicScope scope = tenantAScope();
        TenantContext doctorCtx = fixture.adminAContext();
        UUID patientId = newPatient(scope, "P-307");

        var saveReq = new EncounterModels.SaveMedicalHistoryRequest(
                "Penicillin, Latex, Sulfa",
                "Hypertension, Type 2 Diabetes",
                "Metformin 500mg BID, Amlodipine 5mg OD",
                false, // bleedingDisorder
                true,  // cardiacCondition
                true,  // diabetes
                "Requires prophylactic antibiotic consultation prior to surgical procedures"
        );

        UUID historyId = service.saveMedicalHistory(scope, patientId, saveReq, doctorCtx);
        assertThat(historyId).isNotNull();

        var retrievedOpt = service.getMedicalHistory(scope, patientId, doctorCtx);
        assertThat(retrievedOpt).isPresent();

        var history = retrievedOpt.get();
        assertThat(history.id()).isEqualTo(historyId);
        assertThat(history.patientId()).isEqualTo(patientId);
        assertThat(history.allergies()).isEqualTo("Penicillin, Latex, Sulfa");
        assertThat(history.conditions()).isEqualTo("Hypertension, Type 2 Diabetes");
        assertThat(history.currentMedications()).isEqualTo("Metformin 500mg BID, Amlodipine 5mg OD");
        assertThat(history.cardiacCondition()).isTrue();
        assertThat(history.diabetes()).isTrue();
        assertThat(history.bleedingDisorder()).isFalse();
        assertThat(history.notes()).contains("Requires prophylactic antibiotic");

        // Test upsert updates the record rather than failing
        var updateReq = new EncounterModels.SaveMedicalHistoryRequest(
                "Penicillin, Latex, Sulfa, Aspirin",
                "Hypertension, Type 2 Diabetes, Bleeding tendency",
                "Metformin 500mg BID, Amlodipine 5mg OD, Warfarin 2mg",
                true,  // bleedingDisorder updated to true!
                true,
                true,
                "Patient started on Warfarin. Monitor INR before extractions."
        );

        UUID updatedHistoryId = service.saveMedicalHistory(scope, patientId, updateReq, doctorCtx);
        assertThat(updatedHistoryId).isEqualTo(historyId);

        var updatedHistory = service.getMedicalHistory(scope, patientId, doctorCtx).orElseThrow();
        assertThat(updatedHistory.allergies()).contains("Aspirin");
        assertThat(updatedHistory.bleedingDisorder()).isTrue();
        assertThat(updatedHistory.currentMedications()).contains("Warfarin");
    }

    // ------------------------------------------------------------------
    // 8. Receptionist cannot sign notes or add amendments
    // ------------------------------------------------------------------

    @Test
    @DisplayName("receptionist staff cannot digitally sign encounters or append clinical amendments (CLN-07, CLN-08)")
    void receptionistCannotSignOrAmendNotes() {
        ClinicScope scope = tenantAScope();
        TenantContext doctorCtx = fixture.adminAContext();
        TenantContext receptionistCtx = fixture.contextFor(
                UUID.randomUUID(), fixture.orgA, UserRole.CLINIC_STAFF, Set.of(fixture.projectA1));

        UUID patientId = newPatient(scope, "P-308");
        UUID doctorId = seedDoctor("Dr Khalid");

        UUID encounterId = service.createEncounter(scope, patientId, new EncounterModels.CreateEncounterRequest(
                doctorId, null, null, null,
                "Scale and polish", null, "K03.6 Deposits on teeth", null, "Cleaning",
                11, null, "COMPLETED", LocalDateTime.now(), 20, fixture.projectA1.toString(), false,
                null, null, null, null, List.of()
        ), doctorCtx);

        // Receptionist cannot sign
        assertThatThrownBy(() -> service.signEncounter(scope, encounterId, receptionistCtx))
                .isInstanceOf(AccessDeniedException.class);

        // Doctor signs
        service.signEncounter(scope, encounterId, doctorCtx);

        // Receptionist cannot amend
        var amendReq = new EncounterModels.CreateAmendmentRequest("Unauthorized change", "Note");
        assertThatThrownBy(() -> service.addAmendment(scope, encounterId, amendReq, receptionistCtx))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private ClinicScope tenantAScope() {
        return ClinicScope.forRequestedProject(guard, fixture.adminAContext(), fixture.projectA1);
    }

    private UUID newPatient(ClinicScope scope, String patientNo) {
        return patients.create(scope, patientNo, "Test Patient " + patientNo, "Test", "Patient",
                LocalDate.of(1990, 1, 1), "+96650000" + patientNo.replaceAll("\\D", ""),
                "test" + patientNo + "@example.test", "ar", null).id();
    }

    private UUID seedDoctor(String name) {
        UUID id = UUID.randomUUID();
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("id", id).addValue("org", fixture.orgA)
                .addValue("project", fixture.projectA1).addValue("name", name);
        jdbc.update("""
                insert into "doctors"
                    ("id", "organizationId", "projectId", "name", "specialty",
                     "workingDays", "startHour", "endHour", "slotDuration", "isActive",
                     "createdAt", "updatedAt")
                values (:id, :org, :project, :name, 'General',
                        ARRAY[1,2,3,4,5]::integer[], '09:00', '17:00', 30, true,
                        (current_timestamp at time zone 'utc'),
                        (current_timestamp at time zone 'utc'))
                """, p);
        return id;
    }
}
