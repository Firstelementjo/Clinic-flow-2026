package com.firstelement.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.firstelement.security.config.SecurityProperties;
import com.firstelement.security.session.BrowserSessionStore;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
 * The patients, odontogram and availability routes, over HTTP.
 *
 * <p>This closes the gate clause that was open: "unauthorized roles cannot enumerate". Everything
 * underneath was already proven at the data layer in {@code crm-core}; this file proves it at the
 * boundary a real caller meets.
 *
 * <p>Two properties carry most of the weight, and neither is about a status code:
 *
 * <ul>
 *   <li><b>Absent and unauthorized are indistinguishable.</b> Another tenant's patient id and a
 *       nonexistent one produce the same code, the same message and the same details. A route that
 *       answered differently for one and the other would turn a patient id into an oracle.</li>
 *   <li><b>A list route answers empty rather than refusing.</b> An outsider with no membership
 *       reads an empty list, and a foreign patient id yields an empty chart. Both are the same
 *       non-disclosure property a 404 gives, and both are indistinguishable from "nothing there".
 *       Asserting 404 here instead would have been asserting a DIFFERENT answer from a member's,
 *       which is the defect the test exists to catch.</li>
 * </ul>
 *
 * <p>Seeding is raw SQL rather than {@code PatientService} on purpose: this file is about the HTTP
 * contract, and going through the service would mean a change in the service could quietly change
 * what these assertions mean.
 */
