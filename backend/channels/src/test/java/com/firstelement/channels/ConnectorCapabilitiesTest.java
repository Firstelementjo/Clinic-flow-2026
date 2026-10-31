package com.firstelement.channels;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstelement.channels.api.ConnectorCapabilities;
import com.firstelement.channels.api.ConnectorCapability;
import com.firstelement.channels.api.NormalizedAttachmentType;
import com.firstelement.channels.connector.ConnectorCatalog;
import com.firstelement.channels.connector.OutboundGuard;
import com.firstelement.channels.connector.UnsupportedCapabilityException;
import com.firstelement.crm.domain.ChannelType;
import com.firstelement.crm.error.ValidationFailedException;
import com.firstelement.inbox.api.IncomingMessage;
import com.firstelement.channels.api.NormalizedIncomingMessage;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Connector capability reporting, as a contract with the platform boundary.
 *
 * <h2>The numbers are assertions, not documentation</h2>
 *
 * <p>Each value is the number the legacy runtime declared and the platform documents. If one of them
 * changes, a composer starts accepting a message the platform rejects, or the outbound path starts
 * relying on a capability the channel does not have — and both failures surface to a customer at the
 * moment they are hardest to explain. So every number is asserted here.
 */
class ConnectorCapabilitiesTest {

    @Test
    @DisplayName("every declared channel has capabilities, and no channel is missing a declaration")
    void everyChannelIsDeclared() {
        for (ChannelType channel : ChannelType.values()) {
            ConnectorCapabilities capabilities = ConnectorCatalog.forChannel(channel);
            assertThat(capabilities.channel()).isEqualTo(channel);
            assertThat(capabilities.maxTextLength()).isPositive();
            assertThat(capabilities.supportedAttachmentTypes()).isNotEmpty();
        }
        assertThat(ConnectorCatalog.all()).hasSize(ChannelType.values().length);
        assertThat(ConnectorCatalog.all().keySet())
            .containsExactlyInAnyOrder(ChannelType.values());
    }

    @Test
    @DisplayName("Mock: no signature scheme, no session window, 4000 characters")
    void mock() {
        ConnectorCapabilities mock = ConnectorCatalog.forChannel(ChannelType.MOCK);
        assertThat(mock.sendText()).isTrue();
        assertThat(mock.sendMedia()).isTrue();
        assertThat(mock.markAsRead()).isTrue();
        assertThat(mock.editMessage()).isFalse();
        assertThat(mock.deleteMessage()).isFalse();
        assertThat(mock.sessionWindowHours()).isNull();
        assertThat(mock.requiresTemplateOutsideSession()).isFalse();
        assertThat(mock.maxTextLength()).isEqualTo(4000);
        // Deliberately false: the dev endpoint is gated by configuration, not by a signature it could
        // only check against itself.
        assertThat(mock.verifiesWebhookSignature()).isFalse();
        assertThat(mock.supportedAttachmentTypes()).containsExactlyInAnyOrder(
            NormalizedAttachmentType.IMAGE, NormalizedAttachmentType.DOCUMENT,
            NormalizedAttachmentType.AUDIO, NormalizedAttachmentType.VIDEO);
    }

    @Test
    @DisplayName("WhatsApp: the only channel with a 24-hour customer-service window")
    void whatsapp() {
        ConnectorCapabilities whatsapp = ConnectorCatalog.forChannel(ChannelType.WHATSAPP);
        assertThat(whatsapp.sessionWindowHours()).isEqualTo(24);
        assertThat(whatsapp.requiresTemplateOutsideSession()).isTrue();
        assertThat(whatsapp.maxTextLength()).isEqualTo(4096);
        assertThat(whatsapp.verifiesWebhookSignature()).isTrue();
        assertThat(whatsapp.supportedAttachmentTypes()).contains(NormalizedAttachmentType.STICKER,
            NormalizedAttachmentType.LOCATION);
    }

