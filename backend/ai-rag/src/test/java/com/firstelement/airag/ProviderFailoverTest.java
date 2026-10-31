package com.firstelement.airag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstelement.airag.config.AiRagProperties;
import com.firstelement.airag.provider.AiChatProvider;
import com.firstelement.airag.provider.AiEmbeddingProvider;
import com.firstelement.airag.provider.AiProviderKind;
import com.firstelement.airag.provider.AiProviderRegistry;
import com.firstelement.airag.provider.MockAiChatProvider;
import com.firstelement.airag.provider.ProviderAttempt;
import com.firstelement.airag.provider.ProviderFailure;
import com.firstelement.airag.provider.ProviderFailoverExecutor;
import com.firstelement.airag.provider.ProviderOutcome;
import com.firstelement.airag.provider.SecretScrubber;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * Provider outage behaviour: the chain, the per-attempt timeout, and what is persisted when it fails.
 *
 * <h2>Why this suite builds its own adapters rather than using the container</h2>
 *
 * <p>Because the property under test is {@link ProviderFailoverExecutor}'s <em>ordering, bounding and
 * classification</em>, and none of those need a database. A stub that sleeps past the timeout, or throws a
 * message containing "429", is expressible in four lines here and impossible to express against a real
 * provider without a key — which this migration is forbidden from having. So no test here needs a key and
 * none is skipped for lack of one.
 *
 * <p>It is still not a substitute for the container suites: those prove the statements' column names and
 * the real trace. This one proves that a provider outage produces a <b>reason</b> rather than a hang, which
 * is only observable with a provider that misbehaves on demand.
 *
 * <h2>The three outcomes that must never be confused</h2>
 *
 * <ul>
 *   <li><b>No chain configured</b> — the agent named no providers. Refused {@code NO_PROVIDER_CHAIN}, with
 *       zero attempts. Not the same as "the chain was tried and failed".</li>
 *   <li><b>The chain was walked and every hop failed</b> — refused with the LAST failure, and every attempt
 *       recorded.</li>
 *   <li><b>One hop timed out</b> — refused {@code TIMEOUT} for that hop, then the next hop is tried. A
 *       timeout is the reason this class exists.</li>
 * </ul>
 */
class ProviderFailoverTest {

    private static final Prompt PROMPT = new Prompt(List.of(new org.springframework.ai.chat.messages
        .SystemMessage("أجب من المصادر فقط")), ChatOptions.builder().model("m").build());

    @Nested
    @DisplayName("the chain")
    class Chain {

        @Test
        @DisplayName("an agent version naming NO provider is refused, never silently defaulted")
        void noChainIsARefusal() {
            ProviderFailoverExecutor executor = executor(2_000L, 3, throwing(AiProviderKind.OPENAI, "503"));

            ProviderOutcome outcome = executor.execute(List.of(), PROMPT, "m");

            // Substituting a default here would make the model that produced an answer differ from the one
            // the tenant registered, and the provenance row would name a provider nobody asked for.
            assertThat(outcome.answered()).isFalse();
            assertThat(outcome.refusalCode()).isEqualTo("NO_PROVIDER_CHAIN");
            assertThat(outcome.attempts()).as("nothing was tried, so nothing is claimed to have been tried")
                .isEmpty();
        }

        @Test
        @DisplayName("the first hop that answers wins, and later hops are not called")
        void firstSuccessStopsTheChain() {
            StubProvider first = answering(AiProviderKind.OPENAI, "from openai");
            StubProvider second = answering(AiProviderKind.ANTHROPIC, "from anthropic");
            ProviderFailoverExecutor executor = executor(2_000L, 3, first, second);

            ProviderOutcome outcome = executor.execute(
                List.of(AiProviderKind.OPENAI, AiProviderKind.ANTHROPIC), PROMPT, "m");

            assertThat(outcome.answered()).isTrue();
            assertThat(outcome.winningAttempt().provider()).isEqualTo("OPENAI");
            assertThat(outcome.winningAttempt().text()).isEqualTo("from openai");
            assertThat(second.calls()).as("a hop after the winner is wasted budget").isZero();
        }

