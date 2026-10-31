package com.firstelement.security.platform;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

/**
 * What a platform operator may do, independent of any tenant.
 *
 * <p>Deliberately not {@code Permission}. {@code Permission} is evaluated against a
 * {@link com.firstelement.security.TenantContext} inside one organization; a capability here is
 * evaluated against a principal that spans every organization. Sharing one enum would have let a
 * tenant-scoped grant and a cross-tenant grant be confused at the call site, and the compiler could
 * not have told them apart.
 *
 * <p>Every constant is a real operation the console performs. There is deliberately no
 * {@code SQL_EXECUTE}, {@code SHELL} or {@code READ_SECRET}: ADR 0020 forbids them, and an absent
 * constant cannot be granted by a mistake.
 */
public enum PlatformCapability {

    /** Read the platform-wide list of clinics, their state and their configuration. */
    READ_PLATFORM_OVERVIEW(false),

    /** Create a clinic (a project) and its first administrator account. */
    CREATE_CLINIC(true),

    /** Create further accounts inside an existing clinic. */
    CREATE_ACCOUNT(true),

    /** Grant or revoke a user's access to a specific clinic. */
    GRANT_ACCESS(true),

    /** Read the cross-tenant audit trail. */
    READ_PLATFORM_AUDIT(false),

    /** Perform platform status actions: replay a failed job, restart a worker. */
    OPERATE_PLATFORM(true),

    /** Request a validated database operation. Never arbitrary SQL. */
    REQUEST_DB_ACTION(true),

    /** Record manual subscription payments, reversals, and manage financial ledger. */
    MANAGE_BILLING(true);

    private final boolean mutating;

    PlatformCapability(boolean mutating) {
        this.mutating = mutating;
    }

    /**
     * Whether holding this capability permits a write.
     *
     * <p>Used to require a justification on every mutating audit row, so "the operator did
     * something" always has an answer to "why".
     */
    public boolean isMutating() {
        return mutating;
    }

    public static Optional<PlatformCapability> fromValue(String value) {
        if (value == null) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(c -> c.name().equalsIgnoreCase(value.trim()))
                .findFirst();
    }

    /** Every capability a platform operator can hold. Used only when bootstrapping the first one. */
    public static Set<PlatformCapability> all() {
        return Set.of(values());
    }
}