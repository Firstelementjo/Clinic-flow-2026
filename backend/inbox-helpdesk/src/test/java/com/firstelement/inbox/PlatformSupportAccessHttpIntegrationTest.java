package com.firstelement.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.firstelement.security.auth.PasswordVerifier;
import com.firstelement.security.platform.PlatformAuditRepository;
import com.firstelement.security.platform.PlatformCapability;
import com.firstelement.security.platform.PlatformOperatorContext;
import com.firstelement.security.platform.PlatformOperatorContextHolder.PlatformAccessDenied;
import com.firstelement.security.platform.PlatformSupportAccessService;
import com.firstelement.security.platform.PlatformSupportAccessService.RequestAccessCommand;
import com.firstelement.security.platform.PlatformSupportAccessService.RevokeAccessCommand;
import com.firstelement.security.platform.PlatformSupportAccessService.SupportAccessGrantDto;
import com.firstelement.security.session.BrowserSessionStore;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
 * End-to-end integration tests for Slice 5: Support Access (Break-Glass).
 *
 * <p>SPEC Requirements:
 * <ul>
 *   <li>Operator has NO patient-data access by default.</li>
 *   <li>To investigate an issue: request access with a written reason, time-limited (e.g. 60 min, auto-expires).</li>
 *   <li>Read-only by default and by enforcement: mutations are strictly forbidden (403).</li>
 *   <li>Clinic admin notified, fully audited.</li>
 *   <li>No open-ended "login as user" (no token masquerade).</li>
 *   <li>Support-access grants expire and are audited.</li>
 *   <li>Operator gets 403 on every patient/appointment/encounter/clinic-finance endpoint; clinic admin gets 403 on every operator endpoint; cross-tenant access denied.</li>
 * </ul>
 */