        @Test
        @DisplayName("a hop that fails transiently hands over to the NEXT provider")
        void transientFailureFailsOver() {
            ProviderFailoverExecutor executor = executor(2_000L, 3,
                throwing(AiProviderKind.OPENAI, "503 Service Unavailable"),
                answering(AiProviderKind.ANTHROPIC, "from anthropic"));

            ProviderOutcome outcome = executor.execute(
                List.of(AiProviderKind.OPENAI, AiProviderKind.ANTHROPIC), PROMPT, "m");

            assertThat(outcome.answered()).isTrue();
            assertThat(outcome.attemptedProviders()).containsExactly("OPENAI", "ANTHROPIC");
            assertThat(outcome.failures()).hasSize(1);
            assertThat(outcome.failures().get(0).failure()).isEqualTo(ProviderFailure.UNAVAILABLE);
        }

        @Test
        @DisplayName("an INVALID_REQUEST stops the chain, because the next provider fails identically")
        void nonRetryableFailureStopsTheChain() {
            StubProvider first = throwing(AiProviderKind.OPENAI, "400 bad request: unknown model");
            StubProvider second = answering(AiProviderKind.ANTHROPIC, "from anthropic");
            ProviderFailoverExecutor executor = executor(2_000L, 3, first, second);

            ProviderOutcome outcome = executor.execute(
                List.of(AiProviderKind.OPENAI, AiProviderKind.ANTHROPIC), PROMPT, "m");

            // Walking on would spend the run's remaining hops on a failure that recurs identically, and
            // dilute the "provider is degraded" signal an operator watches.
            assertThat(outcome.answered()).isFalse();
            assertThat(outcome.refusalCode()).isEqualTo(ProviderFailure.INVALID_REQUEST.name());
            assertThat(second.calls()).isZero();
        }

        @Test
        @DisplayName("a credential failure is NOT_CONFIGURED, so the chain skips the hop cheaply")
        void credentialFailureIsNotConfigured() {
            ProviderFailoverExecutor executor = executor(2_000L, 3,
                throwing(AiProviderKind.OPENAI, "401 unauthorized: invalid api key"),
                answering(AiProviderKind.MOCK, "from mock"));

            ProviderOutcome outcome = executor.execute(
                List.of(AiProviderKind.OPENAI, AiProviderKind.MOCK), PROMPT, "m");

            assertThat(outcome.answered()).isTrue();
            assertThat(outcome.failures().get(0).failure()).isEqualTo(ProviderFailure.NOT_CONFIGURED);
        }

        @Test
        @DisplayName("a provider with no adapter is recorded once, and costs no call")
        void absentAdapterIsRecordedNotCalled() {
            ProviderFailoverExecutor executor = executor(2_000L, 3, answering(AiProviderKind.MOCK, "ok"));

            ProviderOutcome outcome = executor.execute(
                List.of(AiProviderKind.ANTHROPIC, AiProviderKind.MOCK), PROMPT, "m");

            // The normal state with no API key (FR-5.2). "Attempted" would imply work that never happened.
            assertThat(outcome.failures().get(0).failure()).isEqualTo(ProviderFailure.NOT_CONFIGURED);
            assertThat(outcome.failures().get(0).detail()).isNull();
            assertThat(outcome.attemptedProviders()).containsExactly("ANTHROPIC", "MOCK");
        }

        @Test
        @DisplayName("an adapter that declares itself unavailable is skipped, not called")
        void unavailableAdapterIsSkipped() {
            ProviderFailoverExecutor executor = executor(2_000L, 3,
                new StubProvider(AiProviderKind.OPENAI, false, null, null, null),
                answering(AiProviderKind.MOCK, "ok"));

            ProviderOutcome outcome = executor.execute(
                List.of(AiProviderKind.OPENAI, AiProviderKind.MOCK), PROMPT, "m");

            assertThat(outcome.answered()).isTrue();
            assertThat(outcome.failures().get(0).failure()).isEqualTo(ProviderFailure.NOT_CONFIGURED);
        }

