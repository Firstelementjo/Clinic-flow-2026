package com.firstelement.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstelement.security.auth.PasswordVerifier;
import com.firstelement.security.platform.PlatformCapability;
import com.firstelement.security.platform.PlatformOperatorAuthenticationFilter;
import com.firstelement.security.platform.PlatformOperatorRepository;
import com.firstelement.security.platform.PlatformSecretCodec;
import com.firstelement.security.platform.TotpVerifier;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The platform/customer credential boundary, over HTTP.
 *
 * <h2>Why this test exists and the service test does not</h2>
 *
 * <p>{@code PlatformOperatorHandshakeIntegrationTest} proves the handshake behaves correctly against
 * the database, but it calls the service directly. It therefore cannot observe whether a
 * <em>cookie</em> reaches the wrong tier, because a cookie is a property of the filter chain, not of
 * a service call.
 *
 * <p>ADR 0020 rests on a single claim: the two credential paths cannot reach each other. That claim is
 * entirely about wiring, so it has to be tested through the wiring. Each test below presents a
 * credential that is valid for one tier to the other tier and asserts it is refused.
 *
 * <p>A test that only asserted "the platform route requires authentication" would still pass if both
 * tiers shared one cookie, because a shared cookie also requires a login. These tests are written to
 * fail on a shared implementation.
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
@AutoConfigureMockMvc
class PlatformConsoleBoundaryHttpIntegrationTest extends SecurityIntegrationTestBase {

    private static final String CSRF_TOKEN = "test-csrf-token";
    private static final Cookie CSRF_COOKIE = new Cookie("XSRF-TOKEN", CSRF_TOKEN);
    private static final String OPERATOR_PASSWORD = "throwaway-operator-password";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Autowired
    private com.firstelement.security.auth.LoginRateLimiter loginRateLimiter;

    @Autowired
    private PlatformSecretCodec codec;

    @Autowired
    private PlatformOperatorRepository operators;

    @Autowired
    private SecurityFixture customerFixture;

    private UUID operatorId;
    private String operatorEmail;
    private String totpSecret;

    @BeforeEach
    void seedOperator() {
        // The customer tier needs its accounts before any boundary assertion means anything.
        customerFixture.reset();
        // The platform password step is rate limited, so each test starts with a clean budget.
        // Mirrors LoginRateLimitIntegrationTest, which resets for the same reason: the limiter is
        // per-process state and every test signs in from the same address.
        loginRateLimiter.resetAll();
        operatorId = UUID.randomUUID();
        operatorEmail = "ops-" + operatorId + "@firstelement.test";
        totpSecret = TotpVerifier.generateSecret();
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
                    .addValue("email", operatorEmail)
                    .addValue("hash", new PasswordVerifier().hash(OPERATOR_PASSWORD))
                    .addValue("secret", codec.encrypt(totpSecret)));

