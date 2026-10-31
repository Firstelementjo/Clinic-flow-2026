package com.firstelement.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.firstelement.security.platform.PlatformAuditRepository;
import com.firstelement.security.platform.PlatformCapability;
import com.firstelement.security.platform.PlatformOperatorContext;
import com.firstelement.security.platform.SubscriptionPaymentService;
import com.firstelement.security.platform.SubscriptionStatusTransitionService;
import com.firstelement.security.session.BrowserSessionStore;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * Slice 3 Integration Tests: Subscriptions & Payments.
 *
 * <p>Specification compliance tests:
 * <ul>
 *   <li>Payment endpoint is strictly idempotent; duplicate key returns original payment without double-crediting.</li>
 *   <li>Append-only ledger: corrections are reversal entries with reason; ledger rows are never mutated or deleted.</li>
 *   <li>Reversal correctly restores paid_until and status.</li>
 *   <li>Status transition job is idempotent and correct around paid_until and grace boundaries.</li>
 *   <li>Suspended and expired clinics cannot write (403); data is intact under DEC-007.</li>
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
class PlatformSubscriptionHttpIntegrationTest extends InboxIntegrationTestBase {

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

    private SubscriptionPaymentService paymentService;
    private SubscriptionStatusTransitionService transitionService;

    private static final UUID OPERATOR_ID = UUID.fromString("00000000-0000-0000-0000-00000000e001");
    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-0000-0000-00000000e002");
    private PlatformOperatorContext elevatedOperator;

    @BeforeEach
    void setup() {
        // Clean dependent subscription tables before fixture reset touches projects
        jdbc.getJdbcTemplate().execute("delete from subscription_payments");
        jdbc.getJdbcTemplate().execute("delete from clinic_subscriptions");

        fixture.reset();
        clinic.seedClinic();

        // Seed platform operator and platform session for audit integrity
        jdbc.update("""
            insert into platform_operators ("id", "email", "displayName", "passwordHash", "totpSecretEnc", "totpEnrolledAt", "isActive", "createdAt", "updatedAt")
            values (:id, 'billing-operator@firstelement.test', 'Billing Operator', 'dummy-hash', 'dummy-totp', now(), true, now(), now())
            on conflict ("id") do nothing
            """,
            new MapSqlParameterSource("id", OPERATOR_ID));

        jdbc.update("""
            insert into platform_sessions ("id", "operatorId", "tokenHash", "mfaSatisfiedAt", "elevatedAt", "elevatedExpiresAt", "elevationReason", "createdAt", "expiresAt")
            values (:sid, :opId, 'dummy-token-hash', now(), now(), now() + interval '1 hour', 'Test elevation session', now(), now() + interval '8 hours')
            on conflict ("id") do nothing
            """,
            new MapSqlParameterSource()
                .addValue("sid", SESSION_ID)
                .addValue("opId", OPERATOR_ID));

        PlatformAuditRepository audit = new PlatformAuditRepository(jdbc);
        this.paymentService = new SubscriptionPaymentService(jdbc, audit);
        this.transitionService = new SubscriptionStatusTransitionService(jdbc, audit);

        this.elevatedOperator = new PlatformOperatorContext(
            OPERATOR_ID,
            "billing-operator@firstelement.test",
            "Billing Operator",
            SESSION_ID,
            Set.of(PlatformCapability.READ_PLATFORM_OVERVIEW, PlatformCapability.MANAGE_BILLING, PlatformCapability.CREATE_CLINIC),
            true,
            Instant.now().plus(Duration.ofHours(1))
        );

        // Seed baseline subscription for PROJECT_A1
        jdbc.update("""
            insert into "public"."clinic_subscriptions"
                ("id", "organizationId", "projectId", "planId", "status", "paidUntil", "gracePeriodDays", "version", "createdAt", "updatedAt")
            values
                (gen_random_uuid(), :org, :pid, 'a0000000-0000-0000-0000-000000000001', 'ACTIVE', now() + interval '14 days', 7, 0, now(), now())
            on conflict ("projectId") do update
                set "status" = 'ACTIVE', "paidUntil" = now() + interval '14 days', "gracePeriodDays" = 7
            """,
            new MapSqlParameterSource()
                .addValue("org", InboxFixture.ORG_A)
                .addValue("pid", InboxFixture.PROJECT_A1));
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        jdbc.getJdbcTemplate().execute("delete from subscription_payments");
        jdbc.getJdbcTemplate().execute("delete from clinic_subscriptions");
    }

