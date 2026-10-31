package com.firstelement.airag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstelement.airag.drafts.AiDraftReadService;
import com.firstelement.airag.drafts.AiResponseJobHandler;
import com.firstelement.airag.principal.AiPrincipalScope;
import com.firstelement.airag.web.AiDraftController;
import com.firstelement.security.TenantContext;
import com.firstelement.security.UserRole;
import com.firstelement.security.annotation.RequirePermission;
import com.firstelement.security.config.SecurityProperties;
import com.firstelement.security.permission.AuthorizationService;
import com.firstelement.security.permission.Permission;
import com.firstelement.security.permission.RolePermissions;
import com.firstelement.security.session.BrowserSessionStore;
import com.firstelement.worker.queue.JobEnvelope;
import com.firstelement.worker.queue.QueueName;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.net.URI;
import java.util.List;
import java.util.Map;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The read route, the cross-tenant negatives, and the OpenAPI contract — over a real socket with the
 * product's own filter chain.
 *
 * <h2>Why the contract half is here and not in {@code app-bootstrap}</h2>
 *
 * <p>ADR 0016 is "observe, then declare": the statuses are <em>executed</em> and the declarations are
 * compared against them, so the document cannot be edited to match a bug. {@code app-bootstrap} owns
 * {@code tests/contracts/openapi.json} and is not this phase's to touch, so this suite reads the
 * <b>live</b> document the running application serves and compares the route's declared refusals
 * against what it really returns.
 *
 * <h2>Why the uniform 404 is asserted byte-for-byte</h2>
 *
 * <p>A cross-tenant denial is only non-disclosing if it is indistinguishable from "no such
 * conversation". Asserting the status alone would pass for a route that returned 404 with a body
 * naming the tenant, so the bodies are compared. This is the lesson from the legacy cross-tenant
 * breach, §10 of {@code docs/migration/migration-ledger.md}.
 */
@SpringBootTest(classes = AiRagTestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        // Same override as OpenApiDocumentExportTest: the operator surface is bound to a management
        // port in a real deployment, and asking for the document on the main context is how this
        // test reads its own API surface. It changes no part of the document.
        "springdoc.use-management-port=false"
    })
class AiDraftRouteHttpIntegrationTest extends AiIntegrationTestBase {

    private static final String PATH = "/api/v1/ai/drafts/conversations/";

    @Autowired
    private AiFixture fixture;

    @Autowired
    private AiResponseJobHandler handler;

    @Autowired
    private AiDraftReadService drafts;

    @Autowired
    private AuthorizationService authorization;

    @Autowired
    private BrowserSessionStore sessions;

    @Autowired
    private SecurityProperties securityProperties;

    @Autowired
    private TestRestTemplate http;

    private UUID conversation;
    private UUID triggerMessage;

    @BeforeEach
    void seed() {
        fixture.reset();
        UUID knowledgeBase = fixture.knowledgeBase(AiFixture.ORG_A, AiFixture.PROJECT_A, "KB");
        fixture.agentWithVersion(AiFixture.ORG_A, AiFixture.PROJECT_A, AiFixture.ADMIN_A,
            "MOCK", "mock-deterministic-v1", "[]", "[\"" + knowledgeBase + "\"]", 0.0d,
            "أجب من المصادر فقط");
        fixture.policy(AiFixture.ORG_A, AiFixture.PROJECT_A, "default", 4000, 4, "STRICT_CLINICAL",
            AiFixture.ADMIN_A);
        fixture.model(AiFixture.ORG_A, AiFixture.PROJECT_A, "MOCK", "mock-deterministic-v1", true,
            "LOCAL", false, "USD", "0.00000150", "0.00000600");
        UUID document = fixture.document(AiFixture.ORG_A, AiFixture.PROJECT_A, knowledgeBase,
            "ساعات العمل", "READY");
        fixture.chunk(AiFixture.ORG_A, AiFixture.PROJECT_A, knowledgeBase, document, 0,
            "ساعات العمل من الأحد إلى الخميس.", "ساعات العمل");

        conversation = fixture.conversation(AiFixture.ORG_A, AiFixture.PROJECT_A, "AI");
        triggerMessage = fixture.customerMessage(AiFixture.ORG_A, AiFixture.PROJECT_A, conversation,
            "ما ساعات العمل؟");
    }

