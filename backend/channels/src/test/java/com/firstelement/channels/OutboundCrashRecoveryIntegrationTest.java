package com.firstelement.channels;

import static org.assertj.core.api.Assertions.assertThat;

import com.firstelement.channels.connector.ConnectorRegistry;
import com.firstelement.channels.connector.mock.MockConnector;
import com.firstelement.channels.persistence.ChannelConnectionRepository;
import com.firstelement.worker.outbox.OutboxIntent;
import com.firstelement.worker.outbox.OutboxRepository;
import com.firstelement.worker.outbox.OutboxState;
import com.firstelement.worker.queue.QueueName;
import com.firstelement.worker.runtime.OutboxDispatcher;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * The crash window, driven through the real dispatcher.
 *
 * <h2>The failure this exists to make impossible</h2>
 *
 * <p>A worker sends a customer's reply to a provider and then dies before it records that it did.
 * This is the one window no local mechanism can fully close, so it must be made <em>harmless</em>:
 * the re-run has to find the row already {@code SENT}, skip the provider, and record a successful
 * attempt. A customer receiving their reply twice is the observable outcome of getting it wrong, so
 * the assertion is made on the connector's own send log &mdash; the number of external calls, not
 * the number of database writes.
 *
 * <h2>How the crash is simulated honestly</h2>
 *
 * <p>The outbox row is put back exactly where a dead worker's row would be: {@code PENDING}, no
 * lease, due, with the attempt counter intact. Nothing about the message row is touched, because the
 * point of the scenario is that the message row was already written and the crash happened
 * afterwards.
 *
 * <h2>Why the whole dispatcher is involved</h2>
 *
 * <p>Calling {@code OutboundDeliveryService.deliver} twice in a unit test proves the service reads
 * the status. It does not prove the queue can re-run a job, that the second run is tenant-scoped,
 * that it is recorded as an attempt, or that the row reaches {@code SENT}. Those are the dispatcher's
 * job, so this test uses the dispatcher.
 */
@SpringBootTest(classes = ChannelsTestApplication.class)
class OutboundCrashRecoveryIntegrationTest extends ChannelsIntegrationTestBase {

    @Autowired
    private OutboxDispatcher dispatcher;
    @Autowired
    private OutboxRepository outbox;
    @Autowired
    private ChannelConnectionRepository connections;
    @Autowired
    private ConnectorRegistry registry;
    @Autowired
    private ChannelsFixture fixture;

    @BeforeEach
    void reset() {
        fixture.reset();
        registry.clearMockConnectors();
    }

    @Test
    @DisplayName("a worker that dies after the provider send does not send the reply twice")
    void crashAfterSendDoesNotDuplicate() {
        UUID conversation = fixture.conversation(ChannelsFixture.ORG_A, ChannelsFixture.PROJECT_A,
            ChannelsFixture.CUSTOMER_A, "MOCK", "966500000001");
        UUID connection = fixture.connection(ChannelsFixture.ORG_A, ChannelsFixture.PROJECT_A,
            "MOCK", "{}");
        UUID message = fixture.pendingOutbound(ChannelsFixture.ORG_A, ChannelsFixture.PROJECT_A,
            conversation, ChannelsFixture.CUSTOMER_A, "MOCK", "hello", "reply-crash");
        UUID intent = enqueueDeliveryIntent(message);

        // Attempt one: the whole path runs, the provider accepts, the row is completed.
        var first = dispatcher.dispatchForTenant(ChannelsFixture.ORG_A);
        assertThat(first.succeeded()).isEqualTo(1);
        assertThat(fixture.stateOf(intent)).isEqualTo("SENT");
        assertThat(fixture.deliveryStatusOf(message)).isEqualTo("SENT");
        assertThat(mock(connection).sendCountFor(message.toString())).isEqualTo(1);

        // The crash: the external send happened, the completion write did not.
        requeueAsIfTheWorkerDied(intent);

        // Attempt two: the re-run reads SENT and does not call the provider again.
        var second = dispatcher.dispatchForTenant(ChannelsFixture.ORG_A);
        assertThat(second.succeeded()).isEqualTo(1);
        assertThat(second.retried()).isZero();
        assertThat(second.deadLettered()).isZero();
        assertThat(fixture.stateOf(intent)).isEqualTo("SENT");
        assertThat(fixture.deliveryStatusOf(message)).isEqualTo("SENT");
        // One external call. This is the whole assertion.
        assertThat(mock(connection).sendCountFor(message.toString())).isEqualTo(1);

        // Both attempts are recorded, and the history is what an operator reads afterwards.
        assertThat(fixture.attemptOutcomes(ChannelsFixture.ORG_A, QueueName.OUTBOUND_SEND.wireValue()))
            .containsExactly("SUCCEEDED", "SUCCEEDED");
    }

