package com.firstelement.channels.service;

import com.firstelement.channels.api.MessagingConnector;
import com.firstelement.channels.api.NormalizedIncomingMessage;
import com.firstelement.channels.api.WebhookVerification;
import com.firstelement.channels.config.ChannelsProperties;
import com.firstelement.channels.connector.ConnectorRegistry;
import com.firstelement.channels.persistence.ChannelConnectionRepository;
import com.firstelement.channels.persistence.ProviderEventStore;
import com.firstelement.crm.domain.ChannelType;
import com.firstelement.security.Capability;
import com.firstelement.security.PrincipalType;
import com.firstelement.security.TenantContext;
import com.firstelement.security.job.TenantScopedExecutor;
import com.firstelement.security.event.SecurityEvent;
import com.firstelement.security.event.SecurityEventPublisher;
import com.firstelement.security.event.SecurityEventType;
import com.firstelement.worker.outbox.OutboxEnqueuer;
import com.firstelement.worker.outbox.OutboxIntent;
import com.firstelement.worker.queue.QueueName;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Webhook → verified → normalized → provider-event reserved → outbox intent. One transaction.
 *
 * <h2>The order is the whole design</h2>
 *
 * <ol>
 *   <li><b>Load the connection.</b> Not authorization: it selects which credential to verify
 *       against.</li>
 *   <li><b>Verify over the raw bytes.</b> Before anything is parsed for meaning, and before
 *       anything is queued. A request that fails verification is never parsed and never
 *       enqueued.</li>
 *   <li><b>Reserve the provider event id.</b> Inside this transaction, so a redelivery is refused
 *       before any work is published.</li>
 *   <li><b>Enqueue the intent.</b> In the same transaction. If this rolls back, so does the
 *       reservation, and a later redelivery is admitted properly.</li>
 * </ol>
 *
 * <h2>The one payload that carries customer text</h2>
 *
 * <p>{@code V20261004000100} Q2 says the outbox payload is the delivery <em>envelope</em> and
 * "never customer prose". For <b>outbound</b> that holds exactly: the envelope names the message id
 * and the worker reads the row. For <b>inbound</b> this schema has no durable pre-conversation
 * store — {@code messages} requires a {@code conversationId}, which only exists after identity
 * resolution — so there is nowhere else for the provider's payload to live while it waits for a
 * worker. The alternatives were rejecting the message, or calling the whole pipeline synchronously
 * on the request thread, which is the "no synchronous heavy work on request threads" rule. The text
 * is therefore carried in the tenant-scoped, tenant-private queue row, and:
 *
 * <ul>
 *   <li>it is never logged (this class logs ids and counts only);</li>
 *   <li>it never reaches {@code job_dead_letters."payloadSummary"};</li>
 *   <li>it never reaches a metric tag or an admin read model;</li>
 *   <li>{@link OutboundEnvelopePayloads} documents it as the one exception.</li>
 * </ul>
 *
 * <h2>Always HTTP 200 on a verified request</h2>
 *
 * <p>The legacy controller's rule, and it is the right one: "Meta and Telegram retry aggressively
 * on non-2xx and eventually disable a failing webhook; durability belongs to the queue, not to the
 * HTTP response." A failure <em>after</em> verification is recorded and answered 200.
 */
@Service
public class InboundWebhookIntakeService {

    private static final Logger log = LoggerFactory.getLogger(InboundWebhookIntakeService.class);

    /** Channels that accept a Meta-shaped signed body. */
    public static final List<ChannelType> META_CHANNELS =
        List.of(ChannelType.WHATSAPP, ChannelType.INSTAGRAM, ChannelType.MESSENGER);

    /** The channel the Telegram endpoint accepts. */
    public static final List<ChannelType> TELEGRAM_CHANNELS = List.of(ChannelType.TELEGRAM);

    /** The channel the development endpoint accepts. */
    public static final List<ChannelType> MOCK_CHANNELS = List.of(ChannelType.MOCK);

