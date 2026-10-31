package com.firstelement.inbox.api;

import com.firstelement.crm.domain.ChannelType;
import java.util.Map;

/**
 * One normalized inbound customer message, as a connector hands it over.
 *
 * <p>Mirrors {@code NormalizedIncomingMessage} from {@code packages/connectors}. Phase 5's
 * webhook path produces exactly this shape; the pipeline below is connector-agnostic, which is
 * what keeps one implementation of "what happens when a customer writes in" instead of one per
 * channel.
 *
 * @param externalUserId     the platform's handle for the sender; the identity key
 * @param externalMessageId  the platform's message id; the inbound idempotency key, enforced by
 *                           {@code messages(projectId, channel, externalMessageId)}
 * @param sentAt             when the platform says the message was sent
 */
public record IncomingMessage(String externalUserId,
                              String text,
                              String displayName,
                              String phone,
                              String email,
                              String externalMessageId,
                              ChannelType channel,
                              Map<String, Object> attachments,
                              Map<String, Object> metadata,
                              java.time.LocalDateTime sentAt) {

    public IncomingMessage {
        attachments = attachments == null ? Map.of() : Map.copyOf(attachments);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        sentAt = sentAt == null ? java.time.LocalDateTime.now() : sentAt;
    }
}