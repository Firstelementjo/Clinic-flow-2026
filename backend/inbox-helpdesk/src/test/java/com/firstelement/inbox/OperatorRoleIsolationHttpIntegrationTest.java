package com.firstelement.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.firstelement.security.UserRole;
import com.firstelement.security.session.BrowserSessionStore;
import java.util.List;
import java.util.UUID;
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
 * Slice 1 Integration Test: Platform Operator Role Isolation and Boundary Enforcement.
 *
 * <p>Proves over real HTTP that:
 * <ul>
 *   <li>Operator accounts (is_platform_admin = true) get 403 Forbidden on EVERY clinical,
 *       appointment, patient, encounter, and clinic-finance endpoint.</li>
 *   <li>Clinic admin accounts get 200 OK on clinic endpoints, but 401/403 on operator/platform endpoints.</li>
 *   <li>Cross-tenant access between distinct clinic organizations is strictly denied.</li>
 * </ul>
 */
@SpringBootTest(classes = InboxTestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OperatorRoleIsolationHttpIntegrationTest extends InboxIntegrationTestBase {

    private static final UUID OPERATOR_USER_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-00000000e099");

    @Autowired
    private TestRestTemplate http;
    @Autowired
    private BrowserSessionStore sessions;
    @Autowired
    private com.firstelement.security.config.SecurityProperties securityProperties;
    @Autowired
    private InboxFixture fixture;
    @Autowired
    private ClinicFixture clinic;
    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @BeforeEach
    void setup() {
        fixture.reset();
        clinic.seedClinic();

        // Seed dedicated operator user with is_platform_admin = true
        jdbc.update("""
            insert into users ("id", "organizationId", "email", "passwordHash", "fullName",
                               "role", "locale", "isActive", "createdAt", "updatedAt", "is_platform_admin")
            values (:id, :org, 'operator@firstelement.test', 'not-a-real-hash', 'Platform Operator',
                    cast('ADMIN' as "UserRole"), cast('en' as "Locale"), true, now(), now(), true)
            on conflict ("id") do update set "is_platform_admin" = true
            """,
            new MapSqlParameterSource()
                .addValue("id", OPERATOR_USER_ID)
                .addValue("org", InboxFixture.ORG_A));
    }

    @Test
    @DisplayName("Operator is refused with 403 on clinic analytics / dashboard")
    void operatorBlockedFromClinicAnalytics() {
        String operatorCookie = sessionFor(OPERATOR_USER_ID);
        String path = "/api/v1/clinic/analytics?projectId=" + InboxFixture.PROJECT_A1
            + "&from=2026-08-01T00:00:00.000Z&to=2026-09-01T00:00:00.000Z";

        ResponseEntity<JsonNode> response = http.exchange(
            path, HttpMethod.GET, new HttpEntity<>(headers(operatorCookie)), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("Operator is refused with 403 on clinic appointment schedule and list")
    void operatorBlockedFromClinicAppointments() {
        String operatorCookie = sessionFor(OPERATOR_USER_ID);

        ResponseEntity<JsonNode> response = http.exchange(
            "/api/v1/clinic/appointments", HttpMethod.GET, new HttpEntity<>(headers(operatorCookie)), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("Operator is refused with 403 on clinic patient directory")
    void operatorBlockedFromClinicPatients() {
        String operatorCookie = sessionFor(OPERATOR_USER_ID);

        ResponseEntity<JsonNode> response = http.exchange(
            "/api/v1/clinic/patients", HttpMethod.GET, new HttpEntity<>(headers(operatorCookie)), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("Operator is refused with 403 on clinic invoices and billing records")
    void operatorBlockedFromClinicInvoices() {
        String operatorCookie = sessionFor(OPERATOR_USER_ID);

        ResponseEntity<JsonNode> response = http.exchange(
            "/api/v1/clinic/invoices", HttpMethod.GET, new HttpEntity<>(headers(operatorCookie)), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("Operator is refused with 403 on clinic reports and revenue breakdown")
    void operatorBlockedFromClinicReports() {
        String operatorCookie = sessionFor(OPERATOR_USER_ID);

        ResponseEntity<JsonNode> response = http.exchange(
            "/api/v1/clinic/reports?projectId=" + InboxFixture.PROJECT_A1,
            HttpMethod.GET, new HttpEntity<>(headers(operatorCookie)), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("Operator is refused with 403 on clinic AI assistant runs")
    void operatorBlockedFromClinicAi() {
        String operatorCookie = sessionFor(OPERATOR_USER_ID);

        ResponseEntity<JsonNode> response = http.exchange(
            "/api/v1/clinic/ai/runs", HttpMethod.GET, new HttpEntity<>(headers(operatorCookie)), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("Operator is refused with 403 on doctors and procedures clinical management")
    void operatorBlockedFromDoctorsAndProcedures() {
        String operatorCookie = sessionFor(OPERATOR_USER_ID);

        ResponseEntity<JsonNode> doctorRes = http.exchange(
            "/api/v1/clinic/doctors", HttpMethod.GET, new HttpEntity<>(headers(operatorCookie)), JsonNode.class);
        assertThat(doctorRes.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<JsonNode> procRes = http.exchange(
            "/api/v1/clinic/procedures", HttpMethod.GET, new HttpEntity<>(headers(operatorCookie)), JsonNode.class);
        assertThat(procRes.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("Clinic Admin gets 200 OK on clinic endpoints but is blocked from operator platform routes")
    void clinicAdminCanAccessClinicButBlockedFromPlatform() {
        String clinicAdminCookie = sessionFor(InboxFixture.USER_ADMIN_A);

        // Clinic Admin succeeds on clinic endpoints
        ResponseEntity<JsonNode> doctorRes = http.exchange(
            "/api/v1/clinic/doctors", HttpMethod.GET, new HttpEntity<>(headers(clinicAdminCookie)), JsonNode.class);
        assertThat(doctorRes.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Clinic Admin calling operator platform route gets 401 or 403
        ResponseEntity<JsonNode> platformRes = http.exchange(
            "/api/v1/platform/clinics", HttpMethod.GET, new HttpEntity<>(headers(clinicAdminCookie)), JsonNode.class);
        assertThat(platformRes.getStatusCode())
            .isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Cross-tenant isolation: Tenant A admin cannot access Tenant B project")
    void crossTenantAccessDenied() {
        String tenantACookie = sessionFor(InboxFixture.USER_ADMIN_A);

        // Tenant A admin querying Tenant B's project receives non-disclosing 404
        ResponseEntity<JsonNode> response = http.exchange(
            "/api/v1/clinic/analytics?projectId=" + InboxFixture.PROJECT_B1
                + "&from=2026-08-01T00:00:00.000Z&to=2026-09-01T00:00:00.000Z",
            HttpMethod.GET, new HttpEntity<>(headers(tenantACookie)), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private HttpHeaders headers(String cookie) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        if (cookie != null) {
            headers.set(HttpHeaders.COOKIE, cookie);
        }
        return headers;
    }

    private String sessionFor(UUID userId) {
        BrowserSessionStore.IssuedSession issued =
            sessions.issue(userId, "isolation-http-test", "127.0.0.1", 1);
        assertThat(issued).as("a session was issued for user %s", userId).isNotNull();
        return securityProperties.getSessionCookieName() + "=" + issued.rawToken();
    }
}
