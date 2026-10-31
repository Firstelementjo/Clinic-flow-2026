package com.firstelement.channels.api;

import com.firstelement.crm.domain.ChannelType;

/**
 * The connector contract, ported from {@code packages/connectors/src/types.ts}.
 *
 * <h2>The one rule the whole module exists to keep</h2>
 *
 * <p>A WhatsApp payload, an Instagram payload and a Telegram update never travel further than the
 * adapter that produced them. Everything above this layer — the inbox pipeline, the dispatcher, the
 * admin read models — sees only {@link com.firstelement.channels.api.NormalizedIncomingMessage} and
 * {@link com.firstelement.channels.api.OutgoingMessage}, which is what keeps external platforms
 * adapters rather than business logic.
 *
 * @param connectionId identifies the {@code channel_connections} row this instance was built from.
 *                     It is <b>not</b> authorization: a webhook URL naming a connection only says
 *                     which credentials to verify against.
 */
public interface MessagingConnector {

    ChannelType channel();

    /** Identifies the {@code channel_connections} row this adapter was built from. */
    String connectionId();

    /** What this channel can actually do. Checked before acting, never assumed. */
    ConnectorCapabilities capabilities();

    /**
     * Verifies an inbound webhook over the bytes as received.
     *
     * <p>{@code rawBody} is the unparsed request body. Meta signs the exact bytes, so verifying a
     * re-serialized {@code JSON.parse} result computes a different digest.
     *
     * @return a result carrying the decision and <em>why</em>, never a bare boolean: "no signature
     *         header", "wrong app secret" and "this channel does not sign" are three different
     *         operator problems
     */
    WebhookVerification verifyWebhook(byte[] rawBody, java.util.Map<String, String> headers);

    /**
     * Normalizes a verified webhook body into conversation turns.
     *
     * <p>Delivery receipts and status callbacks are <b>not</b> turns and must be filtered here.
     *
     * @return the messages to process; empty when the payload carried none
     */
    java.util.List<NormalizedIncomingMessage> receiveWebhook(String rawBody);

    /** Sends a text message. Never throws for a provider failure: the result carries the class. */
    SendResult sendText(OutgoingMessage message);

    /** @return false when this channel does not support the capability; no exception. */
    default boolean supports(ConnectorCapability capability) {
        return capabilities().supports(capability);
    }
}