package com.firstelement.channels.connector;

import com.firstelement.channels.api.ConnectorCapabilities;
import com.firstelement.channels.api.ConnectorCapability;
import com.firstelement.channels.api.NormalizedAttachmentType;
import com.firstelement.crm.domain.ChannelType;
import java.util.List;
import java.util.Map;

/**
 * The declared capabilities per platform, ported value-for-value from
 * {@code packages/connectors/src/capabilities.ts}.
 *
 * <h2>These numbers are the contract the UI and the outbound path ask</h2>
 *
 * <p>The legacy comment states the reason they exist before the adapters do: "a composer that
 * silently allows a 5,000-character WhatsApp message, or an agent that replies four days into a
 * closed session window, fails at the platform boundary where the failure is hardest to explain to
 * a customer."
 *
 * <p>The table is immutable and validated: a channel with no declaration throws at first use
 * rather than defaulting to a permissive shape, because a missing declaration that defaulted to
 * {@code sendText = true} would be a policy hole.
 */
public final class ConnectorCatalog {

    private static final Map<ChannelType, ConnectorCapabilities> CAPABILITIES = Map.of(
        ChannelType.MOCK, new ConnectorCapabilities(
            ChannelType.MOCK, true, true, true, false, false, null, false, 4000,
            List.of(NormalizedAttachmentType.IMAGE, NormalizedAttachmentType.DOCUMENT,
                NormalizedAttachmentType.AUDIO, NormalizedAttachmentType.VIDEO),
            false),

        ChannelType.WHATSAPP, new ConnectorCapabilities(
            ChannelType.WHATSAPP, true, true, true, false, false, 24, true, 4096,
            List.of(NormalizedAttachmentType.IMAGE, NormalizedAttachmentType.VIDEO,
                NormalizedAttachmentType.AUDIO, NormalizedAttachmentType.DOCUMENT,
                NormalizedAttachmentType.STICKER, NormalizedAttachmentType.LOCATION),
            true),

        ChannelType.INSTAGRAM, new ConnectorCapabilities(
            ChannelType.INSTAGRAM, true, true, true, false, false, 24, false, 1000,
            List.of(NormalizedAttachmentType.IMAGE, NormalizedAttachmentType.VIDEO,
                NormalizedAttachmentType.AUDIO),
            true),

        ChannelType.MESSENGER, new ConnectorCapabilities(
            ChannelType.MESSENGER, true, true, true, false, false, 24, false, 2000,
            List.of(NormalizedAttachmentType.IMAGE, NormalizedAttachmentType.VIDEO,
                NormalizedAttachmentType.AUDIO, NormalizedAttachmentType.DOCUMENT),
            true),

        ChannelType.TELEGRAM, new ConnectorCapabilities(
            ChannelType.TELEGRAM, true, true, false, true, true, null, false, 4096,
            List.of(NormalizedAttachmentType.IMAGE, NormalizedAttachmentType.VIDEO,
                NormalizedAttachmentType.AUDIO, NormalizedAttachmentType.DOCUMENT,
                NormalizedAttachmentType.STICKER, NormalizedAttachmentType.LOCATION),
            true));

    private ConnectorCatalog() {
    }

    /** @throws IllegalArgumentException when the channel declares nothing; never a default. */
    public static ConnectorCapabilities forChannel(ChannelType channel) {
        ConnectorCapabilities capabilities = CAPABILITIES.get(channel);
        if (capabilities == null) {
            throw new IllegalArgumentException("No capability declaration exists for channel "
                + channel + ". Declaring capabilities is deny-by-default: a missing declaration "
                + "must fail, not assume parity.");
        }
        return capabilities;
    }

    public static boolean supports(ChannelType channel, ConnectorCapability capability) {
        return forChannel(channel).supports(capability);
    }

    /** Every declaration, for the connector capability endpoint and the admin read model. */
    public static Map<ChannelType, ConnectorCapabilities> all() {
        return CAPABILITIES;
    }
}