package com.firstelement.channels.api;

import com.firstelement.crm.domain.ChannelType;
import java.util.List;

/**
 * What a channel can actually do, ported from
 * {@code packages/connectors/src/capabilities.ts} with the numbers unchanged.
 *
 * <p>These are configuration, not guesses scattered through the code, and they reflect the
 * official APIs (Meta Cloud API, Telegram Bot API). The five declarations are deliberately
 * <b>not</b> identical:
 *
 * <ul>
 *   <li>Telegram has no read receipts but does support edit and delete.</li>
 *   <li>WhatsApp is the only channel with a 24-hour customer-service window, outside which only
 *       approved templates may be sent.</li>
 *   <li>The development Mock channel declares {@code verifiesWebhookSignature = false}: the dev
 *       endpoint is gated by configuration, not by a signature it could only check against
 *       itself.</li>
 * </ul>
 *
 * @param sessionWindowHours the customer-service window, {@code null} for no limit
 */
public record ConnectorCapabilities(ChannelType channel,
                                    boolean sendText,
                                    boolean sendMedia,
                                    boolean markAsRead,
                                    boolean editMessage,
                                    boolean deleteMessage,
                                    Integer sessionWindowHours,
                                    boolean requiresTemplateOutsideSession,
                                    int maxTextLength,
                                    List<NormalizedAttachmentType> supportedAttachmentTypes,
                                    boolean verifiesWebhookSignature) {

    public ConnectorCapabilities {
        supportedAttachmentTypes = supportedAttachmentTypes == null ? List.of()
            : List.copyOf(supportedAttachmentTypes);
        if (maxTextLength < 1) {
            throw new IllegalArgumentException("maxTextLength must be positive for " + channel);
        }
    }

    public boolean supports(ConnectorCapability capability) {
        return switch (capability) {
            case SEND_TEXT -> sendText;
            case SEND_MEDIA -> sendMedia;
            case MARK_AS_READ -> markAsRead;
            case EDIT_MESSAGE -> editMessage;
            case DELETE_MESSAGE -> deleteMessage;
            case REQUIRES_TEMPLATE_OUTSIDE_SESSION -> requiresTemplateOutsideSession;
            case VERIFIES_WEBHOOK_SIGNATURE -> verifiesWebhookSignature;
        };
    }
}