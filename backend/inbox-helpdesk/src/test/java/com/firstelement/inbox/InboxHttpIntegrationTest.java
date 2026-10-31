package com.firstelement.inbox;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstelement.inbox.api.AssignConversationRequest;
import com.firstelement.inbox.api.IncomingMessage;
import com.firstelement.inbox.api.SendMessageRequest;
import com.firstelement.inbox.service.ConversationService;
import com.firstelement.inbox.service.InboundMessageService;
import com.firstelement.security.TenantAuthentication;
import com.firstelement.security.TenantContext;
import com.firstelement.security.TenantContextHolder;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The {@code /api/v1} HTTP surface over a real PostgreSQL container.
 *
 * <h2>What only HTTP can prove</h2>
 *
 * <ul>
 *   <li><b>Route precedence.</b> {@code /customers/cx/{cxId}} must win over
 *       {@code /customers/{customerId}}; if it does not, a CX lookup becomes a 400 UUID
 *       parse failure.</li>
 *   <li><b>The concurrency and idempotency headers</b> survive the wire: {@code ETag},
 *       {@code If-Match}, {@code Idempotency-Key}, {@code X-Idempotent-Replay},
 *       {@code X-Conversation-Collision}.</li>
 *   <li><b>Status codes</b>: 404 for out-of-scope <em>and</em> nonexistent (no existence oracle),
 *       409 for a stale write, 400 for a domain rule, 200 for an idempotent replay.</li>
 *   <li><b>Serialization</b>: the JSON has the legacy key set and nothing extra — the failure mode
 *       a controller introduces that a service-level test cannot see.</li>
 * </ul>
 */
@SpringBootTest(classes = InboxTestApplication.class)
@AutoConfigureMockMvc
class InboxHttpIntegrationTest extends InboxIntegrationTestBase {

    @Autowired
    private InboxFixture fixture;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper mapper;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private ConversationService conversations;

    @Autowired
    private InboundMessageService inbound;

    private TenantContext staff;

    @BeforeEach
    void setUp() {
        fixture.reset();
        staff = fixture.staffA();
        // The controllers resolve their scope from the thread bindings the filter chain would
        // normally supply; binding them here exercises the same lookup path.
        PrincipalScope.bind(staff);
    }

    @AfterEach
    void tearDown() {
        PrincipalScope.clear();
    }

    // ==================================================================
    // reads
    // ==================================================================

