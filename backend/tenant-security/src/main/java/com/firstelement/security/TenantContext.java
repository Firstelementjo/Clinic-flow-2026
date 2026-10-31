package com.firstelement.security;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The immutable, server-resolved scope of one request, job, or tool invocation.
 *
 * <h2>Why this is a value, not a parameter</h2>
 *
 * <p>A tenant/project id arriving from a client is a <em>request</em>. This record
 * only ever holds ids that the server resolved and validated:
 *
 * <ul>
 *   <li>{@code organizationId} comes from the authenticated principal's own
 *       {@code users."organizationId"}.</li>
 *   <li>{@code projectIds} comes from {@code project_memberships} for an
 *       {@code EMPLOYEE}, or from every project in the organization for an
 *       {@code ADMIN}. It is recomputed per request — never cached in a token —
 *       so a revoked membership takes effect immediately.</li>
 * </ul>
 *
 * <p>Consequently there is exactly one legitimate way to move between tenants or
 * projects: through {@code TenantQueryGuard}, which compares a requested id
 * against this set and throws a non-disclosing denial when it is not present.
 *
 * <h2>Invariants</h2>
 *
 * <ul>
 *   <li>{@code organizationId} is never null.</li>
 *   <li>{@code userId}, {@code role} and {@code sessionId} are non-null for
 *       {@link PrincipalType#USER} and null for every machine principal.</li>
 *   <li>{@code role} is never set on a machine principal, and {@code capabilities}
 *       is never set on a human principal. The two grant models cannot be mixed.</li>
 *   <li>{@code projectIds} is non-null and unmodifiable (empty for a machine
 *       principal that has not been granted any project scope).</li>
 * </ul>
 */
public record TenantContext(
        PrincipalType principalType,
        UUID principalId,
        UUID organizationId,
        UUID userId,
        UserRole role,
        Set<UUID> projectIds,
        Set<Capability> capabilities,
        UUID sessionId,
        boolean isPlatformAdmin
) {

    public TenantContext(
            PrincipalType principalType,
            UUID principalId,
            UUID organizationId,
            UUID userId,
            UserRole role,
            Set<UUID> projectIds,
            Set<Capability> capabilities,
            UUID sessionId) {
        this(principalType, principalId, organizationId, userId, role, projectIds, capabilities, sessionId, false);
    }

    public TenantContext {
        Objects.requireNonNull(principalType, "principalType");
        Objects.requireNonNull(principalId, "principalId");
        Objects.requireNonNull(organizationId, "organizationId");
        projectIds = projectIds == null ? Set.<UUID>of() : unmodifiableCopy(projectIds);
        capabilities = capabilities == null ? Set.<Capability>of() : unmodifiableCopy(capabilities);

        if (principalType.isHuman()) {
            Objects.requireNonNull(userId, "userId is required for a USER principal");
            Objects.requireNonNull(role, "role is required for a USER principal");
            Objects.requireNonNull(sessionId, "sessionId is required for a USER principal");
            if (!capabilities.isEmpty()) {
                throw new IllegalArgumentException(
                    "A USER principal carries a role, not capabilities: " + capabilities);
            }
        } else {
            if (userId != null || role != null || sessionId != null) {
                throw new IllegalArgumentException(
                    "A " + principalType + " principal carries capabilities, not a user id, role or browser session");
            }
            for (Capability capability : capabilities) {
                if (!capability.mayBeHeldBy(principalType)) {
                    throw new IllegalArgumentException(
                        "Capability " + capability + " may not be held by a " + principalType + " principal");
                }
            }
        }
    }

    private static <T> Set<T> unmodifiableCopy(Collection<? extends T> source) {
        return Set.copyOf(new LinkedHashSet<>(source));
    }

    // ------------------------------------------------------------------
    // factories
    // ------------------------------------------------------------------

    /** A browser session for a human user. */
    public static TenantContext forUser(UUID sessionId,
                                        UUID userId,
                                        UUID organizationId,
                                        UserRole role,
                                        Collection<UUID> projectIds) {
        return forUser(sessionId, userId, organizationId, role, projectIds, false);
    }

    /** A browser session for a human user with platform admin flag. */
    public static TenantContext forUser(UUID sessionId,
                                        UUID userId,
                                        UUID organizationId,
                                        UserRole role,
                                        Collection<UUID> projectIds,
                                        boolean isPlatformAdmin) {
        return new TenantContext(PrincipalType.USER, userId, organizationId, userId, role,
            copyOf(projectIds), Set.of(), sessionId, isPlatformAdmin);
    }

    /** A machine principal. {@code projectIds} is its explicit, server-granted scope. */
    public static TenantContext forMachine(PrincipalType type,
                                           UUID organizationId,
                                           String principalId,
                                           Collection<UUID> projectIds,
                                           Collection<Capability> capabilities) {
        return new TenantContext(type, stablePrincipalId(type, principalId), organizationId, null, null,
            copyOf(projectIds), copyOf(capabilities), null, false);
    }

    private static <T> Set<T> copyOf(Collection<? extends T> source) {
        return source == null ? Set.of() : Set.copyOf(new LinkedHashSet<>(source));
    }

    /**
     * Machine principal ids are opaque strings (a connector name, a worker id, an
     * agent key). They are namespaced by principal type so two machines with the
     * same short name can never collide in the audit stream.
     */
    private static UUID stablePrincipalId(PrincipalType type, String principalId) {
        Objects.requireNonNull(principalId, "principalId");
        byte[] bytes = (type.value() + ":" + principalId).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return UUID.nameUUIDFromBytes(bytes);
    }

    // ------------------------------------------------------------------
    // queries
    // ------------------------------------------------------------------

    public boolean isUser() {
        return principalType == PrincipalType.USER;
    }

    public boolean isMachine() {
        return principalType.isMachine();
    }

    public boolean isAdmin() {
        return role == UserRole.ADMIN;
    }

    /**
     * Whether this context's server-resolved scope includes {@code projectId}.
     * This is the single question every cross-project check asks.
     */
    public boolean hasProject(UUID projectId) {
        return projectId != null && projectIds.contains(projectId);
    }

    public boolean hasCapability(Capability capability) {
        return capabilities.contains(capability);
    }

    /**
     * A copy with a different project scope. Used by tests and by worker job
     * claiming code that narrows an inherited scope; never widens by accident
     * because callers still have to pass ids that already belong to this tenant.
     */
    public TenantContext withProjects(Collection<UUID> scope) {
        return new TenantContext(principalType, principalId, organizationId, userId, role,
            copyOf(scope), capabilities, sessionId, isPlatformAdmin);
    }

    /** A non-disclosing identity for logs and audit rows. Never an email, never a token. */
    public String auditActorLabel() {
        return isUser() ? "USER:" + userId : principalType.value() + ":" + principalId;
    }
}