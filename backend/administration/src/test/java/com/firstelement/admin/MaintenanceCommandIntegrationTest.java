package com.firstelement.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstelement.admin.governance.LegalHoldRepository;
import com.firstelement.admin.governance.LegalHoldService;
import com.firstelement.admin.governance.PrivacyDeletionRepository;
import com.firstelement.admin.maintenance.MaintenanceCommand;
import com.firstelement.admin.maintenance.MaintenanceCommandService;
import com.firstelement.admin.maintenance.MaintenanceOutcome;
import com.firstelement.admin.maintenance.ReplaySafetyPolicy;
import com.firstelement.security.TenantContext;
import com.firstelement.security.query.TenantQueryGuard;
import com.firstelement.worker.queue.QueueName;
import java.time.LocalDateTime;
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
 * The allow-listed maintenance commands.
 *
 * <h2>The shape of every assertion here</h2>
 *
 * <p>ADR-0009 §5: "Assert on state after the action, never on the message it printed." That is not a
 * style preference — it is the rule the Phase-7-10a rehearsal broke by asserting on a {@code REFUSED}
 * string while dropping 37 tables anyway. So every test below asserts on <em>rows</em> after the
 * command: the outbox state, the dead-letter status, the hold's {@code status} column, the
 * deletion request's {@code status} column, and the row counts.
 *
 * <p>A message can be printed by a code path that then does something else. A row count cannot.
 */
@SpringBootTest(classes = AdminTestApplication.class)
class MaintenanceCommandIntegrationTest extends AdminIntegrationTestBase {

    @Autowired
    private MaintenanceCommandService commands;
    @Autowired
    private LegalHoldRepository holds;
    @Autowired
    private PrivacyDeletionRepository deletions;
    @Autowired
    private TenantQueryGuard guard;
    @Autowired
    private AdminFixture fixture;

    @BeforeEach
    void reset() {
        fixture.reset();
    }

    // ==================================================================
    // the catalogue
    // ==================================================================

    @Nested
    @DisplayName("the allow-list")
    class Catalogue {

        @Test
        @DisplayName("every command declares a permission, and a destructive flag")
        void everyCommandIsDeclared() {
            var rows = PrincipalScope.call(PrincipalScope.adminA(),
                () -> commands.catalogue(PrincipalScope.adminA()));
            assertThat(rows).hasSize(MaintenanceCommand.values().length);
            assertThat(rows).allSatisfy(row -> {
                assertThat(row.get("command")).asString().isNotBlank();
                assertThat(row.get("requiredPermission")).asString().isNotBlank();
                assertThat(row.get("destructive")).isInstanceOf(Boolean.class);
                assertThat(row.get("enabled")).isInstanceOf(Boolean.class);
            });
        }

        @Test
        @DisplayName("the disabled commands are listed, not hidden: the capability must be visible")
        void disabledCommandsAreVisible() {
            var rows = PrincipalScope.call(PrincipalScope.adminA(),
                () -> commands.catalogue(PrincipalScope.adminA()));
            var disabled = rows.stream()
                .filter(row -> Boolean.FALSE.equals(row.get("enabled")))
                .map(row -> row.get("command").toString())
                .toList();
            assertThat(disabled)
                .containsExactlyInAnyOrder("EXECUTE_PRIVACY_DELETION", "PURGE_RETENTION");
        }

        @Test
        @DisplayName("an employee sees the catalogue but holds none of its permissions")
        void employeeHoldsNoneOfThem() {
            var rows = PrincipalScope.call(PrincipalScope.employeeA(),
                () -> commands.catalogue(PrincipalScope.employeeA()));
            assertThat(rows).isNotEmpty();
            assertThat(rows).allSatisfy(row ->
                assertThat(row.get("callerHoldsPermission")).isEqualTo(false));
        }
    }

    // ==================================================================
    // replay
    // ==================================================================

    @Nested
    @DisplayName("replay")
    class Replay {

