package com.firstelement.security.platform;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A principal above the organization.
 *
 * <h2>Why this is not a {@code TenantContext}</h2>
 *
 * <p>{@code TenantContext} requires a non-null {@code organizationId} and refuses a machine
 * principal that carries a user id, role or browser session. Those invariants are correct for every
 * principal that belongs to exactly one customer, and they are why the boundary holds: the type
 * cannot be constructed for a principal that spans tenants.
 *
 * <p>A platform operator belongs to no organization. It is therefore not representable here, and
 * that is the point. Any code that needs tenant scope asks for a {@code TenantContext} and cannot
 * accept this type; any code that receives a {@code TenantContext} cannot have been handed a
 * platform operator by accident. The separation the ADR requires is enforced by the type system
 * rather than by a field someone remembers to check.
 *
 * <h2>Elevation</h2>
 *
 * <p>Authentication and authority are separate. {@link #mfaSatisfied} records that a second factor
 * was presented; {@link #isElevated} records that the short elevation window is still open. A
 * session can be authenticated and not yet usable for a write, which is the state ADR 0020 means by
 * "just-in-time elevation rather than a standing super-admin".
 *
 * @param operatorId      the operator
 * @param email           their address; never a customer {@code users} row
 * @param displayName     shown in the console and in the audit trail
 * @param sessionId       the platform session, not a customer {@code sessions} row
 * @param capabilities    what this operator may do, across every tenant
 * @param mfaSatisfied    a second factor was presented for this session
 * @param elevatedUntil   the elevation window closes here; {@code null} when not elevated
 */
public record PlatformOperatorContext(
        UUID operatorId,
        String email,
        String displayName,
        UUID sessionId,
        Set<PlatformCapability> capabilities,
        boolean mfaSatisfied,
        Instant elevatedUntil
) {

    public PlatformOperatorContext {
        Objects.requireNonNull(operatorId, "operatorId is required");
        Objects.requireNonNull(email, "email is required");
        Objects.requireNonNull(sessionId, "sessionId is required");
        capabilities = capabilities == null
                ? Set.of()
                : Set.copyOf(new LinkedHashSet<>(capabilities));

        if (!mfaSatisfied && elevatedUntil != null) {
            throw new IllegalArgumentException(
                    "A session cannot be elevated before its second factor is satisfied");
        }
    }

    public static PlatformOperatorContext authenticated(UUID operatorId,
                                                        String email,
                                                        String displayName,
                                                        UUID sessionId,
                                                        Collection<PlatformCapability> capabilities) {
        return new PlatformOperatorContext(operatorId, email, displayName, sessionId,
                Set.copyOf(new LinkedHashSet<>(capabilities)), true, null);
    }

    public boolean has(PlatformCapability capability) {
        return capabilities.contains(capability);
    }

    /** Whether the elevation window is open at {@code now}. */
    public boolean isElevated(Instant now) {
        return elevatedUntil != null && now.isBefore(elevatedUntil);
    }

    /**
     * Whether this context may perform {@code capability} at {@code now}.
     *
     * <p>Reading does not require elevation; writing does. A session that has authenticated but not
     * elevated can therefore survey the platform and must re-authorise to change anything, which is
     * the property that makes a stolen operator cookie far less useful than it would otherwise be.
     */
    public boolean permits(PlatformCapability capability, Instant now) {
        if (!has(capability)) {
            return false;
        }
        return !capability.isMutating() || isElevated(now);
    }

    /** A copy with the elevation window set, which only an MFA-satisfied session may have. */
    public PlatformOperatorContext elevatedUntil(Instant until) {
        return new PlatformOperatorContext(operatorId, email, displayName, sessionId, capabilities,
                mfaSatisfied, until);
    }

    /** The audit label; never the token, never the secret. */
    public String auditActorLabel() {
        return "platform-operator:" + email;
    }
}