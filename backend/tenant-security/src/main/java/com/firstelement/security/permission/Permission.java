package com.firstelement.security.permission;

/**
 * The complete capability catalogue, ported verbatim from
 * {@code packages/auth/src/policy.ts} so the Spring boundary and the legacy
 * NestJS runtime agree on the vocabulary during the strangler window.
 *
 * <p>Two rules keep this list honest:
 *
 * <ol>
 *   <li>The wire value is the legacy string ({@code "project:read"}). It is what
 *       an audit row, a policy event, and the permission inspector print, so old
 *       and new evidence is joinable.</li>
 *   <li>Phase 3 adds only what the security boundary itself needs
 *       ({@link #SECURITY_EVENT_READ}, {@link #PERMISSION_INSPECTOR_READ},
 *       {@link #SESSION_REVOKE}, {@link #USER_MANAGE}) and grants them to
 *       {@code ADMIN} only. No legacy permission was removed or renamed, and no
 *       legacy {@code EMPLOYEE} grant was widened — see {@link RolePermissions}.</li>
 * </ol>
 */
public enum Permission {

    ORGANIZATION_READ("organization:read"),
    ORGANIZATION_UPDATE("organization:update"),

    PROJECT_READ("project:read"),
    PROJECT_CREATE("project:create"),
    PROJECT_UPDATE("project:update"),
    PROJECT_DELETE("project:delete"),
    PROJECT_ARCHIVE("project:archive"),

    EMPLOYEE_READ("employee:read"),
    EMPLOYEE_CREATE("employee:create"),
    EMPLOYEE_UPDATE("employee:update"),
    EMPLOYEE_DELETE("employee:delete"),
    EMPLOYEE_IMPORT("employee:import"),
    EMPLOYEE_SET_OWN_AVAILABILITY("employee:set-own-availability"),

    CUSTOMER_READ("customer:read"),
    CUSTOMER_UPDATE("customer:update"),

    ACCOUNT_READ("account:read"),
    ACCOUNT_CREATE("account:create"),
    ACCOUNT_UPDATE("account:update"),
    ACCOUNT_DELETE("account:delete"),

    NOTE_READ("note:read"),
    NOTE_CREATE("note:create"),
    NOTE_UPDATE("note:update"),
    NOTE_DELETE("note:delete"),

    VIEW_READ("view:read"),
    VIEW_MANAGE("view:manage"),

    LEAD_READ("lead:read"),
    LEAD_CREATE("lead:create"),
    LEAD_UPDATE("lead:update"),
    LEAD_CONVERT("lead:convert"),
    LEAD_DELETE("lead:delete"),
    DEAL_READ("deal:read"),
    DEAL_CREATE("deal:create"),
    DEAL_UPDATE("deal:update"),
    DEAL_CLOSE("deal:close"),
    DEAL_DELETE("deal:delete"),
    TASK_READ("task:read"),
    TASK_CREATE("task:create"),
    TASK_UPDATE("task:update"),
    TASK_DELETE("task:delete"),

    CONVERSATION_READ("conversation:read"),
    CONVERSATION_REPLY("conversation:reply"),
    CONVERSATION_TAKE_OVER("conversation:take-over"),
    CONVERSATION_RETURN_TO_AI("conversation:return-to-ai"),
    CONVERSATION_ASSIGN("conversation:assign"),
    CONVERSATION_RESOLVE("conversation:resolve"),

    AI_AGENT_READ("ai-agent:read"),
    AI_AGENT_CONFIGURE("ai-agent:configure"),
    AI_RUN_READ("ai-run:read"),
    /**
     * Send a customer-visible AI reply. Coarse on purpose: ADR-0007 Part 2 decides whether a send is
     * permitted at all (AI mode, and never after a human takeover), and this permission only decides
     * whether the principal is the kind that may send one. It is deliberately not a read permission,
     * so it can never be satisfied by an employee who may only view runs.
     */
    AI_SEND("ai-send"),
    KNOWLEDGE_READ("knowledge:read"),
    KNOWLEDGE_MANAGE("knowledge:manage"),