    @Test
    @DisplayName("Instagram and Messenger declare their own limits, not WhatsApp's")
    void instagramAndMessenger() {
        ConnectorCapabilities instagram = ConnectorCatalog.forChannel(ChannelType.INSTAGRAM);
        assertThat(instagram.maxTextLength()).isEqualTo(1000);
        assertThat(instagram.sessionWindowHours()).isEqualTo(24);
        assertThat(instagram.requiresTemplateOutsideSession()).isFalse();
        assertThat(instagram.supportedAttachmentTypes()).doesNotContain(
            NormalizedAttachmentType.DOCUMENT, NormalizedAttachmentType.STICKER);

        ConnectorCapabilities messenger = ConnectorCatalog.forChannel(ChannelType.MESSENGER);
        assertThat(messenger.maxTextLength()).isEqualTo(2000);
        assertThat(messenger.sessionWindowHours()).isEqualTo(24);
        assertThat(messenger.supportedAttachmentTypes()).contains(NormalizedAttachmentType.DOCUMENT);
    }

    @Test
    @DisplayName("Telegram: edits and deletes yes, read receipts no")
    void telegram() {
        ConnectorCapabilities telegram = ConnectorCatalog.forChannel(ChannelType.TELEGRAM);
        assertThat(telegram.markAsRead()).isFalse();
        assertThat(telegram.editMessage()).isTrue();
        assertThat(telegram.deleteMessage()).isTrue();
        assertThat(telegram.sessionWindowHours()).isNull();
        assertThat(telegram.maxTextLength()).isEqualTo(4096);
        // Telegram signs nothing, so "verifies a webhook signature" means "authenticates by secret".
        assertThat(telegram.verifiesWebhookSignature()).isTrue();
    }

    @Test
    @DisplayName("capability lookup is case for the query method, and total for every channel")
    void supportsIsTotal() {
        for (ChannelType channel : ChannelType.values()) {
            for (ConnectorCapability capability : ConnectorCapability.values()) {
                // Never throws: an unknown capability must be a `false`, not an exception the caller
                // has to remember to catch.
                assertThat(ConnectorCatalog.supports(channel, capability)).isIn(true, false);
            }
        }
    }

    // ------------------------------------------------------------------
    // outbound pre-flight
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a text within the platform's limit is allowed")
    void textWithinTheLimitIsAllowed() {
        ConnectorCapabilities whatsapp = ConnectorCatalog.forChannel(ChannelType.WHATSAPP);
        assertThat(OutboundGuard.check(whatsapp, "مرحبا", true).allowed()).isTrue();
        assertThat(OutboundGuard.check(whatsapp, "a".repeat(4096), true).allowed()).isTrue();
    }

    @Test
    @DisplayName("an over-long text is refused with the limit and the actual length")
    void overLongTextIsRefused() {
        ConnectorCapabilities instagram = ConnectorCatalog.forChannel(ChannelType.INSTAGRAM);
        OutboundGuard.Verdict verdict = OutboundGuard.check(instagram, "a".repeat(1001), true);

        assertThat(verdict.allowed()).isFalse();
        assertThat(verdict.refusedCapability()).isEqualTo(ConnectorCapability.SEND_TEXT);
        assertThat(verdict.reason()).contains("1001").contains("1000");
    }

    @Test
    @DisplayName("outside WhatsApp's session window only templates may be sent")
    void sessionWindowIsEnforced() {
        ConnectorCapabilities whatsapp = ConnectorCatalog.forChannel(ChannelType.WHATSAPP);

        OutboundGuard.Verdict inside = OutboundGuard.check(whatsapp, "hello", true);
        assertThat(inside.allowed()).isTrue();

        OutboundGuard.Verdict outside = OutboundGuard.check(whatsapp, "hello", false);
        assertThat(outside.allowed()).isFalse();
        assertThat(outside.refusedCapability())
            .isEqualTo(ConnectorCapability.REQUIRES_TEMPLATE_OUTSIDE_SESSION);
        assertThat(outside.reason()).contains("24-hour");
    }

    @Test
    @DisplayName("channels with no session window are never blocked by the window check")
    void channelsWithoutAWindowAreUnaffected() {
        ConnectorCapabilities telegram = ConnectorCatalog.forChannel(ChannelType.TELEGRAM);
        assertThat(OutboundGuard.check(telegram, "hello", false).allowed()).isTrue();
        assertThat(OutboundGuard.check(telegram, "hello", true).allowed()).isTrue();
    }

