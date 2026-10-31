package com.firstelement.channels.api;

import java.util.UUID;

/**
 * A message to hand to a connector.
 *
 * <h2>{@code messageId} is the idempotency handle across the send</h2>
 *
 * <p>The legacy dispatcher passed {@code messageId} into the connector for exactly one reason: "It
 * correlates the send with the persisted message row." It is what lets the outbound path prove
 * "this reply has already been sent" after a crash, which is the difference between a customer
 * receiving a reply once and receiving it twice.
 *
 * @param customerExternalId the platform's handle for this customer, resolved from
 *                           {@code customer_identities} under tenant scope — never taken from the
 *                           request
 */
public record OutgoingMessage(UUID messageId,
                              String customerExternalId,
                              String customerExternalConversationId,
                              String text) {

    public OutgoingMessage {
        if (messageId == null) {
            throw new IllegalArgumentException("messageId is required to correlate the send");
        }
        if (customerExternalId == null || customerExternalId.isBlank()) {
            throw new IllegalArgumentException(
                "customerExternalId is required: the customer has no handle on this channel");
        }
    }
}