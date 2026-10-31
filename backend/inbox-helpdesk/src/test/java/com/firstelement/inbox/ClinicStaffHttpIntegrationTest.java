package com.firstelement.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.firstelement.security.auth.PasswordVerifier;
import com.firstelement.security.config.SecurityProperties;
import com.firstelement.security.session.BrowserSessionStore;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
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
 * Doctor-profile and front-desk administration over real HTTP and PostgreSQL.
 *
 * <h2>What this suite is allowed to claim</h2>
 *
 * <p>The service tests in {@code :backend:crm-core} can prove validation and the PATCH
 * merge. They cannot prove that an employee is refused by the method-security layer, that
 * a front-desk account can actually obtain a session with the role the create route
 * assigned, or that a soft delete keeps the row while removing it from the list. Those
 * are properties of the whole chain, so they are asserted here.
 *
 * <h2>The two identities</h2>
 *
 * <p>An ADMIN reaches the management reads and writes. The seeded EMPLOYEE reaches the
 * roster but not the management view, which is Phase 33's split observed rather than
 * assumed. A newly created front-desk officer is a CLINIC_STAFF account: it can read the
 * roster and cannot read the front-desk directory.
 */
@SpringBootTest(classes = InboxTestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ClinicStaffHttpIntegrationTest extends InboxIntegrationTestBase {

    @Autowired
    private TestRestTemplate http;
    @Autowired
    private BrowserSessionStore sessions;
    @Autowired
    private SecurityProperties securityProperties;
    @Autowired
    private InboxFixture fixture;
    @Autowired
    private ClinicFixture clinic;
    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    private UUID clinicId;
    private UUID secondClinicId;
    private String adminCookie;
    private Csrf csrf;

    @BeforeEach
    void reset() {
        fixture.reset();
        clinic.seedClinic();
        clinicId = insertClinic("Riyadh Main");
        secondClinicId = insertClinic("Riyadh North");
        adminCookie = sessionFor(InboxFixture.USER_ADMIN_A);
        csrf = csrfToken();
    }

    /**
     * Remove the rows this suite can leave beyond the shared fixture.
     *
     * <p>Doctors go first because {@code doctors.clinicId} is RESTRICT; qualifications
     * cascade with the doctor. Clinic locations go next, before the shared reset deletes
     * projects (that FK is RESTRICT too). A remaining account assignment is cleared by the
     * location's composite {@code ON DELETE SET NULL ("frontDeskOfficerId")}.
     */
    @AfterEach
    void cleanup() {
        jdbc.update("delete from doctors", new MapSqlParameterSource());
        jdbc.update("delete from clinic_locations", new MapSqlParameterSource());
    }

    // ==================================================================
    // doctor profiles
    // ==================================================================

    @Nested
    @DisplayName("doctor profiles")
    class DoctorProfiles {

        @Test
        @DisplayName("the management list publishes contact details; the roster still does not")
        void theManagementReadPublishesWhatTheRosterWithholds() {
            JsonNode management = get(adminCookie,
                "/api/v1/clinic/doctor-profiles?projectId=" + InboxFixture.PROJECT_A1);
            assertThat(management.get("count").asInt())
                .as("PROJECT_A1 has two seeded doctors; the management read is project-scoped")
                .isEqualTo(2);
            assertThat(management.toString())
                .as("the marker is in the table, so this is a projection assertion")
                .contains("@clinic.test")
                .contains("phone");

            JsonNode roster = get(adminCookie, "/api/v1/clinic/doctors");
            assertThat(roster.toString())
                .as("the existing roster contract must not start disclosing the management fields")
                .doesNotContain("@clinic.test")
                .doesNotContain("\"email\"")
                .doesNotContain("\"phone\"");
        }

        @Test
        @DisplayName("an employee is refused the management read and the write, not merely shown nothing")
        void anEmployeeIsRefusedTheManagementRead() {
            String employee = sessionFor(InboxFixture.USER_STAFF_A);
            assertThat(status(employee, HttpMethod.GET,
                "/api/v1/clinic/doctor-profiles?projectId=" + InboxFixture.PROJECT_A1, null))
                .as("EMPLOYEE holds doctor:read, not doctor:read-management")
                .isEqualTo(403);
            assertThat(write(employee, HttpMethod.POST,
                "/api/v1/clinic/doctor-profiles?projectId=" + InboxFixture.PROJECT_A1,
                "{\"firstName\":\"No\",\"lastName\":\"Access\",\"email\":\"no@access.test\"}",
                HttpStatus.FORBIDDEN))
                .as("the write is doctor:manage, a second independent grant")
                .isNotNull();
        }

        @Test
        @DisplayName("create, status-only patch, read detail, list and soft delete")
        void doctorLifecycleIsAPartialUpdateAndASoftDelete() {
            String createBody = """
                {
                  "firstName": "Lina",
                  "lastName": "Hassan",
                  "email": "lina.hassan@clinic.test",
                  "phone": "+966500000100",
                  "gender": "FEMALE",
                  "expert": "Endodontics",
                  "commission": 12.5,
                  "clinicId": "%s",
                  "serviceId": "%s",
                  "experienceYears": 9,
                  "country": "Saudi Arabia",
                  "city": "Riyadh",
                  "qualifications": [
                    {"degree": "BDS", "university": "King Saud University", "graduationYear": 2012}
                  ]
                }
                """.formatted(clinicId, ClinicFixture.PROCEDURE_A1);

            JsonNode created = write(adminCookie, HttpMethod.POST,
                "/api/v1/clinic/doctor-profiles?projectId=" + InboxFixture.PROJECT_A1,
                createBody, HttpStatus.CREATED);
            String doctorId = created.get("id").asText();
            assertThat(created.get("name").asText()).isEqualTo("Lina Hassan");
            assertThat(created.get("specialty").asText()).isEqualTo("Endodontics");
            assertThat(created.get("clinicName").asText()).isEqualTo("Riyadh Main");
            assertThat(created.get("serviceName").asText()).isEqualTo("Consultation");
            assertThat(created.get("qualifications")).hasSize(1);

            JsonNode patched = write(adminCookie, HttpMethod.PATCH,
                "/api/v1/clinic/doctor-profiles/" + doctorId
                    + "?projectId=" + InboxFixture.PROJECT_A1,
                "{\"isActive\":false}", HttpStatus.OK);
            assertThat(patched.get("isActive").asBoolean()).isFalse();
            assertThat(patched.get("firstName").asText())
                .as("a status-only PATCH must not erase the rest of the profile")
                .isEqualTo("Lina");
            assertThat(patched.get("qualifications")).hasSize(1);

            JsonNode detail = get(adminCookie,
                "/api/v1/clinic/doctor-profiles/" + doctorId
                    + "?projectId=" + InboxFixture.PROJECT_A1);
            assertThat(detail.get("qualifications").get(0).get("degree").asText())
                .isEqualTo("BDS");

            JsonNode activeList = get(adminCookie,
                "/api/v1/clinic/doctor-profiles?projectId=" + InboxFixture.PROJECT_A1
                    + "&status=INACTIVE");
            assertThat(activeList.get("count").asInt()).isEqualTo(1);
            assertThat(activeList.get("items").get(0).get("id").asText()).isEqualTo(doctorId);

            delete(adminCookie, "/api/v1/clinic/doctor-profiles/" + doctorId
                + "?projectId=" + InboxFixture.PROJECT_A1, HttpStatus.NO_CONTENT);

            JsonNode list = get(adminCookie,
                "/api/v1/clinic/doctor-profiles?projectId=" + InboxFixture.PROJECT_A1);
            assertThat(list.get("count").asInt())
                .as("the soft-deleted row disappears from the management list")
                .isEqualTo(2);
            assertThat(status(adminCookie, HttpMethod.GET,
                "/api/v1/clinic/doctor-profiles/" + doctorId
                    + "?projectId=" + InboxFixture.PROJECT_A1, null))
                .isEqualTo(404);

            Boolean deleted = jdbc.queryForObject(
                "select \"deletedAt\" is not null from doctors where id = :id",
                Map.of("id", UUID.fromString(doctorId)), Boolean.class);
            assertThat(deleted)
                .as("soft delete keeps the row for clinical history")
                .isTrue();
        }

        @Test
        @DisplayName("a doctor in another tenant is a 404, identically to a nonexistent id")
        void aCrossTenantDoctorIsNotFound() {
            String adminB = sessionFor(InboxFixture.USER_ADMIN_B);
            assertThat(status(adminB, HttpMethod.GET,
                "/api/v1/clinic/doctor-profiles/" + ClinicFixture.DOCTOR_A1, null))
                .isEqualTo(404);
            assertThat(status(adminB, HttpMethod.GET,
                "/api/v1/clinic/doctor-profiles?projectId=" + InboxFixture.PROJECT_A1, null))
                .as("naming another tenant's project is refused by the scope, not filtered")
                .isEqualTo(404);
        }
    }

    // ==================================================================
    // front desk
    // ==================================================================

    @Nested
    @DisplayName("front desk")
    class FrontDesk {

        @Test
        @DisplayName("create hashes the password, issues a CLINIC_STAFF session and assigns one clinic")
        void createProvisionsARealAccount() {
            String email = "nora.saleh." + UUID.randomUUID() + "@clinic.test";
            JsonNode created = write(adminCookie, HttpMethod.POST,
                "/api/v1/clinic/front-desk?projectId=" + InboxFixture.PROJECT_A1,
                """
                    {
                      "firstName": "Nora",
                      "lastName": "Saleh",
                      "email": "%s",
                      "gender": "FEMALE",
                      "phone": "+966500000200",
                      "password": "Password123!",
                      "dateOfBirth": "1995-04-12",
                      "clinicId": "%s",
                      "verificationStatus": "VERIFIED",
                      "isActive": true
                    }
                    """.formatted(email, clinicId),
                HttpStatus.CREATED);

            UUID userId = UUID.fromString(created.get("userId").asText());
            assertThat(created.get("clinicId").asText()).isEqualTo(clinicId.toString());
            assertThat(created.toString())
                .as("no read path may serialize the hash")
                .doesNotContain("passwordHash")
                .doesNotContain("Password123!");

            String hash = jdbc.queryForObject(
                "select \"passwordHash\" from users where id = :id",
                Map.of("id", userId), String.class);
            assertThat(hash).startsWith("$2");
            assertThat(new PasswordVerifier().matches("Password123!", hash))
                .as("the stored value is a bcrypt hash of the submitted password")
                .isTrue();

            Integer memberships = jdbc.queryForObject("""
                    select count(*) from project_memberships
                     where "userId" = :id and "projectId" = :project
                    """,
                Map.of("id", userId, "project", InboxFixture.PROJECT_A1), Integer.class);
            assertThat(memberships).isEqualTo(1);

            String officer = sessionFor(userId);
            assertThat(status(officer, HttpMethod.GET, "/api/v1/clinic/doctors", null))
                .as("the created role is CLINIC_STAFF, which holds the roster read")
                .isEqualTo(200);
            assertThat(status(officer, HttpMethod.GET,
                "/api/v1/clinic/front-desk?projectId=" + InboxFixture.PROJECT_A1, null))
                .as("CLINIC_STAFF does not hold front-desk:read")
                .isEqualTo(403);
        }

        @Test
        @DisplayName("status-only patch, clinic reassignment, duplicate-email refusal and delete")
        void updateAndDeleteKeepTheAccountAuditable() {
            String email = "omar.front." + UUID.randomUUID() + "@clinic.test";
            JsonNode created = write(adminCookie, HttpMethod.POST,
                "/api/v1/clinic/front-desk?projectId=" + InboxFixture.PROJECT_A1,
                """
                    {
                      "firstName": "Omar",
                      "lastName": "Front",
                      "email": "%s",
                      "gender": "MALE",
                      "phone": "+966500000300",
                      "password": "Password123!",
                      "clinicId": "%s"
                    }
                    """.formatted(email, clinicId),
                HttpStatus.CREATED);
            UUID userId = UUID.fromString(created.get("userId").asText());

            JsonNode patched = write(adminCookie, HttpMethod.PATCH,
                "/api/v1/clinic/front-desk/" + userId
                    + "?projectId=" + InboxFixture.PROJECT_A1,
                "{\"isActive\":false}", HttpStatus.OK);
            assertThat(patched.get("isActive").asBoolean()).isFalse();
            assertThat(patched.get("firstName").asText())
                .as("a status-only PATCH keeps the profile")
                .isEqualTo("Omar");

            JsonNode reassigned = write(adminCookie, HttpMethod.PATCH,
                "/api/v1/clinic/front-desk/" + userId
                    + "?projectId=" + InboxFixture.PROJECT_A1,
                "{\"clinicId\":\"" + secondClinicId + "\"}", HttpStatus.OK);
            assertThat(reassigned.get("clinicId").asText())
                .isEqualTo(secondClinicId.toString());
            UUID firstAssignment = jdbc.queryForObject(
                "select \"frontDeskOfficerId\" from clinic_locations where id = :id",
                Map.of("id", clinicId), UUID.class);
            UUID secondAssignment = jdbc.queryForObject(
                "select \"frontDeskOfficerId\" from clinic_locations where id = :id",
                Map.of("id", secondClinicId), UUID.class);
            assertThat(firstAssignment).isNull();
            assertThat(secondAssignment).isEqualTo(userId);

            assertThat(write(adminCookie, HttpMethod.POST,
                "/api/v1/clinic/front-desk?projectId=" + InboxFixture.PROJECT_A1,
                """
                    {
                      "firstName": "Duplicate",
                      "lastName": "Email",
                      "email": "%s",
                      "password": "Password123!",
                      "clinicId": "%s"
                    }
                    """.formatted(email, clinicId),
                HttpStatus.BAD_REQUEST))
                .as("a duplicate email is a validation failure, not a 500")
                .isNotNull();

            delete(adminCookie, "/api/v1/clinic/front-desk/" + userId
                + "?projectId=" + InboxFixture.PROJECT_A1, HttpStatus.NO_CONTENT);

            assertThat(status(adminCookie, HttpMethod.GET,
                "/api/v1/clinic/front-desk/" + userId
                    + "?projectId=" + InboxFixture.PROJECT_A1, null))
                .isEqualTo(404);
            Integer memberships = jdbc.queryForObject("""
                    select count(*) from project_memberships where "userId" = :id
                    """,
                Map.of("id", userId), Integer.class);
            assertThat(memberships).isZero();
            Boolean active = jdbc.queryForObject(
                "select \"isActive\" from users where id = :id",
                Map.of("id", userId), Boolean.class);
            assertThat(active)
                .as("with no memberships left the account is deactivated, not destroyed")
                .isFalse();
            UUID assignment = jdbc.queryForObject(
                "select \"frontDeskOfficerId\" from clinic_locations where id = :id",
                Map.of("id", secondClinicId), UUID.class);
            assertThat(assignment).isNull();
        }
    }

    // ==================================================================
    // fixtures and HTTP helpers
    // ==================================================================

    private UUID insertClinic(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into clinic_locations
                    ("id", "organizationId", "projectId", "name", "city", "slotMinutes",
                     "isActive", "createdAt", "updatedAt")
                values (:id, :org, :project, :name, :city, 30, true, :now, :now)
                """,
            new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("org", InboxFixture.ORG_A)
                .addValue("project", InboxFixture.PROJECT_A1)
                .addValue("name", name)
                .addValue("city", "Riyadh")
                .addValue("now", java.time.LocalDateTime.now()));
        return id;
    }

    private JsonNode get(String cookie, String path) {
        ResponseEntity<JsonNode> response = http.exchange(path, HttpMethod.GET,
            new HttpEntity<>(headers(cookie)), JsonNode.class);
        assertThat(response.getStatusCode())
            .as("GET %s returned %s: %s", path, response.getStatusCode(), response.getBody())
            .isEqualTo(HttpStatus.OK);
        return response.getBody();
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

    private void delete(String cookie, String path, HttpStatus expected) {
        ResponseEntity<String> response = http.exchange(path, HttpMethod.DELETE,
            new HttpEntity<>(writeHeaders(cookie)), String.class);
        assertThat(response.getStatusCode())
            .as("DELETE %s returned %s", path, response.getStatusCode())
            .isEqualTo(expected);
    }

    private int status(String cookie, HttpMethod method, String path, String body) {
        return http.exchange(path, method, new HttpEntity<>(body, headers(cookie)),
            String.class).getStatusCode().value();
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
        String cookies = cookie == null ? csrf.cookie : cookie + "; " + csrf.cookie;
        headers.set(HttpHeaders.COOKIE, cookies);
        return headers;
    }

    /** A real revocable session for a seeded user; the raw token never leaves this method. */
    private String sessionFor(UUID userId) {
        BrowserSessionStore.IssuedSession issued =
            sessions.issue(userId, "clinic-staff-http-test", "127.0.0.1", 1);
        assertThat(issued).as("a session was issued for user %s", userId).isNotNull();
        return securityProperties.getSessionCookieName() + "=" + issued.rawToken();
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

    private record Csrf(String cookie, String headerName, String token) {
    }
}
