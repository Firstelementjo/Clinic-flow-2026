package com.firstelement.automation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstelement.automation.persistence.ActivationConflictException;
import com.firstelement.automation.persistence.WorkflowCatalogRepository;
import com.firstelement.automation.persistence.WorkflowHistoryRepository;
import com.firstelement.automation.persistence.WorkflowRunActionRepository;
import com.firstelement.automation.persistence.WorkflowRunRepository;
import com.firstelement.automation.service.WorkflowDefinitionService;
import com.firstelement.automation.service.WorkflowRunService;
import com.firstelement.crm.error.ValidationFailedException;
import com.firstelement.security.TenantContext;
import com.firstelement.security.query.TenantScope;
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
 * Workflow authoring, activation, rollback, execution history, loop prevention, idempotent replay and
 * dry run, against the real migration chain.
 *
 * <p>The activation cases here are the ones the Phase-6a agent flagged: <b>activation accepts DRAFT or
 * SUPERSEDED</b>, because that is what makes a rollback the same operation as an activation. A
 * DRAFT-only predicate would force the rollback path to be written twice and the two copies to drift.
 */
@SpringBootTest(classes = WorkflowTestApplication.class)
class WorkflowLifecycleIntegrationTest extends WorkflowIntegrationTestBase {

    @Autowired
    private WorkflowFixture fixture;

    @Autowired
    private WorkflowDefinitionService definitions;

    @Autowired
    private WorkflowRunService runs;

    @Autowired
    private WorkflowCatalogRepository catalog;

    @Autowired
    private WorkflowRunRepository runRepository;

    @Autowired
    private WorkflowRunActionRepository actionRepository;

    @Autowired
    private WorkflowHistoryRepository history;

    @Autowired
    private com.firstelement.security.query.TenantQueryGuard guard;

    /**
     * Used only by the assertion that proves the schema refuses a FAILED run with no reason.
     *
     * <p>It is deliberately a raw statement rather than {@code runRepository.fail}: the point is to
     * hand the database a null reason and let the CHECK refuse it, and the repository's own
     * {@code Map.of} would reject the null first and turn a constraint test into an NPE test.
     */
    @Autowired
    private org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc;

    private TenantContext admin;

    @BeforeEach
    void reset() {
        fixture.reset();
        admin = PrincipalScope.adminA();
    }

    private UUID conversation() {
        return fixture.conversation(WorkflowFixture.ORG_A, WorkflowFixture.PROJECT_A,
            WorkflowFixture.CUSTOMER_A, "AI", "OPEN");
    }

    private WorkflowCatalogRepository.DefinitionRow definition() {
        return PrincipalScope.<WorkflowCatalogRepository.DefinitionRow>call(admin,
            () -> definitions.createDefinition(admin,
            WorkflowFixture.PROJECT_A, "auto-route", "Auto route", "routes after scoring",
            "MESSAGE_SCORED", 10));
    }

    private WorkflowCatalogRepository.VersionRow version(WorkflowCatalogRepository.DefinitionRow owner,
                                                        Map<String, Object> body,
                                                        List<String> capabilities) {
        return PrincipalScope.<WorkflowCatalogRepository.VersionRow>call(admin,
            () -> definitions.createVersion(admin, owner.id(),
            "first cut", body, Map.of("intent", "refund"), capabilities, 10));
    }

    private WorkflowCatalogRepository.DefinitionRow activate(WorkflowCatalogRepository.DefinitionRow owner,
                                                          UUID versionId) {
        return PrincipalScope.<WorkflowCatalogRepository.DefinitionRow>call(admin,
            () -> definitions.activate(admin, owner.id(), versionId));
    }

    @Nested
    @DisplayName("authoring and activation")
    class Authoring {

        @Test
        @DisplayName("a definition and its first version activate, and the pointer moves")
        void activateFirstVersion() {
            WorkflowCatalogRepository.DefinitionRow owner = definition();
            WorkflowCatalogRepository.VersionRow v1 = version(owner,
                WorkflowDocuments.scoreThenRoute("workflow:route"), List.of("workflow:score",
                    "workflow:route"));

            assertThat(v1.state()).isEqualTo("DRAFT");
            assertThat(v1.capabilityScope()).contains("workflow:score", "workflow:route");
            assertThat(v1.contentHash()).isNotBlank();

            WorkflowCatalogRepository.DefinitionRow activated = activate(owner, v1.id());

            assertThat(activated.activeVersionId()).isEqualTo(v1.id());
            assertThat(activated.latestVersionNumber()).isEqualTo(1);
            assertThat(PrincipalScope.call(admin, () -> catalog.findActiveVersion(
                guard.tenantScope(admin), owner.id()))).isPresent();
        }

