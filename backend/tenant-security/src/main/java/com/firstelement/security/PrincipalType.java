package com.firstelement.security;

/**
 * The principal taxonomy.
 *
 * <p>A {@code TenantContext} always answers "who is acting", and the answer is
 * never just a user id. PROJECT_REQUIREMENTS §3.4 requires service accounts,
 * connector principals, worker identities, and AI agents to each carry an
 * explicit tenant and capability set, so the type of actor is part of the
 * security decision rather than an implementation detail.
 *
 * <p>The split that matters is human versus not:
 *
 * <ul>
 *   <li>{@link #USER} is the only principal authorized by <em>role</em>. Role is
 *       stored on the {@code users} row and is a human administrative fact.
 *   <li>{@link #SERVICE}, {@link #CONNECTOR}, {@link #WORKER} and {@link #AI_AGENT}
 *       are authorized by <em>capability</em> only, deny-by-default, with an
 *       explicit principal-id string. They never receive a role, so
 *       "promote the workflow job to administrator" has no representation to
 *       express it in.
 * </ul>
 */
public enum PrincipalType {

    /** Interactive human authenticated by the cookie-backed server-side session. */
    USER("USER"),

    /** Machine identity representing the application itself (batch, admin tooling, API). */
    SERVICE("SERVICE"),

    /** An inbound channel/webhook adapter. Inbound-facing, signature-verified. */
    CONNECTOR("CONNECTOR"),

    /** A durable worker runtime claim. Reaches data through jobs, never through the browser. */
    WORKER("WORKER"),

    /**
     * An AI agent acting under application-owned policy. Never more than the agent
     * policy allows (PROJECT_REQUIREMENTS §3.4); see
     * {@link Capability#mayBeHeldBy(PrincipalType)}.
     */
    AI_AGENT("AI_AGENT");

    private final String value;

    PrincipalType(String value) {
        this.value = value;
    }

    /** Stable wire/database spelling, matching {@code audit_logs.actorType}. */
    public String value() {
        return value;
    }

    public static PrincipalType fromValue(String value) {
        for (PrincipalType type : values()) {
            if (type.value.equalsIgnoreCase(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown principal type: " + value);
    }

    /** Human principals are role-bearing; machine principals are capability-bearing. */
    public boolean isHuman() {
        return this == USER;
    }

    /**
     * A WRITE-capable principal. Used by the audit trail to label an entry and by
     * policy to refuse read-only integrations from mutating anything.
     */
    public boolean isMachine() {
        return !isHuman();
    }
}