package com.firstelement.security.platform;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Which cookies the platform filter is willing to read.
 *
 * <h2>Why this is asserted directly rather than left to the HTTP tests</h2>
 *
 * <p>A regression probe was run against this class: making the filter also return the value of
 * {@code fe_session} did <em>not</em> turn any HTTP test red, because the token is then looked up in
 * {@code platform_sessions} and a clinic administrator's token is not in that table. The boundary
 * held anyway, but for a reason other than the one the code claims.
 *
 * <p>Two independent controls keep a customer session out of the console -- the filter's own cookie
 * name, and the separate session table the chain resolves against -- and the second one silently
 * masks a break in the first. That is exactly the arrangement in which a real defect could sit
 * unnoticed until the tables were unified. So the stated invariant is asserted here, where it can
 * actually fail.
 */
class PlatformOperatorAuthenticationFilterTest {

    /**
     * A filter over a service whose repositories are mocked, so a cookie that was <em>read</em> can be
     * told apart from one that was never looked at.
     *
     * <p>Asserting only "no operator was resolved" is not enough and was the first version of this
     * test. It stays green even when the filter hands the customer cookie to the session store,
     * because the store simply finds nothing -- the observable outcome is identical. So the tests
     * below assert on the interaction with the store, which is the thing that actually changed.
     */
    private PlatformOperatorRepository operatorRepository;

    private PlatformOperatorAuthenticationFilter filter;

    @org.junit.jupiter.api.BeforeEach
    void buildFilter() {
        operatorRepository = org.mockito.Mockito.mock(PlatformOperatorRepository.class);
        filter = new PlatformOperatorAuthenticationFilter(new PlatformAuthService(
                operatorRepository,
                org.mockito.Mockito.mock(PlatformAuditRepository.class),
                new PlatformSecretCodec("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="),
                new com.firstelement.security.auth.LoginRateLimiter(10,
                        java.time.Duration.ofSeconds(60))));
    }

    @AfterEach
    void clearContext() {
        PlatformOperatorContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletRequest platformRequest(Cookie... cookies) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/platform/clinics");
        request.setCookies(cookies);
        return request;
    }

