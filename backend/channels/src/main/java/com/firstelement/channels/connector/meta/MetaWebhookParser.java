package com.firstelement.channels.connector.meta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstelement.channels.api.NormalizedAttachment;
import com.firstelement.channels.api.NormalizedAttachmentType;
import com.firstelement.channels.api.NormalizedIncomingMessage;
import com.firstelement.crm.domain.ChannelType;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * The Meta webhook body, normalised. One parser for WhatsApp, Instagram and Messenger.
 *
 * <h2>Delivery receipts are not conversation turns</h2>
 *
 * <p>The legacy WhatsApp connector's comment is the reason this parser exists as its own class:
 * "Delivery receipts, not conversation turns" — a {@code statuses} array arrives on the same
 * endpoint as messages, and treating it as a turn would fabricate customer messages out of our own
 * delivery reports. The status transitions are handled elsewhere (delivery status tracking);
 * {@link #parse} returns only turns.
 *
 * <h2>Everything becomes text the CRM can reason about</h2>
 *
 * <p>"A media message with no caption still needs <em>something</em> — a conversation row reading
 * {@code [image]} is honest; an empty body looks like a bug."
 */
public final class MetaWebhookParser {

    private final ObjectMapper mapper;

    public MetaWebhookParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public List<NormalizedIncomingMessage> parse(ChannelType channel, String rawBody) {
        List<NormalizedIncomingMessage> messages = new ArrayList<>();
        if (rawBody == null || rawBody.isBlank()) {
            return messages;
        }
        JsonNode root;
        try {
            root = mapper.readTree(rawBody);
        } catch (Exception e) {
            // A body that is not JSON is not this parser's problem to report: the caller already
            // verified the signature, so a malformed body means the provider changed shape.
            return messages;
        }
        JsonNode entries = root.path("entry");
        if (!entries.isArray()) {
            return messages;
        }
        for (JsonNode entry : entries) {
            for (JsonNode change : entry.path("changes")) {
                JsonNode value = change.path("value");
                JsonNode incoming = value.path("messages");
                if (!incoming.isArray()) {
                    // A `statuses` payload, or a metadata echo. Neither is a turn.
                    continue;
                }
                java.util.Map<String, String> contactNames = new java.util.HashMap<>();
                for (JsonNode contact : value.path("contacts")) {
                    contactNames.put(contact.path("wa_id").asText(), contact.path("profile")
                        .path("name").asText(null));
                }
                for (JsonNode message : incoming) {
                    String id = message.path("id").asText(null);
                    String from = message.path("from").asText(null);
                    if (id == null || id.isBlank() || from == null || from.isBlank()) {
                        // Without an id the message cannot be deduplicated, and without a sender
                        // there is no identity to resolve. Dropping it is safer than admitting an
                        // undeduplicatable turn.
                        continue;
                    }
                    messages.add(new NormalizedIncomingMessage(channel, id, from, from,
                        contactNames.get(from), "+" + from, null, extractText(message),
                        extractAttachments(message), sentAt(message)));
                }
            }
        }
        return messages;
    }

    private static LocalDateTime sentAt(JsonNode message) {
        String timestamp = message.path("timestamp").asText(null);
        if (timestamp == null || timestamp.isBlank()) {
            return LocalDateTime.now();
        }
        try {
            return LocalDateTime.ofInstant(Instant.ofEpochSecond(Long.parseLong(timestamp)),
                ZoneOffset.UTC);
        } catch (NumberFormatException e) {
            return LocalDateTime.now();
        }
    }

    private static String extractText(JsonNode message) {
        String text = message.path("text").path("body").asText(null);
        if (text != null && !text.isBlank()) {
            return text;
        }
        String button = message.path("button").path("text").asText(null);
        if (button != null && !button.isBlank()) {
            return button;
        }
        String replyTitle = message.path("interactive").path("button_reply").path("title")
            .asText(null);
        if (replyTitle != null && !replyTitle.isBlank()) {
            return replyTitle;
        }
        String listTitle = message.path("interactive").path("list_reply").path("title").asText(null);
        if (listTitle != null && !listTitle.isBlank()) {
            return listTitle;
        }
        for (String mediaField : List.of("image", "video", "audio", "document")) {
            String caption = message.path(mediaField).path("caption").asText(null);
            if (caption != null && !caption.isBlank()) {
                return caption;
            }
        }
        JsonNode location = message.path("location");
        if (!location.isMissingNode() && !location.isNull()) {
            return "[location " + location.path("latitude").asText("?") + ", "
                + location.path("longitude").asText("?") + "]";
        }
        String type = message.path("type").asText("message");
        return "[" + type + "]";
    }

    private static List<NormalizedAttachment> extractAttachments(JsonNode message) {
        List<NormalizedAttachment> attachments = new ArrayList<>();
        add(attachments, message.path("image"), NormalizedAttachmentType.IMAGE);
        add(attachments, message.path("video"), NormalizedAttachmentType.VIDEO);
        add(attachments, message.path("audio"), NormalizedAttachmentType.AUDIO);
        JsonNode document = message.path("document");
        if (document.isObject()) {
            attachments.add(new NormalizedAttachment(NormalizedAttachmentType.DOCUMENT,
                document.path("id").asText(null), document.path("mime_type").asText(null),
                document.path("filename").asText(null), null));
        }
        JsonNode location = message.path("location");
        if (location.isObject()) {
            attachments.add(new NormalizedAttachment(NormalizedAttachmentType.LOCATION, null, null,
                null, null));
        }
        return attachments;
    }

    private static void add(List<NormalizedAttachment> attachments, JsonNode node,
                            NormalizedAttachmentType type) {
        if (node.isObject()) {
            attachments.add(new NormalizedAttachment(type, node.path("id").asText(null),
                node.path("mime_type").asText(null), null, null));
        }
    }
}