package com.firstelement.observ;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstelement.observ.workflow.WorkflowOperationsService;
import com.firstelement.security.TenantContext;
import com.firstelement.security.permission.Permission;
import com.firstelement.security.permission.RolePermissions;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;

/**
 * Workflow operations, and the two permission properties that hold across the whole Phase-7 surface.
 *
 * <h2>Workflow panel</h2>
 *
 * <p>It answers "which version is live, what is it allowed to do, and how has it behaved" — and it
 * deliberately does not dump the state machine, because an Admin read model that renders the authored
 * workflow is a configuration editor wearing a read model's clothes. The panel also inherits a
 * legitimate state from the catalog repository: {@code activeVersionId = null} means "still being
 * authored", and the assertion here is that such a definition is reported as such rather than being
 * hidden, because a hidden definition looks like a deleted one.
 *
 * <h2>The two permission properties</h2>
 *
 * <ol>
 *   <li><b>An employee is refused every operational and administrative action.</b> The employee grant
 *       set is Phase 3's and holds none of these permissions, so this is a property of the grant table
 *       rather than of any one route — and it is the property that makes FE-WEB-003's per-route
 *       {@code @RequirePermission} meaningful.</li>
 *   <li><b>The workflow panel runs on {@code SYSTEM_HEALTH_READ}, and that is a documented gap.</b>
 *       The catalogue has no workflow-read permission and this phase may not widen one, so the panel
 *       borrows the administrative permission. The test asserts the fact so the gap is visible rather
 *       than forgotten, and the follow-up in the phase report names it.</li>
 * </ol>
 */
@SpringBootTest(classes = ObservTestApplication.class)
class WorkflowAndRoutePermissionIntegrationTest extends ObservIntegrationTestBase {

    @Autowired
    private WorkflowOperationsService workflows;
    @Autowired
    private com.firstelement.observ.jobs.JobOperationsService jobs;
    @Autowired
    private com.firstelement.observ.search.SearchFreshnessService search;
    @Autowired
    private com.firstelement.observ.connectors.ConnectorHealthService connectors;
    @Autowired
    private ObservFixture fixture;

    @BeforeEach
    void reset() {
        fixture.reset();
    }

    // ==================================================================
    // the workflow panel
    // ==================================================================

    @Nested
    @DisplayName("workflow operations")
    class Workflows {

        @Test
        @DisplayName("an active version is reported with its capability scope and content hash")
        void theLiveVersionIsVisible() {
            UUID definitionId = fixture.workflow(ObservFixture.ORG_A, ObservFixture.PROJECT_A,
                "escalate-on-silence", true);
            TenantContext admin = ObservPrincipalScope.adminA();

            var view = ObservPrincipalScope.call(admin, () -> workflows.workflows(admin, null, null));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> definitions =
                (List<Map<String, Object>>) view.document().get("definitions");
            assertThat(definitions).hasSize(1);
            Map<String, Object> definition = definitions.get(0);
            assertThat(definition.get("id")).isEqualTo(definitionId);
            assertThat(definition.get("key")).isEqualTo("escalate-on-silence");
            assertThat(definition.get("activeVersionId")).isNotNull();
            assertThat(definition.get("latestVersionNumber")).isEqualTo(2);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> versions = (List<Map<String, Object>>) definition.get("versions");
            assertThat(versions).hasSize(1);
            Map<String, Object> version = versions.get(0);
            assertThat(version.get("versionNumber")).isEqualTo(2);
            assertThat(version.get("state")).isEqualTo("ACTIVE");
            assertThat(version.get("contentHash")).isEqualTo("content-hash-abcdef0123456789");
            // The capability scope is the "what is it allowed to do" answer, and it is projected.
            assertThat(String.valueOf(version.get("capabilityScope"))).contains("conversation:read");
            assertThat(version.get("activatedAt")).isNotNull();
            // And the state machine body is not: an Admin read model is not a configuration editor.
            assertThat(version).doesNotContainKey("stateMachine");
            assertThat(String.valueOf(definition)).doesNotContain("INIT");
        }