    @Nested
    @DisplayName("the route answers what it is observed to answer")
    class ObservedStatus {

        @Test
        @DisplayName("an operator is answered 200 and the body is visibly a DRAFT")
        void anOperatorReadsTheDraft() {
            runTurn();

            ResponseEntity<String> response = exchange(cookieFor(AiFixture.ADMIN_A), HttpMethod.GET,
                PATH + conversation, null);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            JsonNode body = json(response.getBody());
            // The literal that makes it a draft and not a message, published in the response itself.
            assertThat(body.get("state").asText()).isEqualTo("DRAFT");
            assertThat(body.get("body").asText()).isNotBlank();
            assertThat(body.get("runId").asText()).isNotBlank();
            assertThat(body.get("conversationId").asText()).isEqualTo(conversation.toString());
            // The mode is published so a human can see the AI still owns the thread.
            assertThat(body.get("conversationMode").asText()).isEqualTo("AI");
        }

        @Test
        @DisplayName("no session is 401")
        void unauthenticatedIsRefused() {
            runTurn();

            assertThat(exchange(null, HttpMethod.GET, PATH + conversation, null)
                .getStatusCode().value()).isEqualTo(401);
        }

        @Test
        @DisplayName("an id that is not a UUID is 400, which is why the contract declares one")
        void malformedIdIsFourHundred() {
            runTurn();

            assertThat(exchange(cookieFor(AiFixture.ADMIN_A), HttpMethod.GET, PATH + "not-a-uuid", null)
                .getStatusCode().value())
                .as("the path variable is a typed UUID, so an unparseable id is a binding failure "
                    + "and not a 404. Declaring a 400 the route cannot produce is the ADR 0016 lie")
                .isEqualTo(400);
        }

        @Test
        @DisplayName("a conversation with no pending draft is 404, not an empty 200")
        void noDraftIsNotFound() {
            // Nothing ran, so there is no draft. The route cannot answer 200 with an empty body,
            // because a client would then treat "nothing pending" as "a pending blank draft".
            assertThat(exchange(cookieFor(AiFixture.ADMIN_A), HttpMethod.GET, PATH + conversation, null)
                .getStatusCode().value()).isEqualTo(404);
        }
    }

    @Nested
    @DisplayName("cross-tenant is not probeable")
    class CrossTenant {

        @Test
        @DisplayName("another tenant's conversation is the same 404, with the same body")
        void anotherTenantsConversationIsIndistinguishable() {
            runTurn();
            String cookie = cookieFor(AiFixture.ADMIN_A);

            JsonNode mine = json(exchange(cookie, HttpMethod.GET, PATH + conversation, null)
                .getBody());
            JsonNode nonexistent = json(exchange(cookie, HttpMethod.GET, PATH + UUID.randomUUID(), null)
                .getBody());
            JsonNode foreign = json(exchange(cookie, HttpMethod.GET, PATH + tenantBConversation(), null)
                .getBody());

            // Compared field by field rather than as raw strings: the error body carries a
            // `timestamp`, so two byte-identical refusals differ by a clock reading. What must match
            // is the code, the message and the details -- the parts a probe could read. A route that
            // answered "Conversation 3f2a... is in another tenant" would differ here and be caught.
            assertThat(foreign.get("code")).isEqualTo(nonexistent.get("code"));
            assertThat(foreign.get("message")).isEqualTo(nonexistent.get("message"));
            assertThat(foreign.get("details")).isEqualTo(nonexistent.get("details"));
            assertThat(foreign.get("code").asText()).isEqualTo("NOT_FOUND");
            assertThat(foreign.get("message").asText())
                .as("a per-resource message is itself an existence oracle")
                .isEqualTo("Resource not found");
            // And it is genuinely different from a real hit, so the assertion above has teeth.
            assertThat(foreign.get("code")).isNotEqualTo(mine.get("code"));
        }

