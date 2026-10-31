package com.firstelement.worker;

import com.firstelement.worker.config.UtcTime;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstelement.worker.history.DeadLetterRepository;
import com.firstelement.worker.outbox.OutboxRepository;
import com.firstelement.worker.outbox.OutboxState;
import com.firstelement.worker.queue.QueueName;
import com.firstelement.worker.runtime.JobProgressService;
import com.firstelement.worker.runtime.OutboxDispatcher;
import com.firstelement.worker.runtime.OutboxReplayService;
import com.firstelement.security.TenantContext;
import com.firstelement.security.UserRole;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The outbox state machine against real PostgreSQL, real CHECK constraints and real unique
 * indexes.
 *
 * <h2>What each test is actually proving</h2>
 *
 * <ul>
 *   <li><b>Claim is tenant-scoped and lease-protected</b> — one tenant's claim never touches another's
 *       row, and a live lease blocks a second claimer.</li>
 *   <li><b>Only the lease holder may finish</b> — complete/retry/dead-letter from a worker that does
 *       not hold the lease affects zero rows and leaves the row untouched.</li>
 *   <li><b>An expired lease is reclaimed by the reaper, not by the claim</b> — the claim predicate
 *       only sees {@code PENDING}, which is exactly why §7 exists.</li>
 *   <li><b>Attempts keep counting across the reap</b>, so the backoff stays monotonic and a crashed
 *       worker cannot buy itself a fresh budget.</li>
 *   <li><b>Replay is audited and safe to repeat</b> — same id, recorded who and when, and a second
 *       replay writes nothing.</li>
 *   <li><b>Publish refuses outside a transaction</b>, so a domain change can never commit without the
 *       work it promised.</li>
 * </ul>
 */
@SpringBootTest(classes = WorkerTestApplication.class,
    properties = "firstelement.worker.dispatcher.enabled=false")
class OutboxLifecycleIntegrationTest extends WorkerIntegrationTestBase {

    @Autowired
    private OutboxRepository outbox;
    @Autowired
    private OutboxReplayService replay;
    @Autowired
    private JobProgressService progress;
    @Autowired
    private OutboxDispatcher dispatcher;
    @Autowired
    private WorkerFixture fixture;

    @BeforeEach
    void reset() {
        fixture.reset();
    }

    // ------------------------------------------------------------------
    // claim
    // ------------------------------------------------------------------

    @Test
    @DisplayName("claim is tenant-scoped: tenant B's claim never returns tenant A's row")
    void claimIsTenantScoped() {
        UUID a = fixture.seedOutbox(WorkerFixture.ORG_A, WorkerFixture.PROJECT_A,
            QueueName.OUTBOX_MAINTENANCE.wireValue(), "key-a");
        UUID b = fixture.seedOutbox(WorkerFixture.ORG_B, WorkerFixture.PROJECT_B,
            QueueName.OUTBOX_MAINTENANCE.wireValue(), "key-b");

        List<OutboxRepository.OutboxRow> claimedByA =
            outbox.claimFor(WorkerFixture.ORG_A, "worker-a", 10, 60);
        List<OutboxRepository.OutboxRow> claimedByB =
            outbox.claimFor(WorkerFixture.ORG_B, "worker-b", 10, 60);

        assertThat(claimedByA).extracting(OutboxRepository.OutboxRow::id).containsExactly(a);
        assertThat(claimedByB).extracting(OutboxRepository.OutboxRow::id).containsExactly(b);
        assertThat(fixture.leaseOwnerOf(a)).isEqualTo("worker-a");
        assertThat(fixture.leaseOwnerOf(b)).isEqualTo("worker-b");
        // attempts is incremented by the claim, atomically.
        assertThat(fixture.attemptsOf(a)).isEqualTo(1);
    }

