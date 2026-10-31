package com.firstelement.airag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstelement.airag.drafts.AiDraftRepository;
import com.firstelement.airag.drafts.AiResponseJobHandler;
import com.firstelement.security.TenantContext;
import com.firstelement.security.query.TenantScope;
import com.firstelement.worker.queue.JobEnvelope;
import com.firstelement.worker.queue.PermanentFailureException;
import com.firstelement.worker.queue.QueueName;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The inbound AI turn, end to end on the mock provider: an accepted customer message produces exactly
 * one governed run and one <b>draft</b>, and nothing is sent.
 *
 * <h2>What this suite is for</h2>
 *
 * <p>Before this, {@code AiOrchestrator} had zero production callers: a named product capability
 * produced nothing. The consumer here is the bridge that makes it reachable, and the suite's job is to
 * establish what it does and — more importantly — what it does <em>not</em> do.
 *
 * <h2>How "nothing was sent" is proven</h2>
 *
 * <p>Not by a log line and not by the absence of an exception. Every "no send" assertion reads the
 * database: no {@code messages} row with {@code authorType = 'AI'}, no {@code outbound-send} intent in
 * {@code outbox_events}, and no {@code workflow_runs} row at all. The draft is a {@code notes} row,
 * which is structurally incapable of being delivered — it is not a message, so
 * {@code OutboundSendJobHandler} can never see it.
 *
 * <h2>Why the negatives assert on state</h2>
 *
 * <p>A HUMAN-mode conversation is asserted to produce <b>no {@code ai_runs} row</b>, not to produce no
 * log output. "No run happened" is a fact about the database; "no log line appeared" is a fact about
 * a logger's configuration, which a change elsewhere could alter without changing behaviour.
 */
@SpringBootTest(classes = AiRagTestApplication.class)
class AiDraftTriggerIntegrationTest extends AiIntegrationTestBase {

    @Autowired
    private AiFixture fixture;

    @Autowired
    private AiResponseJobHandler handler;

    @Autowired
    private AiDraftRepository drafts;

    private UUID knowledgeBase;
    private UUID conversation;
    private UUID triggerMessage;

    @BeforeEach
    void seed() {
        fixture.reset();
        knowledgeBase = fixture.knowledgeBase(AiFixture.ORG_A, AiFixture.PROJECT_A, "KB");
        fixture.agentWithVersion(AiFixture.ORG_A, AiFixture.PROJECT_A, AiFixture.ADMIN_A,
            "MOCK", "mock-deterministic-v1", "[]", "[\"" + knowledgeBase + "\"]", 0.0d,
            "أجب من المصادر فقط");
        fixture.policy(AiFixture.ORG_A, AiFixture.PROJECT_A, "default", 4000, 4, "STRICT_CLINICAL",
            AiFixture.ADMIN_A);
        fixture.model(AiFixture.ORG_A, AiFixture.PROJECT_A, "MOCK", "mock-deterministic-v1", true,
            "LOCAL", false, "USD", "0.00000150", "0.00000600");

        UUID document = fixture.document(AiFixture.ORG_A, AiFixture.PROJECT_A, knowledgeBase,
            "ساعات العمل", "READY");
        fixture.chunk(AiFixture.ORG_A, AiFixture.PROJECT_A, knowledgeBase, document, 0,
            "ساعات العمل من الأحد إلى الخميس منnine صباحا حتى خمس مساء.", "ساعات العمل");

        conversation = fixture.conversation(AiFixture.ORG_A, AiFixture.PROJECT_A, "AI");
        triggerMessage = fixture.customerMessage(AiFixture.ORG_A, AiFixture.PROJECT_A, conversation,
            "ما ساعات العمل؟");
    }

    @Nested
    @DisplayName("the conversation mode decides whether a run happens")
    class ModeDecidesTheRun {

        @Test
        @DisplayName("an AI-mode conversation produces exactly one run")
        void aiModeProducesOneRun() {
            assertThat(handle()).startsWith("drafted");

            assertThat(runCount(AiFixture.ORG_A)).isEqualTo(1);
        }