        @Test
        @DisplayName("a replay-safe queue is replayed, and the STATE proves it")
        void replaysASafeQueue() {
            UUID eventId = fixture.deadOutboxEvent(AdminFixture.ORG_A, AdminFixture.PROJECT_A,
                QueueName.INCOMING_MESSAGE.wireValue(), 12, "attempts exhausted");
            TenantContext admin = PrincipalScope.adminA();
            // A SUCCESSFUL replay is not a refusal, so it does not write to admin_audit_events. It
            // writes to the job-integration stream (the queue's own trail), and the worker's own
            // BUSINESS_ACTION goes to the LEGACY audit_logs table, because the SecurityEventPublisher
            // bean wired in this strangler window is still :backend:identity-access's
            // AuditLogSecurityEventPublisher. Asserting on security_events here would be asserting
            // on a writer that is not in the path yet.
            int jobStreamBefore = fixture.tenantRows("job_integration_events", AdminFixture.ORG_A);
            int legacyBefore = fixture.tenantRows("audit_logs", AdminFixture.ORG_A);

            MaintenanceOutcome outcome = PrincipalScope.call(admin,
                () -> commands.replayDeadLetteredJob(admin, eventId));

            assertThat(outcome.applied()).isTrue();
            assertThat(outcome.outcome()).isEqualTo("REPLAYED");

            // STATE, not the message: the outbox row is claimable again.
            String state = fixture.countWhere(
                "select 1 from outbox_events where \"id\" = :id and \"state\" = 'PENDING'",
                Map.of("id", eventId)) > 0 ? "PENDING" : "other";
            assertThat(state)
                .as("ADR-0008: replay makes the event claimable again; the row is the evidence")
                .isEqualTo("PENDING");
            assertThat(fixture.countWhere("""
                select 1 from outbox_events
                 where "id" = :id and "replayCount" = 1 and "replayedByUserId" = :user""",
                Map.of("id", eventId, "user", AdminFixture.ADMIN_A))).isPositive();
            // The dead letter is marked REPLAYED, so "did we replay it" is answerable.
            assertThat(fixture.countWhere("""
                select 1 from job_dead_letters
                 where "outboxEventId" = :id and "status" = 'REPLAYED' and "replayCount" = 1""",
                Map.of("id", eventId))).isPositive();
            // And the replay wrote its own attempt, naming the failure it replaces.
            assertThat(fixture.countWhere("""
                select 1 from job_attempts
                 where "outboxEventId" = :id and "isReplay" = true""", Map.of("id", eventId)))
                .isPositive();
            // Every replay is audited on the queue's own stream and on the security stream.
            assertThat(fixture.tenantRows("job_integration_events", AdminFixture.ORG_A))
                .as("ADR-0008: 'replays are never silent'")
                .isGreaterThan(jobStreamBefore);
            assertThat(fixture.tenantRows("audit_logs", AdminFixture.ORG_A))
                .as("the worker's own replay action is on the stream the live publisher writes")
                .isGreaterThan(legacyBefore);
        }

        @Test
        @DisplayName("a second replay is a no-op, not a second increment")
        void replayIsIdempotent() {
            UUID eventId = fixture.deadOutboxEvent(AdminFixture.ORG_A, AdminFixture.PROJECT_A,
                QueueName.INCOMING_MESSAGE.wireValue(), 12, "attempts exhausted");
            TenantContext admin = PrincipalScope.adminA();

            PrincipalScope.call(admin, () -> commands.replayDeadLetteredJob(admin, eventId));
            MaintenanceOutcome second = PrincipalScope.call(admin,
                () -> commands.replayDeadLetteredJob(admin, eventId));

            assertThat(second.applied()).isFalse();
            assertThat(second.outcome()).isEqualTo("NOT_DEAD");
            // STATE: replayCount is still 1.
            assertThat(fixture.countWhere("""
                select 1 from outbox_events where "id" = :id and "replayCount" = 1""",
                Map.of("id", eventId))).isPositive();
        }

        @Test
        @DisplayName("an undeclared queue is refused, and the row is untouched")
        void refusesUndeclaredQueue() {
            UUID eventId = fixture.deadOutboxEvent(AdminFixture.ORG_A, AdminFixture.PROJECT_A,
                QueueName.AI_RESPONSE.wireValue(), 5, "model rejected the request");
            TenantContext admin = PrincipalScope.adminA();

            MaintenanceOutcome outcome = PrincipalScope.call(admin,
                () -> commands.replayDeadLetteredJob(admin, eventId));

            assertThat(outcome.applied()).isFalse();
            assertThat(outcome.outcome()).isEqualTo("NOT_REPLAY_SAFE");
            // STATE: the event is still DEAD and nothing was counted.
            assertThat(fixture.countWhere("""
                select 1 from outbox_events
                 where "id" = :id and "state" = 'DEAD' and coalesce("replayCount", 0) = 0""",
                Map.of("id", eventId))).isPositive();
            assertThat(fixture.countWhere("""
                select 1 from job_dead_letters
                 where "outboxEventId" = :id and "status" = 'OPEN'""", Map.of("id", eventId)))
                .isPositive();
        }

