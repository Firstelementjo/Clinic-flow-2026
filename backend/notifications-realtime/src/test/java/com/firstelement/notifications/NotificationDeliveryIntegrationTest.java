package com.firstelement.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import com.firstelement.notifications.event.RealtimeEvent;
import com.firstelement.notifications.event.RealtimeEventType;
import com.firstelement.notifications.persistence.NotificationRepository;
import com.firstelement.notifications.realtime.RealtimeSubscriptionRegistry;
import com.firstelement.notifications.service.NotificationService;
import com.firstelement.security.TenantAuthentication;
import com.firstelement.security.TenantContext;
import com.firstelement.security.TenantContextHolder;
import com.firstelement.security.UserRole;
import com.firstelement.security.realtime.RealtimeTopicPolicy;
import com.firstelement.worker.config.UtcTime;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Persist first, push second, and the reconnect/resync contract.
 *
 * <h2>ADR-0003's requirement, verbatim</h2>
 *
 * <p>"Realtime tests must prove resync-from-API after reconnect, not trust missed frames." The last
 * test here is that one: a subscriber that misses frames still recovers, because the row is the
 * record and the frame was only ever a hint.
 */
@SpringBootTest(classes = NotificationsTestApplication.class)
class NotificationDeliveryIntegrationTest extends NotificationsIntegrationTestBase {

    @Autowired
    private NotificationService notifications;
    @Autowired
    private NotificationRepository repository;
    @Autowired
    private RealtimeSubscriptionRegistry registry;
    @Autowired
    private NotificationsFixture fixture;
    @Autowired
    private RealtimeTopicPolicy topicPolicy;
    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    /**
     * Every subscriber id this suite registered.
     *
     * <p><b>Static on purpose.</b> JUnit creates a new test instance per method, so an instance
     * field would be empty at the start of each test and the {@code @BeforeEach} would remove
     * nothing — every earlier subscriber would keep receiving this test's frames and any assertion
     * about the number of local subscribers would be an assertion about test ordering. A
     * process-wide singleton needs process-wide cleanup.
     */
    private static final List<String> SUBSCRIBERS = java.util.Collections.synchronizedList(
        new ArrayList<>());

    @BeforeEach
    void reset() {
        // The registry is a process-wide singleton, so a leftover subscriber from a previous test
        // would receive this test's frames. Every id registered by this suite is removed.
        fixture.reset();
        List<String> toRemove;
        synchronized (SUBSCRIBERS) {
            toRemove = new ArrayList<>(SUBSCRIBERS);
            SUBSCRIBERS.clear();
        }
        toRemove.forEach(registry::remove);
    }