    private final ChannelConnectionRepository connections;
    private final ConnectorRegistry registry;
    private final ProviderEventStore providerEvents;
    private final OutboxEnqueuer outbox;
    private final ChannelsProperties properties;
    private final SecurityEventPublisher events;
    private final TenantScopedExecutor tenantScope;

    public InboundWebhookIntakeService(ChannelConnectionRepository connections,
                                       ConnectorRegistry registry,
                                       ProviderEventStore providerEvents,
                                       OutboxEnqueuer outbox,
                                       ChannelsProperties properties,
                                       SecurityEventPublisher events,
                                       TenantScopedExecutor tenantScope) {
        this.connections = connections;
        this.registry = registry;
        this.providerEvents = providerEvents;
        this.outbox = outbox;
        this.properties = properties;
        this.events = events;
        this.tenantScope = tenantScope;
    }

    /** What the HTTP layer needs to know. Never carries a message body. */
    public record IntakeResult(Outcome outcome, int accepted, int duplicates, String reason) {

        public enum Outcome {
            /** At least one new turn was queued. */
            ACCEPTED,
            /** Every message in the payload had already been seen. */
            DUPLICATE,
            /** The connection id is unknown, disabled, or does not match this endpoint. */
            NOT_FOUND,
            /** Verification failed. Indistinguishable from NOT_FOUND to the caller. */
            SIGNATURE_REJECTED,
            /** The request was structurally readable but produced no turns (e.g. status callbacks). */
            NO_MESSAGES
        }
    }