@SpringBootTest(classes = InboxTestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ClinicPatientHttpIntegrationTest extends InboxIntegrationTestBase {

    private static final UUID PATIENT_A = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000a0a01");
    private static final UUID PATIENT_B = UUID.fromString("bbbbbbbb-0000-0000-0000-0000000a0b01");
    private static final UUID DOCTOR_A = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000d0a01");
    private static final UUID DOCTOR_B = UUID.fromString("bbbbbbbb-0000-0000-0000-0000000d0b01");
    private static final UUID PROCEDURE_A = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000c0a01");
    private static final UUID ABSENT = UUID.fromString("cccccccc-0000-0000-0000-0000000000ff");

    /** A Monday inside the seeded schedule's effective range. */
    private static final String MONDAY = "2026-08-03";

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

    private Csrf csrf;

    @BeforeEach
    void seed() {
        // The shared reset seeds the USERS and their memberships. Without it a session cannot be
        // issued and every route answers as an anonymous caller. It also clears doctors and
        // procedures, so it runs BEFORE this file seeds anything.
        inbox.reset();

        // TRUNCATE, not DELETE: patient_odontogram_findings is guarded by
        // firstelement_append_only(), which refuses a row-level DELETE by design. That guard
        // firing during a fixture teardown is the guard WORKING, and weakening it to make a test
        // convenient would be exactly backwards. TRUNCATE does not fire row triggers.
        // These are disposable test containers, never production.
        jdbc.getJdbcOperations().execute(
                "truncate table patient_odontogram_findings, clinic_finding_vocabulary,"
                        + " clinic_schedules, patients cascade");
        jdbc.update("delete from \"doctors\" where \"id\" in (:a, :b)", new MapSqlParameterSource()
                .addValue("a", DOCTOR_A).addValue("b", DOCTOR_B));

        patient(PATIENT_A, InboxFixture.ORG_A, InboxFixture.PROJECT_A1, "P-1001", "Layla Hassan");
        patient(PATIENT_B, InboxFixture.ORG_B, InboxFixture.PROJECT_B1, "P-2001", "Omar Haddad");

        // Seeded HERE rather than taken from ClinicFixture: that fixture seeds in its own
        // @BeforeEach, and depending on another class's lifecycle would make this one pass or
        // fail on the order the suite happens to run in.
        doctor(DOCTOR_A, InboxFixture.ORG_A, InboxFixture.PROJECT_A1);
        doctor(DOCTOR_B, InboxFixture.ORG_B, InboxFixture.PROJECT_B1);
        procedure(PROCEDURE_A);
        schedule(DOCTOR_A);
        csrf = csrfToken();
    }

    @Nested
    @DisplayName("the patients routes")
    class Patients {

        @Test
        @DisplayName("a caller who may read the clinic schedule sees their own clinic's patients")
        void anAuthorizedCallerSeesTheirOwnPatients() {
            JsonNode response = get(sessionFor(InboxFixture.USER_ADMIN_A), "/api/v1/clinic/patients");

            assertThat(response.isArray()).isTrue();
            assertThat(names(response)).contains("Layla Hassan").doesNotContain("Omar Haddad");
        }

        @Test
        @DisplayName("the search term narrows the list, and a blank term lists everything")
        void theSearchTermNarrows() {
            String admin = sessionFor(InboxFixture.USER_ADMIN_A);

            assertThat(names(get(admin, "/api/v1/clinic/patients?q=Layla")))
                    .as("a term that matches one patient")
                    .containsExactly("Layla Hassan");

            assertThat(names(get(admin, "/api/v1/clinic/patients?q=")))
                    .as("a BLANK term is a list, not a search for the empty string")
                    .contains("Layla Hassan");

            assertThat(get(admin, "/api/v1/clinic/patients?q=zzzz-no-such-patient"))
                    .as("a term matching nothing is an empty list, not a 404")
                    .isEmpty();
        }

        @Test
        @DisplayName("another tenant's patient id is indistinguishable from one that never existed")
        void anotherTenantsPatientIsInvisible() {
            String adminB = sessionFor(InboxFixture.USER_ADMIN_B);

            ResponseEntity<JsonNode> foreign = exchange(adminB, "/api/v1/clinic/patients/" + PATIENT_A);
            ResponseEntity<JsonNode> absent = exchange(adminB, "/api/v1/clinic/patients/" + ABSENT);

            assertThat(foreign.getStatusCode())
                    .as("a patient in another organization must not be reachable")
                    .isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(absent.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

            // Compared field by field: the advice stamps a timestamp, so the bodies cannot be
            // compared whole. Everything a caller could read must match.
            assertThat(foreign.getBody().get("code")).isEqualTo(absent.getBody().get("code"));
            assertThat(foreign.getBody().get("message")).isEqualTo(absent.getBody().get("message"));
            assertThat(foreign.getBody().get("details")).isEqualTo(absent.getBody().get("details"));
            assertThat(String.valueOf(foreign.getBody()))
                    .as("the refusal carries no tenant, no project and no patient name")
                    .doesNotContain(InboxFixture.ORG_A.toString())
                    .doesNotContain("Hassan");
        }

        @Test
        @DisplayName("a caller with no membership reads an empty list, not a different answer")
        void anOutsiderReachesNothing() {
            JsonNode body = get(sessionFor(InboxFixture.USER_OUTSIDER_A), "/api/v1/clinic/patients");

            assertThat(body)
                    .as("no membership means an empty scope, so the list is EMPTY rather than "
                        + "refused. An earlier version of this test expected 404, which would have "
                        + "been a DIFFERENT answer from a member's and therefore a way to tell them "
                        + "apart - the exact defect the test exists to catch.")
                    .isEmpty();
        }

        @Test
        @DisplayName("a project outside the caller's membership is refused before any SQL")
        void aForeignProjectIsRefused() {
            ResponseEntity<JsonNode> response = exchange(
                    sessionFor(InboxFixture.USER_ADMIN_A),
                    "/api/v1/clinic/patients?projectId=" + InboxFixture.PROJECT_B1);

            assertThat(response.getStatusCode())
                    .as("a client-supplied project is a request, not an authorization")
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("the odontogram routes")
    class Odontogram {

        @Test
        @DisplayName("the chart is empty before anything is recorded, and readable after")
        void theChartReadsBack() {
            String admin = sessionFor(InboxFixture.USER_ADMIN_A);
            String chart = "/api/v1/clinic/patients/" + PATIENT_A + "/odontogram";

            assertThat(get(admin, chart))
                    .as("an empty chart is distinguishable from a denied route")
                    .isEmpty();

            seedFinding();

            JsonNode recorded = get(admin, chart);
            assertThat(recorded).hasSize(1);
            assertThat(recorded.get(0).get("finding").get("code").asText())
                    .isEqualTo("CARIES-ICDAS-3");
            assertThat(recorded.get(0).get("superseded").asBoolean())
                    .as("a fresh finding still stands")
                    .isFalse();
        }

        @Test
        @DisplayName("another tenant's chart is an empty chart, identical to a patient who never existed")
        void anotherTenantsChartIsNotReachable() {
            String adminB = sessionFor(InboxFixture.USER_ADMIN_B);

            JsonNode foreign = get(adminB, "/api/v1/clinic/patients/" + PATIENT_A + "/odontogram");
            JsonNode absent = get(adminB, "/api/v1/clinic/patients/" + ABSENT + "/odontogram");

            assertThat(foreign)
                    .as("the chart reads findings inside the caller's scope rather than looking the "
                        + "patient up, so an unknown id yields an EMPTY CHART rather than a 404. "
                        + "That is the same non-disclosure property a 404 gives, and it is what "
                        + "makes a patient id useless as an oracle.")
                    .isEmpty();
            assertThat(foreign).isEqualTo(absent);
        }

        @Test
        @DisplayName("the vocabulary seeds from the template and names the clinic it seeded")
        void theVocabularySeedsOnFirstCall() {
            String admin = sessionFor(InboxFixture.USER_ADMIN_A);

            JsonNode vocabulary = get(admin,
                    "/api/v1/clinic/odontogram/vocabulary?projectId=" + InboxFixture.PROJECT_A1);

            assertThat(vocabulary)
                    .as("a clinician never has to prepare a vocabulary before diagnosing")
                    .isNotEmpty();
            assertThat(codes(vocabulary)).contains("CARIES-ICDAS-3");
            assertThat(get(admin, "/api/v1/clinic/odontogram/vocabulary?projectId="
                    + InboxFixture.PROJECT_A1))
                    .as("and a second call is not a duplicate copy")
                    .hasSameSizeAs(vocabulary);
        }

        @Test
        @DisplayName("the vocabulary route refuses when the clinic is not named and cannot be inferred")
        void theVocabularyRouteRefusesAnAmbiguousClinic() {
            ResponseEntity<JsonNode> response = exchange(
                    sessionFor(InboxFixture.USER_ADMIN_A), "/api/v1/clinic/odontogram/vocabulary");

            assertThat(response.getStatusCode())
                    .as("ADMIN_A can reach two clinics. Seeding a catalogue belongs to exactly one, "
                        + "and writing 'the first accessible project' would file it under a clinic "
                        + "the caller never chose.")
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("the availability route")
    class Availability {

        @Test
        @DisplayName("slots separate patient time from the padding the dentist occupies")
        void slotsSeparatePatientTimeFromPadding() {
            JsonNode slots = get(sessionFor(InboxFixture.USER_ADMIN_A),
                    "/api/v1/clinic/availability?doctorId=" + DOCTOR_A
                            + "&date=" + MONDAY + "&durationMinutes=30&bufferMinutes=10");

            assertThat(slots.isArray()).isTrue();
            assertThat(slots).isNotEmpty();

            JsonNode first = slots.get(0);
            assertThat(first.get("patientMinutes").asInt())
                    .as("DEC-001: the patient is booked for the SERVICE duration")
                    .isEqualTo(30);
            assertThat(first.get("startsAt").asText())
                    .as("09:00, and the next is 40 minutes later because the buffer occupies the "
                        + "schedule AROUND the patient rather than being patient time")
                    .isEqualTo("09:00:00");
            assertThat(slots.get(1).get("startsAt").asText()).isEqualTo("09:40:00");
        }

        @Test
        @DisplayName("without a buffer the steps are 30 minutes apart")
        void withoutABufferTheStepIsTheServiceDuration() {
            JsonNode slots = get(sessionFor(InboxFixture.USER_ADMIN_A),
                    "/api/v1/clinic/availability?doctorId=" + DOCTOR_A
                            + "&date=" + MONDAY + "&durationMinutes=30&bufferMinutes=0");

            assertThat(slots.get(0).get("startsAt").asText()).isEqualTo("09:00:00");
            assertThat(slots.get(1).get("startsAt").asText()).isEqualTo("09:30:00");
        }

        @Test
        @DisplayName("another tenant's dentist has no slots")
        void anotherTenantsDoctorHasNoSlots() {
            assertThat(get(sessionFor(InboxFixture.USER_ADMIN_B),
                    "/api/v1/clinic/availability?doctorId=" + DOCTOR_A
                            + "&date=" + MONDAY + "&durationMinutes=30&bufferMinutes=0"))
                    .as("a dentist from another organization has no schedule here")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("the patient registration route")
    class Registration {

        @Test
        @DisplayName("create stores the profile fields and hashes the portal password")
        void createStoresTheProfileAndHashesThePassword() {
            String admin = sessionFor(InboxFixture.USER_ADMIN_A);
            String cnic = "CNIC-" + UUID.randomUUID();

            JsonNode created = write(admin, HttpMethod.POST,
                    "/api/v1/clinic/patients?projectId=" + InboxFixture.PROJECT_A1,
                    """
                    {
                      "givenName": "Nadia",
                      "familyName": "Saleh",
                      "gender": "FEMALE",
                      "dateOfBirth": "1993-02-14",
                      "phone": "+966500009991",
                      "email": "nadia.saleh@example.test",
                      "cnic": "%s",
                      "password": "PortalPass123!",
                      "confirmPassword": "PortalPass123!",
                      "profileImageUrl": "data:image/png;base64,iVBORw0KGgo=",
                      "addressLine": "1 Main Street",
                      "country": "Saudi Arabia",
                      "state": "Riyadh",
                      "city": "Riyadh",
                      "postalCode": "11564",
                      "isActive": false
                    }
                    """.formatted(cnic),
                    HttpStatus.CREATED);

            UUID id = UUID.fromString(created.path("id").asText());
            assertThat(created.path("givenName").asText()).isEqualTo("Nadia");
            assertThat(created.path("familyName").asText()).isEqualTo("Saleh");
            assertThat(created.path("gender").asText()).isEqualTo("FEMALE");
            assertThat(created.path("cnic").asText()).isEqualTo(cnic);
            assertThat(created.path("primaryEmail").asText())
                    .isEqualTo("nadia.saleh@example.test");
            assertThat(created.path("isActive").asBoolean())
                    .as("the drawer's status switch is a real column")
                    .isFalse();
            assertThat(created.has("portalPasswordHash"))
                    .as("a bcrypt hash is never projected, even to the caller who set it")
                    .isFalse();
            assertThat(created.toString())
                    .as("the raw password does not travel back")
                    .doesNotContain("PortalPass123!");

            String hash = jdbc.queryForObject(
                    "select \"portalPasswordHash\" from \"patients\" where \"id\" = :id",
                    Map.of("id", id), String.class);
            assertThat(hash)
                    .as("the password is stored as a bcrypt hash")
                    .startsWith("$2")
                    .doesNotContain("PortalPass123!");
        }

        @Test
        @DisplayName("the required fields are enforced by the server, not only by the asterisk")
        void theServerEnforcesTheRequiredFields() {
            String admin = sessionFor(InboxFixture.USER_ADMIN_A);

            JsonNode response = write(admin, HttpMethod.POST,
                    "/api/v1/clinic/patients?projectId=" + InboxFixture.PROJECT_A1,
                    """
                    {
                      "givenName": "No",
                      "familyName": "Gender",
                      "dateOfBirth": "1990-01-01",
                      "phone": "+966500009992"
                    }
                    """,
                    HttpStatus.BAD_REQUEST);

            assertThat(response.path("code").asText()).isEqualTo("VALIDATION_FAILED");
            assertThat(response.path("details").path("field").asText())
                    .as("the refusal names the missing field rather than the whole form")
                    .isEqualTo("gender");
        }

        @Test
        @DisplayName("a password longer than seven characters must match its confirmation")
        void theConfirmationMustMatch() {
            String admin = sessionFor(InboxFixture.USER_ADMIN_A);

            JsonNode response = write(admin, HttpMethod.POST,
                    "/api/v1/clinic/patients?projectId=" + InboxFixture.PROJECT_A1,
                    """
                    {
                      "givenName": "Mismatch",
                      "familyName": "Password",
                      "gender": "MALE",
                      "dateOfBirth": "1990-01-01",
                      "phone": "+966500009993",
                      "password": "PortalPass123!",
                      "confirmPassword": "PortalPass124!"
                    }
                    """,
                    HttpStatus.BAD_REQUEST);

            assertThat(response.path("code").asText()).isEqualTo("VALIDATION_FAILED");
            assertThat(response.path("details").path("field").asText())
                    .isEqualTo("confirmPassword");
        }
    }

    @Nested
    @DisplayName("the appointment commerce route")
    class AppointmentCommerce {

        @Test
        @DisplayName("a registered patient and the service catalogue own the snapshots and price")
        void createResolvesThePatientDoctorAndPrice() {
            String admin = sessionFor(InboxFixture.USER_ADMIN_A);

            JsonNode created = write(admin, HttpMethod.POST,
                    "/api/v1/clinic/schedules/appointments?projectId="
                            + InboxFixture.PROJECT_A1,
                    """
                    {
                      "patientId": "%s",
                      "patientName": "Browser Forgery",
                      "doctorId": "%s",
                      "doctorName": "Browser Doctor",
                      "procedureId": "%s",
                      "service": "Browser Service",
                      "appointmentDate": "2026-08-03T11:00:00",
                      "quantity": 2,
                      "paymentStatus": "UNPAID",
                      "medicalReportName": "scan.pdf",
                      "medicalReportContentType": "application/pdf",
                      "medicalReportUrl": "data:application/pdf;base64,JVBERi0="
                    }
                    """.formatted(PATIENT_A, DOCTOR_A, PROCEDURE_A),
                    HttpStatus.CREATED);

            UUID id = UUID.fromString(created.path("id").asText());
            Map<String, Object> row = jdbc.queryForMap("""
                    select "patientName", "patientEmail", "doctorName", "service",
                           "price", "quantity", "paymentStatus", "durationMinutes",
                           "medicalReportName", "medicalReportContentType",
                           "medicalReportUrl"
                      from "appointments" where "id" = :id
                    """, Map.of("id", id));

            assertThat(row.get("patientName"))
                    .as("the patients row wins over the browser's patientName")
                    .isEqualTo("Layla Hassan");
            assertThat(row.get("patientEmail"))
                    .isEqualTo("patient." + PATIENT_A + "@example.test");
            assertThat(row.get("doctorName")).isEqualTo("Dr Test");
            assertThat(row.get("service")).isEqualTo("Consultation");
            assertThat(row.get("price")).isEqualTo(new java.math.BigDecimal("150.00"));
            assertThat(row.get("quantity")).isEqualTo(2);
            assertThat(row.get("durationMinutes")).isEqualTo(30);
            assertThat(row.get("paymentStatus")).isEqualTo("UNPAID");
            assertThat(row.get("medicalReportName")).isEqualTo("scan.pdf");
            assertThat(row.get("medicalReportContentType")).isEqualTo("application/pdf");
            assertThat(row.get("medicalReportUrl")).isEqualTo("data:application/pdf;base64,JVBERi0=");
        }

        @Test
        @DisplayName("the list projects the commercial total and the payment filter is real")
        void theListProjectsTheTotalAndFiltersPayment() {
            String admin = sessionFor(InboxFixture.USER_ADMIN_A);
            UUID id = createAppointment(admin);

            JsonNode page = get(admin,
                    "/api/v1/clinic/appointments?projectId=" + InboxFixture.PROJECT_A1
                            + "&from=2026-08-03T00:00:00&to=2026-08-03T23:59:59&q=Layla");

            JsonNode item = StreamSupport.stream(page.path("items").spliterator(), false)
                    .filter(node -> id.toString().equals(node.path("id").asText()))
                    .findFirst()
                    .orElseThrow();
            assertThat(item.path("patientName").asText()).isEqualTo("Layla Hassan");
            assertThat(item.path("patientEmail").asText()).isNotBlank();
            assertThat(item.path("doctorName").asText()).isEqualTo("Dr Test");
            assertThat(item.path("service").asText()).isEqualTo("Consultation");
            assertThat(item.path("quantity").asInt()).isEqualTo(2);
            assertThat(item.path("price").decimalValue())
                    .isEqualByComparingTo(new java.math.BigDecimal("150.00"));
            assertThat(item.path("totalAmount").decimalValue())
                    .as("the total is computed in SQL from price * quantity")
                    .isEqualByComparingTo(new java.math.BigDecimal("300.00"));
            assertThat(item.path("paymentStatus").asText()).isEqualTo("UNPAID");

            JsonNode paid = write(admin, HttpMethod.PATCH,
                    "/api/v1/clinic/schedules/appointments/" + id
                            + "/payment?projectId=" + InboxFixture.PROJECT_A1,
                    "{\"paymentStatus\":\"PAID\"}", HttpStatus.OK);
            assertThat(paid.path("updated").asBoolean()).isTrue();

            JsonNode paidPage = get(admin,
                    "/api/v1/clinic/appointments?projectId=" + InboxFixture.PROJECT_A1
                            + "&from=2026-08-03T00:00:00&to=2026-08-03T23:59:59"
                            + "&paymentStatus=PAID");
            assertThat(StreamSupport.stream(paidPage.path("items").spliterator(), false)
                    .map(node -> node.path("id").asText())
                    .toList())
                    .as("the paid filter returns the row only after its payment state changes")
                    .contains(id.toString());
        }

        @Test
        @DisplayName("an unknown payment state is refused rather than stored")
        void anUnknownPaymentStateIsRefused() {
            String admin = sessionFor(InboxFixture.USER_ADMIN_A);
            UUID id = createAppointment(admin);

            JsonNode response = write(admin, HttpMethod.PATCH,
                    "/api/v1/clinic/schedules/appointments/" + id
                            + "/payment?projectId=" + InboxFixture.PROJECT_A1,
                    "{\"paymentStatus\":\"SETTLED\"}", HttpStatus.BAD_REQUEST);

            assertThat(response.path("code").asText()).isEqualTo("VALIDATION_FAILED");
        }

        private UUID createAppointment(String admin) {
            JsonNode created = write(admin, HttpMethod.POST,
                    "/api/v1/clinic/schedules/appointments?projectId="
                            + InboxFixture.PROJECT_A1,
                    """
                    {
                      "patientId": "%s",
                      "doctorId": "%s",
                      "procedureId": "%s",
                      "appointmentDate": "2026-08-03T12:00:00",
                      "quantity": 2
                    }
                    """.formatted(PATIENT_A, DOCTOR_A, PROCEDURE_A),
                    HttpStatus.CREATED);
            return UUID.fromString(created.path("id").asText());
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private List<String> names(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false)
                .map(node -> node.path("displayName").asText())
                .toList();
    }

    private List<String> codes(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false)
                .map(node -> node.path("code").asText())
                .toList();
    }

    /**
     * A phone unique per TENANT, which is what DEC-016's index enforces.
     *
     * <p>Derived from the row id rather than the patient number: an earlier version hashed the
     * patient number and two of them collided, which surfaced as a duplicate-key failure in every
     * test after the first.
     */
    private static String phoneFor(UUID id) {
        return "+9665" + String.format("%08d", Math.abs(id.hashCode() % 100000000));
    }

    private void patient(UUID id, UUID org, UUID project, String patientNo, String name) {
        jdbc.update("""
                insert into "patients"
                    ("id", "organizationId", "projectId", "patientNo", "displayName",
                     "givenName", "familyName", "primaryPhone", "primaryEmail", "locale",
                     "createdAt", "updatedAt")
                values (:id, :org, :proj, :patientNo, :name, :given, :family, :phone, :email,
                        'ar', (current_timestamp at time zone 'utc'),
                        (current_timestamp at time zone 'utc'))
                """, new MapSqlParameterSource()
                .addValue("id", id).addValue("org", org).addValue("proj", project)
                .addValue("patientNo", patientNo).addValue("name", name)
                .addValue("given", name.split(" ")[0]).addValue("family", name.split(" ")[1])
                .addValue("phone", phoneFor(id))
                .addValue("email", "patient." + id + "@example.test"));
    }

    /** A priced service whose duration and buffer are the values the appointment inherits. */
    private void procedure(UUID id) {
        jdbc.update("""
                insert into "procedures"
                    ("id", "organizationId", "projectId", "name", "specialty",
                     "durationMinutes", "bufferMinutes", "price", "isActive",
                     "createdAt", "updatedAt")
                values (:id, :org, :proj, 'Consultation', 'General', 30, 5, 150.00, true,
                        (current_timestamp at time zone 'utc'),
                        (current_timestamp at time zone 'utc'))
                """, new MapSqlParameterSource()
                .addValue("id", id).addValue("org", InboxFixture.ORG_A)
                .addValue("proj", InboxFixture.PROJECT_A1));
    }

    private void doctor(UUID id, UUID org, UUID project) {
        jdbc.update("""
                insert into "doctors"
                    ("id", "organizationId", "projectId", "name", "specialty",
                     "workingDays", "startHour", "endHour", "slotDuration", "isActive",
                     "createdAt", "updatedAt")
                values (:id, :org, :proj, 'Dr Test', 'General',
                        ARRAY[1,2,3,4,5]::integer[], '09:00', '17:00', 30, true,
                        (current_timestamp at time zone 'utc'),
                        (current_timestamp at time zone 'utc'))
                """, new MapSqlParameterSource().addValue("id", id)
                .addValue("org", org).addValue("proj", project));
    }

    private void schedule(UUID doctorId) {
        jdbc.update("""
                insert into "clinic_schedules"
                    ("id", "organizationId", "projectId", "doctorId", "weekday",
                     "startMinute", "endMinute", "timezone", "effectiveFrom", "isActive", "createdAt")
                values (gen_random_uuid(), :org, :proj, :doctor, 1,
                        540, 1020, 'Asia/Amman', DATE '2026-01-01', true,
                        (current_timestamp at time zone 'utc'))
                """, new MapSqlParameterSource()
                .addValue("org", InboxFixture.ORG_A).addValue("proj", InboxFixture.PROJECT_A1)
                .addValue("doctor", doctorId));
    }

    private void seedFinding() {
        jdbc.update("""
                insert into "clinic_finding_vocabulary"
                    ("id", "organizationId", "projectId", "code", "category", "labelEn",
                     "isClinicianReviewed", "isActive", "createdAt")
                values (gen_random_uuid(), :org, :proj, 'CARIES-ICDAS-3', 'CARIES',
                        'Caries ICDAS 3', false, true,
                        (current_timestamp at time zone 'utc'))
                """, new MapSqlParameterSource()
                .addValue("org", InboxFixture.ORG_A).addValue("proj", InboxFixture.PROJECT_A1));
        jdbc.update("""
                insert into "patient_odontogram_findings"
                    ("id", "organizationId", "projectId", "patientId", "toothFdi", "scope",
                     "code", "state", "source", "recordedAt", "createdAt")
                values (gen_random_uuid(), :org, :proj, :patient, 16, 'TOOTH',
                        'CARIES-ICDAS-3', 'EXISTING', 'CLINICAL',
                        (current_timestamp at time zone 'utc'),
                        (current_timestamp at time zone 'utc'))
                """, new MapSqlParameterSource()
                .addValue("org", InboxFixture.ORG_A).addValue("proj", InboxFixture.PROJECT_A1)
                .addValue("patient", PATIENT_A));
    }

    private JsonNode get(String cookie, String path) {
        ResponseEntity<JsonNode> response = exchange(cookie, path);
        assertThat(response.getStatusCode())
                .as("GET %s returned %s: %s", path, response.getStatusCode(), response.getBody())
                .isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private ResponseEntity<JsonNode> exchange(String cookie, String path) {
        return http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers(cookie)), JsonNode.class);
    }

    private JsonNode write(String cookie, HttpMethod method, String path, String body,
                           HttpStatus expected) {
        ResponseEntity<JsonNode> response = http.exchange(path, method,
                new HttpEntity<>(body, writeHeaders(cookie)), JsonNode.class);
        assertThat(response.getStatusCode())
                .as("%s %s returned %s: %s", method, path, response.getStatusCode(),
                        response.getBody())
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
        headers.set(csrf.headerName, csrf.token);
        headers.set(HttpHeaders.COOKIE,
                cookie == null ? csrf.cookie : cookie + "; " + csrf.cookie);
        return headers;
    }

    private Csrf csrfToken() {
        ResponseEntity<JsonNode> response = http.exchange("/api/v1/auth/csrf",
                HttpMethod.GET, new HttpEntity<>(headers(null)), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String setCookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).as("the CSRF endpoint sets its cookie").isNotBlank();
        return new Csrf(
                setCookie.split(";", 2)[0],
                response.getBody().get("headerName").asText(),
                response.getBody().get("token").asText());
    }

    /** A real revocable session; the raw token never leaves this method. */
    private String sessionFor(UUID userId) {
        BrowserSessionStore.IssuedSession issued =
                sessions.issue(userId, "clinic-patient-http-test", "127.0.0.1", 1);
        assertThat(issued).as("a session was issued for user %s", userId).isNotNull();
        return securityProperties.getSessionCookieName() + "=" + issued.rawToken();
    }

    private record Csrf(String cookie, String headerName, String token) {
    }
}