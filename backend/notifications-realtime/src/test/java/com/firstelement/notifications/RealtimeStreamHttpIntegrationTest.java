package com.firstelement.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.firstelement.notifications.persistence.NotificationRepository;
import com.firstelement.notifications.realtime.RealtimeSubscriptionRegistry;
import com.firstelement.notifications.service.NotificationService;
import com.firstelement.security.TenantAuthentication;
import com.firstelement.security.TenantContext;
import com.firstelement.security.TenantContextHolder;
import com.firstelement.security.UserRole;
import com.firstelement.security.config.SecurityProperties;
import com.firstelement.security.realtime.RealtimeTopicPolicy;
import com.firstelement.security.session.BrowserSessionStore;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * ADR-0003's requirement, over a real HTTP server: "Realtime tests must prove resync-from-API after
 * reconnect, not trust missed frames."
 *
 * <h2>What is actually proved here</h2>
 *
 * <ol>
 *   <li>The stream is <b>SSE</b>, not a WebSocket, and it is <b>authenticated</b> &mdash; there is
 *       no second authentication path, so an anonymous tab gets nothing.</li>
 *   <li>The <b>first frame on every connection is {@code resync}</b>, and it names the API endpoint
 *       the client must refetch from. A reconnect gets it again, which is what makes a dropped frame
 *       harmless.</li>
 *   <li>A <b>cross-tenant topic is refused</b> and recorded; the subscriber never receives a frame
 *       published on it.</li>
 *   <li>The resync target answers with the row the frame deliberately did not carry, so the client's
 *       stale view is repairable from the API alone.</li>
 * </ol>
 *
 * <h2>Why a real server, and why the frames are read with a deadline</h2>
 *
 * <p>MockMvc has no servlet container behind it, so nothing enforces
 * {@code firstelement.realtime.stream-timeout-seconds} and {@code asyncDispatch(...)} on an open
 * {@code SseEmitter} waits for an event only a container timeout can produce. The previous version of
 * this test parked a thread on that latch forever, which is a hang and not a pass. A
 * {@code RANDOM_PORT} server plus a real HTTP client is the honest way to read a never-ending stream:
 *
 * <ul>
 *   <li>the {@code Content-Type} and the {@code event:} / {@code data:} framing a browser actually
 *       receives are asserted, not MockMvc's assembled {@code MockHttpServletResponse};</li>
 *   <li>the session cookie is a real revocable {@code sessions} row resolved by
 *       {@code BrowserSessionAuthenticationFilter}, so the authentication guarantee is the one the
 *       product ships rather than a {@code SecurityMockMvcRequestPostProcessors} shortcut;</li>
 *   <li>the read has an explicit deadline, and closing the response body is what closing a browser tab
 *       does. A stream that stops producing frames <em>fails</em> the test instead of parking a
 *       thread forever.</li>
 * </ul>
 *
 * <p>{@code NotificationsIntegrationTestBase} additionally pins the emitter timeout to its 30s floor,
 * so even a client that failed to abort would end the stream server-side and fail the assertions
 * rather than hang.
 */
