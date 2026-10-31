package com.firstelement.security;

import java.util.Collection;
import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * The Spring Security {@code Authentication} for every FirstElement principal.
 *
 * <p>One token shape for humans and machines. {@link #tenantContext()} is the
 * server-resolved scope; {@link #getPrincipal()} returns it so method-security
 * expressions and audit hooks read the same immutable value instead of
 * re-deriving it from request attributes.
 *
 * <p>Authorities are coarse and advisory only. The authoritative decision is
 * {@code AuthorizationService}, which also applies tenant, project, owner and
 * capability boundaries that a flat authority string cannot express.
 */
public final class TenantAuthentication extends AbstractAuthenticationToken
    implements TenantScopedAuthentication {

    private static final long serialVersionUID = 1L;

    private final transient TenantContext tenantContext;

    public TenantAuthentication(TenantContext tenantContext) {
        super(authorities(tenantContext));
        this.tenantContext = java.util.Objects.requireNonNull(tenantContext, "tenantContext");
        setAuthenticated(true);
    }

    private static Collection<GrantedAuthority> authorities(TenantContext context) {
        List<GrantedAuthority> authorities = new java.util.ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("PRINCIPAL_" + context.principalType().name()));
        if (context.isUser()) {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + context.role().name()));
        } else {
            for (Capability capability : context.capabilities()) {
                authorities.add(new SimpleGrantedAuthority("CAP_" + capability.name()));
            }
        }
        return authorities;
    }

    @Override
    public TenantContext tenantContext() {
        return tenantContext;
    }

    @Override
    public Object getPrincipal() {
        return tenantContext;
    }

    /**
     * {@code null} on purpose. A browser session authenticates with an opaque cookie that
     * is already verified against the {@code sessions} table; keeping a copy of the raw
     * token on the {@code Authentication} would put a live credential into every object
     * graph that serialises it, and {@code eraseCredentials()} would have nothing to do.
     */
    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public String getName() {
        return tenantContext.auditActorLabel();
    }
}