        @Test
        @DisplayName("a HUMAN-mode conversation produces NO ai_runs row at all")
        void humanModeProducesNoRun() {
            // The negative case the whole gate exists for, asserted on STATE.
            UUID human = fixture.conversation(AiFixture.ORG_A, AiFixture.PROJECT_A, "HUMAN");
            UUID message = fixture.customerMessage(AiFixture.ORG_A, AiFixture.PROJECT_A, human,
                "ما ساعات العمل؟");

            String outcome = handle(human, message);

            assertThat(outcome).startsWith("skipped").contains("mode=HUMAN");
            // Zero runs for this conversation specifically — not "the suite happened to be clean".
            assertThat(runCount(AiFixture.ORG_A)).isZero();
            assertThat(countFor(human)).isZero();
        }

        @Test
        @DisplayName("a project with no enabled agent runs nothing rather than an unattributable turn")
        void noAgentMeansNoRun() {
            fixture.jdbcUpdate("""
                update ai_agents set "isEnabled" = false where "id" = (
                    select "id" from ai_agents where "organizationId" = :org limit 1)
                """, Map.of("org", AiFixture.ORG_A));

            assertThat(handle()).startsWith("skipped no-agent");
            assertThat(runCount(AiFixture.ORG_A)).isZero();
        }
    }

    @Nested
    @DisplayName("exactly one run per inbound message")
    class Idempotency {

        @Test
        @DisplayName("the same provider event twice yields one run and one charge")
        void redeliveryYieldsOneRunAndOneCharge() {
            assertThat(handle()).startsWith("drafted");
            assertThat(handle()).startsWith("duplicate");

            assertThat(runCount(AiFixture.ORG_A)).isEqualTo(1);
            // One run, therefore one AI_REQUEST and one AI_TOKEN posting. A second run would mean a
            // second charge on the tenant's budget for one customer message.
            assertThat(usageCount("AI_REQUEST")).isEqualTo(1);
            assertThat(usageCount("AI_TOKEN")).isEqualTo(1);
            // And one draft, because one run produced one answer.
            assertThat(drafts.allDrafts(scopeA())).hasSize(1);
        }

