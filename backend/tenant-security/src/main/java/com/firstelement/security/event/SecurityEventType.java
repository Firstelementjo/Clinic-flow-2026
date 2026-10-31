package com.firstelement.security.event;

/**
 * The security/audit stream vocabulary.
 *
 * <p>Each constant carries the stable {@code audit_logs."action"} string, so a row
 * written by the Spring boundary is joinable with the 43 rows the legacy NestJS
 * runtime already wrote during Phase 2 verification.
 *
 * <p>PROJECT_REQUIREMENTS §5 asks for {@code AdminAuditEvent} and {@code SecurityEvent}
 * as separate streams. The split is done by convention here, because the Phase-2
 * migration {@code V202610040008} (audit/security streams) is still an inert
 * {@code .sql.todo} draft that this phase may not promote: these constants ending in
 * {@code LOGIN/SESSION/AUTHORIZATION/ROLE/MEMBERSHIP/AI_TOOL/REALTIME/RATE_LIMIT/CSRF}
 * are the security stream; {@link #BUSINESS_ACTION} is the admin stream. The target
 * tables arrive with the pending migration and the writer moves with them.
 */
public enum SecurityEventType {

    LOGIN_SUCCEEDED("User.Login.Succeeded"),
    LOGIN_FAILED("User.Login.Failed"),
    LOGOUT("User.Logout"),
    LOGIN_RATE_LIMITED("Auth.LoginRateLimited"),

    SESSION_ISSUED("Session.Issued"),
    SESSION_REJECTED("Session.Rejected"),
    SESSION_REVOKED("Session.Revoked"),
    SESSION_REVOKED_ALL_FOR_USER("Session.RevokedAllForUser"),

    AUTHORIZATION_DENIED("Authorization.Denied"),
    ACCESS_DENIED("Authorization.AccessDenied"),
    CSRF_REJECTED("Security.CsrfRejected"),
    CORS_REJECTED("Security.CorsRejected"),

    ROLE_CHANGED("Identity.RoleChanged"),
    MEMBERSHIP_CHANGED("Identity.MembershipChanged"),
    USER_DEACTIVATED("Identity.UserDeactivated"),

    AI_TOOL_DENIED("Ai.ToolDenied"),
    REALTIME_SUBSCRIPTION_DENIED("Realtime.SubscriptionDenied"),
    UNSCOPED_JOB_REJECTED("Job.UnscopedRejected"),

    /** Reserved for the Admin audit stream; never written by the security boundary. */
    BUSINESS_ACTION("Admin.BusinessAction");

    private final String auditAction;

    SecurityEventType(String auditAction) {
        this.auditAction = auditAction;
    }

    /** The value written to {@code audit_logs."action"}. */
    public String auditAction() {
        return auditAction;
    }
}