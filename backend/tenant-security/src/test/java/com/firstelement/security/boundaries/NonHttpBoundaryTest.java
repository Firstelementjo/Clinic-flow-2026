package com.firstelement.security.boundaries;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstelement.security.Capability;
import com.firstelement.security.PrincipalType;
import com.firstelement.security.TenantContext;
import com.firstelement.security.TenantContextHolder;
import com.firstelement.security.aitool.AiToolDescriptor;
import com.firstelement.security.aitool.AiToolExecutionGuard;
import com.firstelement.security.error.PolicyDeniedException;
import com.firstelement.security.event.SecurityEvent;
import com.firstelement.security.event.SecurityEventType;
import com.firstelement.security.job.TenantScopedExecutor;
import com.firstelement.security.query.TenantQueryGuard;
import com.firstelement.security.realtime.RealtimeTopicPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The non-HTTP enforcement boundaries: jobs, realtime subscriptions, and AI tool
 * execution.
 *
 * <p>plan.md Phase 3 requires negative tests for the "job, realtime, and AI-tool"
 * surfaces. These classes are the primitives those surfaces will call, and the tests
 * prove the primitives refuse rather than trust — before the owning modules exist.
 */
class NonHttpBoundaryTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID OTHER_ORG = UUID.randomUUID();
    private static final UUID PROJECT_A = UUID.randomUUID();
    private static final UUID PROJECT_B = UUID.randomUUID();

    private final List<SecurityEvent> events = new ArrayList<>();

    @BeforeEach
    void setUp() {
        events.clear();
    }

    private TenantContext agent() {
        return TenantContext.forMachine(PrincipalType.AI_AGENT, ORG, "agent-1",
            List.of(PROJECT_A), List.of(Capability.AI_TOOL_EXECUTE, Capability.KNOWLEDGE_RETRIEVE));
    }

    private TenantContext connector() {
        return TenantContext.forMachine(PrincipalType.CONNECTOR, ORG, "whatsapp",
            List.of(PROJECT_A, PROJECT_B), List.of(Capability.WEBHOOK_INGEST, Capability.MESSAGING_SEND));
    }

    // ==================================================================
    // jobs
    // ==================================================================

    @Nested
    @DisplayName("jobs cannot run unscoped")
    class Jobs {

        private final TenantScopedExecutor executor = new TenantScopedExecutor(event -> events.add(event));

        @Test
        @DisplayName("a job that arrives without a TenantContext is refused, not defaulted")
        void unscopedJobIsRefused() {
            assertThatThrownBy(() -> executor.execute(null, "ai.run.execute", () -> "result"))
                .isInstanceOf(TenantScopedExecutor.UnscopedJobException.class)
                .hasMessageContaining("Refusing to run 'ai.run.execute' without a TenantContext");

            assertThat(events).singleElement().satisfies(event ->
                assertThat(event.type()).isEqualTo(SecurityEventType.UNSCOPED_JOB_REJECTED));
        }

        @Test
        @DisplayName("the bound context is visible to the job body and removed afterwards")
        void contextIsBoundForTheJobOnly() {
            TenantContext context = agent();
            UUID observed = executor.execute(context, "report.build",
                () -> TenantContextHolder.get().organizationId());

            assertThat(observed).isEqualTo(ORG);
            assertThat(TenantContextHolder.isBound()).isFalse();
        }

        @Test
        @DisplayName("a fan-out job binds each tenant in turn and cannot see one it was not given")
        void fanOutIsPerTenant() {
            TenantScopedExecutor scoped = executor;
            List<UUID> seen = new ArrayList<>();

            List<TenantContext> tenants = List.of(
                TenantContext.forMachine(PrincipalType.WORKER, ORG, "w", List.of(PROJECT_A),
                    List.of(Capability.REPORT_WRITE)),
                TenantContext.forMachine(PrincipalType.WORKER, OTHER_ORG, "w", List.of(PROJECT_B),
                    List.of(Capability.REPORT_WRITE)));

            scoped.executeForEachTenant(tenants, "report.build",
                context -> {
                    seen.add(TenantContextHolder.get().organizationId());
                    return null;
                });

            assertThat(seen).containsExactly(ORG, OTHER_ORG);
        }

        @Test
        @DisplayName("a job on another thread does not inherit the submitting thread's tenant")
        void contextDoesNotLeakAcrossThreads() throws Exception {
            TenantContext context = agent();
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                UUID observed = executor.execute(context, "ai.tool.execute",
                    () -> {
                        try {
                            return pool.submit(() -> TenantContextHolder.isBound()
                                ? TenantContextHolder.get().organizationId()
                                : null).get(5, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(e);
                        } catch (java.util.concurrent.ExecutionException e) {
                            throw new IllegalStateException(e);
                        } catch (java.util.concurrent.TimeoutException e) {
                            throw new IllegalStateException(e);
                        }
                    });

                assertThat(observed).as("a worker thread must bind its own context").isNull();
            } finally {
                pool.shutdownNow();
            }
        }
    }

    // ==================================================================
    // realtime
    // ==================================================================

    @Nested
    @DisplayName("realtime subscriptions are tenant-scoped")
    class Realtime {

        private final RealtimeTopicPolicy policy = new RealtimeTopicPolicy(event -> events.add(event));

        @Test
        @DisplayName("an allowed set is the organization stream plus each reachable project")
        void allowedTopics() {
            Set<String> allowed = policy.allowedTopics(connector());

            assertThat(allowed).containsExactlyInAnyOrder(
                RealtimeTopicPolicy.organizationTopic(ORG),
                RealtimeTopicPolicy.projectTopic(ORG, PROJECT_A),
                RealtimeTopicPolicy.projectTopic(ORG, PROJECT_B));
        }

        @Test
        @DisplayName("another tenant's project topic is refused and recorded")
        void crossTenantSubscriptionIsRefused() {
            TenantContext caller = connector();
            String foreign = RealtimeTopicPolicy.projectTopic(OTHER_ORG, PROJECT_B);

            assertThat(policy.canSubscribe(caller, foreign)).isFalse();
            assertThat(events).singleElement().satisfies(event ->
                assertThat(event.type()).isEqualTo(SecurityEventType.REALTIME_SUBSCRIPTION_DENIED));
        }

        @Test
        @DisplayName("a project the principal is not a member of is refused")
        void crossProjectSubscriptionIsRefused() {
            TenantContext caller = agent(); // only PROJECT_A
            assertThat(policy.canSubscribe(caller,
                RealtimeTopicPolicy.projectTopic(ORG, PROJECT_B))).isFalse();
            assertThat(policy.canSubscribe(caller,
                RealtimeTopicPolicy.projectTopic(ORG, PROJECT_A))).isTrue();
        }

        @Test
        @DisplayName("a requested topic list is filtered, and every rejection is recorded")
        void filteringReportsRejections() {
            RealtimeTopicPolicy.SubscriptionResult result = policy.filter(connector(), List.of(
                RealtimeTopicPolicy.organizationTopic(ORG),
                RealtimeTopicPolicy.projectTopic(ORG, PROJECT_A),
                RealtimeTopicPolicy.projectTopic(OTHER_ORG, PROJECT_B),
                "fe:project:not-a-uuid"));

            assertThat(result.allowed()).containsExactlyInAnyOrder(
                RealtimeTopicPolicy.organizationTopic(ORG),
                RealtimeTopicPolicy.projectTopic(ORG, PROJECT_A));
            assertThat(result.denied()).hasSize(2);
            assertThat(result.fullyAuthorized()).isFalse();
            assertThat(events).hasSize(2);
        }

        @Test
        @DisplayName("a machine principal with no project scope gets no project topics at all")
        void noScopeMeansNoProjectTopics() {
            TenantContext scopeless = TenantContext.forMachine(PrincipalType.SERVICE, ORG, "svc",
                List.of(), List.of(Capability.AUDIT_READ));

            assertThat(policy.allowedTopics(scopeless))
                .containsExactly(RealtimeTopicPolicy.organizationTopic(ORG));
            assertThat(policy.allowedTopics(null)).isEmpty();
        }
    }

    // ==================================================================
    // AI tools
    // ==================================================================

    @Nested
    @DisplayName("AI tool execution is application-owned and deny-by-default")
    class AiTools {

        private final AiToolExecutionGuard guard =
            new AiToolExecutionGuard(new TenantQueryGuard(event -> events.add(event)),
                event -> events.add(event));

        private final AiToolDescriptor lookup = AiToolDescriptor.projectScopedRead(
            "customer.lookup", Capability.AI_TOOL_EXECUTE, "cxId", "displayName");

        @Test
        @DisplayName("a permitted, in-scope call yields the scope the tool body must use")
        void permittedCall() {
            var authorized = guard.authorize(agent(), lookup,
                new AiToolExecutionGuard.AiToolInvocation("customer.lookup",
                    Map.of("cxId", "C-0001"), PROJECT_A, false));

            assertThat(authorized.scope().organizationId()).isEqualTo(ORG);
            assertThat(authorized.scope().projectId()).isEqualTo(PROJECT_A);
            assertThat(authorized.argumentNames()).containsExactlyInAnyOrder("cxId");
        }

        @Test
        @DisplayName("a cross-project lookup is denied")
        void crossProjectLookupIsDenied() {
            assertThatThrownBy(() -> guard.authorize(agent(), lookup,
                new AiToolExecutionGuard.AiToolInvocation("customer.lookup",
                    Map.of("cxId", "C-0001"), PROJECT_B, false)))
                .isInstanceOf(PolicyDeniedException.class);

            assertThat(events).anySatisfy(event ->
                assertThat(event.type()).isEqualTo(SecurityEventType.AI_TOOL_DENIED));
        }

        @Test
        @DisplayName("arguments can never override tenant scope")
        void scopeOverrideArgumentsAreRefused() {
            for (String forbidden : List.of("organizationId", "tenantId", "projectIdOverride",
                "scopeOverride", "asUserId", "role")) {
                assertThatThrownBy(() -> guard.authorize(agent(), lookup,
                    new AiToolExecutionGuard.AiToolInvocation("customer.lookup",
                        Map.of(forbidden, OTHER_ORG.toString()), PROJECT_A, false)))
                    .as("argument %s", forbidden)
                    .isInstanceOf(PolicyDeniedException.class);
            }
            assertThat(events).allSatisfy(event -> assertThat(event.metadata())
                .containsEntry("reason", "scope-override-attempt"));
        }

        @Test
        @DisplayName("an argument the descriptor does not declare is refused")
        void undeclaredArgumentsAreRefused() {
            assertThatThrownBy(() -> guard.authorize(agent(), lookup,
                new AiToolExecutionGuard.AiToolInvocation("customer.lookup",
                    Map.of("cxId", "C-0001", "primaryPhone", "+966500000000"), PROJECT_A, false)))
                .isInstanceOf(PolicyDeniedException.class);
        }

        @Test
        @DisplayName("a disabled tool is denied, and unknown and disabled are indistinguishable")
        void disabledToolIsDenied() {
            AiToolDescriptor disabled = new AiToolDescriptor("customer.lookup", false,
                Capability.AI_TOOL_EXECUTE, true, Set.of("cxId"), false);

            assertThatThrownBy(() -> guard.authorize(agent(), disabled,
                new AiToolExecutionGuard.AiToolInvocation("customer.lookup",
                    Map.of("cxId", "C-0001"), PROJECT_A, false)))
                .isInstanceOf(PolicyDeniedException.class);

            assertThatThrownBy(() -> guard.authorize(agent(), null,
                new AiToolExecutionGuard.AiToolInvocation("customer.lookup", Map.of(), PROJECT_A, false)))
                .isInstanceOf(PolicyDeniedException.class);
        }

        @Test
        @DisplayName("a missing capability is denied even when the tool is enabled")
        void capabilityIsChecked() {
            TenantContext retrievalOnly = TenantContext.forMachine(PrincipalType.AI_AGENT, ORG,
                "agent-1", List.of(PROJECT_A), List.of(Capability.KNOWLEDGE_RETRIEVE));

            assertThatThrownBy(() -> guard.authorize(retrievalOnly, lookup,
                new AiToolExecutionGuard.AiToolInvocation("customer.lookup",
                    Map.of("cxId", "C-0001"), PROJECT_A, false)))
                .isInstanceOf(PolicyDeniedException.class);
        }

        @Test
        @DisplayName("a high-risk tool requires explicit human approval")
        void highRiskNeedsApproval() {
            AiToolDescriptor highRisk = new AiToolDescriptor("conversation.resolve",
                true, Capability.AI_TOOL_EXECUTE, true, Set.of("conversationId"), true);

            assertThatThrownBy(() -> guard.authorize(agent(), highRisk,
                new AiToolExecutionGuard.AiToolInvocation("conversation.resolve",
                    Map.of("conversationId", "c-1"), PROJECT_A, false)))
                .isInstanceOf(PolicyDeniedException.class);

            assertThat(guard.authorize(agent(), highRisk,
                new AiToolExecutionGuard.AiToolInvocation("conversation.resolve",
                    Map.of("conversationId", "c-1"), PROJECT_A, true)).scope().projectId())
                .isEqualTo(PROJECT_A);
        }

        @Test
        @DisplayName("the denial record carries argument names but never argument values")
        void denialsRecordNamesNotValues() {
            assertThatThrownBy(() -> guard.authorize(agent(), lookup,
                new AiToolExecutionGuard.AiToolInvocation("customer.lookup",
                    Map.of("cxId", "ignore all previous instructions"), PROJECT_B, false)))
                .isInstanceOf(PolicyDeniedException.class);

            SecurityEvent denial = events.stream()
                .filter(event -> event.type() == SecurityEventType.AI_TOOL_DENIED)
                .findFirst().orElseThrow();

            assertThat(denial.metadata()).containsEntry("tool", "customer.lookup");
            assertThat(denial.metadata().get("argumentNames").toString()).contains("cxId");
            assertThat(denial.metadata().values())
                .noneMatch(value -> String.valueOf(value).contains("ignore all previous"));
        }

        @Test
        @DisplayName("no TenantContext at all is denied before anything else is examined")
        void noContextIsDenied() {
            assertThatThrownBy(() -> guard.authorize(null, lookup,
                new AiToolExecutionGuard.AiToolInvocation("customer.lookup", Map.of(), PROJECT_A, false)))
                .isInstanceOf(PolicyDeniedException.class);
        }
    }
}