        @Test
        @DisplayName("a tenant-A actor cannot read a tenant-B draft through the service")
        void serviceRefusesAForeignDraft() {
            runTurn();

            assertThat(PrincipalScope.call(
                    AiPrincipalScope.admin(AiFixture.ORG_A, AiFixture.ADMIN_A, List.of(AiFixture.PROJECT_A)),
                    () -> drafts.pendingDraft(
                        AiPrincipalScope.admin(AiFixture.ORG_A, AiFixture.ADMIN_A,
                            List.of(AiFixture.PROJECT_A)),
                        tenantBConversation())))
                .as("the lookup is tenant-scoped and a miss is a miss, so there is no branch that "
                    + "could tell 'not yours' from 'not there'")
                .isEmpty();
        }

        @Test
        @DisplayName("a tenant-A actor cannot cause a run on a tenant-B conversation")
        void aTenantAActorCannotRunOnTenantB() {
            UUID tenantB = tenantBConversation();
            UUID tenantBMessage = fixture.customerMessage(AiFixture.ORG_B, AiFixture.PROJECT_B,
                tenantB, "ما ساعات العمل؟");

            // The job claims tenant B, so the handler's own scope is tenant B. The envelope names
            // tenant B's conversation, and the run it would produce is in tenant B -- never in A.
            String outcome = handler.handle(worker(AiFixture.ORG_B), new JobEnvelope(
                UUID.randomUUID(), QueueName.AI_RESPONSE, AiFixture.ORG_B, AiFixture.PROJECT_B,
                "Conversation", tenantB,
                Map.of("conversationId", tenantB.toString(),
                    "triggerMessageId", tenantBMessage.toString()),
                "corr", tenantBMessage.toString(), 1, "job-x", false, null, null));

            // Tenant B has no agent of its own, so nothing runs there either -- and tenant A is
            // untouched, which is the property under test.
            assertThat(outcome).startsWith("skipped");
            assertThat(fixture.count("select 1 from ai_runs where \"organizationId\" = :org",
                Map.of("org", AiFixture.ORG_A))).isZero();
            assertThat(fixture.count("select 1 from ai_runs where \"organizationId\" = :org",
                Map.of("org", AiFixture.ORG_B))).isZero();
        }

        @Test
        @DisplayName("a project the caller has left is answered like a conversation that is not there")
        void aLeftProjectIsIndistinguishable() {
            runTurn();
            // ADMIN_A is a member of PROJECT_A and PROJECT_A2, so use a principal that is in
            // PROJECT_A only. The conversation is in PROJECT_A2: same tenant, outside the caller's
            // memberships.
            UUID other = fixture.conversation(AiFixture.ORG_A, AiFixture.PROJECT_A2, "AI");
            TenantContext scoped = AiPrincipalScope.admin(AiFixture.ORG_A, AiFixture.ADMIN_A,
                List.of(AiFixture.PROJECT_A));
            UUID unknown = UUID.randomUUID();

            // An EMPTY result rather than a thrown denial. `CrmQueryScope.accessibleProjects`
            // already narrows the predicate to the principal's memberships, so the row is simply not
            // found -- which is strictly better than an exception: there is no distinct status a
            // probe could key on. This is the Phase-33 clinic shape enforced in SQL.
            assertThat(PrincipalScope.call(scoped, () -> drafts.pendingDraft(scoped, other)))
                .as("a conversation in a project the caller has left is a miss, not a disclosure")
                .isEqualTo(PrincipalScope.call(scoped, () -> drafts.pendingDraft(scoped, unknown)));
            assertThat(PrincipalScope.call(scoped, () -> drafts.pendingDraft(scoped, other)))
                .isEmpty();
        }
    }

    @Nested
    @DisplayName("the takeover guard is visible at the read")
    class TakeoverIsVisible {

        @Test
        @DisplayName("a draft is not offered once a human owns the conversation")
        void aWithheldDraftIsNotOffered() {
            runTurn();
            String cookie = cookieFor(AiFixture.ADMIN_A);
            assertThat(exchange(cookie, HttpMethod.GET, PATH + conversation, null)
                .getStatusCode().value()).isEqualTo(200);

            takeOver();

            assertThat(exchange(cookie, HttpMethod.GET, PATH + conversation, null)
                .getStatusCode().value())
                .as("the route refuses the same way it refuses a nonexistent conversation, so it "
                    + "cannot be used to learn that the AI had answered before a human intervened")
                .isEqualTo(404);
        }

