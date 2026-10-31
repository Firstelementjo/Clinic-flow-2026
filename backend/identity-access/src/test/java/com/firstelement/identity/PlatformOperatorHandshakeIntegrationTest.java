package com.firstelement.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstelement.security.platform.PlatformAuditRepository;
import com.firstelement.security.platform.PlatformAuthService;
import com.firstelement.security.platform.PlatformCapability;
import com.firstelement.security.platform.PlatformClinicService;
import com.firstelement.security.platform.PlatformOperatorContext;
import com.firstelement.security.platform.PlatformOperatorContextHolder;
import com.firstelement.security.platform.PlatformOperatorRepository;
import com.firstelement.security.platform.PlatformSecretCodec;
import com.firstelement.security.platform.TotpVerifier;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * The platform operator handshake, against real PostgreSQL.
 *
 * <p>Lives in {@code identity-access} rather than {@code tenant-security} because that is the
 * module with a Spring test harness and a real database; the platform classes live in
 * tenant-security and are on this module's classpath.
 *
 * <p>Every test here proves a property ADR 0020 depends on. The unit tests cover the types; this
 * covers the thing that actually matters — that the three steps behave differently in the database,
 * not only in a record.
 */
@SpringBootTest(
    classes = IdentityAccessTestApplication.class,
    properties = {
        "firstelement.environment=TEST",
        "firstelement.api.web-origin=http://localhost:5173",
        "spring.flyway.locations=classpath:db/migration",
        "firstelement.platform.console.enabled=true",
        // A throwaway test key. Never used outside this harness.
        "firstelement.security.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "management.health.db.enabled=false",
        "management.health.redis.enabled=false",
        "management.tracing.sampling.probability=0.0"
    })
class PlatformOperatorHandshakeIntegrationTest extends SecurityIntegrationTestBase {

    private static final String PASSWORD = "throwaway-operator-password";

    @Autowired
    private PlatformAuthService auth;

    @Autowired
    private PlatformOperatorRepository operators;

    @Autowired
    private PlatformAuditRepository audit;

    @Autowired
    private PlatformClinicService clinics;

    @Autowired
    private PlatformSecretCodec codec;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Autowired
    private com.firstelement.security.auth.LoginRateLimiter loginRateLimiter;

    private UUID operatorId;
    private String secret;

    @BeforeEach
    void seedOperator() {
        // The platform password step is rate limited, so each test starts with a clean budget.
        // Mirrors LoginRateLimitIntegrationTest, which resets for the same reason: the limiter is
        // per-process state and every test signs in from the same address.
        loginRateLimiter.resetAll();
        operatorId = UUID.randomUUID();
        secret = TotpVerifier.generateSecret();
        jdbc.update("""
            insert into platform_operators
                ("id", "email", "displayName", "passwordHash", "totpSecretEnc", "totpEnrolledAt",
                 "isActive", "createdAt", "updatedAt")
            values
                (:id, :email, 'Operations', :hash, :secret, (current_timestamp at time zone 'utc'),
                 true, (current_timestamp at time zone 'utc'), (current_timestamp at time zone 'utc'))
            """,
            new MapSqlParameterSource()
                    .addValue("id", operatorId)
                    .addValue("email", "ops-" + operatorId + "@firstelement.test")
                    .addValue("hash", new com.firstelement.security.auth.PasswordVerifier().hash(PASSWORD))
                    .addValue("secret", codec.encrypt(secret)));

        operators.grantCapabilities(operatorId, List.of(PlatformCapability.values()));
    }

    /**

     * The handshake deliberately uses real time, because the service does. A fixed past

     * instant would expire the fifteen-minute elevation window before the service

     * got to check it, and the failure would look like an authorization defect rather than a

     * stale clock in the test.

     */

    private String codeAt(Instant when) {
        return TotpVerifier.codeFor(hexToBytes(secret), when.getEpochSecond() / 30);
    }