    @Test
    @DisplayName("an expired lease is reclaimed and the re-run still does not duplicate the send")
    void expiredLeaseIsReclaimedAndStillDoesNotDuplicate() {
        UUID conversation = fixture.conversation(ChannelsFixture.ORG_A, ChannelsFixture.PROJECT_A,
            ChannelsFixture.CUSTOMER_A, "MOCK", "966500000001");
        UUID connection = fixture.connection(ChannelsFixture.ORG_A, ChannelsFixture.PROJECT_A,
            "MOCK", "{}");
        UUID message = fixture.pendingOutbound(ChannelsFixture.ORG_A, ChannelsFixture.PROJECT_A,
            conversation, ChannelsFixture.CUSTOMER_A, "MOCK", "hello", "reply-lease");
        UUID intent = enqueueDeliveryIntent(message);

        assertThat(dispatcher.dispatchForTenant(ChannelsFixture.ORG_A).succeeded()).isEqualTo(1);
        assertThat(mock(connection).sendCountFor(message.toString())).isEqualTo(1);

        // A worker that died while holding the lease: LEASED, expiry in the past. The claim cannot
        // see it, which is exactly why the reaper exists.
        requeueAsIfTheWorkerDied(intent);
        outbox.claimFor(ChannelsFixture.ORG_A, "worker-that-died", 10, 60);
        fixture.expireLease(intent);
        assertThat(fixture.stateOf(intent)).isEqualTo("LEASED");
        assertThat(dispatcher.dispatchForTenant(ChannelsFixture.ORG_A).claimed()).isZero();
        assertThat(outbox.expiredLeaseCount()).isEqualTo(1);

        assertThat(dispatcher.reap()).hasSize(1);
        assertThat(fixture.stateOf(intent)).isEqualTo("PENDING");
        // The attempt that died still counts, so the backoff stays monotonic.
        assertThat(fixture.attemptsOf(intent)).isEqualTo(2);

        assertThat(dispatcher.dispatchForTenant(ChannelsFixture.ORG_A).succeeded()).isEqualTo(1);
        assertThat(mock(connection).sendCountFor(message.toString())).isEqualTo(1);
        assertThat(fixture.deliveryStatusOf(message)).isEqualTo("SENT");
    }

    @Test
    @DisplayName("a provider that refuses permanently dead-letters the job and records the reason")
    void permanentProviderRefusalIsDeadLettered() {
        // No enabled connection for the channel, so the delivery is refused permanently.
        UUID conversation = fixture.conversation(ChannelsFixture.ORG_A, ChannelsFixture.PROJECT_A,
            ChannelsFixture.CUSTOMER_A, "MOCK", "966500000001");
        UUID message = fixture.pendingOutbound(ChannelsFixture.ORG_A, ChannelsFixture.PROJECT_A,
            conversation, ChannelsFixture.CUSTOMER_A, "MOCK", "hello", "reply-dead");
        UUID intent = enqueueDeliveryIntent(message);

        var result = dispatcher.dispatchForTenant(ChannelsFixture.ORG_A);

        assertThat(result.deadLettered()).isEqualTo(1);
        assertThat(fixture.stateOf(intent)).isEqualTo("DEAD");
        assertThat(fixture.deliveryStatusOf(message)).isEqualTo("FAILED");
        assertThat(fixture.failureReasonOf(message)).contains("No enabled MOCK connection");
        // Both halves of the evidence exist: the event is dead and an operator can see it.
        assertThat(fixture.deadLetterCount()).isEqualTo(1);
        assertThat(fixture.attemptOutcomes(ChannelsFixture.ORG_A,
            QueueName.OUTBOUND_SEND.wireValue())).containsExactly("NON_RETRYABLE_FAILURE");
    }

    // ------------------------------------------------------------------

    /** The intent a committed reply would have written, written directly for this fixture. */
    private UUID enqueueDeliveryIntent(UUID messageId) {
        return outbox.enqueue(OutboxIntent.of(ChannelsFixture.ORG_A, ChannelsFixture.PROJECT_A,
            "Message", messageId, QueueName.OUTBOUND_SEND.wireValue(),
            "{\"messageId\":\"" + messageId + "\"}", "outbound-send:" + messageId, "corr-crash",
            null)).orElseThrow().id();
    }

    /**
     * Puts the row back where a dead worker's row would be.
     *
     * <p>Not {@code state = 'PENDING'} alone: {@code deliveredAt} is only permitted when the state is
     * {@code SENT}, and {@code leaseOwner}/{@code leaseUntil} are cleared together. The CHECK
     * constraints are doing their job and the fixture has to honour them.
     */
    private void requeueAsIfTheWorkerDied(UUID intent) {
        fixture.jdbc().update("""
            update outbox_events
               set "state" = 'PENDING', "deliveredAt" = null, "leaseOwner" = null,
                   "leaseUntil" = null, "nextAttemptAt" = (now() AT TIME ZONE 'UTC') - interval '1 second',
                   "updatedAt" = (now() AT TIME ZONE 'UTC')
             where "id" = :id""", new MapSqlParameterSource().addValue("id", intent));
        assertThat(fixture.stateOf(intent)).isEqualTo(OutboxState.PENDING.name());
    }

    /** The memoised Mock adapter for a connection; the same instance the delivery path used. */
    private MockConnector mock(UUID connectionId) {
        ChannelConnectionRepository.Connection connection =
            connections.findById(connectionId).orElseThrow();
        return (MockConnector) registry.connectorFor(connection);
    }
}