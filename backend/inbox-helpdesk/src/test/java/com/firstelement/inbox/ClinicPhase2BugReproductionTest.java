package com.firstelement.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.firstelement.crm.clinic.ClinicScope;
import com.firstelement.crm.clinic.billing.InvoiceModels;
import com.firstelement.crm.clinic.billing.InvoiceService;
import com.firstelement.crm.clinic.encounter.EncounterModels;
import com.firstelement.crm.clinic.encounter.EncounterService;
import com.firstelement.crm.clinic.report.ClinicReportModels;
import com.firstelement.crm.clinic.report.ClinicReportService;
import com.firstelement.security.TenantContext;
import com.firstelement.security.UserRole;
import com.firstelement.security.config.SecurityProperties;
import com.firstelement.security.query.TenantQueryGuard;
import com.firstelement.security.session.BrowserSessionStore;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Phase 2 TDD Bug Reproduction Suite.
 *
 * <p>Ground Rule: "For any fix touching financial calculations, clinical record logic, or
 * permissions, write a failing test first, then fix, then confirm the test passes."
 */
@SpringBootTest(
    classes = InboxTestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ClinicPhase2BugReproductionTest extends InboxIntegrationTestBase {

    private static final UUID PATIENT_A1 = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000a2a01");
    private static final UUID PATIENT_A2 = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000a2a02");
    private static final UUID DOCTOR_A1 = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000d2a01");
    private static final UUID PROCEDURE_A1 = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000c2a01");

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

    @Autowired
    private TenantQueryGuard guard;

    @Autowired
    private EncounterService encounters;

    @Autowired
    private InvoiceService invoices;

    @Autowired
    private ClinicReportService reports;

    private TenantContext adminCtx;
    private ClinicScope scopeA1;
    private String adminCookie;
    private String staffCookie;
    private String csrfCookie;
    private String csrfToken;

    @BeforeEach
    void setUp() {
        jdbc.getJdbcOperations().execute(
            "truncate table patient_odontogram_findings, clinic_finding_vocabulary,"
                + " clinic_schedules, patients, clinical_encounters, clinic_invoices,"
                + " clinic_payments, clinic_refunds, clinic_patient_ledger_entries,"
                + " treatment_plans cascade");
        inbox.reset();

        seedDoctor(DOCTOR_A1, InboxFixture.ORG_A, InboxFixture.PROJECT_A1, "Dr. Tariq Mansour");
        seedProcedure(PROCEDURE_A1, InboxFixture.ORG_A, InboxFixture.PROJECT_A1, "Crown Prep", 75.500);
        seedPatient(PATIENT_A1, InboxFixture.ORG_A, InboxFixture.PROJECT_A1, "P-2001", "Sami Al-Zoubi", LocalDate.of(1988, 5, 12));
        seedPatient(PATIENT_A2, InboxFixture.ORG_A, InboxFixture.PROJECT_A1, "P-2002", "Rania Al-Zoubi", LocalDate.of(1992, 9, 20));

        adminCtx = TenantContext.forUser(
            UUID.randomUUID(),
            InboxFixture.USER_ADMIN_A,
            InboxFixture.ORG_A,
            UserRole.ADMIN,
            Set.of(InboxFixture.PROJECT_A1, InboxFixture.PROJECT_A2));
        scopeA1 = ClinicScope.forRequestedProject(guard, adminCtx, InboxFixture.PROJECT_A1);

        adminCookie = sessionFor(InboxFixture.USER_ADMIN_A);
        staffCookie = sessionFor(InboxFixture.USER_STAFF_A);
        initCsrf();
    }

    @AfterEach
    void tearDown() {
        jdbc.getJdbcOperations().execute(
            "truncate table patient_odontogram_findings, clinic_finding_vocabulary,"
                + " clinic_schedules, patients, clinical_encounters, clinic_invoices,"
                + " clinic_payments, clinic_refunds, clinic_patient_ledger_entries,"
                + " treatment_plans, clinic_ai_governance, clinic_ai_proposals cascade");
    }

    @Test
    @DisplayName("BUG-001 (P0): Signed encounters must reject adding or deleting prescriptions or deleting prescription sheets")
    void signedEncounterBlocksPrescriptionMutations() {
        UUID encounterId = encounters.createEncounter(
            scopeA1,
            PATIENT_A1,
            new EncounterModels.CreateEncounterRequest(
                DOCTOR_A1,
                null,
                null,
                null,
                "Severe molar pain",
                "Deep caries on tooth 36",
                "Irreversible pulpitis",
                "Patient tolerated procedure well",
                "Root canal therapy initiated",
                36,
                "Lower Left",
                "COMPLETED",
                LocalDateTime.now(),
                30,
                InboxFixture.PROJECT_A1.toString(),
                false,
                120,
                80,
                72,
                new BigDecimal("36.8"),
                List.of()),
            adminCtx);

        UUID rxId = encounters.addPrescription(
            scopeA1,
            encounterId,
            PATIENT_A1,
            new EncounterModels.CreatePrescriptionRequest(
                "Ibuprofen", "400mg", "TID", "5 days", "Take after meals", DOCTOR_A1),
            adminCtx);

        String sig = encounters.signEncounter(scopeA1, encounterId, adminCtx);
        assertThat(sig).isNotBlank();
        EncounterModels.EncounterDetail signed = encounters.getEncounterDetail(scopeA1, encounterId, adminCtx);
        assertThat(signed.encounter().isSigned()).isTrue();
        assertThat(signed.encounter().signatureHash()).isEqualTo(sig);

        // 1. Adding a prescription to a signed encounter MUST fail
        assertThatThrownBy(() -> encounters.addPrescription(
            scopeA1,
            encounterId,
            PATIENT_A1,
            new EncounterModels.CreatePrescriptionRequest(
                "Amoxicillin", "500mg", "TID", "7 days", "Finish course", DOCTOR_A1),
            adminCtx))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("immutable");

        // 2. Deleting a prescription on a signed encounter MUST fail
        assertThatThrownBy(() -> encounters.deletePrescription(scopeA1, rxId, adminCtx))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("immutable");

        // 3. Deleting a prescription sheet on a signed encounter MUST fail
        assertThatThrownBy(() -> encounters.deletePrescriptionSheet(scopeA1, encounterId, adminCtx))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("immutable");
    }

    @Test
    @DisplayName("BUG-002 (P0): Invoice discounts must maintain exact ledger-to-invoice reconciliation and reject discounts exceeding unpaid balance")
    void invoiceDiscountMaintainsExactLedgerReconciliationAndRejectsOverPaidDiscount() {
        InvoiceModels.InvoiceHeader inv = invoices.createInvoice(
            scopeA1,
            new InvoiceModels.CreateInvoiceRequest(
                PATIENT_A1,
                null,
                null,
                "JOD",
                "Initial treatment invoice",
                List.of(new InvoiceModels.CreateInvoiceItemRequest(
                    null, PROCEDURE_A1, "PROCEDURE", "Zirconia Crown", 36, new BigDecimal("100.000"), 1))),
            adminCtx);

        // Record a 60.000 JOD partial payment -> remaining balanceDue is 40.000 JOD
        InvoiceModels.PaymentReceipt receipt = invoices.recordPayment(
            scopeA1,
            inv.id(),
            new InvoiceModels.RecordPaymentRequest(
                new BigDecimal("60.000"),
                "JOD",
                "CASH",
                "RCPT-001",
                "Partial cash payment",
                "IDEM-PAY-001"),
            adminCtx);
        assertThat(receipt.remainingInvoiceBalance()).isEqualByComparingTo("40.000");

        // 1. Applying a 50.000 JOD discount (> 40.000 JOD remaining unpaid balance) MUST be rejected
        assertThatThrownBy(() -> invoices.applyDiscount(
            scopeA1,
            inv.id(),
            new InvoiceModels.ApplyDiscountRequest(new BigDecimal("50.000"), "Over-balance discount"),
            adminCtx))
            .isInstanceOf(IllegalArgumentException.class);

        // 2. Apply a 10.000 JOD discount -> balanceDue = 30.000 JOD
        InvoiceModels.InvoiceHeader afterFirstDiscount = invoices.applyDiscount(
            scopeA1,
            inv.id(),
            new InvoiceModels.ApplyDiscountRequest(new BigDecimal("10.000"), "Loyalty discount 10%"),
            adminCtx);
        assertThat(afterFirstDiscount.balanceDue()).isEqualByComparingTo("30.000");

        // 3. Update discount to 15.000 JOD -> balanceDue = 25.000 JOD
        InvoiceModels.InvoiceHeader afterSecondDiscount = invoices.applyDiscount(
            scopeA1,
            inv.id(),
            new InvoiceModels.ApplyDiscountRequest(new BigDecimal("15.000"), "Updated loyalty discount 15%"),
            adminCtx);
        assertThat(afterSecondDiscount.balanceDue()).isEqualByComparingTo("25.000");

        // 4. Verify patient statement netBalanceDue matches invoice balanceDue to the exact fils (25.000 JOD)
        InvoiceModels.PatientStatement statement = invoices.getPatientStatement(scopeA1, PATIENT_A1, null, null);
        assertThat(statement.netBalanceDue())
            .as("Patient statement netBalanceDue must equal invoice.balanceDue (25.000 JOD) to the exact fils")
            .isEqualByComparingTo(afterSecondDiscount.balanceDue());
    }

    @Test
    @DisplayName("BUG-003 (P0): Recording a refund in a currency different from the invoice currency must be rejected")
    void recordRefundRejectsMismatchedCurrency() {
        InvoiceModels.InvoiceHeader inv = invoices.createInvoice(
            scopeA1,
            new InvoiceModels.CreateInvoiceRequest(
                PATIENT_A1,
                null,
                null,
                "JOD",
                "JOD invoice",
                List.of(new InvoiceModels.CreateInvoiceItemRequest(
                    null, PROCEDURE_A1, "PROCEDURE", "Composite Filling", 11, new BigDecimal("50.000"), 1))),
            adminCtx);

        InvoiceModels.PaymentReceipt receipt = invoices.recordPayment(
            scopeA1,
            inv.id(),
            new InvoiceModels.RecordPaymentRequest(
                new BigDecimal("50.000"), "JOD", "CARD", "CARD-101", "Paid in full", "IDEM-PAY-002"),
            adminCtx);

        assertThatThrownBy(() -> invoices.recordRefund(
            scopeA1,
            inv.id(),
            new InvoiceModels.RecordRefundRequest(
                new BigDecimal("10.000"),
                "USD",
                "CASH",
                "Cross-currency refund attempt",
                receipt.paymentId()),
            adminCtx))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("currency");
    }

    @Test
    @DisplayName("BUG-010 (P1): Reports must include patientId appointments, compute doctor invoiced/collected totals, and exclude cancelled invoices from invoiceCount")
    void reportsReflectPatientIdAppointmentsDoctorRevenueAndExcludeCancelledInvoiceCount() {
        // 1. Insert appointment linked by patientId (with customerId = null)
        UUID apptId = UUID.randomUUID();
        jdbc.update("""
            insert into "appointments"
                ("id", "organizationId", "projectId", "patientId", "doctorId", "procedureId",
                 "title", "patientName", "patientPhone", "appointmentDate", "durationMinutes",
                 "status", "service", "doctorName", "isAiProposed", "ticketNumber", "createdAt", "updatedAt")
            values
                (:id, :org, :proj, :patientId, :doctorId, :procId,
                 'Dental Checkup', 'Sami Al-Zoubi', '+962791112233',
                 (now() at time zone 'utc'), 30, 'COMPLETED', 'Crown Prep', 'Dr. Tariq Mansour',
                 false, 'T-REP-1', (now() at time zone 'utc'), (now() at time zone 'utc'))
            """,
            new MapSqlParameterSource()
                .addValue("id", apptId)
                .addValue("org", InboxFixture.ORG_A)
                .addValue("proj", InboxFixture.PROJECT_A1)
                .addValue("patientId", PATIENT_A1)
                .addValue("doctorId", DOCTOR_A1)
                .addValue("procId", PROCEDURE_A1));

        // 2. Create encounter with DOCTOR_A1
        UUID encId = encounters.createEncounter(
            scopeA1,
            PATIENT_A1,
            new EncounterModels.CreateEncounterRequest(
                DOCTOR_A1, apptId, null, null, "Checkup", "Exam", "Caries", "Done", "Crown Prep",
                16, "UR", "COMPLETED", LocalDateTime.now(), 30, InboxFixture.PROJECT_A1.toString(),
                false, null, null, null, null, List.of()),
            adminCtx);

        // 3. Create active invoice for 75.500 JOD and pay 50.250 JOD
        InvoiceModels.InvoiceHeader activeInv = invoices.createInvoice(
            scopeA1,
            new InvoiceModels.CreateInvoiceRequest(
                PATIENT_A1, encId, null, "JOD", "Active invoice",
                List.of(new InvoiceModels.CreateInvoiceItemRequest(
                    null, PROCEDURE_A1, "PROCEDURE", "Crown Prep", 16, new BigDecimal("75.500"), 1))),
            adminCtx);
        invoices.recordPayment(
            scopeA1, activeInv.id(),
            new InvoiceModels.RecordPaymentRequest(
                new BigDecimal("50.250"), "JOD", "CASH", "RCPT-REP-1", "Partial payment", "IDEM-REP-1"),
            adminCtx);

        // 4. Create second invoice and cancel it
        InvoiceModels.InvoiceHeader cancelledInv = invoices.createInvoice(
            scopeA1,
            new InvoiceModels.CreateInvoiceRequest(
                PATIENT_A1, encId, null, "JOD", "To cancel",
                List.of(new InvoiceModels.CreateInvoiceItemRequest(
                    null, PROCEDURE_A1, "PROCEDURE", "Extra item", 17, new BigDecimal("30.000"), 1))),
            adminCtx);
        invoices.cancelInvoice(
            scopeA1, cancelledInv.id(),
            new InvoiceModels.CancelInvoiceRequest("Entered in error"),
            adminCtx);

        ClinicReportModels.ClinicReportView report = reports.getClinicReport(
            scopeA1, "30d", LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1));

        // Verify Age Distribution includes PATIENT_A1 (born 1988 -> age26to50 bucket)
        ClinicReportModels.PatientAgeDistribution ageDist = report.patientAgeDistribution();
        int totalPatientsInAgeReport = ageDist.age0to25() + ageDist.age26to50() + ageDist.age50Plus();
        assertThat(totalPatientsInAgeReport)
            .as("Age distribution report must include patients linked via appointments.patientId")
            .isGreaterThanOrEqualTo(1);

        // Verify Doctor Productivity reflects invoiced (75.500) and collected (50.250) amounts
        List<ClinicReportModels.DoctorProductivityRow> productivity = report.doctorProductivity();
        assertThat(productivity).isNotEmpty();
        ClinicReportModels.DoctorProductivityRow docRow = productivity.stream()
            .filter(r -> DOCTOR_A1.equals(r.doctorId()))
            .findFirst()
            .orElseThrow();
        assertThat(docRow.totalInvoicedAmount()).isEqualByComparingTo("75.500");
        assertThat(docRow.totalCollectedAmount()).isEqualByComparingTo("50.250");

        // Verify Financial Summary excludes cancelled invoice from invoiceCount
        assertThat(report.financials()).isNotEmpty();
        ClinicReportModels.FinancialSummaryRow jodRow = report.financials().stream()
            .filter(r -> "JOD".equalsIgnoreCase(r.currency()))
            .findFirst()
            .orElseThrow();
        assertThat(jodRow.invoiceCount())
            .as("Financial summary invoiceCount must exclude CANCELLED invoices")
            .isEqualTo(1);
    }

    @Test
    @DisplayName("BUG-011 (P1): Nested patient endpoints must verify parent patientId ownership and reject cross-patient access")
    void nestedPatientEndpointsVerifyParentPatientId() {
        UUID encForPatientA2 = encounters.createEncounter(
            scopeA1,
            PATIENT_A2,
            new EncounterModels.CreateEncounterRequest(
                DOCTOR_A1, null, null, null, "Toothache", "Exam", "Gingivitis", "Notes", "Scaling",
                null, null, "COMPLETED", LocalDateTime.now(), 30, InboxFixture.PROJECT_A1.toString(),
                false, null, null, null, null, List.of()),
            adminCtx);

        // Requesting Patient A2's encounter under Patient A1's URL path MUST return 404 NOT_FOUND
        ResponseEntity<JsonNode> wrongParentEncounter = http.exchange(
            "/api/v1/clinic/patients/" + PATIENT_A1 + "/encounters/" + encForPatientA2
                + "?projectId=" + InboxFixture.PROJECT_A1,
            HttpMethod.GET,
            new HttpEntity<>(headers(adminCookie, false)),
            JsonNode.class);
        assertThat(wrongParentEncounter.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("BUG-012 (P1): AI emergency kill switch must be restricted to ADMIN role and reject non-admin staff with 403")
    void aiKillSwitchRestrictedToAdminRole() {
        HttpEntity<String> body = new HttpEntity<>(
            "{\"engaged\":true,\"reason\":\"Unauthorized staff attempt\"}",
            headers(staffCookie, true));

        ResponseEntity<JsonNode> staffAttempt = http.exchange(
            "/api/v1/clinic/ai/governance/kill-switch?projectId=" + InboxFixture.PROJECT_A1,
            HttpMethod.POST,
            body,
            JsonNode.class);
        assertThat(staffAttempt.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        HttpEntity<String> adminBody = new HttpEntity<>(
            "{\"engaged\":true,\"reason\":\"Authorized admin emergency stop\"}",
            headers(adminCookie, true));
        ResponseEntity<JsonNode> adminAttempt = http.exchange(
            "/api/v1/clinic/ai/governance/kill-switch?projectId=" + InboxFixture.PROJECT_A1,
            HttpMethod.POST,
            adminBody,
            JsonNode.class);
        assertThat(adminAttempt.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // --- Helpers ---

    private void seedDoctor(UUID id, UUID org, UUID project, String name) {
        jdbc.update("""
            insert into "doctors"
                ("id", "organizationId", "projectId", "name", "specialty", "department",
                 "workingDays", "startHour", "endHour", "slotDuration", "isActive", "createdAt", "updatedAt")
            values
                (:id, :org, :proj, :name, 'General Dentistry', 'Dental',
                 '{1,2,3,4,5}', '09:00', '17:00', 30, true, (now() at time zone 'utc'), (now() at time zone 'utc'))
            """,
            new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("org", org)
                .addValue("proj", project)
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
            sessions.issue(userId, "phase2-bug-repro", "127.0.0.1", 1);
        return securityProperties.getSessionCookieName() + "=" + issued.rawToken();
    }

    private void initCsrf() {
        ResponseEntity<String> resp = http.exchange(
            "/api/v1/auth/csrf",
            HttpMethod.GET,
            new HttpEntity<>(new HttpHeaders()),
            String.class);
        List<String> cookies = resp.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(cookies).isNotEmpty();
        for (String c : cookies) {
            if (c.startsWith("XSRF-TOKEN=")) {
                String pair = c.split(";", 2)[0];
                this.csrfCookie = pair;
                this.csrfToken = pair.substring("XSRF-TOKEN=".length());
            }
        }
    }

    private HttpHeaders headers(String sessionCookie, boolean includeCsrf) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (includeCsrf && csrfCookie != null) {
            h.add(HttpHeaders.COOKIE, sessionCookie + "; " + csrfCookie);
            h.add("X-XSRF-TOKEN", csrfToken);
        } else {
            h.add(HttpHeaders.COOKIE, sessionCookie);
        }
        return h;
    }
}