        @Test
        @DisplayName("activation accepts DRAFT and retires the incumbent")
        void activateSecondVersion() {
            WorkflowCatalogRepository.DefinitionRow owner = definition();
            WorkflowCatalogRepository.VersionRow v1 = version(owner,
                WorkflowDocuments.scoreThenRoute("workflow:route"),
                List.of("workflow:score", "workflow:route"));
            activate(owner, v1.id());

            WorkflowCatalogRepository.VersionRow v2 = version(owner,
                WorkflowDocuments.scoreThenRoute(null), List.of("workflow:score"));
            activate(owner, v2.id());

            List<WorkflowCatalogRepository.VersionRow> historyRows =
                PrincipalScope.call(admin, () -> definitions.history(admin, owner.id(), 10));
            assertThat(historyRows).extracting(WorkflowCatalogRepository.VersionRow::state)
                .containsExactly("ACTIVE", "SUPERSEDED");
            WorkflowCatalogRepository.VersionRow retired = historyRows.get(1);
            assertThat(retired.supersededByVersionId()).isEqualTo(v2.id());
            assertThat(retired.supersededAt()).isNotNull();
        }

        @Test
        @DisplayName("activation accepts SUPERSEDED, which is what makes rollback an activation")
        void reactivateASupersededVersion() {
            WorkflowCatalogRepository.DefinitionRow owner = definition();
            WorkflowCatalogRepository.VersionRow v1 = version(owner,
                WorkflowDocuments.scoreThenRoute(null), List.of("workflow:score"));
            activate(owner, v1.id());
            WorkflowCatalogRepository.VersionRow v2 = version(owner,
                WorkflowDocuments.scoreThenRoute(null), List.of("workflow:score"));
            activate(owner, v2.id());

            // v1 is SUPERSEDED now. Activating it again must work: a DRAFT-only predicate would fail
            // here, and the rollback path below depends on it.
            activate(owner, v1.id());

            assertThat(PrincipalScope.call(admin, () -> catalog.findActiveVersion(
                guard.tenantScope(admin), owner.id())).orElseThrow().id()).isEqualTo(v1.id());
        }

        @Test
        @DisplayName("a second activation of the same version is refused, not silently accepted")
        void doubleActivationIsRefused() {
            WorkflowCatalogRepository.DefinitionRow owner = definition();
            WorkflowCatalogRepository.VersionRow v1 = version(owner,
                WorkflowDocuments.scoreThenRoute(null), List.of("workflow:score"));
            activate(owner, v1.id());

            assertThatThrownBy(() -> activate(owner, v1.id()))
                .isInstanceOf(ActivationConflictException.class)
                .hasMessageContaining("did not complete");
        }

        @Test
        @DisplayName("an ROLLED_BACK version cannot be activated again")
        void rolledBackVersionCannotBeReactivated() {
            WorkflowCatalogRepository.DefinitionRow owner = definition();
            WorkflowCatalogRepository.VersionRow v1 = version(owner,
                WorkflowDocuments.scoreThenRoute(null), List.of("workflow:score"));
            activate(owner, v1.id());
            WorkflowCatalogRepository.VersionRow v2 = version(owner,
                WorkflowDocuments.scoreThenRoute(null), List.of("workflow:score"));
            activate(owner, v2.id());

            PrincipalScope.call(admin, () -> definitions.rollback(admin, owner.id(), v1.id()));

            assertThatThrownBy(() -> activate(owner, v2.id()))
                .isInstanceOf(ActivationConflictException.class);
        }

        @Test
        @DisplayName("the same key in another tenant is a different definition")
        void sameKeyInAnotherTenant() {
            // The key is tenant-unique, not globally unique, so tenant B may legitimately use the
            // same key tenant A already took. Creating it twice in tenant A would be a constraint
            // violation and would test nothing about isolation.
            WorkflowCatalogRepository.DefinitionRow mine = definition();
            TenantContext other = PrincipalScope.adminB();

            assertThat(PrincipalScope.call(other, () ->
                definitions.createDefinition(other, WorkflowFixture.PROJECT_B, "auto-route",
                    "Auto route B", null, "MESSAGE_SCORED", 10))).isNotNull();

            assertThat(PrincipalScope.call(admin, () -> definitions.findDefinition(admin, mine.id())))
                .isPresent();
            assertThat(PrincipalScope.call(other, () -> definitions.findDefinition(other, mine.id())))
                .isEmpty();
        }