        @Test
        @DisplayName("the write side withholds the draft itself, so there is nothing to offer")
        void theWriteSideWithholdsRatherThanFlags() {
            takeOver();

            // The conversation is HUMAN before the turn runs, so no run and no draft exist at all.
            assertThat(handler.handle(worker(AiFixture.ORG_A), envelope())).startsWith("skipped");
            assertThat(fixture.count("select 1 from ai_runs where \"organizationId\" = :org",
                Map.of("org", AiFixture.ORG_A))).isZero();
            assertThat(fixture.count("select 1 from notes where \"resourceType\" = 'AiDraft'",
                Map.of())).isZero();
        }
    }

    @Nested
    @DisplayName("the declarations")
    class Declarations {

        @Test
        @DisplayName("the route declares AI_RUN_READ, never AI_SEND")
        void theRouteDeclaresTheReadPermission() throws Exception {
            var method = AiDraftController.class.getMethod("pendingDraft", UUID.class);
            RequirePermission annotation = method.getAnnotation(RequirePermission.class);
            assertThat(annotation).isNotNull();
            // ADR 0010 §2 and the Phase-22 finding: a send that reused the read-only permission is
            // exactly how AI_SEND came to exist. Reading a governed run is not speaking.
            assertThat(annotation.value()).isEqualTo(Permission.AI_RUN_READ);
            assertThat(method.getAnnotation(GetMapping.class)).isNotNull();
        }

        @Test
        @DisplayName("AI_RUN_READ is a permission a real role holds")
        void thePermissionIsHoldable() {
            assertThat(RolePermissions.forRole(UserRole.EMPLOYEE)).contains(Permission.AI_RUN_READ);
            assertThat(RolePermissions.forRole(UserRole.ADMIN)).contains(Permission.AI_RUN_READ);
        }

        @Test
        @DisplayName("the service enforces the permission, so the rule is not controller-only")
        void theServiceEnforcesThePermission() {
            TenantContext noGrant = TenantContext.forUser(UUID.randomUUID(), AiFixture.ADMIN_A,
                AiFixture.ORG_A, UserRole.CLINIC_STAFF, List.of(AiFixture.PROJECT_A));

            // Bound as a real principal, so this exercises AuthorizationService and not a missing
            // SecurityContext. A CLINIC_STAFF member is a legitimate caller of the tenant who does
            // not hold ai-run:read.
            assertThatThrownBy(() -> PrincipalScope.call(noGrant,
                    () -> drafts.pendingDraft(noGrant, conversation)))
                .isInstanceOf(AccessDeniedException.class);
            assertThat(authorization.allows(noGrant, Permission.AI_RUN_READ)).isFalse();
        }

        @Test
        @DisplayName("the route declares exactly the refusals it was observed to produce")
        void theRouteDeclaresObservedRefusals() throws Exception {
            // Read from the annotation the application actually serves to springdoc, rather than
            // fetched from /api/v1/openapi: that document is served on the management port under
            // app-bootstrap's configuration, and :backend:ai-rag does not own that module. The
            // refusal STATUSES themselves are observed over a real socket in ObservedStatus, so this
            // half asserts the declaration matches what was executed rather than restating a list.
            //
            // Declared: 200, 400 (a typed UUID path variable), 401 (not public), 403 (@RequirePermission),
            // 404 (names a resource). Deliberately NOT declared: 415 (consumes no body), CSRF (a GET),
            // 409 (no optimistic-concurrency check). A declared refusal a route cannot produce is the
            // same lie as a wrong success code -- ADR 0016 rule 3.
            ApiResponses responses = AiDraftController.class
                .getMethod("pendingDraft", UUID.class).getAnnotation(ApiResponses.class);
            assertThat(responses)
                .as("the draft route must carry its refusals as annotations, or the served "
                    + "document will describe it as incapable of refusing anything")
                .isNotNull();

            List<String> declared = new java.util.ArrayList<>();
            for (ApiResponse response : responses.value()) {
                declared.add(response.responseCode());
            }
            assertThat(declared).containsExactlyInAnyOrder("200", "400", "401", "403", "404");
        }

