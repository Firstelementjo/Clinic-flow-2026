package com.firstelement.observ;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.firstelement.security.permission.Permission;
import com.firstelement.security.permission.RolePermissions;
import com.firstelement.security.session.BrowserSessionStore;
import com.firstelement.worker.queue.QueueName;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * The operations routes over real HTTP: the second half of the FE-WEB-003 evidence.
 *
 * <h2>Why a real server here and not MockMvc</h2>
 *
 * <p>The point of these assertions is that the product's own enforcement refuses a caller. A security
 * context injected into a test thread is not what the product ships, so each request here carries a
 * real revocable session cookie resolved by the product's own filter, and the CSRF token the cookie
 * repository issues for the two mutation routes. A test that proved the refusal only under an injected
 * context would pass while the deployed route was open.
 *
 * <h2>The 404 case is asserted as carefully as the 403</h2>
 *
 * <p>An operational diagnosis endpoint is the most natural id-probe there is: an attacker wants to know
 * whether a given outbox event id exists in some other tenant. The route therefore answers a foreign
 * id and a nonexistent id identically, and that is asserted over HTTP where the status code is what a
 * client actually sees.
 */
@SpringBootTest(classes = ObservTestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OperationsRoutePermissionHttpIntegrationTest extends ObservIntegrationTestBase {

    private static final List<Class<?>> CONTROLLERS = List.of(
        com.firstelement.observ.web.OperationsController.class);

    @Autowired
    private TestRestTemplate http;
    @Autowired
    private BrowserSessionStore sessions;
    @Autowired
    private com.firstelement.security.config.SecurityProperties securityProperties;
    @Autowired
    private ObservFixture fixture;

    @BeforeEach
    void reset() {
        fixture.reset();
    }

    // ==================================================================
    // declarative
    // ==================================================================

    @Nested
    @DisplayName("every operations route names its permission")
    class Declarations {

        @Test
        @DisplayName("no handler method is missing @RequirePermission")
        void everyHandlerDeclaresAPermission() {
            List<String> undeclared = new ArrayList<>();
            int declared = 0;

            for (Class<?> controller : CONTROLLERS) {
                for (Method method : controller.getDeclaredMethods()) {
                    if (!isHandler(method)) {
                        continue;
                    }
                    var annotation =
                        method.getAnnotation(com.firstelement.security.annotation.RequirePermission.class);
                    if (annotation == null || annotation.value() == null) {
                        undeclared.add(controller.getSimpleName() + "#" + method.getName());
                    } else {
                        declared++;
                        assertThat(RolePermissions.forRole(
                            com.firstelement.security.UserRole.ADMIN))
                            .as("%s#%s declares %s", controller.getSimpleName(), method.getName(),
                                annotation.value().value())
                            .contains(annotation.value());
                    }
                }
            }

            assertThat(undeclared)
                .as("FE-WEB-003 covers operations-observability as well as Admin")
                .isEmpty();
            assertThat(declared).isEqualTo(13);
        }

        private boolean isHandler(Method method) {
            return java.lang.reflect.Modifier.isPublic(method.getModifiers())
                && (method.isAnnotationPresent(
                        org.springframework.web.bind.annotation.GetMapping.class)
                    || method.isAnnotationPresent(
                        org.springframework.web.bind.annotation.PostMapping.class));
        }
    }

    // ==================================================================
    // behavioural
    // ==================================================================

    @Nested
    @DisplayName("over real HTTP")
    class Behaviour {

        @Test
        @DisplayName("an employee is refused every operations read route")
        void anEmployeeIsRefusedEveryReadRoute() {
            String cookie = sessionFor(ObservFixture.EMPLOYEE_A);

            // The three the employee grant genuinely does not cover. KNOWLEDGE_READ and AI_AGENT_READ
            // are employee grants, so those two panels are answered — a fact the grant table asserts
            // rather than this suite assuming.
            for (String route : List.of(
                "/api/v1/operations/jobs",
                "/api/v1/operations/jobs/worklist",
                "/api/v1/operations/jobs/stranded-leases",
                "/api/v1/operations/realtime",
                "/api/v1/operations/workflows",
                "/api/v1/operations/workflow-failures",
                "/api/v1/operations/connectors",
                "/api/v1/operations/webhooks")) {
                assertThat(status(cookie, HttpMethod.GET, route, null))
                    .as("%s must refuse an employee", route)
                    .isEqualTo(403);
            }
        }

        @Test
        @DisplayName("an employee cannot replay, and the event is untouched")
        void anEmployeeCannotReplay() {
            UUID eventId = fixture.deadOutboxEvent(ObservFixture.ORG_A, ObservFixture.PROJECT_A,
                QueueName.INCOMING_MESSAGE.wireValue(), 12, "attempts exhausted");
            String cookie = sessionFor(ObservFixture.EMPLOYEE_A);

            assertThat(post(cookie, "/api/v1/operations/jobs/" + eventId + "/replay", null))
                .isEqualTo(403);
            // STATE, not the status code.
            assertThat(fixture.count("select 1 from outbox_events"
                + " where \"id\" = :id and \"state\" = 'DEAD'", java.util.Map.of("id", eventId)))
                .isEqualTo(1);
        }

        @Test
        @DisplayName("an administrator is answered, so the 403 above is about the permission")
        void anAdministratorIsAnswered() {
            String cookie = sessionFor(ObservFixture.ADMIN_A);

            for (String route : List.of(
                "/api/v1/operations/jobs",
                "/api/v1/operations/jobs/worklist",
                "/api/v1/operations/jobs/stranded-leases",
                "/api/v1/operations/realtime",
                "/api/v1/operations/workflows",
                "/api/v1/operations/workflow-failures",
                "/api/v1/operations/connectors",
                "/api/v1/operations/webhooks",
                "/api/v1/operations/search/freshness",
                "/api/v1/operations/search/gaps")) {
                assertThat(status(cookie, HttpMethod.GET, route, null))
                    .as("%s must answer an administrator", route)
                    .isEqualTo(200);
            }
        }

        @Test
        @DisplayName("a foreign outbox event id is 404, identical to a nonexistent one")
        void aForeignEventIdIsNotFound() {
            UUID alphaEvent = fixture.deadOutboxEvent(ObservFixture.ORG_A, ObservFixture.PROJECT_A,
                QueueName.INCOMING_MESSAGE.wireValue(), 12, "attempts exhausted");
            String cookie = sessionFor(ObservFixture.ADMIN_B);

            int foreign = status(cookie, HttpMethod.GET,
                "/api/v1/operations/jobs/" + alphaEvent, null);
            int nonexistent = status(cookie, HttpMethod.GET,
                "/api/v1/operations/jobs/" + UUID.randomUUID(), null);

            assertThat(foreign).isEqualTo(404);
            assertThat(nonexistent).isEqualTo(404);
        }

        @Test
        @DisplayName("the diagnosis answers without the payload and without the error messages")
        void theDiagnosisIsContentFreeOverHttp() {
            UUID eventId = fixture.deadOutboxEvent(ObservFixture.ORG_A, ObservFixture.PROJECT_A,
                QueueName.INCOMING_MESSAGE.wireValue(), 12, "attempts exhausted");
            String cookie = sessionFor(ObservFixture.ADMIN_A);

            JsonNode diagnosis = get(cookie, "/api/v1/operations/jobs/" + eventId);

            assertThat(diagnosis.get("event").get("state").asText()).isEqualTo("DEAD");
            assertThat(diagnosis.get("attempts")).isNotEmpty();
            assertThat(diagnosis.get("deadLetters")).hasSize(1);
            assertThat(diagnosis.get("replayAssessment").get("replaySafe").asBoolean()).isTrue();
            assertThat(diagnosis.toString())
                .doesNotContain(ObservFixture.PAYLOAD_MARKER)
                .doesNotContain(ObservFixture.ERROR_MESSAGE_MARKER);
        }

        @Test
        @DisplayName("the replay route is the Admin allow-list: the STATE changes and it is audited")
        void theReplayRouteWritesAndAudits() {
            UUID eventId = fixture.deadOutboxEvent(ObservFixture.ORG_A, ObservFixture.PROJECT_A,
                QueueName.INCOMING_MESSAGE.wireValue(), 12, "attempts exhausted");
            String cookie = sessionFor(ObservFixture.ADMIN_A);
            int jobStreamBefore = fixture.tenantRows("job_integration_events", ObservFixture.ORG_A);

            ResponseEntity<JsonNode> response = http.exchange(
                "/api/v1/operations/jobs/" + eventId + "/replay", HttpMethod.POST,
                new HttpEntity<>(postHeaders(cookie)), JsonNode.class);

            assertThat(response.getStatusCode())
                .as("202 Accepted, per ADR-0016 rule 2: the replay moves a DEAD event back to PENDING "
                    + "and returns. No worker has claimed it, so this is accepted work rather than a "
                    + "finished one. Asserting OK here is what kept the route publishing a lie.")
                .isEqualTo(HttpStatus.ACCEPTED);
            assertThat(response.getBody().get("applied").asBoolean()).isTrue();
            assertThat(response.getBody().get("outcome").asText()).isEqualTo("REPLAYED");
            assertThat(fixture.count("select 1 from outbox_events"
                + " where \"id\" = :id and \"state\" = 'PENDING'", java.util.Map.of("id", eventId)))
                .as("STATE, not the response body")
                .isEqualTo(1);
            assertThat(fixture.tenantRows("job_integration_events", ObservFixture.ORG_A))
                .isGreaterThan(jobStreamBefore);
        }

        @Test
        @DisplayName("an undeclared queue is refused over HTTP, with the reason in the body")
        void anUndeclaredQueueIsRefusedOverHttp() {
            UUID eventId = fixture.deadOutboxEvent(ObservFixture.ORG_A, ObservFixture.PROJECT_A,
                QueueName.AI_RESPONSE.wireValue(), 12, "model refused the request");
            String cookie = sessionFor(ObservFixture.ADMIN_A);

            ResponseEntity<JsonNode> response = http.exchange(
                "/api/v1/operations/jobs/" + eventId + "/replay", HttpMethod.POST,
                new HttpEntity<>(postHeaders(cookie)), JsonNode.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().get("applied").asBoolean()).isFalse();
            assertThat(response.getBody().get("outcome").asText()).isEqualTo("NOT_REPLAY_SAFE");
            assertThat(response.getBody().get("recovery").asText())
                .as("the refusal says why, not merely that it was refused")
                .isNotBlank();
            assertThat(fixture.count("select 1 from outbox_events"
                + " where \"id\" = :id and \"state\" = 'DEAD'", java.util.Map.of("id", eventId)))
                .isEqualTo(1);
        }

        @Test
        @DisplayName("a dead-letter resolution needs who, when and a reason, and the row carries them")
        void aResolutionIsAttributable() {
            UUID eventId = fixture.deadOutboxEvent(ObservFixture.ORG_A, ObservFixture.PROJECT_A,
                QueueName.INCOMING_MESSAGE.wireValue(), 12, "attempts exhausted");
            UUID deadLetterId = fixture.deadLetterId(eventId);
            String cookie = sessionFor(ObservFixture.ADMIN_A);

            String blankNote = "{\"status\":\"RESOLVED\",\"note\":\"  \"}";
            ResponseEntity<JsonNode> blank = http.exchange(
                "/api/v1/operations/dead-letters/" + deadLetterId + "/resolve", HttpMethod.POST,
                new HttpEntity<>(blankNote, postHeaders(cookie, blankNote)), JsonNode.class);
            // Two layers again, and this one the product owns: @NotBlank on the request record rejects
            // a whitespace-only note at the HTTP edge, so the service never sees it.
            assertThat(blank.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(fixture.count("select 1 from job_dead_letters"
                + " where \"id\" = :id and \"status\" = 'OPEN'",
                java.util.Map.of("id", deadLetterId))).isEqualTo(1);

            // A syntactically valid but unrecognised status reaches the service and is refused there,
            // with the row still OPEN.
            String unknownStatus = "{\"status\":\"MAYBE\",\"note\":\"not a declared state\"}";
            ResponseEntity<JsonNode> unknown = http.exchange(
                "/api/v1/operations/dead-letters/" + deadLetterId + "/resolve", HttpMethod.POST,
                new HttpEntity<>(unknownStatus, postHeaders(cookie, unknownStatus)),
                JsonNode.class);
            assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(unknown.getBody().get("outcome").asText()).isEqualTo("INVALID_STATUS");
            assertThat(fixture.count("select 1 from job_dead_letters"
                + " where \"id\" = :id and \"status\" = 'OPEN'",
                java.util.Map.of("id", deadLetterId))).isEqualTo(1);

            String resolution = "{\"status\":\"RESOLVED\",\"note\":\"provider configuration fixed\"}";
            ResponseEntity<JsonNode> resolved = http.exchange(
                "/api/v1/operations/dead-letters/" + deadLetterId + "/resolve", HttpMethod.POST,
                new HttpEntity<>(resolution, postHeaders(cookie, resolution)), JsonNode.class);
            assertThat(resolved.getBody().get("applied").asBoolean()).isTrue();
            assertThat(fixture.count("select 1 from job_dead_letters"
                + " where \"id\" = :id and \"status\" = 'RESOLVED' and \"resolvedByUserId\" = :user"
                + " and \"resolutionNote\" = :note",
                java.util.Map.of("id", deadLetterId, "user", ObservFixture.ADMIN_A,
                    "note", "provider configuration fixed"))).isEqualTo(1);
        }

        @Test
        @DisplayName("an unauthenticated caller gets 401, not 403")
        void unauthenticatedIsFourOhOne() {
            for (String route : List.of("/api/v1/operations/jobs", "/api/v1/operations/connectors",
                "/api/v1/operations/workflows")) {
                assertThat(status(null, HttpMethod.GET, route, null)).isEqualTo(401);
            }
        }

        @Test
        @DisplayName("the panels the employee grant does cover are answered, and say so")
        void theKnowledgePanelsAreEmployeeReadable() {
            // KNOWLEDGE_READ has been in the employee grant since Phase 3. Asserting it here keeps the
            // exposure honest: an employee reads index freshness for their own projects, which is an
            // ingestion-state view and never chunk content.
            assertThat(RolePermissions.forRole(com.firstelement.security.UserRole.EMPLOYEE))
                .contains(Permission.KNOWLEDGE_READ);
            String cookie = sessionFor(ObservFixture.EMPLOYEE_A);

            assertThat(status(cookie, HttpMethod.GET, "/api/v1/operations/search/freshness", null))
                .isEqualTo(200);
            assertThat(status(cookie, HttpMethod.GET, "/api/v1/operations/search/gaps", null))
                .isEqualTo(200);
        }
    }

    // ------------------------------------------------------------------
    // wire helpers
    // ------------------------------------------------------------------

    private JsonNode get(String cookie, String path) {
        ResponseEntity<JsonNode> response = http.exchange(path, HttpMethod.GET,
            new HttpEntity<>(getHeaders(cookie)), JsonNode.class);
        assertThat(response.getStatusCode())
            .as("GET %s returned %s: %s", path, response.getStatusCode(), response.getBody())
            .isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private int status(String cookie, HttpMethod method, String path, String body) {
        HttpHeaders headers = cookie == null ? getHeaders(null) : postHeaders(cookie, body);
        return http.exchange(path, method, new HttpEntity<>(body, headers), String.class)
            .getStatusCode().value();
    }

    private int post(String cookie, String path, String body) {
        return status(cookie, HttpMethod.POST, path, body);
    }

    private HttpHeaders getHeaders(String cookie) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
        if (cookie != null) {
            headers.set(HttpHeaders.COOKIE, cookie);
        }
        return headers;
    }

    /**
     * Headers for a mutation, with the CSRF token the cookie repository issued.
     *
     * <p>A write route that only worked with a test-injected security context would prove nothing
     * about the product, so the CSRF handshake a browser performs is performed here too.
     */
    private HttpHeaders postHeaders(String cookie) {
        return postHeaders(cookie, null);
    }

    private HttpHeaders postHeaders(String cookie, String body) {
        HttpHeaders headers = getHeaders(cookie);
        ResponseEntity<JsonNode> token = http.getForEntity("/api/v1/auth/csrf", JsonNode.class);
        String csrf = token.getBody().get("token").asText();
        headers.set(securityProperties.getCsrfHeaderName(), csrf);
        headers.set(HttpHeaders.COOKIE, cookie + "; " + securityProperties.getCsrfCookieName()
            + "=" + csrf);
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        return headers;
    }

    /**
     * Issues a real revocable session and returns the cookie header value.
     *
     * <p>The raw token exists in this method and in the outgoing header, is never logged, and the
     * server stores only its SHA-256.
     */
    private String sessionFor(UUID userId) {
        BrowserSessionStore.IssuedSession issued =
            sessions.issue(userId, "operations-route-http-test", "127.0.0.1", 1);
        assertThat(issued).as("a session was issued for user %s", userId).isNotNull();
        assertThat(issued.rawToken()).isNotBlank();
        return securityProperties.getSessionCookieName() + "=" + issued.rawToken();
    }
}
