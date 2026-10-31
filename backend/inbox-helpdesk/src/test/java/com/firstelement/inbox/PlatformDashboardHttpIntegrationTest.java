package com.firstelement.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.firstelement.security.auth.PasswordVerifier;
import com.firstelement.security.platform.PlatformAuditRepository;
import com.firstelement.security.platform.PlatformCapability;
import com.firstelement.security.platform.PlatformDashboardService;
import com.firstelement.security.platform.PlatformOperatorContext;
import com.firstelement.security.session.BrowserSessionStore;
import java.math.BigDecimal;
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
 * End-to-end integration tests for Slice 6: Operator Dashboard.
 *
 * <p>SPEC Requirements:
 * <ul>
 *   <li>KPI cards: total clinics; clinics by status (Active/Trial/Grace/Suspended/Expired);
 *       expiring in 7 and 30 days; subscription revenue this month (platform revenue in JOD, not clinic revenue);
 *       new clinics this month.</li>
 *   <li>"Needs attention" table: expiring soon, overdue, clinics with no active admin, failed backups/jobs.</li>
 *   <li>Charts: subscription revenue by month; clinics by status; AI usage per clinic vs quota.</li>
 *   <li>Recent audit events panel: operator, action, target, outcome, timestamp.</li>
 *   <li>Role isolation: clinic admin/customer sessions receive 401/403.</li>
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
class PlatformDashboardHttpIntegrationTest extends InboxIntegrationTestBase {

    private static final UUID OPERATOR_ID = UUID.fromString("00000000-0000-0000-0000-00000000d001");
    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-0000-0000-00000000d002");

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

    private PlatformDashboardService dashboardService;
    private PlatformAuditRepository audit;
    private PlatformOperatorContext operator;
    private UUID clinicAdminId;
    private UUID testSubId;
    private UUID testPaymentId;

    @BeforeEach
    void setUp() {
        jdbc.getJdbcTemplate().execute("delete from subscription_payments where \"idempotencyKey\" like 'TEST-DASH-%'");
        jdbc.getJdbcTemplate().execute("delete from clinic_subscriptions where \"projectId\" = '" + InboxFixture.PROJECT_A1 + "'");

        fixture.reset();
        clinic.seedClinic();

        this.audit = new PlatformAuditRepository(jdbc);
        this.dashboardService = new PlatformDashboardService(jdbc, audit);

        // Seed platform operator
        jdbc.update("""
            insert into "public"."platform_operators"
                ("id", "email", "displayName", "passwordHash", "totpSecretEnc", "totpEnrolledAt", "isActive", "createdAt", "updatedAt")
            values
                (:id, 'operator.dash@firstelement.test', 'Dash Operator', 'dummy-hash', 'dummy-totp', now(), true, now(), now())
            on conflict ("id") do nothing
            """,
            new MapSqlParameterSource("id", OPERATOR_ID));

        // Seed platform session with elevated capability
        String rawToken = "dummyDashRawToken123";
        String tokenHash = com.firstelement.security.platform.PlatformAuthService.hash(rawToken);

        jdbc.update("""
            insert into "public"."platform_sessions"
                ("id", "operatorId", "tokenHash", "mfaSatisfiedAt", "elevatedAt", "elevatedExpiresAt",
                 "elevationReason", "createdAt", "expiresAt")
            values
                (:sid, :oid, :tokenHash, now(), now(), now() + interval '1 hour',
                 'Dashboard integration test', now(), now() + interval '8 hours')
            on conflict ("id") do update set "tokenHash" = :tokenHash, "elevatedExpiresAt" = now() + interval '1 hour'
            """,
            new MapSqlParameterSource()
                .addValue("sid", SESSION_ID)
                .addValue("oid", OPERATOR_ID)
                .addValue("tokenHash", tokenHash));

        // Grant capabilities in database
        jdbc.update("""
            insert into "public"."platform_operator_capabilities" ("operatorId", "capability", "grantedAt")
            values (:oid, 'READ_PLATFORM_OVERVIEW', now()),
                   (:oid, 'READ_PLATFORM_AUDIT', now())
            on conflict do nothing
            """,
            new MapSqlParameterSource("oid", OPERATOR_ID));

        this.operator = new PlatformOperatorContext(
            OPERATOR_ID,
            "operator.dash@firstelement.test",
            "Dash Operator",
            SESSION_ID,
            Set.of(PlatformCapability.READ_PLATFORM_OVERVIEW, PlatformCapability.READ_PLATFORM_AUDIT),
            true,
            Instant.now().plus(Duration.ofHours(1))
        );

        // Create Clinic Admin user for Project A1
        this.clinicAdminId = UUID.randomUUID();
        jdbc.update("""
            insert into "public"."users"
                ("id", "organizationId", "email", "passwordHash", "fullName", "role", "locale",
                 "isActive", "createdAt", "updatedAt")
            values
                (:id, :org, 'dr.admin@dash-clinic.test', :dummyHash, 'Dr. Admin Dash',
                 cast('ADMIN' as "UserRole"), cast('ar' as "Locale"), true, now(), now())
            """,
            new MapSqlParameterSource()
                .addValue("id", clinicAdminId)
                .addValue("org", InboxFixture.ORG_A)
                .addValue("dummyHash", PasswordVerifier.DUMMY_PASSWORD_HASH));

        jdbc.update("""
            insert into "public"."project_memberships" ("id", "organizationId", "projectId", "userId", "createdAt")
            values (gen_random_uuid(), :org, :pid, :uid, now())
            on conflict do nothing
            """,
            new MapSqlParameterSource()
                .addValue("org", InboxFixture.ORG_A)
                .addValue("pid", InboxFixture.PROJECT_A1)
                .addValue("uid", clinicAdminId));

        // Seed a plan and subscription for Project A1
        UUID starterPlanId = UUID.fromString("a0000000-0000-0000-0000-000000000001");
        this.testSubId = UUID.randomUUID();
        jdbc.update("""
            insert into "public"."clinic_subscriptions"
                ("id", "projectId", "organizationId", "planId", "status", "paidUntil", "gracePeriodDays",
                 "monthlyAiQuotaOverride", "version", "createdAt", "updatedAt")
            values
                (:subId, :pid, :orgId, :planId, 'ACTIVE', now() + interval '5 days', 7,
                 1000, 0, now(), now())
            on conflict ("projectId") do update set
                "status" = 'ACTIVE',
                "paidUntil" = now() + interval '5 days',
                "version" = "public"."clinic_subscriptions"."version" + 1
            """,
            new MapSqlParameterSource()
                .addValue("subId", testSubId)
                .addValue("pid", InboxFixture.PROJECT_A1)
                .addValue("orgId", InboxFixture.ORG_A)
                .addValue("planId", starterPlanId));

        // Seed a platform subscription payment
        this.testPaymentId = UUID.randomUUID();
        jdbc.update("""
            insert into "public"."subscription_payments"
                ("id", "subscriptionId", "projectId", "organizationId", "idempotencyKey",
                 "amount", "currency", "paymentMethod", "referenceNumber", "periodStart", "periodEnd",
                 "recordedByOperatorId", "notes", "isReversal", "createdAt")
            values
                (:payId, (select "id" from "public"."clinic_subscriptions" where "projectId" = :pid),
                 :pid, :orgId, :idemKey, 250.000, 'JOD', 'CliQ', 'CLIQ-TEST-99', now(), now() + interval '30 days',
                 :opId, 'Test subscription payment', false, now())
            on conflict ("idempotencyKey") do nothing
            """,
            new MapSqlParameterSource()
                .addValue("payId", testPaymentId)
                .addValue("pid", InboxFixture.PROJECT_A1)
                .addValue("orgId", InboxFixture.ORG_A)
                .addValue("idemKey", "TEST-DASH-IDEM-001")
                .addValue("opId", OPERATOR_ID));
    }

