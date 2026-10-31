package com.firstelement.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The credential-stuffing surface is rate limited.
 *
 * <p>Runs in its own Spring context so the limit can be lowered to a testable value
 * without weakening — or accidentally raising — the production default of 10 attempts per
 * 60 seconds.
 *
 * <h2>What is asserted</h2>
 *
 * <ul>
 *   <li>Attempts up to the limit are answered {@code 401}; the next is {@code 429} with
 *       {@code Retry-After}.</li>
 *   <li>The refusal happens <em>before</em> the account is consulted, and flooding one
 *       unknown address does not lock a legitimate account from elsewhere.</li>
 *   <li>A refused attempt creates no session.</li>
 *   <li>A successful login clears the account bucket but not the address bucket, so a
 *       client cannot use its own success to reset a host-level spray window.</li>
 * </ul>
 *
 * <h2>Isolation between tests</h2>
 *
 * <p>Each test uses its own client address <em>and</em> the limiter is reset in
 * {@code @BeforeEach}. The account bucket deliberately has no address component (so source
 * rotation cannot reset it), which means isolating tests by address alone would leave that
 * bucket shared between them.
 */
@SpringBootTest(
    classes = IdentityAccessTestApplication.class,
    properties = {
        "firstelement.environment=TEST",
        "firstelement.api.web-origin=http://localhost:5173",
        "firstelement.security.session-cookie-secure=false",
        "firstelement.security.login-rate-limit-max-attempts=5",
        "firstelement.security.login-rate-limit-window=PT60S",
        "spring.flyway.locations=classpath:db/migration",
        "management.health.db.enabled=false",
        "management.health.redis.enabled=false",
        "management.tracing.sampling.probability=0.0"
    })
@AutoConfigureMockMvc
class LoginRateLimitIntegrationTest extends SecurityIntegrationTestBase {

    private static final String CSRF_TOKEN = "test-csrf-token";
    private static final int LIMIT = 5;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SecurityFixture fixture;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Autowired
    private com.firstelement.security.auth.LoginRateLimiter loginRateLimiter;

    @BeforeEach
    void seed() {
        fixture.reset();
        // The limiter is per-process state; without this reset, whichever test runs first
        // would consume the budget for all the others.
        loginRateLimiter.resetAll();
    }

    private RequestPostProcessor client(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            request.addHeader("X-XSRF-TOKEN", CSRF_TOKEN);
            return request;
        };
    }

    private ResultActions attempt(String ip, String email, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login")
            .cookie(new jakarta.servlet.http.Cookie("XSRF-TOKEN", CSRF_TOKEN))
            .with(client(ip))
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("email", email, "password", password))));
    }

    private long auditRowCount(String action) {
        return jdbc.queryForObject(
            "select count(*) from audit_logs where \"action\" = :action",
            new MapSqlParameterSource("action", action), Long.class);
    }

    @Test
    @DisplayName("attempts up to the limit are answered 401; the next is 429 with Retry-After")
    void theLimitIsEnforcedOverHttp() throws Exception {
        String ip = "198.51.100.1";
        for (int i = 1; i <= LIMIT; i++) {
            attempt(ip, SecurityFixture.STAFF_A_EMAIL, "wrong-password")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        }

        attempt(ip, SecurityFixture.STAFF_A_EMAIL, "wrong-password")
            .andExpect(status().isTooManyRequests())
            .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
            .andExpect(jsonPath("$.code").value("LOGIN_RATE_LIMITED"))
            .andExpect(jsonPath("$.retryable").value(true));

        assertThat(auditRowCount("User.Login.Failed"))
            .as("only the attempts that reached the credential check are recorded")
            .isEqualTo(LIMIT);
    }

    @Test
    @DisplayName("the limiter runs before the account lookup, so flooding it is not an enumeration oracle")
    void rateLimitedDoesNotRevealExistence() throws Exception {
        String flooder = "198.51.100.2";
        for (int i = 1; i <= LIMIT; i++) {
            // An unknown account is answered exactly like a wrong password, for as long as
            // the window allows.
            attempt(flooder, SecurityFixture.UNKNOWN_EMAIL, "wrong-password")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        }
        attempt(flooder, SecurityFixture.UNKNOWN_EMAIL, "wrong-password")
            .andExpect(status().isTooManyRequests());

        // And the flood did not lock the legitimate account from another address: the
        // account bucket keys on the email alone, but this address's account differs.
        attempt("198.51.100.3", SecurityFixture.STAFF_A_EMAIL, SecurityFixture.PASSWORD)
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a rate-limited attempt creates no session")
    void rateLimitedAttemptCreatesNoSession() throws Exception {
        String ip = "198.51.100.4";
        for (int i = 0; i <= LIMIT; i++) {
            attempt(ip, SecurityFixture.ADMIN_A_EMAIL, "wrong-password");
        }
        assertThat(jdbc.queryForObject("select count(*) from sessions",
            new MapSqlParameterSource(), Integer.class)).isZero();
    }

    @Test
    @DisplayName("a successful login clears the account bucket but not the address window")
    void successResetsTheAccountBucket() throws Exception {
        String ip = "198.51.100.5";
        // Two wrong passwords: address=2, account=2.
        attempt(ip, SecurityFixture.STAFF_A_EMAIL, "wrong-password").andExpect(status().isUnauthorized());
        attempt(ip, SecurityFixture.STAFF_A_EMAIL, "wrong-password").andExpect(status().isUnauthorized());

        // Success: address=3, and the account bucket is cleared.
        attempt(ip, SecurityFixture.STAFF_A_EMAIL, SecurityFixture.PASSWORD).andExpect(status().isOk());

        // The account bucket starts again from zero, so these two are answered normally
        // even though the address is already at 5.
        attempt(ip, SecurityFixture.STAFF_A_EMAIL, "wrong-password").andExpect(status().isUnauthorized());
        attempt(ip, SecurityFixture.STAFF_A_EMAIL, "wrong-password").andExpect(status().isUnauthorized());

        // The address bucket was never cleared by the success, so the sixth attempt is
        // refused: a client cannot reset a host-level spray window by authenticating once.
        attempt(ip, SecurityFixture.STAFF_A_EMAIL, "wrong-password")
            .andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("the CSRF endpoint stays public even while the account is rate limited")
    void csrfEndpointIsUnaffectedByTheLimiter() throws Exception {
        String ip = "198.51.100.6";
        for (int i = 0; i <= LIMIT; i++) {
            attempt(ip, SecurityFixture.STAFF_A_EMAIL, "wrong-password");
        }
        mvc.perform(get("/api/v1/auth/csrf").with(client(ip)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.headerName").value("X-XSRF-TOKEN"))
            .andExpect(jsonPath("$.token").isNotEmpty());
    }
}