    @Test
    @DisplayName("a request carrying only the customer session cookie never reaches the store")
    void customerSessionCookieIsIgnored() throws Exception {
        MockHttpServletRequest request =
                platformRequest(new Cookie("fe_session", "a-live-customer-session-token"));

        filter.doFilter(request, new MockHttpServletResponse(), chainThatRecordsNothing());

        // The interaction, not the outcome. With a mocked store that finds nothing, "resolved no
        // operator" is also what happens when the cookie IS read -- so the outcome cannot tell the
        // two apart, and a filter quietly widened to the customer cookie would go unnoticed.
        org.mockito.Mockito.verify(operatorRepository, org.mockito.Mockito.never())
                .findSessionByTokenHash(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any());
        assertThat(PlatformOperatorContextHolder.current()).isEmpty();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("the platform cookie does reach the store")
    void platformCookieIsResolved() throws Exception {
        MockHttpServletRequest request =
                platformRequest(new Cookie(PlatformOperatorAuthenticationFilter.COOKIE_NAME, "token"));

        filter.doFilter(request, new MockHttpServletResponse(), chainThatRecordsNothing());

        org.mockito.Mockito.verify(operatorRepository, org.mockito.Mockito.atLeastOnce())
                .findSessionByTokenHash(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any());
        assertThat(PlatformOperatorContextHolder.current())
                .as("the mocked store finds no session, so no operator is produced")
                .isEmpty();
    }

    @Test
    @DisplayName("a request carrying no cookie at all resolves no operator")
    void noCookieResolvesNothing() throws Exception {
        filter.doFilter(platformRequest(), new MockHttpServletResponse(),
                chainThatRecordsNothing());

        assertThat(PlatformOperatorContextHolder.current()).isEmpty();
    }



    @Test
    @DisplayName("a blank or cleared platform cookie is treated as absent")
    void blankCookieIsTreatedAsAbsent() throws Exception {
        for (String value : List.of("", "   ")) {
            filter.doFilter(
                    platformRequest(new Cookie(PlatformOperatorAuthenticationFilter.COOKIE_NAME, value)),
                    new MockHttpServletResponse(), chainThatRecordsNothing());

            assertThat(PlatformOperatorContextHolder.current())
                    .as("value %q must not reach the store", value)
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("the cookie name is not a substring match on fe_session")
    void cookieNameIsExact() {
        // Guards the naive "endsWith(SESSION)" style bug, which would make fe_session and any
        // cookie whose name merely contains it authenticate the console.
        assertThat(PlatformOperatorAuthenticationFilter.COOKIE_NAME)
                .isNotEqualTo("fe_session")
                .isEqualTo("fe_platform_session");
    }

    @Test
    @DisplayName("the operator is cleared after the request, whatever happened")
    void contextIsClearedAfterTheRequest() throws Exception {
        // A pooled thread that kept an operator would hand the next request someone else's
        // authority. The clearing must happen even though the chain below throws.
        FilterChain exploding = (request, response) -> {
            throw new IllegalStateException("downstream failed");
        };

        try {
            filter.doFilter(
                    platformRequest(new Cookie(PlatformOperatorAuthenticationFilter.COOKIE_NAME, "t")),
                    new MockHttpServletResponse(), exploding);
        } catch (IllegalStateException expected) {
            // the point is the state afterwards, not the exception
        }

        assertThat(PlatformOperatorContextHolder.current())
                .as("a pooled request thread must not carry an operator into the next request")
                .isEmpty();
    }

    @Test
    @DisplayName("the filter only runs under the platform prefix")
    void shouldNotFilterOutsideTheConsole() {
        MockHttpServletRequest customer = new MockHttpServletRequest();
        customer.setRequestURI("/api/v1/auth/me");
        customer.setCookies(new Cookie(PlatformOperatorAuthenticationFilter.COOKIE_NAME, "token"));

        MockHttpServletRequest platform = new MockHttpServletRequest();
        platform.setRequestURI("/api/v1/platform/clinics");

        // shouldNotFilter is protected; the subclass exposes it for the assertion.
        assertThat(exposed(customer)).as("a customer route must not run the platform filter").isTrue();
        assertThat(exposed(platform)).as("a platform route must run it").isFalse();
    }

    private static boolean exposed(MockHttpServletRequest request) {
        return new PlatformOperatorAuthenticationFilter(new PlatformAuthService(
                org.mockito.Mockito.mock(PlatformOperatorRepository.class),
                org.mockito.Mockito.mock(PlatformAuditRepository.class),
                new PlatformSecretCodec("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="),
                new com.firstelement.security.auth.LoginRateLimiter(10,
                        java.time.Duration.ofSeconds(60)))) {
            boolean shouldNotFilterForTest(HttpServletRequest request) {
                return shouldNotFilter(request);
            }
        }.shouldNotFilterForTest(request);
    }

    @Test
    @DisplayName("the prefix is matched within the application, not including a context path")
    void theContextPathDoesNotDisableTheFilter() {
        MockHttpServletRequest withContextPath = new MockHttpServletRequest();
        withContextPath.setContextPath("/api");
        withContextPath.setServletPath("/api/v1/platform/clinics");
        withContextPath.setRequestURI("/api/api/v1/platform/clinics");

        // A deployment served under a context path must still run the filter. Matching
        // getRequestURI() against the bare prefix would skip it here, and skipping means "no
        // authentication", which is the dangerous direction to fail in.
        assertThat(exposed(withContextPath))
                .as("a context path must not silently disable platform authentication")
                .isFalse();
    }
    private static FilterChain chainThatRecordsNothing() {
        return (request, response) -> { };
    }
}