        @Test
        @DisplayName("a provider named twice is attempted once, so one logical provider costs one hop")
        void duplicateKindsAreCollapsed() {
            StubProvider openai = answering(AiProviderKind.OPENAI, "ok");
            ProviderFailoverExecutor executor = executor(2_000L, 3, openai);

            ProviderOutcome outcome = executor.execute(
                List.of(AiProviderKind.OPENAI, AiProviderKind.OPENAI, AiProviderKind.OPENAI), PROMPT, "m");

            assertThat(outcome.attemptedProviders()).hasSize(1);
            assertThat(openai.calls()).isEqualTo(1);
        }

        @Test
        @DisplayName("the hop count is bounded, so a dead provider cannot be re-probed forever")
        void hopCountIsBounded() {
            ProviderFailoverExecutor executor = executor(2_000L, 2,
                throwing(AiProviderKind.OPENAI, "503 down"),
                throwing(AiProviderKind.ANTHROPIC, "503 down"),
                throwing(AiProviderKind.GEMINI, "503 down"));

            ProviderOutcome outcome = executor.execute(List.of(AiProviderKind.OPENAI, AiProviderKind.ANTHROPIC,
                AiProviderKind.GEMINI), PROMPT, "m");

            assertThat(outcome.attemptedProviders()).hasSize(2);
            assertThat(outcome.refusalCode()).isEqualTo(ProviderFailure.UNAVAILABLE.name());
        }

        @Test
        @DisplayName("a null entry in the chain is skipped rather than becoming a NO_PROVIDER_CHAIN refusal")
        void nullChainEntryIsSkipped() {
            ProviderFailoverExecutor executor = executor(2_000L, 3, answering(AiProviderKind.MOCK, "ok"));
            List<AiProviderKind> chain = new java.util.ArrayList<>();
            chain.add(null);
            chain.add(AiProviderKind.MOCK);

            ProviderOutcome outcome = executor.execute(chain, PROMPT, "m");

            assertThat(outcome.answered()).isTrue();
        }
    }

    @Nested
    @DisplayName("the per-attempt timeout")
    class Timeout {

        @Test
        @DisplayName("a provider that never answers is abandoned at the deadline, not waited on")
        void aHangingProviderIsAbandoned() throws Exception {
            CountDownLatch release = new CountDownLatch(1);
            var hanging = new StubProvider(AiProviderKind.OPENAI, true, null, null,
                () -> {
                    try {
                        release.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return "too late to matter";
                });
            ProviderFailoverExecutor executor = executor(150L, 3, hanging,
                answering(AiProviderKind.MOCK, "from mock"));

            long started = System.nanoTime();
            ProviderOutcome outcome = executor.execute(
                List.of(AiProviderKind.OPENAI, AiProviderKind.MOCK), PROMPT, "m");
            long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;

            try {
                assertThat(outcome.answered()).isTrue();
                assertThat(outcome.attemptedProviders()).containsExactly("OPENAI", "MOCK");
                assertThat(outcome.failures().get(0).failure()).isEqualTo(ProviderFailure.TIMEOUT);
                // The whole point: a hang in a worker means a leased job and a customer with no answer.
                assertThat(elapsedMillis)
                    .as("the run gave up on the deadline instead of waiting 30s for the latch")
                    .isLessThan(10_000L);
            } finally {
                release.countDown();
            }
        }

        @Test
        @DisplayName("when every hop times out, the refusal is TIMEOUT and every attempt is recorded")
        void allTimeoutsRefuseWithTimeout() {
            ProviderFailoverExecutor executor = executor(120L, 3,
                sleeping(AiProviderKind.OPENAI, 5_000L),
                sleeping(AiProviderKind.ANTHROPIC, 5_000L));

            ProviderOutcome outcome = executor.execute(
                List.of(AiProviderKind.OPENAI, AiProviderKind.ANTHROPIC), PROMPT, "m");

            assertThat(outcome.answered()).isFalse();
            assertThat(outcome.refusalCode()).isEqualTo(ProviderFailure.TIMEOUT.name());
            assertThat(outcome.failures()).allSatisfy(attempt ->
                assertThat(attempt.failure()).isEqualTo(ProviderFailure.TIMEOUT));
        }

        @Test
        @DisplayName("a timed-out attempt carries no text, because there was none")
        void timedOutAttemptHasNoText() {
            ProviderFailoverExecutor executor = executor(120L, 3,
                sleeping(AiProviderKind.OPENAI, 5_000L));

            ProviderOutcome outcome = executor.execute(List.of(AiProviderKind.OPENAI), PROMPT, "m");

            assertThat(outcome.failures().get(0).text()).isNull();
            assertThat(outcome.failures().get(0).detail()).contains("no response within");
        }
    }