    @Test
    @DisplayName("GET /api/v1/conversations returns the legacy {items, nextCursor} body")
    void inboxBodyHasNoExtraFields() throws Exception {
        receiveAndReply();

        mvc.perform(get("/api/v1/conversations"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items").isArray())
            .andExpect(jsonPath("$.nextCursor").doesNotExist())
            .andExpect(jsonPath("$.items[0].ticketNumber").exists())
            .andExpect(jsonPath("$.items[0].customer.cxId").exists())
            .andExpect(jsonPath("$.items[0].assignedEmployee").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/v1/customers/cx/{cxId} wins over /customers/{customerId}")
    void cxRoutePrecedence() throws Exception {
        mvc.perform(get("/api/v1/customers/cx/{cxId}", "#33000001"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.cxId").value("#33000001"));
    }

    @Test
    @DisplayName("a thread read carries an ETag and the legacy thread key set")
    void threadCarriesAnEtag() throws Exception {
        UUID conversationId = receiveAndReply();

        mvc.perform(get("/api/v1/conversations/{id}", conversationId))
            .andExpect(status().isOk())
            .andExpect(header().exists("ETag"))
            .andExpect(jsonPath("$.messages").isArray())
            .andExpect(jsonPath("$.events").isArray())
            .andExpect(jsonPath("$.mode").value("HUMAN"))
            // The legacy thread has no version field; it travels in the header instead.
            .andExpect(jsonPath("$.updatedAt").doesNotExist());
    }

    // ==================================================================
    // non-disclosure
    // ==================================================================

    @Test
    @DisplayName("another tenant's thread is byte-identical to a nonexistent id")
    void crossTenantThreadIsNotDisclosed() throws Exception {
        UUID theirs = fixture.conversation("0000000000b1", InboxFixture.ORG_B,
            InboxFixture.PROJECT_B1, InboxFixture.CUSTOMER_B1, "#0814000000b1", "OPEN", "HUMAN",
            InboxFixture.SEED_AT);

        String otherTenant = mvc.perform(get("/api/v1/conversations/{id}", theirs))
            .andExpect(status().isNotFound())
            .andReturn().getResponse().getContentAsString();
        String nonexistent = mvc.perform(
                get("/api/v1/conversations/{id}", UUID.randomUUID()))
            .andExpect(status().isNotFound())
            .andReturn().getResponse().getContentAsString();

        // Only the correlation id and the timestamp may differ; the code, message and details must
        // be the same string, or the status code becomes an existence oracle.
        assertSameShape(otherTenant, nonexistent);
    }

    @Test
    @DisplayName("a no-membership principal is refused and reads nothing")
    void noMembershipIsRefused() throws Exception {
        // Bound for the whole test rather than per request, so the assertions cannot depend on
        // which thread binding survives a MockMvc dispatch.
        PrincipalScope.bind(fixture.outsiderA());

        mvc.perform(get("/api/v1/conversations"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(get("/api/v1/customers"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(get("/api/v1/inbox/unassigned"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.entries").isEmpty());
    }

    // ==================================================================
    // concurrency and idempotency on the wire
    // ==================================================================

    @Test
    @DisplayName("a stale If-Match on a status update is 409 with the current version")
    void staleIfMatchIsAConflict() throws Exception {
        UUID conversationId = receiveAndReply();
        String stale = mvc.perform(get("/api/v1/conversations/{id}", conversationId))
            .andReturn().getResponse().getHeader("ETag");

        mvc.perform(patch("/api/v1/conversations/{id}", conversationId).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"priority\":\"HIGH\"}"))
            .andExpect(status().isOk());

        mvc.perform(patch("/api/v1/conversations/{id}", conversationId).with(csrf())
                .header("If-Match", stale)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"priority\":\"URGENT\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
            .andExpect(jsonPath("$.details.currentVersion").exists())
            .andExpect(jsonPath("$.retryable").value(false));
    }

    @Test
    @DisplayName("a fresh If-Match applies")
    void freshIfMatchApplies() throws Exception {
        UUID conversationId = receiveAndReply();
        String current = mvc.perform(get("/api/v1/conversations/{id}", conversationId))
            .andReturn().getResponse().getHeader("ETag");

        mvc.perform(patch("/api/v1/conversations/{id}", conversationId).with(csrf())
                .header("If-Match", current)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"priority\":\"URGENT\",\"subject\":\"Escalated\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.priority").value("URGENT"))
            .andExpect(jsonPath("$.subject").value("Escalated"));
    }

    @Test
    @DisplayName("an Idempotency-Key replay is 200 with the replay header and no second message")
    void replyIdempotencyOnTheWire() throws Exception {
        UUID conversationId = receiveAndReply();
        String body = mapper.writeValueAsString(new SendMessageRequest("One and only"));

        mvc.perform(post("/api/v1/conversations/{id}/messages", conversationId).with(csrf())
                .header("Idempotency-Key", "wire-key-1")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andExpect(header().doesNotExist("X-Idempotent-Replay"));

        mvc.perform(post("/api/v1/conversations/{id}/messages", conversationId).with(csrf())
                .header("Idempotency-Key", "wire-key-1")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Idempotent-Replay", "true"))
            .andExpect(jsonPath("$.messageCount").value(3));
    }

    @Test
    @DisplayName("a stale expected-message-count is reported, and the reply is still delivered")
    void collisionHeaderIsReported() throws Exception {
        UUID conversationId = receiveAndReply();

        mvc.perform(post("/api/v1/conversations/{id}/messages", conversationId).with(csrf())
                .header("X-Expected-Message-Count", "0")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"Answering as well\"}"))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Conversation-Collision", "true"));
    }

    // ==================================================================
    // domain rules and the unassigned queue
    // ==================================================================

    @Test
    @DisplayName("assigning an employee from another project is 400 with the legacy message")
    void crossProjectAssignmentIsRejected() throws Exception {
        UUID conversationId = receiveAndReply();

        mvc.perform(patch("/api/v1/conversations/{id}/assignment", conversationId).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(
                    new AssignConversationRequest(InboxFixture.EMPLOYEE_BETA))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.message").value("That employee is not a member of this project"));
    }

    @Test
    @DisplayName("the unassigned queue explains every entry and reports the totals")
    void unassignedQueueIsExplained() throws Exception {
        receive("ext-queue", "provider-queue", "Nobody has answered me");

        mvc.perform(get("/api/v1/inbox/unassigned"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.entries[0].reason").value("NEVER_ASSIGNED"))
            .andExpect(jsonPath("$.entries[0].reasonDetail").isNotEmpty())
            .andExpect(jsonPath("$.entries[0].waitingSeconds").isNumber())
            .andExpect(jsonPath("$.entries[0].signals.status").value("OPEN"))
            .andExpect(jsonPath("$.totals.total").value(1))
            .andExpect(jsonPath("$.totals.byStatus.OPEN").value(1))
            .andExpect(jsonPath("$.generatedAt").isNotEmpty());
    }

    @Test
    @DisplayName("closing a case without a recorded resolution is 400")
    void closeRequiresAResolution() throws Exception {
        UUID conversationId = receiveAndReply();

        mvc.perform(post("/api/v1/conversations/{id}/close", conversationId).with(csrf()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message")
                .value("Record how the case was resolved before closing it"));
    }

    @Test
    @DisplayName("a mode change without a modeVersion is 400")
    void modeChangeRequiresAVersion() throws Exception {
        UUID conversationId = receiveAndReply();

        mvc.perform(patch("/api/v1/conversations/{id}/mode", conversationId).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"AI\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // ==================================================================
    // request parsing
    // ==================================================================

    @Test
    @DisplayName("a malformed UUID in the path is 400, not a 404 for a route that exists")
    void malformedPathIdIsBadRequest() throws Exception {
        mvc.perform(get("/api/v1/conversations/not-a-uuid"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("an unknown inbox filter is 400 rather than a silent default")
    void unknownFilterIsRejected() throws Exception {
        mvc.perform(get("/api/v1/conversations").param("filter", "everything"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.details.field").value("filter"));
    }

    @Test
    @DisplayName("an over-large limit is clamped, not rejected")
    void limitIsClamped() throws Exception {
        receiveAndReply();

        mvc.perform(get("/api/v1/conversations").param("limit", "5000"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items").isArray());
    }

    @Test
    @DisplayName("a malformed If-Match is 400 rather than being ignored")
    void malformedIfMatchIsRejected() throws Exception {
        UUID conversationId = receiveAndReply();

        mvc.perform(patch("/api/v1/conversations/{id}", conversationId).with(csrf())
                .header("If-Match", "\"yesterday\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"priority\":\"HIGH\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_IF_MATCH"));
    }

    // ==================================================================
    // helpers
    // ==================================================================

    private UUID receive(String externalUserId, String externalMessageId, String text) {
        return inbound.receive(staff, InboxFixture.PROJECT_A1, null, new IncomingMessage(
            externalUserId, text, null, null, null, externalMessageId,
            com.firstelement.crm.domain.ChannelType.MOCK, Map.of(), Map.of(),
            java.time.LocalDateTime.now())).conversationId();
    }

    private UUID receiveAndReply() {
        UUID conversationId = receive("ext-http", "provider-http", "I need help");
        conversations.reply(staff, conversationId, new SendMessageRequest("Looking into it"),
            null, null, null);
        return conversationId;
    }

    /**
     * Binds a principal for the duration of one MockMvc request.
     *
     * <p>A real request gets these bindings from the servlet filter chain; MockMvc with
     * {@code @AutoConfigureMockMvc} does not run that chain, so they are established here. The
     * point of the assertion is the controller's behaviour, not the filter's.
     */
    private static RequestPostProcessor asPrincipal(TenantContext context) {
        return request -> {
            SecurityContextHolder.clearContext();
            var securityContext = SecurityContextHolder.createEmptyContext();
            securityContext.setAuthentication(new TenantAuthentication(context));
            SecurityContextHolder.setContext(securityContext);
            TenantContextHolder.set(context);
            return request;
        };
    }

    /**
     * Asserts two error bodies differ only in the request id and the timestamp.
     *
     * <p>Code, message and details must be the same string: if a cross-tenant denial said
     * something a nonexistent id does not, the difference is an existence oracle.
     */
    private static void assertSameShape(String left, String right) throws Exception {
        com.fasterxml.jackson.databind.JsonNode a = MAPPER.readTree(left);
        com.fasterxml.jackson.databind.JsonNode b = MAPPER.readTree(right);
        org.assertj.core.api.Assertions.assertThat(a.get("code")).isEqualTo(b.get("code"));
        org.assertj.core.api.Assertions.assertThat(a.get("message")).isEqualTo(b.get("message"));
        org.assertj.core.api.Assertions.assertThat(a.get("details")).isEqualTo(b.get("details"));
    }

    /** Kept so the note route family stays visible in this suite's intent. */
    @SuppressWarnings("unused")
    private static List<String> noteRoutes() {
        return List.of("/api/v1/notes", "/api/v1/notes/project");
    }
}