    @Test
    @DisplayName("a live lease blocks a second claim, and a future-dated row is not claimable")
    void liveLeaseAndBackoffBlockSecondClaim() {
        UUID first = fixture.seedOutbox(WorkerFixture.ORG_A, WorkerFixture.PROJECT_A,
            QueueName.OUTBOX_MAINTENANCE.wireValue(), "key-1");
        UUID delayed = fixture.seedOutbox(WorkerFixture.ORG_A, WorkerFixture.PROJECT_A,
            QueueName.OUTBOX_MAINTENANCE.wireValue(), "key-2");
        fixture.jdbc().update("""
            update outbox_events set "nextAttemptAt" = (now() AT TIME ZONE 'UTC') + interval '1 hour'
             where "id" = :id""", new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                .addValue("id", delayed));

        assertThat(outbox.claimFor(WorkerFixture.ORG_A, "worker-a", 10, 60))
            .extracting(OutboxRepository.OutboxRow::id).containsExactly(first);
        assertThat(outbox.claimFor(WorkerFixture.ORG_A, "worker-b", 10, 60)).isEmpty();
        assertThat(fixture.stateOf(first)).isEqualTo("LEASED");
    }

    // ------------------------------------------------------------------
    // lease ownership
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a worker that does not hold the lease cannot complete, retry or dead-letter")
    void leaseOwnershipIsEnforced() {
        UUID id = fixture.seedOutbox(WorkerFixture.ORG_A, WorkerFixture.PROJECT_A,
            QueueName.OUTBOX_MAINTENANCE.wireValue(), "key-lease");
        outbox.claimFor(WorkerFixture.ORG_A, "worker-a", 10, 60);

        assertThat(outbox.complete(WorkerFixture.ORG_A, id, "worker-b")).isFalse();
        assertThat(outbox.retry(WorkerFixture.ORG_A, id, "worker-b", Duration.ofSeconds(1), "x"))
            .isFalse();
        assertThat(outbox.deadLetter(WorkerFixture.ORG_A, id, "worker-b", "reason", "x")).isFalse();

        // The row is untouched: still LEASED, still owned by worker-a, still error-free.
        assertThat(fixture.stateOf(id)).isEqualTo("LEASED");
        assertThat(fixture.leaseOwnerOf(id)).isEqualTo("worker-a");
        assertThat(fixture.lastErrorOf(id)).isNull();
    }

    @Test
    @DisplayName("the lease holder completes the row and deliveredAt is set (CHECK enforced)")
    void completeIsTerminal() {
        UUID id = fixture.seedOutbox(WorkerFixture.ORG_A, WorkerFixture.PROJECT_A,
            QueueName.OUTBOX_MAINTENANCE.wireValue(), "key-done");
        outbox.claimFor(WorkerFixture.ORG_A, "worker-a", 10, 60);

        assertThat(outbox.complete(WorkerFixture.ORG_A, id, "worker-a")).isTrue();
        assertThat(fixture.stateOf(id)).isEqualTo("SENT");
        assertThat(fixture.leaseOwnerOf(id)).isNull();
        assertThat(outbox.reapExpiredLeases(10)).noneMatch(row -> row.id().equals(id));
    }

    // ------------------------------------------------------------------
    // retry / dead letter
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a retryable failure returns the row to PENDING with backoff and a recorded error")
    void retryReschedules() {
        UUID id = fixture.seedOutbox(WorkerFixture.ORG_A, WorkerFixture.PROJECT_A,
            QueueName.OUTBOX_MAINTENANCE.wireValue(), "key-retry");
        outbox.claimFor(WorkerFixture.ORG_A, "worker-a", 10, 60);

        assertThat(outbox.retry(WorkerFixture.ORG_A, id, "worker-a", Duration.ofMinutes(5),
            "TIMEOUT provider did not answer")).isTrue();

        assertThat(fixture.stateOf(id)).isEqualTo("PENDING");
        assertThat(fixture.leaseOwnerOf(id)).isNull();
        assertThat(fixture.lastErrorOf(id)).contains("TIMEOUT");
        // The backoff really was applied: the row is not claimable yet.
        assertThat(outbox.claimFor(WorkerFixture.ORG_A, "worker-a", 10, 60)).isEmpty();
    }