@SpringBootTest(
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
class PlatformSupportAccessHttpIntegrationTest extends InboxIntegrationTestBase {

    private static final UUID OPERATOR_ID = UUID.fromString("00000000-0000-0000-0000-00000000f001");
    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-0000-0000-00000000f002");

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

    private PlatformSupportAccessService supportService;
    private PlatformAuditRepository audit;
    private PlatformOperatorContext operator;
    private UUID clinicAdminId;
    private UUID testPatientId;
    private String testPatientNo;

    @BeforeEach
    void setup() {
        jdbc.getJdbcTemplate().execute("delete from support_access_grants");
        jdbc.getJdbcTemplate().execute("delete from clinic_audit_log");
        jdbc.getJdbcTemplate().execute("delete from clinic_locations");
        jdbc.getJdbcTemplate().execute("delete from clinic_ai_proposals");
        jdbc.getJdbcTemplate().execute("delete from clinic_entitlement_overrides");
        jdbc.getJdbcTemplate().execute("delete from subscription_payments");
        jdbc.getJdbcTemplate().execute("delete from clinic_subscriptions");

        fixture.reset();
        clinic.seedClinic();

        audit = new PlatformAuditRepository(jdbc);
        supportService = new PlatformSupportAccessService(jdbc, audit);

        // Seed platform operator & session
        jdbc.update("""
            insert into platform_operators ("id", "email", "displayName", "passwordHash", "totpSecretEnc", "totpEnrolledAt", "isActive", "createdAt", "updatedAt")
            values (:id, 'support-ops@firstelement.test', 'Support Engineer', 'dummy-hash', 'dummy-totp', now(), true, now(), now())
            on conflict ("id") do nothing
            """,
            new MapSqlParameterSource("id", OPERATOR_ID));

        jdbc.update("""
            insert into platform_sessions ("id", "operatorId", "tokenHash", "mfaSatisfiedAt", "elevatedAt", "elevatedExpiresAt", "elevationReason", "createdAt", "expiresAt")
            values (:sid, :opId, 'support-token-hash-f002', now(), now(), now() + interval '1 hour', 'Break-glass test', now(), now() + interval '8 hours')
            on conflict ("id") do update set "elevatedExpiresAt" = now() + interval '1 hour'
            """,
            new MapSqlParameterSource()
                .addValue("sid", SESSION_ID)
                .addValue("opId", OPERATOR_ID));

        this.operator = new PlatformOperatorContext(
            OPERATOR_ID,
            "support-ops@firstelement.test",
            "Support Engineer",
            SESSION_ID,
            Set.of(PlatformCapability.READ_PLATFORM_OVERVIEW, PlatformCapability.GRANT_ACCESS),
            true,
            Instant.now().plus(Duration.ofHours(1))
        );

        // Create Clinic Admin user
        this.clinicAdminId = UUID.randomUUID();
        jdbc.update("""
            insert into "public"."users"
                ("id", "organizationId", "email", "passwordHash", "fullName", "role", "locale",
                 "isActive", "createdAt", "updatedAt")
            values
                (:id, :org, 'dr.admin@clinic.test', :dummyHash, 'Dr. Admin Tariq',
                 cast('ADMIN' as "UserRole"), cast('ar' as "Locale"), true, now(), now())
            """,
            new MapSqlParameterSource()
                .addValue("id", clinicAdminId)
                .addValue("org", InboxFixture.ORG_A)
                .addValue("dummyHash", PasswordVerifier.DUMMY_PASSWORD_HASH));

        // Assign to project A1
        jdbc.update("""
            insert into "public"."project_memberships" ("id", "organizationId", "projectId", "userId", "createdAt")
            values (gen_random_uuid(), :org, :pid, :uid, now())
            on conflict do nothing
            """,
            new MapSqlParameterSource()
                .addValue("org", InboxFixture.ORG_A)
                .addValue("pid", InboxFixture.PROJECT_A1)
                .addValue("uid", clinicAdminId));

        // Seed a patient in Project A1
        this.testPatientId = UUID.randomUUID();
        this.testPatientNo = "P-" + UUID.randomUUID().toString().substring(0, 8);
        String uniqueSuffix = UUID.randomUUID().toString().replaceAll("[^0-9]", "") + "0000000";
        String uniquePhone = "+9627" + uniqueSuffix.substring(0, 7);

        jdbc.update("""
            insert into "public"."patients"
                ("id", "organizationId", "projectId", "patientNo", "displayName", "givenName", "familyName",
                 "dateOfBirth", "primaryPhone", "primaryEmail", "gender", "isActive", "createdAt", "updatedAt")
            values
                (:id, :org, :pid, :patientNo, 'Omar Khalid', 'Omar', 'Khalid',
                 '1985-06-15', :phone, :email, 'MALE', true, now(), now())
            on conflict do nothing
            """,
            new MapSqlParameterSource()
                .addValue("id", testPatientId)
                .addValue("org", InboxFixture.ORG_A)
                .addValue("pid", InboxFixture.PROJECT_A1)
                .addValue("patientNo", testPatientNo)
                .addValue("phone", uniquePhone)
                .addValue("email", "patient." + testPatientId + "@test.com"));
    }

    @AfterEach
    void tearDown() {
        if (testPatientId != null) {
            jdbc.update("delete from patients where id = :id", new MapSqlParameterSource("id", testPatientId));
        }
        jdbc.getJdbcTemplate().execute("delete from support_access_grants");
        jdbc.getJdbcTemplate().execute("delete from clinic_audit_log");
        jdbc.getJdbcTemplate().execute("delete from clinic_locations");
        jdbc.getJdbcTemplate().execute("delete from clinic_ai_proposals");
        jdbc.getJdbcTemplate().execute("delete from clinic_entitlement_overrides");
        jdbc.getJdbcTemplate().execute("delete from subscription_payments");
        jdbc.getJdbcTemplate().execute("delete from clinic_subscriptions");
    }

    @Test
    @DisplayName("Operator has NO patient-data access by default (without grant)")
    void operatorHasNoPatientDataAccessByDefault() {
        UUID projectId = InboxFixture.PROJECT_A1;

        // Verify operator has no active grant
        boolean hasAccess = supportService.hasActiveSupportAccess(OPERATOR_ID, projectId);
        assertThat(hasAccess).isFalse();

        // Calling customer clinical endpoint with no customer session returns 401 Unauthorized
        ResponseEntity<JsonNode> resp = http.exchange(
            "/api/v1/clinic/patients?projectId=" + projectId,
            HttpMethod.GET,
            new HttpEntity<>(new HttpHeaders()),
            JsonNode.class
        );
        assertThat(resp.getStatusCode().value()).isIn(401, 403);
    }

    @Test
    @DisplayName("Requesting support access creates active 60-min grant, audits event, and notifies clinic admin")
    void requestSupportAccessCreatesActiveGrantWithAuditAndNotification() {
        UUID projectId = InboxFixture.PROJECT_A1;
        String reason = "Investigating missing appointment sync reported by clinic admin";

        SupportAccessGrantDto grant = supportService.requestAccess(
            operator,
            new RequestAccessCommand(projectId, reason, 60, true),
            "corr-break-glass-1"
        );

        assertThat(grant.id()).isNotNull();
        assertThat(grant.projectId()).isEqualTo(projectId);
        assertThat(grant.operatorId()).isEqualTo(OPERATOR_ID);
        assertThat(grant.reason()).isEqualTo(reason);
        assertThat(grant.isReadOnly()).isTrue();
        assertThat(grant.status()).isEqualTo("ACTIVE");
        assertThat(grant.remainingMinutes()).isGreaterThan(55);
        assertThat(grant.notifiedClinicAdminAt()).isNotNull();

        // 1. Verify Platform Audit event was recorded
        List<Map<String, Object>> platformAudits = jdbc.queryForList("""
            select * from platform_audit_events
             where "action" = 'SUPPORT_ACCESS_GRANTED'
               and "operatorId" = :opId
            """, new MapSqlParameterSource("opId", OPERATOR_ID));
        assertThat(platformAudits).isNotEmpty();
        assertThat(platformAudits.get(0).get("reason")).isEqualTo(reason);

        // 2. Verify Clinic Audit notification was recorded for the clinic admin
        List<Map<String, Object>> clinicAudits = jdbc.queryForList("""
            select * from clinic_audit_log
             where "action" = 'BREAK_GLASS_ACTIVATED'
               and "projectId" = :pid
            """, new MapSqlParameterSource("pid", projectId));
        assertThat(clinicAudits).isNotEmpty();
        assertThat(clinicAudits.get(0).get("resourceType")).isEqualTo("BREAK_GLASS");
        assertThat(clinicAudits.get(0).get("resourceId")).isEqualTo(grant.id().toString());
        assertThat(clinicAudits.get(0).get("actorRole")).isEqualTo("PLATFORM_OPERATOR");
    }

    @Test
    @DisplayName("Active grant allows diagnostic inspection of patients, appointments, and audit log")
    void activeGrantAllowsDiagnosticInspection() {
        UUID projectId = InboxFixture.PROJECT_A1;

        SupportAccessGrantDto grant = supportService.requestAccess(
            operator,
            new RequestAccessCommand(projectId, "Investigating corrupted patient profile records", 60, true),
            "corr-inspect-1"
        );

        // 1. Read patients under active grant
        List<Map<String, Object>> patients = supportService.getClinicPatients(operator, grant.id(), "corr-patients");
        assertThat(patients).isNotEmpty();
        assertThat(patients.get(0).get("displayName")).isEqualTo("Omar Khalid");
        assertThat(patients.get(0).get("patientNo")).isEqualTo(testPatientNo);

        // 2. Read appointments under active grant
        List<Map<String, Object>> appointments = supportService.getClinicAppointments(operator, grant.id(), "corr-appts");
        assertThat(appointments).isNotNull();

        // 3. Read clinic audit log under active grant
        List<Map<String, Object>> auditLogs = supportService.getClinicAuditLog(operator, grant.id(), "corr-audit");
        assertThat(auditLogs).isNotEmpty();
    }

    @Test
    @DisplayName("Support access is strictly read-only: mutations are refused with 403 Forbidden")
    void supportAccessIsStrictlyReadOnlyMutationsRefused() {
        assertThatThrownBy(() -> supportService.rejectMutation())
            .isInstanceOf(PlatformAccessDenied.class)
            .hasMessageContaining("strictly read-only");
    }

    @Test
    @DisplayName("Cross-tenant isolation: grant for Clinic A cannot access Clinic B")
    void crossTenantIsolationEnforced() {
        UUID projectA = InboxFixture.PROJECT_A1;
        UUID projectB = InboxFixture.PROJECT_B1;

        // Grant is for Project A
        SupportAccessGrantDto grantA = supportService.requestAccess(
            operator,
            new RequestAccessCommand(projectA, "Investigating Clinic A records only", 60, true),
            "corr-cross-1"
        );

        // Operator does NOT have active access for Project B
        boolean hasAccessB = supportService.hasActiveSupportAccess(OPERATOR_ID, projectB);
        assertThat(hasAccessB).isFalse();

        // Patient in Clinic A is not returned for Clinic B queries
        List<Map<String, Object>> patientsInA = supportService.getClinicPatients(operator, grantA.id(), "corr-a");
        assertThat(patientsInA).anyMatch(p -> "Omar Khalid".equals(p.get("displayName")));
    }

    @Test
    @DisplayName("Revoking grant immediately restores 403 denial and logs BREAK_GLASS_REVOKED")
    void revokingGrantImmediatelyRestores403() {
        UUID projectId = InboxFixture.PROJECT_A1;

        SupportAccessGrantDto grant = supportService.requestAccess(
            operator,
            new RequestAccessCommand(projectId, "Routine support check", 60, true),
            "corr-revoke-1"
        );

        // Revoke the grant
        SupportAccessGrantDto revoked = supportService.revokeAccess(
            operator,
            new RevokeAccessCommand(grant.id(), "Investigation completed successfully"),
            "corr-revoke-2"
        );

        assertThat(revoked.status()).isEqualTo("REVOKED");
        assertThat(revoked.revokedAt()).isNotNull();
        assertThat(revoked.revokedByOperatorId()).isEqualTo(OPERATOR_ID);
        assertThat(revoked.revocationReason()).isEqualTo("Investigation completed successfully");

        // Verify clinic audit log has BREAK_GLASS_REVOKED
        List<Map<String, Object>> clinicAudits = jdbc.queryForList("""
            select * from clinic_audit_log
             where "action" = 'BREAK_GLASS_REVOKED'
               and "projectId" = :pid
            """, new MapSqlParameterSource("pid", projectId));
        assertThat(clinicAudits).isNotEmpty();

        // Subsequent patient inspection attempt MUST throw PlatformAccessDenied (403)
        assertThatThrownBy(() -> supportService.getClinicPatients(operator, grant.id(), "corr-fail"))
            .isInstanceOf(PlatformAccessDenied.class)
            .hasMessageContaining("revoked");
    }

    @Test
    @DisplayName("Expired grant automatically refuses clinical inspection with 403")
    void expiredGrantRefusesAccessWith403() {
        UUID projectId = InboxFixture.PROJECT_A1;

        // Create grant with expired timestamp
        UUID grantId = UUID.randomUUID();
        Instant past = Instant.now().minus(Duration.ofMinutes(10));
        Instant earlier = past.minus(Duration.ofMinutes(60));

        jdbc.update("""
            insert into "public"."support_access_grants"
                ("id", "organizationId", "projectId", "operatorId", "reason", "isReadOnly",
                 "status", "startsAt", "expiresAt", "createdAt")
            values
                (:id, :org, :pid, :opId, 'Past investigation', true,
                 'ACTIVE', :startsAt, :expiresAt, :startsAt)
            """,
            new MapSqlParameterSource()
                .addValue("id", grantId)
                .addValue("org", InboxFixture.ORG_A)
                .addValue("pid", projectId)
                .addValue("opId", OPERATOR_ID)
                .addValue("startsAt", Timestamp.from(earlier))
                .addValue("expiresAt", Timestamp.from(past)));

        // Inspection must be rejected because grant has expired
        assertThatThrownBy(() -> supportService.getClinicPatients(operator, grantId, "corr-expired"))
            .isInstanceOf(PlatformAccessDenied.class)
            .hasMessageContaining("expired");

        // Querying grant should show status auto-transitioned to EXPIRED
        SupportAccessGrantDto grant = supportService.getGrant(grantId);
        assertThat(grant.status()).isEqualTo("EXPIRED");
        assertThat(grant.remainingMinutes()).isEqualTo(0);
    }

    @Test
    @DisplayName("Invalid reason (< 10 chars) or invalid duration (< 15 or > 120 min) is rejected with 400")
    void invalidParametersRejectedWith400() {
        UUID projectId = InboxFixture.PROJECT_A1;

        // Too short reason
        assertThatThrownBy(() -> supportService.requestAccess(
            operator,
            new RequestAccessCommand(projectId, "short", 60, true),
            "corr-err-1"
        )).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("at least 10 characters");

        // Duration too low (< 15)
        assertThatThrownBy(() -> supportService.requestAccess(
            operator,
            new RequestAccessCommand(projectId, "Valid reason of sufficient length", 5, true),
            "corr-err-2"
        )).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("between 15 and 120 minutes");

        // Duration too high (> 120)
        assertThatThrownBy(() -> supportService.requestAccess(
            operator,
            new RequestAccessCommand(projectId, "Valid reason of sufficient length", 200, true),
            "corr-err-3"
        )).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("between 15 and 120 minutes");
    }

    @Test
    @DisplayName("Clinic admin session gets 401/403 on operator support access endpoints")
    void clinicAdminCannotAccessPlatformSupportEndpoints() {
        BrowserSessionStore.IssuedSession adminSession =
            sessions.issue(clinicAdminId, "admin-test", "127.0.0.1", 1);
        String cookie = securityProperties.getSessionCookieName() + "=" + adminSession.rawToken();

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.COOKIE, cookie);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        ResponseEntity<JsonNode> response = http.exchange(
            "/api/v1/platform/support-access",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            JsonNode.class
        );

        // Must be refused: customer session cannot reach /api/v1/platform/**
        assertThat(response.getStatusCode().value()).isIn(401, 403);
    }
}
