package com.firstelement.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstelement.security.TenantContext;
import com.firstelement.security.UserRole;
import com.firstelement.worker.config.UtcTime;
import com.firstelement.worker.history.DeadLetterRepository;
import com.firstelement.worker.history.JobAttemptRepository;
import com.firstelement.worker.history.JobAttemptRow;
import com.firstelement.worker.outbox.OutboxRepository;
import com.firstelement.worker.queue.QueueName;
import com.firstelement.worker.runtime.OutboxReplayService;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * "Replays are never silent" &mdash; the operator's side of the dead-letter lifecycle, against the
 * real audit writer.
 *
 * <h2>What an audited replay means here</h2>
 *
 * <p>Four separate facts, all of which must exist or none of which counts as an audit trail:
 *
 * <ol>
 *   <li>the event records <b>who</b> replayed it and <b>when</b>;</li>
 *   <li>a {@code job_attempts} row with {@code isReplay = true} names the failure it replaces, and
 *       the failed attempt is <em>unchanged</em>;</li>
 *   <li>the dead letter moves to {@code REPLAYED};</li>
 *   <li>a {@code BUSINESS_ACTION} row lands in {@code audit_logs}.</li>
 * </ol>
 *
 * <h2>And what a safe replay means</h2>
 *
 * <p>The update only matches {@code state = 'DEAD'}, so a second replay writes nothing, increments
 * nothing, and creates no second outbox row. Attempts are not reset either: a replayed row runs as
 * the next attempt number, so the backoff stays monotonic and an operator who replays a job that is
 * still broken gets one more try, not a fresh budget.
 */
@SpringBootTest(classes = WorkerTestApplication.class,
    properties = "firstelement.worker.dispatcher.enabled=false")
class OutboxDeadLetterReplayIntegrationTest extends WorkerIntegrationTestBase {

    private static final String REASON = "INVALID_REQUEST/400: the provider rejected the request";
    private static final String SUMMARY = "{\"queue\":\"outbound-send\",\"attempts\":3}";

    @Autowired
    private OutboxRepository outbox;
    @Autowired
    private OutboxReplayService replay;
    @Autowired
    private DeadLetterRepository deadLetters;
    @Autowired
    private JobAttemptRepository attempts;
    @Autowired
    private WorkerFixture fixture;

    @BeforeEach
    void reset() {
        fixture.reset();
    }

    @Test
    @DisplayName("a replay records who, when, and what it replays, and mutates no history")
    void replayIsFullyAudited() {
        DeadLetter dead = deadLetterOutbound("audit-key", 3);

        assertThat(replay.replay(alphaAdmin(), dead.event()))
            .isEqualTo(OutboxReplayService.ReplayOutcome.REPLAYED);

        // (1) who and when, on the event itself.
        assertThat(fixture.stateOf(dead.event())).isEqualTo("PENDING");
        assertThat(fixture.replayCountOf(dead.event())).isEqualTo(1);
        assertThat(fixture.textOf("select \"replayedByUserId\"::text from outbox_events"
            + " where \"id\" = :id", dead.event())).isEqualTo(WorkerFixture.USER_A.toString());
        assertThat(fixture.timestampOf("select \"lastReplayedAt\" from outbox_events"
            + " where \"id\" = :id", dead.event())).isNotNull();

        // (2) the replay is an attempt that names the failure, and the failure is untouched.
        var replayAttempt = attempts.attemptsFor(WorkerFixture.ORG_A,
            QueueName.OUTBOUND_SEND.wireValue(), dead.event().toString()).stream()
            .filter(JobAttemptRow::isReplay).findFirst().orElseThrow();
        assertThat(replayAttempt.replayOfId()).isEqualTo(dead.failure());
        assertThat(replayAttempt.attemptNumber()).isEqualTo(4);
        assertThat(replayAttempt.errorCode()).isEqualTo("REPLAY_REQUESTED");
        JobAttemptRow preserved = attempts.findByNumber(WorkerFixture.ORG_A,
            QueueName.OUTBOUND_SEND.wireValue(), dead.event().toString(), 3).orElseThrow();
        assertThat(preserved.id()).isEqualTo(dead.failure());
        assertThat(preserved.outcome()).isEqualTo("NON_RETRYABLE_FAILURE");

        // (3) the dead letter moved to REPLAYED and still names the job an operator must open.
        DeadLetterRepository.DeadLetterRow row = deadLetters.find(WorkerFixture.ORG_A,
            QueueName.OUTBOUND_SEND.wireValue(), dead.event().toString()).orElseThrow();
        assertThat(row.status()).isEqualTo("REPLAYED");
        assertThat(row.lastAttemptId()).isEqualTo(dead.failure());
        assertThat(row.outboxEventId()).isEqualTo(dead.event());
        assertThat(row.attempts()).isEqualTo(3);
        assertThat(row.payloadSummary()).contains("outbound-send").doesNotContain("payload");

        // (4) and the audit stream has it.
        assertThat(auditCount(dead.event(), "outbox.replay")).isEqualTo(1);
    }