@SpringBootTest(classes = NotificationsTestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RealtimeStreamHttpIntegrationTest extends NotificationsIntegrationTestBase {

    /** Long enough for a cold server, short enough that a stalled stream fails rather than hangs. */
    private static final long READ_DEADLINE_SECONDS = 20;

    /**
     * Daemon threads, and static: a JUnit test instance is per-method, so an instance field would
     * leak a pool per test, and a non-daemon reader could hold the Gradle worker JVM open.
     */
    private static final ExecutorService READER = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "fe-sse-test-reader");
        thread.setDaemon(true);
        return thread;
    });

    @LocalServerPort
    private int port;
    @Autowired
    private TestRestTemplate http;
    @Autowired
    private BrowserSessionStore sessions;
    @Autowired
    private SecurityProperties security;
    @Autowired
    private NotificationService notifications;
    @Autowired
    private NotificationRepository repository;
    @Autowired
    private RealtimeSubscriptionRegistry registry;
    @Autowired
    private NotificationsFixture fixture;

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .version(HttpClient.Version.HTTP_1_1)
        .build();

    @BeforeEach
    void reset() {
        fixture.reset();
    }

    @Test
    @DisplayName("the stream is authenticated SSE and its first frame is a resync instruction")
    void theFirstFrameIsAlwaysResync() throws Exception {
        String wire = readStream(2, "/api/v1/realtime/stream?topic="
            + urlEncode(RealtimeTopicPolicy.projectTopic(NotificationsFixture.ORG_A,
            NotificationsFixture.PROJECT_A)), sessionFor(NotificationsFixture.USER_A));

        assertThat(wire).contains("event:resync");
        assertThat(wire).contains("/api/v1/realtime/state");
        assertThat(wire).contains("\"version\":1");
        // The keepalive proves the path is alive and is what stops a proxy closing an idle stream.
        assertThat(wire).contains("event:heartbeat");
        // Resync comes before anything else on the wire: the client must be told it is stale before
        // it is told about anything new.
        assertThat(wire.indexOf("event:resync")).isLessThan(wire.indexOf("event:heartbeat"));
    }

    @Test
    @DisplayName("the Accept header cannot be used to choose the security outcome")
    void contentNegotiationDoesNotDecideWhetherAuthenticationIsRequired() {
        // Regression guard. The boundary matcher used to fold the `produces` condition into "is this
        // path mapped", so a caller could pick its own answer by sending a different Accept header:
        // an unauthenticated request that could not satisfy text/event-stream was permitted at the
        // security layer and then answered 406 instead of 401. No handler ran and nothing leaked,
        // but a client-controlled header deciding whether authentication applies is a default-deny
        // hole. Both Accepts must now get the same 401.
        for (String accept : new String[] {
                MediaType.TEXT_EVENT_STREAM_VALUE,
                MediaType.APPLICATION_JSON_VALUE,
                MediaType.ALL_VALUE }) {
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.ACCEPT, accept);
            ResponseEntity<String> response = http.exchange("/api/v1/realtime/stream", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
            assertThat(response.getStatusCode().value())
                .as("Accept: %s must still require authentication", accept)
                .isEqualTo(401);
        }
    }

    @Test
    @DisplayName("an unauthenticated tab gets nothing: realtime rides the cookie session")
    void theStreamIsNotPublic() {
        // What a browser's EventSource actually sends. It matters: a request whose Accept cannot
        // satisfy the endpoint's `produces` is treated by the boundary as an unmapped path, so the
        // interesting case is the one a real client makes.
        HttpHeaders eventStreamAccept = new HttpHeaders();
        eventStreamAccept.set(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE);
        ResponseEntity<String> response = http.exchange("/api/v1/realtime/stream", HttpMethod.GET,
            new HttpEntity<>(eventStreamAccept), String.class);

        // Not an SSE stream, not a resync frame, not a heartbeat: the stable error contract.
        assertThat(response.getStatusCode().value()).isEqualTo(401);
        MediaType errorType = response.getHeaders().getContentType();
        assertThat(errorType).isNotNull();
        assertThat(errorType.isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();
        assertThat(errorType.isCompatibleWith(MediaType.TEXT_EVENT_STREAM)).isFalse();
        assertThat(response.getBody()).contains("AUTHENTICATION_REQUIRED");
        assertThat(response.getBody()).doesNotContain("resync");

        // The resync target is closed too: it is the same cookie session, not something weaker.
        assertThat(http.getForEntity("/api/v1/realtime/state", JsonNode.class).getStatusCode().value())
            .isEqualTo(401);
    }

    @Test
    @DisplayName("after a reconnect the client is told to resync again, and the API answers")
    void reconnectResyncsFromTheApi() throws Exception {
        // A notification exists while nothing is connected: the frame is missed entirely.
        UUID id = as(staffA(), () -> notifications.create(staffA(), NotificationsFixture.PROJECT_A,
            NotificationsFixture.USER_A, NotificationRepository.MENTION, "Missed while offline",
            null, "Conversation", UUID.randomUUID()));
        assertThat(repository.unreadCount(staffA())).isEqualTo(1);

        // The reconnect: a brand new connection, exactly what the browser does after a blip.
        String wire = readStream(2, "/api/v1/realtime/stream",
            sessionFor(NotificationsFixture.USER_A));
        assertThat(wire).contains("event:resync");
        assertThat(wire).contains("/api/v1/realtime/state");

        // And the resync target is authoritative: it answers with the row the frame never carried.
        JsonNode state = getState(sessionFor(NotificationsFixture.USER_A));
        assertThat(state.get("unreadCount").asInt()).isEqualTo(1);
        assertThat(state.get("notifications").size()).isEqualTo(1);
        assertThat(state.get("notifications").get(0).get("id").asText()).isEqualTo(id.toString());
        assertThat(state.get("notifications").get(0).get("title").asText())
            .isEqualTo("Missed while offline");
        assertThat(state.get("notifications").get(0).get("read").asBoolean()).isFalse();
        assertThat(state.get("organizationId").asText())
            .isEqualTo(NotificationsFixture.ORG_A.toString());
        assertThat(state.get("realtime").has("multiInstanceCapable")).isTrue();
    }

    @Test
    @DisplayName("another tenant's topic is refused, recorded, and never delivered")
    void aForeignTopicIsRefusedAndRecorded() throws Exception {
        String foreignProject = RealtimeTopicPolicy.projectTopic(NotificationsFixture.ORG_B,
            NotificationsFixture.PROJECT_B);

        String wire = readStream(2, "/api/v1/realtime/stream?topic=" + urlEncode(foreignProject),
            sessionFor(NotificationsFixture.USER_A));

        // The connection still opens — one bad topic must not become an information leak either way
        // — and what is on it is the resync plus the keepalive. The refused topic never became a
        // subscription, so no frame on it can arrive.
        assertThat(wire).contains("event:resync");
        assertThat(wire).contains("event:heartbeat");

        // Refused, and visible to an operator: a probing client is not silent. The event type is in
        // `action`; the refused topic and its reason are in the metadata.
        assertThat(fixture.count("select count(*) from audit_logs"
            + " where action = 'Realtime.SubscriptionDenied'"
            + " and \"organizationId\" = '" + NotificationsFixture.ORG_A + "'"
            + " and \"resourceType\" = 'RealtimeTopic'"
            + " and \"metadata\"->>'topic' = '" + foreignProject + "'")).isEqualTo(1);

        // And a frame on a topic the subscriber was refused never reaches it.
        ArrayList<com.firstelement.notifications.event.RealtimeEvent> received = new ArrayList<>();
        String subscriberId = registry.register(new RealtimeSubscriptionRegistry.Subscriber(
            "refused-topic-" + UUID.randomUUID(), NotificationsFixture.ORG_A,
            NotificationsFixture.USER_A, Set.of(NotificationsFixture.PROJECT_A),
            // The empty allowed set is exactly what the refusal leaves behind.
            Set.of(), received::add));
        try {
            registry.deliver(new com.firstelement.notifications.event.RealtimeEvent(
                com.firstelement.notifications.event.RealtimeEventType.CONVERSATION_CHANGED, 1,
                NotificationsFixture.ORG_B, NotificationsFixture.PROJECT_B, null, UUID.randomUUID(),
                com.firstelement.worker.config.UtcTime.now(), java.util.Map.of()));
            assertThat(received).isEmpty();
        } finally {
            registry.remove(subscriberId);
        }
    }

    @Test
    @DisplayName("a notification written for one user is not readable or markable by another")
    void notificationReadsAreUserScopedOverHttp() throws Exception {
        UUID id = as(staffA(), () -> notifications.create(staffA(), NotificationsFixture.PROJECT_A,
            NotificationsFixture.USER_A, NotificationRepository.MENTION, "Only for staff-a", null,
            "Conversation", UUID.randomUUID()));

        // Two real sessions in one tenant: the row must not be visible to the other user, and the
        // claim is proved with the session cookie rather than an injected security context.
        String staffCookie = sessionFor(NotificationsFixture.USER_A);
        String leadCookie = sessionFor(NotificationsFixture.USER_A2);

        ResponseEntity<JsonNode> asLead = http.exchange("/api/v1/notifications", HttpMethod.GET,
            new HttpEntity<>(headers(leadCookie)), JsonNode.class);
        assertThat(asLead.getStatusCode().value()).isEqualTo(200);
        assertThat(asLead.getBody().size()).as("another user in the same tenant sees nothing").isZero();

        // Marking someone else's notification read is reported exactly like a missing one.
        assertThat(markRead(leadCookie, id).get("updated").asBoolean()).isFalse();
        assertThat(markRead(staffCookie, id).get("updated").asBoolean()).isTrue();
    }

    // ------------------------------------------------------------------
    // Wire helpers
    // ------------------------------------------------------------------

    /**
     * Opens the stream over real HTTP and reads it until {@code expectedFrames} {@code event:} lines
     * have arrived, the server ends the response, or the deadline expires.
     *
     * <p>Three ways to stop, and only one of them is a pass. The body is closed in a
     * {@code finally} &mdash; that is how a closed browser tab reaches the server, and it is what
     * makes this terminate even when no frame ever arrives.
     */
    private String readStream(int expectedFrames, String pathAndQuery, String cookie)
        throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + pathAndQuery))
            .header("Accept", MediaType.TEXT_EVENT_STREAM_VALUE)
            .header(HttpHeaders.COOKIE, cookie)
            .timeout(Duration.ofSeconds(READ_DEADLINE_SECONDS))
            .GET()
            .build();

        HttpResponse<InputStream> response =
            httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

        assertThat(response.statusCode())
            .as("the stream must be an accepted 200 text/event-stream")
            .isEqualTo(200);
        assertThat(response.headers().firstValue(HttpHeaders.CONTENT_TYPE))
            .as("a browser must be told this is text/event-stream")
            .hasValueSatisfying(value -> assertThat(value)
                .startsWith(MediaType.TEXT_EVENT_STREAM_VALUE));

        StringBuilder wire = new StringBuilder();
        CountDownLatch frames = new CountDownLatch(expectedFrames);
        InputStream body = response.body();
        READER.submit(() -> {
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(body, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    wire.append(line).append('\n');
                    if (line.startsWith("event:") && frames.getCount() > 0) {
                        frames.countDown();
                    }
                }
            } catch (IOException e) {
                // The client aborted, or the server hung up. The assertions below decide whether
                // what arrived was enough; an incomplete read fails there rather than passing quietly.
            } finally {
                // Release the waiter on EOF too, so a server-closed stream fails on the frame count
                // instead of on the deadline.
                while (frames.getCount() > 0) {
                    frames.countDown();
                }
            }
        });

        try {
            frames.await(READ_DEADLINE_SECONDS, TimeUnit.SECONDS);
        } finally {
            body.close();
        }

        assertThat(frames.getCount())
            .as("the stream produced no more than the %d frame(s) it ended with:\n%s", expectedFrames,
                wire)
            .isZero();
        return wire.toString();
    }

    // ------------------------------------------------------------------
    // API helpers, over the same real session cookie
    // ------------------------------------------------------------------

    private JsonNode getState(String cookie) {
        ResponseEntity<JsonNode> response = http.exchange("/api/v1/realtime/state", HttpMethod.GET,
            new HttpEntity<>(headers(cookie)), JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return response.getBody();
    }

    /**
     * Marks a notification read the way the SPA does: the session cookie <em>and</em> the CSRF token
     * the cookie repository issued. A mutation path that only worked with a test-injected security
     * context would prove nothing about the product.
     */
    private JsonNode markRead(String cookie, UUID notificationId) {
        ResponseEntity<JsonNode> token = http.getForEntity("/api/v1/auth/csrf", JsonNode.class);
        assertThat(token.getStatusCode().value()).isEqualTo(200);
        String csrfToken = token.getBody().get("token").asText();
        assertThat(csrfToken).isNotBlank();

        HttpHeaders csrfHeaders = headers(cookie + "; " + security.getCsrfCookieName() + "=" + csrfToken);
        csrfHeaders.set(security.getCsrfHeaderName(), csrfToken);

        ResponseEntity<JsonNode> response = http.exchange("/api/v1/notifications/{id}/read",
            HttpMethod.POST, new HttpEntity<>(csrfHeaders), JsonNode.class, notificationId);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return response.getBody();
    }

    private HttpHeaders headers(String cookie) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.COOKIE, cookie);
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
        return headers;
    }

    /**
     * Issues a real revocable session and returns the cookie header value. The raw token exists in
     * this method and in the outgoing header, is never logged, and the server stores only its
     * SHA-256.
     */
    private String sessionFor(UUID userId) {
        BrowserSessionStore.IssuedSession issued =
            sessions.issue(userId, "realtime-http-integration-test", "127.0.0.1", 1);
        assertThat(issued).as("a session was issued for user %s", userId).isNotNull();
        assertThat(issued.rawToken()).isNotBlank();
        return security.getSessionCookieName() + "=" + issued.rawToken();
    }

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    private static String urlEncode(String topic) {
        return java.net.URLEncoder.encode(topic, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------
    // Tenant scope for the writes this suite performs directly
    // ------------------------------------------------------------------

    private static TenantContext staffA() {
        return TenantContext.forUser(UUID.randomUUID(), NotificationsFixture.USER_A,
            NotificationsFixture.ORG_A, UserRole.EMPLOYEE,
            Set.of(NotificationsFixture.PROJECT_A));
    }

    private static <T> T as(TenantContext context, java.util.function.Supplier<T> work) {
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext scoped = SecurityContextHolder.createEmptyContext();
        scoped.setAuthentication(new TenantAuthentication(context));
        SecurityContextHolder.setContext(scoped);
        try {
            return TenantContextHolder.call(context, work);
        } finally {
            SecurityContextHolder.setContext(previous);
            TenantContextHolder.clear();
        }
    }
}