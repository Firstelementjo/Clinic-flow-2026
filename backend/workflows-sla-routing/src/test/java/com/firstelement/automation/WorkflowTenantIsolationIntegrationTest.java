package com.firstelement.automation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstelement.automation.domain.RosterImport;
import com.firstelement.automation.persistence.CollisionRepository;
import com.firstelement.automation.persistence.ConversationAutomationRepository;
import com.firstelement.automation.persistence.EscalationRepository;
import com.firstelement.automation.persistence.SlaRepository;
import com.firstelement.automation.persistence.WorkflowHistoryRepository;
import com.firstelement.automation.persistence.WorkflowCatalogRepository;
import com.firstelement.automation.persistence.WorkflowRunActionRepository;
import com.firstelement.automation.persistence.WorkflowRunRepository;
import com.firstelement.security.TenantContext;
import com.firstelement.security.query.TenantScope;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Tenant isolation, stated as negatives.
 *
 * <h2>Why these are statements and not comments</h2>
 *
 * <p>Canonical statement section 12 exists for exactly this reason: "tenant scope is mandatory" is
 * only credible if the query that proves it exists. Every test here is a read that must return
 * <b>zero rows</b> or <b>empty</b> when tenant B is asking for tenant A's rows, and the row is
 * written first so the read has something real to fail to find.
 *
 * <h2>The three ways isolation fails</h2>
 *
 * <ol>
 *   <li>A client-supplied id finds the row anyway (an unscoped {@code where id = ?}).</li>
 *   <li>The row is found but the <em>child</em> rows are not scoped, so a summary leaks.</li>
 *   <li>The write lands in the wrong tenant, which is worse than either.</li>
 * </ol>
 *
 * <h2>What is deliberately not scoped</h2>
 *
 * <p>{@code enabledDefinitionTenants} returns organization ids and nothing else; it opens the scopes
 * rather than bypassing them, and {@code SlaCalendarIntegrationTest} covers its read path. It is
 * called out here so a reader knows it was a decision rather than an oversight.
 */
@SpringBootTest(classes = WorkflowTestApplication.class)
class WorkflowTenantIsolationIntegrationTest extends WorkflowIntegrationTestBase {

    @Autowired
    private WorkflowFixture fixture;

    @Autowired
    private WorkflowCatalogRepository catalog;

    @Autowired
    private WorkflowRunRepository runs;

    @Autowired
    private WorkflowRunActionRepository actions;

    @Autowired
    private CollisionRepository collisions;

    @Autowired
    private EscalationRepository escalations;

    @Autowired
    private ConversationAutomationRepository conversations;

    @Autowired
    private SlaRepository sla;

    @Autowired
    private WorkflowHistoryRepository history;

    @Autowired
    private com.firstelement.automation.service.RosterImportService roster;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Autowired
    private com.firstelement.security.query.TenantQueryGuard guard;

    private TenantContext adminA;
    private TenantContext adminB;

    /** The key of the definition {@link #tenantADefinitionWithKey()} last created. */
    private String lastKey;

    @BeforeEach
    void reset() {
        fixture.reset();
        adminA = PrincipalScope.adminA();
        adminB = PrincipalScope.adminB();
    }

    private TenantScope scopeA() {
        return new TenantScope(WorkflowFixture.ORG_A, WorkflowFixture.PROJECT_A);
    }

    private TenantScope scopeB() {
        return new TenantScope(WorkflowFixture.ORG_B, WorkflowFixture.PROJECT_B);
    }

    // ------------------------------------------------------------------
    // rows created by tenant A
    // ------------------------------------------------------------------

    /**
     * A definition belonging to tenant A.
     *
     * <p>Written through the repository under tenant A's scope and read back under the same scope, so
     * the row genuinely exists before the negatives try to find it — otherwise "tenant B cannot see
     * it" would be true for the uninteresting reason that nobody created it.
     */
    private UUID tenantADefinitionWithKey() {
        lastKey = "iso-" + UUID.randomUUID();
        UUID definitionId = UUID.randomUUID();
        PrincipalScope.call(adminA, () -> catalog.insertDefinition(scopeA(), definitionId, lastKey,
            "Isolation", null, WorkflowFixture.PROJECT_A, "MESSAGE_SCORED", 10,
            WorkflowFixture.ADMIN_A));
        return catalog.findDefinitionByKey(scopeA(), lastKey).orElseThrow().id();
    }

