package com.firstelement.channels.service;

import com.firstelement.channels.api.NormalizedIncomingMessage;
import com.firstelement.crm.domain.ChannelType;
import com.firstelement.inbox.api.IncomingMessage;
import com.firstelement.security.TenantContext;
import com.firstelement.worker.queue.JobEnvelope;
import com.firstelement.worker.queue.JobHandler;
import com.firstelement.worker.queue.PermanentFailureException;
import com.firstelement.worker.queue.QueueName;
import com.firstelement.worker.queue.RetryDecision;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runs the Phase-4 inbound pipeline for one queued provider event.
 *
 * <h2>Idempotency here is the database's, not this class's</h2>
 *
 * <p>{@code InboundMessageService.receive} already ends with
 * {@code messages.insertInbound} and lets
 * {@code messages_projectId_channel_externalMessageId_key} arbitrate duplicates. That is the
 * guarantee; the provider-event reservation and the outbox {@code idempotencyKey} only make the
 * duplicate cheap. So this handler's failure mode is the honest one: if the pipeline reports a
 * duplicate, the job succeeded — nothing was lost and nothing was written twice.
 *
 * <h2>No provider call happens here</h2>
 *
 * <p>Everything downstream of a verified webhook is database work, which is what keeps the helpdesk
 * usable when a provider is down (PROJECT_REQUIREMENTS' primary product rule). A failure here is a
 * database failure, so the classifier sees an {@code SQLException} and decides correctly.
 *
 * <h2>Why this method is transactional</h2>
 *
 * <p>Because the AI trigger publishes an outbox intent, and {@code OutboxEnqueuer} refuses to write one
 * outside a transaction. That refusal is the outbox working: the inbound {@code messages} row and the
 * promise to answer it must commit or roll back together, or a rollback would announce work for a
 * message that does not exist. {@code InboundMessageService.receive} opens its own transaction and
 * joins this one, so the enqueue is inside the same commit boundary as the insert that justified it.
 *
 * <p>A duplicate is <b>not</b> an AI trigger point. The message is already stored and a turn for it was
 * either already queued or already consumed, so the duplicate branch returns before the trigger runs —
 * which is the first of the two halves of "exactly one AI run per inbound message". The second half is
 * the {@code ai_runs."triggerMessageId"} check in {@code AiResponseJobHandler}, which is what actually
 * arbitrates, because this handler can be re-delivered after the enqueue committed.
 */
@Component
public class InboundMessageJobHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(InboundMessageJobHandler.class);

    private final com.firstelement.inbox.service.InboundMessageService inbound;
    private final AiResponseTrigger aiTrigger;

    public InboundMessageJobHandler(com.firstelement.inbox.service.InboundMessageService inbound,
                                    AiResponseTrigger aiTrigger) {
        this.inbound = inbound;
        this.aiTrigger = aiTrigger;
    }

    @Override
    public QueueName queue() {
        return QueueName.INCOMING_MESSAGE;
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public String handle(TenantContext context, JobEnvelope job) {
        IncomingMessage message = read(job);
        UUID connectionId = job.payloadUuid("connectionId");

        var result = inbound.receive(context, job.projectId(), connectionId, message);
        if (result.status() == com.firstelement.inbox.api.InboundResult.Status.DUPLICATE) {
            // Not an error. Providers redeliver, and recording the distinction keeps it visible
            // instead of looking like a lost message. Returns BEFORE the trigger: the message is
            // already stored, so a turn for it was already queued.
            log.info("inbound job was a duplicate channel={} externalMessageId={}",
                job.payloadText("channel"), job.payloadText("externalMessageId"));
            return "duplicate conversation=" + result.conversationId();
        }

        // The AI turn is enqueued only for an accepted message on an AI-mode conversation, and only
        // inside this transaction. `false` means HUMAN mode, out of scope, or already queued — all
        // ordinary. It is never allowed to fail the inbound pipeline: losing a customer's message
        // because a draft could not be queued would trade a real defect for a worse one.
        boolean queued;
        try {
            queued = aiTrigger.triggerFor(context, job.projectId(), result.conversationId(),
                result.messageId(), job.correlationId());
        } catch (RuntimeException e) {
            log.error("AI turn could not be queued for message {} conversation={} reason={}",
                result.messageId(), result.conversationId(), e.getClass().getSimpleName());
            queued = false;
        }

        log.info("inbound job processed channel={} externalMessageId={} conversation={} created={} aiTurn={}",
            job.payloadText("channel"), job.payloadText("externalMessageId"),
            result.conversationId(), result.conversationCreated(), queued ? "queued" : "skipped");
        return "conversation=" + result.conversationId()
            + " message=" + result.messageId()
            + " customer=" + result.customerId()
            + " created=" + result.conversationCreated()
            + " aiTurn=" + (queued ? "queued" : "skipped");
    }

    /**
     * Reads the inbound envelope.
     *
     * <p>A malformed payload is a {@link PermanentFailureException}: the provider will send the
     * same bytes again, so retrying would spend the tenant's attempt budget five times to reach the
     * same dead letter.
     */
    private static IncomingMessage read(JobEnvelope job) {
        ChannelType channel = channel(job);
        String externalUserId = required(job, "externalUserId");
        String externalMessageId = required(job, "externalMessageId");
        String text = job.payloadText("text");
        if (text == null) {
            text = "";
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "outbox:" + job.queue().wireValue());
        metadata.put("aggregateId", job.aggregateId() == null ? "" : job.aggregateId().toString());
        metadata.put("displayName", job.payloadText("displayName"));

        Map<String, Object> attachments = new LinkedHashMap<>();
        Object rawAttachments = job.payload().get("attachments");
        if (rawAttachments instanceof List<?> list) {
            List<Map<String, Object>> normalized = new ArrayList<>();
            for (Object element : list) {
                if (element instanceof Map<?, ?> map) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    map.forEach((key, value) -> entry.put(String.valueOf(key), value));
                    normalized.add(entry);
                }
            }
            metadata.put("attachments", normalized);
        }

        return new IncomingMessage(externalUserId, text, job.payloadText("displayName"),
            job.payloadText("phone"), job.payloadText("email"), externalMessageId, channel,
            attachments, metadata, sentAt(job));
    }

    private static ChannelType channel(JobEnvelope job) {
        String value = required(job, "channel");
        try {
            return ChannelType.fromValue(value);
        } catch (IllegalArgumentException e) {
            throw new PermanentFailureException(RetryDecision.INVALID_PAYLOAD, "UNKNOWN_CHANNEL",
                "the inbound envelope names a channel that is not declared: " + value);
        }
    }

    private static String required(JobEnvelope job, String field) {
        String value = job.payloadText(field);
        if (value == null || value.isBlank()) {
            throw new PermanentFailureException(RetryDecision.INVALID_PAYLOAD, "MISSING_FIELD",
                "the inbound envelope is missing the required field '" + field + "'");
        }
        return value;
    }

    private static LocalDateTime sentAt(JobEnvelope job) {
        String value = job.payloadText("sentAt");
        if (value == null || value.isBlank()) {
            return LocalDateTime.now();
        }
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException e) {
            // A provider timestamp this module cannot read is not a reason to lose the message;
            // arrival time is the honest fallback and the raw value stays in the payload.
            return LocalDateTime.now();
        }
    }

    /** The normalized shape the connector produced, for the read models. */
    public static NormalizedIncomingMessage describe(JobEnvelope job) {
        return new NormalizedIncomingMessage(channel(job), required(job, "externalMessageId"),
            required(job, "externalUserId"), job.payloadText("externalConversationId"),
            job.payloadText("displayName"), job.payloadText("phone"), job.payloadText("email"),
            job.payloadText("text"), List.of(), sentAt(job));
    }
}