    @Test
    @DisplayName("a second replay of the same event writes nothing and is still audited")
    void replayIsSafeToRepeat() {
        DeadLetter dead = deadLetterOutbound("repeat-key", 1);

        assertThat(replay.replay(alphaAdmin(), dead.event()))
            .isEqualTo(OutboxReplayService.ReplayOutcome.REPLAYED);
        assertThat(replay.replay(alphaAdmin(), dead.event()))
            .isEqualTo(OutboxReplayService.ReplayOutcome.NOT_DEAD);
        assertThat(replay.replay(alphaAdmin(), dead.event()))
            .isEqualTo(OutboxReplayService.ReplayOutcome.NOT_DEAD);

        assertThat(fixture.replayCountOf(dead.event())).isEqualTo(1);
        assertThat(fixture.count("select count(*) from outbox_events where \"id\" = '"
            + dead.event() + "'")).isEqualTo(1);
        // The no-op is itself recorded, so an operator replaying something already replayed leaves a
        // trace of the attempt rather than silence.
        assertThat(auditCount(dead.event(), "outbox.replay-noop")).isEqualTo(2);
    }

    @Test
    @DisplayName("a dead letter is one row per job: attempts accumulate on it instead of growing")
    void deadLetterAccumulatesRatherThanGrowing() {
        String jobId = "stable-job-identity";
        // outboxEventId is a real foreign key, so the row an operator would open must exist.
        UUID outboxEventId = fixture.seedOutbox(WorkerFixture.ORG_A, WorkerFixture.PROJECT_A,
            QueueName.OUTBOUND_SEND.wireValue(), "dlq-accumulation");
        deadLetters.record(WorkerFixture.ORG_A, WorkerFixture.PROJECT_A,
            QueueName.OUTBOUND_SEND.wireValue(), jobId, outboxEventId, null, REASON, SUMMARY, 2);
        deadLetters.record(WorkerFixture.ORG_A, WorkerFixture.PROJECT_A,
            QueueName.OUTBOUND_SEND.wireValue(), jobId, outboxEventId, null, REASON, SUMMARY, 3);

        DeadLetterRepository.DeadLetterRow row = deadLetters.find(WorkerFixture.ORG_A,
            QueueName.OUTBOUND_SEND.wireValue(), jobId).orElseThrow();
        assertThat(row.attempts()).isEqualTo(5);
        // Two deaths are not two replays. replayCount/lastReplayedAt are the operator's history and
        // only the replay path may write them; if a failure counted as a replay, the Phase-7 Admin
        // UI would report replay activity that nobody performed.
        assertThat(row.replayCount()).isZero();
        assertThat(row.lastReplayedAt()).isNull();
        assertThat(row.status()).isEqualTo("OPEN");
        assertThat(fixture.count("select count(*) from job_dead_letters")).isEqualTo(1);

        // A resolution is attributable, or the database's CHECK refuses it and so does the service.
        assertThat(replay.resolve(alphaAdmin(), row.id(), "RESOLVED", "provider credentials fixed"))
            .isTrue();
        DeadLetterRepository.DeadLetterRow resolved = deadLetters.find(WorkerFixture.ORG_A,
            QueueName.OUTBOUND_SEND.wireValue(), jobId).orElseThrow();
        assertThat(resolved.status()).isEqualTo("RESOLVED");
        assertThat(resolved.resolvedByUserId()).isEqualTo(WorkerFixture.USER_A);
        assertThat(resolved.resolvedAt()).isNotNull();
        assertThat(resolved.resolutionNote()).contains("credentials fixed");
        assertThat(fixture.count("select count(*) from audit_logs"
            + " where \"resourceType\" = 'JobDeadLetter' and \"resourceId\" = '" + row.id() + "'"
            + " and \"metadata\"->>'action' = 'outbox.deadletter-resolved'")).isEqualTo(1);

        // Another tenant's operator sees nothing.
        assertThat(deadLetters.find(WorkerFixture.ORG_B, QueueName.OUTBOUND_SEND.wireValue(), jobId))
            .isEmpty();
    }