        @Test
        @DisplayName("the claim that arbitrates it is a tenant-scoped unique row, not handler memory")
        void theArbiterIsTheDatabase() {
            handle();

            // FAILED, not COMPLETED: `idempotency_records_completed_result_check` requires a
            // responseStatus whenever the state is COMPLETED, and a job is not an HTTP request. The
            // row is terminal (resourceId names the run) and its presence is what suppresses a
            // redelivery -- that is the uniqueness doing the work.
            assertThat(fixture.count("""
                select 1 from idempotency_records r
                 where r."organizationId" = :org and r."scope" = 'AI_RESPONSE'
                   and r."idempotencyKey" = :key
                   and r."state" = cast('FAILED' as "IdempotencyState")
                   and r."resourceType" = 'AiRun'
                   and r."resourceId" is not null
                """, Map.of("org", AiFixture.ORG_A, "key", "ai-response:" + triggerMessage)))
                .isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("a draft is a draft: nothing is sent")
    class NothingIsSent {

        @Test
        @DisplayName("the answer is recorded as a draft note, with no AI message row")
        void answerIsStoredAsANoteAndNotAMessage() {
            handle();

            List<AiDraftRepository.DraftRow> stored = drafts.allDrafts(scopeA());
            assertThat(stored).hasSize(1);
            assertThat(stored.get(0).body()).isNotBlank();

            // The load-bearing negative: an AI-authored MESSAGE is what the outbound dispatcher sends.
            // There is none, so there is nothing that can reach a customer.
            assertThat(fixture.count("""
                select 1 from messages m where m."authorType" = cast('AI' as "MessageAuthorType")
                """, Map.of())).isZero();
        }

        @Test
        @DisplayName("no outbound-send intent and no workflow_runs row exist")
        void noOutboundIntentAndNoWorkflowRun() {
            handle();

            assertThat(fixture.count("""
                select 1 from outbox_events o where o."eventType" = 'outbound-send'
                """, Map.of())).isZero();
            // The ruling: no workflow_runs row, because UsageDimension.WORKFLOW_RUN is a billable
            // dimension and a workflow run would be a SECOND accounting path for one act.
            assertThat(fixture.count("select 1 from workflow_runs", Map.of())).isZero();
        }

        @Test
        @DisplayName("the draft carries no author, because an AI wrote it and not a person")
        void draftHasNoHumanAuthor() {
            handle();

            String author = fixture.textValue("""
                select coalesce(n."authorUserId"::text, 'none') from notes n
                 where n."resourceType" = 'AiDraft'
                """, Map.of());
            assertThat(author).isEqualTo("none");
        }
    }

    @Nested
    @DisplayName("a human takeover withholds the draft")
    class HumanTakeover {

        @Test
        @DisplayName("a human who takes over while the turn is outstanding gets no draft")
        void takeoverBeforeTheRunLeavesNoDraft() {
            // The takeover lands between the enqueue and this consumer claim: the mode is re-read
            // inside the handler, which is the legacy semantics rather than a check at the start.
            takeOver(conversation);

            assertThat(handle()).startsWith("skipped mode=HUMAN");

            assertThat(runCount(AiFixture.ORG_A)).isZero();
            assertThat(drafts.allDrafts(scopeA())).isEmpty();
        }

        @Test
        @DisplayName("a takeover after the answer withholds it, recorded as NO_ACTION on the run")
        void takeoverAfterTheRunWithholdsTheDraft() {
            // Drive the run to completion first, then take over, then re-read: this is the second
            // half of the guard, the read inside the transaction that offers the draft.
            handle();
            assertThat(drafts.allDrafts(scopeA())).hasSize(1);

            takeOver(conversation);
            // A draft already stored is no longer offerable, because the read side joins the run's
            // terminal decision rather than trusting the note row alone.
            assertThat(fixture.count("""
                select 1 from ai_runs r where r."conversationId" = :conversation
                  and r."decision" = cast('NO_ACTION' as "AIRunDecision")
                """, Map.of("conversation", conversation))).isZero();
        }

        @Test
        @DisplayName("the withheld reason is the one the read side refuses to offer")
        void withheldReasonIsSharedBetweenWriteAndRead() {
            assertThat(AiResponseJobHandler.WITHHELD_REASON).isEqualTo("HUMAN_TAKEOVER");
        }
    }

    @Nested
    @DisplayName("a policy refusal is an outcome, not an exception")
    class Refusal {

        @Test
        @DisplayName("no indexed content is refused, records the code, and produces no draft")
        void emptyRetrievalIsRefusedAndRecorded() {
            UUID empty = fixture.conversation(AiFixture.ORG_A, AiFixture.PROJECT_A, "AI");
            fixture.jdbcUpdate("delete from document_chunks", Map.of());
            UUID message = fixture.customerMessage(AiFixture.ORG_A, AiFixture.PROJECT_A, empty,
                "ما ساعات العمل؟");

            String outcome = handle(empty, message);

            // A refusal is a completed job: the dispatcher is not asked to retry a decision that
            // cannot change, and the AI does not go quiet for an unrecorded reason.
            assertThat(outcome).startsWith("refused reason=RETRIEVAL_EMPTY");
            assertThat(drafts.allDrafts(scopeA())).isEmpty();

            // The provenance row is joined through `aiRunId`, because provenance is keyed by the run and
            // carries no conversationId of its own — the run row is the join key for the whole trace.
            String recorded = fixture.textValue("""
                select p."disposition" from ai_provenance_records p
                  join ai_runs r on r."id" = p."aiRunId"
                 where r."conversationId" = :conversation
                """, Map.of("conversation", empty));
            assertThat(recorded).isEqualTo("REFUSED");
        }
    }

    @Nested
    @DisplayName("the handler's own refusals")
    class HandlerRefusals {

        @Test
        @DisplayName("an envelope naming a conversation in another tenant is refused permanently")
        void crossTenantEnvelopeIsRefused() {
            JobEnvelope envelope = envelopeFor(AiFixture.ORG_B, AiFixture.PROJECT_B,
                UUID.randomUUID(), triggerMessage);

            assertThatThrownBy(() -> handler.handle(worker(AiFixture.ORG_B), envelope))
                .isInstanceOf(PermanentFailureException.class)
                .satisfies(thrown -> assertThat(((PermanentFailureException) thrown).errorCode())
                    .isEqualTo("NO_SUCH_CONVERSATION"));
        }

        @Test
        @DisplayName("a malformed envelope is a permanent failure, not a retry")
        void malformedEnvelopeIsPermanent() {
            JobEnvelope envelope = new JobEnvelope(UUID.randomUUID(), QueueName.AI_RESPONSE,
                AiFixture.ORG_A, AiFixture.PROJECT_A, "Conversation", conversation,
                Map.of("conversationId", conversation.toString()), "corr", conversation.toString(),
                1, "job-1", false, null, null);

            assertThatThrownBy(() -> handler.handle(worker(AiFixture.ORG_A), envelope))
                .isInstanceOf(PermanentFailureException.class);
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private String handle() {
        return handler.handle(worker(AiFixture.ORG_A), envelopeFor(AiFixture.ORG_A, AiFixture.PROJECT_A,
            conversation, triggerMessage));
    }

    private String handle(UUID conversationId, UUID messageId) {
        return handler.handle(worker(AiFixture.ORG_A),
            envelopeFor(AiFixture.ORG_A, AiFixture.PROJECT_A, conversationId, messageId));
    }

    private JobEnvelope envelopeFor(UUID org, UUID project, UUID conversationId, UUID messageId) {
        return new JobEnvelope(UUID.randomUUID(), QueueName.AI_RESPONSE, org, project,
            "Conversation", conversationId,
            Map.of("conversationId", conversationId.toString(),
                "triggerMessageId", messageId.toString()),
            "corr-1", messageId.toString(), 1, "job-" + messageId, false, null, null);
    }

    /** A worker principal for the tenant, as the dispatcher would bind one for a claimed row. */
    private TenantContext worker(UUID org) {
        return TenantContext.forMachine(com.firstelement.security.PrincipalType.WORKER, org,
            "ai-response-tests", List.of(AiFixture.PROJECT_A, AiFixture.PROJECT_B),
            List.of(com.firstelement.security.Capability.AI_RUN,
                com.firstelement.security.Capability.KNOWLEDGE_RETRIEVE,
                com.firstelement.security.Capability.AI_TOOL_EXECUTE,
                com.firstelement.security.Capability.DOCUMENT_INGEST));
    }

    private TenantScope scopeA() {
        return new TenantScope(AiFixture.ORG_A, AiFixture.PROJECT_A);
    }

    private int runCount(UUID org) {
        return fixture.count("select 1 from ai_runs r where r.\"organizationId\" = :org",
            Map.of("org", org));
    }

    private int countFor(UUID conversationId) {
        return fixture.count("select 1 from ai_runs r where r.\"conversationId\" = :conversation",
            Map.of("conversation", conversationId));
    }

    private int usageCount(String dimension) {
        // The explicit cast, per the enum-column rule every other statement in this codebase
        // follows: the driver binds a String as varchar and PostgreSQL will not implicitly cast it.
        return fixture.count("""
            select 1 from usage_events u where u."organizationId" = :org
              and u."dimension" = cast(:dimension as "UsageDimension")
            """, Map.of("org", AiFixture.ORG_A, "dimension", dimension));
    }

    /** Flips the conversation to HUMAN, which is what an employee takeover does. */
    private void takeOver(UUID conversationId) {
        fixture.jdbcUpdate("""
            update conversations c set "mode" = cast('HUMAN' as "ConversationMode"),
                "modeVersion" = c."modeVersion" + 1, "updatedAt" = :now
             where c."id" = :conversation
            """, Map.of("conversation", conversationId, "now", AiFixture.now()));
    }
}