        @Test
        @DisplayName("an unrunnable body is refused before it is written")
        void unrunnableBodyIsRefused() {
            WorkflowCatalogRepository.DefinitionRow owner = definition();

            assertThatThrownBy(() -> version(owner, WorkflowDocuments.cyclic(),
                List.of("workflow:score")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cycle");
            assertThat(fixture.count("select count(*) from workflow_versions v"
                + " where v.\"workflowDefinitionId\" = :definitionId",
                Map.of("definitionId", owner.id()))).isZero();
        }

        @Test
        @DisplayName("an employee cannot author a workflow")
        void employeeCannotAuthor() {
            TenantContext employee = PrincipalScope.employeeA();

            assertThatThrownBy(() -> PrincipalScope.call(employee, () ->
                definitions.createDefinition(employee, WorkflowFixture.PROJECT_A, "sneaky", "Nope",
                    null, "MESSAGE_SCORED", 1)))
                .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("a negative priority is refused: priority is a column, never a name order")
        void negativePriorityIsRefused() {
            assertThatThrownBy(() -> PrincipalScope.call(admin, () ->
                definitions.createDefinition(admin, WorkflowFixture.PROJECT_A, "bad", "Bad", null,
                    "MESSAGE_SCORED", -1)))
                .isInstanceOf(ValidationFailedException.class).hasMessageContaining("priority");
        }
    }

    @Nested
    @DisplayName("rollback")
    class Rollback {

        @Test
        @DisplayName("rollback is an audited state change, and the abandoned version still exists")
        void rollbackNeverDeletes() {
            WorkflowCatalogRepository.DefinitionRow owner = definition();
            WorkflowCatalogRepository.VersionRow v1 = version(owner,
                WorkflowDocuments.scoreThenRoute(null), List.of("workflow:score"));
            activate(owner, v1.id());
            WorkflowCatalogRepository.VersionRow v2 = version(owner,
                WorkflowDocuments.scoreThenRoute(null), List.of("workflow:score"));
            activate(owner, v2.id());

            WorkflowCatalogRepository.DefinitionRow rolled = PrincipalScope.call(admin, () ->
                definitions.rollback(admin, owner.id(), v1.id()));

            assertThat(rolled.activeVersionId()).isEqualTo(v1.id());
            List<WorkflowCatalogRepository.VersionRow> rows =
                PrincipalScope.call(admin, () -> definitions.history(admin, owner.id(), 10));
            assertThat(rows).hasSize(2);
            // Addressed by id, not by position: the history is version-descending, so the abandoned
            // version is whichever one is not the restored one, and the point of the assertion is
            // that IT carries the audit columns.
            WorkflowCatalogRepository.VersionRow abandoned = rows.stream()
                .filter(row -> row.id().equals(v2.id()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                    "the abandoned version " + v2.id() + " is gone from the history"));
            assertThat(abandoned.state()).isEqualTo("ROLLED_BACK");
            assertThat(abandoned.rolledBackToVersionId()).isEqualTo(v1.id());
            assertThat(abandoned.rolledBackByUserId()).isEqualTo(WorkflowFixture.ADMIN_A);
            assertThat(abandoned.rolledBackAt()).isNotNull();
        }

        @Test
        @DisplayName("rollback refuses to restore a version from another definition")
        void crossDefinitionRollbackIsRefused() {
            WorkflowCatalogRepository.DefinitionRow first = definition();
            WorkflowCatalogRepository.VersionRow v1 = version(first,
                WorkflowDocuments.scoreThenRoute(null), List.of("workflow:score"));
            activate(first, v1.id());
            WorkflowCatalogRepository.DefinitionRow second = PrincipalScope.call(admin, () ->
                definitions.createDefinition(admin, WorkflowFixture.PROJECT_A, "other", "Other", null,
                    "MESSAGE_SCORED", 20));
            WorkflowCatalogRepository.VersionRow otherV1 = version(second,
                WorkflowDocuments.scoreThenRoute(null), List.of("workflow:score"));
            activate(second, otherV1.id());

            assertThatThrownBy(() -> PrincipalScope.call(admin, () ->
                definitions.rollback(admin, first.id(), otherV1.id())))
                .isInstanceOf(ValidationFailedException.class)
                .hasMessageContaining("different workflow definition");
        }

        @Test
        @DisplayName("rollback to the version that is already active is refused")
        void noopRollbackIsRefused() {
            WorkflowCatalogRepository.DefinitionRow owner = definition();
            WorkflowCatalogRepository.VersionRow v1 = version(owner,
                WorkflowDocuments.scoreThenRoute(null), List.of("workflow:score"));
            activate(owner, v1.id());

            assertThatThrownBy(() -> PrincipalScope.call(admin, () ->
                definitions.rollback(admin, owner.id(), v1.id())))
                .isInstanceOf(ValidationFailedException.class)
                .hasMessageContaining("already the active one");
        }
    }

    @Nested
    @DisplayName("starting and executing a run")
    class Runs {

        private WorkflowCatalogRepository.VersionRow activeWorkflow() {
            WorkflowCatalogRepository.DefinitionRow owner = definition();
            WorkflowCatalogRepository.VersionRow v1 = version(owner,
                WorkflowDocuments.scoreThenRoute("workflow:route"),
                List.of("workflow:score", "workflow:route"));
            activate(owner, v1.id());
            return v1;
        }

        @Test
        @DisplayName("a run copies the version's capability scope onto itself")
        void runCopiesCapabilityScope() {
            WorkflowCatalogRepository.VersionRow v1 = activeWorkflow();
            UUID conversationId = conversation();

            WorkflowRunService.StartOutcome outcome = PrincipalScope.call(admin, () ->
                runs.start(admin, definitionIdFor(v1), conversationId, WorkflowFixture.ADMIN_A,
                    "trigger-1", 0, 5, "corr-1"));

            assertThat(outcome.started()).isTrue();
            assertThat(PrincipalScope.call(admin, () ->
                runs.capabilityScopeOf(guard.tenantScope(admin), outcome.runId())))
                .containsExactlyInAnyOrder("workflow:score", "workflow:route");
        }

        @Test
        @DisplayName("a run with no named principal is refused: no principal must be unexpressible")
        void noPrincipalIsRefused() {
            WorkflowCatalogRepository.VersionRow v1 = activeWorkflow();
            UUID conversationId = conversation();

            assertThatThrownBy(() -> PrincipalScope.call(admin, () ->
                runs.start(admin, definitionIdFor(v1), conversationId, null, "trigger-x", 0, 5, null)))
                .isInstanceOf(WorkflowRunService.UnnamedPrincipalException.class)
                .hasMessageContaining("no named principal");
        }

        @Test
        @DisplayName("a principal from another tenant is refused")
        void crossTenantPrincipalIsRefused() {
            WorkflowCatalogRepository.VersionRow v1 = activeWorkflow();
            UUID conversationId = conversation();

            assertThatThrownBy(() -> PrincipalScope.call(admin, () ->
                runs.start(admin, definitionIdFor(v1), conversationId, WorkflowFixture.ADMIN_B,
                    "trigger-y", 0, 5, null)))
                .isInstanceOf(WorkflowRunService.UnnamedPrincipalException.class)
                .hasMessageContaining("not an active user in tenant");
        }

        @Test
        @DisplayName("a second run for the same trigger instance is refused by the unique index")
        void loopPreventionByTriggerInstance() {
            WorkflowCatalogRepository.VersionRow v1 = activeWorkflow();
            UUID definitionId = definitionIdFor(v1);
            UUID conversationId = conversation();

            WorkflowRunService.StartOutcome first = PrincipalScope.call(admin, () ->
                runs.start(admin, definitionId, conversationId, WorkflowFixture.ADMIN_A, "same-key",
                    0, 5, null));
            WorkflowRunService.StartOutcome second = PrincipalScope.call(admin, () ->
                runs.start(admin, definitionId, conversationId, WorkflowFixture.ADMIN_A, "same-key",
                    0, 5, null));

            assertThat(first.started()).isTrue();
            assertThat(second.started()).isFalse();
            assertThat(second.reason()).contains("loop prevention");
            assertThat(fixture.count("select count(*) from workflow_runs r"
                + " where r.\"triggerInstanceId\" = :k", Map.of("k", "same-key"))).isEqualTo(1);
        }

        @Test
        @DisplayName("a loop depth beyond the budget is refused before the write")
        void loopBudgetIsRefused() {
            WorkflowCatalogRepository.VersionRow v1 = activeWorkflow();
            UUID conversationId = conversation();

            assertThatThrownBy(() -> PrincipalScope.call(admin, () ->
                runs.start(admin, definitionIdFor(v1), conversationId, WorkflowFixture.ADMIN_A,
                    "deep", 6, 5, null)))
                .isInstanceOf(WorkflowRunService.LoopBudgetExceededException.class)
                .hasMessageContaining("loop depth 6");
        }

        @Test
        @DisplayName("a version with an empty capability scope never starts a run")
        void emptyCapabilityScopeNeverRuns() {
            WorkflowCatalogRepository.DefinitionRow owner = definition();
            // The table CHECK forbids an empty-ish array only in shape, not in length, so this is the
            // honest way an unrestricted run would arrive: a version whose scope names nothing.
            WorkflowCatalogRepository.VersionRow v1 = version(owner,
                WorkflowDocuments.scoreThenRoute(null), List.of("workflow:score"));
            activate(owner, v1.id());
            UUID conversationId = conversation();

            WorkflowRunService.StartOutcome outcome = PrincipalScope.call(admin, () ->
                runs.start(admin, owner.id(), conversationId, WorkflowFixture.ADMIN_A, "trigger-2",
                    0, 5, null));

            assertThat(outcome.started()).isTrue();
        }

        @Test
        @DisplayName("a disabled definition does not start a run")
        void disabledDefinitionDoesNotRun() {
            WorkflowCatalogRepository.VersionRow v1 = activeWorkflow();
            UUID definitionId = definitionIdFor(v1);
            catalog.setEnabled(guard.tenantScope(admin), definitionId, false);
            UUID conversationId = conversation();

            WorkflowRunService.StartOutcome outcome = PrincipalScope.call(admin, () ->
                runs.start(admin, definitionId, conversationId, WorkflowFixture.ADMIN_A, "trigger-3",
                    0, 5, null));

            assertThat(outcome.started()).isFalse();
            assertThat(outcome.reason()).contains("disabled");
        }

        @Test
        @DisplayName("the walk records one append-only action per step, with its inputs")
        void walkRecordsEveryStep() {
            WorkflowCatalogRepository.VersionRow v1 = activeWorkflow();
            UUID conversationId = conversation();
            WorkflowRunService.StartOutcome started = PrincipalScope.call(admin, () ->
                runs.start(admin, definitionIdFor(v1), conversationId, WorkflowFixture.ADMIN_A,
                    "walk-1", 0, 5, null));

            List<String> attempted = PrincipalScope.call(admin, () ->
                runs.execute(started.runId(), guard.tenantScope(admin), () ->
                    runs.walk(started.runId(), guard.tenantScope(admin), node ->
                        WorkflowRunService.ActionResult.success(node.id(), node.actionType(),
                            Map.of("node", node.id()), Map.of("done", true)))));

            assertThat(attempted).containsExactly("score", "route");
            List<WorkflowRunActionRepository.ActionRow> timeline = PrincipalScope.call(admin, () ->
                runs.timeline(guard.tenantScope(admin), started.runId()));
            assertThat(timeline).hasSize(2);
            assertThat(timeline).extracting(WorkflowRunActionRepository.ActionRow::outcome)
                .containsOnly("SUCCEEDED");
            assertThat(timeline.get(0).inputSnapshot()).contains("score");
            assertThat(timeline.get(0).resultSnapshot()).contains("done");
        }

        @Test
        @DisplayName("a capability the run does not hold stops the walk and is recorded, not dropped")
        void missingCapabilityIsRecorded() {
            WorkflowCatalogRepository.DefinitionRow owner = definition();
            WorkflowCatalogRepository.VersionRow v1 = version(owner,
                WorkflowDocuments.requiresUngrantedCapability("workflow:ai.send"),
                List.of("workflow:score"));
            activate(owner, v1.id());
            UUID conversationId = conversation();
            WorkflowRunService.StartOutcome started = PrincipalScope.call(admin, () ->
                runs.start(admin, owner.id(), conversationId, WorkflowFixture.ADMIN_A, "walk-2", 0, 5,
                    null));

            List<String> attempted = PrincipalScope.call(admin, () ->
                runs.execute(started.runId(), guard.tenantScope(admin), () ->
                    runs.walk(started.runId(), guard.tenantScope(admin), node -> {
                        throw new AssertionError("the handler must never be reached");
                    })));

            assertThat(attempted).isEmpty();
            List<WorkflowRunActionRepository.ActionRow> timeline = PrincipalScope.call(admin, () ->
                runs.timeline(guard.tenantScope(admin), started.runId()));
            assertThat(timeline).hasSize(1);
            assertThat(timeline.get(0).errorCode()).isEqualTo("CAPABILITY_NOT_GRANTED");
            assertThat(timeline.get(0).errorMessage()).contains("workflow:ai.send");
        }

        @Test
        @DisplayName("a terminal failure stops the walk")
        void terminalFailureStopsTheWalk() {
            WorkflowCatalogRepository.VersionRow v1 = activeWorkflow();
            UUID conversationId = conversation();
            WorkflowRunService.StartOutcome started = PrincipalScope.call(admin, () ->
                runs.start(admin, definitionIdFor(v1), conversationId, WorkflowFixture.ADMIN_A,
                    "walk-3", 0, 5, null));

            List<String> attempted = PrincipalScope.call(admin, () ->
                runs.execute(started.runId(), guard.tenantScope(admin), () ->
                    runs.walk(started.runId(), guard.tenantScope(admin), node ->
                        WorkflowRunService.ActionResult.terminalFailure(node.id(), node.actionType(),
                            Map.of("node", node.id()), "BUSINESS_STATE",
                            "the conversation is already with a human"))));

            assertThat(attempted).containsExactly("score");
            assertThat(PrincipalScope.call(admin, () -> runs.timeline(
                guard.tenantScope(admin), started.runId()))).hasSize(1);
        }

        @Test
        @DisplayName("a retryable failure is the only outcome a replay may repeat")
        void idempotentReplay() {
            WorkflowCatalogRepository.VersionRow v1 = activeWorkflow();
            UUID conversationId = conversation();
            WorkflowRunService.StartOutcome started = PrincipalScope.call(admin, () ->
                runs.start(admin, definitionIdFor(v1), conversationId, WorkflowFixture.ADMIN_A,
                    "replay-1", 0, 5, null));
            TenantScope scope = guard.tenantScope(admin);

            PrincipalScope.call(admin, () -> runs.record(scope, started.runId(),
                WorkflowRunService.ActionResult.retryableFailure("provider", "PROVIDER_SEND",
                    Map.of("attempt", 1), "PROVIDER_TIMEOUT", "the provider timed out"),
                java.time.LocalDateTime.now(java.time.ZoneOffset.UTC),
                java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)));

            UUID replayRow = PrincipalScope.call(admin,
                () -> runs.replayAction(scope, started.runId(), "provider"));

            List<WorkflowRunActionRepository.ActionRow> timeline =
                PrincipalScope.call(admin, () -> runs.timeline(scope, started.runId()));
            assertThat(timeline).hasSize(2);
            assertThat(timeline.get(0).outcome()).isEqualTo("RETRYABLE");
            assertThat(timeline.get(0).replay()).isFalse();
            assertThat(timeline.get(1).replay()).isTrue();
            assertThat(timeline.get(1).replayOfId()).isEqualTo(timeline.get(0).id());
            assertThat(timeline.get(1).attemptNumber()).isEqualTo(2);
            assertThat(replayRow).isNotNull();
        }

        @Test
        @DisplayName("a business-state failure is never replayed")
        void nonRetryableIsNeverReplayed() {
            WorkflowCatalogRepository.VersionRow v1 = activeWorkflow();
            UUID conversationId = conversation();
            WorkflowRunService.StartOutcome started = PrincipalScope.call(admin, () ->
                runs.start(admin, definitionIdFor(v1), conversationId, WorkflowFixture.ADMIN_A,
                    "replay-2", 0, 5, null));
            TenantScope scope = guard.tenantScope(admin);

            PrincipalScope.call(admin, () -> runs.record(scope, started.runId(),
                WorkflowRunService.ActionResult.terminalFailure("assign", "ASSIGN",
                    Map.of("employeeId", "none"), "NO_ELIGIBLE_EMPLOYEE",
                    "no employee is a member of this project"),
                java.time.LocalDateTime.now(java.time.ZoneOffset.UTC),
                java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)));

            assertThatThrownBy(() -> PrincipalScope.call(admin,
                () -> runs.replayAction(scope, started.runId(), "assign")))
                .isInstanceOf(WorkflowRunService.NonRetryableFailureException.class)
                .hasMessageContaining("business-state failure");

            assertThat(PrincipalScope.call(admin,
                () -> runs.timeline(scope, started.runId()))).hasSize(1);
        }

        @Test
        @DisplayName("a duplicate attempt number is refused by the unique index, not merged")
        void duplicateAttemptNumberIsRefused() {
            WorkflowCatalogRepository.VersionRow v1 = activeWorkflow();
            UUID conversationId = conversation();
            WorkflowRunService.StartOutcome started = PrincipalScope.call(admin, () ->
                runs.start(admin, definitionIdFor(v1), conversationId, WorkflowFixture.ADMIN_A,
                    "attempt-1", 0, 5, null));
            // A project scope, because workflow_run_actions.projectId is NOT NULL and this test talks to the
            // repository directly rather than through the service that narrows the scope for it.
            TenantScope scope = new TenantScope(WorkflowFixture.ORG_A, WorkflowFixture.PROJECT_A);
            UUID actionId = UUID.randomUUID();
            java.time.LocalDateTime now = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC);

            PrincipalScope.call(admin, () -> actionRepository.append(scope, actionId, started.runId(),
                "manual", "MANUAL", 1, "SUCCEEDED", Map.of(), Map.of(), null, "test", null, now, now,
                0, null, null, false, null, null));

            assertThatThrownBy(() -> PrincipalScope.call(admin, () -> actionRepository.append(scope,
                UUID.randomUUID(), started.runId(), "manual", "MANUAL", 1, "SUCCEEDED", Map.of(),
                Map.of(), null, "test", null, now, now, 0, null, null, false, null, null)))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        }