    /**
     * A running workflow run belonging to tenant A.
     *
     * <p>The definition and version rows are real, because the schema forbids a run that names a
     * version from another tenant or another definition. A fixture using random ids would be
     * refused by the very foreign keys these tests are checking.
     */
    private UUID tenantARun(UUID conversationId) {
        UUID definitionId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        java.time.LocalDateTime now = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC);
        jdbc.update("""
            insert into workflow_definitions
                ("id", "organizationId", "projectId", "key", "name", "triggerType", "priority",
                 "isEnabled", "latestVersionNumber", "createdAt", "updatedAt")
            values (:id, :org, :project, :key, 'Isolation run', 'MESSAGE_SCORED', 10, true, 1,
                    :now, :now)
            """, new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
            .addValue("id", definitionId).addValue("org", WorkflowFixture.ORG_A)
            .addValue("project", WorkflowFixture.PROJECT_A).addValue("key", "run-" + definitionId)
            .addValue("now", now));
        jdbc.update("""
            insert into workflow_versions
                ("id", "organizationId", "projectId", "workflowDefinitionId", "versionNumber",
                 "state", "stateMachine", "triggerConfig", "capabilityScope", "contentHash",
                 "createdAt", "updatedAt")
            values (:id, :org, :project, :definitionId, 1, cast('DRAFT' as "WorkflowVersionState"),
                    cast('{}' as jsonb), cast('{}' as jsonb), cast('[]' as jsonb), 'x', :now, :now)
            """, new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
            .addValue("id", versionId).addValue("org", WorkflowFixture.ORG_A)
            .addValue("project", WorkflowFixture.PROJECT_A).addValue("definitionId", definitionId)
            .addValue("now", now));