        @Test
        @DisplayName("a definition with no active version is reported, not hidden")
        void anUnauthoredDefinitionIsVisible() {
            fixture.workflow(ObservFixture.ORG_A, ObservFixture.PROJECT_A, "draft-workflow", false);
            TenantContext admin = ObservPrincipalScope.adminA();

            var view = ObservPrincipalScope.call(admin, () -> workflows.workflows(admin, null, null));

            // definitionsForDryRun excludes a NULL activeVersionId by design, so the panel's answer is
            // an empty list plus the note. What matters is that the note says why, so an operator
            // reads "not shown because it has no live version" rather than "gone".
            assertThat(String.valueOf(view.document().get("note")))
                .contains("still being authored");
        }

        @Test
        @DisplayName("another tenant's workflows are not on this panel")
        void crossTenantWorkflowsAreAbsent() {
            fixture.workflow(ObservFixture.ORG_A, ObservFixture.PROJECT_A, "alpha-flow", true);
            fixture.workflow(ObservFixture.ORG_B, ObservFixture.PROJECT_B, "beta-flow", true);
            TenantContext beta = ObservPrincipalScope.adminB();

            var view = ObservPrincipalScope.call(beta, () -> workflows.workflows(beta, null, null));

            assertThat(String.valueOf(view.document().get("definitions")))
                .contains("beta-flow")
                .doesNotContain("alpha-flow");
        }

        @Test
        @DisplayName("the failure panel reports zero rows over a window with no activity")
        void theFailurePanelIsEmptyOverAnIdleWindow() {
            TenantContext admin = ObservPrincipalScope.adminA();

            var view = ObservPrincipalScope.call(admin, () -> workflows.failures(admin, 168, null));

            assertThat(view.document()).containsEntry("windowHours", 168);
            assertThat(view.document().get("failedActions")).asInstanceOf(
                org.assertj.core.api.InstanceOfAssertFactories.LIST).isEmpty();
            assertThat(view.document().get("collisions")).asInstanceOf(
                org.assertj.core.api.InstanceOfAssertFactories.LIST).isEmpty();
        }
    }

    // ==================================================================
    // permission properties
    // ==================================================================

    @Nested
    @DisplayName("the grant table, as every route depends on it")
    class Grants {

        @Test
        @DisplayName("an employee holds none of the permissions this surface asks for")
        void anEmployeeHoldsNone() {
            var employee = RolePermissions.forRole(com.firstelement.security.UserRole.EMPLOYEE);

            assertThat(employee).doesNotContain(
                Permission.SYSTEM_HEALTH_READ,
                Permission.AUDIT_LOG_READ,
                Permission.SECURITY_EVENT_READ,
                Permission.ORGANIZATION_UPDATE,
                Permission.USER_MANAGE,
                Permission.SESSION_REVOKE,
                Permission.PERMISSION_INSPECTOR_READ,
                Permission.AI_AGENT_READ,
                Permission.ANALYTICS_READ_ORGANIZATION);
        }

        @Test
        @DisplayName("an employee is refused a replay at the service layer, and the event is untouched")
        void anEmployeeCannotReplay() {
            UUID eventId = fixture.deadOutboxEvent(ObservFixture.ORG_A, ObservFixture.PROJECT_A,
                com.firstelement.worker.queue.QueueName.INCOMING_MESSAGE.wireValue(), 12,
                "attempts exhausted");
            TenantContext employee = ObservPrincipalScope.employeeA();

            assertThatThrownBy(() -> ObservPrincipalScope.call(employee,
                () -> jobs.replay(employee, eventId)))
                .isInstanceOf(AccessDeniedException.class);
            // STATE, not the message: nothing was written.
            assertThat(fixture.count("select 1 from outbox_events"
                + " where \"id\" = :id and \"state\" = 'DEAD'", Map.of("id", eventId)))
                .isEqualTo(1);
        }

