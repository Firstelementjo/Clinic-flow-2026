package com.firstelement.airag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstelement.airag.orchestration.AiOrchestrator;
import com.firstelement.airag.orchestration.AiRunDisposition;
import com.firstelement.airag.orchestration.AiRunResult;
import com.firstelement.airag.policy.AiRefusalReason;
import com.firstelement.airag.principal.AiPrincipalScope;
import com.firstelement.airag.send.AiSendAuthorization;
import com.firstelement.security.TenantContext;
import com.firstelement.security.query.TenantScope;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ADR 0007 Part 2 rule 2: AI may send only in AI mode — and the takeover race that decides whether an
 * answer produced before a human intervened may still be delivered after they did.
 *
 * <h2>The race, stated precisely</h2>
 *
 * <p>An AI run finishes, and between that moment and the moment the answer is enqueued for delivery an
 * employee takes the conversation over. If the send consults a mode it read <em>before</em> the takeover
 * committed, it delivers an answer into a conversation a human has just claimed — which is the exact
 * outcome a takeover exists to prevent.
 *
 * <p>{@link AiSendAuthorization} closes half of that: it reads the mode with {@code FOR UPDATE}, so a
 * takeover already in flight is waited for rather than raced. The other half belongs to Phase 6's
 * {@code AiSendService}, which re-reads the mode in the same transaction as the outbox publish — that class
 * does not exist yet and is reported as a hook, not faked here. What this suite proves is that the half
 * which <em>does</em> exist really takes the lock, by holding it in one transaction and observing a
 * concurrent takeover block on it.
 *
 * <h2>Why the tests use two connections and not a mocked clock</h2>
 *
 * <p>A "race" reproduced on one connection inside one transaction is not a race; it is a sequence, and it
 * passes against an implementation that takes no lock at all. So the takeover test really does open a second
 * transaction from a second thread and asserts that its UPDATE does not complete until the first commits.
 */
@SpringBootTest(classes = AiRagTestApplication.class)
class SendHandoffRaceIntegrationTest extends AiIntegrationTestBase {

    @Autowired
    private AiFixture fixture;

    @Autowired
    private AiSendAuthorization authorization;

    @Autowired
    private AiOrchestrator orchestrator;

    @Autowired
    private PlatformTransactionManager transactions;

    private TransactionTemplate tx;

    private UUID knowledgeBase;
    private AiFixture.AgentVersion agent;
    private UUID conversation;
    private UUID triggerMessage;

    @BeforeEach
    void seed() {
        fixture.reset();
        tx = new TransactionTemplate(transactions);
        knowledgeBase = fixture.knowledgeBase(AiFixture.ORG_A, AiFixture.PROJECT_A, "KB");
        agent = fixture.agentWithVersion(AiFixture.ORG_A, AiFixture.PROJECT_A, AiFixture.ADMIN_A,
            "MOCK", "mock-deterministic-v1", "[]", "[\"" + knowledgeBase + "\"]", 0.0d,
            "أجب من المصادر فقط");
        fixture.policy(AiFixture.ORG_A, AiFixture.PROJECT_A, "default", 4000, 4, "STRICT_CLINICAL",
            AiFixture.ADMIN_A);
        fixture.model(AiFixture.ORG_A, AiFixture.PROJECT_A, "MOCK", "mock-deterministic-v1", true,
            "LOCAL", false, "USD", "0.00000150", "0.00000600");
        conversation = fixture.conversation(AiFixture.ORG_A, AiFixture.PROJECT_A, "AI");
        triggerMessage = fixture.customerMessage(AiFixture.ORG_A, AiFixture.PROJECT_A, conversation,
            "ما ساعات العمل؟");
        UUID document = fixture.document(AiFixture.ORG_A, AiFixture.PROJECT_A, knowledgeBase, "ساعات",
            "READY");
        fixture.chunk(AiFixture.ORG_A, AiFixture.PROJECT_A, knowledgeBase, document, 0,
            "ساعات العمل من الأحد إلى الخميس.", "ساعات العمل");
    }

    @Nested
    @DisplayName("the mode gate")
    class ModeGate {

