package com.firstelement.inbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.firstelement.security.session.BrowserSessionStore;
import com.firstelement.security.auth.PasswordVerifier;
import com.firstelement.security.config.SecurityProperties;
import com.firstelement.security.platform.PlatformAuditRepository;
import com.firstelement.security.platform.PlatformCapability;
import com.firstelement.security.platform.PlatformOperatorContext;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

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
class PlatformAuditTrailHttpIntegrationTest extends InboxIntegrationTestBase {

    private static final UUID OPERATOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000777");
    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-0000-0000-000000000888");

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Autowired
    private InboxFixture fixture;

    @Autowired
    private ClinicFixture clinic;

    @Autowired
    private BrowserSessionStore sessions;

    @Autowired
    private SecurityProperties securityProperties;

    @Autowired
    private PlatformAuditRepository auditRepo;

    private UUID clinicAdminId;
    private UUID testAuditEventId;

    @BeforeEach
    void setUp() {
        fixture.reset();
        clinic.seedClinic();
        // Ensure test operator exists
        jdbc.update("""
            insert into "public"."platform_operators"
                ("id", "email", "displayName", "passwordHash", "totpSecretEnc", "totpEnrolledAt", "isActive", "createdAt", "updatedAt")
            values
                (:id, 'operator.audit@firstelement.test', 'Audit Operator', 'dummy-hash', 'dummy-totp', now(), true, now(), now())
            on conflict ("id") do nothing
            """,
            new MapSqlParameterSource("id", OPERATOR_ID));

        // Seed platform session
        String rawToken = "dummyAuditRawToken123";
        String tokenHash = com.firstelement.security.platform.PlatformAuthService.hash(rawToken);

        jdbc.update("""
            insert into "public"."platform_sessions"
                ("id", "operatorId", "tokenHash", "mfaSatisfiedAt", "elevatedAt", "elevatedExpiresAt",
                 "elevationReason", "createdAt", "expiresAt")
            values
                (:sid, :oid, :tokenHash, now(), now(), now() + interval '1 hour',
                 'Audit trail test', now(), now() + interval '8 hours')
            on conflict ("id") do update set "tokenHash" = :tokenHash, "elevatedExpiresAt" = now() + interval '1 hour'
            """,
            new MapSqlParameterSource()
                .addValue("sid", SESSION_ID)
                .addValue("oid", OPERATOR_ID)
                .addValue("tokenHash", tokenHash));

        // Grant capabilities
        jdbc.update("""
            insert into "public"."platform_operator_capabilities" ("operatorId", "capability", "grantedAt")
            values (:oid, 'READ_PLATFORM_AUDIT', now()),
                   (:oid, 'READ_PLATFORM_OVERVIEW', now())
            on conflict do nothing
            """,
            new MapSqlParameterSource("oid", OPERATOR_ID));

        // Seed customer user
        this.clinicAdminId = UUID.randomUUID();
        jdbc.update("""
            insert into "public"."users"
                ("id", "organizationId", "email", "passwordHash", "fullName", "role", "locale",
                 "isActive", "createdAt", "updatedAt")
            values
                (:id, :org, 'dr.admin@audit-test.test', :dummyHash, 'Dr. Admin Audit',
                 cast('ADMIN' as "UserRole"), cast('ar' as "Locale"), true, now(), now())
            """,
            new MapSqlParameterSource()
                .addValue("id", clinicAdminId)
                .addValue("org", InboxFixture.ORG_A)
                .addValue("dummyHash", PasswordVerifier.DUMMY_PASSWORD_HASH));

        // Seed test audit events directly
        this.testAuditEventId = UUID.randomUUID();
        jdbc.update("""
            insert into "public"."platform_audit_events"
                ("id", "operatorId", "sessionId", "action", "resourceType", "resourceId",
                 "organizationId", "projectId", "reason", "detail", "correlationId", "ipAddress", "outcome", "occurredAt")
            values
                (:id, :opId, :sessId, 'TEST_AUDIT_ACTION', 'Clinic', :projId,
                 :orgId, :projId, 'Testing audit trail details', 'Comprehensive audit details test',
                 'CORR-AUDIT-999', '192.168.1.100', 'ALLOWED', now())
            """,
            new MapSqlParameterSource()
                .addValue("id", testAuditEventId)
                .addValue("opId", OPERATOR_ID)
                .addValue("sessId", SESSION_ID)
                .addValue("orgId", InboxFixture.ORG_A)
                .addValue("projId", InboxFixture.PROJECT_A1));
    }

    @AfterEach
    void tearDown() {
        if (clinicAdminId != null) {
            jdbc.update("delete from users where \"id\" = :uid", new MapSqlParameterSource("uid", clinicAdminId));
        }
    }

