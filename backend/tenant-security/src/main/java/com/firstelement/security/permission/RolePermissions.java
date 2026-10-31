package com.firstelement.security.permission;

import com.firstelement.security.Capability;
import com.firstelement.security.PrincipalType;
import com.firstelement.security.UserRole;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The single grant table. Two disjoint models, no fallthrough.
 *
 * <h2>Humans: role</h2>
 *
 * <p>{@code ADMIN} holds every permission (the legacy behaviour: the legacy
 * matrix used {@code Object.values(Permission)}). {@code EMPLOYOYEE} holds exactly
 * the legacy {@code EMPLOYEE_PERMISSIONS} list, unchanged, plus nothing. Phase 3
 * deliberately did not widen the employee grant: the inventory records the clinic
 * module shipping without any permission decorator, and the fix for that is an
 * explicit per-route decision by the clinic/CRM owner, not a blanket widening
 * here.
 *
 * <h2>Machines: capability</h2>
 *
 * <p>A machine principal has no role and is never evaluated by
 * {@link #forRole}. It is authorized by {@link #forCapabilities}, which maps the
 * explicitly granted {@link Capability} set to permissions. An empty capability set
 * grants nothing — deny by default.
 *
 * <h2>Phase 31: REPORT_MANAGE is ADMIN-only, and this is the decision</h2>
 *
 * <p>{@code ADMIN} is {@link EnumSet#allOf} of {@link Permission}, so it holds
 * {@link Permission#REPORT_MANAGE} with no line of configuration — which is the
 * honest way to say "granted deliberately to administrators and nobody else": the
 * grant is a consequence of the role being the administrator role, not an
 * exception bolted onto it. No {@code Capability} entry carries it, and the reason
 * is recorded at {@code BY_CAPABILITY}'s {@code REPORT_WRITE} row rather than only
 * here.
 *
 * <p>What this also settles: {@link Permission#WORKFLOW_MANAGE} and
 * {@link Permission#REPORT_MANAGE} are now granted to the <em>same</em> set of
 * principals (ADMIN), so no principal can change a workflow without also being
 * able to change what a finance export contains. Before Phase 31 the reporting
 * module borrowed {@code WORKFLOW_MANAGE}, which made the two independent in name
 * and identical in effect — an audit row attributing a report-body change to
 * {@code workflow:manage}.
 */
public final class RolePermissions {

    private static final Set<Permission> ADMIN = Collections.unmodifiableSet(EnumSet.allOf(Permission.class));

    /**
     * Operator (Platform Admin) permissions.
     *
     * <p>Strict isolation: Operators hold system oversight capabilities, but MUST NOT hold
     * any clinical, doctor, procedure, appointment, patient, encounter, clinical-note,
     * radiograph or clinic-finance permissions.
     */
    private static final Set<Permission> OPERATOR = Collections.unmodifiableSet(EnumSet.of(
        Permission.SYSTEM_HEALTH_READ,
        Permission.AUDIT_LOG_READ,
        Permission.SECURITY_EVENT_READ,
        Permission.PERMISSION_INSPECTOR_READ,
        Permission.SESSION_REVOKE,
        Permission.USER_MANAGE,
        Permission.MEMBERSHIP_MANAGE,
        Permission.ORGANIZATION_READ,
        Permission.PROJECT_READ
    ));

    private static final Set<Permission> EMPLOYEE = Collections.unmodifiableSet(EnumSet.of(
        Permission.ORGANIZATION_READ,
        Permission.PROJECT_READ,
        Permission.EMPLOYEE_READ,
        Permission.EMPLOYEE_SET_OWN_AVAILABILITY,
        Permission.CUSTOMER_READ,
        Permission.CUSTOMER_UPDATE,
        Permission.ACCOUNT_READ,
        Permission.ACCOUNT_CREATE,
        Permission.ACCOUNT_UPDATE,
        Permission.ACCOUNT_DELETE,
        Permission.NOTE_READ,
        Permission.NOTE_CREATE,
        Permission.NOTE_UPDATE,
        Permission.NOTE_DELETE,
        Permission.VIEW_READ,
        Permission.VIEW_MANAGE,
        Permission.LEAD_READ,
        Permission.LEAD_CREATE,
        Permission.LEAD_UPDATE,
        Permission.LEAD_CONVERT,
        Permission.LEAD_DELETE,
        Permission.DEAL_READ,
        Permission.DEAL_CREATE,
        Permission.DEAL_UPDATE,
        Permission.DEAL_CLOSE,
        Permission.DEAL_DELETE,
        Permission.TASK_READ,
        Permission.TASK_CREATE,
        Permission.TASK_UPDATE,
        Permission.TASK_DELETE,
        Permission.CONVERSATION_READ,
        Permission.CONVERSATION_REPLY,
        Permission.CONVERSATION_TAKE_OVER,
        Permission.CONVERSATION_RETURN_TO_AI,
        Permission.CONVERSATION_ASSIGN,
        Permission.CONVERSATION_RESOLVE,
        Permission.AI_RUN_READ,
        Permission.KNOWLEDGE_READ,
        Permission.ANALYTICS_READ_PROJECT,
        // Phase 37: the clinic READ model, and only the read half.
        //
        // <p>Ported verbatim from the legacy `CLINIC_EMPLOYEE_GRANT` in
        // `packages/auth/src/policy.ts`, restricted to the three members Phase 37
        // actually routes. That legacy set also granted
        // `SCHEDULE_AVAILABILITY_READ`, `APPOINTMENT_CREATE/RESCHEDULE/CANCEL` and the
        // three `REMINDER_*` members, and none of those is ported: this module
        // exposes no route they would authorize, so granting them would produce a
        // permission an employee holds that no code can exercise -- and an audit row
        // naming `appointment:cancel` would describe an action the Spring runtime
        // does not perform.
        //
        // <p>`ANALYTICS_READ_PROJECT` above was already present and already covers the
        // clinic analytics routes, matching what the legacy `GET /clinic/analytics`
        // declared. It is not repeated here.
        //
        // <p>What is still withheld from an employee is the interesting half, and it
        // is Phase 33's split rather than a Phase 37 decision:
        // `DOCTOR_READ_MANAGEMENT` (clinician email + phone + appointment count) and
        // `PROCEDURE_READ_MANAGEMENT` (appointment count per procedure) are ADMIN-only
        // in the legacy matrix. Phase 39 ports `DOCTOR_READ_MANAGEMENT` because the
        // management screen it discloses to now exists, but keeps it out of this set.
        // An employee can see that a clinician works Tuesday to Thursday from 09:00 to
        // 17:00 -- what a booking form needs -- and cannot see that clinician's phone
        // number.
        Permission.DOCTOR_READ,
        Permission.PROCEDURE_READ,
        Permission.APPOINTMENT_READ));

    /**
     * The clinic front desk, and a strict SUBSET of {@link #EMPLOYEE}.
     *
     * <p>The value of this role is entirely in the denials. {@code EMPLOYEE} carries the whole CRM
     * read surface — {@code NOTE_READ}, {@code CUSTOMER_READ}, {@code CONVERSATION_READ},
     * {@code DEAL_READ}, {@code TASK_READ} — so before the role existed the only way to employ a
     * receptionist was to hand a booking clerk the ability to read every clinical note in the
     * organization. {@code CLINIC_STAFF} books and reads the roster, and reaches none of it.
     *
     * <p>Deliberately a subset, never a superset: it can only remove reach. It does not administer
     * the roster (no clinic mutation is routed in this module, so no clinic mutation permission is
     * granted — a grant with no route behind it describes an action the runtime does not perform)
     * and it does not read clinician contact details.
     *
     * <p>{@code ANALYTICS_READ_PROJECT} is included because the clinic metrics route is a project
     * analytics read and every metric on it drills to source rows within that project. It is
     * project-scoped like its {@code EMPLOYEE} counterpart, so it exposes no organization's numbers.
     */
    private static final Set<Permission> CLINIC_STAFF = Collections.unmodifiableSet(EnumSet.of(
        Permission.ORGANIZATION_READ,
        Permission.PROJECT_READ,
        Permission.DOCTOR_READ,
        Permission.PROCEDURE_READ,
        Permission.APPOINTMENT_READ,
        Permission.ANALYTICS_READ_PROJECT));

    private static final Map<UserRole, Set<Permission>> BY_ROLE = Map.of(
        UserRole.ADMIN, ADMIN,
        UserRole.EMPLOYEE, EMPLOYEE,
        UserRole.CLINIC_STAFF, CLINIC_STAFF);

    /** Capability -> permission grants for non-human principals. */
    private static final Map<Capability, Set<Permission>> BY_CAPABILITY = Map.ofEntries(
        Map.entry(Capability.ORGANIZATION_MANAGE,
            EnumSet.of(Permission.ORGANIZATION_READ, Permission.ORGANIZATION_UPDATE)),
        Map.entry(Capability.MEMBERSHIP_READ,
            EnumSet.of(Permission.PROJECT_READ, Permission.EMPLOYEE_READ)),
        Map.entry(Capability.MEMBERSHIP_MANAGE,
            EnumSet.of(Permission.PROJECT_READ, Permission.EMPLOYEE_READ,
                Permission.USER_MANAGE, Permission.MEMBERSHIP_MANAGE)),
        Map.entry(Capability.SESSION_REVOKE, EnumSet.of(Permission.SESSION_REVOKE)),
        Map.entry(Capability.AUDIT_READ,
            EnumSet.of(Permission.AUDIT_LOG_READ, Permission.SECURITY_EVENT_READ)),
        Map.entry(Capability.PERMISSION_INSPECTOR_READ,
            EnumSet.of(Permission.PERMISSION_INSPECTOR_READ)),
        Map.entry(Capability.WEBHOOK_INGEST,
            EnumSet.of(Permission.CONVERSATION_READ, Permission.CHANNEL_READ)),
        Map.entry(Capability.MESSAGING_SEND,
            EnumSet.of(Permission.CONVERSATION_REPLY, Permission.CHANNEL_READ)),
        Map.entry(Capability.DOCUMENT_INGEST,
            EnumSet.of(Permission.KNOWLEDGE_MANAGE)),
        Map.entry(Capability.KNOWLEDGE_RETRIEVE,
            EnumSet.of(Permission.KNOWLEDGE_READ, Permission.AI_RUN_READ)),
        Map.entry(Capability.AI_RUN,
            // AI_SEND is granted here, not to AI_TOOL_EXECUTE: holding the ability to run a tool
            // must not by itself grant the ability to speak to a customer. ADR-0007 Part 2 keeps the
            // mode/takeover decision in application policy; this grant only says the principal is
            // the kind that may send at all.
            EnumSet.of(Permission.AI_RUN_READ, Permission.AI_SEND)),
        Map.entry(Capability.AI_TOOL_EXECUTE,
            EnumSet.of(Permission.CONVERSATION_READ, Permission.CUSTOMER_READ)),
        Map.entry(Capability.REPORT_WRITE,
            // ANALYTICS reads only. REPORT_MANAGE is deliberately ABSENT, and Phase 31 records why as a
            // schema fact rather than a preference:
            //
            //   report_versions."createdByUserId" is NOT NULL and carries a composite tenant foreign key to
            //   users(id, organizationId). A machine principal has no user id, so granting it
            //   REPORT_MANAGE would let it create a report_definitions row — which has no author column at
            //   all, so the definition would be unattributable — and then fail on the first version. The
            //   result is a catalogue entry nobody can publish, authored by nobody, which is precisely the
            //   dead configuration V20261005060000's Arabic-name CHECKs exist to prevent.
            //
            //   REPORT_WRITE therefore means "build a read model from the reports that exist". Widening it
            //   to "decide what a report is" is a separate decision with a separate migration (an author
            //   column that accepts a service principal), not a line in this table.
            EnumSet.of(Permission.ANALYTICS_READ_PROJECT, Permission.ANALYTICS_READ_ORGANIZATION)),
        Map.entry(Capability.EXPORT_WRITE,
            EnumSet.of(Permission.ANALYTICS_READ_PROJECT, Permission.ANALYTICS_READ_ORGANIZATION)),
        Map.entry(Capability.FINANCE_AGGREGATE,
            EnumSet.of(Permission.ANALYTICS_READ_ORGANIZATION))
    );

    private RolePermissions() {
    }

    /** @return permissions held by a human role. Empty when the role is absent. */
    public static Set<Permission> forRole(UserRole role) {
        return role == null ? Set.of() : BY_ROLE.getOrDefault(role, Set.of());
    }

    /** @return permissions granted by an explicit machine capability set. */
    public static Set<Permission> forCapabilities(Set<Capability> capabilities) {
        if (capabilities == null || capabilities.isEmpty()) {
            return Set.of();
        }
        EnumSet<Permission> granted = EnumSet.noneOf(Permission.class);
        for (Capability capability : capabilities) {
            granted.addAll(BY_CAPABILITY.getOrDefault(capability, Set.of()));
        }
        return Collections.unmodifiableSet(granted);
    }

    /**
     * The single entry point used by {@code AuthorizationService}. Dispatches on the
     * principal taxonomy and never mixes the two grant models.
     */
    public static Set<Permission> forPrincipalType(PrincipalType type,
                                                    UserRole role,
                                                    Set<Capability> capabilities,
                                                    boolean isPlatformAdmin) {
        if (type == PrincipalType.USER) {
            if (role == UserRole.ADMIN) {
                return ADMIN;
            }
            return isPlatformAdmin ? OPERATOR : forRole(role);
        }
        return forCapabilities(capabilities);
    }

    public static Set<Permission> forPrincipalType(PrincipalType type,
                                                    UserRole role,
                                                    Set<Capability> capabilities) {
        return forPrincipalType(type, role, capabilities, false);
    }
}