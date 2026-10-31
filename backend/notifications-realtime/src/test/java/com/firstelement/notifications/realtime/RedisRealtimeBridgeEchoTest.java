package com.firstelement.notifications.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstelement.notifications.event.RealtimeEvent;
import com.firstelement.notifications.event.RealtimeEventType;
import com.firstelement.security.realtime.RealtimeTopicPolicy;
import com.firstelement.worker.config.UtcTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * The publish side of the bridge, including the one thing only the publisher can observe: the echo.
 *
 * <h2>Why this is a unit test and not an integration test</h2>
 *
 * <p>Redis broadcasts to every subscriber, including the instance that published the frame. That
 * echo carries the exact bytes {@link RedisRealtimeBridge#publish} sent, with the frame id that call
 * generated — so proving the echo is suppressed requires capturing what the broker was handed, which
 * means owning the template the bridge publishes through. That is a wiring seam, not a database, so
 * this test supplies a template that records instead of connecting and asserts the real beans'
 * behaviour with no container and no broker.
 *
 * <p>It lives in the bridge's own package because it exercises package-private wiring deliberately
 * rather than reaching past the public surface.
 */
class RedisRealtimeBridgeEchoTest {

    private static final UUID ORG = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID PROJECT = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000000a1");
    private static final UUID USER = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000000e1");

    @Test
    @DisplayName("this instance does not deliver its own frame twice when the broker echoes it back")
    void aLocalEchoIsNotDeliveredTwice() {
        RecordingTemplate template = new RecordingTemplate();
        Fixture fixture = new Fixture(template);

        RealtimeEvent event = messageEvent();
        // The publish the bridge makes: a local delivery, then the envelope on the wire.
        assertThat(fixture.bridge.publish(event)).isEqualTo(1);
        assertThat(fixture.received).hasSize(1);
        assertThat(template.published).hasSize(1);
        assertThat(template.published.get(0)).contains("\"frameId\":\"");

        // Redis broadcasts to the publisher too. The echo carries the id publish() generated, and
        // this instance already delivered it locally, so it must not deliver it a second time.
        assertThat(fixture.bridge.deliverRemote(template.published.get(0))).isZero();
        assertThat(fixture.received).hasSize(1);

        // A second echo is suppressed as well: the id is remembered, not consumed once.
        assertThat(fixture.bridge.deliverRemote(template.published.get(0))).isZero();
        assertThat(fixture.received).hasSize(1);
    }

    @Test
    @DisplayName("suppression is by frame id, not by content: another publisher's copy is delivered")
    void suppressionIsByFrameIdNotByContent() {
        RecordingTemplate template = new RecordingTemplate();
        Fixture fixture = new Fixture(template);

        // This instance publishes a frame and delivers it to its own subscriber.
        RealtimeEvent event = messageEvent();
        assertThat(fixture.bridge.publish(event)).isEqualTo(1);
        assertThat(fixture.received).hasSize(1);

        // Another instance publishes the same frame — same type, tenant, project, resource and
        // instant, its own id. Content equality must not make it look like this instance's echo, or
        // two tenants' subscribers connected to different processes would silently lose the second
        // copy. (And a duplicate is harmless anyway: it is one extra API call.)
        String fromElsewhere = RedisRealtimeBridge.toEnvelope(event).body();
        assertThat(fixture.bridge.deliverRemote(fromElsewhere)).isEqualTo(1);
        assertThat(fixture.received).hasSize(2);

        // This instance's own copy of that same content is still suppressed, by its own id.
        assertThat(fixture.bridge.deliverRemote(template.published.get(0))).isZero();
        assertThat(fixture.received).hasSize(2);
    }

    @Test
    @DisplayName("a broker outage leaves the local delivery done and the frame still publishable")
    void aBrokerOutageDoesNotBreakTheLocalDelivery() {
        Fixture fixture = new Fixture(new StringRedisTemplate() {
            @Override
            public Long convertAndSend(String channel, Object message) {
                throw new IllegalStateException("connection refused");
            }
        });

        // The contract: publish never throws, because a broker outage degrades realtime and must
        // never roll back the domain work that produced the frame.
        assertThat(fixture.bridge.publish(messageEvent())).isEqualTo(1);
        assertThat(fixture.received).hasSize(1);
    }

    // ------------------------------------------------------------------

    /** The registry the bridge under test delivers to, with one entitled subscriber attached. */
    private static final class Fixture {

        private final RealtimeSubscriptionRegistry registry = new RealtimeSubscriptionRegistry();
        private final List<RealtimeEvent> received = new ArrayList<>();
        private final RedisRealtimeBridge bridge;

        private Fixture(StringRedisTemplate template) {
            registry.register(new RealtimeSubscriptionRegistry.Subscriber("test-subscriber", ORG,
                USER, Set.of(PROJECT), Set.of(RealtimeTopicPolicy.projectTopic(ORG, PROJECT)),
                received::add));
            bridge = new RedisRealtimeBridge(registry, template,
                new ObjectMapper().findAndRegisterModules());
        }
    }

    private static RealtimeEvent messageEvent() {
        return new RealtimeEvent(RealtimeEventType.MESSAGE_CREATED, 1, ORG, PROJECT, null,
            UUID.randomUUID(), UtcTime.now(), java.util.Map.of("messageId",
            UUID.randomUUID().toString()));
    }

    /** The template the bridge publishes through, recording instead of connecting. */
    static class RecordingTemplate extends StringRedisTemplate {

        private final List<String> published = new ArrayList<>();

        @Override
        public Long convertAndSend(String channel, Object message) {
            published.add(String.valueOf(message));
            return 1L;
        }
    }
}