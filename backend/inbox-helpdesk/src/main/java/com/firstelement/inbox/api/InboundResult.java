package com.firstelement.inbox.api;

import java.util.UUID;

/**
 * The result of processing one inbound message.
 *
 * @param status              {@code PROCESSED} or {@code DUPLICATE}
 * @param conversationCreated {@code true} when this message started a new thread
 * @param externalMessageId   the provider's id, echoed on a duplicate so the caller can log it
 */
public record InboundResult(Status status,
                            UUID conversationId,
                            UUID customerId,
                            UUID messageId,
                            boolean conversationCreated,
                            String externalMessageId) {

    public enum Status {
        PROCESSED,
        DUPLICATE
    }

    public static InboundResult processed(UUID conversationId, UUID customerId, UUID messageId,
                                          boolean conversationCreated, String externalMessageId) {
        return new InboundResult(Status.PROCESSED, conversationId, customerId, messageId,
            conversationCreated, externalMessageId);
    }

    public static InboundResult duplicate(UUID conversationId, String externalMessageId) {
        return new InboundResult(Status.DUPLICATE, conversationId, null, null, false,
            externalMessageId);
    }
}