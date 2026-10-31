package com.firstelement.channels;

import static org.assertj.core.api.Assertions.assertThat;

import com.firstelement.channels.service.InboundWebhookIntakeService;
import com.firstelement.worker.queue.QueueName;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Provider-event deduplication, the acceptance scenario FR-4.4 names.
 *
 * <h2>"A duplicate webhook must be safe"</h2>
 *
 * <p>The guarantee is layered, and each layer is asserted:
 *
 * <ol>
 *   <li>{@code idempotency_records} (scope {@code PROVIDER_EVENT}) refuses the redelivery inside the
 *       webhook transaction, before any work is published;</li>
 *   <li>the outbox {@code idempotencyKey}, tenant-scoped unique, would produce one intent even if
 *       layer one were bypassed;</li>
 *   <li>{@code messages_projectId_channel_externalMessageId_key} is what makes the <em>result</em>
 *       correct — and this suite asserts it directly, by inserting the same provider id twice and
 *       requiring the database to refuse the second row.</li>
 * </ol>
 *
 * <p>Queue job ids are deliberately absent from all three: they are an optimization, not the
 * guarantee.
 */
@SpringBootTest(classes = ChannelsTestApplication.class)
class ProviderEventDeduplicationIntegrationTest extends ChannelsIntegrationTestBase {

    private static final String MOCK_CREDENTIALS = "{}";

    @Autowired
    private InboundWebhookIntakeService intake;
    @Autowired
    private ChannelsFixture fixture;

    @BeforeEach
    void reset() {
        fixture.reset();
    }

    @Test
    @DisplayName("one webhook admits one event; the redelivery is refused and nothing is republished")
    void duplicateWebhookIsSafe() {
        UUID connection = fixture.connection(ChannelsFixture.ORG_A, ChannelsFixture.PROJECT_A,
            "MOCK", MOCK_CREDENTIALS);

        var first = intake.accept(connection, body("wamid.ONE", "مرحبا"), Map.of(), com.firstelement.channels.service.InboundWebhookIntakeService.MOCK_CHANNELS);
        assertThat(first.outcome()).isEqualTo(InboundWebhookIntakeService.IntakeResult.Outcome.ACCEPTED);
        assertThat(first.accepted()).isEqualTo(1);
        assertThat(first.duplicates()).isZero();

        var second = intake.accept(connection, body("wamid.ONE", "مرحبا"), Map.of(), com.firstelement.channels.service.InboundWebhookIntakeService.MOCK_CHANNELS);
        assertThat(second.outcome())
            .isEqualTo(InboundWebhookIntakeService.IntakeResult.Outcome.DUPLICATE);
        assertThat(second.accepted()).isZero();
        assertThat(second.duplicates()).isEqualTo(1);

        // The database arbiter: one provider-event reservation, one outbox intent.
        assertThat(fixture.count("select count(*) from idempotency_records")).isEqualTo(1);
        assertThat(fixture.count("select count(*) from outbox_events")).isEqualTo(1);
        assertThat(fixture.count("select count(*) from outbox_events where \"idempotencyKey\" = "
            + "'inbound:" + connection + ":MOCK:wamid.ONE'")).isEqualTo(1);
    }

    @Test
    @DisplayName("five redeliveries still produce exactly one message and one intent")
    void repeatedRedeliveryIsStillOneMessage() {
        UUID connection = fixture.connection(ChannelsFixture.ORG_A, ChannelsFixture.PROJECT_A,
            "MOCK", MOCK_CREDENTIALS);

        for (int i = 0; i < 5; i++) {
            intake.accept(connection, body("wamid.REPEAT", "مرحبا"), Map.of(), com.firstelement.channels.service.InboundWebhookIntakeService.MOCK_CHANNELS);
        }

        assertThat(fixture.count("select count(*) from outbox_events")).isEqualTo(1);
        assertThat(fixture.count("select count(*) from idempotency_records")).isEqualTo(1);
    }

