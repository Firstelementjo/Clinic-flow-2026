package com.firstelement.channels.connector.meta;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstelement.channels.api.ConnectorCapabilities;
import com.firstelement.channels.api.MessagingConnector;
import com.firstelement.channels.api.NormalizedIncomingMessage;
import com.firstelement.channels.api.OutgoingMessage;
import com.firstelement.channels.api.SendResult;
import com.firstelement.channels.api.WebhookVerification;
import com.firstelement.channels.connector.AbstractHttpConnector;
import com.firstelement.channels.connector.ConnectorCatalog;
import com.firstelement.channels.connector.UnsupportedCapabilityException;
import com.firstelement.crm.domain.ChannelType;
import com.firstelement.worker.queue.RetryDecision;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * The Meta Cloud API adapter, shared by WhatsApp, Instagram and Messenger.
 *
 * <h2>What differs per channel</h2>
 *
 * <p>Only the capability declaration, the {@code messaging_product} field and the API version
 * path. Everything security-relevant — the signature scheme, the payload shape, the status
 * classification — is identical across the three, so splitting them would create three places to
 * get a signature check subtly different.
 *
 * <h2>The credentials are values, never fields anyone can read</h2>
 *
 * <p>{@link MetaCredentials} has a {@link #toString()} that emits {@code MetaCredentials[REDACTED]}.
 * That matters because credentials arrive from a decrypting repository and end up in a log line the
 * first time somebody debugs a send failure; FR-4.7 requires them redacted in logs and there is
 * no review step that catches a data class.
 *
 * <h2>The system user id is part of the URL</h2>
 *
 * <p>Which means the full request URL is a secret. {@link AbstractHttpConnector} never logs it, and
 * the admin read models select no credential column at all.
 */
public class MetaConnector extends AbstractHttpConnector implements MessagingConnector {

    /** Decrypted Meta credentials. Never logged, never returned by an API. */
    public record MetaCredentials(String phoneNumberId, String accessToken, String appSecret,
                                  String verifyToken, String apiVersion) {

        @Override
        public String toString() {
            return "MetaCredentials[REDACTED]";
        }
    }

    private static final String DEFAULT_API_VERSION = "v21.0";

    private final ChannelType channel;
    private final String connectionId;
    private final MetaCredentials credentials;
    private final MetaWebhookParser parser;

    public MetaConnector(ChannelType channel, String connectionId, MetaCredentials credentials,
                         ObjectMapper mapper, Duration requestTimeout) {
        super(requestTimeout);
        this.channel = channel;
        this.connectionId = connectionId;
        this.credentials = credentials == null ? new MetaCredentials(null, null, null, null, null)
            : credentials;
        this.parser = new MetaWebhookParser(mapper);
    }

    @Override
    public ChannelType channel() {
        return channel;
    }

    @Override
    public String connectionId() {
        return connectionId;
    }

    @Override
    public ConnectorCapabilities capabilities() {
        return ConnectorCatalog.forChannel(channel);
    }

    @Override
    public WebhookVerification verifyWebhook(byte[] rawBody, Map<String, String> headers) {
        return MetaSignatureVerifier.verify(rawBody, headers, credentials.appSecret());
    }

    /** Whether the Meta GET handshake's verify token matches, in constant time. */
    public boolean verifyTokenMatches(String providedToken) {
        return MetaSignatureVerifier.matchesVerifyToken(providedToken, credentials.verifyToken());
    }

    /**
     * The configured verify token, for the registration handshake.
     *
     * <p>Returned as an {@code Optional} so the HTTP layer can treat "absent" and "unreadable"
     * the same way, which is what keeps the handshake non-disclosing. Never logged.
     */
    public java.util.Optional<String> verificationToken() {
        return credentials.verifyToken() == null || credentials.verifyToken().isBlank()
            ? java.util.Optional.empty()
            : java.util.Optional.of(credentials.verifyToken());
    }

    @Override
    public List<NormalizedIncomingMessage> receiveWebhook(String rawBody) {
        return parser.parse(channel, rawBody);
    }

    @Override
    public SendResult sendText(OutgoingMessage message) {
        if (!capabilities().sendText()) {
            throw new UnsupportedCapabilityException(channel,
                "SEND_TEXT");
        }
        String json = """
            {"messaging_product":"%s","recipient_type":"individual","to":"%s","type":"text",\
            "text":{"preview_url":false,"body":%s}}"""
            .formatted(wireName(), escape(message.customerExternalId()),
                quote(message.text()));
        return postJson(messagesUrl(), credentials.accessToken(), json);
    }

    /** Marks a conversation read. Best effort: a failed read receipt must not fail a send. */
    public boolean markAsRead(String externalMessageId) {
        if (!capabilities().markAsRead() || externalMessageId == null || externalMessageId.isBlank()) {
            return false;
        }
        String json = """
            {"messaging_product":"%s","status":"read","message_id":"%s"}"""
            .formatted(wireName(), escape(externalMessageId));
        return postJson(messagesUrl(), credentials.accessToken(), json).ok();
    }

    private URI messagesUrl() {
        String version = credentials.apiVersion() == null || credentials.apiVersion().isBlank()
            ? DEFAULT_API_VERSION : credentials.apiVersion();
        // The phone number id is in the path, which is why this URL must never be logged.
        return URI.create("https://graph.facebook.com/" + version + "/"
            + credentials.phoneNumberId() + "/messages");
    }

    @Override
    protected String extractProviderMessageId(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode root =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
            for (com.fasterxml.jackson.databind.JsonNode message : root.path("messages")) {
                String id = message.path("id").asText(null);
                if (id != null && !id.isBlank()) {
                    return id;
                }
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    /** JSON string escaping for the two fields we build by hand. */
    static String quote(String value) {
        StringBuilder out = new StringBuilder(value.length() + 8);
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** The wire name Meta uses for this channel's messaging product. */
    private String wireName() {
        return switch (channel) {
            case WHATSAPP, INSTAGRAM, MESSENGER -> channel.name().toLowerCase(java.util.Locale.ROOT);
            default -> throw new UnsupportedCapabilityException(channel, "Meta Cloud API");
        };
    }

    @Override
    public String toString() {
        return "MetaConnector[channel=" + channel + ", connection=" + connectionId + "]";
    }

    /** Reserved for callers that need to report a provider rejection without a decision object. */
    public static SendResult rejected(String code, String reason) {
        return SendResult.failed(RetryDecision.permanent(RetryDecision.INVALID_REQUEST, code, reason));
    }
}
