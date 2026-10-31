package com.firstelement.security;

/**
 * What a non-human principal is allowed to be.
 *
 * <p>PROJECT_REQUIREMENTS §3.4: "A workflow must not silently grant an AI agent
 * more access than the agent policy allows." That is enforced structurally here —
 * {@link #mayBeHeldBy(PrincipalType)} is a closed table, and
 * {@link TenantContext}'s constructor rejects an inconsistent combination. There
 * is no code path that "just adds" an administrative capability to an agent.
 */
public enum Capability {

    /** Read/update the organization record itself. Service identities only. */
    ORGANIZATION_MANAGE,
    /** Read users and memberships; used by the Admin read models. */
    MEMBERSHIP_READ,
    /** Change roles and project memberships. Service identities only; always audited. */
    MEMBERSHIP_MANAGE,
    /** Revoke another user's sessions. Service identities only. */
    SESSION_REVOKE,
    /** Read the audit and security event streams. */
    AUDIT_READ,
    /** Read the permission inspector. */
    PERMISSION_INSPECTOR_READ,

    /** Accept inbound provider events and enqueue processing. */
    WEBHOOK_INGEST,
    /** Perform outbound delivery through a channel connection. */
    MESSAGING_SEND,

    /** Parse/embed/index knowledge documents. */
    DOCUMENT_INGEST,
    /** Run tenant- and project-filtered vector retrieval. */
    KNOWLEDGE_RETRIEVE,
    /** Execute an AI run under application-owned policy. */
    AI_RUN,
    /** Execute an application-owned tool on behalf of a run. */
    AI_TOOL_EXECUTE,

    /** Build a report read model. */
    REPORT_WRITE,
    /** Create and run an export job. */
    EXPORT_WRITE,
    /** Rebuild finance aggregates from the usage ledger. */
    FINANCE_AGGREGATE;

    /**
     * The closed table of which principal types may hold a capability.
     * Anything not listed is refused, so a new capability fails closed until it
     * is deliberately granted.
     */
    public boolean mayBeHeldBy(PrincipalType type) {
        return switch (this) {
            case ORGANIZATION_MANAGE, MEMBERSHIP_MANAGE, SESSION_REVOKE,
                 MEMBERSHIP_READ, PERMISSION_INSPECTOR_READ ->
                type == PrincipalType.SERVICE;
            case AUDIT_READ ->
                type == PrincipalType.SERVICE || type == PrincipalType.WORKER;
            case WEBHOOK_INGEST, MESSAGING_SEND ->
                type == PrincipalType.CONNECTOR || type == PrincipalType.WORKER;
            case DOCUMENT_INGEST ->
                type == PrincipalType.WORKER || type == PrincipalType.AI_AGENT;
            case KNOWLEDGE_RETRIEVE, AI_RUN ->
                type == PrincipalType.WORKER || type == PrincipalType.AI_AGENT;
            case AI_TOOL_EXECUTE ->
                type == PrincipalType.WORKER || type == PrincipalType.AI_AGENT;
            case REPORT_WRITE, EXPORT_WRITE, FINANCE_AGGREGATE ->
                type == PrincipalType.WORKER;
        };
    }

    /** Human principals are never capability-bearing; they hold a role instead. */
    public boolean mayBeHeldByHuman() {
        return false;
    }

    /**
     * Whether a human-role grant for this permission would be meaningless for a
     * capability. Used by the capability policy to keep the two grant models from
     * quietly overlapping.
     */
    public boolean isAdministrative() {
        return switch (this) {
            case ORGANIZATION_MANAGE, MEMBERSHIP_MANAGE, SESSION_REVOKE,
                 MEMBERSHIP_READ, PERMISSION_INSPECTOR_READ -> true;
            default -> false;
        };
    }
}