    @Test
    @DisplayName("the provider event id is the key: a different id is a different event")
    void differentProviderIdsAreDifferentEvents() {
        UUID connection = fixture.connection(ChannelsFixture.ORG_A, ChannelsFixture.PROJECT_A,
            "MOCK", MOCK_CREDENTIALS);

        assertThat(intake.accept(connection, body("wamid.A", "one"), Map.of(), com.firstelement.channels.service.InboundWebhookIntakeService.MOCK_CHANNELS).accepted()).isEqualTo(1);
        assertThat(intake.accept(connection, body("wamid.B", "two"), Map.of(), com.firstelement.channels.service.InboundWebhookIntakeService.MOCK_CHANNELS).accepted()).isEqualTo(1);

        assertThat(fixture.count("select count(*) from outbox_events")).isEqualTo(2);
        assertThat(fixture.count("select count(*) from idempotency_records")).isEqualTo(2);
    }

    @Test
    @DisplayName("the same provider id in another tenant is a different event")
    void providerEventDeduplicationIsTenantScoped() {
        UUID alpha = fixture.connection(ChannelsFixture.ORG_A, ChannelsFixture.PROJECT_A,
            "MOCK", MOCK_CREDENTIALS);
        UUID beta = fixture.connection(ChannelsFixture.ORG_B, ChannelsFixture.PROJECT_B,
            "MOCK", MOCK_CREDENTIALS);

        assertThat(intake.accept(alpha, body("wamid.SHARED", "one"), Map.of(), com.firstelement.channels.service.InboundWebhookIntakeService.MOCK_CHANNELS).accepted())
            .isEqualTo(1);
        // Beta must not be told Alpha already has it: a shared provider id across tenants is a
        // collision, not a duplicate, and refusing it would leak another tenant's existence.
        assertThat(intake.accept(beta, body("wamid.SHARED", "one"), Map.of(), com.firstelement.channels.service.InboundWebhookIntakeService.MOCK_CHANNELS).accepted())
            .isEqualTo(1);

        assertThat(fixture.count("select count(*) from outbox_events")).isEqualTo(2);
        assertThat(fixture.count("select count(*) from outbox_events"
            + " where \"organizationId\" = '" + ChannelsFixture.ORG_A + "'")).isEqualTo(1);
        assertThat(fixture.count("select count(*) from outbox_events"
            + " where \"organizationId\" = '" + ChannelsFixture.ORG_B + "'")).isEqualTo(1);
    }

    @Test
    @DisplayName("the reservation is completed with the outbox row it produced")
    void reservationIsCompleted() {
        UUID connection = fixture.connection(ChannelsFixture.ORG_A, ChannelsFixture.PROJECT_A,
            "MOCK", MOCK_CREDENTIALS);
        intake.accept(connection, body("wamid.COMPLETE", "one"), Map.of(), com.firstelement.channels.service.InboundWebhookIntakeService.MOCK_CHANNELS);

        UUID outboxId = fixture.jdbc().queryForObject("select \"id\" from outbox_events",
            new org.springframework.jdbc.core.namedparam.MapSqlParameterSource(), UUID.class);

        assertThat(fixture.count("select count(*) from idempotency_records"
            + " where \"state\" = 'COMPLETED'::\"IdempotencyState\"")).isEqualTo(1);
        assertThat(fixture.jdbc().queryForObject(
            "select \"resourceId\" from idempotency_records",
            new org.springframework.jdbc.core.namedparam.MapSqlParameterSource(), UUID.class))
            .isEqualTo(outboxId);
    }