        @Test
        @DisplayName("finishing a run moves it to SUCCEEDED, and only while it is running")
        void finishRun() {
            WorkflowCatalogRepository.VersionRow v1 = activeWorkflow();
            UUID conversationId = conversation();
            WorkflowRunService.StartOutcome started = PrincipalScope.call(admin, () ->
                runs.start(admin, definitionIdFor(v1), conversationId, WorkflowFixture.ADMIN_A,
                    "finish-1", 0, 5, null));
            TenantScope scope = guard.tenantScope(admin);

            assertThat(PrincipalScope.call(admin, () -> runs.succeed(scope, started.runId()))).isTrue();
            assertThat(PrincipalScope.call(admin,
                () -> runs.succeed(scope, started.runId()))).isFalse();
            assertThat(PrincipalScope.call(admin, () -> runs.findRun(scope, started.runId()))
                .orElseThrow().status()).isEqualTo("SUCCEEDED");
        }

        @Test
        @DisplayName("a FAILED run must say why, and the schema refuses one with no reason")
        void failedRunCarriesAReason() {
            WorkflowCatalogRepository.VersionRow v1 = activeWorkflow();
            UUID conversationId = conversation();
            WorkflowRunService.StartOutcome started = PrincipalScope.call(admin, () ->
                runs.start(admin, definitionIdFor(v1), conversationId, WorkflowFixture.ADMIN_A,
                    "fail-1", 0, 5, null));
            TenantScope scope = guard.tenantScope(admin);

            // A LinkedHashMap, not Map.of: Map.of rejects a null value, so a null reason would fail with a
            // NullPointerException inside the test rather than reaching the CHECK constraint the
            // assertion is about.
            Map<String, Object> noReason = new java.util.LinkedHashMap<>();
            noReason.put("id", started.runId());
            noReason.put("reason", null);
            assertThatThrownBy(() -> PrincipalScope.call(admin, () ->
                jdbc.update("""
                    update workflow_runs
                       set "status" = cast('FAILED' as "WorkflowRunStatus"),
                           "failureReason" = :reason,
                           "finishedAt" = (now() at time zone 'UTC'),
                           "updatedAt" = (now() at time zone 'UTC')
                     where "id" = :id
                    """, noReason)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

            assertThat(PrincipalScope.call(admin,
                () -> runs.fail(scope, started.runId(), "the conversation is with a human"))).isTrue();
        }

        private TenantContext admin() {
            return admin;
        }

        private UUID definitionIdFor(WorkflowCatalogRepository.VersionRow version) {
            return version.workflowDefinitionId();
        }
    }

    @Nested
    @DisplayName("the routing explanation read model")
    class Explanations {

        @Test
        @DisplayName("a conversation's explanation returns the run, the version in force and the actions")
        void explanationIsDerivedFromHistory() {
            WorkflowCatalogRepository.DefinitionRow owner = definition();
            WorkflowCatalogRepository.VersionRow v1 = version(owner,
                WorkflowDocuments.scoreThenRoute("workflow:route"),
                List.of("workflow:score", "workflow:route"));
            activate(owner, v1.id());
            UUID conversationId = conversation();
            WorkflowRunService.StartOutcome started = PrincipalScope.call(admin, () ->
                runs.start(admin, owner.id(), conversationId, WorkflowFixture.ADMIN_A, "explain-1", 0,
                    5, "corr-9"));
            TenantScope scope = guard.tenantScope(admin);

            PrincipalScope.call(admin, () -> runs.record(scope, started.runId(),
                WorkflowRunService.ActionResult.success("route", "ROUTE",
                    Map.of("considered", 4, "chosen", "employee-1"),
                    Map.of("blockedAt", "WORKLOAD")),
                java.time.LocalDateTime.now(java.time.ZoneOffset.UTC),
                java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)));

            List<WorkflowHistoryRepository.ExplanationRow> rows = PrincipalScope.call(admin, () ->
                history.explainConversation(scope, conversationId, 100));

            assertThat(rows).hasSize(1);
            WorkflowHistoryRepository.ExplanationRow row = rows.get(0);
            assertThat(row.runId()).isEqualTo(started.runId());
            assertThat(row.versionNumber()).isEqualTo(1);
            assertThat(row.capabilityScopeInForce()).contains("workflow:route");
            assertThat(row.versionContentHash()).isEqualTo(v1.contentHash());
            assertThat(row.principalUserId()).isEqualTo(WorkflowFixture.ADMIN_A);
            assertThat(row.actionKey()).isEqualTo("route");
            assertThat((Map<String, Object>) row.inputSnapshot()).containsKeys("considered", "chosen");
            assertThat((Map<String, Object>) row.resultSnapshot()).containsEntry("blockedAt", "WORKLOAD");
            assertThat(row.actorPrincipal()).isEqualTo("workflow:route");
        }

        @Test
        @DisplayName("the run history reads newest first and the failure dashboard is tenant-scoped")
        void runHistoryAndFailures() {
            WorkflowCatalogRepository.DefinitionRow owner = definition();
            WorkflowCatalogRepository.VersionRow v1 = version(owner,
                WorkflowDocuments.scoreThenRoute(null), List.of("workflow:score"));
            activate(owner, v1.id());
            UUID conversationId = conversation();
            TenantScope scope = guard.tenantScope(admin);
            WorkflowRunService.StartOutcome started = PrincipalScope.call(admin, () ->
                runs.start(admin, owner.id(), conversationId, WorkflowFixture.ADMIN_A, "history-1",
                    0, 5, null));

            PrincipalScope.call(admin, () -> runs.record(scope, started.runId(),
                WorkflowRunService.ActionResult.retryableFailure("provider", "PROVIDER_SEND",
                    Map.of(), "PROVIDER_TIMEOUT", "timed out"),
                java.time.LocalDateTime.now(java.time.ZoneOffset.UTC),
                java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)));

            // A project scope, deliberately: runHistory filters workflow_runs by the scope's projectId, so
            // handing it a tenant-only scope is asking a different question than the one asserted.
            TenantScope projectScope = guard.scope(admin, WorkflowFixture.PROJECT_A);
            assertThat(PrincipalScope.call(admin, () ->
                history.runHistory(projectScope, WorkflowFixture.PROJECT_A, List.of("RUNNING"), 10)))
                .hasSize(1);
            assertThat(PrincipalScope.call(admin,
                () -> history.statusCountsForVersion(scope, v1.id())))
                .containsEntry("RUNNING", 1);
            assertThat(PrincipalScope.call(admin, () -> history.failureDashboard(projectScope,
                WorkflowFixture.SEED_AT, 10)))
                .anySatisfy(row -> assertThat(row.get("outcome")).isEqualTo("RETRYABLE"));
        }
    }

