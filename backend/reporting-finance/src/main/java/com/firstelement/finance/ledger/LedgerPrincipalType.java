package com.firstelement.finance.ledger;

import com.firstelement.security.PrincipalType;
import java.util.Objects;

/**
 * {@code usage_events."principalType"} — who caused the usage.
 *
 * <h2>Why this is not {@link PrincipalType}</h2>
 *
 * <p>The column's CHECK is {@code IN ('USER','SERVICE','AI_AGENT','JOB','CONNECTOR','SYSTEM')} and it
 * is deliberate that it is <em>wider</em> than the authentication taxonomy. Two of its values have
 * no {@link PrincipalType}:
 *
 * <ul>
 *   <li>{@link #JOB} — a durable worker claim. {@code PrincipalType.WORKER} describes an
 *       <em>identity</em>; a job is a unit of work inside one, and the ledger needs to distinguish
 *       "the worker did this" from "the export job {@code exp-42} did this", because a drill-through
 *       that can only say WORKER cannot answer "which job".</li>
 *   <li>{@link #SYSTEM} — a scheduler or the rollup itself. It is not an identity at all, and calling
 *       it {@code SERVICE} would attribute an autonomous run to a service account that may not
 *       exist.</li>
 * </ul>
 *
 * <p>Widening the other way is the mistake this type prevents: reusing {@link PrincipalType} would
 * make {@code JOB} and {@code SYSTEM} unrepresentable, and the tempting fix — dropping them from the
 * enum and writing {@code SERVICE} — would put a false attribution on every scheduler-written row.
 *
 * <h2>The invariant that is actually load-bearing</h2>
 *
 * <p>{@code usage_events_principal_check}: {@code actorUserId IS NOT NULL OR principalName <> ''}. A
 * principal is mandatory; a <em>user</em> is not, because a scheduled job, an AI run and a connector
 * are all legitimate causes. {@link #principalName(UUID)} is the only factory that fills both.
 */
public enum LedgerPrincipalType {

    USER("USER"),
    SERVICE("SERVICE"),
    AI_AGENT("AI_AGENT"),
    JOB("JOB"),
    CONNECTOR("CONNECTOR"),
    SYSTEM("SYSTEM");

    private final String value;

    LedgerPrincipalType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    /** The label of an authenticated principal, exactly as the security boundary spells it. */
    public static LedgerPrincipalType of(PrincipalType principalType) {
        Objects.requireNonNull(principalType, "principalType");
        return valueOf(principalType.value());
    }

    public static LedgerPrincipalType fromValue(String value) {
        for (LedgerPrincipalType type : values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException(
            "Unknown usage_events.principalType '" + value + "'. The column's CHECK admits "
                + "USER,SERVICE,AI_AGENT,JOB,CONNECTOR,SYSTEM and nothing else.");
    }

    /** The opaque attribution label for an authenticated principal, e.g. {@code USER:<uuid>}. */
    public static String principalName(PrincipalType principalType, java.util.UUID principalId,
                                       String label) {
        return switch (principalType) {
            case USER -> "USER:" + principalId;
            case SERVICE -> "SERVICE:" + label;
            case CONNECTOR -> "CONNECTOR:" + label;
            case WORKER -> "JOB:" + label;
            case AI_AGENT -> "AI_AGENT:" + label;
        };
    }

    /** A system principal's label, e.g. a named scheduler or the rollup job. */
    public static String systemName(String component) {
        return "SYSTEM:" + component;
    }
}