    private HttpHeaders operatorHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.COOKIE, "fe_platform_session=dummyAuditRawToken123");
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        return headers;
    }

    @Test
    @DisplayName("Gated endpoint: unauthenticated request gets 401/403 on audit trail")
    void unauthenticatedRequestDenied() {
        ResponseEntity<JsonNode> resp = http.exchange(
            "/api/v1/platform/audit",
            HttpMethod.GET,
            HttpEntity.EMPTY,
            JsonNode.class
        );
        assertThat(resp.getStatusCode().value()).isIn(401, 403);
    }

    @Test
    @DisplayName("Gated endpoint: clinic customer session gets 401/403 on platform audit trail")
    void customerDeniedOnPlatformAudit() {
        BrowserSessionStore.IssuedSession adminSession =
            sessions.issue(clinicAdminId, "admin-test", "127.0.0.1", 1);
        String cookie = securityProperties.getSessionCookieName() + "=" + adminSession.rawToken();

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.COOKIE, cookie);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        ResponseEntity<JsonNode> resp = http.exchange(
            "/api/v1/platform/audit",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            JsonNode.class
        );
        assertThat(resp.getStatusCode().value()).isIn(401, 403);
    }

    @Test
    @DisplayName("Operator retrieves complete audit trail with all required columns and details")
    void operatorRetrievesAuditTrailWithAllRequiredFields() {
        ResponseEntity<JsonNode> resp = http.exchange(
            "/api/v1/platform/audit?limit=50",
            HttpMethod.GET,
            new HttpEntity<>(operatorHeaders()),
            JsonNode.class
        );

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.isArray()).isTrue();
        assertThat(body.size()).isGreaterThanOrEqualTo(1);

        // Find our test audit event
        JsonNode event = null;
        for (JsonNode item : body) {
            if ("TEST_AUDIT_ACTION".equals(item.get("action").asText())) {
                event = item;
                break;
            }
        }
        assertThat(event).isNotNull();

        // Verify all 7 required SPEC columns are present:
        // timestamp, actor, action, target (clinic/user), tenant, outcome, IP, plus details/reason
        assertThat(event.get("occurredAt").asText()).isNotBlank();
        assertThat(event.get("operatorEmail").asText()).isEqualTo("operator.audit@firstelement.test");
        assertThat(event.get("operatorName").asText()).isEqualTo("Audit Operator");
        assertThat(event.get("action").asText()).isEqualTo("TEST_AUDIT_ACTION");
        assertThat(event.get("resourceType").asText()).isEqualTo("Clinic");
        assertThat(event.get("clinicName").asText()).isEqualTo("Alpha One");
        assertThat(event.get("organizationName").asText()).isEqualTo("Alpha Clinic");
        assertThat(event.get("outcome").asText()).isEqualTo("ALLOWED");
        assertThat(event.get("ipAddress").asText()).isEqualTo("192.168.1.100");
        assertThat(event.get("detail").asText()).isEqualTo("Comprehensive audit details test");
        assertThat(event.get("reason").asText()).isEqualTo("Testing audit trail details");
    }

    @Test
    @DisplayName("Operator filters audit trail by clinic, actor, action, and outcome")
    void operatorFiltersAuditTrail() {
        // Filter by matching clinic
        ResponseEntity<JsonNode> respClinic = http.exchange(
            "/api/v1/platform/audit?clinicId=" + InboxFixture.PROJECT_A1 + "&action=TEST_AUDIT_ACTION",
            HttpMethod.GET,
            new HttpEntity<>(operatorHeaders()),
            JsonNode.class
        );
        assertThat(respClinic.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode list = respClinic.getBody();
        assertThat(list).isNotNull();
        assertThat(list.size()).isGreaterThanOrEqualTo(1);
        assertThat(list.get(0).get("action").asText()).isEqualTo("TEST_AUDIT_ACTION");

        // Filter by non-matching clinic
        ResponseEntity<JsonNode> respNoMatch = http.exchange(
            "/api/v1/platform/audit?clinicId=" + InboxFixture.PROJECT_A2 + "&action=TEST_AUDIT_ACTION",
            HttpMethod.GET,
            new HttpEntity<>(operatorHeaders()),
            JsonNode.class
        );
        assertThat(respNoMatch.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(respNoMatch.getBody().size()).isEqualTo(0);

        // Filter by actor name substring
        ResponseEntity<JsonNode> respActor = http.exchange(
            "/api/v1/platform/audit?actor=audit&action=TEST_AUDIT_ACTION",
            HttpMethod.GET,
            new HttpEntity<>(operatorHeaders()),
            JsonNode.class
        );
        assertThat(respActor.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(respActor.getBody().size()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("Operator retrieves distinct action types for filter dropdown")
    void operatorRetrievesDistinctActions() {
        ResponseEntity<JsonNode> resp = http.exchange(
            "/api/v1/platform/audit/actions",
            HttpMethod.GET,
            new HttpEntity<>(operatorHeaders()),
            JsonNode.class
        );

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.isArray()).isTrue();
        assertThat(body.toString()).contains("TEST_AUDIT_ACTION");
    }
}
