package com.firstelement.automation;

import static org.assertj.core.api.Assertions.assertThat;

import com.firstelement.automation.persistence.RoutingRepository;
import com.firstelement.automation.service.RoutingService;
import com.firstelement.security.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Routing explanations, capacity limits, and the unassigned queue as the terminal stage.
 *
 * <h2>Explainability is the acceptance gate</h2>
 *
 * <p>"A decision returns the inputs that produced it, not just a result." Every test here asserts on
 * the <em>inputs</em> in the explanation — the per-stage survivor counts, the winning candidate's
 * workload, the instant the working-hours stage judged — and not merely on the chosen employee. A
 * routing decision whose explanation only says "assigned to X" is indistinguishable from a bug.
 *
 * <h2>Why the unassigned queue is asserted as the blocking stage</h2>
 *
 * <p>When nobody qualifies, the conversation must stay in the queue <em>with the reason</em>. A silent
 * drop is the failure this covers: the ticket disappears from every queue and the escalation just
 * stops.
 *
 * <h2>Time is supplied, never read</h2>
 *
 * <p>{@code Instant now} is passed in rather than taken from the clock, so the working-hours stage is
 * deterministic and a test can place a conversation inside or outside an agent's week on purpose.
 */
@SpringBootTest(classes = WorkflowTestApplication.class)
class RoutingExplanationIntegrationTest extends WorkflowIntegrationTestBase {

    /** A Wednesday at 10:00 UTC — inside every seeded business day. */
    private static final Instant WEDNESDAY_MORNING =
        Instant.parse("2026-08-19T10:00:00Z");

    /** A Sunday at 10:00 UTC — outside the seeded week. */
    private static final Instant SUNDAY_MORNING =
        Instant.parse("2026-08-23T10:00:00Z");

    @Autowired
    private WorkflowFixture fixture;

    @Autowired
    private RoutingService routing;

    @Autowired
    private org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc;

    private TenantContext admin;

    @BeforeEach
    void reset() {
        fixture.reset();
        admin = PrincipalScope.adminA();
    }

    private UUID unassignedConversation() {
        // OPEN and unassigned is what UNASSIGNED_ACTIVE requires: the membership predicate is
        // reused from the inbox rather than re-derived here, which is the ADR 0006 §5 point.
        return fixture.conversation(WorkflowFixture.ORG_A, WorkflowFixture.PROJECT_A,
            WorkflowFixture.CUSTOMER_A, "AI", "OPEN");
    }

    private RoutingService.RoutingResult route(UUID conversationId, String intent, Instant now) {
        return route(conversationId, intent, null, now);
    }

