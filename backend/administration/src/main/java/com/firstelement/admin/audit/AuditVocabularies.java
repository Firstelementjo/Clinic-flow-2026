package com.firstelement.admin.audit;

import com.firstelement.security.PrincipalType;

/**
 * The two closed vocabularies {@code admin_audit_events}, {@code security_events} and
 * {@code job_integration_events} are declared with, mirrored as Java enums so a typo cannot reach
 * PostgreSQL as a value the enum refuses.
 *
 * <h2>Why the principal mapping is a table and not a cast</h2>
 *
 * <p>{@code AuditPrincipalType} (the database enum, declared by
 * {@code V20261005050000}) has {@code JOB}; {@link PrincipalType} (the runtime taxonomy, Phase 3) has
 * {@code WORKER}. They are the same actor under two vocabularies, and the mapping is written out
 * here rather than left to a reader: an unlisted principal type fails closed, because the schema
 * CHECK on {@code principalType} would otherwise be the only thing deciding which word an audit row
 * gets, and the only way to find out is a constraint violation at runtime.
 */
public final class AuditVocabularies {

    private AuditVocabularies() {
    }

    /** Mirrors {@code public."AuditOutcome"}. */
    public enum AuditOutcome {
        SUCCESS,
        DENIED,
        FAILED;

        /** The wire/database spelling. */
        public String label() {
            return name();
        }
    }

    /** Mirrors {@code public."AuditPrincipalType"}. */
    public enum AuditPrincipalType {
        USER,
        SERVICE,
        AI_AGENT,
        JOB,
        CONNECTOR,
        SYSTEM;

        /**
         * The audit-stream spelling for a runtime principal.
         *
         * @throws IllegalArgumentException for a principal type this table does not cover. Fail
         *         closed: an audit row attributed to the wrong kind of actor is worse than no row.
         */
        public static AuditPrincipalType of(PrincipalType principalType) {
            if (principalType == null) {
                return SYSTEM;
            }
            return switch (principalType) {
                case USER -> USER;
                case SERVICE -> SERVICE;
                case CONNECTOR -> CONNECTOR;
                case AI_AGENT -> AI_AGENT;
                // The runtime says WORKER; the audit stream says JOB. A durable worker claim is
                // the audit stream's JOB, and the metadata names the queue so the two remain
                // joinable.
                case WORKER -> JOB;
            };
        }
    }

    /**
     * The closed {@code security_events."eventType"} list, as declared by
     * {@code V20261005050000}. It is a CHECK constraint, so a value outside it is a failed insert
     * rather than a warning; the enum makes the refusal happen in review instead.
     */
    public enum SecurityEventKind {
        LOGIN_SUCCEEDED,
        LOGIN_FAILED,
        LOGOUT,
        MFA_CHALLENGE,
        MFA_FAILED,
        PASSWORD_CHANGED,
        PASSWORD_RESET_REQUESTED,
        TOKEN_REFRESHED,
        TOKEN_REVOKED,
        PERMISSION_DENIED,
        ROLE_CHANGED,
        MEMBERSHIP_INVITED,
        MEMBERSHIP_REMOVED,
        IMPERSONATION_STARTED,
        IMPERSONATION_ENDED,
        RATE_LIMIT_EXCEEDED,
        CSRF_REJECTED,
        CSRF_VIOLATION,
        CROSS_TENANT_ACCESS_BLOCKED;

        public String label() {
            return name();
        }
    }

    /** The closed {@code job_integration_events."eventType"} list. */
    public enum JobEventKind {
        JOB_ENQUEUED,
        JOB_STARTED,
        JOB_SUCCEEDED,
        JOB_FAILED,
        JOB_RETRY_SCHEDULED,
        JOB_DEAD_LETTERED,
        JOB_REPLAYED,
        OUTBOX_PUBLISHED,
        OUTBOX_DELIVERED,
        OUTBOX_DEAD,
        CONNECTOR_REGISTERED,
        CONNECTOR_RECONNECTED,
        CONNECTOR_DISABLED,
        WEBHOOK_RECEIVED,
        WEBHOOK_REJECTED,
        SCHEDULER_TICK,
        SWEEP_EXECUTED;

        public String label() {
            return name();
        }
    }

    /**
     * The value written when a stream has no declared {@code retention_classes} row.
     *
     * <p>It is deliberately a visible sentinel rather than a plausible-looking class code: ADR-0009
     * §4 rule 4 says "no table in this ADR is purged by a scheduled job until the retention-class
     * column is populated for it", and an operator reading {@code UNDECLARED} on a row learns that
     * this tenant's retention configuration is incomplete. A silent {@code DEFAULT} would look like
     * a configured policy and would be the reason a purge job ran against an unagreed window.
     */
    public static final String UNDECLARED_RETENTION_CLASS = "UNDECLARED";
}