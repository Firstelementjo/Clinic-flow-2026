package com.firstelement.finance.ledger;

import com.firstelement.security.PrincipalType;
import com.firstelement.security.TenantContext;
import java.util.UUID;

/**
 * Who caused a usage event, as the ledger records it.
 *
 * <h2>Why this is a value and not four fields</h2>
 *
 * <p>{@code usage_events} carries {@code actorUserId} (nullable), {@code principalType} (mandatory)
 * and {@code principalName} (mandatory), and its CHECK requires
 * {@code actorUserId IS NOT NULL OR principalName <> ''}. Three fields describing one identity is
 * three ways to be half-right: a row with a user id and no name, a row whose name is an email, a row
 * whose principal type disagrees with the id.
 *
 * <p>So the identity is built here, once, and the record is what the writer reads. {@link #name()}
 * is always non-blank, {@link #type()} always one of the six values the column admits, and the
 * factories refuse combinations that do not make sense — a USER principal with no user id is the one
 * that has to fail, because it is the case that produces an unattributable row.
 *
 * <h2>Why the name is a label and never an email</h2>
 *
 * <p>{@code USER:<uuid>}, {@code SERVICE:<name>}, {@code JOB:<name>}. The ledger is read by finance
 * reviewers who are not necessarily entitled to every actor's {@code users.email}, and a finance
 * table is exactly where a leaked email column does most damage. Same rule as
 * {@code TenantContext.auditActorLabel()}, applied deliberately rather than by reuse.
 */
public record LedgerPrincipal(LedgerPrincipalType type, String name, UUID userId) {

    public LedgerPrincipal {
        if (type == null) {
            throw new IllegalArgumentException("A usage event must name a principal type");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException(
                "usage_events.principal_check requires a non-blank principalName; an unattributable "
                    + "usage event is an amount nobody can explain");
        }
        if (type == LedgerPrincipalType.USER && userId == null) {
            throw new IllegalArgumentException(
                "A USER principal must carry the user id; a row claiming a human caused the usage "
                    + "with no way to name the human is exactly the 'no principal' case the column "
                    + "CHECK exists to prevent.");
        }
    }

    /**
     * The principal behind an authenticated request or job.
     *
     * <p>{@code auditActorLabel()} already produces the right shape and is non-disclosing, so this
     * does not re-derive it.
     */
    public static LedgerPrincipal from(TenantContext context) {
        if (context == null) {
            throw new IllegalArgumentException(
                "A usage event must be attributed to a principal; refusing to write an "
                    + "unattributed row into the immutable ledger.");
        }
        return new LedgerPrincipal(
            context.isUser()
                ? LedgerPrincipalType.USER
                : LedgerPrincipalType.of(context.principalType()),
            context.auditActorLabel(),
            context.userId());
    }

    /** A named durable job, e.g. the nightly seat meter. */
    public static LedgerPrincipal job(String jobName) {
        return new LedgerPrincipal(LedgerPrincipalType.JOB, "JOB:" + jobName, null);
    }

    /** A named scheduler or the rollup itself. Not an identity; a component. */
    public static LedgerPrincipal system(String component) {
        return new LedgerPrincipal(LedgerPrincipalType.SYSTEM,
            LedgerPrincipalType.systemName(component), null);
    }

    /** A named connector adapter. */
    public static LedgerPrincipal connector(String connectorName) {
        return new LedgerPrincipal(LedgerPrincipalType.CONNECTOR, "CONNECTOR:" + connectorName, null);
    }

    /** A named AI agent, attributed to the run rather than to a person. */
    public static LedgerPrincipal aiAgent(String agentName) {
        return new LedgerPrincipal(LedgerPrincipalType.AI_AGENT, "AI_AGENT:" + agentName, null);
    }

    /** A named application service. */
    public static LedgerPrincipal service(String serviceName) {
        return new LedgerPrincipal(LedgerPrincipalType.SERVICE, "SERVICE:" + serviceName, null);
    }

    /** A human user. */
    public static LedgerPrincipal user(UUID userId) {
        return new LedgerPrincipal(LedgerPrincipalType.USER, "USER:" + userId, userId);
    }

    /** Whether a human caused this usage. */
    public boolean isHuman() {
        return type == LedgerPrincipalType.USER;
    }

    /**
     * Whether the principal is a {@link PrincipalType#WORKER} claim recorded as a job.
     *
     * <p>Named here so the mapping is stated once. {@code PrincipalType.WORKER} is an identity;
     * {@code usage_events.principalType} offers {@code JOB} instead, because a ledger row has to be
     * able to say <em>which</em> job.
     */
    public static LedgerPrincipalType forPrincipalType(PrincipalType principalType) {
        return LedgerPrincipalType.of(principalType);
    }
}