    /**
     * Handles one verified-or-not webhook.
     *
     * @param connectionId  from the URL. <b>Not authorization.</b>
     * @param rawBody       the request body exactly as received
     * @param headers       request headers, case-insensitive names
     * @param endpointFamily the channel family the URL declares. A connection whose channel is not
     *                      in this set is answered exactly like an unknown id, so posting a Telegram
     *                      URL with a WhatsApp connection id cannot be used to probe which ids exist.
     */
    @Transactional
    public IntakeResult accept(UUID connectionId, byte[] rawBody, Map<String, String> headers,
                               List<ChannelType> endpointFamily) {
        ChannelConnectionRepository.Connection connection = connections.findById(connectionId)
            .orElse(null);
        if (connection == null || !connection.enabled()
            || !channelAccepts(connection.channelType(), headers, endpointFamily)) {
            // Same answer for an unknown id and a wrong channel: a probe must not learn which
            // connection ids exist.
            return new IntakeResult(IntakeResult.Outcome.NOT_FOUND, 0, 0,
                "no such webhook endpoint");
        }

        MessagingConnector connector;
        try {
            connector = registry.connectorFor(connection);
        } catch (ConnectorRegistry.CredentialUnavailableException e) {
            connections.recordError(connection.organizationId(), connection.id(),
                "credential unavailable: " + e.getMessage());
            return new IntakeResult(IntakeResult.Outcome.SIGNATURE_REJECTED, 0, 0,
                "the connection's credential is not readable");
        }

        WebhookVerification verification = connector.verifyWebhook(rawBody, headers);
        if (!verification.verified()) {
            // Recorded on the connection so a misconfigured app secret shows up on the channel
            // panel "rather than as silence". The caller gets nothing that distinguishes this from
            // an unknown connection.
            connections.recordError(connection.organizationId(), connection.id(),
                "Webhook verification failed: " + verification.auditLabel());
            publishDenied(connection, verification);
            log.warn("webhook verification failed connection={} channel={} reason={}",
                connection.id(), connection.channel(), verification.reason());
            return new IntakeResult(IntakeResult.Outcome.SIGNATURE_REJECTED, 0, 0,
                "webhook verification failed");
        }
        connections.clearError(connection.organizationId(), connection.id());

        List<NormalizedIncomingMessage> messages;
        try {
            messages = connector.receiveWebhook(new String(rawBody,
                java.nio.charset.StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            // Still a 200 for the caller. The provider retrying the same broken payload does not
            // help, and a run of non-2xx gets the webhook disabled.
            connections.recordError(connection.organizationId(), connection.id(),
                "Webhook parse failed: " + e.getClass().getSimpleName());
            log.error("verified webhook could not be parsed connection={} channel={}",
                connection.id(), connection.channel(), e);
            return new IntakeResult(IntakeResult.Outcome.NO_MESSAGES, 0, 0,
                "the payload could not be normalized");
        }

        if (messages.isEmpty()) {
            // Status callbacks and metadata echoes land here. They are not turns and must not become
            // conversation history.
            return new IntakeResult(IntakeResult.Outcome.NO_MESSAGES, 0, 0, null);
        }

        // A webhook request carries no authenticated principal, so nothing is bound to this thread.
        // The connector scope is established here — for the TenantContext AND the SecurityContext,
        // so the outbox's tenant check and any nested authorization see the same values. Binding it
        // for the transaction (rather than per enqueue) is what makes "the intent and the domain
        // change share a transaction" true of the whole batch.
        TenantContext context = connectorContext(connection);
        int[] tallies = tenantScope.execute(context, "channel.webhook.intake",
            () -> {
                int accepted = 0;
                int duplicates = 0;
                for (NormalizedIncomingMessage message : messages) {
                    if (enqueue(context, connection, message)) {
                        accepted++;
                    } else {
                        duplicates++;
                    }
                }
                return new int[] { accepted, duplicates };
            });
        int accepted = tallies[0];
        int duplicates = tallies[1];
        if (accepted > 0) {
            connections.markInbound(connection.organizationId(), connection.id());
        }
        log.info("webhook accepted connection={} channel={} accepted={} duplicates={}",
            connection.id(), connection.channel(), accepted, duplicates);
        return new IntakeResult(
            accepted == 0 ? IntakeResult.Outcome.DUPLICATE : IntakeResult.Outcome.ACCEPTED,
            accepted, duplicates, null);
    }

    /**
     * Reserves the provider event and enqueues the intent.
     *
     * @return {@code true} when this caller is the first to see the event
     */
    private boolean enqueue(TenantContext context,
                            ChannelConnectionRepository.Connection connection,
                            NormalizedIncomingMessage message) {
        String correlationId = MDC.get("correlationId");
        boolean firstSighting = providerEvents.reserveFirstSighting(connection.organizationId(),
            connection.projectId(), connection.channel(), message.externalMessageId(),
            correlationId);
        if (!firstSighting) {
            // Not an error: providers redeliver, and the distinction between "accepted" and
            // "already seen" must stay visible instead of looking like a lost message.
            log.info("duplicate inbound message suppressed channel={} externalMessageId={}",
                connection.channel(), message.externalMessageId());
            return false;
        }

        UUID intentId = outbox.publish(OutboxIntent.of(
            connection.organizationId(),
            connection.projectId(),
            "InboundMessage",
            messageIdOf(connection, message),
            QueueName.INCOMING_MESSAGE.wireValue(),
            payload(connection, message),
            idempotencyKey(connection, message),
            correlationId,
            connection.id().toString()));

        providerEvents.complete(connection.organizationId(), connection.channel(),
            message.externalMessageId(), "OutboxEvent", intentId);
        return true;
    }

    /**
     * A stable id for the not-yet-created message row.
     *
     * <p>{@code outbox_events.aggregateId} is the id the inbound pipeline will use for the message
     * it creates, so the enqueue and the pipeline agree without a second lookup. Deriving it from
     * the provider id makes it deterministic, which is what keeps the outbox
     * {@code idempotencyKey} and the {@code messages} unique index in agreement.
     */
    private static UUID messageIdOf(ChannelConnectionRepository.Connection connection,
                                    NormalizedIncomingMessage message) {
        String seed = connection.id() + "|" + message.channel().name() + "|"
            + message.externalMessageId();
        return UUID.nameUUIDFromBytes(seed.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String idempotencyKey(ChannelConnectionRepository.Connection connection,
                                         NormalizedIncomingMessage message) {
        return "inbound:" + connection.id() + ":" + message.channel().name() + ":"
            + message.externalMessageId();
    }

    /**
     * The inbound envelope. See the class note: this is the one outbox payload that carries
     * customer text, because no durable pre-conversation store exists in this schema.
     */
    private static String payload(ChannelConnectionRepository.Connection connection,
                                  NormalizedIncomingMessage message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("connectionId", connection.id().toString());
        payload.put("channel", message.channel().name());
        payload.put("externalMessageId", message.externalMessageId());
        payload.put("externalUserId", message.externalUserId());
        payload.put("externalConversationId", message.externalConversationId());
        payload.put("displayName", message.displayName());
        payload.put("phone", message.phone());
        payload.put("email", message.email());
        payload.put("text", message.text());
        payload.put("attachments", message.attachments());
        payload.put("sentAt", message.sentAt().toString());
        payload.put("messageId", messageIdOf(connection, message).toString());
        try {
            return PAYLOAD_MAPPER.writeValueAsString(payload);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // Only reachable if a value cannot be serialized, which for a map of strings and
            // scalars means a defect. It must not be swallowed: an inbound message that cannot be
            // queued is a message that would be lost.
            throw new IllegalStateException("The inbound envelope could not be serialized", e);
        }
    }

    /** One mapper for the envelope; the webhook path is not hot enough to pool. */
    private static final com.fasterxml.jackson.databind.ObjectMapper PAYLOAD_MAPPER =
        new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * Whether this endpoint accepts the connection's channel.
     *
     * <p>The URL's family is the authority here, not the request headers: a Telegram URL must not
     * accept a WhatsApp connection just because the caller sent a Telegram-looking header. The
     * header check remains as a second narrowing for the legacy routing, and both fail the same
     * way.
     */
    private boolean channelAccepts(ChannelType channel, Map<String, String> headers,
                                   List<ChannelType> endpointFamily) {
        List<ChannelType> family = endpointFamily == null || endpointFamily.isEmpty()
            ? META_CHANNELS : endpointFamily;
        if (!family.contains(channel)) {
            return false;
        }
        if (family == TELEGRAM_CHANNELS && !hasTelegramSecretHeader(headers)) {
            return false;
        }
        return true;
    }

    private static boolean hasTelegramSecretHeader(Map<String, String> headers) {
        if (headers == null) {
            return false;
        }
        for (String key : headers.keySet()) {
            if (key.equalsIgnoreCase("X-Telegram-Bot-Api-Secret-Token")) {
                return true;
            }
        }
        return false;
    }

    /**
     * The scope the enqueue is authorised against.
     *
     * <p>A connector principal with {@code WEBHOOK_INGEST}: it may admit an inbound event for the
     * tenant and project the connection row belongs to, and nothing else. The project is taken from
     * the connection, never from the request body.
     */
    private static TenantContext connectorContext(ChannelConnectionRepository.Connection connection) {
        return TenantContext.forMachine(PrincipalType.CONNECTOR, connection.organizationId(),
            "channel:" + connection.id(), List.of(connection.projectId()),
            List.of(Capability.WEBHOOK_INGEST));
    }

    private void publishDenied(ChannelConnectionRepository.Connection connection,
                               WebhookVerification verification) {
        try {
            events.publish(SecurityEvent.builder(SecurityEventType.AUTHORIZATION_DENIED)
                .organizationId(connection.organizationId())
                .resourceType("ChannelWebhook")
                .resourceId(connection.id().toString())
                .reason("webhook-" + verification.reason().name().toLowerCase(java.util.Locale.ROOT))
                // The reason and the channel only. Never the header value and never the body.
                .metadata(Map.of("channel", connection.channel(),
                    "verification", verification.reason().name()))
                .correlationId(MDC.get("correlationId"))
                .build());
        } catch (RuntimeException e) {
            log.error("Failed to publish the webhook denial event connection={}", connection.id(), e);
        }
    }

    /** Resolves the properties once for the HTTP layer's gate check. */
    public boolean mockWebhookAllowed() {
        return properties.isMockWebhookEnabled();
    }

    /** The channels this endpoint family accepts, for the HTTP layer's documentation. */
    public static List<ChannelType> metaChannels() {
        return new ArrayList<>(META_CHANNELS);
    }
}