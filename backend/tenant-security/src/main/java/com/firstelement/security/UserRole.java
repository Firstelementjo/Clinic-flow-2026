package com.firstelement.security;

/**
 * The role vocabulary carried by a {@code users} row.
 *
 * <p>Mirrors the PostgreSQL {@code "UserRole"} enum adopted in Phase 2
 * ({@code ADMIN}, {@code EMPLOYEE}) and extended in Phase 38 with
 * {@code CLINIC_STAFF}. Roles are a <em>half</em> of an authorization
 * decision; project membership is the other half — see
 * {@link com.firstelement.security.permission.AuthorizationService}.
 *
 * <p><b>This enum must not fall behind the database.</b> {@link #fromValue} throws
 * on an unknown label, so a {@code users} row carrying a role this enum does not
 * declare cannot be read at all — the user becomes a sign-in failure rather than
 * a denied action, which is a far worse symptom than a refusal. The Prisma enum
 * member, this enum, and the {@code ALTER TYPE} migration are three places that
 * must move together; {@code UserRoleContractTest} fails if they diverge.
 *
 * <p>Machine principals have no role. See {@link PrincipalType}.
 */
public enum UserRole {

    /** Full reach inside its own organization, still bounded by project membership. */
    ADMIN,

    /** Inbox/CRM operator, bounded by project membership. */
    EMPLOYEE,

    /**
     * Clinic front desk: books, reschedules and cancels appointments, reads the
     * roster, the catalogue and open slots, and works reminders.
     *
     * <p>A strict SUBSET of {@link #EMPLOYEE}, never a superset. That is the whole value of the
     * role: before it existed, the only way to employ a receptionist was {@code EMPLOYEE}, which
     * also carries the CRM read surface — so a booking clerk could read every clinical note in the
     * organization. This role can only remove reach. It deliberately does not administer the roster
     * and does not read clinician contact details; both stay behind the clinic admin permissions.
     */
    CLINIC_STAFF;

    public static UserRole fromValue(String value) {
        if (value == null) {
            return null;
        }
        for (UserRole role : values()) {
            if (role.name().equalsIgnoreCase(value)) {
                return role;
            }
        }
        throw new IllegalArgumentException("Unknown user role: " + value);
    }

    public String value() {
        return name();
    }
}