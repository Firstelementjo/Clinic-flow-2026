package com.firstelement.security.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Security configuration for the boundary module.
 *
 * <p>Prefix {@code firstelement.security}. Registered explicitly with
 * {@code @EnableConfigurationProperties} rather than by
 * {@code @ConfigurationPropertiesScan("com.firstelement.config")}, because that scan
 * belongs to the application module and this module must not require a change there.
 *
 * <h2>Defaults are the legacy behaviour</h2>
 *
 * <p>Every default below is the value the NestJS runtime already used, so switching
 * runtimes does not silently change a security posture:
 *
 * <table>
 *   <caption>Default provenance</caption>
 *   <tr><th>Property</th><th>Default</th><th>Legacy source</th></tr>
 *   <tr><td>{@code session-cookie-name}</td><td>{@code fe_session}</td>
 *       <td>{@code SESSION_COOKIE_NAME}</td></tr>
 *   <tr><td>{@code session-ttl-hours}</td><td>{@code 720}</td>
 *       <td>{@code SESSION_TTL_HOURS} default</td></tr>
 *   <tr><td>{@code login-rate-limit-max-attempts}</td><td>{@code 10}</td>
 *       <td>{@code @Throttle({default: {limit: 10}})} on {@code /auth/login}</td></tr>
 *   <tr><td>{@code login-rate-limit-window}</td><td>{@code PT60S}</td>
 *       <td>{@code ttl: 60_000}</td></tr>
 * </table>
 *
 * <p>{@code session-cookie-secure} defaults to <em>not secure</em> so a local HTTP
 * developer login works, and is forced to {@code true} in production by
 * {@link com.firstelement.security.config.SecurityConfiguration}, which reads
 * {@code firstelement.environment}. A production deployment therefore cannot run with a
 * non-Secure session cookie even if the property is left alone.
 */
@Validated
@ConfigurationProperties(prefix = "firstelement.security")
public class SecurityProperties {

    @NotBlank
    @Size(min = 3, max = 64)
    private String sessionCookieName = "fe_session";

    @Min(1)
    @Max(24 * 30)
    private int sessionTtlHours = 720;

    /** Overrides {@code session-cookie-name} when false. Documented, not silent. */
    private Boolean sessionCookieSecure;

    /** {@code lax} matches the legacy cookie; {@code strict} is available for hardening. */
    @NotNull
    private SameSite sessionCookieSameSite = SameSite.LAX;

    @NotBlank
    @Size(min = 3, max = 64)
    private String csrfCookieName = "XSRF-TOKEN";

    @NotBlank
    @Size(min = 3, max = 64)
    private String csrfHeaderName = "X-XSRF-TOKEN";

    /**
     * Whether the CSRF token is required on login. Left on: a login that can be
     * triggered cross-site is a login-CSRF vector.
     */
    private boolean csrfRequiredOnLogin = true;

    @Min(1)
    @Max(1000)
    private int loginRateLimitMaxAttempts = 10;

    @NotNull
    private Duration loginRateLimitWindow = Duration.ofSeconds(60);

    /**
     * Minimum spacing between {@code sessions."lastSeenAt"} writes. The value is
     * informational for the Admin session list; authorization never reads it.
     */
    @Min(0)
    private int sessionTouchIntervalSeconds = 60;

    /**
     * Extra allowed origins beyond {@code firstelement.api.web-origin}. Comma separated.
     * There is no wildcard option: {@code allowCredentials=true} forbids
     * {@code allowedOrigins=*}, and a credentialed wildcard allowlist is not a thing
     * that can be made safe.
     */
    private List<String> additionalAllowedOrigins = new ArrayList<>();

    @Valid
    @NotNull
    private Oauth2 oauth2 = new Oauth2();

    public enum SameSite {
        LAX("Lax"),
        STRICT("Strict"),
        NONE("None");

        private final String header;

        SameSite(String header) {
            this.header = header;
        }

        public String header() {
            return header;
        }

        /** {@code SameSite=None} requires {@code Secure}, so refuse it when insecure. */
        public boolean requiresSecureCookie() {
            return this == NONE;
        }

