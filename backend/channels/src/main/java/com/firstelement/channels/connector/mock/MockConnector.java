package com.firstelement.channels.connector.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstelement.channels.api.ConnectorCapabilities;
import com.firstelement.channels.api.MessagingConnector;
import com.firstelement.channels.api.NormalizedIncomingMessage;
import com.firstelement.channels.api.OutgoingMessage;
import com.firstelement.channels.api.SendResult;
import com.firstelement.channels.api.WebhookVerification;
import com.firstelement.channels.connector.ConnectorCatalog;
import com.firstelement.crm.domain.ChannelType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The development connector, ported from {@code packages/connectors/src/mock.connector.ts}.
 *
 * <h2>It is not a stub that fakes success</h2>
 *
 * <p>The legacy comment is explicit: "It exists so the entire pipeline — webhook → customer →
 * conversation → message → (later) AI, scoring, escalation, routing — is exercisable without live
 * Meta or Telegram credentials. It is not a stub that fakes success: sends are recorded in an outbox
 * the dev endpoint can read back, and it declares capabilities like any other channel so
 * capability-aware code paths are exercised in development rather than first meeting reality in
 * slice 8."
 *
 * <p>That is why {@link #sent()} exists and why this connector declares
 * {@code verifiesWebhookSignature = false} instead of pretending to sign.
 *
 * <h2>The sent log is in memory and is a development affordance</h2>
 *
 * <p>It is per-process and bounded. It is not a system of record: the durable evidence that a
 * message went out is {@code messages.deliveryStatus} plus {@code messages.externalMessageId},
 * which the outbound path writes. Nothing operational may read this list.
 */
public class MockConnector implements MessagingConnector {

    /** One recorded send. The text is present because this is the development inspection surface. */
    public record SentMessage(String messageId, String externalUserId, String text,
                              LocalDateTime sentAt, String providerMessageId) {
    }

    private static final int MAX_SENT_ENTRIES = 500;

    private final String connectionId;
    private final List<SentMessage> outbox = new CopyOnWriteArrayList<>();
    private final AtomicLong counter = new AtomicLong();

    public MockConnector(String connectionId) {
        this.connectionId = connectionId;
    }

    @Override
    public ChannelType channel() {
        return ChannelType.MOCK;
    }

    @Override
    public String connectionId() {
        return connectionId;
    }

    @Override
    public ConnectorCapabilities capabilities() {
        return ConnectorCatalog.forChannel(ChannelType.MOCK);
    }

    @Override
    public WebhookVerification verifyWebhook(byte[] rawBody, Map<String, String> headers) {
        // Deliberately NOT signed: "the dev endpoint is gated by configuration, not by a signature
        // it could only check against itself". The caller must still enforce that gate.
        return new WebhookVerification(true, WebhookVerification.Reason.NOT_SIGNED,
            "the Mock channel is gated by configuration, not by a signature");
    }

    @Override
    public List<NormalizedIncomingMessage> receiveWebhook(String rawBody) {
        // The development endpoint posts one message per request in the shape the legacy
        // `MockIncomingPayload` declared: { externalUserId, text, externalMessageId?, displayName?,
        // phone?, email?, sentAt? }. Parsing it here means the whole webhook path — verification,
        // provider-event deduplication, outbox intent, worker — is exercisable without live provider
        // credentials, which is the point of the Mock channel.
        if (rawBody == null || rawBody.isBlank()) {
            return List.of();
        }
        JsonNode root;
        try {
            root = new ObjectMapper().readTree(rawBody);
        } catch (Exception e) {
            return List.of();
        }
        JsonNode payload = root.isArray() && root.size() > 0 ? root.get(0) : root;
        String externalUserId = text(payload, "externalUserId");
        if (externalUserId == null) {
            return List.of();
        }
        // A caller-supplied id lets a test replay the same message and assert that the pipeline
        // deduplicates it; without one the Mock channel mints a unique id, exactly as the legacy
        // connector did.
        String externalMessageId = text(payload, "externalMessageId");
        LocalDateTime sentAt = parseSentAt(text(payload, "sentAt"));
        return List.of(new NormalizedIncomingMessage(ChannelType.MOCK,
            externalMessageId == null || externalMessageId.isBlank() ? nextMessageId()
                : externalMessageId,
            externalUserId, externalUserId, text(payload, "displayName"), text(payload, "phone"),
            text(payload, "email"), text(payload, "text"), List.of(), sentAt));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static LocalDateTime parseSentAt(String value) {
        if (value == null || value.isBlank()) {
            return LocalDateTime.now();
        }
        try {
            return LocalDateTime.parse(value);
        } catch (java.time.format.DateTimeParseException e) {
            return LocalDateTime.now();
        }
    }

    @Override
    public SendResult sendText(OutgoingMessage message) {
        String providerMessageId = nextMessageId();
        outbox.add(new SentMessage(message.messageId().toString(), message.customerExternalId(),
            message.text(), LocalDateTime.now(), providerMessageId));
        while (outbox.size() > MAX_SENT_ENTRIES) {
            outbox.remove(0);
        }
        return SendResult.sent(providerMessageId);
    }

    /** Development inspection: what this connector has "delivered" so far. */
    public List<SentMessage> sent() {
        return List.copyOf(outbox);
    }

    /** How many sends this connector has made for {@code messageId}. Proves a retry did not. */
    public long sendCountFor(String messageId) {
        return outbox.stream().filter(entry -> entry.messageId().equals(messageId)).count();
    }

    /** Clears the log; the test fixture's per-test reset. */
    public void clearSent() {
        outbox.clear();
    }

    private String nextMessageId() {
        return "mock-" + connectionId + "-" + counter.incrementAndGet();
    }

    @Override
    public String toString() {
        return "MockConnector[connection=" + connectionId + "]";
    }
}