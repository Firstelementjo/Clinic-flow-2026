package com.firstelement.observ;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.firstelement.security.session.BrowserSessionStore;
import com.firstelement.worker.queue.QueueName;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * ADR 0016 over the wire: replaying a dead-lettered event <b>accepts</b> the work; it does not
 * perform it.
 *
 * <h2>What the route actually does, and why that makes 200 wrong</h2>
 *
 * <p>{@code OutboxReplayService.replay} moves the row from {@code DEAD} to {@code PENDING} and
 * returns. It does not claim the event, does not call a provider, and does not reset
 * {@code attempts} — the service's own recovery text says so: <i>"The event is claimable again."</i>
 * A {@code 200} told an operator the replay had happened, and the operator told the customer, before
 * anything had been delivered. That is the finance-period-lock failure ADR 0016 describes, on a
 * route whose whole purpose is to tell an operator what state a job is in.
 *
 * <h2>Why a real server and a real session here</h2>
 *
 * <p>Because the status under test is produced by the handler through the product's own
 * authorization and CSRF chain. An injected security context would answer a different question. The
 * sibling {@code OperationsRoutePermissionHttpIntegrationTest} already asserts the refusals on these
 * routes over the same socket; this suite asserts the success code, and asserts the STATE the row is
 * left in, which is the half a document edit cannot fake.
 */
@SpringBootTest(classes = ObservTestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReplayAsyncStatusHttpIntegrationTest extends ObservIntegrationTestBase {

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private BrowserSessionStore sessions;

    @Autowired
    private com.firstelement.security.config.SecurityProperties securityProperties;

    @Autowired
    private ObservFixture fixture;

    @Autowired
    private org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        fixture.reset();
    }

    @Test
    @DisplayName("a replay answers 202, and the event is claimable again rather than delivered")
    void aReplayIsAcceptedNotCompleted() {
        UUID eventId = fixture.deadOutboxEvent(ObservFixture.ORG_A, ObservFixture.PROJECT_A,
            QueueName.INCOMING_MESSAGE.wireValue(), 12, "attempts exhausted");
        String cookie = sessionFor(ObservFixture.ADMIN_A);
        int attemptsBefore = attempts(eventId);

        ResponseEntity<JsonNode> response = http.exchange(
            "/api/v1/operations/jobs/" + eventId + "/replay", HttpMethod.POST,
            new HttpEntity<>(postHeaders(cookie)), JsonNode.class);

        assertThat(response.getStatusCode())
            .as("202 Accepted: the replay is accepted and outstanding. No worker has claimed the "
                + "event and no provider has been called, so 200 told the operator it had been.")
            .isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getStatusCode().value())
            .as("and the contract declares the code the wire just produced. Comparing the document "
                + "with itself would be circular; this number came off the socket and that one comes "
                + "from the table the customiser publishes from.")
            .isEqualTo(declaredStatusOf(202));
        assertThat(response.getBody().get("applied").asBoolean())
            .as("the outcome body is unchanged; only the status moved")
            .isTrue();
        assertThat(response.getBody().get("outcome").asText()).isEqualTo("REPLAYED");

        // STATE, not the response body. This is the half that cannot be produced by editing a
        // document: the row is PENDING and claimable, and no new attempt exists.
        assertThat(state(eventId))
            .as("the event is PENDING, not DELIVERED -- nothing has run it yet")
            .isEqualTo("PENDING");
        assertThat(attempts(eventId))
            .as("a replay does not reset the attempt count; that is what makes the row claimable "
                + "again without pretending the work was done")
            .isEqualTo(attemptsBefore);
        assertThat(fixture.count(
            "select 1 from outbox_events where \"id\" = :id and \"state\" = 'DEAD'",
            Map.of("id", eventId)))
            .as("the row is no longer dead: it is claimable again, which is acceptance and not "
                + "delivery. The audit row this wrote is a record of the decision, not of a delivery.")
            .isZero();
    }

    @Test
    @DisplayName("a refused replay is not accepted work, so it is not 202")
    void aRefusedReplayIsNotAcceptedWork() {
        UUID eventId = fixture.deadOutboxEvent(ObservFixture.ORG_A, ObservFixture.PROJECT_A,
            QueueName.AI_RESPONSE.wireValue(), 12, "model refused the request");
        String cookie = sessionFor(ObservFixture.ADMIN_A);

        ResponseEntity<JsonNode> response = http.exchange(
            "/api/v1/operations/jobs/" + eventId + "/replay", HttpMethod.POST,
            new HttpEntity<>(postHeaders(cookie)), JsonNode.class);

        assertThat(response.getStatusCode())
            .as("ADR-0016 rule 3 forbids declaring a code the route does not return, in either "
                + "direction. A refusal that completed on this thread wrote nothing, so 202 would "
                + "tell an operator to poll a job that will never run.")
            .isEqualTo(HttpStatus.OK);
        assertThat(response.getStatusCode().value())
            .as("and the contract declares 200 for this branch too, because it genuinely returns both")
            .isEqualTo(declaredStatusOf(200));
        assertThat(response.getBody().get("applied").asBoolean()).isFalse();
        assertThat(response.getBody().get("outcome").asText()).isEqualTo("NOT_REPLAY_SAFE");
        assertThat(state(eventId))
            .as("STATE, not the status code: nothing was written")
            .isEqualTo("DEAD");
    }

    @Test
    @DisplayName("replaying an event that is already in flight is refused, and is not accepted work")
    void replayingALiveEventIsRefused() {
        UUID eventId = fixture.deadOutboxEvent(ObservFixture.ORG_A, ObservFixture.PROJECT_A,
            QueueName.INCOMING_MESSAGE.wireValue(), 12, "attempts exhausted");
        String cookie = sessionFor(ObservFixture.ADMIN_A);
        assertThat(replay(eventId, cookie).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        // The event is PENDING now, so a second replay has nothing to do. A client that polled after
        // the first 202 must be told "nothing was written", not handed another 202.
        ResponseEntity<JsonNode> second = replay(eventId, cookie);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody().get("outcome").asText()).isEqualTo("NOT_DEAD");
        assertThat(second.getBody().get("applied").asBoolean()).isFalse();
    }

    // ------------------------------------------------------------------
    // wire helpers
    // ------------------------------------------------------------------

    /**
 * The code the contract declares for the branch just exercised.
 *
 * <p><b>This is the seam that stops the gate being circular.</b> ADR 0016 rule 5 rejects a drift gate
 * on its own "because a document edited to match a bug passes it". A test comparing the document
 * with the table would pass when the table was edited to match a wrong route — which is what a
 * maintainer does to silence it. So the table lives in {@code :backend:shared-kernel}, where this
 * suite can read it, and this suite compares it against a status that came off the socket.
 */