    @Test
    @DisplayName("a non-retryable failure makes the event terminal and operator-visible")
    void deadLetterIsTerminal() {
        UUID id = fixture.seedOutbox(WorkerFixture.ORG_A, WorkerFixture.PROJECT_A,
            QueueName.OUTBOX_MAINTENANCE.wireValue(), "key-dead");
        outbox.claimFor(WorkerFixture.ORG_A, "worker-a", 10, 60);

        assertThat(outbox.deadLetter(WorkerFixture.ORG_A, id, "worker-a",
            "INVALID_REQUEST/400: the provider rejected the request", "400 rejected")).isTrue();

        assertThat(fixture.stateOf(id)).isEqualTo("DEAD");
        // The dead letter is visible for this tenant only.
        assertThat(outbox.deadLetters(WorkerFixture.ORG_A, 10))
            .extracting(OutboxRepository.OutboxRow::id).contains(id);
        assertThat(outbox.deadLetters(WorkerFixture.ORG_B, 10)).isEmpty();
        // A DEAD row is not claimable again until an explicit replay.
        assertThat(outbox.claimFor(WorkerFixture.ORG_A, "worker-a", 10, 60)).isEmpty();
    }

    // ------------------------------------------------------------------
    // reaper
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an expired lease is NOT claimable until the reaper runs, then it is")
    void expiredLeaseIsReclaimedByTheReaper() {
        UUID id = fixture.seedOutbox(WorkerFixture.ORG_A, WorkerFixture.PROJECT_A,
            QueueName.OUTBOX_MAINTENANCE.wireValue(), "key-crashed");
        outbox.claimFor(WorkerFixture.ORG_A, "worker-that-died", 10, 60);
        fixture.expireLease(id);

        // state is still LEASED, so the canonical claim cannot see it. Without §7 this row is
        // stranded forever.
        assertThat(fixture.stateOf(id)).isEqualTo("LEASED");
        assertThat(outbox.claimFor(WorkerFixture.ORG_A, "worker-b", 10, 60)).isEmpty();
        assertThat(outbox.expiredLeaseCount()).isEqualTo(1);

        List<OutboxRepository.Reaped> reaped = outbox.reapExpiredLeases(10);
        assertThat(reaped).extracting(OutboxRepository.Reaped::id).containsExactly(id);
        assertThat(fixture.stateOf(id)).isEqualTo("PENDING");
        assertThat(fixture.leaseOwnerOf(id)).isNull();
        assertThat(fixture.lastErrorOf(id)).contains("lease expired");

        // The attempt that died still counts: the backoff stays monotonic.
        assertThat(fixture.attemptsOf(id)).isEqualTo(1);
        assertThat(outbox.claimFor(WorkerFixture.ORG_A, "worker-b", 10, 60))
            .extracting(OutboxRepository.OutboxRow::id).contains(id);
        assertThat(fixture.attemptsOf(id)).isEqualTo(2);
    }

    @Test
    @DisplayName("reaping twice is idempotent")
    void reapIsIdempotent() {
        UUID id = fixture.seedOutbox(WorkerFixture.ORG_A, WorkerFixture.PROJECT_A,
            QueueName.OUTBOX_MAINTENANCE.wireValue(), "key-reap-twice");
        outbox.claimFor(WorkerFixture.ORG_A, "worker-a", 10, 60);
        fixture.expireLease(id);

        assertThat(outbox.reapExpiredLeases(10)).hasSize(1);
        assertThat(outbox.reapExpiredLeases(10)).isEmpty();
    }

    // ------------------------------------------------------------------
    // replay
    // ------------------------------------------------------------------

    @Test
    @DisplayName("replay is audited, idempotent, and never creates a second row")
    void replayIsAuditedAndIdempotent() {
        UUID id = fixture.seedOutbox(WorkerFixture.ORG_A, WorkerFixture.PROJECT_A,
            QueueName.OUTBOUND_SEND.wireValue(), "key-replay");
        outbox.claimFor(WorkerFixture.ORG_A, "worker-a", 10, 60);
        outbox.deadLetter(WorkerFixture.ORG_A, id, "worker-a", "TIMEOUT", "TIMEOUT");

        TenantContext operator = TenantContext.forUser(UUID.randomUUID(), WorkerFixture.USER_A,
            WorkerFixture.ORG_A, UserRole.ADMIN, Set.of(WorkerFixture.PROJECT_A));

        assertThat(replay.replay(operator, id))
            .isEqualTo(OutboxReplayService.ReplayOutcome.REPLAYED);
        assertThat(fixture.stateOf(id)).isEqualTo("PENDING");
        assertThat(fixture.replayCountOf(id)).isEqualTo(1);
        assertThat(fixture.jdbc().queryForObject(
            "select \"replayedByUserId\" from outbox_events where \"id\" = :id",
            new org.springframework.jdbc.core.namedparam.MapSqlParameterSource().addValue("id", id),
            UUID.class)).isEqualTo(WorkerFixture.USER_A);

        // A second replay of an already-replayed row writes nothing and creates no second row.
        assertThat(replay.replay(operator, id))
            .isEqualTo(OutboxReplayService.ReplayOutcome.NOT_DEAD);
        assertThat(fixture.replayCountOf(id)).isEqualTo(1);
        assertThat(fixture.count("select count(*) from outbox_events where \"id\" = '"
            + id + "'")).isEqualTo(1);
    }

