package com.firstelement.channels.service;

import com.firstelement.crm.domain.ConversationMode;
import com.firstelement.crm.persistence.ConversationRepository;
import com.firstelement.crm.persistence.ConversationRow;
import com.firstelement.crm.query.CrmQueryScope;
import com.firstelement.security.TenantContext;
import com.firstelement.security.query.TenantQueryGuard;
import com.firstelement.worker.outbox.OutboxEnqueuer;
import com.firstelement.worker.outbox.OutboxIntent;
import com.firstelement.worker.queue.QueueName;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The inbound trigger for an AI turn: an accepted customer message on an AI-mode conversation enqueues
 * {@link QueueName#AI_RESPONSE}, and nothing else in this module decides that.
 *
 * <h2>Why this lives in {@code channels} and not in the AI module</h2>
 *
 * <p>{@code :backend:channels} is depended on <em>by</em> {@code :backend:ai-rag} through
 * {@code crm-core} and {@code worker-runtime}, so a dependency the other way — "channels asks the AI
 * module whether this conversation wants a run" — would be a cycle, and {@code check-architecture.ps1}
 * rule FE-ARCH-001 would refuse it. The turn is therefore triggered by the fact the producer already
 * knows, not by asking a question it cannot ask: {@code conversations."mode"} is a column on the row
 * the inbound pipeline has just written counters for, and {@link QueueName#AI_RESPONSE#wireValue()} is
 * a string in a closed enum. No {@code ai-rag} type appears anywhere in this class, and none is needed.
 *
 * <h2>Why mode is the only gate, and why it is re-read under the caller's scope</h2>
 *
 * <p>ADR 0007 Part 2 rule 1: the model may reply only in AI mode. This is the first of two reads of
 * that mode — the second happens inside {@code AiResponseJobHandler}, in the transaction that decides
 * whether the draft is offered. Neither is sufficient alone: this one avoids enqueueing work a human
 * owns, and that one refuses to <em>offer</em> the result. Reproducing the legacy guard means
 * reproducing both, because the legacy
 * {@code packages/ai/src/orchestrator/orchestrator.ts} read the mode once before the model call
 * <em>and again inside the transaction that wrote the message</em>, and the comment there is explicit
 * that "checking once at the start and trusting it is the race that lets an AI reply land after a human
 * took over (plan §10)".
 *
 * <p>The read is tenant- and project-scoped through {@link CrmQueryScope}, built from the job's own
 * resolved context. A conversation in another tenant is not "found to be HUMAN" — it is not found at
 * all, so this predicate cannot become an existence oracle for another tenant's conversations.
 *
 * <h2>Why the enqueue is idempotent on the trigger message</h2>
 *
 * <p>{@code idempotencyKey} is {@code ai-response:<messageId>} and is tenant-scoped unique with
 * {@code ON CONFLICT DO NOTHING}. A provider redelivering the same webhook is the ordinary case, not an
 * exceptional one: the inbound pipeline already answers {@code DUPLICATE} through
 * {@code messages_projectId_channel_externalMessageId_key}, and this key makes the enqueue cheap rather
 * than load-bearing. Even so it is stated, because the property the mission requires — one AI run per
 * inbound message — is enforced downstream in {@code AiResponseJobHandler} against
 * {@code ai_runs."triggerMessageId"}, not here. This key is an optimisation; that check is the guarantee.
 *
 * <h2>No answer, no message, no send</h2>
 *
 * <p>This class publishes an intent and returns. It has no reference to {@code AiSendService}, writes no
 * {@code messages} row, and creates no {@code workflow_runs} row. The consumer it feeds produces a
 * governed draft that a human must still choose to send.
 */
@Service
public class AiResponseTrigger {

    private static final Logger log = LoggerFactory.getLogger(AiResponseTrigger.class);

    /** The {@code notes."resourceType"} an AI draft is stored under. Not a message and never becomes one. */
    public static final String DRAFT_RESOURCE_TYPE = "AiDraft";

    private final ConversationRepository conversations;
    private final TenantQueryGuard guard;
    private final OutboxEnqueuer outbox;

    public AiResponseTrigger(ConversationRepository conversations,
                             TenantQueryGuard guard,
                             OutboxEnqueuer outbox) {
        this.conversations = conversations;
        this.guard = guard;
        this.outbox = outbox;
    }

    /**
     * Enqueues an AI turn for an accepted inbound message, when the conversation is in AI mode.
     *
     * <p>Must run inside the caller's transaction — the one that wrote the inbound {@code messages} row —
     * because {@link OutboxEnqueuer} refuses to publish outside one. That is the whole point of the
     * outbox: the message and the promise to answer it commit or roll back together, so a rollback can
     * never announce work for a message that does not exist.
     *
     * @param conversationId the conversation the message was accepted onto
     * @param messageId      the inbound message being answered; the idempotency key's subject
     * @return whether an intent was enqueued. {@code false} means the conversation is HUMAN-owned, is
     *         not visible in this scope, or was already queued — all three are ordinary outcomes, and
     *         none of them is an error worth failing the inbound pipeline over
     */
    public boolean triggerFor(TenantContext context,
                              UUID projectId,
                              UUID conversationId,
                              UUID messageId,
                              String correlationId) {
        if (conversationId == null || messageId == null) {
            // A message with no conversation cannot have a mode, and a message with no id cannot be
            // idempotent. Refusing loudly here beats enqueueing work nothing can dedupe.
            throw new IllegalArgumentException(
                "An AI turn needs both a conversationId and the trigger messageId");
        }

        CrmQueryScope scope = CrmQueryScope.forProject(guard, context, projectId);
        Optional<ConversationRow> found = conversations.findInScope(scope, conversationId);
        if (found.isEmpty()) {
            log.warn("AI trigger skipped: conversation {} is not visible in the inbound scope",
                conversationId);
            return false;
        }
        ConversationRow row = found.get();
        if (!ConversationMode.AI.name().equals(row.mode())) {
            // The negative case this whole gate exists for, and it is not an error: a HUMAN
            // conversation is the CRM working. A new conversation starts HUMAN precisely because a
            // project without a live agent should wait for a person.
            log.debug("AI trigger skipped: conversation {} is in {} mode", conversationId, row.mode());
            return false;
        }

        String payloadJson = "{\"conversationId\":\"" + conversationId + "\",\"triggerMessageId\":\""
            + messageId + "\"}";
        UUID outboxEventId = outbox.publish(OutboxIntent.of(context.organizationId(), projectId,
            "Conversation", conversationId, QueueName.AI_RESPONSE.wireValue(), payloadJson,
            "ai-response:" + messageId, correlationId, messageId.toString()));

        if (outboxEventId == null) {
            log.info("AI turn for message {} was already queued for tenant {}", messageId,
                context.organizationId());
            return false;
        }
        log.info("AI turn queued message={} conversation={} queue={}", messageId, conversationId,
            QueueName.AI_RESPONSE.wireValue());
        return true;
    }
}