private static int declaredStatusOf(int branch) {
    java.util.List<Integer> declared = com.firstelement.kernel.contract.ObservedRouteStatuses
        .governedByOperation()
        .getOrDefault("POST /api/v1/operations/jobs/{outboxEventId}/replay", java.util.List.of())
        .stream().map(com.firstelement.kernel.contract.ObservedRouteStatuses.Observation::status)
        .distinct().sorted().toList();

    assertThat(declared)
        .as("the replay route is a split-outcome operation: it returns 202 when the replay was "
            + "applied and 200 when it was refused, and both were observed")
        .containsExactly(200, 202);
    assertThat(declared).as("branch %d must be one the contract declares", branch).contains(branch);
    return branch;
}

private ResponseEntity<JsonNode> replay(UUID eventId, String cookie) {
        return http.exchange("/api/v1/operations/jobs/" + eventId + "/replay", HttpMethod.POST,
            new HttpEntity<>(postHeaders(cookie)), JsonNode.class);
    }

    private String state(UUID eventId) {
        return jdbc.queryForObject("select \"state\" from outbox_events where \"id\" = :id",
            new MapSqlParameterSource(Map.of("id", eventId)), String.class);
    }

    private int attempts(UUID eventId) {
        Integer value = jdbc.queryForObject(
            "select \"attempts\" from outbox_events where \"id\" = :id",
            new MapSqlParameterSource(Map.of("id", eventId)), Integer.class);
        return value == null ? 0 : value;
    }

    private HttpHeaders postHeaders(String cookie) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
        ResponseEntity<JsonNode> token = http.getForEntity("/api/v1/auth/csrf", JsonNode.class);
        String csrf = token.getBody().get("token").asText();
        headers.set(securityProperties.getCsrfHeaderName(), csrf);
        headers.set(HttpHeaders.COOKIE, cookie + "; " + securityProperties.getCsrfCookieName()
            + "=" + csrf);
        return headers;
    }

    /**
     * A real revocable session, issued by the product's own store.
     *
     * <p>The raw token exists here and in the outgoing header, is never logged, and the server
     * stores only its SHA-256.
     */
    private String sessionFor(UUID userId) {
        BrowserSessionStore.IssuedSession issued =
            sessions.issue(userId, "replay-async-status-http-test", "127.0.0.1", 1);
        assertThat(issued).as("a session was issued for user %s", userId).isNotNull();
        assertThat(issued.rawToken()).isNotBlank();
        return securityProperties.getSessionCookieName() + "=" + issued.rawToken();
    }
}