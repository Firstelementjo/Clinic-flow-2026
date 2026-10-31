package com.firstelement.security.platform;

import java.util.Optional;

/**
 * Holds the platform operator for the current request.
 *
 * <h2>A ThreadLocal, and separately a platform-only authority — deliberately</h2>
 *
 * <p>The customer path authenticates into {@code SecurityContext}, which means any
 * {@code authenticated()} rule would accept a customer session. If the platform console relied on the
 * same bucket, a clinic administrator's cookie could satisfy a cross-tenant route and the boundary
 * would rest on a controller remembering to check. That is the failure ADR 0020 is written against.
 *
 * <p>So the platform principal is held in two places, each doing a different job:
 *
 * <ul>
 *   <li><b>This thread-local</b> carries the operator's identity, capabilities and elevation window,
 *       and is what the console authorises against. It is not a Spring Security concept.</li>
 *   <li><b>{@code ROLE_PLATFORM_OPERATOR} in the {@code SecurityContext}</b> exists so that Spring
 *       Security can evaluate the platform chain's {@code hasAuthority(...)} rule. Without it a
 *       platform operator holds no tenant permission and every console route answers 401 before a
 *       controller runs.</li>
 * </ul>
 *
 * <p>The separation holds because the platform filter is installed only on the {@code @Order(1)}
 * chain matched to {@code /api/v1/platform/**}, and the customer filter only on the chain above it.
 * Neither chain contains the other's filter, so neither credential is ever read on the other's routes.
 *
 * <p>An earlier version of this comment claimed the {@code SecurityContext} was "never written to"
 * by the platform filter. That was true when written and stopped being true when the authority was
 * added; the claim was left behind and is the reason the two sources disagreed.
 *
 * <p>{@link #clear()} runs in a {@code finally} so a pooled request thread cannot carry an operator
 * into the next request that reuses it.
 */
public final class PlatformOperatorContextHolder {

    private static final ThreadLocal<PlatformOperatorContext> CURRENT = new ThreadLocal<>();

    private PlatformOperatorContextHolder() {
    }

    public static void set(PlatformOperatorContext context) {
        CURRENT.set(context);
    }

    /** The operator for this request, or empty. Never throws, never blocks. */
    public static Optional<PlatformOperatorContext> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /**
     * The operator for this request, or a refusal.
     *
     * <p>Used by console endpoints. A route reached without one is refused rather than falling back
     * to whatever principal Spring Security happens to hold.
     */
    public static PlatformOperatorContext require() {
        PlatformOperatorContext context = CURRENT.get();
        if (context == null) {
            throw new PlatformAccessDenied("No platform operator is authenticated for this request.");
        }
        return context;
    }

    /** Refused when the request carries no platform principal. Rendered as a 403. */
    public static class PlatformAccessDenied extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public PlatformAccessDenied(String message) {
            super(message);
        }
    }

    public static void clear() {
        CURRENT.remove();
    }
}