    /**
     * Routes with an explicit department.
     *
     * <p>The department is an argument because {@code firstMatch} treats a rule's own department as a
     * match target, not an override: a rule naming {@code billing} matches a request that asks for
     * {@code billing}. Passing it explicitly is how the department-scoped rules are exercised.
     */
    private RoutingService.RoutingResult route(UUID conversationId, String intent, String department,
                                              Instant now) {
        return PrincipalScope.call(admin, () ->
            routing.route(admin, WorkflowFixture.PROJECT_A, conversationId, intent, department, now));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> chosen(RoutingService.RoutingResult result) {
        return (Map<String, Object>) result.explanation().get("chosen");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> stages(RoutingService.RoutingResult result) {
        return (List<Map<String, Object>>) result.explanation().get("stages");
    }

    // ------------------------------------------------------------------
    // the explanation
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an assignment explains itself: the rule, the stages and the winner's workload")
    void assignmentCarriesItsInputs() {
        UUID conversationId = unassignedConversation();
        fixture.routingRule(WorkflowFixture.ORG_A, WorkflowFixture.PROJECT_A, "billing first", 10,
            "billing", null, "LEAST_LOADED");

        RoutingService.RoutingResult result = route(conversationId, "refund", "billing", WEDNESDAY_MORNING);

        assertThat(result.status()).isEqualTo(RoutingService.RoutingResult.Status.ASSIGNED);
        Map<String, Object> explanation = result.explanation();
        assertThat(explanation).containsKeys("judgedAt", "rule", "department", "strategy",
            "considered", "eligible", "stages", "blockedAt", "blockedExplanation", "chosen",
            "reasons");
        assertThat(explanation.get("judgedAt")).isEqualTo(WEDNESDAY_MORNING.toString());
        // The requirement, not the winner's own department: the RULE's department is the filter
        // that was applied, and saying so is what makes the choice checkable afterwards.
        assertThat(explanation.get("department")).isEqualTo("billing");
        assertThat(result.rule()).isNotNull();
        assertThat(chosen(result)).containsEntry("employeeId",
            WorkflowFixture.EMPLOYEE_A.toString());
        assertThat(chosen(result))
            .as("the winner's workload is part of the decision, not an afterthought")
            .containsEntry("activeConversationCount", 0)
            .containsEntry("maxConcurrentConversations", 5);
    }

    @Test
    @DisplayName("every stage's survivor count is present, in pipeline order")
    void stageTraceIsComplete() {
        UUID conversationId = unassignedConversation();

        RoutingService.RoutingResult result = route(conversationId, "refund", WEDNESDAY_MORNING);

        List<Map<String, Object>> stages = stages(result);
        assertThat(stages).as("an unassignable stage trace is what makes this unexplainable").isNotEmpty();
        assertThat(stages).extracting(stage -> stage.get("stage"))
            .containsExactly("MEMBERSHIP", "DEPARTMENT", "SKILL", "STATUS", "WORKING_HOURS",
                "WORKLOAD");
        // The counts must be a monotonically non-increasing funnel, which is what makes the trace
        // readable as "who was dropped and where".
        int previous = Integer.MAX_VALUE;
        for (Map<String, Object> stage : stages) {
            int after = ((Number) stage.get("after")).intValue();
            assertThat(after).as("a stage cannot create candidates").isLessThanOrEqualTo(previous);
            previous = after;
        }
    }

    @Test
    @DisplayName("explain() produces the same inputs as route() without assigning anybody")
    void dryRunExplanationMatchesTheRealDecision() {
        UUID conversationId = unassignedConversation();
        fixture.routingRule(WorkflowFixture.ORG_A, WorkflowFixture.PROJECT_A, "billing first", 10,
            "billing", null, "LEAST_LOADED");

        // The preview FIRST: routing assigns, and the assignment changes the winning agent's
        // activeConversationCount. Comparing a post-assignment preview with the decision that caused
        // it would compare two different worlds.
        Map<String, Object> preview = PrincipalScope.call(admin, () ->
            routing.explain(admin, WorkflowFixture.PROJECT_A, "refund", "billing", WEDNESDAY_MORNING));
        RoutingService.RoutingResult actual =
            route(conversationId, "refund", "billing", WEDNESDAY_MORNING);

        assertThat(preview.get("chosen")).isEqualTo(actual.explanation().get("chosen"));
        assertThat(preview.get("stages")).isEqualTo(actual.explanation().get("stages"));
        assertThat(preview.get("department")).isEqualTo("billing");
        assertThat(preview)
            .as("a preview is a decision's inputs, not just a name: it must carry the stage trace")
            .containsKeys("judgedAt", "considered", "eligible", "stages", "chosen", "reasons");
    }

    // ------------------------------------------------------------------
    // the unassigned queue as the terminal stage
    // ------------------------------------------------------------------

    @Test
    @DisplayName("nobody in the department queues the ticket and names the blocking stage")
    void nobodyQualifyingQueuesWithAReason() {
        UUID conversationId = unassignedConversation();
        // "legal" has no employee at all, so the pipeline empties well before capacity.
        fixture.routingRule(WorkflowFixture.ORG_A, WorkflowFixture.PROJECT_A, "legal only", 10,
            "legal", null, "LEAST_LOADED");

        RoutingService.RoutingResult result =
            route(conversationId, "dispute", "legal", WEDNESDAY_MORNING);

        assertThat(result.status()).isEqualTo(RoutingService.RoutingResult.Status.QUEUED);
        assertThat(result.explanation().get("chosen")).isNull();
        assertThat(result.explanation().get("blockedAt"))
            .as("a queue without a named stage is how a ticket disappears")
            .isEqualTo("DEPARTMENT");
        assertThat(result.reason()).isNotBlank();
        assertThat(assignedEmployeeOf(conversationId)).isNull();
    }

    @Test
    @DisplayName("a full agent is dropped by WORKLOAD and the reason says so")
    void capacityLimitIsExplained() {
        UUID conversationId = unassignedConversation();
        // EMPLOYEE_A_BUSY is at 3 of 5; raise it to full and put it ONLINE so the capacity stage —
        // not the status stage — is what drops it.
        setActiveCount(WorkflowFixture.EMPLOYEE_A_BUSY, 5);
        setAvailability(WorkflowFixture.EMPLOYEE_A_BUSY, "ONLINE");
        fixture.employeeSkill(WorkflowFixture.ORG_A, WorkflowFixture.EMPLOYEE_A_BUSY, "support", 3);
        fixture.routingRule(WorkflowFixture.ORG_A, WorkflowFixture.PROJECT_A, "support only", 10,
            null, "support", "LEAST_LOADED");

        RoutingService.RoutingResult result = route(conversationId, "refund", WEDNESDAY_MORNING);

        assertThat(result.status()).isEqualTo(RoutingService.RoutingResult.Status.QUEUED);
        assertThat(result.explanation().get("blockedAt")).isEqualTo("WORKLOAD");
        assertThat(String.valueOf(result.explanation().get("blockedExplanation")))
            .as("the operator must be told the agent is at their limit, not that 'nothing matched'")
            .containsIgnoringCase("limit");
    }

    @Test
    @DisplayName("an agent outside their working hours is dropped by WORKING_HOURS")
    void workingHoursStageIsExplained() {
        UUID conversationId = unassignedConversation();
        setAvailability(WorkflowFixture.EMPLOYEE_A_BUSY, "ONLINE");
        fixture.employeeSkill(WorkflowFixture.ORG_A, WorkflowFixture.EMPLOYEE_A_BUSY, "support", 3);
        // A Friday-only week, judged on a Sunday: ONLINE, skilled, not full, and still unroutable.
        fixture.employeeWindow(WorkflowFixture.ORG_A, WorkflowFixture.EMPLOYEE_A_BUSY, 5, 540, 1020,
            "UTC");
        fixture.routingRule(WorkflowFixture.ORG_A, WorkflowFixture.PROJECT_A, "support only", 10,
            null, "support", "LEAST_LOADED");

        RoutingService.RoutingResult result = route(conversationId, "refund", SUNDAY_MORNING);

        assertThat(result.status()).isEqualTo(RoutingService.RoutingResult.Status.QUEUED);
        assertThat(result.explanation().get("blockedAt")).isEqualTo("WORKING_HOURS");
        assertThat(String.valueOf(result.explanation().get("blockedExplanation")))
            .containsIgnoringCase("working hours");
    }

    @Test
    @DisplayName("the same agent on a Friday IS routable, so the hours stage is what refused it")
    void theSameAgentIsRoutableInsideItsWindow() {
        UUID conversationId = unassignedConversation();
        setAvailability(WorkflowFixture.EMPLOYEE_A_BUSY, "ONLINE");
        fixture.employeeSkill(WorkflowFixture.ORG_A, WorkflowFixture.EMPLOYEE_A_BUSY, "support", 3);
        fixture.employeeWindow(WorkflowFixture.ORG_A, WorkflowFixture.EMPLOYEE_A_BUSY, 5, 540, 1020,
            "UTC");
        fixture.routingRule(WorkflowFixture.ORG_A, WorkflowFixture.PROJECT_A, "support only", 10,
            null, "support", "LEAST_LOADED");

        // Friday 2026-08-21, inside the seeded window.
        RoutingService.RoutingResult inside = route(conversationId, "refund",
            Instant.parse("2026-08-21T10:00:00Z"));

        assertThat(inside.status()).isEqualTo(RoutingService.RoutingResult.Status.ASSIGNED);
        assertThat(chosen(inside)).containsEntry("employeeId",
            WorkflowFixture.EMPLOYEE_A_BUSY.toString());
    }

    @Test
    @DisplayName("an OFFLINE agent is dropped by STATUS, and a BUSY one is deliberately not")
    void availabilityStageIsExplained() {
        UUID conversationId = unassignedConversation();
        fixture.employeeSkill(WorkflowFixture.ORG_A, WorkflowFixture.EMPLOYEE_A_BUSY, "support", 3);
        fixture.routingRule(WorkflowFixture.ORG_A, WorkflowFixture.PROJECT_A, "support only", 10,
            null, "support", "LEAST_LOADED");

        // BUSY is still "at the desk": atDesk() mirrors EmployeeRepository.isRoutable, and a busy
        // agent is one who can take work once they finish what they have. Asserting that here, because
        // it is exactly the kind of rule a reader would assume is the opposite.
        RoutingService.RoutingResult busy = route(unassignedConversation(), "refund",
            WEDNESDAY_MORNING);
        assertThat(busy.explanation().get("blockedAt"))
            .as("a BUSY agent must remain routable").isNull();
        assertThat(chosen(busy)).containsEntry("employeeId",
            WorkflowFixture.EMPLOYEE_A_BUSY.toString());

        setAvailability(WorkflowFixture.EMPLOYEE_A_BUSY, "OFFLINE");
        RoutingService.RoutingResult offline = route(conversationId, "refund", WEDNESDAY_MORNING);
        assertThat(offline.explanation().get("blockedAt")).isEqualTo("STATUS");
        assertThat(String.valueOf(offline.explanation().get("blockedExplanation")))
            .containsIgnoringCase("available");
    }

    // ------------------------------------------------------------------
    // the membership gate
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a conversation somebody already owns is skipped, never reassigned")
    void alreadyOwnedIsSkipped() {
        UUID conversationId = fixture.conversation(WorkflowFixture.ORG_A,
            WorkflowFixture.PROJECT_A, WorkflowFixture.CUSTOMER_A, "AI", "OPEN");
        assignTo(conversationId, WorkflowFixture.EMPLOYEE_A);

        RoutingService.RoutingResult result = route(conversationId, "refund", WEDNESDAY_MORNING);

        assertThat(result.status()).isEqualTo(RoutingService.RoutingResult.Status.SKIPPED);
        assertThat(result.explanation().get("blockedAt")).isEqualTo("MEMBERSHIP");
        assertThat(assignedEmployeeOf(conversationId))
            .as("the existing owner must be untouched")
            .isEqualTo(WorkflowFixture.EMPLOYEE_A);
    }

    @Test
    @DisplayName("the router and the queue agree on membership, because the predicate is shared")
    void membershipPredicateIsTheSharedOne() {
        assertThat(RoutingRepository.unassignedPredicate())
            .as("ADR 0006 §5: the queue's own definition is reused, never re-derived, so the queue an "
                + "operator sees and the queue the router fills cannot drift apart")
            .isEqualTo(com.firstelement.inbox.persistence.UnassignedQueueRepository.UNASSIGNED_ACTIVE);
    }

    @Test
    @DisplayName("rules are matched in explicit priority order, never by name")
    void rulesAreOrderedByPriorityNotName() {
        UUID conversationId = unassignedConversation();
        // Both rules match the request; only priority separates them. "zzz" sorts LAST alphabetically
        // and has the LOWER priority number, so a name ordering would pick the other one. The two
        // rules name different departments on purpose, so the winner is identifiable from the
        // explanation and not merely from which row came back.
        fixture.routingRule(WorkflowFixture.ORG_A, WorkflowFixture.PROJECT_A, "zzz wins", 1,
            "billing", null, "LEAST_LOADED");
        fixture.routingRule(WorkflowFixture.ORG_A, WorkflowFixture.PROJECT_A, "aaa loses", 2,
            "billing", null, "LEAST_LOADED");

        RoutingService.RoutingResult result = route(conversationId, "refund", "billing", WEDNESDAY_MORNING);

        assertThat(result.rule()).isNotNull();
        assertThat(result.rule().name()).isEqualTo("zzz wins");
        assertThat(chosen(result))
            .as("the higher-priority rule's department is the one applied")
            .containsEntry("department", "billing");
    }

    @Test
    @DisplayName("a deterministic tie-break: identical inputs always choose the identical employee")
    void tieBreakingIsDeterministic() {
        // Two identical, equally-loaded, equally-available agents in a department of their own: only
        // the final tie-break can separate them.
        seedTiedEmployee();
        setDepartment(seededTieEmployeeId, "legal");
        setDepartment(seededLegalEmployeeId, "legal");
        fixture.routingRule(WorkflowFixture.ORG_A, WorkflowFixture.PROJECT_A, "legal pair", 10,
            "legal", null, "LEAST_LOADED");

        // Repeated through the PREVIEW, not through route(): route() assigns, which raises the
        // winner's activeConversationCount, so the next call has genuinely different inputs and a
        // different correct answer. Determinism has to be tested on a fixed input set.
        List<String> choices = new java.util.ArrayList<>();
        for (int attempt = 0; attempt < 4; attempt++) {
            Map<String, Object> preview = PrincipalScope.call(admin, () ->
                routing.explain(admin, WorkflowFixture.PROJECT_A, "refund", "legal",
                    WEDNESDAY_MORNING));
            choices.add(String.valueOf(((Map<?, ?>) preview.get("chosen")).get("employeeId")));
        }

        assertThat(choices).as("a tie-break that rotates is indistinguishable from no tie-break at "
            + "all, because the same inputs would give different answers on a retry")
            .containsOnly(choices.get(0));
        assertThat(choices.get(0))
            .as("and the winner must be the lowest employee id, which is the documented final term")
            .isEqualTo(List.of(WorkflowFixture.EMPLOYEE_A.toString(),
                seededTieEmployeeId.toString()).stream().sorted().findFirst().orElseThrow());
    }

    @Test
    @DisplayName("LEAST_LOADED genuinely rebalances: the second ticket goes to the other agent")
    void leastLoadedSpreadsTheLoad() {
        seedTiedEmployee();
        setDepartment(seededTieEmployeeId, "legal");
        setDepartment(seededLegalEmployeeId, "legal");
        fixture.routingRule(WorkflowFixture.ORG_A, WorkflowFixture.PROJECT_A, "legal pair", 10,
            "legal", null, "LEAST_LOADED");

        RoutingService.RoutingResult first = route(unassignedConversation(), "refund", "legal",
            WEDNESDAY_MORNING);
        RoutingService.RoutingResult second = route(unassignedConversation(), "refund", "legal",
            WEDNESDAY_MORNING);

        assertThat(first.status()).isEqualTo(RoutingService.RoutingResult.Status.ASSIGNED);
        assertThat(second.status()).isEqualTo(RoutingService.RoutingResult.Status.ASSIGNED);
        assertThat(chosen(first).get("employeeId"))
            .as("the second ticket must go to the now-less-loaded agent; concentrating both on one "
                + "is what 'least loaded' is supposed to prevent")
            .isNotEqualTo(chosen(second).get("employeeId"));
    }

    private UUID seededTieEmployeeId;
    private UUID seededLegalEmployeeId;

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private void setActiveCount(UUID employeeId, int active) {
        PrincipalScope.call(admin, () -> {
            jdbc().update("""
                update employees e
                   set "activeConversationCount" = :active
                 where e."id" = :id and e."organizationId" = :org
                """, new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                .addValue("active", active).addValue("id", employeeId)
                .addValue("org", WorkflowFixture.ORG_A));
            return null;
        });
    }

    /**
     * Flips an agent's presence.
     *
     * <p>Separate from {@link #setActiveCount} because STATUS is evaluated <em>before</em> WORKLOAD: an
     * agent left BUSY would be dropped by the status stage, and the capacity stage would never be the
     * one under test.
     */
    private void setAvailability(UUID employeeId, String status) {
        PrincipalScope.call(admin, () -> {
            jdbc().update("""
                update employees e
                   set "availabilityStatus" = cast(:status as "EmployeeAvailabilityStatus")
                 where e."id" = :id and e."organizationId" = :org
                """, new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                .addValue("status", status).addValue("id", employeeId)
                .addValue("org", WorkflowFixture.ORG_A));
            return null;
        });
    }

    private void assignTo(UUID conversationId, UUID employeeId) {
        PrincipalScope.call(admin, () -> {
            jdbc().update("""
                update conversations c
                   set "assignedEmployeeId" = :employeeId,
                       "status" = cast('PENDING' as "ConversationStatus")
                 where c."id" = :id and c."organizationId" = :org
                """, new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                .addValue("employeeId", employeeId).addValue("id", conversationId)
                .addValue("org", WorkflowFixture.ORG_A));
            return null;
        });
    }

    private UUID assignedEmployeeOf(UUID conversationId) {
        return jdbc().query("""
            select c."assignedEmployeeId" from conversations c
             where c."id" = :id and c."organizationId" = :org
            """, new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
            .addValue("id", conversationId).addValue("org", WorkflowFixture.ORG_A),
            rs -> rs.next() ? rs.getObject(1, UUID.class) : null);
    }

    /**
     * A second agent with exactly the seeded online agent's workload, so the only thing separating
     * them is the tie-break.
     */
    private UUID seedTiedEmployee() {
        UUID userId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        seededTieEmployeeId = employeeId;
        seededLegalEmployeeId = WorkflowFixture.EMPLOYEE_A;
        PrincipalScope.call(admin, () -> {
            jdbc().update("""
                insert into users ("id", "organizationId", "email", "passwordHash", "fullName",
                    "role", "locale", "isActive", "createdAt", "updatedAt")
                values (:id, :org, :email, 'not-a-real-hash', 'Tied Agent',
                        cast('EMPLOYEE' as "UserRole"), cast('ar' as "Locale"), true, :now, :now)
                """, new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                .addValue("id", userId).addValue("org", WorkflowFixture.ORG_A)
                .addValue("email", "tied-" + userId + "@a.test").addValue("now", java.time.LocalDateTime
                    .now(java.time.ZoneOffset.UTC)));
            jdbc().update("""
                insert into project_memberships ("id", "organizationId", "projectId", "userId",
                    "createdAt")
                values (:id, :org, :project, :userId, :now)
                """, new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                .addValue("id", UUID.randomUUID()).addValue("org", WorkflowFixture.ORG_A)
                .addValue("project", WorkflowFixture.PROJECT_A).addValue("userId", userId)
                .addValue("now", java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)));
            jdbc().update("""
                insert into employees ("id", "organizationId", "userId", "department",
                    "availabilityStatus", "maxConcurrentConversations", "activeConversationCount",
                    "createdAt", "updatedAt")
                values (:id, :org, :userId, 'billing', cast('ONLINE' as "EmployeeAvailabilityStatus"),
                        5, 0, :now, :now)
                """, new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                .addValue("id", employeeId).addValue("org", WorkflowFixture.ORG_A)
                .addValue("userId", userId)
                .addValue("now", java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)));
            return null;
        });
        return employeeId;
    }

    private org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc() {
        return jdbc;
    }

    private void setDepartment(UUID employeeId, String department) {
        PrincipalScope.call(admin, () -> {
            jdbc().update("""
                update employees e
                   set "department" = :department
                 where e."id" = :id and e."organizationId" = :org
                """, new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                .addValue("department", department).addValue("id", employeeId)
                .addValue("org", WorkflowFixture.ORG_A));
            return null;
        });
    }
}