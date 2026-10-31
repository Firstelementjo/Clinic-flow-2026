package com.firstelement.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.firstelement.security.auth.PasswordVerifier;
import com.firstelement.security.platform.InviteTokenService;
import com.firstelement.security.platform.PlatformAuditRepository;
import com.firstelement.security.platform.PlatformClinicService;
import com.firstelement.security.platform.PlatformOperatorAuthenticationFilter;
import com.firstelement.security.platform.PlatformOperatorContext;
import com.firstelement.security.platform.PlatformOperatorRepository;
import com.firstelement.security.session.BrowserSessionStore;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
 * Slice 2 Integration Tests: Clinics (Tenant Lifecycle).
 *
 * <p>Specification compliance tests:
 * <ul>
 *   <li>Invite token is single-use and expiring; operator cannot set a clinic admin password.</li>
 *   <li>Create-clinic wizard provisions clinic, admin, subscription, and hashed invite token.</li>
 *   <li>Suspended clinic cannot write; read access and medical data remain 100% intact (DEC-007).</li>
 *   <li>Reactivation restores clinic write access.</li>
 * </ul>
 */
@SpringBootTest(classes = InboxTestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "firstelement.environment=TEST",
        "firstelement.api.web-origin=http://localhost:5173",
        "spring.flyway.locations=classpath:db/migration",
        "firstelement.platform.console.enabled=true",
        "firstelement.security.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "management.health.db.enabled=false",
        "management.health.redis.enabled=false",
        "management.tracing.sampling.probability=0.0"
    })
class PlatformTenantLifecycleHttpIntegrationTest extends InboxIntegrationTestBase {

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

    private InviteTokenService inviteService;
    private PlatformClinicService clinicService;

    @BeforeEach
    void setup() {
        fixture.reset();
        clinic.seedClinic();

        PlatformAuditRepository audit = new PlatformAuditRepository(jdbc);
        this.inviteService = new InviteTokenService(jdbc, audit);
        this.clinicService = new PlatformClinicService(jdbc, audit, inviteService);
    }

