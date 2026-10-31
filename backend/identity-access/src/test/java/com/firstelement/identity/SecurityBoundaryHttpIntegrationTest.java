package com.firstelement.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
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
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The HTTP surface of the security boundary, against real PostgreSQL.
 *
 * <h2>What this proves</h2>
 *
 * <ul>
 *   <li><b>Cookie contract</b> — {@code fe_session}, HttpOnly, SameSite, path {@code /},
 *       and the raw token never in a body.</li>
 *   <li><b>Default deny</b> — every endpoint answers 401 without a live session, and an
 *       unmapped path answers the stable 404.</li>
 *   <li><b>CSRF</b> — a cookie-authenticated mutation without a token is refused; with one
 *       it succeeds. Login is covered too (login-CSRF).</li>
 *   <li><b>CORS</b> — an origin outside the allowlist is refused; the allowlisted origin
 *       gets credentialed CORS.</li>
 *   <li><b>Secure headers</b> — CSP, frame options, referrer policy, HSTS.</li>
 *   <li><b>Immediate revocation</b> — logout kills the session for the very next request.</li>
 *   <li><b>Non-disclosing denial</b> — a cross-tenant user id and a nonexistent one produce
 *       byte-identical responses.</li>
 *   <li><b>Method security</b> — the inspector requires {@code PERMISSION_INSPECTOR_READ},
 *       so an EMPLOYEE is refused with 403 even though it holds the request permission.</li>
 * </ul>
 */
@SpringBootTest(
    classes = IdentityAccessTestApplication.class,
    properties = {
        "firstelement.environment=TEST",
        "firstelement.api.web-origin=http://localhost:5173",
        "firstelement.security.session-cookie-secure=false",
        "spring.flyway.locations=classpath:db/migration",
        "management.health.db.enabled=false",
        "management.health.redis.enabled=false",
        "management.tracing.sampling.probability=0.0"
    })
@AutoConfigureMockMvc
class SecurityBoundaryHttpIntegrationTest extends SecurityIntegrationTestBase {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SecurityFixture fixture;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    private static final String CSRF_TOKEN = "test-csrf-token";
    private static final String CSRF_COOKIE = "XSRF-TOKEN";
    private static final String CSRF_HEADER = "X-XSRF-TOKEN";

    /**
     * A distinct client address per test.
     *
     * <p>The login rate limiter keys on the remote address and this class performs more
     * logins than the production limit of 10 per 60 s. Giving each test its own address
     * keeps the limiter exercised in its own class instead of incidentally tripping it
     * here.
     */
    private static final java.util.concurrent.atomic.AtomicInteger CLIENT = new java.util.concurrent.atomic.AtomicInteger();