    @Nested
    @DisplayName("what a failed attempt is allowed to persist")
    class PersistenceSafety {

        @Test
        @DisplayName("a provider message carrying a credential is scrubbed before it is persisted")
        void credentialInProviderMessageIsScrubbed() {
            ProviderFailoverExecutor executor = executor(2_000L, 3,
                throwing(AiProviderKind.OPENAI,
                    "401 unauthorized for api_key=sk-live-AAAAAAAAAAAAAAAAAAAAAAAA with Authorization: "
                        + "Bearer aaaabbbbccccdddd"));

            ProviderOutcome outcome = executor.execute(List.of(AiProviderKind.OPENAI), PROMPT, "m");

            String detail = outcome.failures().get(0).detail();
            assertThat(detail)
                .as("this string is written to ai_runs.errorMessage and shown in an operator UI")
                .doesNotContain("sk-live-AAAAAAAAAAAAAAAAAAAAAAAA")
                .doesNotContain("aaaabbbbccccdddd");
        }

        @Test
        @DisplayName("a provider echo of the customer prompt is bounded, not copied")
        void providerEchoOfThePromptIsBounded() {
            String echo = "your prompt was: " + "س ".repeat(2_000);
            ProviderFailoverExecutor executor = executor(2_000L, 3,
                throwing(AiProviderKind.OPENAI, echo));

            ProviderOutcome outcome = executor.execute(List.of(AiProviderKind.OPENAI), PROMPT, "m");

            assertThat(outcome.failures().get(0).detail().length())
                .as("a provider message can contain the request by design; a length cap is what stops "
                    + "ai_runs.errorMessage becoming a copy of it")
                .isLessThanOrEqualTo(SecretScrubber.MAX_LENGTH + 1);
        }

        @Test
        @DisplayName("the attempt's provider is a wire value, never an adapter class name")
        void providerIsAWireValue() {
            ProviderFailoverExecutor executor = executor(2_000L, 3, answering(AiProviderKind.MOCK, "ok"));

            ProviderOutcome outcome = executor.execute(List.of(AiProviderKind.MOCK), PROMPT, "m");

            assertThat(outcome.attemptedProviders()).containsExactly("MOCK");
        }

        @Test
        @DisplayName("an EMPTY completion is a failure, never a success with nothing in it")
        void emptyCompletionIsAFailure() {
            ProviderFailoverExecutor executor = executor(2_000L, 3,
                answering(AiProviderKind.OPENAI, "   "));

            ProviderOutcome outcome = executor.execute(List.of(AiProviderKind.OPENAI), PROMPT, "m");

            // An empty answer is indistinguishable downstream from a working provider that declined, and
            // shipping it would put an empty customer reply on a channel.
            assertThat(outcome.answered()).isFalse();
            assertThat(outcome.failures().get(0).failure()).isEqualTo(ProviderFailure.CONTENT_FILTERED);
        }

        @Test
        @DisplayName("a response with no result at all is a failure, not a NullPointerException")
        void nullResponseIsAFailure() {
            ProviderFailoverExecutor executor = executor(2_000L, 3,
                new StubProvider(AiProviderKind.OPENAI, true, null, null, null, true));

            ProviderOutcome outcome = executor.execute(List.of(AiProviderKind.OPENAI), PROMPT, "m");

            // A provider returning a null ChatResponse is misbehaving. It must be classified and recorded,
            // not propagated into the orchestrator as an unexpected RuntimeException.
            assertThat(outcome.answered()).isFalse();
            assertThat(outcome.failures().get(0).failure()).isEqualTo(ProviderFailure.CONTENT_FILTERED);
            assertThat(outcome.failures().get(0).text()).isNull();
        }