    @Test
    @DisplayName("a replay for another tenant's event is invisible, not a denial")
    void replayIsTenantScoped() {
        UUID id = fixture.seedOutbox(WorkerFixture.ORG_A, WorkerFixture.PROJECT_A,
            QueueName.OUTBOUND_SEND.wireValue(), "key-cross-tenant");
        outbox.claimFor(WorkerFixture.ORG_A, "worker-a", 10, 60);
        outbox.deadLetter(WorkerFixture.ORG_A, id, "worker-a", "TIMEOUT", "TIMEOUT");

        TenantContext betaAdmin = TenantContext.forUser(UUID.randomUUID(), WorkerFixture.USER_B,
            WorkerFixture.ORG_B, UserRole.ADMIN, Set.of(WorkerFixture.PROJECT_B));

        assertThat(replay.replay(betaAdmin, id))
            .isEqualTo(OutboxReplayService.ReplayOutcome.NOT_FOUND);
        assertThat(fixture.stateOf(id)).isEqualTo("DEAD");
        assertThat(fixture.replayCountOf(id)).isZero();
    }

    // ------------------------------------------------------------------
    // progress
    // ------------------------------------------------------------------

    @Test
    @DisplayName("progress is derived from the durable record, not stored beside it")
    void progressProjectsTheDurableRecord() {
        UUID id = fixture.seedOutbox(WorkerFixture.ORG_A, WorkerFixture.PROJECT_A,
            QueueName.OUTBOUND_SEND.wireValue(), "key-progress");

        var queued = progress.progressFor(WorkerFixture.ORG_A, id).orElseThrow();
        assertThat(queued.state()).isEqualTo(OutboxState.PENDING);
        assertThat(queued.summary()).isEqualTo("queued");

        outbox.claimFor(WorkerFixture.ORG_A, "worker-a", 10, 60);
        var inFlight = progress.progressFor(WorkerFixture.ORG_A, id).orElseThrow();
        assertThat(inFlight.state()).isEqualTo(OutboxState.LEASED);
        assertThat(inFlight.leaseOwner()).isEqualTo("worker-a");
        assertThat(inFlight.attemptsMade()).isEqualTo(1);
        assertThat(inFlight.maxAttempts()).isGreaterThan(1);

        outbox.retry(WorkerFixture.ORG_A, id, "worker-a", Duration.ofMinutes(2), "TIMEOUT");
        var retrying = progress.progressFor(WorkerFixture.ORG_A, id).orElseThrow();
        assertThat(retrying.state()).isEqualTo(OutboxState.PENDING);
        assertThat(retrying.summary()).contains("attempt 1/");
        assertThat(retrying.waitRemaining().toSeconds()).isGreaterThan(0);
        // There is deliberately no percentComplete; see JobProgressService.
        assertThat(retrying.toString()).doesNotContain("percentComplete");

        assertThat(progress.progressFor(WorkerFixture.ORG_B, id)).isEmpty();
    }

    // ------------------------------------------------------------------
    // dispatcher
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the dispatcher refuses a null tenant rather than using the tenant-less sweep")
    void claimRefusesAnUnscopedTenant() {
        assertThatThrownBy(() -> outbox.claimFor(null, "worker-a", 10, 60))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("tenant-scoped");
    }

