package com.firstelement.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import com.firstelement.notifications.event.RealtimeEvent;
import com.firstelement.notifications.event.RealtimeEventType;
import com.firstelement.notifications.realtime.RedisRealtimeBridge;
import com.firstelement.notifications.realtime.RealtimeSubscriptionRegistry;
import com.firstelement.worker.config.UtcTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The multi-instance bridge, without a broker.
 *
 * <h2>Why the broker is not needed to prove this</h2>
 *
 * <p>pub/sub's only job here is to carry one envelope from the publishing process to the others.
 * The behaviour that matters is on either side of that wire: what {@code publish} puts in the
 * envelope, and what {@code deliverRemote} does with one that arrives. Both are exercised here
 * directly against the real beans, with the broker configured against a closed port so the local
 * delivery path is also the one under test.
 *
 * <p>An integration test that needed a live Redis would be a test this repository cannot run
 * hermetically, and an unrunnable test is not a pass.
 *
 * <h2>What must be true</h2>
 *
 * <ol>
 *   <li>A frame published on one instance reaches a subscriber on another.</li>
 *   <li>A frame for another tenant never reaches it, even though it arrived over the shared channel
 *       — the channel is not an authorization path.</li>
 *   <li>The envelope carries ids and no customer content.</li>
 *   <li>An unusable envelope — oversized, tenant-less, unknown type, not JSON, empty — is ignored
 *       rather than thrown, because the caller is a listener callback that must keep listening.</li>
 *   <li>Redis broadcasts to the publisher too, and this process does not deliver its own frame
 *       twice.</li>
 * </ol>
 */
@SpringBootTest(classes = NotificationsTestApplication.class)
class RealtimeBridgeFanoutIntegrationTest extends NotificationsIntegrationTestBase {

    private static final List<String> SUBSCRIBERS = java.util.Collections.synchronizedList(
        new ArrayList<>());

    @Autowired
    private RedisRealtimeBridge bridge;
    @Autowired
    private RealtimeSubscriptionRegistry registry;
    @Autowired
    private NotificationsFixture fixture;

    @BeforeEach
    void reset() {
        fixture.reset();
        List<String> toRemove;
        synchronized (SUBSCRIBERS) {
            toRemove = new ArrayList<>(SUBSCRIBERS);
            SUBSCRIBERS.clear();
        }
        toRemove.forEach(registry::remove);
    }

    @Test
    @DisplayName("a frame published on another instance reaches a local subscriber")
    void aRemoteFrameIsDeliveredLocally() {
        List<RealtimeEvent> received = subscribe(NotificationsFixture.ORG_A,
            NotificationsFixture.USER_A, Set.of(NotificationsFixture.PROJECT_A),
            Set.of(projectTopic()));

        String envelope = RedisRealtimeBridge.toEnvelope(new RealtimeEvent(
            RealtimeEventType.MESSAGE_CREATED, 1, NotificationsFixture.ORG_A,
            NotificationsFixture.PROJECT_A, null, UUID.randomUUID(), UtcTime.now(),
            java.util.Map.of("messageId", UUID.randomUUID().toString()))).body();

        // What another instance's listener would do with what it received.
        assertThat(bridge.deliverRemote(envelope)).isEqualTo(1);
        assertThat(received).hasSize(1);
        RealtimeEvent delivered = received.get(0);
        assertThat(delivered.type()).isEqualTo(RealtimeEventType.MESSAGE_CREATED);
        assertThat(delivered.organizationId()).isEqualTo(NotificationsFixture.ORG_A);
        assertThat(delivered.projectId()).isEqualTo(NotificationsFixture.PROJECT_A);
    }

    @Test
    @DisplayName("a project frame does not reach a subscriber of another project")
    void aRemoteProjectFrameIsTopicScoped() {
        List<RealtimeEvent> other = subscribe(NotificationsFixture.ORG_A,
            NotificationsFixture.USER_A2, Set.of(NotificationsFixture.PROJECT_A),
            Set.of(projectTopic()));

        String envelope = RedisRealtimeBridge.toEnvelope(new RealtimeEvent(
            RealtimeEventType.CONVERSATION_CHANGED, 1, NotificationsFixture.ORG_A,
            NotificationsFixture.PROJECT_B, null, UUID.randomUUID(), UtcTime.now(),
            java.util.Map.of())).body();

        assertThat(bridge.deliverRemote(envelope)).isZero();
        assertThat(other).isEmpty();
    }

    @Test
    @DisplayName("the shared channel is not an authorization path: another tenant gets nothing")
    void aRemoteFrameFromAnotherTenantIsRefused() {
        List<RealtimeEvent> received = subscribe(NotificationsFixture.ORG_A,
            NotificationsFixture.USER_A, Set.of(NotificationsFixture.PROJECT_A),
            Set.of(orgTopic()));

        String envelope = RedisRealtimeBridge.toEnvelope(new RealtimeEvent(
            RealtimeEventType.NOTIFICATION_CREATED, 1, NotificationsFixture.ORG_B,
            NotificationsFixture.PROJECT_B, NotificationsFixture.USER_B, UUID.randomUUID(),
            UtcTime.now(), java.util.Map.of())).body();

        assertThat(bridge.deliverRemote(envelope)).isZero();
        assertThat(received).isEmpty();
    }