    @AfterEach
    void tearDown() {
        jdbc.getJdbcTemplate().execute("delete from subscription_payments where \"idempotencyKey\" like 'TEST-DASH-%'");
        if (clinicAdminId != null) {
            jdbc.update("delete from project_memberships where \"userId\" = :uid", new MapSqlParameterSource("uid", clinicAdminId));
            jdbc.update("delete from users where \"id\" = :uid", new MapSqlParameterSource("uid", clinicAdminId));
        }
    }

    private HttpHeaders operatorHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.COOKIE, "fe_platform_session=dummyDashRawToken123");
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        return headers;
    }

    @Test
    @DisplayName("Gated endpoint: unauthenticated request gets 401/403")
    void unauthenticatedRequestDenied() {
        ResponseEntity<JsonNode> resp = http.exchange(
            "/api/v1/platform/dashboard/overview",
            HttpMethod.GET,
            new HttpEntity<>(new HttpHeaders()),
            JsonNode.class
        );
        assertThat(resp.getStatusCode().value()).isIn(401, 403);
    }

    @Test
    @DisplayName("Gated endpoint: clinic customer session gets 401/403 on platform dashboard")
    void clinicCustomerSessionDeniedOnPlatformDashboard() {
        BrowserSessionStore.IssuedSession adminSession =
            sessions.issue(clinicAdminId, "admin-test", "127.0.0.1", 1);
        String cookie = securityProperties.getSessionCookieName() + "=" + adminSession.rawToken();

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.COOKIE, cookie);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        ResponseEntity<JsonNode> resp = http.exchange(
            "/api/v1/platform/dashboard/overview",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            JsonNode.class
        );
        assertThat(resp.getStatusCode().value()).isIn(401, 403);
    }

    @Test
    @DisplayName("Operator retrieves complete dashboard overview with valid KPIs, revenue, charts and audit")
    void operatorRetrievesDashboardOverviewSuccessfully() {
        ResponseEntity<JsonNode> resp = http.exchange(
            "/api/v1/platform/dashboard/overview",
            HttpMethod.GET,
            new HttpEntity<>(operatorHeaders()),
            JsonNode.class
        );

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = resp.getBody();
        assertThat(body).isNotNull();

        // 1. KPI cards
        JsonNode kpis = body.get("kpis");
        assertThat(kpis).isNotNull();
        assertThat(kpis.get("totalClinics").asInt()).isGreaterThanOrEqualTo(2);
        assertThat(kpis.get("activeClinics").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(kpis.get("currency").asText()).isEqualTo("JOD");
        assertThat(kpis.get("subscriptionRevenueThisMonth").asDouble()).isGreaterThanOrEqualTo(250.0);
        assertThat(kpis.get("expiringIn7Days").asInt()).isGreaterThanOrEqualTo(1);

        // 2. Needs Attention table
        JsonNode needsAttention = body.get("needsAttention");
        assertThat(needsAttention).isNotNull();
        assertThat(needsAttention.isArray()).isTrue();
        // Project A1 expires in 5 days, so it should be flagged as EXPIRING_SOON
        boolean hasExpiringSoon = false;
        for (JsonNode item : needsAttention) {
            if ("EXPIRING_SOON".equals(item.get("attentionType").asText())
                && InboxFixture.PROJECT_A1.toString().equals(item.get("clinicId").asText())) {
                hasExpiringSoon = true;
                assertThat(item.get("severity").asText()).isEqualTo("WARNING");
                assertThat(item.get("attentionReason").asText()).contains("expires in");
            }
        }
        assertThat(hasExpiringSoon).isTrue();

        // 3. Revenue by month (last 6 months)
        JsonNode revenueByMonth = body.get("revenueByMonth");
        assertThat(revenueByMonth).isNotNull();
        assertThat(revenueByMonth.isArray()).isTrue();
        assertThat(revenueByMonth.size()).isEqualTo(6);
        JsonNode latestMonth = revenueByMonth.get(revenueByMonth.size() - 1);
        assertThat(latestMonth.get("currency").asText()).isEqualTo("JOD");
        assertThat(latestMonth.get("amount").asDouble()).isGreaterThanOrEqualTo(250.0);

        // 4. Clinics by status distribution
        JsonNode clinicsByStatus = body.get("clinicsByStatus");
        assertThat(clinicsByStatus).isNotNull();
        assertThat(clinicsByStatus.isArray()).isTrue();
        assertThat(clinicsByStatus.size()).isGreaterThanOrEqualTo(5);

        // 5. AI usage quota
        JsonNode aiUsage = body.get("aiUsage");
        assertThat(aiUsage).isNotNull();
        assertThat(aiUsage.isArray()).isTrue();

        // 6. Recent audit events panel
        JsonNode recentAudit = body.get("recentAuditEvents");
        assertThat(recentAudit).isNotNull();
        assertThat(recentAudit.isArray()).isTrue();
        assertThat(recentAudit.size()).isGreaterThanOrEqualTo(1);
        boolean foundViewed = false;
        for (JsonNode evt : recentAudit) {
            if (PlatformAuditRepository.VIEWED.equals(evt.get("action").asText())) {
                foundViewed = true;
                assertThat(evt.get("targetType").asText()).isEqualTo("PlatformDashboard");
                break;
            }
        }
        assertThat(foundViewed).isTrue();
    }

    @Test
    @DisplayName("Needs attention feed flags suspended clinics with CRITICAL severity")
    void needsAttentionFlagsSuspendedClinic() {
        // Suspend Project A2
        jdbc.update("""
            update "public"."projects"
               set "status" = 'SUSPENDED',
                   "suspendedAt" = now(),
                   "suspendedReason" = 'Late payment test'
             where "id" = :pid
            """,
            new MapSqlParameterSource("pid", InboxFixture.PROJECT_A2));

        try {
            ResponseEntity<JsonNode> resp = http.exchange(
                "/api/v1/platform/dashboard/overview",
                HttpMethod.GET,
                new HttpEntity<>(operatorHeaders()),
                JsonNode.class
            );

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            JsonNode body = resp.getBody();
            JsonNode needsAttention = body.get("needsAttention");

            boolean foundSuspended = false;
            for (JsonNode item : needsAttention) {
                if ("SUSPENDED".equals(item.get("attentionType").asText())
                    && InboxFixture.PROJECT_A2.toString().equals(item.get("clinicId").asText())) {
                    foundSuspended = true;
                    assertThat(item.get("severity").asText()).isEqualTo("CRITICAL");
                    assertThat(item.get("attentionReason").asText()).contains("suspended");
                }
            }
            assertThat(foundSuspended).isTrue();
        } finally {
            // Restore Project A2
            jdbc.update("""
                update "public"."projects"
                   set "status" = 'ACTIVE',
                       "suspendedAt" = null,
                       "suspendedReason" = null
                 where "id" = :pid
                """,
                new MapSqlParameterSource("pid", InboxFixture.PROJECT_A2));
        }
    }
}
