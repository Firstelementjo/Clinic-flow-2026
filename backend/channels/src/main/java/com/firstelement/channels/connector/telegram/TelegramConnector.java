package com.firstelement.channels.connector.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstelement.channels.api.ConnectorCapabilities;
import com.firstelement.channels.api.MessagingConnector;
import com.firstelement.channels.api.NormalizedAttachment;
import com.firstelement.channels.api.NormalizedAttachmentType;
import com.firstelement.channels.api.NormalizedIncomingMessage;
import com.firstelement.channels.api.OutgoingMessage;
import com.firstelement.channels.api.SendResult;
import com.firstelement.channels.api.WebhookVerification;
import com.firstelement.channels.connector.AbstractHttpConnector;
import com.firstelement.channels.connector.ConnectorCatalog;
import com.firstelement.crm.domain.ChannelType;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The Telegram Bot API adapter, ported from
 * {@code packages/connectors/src/telegram/telegram.connector.ts}.
 *
 * <h2>An edit is not a new message</h2>
 *
 * <p>The legacy comment: "Edited messages arrive as a separate field; treating an edit as a new
 * message would duplicate the turn, so only new ones are taken." {@link #receiveWebhook} reads
 * {@code update.message} and ignores {@code edited_message}.
 *
 * <h2>Message ids are only unique per chat</h2>
 *
 * <p>"Message ids are only unique per chat, so the chat id is part of the identifier the
 * idempotency constraint sees." The external message id is therefore
 * {@code <chatId>:<messageId>}, which is what makes a redelivery a duplicate under
 * {@code messages(projectId, channel, externalMessageId)} rather than a second turn.
 */
public class TelegramConnector extends AbstractHttpConnector implements MessagingConnector {

    /** Decrypted Telegram credentials. The bot token is in every request URL: never logged. */
    public record TelegramCredentials(String botToken, String webhookSecret) {

        @Override
        public String toString() {
            return "TelegramCredentials[REDACTED]";
        }
    }

    private final String connectionId;
    private final TelegramCredentials credentials;
    private final ObjectMapper mapper;

    public TelegramConnector(String connectionId, TelegramCredentials credentials, ObjectMapper mapper,
                             Duration requestTimeout) {
        super(requestTimeout);
        this.connectionId = connectionId;
        this.credentials = credentials == null ? new TelegramCredentials(null, null) : credentials;
        this.mapper = mapper;
    }

    @Override
    public ChannelType channel() {
        return ChannelType.TELEGRAM;
    }

    @Override
    public String connectionId() {
        return connectionId;
    }

    @Override
    public ConnectorCapabilities capabilities() {
        return ConnectorCatalog.forChannel(ChannelType.TELEGRAM);
    }

    @Override
    public WebhookVerification verifyWebhook(byte[] rawBody, Map<String, String> headers) {
        // Telegram signs nothing, so the shared secret is the whole story. The body is irrelevant
        // to the decision and is deliberately not compared.
        return TelegramSecretVerifier.verify(headers, credentials.webhookSecret());
    }

    @Override
    public List<NormalizedIncomingMessage> receiveWebhook(String rawBody) {
        List<NormalizedIncomingMessage> messages = new ArrayList<>();
        if (rawBody == null || rawBody.isBlank()) {
            return messages;
        }
        JsonNode update;
        try {
            update = mapper.readTree(rawBody);
        } catch (Exception e) {
            return messages;
        }
        JsonNode message = update.path("message");
        JsonNode chat = message.path("chat");
        if (!chat.isObject() || !chat.has("id")) {
            // edited_message, channel posts and callback queries are not customer turns here.
            return messages;
        }
        String text = extractText(message);
        if (text == null || text.isBlank()) {
            return messages;
        }
        String chatId = String.valueOf(chat.path("id").asLong());
        JsonNode from = message.path("from");
        String displayName = displayName(from);

        messages.add(new NormalizedIncomingMessage(ChannelType.TELEGRAM,
            chatId + ":" + message.path("message_id").asText("0"), chatId, chatId, displayName,
            null, null, text, extractAttachments(message), sentAt(message)));
        return messages;
    }

