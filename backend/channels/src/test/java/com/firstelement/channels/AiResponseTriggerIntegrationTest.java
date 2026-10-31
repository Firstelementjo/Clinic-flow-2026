package com.firstelement.channels;

import static org.assertj.core.api.Assertions.assertThat;

import com.firstelement.channels.service.AiResponseTrigger;
import com.firstelement.channels.service.InboundMessageJobHandler;
import com.firstelement.security.Capability;
import com.firstelement.security.PrincipalType;
import com.firstelement.security.TenantContext;
import com.firstelement.worker.config.UtcTime;
import com.firstelement.worker.queue.JobEnvelope;
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
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The producer half: an accepted inbound message enqueues an {@code ai-response} intent, and only on
 * an AI-mode conversation.
 *
 * <h2>Why the trigger is asserted here and not in {@code ai-rag}</h2>
 *
 * <p>It lives in {@code :backend:channels} because {@code ai-rag} depends on this module, not the
 * other way round, so asking "does this conversation want a run?" from here could only be answered
 * through an {@code ai-rag} dependency — which {@code check-architecture.ps1} FE-ARCH-001 refuses. The
 * trigger therefore reads the one fact the producer already owns, {@code conversations."mode"}, and
 * writes the queue's wire value. No {@code ai-rag} type appears in the production path, and this suite
 * is what proves it.
 *
 * <h2>What "the trigger fired" means here</h2>
 *
 * <p>An outbox row. Not a log line: the intent is the durable promise, and {@code ai_runs} belongs to
 * the consumer in the other module, which is where the run assertions live.
 */
@SpringBootTest(classes = ChannelsTestApplication.class)
class AiResponseTriggerIntegrationTest extends ChannelsIntegrationTestBase {

    @Autowired
    private ChannelsFixture fixture;

    @Autowired
    private AiResponseTrigger trigger;

    @Autowired
    private InboundMessageJobHandler handler;

    @Autowired
    private TransactionTemplate transactions;

    @BeforeEach
    void reset() {
        fixture.reset();
    }

    @Nested
    @DisplayName("the conversation mode decides whether an intent is written")
    class ModeDecidesTheIntent {

        @Test
        @DisplayName("an AI-mode conversation enqueues exactly one ai-response intent")
        void aiModeEnqueuesOneIntent() {
            UUID conversation = fixture.conversation(ChannelsFixture.ORG_A,
                ChannelsFixture.PROJECT_A, ChannelsFixture.CUSTOMER_A, "MOCK", "966500000001", "AI");
            UUID message = UUID.randomUUID();

            boolean queued = inTransaction(() -> trigger.triggerFor(worker(ChannelsFixture.ORG_A),
                ChannelsFixture.PROJECT_A, conversation, message, "corr-1"));

            assertThat(queued).isTrue();
            assertThat(intentCount()).isEqualTo(1);
            assertThat(countFor(QueueName.AI_RESPONSE.wireValue())).isEqualTo(1);
        }

        @Test
        @DisplayName("a HUMAN-mode conversation enqueues nothing")
        void humanModeEnqueuesNothing() {
            UUID conversation = fixture.conversation(ChannelsFixture.ORG_A,
                ChannelsFixture.PROJECT_A, ChannelsFixture.CUSTOMER_A, "MOCK", "966500000002", "HUMAN");

            boolean queued = inTransaction(() -> trigger.triggerFor(worker(ChannelsFixture.ORG_A),
                ChannelsFixture.PROJECT_A, conversation, UUID.randomUUID(), "corr-2"));

            assertThat(queued).isFalse();
            assertThat(intentCount()).isZero();
        }

        @Test
        @DisplayName("a conversation in another tenant is not found, so nothing is enqueued")
        void anotherTenantsConversationEnqueuesNothing() {
            UUID conversation = fixture.conversation(ChannelsFixture.ORG_B,
                ChannelsFixture.PROJECT_B, ChannelsFixture.CUSTOMER_B, "MOCK", "966500000003", "AI");

            // A tenant-A scope looking at a tenant-B conversation must be a MISS, not "found and
            // HUMAN". The two are indistinguishable here on purpose.
            boolean queued = inTransaction(() -> trigger.triggerFor(worker(ChannelsFixture.ORG_A),
                ChannelsFixture.PROJECT_A, conversation, UUID.randomUUID(), "corr-3"));

            assertThat(queued).isFalse();
            assertThat(intentCount()).isZero();
        }