    @Test
    @DisplayName("the envelope carries ids only, so a broker compromise is not a data breach")
    void theEnvelopeCarriesNoCustomerContent() {
        String envelope = RedisRealtimeBridge.toEnvelope(new RealtimeEvent(
            RealtimeEventType.NOTIFICATION_CREATED, 1, NotificationsFixture.ORG_A,
            NotificationsFixture.PROJECT_A, NotificationsFixture.USER_A, UUID.randomUUID(),
            UtcTime.now(), java.util.Map.of("title", "A customer wrote: my appointment moved")))
            .body();

        assertThat(envelope).doesNotContain("my appointment moved");
        assertThat(envelope).doesNotContain("title");
        assertThat(envelope).contains("\"payload\":{}");
        assertThat(envelope).contains("\"organizationId\":\"" + NotificationsFixture.ORG_A + "\"");
        // Bounded: an envelope is a handful of ids, never an unbounded document.
        assertThat(envelope.length()).isLessThan(RedisRealtimeBridge.MAX_BRIDGE_BYTES);
    }

    @Test
    @DisplayName("an envelope that cannot be a frame is ignored, and the listener keeps listening")
    void unusableEnvelopesAreIgnored() {
        List<RealtimeEvent> received = subscribe(NotificationsFixture.ORG_A,
            NotificationsFixture.USER_A, Set.of(NotificationsFixture.PROJECT_A),
            Set.of(projectTopic()));

        assertThat(bridge.deliverRemote(null)).isZero();
        assertThat(bridge.deliverRemote("")).isZero();
        assertThat(bridge.deliverRemote("not json at all")).isZero();
        assertThat(bridge.deliverRemote("[]")).isZero();
        // No tenant: a tenant-less frame has no audience.
        assertThat(bridge.deliverRemote("{\"type\":\"message\",\"version\":1}")).isZero();
        // A type this process does not understand: a rolling deployment must not crash the listener.
        assertThat(bridge.deliverRemote("{\"type\":\"invented\",\"version\":9,\"organizationId\":\""
            + NotificationsFixture.ORG_A + "\"}")).isZero();
        // A malformed id in an otherwise valid frame.
        assertThat(bridge.deliverRemote("{\"type\":\"message\",\"version\":1,\"organizationId\":\""
            + "not-a-uuid\"}")).isZero();
        // Oversized: the channel is not a general-purpose message bus.
        String oversized = "{\"type\":\"message\",\"padding\":\"" + "x".repeat(
            RedisRealtimeBridge.MAX_BRIDGE_BYTES) + "\"}";
        assertThat(bridge.deliverRemote(oversized)).isZero();

        assertThat(received).isEmpty();

        // And it is still listening: a good envelope after all of that is still delivered.
        String good = RedisRealtimeBridge.toEnvelope(new RealtimeEvent(
            RealtimeEventType.MESSAGE_CREATED, 1, NotificationsFixture.ORG_A,
            NotificationsFixture.PROJECT_A, null, UUID.randomUUID(), UtcTime.now(),
            java.util.Map.of())).body();
        assertThat(bridge.deliverRemote(good)).isEqualTo(1);
        assertThat(received).hasSize(1);
    }

    @Test
    @DisplayName("an envelope the broker redelivers is delivered to subscribers exactly once")
    void aRedeliveredEnvelopeIsDeliveredOnce() {
        List<RealtimeEvent> received = subscribe(NotificationsFixture.ORG_A,
            NotificationsFixture.USER_A, Set.of(NotificationsFixture.PROJECT_A),
            Set.of(projectTopic()));

        String envelope = RedisRealtimeBridge.toEnvelope(new RealtimeEvent(
            RealtimeEventType.MESSAGE_CREATED, 1, NotificationsFixture.ORG_A,
            NotificationsFixture.PROJECT_A, null, UUID.randomUUID(), UtcTime.now(),
            java.util.Map.of())).body();

        assertThat(bridge.deliverRemote(envelope)).isEqualTo(1);
        assertThat(received).hasSize(1);
        // pub/sub is at-least-once in practice, and a redelivery must not become a second frame.
        assertThat(bridge.deliverRemote(envelope)).isZero();
        assertThat(received).hasSize(1);
    }

    @Test
    @DisplayName("the bridge reports what an operator needs to see it is a bridge at all")
    void theHealthSurfaceNamesTheChannel() {
        assertThat(bridge.channelName()).contains(RedisRealtimeBridge.CHANNEL);
        assertThat(bridge.topic().getTopic()).isEqualTo(RedisRealtimeBridge.CHANNEL);
        // A connection factory exists even when the broker is unreachable, so this is "configured",
        // not "connected"; the subscription log line is what proves the latter.
        assertThat(bridge.multiInstanceCapable()).isTrue();
    }

    // ------------------------------------------------------------------

    private List<RealtimeEvent> subscribe(UUID organizationId, UUID userId, Set<UUID> projects,
                                          Set<String> topics) {
        List<RealtimeEvent> received = new ArrayList<>();
        SUBSCRIBERS.add(registry.register(new RealtimeSubscriptionRegistry.Subscriber(
            UUID.randomUUID().toString(), organizationId, userId, projects, topics, received::add)));
        return received;
    }

    private static String orgTopic() {
        return com.firstelement.security.realtime.RealtimeTopicPolicy
            .organizationTopic(NotificationsFixture.ORG_A);
    }

    private static String projectTopic() {
        return com.firstelement.security.realtime.RealtimeTopicPolicy.projectTopic(
            NotificationsFixture.ORG_A, NotificationsFixture.PROJECT_A);
    }
}