        @Test
        @DisplayName("the token counts a provider reports travel on the winning attempt")
        void usageTravelsOnTheAttempt() {
            ProviderFailoverExecutor executor = executor(2_000L, 3,
                answeringWithUsage(AiProviderKind.MOCK, "ok", 120, 45));

            ProviderOutcome outcome = executor.execute(List.of(AiProviderKind.MOCK), PROMPT, "m");

            ProviderAttempt winner = outcome.winningAttempt();
            assertThat(winner.inputTokens()).isEqualTo(120);
            assertThat(winner.outputTokens()).isEqualTo(45);
        }

        @Test
        @DisplayName("a provider that reports NO usage reads as zero, not as a missing run")
        void missingUsageReadsAsZero() {
            ProviderFailoverExecutor executor = executor(2_000L, 3,
                answeringWithoutUsage(AiProviderKind.MOCK, "ok"));

            ProviderOutcome outcome = executor.execute(List.of(AiProviderKind.MOCK), PROMPT, "m");

            // The real cost comes from the rate card over the token counts, so a missing count degrades
            // attribution and never the charge. What it must not do is throw.
            assertThat(outcome.winningAttempt().inputTokens()).isZero();
            assertThat(outcome.winningAttempt().outputTokens()).isZero();
        }

        @Test
        @DisplayName("a tool call the provider asked for is carried, but its arguments are scrubbed")
        void providerToolCallArgumentsAreScrubbed() {
            ProviderFailoverExecutor executor = executor(2_000L, 3,
                requestingTool(AiProviderKind.MOCK, "conversation.lookup",
                    "{\"conversationId\":\"abc\",\"note\":\"api_key=sk-live-BBBBBBBBBBBBBBBBBBBB\"}"));

            ProviderOutcome outcome = executor.execute(List.of(AiProviderKind.MOCK), PROMPT, "m");

            ProviderAttempt.AssistantToolCall call = outcome.winningAttempt().toolCalls().get(0);
            assertThat(call.name()).isEqualTo("conversation.lookup");
            assertThat(call.arguments()).doesNotContain("sk-live-BBBBBBBBBBBBBBBBBBBB");
        }
    }

    @Nested
    @DisplayName("the registry")
    class Registry {