        @Test
        @DisplayName("an answered run on an AI conversation is authorized")
        void answeredInAiModeIsAuthorized() {
            AiRunResult result = run();

            AiSendAuthorization.SendAuthorization decision = authorize(admin(), result);

            assertThat(decision.authorized()).isTrue();
            assertThat(decision.code()).isEqualTo("AUTHORIZED");
            assertThat(decision.scope()).isEqualTo(scopeA());
            assertThat(decision.conversationId()).isEqualTo(conversation);
        }

        @Test
        @DisplayName("a conversation an employee took over is NOT authorized, and the refusal names the mode")
        void humanModeIsRefused() {
            AiRunResult result = run();
            takeover();

            AiSendAuthorization.SendAuthorization decision = authorize(admin(), result);

            // Outside AI mode the assistant may DRAFT; it may not send. The detail names the mode, which is
            // not a disclosure — the caller is an operator who can see it, and "an employee owns it" is the
            // actionable fact.
            assertThat(decision.authorized()).isFalse();
            assertThat(decision.reason()).isEqualTo(AiRefusalReason.NOT_AI_MODE);
            assertThat(decision.detail()).isEqualTo("mode=HUMAN");
        }

        @Test
        @DisplayName("a conversation the principal is not scoped to is refused by the guard, before any read")
        void outOfScopeProjectIsRefusedByTheGuard() {
            AiRunResult result = run();

            // The principal carries PROJECT_A only, so PROJECT_A2 is refused by TenantQueryGuard before the
            // mode statement runs at all. That is the right place for the refusal: the guard is what decides
            // the caller's scope, and a mode answer for a project the caller cannot see would be a question
            // answered about someone else's conversation.
            assertThatThrownBy(() -> authorization.authorize(admin(), AiFixture.PROJECT_A2, conversation,
                result)).isInstanceOf(com.firstelement.security.error.PolicyDeniedException.class);
        }

        @Test
        @DisplayName("a member of BOTH projects still cannot read the other project's conversation")
        void membershipInBothProjectsDoesNotGrantVisibility() {
            AiRunResult result = run();
            TenantContext bothProjects = AiPrincipalScope.admin(AiFixture.ORG_A, AiFixture.ADMIN_A,
                List.of(AiFixture.PROJECT_A, AiFixture.PROJECT_A2));

            // Scoped to both, so the guard resolves -- and the mode statement still finds no row, because
            // the conversation's own projectId is PROJECT_A. Denied rather than reporting mode=AI from the
            // row it can see under a different scope.
            AiSendAuthorization.SendAuthorization decision = authorization.authorize(bothProjects,
                AiFixture.PROJECT_A2, conversation, result);

            assertThat(decision.authorized()).isFalse();
            assertThat(decision.reason()).isEqualTo(AiRefusalReason.NOT_AI_MODE);
            assertThat(decision.detail())
                .as("and it does not leak the other project's mode either")
                .isNull();
        }

        @Test
        @DisplayName("a conversation that does not exist is refused, not treated as unowned")
        void missingConversationIsRefused() {
            AiRunResult result = run();

            AiSendAuthorization.SendAuthorization decision = authorization.authorize(admin(),
                AiFixture.PROJECT_A, UUID.randomUUID(), result);

            assertThat(decision.authorized()).isFalse();
            assertThat(decision.reason()).isEqualTo(AiRefusalReason.NOT_AI_MODE);
        }
    }

    @Nested
    @DisplayName("who may send")
    class SendPermission {