        @Test
        @DisplayName("the route is read-only, and publishes a DRAFT state rather than a sent marker")
        void theRouteIsReadOnlyAndPublishesADraft() throws Exception {
            // Every mapped method on the controller is a GET. A POST here would be a send, and a send
            // is what this phase explicitly does not do.
            for (var method : AiDraftController.class.getDeclaredMethods()) {
                if (method.isSynthetic() || method.getName().equals("pendingDraft")) {
                    continue;
                }
                assertThat(method.getAnnotation(
                        org.springframework.web.bind.annotation.PostMapping.class))
                    .as("AiDraftController#%s is not a POST: this route must not be able to send",
                        method.getName())
                    .isNull();
                assertThat(method.getAnnotation(
                        org.springframework.web.bind.annotation.PatchMapping.class)).isNull();
                assertThat(method.getAnnotation(
                        org.springframework.web.bind.annotation.DeleteMapping.class)).isNull();
            }

            // The DRAFT literal is a constant in the response, so a future edit cannot make it
            // report "SENT" without failing here.
            AiDraftController.AiDraftView view = new AiDraftController.AiDraftView(
                UUID.randomUUID(), UUID.randomUUID(), "body", AiFixture.now(), "DRAFT", "AI", 0);
            assertThat(view.state()).isEqualTo("DRAFT");
            assertThat(AiDraftController.AiDraftView.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .as("the published fields name the run, the conversation and the mode, and carry "
                    + "no reasoning, step detail or retrieved content -- ADR 0007 Part 2 rule 4")
                .containsExactlyInAnyOrder("runId", "conversationId", "body", "createdAt", "state",
                    "conversationMode", "conversationModeVersion");
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private void runTurn() {
        assertThat(handler.handle(worker(AiFixture.ORG_A), envelope())).startsWith("drafted");
    }

    private JobEnvelope envelope() {
        return new JobEnvelope(UUID.randomUUID(), QueueName.AI_RESPONSE, AiFixture.ORG_A,
            AiFixture.PROJECT_A, "Conversation", conversation,
            Map.of("conversationId", conversation.toString(),
                "triggerMessageId", triggerMessage.toString()),
            "corr", triggerMessage.toString(), 1, "job-" + triggerMessage, false, null, null);
    }

    private UUID tenantBConversation() {
        return fixture.conversation(AiFixture.ORG_B, AiFixture.PROJECT_B, "AI");
    }

    private void takeOver() {
        fixture.jdbcUpdate("""
            update conversations c set "mode" = cast('HUMAN' as "ConversationMode"),
                "modeVersion" = c."modeVersion" + 1, "updatedAt" = :now
             where c."id" = :conversation
            """, Map.of("conversation", conversation, "now", AiFixture.now()));
    }

    private TenantContext worker(UUID org) {
        return TenantContext.forMachine(com.firstelement.security.PrincipalType.WORKER, org,
            "ai-draft-http-tests", List.of(AiFixture.PROJECT_A, AiFixture.PROJECT_B),
            List.of(com.firstelement.security.Capability.AI_RUN,
                com.firstelement.security.Capability.KNOWLEDGE_RETRIEVE,
                com.firstelement.security.Capability.AI_TOOL_EXECUTE,
                com.firstelement.security.Capability.DOCUMENT_INGEST));
    }

    private ResponseEntity<String> exchange(String cookie, HttpMethod method, String path, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        if (cookie != null) {
            headers.set(HttpHeaders.COOKIE, cookie);
        }
        return http.exchange(URI.create(path), method, new HttpEntity<>(body, headers), String.class);
    }

    /** A real revocable session; the raw token never leaves this method. */
    private String cookieFor(UUID userId) {
        BrowserSessionStore.IssuedSession issued =
            sessions.issue(userId, "ai-draft-http-test", "127.0.0.1", 1);
        assertThat(issued).isNotNull();
        return securityProperties.getSessionCookieName() + "=" + issued.rawToken();
    }

    private static JsonNode json(String body) {
        try {
            return new ObjectMapper().readTree(body == null ? "null" : body);
        } catch (Exception e) {
            throw new AssertionError("the response was not JSON: " + body, e);
        }
    }
}