    @Test
    @DisplayName("Payment creation is idempotent; duplicate key returns original payment without double-crediting")
    void paymentCreationIdempotent() {
        UUID projectId = InboxFixture.PROJECT_A1;
        String idempotencyKey = "PAY-IDEMPOTENCY-" + UUID.randomUUID();
        BigDecimal amount = new BigDecimal("120.000");

        // Initial paid_until
        Timestamp initialPaidUntilTs = jdbc.queryForObject(
            "select \"paidUntil\" from \"public\".\"clinic_subscriptions\" where \"projectId\" = :pid",
            new MapSqlParameterSource("pid", projectId),
            Timestamp.class);
        assertThat(initialPaidUntilTs).isNotNull();

        // 1. Record payment for 1 month
        SubscriptionPaymentService.RecordPaymentCommand cmd = new SubscriptionPaymentService.RecordPaymentCommand(
            idempotencyKey, amount, "JOD", "CLIQ", "CLIQ-TX-9901", 1, "Initial monthly subscription"
        );
        SubscriptionPaymentService.PaymentRecordDto p1 = paymentService.recordPayment(elevatedOperator, projectId, cmd, "corr-1");
        assertThat(p1).isNotNull();
        assertThat(p1.idempotencyKey()).isEqualTo(idempotencyKey);
        assertThat(p1.amount()).isEqualByComparingTo(new BigDecimal("120.000"));
        assertThat(p1.currency()).isEqualTo("JOD");

        Timestamp paidUntilAfterP1 = jdbc.queryForObject(
            "select \"paidUntil\" from \"public\".\"clinic_subscriptions\" where \"projectId\" = :pid",
            new MapSqlParameterSource("pid", projectId),
            Timestamp.class);

        // 2. Resubmit with the EXACT SAME idempotencyKey
        SubscriptionPaymentService.PaymentRecordDto p2 = paymentService.recordPayment(elevatedOperator, projectId, cmd, "corr-2");
        assertThat(p2).isNotNull();
        // Exact same payment id returned
        assertThat(p2.id()).isEqualTo(p1.id());

        // 3. Verify ledger count: only 1 payment exists with this key
        Integer ledgerCount = jdbc.queryForObject(
            "select count(*) from \"public\".\"subscription_payments\" where \"idempotencyKey\" = :key",
            new MapSqlParameterSource("key", idempotencyKey),
            Integer.class);
        assertThat(ledgerCount).isEqualTo(1);

        // 4. Verify paidUntil was NOT double-credited
        Timestamp paidUntilAfterP2 = jdbc.queryForObject(
            "select \"paidUntil\" from \"public\".\"clinic_subscriptions\" where \"projectId\" = :pid",
            new MapSqlParameterSource("pid", projectId),
            Timestamp.class);
        assertThat(paidUntilAfterP2).isEqualTo(paidUntilAfterP1);
    }