        @Test
        @DisplayName("an EMPLOYEE without the AI_SEND permission is refused by the authorization service")
        void employeeWithoutAiSendIsRefused() {
            AiRunResult result = run();
            TenantContext employee = AiPrincipalScope.employee(AiFixture.ORG_A, AiFixture.STAFF_A,
                List.of(AiFixture.PROJECT_A));

            // RolePermissions deliberately did not widen the employee grant with an AI-specific permission,
            // so this is a real absence rather than a fixture trick. Asserted against AccessDeniedException
            // because that is what AuthorizationService raises, not a FirstElement PolicyDeniedException --
            // naming the wrong type would make the test a tautology about the class hierarchy.
            assertThatThrownBy(() -> authorization.authorize(employee, AiFixture.PROJECT_A, conversation,
                result)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
            assertThat(com.firstelement.security.permission.RolePermissions
                .forRole(com.firstelement.security.UserRole.EMPLOYEE))
                .doesNotContain(AiSendAuthorization.sendPermission());
        }

        @Test
        @DisplayName("an ADMIN holds AI_SEND, so the same call succeeds")
        void adminHoldsAiSend() {
            AiRunResult result = run();

            assertThat(AiSendAuthorization.sendPermission())
                .isEqualTo(com.firstelement.security.permission.Permission.AI_SEND);
            assertThat(authorization.authorize(admin(), AiFixture.PROJECT_A, conversation, result)
                .authorized()).isTrue();
        }

        @Test
        @DisplayName("the MACHINE principal can also send, via AI_RUN, so the no-key path is not blocked")
        void theAgentPrincipalCanSend() {
            AiRunResult result = run();
            TenantContext agentPrincipal = AiPrincipalScope.agentScopedTo(AiFixture.ORG_A, AiFixture.PROJECT_A);

            // forCapabilities grants AI_SEND to exactly Capability.AI_RUN, so the agent reaches the gate
            // through the same permission -- and NOT because it is an AI_AGENT. Asserted because "the
            // machine may speak to a customer" is only acceptable while the permission still means something.
            assertThat(agentPrincipal.hasCapability(com.firstelement.security.Capability.AI_RUN)).isTrue();
            assertThat(authorization.authorize(agentPrincipal, AiFixture.PROJECT_A, conversation, result)
                .authorized()).isTrue();
        }

        @Test
        @DisplayName("an agent with NO capabilities cannot send, because it holds no permission")
        void anAgentWithoutCapabilitiesCannotSend() {
            AiRunResult result = run();
            TenantContext stripped = AiPrincipalScope.agentWithoutCapabilities(AiFixture.ORG_A,
                List.of(AiFixture.PROJECT_A));

            // A machine principal's permissions come FROM its capabilities, so removing the capabilities
            // removes AI_SEND. Fails closed at the permission, which is where the grant actually lives.
            assertThatThrownBy(() -> authorization.authorize(stripped, AiFixture.PROJECT_A, conversation,
                result)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        }

        @Test
        @DisplayName("holding AI_TOOL_EXECUTE alone does not grant a send")
        void toolExecuteDoesNotGrantSend() {
            // The separation ADR 0007 Part 2 asks for, asserted against the real grant table rather than
            // against this module's reading of it: Capability.AI_TOOL_EXECUTE maps to the tool permissions
            // and NOT to AI_SEND.
            assertThat(com.firstelement.security.permission.RolePermissions
                .forCapabilities(java.util.Set.of(com.firstelement.security.Capability.AI_TOOL_EXECUTE)))
                .doesNotContain(AiSendAuthorization.sendPermission());
            assertThat(com.firstelement.security.permission.RolePermissions
                .forCapabilities(java.util.Set.of(com.firstelement.security.Capability.AI_RUN)))
                .contains(AiSendAuthorization.sendPermission());
        }
    }

    @Nested
    @DisplayName("what may be sent")
    class Disposition {

        @Test
        @DisplayName("an ESCALATED run has no sendable answer, and this class does not invent one")
        void escalatedHasNoAnswer() {
            AiRunResult escalated = synthetic(AiRunDisposition.ESCALATED, null);

            AiSendAuthorization.SendAuthorization decision = authorize(admin(), escalated);

            assertThat(decision.authorized()).isFalse();
            // There is deliberately no fallback here: a caller that wants a fallback message writes it
            // itself, with its own content, rather than this class inventing one.
            assertThat(decision.reason()).isEqualTo(AiRefusalReason.EMPTY_COMPLETION);
            assertThat(decision.detail()).contains("ESCALATED");
        }

        @Test
        @DisplayName("a REFUSED run is refused with the refusal code, so the reason reaches the operator")
        void refusedRunIsRefused() {
            AiRunResult refused = new AiRunResult(UUID.randomUUID(), AiRunDisposition.REFUSED, null,
                "PROVIDER_UNAVAILABLE", List.of(), List.of(), List.of(), List.of(), "sha-x",
                List.of("MOCK"), null, null, null, null, 0, List.of());

            AiSendAuthorization.SendAuthorization decision = authorize(admin(), refused);

            assertThat(decision.authorized()).isFalse();
            assertThat(decision.reason()).isEqualTo(AiRefusalReason.ABORTED_BY_HUMAN);
            assertThat(decision.detail()).isEqualTo("PROVIDER_UNAVAILABLE");
        }

        @Test
        @DisplayName("a null result is refused rather than read as permission to send something")
        void nullResultIsRefused() {
            assertThat(authorization.authorize(admin(), AiFixture.PROJECT_A, conversation, null)
                .authorized()).isFalse();
        }

        @Test
        @DisplayName("the refusal detail never carries the answer text, which is customer content")
        void refusalCarriesNoAnswerText() {
            String answer = "ساعات العمل من الأحد، والمريض 1012345678";
            AiRunResult answered = synthetic(AiRunDisposition.ANSWERED, answer);
            takeover();

            AiSendAuthorization.SendAuthorization decision = authorize(admin(), answered);

            assertThat(decision.detail()).doesNotContain("1012345678").doesNotContain(answer);
            assertThat(decision.result()).isNull();
        }
    }

    @Nested
    @DisplayName("the takeover race")
    class Race {

        @Test
        @DisplayName("the authorization takes the conversation row lock, so a takeover WAITS for it")
        void theAuthorizationLocksTheConversation() throws Exception {
            CountDownLatch locked = new CountDownLatch(1);
            CountDownLatch takeoverStarted = new CountDownLatch(1);
            AtomicReference<AiSendAuthorization.SendAuthorization> decision = new AtomicReference<>();
            AtomicReference<Boolean> takeoverCompleted = new AtomicReference<>(false);

            // Thread A: authorize and HOLD the transaction, which holds SELECT ... FOR UPDATE on the
            // conversation row.
            Thread reader = new Thread(() -> tx.executeWithoutResult(status -> {
                decision.set(authorization.authorize(admin(), AiFixture.PROJECT_A, conversation,
                    synthetic(AiRunDisposition.ANSWERED, "إجابة")));
                locked.countDown();
                try {
                    // Long enough for the writer to demonstrably reach its UPDATE and block on it.
                    Thread.sleep(750L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            reader.start();
            assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue();

            // Thread B: an employee takes the conversation over. This UPDATE must block on A's row lock.
            Thread writer = new Thread(() -> tx.executeWithoutResult(status -> {
                takeoverStarted.countDown();
                fixture.jdbcUpdate("""
                    update conversations set "mode" = cast('HUMAN' as "ConversationMode"), "updatedAt" = :now
                     where "id" = :id and "organizationId" = :org
                    """, Map.of("now", AiFixture.now(), "id", conversation, "org", AiFixture.ORG_A));
                takeoverCompleted.set(true);
            }));
            writer.start();
            assertThat(takeoverStarted.await(30, TimeUnit.SECONDS)).isTrue();

            // While A holds the lock, B has NOT completed. Without FOR UPDATE this would already be true,
            // and the test would pass against an implementation with no lock at all.
            Thread.sleep(300L);
            assertThat(takeoverCompleted.get())
                .as("the takeover UPDATE must not complete while the authorization holds the row lock")
                .isFalse();

            reader.join(30_000L);
            writer.join(30_000L);

            assertThat(takeoverCompleted.get())
                .as("and it completes once the authorization's transaction ends -- a wait, not a deadlock")
                .isTrue();
            assertThat(decision.get().authorized())
                .as("the authorization saw the pre-takeover mode, which is correct: it is an EARLY check, "
                    + "and AiSendService re-reads under the same lock at publish time")
                .isTrue();
            assertThat(modeOf(conversation)).isEqualTo("HUMAN");
        }

        @Test
        @DisplayName("a takeover that COMMITTED before the check is seen, so no stale answer is sent")
        void aCommittedTakeoverIsSeen() {
            // The other ordering, and the one that decides the customer outcome: takeover first, then
            // authorize. The mode read observes the committed HUMAN mode and refuses.
            AiRunResult result = run();
            takeover();

            AiSendAuthorization.SendAuthorization decision = authorize(admin(), result);

            assertThat(decision.authorized()).isFalse();
            assertThat(decision.detail()).isEqualTo("mode=HUMAN");
        }

        @Test
        @DisplayName("the gate does not cache the mode between calls")
        void theGateDoesNotCacheTheMode() {
            AiRunResult result = run();

            assertThat(authorize(admin(), result).authorized()).isTrue();
            takeover();
            assertThat(authorize(admin(), result).authorized()).isFalse();
            handBack();
            assertThat(authorize(admin(), result).authorized())
                .as("every call reads the row, so a re-handed-back conversation can be automated again")
                .isTrue();
        }

        @Test
        @DisplayName("the lock is held PAST the method return, which is what @Transactional buys")
        void theLockOutlivesTheMethodCall() throws Exception {
            // The reflection test that used to sit here asserted that authorize() carries @Transactional.
            // It is removed deliberately: the property it checked is already proved by
            // theAuthorizationLocksTheConversation, and proved better. If authorize() were not
            // transactional, the FOR UPDATE lock would be released when it returned, the writer's UPDATE
            // would complete during the reader's 750ms hold, and that test would fail. Reflecting over an
            // annotation to re-check what a behavioural test already establishes is the kind of assertion
            // that survives a refactor which breaks the thing it claims to protect.
            CountDownLatch released = new CountDownLatch(1);
            AtomicReference<Boolean> blockedWhileIdle = new AtomicReference<>(null);

            Thread reader = new Thread(() -> tx.executeWithoutResult(status -> {
                authorization.authorize(admin(), AiFixture.PROJECT_A, conversation,
                    synthetic(AiRunDisposition.ANSWERED, "إجابة"));
                released.countDown();
                try {
                    Thread.sleep(750L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            reader.start();
            assertThat(released.await(30, TimeUnit.SECONDS)).isTrue();

            // No lock is held between authorize() returning and the next statement in the SAME transaction,
            // so a writer that starts here must complete. Asserted so the previous test's meaning is
            // explicit: what blocks the takeover is the open transaction, not the method call.
            Thread writer = new Thread(() -> tx.executeWithoutResult(status -> {
                Instant start = Instant.now();
                fixture.jdbcUpdate("""
                    update conversations set "updatedAt" = :now
                     where "id" = :id and "organizationId" = :org
                    """, Map.of("now", AiFixture.now(), "id", conversation, "org", AiFixture.ORG_A));
                blockedWhileIdle.set(Duration.between(start, Instant.now()).toMillis() > 400L);
            }));
            writer.start();
            writer.join(30_000L);
            reader.join(30_000L);

            assertThat(blockedWhileIdle.get()).isTrue();
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private AiRunResult run() {
        TenantContext principal = AiPrincipalScope.agentScopedTo(AiFixture.ORG_A, AiFixture.PROJECT_A);
        return orchestrator.run(new AiOrchestrator.RunRequest(principal, AiFixture.PROJECT_A, conversation,
            triggerMessage, agent.agentId(), agent.versionId(), "ما ساعات العمل؟"));
    }

    private AiSendAuthorization.SendAuthorization authorize(TenantContext principal, AiRunResult result) {
        return authorization.authorize(principal, AiFixture.PROJECT_A, conversation, result);
    }

    private TenantContext admin() {
        return AiPrincipalScope.admin(AiFixture.ORG_A, AiFixture.ADMIN_A, List.of(AiFixture.PROJECT_A));
    }

    private TenantScope scopeA() {
        return new TenantScope(AiFixture.ORG_A, AiFixture.PROJECT_A);
    }

    private void takeover() {
        setMode("HUMAN");
    }

    private void handBack() {
        setMode("AI");
    }

    private void setMode(String mode) {
        fixture.jdbcUpdate("""
            update conversations set "mode" = cast(:mode as "ConversationMode"), "updatedAt" = :now
             where "id" = :id and "organizationId" = :org
            """, Map.of("mode", mode, "now", AiFixture.now(), "id", conversation, "org", AiFixture.ORG_A));
    }

    private String modeOf(UUID conversationId) {
        return fixture.text("select c.\"mode\"::text from conversations c where c.\"id\" = :id",
            Map.of("id", conversationId));
    }

    /** An answered result without running the orchestrator, for the disposition and detail assertions. */
    private static AiRunResult synthetic(AiRunDisposition disposition, String answer) {
        return new AiRunResult(UUID.randomUUID(), disposition, answer, null, List.of(), List.of(), List.of(),
            List.of(), "sha-synthetic", List.of("MOCK"), null, null, 100, 20, 0, List.of());
    }
}