        public static SameSite parse(String value) {
            if (value == null) {
                return LAX;
            }
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                    "firstelement.security.session-cookie-same-site must be lax, strict or none; got: " + value);
            }
        }
    }

    /**
     * The optional OAuth2 resource-server path for trusted integrations
     * (FR-2.6). Disabled by default: a browser session must never authenticate this way,
     * and the two credential paths must remain separate.
     */
    public static class Oauth2 {

        /** Master switch. When false no bearer-token filter chain is registered. */
        private boolean enabled = false;

        /**
         * When true, bearer tokens are accepted only on integration paths (the
         * {@code /api/v1/integrations/**} prefix). A blanket "accept JWTs anywhere" would
         * blur the browser and external-integration credential paths that FR-2.6 requires
         * to stay separate.
         */
        private boolean integrationPathsOnly = true;

        private List<String> integrationPathPrefixes = new ArrayList<>(Arrays.asList("/api/v1/integrations"));

        /** Claim carrying the tenant. Rejected when absent: the model never chooses scope. */
        @NotBlank
        private String tenantClaim = "tenant_id";

        /** Claim carrying the principal type; must name a {@code PrincipalType}. */
        @NotBlank
        private String principalTypeClaim = "principal_type";

        /** Claim carrying capabilities; every entry must pass the closed capability table. */
        @NotBlank
        private String capabilitiesClaim = "capabilities";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isIntegrationPathsOnly() {
            return integrationPathsOnly;
        }

        public void setIntegrationPathsOnly(boolean integrationPathsOnly) {
            this.integrationPathsOnly = integrationPathsOnly;
        }

        public List<String> integrationPathPrefixes() {
            return integrationPathPrefixes;
        }

        public void setIntegrationPathPrefixes(List<String> integrationPathPrefixes) {
            this.integrationPathPrefixes = integrationPathPrefixes;
        }

        public String tenantClaim() {
            return tenantClaim;
        }

        public void setTenantClaim(String tenantClaim) {
            this.tenantClaim = tenantClaim;
        }

        public String principalTypeClaim() {
            return principalTypeClaim;
        }

        public void setPrincipalTypeClaim(String principalTypeClaim) {
            this.principalTypeClaim = principalTypeClaim;
        }

        public String capabilitiesClaim() {
            return capabilitiesClaim;
        }

        public void setCapabilitiesClaim(String capabilitiesClaim) {
            this.capabilitiesClaim = capabilitiesClaim;
        }
    }

    // ------------------------------------------------------------------

    public String getSessionCookieName() {
        return sessionCookieName;
    }

    public void setSessionCookieName(String sessionCookieName) {
        this.sessionCookieName = sessionCookieName;
    }

    public int getSessionTtlHours() {
        return sessionTtlHours;
    }

    public void setSessionTtlHours(int sessionTtlHours) {
        this.sessionTtlHours = sessionTtlHours;
    }

    public Boolean getSessionCookieSecure() {
        return sessionCookieSecure;
    }

    /**
     * The one place that decides whether a session cookie carries {@code Secure}.
     *
     * <p>This exists because that decision was made twice and diverged. The customer cookie used a
     * private helper in {@code SecurityConfiguration}; the platform cookie, written later, inlined
     * {@code Boolean.TRUE.equals(...)}. Because {@code sessionCookieSecure} is a nullable
     * {@code Boolean} with no default, the inlined form evaluated to {@code false} when the property
     * is unset — which is the production default — so the platform operator's cookie, the
     * highest-privilege credential in the product, would have been issued without {@code Secure} in
     * exactly the environment where that matters most. Two copies of one security decision is the
     * defect; this method is the single copy.
     *
     * <p>The rules are unchanged from the original helper and are now stated once:
     * <ol>
     *   <li>A {@code SameSite} mode that requires it forces it on, regardless of configuration.</li>
     *   <li>An explicit value is honoured, but explicitly turning it off in production is refused
     *       at startup rather than silently accepted.</li>
     *   <li>Unset means "whatever the environment requires" — {@code true} in production.</li>
     * </ol>
     *
     * @param production whether the deployment reports itself as production
     */
    public boolean resolvedSessionCookieSecure(boolean production) {
        if (getSessionCookieSameSite().requiresSecureCookie()) {
            return true;
        }
        if (sessionCookieSecure != null) {
            if (production && !sessionCookieSecure) {
                throw new IllegalStateException(
                    "firstelement.security.session-cookie-secure=false is not permitted when "
                        + "firstelement.environment=PRODUCTION.");
            }
            return sessionCookieSecure;
        }
        return production;
    }

    public void setSessionCookieSecure(Boolean sessionCookieSecure) {
        this.sessionCookieSecure = sessionCookieSecure;
    }

    public SameSite getSessionCookieSameSite() {
        return sessionCookieSameSite;
    }

    public void setSessionCookieSameSite(SameSite sessionCookieSameSite) {
        this.sessionCookieSameSite = sessionCookieSameSite;
    }

    public String getCsrfCookieName() {
        return csrfCookieName;
    }

    public void setCsrfCookieName(String csrfCookieName) {
        this.csrfCookieName = csrfCookieName;
    }

    public String getCsrfHeaderName() {
        return csrfHeaderName;
    }

    public void setCsrfHeaderName(String csrfHeaderName) {
        this.csrfHeaderName = csrfHeaderName;
    }

    public boolean isCsrfRequiredOnLogin() {
        return csrfRequiredOnLogin;
    }

    public void setCsrfRequiredOnLogin(boolean csrfRequiredOnLogin) {
        this.csrfRequiredOnLogin = csrfRequiredOnLogin;
    }

    public int getLoginRateLimitMaxAttempts() {
        return loginRateLimitMaxAttempts;
    }

    public void setLoginRateLimitMaxAttempts(int loginRateLimitMaxAttempts) {
        this.loginRateLimitMaxAttempts = loginRateLimitMaxAttempts;
    }

    public Duration getLoginRateLimitWindow() {
        return loginRateLimitWindow;
    }

    public void setLoginRateLimitWindow(Duration loginRateLimitWindow) {
        this.loginRateLimitWindow = loginRateLimitWindow;
    }

    public int getSessionTouchIntervalSeconds() {
        return sessionTouchIntervalSeconds;
    }

    public void setSessionTouchIntervalSeconds(int sessionTouchIntervalSeconds) {
        this.sessionTouchIntervalSeconds = sessionTouchIntervalSeconds;
    }

    public List<String> getAdditionalAllowedOrigins() {
        return additionalAllowedOrigins;
    }

    public void setAdditionalAllowedOrigins(List<String> additionalAllowedOrigins) {
        this.additionalAllowedOrigins = additionalAllowedOrigins == null
            ? new ArrayList<>() : new ArrayList<>(additionalAllowedOrigins);
    }

    public Oauth2 getOauth2() {
        return oauth2;
    }

    public void setOauth2(Oauth2 oauth2) {
        this.oauth2 = oauth2;
    }
}