    CHANNEL_READ("channel:read"),
    CHANNEL_MANAGE("channel:manage"),
    ESCALATION_RULE_READ("escalation-rule:read"),
    ESCALATION_RULE_MANAGE("escalation-rule:manage"),
    ROUTING_RULE_MANAGE("routing-rule:manage"),
    /**
     * Author, version, activate, roll back, and dry-run workflow definitions.
     *
     * <p>Separate from {@link #ESCALATION_RULE_MANAGE} on purpose: escalation rules are one input to
     * routing, workflow definitions orchestrate the whole conversation lifecycle, and an operator
     * reviewing least privilege needs to be able to grant them independently.
     */
    WORKFLOW_MANAGE("workflow:manage"),
    /**
     * Author and version SLA policies, working-hours calendars, and holiday calendars. Never implied
     * by {@link #WORKFLOW_MANAGE}: an administrator who may change a workflow must not silently gain
     * the ability to change what the SLA promises a customer.
     */
    SLA_MANAGE("sla:manage"),

    ANALYTICS_READ_PROJECT("analytics:read-project"),
    ANALYTICS_READ_ORGANIZATION("analytics:read-organization"),

    // ---- clinic read model (Phase 37) -------------------------------------
    //
    // Three members, no more, and each names the disclosure it authorizes rather
    // than the table it reads (ADR-0010 §2). The wire values are the Phase-33
    // legacy strings, copied verbatim from packages/auth/src/policy.ts, so an audit
    // row written by the frozen runtime and one written here join on the same value
    // and the strangler window has one vocabulary rather than two.
    //
    // WHAT IS DELIBERATELY ABSENT, and this is the interesting part:
    //
    //   * `doctor:read-management` IS ported (Phase 39), because the management
    //     screen it discloses to now exists in this runtime. Phase 33 split it off
    //     from the roster read because it adds clinician EMAIL and PHONE. Keeping it
    //     separate from `doctor:read` is what makes the split meaningful: an employee
    //     holds the roster (name, specialty, working hours -- what a booking form
    //     needs) and not a colleague's contact details. The management grants are
    //     ADMIN-only by default, so porting the read half does not hand every
    //     front-desk clerk the roster's contact list.
    //
    //   * No `doctor:create/update/delete`, no `procedure:create/update/delete`, no
    //     `appointment:create/reschedule/cancel`, no `reminder:*`, no
    //     `schedule:read-availability`, no `clinic:simulate-ai`. This module is
    //     READ-ONLY (the bounded slice), so a mutation permission with no route is
    //     a grant nobody can exercise and an audit vocabulary that names actions
    //     the Spring runtime cannot perform. Phase 33's full set is owed with the
    //     mutations it authorizes, not ahead of them.
    //
    //   * No `appointment:read` for the *analytics* routes: analytics keeps
    //     `analytics:read-project`, which already exists, is already EMPLOYEE-held,
    //     and is what the legacy `GET /clinic/analytics` declared. Borrowing a new
    //     permission there would have been a second vocabulary for one route.
    //
    // WHY `doctor:read` STILL IS NOT `customer:read`. An appointment names a
    // patient, so the naive move is to reuse the customer grant. It would be false:
    // this permission also discloses a clinician's working days, working hours and
    // slot length, and a booking count per procedure -- none of which is on a
    // customer record. ADR-0010's rule ("a permission is named after what it
    // authorizes") is the reason a clinic vocabulary exists at all.
    DOCTOR_READ("doctor:read"),
    PROCEDURE_READ("procedure:read"),
    /**
     * Read the appointment schedule.
     *
     * <p>The appointment lifecycle's WRITE permissions are not here; see the class
     * comment for why the read is separated from create/reschedule/cancel.
     */
    APPOINTMENT_READ("appointment:read"),
    /**
     * Create and edit physical clinic locations.
     *
     * <p>Phase 38. This is the mutation the read-only slice deliberately left out.
     * It is a separate grant from {@link #DOCTOR_READ}: reading the roster a booking
     * form needs is not the same act as changing a clinic's address, contact number
     * or slot size. Held by ADMIN only by default, through
     * {@code RolePermissions.ADMIN = EnumSet.allOf(Permission.class)}, so the grant is
     * a consequence of being the administrator role rather than an exception bolted
     * onto it.
     */
    CLINIC_MANAGE("clinic:manage"),
    /**
     * Create and edit service-catalogue entries (procedures).
     *
     * <p>Phase 38. A price or a discount here becomes money on an invoice, so the
     * write is not implied by {@link #PROCEDURE_READ}. Held by ADMIN only by default
     * for the same reason as {@link #CLINIC_MANAGE}.
     */
    PROCEDURE_MANAGE("procedure:manage"),
    /**
     * Read a doctor's management record: email, phone, address, commission and the
     * profile the Create Doctor drawer edits.
     *
     * <p>Phase 39. Deliberately separate from {@link #DOCTOR_READ}: the roster read a
     * booking form needs must not disclose a clinician's contact details, which is
     * the split Phase 33 drew and this permission preserves. Held by ADMIN only by
     * default.
     */
    DOCTOR_READ_MANAGEMENT("doctor:read-management"),
    /**
     * Create, edit and retire doctor management records.
     *
     * <p>Phase 39. Separate from {@link #DOCTOR_READ_MANAGEMENT} for the same reason
     * {@link #CLINIC_MANAGE} is separate from a clinic read.
     */
    DOCTOR_MANAGE("doctor:manage"),
    /**
     * Read the front-desk officers: the {@code CLINIC_STAFF} accounts behind
     * reception, with the clinic each one is assigned to.
     *
     * <p>Phase 39. An account read rather than a roster read, because it discloses
     * the email and phone that identify a sign-in account.
     */
    FRONT_DESK_READ("front-desk:read"),
    /**
     * Add, edit and remove front-desk officers.
     *
     * <p>Phase 39. Creating one provisions a {@code CLINIC_STAFF} account and a
     * project membership, so the write is not implied by {@link #FRONT_DESK_READ}.
     */
    FRONT_DESK_MANAGE("front-desk:manage"),
    /**
     * Author, version, publish and retire <b>report definitions</b> and <b>document templates</b>.
     *
     * <p>Phase 31. This permission is owed and was previously borrowed: every authoring entry point in
     * {@code :backend:reporting-exports} required {@link #WORKFLOW_MANAGE}, so a workflow administrator
     * could silently change what a finance export contains. ADR 0010 §2 — "a permission is named after
     * what it authorizes" — is not a naming preference: an audit row that says {@code workflow:manage}
     * while the actor changed a finance report's columns is a row that asserts something untrue, which is
     * worse than a missing one.
     *
     * <p>Never implied by {@link #WORKFLOW_MANAGE} and never implied by
     * {@link #ANALYTICS_READ_PROJECT}. Reading a report catalogue is an analytics read; deciding what a
     * report <em>contains</em> is the authoring decision, and the two are granted to different people in
     * every real deployment — a finance analyst reads the ledger report, a reporting administrator
     * defines it.
     *
     * <p><b>Granted to ADMIN only</b>, and deliberately not to any {@code Capability}: see the comment on
     * {@code RolePermissions.BY_CAPABILITY} for the reason, which is a schema fact rather than a
     * preference. {@code report_versions."createdByUserId"} is NOT NULL with a composite tenant foreign
     * key to {@code users}, so a machine principal could create an unattributable report definition and
     * then no version at all. A half-usable grant is worse than none.
     */
    REPORT_MANAGE("report:manage"),
    AUDIT_LOG_READ("audit-log:read"),
    SYSTEM_HEALTH_READ("system-health:read"),

    // ---- added in Phase 3 for the security boundary itself (ADMIN only) ----
    /** Read the audit + security event streams in the Admin surface. */
    SECURITY_EVENT_READ("security-event:read"),
    /** Inspect another user's effective permissions. */
    PERMISSION_INSPECTOR_READ("permission-inspector:read"),
    /** Revoke another user's sessions. */
    SESSION_REVOKE("session:revoke"),
    /** Create/deactivate users. */
    USER_MANAGE("user:manage"),
    /** Grant and revoke project memberships. */
    MEMBERSHIP_MANAGE("membership:manage");

    private final String value;

    Permission(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static Permission fromValue(String value) {
        for (Permission permission : values()) {
            if (permission.value.equals(value)) {
                return permission;
            }
        }
        throw new IllegalArgumentException("Unknown permission: " + value);
    }

    @Override
    public String toString() {
        return value;
    }
}