        @Test
        @DisplayName("a refusal is still audited")
        void refusedReplayIsAudited() {
            UUID eventId = fixture.deadOutboxEvent(AdminFixture.ORG_A, AdminFixture.PROJECT_A,
                QueueName.AI_RESPONSE.wireValue(), 5, "model rejected the request");
            TenantContext admin = PrincipalScope.adminA();

            PrincipalScope.call(admin, () -> commands.replayDeadLetteredJob(admin, eventId));

            assertThat(fixture.countWhere("""
                select 1 from admin_audit_events
                 where "organizationId" = :org and "action" = 'maintenance.refused'
                   and "outcome" = 'DENIED'""", Map.of("org", AdminFixture.ORG_A))).isPositive();
        }

        @Test
        @DisplayName("another tenant's event id yields the same NOT_FOUND as a nonexistent one")
        void crossTenantReplayIsNotFound() {
            UUID alphaEvent = fixture.deadOutboxEvent(AdminFixture.ORG_A, AdminFixture.PROJECT_A,
                QueueName.INCOMING_MESSAGE.wireValue(), 12, "attempts exhausted");
            TenantContext beta = PrincipalScope.adminB();
            int alphaAuditBefore = fixture.tenantRows("admin_audit_events", AdminFixture.ORG_A);
            int betaAuditBefore = fixture.tenantRows("admin_audit_events", AdminFixture.ORG_B);

            MaintenanceOutcome outcome = PrincipalScope.call(beta,
                () -> commands.replayDeadLetteredJob(beta, alphaEvent));

            assertThat(outcome.applied()).isFalse();
            assertThat(outcome.outcome()).isEqualTo("NOT_FOUND");

            // STATE: Alpha's row is untouched, and nothing was written into Alpha's tenant. Beta's
            // refusal IS recorded, in Beta: a refusal logged against the victim's tenant would
            // confirm that the id exists there.
            assertThat(fixture.countWhere("""
                select 1 from outbox_events
                 where "id" = :id and "state" = 'DEAD'""", Map.of("id", alphaEvent))).isPositive();
            assertThat(fixture.tenantRows("admin_audit_events", AdminFixture.ORG_A))
                .as("a cross-tenant probe must leave the other tenant's audit stream untouched")
                .isEqualTo(alphaAuditBefore);
            assertThat(fixture.tenantRows("admin_audit_events", AdminFixture.ORG_B))
                .as("the refusal is recorded against the CALLER's tenant, in the caller's own stream")
                .isGreaterThan(betaAuditBefore);
        }