    @Override
    public SendResult sendText(OutgoingMessage message) {
        if (!capabilities().sendText()) {
            throw new com.firstelement.channels.connector.UnsupportedCapabilityException(
                ChannelType.TELEGRAM, "SEND_TEXT");
        }
        String json = """
            {"chat_id":"%s","text":"%s"}"""
            .formatted(escape(message.customerExternalId()), escape(message.text()));
        return postJson(URI.create("https://api.telegram.org/bot" + credentials.botToken()
            + "/sendMessage"), null, json);
    }

    @Override
    protected String extractProviderMessageId(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode root = mapper.readTree(body);
            if (!root.path("ok").asBoolean(false)) {
                return null;
            }
            JsonNode result = root.path("result");
            if (result.isObject() && result.has("message_id")) {
                return result.path("chat").path("id").asText() + ":"
                    + result.path("message_id").asText();
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    private static String displayName(JsonNode from) {
        if (!from.isObject()) {
            return null;
        }
        String first = from.path("first_name").asText("");
        String last = from.path("last_name").asText("");
        String joined = (first + " " + last).trim();
        return joined.isEmpty() ? from.path("username").asText(null) : joined;
    }

    private static LocalDateTime sentAt(JsonNode message) {
        long epochSeconds = message.path("date").asLong(0L);
        if (epochSeconds <= 0L) {
            return LocalDateTime.now();
        }
        return LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds), ZoneOffset.UTC);
    }

    private static String extractText(JsonNode message) {
        String text = message.path("text").asText(null);
        if (text != null && !text.isBlank()) {
            return text;
        }
        String caption = message.path("caption").asText(null);
        if (caption != null && !caption.isBlank()) {
            return caption;
        }
        if (message.has("sticker")) {
            String emoji = message.path("sticker").path("emoji").asText(null);
            return emoji == null || emoji.isBlank() ? "[sticker]" : emoji;
        }
        if (message.has("location")) {
            return "[location " + message.path("location").path("latitude").asText("?") + ", "
                + message.path("location").path("longitude").asText("?") + "]";
        }
        if (message.has("photo")) {
            return "[photo]";
        }
        if (message.has("voice")) {
            return "[voice]";
        }
        if (message.has("video")) {
            return "[video]";
        }
        if (message.has("document")) {
            return "[document " + message.path("document").path("file_name").asText("") + "]"
                .replaceAll("\\s+\\]", "]");
        }
        return "";
    }

    private static List<NormalizedAttachment> extractAttachments(JsonNode message) {
        List<NormalizedAttachment> attachments = new ArrayList<>();
        // Telegram sends several sizes of the same photo; the last is largest.
        JsonNode photo = message.path("photo");
        if (photo.isArray() && !photo.isEmpty()) {
            attachments.add(new NormalizedAttachment(NormalizedAttachmentType.IMAGE,
                photo.get(photo.size() - 1).path("file_id").asText(null), null, null, null));
        }
        add(attachments, message, "video", NormalizedAttachmentType.VIDEO);
        add(attachments, message, "voice", NormalizedAttachmentType.AUDIO);
        add(attachments, message, "sticker", NormalizedAttachmentType.STICKER);
        JsonNode document = message.path("document");
        if (document.isObject()) {
            attachments.add(new NormalizedAttachment(NormalizedAttachmentType.DOCUMENT,
                document.path("file_id").asText(null), document.path("mime_type").asText(null),
                document.path("file_name").asText(null), null));
        }
        if (message.has("location")) {
            attachments.add(new NormalizedAttachment(NormalizedAttachmentType.LOCATION, null, null,
                null, null));
        }
        return attachments;
    }

    private static void add(List<NormalizedAttachment> attachments, JsonNode message, String field,
                            NormalizedAttachmentType type) {
        JsonNode node = message.path(field);
        if (node.isObject()) {
            attachments.add(new NormalizedAttachment(type, node.path("file_id").asText(null),
                node.path("mime_type").asText(null), null, null));
        }
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    @Override
    public String toString() {
        return "TelegramConnector[connection=" + connectionId + "]";
    }
}