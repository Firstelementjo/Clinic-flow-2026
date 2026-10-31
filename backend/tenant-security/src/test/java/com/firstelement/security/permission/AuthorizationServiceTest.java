package com.firstelement.security.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstelement.security.Capability;
import com.firstelement.security.PrincipalType;
import com.firstelement.security.TenantAuthentication;
import com.firstelement.security.TenantContext;
import com.firstelement.security.UserRole;
import com.firstelement.security.error.PolicyDeniedException;
import com.firstelement.security.event.SecurityEvent;
import com.firstelement.security.event.SecurityEventType;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

/**
 * The authorization decision table, including the two distinct denial shapes.
 */
class AuthorizationServiceTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID OTHER_ORG = UUID.randomUUID();
    private static final UUID PROJECT_A = UUID.randomUUID();
    private static final UUID PROJECT_B = UUID.randomUUID();

    private final List<SecurityEvent> events = new ArrayList<>();
    private AuthorizationService authorization;

    @BeforeEach
    void setUp() {
        events.clear();
        authorization = new AuthorizationService(event -> events.add(event));
    }

    private TenantContext employee(Set<UUID> projects) {
        return TenantContext.forUser(UUID.randomUUID(), UUID.randomUUID(), ORG,
            UserRole.EMPLOYEE, projects);
    }

    private TenantContext admin() {
        return TenantContext.forUser(UUID.randomUUID(), UUID.randomUUID(), ORG,
            UserRole.ADMIN, List.of(PROJECT_A, PROJECT_B));
    }

    // ------------------------------------------------------------------
    // grants
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an EMPLOYEE keeps the legacy grant set, plus exactly the three clinic READ grants")
    void employeeGrantSetIsUnchanged() {
        Set<Permission> granted = RolePermissions.forRole(UserRole.EMPLOYEE);

        // Spot checks against packages/auth/src/policy.ts EMPLOYEE_PERMISSIONS.
        assertThat(granted).contains(
            Permission.ORGANIZATION_READ, Permission.PROJECT_READ, Permission.EMPLOYEE_READ,
            Permission.CUSTOMER_READ, Permission.CUSTOMER_UPDATE, Permission.CONVERSATION_REPLY,
            Permission.CONVERSATION_ASSIGN, Permission.CONVERSATION_RESOLVE,
            Permission.KNOWLEDGE_READ, Permission.AI_RUN_READ, Permission.ANALYTICS_READ_PROJECT,
            Permission.EMPLOYEE_SET_OWN_AVAILABILITY);
        assertThat(granted).containsExactlyInAnyOrderElementsOf(java.util.EnumSet.of(
            Permission.ORGANIZATION_READ, Permission.PROJECT_READ, Permission.EMPLOYEE_READ,
            Permission.EMPLOYEE_SET_OWN_AVAILABILITY, Permission.CUSTOMER_READ,
            Permission.CUSTOMER_UPDATE, Permission.ACCOUNT_READ, Permission.ACCOUNT_CREATE,
            Permission.ACCOUNT_UPDATE, Permission.ACCOUNT_DELETE, Permission.NOTE_READ,
            Permission.NOTE_CREATE, Permission.NOTE_UPDATE, Permission.NOTE_DELETE,
            Permission.VIEW_READ, Permission.VIEW_MANAGE, Permission.LEAD_READ,
            Permission.LEAD_CREATE, Permission.LEAD_UPDATE, Permission.LEAD_CONVERT,
            Permission.LEAD_DELETE, Permission.DEAL_READ, Permission.DEAL_CREATE,
            Permission.DEAL_UPDATE, Permission.DEAL_CLOSE, Permission.DEAL_DELETE,
            Permission.TASK_READ, Permission.TASK_CREATE, Permission.TASK_UPDATE,
            Permission.TASK_DELETE, Permission.CONVERSATION_READ, Permission.CONVERSATION_REPLY,
            Permission.CONVERSATION_TAKE_OVER, Permission.CONVERSATION_RETURN_TO_AI,
            Permission.CONVERSATION_ASSIGN, Permission.CONVERSATION_RESOLVE,
            Permission.AI_RUN_READ, Permission.KNOWLEDGE_READ,
            Permission.ANALYTICS_READ_PROJECT,
            // Phase 37 clinic read model -- the read half only. See the assertion below for why
            // nothing else from the Phase-33 clinic vocabulary is here.
            Permission.DOCTOR_READ, Permission.PROCEDURE_READ, Permission.APPOINTMENT_READ));

        // The four Phase-3 additions are ADMIN-only.
        assertThat(granted).doesNotContain(Permission.SECURITY_EVENT_READ,
            Permission.PERMISSION_INSPECTOR_READ, Permission.SESSION_REVOKE,
            Permission.USER_MANAGE, Permission.MEMBERSHIP_MANAGE);
        assertThat(RolePermissions.forRole(UserRole.ADMIN))
            .containsAll(java.util.EnumSet.allOf(Permission.class));
    }

    /**
     * Phase 37: what the clinic grant deliberately withholds.
     *
     * <p>Two separate withholdings, asserted separately because they have different reasons and
     * different follow-ups.
     *
     * <p><b>The management split.</b> Phase 33 drew a line between the bookable roster and the
     * roster <em>plus contact details and appointment counts</em>, and put the second behind
     * ADMIN. If that distinction is ever lost -- by widening {@code DOCTOR_READ}, or by
     * granting {@code DOCTOR_READ_MANAGEMENT} to an employee -- every front-desk clerk in the
     * tenant gains a colleague's email and phone number. The members do not exist in the enum
     * yet, so this asserts the shape rather than the values: {@code DOCTOR_READ} must be held
     * while no clinic permission outside the three read members is.
     *
     * <p><b>The mutations.</b> This module exposes no clinic write route, so granting
     * {@code APPOINTMENT_CREATE} would produce a permission an employee holds that no code can
     * exercise, and an audit row naming {@code appointment:cancel} would describe an action the
     * Spring runtime does not perform. Permission is owed together with the route that
     * authorizes it.
     */
    @Test
    @DisplayName("the clinic grant is the read half only: no management view, no mutation")
    void theClinicGrantIsReadOnly() {
        Set<Permission> employee = RolePermissions.forRole(UserRole.EMPLOYEE);

        assertThat(employee)
            .as("Phase 33's split is the point: the roster a booking form needs, and NOT the "
                + "contact details and appointment counts behind doctor:read-management")
            .containsAll(EnumSet.of(
                Permission.DOCTOR_READ, Permission.PROCEDURE_READ, Permission.APPOINTMENT_READ));

        assertThat(EnumSet.copyOf(employee).stream().filter(this::isClinicVocabulary).toList())
            .as("named explicitly, so a fourth clinic member is a diff a reviewer sees here")
            .containsExactlyInAnyOrder(
                Permission.DOCTOR_READ, Permission.PROCEDURE_READ, Permission.APPOINTMENT_READ);
    }

    /**
     * Whether a permission belongs to the clinic vocabulary this phase introduced.
     *
     * <p>Keyed on the wire value's {@code doctor:}/{@code procedure:}/{@code appointment:} prefix
     * rather than on the three constants, so a future {@code DOCTOR_READ_MANAGEMENT} or
     * {@code APPOINTMENT_CREATE} is caught by <em>this</em> assertion instead of quietly
     * appearing in the grant set.
     */
    private boolean isClinicVocabulary(Permission permission) {
        String value = permission.value();
        return value.startsWith("doctor:") || value.startsWith("procedure:")
            || value.startsWith("appointment:") || value.startsWith("reminder:")
            || value.startsWith("schedule:") || value.startsWith("clinic:");
    }

    @Test
    @DisplayName("a machine principal is authorized by capability and grants nothing by default")
    void machinePrincipalsAreCapabilityDriven() {
        TenantContext none = TenantContext.forMachine(PrincipalType.WORKER, ORG, "w",
            List.of(), List.of());
        assertThat(authorization.allows(none, Permission.AI_RUN_READ)).isFalse();
        assertThat(authorization.effectivePermissions(none)).isEmpty();

        TenantContext retrieval = TenantContext.forMachine(PrincipalType.WORKER, ORG, "w",
            List.of(), List.of(Capability.KNOWLEDGE_RETRIEVE));
        assertThat(authorization.allows(retrieval, Permission.KNOWLEDGE_READ)).isTrue();
        assertThat(authorization.allows(retrieval, Permission.KNOWLEDGE_MANAGE)).isFalse();
        assertThat(authorization.allows(retrieval, Permission.AI_RUN_READ)).isTrue();
    }

    // ------------------------------------------------------------------
    // boundaries
    // ------------------------------------------------------------------

    @Test
    @DisplayName("no role crosses the tenant boundary, not even ADMIN")
    void tenantBoundaryIsAbsolute() {
        TenantContext caller = admin();

        assertThat(authorization.allows(caller, Permission.PROJECT_READ,
            PolicyResource.ofProject(ORG, PROJECT_A))).isTrue();
        assertThat(authorization.allows(caller, Permission.PROJECT_READ,
            PolicyResource.ofProject(OTHER_ORG, PROJECT_A))).isFalse();
        assertThat(authorization.allows(caller, Permission.USER_MANAGE,
            PolicyResource.ofOrganization(OTHER_ORG))).isFalse();

        assertThatThrownBy(() -> authorization.check(caller, Permission.PROJECT_READ,
            PolicyResource.ofProject(OTHER_ORG, PROJECT_A)))
            .isInstanceOf(PolicyDeniedException.class);
    }

    @Test
    @DisplayName("project membership is required even for an ADMIN")
    void projectBoundaryAppliesToAdmin() {
        TenantContext adminWithoutProjectB = TenantContext.forUser(UUID.randomUUID(),
            UUID.randomUUID(), ORG, UserRole.ADMIN, List.of(PROJECT_A));

        assertThat(authorization.allows(adminWithoutProjectB, Permission.CONVERSATION_READ,
            PolicyResource.ofProject(ORG, PROJECT_B))).isFalse();
        assertThat(authorization.allows(adminWithoutProjectB, Permission.CONVERSATION_READ,
            PolicyResource.ofProject(ORG, PROJECT_A))).isTrue();
    }

    @Test
    @DisplayName("an employee with no membership reaches nothing in any project")
    void noMembershipMeansNoProject() {
        TenantContext nobody = employee(Set.of());
        assertThat(authorization.allows(nobody, Permission.CONVERSATION_READ,
            PolicyResource.ofProject(ORG, PROJECT_A))).isFalse();
        assertThatThrownBy(() -> authorization.check(nobody, Permission.CONVERSATION_READ,
            PolicyResource.ofProject(ORG, PROJECT_A)))
            .isInstanceOf(PolicyDeniedException.class)
            .hasMessage("Access denied");
    }

    @Test
    @DisplayName("self-service permissions are owner-scoped; every other grant ignores the owner")
    void ownerBoundaryAppliesOnlyToSelfService() {
        UUID callerUser = UUID.randomUUID();
        UUID otherUser = UUID.randomUUID();
        TenantContext caller = TenantContext.forUser(UUID.randomUUID(), callerUser, ORG,
            UserRole.EMPLOYEE, List.of(PROJECT_A));

        assertThat(authorization.allows(caller, Permission.EMPLOYEE_SET_OWN_AVAILABILITY,
            PolicyResource.ofOwner(callerUser))).isTrue();
        assertThat(authorization.allows(caller, Permission.EMPLOYEE_SET_OWN_AVAILABILITY,
            PolicyResource.ofOwner(otherUser))).isFalse();

        // An employee:read grant must NOT narrow to "only the row I own".
        assertThat(authorization.allows(caller, Permission.EMPLOYEE_READ,
            PolicyResource.ofOwner(otherUser))).isTrue();

        // An ADMIN may set another employee's availability on their behalf.
        TenantContext admin = admin();
        assertThat(authorization.allows(admin, Permission.EMPLOYEE_SET_OWN_AVAILABILITY,
            PolicyResource.ofOwner(otherUser))).isTrue();
    }

    // ------------------------------------------------------------------
    // denial shapes and auditing
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a grant denial is AccessDeniedException; a scope denial is PolicyDeniedException")
    void twoDenialShapes() {
        TenantContext caller = employee(Set.of(PROJECT_A));

        assertThatThrownBy(() -> authorization.check(caller, Permission.USER_MANAGE))
            .isInstanceOf(AccessDeniedException.class)
            .isNotInstanceOf(PolicyDeniedException.class);

        assertThatThrownBy(() -> authorization.check(caller, Permission.CONVERSATION_READ,
            PolicyResource.ofProject(ORG, PROJECT_B)))
            .isInstanceOf(PolicyDeniedException.class);
    }

    @Test
    @DisplayName("a denial never carries the target's identity in its message")
    void denialMessageIsNonDisclosing() {
        TenantContext caller = employee(Set.of(PROJECT_A));
        PolicyDeniedException denial = org.junit.jupiter.api.Assertions.assertThrows(
            PolicyDeniedException.class,
            () -> authorization.check(caller, Permission.CONVERSATION_READ,
                PolicyResource.ofProject(OTHER_ORG, PROJECT_B)));

        assertThat(denial.getMessage()).isEqualTo("Access denied");
        assertThat(denial.getMessage())
            .doesNotContain(OTHER_ORG.toString())
            .doesNotContain(PROJECT_B.toString());
        // The internal reason is available for the audit stream.
        assertThat(denial.reason()).isEqualTo("organization-out-of-scope");
    }

    @Test
    @DisplayName("every denial is published to the security stream")
    void denialsAreAudited() {
        TenantContext caller = employee(Set.of(PROJECT_A));

        assertThatThrownBy(() -> authorization.check(caller, Permission.USER_MANAGE))
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> authorization.check(caller, Permission.CONVERSATION_READ,
            PolicyResource.ofProject(ORG, PROJECT_B)));

        assertThat(events).hasSize(2);
        assertThat(events.get(0).type()).isEqualTo(SecurityEventType.ACCESS_DENIED);
        assertThat(events.get(1).type()).isEqualTo(SecurityEventType.AUTHORIZATION_DENIED);
        assertThat(events.get(1).metadata()).containsEntry("scope", "project-out-of-scope");
        assertThat(events.get(1).metadata()).containsEntry("permission", "conversation:read");
    }

    @Test
    @DisplayName("a failing event publisher cannot fail the authorization decision")
    void auditingIsBestEffort() {
        AuthorizationService failing = new AuthorizationService(event -> {
            throw new IllegalStateException("audit table unavailable");
        });
        TenantContext caller = employee(Set.of(PROJECT_A));

        assertThat(failing.allows(caller, Permission.CONVERSATION_READ,
            PolicyResource.ofProject(ORG, PROJECT_A))).isTrue();
        assertThatThrownBy(() -> failing.check(caller, Permission.CONVERSATION_READ,
            PolicyResource.ofProject(ORG, PROJECT_B)))
            .isInstanceOf(PolicyDeniedException.class);
    }

    @Test
    @DisplayName("a null context grants nothing")
    void nullContextIsDenied() {
        assertThat(authorization.allows(null, Permission.PROJECT_READ)).isFalse();
        assertThat(authorization.effectivePermissions(null)).isEmpty();
        assertThatThrownBy(() -> authorization.check(null, Permission.PROJECT_READ))
            .isInstanceOf(AccessDeniedException.class);
    }

    // ------------------------------------------------------------------
    // effective permissions + evaluator
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the effective set is the grant set intersected with what the boundaries allow")
    void effectivePermissionsReflectScope() {
        TenantContext caller = employee(Set.of(PROJECT_A));

        Set<Permission> effective = authorization.effectivePermissions(caller);
        assertThat(effective).contains(Permission.CONVERSATION_READ, Permission.CUSTOMER_READ);
        assertThat(effective).doesNotContain(Permission.USER_MANAGE);

        Set<Permission> forOtherProject = authorization.effectivePermissions(caller,
            PolicyResource.ofProject(ORG, PROJECT_B));
        assertThat(forOtherProject).doesNotContain(Permission.CONVERSATION_READ);
    }

    @Test
    @DisplayName("the permission evaluator denies any Authentication it does not recognise")
    void evaluatorIsDefaultDeny() {
        FirstElementPermissionEvaluator evaluator = new FirstElementPermissionEvaluator(authorization);
        TenantContext caller = employee(Set.of(PROJECT_A));

        assertThat(evaluator.allows(new TenantAuthentication(caller),
            Permission.CONVERSATION_READ)).isTrue();
        assertThat(evaluator.allows(null, Permission.CONVERSATION_READ)).isFalse();
        assertThat(evaluator.allowsAny(new TenantAuthentication(caller),
            Permission.USER_MANAGE, Permission.CONVERSATION_READ)).isTrue();
        assertThat(evaluator.allowsAny(new TenantAuthentication(caller),
            Permission.USER_MANAGE)).isFalse();
        assertThat(evaluator.allowsAny(new TenantAuthentication(caller))).isFalse();
    }

    @Test
    @DisplayName("the evaluator's 4-argument form only accepts a declared PolicyResource type")
    void evaluatorRejectsAnUnrecognisedTargetType() {
        FirstElementPermissionEvaluator evaluator = new FirstElementPermissionEvaluator(authorization);
        TenantContext caller = employee(Set.of(PROJECT_A));
        var authentication = new TenantAuthentication(caller);

        assertThat(evaluator.hasPermission(authentication,
            PolicyResource.ofProject(ORG, PROJECT_A),
            PolicyResource.class.getName(),
            Permission.CONVERSATION_READ)).isTrue();

        // A typo in targetType must deny, not silently fall back to a wider check.
        assertThat(evaluator.hasPermission(authentication,
            PolicyResource.ofProject(OTHER_ORG, PROJECT_A),
            "com.example.Wrong",
            Permission.CONVERSATION_READ)).isFalse();
    }

    @Test
    @DisplayName("wire values round-trip, so audit rows from both runtimes are joinable")
    void permissionWireValuesAreStable() {
        assertThat(Permission.fromValue("conversation:read")).isEqualTo(Permission.CONVERSATION_READ);
        assertThat(Permission.CONVERSATION_READ.toString()).isEqualTo("conversation:read");
        Map<String, Object> metadata = Map.of("permission", Permission.SESSION_REVOKE.value());
        assertThat(metadata).containsEntry("permission", "session:revoke");
    }

    @Test
    @DisplayName("workflow, SLA and AI-send are administrator capabilities, never employee ones")
    void phaseSixAndSevenCapabilitiesAreAdminOnly() {
        // Phase 6 escalated one decision: authoring a workflow reused ESCALATION_RULE_MANAGE and a
        // send reused the read-only AI_RUN_READ. Both were wrong in the same direction — a read or a
        // narrow-rule grant standing in for a write — so these three exist as separate permissions.
        // The guarantee worth locking down is the negative one: widening the admin set must never
        // reach an employee, because EMPLOYEE is an explicit list, not allOf.
        for (Permission permission : new Permission[] {
                Permission.WORKFLOW_MANAGE, Permission.SLA_MANAGE, Permission.AI_SEND }) {
            assertThat(Permission.fromValue(permission.value())).isEqualTo(permission);
            assertThat(RolePermissions.forRole(UserRole.ADMIN)).contains(permission);
            assertThat(RolePermissions.forRole(UserRole.EMPLOYEE))
                .as("%s must not reach an employee", permission)
                .doesNotContain(permission);
        }

        // Holding the ability to run an AI tool must not by itself allow speaking to a customer.
        assertThat(RolePermissions.forCapabilities(java.util.EnumSet.of(Capability.AI_TOOL_EXECUTE)))
            .doesNotContain(Permission.AI_SEND);
        assertThat(RolePermissions.forCapabilities(java.util.EnumSet.of(Capability.AI_RUN))).contains(Permission.AI_SEND);
    }
}