    // ------------------------------------------------------------------
    // the normalized contract's own refusals
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a normalized message without a provider id is refused at construction")
    void providerIdIsRequired() {
        assertThatThrownBy(() -> new NormalizedIncomingMessage(ChannelType.WHATSAPP, "  ",
            "966500000001", null, null, null, null, "hi", List.of(), null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("externalMessageId");

        assertThatThrownBy(() -> new NormalizedIncomingMessage(ChannelType.WHATSAPP, "wamid.1", null,
            null, null, null, null, "hi", List.of(), null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("externalUserId");
    }

    @Test
    @DisplayName("a normalized message's audit label is an id pair, never its text")
    void auditLabelNeverCarriesText() {
        NormalizedIncomingMessage message = new NormalizedIncomingMessage(ChannelType.TELEGRAM,
            "12345:678", "12345", "12345", "A Customer", null, null, "سرية",
            List.of(), null);

        assertThat(message.auditLabel()).isEqualTo("TELEGRAM:12345:678");
        assertThat(message.auditLabel()).doesNotContain("سرية");
    }

    @Test
    @DisplayName("an outgoing message requires a row id to correlate the send with")
    void outgoingMessageRequiresAnId() {
        assertThatThrownBy(() -> new com.firstelement.channels.api.OutgoingMessage(null, "12345",
            null, "hello")).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("messageId");
        assertThatThrownBy(() -> new com.firstelement.channels.api.OutgoingMessage(
            java.util.UUID.randomUUID(), " ", null, "hello"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("customerExternalId");
    }

    @Test
    @DisplayName("an unsupported capability is a permanent failure carrying the channel and capability")
    void unsupportedCapabilityIsPermanent() {
        UnsupportedCapabilityException error =
            new UnsupportedCapabilityException(ChannelType.TELEGRAM, "MARK_AS_READ");

        assertThat(error.retryClass()).isEqualTo("UNSUPPORTED_CAPABILITY");
        assertThat(error.errorCode()).isEqualTo("UNSUPPORTED_CAPABILITY");
        assertThat(error.getMessage()).contains("TELEGRAM").contains("MARK_AS_READ");
    }

    @Test
    @DisplayName("the Phase-4 inbound contract carries the same provider id the connector produced")
    void contractsAgree() {
        // InboundMessage is what the CRM pipeline consumes; NormalizedIncomingMessage is what the
        // connector produces. The provider id is the inbound idempotency key, so the hand-off must
        // preserve it exactly.
        NormalizedIncomingMessage produced = new NormalizedIncomingMessage(ChannelType.TELEGRAM,
            "12345:678", "12345", "12345", "A Customer", null, null, "مرحبا", List.of(), null);

        IncomingMessage consumed = new IncomingMessage(produced.externalUserId(), produced.text(),
            produced.displayName(), produced.phone(), produced.email(), produced.externalMessageId(),
            produced.channel(), Map.of(), Map.of(), produced.sentAt());

        assertThat(consumed.externalMessageId()).isEqualTo(produced.externalMessageId());
        assertThat(consumed.channel()).isEqualTo(produced.channel());
        assertThat(consumed.externalUserId()).isEqualTo(produced.externalUserId());
        // And a sentAt of null is filled in rather than left null, so the pipeline never stores a
        // null timestamp on a message.
        assertThat(consumed.sentAt()).isNotNull();
        // IncomingMessage is Phase 4's record and deliberately does not validate: its validation
        // lives in InboundMessageService.receive, which refuses a null channel before touching the
        // database. What this asserts is the hand-off contract only — the provider id survives.
        assertThat(consumed.attachments()).isEmpty();
        assertThat(consumed.metadata()).isEmpty();
    }

    @Test
    @DisplayName("capability records refuse an impossible maximum")
    void capabilityRecordsValidate() {
        assertThatThrownBy(() -> new ConnectorCapabilities(ChannelType.MOCK, true, true, true, true,
            true, null, false, 0, List.of(), false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("maxTextLength");
    }
}