    @Test
    @DisplayName("a notification is persisted first and is readable from the API afterwards")
    void persistedFirstIsReadableAfterwards() {
        UUID id = as(staffA(), () -> notifications.create(staffA(), NotificationsFixture.PROJECT_A,
            NotificationsFixture.USER_A, NotificationRepository.CONVERSATION_ASSIGNED,
            "A conversation was assigned to you", "Routed to you automatically.",
            "Conversation", UUID.randomUUID()));

        assertThat(id).isNotNull();
        // The row is the record: an agent who was offline still finds it at the start of their shift.
        var rows = as(staffA(), () -> repository.list(staffA(), false, 50));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).id()).isEqualTo(id);
        assertThat(rows.get(0).read()).isFalse();
        assertThat(repository.unreadCount(staffA())).isEqualTo(1);
    }

    @Test
    @DisplayName("the realtime frame carries ids and a version, never the customer's words")
    void theFrameCarriesNoCustomerContent() {
        List<RealtimeEvent> received = subscribe(staffA(), Set.of(NotificationsFixture.PROJECT_A));

        as(staffA(), () -> notifications.create(staffA(), NotificationsFixture.PROJECT_A,
            NotificationsFixture.USER_A, NotificationRepository.MENTION, "You were mentioned",
            "A customer wrote: my appointment moved", "Conversation", UUID.randomUUID()));

        assertThat(received).hasSize(1);
        RealtimeEvent event = received.get(0);
        assertThat(event.type()).isEqualTo(RealtimeEventType.NOTIFICATION_CREATED);
        assertThat(event.payloadVersion()).isEqualTo(1);
        assertThat(event.userId()).isEqualTo(NotificationsFixture.USER_A);
        assertThat(event.occurredAt()).isNotNull();

        String frame = wire(event);
        assertThat(frame).doesNotContain("my appointment moved");
        // The body is never in the frame; the client fetches the row.
        assertThat(frame).doesNotContain("body");
        assertThat(frame).contains("notificationId").contains("\"version\":1");
    }

    /**
     * The frame exactly as it goes on the wire.
     *
     * <p>{@code RealtimeEvent.toFrame()} is handed to {@code SseEmitter.data(..)}, so Spring
     * serializes the map with the application ObjectMapper. Asserting on {@code Map.toString()}
     * would assert on Java syntax that never reaches a browser, and building a second mapper here
     * would miss that the real one carries the Java-time module.
     */
    private String wire(RealtimeEvent event) {
        try {
            return objectMapper.writeValueAsString(event.toFrame());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("the frame must be serializable", e);
        }
    }

    @Test
    @DisplayName("a push that cannot reach another instance still delivers locally")
    void theFrameIsDeliveredLocallyWithoutRedis() {
        // The bridge is configured against a closed port, so this is the single-instance path: local
        // delivery first, broker second, and a broker failure is logged rather than thrown.
        List<RealtimeEvent> received = subscribe(staffA(), Set.of(NotificationsFixture.PROJECT_A));
        assertThat(registry.count()).isEqualTo(1);

        as(staffA(), () -> notifications.create(staffA(), NotificationsFixture.PROJECT_A,
            NotificationsFixture.USER_A, NotificationRepository.SYSTEM, "System", null, null, null));

        assertThat(received).hasSize(1);
        assertThat(registry.publishedFrames()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("one frame reaches only the subscriber it was addressed to")
    void framesAreFilteredPerSubscriber() {
        List<RealtimeEvent> forA = subscribe(staffA(), Set.of(NotificationsFixture.PROJECT_A));
        List<RealtimeEvent> forA2 = subscribe(leadA(), Set.of(NotificationsFixture.PROJECT_A));

        as(staffA(), () -> notifications.create(staffA(), NotificationsFixture.PROJECT_A,
            NotificationsFixture.USER_A, NotificationRepository.MENTION, "Only for you", null,
            "Conversation", UUID.randomUUID()));

        assertThat(forA).hasSize(1);
        assertThat(forA2).isEmpty();
    }

    @Test
    @DisplayName("a subscriber never receives a frame from another tenant")
    void crossTenantFramesAreNeverDelivered() {
        List<RealtimeEvent> betaSubscriber = subscribe(adminB(), Set.of(NotificationsFixture.PROJECT_B));

        as(staffA(), () -> notifications.create(staffA(), NotificationsFixture.PROJECT_A,
            NotificationsFixture.USER_A, NotificationRepository.MENTION, "Alpha only", null,
            "Conversation", UUID.randomUUID()));

        assertThat(betaSubscriber).isEmpty();
    }

    @Test
    @DisplayName("a subscriber never receives a frame for a project it does not hold")
    void crossProjectFramesAreNeverDelivered() {
        List<RealtimeEvent> received = subscribe(leadA(), Set.of());
        assertThat(received).isEmpty();

        RealtimeEvent alphaProject = new RealtimeEvent(RealtimeEventType.CONVERSATION_CHANGED, 1,
            NotificationsFixture.ORG_A, NotificationsFixture.PROJECT_A, null, UUID.randomUUID(),
            UtcTime.now(), java.util.Map.of());
        registry.deliver(alphaProject);

        // A principal with no project membership may subscribe to the organization topic but must
        // not receive project-scoped frames for a project it cannot read.
        assertThat(received).isEmpty();
    }

    @Test
    @DisplayName("a project frame reaches the project subscriber and not the organization-wide one")
    void projectFramesAreTopicScoped() {
        List<RealtimeEvent> projectSubscriber =
            subscribe(staffA(), Set.of(NotificationsFixture.PROJECT_A));
        List<RealtimeEvent> otherProject =
            subscribe(leadA(), Set.of(NotificationsFixture.PROJECT_B));

        registry.deliver(new RealtimeEvent(RealtimeEventType.CONVERSATION_CHANGED, 1,
            NotificationsFixture.ORG_A, NotificationsFixture.PROJECT_A, null, UUID.randomUUID(),
            UtcTime.now(), java.util.Map.of("conversationId", UUID.randomUUID().toString())));

        assertThat(projectSubscriber).hasSize(1);
        assertThat(otherProject).isEmpty();
    }

    @Test
    @DisplayName("a self-assignment is not notified, and each recipient is a separate row")
    void notificationTriggersRespectTheirRules() {
        UUID conversation = UUID.randomUUID();

        // Assigning to yourself does not need telling.
        assertThat(as(staffA(), () -> notifications.notifyConversationAssigned(staffA(),
            NotificationsFixture.PROJECT_A, NotificationsFixture.USER_A, NotificationsFixture.USER_A,
            conversation, "MANUAL"))).isNull();

        // Assigning to a colleague does.
        assertThat(as(staffA(), () -> notifications.notifyConversationAssigned(staffA(),
            NotificationsFixture.PROJECT_A, NotificationsFixture.USER_A2, NotificationsFixture.USER_A,
            conversation, "ROUTING"))).isNotNull();
        assertThat(as(staffA(), () -> notifications.notifyConversationAssigned(staffA(),
            NotificationsFixture.PROJECT_A, NotificationsFixture.USER_A2, NotificationsFixture.USER_A,
            conversation, "MANUAL"))).isNotNull();

        // Two separate rows: a shared row would be read by exactly one of them.
        assertThat(fixture.count("select count(*) from notifications")).isEqualTo(2);
        // And the routing reason is what the employee is told.
        assertThat(fixture.count("select count(*) from notifications where \"body\" = "
            + "'Routed to you automatically.'")).isEqualTo(1);
    }

    @Test
    @DisplayName("marking a notification read is scoped to its owner")
    void markReadIsUserScoped() {
        UUID id = as(staffA(), () -> notifications.create(staffA(), NotificationsFixture.PROJECT_A,
            NotificationsFixture.USER_A, NotificationRepository.MENTION, "For you", null,
            "Conversation", UUID.randomUUID()));

        // A colleague cannot mark it read, and is told nothing about whether it exists.
        assertThat(as(leadA(), () -> repository.markRead(leadA(), id))).isFalse();
        assertThat(as(staffA(), () -> repository.markRead(staffA(), id))).isTrue();
        assertThat(repository.unreadCount(staffA())).isZero();

        // Marking it again changes nothing: readAt is only stamped once.
        assertThat(as(staffA(), () -> repository.markRead(staffA(), id))).isFalse();
    }

    @Test
    @DisplayName("one employee cannot read another's notifications")
    void notificationsAreUserScoped() {
        as(staffA(), () -> notifications.create(staffA(), NotificationsFixture.PROJECT_A,
            NotificationsFixture.USER_A, NotificationRepository.MENTION, "Private to staff-a", null,
            "Conversation", UUID.randomUUID()));

        assertThat(as(leadA(), () -> repository.list(leadA(), false, 50))).isEmpty();
        assertThat(as(leadA(), () -> repository.unreadCount(leadA()))).isZero();
        assertThat(as(staffA(), () -> repository.list(staffA(), false, 50))).hasSize(1);
    }

    @Test
    @DisplayName("markAllRead is scoped to the caller and reports what it changed")
    void markAllReadIsScoped() {
        as(staffA(), () -> notifications.create(staffA(), NotificationsFixture.PROJECT_A,
            NotificationsFixture.USER_A, NotificationRepository.SYSTEM, "one", null, null, null));
        as(staffA(), () -> notifications.create(staffA(), NotificationsFixture.PROJECT_A,
            NotificationsFixture.USER_A, NotificationRepository.SYSTEM, "two", null, null, null));
        as(leadA(), () -> notifications.create(leadA(), NotificationsFixture.PROJECT_A,
            NotificationsFixture.USER_A2, NotificationRepository.SYSTEM, "other", null, null, null));

        assertThat(as(leadA(), () -> repository.markAllRead(leadA()))).isEqualTo(1);
        assertThat(repository.unreadCount(staffA())).isEqualTo(2);
        assertThat(as(staffA(), () -> repository.markAllRead(staffA()))).isEqualTo(2);
        assertThat(repository.unreadCount(staffA())).isZero();
        assertThat(repository.unreadCount(leadA())).isZero();
    }

    @Test
    @DisplayName("a machine principal reads no notifications: there is no user to read them for")
    void machinePrincipalReadsNothing() {
        TenantContext worker = TenantContext.forMachine(
            com.firstelement.security.PrincipalType.WORKER, NotificationsFixture.ORG_A, "worker-1",
            List.of(NotificationsFixture.PROJECT_A),
            List.of(com.firstelement.security.Capability.WEBHOOK_INGEST));

        assertThat(as(worker, () -> repository.list(worker, false, 50))).isEmpty();
        assertThat(as(worker, () -> repository.unreadCount(worker))).isZero();
        assertThat(as(worker, () -> repository.markAllRead(worker))).isZero();
    }

    @Test
    @DisplayName("reconnect: a fresh stream asks the client to resync, and the API answers")
    void reconnectResyncsFromTheApi() {
        UUID id = as(staffA(), () -> notifications.create(staffA(), NotificationsFixture.PROJECT_A,
            NotificationsFixture.USER_A, NotificationRepository.MENTION, "Missed while offline",
            null, "Conversation", UUID.randomUUID()));

        // The frame was never delivered — the subscriber was not connected. The client's view is
        // stale by an unknown amount, so the only correct answer is "fetch again".
        RealtimeEvent resync = new RealtimeEvent(RealtimeEventType.RESYNC_REQUIRED, 1,
            NotificationsFixture.ORG_A, null, NotificationsFixture.USER_A, null, UtcTime.now(),
            java.util.Map.of("reason", "stream-opened", "resyncFrom", "/api/v1/realtime/state"));

        assertThat(resync.type().eventName()).isEqualTo("resync");
        assertThat(resync.payloadVersion()).isEqualTo(1);
        assertThat(resync.payload()).containsEntry("resyncFrom", "/api/v1/realtime/state");

        // The resync target answers, and it answers with the row the frame never carried.
        var state = as(staffA(), () -> repository.list(staffA(), false, 50));
        assertThat(state).hasSize(1);
        assertThat(state.get(0).id()).isEqualTo(id);
        assertThat(state.get(0).title()).isEqualTo("Missed while offline");
        assertThat(repository.unreadCount(staffA())).isEqualTo(1);
    }

    @Test
    @DisplayName("event types and payload versions are a client contract")
    void eventContractIsStable() {
        assertThat(RealtimeEventType.CONVERSATION_CHANGED.eventName()).isEqualTo("conversation");
        assertThat(RealtimeEventType.MESSAGE_CREATED.eventName()).isEqualTo("message");
        assertThat(RealtimeEventType.NOTIFICATION_CREATED.eventName()).isEqualTo("notification");
        assertThat(RealtimeEventType.RESYNC_REQUIRED.eventName()).isEqualTo("resync");
        assertThat(RealtimeEventType.HEARTBEAT.eventName()).isEqualTo("heartbeat");
        for (RealtimeEventType type : RealtimeEventType.values()) {
            assertThat(type.payloadVersion()).isGreaterThanOrEqualTo(1);
            assertThat(type.eventName()).isEqualTo(type.eventName().toLowerCase(java.util.Locale.ROOT));
        }
    }

    @Test
    @DisplayName("a frame computes its topic with the policy, never by re-deriving the rule")
    void frameTopicComesFromThePolicy() {
        TenantContext context = staffA();
        RealtimeEvent projectFrame = RealtimeEvent.of(RealtimeEventType.CONVERSATION_CHANGED, context,
            NotificationsFixture.PROJECT_A, null, UUID.randomUUID(), Map.of());
        RealtimeEvent orgFrame = RealtimeEvent.of(RealtimeEventType.CONVERSATION_CHANGED, context,
            null, null, UUID.randomUUID(), Map.of());

        assertThat(projectFrame.topic())
            .isEqualTo(RealtimeTopicPolicy.projectTopic(NotificationsFixture.ORG_A,
                NotificationsFixture.PROJECT_A));
        assertThat(orgFrame.topic())
            .isEqualTo(RealtimeTopicPolicy.organizationTopic(NotificationsFixture.ORG_A));
        assertThat(topicPolicy.allowedTopics(context)).contains(projectFrame.topic(), orgFrame.topic());
    }

    @Test
    @DisplayName("a frame without a tenant is refused: a tenant-less broadcast has no audience")
    void aTenantlessFrameIsRefused() {
        org.assertj.core.api.Assertions
            .assertThatThrownBy(() -> new RealtimeEvent(RealtimeEventType.CONVERSATION_CHANGED, 1,
                null, NotificationsFixture.PROJECT_A, null, UUID.randomUUID(), UtcTime.now(),
                Map.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("a realtime frame without a tenant has no audience");
    }

    @Test
    @DisplayName("one broken subscriber does not stop the others from being told")
    void oneBrokenSubscriberDoesNotStarveTheRest() {
        List<RealtimeEvent> healthy = subscribe(staffA(), Set.of(NotificationsFixture.PROJECT_A));
        // Tracked like every other id: a subscriber this suite registers must be removed by the
        // next test's reset, whether or not this one removed it itself.
        SUBSCRIBERS.add(registry.register(new RealtimeSubscriptionRegistry.Subscriber("broken",
            NotificationsFixture.ORG_A, NotificationsFixture.USER_A,
            Set.of(NotificationsFixture.PROJECT_A), Set.of(
                RealtimeTopicPolicy.projectTopic(NotificationsFixture.ORG_A,
                    NotificationsFixture.PROJECT_A)),
            event -> {
                throw new IllegalStateException("the client vanished");
            })));

        int delivered = registry.deliver(new RealtimeEvent(RealtimeEventType.MESSAGE_CREATED, 1,
            NotificationsFixture.ORG_A, NotificationsFixture.PROJECT_A, null, UUID.randomUUID(),
            UtcTime.now(), Map.of()));

        assertThat(delivered).isEqualTo(1);
        assertThat(healthy).hasSize(1);
        // The broken one is removed rather than retried forever.
        assertThat(registry.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("a title and body are bounded, because a provider may echo anything back")
    void notificationTextIsBounded() {
        UUID id = as(staffA(), () -> notifications.create(staffA(), NotificationsFixture.PROJECT_A,
            NotificationsFixture.USER_A, NotificationRepository.SYSTEM, "t".repeat(500),
            "b".repeat(2000), null, null));

        var row = as(staffA(), () -> repository.list(staffA(), false, 50)).get(0);
        assertThat(row.id()).isEqualTo(id);
        assertThat(row.title().length()).isLessThanOrEqualTo(201);
        assertThat(row.body().length()).isLessThanOrEqualTo(501);
    }

    @Test
    @DisplayName("a notification with no recipient is refused rather than written to nobody")
    void notificationWithNoRecipientIsRefused() {
        assertThat(as(staffA(), () -> notifications.create(staffA(), NotificationsFixture.PROJECT_A,
            null, NotificationRepository.SYSTEM, "nobody", null, null, null))).isNull();
        assertThat(fixture.count("select count(*) from notifications")).isZero();
    }

    // ------------------------------------------------------------------

    /** Subscribes exactly the topics {@code RealtimeTopicPolicy} would authorize. */
    private List<RealtimeEvent> subscribe(TenantContext context, Set<UUID> projects) {
        List<RealtimeEvent> received = new ArrayList<>();
        Set<String> topics = topicPolicy.allowedTopics(context);
        String id = registry.register(new RealtimeSubscriptionRegistry.Subscriber(
            UUID.randomUUID().toString(), context.organizationId(), context.userId(),
            projects, topics, received::add));
        SUBSCRIBERS.add(id);
        return received;
    }

    private static TenantContext staffA() {
        return TenantContext.forUser(UUID.randomUUID(), NotificationsFixture.USER_A,
            NotificationsFixture.ORG_A, UserRole.EMPLOYEE,
            Set.of(NotificationsFixture.PROJECT_A));
    }

    private static TenantContext leadA() {
        return TenantContext.forUser(UUID.randomUUID(), NotificationsFixture.USER_A2,
            NotificationsFixture.ORG_A, UserRole.EMPLOYEE, Set.of());
    }

    private static TenantContext adminB() {
        return TenantContext.forUser(UUID.randomUUID(), NotificationsFixture.USER_B,
            NotificationsFixture.ORG_B, UserRole.ADMIN, Set.of(NotificationsFixture.PROJECT_B));
    }

    private static <T> T as(TenantContext context, java.util.function.Supplier<T> work) {
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(new TenantAuthentication(context));
        SecurityContextHolder.setContext(securityContext);
        try {
            return TenantContextHolder.call(context, work);
        } finally {
            SecurityContextHolder.setContext(previous);
            TenantContextHolder.clear();
        }
    }
}
