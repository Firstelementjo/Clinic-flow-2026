package com.firstelement.security.config;

import com.firstelement.security.error.SecurityErrorRenderer;
import com.firstelement.security.auth.LoginRateLimiter;
import com.firstelement.security.platform.InviteTokenService;
import com.firstelement.security.platform.PlatformAuditRepository;
import com.firstelement.security.platform.PlatformAuthService;
import com.firstelement.security.platform.PlatformClinicService;
import com.firstelement.security.platform.PlatformOperatorAuthenticationFilter;
import com.firstelement.security.platform.PlatformOperatorRepository;
import com.firstelement.security.platform.PlatformSecretCodec;
import com.firstelement.security.platform.web.PlatformConsoleController;
import com.firstelement.security.platform.web.PlatformExceptionAdvice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * The platform console's beans, present only when the console is switched on.
 *
 * <h2>Why opt-in rather than always-on</h2>
 *
 * <p>Every bean here needs a {@link NamedParameterJdbcTemplate}, and one needs an encryption key.
 * Making them unconditional would put two unrelated requirements on every application context in the
 * repository: a database, and a configured key. Several contexts legitimately have neither -- the
 * {@code app-bootstrap} smoke test exists specifically to prove the API can assemble without a live
 * database -- so unconditional beans would have made this tier break unrelated modules. That is not a
 * cost worth paying for a console that is, by DEC-011, used by a small named internal team.
 *
 * <p>The switch follows the precedent already in this module: {@code OAuth2ResourceServerConfiguration}
 * gates an optional credential path the same way.
 * <h2>Off is the safe direction</h2>
 *
 * <p>When the console is disabled, {@code /api/v1/platform/**} has no handler. A request to it falls
 * through to the unmapped-path matcher and gets the stable 404 contract. So the failure mode of
 * forgetting the flag is a console that is <em>absent</em>, not one that is open -- which is the only
 * direction an unconfigured cross-tenant subsystem is allowed to fail in.
 *
 * <p>Turning it on is a real commitment
 *
 * <p>Enabling this also requires {@code firstelement.security.encryption-key}, because the operator's
 * TOTP shared secret must be decryptable to verify a second factor and cannot be hashed. The codec
 * throws on a missing or malformed key rather than falling back to plaintext, so a deployment that
 * enables the console without a key fails loudly at startup instead of quietly storing secrets under
 * a published value.
 *
 * <h2>None of the console classes may carry a stereotype annotation</h2>
 *
 * <p>This is the trap that makes the switch above silently inert. {@code PlatformAuthService},
 * {@code PlatformClinicService}, the two repositories, the controller and the advice originally carried
 * {@code @Service}, {@code @Repository}, {@code @RestController} and {@code @RestControllerAdvice}.
 * Component scanning creates beans independently of {@code @ConditionalOnProperty}, so the console was
 * fully present in every context in the repository while the switch read as if it were off. The
 * condition was not failing loudly; it was being ignored.
 *
 * <p>So the rule is: these types are plain classes, and this file is the only thing that instantiates
 * them. {@code PlatformConsoleDisabledTest} fails if a stereotype is added back.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "firstelement.platform.console", name = "enabled", havingValue = "true")
public class PlatformConsoleConfiguration {

    private static final Logger log = LoggerFactory.getLogger(PlatformConsoleConfiguration.class);

    /**
     * Resolves the platform operator for {@code /api/v1/platform/**} only.
     */
    @Bean
    PlatformOperatorAuthenticationFilter platformOperatorAuthenticationFilter(
            PlatformAuthService auth,
            org.springframework.beans.factory.ObjectProvider<com.firstelement.security.session.BrowserSessionStore> sessionStoreProvider) {
        log.info("Platform console enabled. It is reachable at /api/v1/platform and authenticates "
                + "operators on a credential path separate from the customer session.");
        return new PlatformOperatorAuthenticationFilter(auth, sessionStoreProvider.getIfAvailable());
    }

    /**
     * Stops Spring Boot from also registering the filter as an ordinary servlet filter.
     *
     * <p>A {@code Filter} bean is auto-registered with the servlet container at
     * {@code LOWEST_PRECEDENCE} — after the Spring Security chain — in addition to wherever it is
     * placed by hand. That would run {@link PlatformOperatorAuthenticationFilter} twice per request,
     * and the outer copy is not harmless: the {@code CsrfFilter} terminates the <em>security</em>
     * chain but not the servlet chain, so a token-less cross-site POST would still reach
     * {@code beginLogin} behind its 403, writing a pending session and audit rows for a request that
     * was refused.
     *
     * <p>This is disabled explicitly rather than left to the absence of a symptom. The customer
     * {@code BrowserSessionAuthenticationFilter} has the same shape and shows no such double run
     * today, which is evidence about current behaviour, not a guarantee -- and the console is the
     * surface where a duplicate authentication run would matter most.
     *
     * <p>{@code setEnabled(false)} is the documented way to say "this filter belongs to a security
     * chain, not to the servlet container".
     */
    @Bean
    FilterRegistrationBean<PlatformOperatorAuthenticationFilter> platformOperatorFilterRegistration(
            PlatformOperatorAuthenticationFilter filter) {
        FilterRegistrationBean<PlatformOperatorAuthenticationFilter> registration =
                new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    /**
     * A filter chain of its own, ahead of the customer chain.
     *
     * <h2>Why not a rule in the existing chain</h2>
     *
     * <p>A platform operator holds no tenant permissions, so in the customer chain they would never
     * satisfy {@code authenticated()} and every console route would answer 401 -- the request is
     * rejected by the chain before a controller gets the chance to check anything. Making the console
     * work by permitting those paths in the customer chain would work, and would also mean a customer
     * session and a platform session pass through the same authentication machinery, which is exactly
     * the coupling ADR 0020 is written against.
     *
     * <p>Giving the console its own chain removes the ambiguity instead of managing it. Spring
     * Security evaluates chains in order and the first matching {@code securityMatcher} wins, so a
     * {@code /api/v1/platform/**} request reaches this chain and never the customer one:
     *
     * <ul>
     *   <li>The customer {@code BrowserSessionAuthenticationFilter} does not run, so a clinic
     *       administrator's cookie is never even read on a platform route. It cannot authenticate
     *       there because the code that would read it is not in the chain.</li>
     *   <li>The platform filter is installed only here, so a platform cookie is inert on a customer
     *       route -- the customer chain has no filter that would look for it.</li>
     * </ul>
     *
     * <p>Within the chain, the two login steps are permitted because they are how a credential is
     * obtained, and everything else requires {@link PlatformOperatorAuthenticationFilter#AUTHORITY}.
     * CSRF stays on for both login steps, because step two acts on the cookie step one just set and a
     * cross-site induction could otherwise ride it.
     *
     * <p>This mirrors {@code OAuth2ResourceServerConfiguration}, which puts its integration chain at
     * order 1 for the same reason.
     */
    @Bean
    @Order(1)
    SecurityFilterChain platformConsoleFilterChain(
            HttpSecurity http,
            PlatformOperatorAuthenticationFilter platformFilter,
            SecurityProperties security,
            SecurityErrorRenderer errorRenderer) throws Exception {
        CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepository.setCookieName(security.getCsrfCookieName());
        csrfRepository.setCookiePath("/");
        csrfRepository.setCookieHttpOnly(false);
        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
        csrfHandler.setCsrfRequestAttributeName(null);

        return http
                .securityMatcher("/api/v1/platform/**")
                .authorizeHttpRequests(requests -> requests
                        // The handshake itself. Unauthenticated by definition.
                        .requestMatchers("/api/v1/platform/auth/password",
                                "/api/v1/platform/auth/second-factor").permitAll()
                        // Everything else needs an operator. The controller's own
                        // PlatformOperatorContextHolder.require() remains as a second gate.
                        .anyRequest().hasAuthority(PlatformOperatorAuthenticationFilter.AUTHORITY))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfRepository)
                        .csrfTokenRequestHandler(csrfHandler))
                // The platform cookie is a bearer-like token that this filter resolves itself, so
                // there is no HttpSession to carry and none is created.
                // The same renderer the customer chain uses, so a refusal here carries the same
                // structured body, correlation id and event as any other refusal in the product.
                // Without it this chain would answer Spring's default 403 with an empty body, and a
                // client could not tell a refused console request from a broken one.
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(errorRenderer)
                        .accessDeniedHandler(errorRenderer))
                .sessionManagement(session -> session
                        .sessionCreationPolicy(
                                org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
                .addFilterBefore(platformFilter, AnonymousAuthenticationFilter.class)
                .build();
    }

    @Bean
    PlatformSecretCodec platformSecretCodec(
            @Value("${firstelement.security.encryption-key:}") String encryptionKey) {
        return new PlatformSecretCodec(encryptionKey);
    }

    @Bean
    PlatformOperatorRepository platformOperatorRepository(NamedParameterJdbcTemplate jdbc) {
        return new PlatformOperatorRepository(jdbc);
    }

    @Bean
    PlatformAuditRepository platformAuditRepository(NamedParameterJdbcTemplate jdbc) {
        return new PlatformAuditRepository(jdbc);
    }

    @Bean
    PlatformAuthService platformAuthService(PlatformOperatorRepository operators,
                                            PlatformAuditRepository audit,
                                            PlatformSecretCodec codec,
                                            LoginRateLimiter rateLimiter) {
        return new PlatformAuthService(operators, audit, codec, rateLimiter);
    }

    @Bean
    InviteTokenService inviteTokenService(NamedParameterJdbcTemplate jdbc,
                                          PlatformAuditRepository audit) {
        return new InviteTokenService(jdbc, audit);
    }

    @Bean
    PlatformClinicService platformClinicService(NamedParameterJdbcTemplate jdbc,
                                                PlatformAuditRepository audit,
                                                InviteTokenService inviteTokenService) {
        return new PlatformClinicService(jdbc, audit, inviteTokenService);
    }

    @Bean
    com.firstelement.security.platform.SubscriptionPaymentService subscriptionPaymentService(
            NamedParameterJdbcTemplate jdbc,
            PlatformAuditRepository audit) {
        return new com.firstelement.security.platform.SubscriptionPaymentService(jdbc, audit);
    }

    @Bean
    com.firstelement.security.platform.SubscriptionStatusTransitionService subscriptionStatusTransitionService(
            NamedParameterJdbcTemplate jdbc,
            PlatformAuditRepository audit) {
        return new com.firstelement.security.platform.SubscriptionStatusTransitionService(jdbc, audit);
    }

    @Bean
    com.firstelement.security.platform.PlatformPlanService platformPlanService(
            NamedParameterJdbcTemplate jdbc,
            PlatformAuditRepository audit) {
        return new com.firstelement.security.platform.PlatformPlanService(jdbc, audit);
    }

    @Bean
    com.firstelement.security.platform.PlatformSupportAccessService platformSupportAccessService(
            NamedParameterJdbcTemplate jdbc,
            PlatformAuditRepository audit) {
        return new com.firstelement.security.platform.PlatformSupportAccessService(jdbc, audit);
    }

    @Bean
    com.firstelement.security.platform.PlatformDashboardService platformDashboardService(
            NamedParameterJdbcTemplate jdbc,
            PlatformAuditRepository audit) {
        return new com.firstelement.security.platform.PlatformDashboardService(jdbc, audit);
    }

    // PlatformConsoleController and PlatformExceptionAdvice are deliberately absent from this class.
    //
    // On Spring Framework 6.2 a controller produced by a @Bean method is not treated as a handler,
    // so its routes never register and every request 404s while the bean itself sits happily in the
    // context. They therefore keep @RestController / @RestControllerAdvice and are gated by
    // @ConditionalOnProperty on the class, which component scanning does honour.
    //
    // The condition is therefore applied twice, by different mechanisms, on purpose: once here for
    // the services, once on each class for the web layer. PlatformConsoleDisabledTest asserts that
    // neither mechanism is quietly bypassed.
}