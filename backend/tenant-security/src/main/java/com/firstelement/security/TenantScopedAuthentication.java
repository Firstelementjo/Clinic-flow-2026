package com.firstelement.security;

import org.springframework.security.core.Authentication;

/**
 * An {@link Authentication} that carries a server-resolved {@link TenantContext}.
 *
 * <p>Implemented by both credential paths — the browser cookie session
 * ({@link TenantAuthentication}) and the optional trusted-integration bearer token
 * ({@code IntegrationAuthentication}) — so {@code TenantContextHolder.from(..)} and the
 * permission evaluator do not need to know which one produced the request.
 */
public interface TenantScopedAuthentication extends Authentication {

    /** The immutable, server-resolved scope for this authentication. Never null. */
    TenantContext tenantContext();
}