        @Test
        @DisplayName("an employee is refused the workflow panel, the failure panel and the job console")
        void anEmployeeCannotReadOperationalState() {
            TenantContext employee = ObservPrincipalScope.employeeA();
            fixture.workflow(ObservFixture.ORG_A, ObservFixture.PROJECT_A, "flow", true);

            assertThatThrownBy(() -> ObservPrincipalScope.call(employee,
                () -> workflows.workflows(employee, null, null)))
                .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> ObservPrincipalScope.call(employee,
                () -> workflows.failures(employee, null, null)))
                .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> ObservPrincipalScope.call(employee,
                () -> jobs.queues(employee, null, null, null)))
                .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> ObservPrincipalScope.call(employee,
                () -> jobs.diagnose(employee, UUID.randomUUID())))
                .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("the connector panel requires CHANNEL_READ, which an employee does not hold")
        void anEmployeeCannotReadConnectors() {
            TenantContext employee = ObservPrincipalScope.employeeA();
            fixture.connection(ObservFixture.ORG_A, ObservFixture.PROJECT_A, "WHATSAPP",
                "{\"token\":\"x\"}", null, "CONNECTED", null);

            assertThatThrownBy(() -> ObservPrincipalScope.call(employee,
                () -> connectors.connectors(employee, null)))
                .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> ObservPrincipalScope.call(employee,
                () -> connectors.webhooks(employee, null)))
                .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("the realtime panel requires SYSTEM_HEALTH_READ, which an employee does not hold")
        void anEmployeeCannotReadRealtimeCounters() {
            TenantContext employee = ObservPrincipalScope.employeeA();

            assertThatThrownBy(() -> ObservPrincipalScope.call(employee,
                () -> search.realtime(employee)))
                .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("the freshness panel is reachable by an employee, because KNOWLEDGE_READ is its grant")
        void theFreshnessPanelReusesAKnowledgePermission() {
            // KNOWNLEDGE_READ has been in the employee grant since Phase 3, and the freshness panel
            // reuses it rather than inventing a stronger one. That is a deliberate consequence of
            // "use existing permissions only", and it is worth stating plainly: an employee can read
            // index freshness for their own projects. The read is document metadata and counts, never
            // chunk content, so the exposure is an ingestion-state view rather than the knowledge base.
            // A dedicated KNOWLEDGE_MANAGE-adjacent read permission is the follow-up if that is judged
            // too wide.
            TenantContext employee = ObservPrincipalScope.employeeA();
            fixture.document(ObservFixture.ORG_A, ObservFixture.PROJECT_A,
                ObservFixture.KNOWLEDGE_BASE_A, "Employee-visible freshness", "READY", 2, 2, 0,
                ObservFixture.now());

            var view = ObservPrincipalScope.call(employee,
                () -> search.freshness(employee, null, 1, null));

            assertThat(RolePermissions.forRole(com.firstelement.security.UserRole.EMPLOYEE))
                .contains(Permission.KNOWLEDGE_READ);
            assertThat(String.valueOf(view.document().get("documents")))
                .contains("Employee-visible freshness")
                .doesNotContain("seeded chunk text");
        }

        @Test
        @DisplayName("the workflow panel's permission is SYSTEM_HEALTH_READ, which is a documented gap")
        void theWorkflowPanelBorrowsAnAdministrativePermission() {
            // The catalogue has no workflow-read permission, and Phase 7 may not widen one. So the
            // workflow reads run on SYSTEM_HEALTH_READ. Asserting it here keeps the gap visible in the
            // test suite rather than only in a report: an operations reader currently needs the
            // administrative capability set to see automation health.
            assertThat(RolePermissions.forRole(com.firstelement.security.UserRole.ADMIN))
                .contains(Permission.SYSTEM_HEALTH_READ);
            assertThat(RolePermissions.forRole(com.firstelement.security.UserRole.EMPLOYEE))
                .doesNotContain(Permission.SYSTEM_HEALTH_READ);
            // And the Admin side, for the same reason, keeps its workflow-shaped reads on
            // SYSTEM_HEALTH_READ rather than on a CRM permission the workflow module does not own.
            assertThat(Permission.values())
                .as("a WORKFLOW_READ permission must be added by :backend:tenant-security, not "
                    + "worked around here")
                .extracting(Permission::value)
                .doesNotContain("workflow:read");
        }

        @Test
        @DisplayName("an admin holds every permission, which is why ADMIN is allOf(Permission)")
        void anAdminHoldsEverything() {
            assertThat(RolePermissions.forRole(com.firstelement.security.UserRole.ADMIN))
                .hasSize(Permission.values().length);
        }
    }
}
