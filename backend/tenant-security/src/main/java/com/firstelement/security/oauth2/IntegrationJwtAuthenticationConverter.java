package com.firstelement.security.oauth2;

import com.firstelement.security.Capability;
import com.firstelement.security.PrincipalType;
import com.firstelement.security.TenantContext;
import com.firstelement.security.TenantScopedAuthentication;
import com.firstelement.security.config.SecurityProperties;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

/**
 * Converts a validated JWT into a {@link TenantContext} for the trusted-integration
 * path (FR-2.6).
 *
 * <h2>The rule that matters</h2>
 *
 * <p>A bearer token chooses its own scope, so it must be validated against a closed
 * table rather than trusted. Every failure below is a rejection, never a downgrade:
 *
 * <ol>
 *   <li>No tenant claim → rejected. There is no default tenant.</li>
 *   <li>Unparseable tenant id → rejected.</li>
 *   <li>Unknown principal type → rejected. Not downgraded to {@code SERVICE}.</li>
 *   <li>A {@code USER} principal type → rejected. A browser authenticates with the
 *       revocable server-side session, never a JWT; accepting one here would blur the
 *       two credential paths FR-2.6 requires to stay separate and would make browser
 *       session revocation meaningless.</li>
 *   <li>A capability the principal type may not hold → rejected, not ignored.</li>
 *   <li>No capability claim → a principal with no grants. Deny by default.</li>
 * </ol>
 *
 * <h2>Project scope is never taken from the token</h2>
 *
 * <p>An integration reads within its tenant. Any project narrowing happens through
 * {@code project_memberships} or through a server-configured service scope — never from
 * a claim the client controls.
 */
public class IntegrationJwtAuthenticationConverter {

    private final SecurityProperties.Oauth2 properties;

    public IntegrationJwtAuthenticationConverter(SecurityProperties.Oauth2 properties) {
        this.properties = properties;
    }

    /** The pure conversion, testable without a JWT fixture or a Spring context. */
    public TenantContext toTenantContext(Jwt jwt) {
        String tenantClaim = jwt.getClaimAsString(properties.tenantClaim());
        if (tenantClaim == null || tenantClaim.isBlank()) {
            throw new InvalidIntegrationToken(
                "integration token is missing the " + properties.tenantClaim() + " claim");
        }
        UUID organizationId;
        try {
            organizationId = UUID.fromString(tenantClaim.trim());
        } catch (IllegalArgumentException e) {
            throw new InvalidIntegrationToken(
                "integration token carries an unparseable " + properties.tenantClaim() + " claim");
        }

        String rawType = jwt.getClaimAsString(properties.principalTypeClaim());
        if (rawType == null || rawType.isBlank()) {
            throw new InvalidIntegrationToken(
                "integration token is missing the " + properties.principalTypeClaim() + " claim");
        }
        PrincipalType principalType;
        try {
            principalType = PrincipalType.fromValue(rawType.trim());
        } catch (IllegalArgumentException e) {
            throw new InvalidIntegrationToken("integration token names an unknown principal type");
        }
        if (principalType == PrincipalType.USER) {
            throw new InvalidIntegrationToken(
                "a USER principal may not authenticate through the resource-server path");
        }

        Set<Capability> capabilities = readCapabilities(jwt, principalType);
        String subject = jwt.getSubject() == null
            ? "subj:" + Integer.toHexString(System.identityHashCode(jwt))
            : jwt.getSubject();
        return TenantContext.forMachine(principalType, organizationId, subject, Set.of(), capabilities);
    }

    /**
     * Wraps the converted context together with the token, keeping the raw token
     * reachable for integration handlers without widening {@code TenantContext}.
     */
    public IntegrationAuthentication convert(Jwt jwt, Collection<GrantedAuthority> authorities) {
        return new IntegrationAuthentication(toTenantContext(jwt), jwt,
            List.copyOf(authorities == null ? List.of() : authorities));
    }

    private Set<Capability> readCapabilities(Jwt jwt, PrincipalType principalType) {
        List<String> raw = jwt.getClaimAsStringList(properties.capabilitiesClaim());
        if (raw == null || raw.isEmpty()) {
            return Set.of();
        }
        Set<Capability> capabilities = new LinkedHashSet<>();
        for (String value : raw) {
            if (value == null || value.isBlank()) {
                continue;
            }
            Capability capability;
            try {
                capability = Capability.valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new InvalidIntegrationToken("integration token carries an unknown capability");
            }
            if (!capability.mayBeHeldBy(principalType)) {
                throw new InvalidIntegrationToken(
                    "integration token carries a capability the principal type may not hold");
            }
            capabilities.add(capability);
        }
        return Set.copyOf(capabilities);
    }

    /** Rejection of a bearer token. Never carries the claim values. */
    public static class InvalidIntegrationToken extends InvalidBearerTokenException {

        private static final long serialVersionUID = 1L;

        public InvalidIntegrationToken(String message) {
            super(message);
        }
    }

    /** An {@link org.springframework.security.core.Authentication} for a validated JWT. */
    public static final class IntegrationAuthentication extends AbstractAuthenticationToken
        implements TenantScopedAuthentication {

        private static final long serialVersionUID = 1L;

        private final transient TenantContext context;
        private final transient Jwt jwt;

        IntegrationAuthentication(TenantContext context, Jwt jwt, List<GrantedAuthority> authorities) {
            super(authorities);
            this.context = context;
            this.jwt = jwt;
            setAuthenticated(true);
        }

        @Override
        public TenantContext tenantContext() {
            return context;
        }

        /** The raw token, deliberately not exposed: see {@link #getCredentials()}. */
        public Jwt jwt() {
            return jwt;
        }

        @Override
        public Object getPrincipal() {
            return context;
        }

        @Override
        public Object getCredentials() {
            // Never expose the raw token through the Authentication contract: it ends up
            // in every log line, error page and serialisation that touches the auth.
            return null;
        }

        @Override
        public String getName() {
            return context.auditActorLabel();
        }
    }
}