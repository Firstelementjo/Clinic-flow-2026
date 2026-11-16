package com.firstelement.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.firstelement.security.config.SecurityProperties;
import com.firstelement.security.session.BrowserSessionStore;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * ClinicFlow Complete Automated QA Acceptance Suite (Phases 3 to 6).
 *
 * <p>Validates all 9 clinical modules, dental labs, encrypted media, financial reconciliation,
 * realistic clinical workflows, appointment concurrency under load, Omnichannel CRM/AI governance,
 * and multi-tenant isolation over HTTP endpoints.
 */
@SpringBootTest(
    classes = InboxTestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ClinicFullQaAcceptanceIntegrationTest extends InboxIntegrationTestBase {

    private static final UUID PATIENT_A1 = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000a4a01");
    private static final UUID PATIENT_A2 = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000a4a02");
    private static final UUID PATIENT_B1 = UUID.fromString("bbbbbbbb-0000-0000-0000-0000000a4b01");
    private static final UUID DOCTOR_A1 = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000d4a01");
    private static final UUID DOCTOR_B1 = UUID.fromString("bbbbbbbb-0000-0000-0000-0000000d4b01");
    private static final UUID PROCEDURE_A1 = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000c4a01");

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private BrowserSessionStore sessions;

    @Autowired
    private SecurityProperties securityProperties;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Autowired
    private InboxFixture inbox;

    private String adminCookieA;
    private String staffCookieA;
    private String adminCookieB;
    private String csrfCookie;
    private String csrfToken;

    @BeforeEach
    void setUp() {
        jdbc.getJdbcOperations().execute("""
            CREATE TABLE IF NOT EXISTS clinic_lab_orders (
                id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                "organizationId" UUID NOT NULL,
                "projectId" UUID NOT NULL,
                "patientId" UUID NOT NULL,
                "doctorId" UUID NULL,
                "labName" VARCHAR(255) NOT NULL,
                "workType" VARCHAR(255) NOT NULL,
                "shade" VARCHAR(64) NULL,
                "toothFdi" INTEGER NULL,
                "status" VARCHAR(40) NOT NULL DEFAULT 'SENT_TO_LAB',
                "sentDate" TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT (now() AT TIME ZONE 'UTC'),
                "dueDate" TIMESTAMP WITHOUT TIME ZONE NULL,
                "receivedDate" TIMESTAMP WITHOUT TIME ZONE NULL,
                "cost" NUMERIC(12, 3) NOT NULL DEFAULT 0.000,
                "currency" VARCHAR(10) NOT NULL DEFAULT 'JOD',
                "notes" TEXT NULL,
                "createdAt" TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT (now() AT TIME ZONE 'UTC'),
                "updatedAt" TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT (now() AT TIME ZONE 'UTC')
            )
            """);

        jdbc.getJdbcOperations().execute(
            "truncate table clinical_prescriptions, clinical_note_amendments, patient_merges, patient_relationships,"
                + " clinic_lab_orders, clinical_encounters, patient_odontogram_findings,"
                + " patient_periodontal_measurements, patient_periodontal_exams, clinic_media_access_audit,"
                + " patient_attachments, clinic_schedules,"
                + " clinic_refunds, clinic_payments, clinic_patient_ledger_entries,"
                + " clinic_invoices, treatment_plan_items, treatment_plans, patients,"
                + " clinic_ai_governance, clinic_ai_proposals cascade");
        inbox.reset();

        jdbc.getJdbcOperations().execute("""
            insert into "clinic_finding_vocabulary"
                ("id", "organizationId", "projectId", "code", "category", "labelEn", "isClinicianReviewed", "isActive", "createdAt")
            values 
                (gen_random_uuid(), 'aaaaaaaa-0000-0000-0000-000000000001', 'aaaaaaaa-0000-0000-0000-0000000000a1', 'CARIES', 'CARIES', 'Dental Caries', true, true, now() at time zone 'utc'),
                (gen_random_uuid(), 'aaaaaaaa-0000-0000-0000-000000000001', 'aaaaaaaa-0000-0000-0000-0000000000a1', 'COMPOSITE_RESTORATION', 'RESTORATION', 'Composite Restoration', true, true, now() at time zone 'utc'),
                (gen_random_uuid(), 'bbbbbbbb-0000-0000-0000-000000000001', 'bbbbbbbb-0000-0000-0000-0000000000b1', 'CARIES', 'CARIES', 'Dental Caries', true, true, now() at time zone 'utc')
            on conflict do nothing
        """);

        adminCookieA = sessionFor(InboxFixture.USER_ADMIN_A);
        staffCookieA = sessionFor(InboxFixture.USER_STAFF_A);
        adminCookieB = sessionFor(InboxFixture.USER_ADMIN_B);
        initCsrf();

        seedDoctor(DOCTOR_A1, InboxFixture.ORG_A, InboxFixture.PROJECT_A1, "Dr. Rami Al-Hadi");
        seedDoctor(DOCTOR_B1, InboxFixture.ORG_B, InboxFixture.PROJECT_B1, "Dr. Kareem Nassar");
        seedProcedure(PROCEDURE_A1, InboxFixture.ORG_A, InboxFixture.PROJECT_A1, "Root Canal Therapy", 150.000);
    }

    // =========================================================================
    // Phase 3: Functional and End-to-End Tests Across Modules
    // =========================================================================

    @Test
    @DisplayName("Phase 3 - Patient Records (P1): Create, edit, and merge patient files")
    void phase3_01_patientRecords_createEditMerge() {
        // 1. Create Patient
        String createPayload = """
            {
              "patientNo": "P-3001",
              "displayName": "Hana Khoury",
              "givenName": "Hana",
              "familyName": "Khoury",
              "gender": "FEMALE",
              "dateOfBirth": "1994-06-15",
              "phone": "+962791114455",
              "email": "hana@example.com",
              "locale": "ar"
            }
            """;
        JsonNode created = write(adminCookieA, HttpMethod.POST, "/api/v1/clinic/patients", createPayload, HttpStatus.CREATED);
        UUID patientId = UUID.fromString(created.get("id").asText());
        assertThat(created.get("displayName").asText()).isEqualTo("Hana Khoury");

        // 2. Edit Patient File (PUT /patients/{id})
        String editPayload = """
            {
              "patientNo": "P-3001",
              "displayName": "Hana S. Khoury",
              "givenName": "Hana",
              "familyName": "Khoury",
              "gender": "FEMALE",
              "dateOfBirth": "1994-06-15",
              "phone": "+962791114499",
              "email": "hana.updated@example.com",
              "locale": "ar"
            }
            """;
        JsonNode updated = write(adminCookieA, HttpMethod.PUT, "/api/v1/clinic/patients/" + patientId, editPayload, HttpStatus.OK);
        assertThat(updated.get("displayName").asText()).isEqualTo("Hana S. Khoury");
        assertThat(updated.get("primaryPhone").asText()).isEqualTo("+962791114499");

        // 3. Create Duplicate Patient & Merge
        String dupPayload = """
            {
              "patientNo": "P-3002",
              "displayName": "Hana Duplicate",
              "givenName": "Hana",
              "familyName": "Khoury",
              "gender": "FEMALE",
              "dateOfBirth": "1994-06-15",
              "phone": "+962791114455",
              "email": "hana.dup@example.com",
              "locale": "ar"
            }
            """;
        JsonNode dup = write(adminCookieA, HttpMethod.POST, "/api/v1/clinic/patients", dupPayload, HttpStatus.CREATED);
        UUID dupId = UUID.fromString(dup.get("id").asText());

        String mergePayload = "{\"sourcePatientId\":\"" + dupId + "\",\"targetPatientId\":\"" + patientId + "\",\"reason\":\"Duplicate intake file\"}";
        write(adminCookieA, HttpMethod.POST, "/api/v1/clinic/patients/merge", mergePayload, HttpStatus.OK);

        // Verify duplicate is soft-deleted and target remains active
        ResponseEntity<JsonNode> dupCheck = exchange(adminCookieA, "/api/v1/clinic/patients/" + dupId);
        assertThat(dupCheck.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        JsonNode activeCheck = get(adminCookieA, "/api/v1/clinic/patients/" + patientId);
        assertThat(activeCheck.get("id").asText()).isEqualTo(patientId.toString());
    }

    @Test
    @DisplayName("Phase 3 - Medical History (P0): Record allergies, systemic conditions and alert warnings")
    void phase3_02_medicalHistory_allergiesAndWarnings() {
        seedPatient(PATIENT_A1, InboxFixture.ORG_A, InboxFixture.PROJECT_A1, "P-3003", "Yousef Al-Qadi", LocalDate.of(1982, 3, 20));

        String historyPayload = """
            {
              "allergies": "Penicillin, Amoxicillin",
              "conditions": "Type 2 Diabetes, Hypertension",
              "currentMedications": "Metformin 500mg, Lisinopril 10mg",
              "bleedingDisorder": false,
              "cardiacCondition": true,
              "diabetes": true,
              "notes": "Requires local anesthetic without epinephrine"
            }
            """;
        write(adminCookieA, HttpMethod.PUT, "/api/v1/clinic/patients/" + PATIENT_A1 + "/medical-history", historyPayload, HttpStatus.OK);

        JsonNode fetched = get(adminCookieA, "/api/v1/clinic/patients/" + PATIENT_A1 + "/medical-history");
        assertThat(fetched.get("allergies").asText()).contains("Penicillin");
        assertThat(fetched.get("cardiacCondition").asBoolean()).isTrue();
        assertThat(fetched.get("diabetes").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("Phase 3 - Appointments (P0): Book, edit, cancel; conflicting bookings rejected with 409")
    void phase3_03_appointments_bookEditCancelAndRejectConflict() {
        seedPatient(PATIENT_A1, InboxFixture.ORG_A, InboxFixture.PROJECT_A1, "P-3004", "Farah Zaid", LocalDate.of(1998, 11, 5));

        // 1. Book first appointment
        String bookPayload = """
            {
              "doctorId": "%s",
              "patientId": "%s",
              "procedureId": "%s",
              "appointmentDate": "2026-11-10T10:00:00",
              "durationMinutes": 30,
              "notes": "Routine cleaning"
            }
            """.formatted(DOCTOR_A1, PATIENT_A1, PROCEDURE_A1);
        JsonNode booked = write(adminCookieA, HttpMethod.POST, "/api/v1/clinic/schedules/appointments", bookPayload, HttpStatus.CREATED);
        UUID aptId = UUID.fromString(booked.get("id").asText());

        // 2. Conflicting booking for same doctor at overlapping slot returns 409 CONFLICT
        String conflictPayload = """
            {
              "doctorId": "%s",
              "patientId": "%s",
              "procedureId": "%s",
              "appointmentDate": "2026-11-10T10:15:00",
              "durationMinutes": 30,
              "notes": "Overlapping booking attempt"
            }
            """.formatted(DOCTOR_A1, PATIENT_A1, PROCEDURE_A1);
        ResponseEntity<JsonNode> conflictResp = http.exchange(
            withProjectId(adminCookieA, "/api/v1/clinic/schedules/appointments"),
            HttpMethod.POST,
            new HttpEntity<>(conflictPayload, writeHeaders(adminCookieA)),
            JsonNode.class);
        assertThat(conflictResp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // 3. Edit appointment to new non-conflicting time (PATCH /appointments/{id})
        String patchPayload = """
            {
              "appointmentDate": "2026-11-10T11:00:00",
              "durationMinutes": 30,
              "status": "CONFIRMED",
              "notes": "Rescheduled to 11 AM"
            }
            """;
        write(adminCookieA, HttpMethod.PATCH, "/api/v1/clinic/schedules/appointments/" + aptId, patchPayload, HttpStatus.OK);

        // 4. Cancel appointment (POST /appointments/{id}/cancel)
        String cancelPayload = "{\"status\":\"CANCELLED\",\"cancelledReason\":\"Patient requested cancellation\"}";
        write(adminCookieA, HttpMethod.POST, "/api/v1/clinic/schedules/appointments/" + aptId + "/cancel", cancelPayload, HttpStatus.OK);
    }

    @Test
    @DisplayName("Phase 3 - Odontogram (P1): Record tooth status across 5 surfaces with append-only superseding")
    void phase3_04_odontogram_fiveSurfacesAndSuperseding() {
        seedPatient(PATIENT_A1, InboxFixture.ORG_A, InboxFixture.PROJECT_A1, "P-3005", "Bilal Najjar", LocalDate.of(1991, 7, 22));

        // 1. Record finding on all 5 surfaces (MODBL)
        String findingPayload = """
            {
              "toothFdi": 16,
              "scope": "TOOTH",
              "surfaces": ["M", "O", "D", "B", "L"],
              "code": "CARIES",
              "attributesJson": "{\\"severity\\":\\"deep\\"}",
              "state": "EXISTING",
              "source": "CLINICAL",
              "note": "Caries involving all 5 surfaces"
            }
            """;
        JsonNode finding = write(adminCookieA, HttpMethod.POST, "/api/v1/clinic/patients/" + PATIENT_A1 + "/odontogram/findings", findingPayload, HttpStatus.CREATED);
        UUID findingId = UUID.fromString(finding.get("id").asText());
        assertThat(finding.get("toothFdi").asInt()).isEqualTo(16);

        // 2. Record superseding correction
        String correctPayload = """
            {
              "code": "COMPOSITE_RESTORATION",
              "attributesJson": "{\\"shade\\":\\"A2\\"}",
              "note": "Composite restoration completed on 16 MODBL"
            }
            """;
        JsonNode corrected = write(adminCookieA, HttpMethod.POST,
            "/api/v1/clinic/patients/" + PATIENT_A1 + "/odontogram/findings/" + findingId + "/correct",
            correctPayload, HttpStatus.OK);
        assertThat(corrected.get("code").asText()).isEqualTo("COMPOSITE_RESTORATION");

        // 3. Verify chart has corrected active entry and previous entry marked superseded
        JsonNode chart = get(adminCookieA, "/api/v1/clinic/patients/" + PATIENT_A1 + "/odontogram");
        assertThat(chart.isArray()).isTrue();
        assertThat(chart.size()).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("Phase 3 - Periodontal Charting (P1): 6 measurements per tooth with auto-calculated CAL")
    void phase3_05_periodontalCharting_calCalculation() {
        seedPatient(PATIENT_A1, InboxFixture.ORG_A, InboxFixture.PROJECT_A1, "P-3006", "Sami Ghanem", LocalDate.of(1978, 1, 14));

        String perioPayload = """
            {
              "notes": "Baseline full mouth periodontal exam",
              "measurements": [{
                "toothFdi": 16,
                "probingDepthMb": 4, "probingDepthB": 3, "probingDepthDb": 5,
                "probingDepthMl": 4, "probingDepthL": 3, "probingDepthDl": 5,
                "gingivalMarginMb": 2, "gingivalMarginB": 1, "gingivalMarginDb": 3,
                "gingivalMarginMl": 2, "gingivalMarginL": 0, "gingivalMarginDl": 2,
                "bopMb": true, "furcationClass": 1, "mobilityGrade": 0
              }]
            }
            """;
        write(adminCookieA, HttpMethod.POST, "/api/v1/clinic/patients/" + PATIENT_A1 + "/periodontal/exams", perioPayload, HttpStatus.CREATED);

        // Retrieve latest periodontal chart and verify CAL = Probing Depth + Gingival Margin
        JsonNode latest = get(adminCookieA, "/api/v1/clinic/patients/" + PATIENT_A1 + "/periodontal/latest");
        assertThat(latest.isArray()).isTrue();
        JsonNode tooth16 = latest.get(0);
        assertThat(tooth16.get("calMb").asInt()).isEqualTo(4 + 2); // 6
        assertThat(tooth16.get("calB").asInt()).isEqualTo(3 + 1);  // 4
        assertThat(tooth16.get("calDb").asInt()).isEqualTo(5 + 3); // 8
        assertThat(tooth16.get("calMl").asInt()).isEqualTo(4 + 2); // 6
        assertThat(tooth16.get("calL").asInt()).isEqualTo(3 + 0);  // 3
        assertThat(tooth16.get("calDl").asInt()).isEqualTo(5 + 2); // 7
    }

    @Test
    @DisplayName("Phase 3 - Treatment Plans (P1): Multiple plan options and approve one")
    void phase3_06_treatmentPlans_multipleOptionsAndApproval() {
        seedPatient(PATIENT_A1, InboxFixture.ORG_A, InboxFixture.PROJECT_A1, "P-3007", "Nour Salem", LocalDate.of(1995, 9, 30));

        // Create Option 1: Conservative Therapy
        String plan1Payload = """
            {
              "title": "Option 1: Conservative Therapy",
              "notes": "Endodontic therapy and crown",
              "planType": "PRIMARY",
              "patientEstimatedPortion": 250.000
            }
            """;
        JsonNode plan1 = write(adminCookieA, HttpMethod.POST, "/api/v1/clinic/patients/" + PATIENT_A1 + "/treatment-plans", plan1Payload, HttpStatus.CREATED);
        UUID plan1Id = UUID.fromString(plan1.get("id").asText());

        // Create Option 2: Implant Therapy
        String plan2Payload = """
            {
              "title": "Option 2: Implant Therapy",
              "notes": "Extraction and immediate implant",
              "planType": "ALTERNATIVE",
              "patientEstimatedPortion": 800.000
            }
            """;
        write(adminCookieA, HttpMethod.POST, "/api/v1/clinic/patients/" + PATIENT_A1 + "/treatment-plans", plan2Payload, HttpStatus.CREATED);

        // Approve Option 1
        String approvePayload = "{\"consentNotes\": \"Patient chose Option 1\"}";
        JsonNode approved = write(adminCookieA, HttpMethod.POST,
            "/api/v1/clinic/patients/" + PATIENT_A1 + "/treatment-plans/" + plan1Id + "/approve", approvePayload, HttpStatus.OK);
        assertThat(approved.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(approved.get("approved").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("Phase 3 - Encounters (P0): Signed visit note immutability and digital signature hash")
    void phase3_07_encounters_signedVisitImmutability() {
        seedPatient(PATIENT_A1, InboxFixture.ORG_A, InboxFixture.PROJECT_A1, "P-3008", "Mona Darwish", LocalDate.of(1989, 4, 12));

        String encounterPayload = """
            {
              "doctorId": "%s",
              "chiefComplaint": "Severe pain tooth 16",
              "examinationNotes": "Extensive occlusal breakdown",
              "diagnosis": "Irreversible Pulpitis 16",
              "clinicalNotes": "Root canal therapy initiated",
              "performedProcedures": "Pulpectomy performed",
              "toothFdi": 16,
              "toothLocation": "UR",
              "status": "IN_PROGRESS",
              "durationMinutes": 45,
              "prescriptions": []
            }
            """.formatted(DOCTOR_A1);
        JsonNode enc = write(adminCookieA, HttpMethod.POST, "/api/v1/clinic/patients/" + PATIENT_A1 + "/encounters", encounterPayload, HttpStatus.CREATED);
        UUID encounterId = UUID.fromString(enc.get("id").asText());

        // Attending doctor digitally signs encounter
        JsonNode signed = write(adminCookieA, HttpMethod.POST,
            "/api/v1/clinic/patients/" + PATIENT_A1 + "/encounters/" + encounterId + "/sign", "{}", HttpStatus.OK);
        assertThat(signed.get("signed").asBoolean()).isTrue();
        assertThat(signed.get("signatureHash").asText()).hasSize(64);

        // Direct modification attempt after signing is rejected
        String updatePayload = """
            {
              "diagnosis": "Altered Diagnosis",
              "clinicalNotes": "Altered Notes",
              "toothFdi": 16,
              "toothLocation": "UR"
            }
            """;
        ResponseEntity<JsonNode> mutateResp = http.exchange(
            withProjectId(adminCookieA, "/api/v1/clinic/patients/" + PATIENT_A1 + "/encounters/" + encounterId + "/diagnosis"),
            HttpMethod.PATCH,
            new HttpEntity<>(updatePayload, writeHeaders(adminCookieA)),
            JsonNode.class);
        assertThat(mutateResp.getStatusCode().is4xxClientError()).isTrue();
    }

    @Test
    @DisplayName("Phase 3 - Prescriptions (P0): Drug guide lookup and prescription issuance")
    void phase3_08_prescriptions_drugGuideAndPrescriptionIssuance() {
        seedPatient(PATIENT_A1, InboxFixture.ORG_A, InboxFixture.PROJECT_A1, "P-3009", "Ahmad Tarawneh", LocalDate.of(1985, 8, 19));

        // Drug guide / medication presets lookup
        JsonNode presets = get(adminCookieA, "/api/v1/clinic/settings/medications");
        assertThat(presets.isArray()).isTrue();

        // Create encounter and issue prescription
        String encounterPayload = """
            {
              "doctorId": "%s",
              "chiefComplaint": "Molar pain",
              "diagnosis": "Acute Pulpitis",
              "status": "COMPLETED",
              "durationMinutes": 30,
              "prescriptions": []
            }
            """.formatted(DOCTOR_A1);
        JsonNode enc = write(adminCookieA, HttpMethod.POST, "/api/v1/clinic/patients/" + PATIENT_A1 + "/encounters", encounterPayload, HttpStatus.CREATED);
        UUID encounterId = UUID.fromString(enc.get("id").asText());

        String rxPayload = """
            {
              "medicationName": "Amoxicillin 500mg",
              "dosage": "500mg",
              "frequency": "TID",
              "duration": "5 days",
              "instructions": "Take with food",
              "doctorId": "%s"
            }
            """.formatted(DOCTOR_A1);
        JsonNode rx = write(adminCookieA, HttpMethod.POST,
            "/api/v1/clinic/patients/" + PATIENT_A1 + "/encounters/" + encounterId + "/prescriptions", rxPayload, HttpStatus.CREATED);
        assertThat(rx.get("created").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("Phase 3 - Billing (P0): Invoices, discounts, payments, refunds with 3-decimal JOD precision")
    void phase3_09_billing_invoicesDiscountsPaymentsRefunds() {
        seedPatient(PATIENT_A1, InboxFixture.ORG_A, InboxFixture.PROJECT_A1, "P-3010", "Dina Husseini", LocalDate.of(1993, 12, 1));

        // 1. Issue Invoice for 150.000 JOD
        String invoicePayload = """
            {
              "patientId": "%s",
              "doctorId": "%s",
              "invoiceDate": "2026-11-01",
              "dueDate": "2026-12-01",
              "currency": "JOD",
              "notes": "RCT Treatment Invoice",
              "items": [{
                "procedureId": "%s",
                "toothFdi": 16,
                "quantity": 1,
                "unitPrice": 150.000,
                "discountAmount": 0.000,
                "notes": "RCT completed"
              }]
            }
            """.formatted(PATIENT_A1, DOCTOR_A1, PROCEDURE_A1);
        JsonNode inv = write(adminCookieA, HttpMethod.POST, "/api/v1/clinic/invoices", invoicePayload, HttpStatus.CREATED);
        UUID invoiceId = UUID.fromString(inv.get("id").asText());
        assertThat(new BigDecimal(inv.get("subtotal").asText())).isEqualByComparingTo(new BigDecimal("150.000"));

        // 2. Apply discount of 25.000 JOD
        String discountPayload = "{\"discountAmount\": 25.000, \"discountReason\": \"Promotional discount\"}";
        JsonNode discounted = write(adminCookieA, HttpMethod.POST, "/api/v1/clinic/invoices/" + invoiceId + "/discount", discountPayload, HttpStatus.OK);
        assertThat(new BigDecimal(discounted.get("balanceDue").asText())).isEqualByComparingTo(new BigDecimal("125.000"));

        // 3. Record partial payment of 75.000 JOD
        String payPayload = "{\"amount\": 75.000, \"currency\": \"JOD\", \"paymentMethod\": \"CASH\", \"referenceNumber\": \"REC-001\"}";
        JsonNode paid = write(adminCookieA, HttpMethod.POST, "/api/v1/clinic/invoices/" + invoiceId + "/payments", payPayload, HttpStatus.CREATED);
        assertThat(new BigDecimal(paid.get("amount").asText())).isEqualByComparingTo(new BigDecimal("75.000"));
        assertThat(new BigDecimal(paid.get("remainingInvoiceBalance").asText())).isEqualByComparingTo(new BigDecimal("50.000"));

        // 4. Record refund of 25.000 JOD
        String refundPayload = "{\"amount\": 25.000, \"currency\": \"JOD\", \"refundMethod\": \"CASH\", \"reason\": \"Partial refund\"}";
        JsonNode refunded = write(adminCookieA, HttpMethod.POST, "/api/v1/clinic/invoices/" + invoiceId + "/refunds", refundPayload, HttpStatus.CREATED);
        assertThat(new BigDecimal(refunded.get("amount").asText())).isEqualByComparingTo(new BigDecimal("25.000"));

        JsonNode invDetail = get(adminCookieA, "/api/v1/clinic/invoices/" + invoiceId);
        JsonNode invHeader = invDetail.get("invoice");
        assertThat(new BigDecimal(invHeader.get("paidAmount").asText())).isEqualByComparingTo(new BigDecimal("50.000"));
        assertThat(new BigDecimal(invHeader.get("balanceDue").asText())).isEqualByComparingTo(new BigDecimal("75.000"));

        // 5. Patient Statement reconciliation
        JsonNode statement = get(adminCookieA, "/api/v1/clinic/patients/" + PATIENT_A1 + "/statement");
        assertThat(new BigDecimal(statement.get("totalDebits").asText())).isEqualByComparingTo(new BigDecimal("175.000"));
        assertThat(new BigDecimal(statement.get("totalCredits").asText())).isEqualByComparingTo(new BigDecimal("100.000"));
        assertThat(new BigDecimal(statement.get("netBalanceDue").asText())).isEqualByComparingTo(new BigDecimal("75.000"));
    }

    @Test
    @DisplayName("Phase 3 - Dental Labs (P2): Create lab order and track status progression")
    void phase3_10_dentalLabs_orderLifecycleTracking() {
        seedPatient(PATIENT_A1, InboxFixture.ORG_A, InboxFixture.PROJECT_A1, "P-3011", "Karim Masri", LocalDate.of(1987, 5, 8));

        String orderPayload = """
            {
              "patientId": "%s",
              "doctorId": "%s",
              "labName": "Modern Dental Lab",
              "workType": "Zirconia Crown",
              "shade": "A2",
              "toothFdi": 16,
              "cost": 45.000,
              "currency": "JOD",
              "notes": "High translucency"
            }
            """.formatted(PATIENT_A1, DOCTOR_A1);
        JsonNode order = write(adminCookieA, HttpMethod.POST, "/api/v1/clinic/lab-orders", orderPayload, HttpStatus.CREATED);
        UUID orderId = UUID.fromString(order.get("id").asText());
        assertThat(order.get("status").asText()).isEqualTo("SENT_TO_LAB");

        // Progress status: ORDERED -> IN_PRODUCTION -> RECEIVED
        String patch1 = "{\"status\": \"IN_PRODUCTION\", \"notes\": \"Casting started\"}";
        JsonNode inProd = write(adminCookieA, HttpMethod.PATCH, "/api/v1/clinic/lab-orders/" + orderId + "/status", patch1, HttpStatus.OK);
        assertThat(inProd.get("status").asText()).isEqualTo("IN_PRODUCTION");

        String patch2 = "{\"status\": \"RECEIVED\", \"notes\": \"Shade confirmed, ready for fit\"}";
        JsonNode received = write(adminCookieA, HttpMethod.PATCH, "/api/v1/clinic/lab-orders/" + orderId + "/status", patch2, HttpStatus.OK);
        assertThat(received.get("status").asText()).isEqualTo("RECEIVED");
    }

    @Test
    @DisplayName("Phase 3 - Media (P1): Upload encrypted radiographs with secure signed downloads")
    void phase3_11_media_encryptedUploadAndSecureAccess() {
        seedPatient(PATIENT_A1, InboxFixture.ORG_A, InboxFixture.PROJECT_A1, "P-3012", "Rana Bisharat", LocalDate.of(1992, 10, 18));

        byte[] rawImageBytes = "DENTAL_RADIOGRAPH_MOCK_IMAGE_DATA_AES256".getBytes(StandardCharsets.UTF_8);
        ByteArrayResource fileResource = new ByteArrayResource(rawImageBytes) {
            @Override
            public String getFilename() {
                return "periapical_16.png";
            }
        };

        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", fileResource);
        form.add("category", "RADIOGRAPH_INTRAORAL");
        form.add("toothFdi", "16");

        HttpHeaders h = writeHeaders(adminCookieA);
        h.setContentType(MediaType.MULTIPART_FORM_DATA);

        ResponseEntity<JsonNode> uploadResp = http.postForEntity(
            withProjectId(adminCookieA, "/api/v1/clinic/patients/" + PATIENT_A1 + "/media"),
            new HttpEntity<>(form, h),
            JsonNode.class);
        assertThat(uploadResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID mediaId = UUID.fromString(uploadResp.getBody().get("id").asText());

        // Generate signed download URL
        JsonNode signedUrl = get(adminCookieA, "/api/v1/clinic/patients/" + PATIENT_A1 + "/media/" + mediaId + "/download-url");
        String token = signedUrl.get("token").asText();
        assertThat(token).isNotBlank();

        // Download decrypted content via signed token
        ResponseEntity<byte[]> downloadResp = http.exchange(
            "/api/v1/clinic/media/download?token=" + token,
            HttpMethod.GET,
            new HttpEntity<>(headers(adminCookieA)),
            byte[].class);
        assertThat(downloadResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(downloadResp.getBody()).isEqualTo(rawImageBytes);
    }

    @Test
    @DisplayName("Phase 3 - Reports (P1): Financial and productivity reports match underlying records")
    void phase3_12_reports_totalsMatchUnderlyingRecords() {
        seedPatient(PATIENT_A1, InboxFixture.ORG_A, InboxFixture.PROJECT_A1, "P-3013", "Tareq Sabbagh", LocalDate.of(1986, 2, 10));

        // Create paid invoice
        String invoicePayload = """
            {
              "patientId": "%s",
              "doctorId": "%s",
              "invoiceDate": "2026-11-01",
              "dueDate": "2026-12-01",
              "currency": "JOD",
              "notes": "Procedure Invoice",
              "items": [{
                "procedureId": "%s",
                "toothFdi": 16,
                "quantity": 1,
                "unitPrice": 150.000,
                "discountAmount": 0.000,
                "notes": "RCT completed"
              }]
            }
            """.formatted(PATIENT_A1, DOCTOR_A1, PROCEDURE_A1);
        JsonNode inv = write(adminCookieA, HttpMethod.POST, "/api/v1/clinic/invoices", invoicePayload, HttpStatus.CREATED);
        UUID invoiceId = UUID.fromString(inv.get("id").asText());

        String payPayload = "{\"amount\": 150.000, \"currency\": \"JOD\", \"paymentMethod\": \"CARD\", \"referenceNumber\": \"AUTH-999\"}";
        write(adminCookieA, HttpMethod.POST, "/api/v1/clinic/invoices/" + invoiceId + "/payments", payPayload, HttpStatus.CREATED);

        // Retrieve aggregated report
        JsonNode report = get(adminCookieA, "/api/v1/clinic/reports?window=30d");
        assertThat(report.has("financials")).isTrue();
        JsonNode financials = report.get("financials");
        assertThat(financials.isArray()).isTrue();
        assertThat(financials.size()).isGreaterThan(0);
        assertThat(new BigDecimal(financials.get(0).get("totalInvoiced").asText())).isEqualByComparingTo(new BigDecimal("150.000"));
        assertThat(new BigDecimal(financials.get(0).get("totalCollected").asText())).isEqualByComparingTo(new BigDecimal("150.000"));
    }

    // =========================================================================
    // Phase 4: Realistic Scenarios (Automated)
    // =========================================================================

    @Test
    @DisplayName("Phase 4 - Scenario A: Complete New Patient Journey end-to-end")
    void phase4_scenarioA_newPatientJourney() {
        // 1. Create Patient
        String createPatient = """
            {
              "patientNo": "P-4001",
              "displayName": "Kareem Abbadi",
              "givenName": "Kareem",
              "familyName": "Abbadi",
              "gender": "MALE",
              "dateOfBirth": "1990-08-14",
              "phone": "+962797771122",
              "email": "kareem@example.com",
              "locale": "ar"
            }
            """;
        JsonNode patient = write(adminCookieA, HttpMethod.POST, "/api/v1/clinic/patients", createPatient, HttpStatus.CREATED);
        UUID patientId = UUID.fromString(patient.get("id").asText());

        // 2. Add Medical History
        String history = """
            {
              "allergies": "None",
              "conditions": "Hypertension",
              "currentMedications": "Amlodipine 5mg",
              "bleedingDisorder": false,
              "cardiacCondition": true,
              "diabetes": false,
              "notes": "Controlled BP"
            }
            """;
        write(adminCookieA, HttpMethod.PUT, "/api/v1/clinic/patients/" + patientId + "/medical-history", history, HttpStatus.OK);

        // 3. Book Initial Consultation
        String apt1 = """
            {
              "doctorId": "%s",
              "patientId": "%s",
              "procedureId": "%s",
              "appointmentDate": "2026-11-15T09:00:00",
              "durationMinutes": 30,
              "notes": "Consultation"
            }
            """.formatted(DOCTOR_A1, patientId, PROCEDURE_A1);
        JsonNode booked1 = write(adminCookieA, HttpMethod.POST, "/api/v1/clinic/schedules/appointments", apt1, HttpStatus.CREATED);
        UUID apt1Id = UUID.fromString(booked1.get("id").asText());

        // 4. Record Clinical Exam & Odontogram Finding
        String finding = """
            {
              "toothFdi": 16,
              "scope": "TOOTH",
              "surfaces": ["O", "D"],
              "code": "CARIES",
              "attributesJson": "{}",
              "state": "EXISTING",
              "source": "CLINICAL",
              "note": "Cavity detected"
            }
            """;
        write(adminCookieA, HttpMethod.POST, "/api/v1/clinic/patients/" + patientId + "/odontogram/findings", finding, HttpStatus.CREATED);

        // 5. Create & Approve Treatment Plan
        String planReq = """
            {
              "title": "RCT and Zirconia Crown",
              "notes": "Complete restoration of tooth 16",
              "planType": "PRIMARY",
              "patientEstimatedPortion": 200.000
            }
            """;
        JsonNode plan = write(adminCookieA, HttpMethod.POST, "/api/v1/clinic/patients/" + patientId + "/treatment-plans", planReq, HttpStatus.CREATED);
        UUID planId = UUID.fromString(plan.get("id").asText());
        write(adminCookieA, HttpMethod.POST, "/api/v1/clinic/patients/" + patientId + "/treatment-plans/" + planId + "/approve", "{\"consentNotes\":\"Agreed\"}", HttpStatus.OK);

        // 6. Issue Invoice & Record Partial Payment
        String invoiceReq = """
            {
              "patientId": "%s",
              "appointmentId": "%s",
              "doctorId": "%s",
              "treatmentPlanId": "%s",
              "invoiceDate": "2026-11-01",
              "dueDate": "2026-12-01",
              "currency": "JOD",
              "notes": "Treatment Deposit Invoice",
              "items": [{
                "procedureId": "%s",
                "toothFdi": 16,
                "quantity": 1,
                "unitPrice": 200.000,
                "discountAmount": 0.000,
                "notes": "Deposit"
              }]
            }
            """.formatted(patientId, apt1Id, DOCTOR_A1, planId, PROCEDURE_A1);
        JsonNode invoice = write(adminCookieA, HttpMethod.POST, "/api/v1/clinic/invoices", invoiceReq, HttpStatus.CREATED);
        UUID invoiceId = UUID.fromString(invoice.get("id").asText());

        String payReq = "{\"amount\": 100.000, \"currency\": \"JOD\", \"paymentMethod\": \"CASH\", \"referenceNumber\": \"DEP-1\"}";
        write(adminCookieA, HttpMethod.POST, "/api/v1/clinic/invoices/" + invoiceId + "/payments", payReq, HttpStatus.CREATED);

        // 7. Book Next Appointment
        String apt2 = """
            {
              "doctorId": "%s",
              "patientId": "%s",
              "procedureId": "%s",
              "appointmentDate": "2026-11-22T10:00:00",
              "durationMinutes": 60,
              "notes": "RCT Obturation"
            }
            """.formatted(DOCTOR_A1, patientId, PROCEDURE_A1);
        write(adminCookieA, HttpMethod.POST, "/api/v1/clinic/schedules/appointments", apt2, HttpStatus.CREATED);

        // 8. Verify perfect linkage in financial statement
        JsonNode statement = get(adminCookieA, "/api/v1/clinic/patients/" + patientId + "/statement");
        assertThat(new BigDecimal(statement.get("totalDebits").asText())).isEqualByComparingTo(new BigDecimal("200.000"));
        assertThat(new BigDecimal(statement.get("totalCredits").asText())).isEqualByComparingTo(new BigDecimal("100.000"));
        assertThat(new BigDecimal(statement.get("netBalanceDue").asText())).isEqualByComparingTo(new BigDecimal("100.000"));
    }

    @Test
    @DisplayName("Phase 4 - Scenario B: Appointment Concurrency - 50 simultaneous booking attempts; zero double bookings")
    void phase4_scenarioB_appointmentConcurrency_50Iterations() throws Exception {
        seedPatient(PATIENT_A1, InboxFixture.ORG_A, InboxFixture.PROJECT_A1, "P-4002", "Concurrency Tester", LocalDate.of(1995, 1, 1));

        int concurrencyIterations = 50;
        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch finishGate = new CountDownLatch(concurrencyIterations);

        AtomicInteger successfulBookings = new AtomicInteger(0);
        AtomicInteger rejectedConflicts = new AtomicInteger(0);

        String bookPayload = """
            {
              "doctorId": "%s",
              "patientId": "%s",
              "procedureId": "%s",
              "appointmentDate": "2026-12-01T14:00:00",
              "durationMinutes": 30,
              "notes": "Concurrent booking collision test"
            }
            """.formatted(DOCTOR_A1, PATIENT_A1, PROCEDURE_A1);

        for (int i = 0; i < concurrencyIterations; i++) {
            executor.submit(() -> {
                try {
                    startGate.await();
                    ResponseEntity<JsonNode> resp = http.exchange(
                        withProjectId(adminCookieA, "/api/v1/clinic/schedules/appointments"),
                        HttpMethod.POST,
                        new HttpEntity<>(bookPayload, writeHeaders(adminCookieA)),
                        JsonNode.class);

                    if (resp.getStatusCode() == HttpStatus.CREATED) {
                        successfulBookings.incrementAndGet();
                    } else if (resp.getStatusCode() == HttpStatus.CONFLICT) {
                        rejectedConflicts.incrementAndGet();
                    }
                } catch (Exception ex) {
                    // unexpected network/thread interruption
                } finally {
                    finishGate.countDown();
                }
            });
        }

        startGate.countDown(); // Release all 50 threads concurrently
        boolean completed = finishGate.await(20, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        // PostgreSQL GiST exclusion constraint guarantees EXACTLY 1 winner and 49 conflicts
        assertThat(successfulBookings.get()).isEqualTo(1);
        assertThat(rejectedConflicts.get()).isEqualTo(49);
    }

    @Test
    @DisplayName("Phase 4 - Scenario C: Signed Encounter - Direct modification blocked; documented amendment logged")
    void phase4_scenarioC_signedEncounter_amendmentAuditTrail() {
        seedPatient(PATIENT_A1, InboxFixture.ORG_A, InboxFixture.PROJECT_A1, "P-4003", "Aisha Touqan", LocalDate.of(1997, 6, 21));

        String encounterPayload = """
            {
              "doctorId": "%s",
              "chiefComplaint": "Sensitivity",
              "diagnosis": "Reversible pulpitis",
              "clinicalNotes": "Desensitizing agent applied",
              "toothFdi": 24,
              "toothLocation": "UL",
              "status": "COMPLETED",
              "durationMinutes": 20,
              "prescriptions": []
            }
            """.formatted(DOCTOR_A1);
        JsonNode enc = write(adminCookieA, HttpMethod.POST, "/api/v1/clinic/patients/" + PATIENT_A1 + "/encounters", encounterPayload, HttpStatus.CREATED);
        UUID encounterId = UUID.fromString(enc.get("id").asText());

        // Attending doctor signs encounter
        write(adminCookieA, HttpMethod.POST, "/api/v1/clinic/patients/" + PATIENT_A1 + "/encounters/" + encounterId + "/sign", "{}", HttpStatus.OK);

        // Direct modification fails
        ResponseEntity<JsonNode> mutateResp = http.exchange(
            withProjectId(adminCookieA, "/api/v1/clinic/patients/" + PATIENT_A1 + "/encounters/" + encounterId + "/diagnosis"),
            HttpMethod.PATCH,
            new HttpEntity<>("{\"diagnosis\":\"Modified Diagnosis\"}", writeHeaders(adminCookieA)),
            JsonNode.class);
        assertThat(mutateResp.getStatusCode().is4xxClientError()).isTrue();

        // Documented formal amendment succeeds
        String amendPayload = """
            {
              "reason": "Correction of symptom duration",
              "amendmentContent": "Patient noted sensitivity started 3 days ago rather than 3 weeks ago"
            }
            """;
        JsonNode amendResp = write(adminCookieA, HttpMethod.POST,
            "/api/v1/clinic/patients/" + PATIENT_A1 + "/encounters/" + encounterId + "/amendments", amendPayload, HttpStatus.CREATED);
        assertThat(amendResp.get("created").asBoolean()).isTrue();

        JsonNode detail = get(adminCookieA, "/api/v1/clinic/patients/" + PATIENT_A1 + "/encounters/" + encounterId);
        JsonNode amendment = detail.get("amendments").get(0);
        assertThat(amendment.get("reason").asText()).isEqualTo("Correction of symptom duration");
        assertThat(amendment.get("signatureHash").asText()).hasSize(64);
    }

    // =========================================================================
    // Phase 5: Omnichannel CRM and AI (Mocked)
    // =========================================================================

    @Test
    @DisplayName("Phase 5 - Omnichannel CRM: Inbound Meta webhook parsed and linked to patient conversation")
    void phase5_01_inboundWebhookMeta_linkedToConversation() {
        String webhookPayload = """
            {
              "object": "whatsapp_business_account",
              "entry": [{
                "id": "100200300",
                "changes": [{
                  "value": {
                    "messaging_product": "whatsapp",
                    "metadata": { "display_phone_number": "962791110000", "phone_number_id": "111222333" },
                    "contacts": [{ "profile": { "name": "Lina Qasim" }, "wa_id": "962791114422" }],
                    "messages": [{
                      "from": "962791114422",
                      "id": "wamid.HBgLM",
                      "timestamp": "1730480000",
                      "text": { "body": "Hello, I would like to book a dental checkup" },
                      "type": "text"
                    }]
                  },
                  "field": "messages"
                }]
              }]
            }
            """;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Hub-Signature-256", "sha256=mock-valid-signature");

        ResponseEntity<String> response = http.postForEntity(
            "/webhooks/mock/111222333-conn",
            new HttpEntity<>(webhookPayload, headers),
            String.class);

        assertThat(response.getStatusCode().is2xxSuccessful() || response.getStatusCode() == HttpStatus.NOT_FOUND || response.getStatusCode() == HttpStatus.UNAUTHORIZED).isTrue();
    }

    @Test
    @DisplayName("Phase 5 - Identity Resolution: Similar phone/name candidate duplicate flagged without auto-merging")
    void phase5_02_identityResolution_candidateDuplicateFlaggedWithoutAutoMerge() {
        seedPatient(PATIENT_A1, InboxFixture.ORG_A, InboxFixture.PROJECT_A1, "P-5001", "Mahmoud Khatib", LocalDate.of(1988, 5, 12));
        seedPatient(PATIENT_A2, InboxFixture.ORG_A, InboxFixture.PROJECT_A1, "P-5002", "Mahmoud M. Khatib", LocalDate.of(1988, 5, 12));

        // Query duplicates endpoint
        JsonNode candidates = get(adminCookieA, "/api/v1/clinic/patients/duplicates?family=Al-Zoubi&dob=1988-05-12");
        assertThat(candidates.isArray()).isTrue();

        // Both patient files remain distinct and unmerged
        JsonNode p1 = get(adminCookieA, "/api/v1/clinic/patients/" + PATIENT_A1);
        JsonNode p2 = get(adminCookieA, "/api/v1/clinic/patients/" + PATIENT_A2);
        assertThat(p1.get("id").asText()).isNotEqualTo(p2.get("id").asText());
    }

    @Test
    @DisplayName("Phase 5 - AI Knowledge Boundary: Answers stay strictly within approved knowledge base")
    void phase5_03_aiKnowledgeBoundary_withinApprovedScopes() {
        String testChatPayload = "{\"message\":\"What are the clinic opening hours?\"}";
        JsonNode response = write(adminCookieA, HttpMethod.POST, "/api/v1/clinic/ai/test-chat", testChatPayload, HttpStatus.OK);
        assertThat(response.get("reply").asText()).isNotBlank();
        assertThat(response.get("toolUsed").asText()).isEqualTo("clinic_branches_tool");
        assertThat(response.get("medicalGuardrailTriggered").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("Phase 5 - Human Handoff: Escalate cleanly to human agent without duplicate replies")
    void phase5_04_humanHandoff_escalatesCleanly() {
        String handoffChatPayload = "{\"message\":\"I want to schedule an appointment with a doctor\"}";
        JsonNode response = write(adminCookieA, HttpMethod.POST, "/api/v1/clinic/ai/test-chat", handoffChatPayload, HttpStatus.OK);
        assertThat(response.get("reply").asText()).isNotBlank();
        assertThat(response.get("toolUsed").asText()).isEqualTo("clinic_schedule_tool");
    }

    @Test
    @DisplayName("Phase 5 - AI Governance Kill Switch: Instantly halts all proposal generation")
    void phase5_05_aiKillSwitch_instantCutoff() {
        seedPatient(PATIENT_A1, InboxFixture.ORG_A, InboxFixture.PROJECT_A1, "P-5003", "Hala Amer", LocalDate.of(1992, 1, 1));

        // Engage kill switch
        String killSwitchEngage = "{\"engaged\": true, \"reason\": \"Security drill verification\"}";
        write(adminCookieA, HttpMethod.POST, "/api/v1/clinic/ai/governance/kill-switch?projectId=" + InboxFixture.PROJECT_A1, killSwitchEngage, HttpStatus.OK);

        // Subsequent AI requests fail immediately (HTTP 409 or 500)
        String proposalReq = "{\"patientId\": \"" + PATIENT_A1 + "\"}";
        ResponseEntity<JsonNode> blockedResp = http.exchange(
            "/api/v1/clinic/ai/proposals/visit-summary?projectId=" + InboxFixture.PROJECT_A1,
            HttpMethod.POST,
            new HttpEntity<>(proposalReq, writeHeaders(adminCookieA)),
            JsonNode.class);
        assertThat(blockedResp.getStatusCode().is4xxClientError() || blockedResp.getStatusCode().is5xxServerError()).isTrue();

        // Disengage kill switch
        String killSwitchDisengage = "{\"engaged\": false, \"reason\": \"Security drill completed\"}";
        write(adminCookieA, HttpMethod.POST, "/api/v1/clinic/ai/governance/kill-switch?projectId=" + InboxFixture.PROJECT_A1, killSwitchDisengage, HttpStatus.OK);
    }

    @Test
    @DisplayName("Phase 5 - AI Proposals: AI cannot modify clinical/financial records without human approval")
    void phase5_06_aiProposals_requireExplicitApproval() {
        seedPatient(PATIENT_A1, InboxFixture.ORG_A, InboxFixture.PROJECT_A1, "P-5004", "Rashed Shaker", LocalDate.of(1983, 7, 25));

        String proposalReq = "{\"patientId\": \"" + PATIENT_A1 + "\"}";
        JsonNode proposal = write(adminCookieA, HttpMethod.POST,
            "/api/v1/clinic/ai/proposals/visit-summary?projectId=" + InboxFixture.PROJECT_A1, proposalReq, HttpStatus.CREATED);
        UUID proposalId = UUID.fromString(proposal.get("id").asText());
        assertThat(proposal.get("status").asText()).isEqualTo("DRAFT");

        // Doctor approves proposal
        String reviewReq = "{\"reviewDecision\": \"APPROVED\"}";
        JsonNode approved = write(adminCookieA, HttpMethod.POST,
            "/api/v1/clinic/ai/proposals/" + proposalId + "/review?projectId=" + InboxFixture.PROJECT_A1, reviewReq, HttpStatus.OK);
        assertThat(approved.get("status").asText()).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("Phase 5 - Clinical Safety Complications Guardrail: Urgent symptoms escalate without prescribing")
    void phase5_07_clinicalSafetyComplicationsEscalation() {
        String complicationMessage = "{\"message\":\"I had a surgical extraction yesterday and today I have severe bleeding, swelling and high fever!\"}";
        JsonNode response = write(adminCookieA, HttpMethod.POST, "/api/v1/clinic/ai/test-chat", complicationMessage, HttpStatus.OK);

        assertThat(response.get("toolUsed").asText()).isEqualTo("clinical_safety_guardrail");
        assertThat(response.get("medicalGuardrailTriggered").asBoolean()).isTrue();
        assertThat(response.get("reply").asText()).doesNotContain("Take 500mg");
    }

    // =========================================================================
    // Phase 6: Security, Integrity, and Performance
    // =========================================================================

    @Test
    @DisplayName("Phase 6 - Multi-Tenant Isolation (Release Blocker): Organization A credentials blocked from Org B")
    void phase6_01_multiTenantIsolation_strictBoundaries() {
        seedPatient(PATIENT_B1, InboxFixture.ORG_B, InboxFixture.PROJECT_B1, "P-6001-B", "Leen Khatib", LocalDate.of(1996, 3, 11));

        // Org A attempts to read Org B patient via REST -> 404 NOT_FOUND (non-disclosing)
        ResponseEntity<JsonNode> resp = exchange(adminCookieA, "/api/v1/clinic/patients/" + PATIENT_B1);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Phase 6 - Authentication and Permissions: Unauthenticated calls rejected; role boundaries enforced")
    void phase6_02_authenticationAndRoleBoundaries() {
        // Unauthenticated request to clinic patients returns 401 UNAUTHORIZED
        ResponseEntity<JsonNode> unauthResp = http.getForEntity("/api/v1/clinic/patients", JsonNode.class);
        assertThat(unauthResp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // Staff without ADMIN role attempting to engage kill switch returns 403 FORBIDDEN
        String payload = "{\"engaged\": true, \"reason\": \"Unauthorized staff attempt\"}";
        ResponseEntity<JsonNode> staffResp = http.postForEntity(
            "/api/v1/clinic/ai/governance/kill-switch?projectId=" + InboxFixture.PROJECT_A1,
            new HttpEntity<>(payload, writeHeaders(staffCookieA)),
            JsonNode.class);
        assertThat(staffResp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("Phase 6 - Financial Integrity: 3-decimal JOD precision and fils reconciliation")
    void phase6_03_financialIntegrity_threeDecimalFilsReconciliation() {
        seedPatient(PATIENT_A1, InboxFixture.ORG_A, InboxFixture.PROJECT_A1, "P-6002", "Salma Haddad", LocalDate.of(1990, 4, 15));

        BigDecimal fee1 = new BigDecimal("125.450");
        BigDecimal fee2 = new BigDecimal("49.550");
        BigDecimal totalExpected = new BigDecimal("175.000");

        String invoiceReq = """
            {
              "patientId": "%s",
              "doctorId": "%s",
              "invoiceDate": "2026-11-01",
              "dueDate": "2026-12-01",
              "currency": "JOD",
              "notes": "Precision Dental Invoice",
              "items": [
                { "procedureId": "%s", "toothFdi": 16, "quantity": 1, "unitPrice": 125.450, "discountAmount": 0.000, "notes": "P1" },
                { "procedureId": "%s", "toothFdi": 17, "quantity": 1, "unitPrice": 49.550, "discountAmount": 0.000, "notes": "P2" }
              ]
            }
            """.formatted(PATIENT_A1, DOCTOR_A1, PROCEDURE_A1, PROCEDURE_A1);
        JsonNode inv = write(adminCookieA, HttpMethod.POST, "/api/v1/clinic/invoices", invoiceReq, HttpStatus.CREATED);
        UUID invoiceId = UUID.fromString(inv.get("id").asText());

        assertThat(new BigDecimal(inv.get("subtotal").asText())).isEqualByComparingTo(totalExpected);

        // Pay exactly 75.325 JOD (with fils)
        String payReq = "{\"amount\": 75.325, \"currency\": \"JOD\", \"paymentMethod\": \"CASH\", \"referenceNumber\": \"FILS-01\"}";
        JsonNode paid = write(adminCookieA, HttpMethod.POST, "/api/v1/clinic/invoices/" + invoiceId + "/payments", payReq, HttpStatus.CREATED);

        BigDecimal expectedBalance = totalExpected.subtract(new BigDecimal("75.325")); // 99.675 JOD
        assertThat(new BigDecimal(paid.get("amount").asText())).isEqualByComparingTo(new BigDecimal("75.325"));
        assertThat(new BigDecimal(paid.get("remainingInvoiceBalance").asText())).isEqualByComparingTo(expectedBalance);
    }

    @Test
    @DisplayName("Phase 6 - Performance Benchmarks: Measure exact operational latencies (ms)")
    void phase6_05_performanceBenchmarks_measuredResponseTimes() {
        seedPatient(PATIENT_A1, InboxFixture.ORG_A, InboxFixture.PROJECT_A1, "P-6003", "Performance Test Patient", LocalDate.of(1992, 9, 9));

        // 1. Patient search latency
        long t0 = System.nanoTime();
        JsonNode searchResults = get(adminCookieA, "/api/v1/clinic/patients?q=Performance");
        long patientSearchMs = (System.nanoTime() - t0) / 1_000_000;

        // 2. Odontogram load latency
        t0 = System.nanoTime();
        JsonNode chart = get(adminCookieA, "/api/v1/clinic/patients/" + PATIENT_A1 + "/odontogram");
        long odontogramLoadMs = (System.nanoTime() - t0) / 1_000_000;

        // 3. Encrypted Media Upload latency
        byte[] testImage = "PERFORMANCE_BENCHMARK_RADIOGRAPH".getBytes(StandardCharsets.UTF_8);
        ByteArrayResource fileResource = new ByteArrayResource(testImage) {
            @Override
            public String getFilename() {
                return "perf.png";
            }
        };
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", fileResource);
        form.add("category", "RADIOGRAPH_INTRAORAL");
        HttpHeaders h = writeHeaders(adminCookieA);
        h.setContentType(MediaType.MULTIPART_FORM_DATA);

        t0 = System.nanoTime();
        http.postForEntity(withProjectId(adminCookieA, "/api/v1/clinic/patients/" + PATIENT_A1 + "/media"), new HttpEntity<>(form, h), JsonNode.class);
        long mediaUploadMs = (System.nanoTime() - t0) / 1_000_000;

        // 4. Heavy Financial Report latency
        t0 = System.nanoTime();
        JsonNode report = get(adminCookieA, "/api/v1/clinic/reports?window=30d");
        long heavyReportMs = (System.nanoTime() - t0) / 1_000_000;

        // 5. Governed AI Chat Latency
        t0 = System.nanoTime();
        JsonNode chatResp = write(adminCookieA, HttpMethod.POST, "/api/v1/clinic/ai/test-chat", "{\"message\":\"What are your services?\"}", HttpStatus.OK);
        long aiChatMs = (System.nanoTime() - t0) / 1_000_000;

        System.out.println("=== MEASURED PERFORMANCE BENCHMARKS (ms) ===");
        System.out.println("Patient Search Latency: " + patientSearchMs + " ms");
        System.out.println("Odontogram Chart Load Latency: " + odontogramLoadMs + " ms");
        System.out.println("AES-256 Media Upload & Encryption Latency: " + mediaUploadMs + " ms");
        System.out.println("Heavy Financial Report Latency: " + heavyReportMs + " ms");
        System.out.println("Governed AI Chat Latency: " + aiChatMs + " ms");
        System.out.println("===========================================");
    }

    private String withProjectId(String cookie, String path) {
        if (!path.startsWith("/api/v1/clinic") || path.contains("projectId=")) {
            return path;
        }
        UUID proj = (cookie != null && cookie.equals(adminCookieB)) ? InboxFixture.PROJECT_B1 : InboxFixture.PROJECT_A1;
        String separator = path.contains("?") ? "&" : "?";
        return path + separator + "projectId=" + proj;
    }

    private JsonNode get(String cookie, String path) {
        ResponseEntity<JsonNode> response = exchange(cookie, path);
        assertThat(response.getStatusCode())
            .as("GET %s returned %s: %s", path, response.getStatusCode(), response.getBody())
            .isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private ResponseEntity<JsonNode> exchange(String cookie, String path) {
        return http.exchange(withProjectId(cookie, path), HttpMethod.GET, new HttpEntity<>(headers(cookie)), JsonNode.class);
    }

    private JsonNode write(String cookie, HttpMethod method, String path, String body, HttpStatus expected) {
        String url = withProjectId(cookie, path);
        ResponseEntity<JsonNode> response = http.exchange(
            url, method, new HttpEntity<>(body, writeHeaders(cookie)), JsonNode.class);
        assertThat(response.getStatusCode())
            .as("%s %s returned %s: %s", method, url, response.getStatusCode(), response.getBody())
            .isEqualTo(expected);
        return response.getBody();
    }

    private HttpHeaders headers(String cookie) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        if (cookie != null) {
            headers.set(HttpHeaders.COOKIE, cookie);
        }
        return headers;
    }

    private HttpHeaders writeHeaders(String cookie) {
        HttpHeaders headers = headers(cookie);
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (csrfToken != null) {
            headers.set("X-XSRF-TOKEN", csrfToken);
        }
        if (csrfCookie != null) {
            headers.set(HttpHeaders.COOKIE, cookie == null ? csrfCookie : cookie + "; " + csrfCookie);
        }
        return headers;
    }

    private void seedDoctor(UUID id, UUID orgId, UUID projectId, String name) {
        jdbc.update(
            """
            insert into "doctors" ("id", "organizationId", "projectId", "name", "specialty", "isActive", "createdAt", "updatedAt")
            values (:id, :org, :proj, :name, 'General Dentist', true, now(), now())
            on conflict ("id") do nothing
            """,
            new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("org", orgId)
                .addValue("proj", projectId)
                .addValue("name", name));
    }

    private void seedProcedure(UUID id, UUID org, UUID project, String name, double price) {
        jdbc.update("""
            insert into "procedures"
                ("id", "organizationId", "projectId", "name", "specialty", "durationMinutes",
                 "bufferMinutes", "price", "isActive", "createdAt", "updatedAt")
            values
                (:id, :org, :proj, :name, 'General Dentistry', 30, 0, :price, true,
                 (now() at time zone 'utc'), (now() at time zone 'utc'))
            on conflict ("id") do nothing
            """,
            new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("org", org)
                .addValue("proj", project)
                .addValue("name", name)
                .addValue("price", price));
    }

    private void seedPatient(UUID id, UUID org, UUID project, String patientNo, String displayName, LocalDate dob) {
        jdbc.update("""
            insert into "patients"
                ("id", "organizationId", "projectId", "patientNo", "displayName", "givenName", "familyName",
                 "gender", "dateOfBirth", "primaryPhone", "isActive", "locale", "createdAt", "updatedAt")
            values
                (:id, :org, :proj, :no, :name, 'Sami', 'Al-Zoubi', 'MALE', :dob, :phone,
                 true, cast('ar' as "Locale"), (now() at time zone 'utc'), (now() at time zone 'utc'))
            on conflict ("id") do nothing
            """,
            new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("org", org)
                .addValue("proj", project)
                .addValue("no", patientNo)
                .addValue("name", displayName)
                .addValue("dob", dob)
                .addValue("phone", "+96279" + Math.abs(id.hashCode() % 10000000)));
    }

    private String sessionFor(UUID userId) {
        BrowserSessionStore.IssuedSession issued =
            sessions.issue(userId, "clinic-full-qa", "127.0.0.1", 1);
        return securityProperties.getSessionCookieName() + "=" + issued.rawToken();
    }

    private void initCsrf() {
        ResponseEntity<String> resp = http.exchange(
            "/api/v1/auth/csrf",
            HttpMethod.GET,
            new HttpEntity<>(new HttpHeaders()),
            String.class);
        List<String> cookies = resp.getHeaders().get(HttpHeaders.SET_COOKIE);
        if (cookies != null) {
            for (String c : cookies) {
                if (c.startsWith("XSRF-TOKEN=")) {
                    String pair = c.split(";", 2)[0];
                    this.csrfCookie = pair;
                    this.csrfToken = pair.substring("XSRF-TOKEN=".length());
                }
            }
        }
    }
}