    private static org.springframework.test.web.servlet.request.RequestPostProcessor client() {
        String ip = "198.51.100." + CLIENT.incrementAndGet();
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    @BeforeEach
    void seed() {
        fixture.reset();
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** A login that succeeds and yields the {@code fe_session} cookie. */
    private Cookie sessionCookieFor(String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login")
                .with(client())
                .cookie(csrfCookie())
                .with(withCsrfHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "email", email, "password", SecurityFixture.PASSWORD))))
            .andExpect(status().isOk())
            .andReturn();
        return sessionCookieFrom(result);
    }

    private Cookie sessionCookieFrom(MvcResult result) {
        var cookie = result.getResponse().getCookie("fe_session");
        assertThat(cookie).as("login must set the session cookie").isNotNull();
        return cookie;
    }

    private Cookie csrfCookie() {
        return new Cookie(CSRF_COOKIE, CSRF_TOKEN);
    }

    /** MockMvc has no cookie jar, so the token is supplied as the header the SPA would send. */
    private org.springframework.test.web.servlet.request.RequestPostProcessor withCsrfHeader() {
        return request -> {
            request.addHeader(CSRF_HEADER, CSRF_TOKEN);
            return request;
        };
    }

    // ------------------------------------------------------------------
    // login / cookie contract
    // ------------------------------------------------------------------

    @Test
    @DisplayName("login sets an HttpOnly SameSite cookie and never returns the token in a body")
    void loginSetsASecureCookie() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login")
                .with(client())
                .cookie(csrfCookie())
                .with(withCsrfHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "email", SecurityFixture.STAFF_A_EMAIL, "password", SecurityFixture.PASSWORD))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.userId").value(fixture.staffA.toString()))
            .andExpect(jsonPath("$.organizationId").value(fixture.orgA.toString()))
            .andExpect(jsonPath("$.role").value("EMPLOYEE"))
            .andExpect(jsonPath("$.projectIds[0]").value(fixture.projectA1.toString()))
            .andExpect(jsonPath("$.csrfHeaderName").value(CSRF_HEADER))
            .andReturn();

        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).contains("fe_session=");
        assertThat(setCookie).contains("HttpOnly");
        assertThat(setCookie).contains("SameSite=Lax");
        assertThat(setCookie).contains("Path=/");

        // The raw token appears only in the Set-Cookie header.
        String body = result.getResponse().getContentAsString();
        String token = sessionCookieFrom(result).getValue();
        assertThat(body).doesNotContain(token);
    }

    @Test
    @DisplayName("a wrong password and an unknown email are indistinguishable")
    void failuresAreIndistinguishable() throws Exception {
        String wrongPassword = mvc.perform(post("/api/v1/auth/login")
                .with(client()).cookie(csrfCookie()).with(withCsrfHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "email", SecurityFixture.STAFF_A_EMAIL, "password", "not-the-password"))))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
            .andReturn().getResponse().getContentAsString();

        String unknownEmail = mvc.perform(post("/api/v1/auth/login")
                .with(client()).cookie(csrfCookie()).with(withCsrfHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "email", SecurityFixture.UNKNOWN_EMAIL, "password", "not-the-password"))))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
            .andReturn().getResponse().getContentAsString();

        assertThat(stripRequestId(unknownEmail))
            .as("no user-enumeration oracle: the two bodies must match")
            .isEqualTo(stripRequestId(wrongPassword));
        assertThat(wrongPassword).doesNotContain(SecurityFixture.STAFF_A_EMAIL)
            .doesNotContain(SecurityFixture.UNKNOWN_EMAIL);
    }

    @Test
    @DisplayName("a session cookie authenticates, and a garbage cookie does not")
    void cookieAuthenticatesAndGarbageDoesNot() throws Exception {
        Cookie session = sessionCookieFor(SecurityFixture.STAFF_A_EMAIL);

        mvc.perform(get("/api/v1/auth/me").cookie(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.userId").value(fixture.staffA.toString()));

        mvc.perform(get("/api/v1/auth/me").cookie(new Cookie("fe_session", "not-a-real-token")))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    // ------------------------------------------------------------------
    // default deny
    // ------------------------------------------------------------------

    @Test
    @DisplayName("protected reads answer 401 without a session; protected writes answer 403 CSRF")
    void protectedEndpointsAreDefaultDeny() throws Exception {
        for (String path : new String[]{"/api/v1/auth/me", "/api/v1/permissions/me"}) {
            mvc.perform(get(path))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        }
        // A state-changing request is refused by CSRF before authorization runs: there is
        // no ambient credential for an attacker to ride, so CSRF is the correct first
        // answer and revealing nothing about authentication is the safer one.
        for (String[] call : new String[][]{
            {"/api/v1/auth/logout", "POST"},
            {"/api/v1/admin/identity/sessions/" + UUID.randomUUID(), "DELETE"},
            {"/api/v1/admin/identity/users/" + UUID.randomUUID(), "DELETE"}}) {
            var request = "POST".equals(call[1])
                ? mvc.perform(post(call[0]))
                : mvc.perform(delete(call[0]));
            request.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));
        }
    }

    @Test
    @DisplayName("the CSRF endpoint and login stay public; everything else is authenticated")
    void publicAndAuthenticatedPaths() throws Exception {
        mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk())
            .andExpect(jsonPath("$.headerName").value(CSRF_HEADER));

        // /api/v1/openapi and /api/v1/docs are required to be authenticated by the security
        // configuration; they are registered by the application module, so this module's
        // test context has no handler for them. Their behaviour is asserted in
        // docs/migration/phases/phase-03.md against a real boot of the API.
    }

    @Test
    @DisplayName("an unmapped path answers 404, not an authentication challenge")
    void unmappedPathAnswersNotFound() throws Exception {
        // The body shape (`code: NOT_FOUND`, `requestId`) is produced by the application
        // module's ErrorHandlingAdvice and is asserted by ApiApplicationSmokeTest; this
        // test owns the security decision, which is that the request is not challenged.
        mvc.perform(get("/api/v1/does-not-exist"))
            .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------
    // CSRF
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a cookie-authenticated mutation without a CSRF token is refused")
    void mutationWithoutCsrfIsRefused() throws Exception {
        Cookie session = sessionCookieFor(SecurityFixture.STAFF_A_EMAIL);

        mvc.perform(post("/api/v1/auth/logout").cookie(session))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));
    }

    @Test
    @DisplayName("login itself is CSRF-protected, which is what prevents login-CSRF")
    void loginIsCsrfProtected() throws Exception {
        mvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "email", SecurityFixture.STAFF_A_EMAIL,
                    "password", SecurityFixture.PASSWORD))))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));

        // No session was created.
        assertThat(jdbc.queryForObject("select count(*) from sessions",
            new MapSqlParameterSource(), Integer.class)).isZero();
    }

    @Test
    @DisplayName("with a CSRF token the mutation succeeds")
    void mutationWithCsrfSucceeds() throws Exception {
        Cookie session = sessionCookieFor(SecurityFixture.STAFF_A_EMAIL);

        mvc.perform(post("/api/v1/auth/logout")
                .cookie(session, csrfCookie())
                .with(withCsrfHeader()))
            .andExpect(status().isNoContent());
    }

    // ------------------------------------------------------------------
    // CORS
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a preflight from the allowlisted origin is granted with credentials")
    void allowlistedOriginIsGranted() throws Exception {
        mvc.perform(options("/api/v1/auth/login")
                .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,
                "http://localhost:5173"))
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
    }

    @Test
    @DisplayName("a preflight from any other origin is refused")
    void otherOriginsAreRefused() throws Exception {
        mvc.perform(options("/api/v1/auth/login")
                .header(HttpHeaders.ORIGIN, "https://evil.example")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
            .andExpect(status().isForbidden());

        mvc.perform(get("/api/v1/auth/csrf")
                .header(HttpHeaders.ORIGIN, "https://evil.example"))
            .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    // ------------------------------------------------------------------
    // secure headers
    // ------------------------------------------------------------------

    @Test
    @DisplayName("HSTS is sent only over TLS, and the rest of the header policy is unconditional")
    void secureHeadersArePresent() throws Exception {
        // /api/v1/auth/csrf rather than /api/v1/status: the status controller lives in the
        // application module, and the header policy must hold on every path regardless.
        MvcResult result = mvc.perform(get("/api/v1/auth/csrf").secure(true))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Frame-Options", "DENY"))
            .andExpect(header().string("Referrer-Policy", "no-referrer"))
            .andExpect(header().string("Strict-Transport-Security",
                "max-age=31536000 ; includeSubDomains"))
            .andReturn();

        String csp = result.getResponse().getHeader("Content-Security-Policy");
        assertThat(csp).contains("default-src 'none'").contains("frame-ancestors 'none'");
        assertThat(result.getResponse().getHeader("Permissions-Policy"))
            .contains("geolocation=()").contains("camera=()");
        assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-cache");
    }

    // ------------------------------------------------------------------
    // immediate revocation
    // ------------------------------------------------------------------

    @Test
    @DisplayName("logout revokes the session for the very next request and clears the cookie")
    void logoutRevokesImmediately() throws Exception {
        Cookie session = sessionCookieFor(SecurityFixture.STAFF_A_EMAIL);

        mvc.perform(get("/api/v1/auth/me").cookie(session)).andExpect(status().isOk());

        MvcResult result = mvc.perform(post("/api/v1/auth/logout")
                .cookie(session, csrfCookie()).with(withCsrfHeader()))
            .andExpect(status().isNoContent())
            .andReturn();

        java.util.List<String> setCookies = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE);
        assertThat(setCookies).anySatisfy(header -> assertThat(header)
            .as("the session cookie is explicitly cleared, not merely left to expire")
            .contains("fe_session=").contains("Max-Age=0"));

        mvc.perform(get("/api/v1/auth/me").cookie(session))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    @DisplayName("a session whose user was deactivated by an administrator dies immediately")
    void administratorRevocationIsImmediate() throws Exception {
        Cookie adminSession = sessionCookieFor(SecurityFixture.ADMIN_A_EMAIL);
        Cookie staffSession = sessionCookieFor(SecurityFixture.STAFF_A_EMAIL);

        mvc.perform(get("/api/v1/auth/me").cookie(staffSession)).andExpect(status().isOk());

        mvc.perform(delete("/api/v1/admin/identity/users/" + fixture.staffA)
                .cookie(adminSession, csrfCookie()).with(withCsrfHeader()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sessionsRevoked").value(org.hamcrest.Matchers.greaterThan(0)));

        mvc.perform(get("/api/v1/auth/me").cookie(staffSession))
            .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------
    // non-disclosing denial + method security
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a cross-tenant user id and a nonexistent one produce identical 404 bodies")
    void crossTenantInspectionIsIndistinguishable() throws Exception {
        Cookie adminA = sessionCookieFor(SecurityFixture.ADMIN_A_EMAIL);

        String foreign = mvc.perform(get("/api/v1/permissions/" + fixture.adminB).cookie(adminA))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"))
            .andReturn().getResponse().getContentAsString();

        String missing = mvc.perform(get("/api/v1/permissions/" + UUID.randomUUID()).cookie(adminA))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"))
            .andReturn().getResponse().getContentAsString();

        assertThat(stripRequestId(foreign))
            .as("existence of another tenant's user must not be observable")
            .isEqualTo(stripRequestId(missing));
        assertThat(foreign).doesNotContain(fixture.adminB.toString())
            .doesNotContain("user-out-of-scope");
    }

    @Test
    @DisplayName("method security refuses an EMPLOYEE the Admin-only inspector")
    void methodSecurityRefusesEmployee() throws Exception {
        Cookie staff = sessionCookieFor(SecurityFixture.STAFF_A_EMAIL);

        // A permission/role denial is a 403, distinct from an object-scope denial's 404.
        mvc.perform(get("/api/v1/permissions/" + fixture.adminA).cookie(staff))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // The employee's own scope is available with only a live session.
        mvc.perform(get("/api/v1/permissions/me").cookie(staff))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.role").value("EMPLOYEE"))
            .andExpect(jsonPath("$.effectivePermissions").isArray());
    }

    @Test
    @DisplayName("an ADMIN can read a member of their own tenant, and the payload has no hash")
    void adminInspectsAMember() throws Exception {
        Cookie adminA = sessionCookieFor(SecurityFixture.ADMIN_A_EMAIL);

        String body = mvc.perform(get("/api/v1/permissions/" + fixture.staffA).cookie(adminA))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.role").value("EMPLOYEE"))
            .andExpect(jsonPath("$.effectivePermissions").isArray())
            .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("$2a$").doesNotContain("passwordHash");
    }

    @Test
    @DisplayName("a role change through the API revokes the target's sessions")
    void roleChangeThroughTheApiRevokesSessions() throws Exception {
        Cookie adminA = sessionCookieFor(SecurityFixture.ADMIN_A_EMAIL);
        Cookie staff = sessionCookieFor(SecurityFixture.STAFF_A_EMAIL);

        mvc.perform(patch("/api/v1/admin/identity/users/" + fixture.staffA + "/role")
                .cookie(adminA, csrfCookie()).with(withCsrfHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"ADMIN\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sessionsRevoked").value(org.hamcrest.Matchers.greaterThan(0)));

        mvc.perform(get("/api/v1/auth/me").cookie(staff)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("an ADMIN cannot demote themselves through the API")
    void selfDemotionIsRefusedOverHttp() throws Exception {
        Cookie adminA = sessionCookieFor(SecurityFixture.ADMIN_A_EMAIL);

        mvc.perform(patch("/api/v1/admin/identity/users/" + fixture.adminA + "/role")
                .cookie(adminA, csrfCookie()).with(withCsrfHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"EMPLOYEE\"}"))
            .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------
    // audit
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a login, a denial, and a logout are all visible in audit_logs")
    void securityDecisionsAreAuditable() throws Exception {
        Cookie adminA = sessionCookieFor(SecurityFixture.ADMIN_A_EMAIL);
        mvc.perform(get("/api/v1/permissions/" + fixture.adminB).cookie(adminA))
            .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/auth/logout")
                .cookie(adminA, csrfCookie()).with(withCsrfHeader()))
            .andExpect(status().isNoContent());

        var actions = jdbc.queryForList(
            "select \"action\" from audit_logs where \"organizationId\" = :org order by \"createdAt\"",
            new MapSqlParameterSource("org", fixture.orgA), String.class);

        assertThat(actions).contains("User.Login.Succeeded", "Authorization.Denied", "User.Logout");
    }

    /** Correlation ids and timestamps differ per request, so they are normalised before comparing. */
    private static String stripRequestId(String body) {
        return body.replaceAll("\"requestId\":(\"[^\"]*\"|null)", "\"requestId\":\"*\"")
            .replaceAll("\"timestamp\":\"[^\"]*\"", "\"timestamp\":\"*\"");
    }
}