        @Test
        @DisplayName("two adapters claiming one kind is a startup failure, not a coin toss")
        void duplicateKindIsRejected() {
            assertThatThrownBy(() -> new AiProviderRegistry(
                List.of(answering(AiProviderKind.MOCK, "a"), answering(AiProviderKind.MOCK, "b")), List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MOCK");
        }

        @Test
        @DisplayName("MOCK needs no credential, and that is what makes the no-key path structural")
        void mockNeedsNoCredential() {
            assertThat(AiProviderKind.MOCK.requiresCredential()).isFalse();
            assertThat(AiProviderKind.OPENAI.requiresCredential()).isTrue();
            assertThat(AiProviderKind.ANTHROPIC.requiresCredential()).isTrue();
        }

        @Test
        @DisplayName("the deterministic embedding adapter is chosen because it needs no credential")
        void defaultEmbeddingNeedsNoCredential() {
            AiProviderRegistry registry = new AiProviderRegistry(List.of(),
                List.of(new com.firstelement.airag.provider.DeterministicEmbeddingProvider(null)));

            assertThat(registry.defaultEmbedding()).isPresent();
            // LOCAL, not MOCK: the deterministic adapter is the local embedding model, and the distinction
            // matters because ingestion's fallback must not be a chat provider that happens to be available.
            assertThat(registry.defaultEmbedding().orElseThrow().kind()).isEqualTo(AiProviderKind.LOCAL);
            assertThat(registry.embeddings(AiProviderKind.LOCAL)).isPresent();
        }

        @Test
        @DisplayName("a real adapter declaring itself unavailable does NOT become the default embedding")
        void unavailableEmbeddingIsNotTheDefault() {
            AiProviderRegistry registry = new AiProviderRegistry(List.of(), List.of(
                new StubEmbedding(AiProviderKind.OPENAI, false),
                new StubEmbedding(AiProviderKind.LOCAL, true)));

            assertThat(registry.defaultEmbedding().orElseThrow().kind()).isEqualTo(AiProviderKind.LOCAL);
        }

        @Test
        @DisplayName("a declared-but-unavailable chat adapter is still visible to an operator read model")
        void declaredChatIgnoresAvailability() {
            AiProviderRegistry registry = new AiProviderRegistry(
                List.of(new StubProvider(AiProviderKind.OPENAI, false, null, null, null)), List.of());

            assertThat(registry.chat(AiProviderKind.OPENAI)).isEmpty();
            // "Why did the run not use OpenAI?" is answered by this pair, not by reading configuration.
            assertThat(registry.declaredChat(AiProviderKind.OPENAI)).isPresent();
            assertThat(registry.isKnown(AiProviderKind.OPENAI)).isTrue();
            assertThat(registry.declaredChatKinds()).containsExactly(AiProviderKind.OPENAI);
        }

        @Test
        @DisplayName("the production MOCK adapter is the one the no-key path reaches")
        void productionMockAdapterIsUsable() {
            // Constructed directly rather than injected, so this is a pure check that the shipped adapter
            // answers without a credential. The Spring wiring is proved by MockProviderEndToEndIntegrationTest
            // and by ApiApplicationSmokeTest in the assembled application.
            AiChatProvider mock = new MockAiChatProvider(new AiRagProperties());
            ChatResponse response = mock.call(PROMPT);

            assertThat(mock.available()).isTrue();
            assertThat(response.getResult().getOutput().getText()).isNotBlank();
        }

        @Test
        @DisplayName("describe() is safe to persist: a wire value, never a URL and never a key")
        void describeIsSafeToPersist() {
            // Asserted here rather than in ApiApplicationSmokeTest because AiChatProvider extends Spring
            // AI's ChatModel, which this module holds at `implementation` scope -- calling a method on an
            // adapter from app-bootstrap would mean widening that scope to satisfy a test. describe() is the
            // one adapter-supplied string that reaches a log line and an operator read model.
            AiChatProvider mock = new MockAiChatProvider(new AiRagProperties());

            assertThat(mock.describe())
                .contains(AiProviderKind.MOCK.wireValue())
                .doesNotContain("http")
                .doesNotContain("?")
                .doesNotContain("key")
                .doesNotContain("token");
        }
    }

    // ------------------------------------------------------------------
    // harness
    // ------------------------------------------------------------------

    private static AiRagProperties properties(long timeoutMillis, int maxAttempts) {
        AiRagProperties properties = new AiRagProperties();
        properties.setProviderTimeoutMillis(timeoutMillis);
        properties.setMaxProviderAttempts(maxAttempts);
        return properties;
    }

    private static ProviderFailoverExecutor executor(long timeoutMillis, int maxAttempts,
                                                     AiChatProvider... providers) {
        return new ProviderFailoverExecutor(new AiProviderRegistry(List.of(providers), List.of()),
            properties(timeoutMillis, maxAttempts));
    }

    /** A stub whose behaviour is one of: answer with text, throw, sleep, hang, or return null. */
    private static class StubProvider implements AiChatProvider {

        private final AiProviderKind kind;
        private final boolean available;
        private final String answer;
        private final RuntimeException failure;
        private final Sleeper sleeper;
        private final boolean returnsNull;
        /** Package-private, not private: the overriding subclasses below must increment the same counter. */
        final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();

        @FunctionalInterface
        interface Sleeper {
            String await();
        }

        StubProvider(AiProviderKind kind, boolean available, String answer, RuntimeException failure,
                     Sleeper sleeper) {
            this(kind, available, answer, failure, sleeper, false);
        }

        StubProvider(AiProviderKind kind, boolean available, String answer, RuntimeException failure,
                     Sleeper sleeper, boolean returnsNull) {
            this.kind = kind;
            this.available = available;
            this.answer = answer;
            this.failure = failure;
            this.sleeper = sleeper;
            this.returnsNull = returnsNull;
        }

        @Override
        public AiProviderKind kind() {
            return kind;
        }

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            calls.incrementAndGet();
            if (returnsNull) {
                return null;
            }
            if (failure != null) {
                throw failure;
            }
            String text = sleeper != null ? sleeper.await() : answer;
            return response(text, null);
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return ChatOptions.builder().model("m").build();
        }

        int calls() {
            return calls.get();
        }

        /** A response with the given text and optional usage, built the way an adapter would build one. */
        static ChatResponse response(String text, org.springframework.ai.chat.metadata.Usage usage) {
            return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(text).build())),
                org.springframework.ai.chat.metadata.ChatResponseMetadata.builder().model("m")
                    .usage(usage).build());
        }
    }

    private static final class StubEmbedding implements AiEmbeddingProvider {

        private final AiProviderKind kind;
        private final boolean available;

        StubEmbedding(AiProviderKind kind, boolean available) {
            this.kind = kind;
            this.available = available;
        }

        @Override
        public AiProviderKind kind() {
            return kind;
        }

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public float[] embed(String text) {
            return new float[com.firstelement.airag.provider.DeterministicEmbeddingProvider.DIMENSIONS];
        }

        @Override
        public float[] embed(org.springframework.ai.document.Document document) {
            return embed(document.getText());
        }

        @Override
        public int dimensions() {
            return com.firstelement.airag.provider.DeterministicEmbeddingProvider.DIMENSIONS;
        }

        @Override
        public org.springframework.ai.embedding.EmbeddingResponse call(
            org.springframework.ai.embedding.EmbeddingRequest request) {
            // Unreachable in this suite: nothing calls an embedding adapter except ingestion, which is
            // covered by the container suites. Present so the stub satisfies the interface honestly rather
            // than by being made abstract away from.
            throw new UnsupportedOperationException("not exercised by this suite");
        }
    }

    private static StubProvider answering(AiProviderKind kind, String text) {
        return new StubProvider(kind, true, text, null, null);
    }

    private static StubProvider throwing(AiProviderKind kind, String message) {
        return new StubProvider(kind, true, null, new IllegalStateException(message), null);
    }

    private static StubProvider sleeping(AiProviderKind kind, long millis) {
        return new StubProvider(kind, true, null, null, () -> {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "late";
        });
    }

    private static StubProvider answeringWithUsage(AiProviderKind kind, String text, int in, int out) {
        return new StubProvider(kind, true, text, null, null) {
            @Override
            public ChatResponse call(Prompt prompt) {
                calls.incrementAndGet();
                return response(text, new Usage(in, out));
            }
        };
    }

    private static StubProvider answeringWithoutUsage(AiProviderKind kind, String text) {
        return new StubProvider(kind, true, text, null, null) {
            @Override
            public ChatResponse call(Prompt prompt) {
                calls.incrementAndGet();
                return response(text, null);
            }
        };
    }

    private static StubProvider requestingTool(AiProviderKind kind, String tool, String arguments) {
        return new StubProvider(kind, true, "ok", null, null) {
            @Override
            public ChatResponse call(Prompt prompt) {
                calls.incrementAndGet();
                return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("ok")
                    .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", tool, arguments)))
                    .build())),
                    org.springframework.ai.chat.metadata.ChatResponseMetadata.builder().model("m").build());
            }
        };
    }

    private record Usage(Integer prompt, Integer completion)
            implements org.springframework.ai.chat.metadata.Usage {

        @Override
        public Integer getPromptTokens() {
            return prompt;
        }

        @Override
        public Integer getCompletionTokens() {
            return completion;
        }

        @Override
        public Object getNativeUsage() {
            return null;
        }
    }
}