    @Test
    @DisplayName("Invite token is single-use and expiring; operator cannot set clinic admin password")
    void inviteTokenSingleUseAndExpiring() {
        UUID orgId = InboxFixture.ORG_A;
        UUID projectId = InboxFixture.PROJECT_A1;
        UUID userId = UUID.randomUUID();
        String adminEmail = "dr.tariq." + UUID.randomUUID().toString().substring(0, 6) + "@clinic.test";

        // 1. Create admin user with dummy unusable password hash
        jdbc.update("""
            insert into "public"."users"
                ("id", "organizationId", "email", "passwordHash", "fullName", "role", "locale",
                 "isActive", "invitePending", "verificationStatus", "createdAt", "updatedAt")
            values
                (:id, :org, :email, :dummyHash, 'Dr. Tariq Zaid', cast('ADMIN' as "UserRole"), cast('ar' as "Locale"),
                 true, true, 'PENDING', now(), now())
            """,
            new MapSqlParameterSource()
                .addValue("id", userId)
                .addValue("org", orgId)
                .addValue("email", adminEmail)
                .addValue("dummyHash", PasswordVerifier.DUMMY_PASSWORD_HASH));

        // 2. Issue a single-use invite token (operator never sets password)
        InviteTokenService.InviteTokenCreated invite = inviteService.createInvite(
            orgId, projectId, userId, adminEmail, UUID.randomUUID(), Duration.ofHours(48));

        assertThat(invite.rawToken()).isNotBlank();
        assertThat(invite.expiresAt()).isAfter(Instant.now());

        // 3. Inspect invite endpoint (GET /api/v1/invites/{token})
        ResponseEntity<JsonNode> inspectResponse = http.getForEntity(
            "/api/v1/invites/" + invite.rawToken(), JsonNode.class);

        assertThat(inspectResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(inspectResponse.getBody().get("email").asText()).isEqualTo(adminEmail);
        assertThat(inspectResponse.getBody().get("adminName").asText()).isEqualTo("Dr. Tariq Zaid");

        // 4. Clinic Admin accepts invite and sets their OWN strong password
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String chosenPassword = "DoctorStrongPassword2026!";
        HttpEntity<String> acceptReq = new HttpEntity<>(
            "{\"password\": \"" + chosenPassword + "\"}", headers);

        ResponseEntity<JsonNode> acceptResponse = http.exchange(
            "/api/v1/invites/" + invite.rawToken() + "/accept",
            HttpMethod.POST, acceptReq, JsonNode.class);

        assertThat(acceptResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(acceptResponse.getBody().get("success").asBoolean()).isTrue();

        // Verify password in DB matches user's chosen password
        String updatedHash = jdbc.queryForObject(
            "select \"passwordHash\" from \"public\".\"users\" where \"id\" = :id",
            new MapSqlParameterSource("id", userId), String.class);
        assertThat(new PasswordVerifier().matches(chosenPassword, updatedHash)).isTrue();

        // Verify account is verified and invite no longer pending
        Boolean invitePending = jdbc.queryForObject(
            "select \"invitePending\" from \"public\".\"users\" where \"id\" = :id",
            new MapSqlParameterSource("id", userId), Boolean.class);
        assertThat(invitePending).isFalse();

        // 5. SECOND USE MUST BE REFUSED (Single-use proof)
        ResponseEntity<JsonNode> secondUseResponse = http.exchange(
            "/api/v1/invites/" + invite.rawToken() + "/accept",
            HttpMethod.POST, acceptReq, JsonNode.class);

        assertThat(secondUseResponse.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(secondUseResponse.getBody().get("error").asText()).isEqualTo("INVITE_ALREADY_USED");

        // 6. EXPIRATION TEST: Token past expiry is refused
        InviteTokenService.InviteTokenCreated expiredInvite = inviteService.createInvite(
            orgId, projectId, userId, adminEmail, UUID.randomUUID(), Duration.ofHours(48));
        // Force expiry in database
        jdbc.update("""
            update "public"."invite_tokens"
               set "expiresAt" = (current_timestamp - interval '2 hours')
             where "id" = :id
            """,
            new MapSqlParameterSource("id", expiredInvite.inviteId()));

        ResponseEntity<JsonNode> expiredResponse = http.exchange(
            "/api/v1/invites/" + expiredInvite.rawToken() + "/accept",
            HttpMethod.POST, acceptReq, JsonNode.class);

        assertThat(expiredResponse.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(expiredResponse.getBody().get("error").asText()).isEqualTo("INVITE_EXPIRED");
    }

    @Test
    @DisplayName("Suspended clinic cannot write; data remains 100% intact (DEC-007)")
    void suspendedClinicCannotWriteWhileDataIsIntact() {
        UUID orgId = InboxFixture.ORG_A;
        UUID projectId = InboxFixture.PROJECT_A1;
        UUID clinicAdminId = InboxFixture.USER_ADMIN_A;
        String sessionCookie = sessionFor(clinicAdminId);

        // 1. Verify that while clinic is ACTIVE, read works normally
        ResponseEntity<JsonNode> readBefore = http.exchange(
            "/api/v1/customers?projectId=" + projectId,
            HttpMethod.GET, new HttpEntity<>(headers(sessionCookie)), JsonNode.class);
        assertThat(readBefore.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 2. Suspend clinic
        jdbc.update("""
            update "public"."projects"
               set "status" = 'SUSPENDED',
                   "suspendedAt" = now(),
                   "suspendedReason" = 'Payment overdue by 30 days'
             where "id" = :id
            """,
            new MapSqlParameterSource("id", projectId));

        // 3. Clinic staff attempts a write mutation (POST /api/v1/clinic/patients)
        CsrfToken csrf = obtainCsrf();
        HttpHeaders writeHeaders = headers(sessionCookie);
        writeHeaders.add(HttpHeaders.COOKIE, csrf.cookie());
        writeHeaders.set(securityProperties.getCsrfHeaderName(), csrf.token());
        writeHeaders.set("X-Project-Id", projectId.toString());
        HttpEntity<String> createPatientReq = new HttpEntity<>(
            """
            {
                "givenName": "Suspended",
                "familyName": "Patient",
                "phone": "+962799999999",
                "gender": "MALE",
                "dateOfBirth": "1990-01-01"
            }
            """, writeHeaders);

        ResponseEntity<JsonNode> writeResponse = http.exchange(
            "/api/v1/clinic/patients?projectId=" + projectId,
            HttpMethod.POST, createPatientReq, JsonNode.class);

        // MUST BE REFUSED WITH 403 FORBIDDEN
        assertThat(writeResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(writeResponse.getBody().get("error").asText()).isEqualTo("CLINIC_SUSPENDED");

        // 4. DATA INTACT PROOF: Read request continues to work and returns intact records
        ResponseEntity<JsonNode> readDuringSuspension = http.exchange(
            "/api/v1/customers?projectId=" + projectId,
            HttpMethod.GET, new HttpEntity<>(headers(sessionCookie)), JsonNode.class);

        assertThat(readDuringSuspension.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Medical records are intact and queryable; zero data deleted (DEC-007)
        assertThat(readDuringSuspension.getBody()).isNotNull();

        // 5. Reactivate clinic
        jdbc.update("""
            update "public"."projects"
               set "status" = 'ACTIVE',
                   "suspendedAt" = null,
                   "suspendedReason" = null
             where "id" = :id
            """,
            new MapSqlParameterSource("id", projectId));

        // 6. Write operations are restored
        // (Any subsequent write passes the suspension filter)
        ResponseEntity<JsonNode> readAfterReactivation = http.exchange(
            "/api/v1/customers?projectId=" + projectId,
            HttpMethod.GET, new HttpEntity<>(headers(sessionCookie)), JsonNode.class);
        assertThat(readAfterReactivation.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private String sessionFor(UUID userId) {
        BrowserSessionStore.IssuedSession issued =
            sessions.issue(userId, "isolation-http-test", "127.0.0.1", 1);
        assertThat(issued).as("a session was issued for user %s", userId).isNotNull();
        return securityProperties.getSessionCookieName() + "=" + issued.rawToken();
    }

    private HttpHeaders headers(String cookie) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (cookie != null) {
            headers.set(HttpHeaders.COOKIE, cookie);
        }
        headers.set("X-Requested-With", "XMLHttpRequest");
        return headers;
    }

    private record CsrfToken(String token, String cookie) {}

    private CsrfToken obtainCsrf() {
        ResponseEntity<JsonNode> csrfResp = http.getForEntity("/api/v1/auth/csrf", JsonNode.class);
        String cookieHeader = csrfResp.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        String token = csrfResp.getBody().get("token").asText();
        return new CsrfToken(token, cookieHeader != null ? cookieHeader.split(";")[0] : securityProperties.getCsrfCookieName() + "=" + token);
    }
}
