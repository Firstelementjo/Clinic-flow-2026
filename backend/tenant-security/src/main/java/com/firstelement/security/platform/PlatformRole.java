package com.firstelement.security.platform;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Platform operator roles designed so platform access can split into
 * Super Admin, Support, and Billing without a system rebuild.
 */
public enum PlatformRole {

    /** Unrestricted platform administrator: full lifecycle, billing, system, and break-glass. */
    SUPER_ADMIN,

    /** Support agent: clinic overview, tenant audit trail, break-glass support access requests. */
    SUPPORT,

    /** Billing administrator: subscriptions, payments ledger, plan limits and quotas. */
    BILLING;

    private static final Map<PlatformRole, Set<PlatformCapability>> CAPABILITIES = Map.of(
        SUPER_ADMIN, Collections.unmodifiableSet(EnumSet.allOf(PlatformCapability.class)),
        SUPPORT, Collections.unmodifiableSet(EnumSet.of(
            PlatformCapability.READ_PLATFORM_OVERVIEW,
            PlatformCapability.READ_PLATFORM_AUDIT,
            PlatformCapability.OPERATE_PLATFORM
        )),
        BILLING, Collections.unmodifiableSet(EnumSet.of(
            PlatformCapability.READ_PLATFORM_OVERVIEW,
            PlatformCapability.CREATE_CLINIC,
            PlatformCapability.CREATE_ACCOUNT,
            PlatformCapability.MANAGE_BILLING
        ))
    );

    public Set<PlatformCapability> capabilities() {
        return CAPABILITIES.getOrDefault(this, Set.of());
    }

    public boolean holds(PlatformCapability capability) {
        return capability != null && capabilities().contains(capability);
    }
}