    @Test
    @DisplayName("a replay for a row that does not exist in this tenant is invisible, not a denial")
    void replayOfAnotherTenantsEventIsNotFound() {
        DeadLetter dead = deadLetterOutbound("cross-tenant-key", 1);

        TenantContext beta = TenantContext.forUser(UUID.randomUUID(), WorkerFixture.USER_B,
            WorkerFixture.ORG_B, UserRole.ADMIN, Set.of(WorkerFixture.PROJECT_B));

        assertThat(replay.replay(beta, dead.event()))
            .isEqualTo(OutboxReplayService.ReplayOutcome.NOT_FOUND);
        assertThat(fixture.stateOf(dead.event())).isEqualTo("DEAD");
        assertThat(fixture.replayCountOf(dead.event())).isZero();
        // The no-op is recorded against the CALLER's tenant and actor, never the victim's, so the
        // audit trail cannot be used to confirm that someone else's id exists.
        assertThat(fixture.count("select count(*) from audit_logs where \"organizationId\" = '"
            + WorkerFixture.ORG_B + "' and \"resourceId\" = '" + dead.event() + "'")).isEqualTo(1);
        assertThat(fixture.count("select count(*) from audit_logs where \"organizationId\" = '"
            + WorkerFixture.ORG_A + "' and \"resourceId\" = '" + dead.event()
            + "' and \"metadata\"->>'action' = 'outbox.replay-noop'")).isZero();
    }

    @Test
    @DisplayName("a machine principal cannot replay: an unattributable replay is refused")
    void aMachinePrincipalCannotReplay() {
        DeadLetter dead = deadLetterOutbound("machine-key", 1);

        TenantContext machine = TenantContext.forMachine(
            com.firstelement.security.PrincipalType.WORKER, WorkerFixture.ORG_A, "worker-1",
            Set.of(WorkerFixture.PROJECT_A),
            java.util.List.of(com.firstelement.security.Capability.MESSAGING_SEND));

        assertThatThrownBy(() -> replay.replay(machine, dead.event()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("authenticated user");
        assertThat(fixture.stateOf(dead.event())).isEqualTo("DEAD");
    }

    // ------------------------------------------------------------------

    private record DeadLetter(UUID event, UUID failure) {
    }

    /** A dead-lettered outbound event with the attempt history and DLQ row a replay would name. */
    private DeadLetter deadLetterOutbound(String key, int attemptNumber) {
        UUID id = fixture.seedOutbox(WorkerFixture.ORG_A, WorkerFixture.PROJECT_A,
            QueueName.OUTBOUND_SEND.wireValue(), key);
        UUID failure = recordFailure(id, attemptNumber);
        outbox.claimFor(WorkerFixture.ORG_A, "worker-a", 10, 60);
        assertThat(outbox.deadLetter(WorkerFixture.ORG_A, id, "worker-a", REASON, "400 rejected"))
            .isTrue();
        deadLetters.record(WorkerFixture.ORG_A, WorkerFixture.PROJECT_A,
            QueueName.OUTBOUND_SEND.wireValue(), id.toString(), id, failure, REASON, SUMMARY,
            attemptNumber);
        return new DeadLetter(id, failure);
    }

    /** One recorded failed attempt, so the replay has something to name. */
    private UUID recordFailure(UUID outboxEventId, int attemptNumber) {
        return attempts.record(new JobAttemptRow(UUID.randomUUID(), WorkerFixture.ORG_A,
            WorkerFixture.PROJECT_A, QueueName.OUTBOUND_SEND.wireValue(),
            QueueName.OUTBOUND_SEND.wireValue(), outboxEventId.toString(), attemptNumber,
            "NON_RETRYABLE_FAILURE", "INVALID_REQUEST", "400", "the provider rejected the request",
            UtcTime.now(), UtcTime.now(), 12, "worker-a", "corr-replay", null, false, null,
            outboxEventId, UtcTime.now()));
    }

    private int auditCount(UUID resourceId, String action) {
        // `audit_logs."action"` carries the event TYPE; the action label lives in the metadata,
        // because the writer has no column for a reason.
        return fixture.count("select count(*) from audit_logs"
            + " where \"organizationId\" = '" + WorkerFixture.ORG_A + "'"
            + " and \"resourceType\" = 'OutboxEvent' and \"resourceId\" = '" + resourceId + "'"
            + " and action = 'Admin.BusinessAction'"
            + " and \"metadata\"->>'action' = '" + action + "'");
    }

    private static TenantContext alphaAdmin() {
        return TenantContext.forUser(UUID.randomUUID(), WorkerFixture.USER_A, WorkerFixture.ORG_A,
            UserRole.ADMIN, Set.of(WorkerFixture.PROJECT_A));
    }
}