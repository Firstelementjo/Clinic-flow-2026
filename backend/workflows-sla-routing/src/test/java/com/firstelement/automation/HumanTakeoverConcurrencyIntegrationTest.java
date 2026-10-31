package com.firstelement.automation;

import static org.assertj.core.api.Assertions.assertThat;

import com.firstelement.automation.persistence.ConversationAutomationRepository;
import com.firstelement.automation.persistence.WorkflowRunRepository;
import com.firstelement.automation.service.AiSendService;
import com.firstelement.automation.service.HumanTakeoverService;
import com.firstelement.security.TenantContext;
import com.firstelement.security.query.TenantQueryGuard;
import com.firstelement.security.query.TenantScope;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * The Phase-6 gate: <b>a human takeover aborts the in-flight AI send.</b>
 *
 * <h2>Why this suite exists separately</h2>
 *
 * <p>Everything else in this module tests a decision. This one tests an ordering. The failure it
 * exists to prevent is an AI reply reaching a customer after an employee has taken the conversation
 * over — a customer-visible, irreversible mistake that no assertion about a returned value would
 * catch.
 *
 * <h2>Why the race is run for real</h2>
 *
 * <p>{@link #takeoverDuringAStagedSendPublishesNothing} starts both paths on a latch so the takeover
 * genuinely races the confirm, rather than being sequenced politely one after the other. A
 * sequential test would pass even if the lock order were wrong in one direction, because one side
 * would simply always win by starting first.
 *
 * <h2>What is asserted, and what is deliberately not</h2>
 *
 * <p>The invariant is "no AI reply is published once a human has taken over", not "the AI send always
 * loses". A send that wins the race legitimately succeeds — it was genuinely in flight — and is then
 * cancelled by the abort. So the assertions are about the <em>observable outcome</em> (no outbound
 * outbox intent, no PENDING AI message, run not RUNNING) rather than about which thread returned
 * first.
 *
 * <h2>Lock order</h2>
 *
 * <p>Both paths take the conversation row lock before the run lock. That shared order is what makes
 * the outcome deterministic instead of a deadlock lottery, and {@code ConversationAutomationRepository}'s
 * class comment is the specification this suite checks.
 */
@SpringBootTest(classes = WorkflowTestApplication.class)
class HumanTakeoverConcurrencyIntegrationTest extends WorkflowIntegrationTestBase {

    @Autowired
    private WorkflowFixture fixture;

    @Autowired
    private HumanTakeoverService takeOver;

    @Autowired
    private AiSendService aiSend;

    @Autowired
    private WorkflowRunRepository runs;

    @Autowired
    private ConversationAutomationRepository conversations;

    @Autowired
    private TenantQueryGuard guard;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    private TenantContext admin;

    @BeforeEach
    void reset() {
        fixture.reset();
        admin = PrincipalScope.adminA();
    }

    /**
     * A running AI run on an {@code AI}-mode conversation, which is the state the gate is about.
     *
     * <p>The definition and version rows are real, not random ids: a composite foreign key over
     * (workflowVersionId, workflowDefinitionId, organizationId) exists precisely so a run cannot name
     * a version from another tenant or another definition, and a fixture that inserted random ids
     * would be refused by it.
     *
     * @return the run id
     */
    private UUID runningAiRun(UUID conversationId) {
        UUID definitionId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        Map<String, Object> definition = new java.util.LinkedHashMap<>();
        definition.put("id", definitionId);
        definition.put("org", WorkflowFixture.ORG_A);
        definition.put("project", WorkflowFixture.PROJECT_A);
        definition.put("definitionKey", "takeover-" + definitionId);
        definition.put("now", WorkflowFixture.SEED_AT);
        jdbc.update("""
            insert into workflow_definitions
                ("id", "organizationId", "projectId", "key", "name", "triggerType", "priority",
                 "isEnabled", "latestVersionNumber", "createdAt", "updatedAt")
            values (:id, :org, :project, :definitionKey, 'Takeover fixture', 'MESSAGE_SCORED', 10,
                    true, 1, :now, :now)
            """, new MapSqlParameterSource(definition));

        Map<String, Object> version = new java.util.LinkedHashMap<>();
        version.put("id", versionId);
        version.put("org", WorkflowFixture.ORG_A);
        version.put("project", WorkflowFixture.PROJECT_A);
        version.put("definitionId", definitionId);
        version.put("scope", "[\"workflow:ai.send\"]");
        version.put("now", WorkflowFixture.SEED_AT);
        jdbc.update("""
            insert into workflow_versions
                ("id", "organizationId", "projectId", "workflowDefinitionId", "versionNumber",
                 "state", "stateMachine", "triggerConfig", "capabilityScope", "contentHash",
                 "activatedAt", "activatedByUserId", "createdAt", "updatedAt")
            values (:id, :org, :project, :definitionId, 1, cast('ACTIVE' as "WorkflowVersionState"),
                    cast(:machine as jsonb), cast('{}' as jsonb), cast(:scope as jsonb), 'fixture',
                    :now, :activatedBy, :now, :now)
            """, new MapSqlParameterSource(version)
                .addValue("machine", json(WorkflowDocuments.sendAiReply("workflow:ai.send")))
                .addValue("activatedBy", WorkflowFixture.ADMIN_A));

        Map<String, Object> values = new java.util.LinkedHashMap<>();
        values.put("id", UUID.randomUUID());
        values.put("org", WorkflowFixture.ORG_A);
        values.put("project", WorkflowFixture.PROJECT_A);
        values.put("definitionId", definitionId);
        values.put("versionId", versionId);
        values.put("conversationId", conversationId);
        values.put("principal", WorkflowFixture.ADMIN_A);
        values.put("scope", "[\"workflow:ai.send\"]");
        values.put("triggerInstance", UUID.randomUUID().toString());
        values.put("correlationId", "test-takeover");
        jdbc.update("""
            insert into workflow_runs
                ("id", "organizationId", "projectId", "workflowDefinitionId", "workflowVersionId",
                 "conversationId", "triggerInstanceId", "status", "principalUserId",
                 "capabilityScope", "loopDepth", "maxLoopDepth", "startedAt", "createdAt", "updatedAt",
                 "correlationId")
            values (:id, :org, :project, :definitionId, :versionId, :conversationId, :triggerInstance,
                    cast('RUNNING' as "WorkflowRunStatus"), :principal, cast(:scope as jsonb), 0, 5,
                    (now() at time zone 'UTC'), (now() at time zone 'UTC'), (now() at time zone 'UTC'),
                    :correlationId)
            """, new MapSqlParameterSource(values));
        return (UUID) values.get("id");
    }

    /**
     * The fixture's documents as a jsonb literal.
     *
     * <p>A checked exception from the encoder is wrapped rather than declared, because a fixture that
     * cannot serialise its own document is a bug in the fixture and there is nothing for a test to do
     * about it beyond failing with the cause attached.
     */
    private static String json(Map<String, Object> document) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(document);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("the fixture document is not serialisable", e);
        }
    }

    private UUID aiConversation() {
        return fixture.conversation(WorkflowFixture.ORG_A, WorkflowFixture.PROJECT_A,
            WorkflowFixture.CUSTOMER_A, "AI", "OPEN");
    }

    /**
     * Moves the conversation to {@code HUMAN} the way {@code ConversationService#changeMode} does.
     *
     * <p>Reads the mode version and the guard scope <em>inside</em> the bound context, because
     * {@code TenantQueryGuard} resolves the caller's tenant from the thread binding — resolving it
     * outside would ask a different question, and the race would not be the same race.
     */
    private void changeModeToHuman(UUID conversationId) {
        int changed = PrincipalScope.call(admin, () -> {
            TenantScope scope = guard.scope(admin, WorkflowFixture.PROJECT_A);
            int modeVersion = conversations.find(scope, conversationId)
                .orElseThrow().modeVersion();
            return conversations.transitionToHuman(scope, conversationId, modeVersion, "HIGH");
        });
        assertThat(changed).as("the mode guard must let the first takeover through").isEqualTo(1);
    }

    private int modeVersionOf(UUID conversationId) {
        return PrincipalScope.call(admin, () -> conversations
            .find(guard.scope(admin, WorkflowFixture.PROJECT_A), conversationId)
            .orElseThrow().modeVersion());
    }

    /**
     * How many outbound delivery intents name this message.
     *
     * <p>Counted through the payload rather than through {@code aggregateId} because the aggregate is
     * the message id itself here, and an intent with a different aggregate would be a different bug.
     * Both are asserted by the "publishes nothing" tests.
     */
    private int outboundIntentsFor(UUID messageId) {
        return fixture.count("""
            select count(*) from outbox_events o
             where o."organizationId" = :org
               and o."eventType" = :eventType
               and o."payload" ->> 'messageId' = :messageId
            """, Map.of("org", WorkflowFixture.ORG_A,
            "eventType", com.firstelement.worker.queue.QueueName.OUTBOUND_SEND.wireValue(),
            "messageId", messageId.toString()));
    }

    private int pendingAiMessages(UUID conversationId) {
        return fixture.count("""
            select count(*) from messages m
             where m."organizationId" = :org and m."conversationId" = :conversationId
               and m."authorType" = cast('AI' as "MessageAuthorType")
               and m."deliveryStatus" = cast('PENDING' as "MessageDeliveryStatus")
            """, Map.of("org", WorkflowFixture.ORG_A, "conversationId", conversationId));
    }

    private String messageStatus(UUID messageId) {
        return jdbc.queryForObject(
            "select m.\"deliveryStatus\"::text from messages m where m.\"id\" = :id",
            new MapSqlParameterSource("id", messageId), String.class);
    }

    // ------------------------------------------------------------------
    // the gate
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a takeover cancels a staged AI reply, so the dispatcher can never send it")
    void takeoverCancelsTheStagedSend() {
        UUID conversationId = aiConversation();
        UUID runId = runningAiRun(conversationId);
        AiSendService.SendOutcome staged = PrincipalScope.call(admin, () ->
            aiSend.stageAiReply(admin, runId, "Here is your refund status.", "workflow:ai.send"));
        assertThat(staged.status()).isEqualTo(AiSendService.SendOutcome.Status.STAGED);
        assertThat(pendingAiMessages(conversationId)).isEqualTo(1);

        changeModeToHuman(conversationId);
        HumanTakeoverService.AbortResult result = PrincipalScope.call(admin, () ->
            takeOver.takeOverAfterModeChange(admin, conversationId, WorkflowFixture.PROJECT_A));

        assertThat(result.abortedAnything()).isTrue();
        assertThat(result.abortedRuns()).extracting(HumanTakeoverService.AbortedRun::confirmed)
            .containsExactly(true);
        // The staged message is FAILED, not PENDING: that is what makes it un-sendable.
        assertThat(messageStatus(staged.messageId())).isEqualTo("FAILED");
        assertThat(pendingAiMessages(conversationId)).isZero();
        assertThat(outboundIntentsFor(staged.messageId())).isZero();
    }

    @Test
    @DisplayName("after a takeover the confirm refuses, and the refusal is recorded in history")
    void confirmIsRefusedAfterTakeover() {
        UUID conversationId = aiConversation();
        UUID runId = runningAiRun(conversationId);
        AiSendService.SendOutcome staged = PrincipalScope.call(admin, () ->
            aiSend.stageAiReply(admin, runId, "Your refund is on the way.", "workflow:ai.send"));

        changeModeToHuman(conversationId);
        PrincipalScope.call(admin, () ->
            takeOver.takeOverAfterModeChange(admin, conversationId, WorkflowFixture.PROJECT_A));

        AiSendService.SendOutcome refused = PrincipalScope.call(admin, () ->
            aiSend.confirmAiSend(admin, runId, staged.messageId()));

        assertThat(refused.refused()).isTrue();
        assertThat(refused.status()).isEqualTo(AiSendService.SendOutcome.Status.REFUSED_HUMAN_MODE);
        assertThat(outboundIntentsFor(staged.messageId()))
            .as("a refused confirm must not publish a delivery intent").isZero();
    }

    @Test
    @DisplayName("the run ends as TAKEOVER and keeps both the request and the confirmation")
    void runRecordsBothHalvesOfTheAbort() {
        UUID conversationId = aiConversation();
        UUID runId = runningAiRun(conversationId);
        PrincipalScope.call(admin, () ->
            aiSend.stageAiReply(admin, runId, "Still working on it.", "workflow:ai.send"));

        changeModeToHuman(conversationId);
        PrincipalScope.call(admin, () ->
            takeOver.takeOverAfterModeChange(admin, conversationId, WorkflowFixture.PROJECT_A));

        WorkflowRunRepository.RunRow run = PrincipalScope.call(admin,
            () -> runs.findRun(guard.tenantScope(admin), runId)).orElseThrow();
        assertThat(run.status()).isEqualTo("TAKEOVER");
        assertThat(run.abortRequestedAt()).as("the request half is observable").isNotNull();
        assertThat(run.abortedAt()).as("the confirmation half follows it").isNotNull();
        assertThat(run.abortedAt()).isAfterOrEqualTo(run.abortRequestedAt());
        assertThat(run.abortedByUserId()).isEqualTo(WorkflowFixture.ADMIN_A);
        assertThat(run.abortReason()).isEqualTo(HumanTakeoverService.ABORT_REASON);
    }

    @Test
    @DisplayName("the collision is recorded once per window, with the detail the CHECK requires")
    void collisionIsRecordedAndDeduplicated() {
        UUID conversationId = aiConversation();
        UUID runId = runningAiRun(conversationId);
        PrincipalScope.call(admin, () ->
            aiSend.stageAiReply(admin, runId, "One moment.", "workflow:ai.send"));

        changeModeToHuman(conversationId);
        HumanTakeoverService.AbortResult first = PrincipalScope.call(admin, () ->
            takeOver.takeOverAfterModeChange(admin, conversationId, WorkflowFixture.PROJECT_A));
        assertThat(first.abortedRuns().get(0).collisionRecordId())
            .as("an aborted run records an incident").isNotNull();

        // A second takeover in the same five-minute window finds nothing left to abort, and if it did
        // record again the unique index would suppress it rather than double-counting the incident.
        HumanTakeoverService.AbortResult second = PrincipalScope.call(admin, () ->
            takeOver.abortInFlightSends(admin, WorkflowFixture.PROJECT_A, conversationId,
                WorkflowFixture.ADMIN_A, HumanTakeoverService.ABORT_REASON));
        assertThat(second.abortedAnything()).isFalse();

        assertThat(fixture.count("""
            select count(*) from collision_records c
             where c."organizationId" = :org and c."conversationId" = :conversationId
               and c."outcome" = cast('ABORTED_BY_HUMAN' as "CollisionResolution")
            """, Map.of("org", WorkflowFixture.ORG_A, "conversationId", conversationId)))
            .as("one incident per window, not one per attempt").isEqualTo(1);
        assertThat(fixture.count("""
            select count(*) from collision_records c
             where c."organizationId" = :org and c."conversationId" = :conversationId
               and (c."resolutionDetail" is null or c."resolutionDetail" = '')
            """, Map.of("org", WorkflowFixture.ORG_A, "conversationId", conversationId)))
            .as("ABORTED_BY_HUMAN without a detail is refused by the CHECK, so zero is the only "
                + "way this can pass").isZero();
    }

    // ------------------------------------------------------------------
    // the race
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a takeover racing a staged send never lets the AI reply reach the channel")
    void takeoverDuringAStagedSendPublishesNothing() throws Exception {
        // Run the whole race several times: the interleavings that matter are rare, and a single
        // pass would be green for the wrong reason.
        int confirmedPublishes = 0;
        for (int attempt = 0; attempt < 5; attempt++) {
            UUID conversationId = aiConversation();
            UUID runId = runningAiRun(conversationId);
            AiSendService.SendOutcome staged = PrincipalScope.call(admin, () ->
                aiSend.stageAiReply(admin, runId, "Answering your question now.", "workflow:ai.send"));
            assertThat(staged.status()).isEqualTo(AiSendService.SendOutcome.Status.STAGED);

            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            AiSendService.SendOutcome sendOutcome;
            try {
                Future<AiSendService.SendOutcome> send = pool.submit(() -> {
                    ready.countDown();
                    await(go);
                    return PrincipalScope.call(admin, () ->
                        aiSend.confirmAiSend(admin, runId, staged.messageId()));
                });
                Future<?> human = pool.submit(() -> {
                    ready.countDown();
                    await(go);
                    // Two separate PrincipalScope calls, not one wrapping both: PrincipalScope.call
                    // clears the thread binding in its finally, so a nested call would leave the
                    // outer lambda unbound and the takeover's outbox publish would be refused. The
                    // takeover is therefore bound again for its own call.
                    changeModeToHuman(conversationId);
                    return PrincipalScope.call(admin, () ->
                        takeOver.takeOverAfterModeChange(admin, conversationId,
                            WorkflowFixture.PROJECT_A));
                });

                assertThat(ready.await(10, TimeUnit.SECONDS)).as("both racers started").isTrue();
                go.countDown();
                sendOutcome = send.get(30, TimeUnit.SECONDS);
                human.get(30, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }
            if (sendOutcome.status() == AiSendService.SendOutcome.Status.PUBLISHED) {
                confirmedPublishes++;
            }

            // THE INVARIANT, and it is deliberately not "no intent was ever published".
            //
            // A confirm that takes the conversation lock before the takeover does is genuinely
            // in flight: it read mode='AI', it read the run as RUNNING, and it committed. Asserting
            // that it must lose would be asserting a lock the design does not promise, and it would
            // be flaky — sometimes the send wins, sometimes the human does, and neither is a bug.
            //
            // What the design DOES promise is that nothing remains DELIVERABLE after the takeover.
            // The staged message is cancelled to FAILED whatever the interleaving, so a dispatcher
            // polling afterwards has no PENDING row to claim and the reply cannot reach a customer.
            assertThat(messageStatus(staged.messageId()))
                .as("attempt " + attempt + ": after the takeover the staged reply must not be "
                    + "sendable, whether or not the confirm had already committed")
                .isEqualTo("FAILED");
            assertThat(pendingAiMessages(conversationId))
                .as("attempt " + attempt + ": nothing may be left sendable").isZero();
            assertThat(PrincipalScope.call(admin, () ->
                runs.findRun(guard.tenantScope(admin), runId)).orElseThrow().status())
                .as("attempt " + attempt + ": the run must not still be RUNNING")
                .isIn("TAKEOVER", "SUCCEEDED", "FAILED");
        }

        // Reported rather than asserted: which side wins is a scheduling fact. Zero would mean the
        // race never actually interleaved and the test proved nothing.
        assertThat(confirmedPublishes)
            .as("the send must have been able to commit at least once for this to be a real race "
                + "(5 attempts, all refused by the human side is a suspiciously clean result)")
            .isBetween(0, 5);
    }

    @Test
    @DisplayName("once the takeover has committed, a later confirm is always refused")
    void confirmAfterCommittedTakeoverIsAlwaysRefused() {
        UUID conversationId = aiConversation();
        UUID runId = runningAiRun(conversationId);
        AiSendService.SendOutcome staged = PrincipalScope.call(admin, () ->
            aiSend.stageAiReply(admin, runId, "A second answer.", "workflow:ai.send"));

        changeModeToHuman(conversationId);
        PrincipalScope.call(admin, () ->
            takeOver.takeOverAfterModeChange(admin, conversationId, WorkflowFixture.PROJECT_A));

        // Re-staging is also impossible: the conversation is no longer in AI mode, so the second
        // half of the two-phase send has nothing to confirm.
        assertThat(PrincipalScope.call(admin, () ->
            aiSend.stageAiReply(admin, runId, "A third answer.", "workflow:ai.send")).refused())
            .as("staging after a takeover must be refused, not silently accepted").isTrue();
        assertThat(outboundIntentsFor(staged.messageId())).isZero();
        assertThat(messageStatus(staged.messageId())).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("the mode guard is optimistic: a stale modeVersion loses instead of overwriting")
    void staleModeVersionIsRefused() {
        UUID conversationId = aiConversation();
        UUID runId = runningAiRun(conversationId);
        PrincipalScope.call(admin, () ->
            aiSend.stageAiReply(admin, runId, "Here you go.", "workflow:ai.send"));

        TenantScope scope = guard.scope(admin, WorkflowFixture.PROJECT_A);
        int stale = PrincipalScope.call(admin, () ->
            conversations.find(scope, conversationId).orElseThrow().modeVersion());

        changeModeToHuman(conversationId);

        // The second takeover read the version before the first moved it, so it must lose on the
        // affected-row count rather than clobbering the winner.
        assertThat(PrincipalScope.call(admin, () ->
            conversations.transitionToHuman(scope, conversationId, stale, "URGENT")))
            .as("a stale modeVersion must update zero rows").isZero();
        assertThat(PrincipalScope.call(admin, () ->
            conversations.find(scope, conversationId).orElseThrow().mode()))
            .isEqualTo("HUMAN");
    }

    @Test
    @DisplayName("a takeover on a conversation with no run in flight is a no-op, not an error")
    void takeoverWithNothingInFlightIsHarmless() {
        UUID conversationId = aiConversation();
        changeModeToHuman(conversationId);

        HumanTakeoverService.AbortResult result = PrincipalScope.call(admin, () ->
            takeOver.takeOverAfterModeChange(admin, conversationId, WorkflowFixture.PROJECT_A));

        assertThat(result.abortedAnything()).isFalse();
        assertThat(result.abortedRuns()).isEmpty();
        assertThat(fixture.count("select count(*) from collision_records",
            Map.of())).isZero();
    }

    @Test
    @DisplayName("every aborted run of a conversation is stopped, not just the first")
    void allInFlightRunsAreStopped() {
        UUID conversationId = aiConversation();
        UUID first = runningAiRun(conversationId);
        UUID second = runningAiRun(conversationId);
        PrincipalScope.call(admin, () ->
            aiSend.stageAiReply(admin, first, "One.", "workflow:ai.send"));
        PrincipalScope.call(admin, () ->
            aiSend.stageAiReply(admin, second, "Two.", "workflow:ai.send"));

        changeModeToHuman(conversationId);
        HumanTakeoverService.AbortResult result = PrincipalScope.call(admin, () ->
            takeOver.takeOverAfterModeChange(admin, conversationId, WorkflowFixture.PROJECT_A));

        assertThat(result.abortedRuns()).hasSize(2);
        assertThat(result.abortedRuns()).allSatisfy(aborted ->
            assertThat(aborted.confirmed()).isTrue());
        assertThat(pendingAiMessages(conversationId)).isZero();
        List<String> statuses = List.of(
            statusOf(first), statusOf(second));
        assertThat(statuses).containsOnly("TAKEOVER");
    }

    private String statusOf(UUID runId) {
        return PrincipalScope.call(admin,
            () -> runs.findRun(guard.tenantScope(admin), runId)).orElseThrow().status();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the race did not start within 10s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting to start the race", e);
        }
    }
}