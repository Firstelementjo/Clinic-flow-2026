package com.firstelement.security.config;

import com.firstelement.config.FirstElementProperties;
import com.firstelement.security.SuspendedClinicEnforcementFilter;
import com.firstelement.security.TenantContextFilter;
import com.firstelement.security.auth.BrowserSessionAuthenticationFilter;
import com.firstelement.security.auth.LoginRateLimiter;
import com.firstelement.security.auth.PasswordVerifier;
import com.firstelement.security.error.SecurityErrorRenderer;
import com.firstelement.security.error.SecurityExceptionAdvice;
import com.firstelement.security.event.SecurityEventPublisher;
import com.firstelement.security.session.BrowserSessionStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AnnotationTemplateExpressionDefaults;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The Spring Security boundary: default-deny request authorization, cookie-backed
 * revocable session authentication, CSRF, CORS, secure headers, and method security.
 *
 * <h2>Design decisions and their justification</h2>
 *
 * <ol>
 *   <li><b>{@code anyRequest().authenticated()} as the catch-all.</b> Spring Security's
 *       own guidance: unannotated methods are not covered by method security, so the
 *       request rule is the backstop. A new controller is protected by default, and a
 *       forgotten permit is a failure rather than a leak.</li>
 *
 *   <li><b>{@code SessionCreationPolicy.STATELESS}.</b> The application session lives in
 *       PostgreSQL ({@code sessions}), not in a servlet {@code HttpSession}. Allowing
 *       Spring Security to create one would produce a second, JVM-local copy of the
 *       session that {@link BrowserSessionStore#revoke} could not reach — the classic
 *       reason "logout did not log out" happens after a horizontal scale-out. STATELESS
 *       also means the {@code SecurityContext} is rebuilt from the database on every
 *       request, which is what makes revocation immediate.</li>
 *
 *   <li><b>CSRF via {@link CookieCsrfTokenRepository} + a non-XOR handler.</b> This is
 *       the documented Angular pairing: the raw token is stored in a JS-readable cookie
 *       and echoed in a header. The inventory records that the legacy runtime had
 *       <em>no</em> CSRF token on cookie-authenticated mutations, so this is a
 *       strengthening, not a port.</li>
 *
 *   <li><b>Login is CSRF-protected too</b> unless explicitly disabled. A login that a
 *       third-party site can trigger is login-CSRF. {@code GET /api/v1/auth/csrf}
 *       exists so a client can obtain a token first.</li>
 *
 *   <li><b>CORS allowlist from {@code firstelement.api.web-origin}</b>, with
 *       {@code allowCredentials=true} and no wildcard. The credentialed-wildcard
 *       combination is not a thing that can be made safe.</li>
 *
 *   <li><b>Header policy is API-shaped, not HTML-shaped.</b> The API returns JSON only, so
 *       the CSP is {@code default-src 'none'; frame-ancestors 'none'; base-uri 'none'};
 *       that is strictly stronger than the API default and does not affect the SPA, which
 *       is served separately.</li>
 *
 *   <li><b>An explicit {@code AuthenticationProvider}</b> backs off Spring Boot's
 *       generated in-memory user, so no accidental {@code user}/{@code password} account
 *       exists anywhere in the runtime.</li>
 *
 *   <li><b>{@code /webhooks/**} is permitted, and separately CSRF-exempt.</b> See
 *       {@link #PUBLIC_PATHS} and {@link #CSRF_EXEMPT_PATHS}. These are the only unauthenticated
 *       write paths in the API, they exist because a provider has no FirstElement session to
 *       present, and they are guarded by provider-signature verification rather than by a session.
 *       This was a live defect, not a documentation ambiguity: with the entry absent,
 *       {@code anyRequest().authenticated()} answered {@code 401} for every inbound message, and
 *       the {@code CsrfFilter} answered {@code 403} first. Both were the filter chain; neither was
 *       the provider's signature. No HTTP test had exercised the path, so neither the outage nor
 *       the intent was proven. {@code com.firstelement.channels
 *       .WebhookAuthenticationBoundaryHttpIntegrationTest} now asserts both directions over the
 *       product's own filter chain.</li>
 * </ol>
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfiguration.class);

    /** Health and status must stay reachable by an unauthenticated probe. */
    private static final String[] PUBLIC_PATHS = {
        "/api/v1/status",
        "/api/v1/health",
        "/api/v1/auth/login",
        "/api/v1/auth/csrf",
        "/actuator/health",
        "/actuator/health/**",
        "/actuator/info",
        "/error",
        // Provider webhooks. There is no FirstElement session to present, so a session
        // requirement here rejects every inbound message from every provider.
        //
        // The control on these paths is NOT "nothing": it is provider-signature verification
        // inside ChannelWebhookController / InboundWebhookIntakeService. An unsigned, tampered or
        // wrong-secret payload is refused with the same 404 an unknown connection id gets, and is
        // never queued. The exemption removes the session requirement, not the authentication
        // requirement.
        //
        // Scoped to the webhook prefix on purpose. A broader pattern would open the API.
        "/webhooks/**",
        // NOTE: /api/v1/platform/** is deliberately absent, and an earlier draft of this file listed
        // its two login steps here. That listing is dead and was removed.
        //
        // The platform console has its own SecurityFilterChain (PlatformConsoleConfiguration) at
        // order 1, matched on this prefix, and the first matching chain wins -- so these requests
        // never reach the rules below. Permitting them here would have been a no-op that reads like
        // an authorisation decision, which is worse than no entry at all: a later reader would
        // reasonably conclude the customer chain is what lets an operator log in, and weaken the
        // platform chain on the belief that this covers it.
        //
        // When the console is disabled there is no platform chain and no handler, so these paths fall
        // to the unmapped matcher and answer the stable 404. Absent, not open.
    };

    /** Single-use clinic admin invitation redemption endpoints (token-authenticated). */
    private static final String[] INVITE_PUBLIC_PATHS = {
        "/api/v1/invites/**",
    };

    /**
     * Public Patient Intake Portal endpoints (write-only quarantine authenticated by
     * clinic QR key + 1-hour patient code + short-lived intake session token).
     */
    private static final String[] INTAKE_PUBLIC_PATHS = {
        "/api/v1/public/intake/**",
    };

    /**
     * The paths the {@code CsrfFilter} does not guard.
     *
     * <p>CSRF defends against a third-party site inducing a <em>browser</em> to attach ambient
     * cookie authority to a mutation. A provider POST carries no cookie and no session, so there is
     * nothing for CSRF to protect — and requiring a token would make the permit entry above
     * unreachable in practice: the exemption would exist and every real webhook would still be
     * refused, first as {@code 403 CSRF_TOKEN_INVALID} and then as {@code 401}.
     *
     * <p>Kept as its own narrow list, mirroring {@link #PUBLIC_PATHS}, so a webhook can never be
     * permitted without being CSRF-exempt or vice versa, and so the two stay auditable together.
     * Anything added here is a route that accepts unauthenticated writes; the webhook path is the
     * only member, and {@code WebhookAuthenticationBoundaryHttpIntegrationTest} fails if that stops
     * being true.
     */
    private static final String[] CSRF_EXEMPT_PATHS = {
        "/webhooks/**",
    };

    /** Read-only OpenAPI. Still authenticated: the contract is not public information. */
    private static final String[] AUTHENTICATED_PATHS = {
        "/api/v1/openapi",
        "/api/v1/openapi/**",
        "/api/v1/docs",
        "/api/v1/docs/**",
        "/actuator/metrics/**",
        "/actuator/info",
    };

    @Bean
    SecurityFilterChain firstElementSecurityFilterChain(HttpSecurity http,
                                                       SecurityProperties security,
                                                       FirstElementProperties app,
                                                       BrowserSessionAuthenticationFilter sessionFilter,
                                                       SecurityErrorRenderer errorRenderer,
                                                       List<RequestMappingHandlerMapping> handlerMappings)
        throws Exception {
        boolean production = app.getEnvironment() == FirstElementProperties.Environment.PRODUCTION;

        CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepository.setCookieName(security.getCsrfCookieName());
        csrfRepository.setCookiePath("/");
        csrfRepository.setCookieHttpOnly(false);
        csrfRepository.setCookieCustomizer(cookie -> cookie
            .sameSite(security.getSessionCookieSameSite().header())
            .secure(cookieSecure(security, production)));

        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
        // BREACH-style defence is skipped on purpose: Angular copies the cookie value
        // verbatim into the header, so an XOR'd token would not match.
        csrfHandler.setCsrfRequestAttributeName(null);

        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource(app, security)))
            .csrf(csrf -> {
                csrf.csrfTokenRepository(csrfRepository)
                    .csrfTokenRequestHandler(csrfHandler);
                // Provider webhooks are exempt because they carry no ambient authority; see
                // CSRF_EXEMPT_PATHS. This is not a relaxation: the CsrfFilter would otherwise
                // reject every real provider POST before the signature verifier ever ran.
                csrf.ignoringRequestMatchers(CSRF_EXEMPT_PATHS);
                csrf.ignoringRequestMatchers(INVITE_PUBLIC_PATHS);
                csrf.ignoringRequestMatchers(INTAKE_PUBLIC_PATHS);
                if (!security.isCsrfRequiredOnLogin()) {
                    log.warn("firstelement.security.csrf-required-on-login=false: login is no longer "
                        + "CSRF-protected and login-CSRF becomes possible.");
                    csrf.ignoringRequestMatchers("/api/v1/auth/login");
                }
            })
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                .sessionFixation(fixation -> fixation.none()))
            .requestCache(cache -> cache.disable())
            .httpBasic(basic -> basic.disable())
            .formLogin(form -> form.disable())
            .logout(logout -> logout.disable())
            .anonymous(anonymous -> anonymous.disable())
            .headers(headers -> headers
                .contentSecurityPolicy(csp -> csp.policyDirectives(
                    "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'"))
                .frameOptions(frame -> frame.deny())
                .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.NO_REFERRER))
                .permissionsPolicyHeader(policy -> policy.policy(
                    "geolocation=(), camera=(), microphone=(), payment=(), usb=(), "
                        + "accelerometer=(), magnetometer=(), gyroscope=()"))
                .httpStrictTransportSecurity(hsts -> hsts
                    .includeSubDomains(true)
                    .maxAgeInSeconds(31_536_000))
                .cacheControl(cache -> {})
            )
            .exceptionHandling(handling -> handling
                .authenticationEntryPoint(errorRenderer)
                .accessDeniedHandler(errorRenderer))
            .authorizeHttpRequests(requests -> requests
                .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR, jakarta.servlet.DispatcherType.ASYNC).permitAll()
                .requestMatchers(PUBLIC_PATHS).permitAll()
                .requestMatchers(INVITE_PUBLIC_PATHS).permitAll()
                .requestMatchers(INTAKE_PUBLIC_PATHS).permitAll()
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers(AUTHENTICATED_PATHS).authenticated()
                // A path no controller declares cannot succeed for anyone, so it answers
                // with the stable 404 contract instead of an authentication challenge.
                // Fail-closed when the mapping table is unavailable.
                .requestMatchers(unmappedRequestMatcher(handlerMappings)).permitAll()
                .anyRequest().authenticated())
            .addFilterAfter(sessionFilter, AnonymousAuthenticationFilter.class);

        // The platform console is NOT wired into this chain. It has its own chain in
        // PlatformConsoleConfiguration, ahead of this one, matched on /api/v1/platform/**. Keeping it
        // out of here is what makes the two credential paths unable to reach each other: a request
        // under the platform prefix never reaches this chain at all, so the customer session cookie
        // is never read on a platform route, and a platform cookie is never looked for on a
        // customer route.
        return http.build();
    }

    /**
     * The unmapped-path matcher, built lazily from the MVC handler mapping so the
     * security configuration does not force the MVC infrastructure to initialise first.
     */
    private RequestMatcher unmappedRequestMatcher(
        List<RequestMappingHandlerMapping> handlerMappings) {
        return request -> new UnmappedRequestMatcher(handlerMappings).matches(request);
    }

    @Bean
    BrowserSessionAuthenticationFilter browserSessionAuthenticationFilter(BrowserSessionStore sessions,
                                                                         SecurityEventPublisher events,
                                                                         SecurityProperties security) {
        return new BrowserSessionAuthenticationFilter(sessions, events,
            security.getSessionTouchIntervalSeconds());
    }

    /**
     * Binds the resolved context for the request. Declared as a {@code Filter} bean so
     * Spring Boot registers it in the servlet chain; its
     * {@code @Order(SecurityProperties.DEFAULT_FILTER_ORDER + 1)} places it directly after
     * {@code SecurityFilterChain}.
     */
    @Bean
    TenantContextFilter tenantContextFilter() {
        return new TenantContextFilter();
    }

    @Bean
    SuspendedClinicEnforcementFilter suspendedClinicEnforcementFilter(NamedParameterJdbcTemplate jdbc,
                                                                      ObjectMapper objectMapper) {
        return new SuspendedClinicEnforcementFilter(jdbc, objectMapper);
    }

    @Bean
    SecurityErrorRenderer securityErrorRenderer(ObjectMapper objectMapper,
                                                 SecurityEventPublisher events) {
        return new SecurityErrorRenderer(objectMapper, events);
    }

    @Bean
    SecurityExceptionAdvice securityExceptionAdvice(SecurityEventPublisher events) {
        return new SecurityExceptionAdvice(events);
    }

    /**
     * Suppresses Spring Boot's generated in-memory user. FirstElement's only credential
     * source is the {@code users} table, so a generated {@code user}/{@code password}
     * account must not exist anywhere in the runtime.
     */
    @Bean
    @ConditionalOnMissingBean(AuthenticationProvider.class)
    AuthenticationProvider noDefaultUserAuthenticationProvider(PasswordVerifier verifier) {
        log.info("No AuthenticationProvider is configured for form/basic login; the browser "
            + "authenticates through the revocable server-side session filter only.");
        return new AuthenticationProvider() {
            @Override
            public Authentication authenticate(Authentication authentication) {
                throw new BadCredentialsException(
                    "Username/password authentication is not supported; use POST /api/v1/auth/login");
            }

            @Override
            public boolean supports(Class<?> authentication) {
                return false;
            }
        };
    }

    @Bean
    LoginRateLimiter loginRateLimiter(SecurityProperties security) {
        return new LoginRateLimiter(security.getLoginRateLimitMaxAttempts(),
            security.getLoginRateLimitWindow());
    }

    @Bean
    PasswordVerifier passwordVerifier() {
        return new PasswordVerifier();
    }

    /**
     * Enables {@code {value}} templating in the {@code @PreAuthorize} meta-annotations
     * ({@code @RequirePermission}, {@code @RequireAnyPermission}). Without this bean,
     * those annotations would be evaluated literally and would deny everything.
     */
    @Bean
    static AnnotationTemplateExpressionDefaults annotationTemplateExpressionDefaults() {
        return new AnnotationTemplateExpressionDefaults();
    }

    // ------------------------------------------------------------------
    // CORS
    // ------------------------------------------------------------------

    @Bean
    CorsConfigurationSource corsConfigurationSource(FirstElementProperties app,
                                                    SecurityProperties security) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins(app, security));
        configuration.setAllowedMethods(Arrays.asList(
            "GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD"));
        Set<String> headers = new LinkedHashSet<>(Arrays.asList(
            HttpHeaders.CONTENT_TYPE,
            HttpHeaders.ACCEPT,
            HttpHeaders.AUTHORIZATION,
            "X-Correlation-Id",
            "X-Requested-With",
            security.getCsrfHeaderName(),
            "Idempotency-Key",
            "If-Match",
            "If-None-Match"));
        configuration.setAllowedHeaders(new ArrayList<>(headers));
        // Read back by the SPA; needed for RFC 4180 CSV downloads.
        configuration.setExposedHeaders(new ArrayList<>(Arrays.asList(
            "X-Correlation-Id", "Content-Disposition", "ETag")));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(600L);
        // A cross-origin request must never be answered from a preflight cache with a
        // decision made for a different origin.
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();

        // Public Patient Intake Portal runs on a standalone mobile-first web origin.
        // allowCredentials=false guarantees no ambient browser cookies can ever be sent here.
        CorsConfiguration publicIntakeCors = new CorsConfiguration();
        publicIntakeCors.setAllowedOriginPatterns(List.of("*"));
        publicIntakeCors.setAllowedMethods(Arrays.asList("GET", "POST", "OPTIONS", "HEAD"));
        publicIntakeCors.setAllowedHeaders(List.of("*"));
        publicIntakeCors.setAllowCredentials(false);
        publicIntakeCors.setMaxAge(600L);
        source.registerCorsConfiguration("/api/v1/public/intake/**", publicIntakeCors);

        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    /**
     * The allowlist. {@code firstelement.api.web-origin} accepts a comma-separated list so
     * staging can name more than one origin without a second property. An entry of
     * {@code "*"} is rejected rather than honoured, because
     * {@code allowCredentials=true} plus a wildcard is an unauthenticated API on the
     * internet.
     */
    private List<String> allowedOrigins(FirstElementProperties app, SecurityProperties security) {
        Set<String> origins = new LinkedHashSet<>();
        for (String configured : app.getApi().getWebOrigin().split(",")) {
            String origin = configured.trim();
            if (!origin.isEmpty()) {
                origins.add(origin);
            }
        }
        for (String extra : security.getAdditionalAllowedOrigins()) {
            String origin = extra == null ? "" : extra.trim();
            if (!origin.isEmpty()) {
                origins.add(origin);
            }
        }
        if (origins.isEmpty()) {
            throw new IllegalStateException(
                "firstelement.api.web-origin must name at least one origin; refusing to start with an "
                    + "empty CORS allowlist.");
        }
        for (String origin : origins) {
            if ("*".equals(origin)) {
                throw new IllegalStateException(
                    "firstelement.api.web-origin must not be '*'. A wildcard origin cannot be combined "
                        + "with credentialed requests; name the SPA origins explicitly.");
            }
        }
        return new ArrayList<>(origins);
    }

    /**
     * Delegates to {@link SecurityProperties#resolvedSessionCookieSecure(boolean)}.
     *
     * <p>Kept as a private method only so the call sites in this class read the same. The decision
     * itself lives on {@code SecurityProperties} because the platform cookie needs it too, and when
     * it was duplicated the two copies silently disagreed -- the platform copy treated an unset
     * property as {@code false}, this one treats it as {@code production}.
     */
    private boolean cookieSecure(SecurityProperties security, boolean production) {
        return security.resolvedSessionCookieSecure(production);
    }
}