    @Test
    @DisplayName("Reversal restores paid_until correctly; ledger is append-only and never mutated")
    void reversalRestoresPaidUntilAndMaintainsAppendOnlyLedger() {
        UUID projectId = InboxFixture.PROJECT_A1;

        // Baseline paidUntil
        Timestamp baselinePaidUntilTs = jdbc.queryForObject(
            "select \"paidUntil\" from \"public\".\"clinic_subscriptions\" where \"projectId\" = :pid",
            new MapSqlParameterSource("pid", projectId),
            Timestamp.class);

        // 1. Record payment for 3 months
        String idempotencyKey = "PAY-REV-TEST-" + UUID.randomUUID();
        SubscriptionPaymentService.RecordPaymentCommand cmd = new SubscriptionPaymentService.RecordPaymentCommand(
            idempotencyKey, new BigDecimal("350.000"), "JOD", "BANK_TRANSFER", "BANK-SLIP-7711", 3, "Quarterly advance"
        );
        SubscriptionPaymentService.PaymentRecordDto payment = paymentService.recordPayment(elevatedOperator, projectId, cmd, "corr-rev-1");
        assertThat(payment).isNotNull();

        // 2. Count ledger rows before reversal
        int rowsBefore = jdbc.queryForObject(
            "select count(*) from \"public\".\"subscription_payments\" where \"projectId\" = :pid",
            new MapSqlParameterSource("pid", projectId),
            Integer.class);

        // 3. Reverse the payment
        String reversalReason = "Customer wire transfer bounced / voided";
        SubscriptionPaymentService.PaymentRecordDto reversal = paymentService.reversePayment(
            elevatedOperator, projectId, payment.id(), reversalReason, "corr-rev-2"
        );

        assertThat(reversal).isNotNull();
        assertThat(reversal.isReversal()).isTrue();
        assertThat(reversal.reversalOfPaymentId()).isEqualTo(payment.id());
        assertThat(reversal.amount()).isEqualByComparingTo(new BigDecimal("-350.000"));
        assertThat(reversal.reversalReason()).isEqualTo(reversalReason);

        // 4. Verify APPEND-ONLY guarantee: ledger rows increased by 1 (no UPDATE or DELETE on original)
        int rowsAfter = jdbc.queryForObject(
            "select count(*) from \"public\".\"subscription_payments\" where \"projectId\" = :pid",
            new MapSqlParameterSource("pid", projectId),
            Integer.class);
        assertThat(rowsAfter).isEqualTo(rowsBefore + 1);

        // 5. Verify original payment row was NEVER mutated
        Map<String, Object> originalRow = jdbc.queryForMap(
            "select * from \"public\".\"subscription_payments\" where \"id\" = :id",
            new MapSqlParameterSource("id", payment.id()));
        assertThat(originalRow.get("isReversal")).isEqualTo(false);
        assertThat((BigDecimal) originalRow.get("amount")).isEqualByComparingTo(new BigDecimal("350.000"));

        // 6. Verify cannot reverse already-reversed payment
        assertThatThrownBy(() -> paymentService.reversePayment(elevatedOperator, projectId, payment.id(), "duplicate reversal", "corr-rev-3"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Payment has already been reversed");

        // 7. Verify paidUntil on subscription was restored to pre-payment baseline
        Timestamp currentPaidUntilTs = jdbc.queryForObject(
            "select \"paidUntil\" from \"public\".\"clinic_subscriptions\" where \"projectId\" = :pid",
            new MapSqlParameterSource("pid", projectId),
            Timestamp.class);
        assertThat(currentPaidUntilTs).isEqualTo(baselinePaidUntilTs);
    }

    @Test
    @DisplayName("Status job is idempotent and correct around paid_until and grace boundaries")
    void statusJobIdempotentAroundPaidUntilAndGraceBoundaries() {
        UUID projectId = InboxFixture.PROJECT_A1;
        Instant now = Instant.now();

        // 1. Configure subscription: expired 2 days ago, grace period = 7 days
        Instant expiredTwoDaysAgo = now.minus(Duration.ofDays(2));
        jdbc.update("""
            update "public"."clinic_subscriptions"
               set "paidUntil" = :paidUntil,
                   "gracePeriodDays" = 7,
                   "status" = 'ACTIVE',
                   "trialEndsAt" = null,
                   "version" = "version" + 1
             where "projectId" = :pid
            """,
            new MapSqlParameterSource()
                .addValue("paidUntil", Timestamp.from(expiredTwoDaysAgo))
                .addValue("pid", projectId));

        // 2. Run transition job at 'now' -> Should move to GRACE
        SubscriptionStatusTransitionService.TransitionResult r1 =
            transitionService.runTransitionJob(now, elevatedOperator, "test-job-1");
        assertThat(r1.statusChangedCount()).isGreaterThanOrEqualTo(1);

        String status1 = jdbc.queryForObject(
            "select \"status\" from \"public\".\"clinic_subscriptions\" where \"projectId\" = :pid",
            new MapSqlParameterSource("pid", projectId),
            String.class);
        assertThat(status1).isEqualTo("GRACE");

        // 3. IDEMPOTENCY CHECK: Run transition job again at the exact same 'now'
        SubscriptionStatusTransitionService.TransitionResult r2 =
            transitionService.runTransitionJob(now, elevatedOperator, "test-job-2");
        // No status change for this clinic
        String status2 = jdbc.queryForObject(
            "select \"status\" from \"public\".\"clinic_subscriptions\" where \"projectId\" = :pid",
            new MapSqlParameterSource("pid", projectId),
            String.class);
        assertThat(status2).isEqualTo("GRACE");

        // 4. Fast forward time beyond grace period (now + 8 days > paid_until + 7 days grace)
        Instant pastGraceTime = now.plus(Duration.ofDays(8));
        SubscriptionStatusTransitionService.TransitionResult r3 =
            transitionService.runTransitionJob(pastGraceTime, elevatedOperator, "test-job-3");
        assertThat(r3.statusChangedCount()).isGreaterThanOrEqualTo(1);

        String status3 = jdbc.queryForObject(
            "select \"status\" from \"public\".\"clinic_subscriptions\" where \"projectId\" = :pid",
            new MapSqlParameterSource("pid", projectId),
            String.class);
        assertThat(status3).isEqualTo("EXPIRED");

        // 5. Verify projects table status is also synced to EXPIRED
        String projStatus = jdbc.queryForObject(
            "select \"status\" from \"public\".\"projects\" where \"id\" = :pid",
            new MapSqlParameterSource("pid", projectId),
            String.class);
        assertThat(projStatus).isEqualTo("EXPIRED");
    }

    @Test
    @DisplayName("Suspended and expired clinics cannot write; read access and data intact (DEC-007)")
    void suspendedAndExpiredClinicsBlockedFromWriting() {
        UUID projectId = InboxFixture.PROJECT_A1;
        UUID adminUserId = InboxFixture.USER_ADMIN_A;
        String sessionCookie = sessionFor(adminUserId);

        // 1. Set clinic to EXPIRED
        jdbc.update("""
            update "public"."projects"
               set "status" = 'EXPIRED',
                   "suspendedAt" = (current_timestamp at time zone 'utc')
             where "id" = :id
            """,
            new MapSqlParameterSource("id", projectId));

        // 2. Write mutation (POST patient) MUST be rejected with HTTP 403
        CsrfToken csrf = obtainCsrf();
        HttpHeaders writeHeaders = headers(sessionCookie);
        writeHeaders.add(HttpHeaders.COOKIE, csrf.cookie());
        writeHeaders.set(securityProperties.getCsrfHeaderName(), csrf.token());
        writeHeaders.set("X-Project-Id", projectId.toString());

        HttpEntity<String> createPatientReq = new HttpEntity<>(
            """
            {
                "givenName": "Locked",
                "familyName": "Patient",
                "phone": "+962799998888",
                "gender": "FEMALE",
                "dateOfBirth": "1995-05-15"
            }
            """, writeHeaders);

        ResponseEntity<JsonNode> writeResponse = http.exchange(
            "/api/v1/clinic/patients?projectId=" + projectId,
            HttpMethod.POST, createPatientReq, JsonNode.class);

        assertThat(writeResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(writeResponse.getBody().get("error").asText()).isEqualTo("CLINIC_SUSPENDED");

        // 3. Read access succeeds; medical records remain 100% intact under DEC-007
        ResponseEntity<JsonNode> readResponse = http.exchange(
            "/api/v1/customers?projectId=" + projectId,
            HttpMethod.GET, new HttpEntity<>(headers(sessionCookie)), JsonNode.class);

        assertThat(readResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(readResponse.getBody()).isNotNull();
    }

    private String sessionFor(UUID userId) {
        BrowserSessionStore.IssuedSession issued =
            sessions.issue(userId, "subscription-test", "127.0.0.1", 1);
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