    /** The verifier's base32 decoding, mirrored so the test can produce a valid code. */
    private static byte[] hexToBytes(String base32) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        String clean = base32.toUpperCase(java.util.Locale.ROOT);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int bits = 0;
        int value = 0;
        for (int i = 0; i < clean.length(); i++) {
            int index = alphabet.indexOf(clean.charAt(i));
            if (index < 0) {
                throw new IllegalArgumentException("not base32");
            }
            value = (value << 5) | index;
            bits += 5;
            if (bits >= 8) {
                out.write((value >>> (bits - 8)) & 0xFF);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }

    private String email() {
        return "ops-" + operatorId + "@firstelement.test";
    }

    private PlatformAuthService.PendingLogin begin(Instant now) {
        return auth.beginLogin(email(), PASSWORD, "test-agent", "203.0.113.9", now);
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("password alone yields a session with no authority at all")
    void passwordAloneIsNotAuthority() {
        Instant now = Instant.now();
        PlatformAuthService.PendingLogin pending = begin(now);

        assertThat(auth.resolve(pending.rawToken(), now))
                .as("a session with no satisfied second factor must not resolve to an operator")
                .isEmpty();

        // It genuinely exists in the database, so the second step can bind to it.
        assertThat(operators.findSessionByTokenHash(
                PlatformAuthService.hash(pending.rawToken()), now)).isPresent();
    }

    @Test
    @DisplayName("a pending session cannot be elevated")
    void pendingSessionCannotBeElevated() {
        Instant now = Instant.now();
        PlatformAuthService.PendingLogin pending = begin(now);

        assertThatThrownBy(() -> auth.elevate(pending.sessionId(), "onboarding a clinic", codeAt(now), now))
                .isInstanceOf(PlatformAuthService.AuthenticationFailed.class);

        assertThat(audit.recent(operatorId, null, 20))
                .as("the refused elevation is recorded, not silently dropped")
                .anySatisfy(row -> assertThat(row.get("action"))
                        .isEqualTo(PlatformAuditRepository.ELEVATION_REFUSED));
    }

    @Test
    @DisplayName("a wrong second factor refuses AND kills the pending session")
    void wrongSecondFactorRevokesTheSession() {
        Instant now = Instant.now();
        PlatformAuthService.PendingLogin pending = begin(now);

        assertThatThrownBy(() -> auth.completeLogin(pending.rawToken(), "000000", now))
                .as("a wrong code is refused even when the secret would not produce it")
                .isInstanceOf(PlatformAuthService.AuthenticationFailed.class);

        assertThat(auth.resolve(pending.rawToken(), now))
                .as("the pending session must not survive a failed second factor")
                .isEmpty();

        assertThat(audit.recent(operatorId, null, 20))
                .anySatisfy(row -> assertThat(row.get("action"))
                        .isEqualTo(PlatformAuditRepository.MFA_FAILED));
    }

    @Test
    @DisplayName("after the second factor: read yes, write no")
    void secondFactorGrantsReadButNotWrite() {
        Instant now = Instant.now();
        PlatformAuthService.PendingLogin pending = begin(now);
        PlatformAuthService.Authenticated authenticated =
                auth.completeLogin(pending.rawToken(), codeAt(now), now);

        PlatformOperatorContext operator = authenticated.operator();
        assertThat(operator.permits(PlatformCapability.READ_PLATFORM_OVERVIEW, now))
                .as("surveying the platform needs no elevation window")
                .isTrue();
        assertThat(operator.permits(PlatformCapability.CREATE_CLINIC, now))
                .as("creating a clinic does")
                .isFalse();

        assertThatThrownBy(() -> clinics.createClinic(operator, UUID.randomUUID(), "X", "x",
                "a@b.co", "A", PASSWORD, "should be refused", null))
                .as("the service refuses rather than trusting the caller's own check")
                .isInstanceOf(PlatformOperatorContextHolder.PlatformAccessDenied.class);

        assertThat(audit.recent(operatorId, null, 20))
                .as("the refusal is in the audit, with the reason")
                .anySatisfy(row -> {
                    assertThat(row.get("action"))
                            .isEqualTo(PlatformAuditRepository.CAPABILITY_DENIED);
                    assertThat(row.get("outcome")).isEqualTo(PlatformAuditRepository.DENIED);
                });
    }

    @Test
    @DisplayName("elevation opens a bounded window and it closes on time")
    void elevationIsBounded() {
        Instant now = Instant.now();
        PlatformAuthService.PendingLogin pending = begin(now);
        PlatformAuthService.Authenticated authenticated =
                auth.completeLogin(pending.rawToken(), codeAt(now), now);

        assertThatThrownBy(() -> auth.elevate(authenticated.sessionId(), null, codeAt(now), now))
                .as("elevation without a reason is refused: a write with no stated purpose is "
                        + "not attributable")
                .isInstanceOf(PlatformAuthService.AuthenticationFailed.class);

        PlatformOperatorContext elevated =
                auth.elevate(authenticated.sessionId(), "onboarding clinic X", codeAt(now), now);

        assertThat(elevated.permits(PlatformCapability.CREATE_CLINIC, now)).isTrue();
        assertThat(elevated.permits(PlatformCapability.CREATE_CLINIC, now.plusSeconds(901)))
                .as("fifteen minutes, then it stops")
                .isFalse();

        assertThat(audit.recent(operatorId, null, 20))
                .anySatisfy(row -> {
                    assertThat(row.get("action")).isEqualTo(PlatformAuditRepository.ELEVATED);
                    assertThat(row.get("reason")).isEqualTo("onboarding clinic X");
                });
    }

    @Test
    @DisplayName("an unknown address and a wrong password are indistinguishable and both audited")
    void failuresLookTheSameFromOutside() {
        Instant now = Instant.now();

        assertThatThrownBy(() -> auth.beginLogin("nobody@firstelement.test", PASSWORD, "ua", "ip", now))
                .isInstanceOf(PlatformAuthService.AuthenticationFailed.class)
                .hasMessage("Authentication failed.");

        assertThatThrownBy(() -> auth.beginLogin(email(), "not-the-password", "ua", "ip", now))
                .isInstanceOf(PlatformAuthService.AuthenticationFailed.class)
                .hasMessage("Authentication failed.");

        // Both refusals are recorded, with the real reason, for the operator who owns the trail.
        assertThat(audit.recent(null, null, 50))
                .filteredOn(row -> PlatformAuditRepository.LOGIN_FAILED.equals(row.get("action")))
                .extracting(row -> row.get("reason"))
                .contains("unknown-email", "wrong-password");
    }

    @Test
    @DisplayName("the audit is append-only even for a privileged principal")
    void auditRowsCannotBeRewritten() {
        Instant now = Instant.now();
        auth.beginLogin(email(), PASSWORD, "ua", "ip", now);

        assertThatThrownBy(() -> jdbc.update(
                "update platform_audit_events set reason = 'tampered' where \"operatorId\" = :id",
                new MapSqlParameterSource("id", operatorId)))
                .as("the trigger, not the application, is the control")
                .hasMessageContaining("append-only");
    }

    @Test
    @DisplayName("the operator TOTP secret never appears in a projected view")
    void secretIsNeverProjected() {
        PlatformOperatorRepository.Operator operator = operators.findById(operatorId).orElseThrow();

        // Asserted on the record's declared components rather than on its values: a projection
        // that carried the secret would be a different type, and a test that only inspected the
        // instance would not notice the type had grown a field.
        List<String> components = java.util.Arrays.stream(
                        PlatformOperatorRepository.Operator.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();

        assertThat(components)
                .as("the console-facing projection has no secret component")
                .doesNotContain("totpSecretEnc", "passwordHash");
        assertThat(operator.toString()).doesNotContain(secret);
    }

    @Test
    @DisplayName("creating a clinic creates its first administrator in the same transaction")
    void creatingAClinicAlsoCreatesItsAdministrator() {
        Instant now = Instant.now();
        PlatformAuthService.PendingLogin pending = begin(now);
        PlatformAuthService.Authenticated authenticated =
                auth.completeLogin(pending.rawToken(), codeAt(now), now);
        PlatformOperatorContext operator =
                auth.elevate(authenticated.sessionId(), "onboarding", codeAt(now), now);

        UUID orgId = seedOrganization();
        PlatformClinicService.ClinicCreated created = clinics.createClinic(
                operator, orgId, "Smile Clinic", "smile-clinic",
                "owner@smile.test", "Clinic Owner", PASSWORD, "new paying customer", null);

        assertThat(clinics.requireProjectCount(orgId, created.projectId())).isEqualTo(1);
        assertThat(clinics.accountCountIn(orgId, created.administratorUserId())).isEqualTo(1);
        assertThat(clinics.membershipCount(created.projectId(), created.administratorUserId()))
                .as("without the membership the account exists but cannot reach its own clinic")
                .isEqualTo(1);

        assertThat(audit.recent(operatorId, null, 20))
                .extracting(row -> row.get("action"))
                .contains(PlatformAuditRepository.PROJECT_CREATED,
                        PlatformAuditRepository.ACCOUNT_CREATED);
    }

    @Test
    @DisplayName("a clinic cannot be created inside an organization that does not exist")
    void unknownOrganizationIsRefusedAndAudited() {
        Instant now = Instant.now();
        PlatformAuthService.PendingLogin pending = begin(now);
        PlatformAuthService.Authenticated authenticated =
                auth.completeLogin(pending.rawToken(), codeAt(now), now);
        PlatformOperatorContext operator =
                auth.elevate(authenticated.sessionId(), "onboarding", codeAt(now), now);

        assertThatThrownBy(() -> clinics.createClinic(operator, UUID.randomUUID(), "Ghost", "ghost",
                "a@b.co", "A", PASSWORD, "should be refused", null))
                .isInstanceOf(PlatformClinicService.UnknownOrganization.class);

        assertThat(audit.recent(operatorId, null, 20))
                .anySatisfy(row -> {
                    assertThat(row.get("action")).isEqualTo(PlatformAuditRepository.PROJECT_CREATED);
                    assertThat(row.get("outcome")).isEqualTo(PlatformAuditRepository.DENIED);
                });
    }

    private UUID seedOrganization() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            insert into organizations
                ("id", "name", "slug", "defaultLocale", "timezone", "isActive", "createdAt", "updatedAt")
            values (:id, :name, :slug, 'ar', 'Asia/Riyadh', true,
                    (current_timestamp at time zone 'utc'), (current_timestamp at time zone 'utc'))
            """,
            new MapSqlParameterSource()
                    .addValue("id", id)
                    .addValue("name", "Org " + id)
                    .addValue("slug", "org-" + id));
        return id;
    }
}