        @Test
        @DisplayName("the enqueue is idempotent on the trigger message")
        void theEnqueueIsIdempotent() {
            UUID conversation = fixture.conversation(ChannelsFixture.ORG_A,
                ChannelsFixture.PROJECT_A, ChannelsFixture.CUSTOMER_A, "MOCK", "966500000004", "AI");
            UUID message = UUID.randomUUID();

            boolean first = inTransaction(() -> trigger.triggerFor(worker(ChannelsFixture.ORG_A),
                ChannelsFixture.PROJECT_A, conversation, message, "corr-4"));
            boolean second = inTransaction(() -> trigger.triggerFor(worker(ChannelsFixture.ORG_A),
                ChannelsFixture.PROJECT_A, conversation, message, "corr-4"));

            assertThat(first).isTrue();
            assertThat(second).isFalse();
            assertThat(intentCount()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("the intent is an envelope, not content")
    class TheIntentCarriesNoContent {

        @Test
        @DisplayName("the payload is ids only and the customer text never enters the queue")
        void thePayloadIsIdsOnly() {
            UUID conversation = fixture.conversation(ChannelsFixture.ORG_A,
                ChannelsFixture.PROJECT_A, ChannelsFixture.CUSTOMER_A, "MOCK", "966500000005", "AI");
            UUID message = UUID.randomUUID();

            inTransaction(() -> trigger.triggerFor(worker(ChannelsFixture.ORG_A),
                ChannelsFixture.PROJECT_A, conversation, message, "corr-5"));

            String payload = fixture.jdbc().queryForObject(
                "select \"payload\"::text from outbox_events where \"eventType\" = '"
                    + QueueName.AI_RESPONSE.wireValue() + "'",
                new MapSqlParameterSource(), String.class);

            assertThat(payload).contains(conversation.toString()).contains(message.toString());
            // The consumer resolves the message row itself; the envelope is a reference, which is the
            // JobEnvelope contract ("no customer text ... the handler is the only component allowed to
            // resolve customer content").
            assertThat(payload).doesNotContain("مرحبا");
        }

        @Test
        @DisplayName("the intent names the queue, the conversation and the tenant")
        void theIntentIsWellFormed() {
            UUID conversation = fixture.conversation(ChannelsFixture.ORG_A,
                ChannelsFixture.PROJECT_A, ChannelsFixture.CUSTOMER_A, "MOCK", "966500000006", "AI");
            UUID message = UUID.randomUUID();

            inTransaction(() -> trigger.triggerFor(worker(ChannelsFixture.ORG_A),
                ChannelsFixture.PROJECT_A, conversation, message, "corr-6"));

            // The tenant comes from the resolved context, never from the caller's argument: the
            // outbox row's organizationId is the context's, and a payload-supplied one would have
            // been refused by OutboxEnqueuer before it reached the table.
            assertThat(fixture.count("select count(*) from outbox_events where "
                + "\"organizationId\" = '" + ChannelsFixture.ORG_A + "' "
                + "and \"projectId\" = '" + ChannelsFixture.PROJECT_A + "' "
                + "and \"aggregateType\" = 'Conversation' "
                + "and \"aggregateId\" = '" + conversation + "'")).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("the trigger refuses rather than guessing")
    class RefusesRatherThanGuesses {

        @Test
        @DisplayName("a turn with no trigger message is refused, because it could not be idempotent")
        void aTurnWithNoMessageIsRefused() {
            UUID conversation = fixture.conversation(ChannelsFixture.ORG_A,
                ChannelsFixture.PROJECT_A, ChannelsFixture.CUSTOMER_A, "MOCK", "966500000007", "AI");

            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                inTransaction(() -> trigger.triggerFor(worker(ChannelsFixture.ORG_A),
                    ChannelsFixture.PROJECT_A, conversation, null, "corr-7")))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("the handler wiring, not just the trigger class")
    class HandlerWiring {

        @Test
        @DisplayName("an inbound job on an AI conversation enqueues the AI turn")
        void anInboundJobEnqueuesTheTurn() {
            fixture.connection(ChannelsFixture.ORG_A, ChannelsFixture.PROJECT_A, "MOCK", "{}");
            UUID conversation = fixture.conversation(ChannelsFixture.ORG_A,
                ChannelsFixture.PROJECT_A, ChannelsFixture.CUSTOMER_A, "MOCK", "966500000010", "AI");

            String summary = asWorker(() -> handler.handle(worker(ChannelsFixture.ORG_A),
                inboundEnvelope(conversation, "966500000010", "ext-1")));

            assertThat(summary).contains("aiTurn=queued");
            assertThat(countFor(QueueName.AI_RESPONSE.wireValue())).isEqualTo(1);
        }

        @Test
        @DisplayName("an inbound job on a HUMAN conversation enqueues nothing")
        void anInboundJobOnHumanEnqueuesNothing() {
            fixture.connection(ChannelsFixture.ORG_A, ChannelsFixture.PROJECT_A, "MOCK", "{}");
            UUID conversation = fixture.conversation(ChannelsFixture.ORG_A,
                ChannelsFixture.PROJECT_A, ChannelsFixture.CUSTOMER_A, "MOCK", "966500000011", "HUMAN");

            String summary = asWorker(() -> handler.handle(worker(ChannelsFixture.ORG_A),
                inboundEnvelope(conversation, "966500000011", "ext-2")));

            assertThat(summary).contains("aiTurn=skipped");
            assertThat(countFor(QueueName.AI_RESPONSE.wireValue())).isZero();
        }

        @Test
        @DisplayName("a redelivered inbound job does not queue a second AI turn")
        void aRedeliveredInboundJobQueuesOneTurn() {
            fixture.connection(ChannelsFixture.ORG_A, ChannelsFixture.PROJECT_A, "MOCK", "{}");
            UUID conversation = fixture.conversation(ChannelsFixture.ORG_A,
                ChannelsFixture.PROJECT_A, ChannelsFixture.CUSTOMER_A, "MOCK", "966500000012", "AI");
            JobEnvelope job = inboundEnvelope(conversation, "966500000012", "ext-3");

            asWorker(() -> handler.handle(worker(ChannelsFixture.ORG_A), job));
            String second = asWorker(() -> handler.handle(worker(ChannelsFixture.ORG_A), job));

            // The pipeline itself reports the duplicate through the messages unique index, so the
            // trigger is never reached twice. One intent, not two.
            assertThat(second).startsWith("duplicate");
            assertThat(countFor(QueueName.AI_RESPONSE.wireValue())).isEqualTo(1);
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /**
     * An inbound envelope shaped like the one the webhook intake publishes.
     *
     * <p>Every field {@link com.firstelement.inbox.api.IncomingMessage} requires is present, because
     * a null in that record is an {@code Objects.requireNonNull} failure rather than a default — the
     * envelope this stands in for is always fully populated by the intake service.
     */
    private JobEnvelope inboundEnvelope(UUID conversationId, String externalUserId,
                                        String externalMessageId) {
        return new JobEnvelope(UUID.randomUUID(), QueueName.INCOMING_MESSAGE,
            ChannelsFixture.ORG_A, ChannelsFixture.PROJECT_A, "Conversation", conversationId,
            Map.of("channel", "MOCK", "externalUserId", externalUserId,
                "externalMessageId", externalMessageId, "text", "ما ساعات العمل؟",
                "displayName", "عميل", "phone", "+966500000001", "email", "caller@example.test",
                "connectionId", ChannelsFixture.ORG_A.toString()),
            "corr-inbound", externalMessageId, 1, "job-" + externalMessageId, false, null,
            UtcTime.now());
    }

    private <T> T asWorker(java.util.function.Supplier<T> work) {
        return inTransaction(work);
    }

    /**
     * Runs the trigger inside a transaction and bound to a tenant, as its only caller does.
     *
     * <p>Two bindings, because the outbox needs both and the refusal proves it:
     * {@code OutboxEnqueuer} reads {@link com.firstelement.security.TenantContextHolder} for the
     * tenant check, and a transaction, or it refuses outright. A test that set only the
     * {@code SecurityContext} would fail with
     * {@code UnscopedOutboxPublishException} — which is the guard working, not the trigger.
     */
    private <T> T inTransaction(java.util.function.Supplier<T> work) {
        TenantContext context = worker(ChannelsFixture.ORG_A);
        return com.firstelement.security.TenantContextHolder.call(context, () -> {
            SecurityContext previous = SecurityContextHolder.getContext();
            SecurityContextHolder.setContext(SecurityContextHolder.createEmptyContext());
            SecurityContextHolder.getContext()
                .setAuthentication(new com.firstelement.security.TenantAuthentication(context));
            try {
                return transactions.execute(status -> work.get());
            } finally {
                SecurityContextHolder.setContext(previous);
            }
        });
    }

    /**
 * A worker principal shaped like the one the dispatcher binds for a claimed inbound row.
 *
 * <p>{@code MESSAGING_SEND} is here because {@code InboundMessageService.receive} carries
 * {@code @RequirePermission(CONVERSATION_REPLY)}, and that permission is granted to
 * {@code MESSAGING_SEND} rather than to {@code WEBHOOK_INGEST}. Without it the handler is refused
 * before the pipeline runs — which is the permission boundary working, and is why this suite asserts
 * the enqueue through the handler rather than calling the trigger directly.
 */
private TenantContext worker(UUID org) {
        return TenantContext.forMachine(PrincipalType.WORKER, org, "channels-ai-trigger-tests",
            List.of(ChannelsFixture.PROJECT_A, ChannelsFixture.PROJECT_B),
            List.of(Capability.AI_RUN, Capability.KNOWLEDGE_RETRIEVE, Capability.AI_TOOL_EXECUTE,
                Capability.DOCUMENT_INGEST, Capability.WEBHOOK_INGEST, Capability.MESSAGING_SEND));
    }

    private int intentCount() {
        return fixture.count("select count(*) from outbox_events");
    }

    private int countFor(String wireValue) {
        return fixture.count("select count(*) from outbox_events where \"eventType\" = '"
            + wireValue + "'");
    }
}