    @Test
    @DisplayName("the outbox envelope names the message id the pipeline will create, deterministically")
    void envelopeIsDeterministic() {
        UUID connection = fixture.connection(ChannelsFixture.ORG_A, ChannelsFixture.PROJECT_A,
            "MOCK", MOCK_CREDENTIALS);
        intake.accept(connection, body("wamid.DETERMINISTIC", "one"), Map.of(), com.firstelement.channels.service.InboundWebhookIntakeService.MOCK_CHANNELS);

        String payload = fixture.jdbc().queryForObject("select \"payload\"::text from outbox_events",
            new org.springframework.jdbc.core.namedparam.MapSqlParameterSource(), String.class);

        // The id is a name-based UUID of (connection, channel, providerEventId), so the enqueue and
        // the pipeline agree without a second lookup — which is what keeps the outbox key and the
        // messages unique index describing the same event.
        assertThat(payload).contains("wamid.DETERMINISTIC").contains("\"messageId\"");
        assertThat(fixture.count("select count(*) from outbox_events where \"eventType\" = '"
            + QueueName.INCOMING_MESSAGE.wireValue() + "'")).isEqualTo(1);
        assertThat(fixture.count("select count(*) from outbox_events where \"aggregateType\" = "
            + "'InboundMessage'")).isEqualTo(1);
    }

    @Test
    @DisplayName("the database itself refuses a second row for the same provider message id")
    void theUniqueIndexIsTheGuarantee() {
        UUID conversation = fixture.conversation(ChannelsFixture.ORG_A, ChannelsFixture.PROJECT_A,
            ChannelsFixture.CUSTOMER_A, "MOCK", "966500000001");

        fixture.inboundMessage(ChannelsFixture.ORG_A, ChannelsFixture.PROJECT_A, conversation,
            "MOCK", "wamid.UNIQUE", "first");

        // The second insert is what the pipeline would have attempted on a redelivery.
        org.assertj.core.api.Assertions
            .assertThatThrownBy(() -> fixture.inboundMessage(ChannelsFixture.ORG_A,
                ChannelsFixture.PROJECT_A, conversation, "MOCK", "wamid.UNIQUE", "second"))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        assertThat(fixture.count("select count(*) from messages where \"externalMessageId\" = "
            + "'wamid.UNIQUE'")).isEqualTo(1);
    }

    @Test
    @DisplayName("the same provider id on a different channel is a different message")
    void providerEventIdIsScopedByChannel() {
        UUID conversation = fixture.conversation(ChannelsFixture.ORG_A, ChannelsFixture.PROJECT_A,
            ChannelsFixture.CUSTOMER_A, "MOCK", "966500000001");

        fixture.inboundMessage(ChannelsFixture.ORG_A, ChannelsFixture.PROJECT_A, conversation,
            "MOCK", "shared-id", "via mock");
        fixture.inboundMessage(ChannelsFixture.ORG_A, ChannelsFixture.PROJECT_A, conversation,
            "TELEGRAM", "shared-id", "via telegram");

        assertThat(fixture.count("select count(*) from messages where \"externalMessageId\" = "
            + "'shared-id'")).isEqualTo(2);
    }

    private static byte[] body(String providerEventId, String text) {
        // The legacy MockIncomingPayload shape: {externalUserId, text, externalMessageId?}. A
        // caller-supplied id is what lets this suite replay the same message and assert that the
        // pipeline deduplicates it.
        return ("{\"externalUserId\":\"966500000001\",\"externalMessageId\":\"" + providerEventId
            + "\",\"text\":\"" + text + "\"}")
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** The tenant-scoped key shape, asserted rather than reproduced, so a change is visible. */
    @Test
    @DisplayName("the provider-event key is channel-scoped and length-bounded")
    void providerEventKeyShape() {
        assertThat(com.firstelement.channels.persistence.ProviderEventStore
            .key("MOCK", "wamid.ONE")).isEqualTo("MOCK:wamid.ONE");
        // A hostile provider id cannot become an unbounded key value.
        String bounded = com.firstelement.channels.persistence.ProviderEventStore
            .key("MOCK", "x".repeat(500));
        assertThat(bounded.length()).isLessThanOrEqualTo(190);
        assertThat(bounded).startsWith("MOCK:");
        assertThat(List.of(bounded)).allSatisfy(value -> assertThat(value).doesNotContain("\n"));
    }
}