        // Capabilities are granted separately from the account, exactly as the console grants them.
        // An operator with no capabilities authenticates successfully and is then refused by every
        // route, which is a different failure from not being able to log in at all.
        operators.grantCapabilities(operatorId, List.of(PlatformCapability.values()));
    }

    // ------------------------------------------------------------------
    // the two directions of the boundary
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a signed-in clinic administrator cannot reach any platform route")
    void customerSessionCannotReachAPlatformRoute() throws Exception {
        Cookie customerSession = signInAsCustomer();

        // A real, valid, live customer session. The only thing wrong with this request is the tier.
        //
        // 401 rather than 403 is the stronger answer and the one the architecture produces: a
        // /api/v1/platform/** request is matched by the platform chain, which never runs the
        // customer session filter, so the cookie is never read and the request is simply
        // unauthenticated. A 403 would have meant the customer session was recognised and then
        // refused -- which is one step further into trust than the design allows.
        mvc.perform(get("/api/v1/platform/clinics").cookie(customerSession, CSRF_COOKIE).with(csrfHeader()))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("clinic"))));
    }

    @Test
    @DisplayName("a signed-in platform operator cannot reach any customer route")
    void platformSessionCannotReachACustomerRoute() throws Exception {
        Cookie platformSession = completePlatformHandshake();

        // The mirror image of the test above, with the credential swapped. If the two tiers shared
        // a cookie or a request context, this would return 200.
        mvc.perform(get("/api/v1/auth/me").cookie(platformSession, CSRF_COOKIE).with(csrfHeader()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a request carrying neither credential is refused by both tiers")
    void noCredentialIsRefusedEverywhere() throws Exception {
        mvc.perform(get("/api/v1/platform/clinics").cookie(CSRF_COOKIE).with(csrfHeader()))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/api/v1/auth/me").cookie(CSRF_COOKIE).with(csrfHeader()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("the platform cookie is not accepted under the customer cookie's name")
    void thePlatformCookieIsNotTheCustomerCookie() throws Exception {
        Cookie platformSession = completePlatformHandshake();

        // Re-labelling the same token as the customer cookie must authenticate nothing. This is the
        // failure mode of "one cookie name, two tables behind it", the shortcut this design refuses.
        mvc.perform(get("/api/v1/auth/me")
                        .cookie(new Cookie("fe_session", platformSession.getValue()), CSRF_COOKIE)
                        .with(csrfHeader()))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------
    // the handshake over HTTP
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the three steps work over HTTP, and the second is CSRF-protected")
    void theThreeStepsWorkOverHttp() throws Exception {
        MvcResult started = mvc.perform(post("/api/v1/platform/auth/password")
                        .cookie(CSRF_COOKIE)
                        .with(csrfHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", operatorEmail, "password", OPERATOR_PASSWORD))))
                .andExpect(status().isNoContent())
                .andExpect(header().stringValues(HttpHeaders.SET_COOKIE,
                        org.hamcrest.Matchers.hasItem(
                                org.hamcrest.Matchers.containsString("fe_platform_session="))))
                .andReturn();

        Cookie pending = firstCookie(started, "fe_platform_session");

        // Step two without the CSRF token is refused: a third-party page must not be able to ride
        // the cookie that step one just set. This is the reason the platform login steps were not
        // added to CSRF_EXEMPT_PATHS.
        mvc.perform(post("/api/v1/platform/auth/second-factor")
                        .cookie(pending, CSRF_COOKIE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("code", currentCode()))))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/v1/platform/auth/second-factor")
                        .cookie(pending, CSRF_COOKIE)
                        .with(csrfHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("code", currentCode()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(operatorEmail))
                .andExpect(jsonPath("$.elevated").value(false));

        mvc.perform(get("/api/v1/platform/auth/me").cookie(pending, CSRF_COOKIE).with(csrfHeader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(operatorEmail));
    }

    @Test
    @DisplayName("a wrong second factor is refused with the same shape as an unknown operator")
    void authenticationFailuresAreIndistinguishableOverHttp() throws Exception {
        Cookie pending = firstCookie(mvc.perform(post("/api/v1/platform/auth/password")
                        .cookie(CSRF_COOKIE)
                        .with(csrfHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", operatorEmail, "password", OPERATOR_PASSWORD))))
                .andExpect(status().isNoContent())
                .andReturn(), "fe_platform_session");

        mvc.perform(post("/api/v1/platform/auth/second-factor")
                        .cookie(pending, CSRF_COOKIE)
                        .with(csrfHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("code", "000000"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("PLATFORM_AUTHENTICATION_FAILED"));

        MvcResult unknown = mvc.perform(post("/api/v1/platform/auth/password")
                        .cookie(CSRF_COOKIE)
                        .with(csrfHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "email", "nobody-" + UUID.randomUUID() + "@firstelement.test",
                                "password", OPERATOR_PASSWORD))))
                .andExpect(status().isUnauthorized())
                .andReturn();

        assertThat(unknown.getResponse().getContentAsString())
                .as("an unknown operator must not be distinguishable from a wrong password")
                .doesNotContain(operatorEmail)
                .doesNotContain("unknown-email")
                .doesNotContain("wrong-password");
    }

    @Test
    @DisplayName("reading the platform needs no elevation; writing needs one")
    void writingOverHttpRequiresElevation() throws Exception {
        Cookie session = completePlatformHandshake();
        UUID organizationId = seedOrganization();

        mvc.perform(get("/api/v1/platform/clinics").cookie(session, CSRF_COOKIE).with(csrfHeader()))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/platform/clinics")
                        .cookie(session, CSRF_COOKIE)
                        .with(csrfHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(clinicRequest(organizationId, "not-elevated"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PLATFORM_ACCESS_DENIED"));

        mvc.perform(post("/api/v1/platform/auth/elevate")
                        .cookie(session, CSRF_COOKIE)
                        .with(csrfHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("reason", "onboarding a clinic", "code", currentCode()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.elevated").value(true));

        mvc.perform(post("/api/v1/platform/clinics")
                        .cookie(session, CSRF_COOKIE)
                        .with(csrfHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(clinicRequest(organizationId, "elevated"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.administratorEmail")
                        .value("owner-" + organizationId + "@smile.test"));

        // Asserted against the database, not the response body: the operation is only proven by
        // what it left behind.
        assertThat(projectCount(organizationId))
                .as("exactly one clinic, created by the elevated request")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("elevation needs a fresh second factor, so a captured cookie cannot hold write")
    void elevationRequiresAFreshSecondFactor() throws Exception {
        Cookie session = completePlatformHandshake();

        // A wrong code must not open the window, even though the session already satisfied MFA at
        // sign-in. This is the property that makes a stolen cookie worth only read access.
        mvc.perform(post("/api/v1/platform/auth/elevate")
                        .cookie(session, CSRF_COOKIE)
                        .with(csrfHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("reason", "onboarding", "code", "000000"))))
                .andExpect(status().isUnauthorized());

        // And with no code at all.
        mvc.perform(post("/api/v1/platform/auth/elevate")
                        .cookie(session, CSRF_COOKIE)
                        .with(csrfHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("reason", "onboarding"))))
                .andExpect(status().isUnauthorized());

        // The operator is still not elevated, which is the state that matters.
        mvc.perform(get("/api/v1/platform/auth/me").cookie(session, CSRF_COOKIE).with(csrfHeader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.elevated").value(false));
    }
    @Test
    @DisplayName("a clinic cannot be created for an organization that does not exist")
    void unknownOrganizationOverHttpIs404() throws Exception {
        Cookie session = completePlatformHandshake();
        mvc.perform(post("/api/v1/platform/auth/elevate")
                        .cookie(session, CSRF_COOKIE)
                        .with(csrfHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("reason", "onboarding", "code", currentCode()))))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/platform/clinics")
                        .cookie(session, CSRF_COOKIE)
                        .with(csrfHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(clinicRequest(UUID.randomUUID(), "ghost-org"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORGANIZATION_NOT_FOUND"));
    }

    @Test
    @DisplayName("the platform cookie's attributes are Secure, HttpOnly, Strict and path-scoped")
    void platformCookieAttributesAreRestrictive() throws Exception {
        MvcResult started = mvc.perform(post("/api/v1/platform/auth/password")
                        .cookie(CSRF_COOKIE)
                        .with(csrfHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", operatorEmail, "password", OPERATOR_PASSWORD))))
                .andExpect(status().isNoContent())
                .andReturn();

        String setCookie = null;
        for (String header : started.getResponse().getHeaders(HttpHeaders.SET_COOKIE)) {
            if (header.startsWith(PlatformOperatorAuthenticationFilter.COOKIE_NAME + "=")) {
                setCookie = header;
            }
        }
        assertThat(setCookie).as("the platform cookie must be issued").isNotNull();

        // Asserted on the header rather than by de-serialising the cookie, because these attributes
        // are set by the browser's honouring of them, not by anything the test client does with a
        // parsed value. This is the assertion that was missing when the cookie was found to be
        // issued without Secure in production.
        assertThat(setCookie)
                .as("HttpOnly keeps the token out of script")
                .contains("HttpOnly");
        assertThat(setCookie)
                .as("SameSite=Strict stops a cross-site page inducing a console request")
                .contains("SameSite=Strict");
        assertThat(setCookie)
                .as("scoped to the console so the token is never sent to a customer route")
                .contains("Path=/api/v1/platform");

        // Secure depends on the resolved environment. The test context is TEST, so it must be
        // absent here -- and the reason it is absent here and not in production is covered by
        // SecurityPropertiesCookieSecureTest.
        assertThat(setCookie.toLowerCase(java.util.Locale.ROOT))
                .as("TEST is not production, so Secure is correctly omitted for local HTTP")
                .doesNotContain("; secure");
    }

    @Test
    @DisplayName("the second factor with no cookie is a 401, not a 500")
    void secondFactorWithoutACookieIsUnauthorized() throws Exception {
        // SameSite=Strict means a cross-site browser withholds the cookie, so this is reachable by
        // any client that simply omits it. It used to reach hash(null), throw a NullPointerException
        // and render as a 500 with a stack trace in the log.
        mvc.perform(post("/api/v1/platform/auth/second-factor")
                        .cookie(CSRF_COOKIE)
                        .with(csrfHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("code", currentCode()))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("PLATFORM_AUTHENTICATION_FAILED"));
    }

    @Test
    @DisplayName("a CSRF-less POST creates no pending session")
    void csrflessPostCreatesNothing() throws Exception {
        long before = pendingSessionCount();

        // A CSRF refusal must terminate the request, not merely decorate the response. This asserts
        // the consequence rather than the status code, which is the distinction that matters: a
        // filter registered a second time as a plain servlet filter would answer 403 and still run
        // beginLogin behind it.
        mvc.perform(post("/api/v1/platform/auth/password")
                        .cookie(CSRF_COOKIE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", operatorEmail, "password", OPERATOR_PASSWORD))))
                .andExpect(status().isForbidden());

        assertThat(pendingSessionCount())
                .as("a refused request must leave no pending session behind")
                .isEqualTo(before);
    }

    private long pendingSessionCount() {
        Long count = jdbc.queryForObject("select count(*) from platform_sessions",
                new MapSqlParameterSource(), Long.class);
        return count == null ? 0L : count;
    }

    @Test
    @DisplayName("logging out ends the platform session immediately")
    void logoutEndsTheSession() throws Exception {
        Cookie session = completePlatformHandshake();

        mvc.perform(get("/api/v1/platform/auth/me").cookie(session, CSRF_COOKIE).with(csrfHeader()))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/platform/auth/logout").cookie(session, CSRF_COOKIE).with(csrfHeader()))
                .andExpect(status().isNoContent());

        // The same cookie value is now worthless. Asserted by replaying it, not by reading the
        // Set-Cookie on the logout response. 401 because the session no longer resolves, so the
        // request is unauthenticated rather than authenticated-and-refused.
        mvc.perform(get("/api/v1/platform/auth/me").cookie(session, CSRF_COOKIE).with(csrfHeader()))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static RequestPostProcessor csrfHeader() {
        return request -> {
            request.addHeader("X-XSRF-TOKEN", CSRF_TOKEN);
            return request;
        };
    }

    private String currentCode() {
        return TotpVerifier.codeFor(decodeBase32(totpSecret), Instant.now().getEpochSecond() / 30);
    }

    private static byte[] decodeBase32(String base32) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        String clean = base32.toUpperCase(Locale.ROOT);
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

    /** Signs in over HTTP the way a browser would, and returns the live customer cookie. */
    private Cookie signInAsCustomer() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login")
                        .cookie(CSRF_COOKIE)
                        .with(csrfHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "email", SecurityFixture.STAFF_A_EMAIL,
                                "password", SecurityFixture.PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        return firstCookie(result, "fe_session");
    }

    private Cookie completePlatformHandshake() throws Exception {
        MvcResult started = mvc.perform(post("/api/v1/platform/auth/password")
                        .cookie(CSRF_COOKIE)
                        .with(csrfHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", operatorEmail, "password", OPERATOR_PASSWORD))))
                .andExpect(status().isNoContent())
                .andReturn();
        Cookie pending = firstCookie(started, "fe_platform_session");

        mvc.perform(post("/api/v1/platform/auth/second-factor")
                        .cookie(pending, CSRF_COOKIE)
                        .with(csrfHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("code", currentCode()))))
                .andExpect(status().isOk());

        // The token is unchanged by the second step; the session behind it gains authority.
        return pending;
    }

    private static Cookie firstCookie(MvcResult result, String name) {
        List<String> setCookies = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE);
        assertThat(setCookies).as("a %s cookie must be issued", name).isNotNull();
        for (String header : setCookies) {
            if (header.startsWith(name + "=")) {
                String value = header.substring(name.length() + 1);
                int semicolon = value.indexOf(';');
                return new Cookie(name, semicolon < 0 ? value : value.substring(0, semicolon));
            }
        }
        throw new AssertionError("No " + name + " cookie in " + setCookies);
    }

    private String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    private Map<String, Object> clinicRequest(UUID organizationId, String slug) {
        return Map.of(
                "organizationId", organizationId.toString(),
                "name", "Smile " + slug,
                "slug", slug,
                "administratorEmail", "owner-" + organizationId + "@smile.test",
                "administratorName", "Clinic Owner",
                "administratorPassword", SecurityFixture.PASSWORD,
                "reason", "new paying customer");
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

    private int projectCount(UUID organizationId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from \"projects\" where \"organizationId\" = :org",
                new MapSqlParameterSource("org", organizationId), Integer.class);
        return count == null ? 0 : count;
    }
}