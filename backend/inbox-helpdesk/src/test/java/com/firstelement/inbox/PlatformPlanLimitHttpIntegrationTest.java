package com.firstelement.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.firstelement.crm.clinic.PlanLimitEnforcementService;
import com.firstelement.security.auth.PasswordVerifier;
import com.firstelement.security.session.BrowserSessionStore;
import com.firstelement.security.platform.PlatformAuditRepository;
import com.firstelement.security.platform.PlatformCapability;
import com.firstelement.security.platform.PlatformOperatorContext;
import com.firstelement.security.platform.PlatformPlanService;
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
 * End-to-end integration tests for Slice 4: Plans, Limits & Server-Side Entitlement Enforcement.
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
class PlatformPlanLimitHttpIntegrationTest extends InboxIntegrationTestBase {

    private static final UUID OPERATOR_ID = UUID.fromString("00000000-0000-0000-0000-00000000e001");
    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-0000-0000-00000000e002");
    private static final UUID STARTER_PLAN_ID = UUID.fromString("a0000000-0000-0000-0000-000000000001");
    private static final UUID GROWTH_PLAN_ID = UUID.fromString("a0000000-0000-0000-0000-000000000002");

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

    private PlatformPlanService planService;
    private PlatformOperatorContext elevatedOperator;
    private UUID clinicAdminId;

