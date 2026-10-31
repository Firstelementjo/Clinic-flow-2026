package com.firstelement.channels.api;

import com.firstelement.crm.domain.ChannelType;
import java.time.LocalDateTime;
import java.util.List;

/**
 * The normalized inbound message contract, ported from
 * {@code packages/connectors/src/types.ts} and shaped for the Phase-4 CRM service.
 *
 * <h2>{@code externalMessageId} is the correctness guarantee</h2>
 *
 * <p>FR-4.4: "Use provider event IDs and database uniqueness for inbound idempotency. Queue job
 * IDs are an optimization, not the guarantee." This record therefore carries the provider's own
 * id, and the unique index
 * {@code messages_projectId_channel_externalMessageId_key} is what actually prevents a second
 * copy. Everything else — the outbox {@code idempotencyKey}, the {@code idempotency_records}
 * reservation — makes the duplicate cheap and early, and none of them is what makes it correct.
 *
 * <h2>What is never logged</h2>
 *
 * <p>{@link #text()} is customer content. It travels through the queue and into the transcript,
 * and it must not reach a log line, a dead-letter summary or a metric tag (FR-10.6).
 *
 * @param sentAt the platform's own timestamp; UTC, because the schema has no timestamptz
 */
public record NormalizedIncomingMessage(ChannelType channel,
                                       String externalMessageId,
                                       String externalUserId,
                                       String externalConversationId,
                                       String displayName,
                                       String phone,
                                       String email,
                                       String text,
                                       List<NormalizedAttachment> attachments,
                                       LocalDateTime sentAt) {

    public NormalizedIncomingMessage {
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        sentAt = sentAt == null ? LocalDateTime.now() : sentAt;
        if (externalMessageId == null || externalMessageId.isBlank()) {
            // A provider message with no id cannot be deduplicated, and admitting it would let a
            // redelivery become a second conversation turn.
            throw new IllegalArgumentException(
                "externalMessageId is required: it is the inbound idempotency key enforced by "
                    + "messages(projectId, channel, externalMessageId)");
        }
        if (externalUserId == null || externalUserId.isBlank()) {
            throw new IllegalArgumentException("externalUserId is required: it is the identity key");
        }
        if (channel == null) {
            throw new IllegalArgumentException("channel is required");
        }
    }

    /** A stable, non-secret identity for logs and audit rows. Never the text. */
    public String auditLabel() {
        return channel.name() + ":" + externalMessageId;
    }
}