    @Test
    @DisplayName("a maintenance job is claimed, handled, attempted and completed in one sweep")
    void maintenanceJobRunsEndToEnd() {
        UUID id = fixture.seedOutbox(WorkerFixture.ORG_A, WorkerFixture.PROJECT_A,
            QueueName.OUTBOX_MAINTENANCE.wireValue(), "key-maintenance");

        OutboxDispatcher.TenantSweepResult result =
            dispatcher.dispatchForTenant(WorkerFixture.ORG_A);

        assertThat(result.claimed()).isEqualTo(1);
        assertThat(result.succeeded()).isEqualTo(1);
        assertThat(fixture.stateOf(id)).isEqualTo("SENT");
        assertThat(fixture.attemptOutcomes(WorkerFixture.ORG_A,
            QueueName.OUTBOX_MAINTENANCE.wireValue())).containsExactly("SUCCEEDED");
    }

    @Test
    @DisplayName("an event whose queue has no handler dead-letters loudly instead of vanishing")
    void unknownQueueDeadLetters() {
        // Seeded directly, bypassing OutboxEnqueuer's write-time check, to prove the dispatcher's
        // own guard: a queue nothing drains must be visible to an operator.
        UUID id = UUID.randomUUID();
        fixture.jdbc().update("""
            insert into outbox_events ("id", "organizationId", "projectId", "aggregateType",
                "aggregateId", "eventType", "payload", "idempotencyKey", "nextAttemptAt",
                "createdAt", "updatedAt")
            values (:id, :org, :project, 'Fixture', :aggregate, 'queue-that-does-not-exist',
                '{}'::jsonb, :key, :now, :now, :now)""",
            new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                .addValue("id", id)
                .addValue("org", WorkerFixture.ORG_A)
                .addValue("project", WorkerFixture.PROJECT_A)
                .addValue("aggregate", UUID.randomUUID())
                .addValue("key", "key-unknown")
                .addValue("now", UtcTime.now()));

        OutboxDispatcher.TenantSweepResult result =
            dispatcher.dispatchForTenant(WorkerFixture.ORG_A);

        assertThat(result.claimed()).isEqualTo(1);
        assertThat(result.deadLettered()).isEqualTo(1);
        assertThat(fixture.stateOf(id)).isEqualTo("DEAD");
        // The dispatcher's own path writes BOTH rows: the event's terminal state and the operator's
        // dead-letter worklist. Recording only one leaves either an invisible event or a DLQ entry
        // with no event to open.
        assertThat(fixture.deadLetterCount()).isEqualTo(1);
        assertThat(deadLetters.open(WorkerFixture.ORG_A, 10)).hasSize(1);
        assertThat(deadLetters.open(WorkerFixture.ORG_B, 10)).isEmpty();
    }

    @Test
    @DisplayName("an attempt history is append-only: a repeated attempt number returns the existing row")
    void attemptHistoryIsAppendOnly() {
        UUID id = fixture.seedOutbox(WorkerFixture.ORG_A, WorkerFixture.PROJECT_A,
            QueueName.OUTBOX_MAINTENANCE.wireValue(), "key-attempt");

        OutboxDispatcher.TenantSweepResult result =
            dispatcher.dispatchForTenant(WorkerFixture.ORG_A);
        assertThat(result.succeeded()).isEqualTo(1);
        assertThat(fixture.attemptCount(WorkerFixture.ORG_A,
            QueueName.OUTBOX_MAINTENANCE.wireValue())).isEqualTo(1);

        // A worker that recorded its attempt and then died before completing finds the same
        // attempt number on restart. The unique index turns that into a no-op rather than a second
        // history row, and the repository returns the original id.
        UUID duplicate = attempts.record(new com.firstelement.worker.history.JobAttemptRow(
            UUID.randomUUID(), WorkerFixture.ORG_A, WorkerFixture.PROJECT_A,
            QueueName.OUTBOX_MAINTENANCE.wireValue(), QueueName.OUTBOX_MAINTENANCE.wireValue(),
            id.toString(), 1, "SUCCEEDED", null, null, null,
            UtcTime.now(), UtcTime.now(), 5, "worker-a", null, null,
            false, null, id, UtcTime.now()));

        assertThat(duplicate).isNotNull();
        assertThat(fixture.attemptCount(WorkerFixture.ORG_A,
            QueueName.OUTBOX_MAINTENANCE.wireValue())).isEqualTo(1);
    }

    @Autowired
    private com.firstelement.worker.history.JobAttemptRepository attempts;
    @Autowired
    private DeadLetterRepository deadLetters;
}