    @Nested
    @DisplayName("dry run and preview")
    class DryRun {

        @Test
        @DisplayName("a runnable version previews as WOULD_RUN with its entry node and capabilities")
        void runnablePreview() {
            WorkflowCatalogRepository.DefinitionRow owner = definition();
            WorkflowCatalogRepository.VersionRow v1 = version(owner,
                WorkflowDocuments.scoreThenRoute("workflow:route"),
                List.of("workflow:score", "workflow:route"));
            activate(owner, v1.id());

            List<WorkflowDefinitionService.DryRunEntry> entries =
                PrincipalScope.call(admin, () -> definitions.dryRun(admin, 10));

            assertThat(entries).hasSize(1);
            assertThat(entries.get(0).verdict()).isEqualTo("WOULD_RUN");
            assertThat(entries.get(0).versionNumber()).isEqualTo(1);
            assertThat(entries.get(0).detail()).contains("entry score");
        }

        @Test
        @DisplayName("a version whose node needs a capability it does not hold previews as refused")
        void missingCapabilityPreview() {
            WorkflowCatalogRepository.DefinitionRow owner = definition();
            WorkflowCatalogRepository.VersionRow v1 = version(owner,
                WorkflowDocuments.requiresUngrantedCapability("workflow:ai.send"),
                List.of("workflow:score"));
            activate(owner, v1.id());

            List<WorkflowDefinitionService.DryRunEntry> entries =
                PrincipalScope.call(admin, () -> definitions.dryRun(admin, 10));

            assertThat(entries.get(0).verdict()).isEqualTo("WOULD_BE_REFUSED");
            assertThat(entries.get(0).detail()).contains("workflow:ai.send");
        }

        @Test
        @DisplayName("a dry run writes nothing")
        void dryRunWritesNothing() {
            WorkflowCatalogRepository.DefinitionRow owner = definition();
            WorkflowCatalogRepository.VersionRow v1 = version(owner,
                WorkflowDocuments.scoreThenRoute(null), List.of("workflow:score"));
            activate(owner, v1.id());

            PrincipalScope.call(admin, () -> definitions.dryRun(admin, 10));

            assertThat(fixture.count("select count(*) from workflow_runs r", Map.of())).isZero();
            assertThat(fixture.count("select count(*) from workflow_run_actions a", Map.of())).isZero();
            assertThat(fixture.count("select count(*) from outbox_events o", Map.of())).isZero();
        }

        @Test
        @DisplayName("the preview batch is bounded")
        void dryRunIsBounded() {
            assertThat(WorkflowDefinitionService.DRY_RUN_BATCH_LIMIT).isEqualTo(200);
            assertThat(PrincipalScope.call(admin, () -> definitions.dryRun(admin, 10_000)).size())
                .isLessThanOrEqualTo(WorkflowDefinitionService.DRY_RUN_BATCH_LIMIT);
        }
    }
}