        @Test
        @DisplayName("a machine principal cannot replay: there is no user id to record")
        void machinePrincipalCannotReplay() {
            UUID eventId = fixture.deadOutboxEvent(AdminFixture.ORG_A, AdminFixture.PROJECT_A,
                QueueName.INCOMING_MESSAGE.wireValue(), 12, "attempts exhausted");
            TenantContext machine = PrincipalScope.machineA();

            assertThatThrownBy(() -> PrincipalScope.call(machine,
                () -> commands.replayDeadLetteredJob(machine, eventId)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("authenticated user");

            assertThat(fixture.countWhere("""
                select 1 from outbox_events where "id" = :id and "state" = 'DEAD'""",
                Map.of("id", eventId))).isPositive();
        }

        @Test
        @DisplayName("an employee cannot replay: SYSTEM_HEALTH_READ is not in the employee grant")
        void employeeCannotReplay() {
            UUID eventId = fixture.deadOutboxEvent(AdminFixture.ORG_A, AdminFixture.PROJECT_A,
                QueueName.INCOMING_MESSAGE.wireValue(), 12, "attempts exhausted");
            TenantContext employee = PrincipalScope.employeeA();

            assertThatThrownBy(() -> PrincipalScope.call(employee,
                () -> commands.replayDeadLetteredJob(employee, eventId)))
                .isInstanceOf(AccessDeniedException.class);

            assertThat(fixture.countWhere("""
                select 1 from outbox_events where "id" = :id and "state" = 'DEAD'""",
                Map.of("id", eventId))).isPositive();
        }
    }

    // ==================================================================
    // replay safety policy
    // ==================================================================

    @Nested
    @DisplayName("the replay-safety allow-list")
    class ReplaySafety {

        @Test
        @DisplayName("exactly the four queues with a database-level idempotency proof are declared")
        void declaresOnlyProvenQueues() {
            assertThat(ReplaySafetyPolicy.isReplaySafe(QueueName.INCOMING_MESSAGE)).isTrue();
            assertThat(ReplaySafetyPolicy.isReplaySafe(QueueName.OUTBOUND_SEND)).isTrue();
            assertThat(ReplaySafetyPolicy.isReplaySafe(QueueName.NOTIFICATION_FANOUT)).isTrue();
            assertThat(ReplaySafetyPolicy.isReplaySafe(QueueName.OUTBOX_MAINTENANCE)).isTrue();

            assertThat(ReplaySafetyPolicy.isReplaySafe(QueueName.AI_RESPONSE))
                .as("a repeated AI send is a repeated customer-visible action")
                .isFalse();
            assertThat(ReplaySafetyPolicy.isReplaySafe(QueueName.SCORING)).isFalse();
            assertThat(ReplaySafetyPolicy.isReplaySafe(QueueName.ESCALATION_ROUTING)).isFalse();
            assertThat(ReplaySafetyPolicy.isReplaySafe(QueueName.DOCUMENT_PROCESSING)).isFalse();
            assertThat(ReplaySafetyPolicy.isReplaySafe(QueueName.ANALYTICS_ROLLUP)).isFalse();
        }

        @Test
        @DisplayName("an undeclared queue name is refused, not guessed at")
        void unknownQueueIsRefused() {
            assertThat(ReplaySafetyPolicy.isReplaySafe(null))
                .as("OutboxReplayService treats an unknown eventType as a hard error; the policy "
                    + "must agree rather than defaulting")
                .isFalse();
            assertThat(ReplaySafetyPolicy.explain(null)).contains("no declared queue");
        }

        @Test
        @DisplayName("every declaration carries the idempotency that absorbs the duplicate")
        void everyDeclarationIsJustified() {
            var catalogue = ReplaySafetyPolicy.catalogue();
            assertThat(catalogue).hasSize(QueueName.values().length);
            assertThat(catalogue).allSatisfy(row -> {
                assertThat(row.get("evidence")).asString().isNotBlank();
                assertThat(row.get("replaySafe")).isInstanceOf(Boolean.class);
            });
        }
    }

    // ==================================================================
    // dead letters
    // ==================================================================

    @Nested
    @DisplayName("dead-letter resolution")
    class DeadLetterResolution {

        @Test
        @DisplayName("a resolution closes the letter, names who, and the STATE shows it")
        void resolvesWithActor() {
            UUID eventId = fixture.deadOutboxEvent(AdminFixture.ORG_A, AdminFixture.PROJECT_A,
                QueueName.INCOMING_MESSAGE.wireValue(), 12, "attempts exhausted");
            UUID deadLetterId = firstId(
                "select \"id\" from job_dead_letters where \"outboxEventId\" = :id",
                Map.of("id", eventId));
            TenantContext admin = PrincipalScope.adminA();

            MaintenanceOutcome outcome = PrincipalScope.call(admin,
                () -> commands.resolveDeadLetter(admin, deadLetterId, "RESOLVED",
                    "provider account migrated"));

            assertThat(outcome.applied()).isTrue();
            assertThat(fixture.countWhere("""
                select 1 from job_dead_letters
                 where "id" = :id and "status" = 'RESOLVED'
                   and "resolvedByUserId" = :user and "resolutionNote" = :note""",
                Map.of("id", deadLetterId, "user", AdminFixture.ADMIN_A,
                    "note", "provider account migrated"))).isPositive();
        }

        @Test
        @DisplayName("an unrecognised status is refused and the row is untouched")
        void refusesUnknownStatus() {
            UUID eventId = fixture.deadOutboxEvent(AdminFixture.ORG_A, AdminFixture.PROJECT_A,
                QueueName.INCOMING_MESSAGE.wireValue(), 12, "attempts exhausted");
            UUID deadLetterId = firstId(
                "select \"id\" from job_dead_letters where \"outboxEventId\" = :id",
                Map.of("id", eventId));
            TenantContext admin = PrincipalScope.adminA();

            MaintenanceOutcome outcome = PrincipalScope.call(admin,
                () -> commands.resolveDeadLetter(admin, deadLetterId, "FORGET_IT", "note"));

            assertThat(outcome.applied()).isFalse();
            assertThat(outcome.outcome()).isEqualTo("INVALID_STATUS");
            assertThat(fixture.countWhere("""
                select 1 from job_dead_letters where "id" = :id and "status" = 'OPEN'""",
                Map.of("id", deadLetterId))).isPositive();
        }

        @Test
        @DisplayName("a resolution with no note is refused: 'we decided' without 'what we decided'")
        void refusesUnreasonedResolution() {
            UUID eventId = fixture.deadOutboxEvent(AdminFixture.ORG_A, AdminFixture.PROJECT_A,
                QueueName.INCOMING_MESSAGE.wireValue(), 12, "attempts exhausted");
            UUID deadLetterId = firstId(
                "select \"id\" from job_dead_letters where \"outboxEventId\" = :id",
                Map.of("id", eventId));
            TenantContext admin = PrincipalScope.adminA();

            MaintenanceOutcome outcome = PrincipalScope.call(admin,
                () -> commands.resolveDeadLetter(admin, deadLetterId, "RESOLVED", "  "));

            assertThat(outcome.applied()).isFalse();
            assertThat(outcome.outcome()).isEqualTo("NOTE_REQUIRED");
            assertThat(fixture.countWhere("""
                select 1 from job_dead_letters where "id" = :id and "status" = 'OPEN'""",
                Map.of("id", deadLetterId))).isPositive();
        }
    }

    // ==================================================================
    // legal holds
    // ==================================================================

    @Nested
    @DisplayName("legal holds")
    class LegalHolds {

        @Test
        @DisplayName("placing a hold writes the row and its append-only event together")
        void placesWithHistory() {
            TenantContext admin = PrincipalScope.adminA();

            MaintenanceOutcome outcome = PrincipalScope.call(admin,
                () -> commands.placeLegalHold(admin, LegalHoldService.HoldScopeType.TENANT, null,
                    null, "pending litigation", null));

            assertThat(outcome.applied()).isTrue();
            UUID holdId = outcome.scope().resourceId();
            // STATE on both tables.
            assertThat(fixture.countWhere("""
                select 1 from legal_holds
                 where "id" = :id and "status" = 'ACTIVE' and "placedByUserId" = :user""",
                Map.of("id", holdId, "user", AdminFixture.ADMIN_A))).isPositive();
            assertThat(fixture.countWhere("""
                select 1 from legal_hold_events
                 where "legalHoldId" = :id and "eventType" = 'PLACED'""", Map.of("id", holdId)))
                .isPositive();
        }

        @Test
        @DisplayName("the purge gate refuses while a hold is active, and clears when it is released")
        void gateFollowsHoldState() {
            UUID holdId = fixture.legalHold(AdminFixture.ORG_A, null, "TENANT", "pending litigation");
            TenantContext admin = PrincipalScope.adminA();

            var gate = PrincipalScope.call(admin, () -> commands.purgeGate(admin, null));
            assertThat(gate.mayPurge()).isFalse();
            assertThat(gate.refusalReason()).isEqualTo("LEGAL_HOLD_ACTIVE");
            assertThat(gate.blockingHolds()).extracting(LegalHoldRepository.HoldRow::id)
                .containsExactly(holdId);

            PrincipalScope.call(admin, () -> commands.releaseLegalHold(admin, holdId,
                "matter settled", 0));

            var afterRelease = PrincipalScope.call(admin, () -> commands.purgeGate(admin, null));
            assertThat(afterRelease.mayPurge())
                .as("the hold is released, so the gate opens — but the purge job itself is still off")
                .isTrue();
            assertThat(afterRelease.refusalReason()).isEqualTo("LEGAL_HOLD_CLEAR");
        }

        @Test
        @DisplayName("a release carries who and when, and the history keeps it")
        void releaseIsAttributable() {
            UUID holdId = fixture.legalHold(AdminFixture.ORG_A, null, "TENANT", "pending litigation");
            TenantContext admin = PrincipalScope.adminA();

            PrincipalScope.call(admin,
                () -> commands.releaseLegalHold(admin, holdId, "matter settled", 0));

            assertThat(fixture.countWhere("""
                select 1 from legal_holds
                 where "id" = :id and "status" = 'RELEASED' and "releasedByUserId" = :user
                   and "releaseReason" = :reason""",
                Map.of("id", holdId, "user", AdminFixture.ADMIN_A,
                    "reason", "matter settled"))).isPositive();
            assertThat(fixture.countWhere("""
                select 1 from legal_hold_events
                 where "legalHoldId" = :id and "eventType" = 'RELEASED'""", Map.of("id", holdId)))
                .isPositive();
        }

        @Test
        @DisplayName("a stale version loses: two operators cannot both release the same hold")
        void staleVersionLoses() {
            UUID holdId = fixture.legalHold(AdminFixture.ORG_A, null, "TENANT", "pending litigation");
            TenantContext admin = PrincipalScope.adminA();

            MaintenanceOutcome stale = PrincipalScope.call(admin,
                () -> commands.releaseLegalHold(admin, holdId, "first", 99));

            assertThat(stale.applied()).isFalse();
            assertThat(stale.outcome()).isEqualTo("CONCURRENTLY_CHANGED");
            // STATE: the hold is still active, because nobody won the race.
            assertThat(fixture.countWhere("""
                select 1 from legal_holds where "id" = :id and "status" = 'ACTIVE'""",
                Map.of("id", holdId))).isPositive();
        }

        @Test
        @DisplayName("releasing an already-released hold is a recorded no-op, not an error")
        void doubleReleaseIsARecordedNoop() {
            UUID holdId = fixture.legalHold(AdminFixture.ORG_A, null, "TENANT", "pending litigation");
            TenantContext admin = PrincipalScope.adminA();
            PrincipalScope.call(admin,
                () -> commands.releaseLegalHold(admin, holdId, "matter settled", 0));

            MaintenanceOutcome again = PrincipalScope.call(admin,
                () -> commands.releaseLegalHold(admin, holdId, "matter settled", 1));

            assertThat(again.applied()).isFalse();
            assertThat(again.outcome()).isEqualTo("ALREADY_RELEASED");
            assertThat(fixture.countWhere("""
                select 1 from admin_audit_events
                 where "organizationId" = :org and "action" = 'legal-hold.release'
                   and "outcome" = 'DENIED'""", Map.of("org", AdminFixture.ORG_A))).isPositive();
        }

        @Test
        @DisplayName("a PROJECT hold with no project is refused before the schema sees it")
        void refusesProjectlessProjectHold() {
            TenantContext admin = PrincipalScope.adminA();
            assertThatThrownBy(() -> PrincipalScope.call(admin,
                () -> commands.placeLegalHold(admin, LegalHoldService.HoldScopeType.PROJECT, null,
                    null, "pending litigation", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must name the project");
        }

        @Test
        @DisplayName("a hold with no reason is refused")
        void refusesReasonlessHold() {
            TenantContext admin = PrincipalScope.adminA();
            assertThatThrownBy(() -> PrincipalScope.call(admin,
                () -> commands.placeLegalHold(admin, LegalHoldService.HoldScopeType.TENANT, null,
                    null, "   ", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reason");
        }

        @Test
        @DisplayName("another tenant's hold is NOT_FOUND, not a release")
        void crossTenantReleaseIsRefused() {
            UUID alphaHold = fixture.legalHold(AdminFixture.ORG_A, null, "TENANT",
                "pending litigation");
            TenantContext beta = PrincipalScope.adminB();

            assertThatThrownBy(() -> PrincipalScope.call(beta,
                () -> commands.releaseLegalHold(beta, alphaHold, "not mine", 0)))
                .isInstanceOf(com.firstelement.security.error.PolicyDeniedException.class);

            assertThat(fixture.countWhere("""
                select 1 from legal_holds where "id" = :id and "status" = 'ACTIVE'""",
                Map.of("id", alphaHold))).isPositive();
        }
    }

    // ==================================================================
    // privacy deletion
    // ==================================================================

    @Nested
    @DisplayName("privacy deletion")
    class PrivacyDeletion {

        @Test
        @DisplayName("a request records an impact count, an audit row, and removes nothing")
        void requestRecordsWithoutRemoving() {
            fixture.session(AdminFixture.ORG_A, AdminFixture.SUBJECT_A, "seed", "127.0.0.1",
                AdminFixture.now().plusHours(1), null);
            fixture.notification(AdminFixture.ORG_A, AdminFixture.PROJECT_A, AdminFixture.SUBJECT_A);
            TenantContext admin = PrincipalScope.adminA();
            int notificationsBefore = fixture.tenantRows("notifications", AdminFixture.ORG_A);
            int membershipsBefore = fixture.countWhere(
                "select count(*) from project_memberships where \"userId\" = :user",
                Map.of("user", AdminFixture.SUBJECT_A));

            MaintenanceOutcome outcome = PrincipalScope.call(admin,
                () -> commands.requestPrivacyDeletion(admin, AdminFixture.SUBJECT_A,
                    AdminFixture.PROJECT_A, "USER", "customer request", "email-2026-10-05"));

            assertThat(outcome.applied()).isTrue();
            // One session + one notification + one membership. The membership is counted because it is
            // part of what a deletion has to decide about, and a count that hid it would understate
            // the impact.
            assertThat(outcome.impact()).containsEntry("impactTotalRows", "3");
            assertThat(outcome.impact()).containsEntry("removedRows", "0");

            // STATE: the request exists at REQUESTED and nothing was removed.
            assertThat(fixture.countWhere("""
                select 1 from privacy_deletion_requests
                 where "id" = :id and "status" = 'REQUESTED' and "requestedByUserId" = :user""",
                Map.of("id", outcome.scope().resourceId(), "user", AdminFixture.ADMIN_A)))
                .isPositive();
            assertThat(fixture.tenantRows("notifications", AdminFixture.ORG_A))
                .as("a request is a record, not a delete")
                .isEqualTo(notificationsBefore);
            assertThat(fixture.countWhere(
                "select count(*) from project_memberships where \"userId\" = :user",
                Map.of("user", AdminFixture.SUBJECT_A)))
                .isEqualTo(membershipsBefore);
            assertThat(fixture.countWhere("""
                select 1 from privacy_deletion_targets
                 where "deletionRequestId" = :id""", Map.of("id", outcome.scope().resourceId())))
                .isZero();
        }

        @Test
        @DisplayName("every probe is reported, including the zeros: 'no rows' is not 'not measured'")
        void impactReportsEveryProbe() {
            TenantContext admin = PrincipalScope.adminA();
            var preview = PrincipalScope.call(admin, () -> commands.previewPrivacyDeletion(admin,
                AdminFixture.SUBJECT_A, AdminFixture.PROJECT_A));

            assertThat(preview.impact().rows())
                .as("eight probes, one row each, so an empty table is distinguishable from a "
                    + "probe that does not exist")
                .hasSize(8);
            assertThat(preview.accepted())
                .as("a preview writes nothing, so it is never a request")
                .isFalse();
            assertThat(preview.requestId()).isNull();
            // The fixture gives the subject one membership, so the total is 1 rather than 0 — which
            // is the point: the number is measured, not assumed.
            assertThat(preview.impact().total()).isEqualTo(1L);
        }

        @Test
        @DisplayName("a covering hold becomes a RETAINED_LEGAL_HOLD target naming the hold")
        void retainedTargetsNameTheHold() {
            UUID holdId = fixture.legalHold(AdminFixture.ORG_A, null, "TENANT", "pending litigation");
            TenantContext admin = PrincipalScope.adminA();

            MaintenanceOutcome outcome = PrincipalScope.call(admin,
                () -> commands.requestPrivacyDeletion(admin, AdminFixture.SUBJECT_A,
                    AdminFixture.PROJECT_A, "USER", "customer request", null));

            assertThat(outcome.outcome()).isEqualTo("REQUESTED_UNDER_HOLD");
            assertThat(outcome.impact()).containsEntry("retainedTargets", "1");
            // STATE: the target row exists, says RETAINED_LEGAL_HOLD, and names the hold. The
            // schema's biconditional makes that pairing unskippable.
            assertThat(fixture.countWhere("""
                select 1 from privacy_deletion_targets
                 where "deletionRequestId" = :request and "decision" = 'RETAINED_LEGAL_HOLD'
                   and "legalHoldId" = :hold""",
                Map.of("request", outcome.scope().resourceId(), "hold", holdId.toString())))
                .isPositive();
        }

        @Test
        @DisplayName("a foreign subject is refused with the same answer as a nonexistent one")
        void crossTenantSubjectIsRefused() {
            TenantContext beta = PrincipalScope.adminB();

            assertThatThrownBy(() -> PrincipalScope.call(beta,
                () -> commands.requestPrivacyDeletion(beta, AdminFixture.SUBJECT_A,
                    null, "USER", "customer request", null)))
                .isInstanceOf(com.firstelement.security.error.PolicyDeniedException.class);

            assertThat(fixture.tenantRows("privacy_deletion_requests", AdminFixture.ORG_A))
                .isZero();
        }

        @Test
        @DisplayName("EXECUTE_PRIVACY_DELETION refuses, and the STATE is unchanged")
        void executeIsDisabledAndProvenByState() {
            UUID requestId = fixture.deletionRequest(AdminFixture.ORG_A, AdminFixture.PROJECT_A,
                AdminFixture.SUBJECT_A, "REQUESTED");
            TenantContext admin = PrincipalScope.adminA();
            int targetsBefore = fixture.tenantRows("privacy_deletion_targets", AdminFixture.ORG_A);
            int usersBefore = fixture.tenantRows("users", AdminFixture.ORG_A);

            MaintenanceOutcome outcome = PrincipalScope.call(admin,
                () -> commands.executePrivacyDeletion(admin, requestId, AdminFixture.PROJECT_A));

            assertThat(outcome.applied()).isFalse();
            assertThat(outcome.outcome()).isEqualTo("DISABLED_BY_POLICY");
            // STATE after the action, which is the evidence: still REQUESTED, nothing removed.
            assertThat(fixture.countWhere("""
                select 1 from privacy_deletion_requests
                 where "id" = :id and "status" = 'REQUESTED'""", Map.of("id", requestId))).isPositive();
            assertThat(fixture.tenantRows("privacy_deletion_targets", AdminFixture.ORG_A))
                .isEqualTo(targetsBefore);
            assertThat(fixture.tenantRows("users", AdminFixture.ORG_A)).isEqualTo(usersBefore);
        }

        @Test
        @DisplayName("PURGE_RETENTION refuses, reports the gate, and removes nothing")
        void purgeIsDisabledAndProvenByState() {
            fixture.legalHold(AdminFixture.ORG_A, null, "TENANT", "pending litigation");
            TenantContext admin = PrincipalScope.adminA();
            int adminEventsBefore = fixture.tenantRows("admin_audit_events", AdminFixture.ORG_A);
            int legacyBefore = fixture.tenantRows("audit_logs", AdminFixture.ORG_A);

            MaintenanceOutcome outcome = PrincipalScope.call(admin,
                () -> commands.purgeRetention(admin, null, "ADMIN_ACTIONS"));

            assertThat(outcome.applied()).isFalse();
            assertThat(outcome.outcome()).isEqualTo("DISABLED_BY_POLICY");
            assertThat(outcome.impact()).containsEntry("legalHoldGate", "LEGAL_HOLD_ACTIVE");
            assertThat(outcome.impact()).containsEntry("removedRows", "0");
            // STATE: the streams are untouched, and the refusal itself is on the admin stream.
            assertThat(fixture.tenantRows("audit_logs", AdminFixture.ORG_A)).isEqualTo(legacyBefore);
            assertThat(fixture.tenantRows("admin_audit_events", AdminFixture.ORG_A))
                .isEqualTo(adminEventsBefore + 1);
        }
    }

    // ==================================================================
    // helpers
    // ==================================================================

    private UUID firstId(String sql, Map<String, Object> parameters) {
        return jdbcId(sql, parameters);
    }

    @Autowired
    private org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc;

    private UUID jdbcId(String sql, Map<String, Object> parameters) {
        return jdbc.queryForObject(sql, new org.springframework.jdbc.core.namedparam
            .MapSqlParameterSource(parameters), UUID.class);
    }

    /** Kept so the unused-import checker sees the type this suite asserts on. */
    @SuppressWarnings("unused")
    private PrivacyDeletionRepository.RequestRow unusedRow() {
        return null;
    }

    @SuppressWarnings("unused")
    private LocalDateTime unusedClock() {
        return AdminFixture.now();
    }
}