    @BeforeEach
    void setup() {
        jdbc.getJdbcTemplate().execute("delete from clinic_locations");
        jdbc.getJdbcTemplate().execute("delete from clinic_ai_proposals");
        jdbc.getJdbcTemplate().execute("delete from clinic_entitlement_overrides");
        jdbc.getJdbcTemplate().execute("delete from subscription_payments");
        jdbc.getJdbcTemplate().execute("delete from clinic_subscriptions");

        fixture.reset();
        clinic.seedClinic();

        // Seed platform operator & session
        jdbc.update("""
            insert into platform_operators ("id", "email", "displayName", "passwordHash", "totpSecretEnc", "totpEnrolledAt", "isActive", "createdAt", "updatedAt")
            values (:id, 'billing-operator@firstelement.test', 'Billing Operator', 'dummy-hash', 'dummy-totp', now(), true, now(), now())
            on conflict ("id") do nothing
            """,
            new MapSqlParameterSource("id", OPERATOR_ID));

        jdbc.update("""
            insert into platform_sessions ("id", "operatorId", "tokenHash", "mfaSatisfiedAt", "elevatedAt", "elevatedExpiresAt", "elevationReason", "createdAt", "expiresAt")
            values (:sid, :opId, 'dummy-token-hash', now(), now(), now() + interval '1 hour', 'Plan limits test', now(), now() + interval '8 hours')
            on conflict ("id") do nothing
            """,
            new MapSqlParameterSource()
                .addValue("sid", SESSION_ID)
                .addValue("opId", OPERATOR_ID));

        PlatformAuditRepository audit = new PlatformAuditRepository(jdbc);
        this.planService = new PlatformPlanService(jdbc, audit);

        this.elevatedOperator = new PlatformOperatorContext(
            OPERATOR_ID,
            "billing-operator@firstelement.test",
            "Billing Operator",
            SESSION_ID,
            Set.of(PlatformCapability.READ_PLATFORM_OVERVIEW, PlatformCapability.CREATE_CLINIC, PlatformCapability.MANAGE_BILLING),
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
                (:id, :org, 'clinic.admin@clinic.test', :dummyHash, 'Dr. Admin Tariq',
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

        // Default clinic subscription on Starter Plan (maxBranches = 1, maxUsers = 3, monthlyAiQuota = 100, CLINIC_AI = false)
        jdbc.update("""
            insert into "public"."clinic_subscriptions"
                ("id", "organizationId", "projectId", "planId", "status", "paidUntil", "gracePeriodDays", "version", "createdAt", "updatedAt")
            values
                (gen_random_uuid(), :org, :pid, :planId, 'ACTIVE', now() + interval '30 days', 7, 0, now(), now())
            """,
            new MapSqlParameterSource()
                .addValue("org", InboxFixture.ORG_A)
                .addValue("pid", InboxFixture.PROJECT_A1)
                .addValue("planId", STARTER_PLAN_ID));
    }

    @AfterEach
    void tearDown() {
        jdbc.getJdbcTemplate().execute("delete from clinic_locations");
        jdbc.getJdbcTemplate().execute("delete from clinic_ai_proposals");
        jdbc.getJdbcTemplate().execute("delete from clinic_entitlement_overrides");
        jdbc.getJdbcTemplate().execute("delete from subscription_payments");
        jdbc.getJdbcTemplate().execute("delete from clinic_subscriptions");
    }

    @Test
    @DisplayName("Branch limit is enforced server-side: 2nd location is refused with 403 on Starter Plan (max 1)")
    void branchLimitEnforcedServerSide() {
        String sessionCookie = sessionFor(clinicAdminId);
        UUID projectId = InboxFixture.PROJECT_A1;

        // 1. Create first location -> Should succeed (count becomes 1 == maxBranches 1)
        Map<String, Object> loc1Payload = Map.of(
            "name", "Amman 7th Circle Branch",
            "city", "Amman",
            "contactNumber", "+96265001111",
            "slotMinutes", 30,
            "isActive", true
        );
        ResponseEntity<JsonNode> r1 = http.exchange(
            "/api/v1/clinic/locations?projectId=" + projectId,
            HttpMethod.POST,
            new HttpEntity<>(loc1Payload, headers(sessionCookie)),
            JsonNode.class
        );
        assertThat(r1.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // 2. Attempt to create second location -> Must be refused with HTTP 403 PLAN_LIMIT_EXCEEDED
        Map<String, Object> loc2Payload = Map.of(
            "name", "Abdali Boulevard Branch",
            "city", "Amman",
            "contactNumber", "+96265002222",
            "slotMinutes", 30,
            "isActive", true
        );
        ResponseEntity<JsonNode> r2 = http.exchange(
            "/api/v1/clinic/locations?projectId=" + projectId,
            HttpMethod.POST,
            new HttpEntity<>(loc2Payload, headers(sessionCookie)),
            JsonNode.class
        );
        assertThat(r2.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(r2.getBody().get("code").asText()).isEqualTo("PLAN_LIMIT_EXCEEDED");
    }

    @Test
    @DisplayName("Branch limit override allows expansion beyond plan defaults")
    void branchLimitOverrideAllowsExpansion() {
        String sessionCookie = sessionFor(clinicAdminId);
        UUID projectId = InboxFixture.PROJECT_A1;

        // Create first location (1/1)
        Map<String, Object> loc1Payload = Map.of(
            "name", "Amman Downtown Branch",
            "city", "Amman",
            "contactNumber", "+96265003333",
            "slotMinutes", 30,
            "isActive", true
        );
        ResponseEntity<JsonNode> r1 = http.exchange(
            "/api/v1/clinic/locations?projectId=" + projectId,
            HttpMethod.POST,
            new HttpEntity<>(loc1Payload, headers(sessionCookie)),
            JsonNode.class
        );
        assertThat(r1.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Platform operator overrides maxBranches to 3
        planService.updateClinicLimits(
            elevatedOperator,
            projectId,
            new PlatformPlanService.ClinicLimitsOverrideCommand(null, 3, null, null, "Clinic opened new branch"),
            "corr-branch-override"
        );

        // Now create second location -> Must succeed with 201 CREATED
        Map<String, Object> loc2Payload = Map.of(
            "name", "Sweifieh Branch",
            "city", "Amman",
            "contactNumber", "+96265004444",
            "slotMinutes", 30,
            "isActive", true
        );
        ResponseEntity<JsonNode> r2 = http.exchange(
            "/api/v1/clinic/locations?projectId=" + projectId,
            HttpMethod.POST,
            new HttpEntity<>(loc2Payload, headers(sessionCookie)),
            JsonNode.class
        );
        assertThat(r2.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("Clinic AI feature entitlement is enforced server-side: 403 FEATURE_NOT_ENTITLED on Starter Plan")
    void clinicAiEntitlementEnforcedServerSide() {
        String sessionCookie = sessionFor(clinicAdminId);
        UUID projectId = InboxFixture.PROJECT_A1;

        // Starter plan has CLINIC_AI = false
        Map<String, Object> recallPayload = Map.of(
            "overdueDaysThreshold", 180,
            "limit", 5
        );
        ResponseEntity<JsonNode> response = http.exchange(
            "/api/v1/clinic/ai/proposals/recall?projectId=" + projectId,
            HttpMethod.POST,
            new HttpEntity<>(recallPayload, headers(sessionCookie)),
            JsonNode.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("code").asText()).isEqualTo("FEATURE_NOT_ENTITLED");
    }

    @Test
    @DisplayName("Clinic AI entitlement override enables feature and monthly quota enforces 403 on limit")
    void clinicAiEntitlementOverrideAndQuotaEnforcement() {
        String sessionCookie = sessionFor(clinicAdminId);
        UUID projectId = InboxFixture.PROJECT_A1;

        // 1. Operator overrides CLINIC_AI to true and monthlyAiQuota to 1
        planService.updateClinicEntitlementOverride(
            elevatedOperator, projectId, "CLINIC_AI", true, "Special AI pilot access", "corr-ai-pilot"
        );
        planService.updateClinicLimits(
            elevatedOperator, projectId,
            new PlatformPlanService.ClinicLimitsOverrideCommand(null, null, null, 1, "Testing AI run quota"),
            "corr-quota-test"
        );

        // 2. First AI proposal generation -> Succeeds (0 used < 1 quota)
        Map<String, Object> recallPayload = Map.of(
            "overdueDaysThreshold", 180,
            "limit", 5
        );
        ResponseEntity<JsonNode> r1 = http.exchange(
            "/api/v1/clinic/ai/proposals/recall?projectId=" + projectId,
            HttpMethod.POST,
            new HttpEntity<>(recallPayload, headers(sessionCookie)),
            JsonNode.class
        );
        assertThat(r1.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // 3. Second AI proposal generation -> Refused with 403 PLAN_LIMIT_EXCEEDED
        // because effective monthly quota is 1, and 1 proposal has already been created this month.
        ResponseEntity<JsonNode> r2 = http.exchange(
            "/api/v1/clinic/ai/proposals/recall?projectId=" + projectId,
            HttpMethod.POST,
            new HttpEntity<>(recallPayload, headers(sessionCookie)),
            JsonNode.class
        );
        assertThat(r2.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(r2.getBody().get("code").asText()).isEqualTo("PLAN_LIMIT_EXCEEDED");
    }

    @Test
    @DisplayName("User limit is enforced server-side: creating front-desk staff beyond maxUsers is refused with 403")
    void userLimitEnforcedServerSide() {
        String sessionCookie = sessionFor(clinicAdminId);
        UUID projectId = InboxFixture.PROJECT_A1;

        // Set maxUsersOverride to 1 (clinicAdmin is already 1 active user)
        planService.updateClinicLimits(
            elevatedOperator, projectId,
            new PlatformPlanService.ClinicLimitsOverrideCommand(1, null, null, null, "Strict 1-seat limit"),
            "corr-user-limit"
        );

        // Attempt to create a front desk user
        Map<String, Object> staffPayload = Map.of(
            "firstName", "Farah",
            "lastName", "Khatib",
            "email", "farah." + UUID.randomUUID().toString().substring(0, 5) + "@clinic.test",
            "password", "Reception2026!Secure",
            "isActive", true
        );

        ResponseEntity<JsonNode> response = http.exchange(
            "/api/v1/clinic/front-desk?projectId=" + projectId,
            HttpMethod.POST,
            new HttpEntity<>(staffPayload, headers(sessionCookie)),
            JsonNode.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("code").asText()).isEqualTo("PLAN_LIMIT_EXCEEDED");
    }

    private String sessionFor(UUID userId) {
        BrowserSessionStore.IssuedSession issued =
            sessions.issue(userId, "plan-limits-test", "127.0.0.1", 1);
        assertThat(issued).as("a session was issued for user %s", userId).isNotNull();
        return securityProperties.getSessionCookieName() + "=" + issued.rawToken();
    }

    private HttpHeaders headers(String cookie) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<JsonNode> token = http.getForEntity("/api/v1/auth/csrf", JsonNode.class);
        String csrf = token.getBody().get("token").asText();
        headers.set(securityProperties.getCsrfHeaderName(), csrf);
        headers.set(HttpHeaders.COOKIE, cookie + "; " + securityProperties.getCsrfCookieName() + "=" + csrf);
        return headers;
    }
}