        UUID runId = UUID.randomUUID();
        lastVersionId = versionId;
        jdbc.update("""
            insert into workflow_runs
                ("id", "organizationId", "projectId", "workflowDefinitionId", "workflowVersionId",
                 "conversationId", "triggerInstanceId", "status", "principalUserId",
                 "capabilityScope", "loopDepth", "maxLoopDepth", "startedAt", "createdAt", "updatedAt")
            values (:id, :org, :project, :definitionId, :versionId, :conversationId, :trigger,
                    cast('RUNNING' as "WorkflowRunStatus"), :principal, cast('[]' as jsonb), 0, 5,
                    :now, :now, :now)
            """, new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
            .addValue("id", runId).addValue("org", WorkflowFixture.ORG_A)
            .addValue("project", WorkflowFixture.PROJECT_A).addValue("definitionId", definitionId)
            .addValue("versionId", versionId).addValue("conversationId", conversationId)
            .addValue("trigger", UUID.randomUUID().toString())
            .addValue("principal", WorkflowFixture.ADMIN_A).addValue("now", now));
        return runId;
    }

    // ------------------------------------------------------------------
    // the negatives
    // ------------------------------------------------------------------

    @Test
    @DisplayName("§12a a run from another tenant is never reachable")
    void runFromAnotherTenantIsUnreachable() {
        UUID conversationId = fixture.conversation(WorkflowFixture.ORG_A,
            WorkflowFixture.PROJECT_A, WorkflowFixture.CUSTOMER_A, "AI", "OPEN");
        UUID runId = tenantARun(conversationId);

        assertThat(PrincipalScope.call(adminA, () -> runs.findRun(scopeA(), runId))).isPresent();
        assertThat(PrincipalScope.call(adminB, () -> runs.findRun(scopeB(), runId)))
            .as("§12a: a client-supplied run id from another tenant must simply not be found")
            .isEmpty();
        assertThat(PrincipalScope.call(adminB, () ->
            runs.runningRunsForConversation(scopeB(), conversationId)))
            .as("and the same conversation id must not enumerate tenant A's in-flight runs")
            .isEmpty();
    }

    @Test
    @DisplayName("§12b a definition from another tenant is never reachable, by id or by key")
    void definitionFromAnotherTenantIsUnreachable() {
        UUID definitionId = tenantADefinitionWithKey();
        String key = lastKey;

        assertThat(PrincipalScope.call(adminA,
            () -> catalog.findDefinition(scopeA(), definitionId))).isPresent();
        assertThat(PrincipalScope.call(adminB,
            () -> catalog.findDefinition(scopeB(), definitionId))).isEmpty();
        assertThat(PrincipalScope.call(adminB, () -> catalog.findDefinitionByKey(scopeB(), key)))
            .as("the key is tenant-unique, so tenant B looking up tenant A's key must find nothing")
            .isEmpty();
        assertThat(PrincipalScope.call(adminB, () ->
            catalog.enabledDefinitionsForTrigger(scopeB(), WorkflowFixture.PROJECT_B,
                "MESSAGE_SCORED", 100)))
            .as("and tenant B's own trigger read must not surface tenant A's definitions")
            .allSatisfy(row -> assertThat(row.organizationId())
                .isEqualTo(WorkflowFixture.ORG_B));
    }

    @Test
    @DisplayName("§12c an action row is scoped, so a summary cannot leak across tenants")
    void actionRowsAreScoped() {
        UUID conversationId = fixture.conversation(WorkflowFixture.ORG_A,
            WorkflowFixture.PROJECT_A, WorkflowFixture.CUSTOMER_A, "AI", "OPEN");
        UUID runId = tenantARun(conversationId);
        java.time.LocalDateTime now =
            java.time.LocalDateTime.now(java.time.ZoneOffset.UTC);
        UUID actionId = UUID.randomUUID();
        PrincipalScope.call(adminA, () -> actions.append(scopeA(), actionId, runId, "score",
            "SCORE", 1, "SUCCEEDED", Map.of("a", 1), Map.of("b", 2), WorkflowFixture.ADMIN_A,
            "test", null, now, now, 5, null, null, false, null, null));

        assertThat(PrincipalScope.call(adminA, () -> actions.timelineForRun(scopeA(), runId)))
            .hasSize(1);
        assertThat(PrincipalScope.call(adminB, () -> actions.timelineForRun(scopeB(), runId)))
            .as("§12c: the action history of another tenant's run must read empty, not error")
            .isEmpty();
        assertThat(PrincipalScope.call(adminB, () -> actions.nextAttemptNumber(scopeB(), runId,
            "score")))
            .as("and the attempt counter must not report another tenant's history")
            .isEqualTo(1);
    }

    @Test
    @DisplayName("§12d another tenant's collisions are never reachable or counted")
    void collisionsAreScoped() {
        UUID conversationId = fixture.conversation(WorkflowFixture.ORG_A,
            WorkflowFixture.PROJECT_A, WorkflowFixture.CUSTOMER_A, "AI", "OPEN");
        UUID collisionId = UUID.randomUUID();
        PrincipalScope.call(adminA, () -> collisions.record(scopeA(),
            new CollisionRepository.NewCollision(collisionId, "CONVERSATION", "Conversation",
                conversationId, conversationId, null, null, "counterparty", "USER", null,
                "KEPT_BOTH", "two humans replied at once", null),
            1_800_000_000L, "corr", null));

        assertThat(PrincipalScope.call(adminA,
            () -> collisions.countOtherTenant(scopeA(), collisionId))).isZero();
        assertThat(PrincipalScope.call(adminB, () -> collisions.hasConversationCollision(scopeB(),
            conversationId)))
            .as("§12d: tenant B asking about tenant A's conversation must not see its incidents")
            .isFalse();
        List<WorkflowHistoryRepository.CollisionTally> tally = PrincipalScope.call(adminB, () ->
            history.collisionTally(scopeB(), java.time.LocalDateTime.of(2000, 1, 1, 0, 0), 50));
        assertThat(tally)
            .as("and the 'how often did a human win' counter must be per tenant")
            .isEmpty();
    }

    @Test
    @DisplayName("a conversation, its messages and its escalations are all scoped")
    void conversationReadsAreScoped() {
        UUID conversationId = fixture.conversation(WorkflowFixture.ORG_A,
            WorkflowFixture.PROJECT_A, WorkflowFixture.CUSTOMER_A, "AI", "OPEN");
        fixture.customerMessage(WorkflowFixture.ORG_A, WorkflowFixture.PROJECT_A, conversationId,
            "tenant A's business", java.time.LocalDateTime.of(2026, 8, 14, 9, 0));

        assertThat(PrincipalScope.call(adminA,
            () -> conversations.find(scopeA(), conversationId))).isPresent();
        assertThat(PrincipalScope.call(adminB,
            () -> conversations.find(scopeB(), conversationId)))
            .as("a conversation is the most sensitive row in the system and must be unreachable")
            .isEmpty();
        assertThat(PrincipalScope.call(adminB, () ->
            conversations.customerMessages(scopeB(), conversationId, 50)))
            .as("the transcript is customer content and must not leak either")
            .isEmpty();
        assertThat(PrincipalScope.call(adminB, () ->
            escalations.escalationsForConversation(scopeB(), conversationId, 50))).isEmpty();
    }

    @Test
    @DisplayName("an SLA policy and calendar belonging to one tenant are invisible to the other")
    void slaRowsAreScoped() {
        UUID calendarId = UUID.randomUUID();
        PrincipalScope.call(adminA, () -> {
            sla.insertCalendar(scopeA(), calendarId, WorkflowFixture.PROJECT_A, "iso-hours",
                "Isolation hours", 1, "UTC", java.time.LocalDate.of(2026, 1, 1),
                WorkflowFixture.ADMIN_A);
            sla.activateCalendar(scopeA(), calendarId);
            return null;
        });
        UUID policyId = UUID.randomUUID();
        PrincipalScope.call(adminA, () -> sla.insertPolicy(scopeA(), policyId,
            WorkflowFixture.PROJECT_A, "iso-policy", "Isolation policy", null, null, 10,
            com.firstelement.automation.domain.SlaClockType.FIRST_RESPONSE, 60, null, null,
            calendarId, null, null, WorkflowFixture.ADMIN_A));

        assertThat(PrincipalScope.call(adminA, () -> sla.findPolicy(scopeA(), policyId))).isPresent();
        assertThat(PrincipalScope.call(adminB, () -> sla.findPolicy(scopeB(), policyId)))
            .isEmpty();
        assertThat(PrincipalScope.call(adminB, () -> sla.findCalendar(scopeB(), calendarId)))
            .as("another tenant's working week must not be readable, or a clock could pause by it")
            .isEmpty();
        assertThat(PrincipalScope.call(adminB, () -> sla.calendarVersions(scopeB(), "iso-hours")))
            .as("nor through the key-history path, which is a different query and a different "
                + "opportunity to forget the predicate")
            .isEmpty();
        assertThat(PrincipalScope.call(adminB, () -> sla.applicablePolicies(scopeB(),
            WorkflowFixture.PROJECT_B, 100)))
            .allSatisfy(row -> assertThat(row.organizationId()).isEqualTo(WorkflowFixture.ORG_B));
    }

    @Test
    @DisplayName("§12f the roster import applies only to the caller's own tenant")
    void rosterImportIsTenantScoped() {
        // Tenant A's own employee. The roster imports against an EXISTING user — user creation is
        // identity-access's job — so the address must be one this tenant already holds, and the
        // preview reports it as a duplicate rather than a fresh row.
        assertThat(PrincipalScope.call(adminA, () -> roster.existingEmails(adminA)))
            .as("tenant A's own roster: both of its seeded users")
            .contains("admin@a.test", "staff@a.test");
        // The real negative: tenant B, whose only user is admin@b.test, must not see tenant A's
        // addresses at all. A roster that could see them would report every one of its rows as a
        // duplicate of somebody in another clinic.
        assertThat(PrincipalScope.call(adminB, () -> roster.existingEmails(adminB)))
            .as("tenant B must see only its own users")
            .containsExactly("admin@b.test");

        // Tenant B presents tenant A's address. It is a syntactically valid row, and it is NOT flagged
        // as a duplicate — because tenant B holds no such user. The refusal happens at apply, per
        // row, with USER_NOT_FOUND: "never blindly import malformed rows" and "nothing is skipped
        // silently" together mean a row this module cannot fulfil is reported, not guessed at.
        RosterImport.Preview otherPreview = PrincipalScope.call(adminB, () ->
            roster.preview(adminB, "email,fullname\nstaff@a.test,Someone Else\n", null));
        assertThat(otherPreview.duplicateRows())
            .as("tenant B holds no user with that address, so it is not a duplicate there")
            .isEmpty();

        int before = fixture.count(
            "select count(*) from employees e where e.\"organizationId\" = :org",
            Map.of("org", WorkflowFixture.ORG_B));
        com.firstelement.automation.service.RosterImportService.ApplyReport report =
            PrincipalScope.call(adminB, () -> roster.apply(adminB, otherPreview));

        assertThat(report.applied()).isZero();
        assertThat(report.skipped())
            .as("the row must be reported, with its number and its reason, never dropped silently")
            .anySatisfy(reason -> assertThat(reason)
                .contains("row 1").contains("USER_NOT_FOUND").contains("staff@a.test"));
        assertThat(fixture.count(
            "select count(*) from employees e where e.\"organizationId\" = :org",
            Map.of("org", WorkflowFixture.ORG_B)))
            .as("and no employee may be created in any tenant")
            .isEqualTo(before);
        assertThat(fixture.count("""
            select count(*) from employees e join users u on u."id" = e."userId"
             where u."email" = :email
            """, Map.of("email", "staff@a.test")))
            .as("and tenant A's employee is untouched by tenant B's roster")
            .isEqualTo(1);
    }

    @Test
    @DisplayName("a project id the caller does not hold is refused before any statement is built")
    void foreignProjectIdIsRefused() {
        assertThatThrownBy(() -> com.firstelement.automation.service.RoutingService.requireProject(null))
            .as("a null project is refused outright")
            .isInstanceOf(com.firstelement.crm.error.ValidationFailedException.class);

        // The guard is the real gate: a client-supplied project id is a request, never an
        // authorization, so opening a scope for a project the caller's context does not carry must
        // fail before any statement is built.
        assertThatThrownBy(() -> PrincipalScope.call(adminB, () ->
            guard.scope(adminB, WorkflowFixture.PROJECT_A)))
            .as("tenant B's admin must not be able to open tenant A's project scope")
            .isInstanceOf(RuntimeException.class);
        assertThat(PrincipalScope.call(adminA, () -> guard.scope(adminA, WorkflowFixture.PROJECT_A)))
            .as("and its own project is of course fine, so the test above is not vacuous")
            .isNotNull();
    }

    @Test
    @DisplayName("§12h the database refuses a run naming another tenant's conversation")
    void crossTenantRunIsRefusedByTheSchema() {
        // Tenant A's conversation and tenant B's own definition/version: every id is a real row, so
        // the only thing wrong with the insert below is that it crosses the tenant boundary.
        UUID tenantAConversation = fixture.conversation(WorkflowFixture.ORG_A,
            WorkflowFixture.PROJECT_A, WorkflowFixture.CUSTOMER_A, "AI", "OPEN");
        UUID tenantBDefinition = UUID.randomUUID();
        UUID tenantBVersion = UUID.randomUUID();
        java.time.LocalDateTime now = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC);
        insertTenantBDefinition(tenantBDefinition, tenantBVersion, now);

        assertThat(fixture.count("select count(*) from conversations where \"id\" = :id",
            Map.of("id", tenantAConversation)))
            .as("the conversation really exists, so the refusal below is about the boundary and "
                + "not about a missing row").isEqualTo(1);

        // FORBIDDEN BY THE DATABASE, not merely by application discipline: the composite foreign key
        // over (conversationId, organizationId) rejects it outright.
        assertThatThrownBy(() -> jdbc.update("""
            insert into workflow_runs
                ("id", "organizationId", "projectId", "workflowDefinitionId", "workflowVersionId",
                 "conversationId", "triggerInstanceId", "status", "principalUserId",
                 "capabilityScope", "loopDepth", "maxLoopDepth", "startedAt", "createdAt", "updatedAt")
            values (:id, :org, :project, :definitionId, :versionId, :conversationId, :trigger,
                    cast('RUNNING' as "WorkflowRunStatus"), :principal, cast('[]' as jsonb), 0, 5,
                    :now, :now, :now)
            """, new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
            .addValue("id", UUID.randomUUID()).addValue("org", WorkflowFixture.ORG_B)
            .addValue("project", WorkflowFixture.PROJECT_B)
            .addValue("definitionId", tenantBDefinition).addValue("versionId", tenantBVersion)
            .addValue("conversationId", tenantAConversation)
            .addValue("trigger", UUID.randomUUID().toString())
            .addValue("principal", WorkflowFixture.ADMIN_B).addValue("now", now)))
            .as("a run may not name another tenant's conversation")
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        // And the version-composite foreign key: tenant B's run naming tenant A's VERSION.
        UUID tenantAVersion = insertTenantAVersion();
        assertThatThrownBy(() -> jdbc.update("""
            insert into workflow_runs
                ("id", "organizationId", "projectId", "workflowDefinitionId", "workflowVersionId",
                 "conversationId", "triggerInstanceId", "status", "principalUserId",
                 "capabilityScope", "loopDepth", "maxLoopDepth", "startedAt", "createdAt", "updatedAt")
            values (:id, :org, :project, :definitionId, :versionId, :conversationId, :trigger,
                    cast('RUNNING' as "WorkflowRunStatus"), :principal, cast('[]' as jsonb), 0, 5,
                    :now, :now, :now)
            """, new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
            .addValue("id", UUID.randomUUID()).addValue("org", WorkflowFixture.ORG_B)
            .addValue("project", WorkflowFixture.PROJECT_B)
            .addValue("definitionId", tenantBDefinition).addValue("versionId", tenantAVersion)
            .addValue("conversationId", tenantAConversation)
            .addValue("trigger", UUID.randomUUID().toString())
            .addValue("principal", WorkflowFixture.ADMIN_B).addValue("now", now)))
            .as("§12h: a run may not name another tenant's version, and the composite foreign key "
                + "over (workflowVersionId, workflowDefinitionId, organizationId) rejects it")
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    private void insertTenantBDefinition(UUID definitionId, UUID versionId,
                                         java.time.LocalDateTime now) {
        jdbc.update("""
            insert into workflow_definitions
                ("id", "organizationId", "projectId", "key", "name", "triggerType", "priority",
                 "isEnabled", "latestVersionNumber", "createdAt", "updatedAt")
            values (:id, :org, :project, :key, 'B', 'MESSAGE_SCORED', 10, true, 1, :now, :now)
            """, new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
            .addValue("id", definitionId).addValue("org", WorkflowFixture.ORG_B)
            .addValue("project", WorkflowFixture.PROJECT_B).addValue("key", "b-" + definitionId)
            .addValue("now", now));
        jdbc.update("""
            insert into workflow_versions
                ("id", "organizationId", "projectId", "workflowDefinitionId", "versionNumber",
                 "state", "stateMachine", "triggerConfig", "capabilityScope", "contentHash",
                 "createdAt", "updatedAt")
            values (:id, :org, :project, :definitionId, 1, cast('DRAFT' as "WorkflowVersionState"),
                    cast('{}' as jsonb), cast('{}' as jsonb), cast('[]' as jsonb), 'x', :now, :now)
            """, new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
            .addValue("id", versionId).addValue("org", WorkflowFixture.ORG_B)
            .addValue("project", WorkflowFixture.PROJECT_B).addValue("definitionId", definitionId)
            .addValue("now", now));
    }

    /** A version row that genuinely belongs to tenant A, returned as its id. */
    private UUID insertTenantAVersion() {
        UUID conversationId = fixture.conversation(WorkflowFixture.ORG_A,
            WorkflowFixture.PROJECT_A, WorkflowFixture.CUSTOMER_A, "AI", "OPEN");
        tenantARun(conversationId);
        return lastVersionId;
    }

    private UUID lastVersionId;
}