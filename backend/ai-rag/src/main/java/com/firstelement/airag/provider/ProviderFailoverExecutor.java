package com.firstelement.airag.provider;

import com.firstelement.airag.config.AiRagProperties;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

/**
 * Runs the provider chain, with a per-attempt timeout and a bounded hop count.
 *
 * <h2>A provider outage must fail with a reason, not a hang</h2>
 *
 * <p>That is the whole reason this class exists. A naive call is a hang whenever the provider accepts a
 * connection and then stops reading, and a hang in a worker means a leased job, a stuck consumer and a
 * conversation that never gets an answer. So each attempt is submitted to a small bounded pool and waited
 * on with {@code Future.get(timeout)}; on expiry the future is cancelled and the attempt is recorded as
 * {@link ProviderFailure#TIMEOUT}.
 *
 * <h2>Why the pool is per-process and tiny</h2>
 *
 * <p>Because a provider call is I/O, and the number that matters is "how many are in flight at once",
 * not "how many threads exist". Four slots, named, daemon, and never allowed to grow: an unbounded pool
 * would turn a slow provider into memory pressure, which is a worse outage than the one being handled.
 *
 * <h2>Why a provider that is not configured is skipped without a call</h2>
 *
 * <p>It costs nothing and it records the truth. A chain of {@code [OPENAI, ANTHROPIC, MOCK]} with no
 * keys tries MOCK once; a chain that "attempted" two unconfigured providers would put two
 * {@code NOT_CONFIGURED} rows on the timeline for a single request and imply work that never happened.
 *
 * <h2>Classification, and why UNKNOWN stops the chain</h2>
 *
 * <p>An exception this build cannot classify becomes {@link ProviderFailure#UNKNOWN}, which is not
 * retryable on the next provider. Continuing would spend the run's budget and its remaining hops on a
 * failure that will recur identically, and the "provider is degraded" signal an operator watches would be
 * diluted. {@link ProviderFailure#retryableOnNextProvider()} is the single place that decision is made.
 */
@Component
public class ProviderFailoverExecutor {

    private static final Logger log = LoggerFactory.getLogger(ProviderFailoverExecutor.class);

    private final AiProviderRegistry registry;
    private final AiRagProperties properties;
    private final ExecutorService pool;

    public ProviderFailoverExecutor(AiProviderRegistry registry, AiRagProperties properties) {
        this.registry = registry;
        this.properties = properties;
        this.pool = Executors.newFixedThreadPool(4, new NamedThreadFactory());
    }

    /**
     * Walks the chain.
     *
     * @param chain the ordered provider kinds; the first is the agent's own provider. Empty means the
     *              agent named none, which is a refusal rather than a default — the default lives in the
     *              agent version, and silently substituting one here would make the model that produced
     *              an answer differ from the model the tenant registered.
     * @param prompt the already-redacted prompt
     * @param model  the model name to ask for
     * @return an outcome that is either answered with the winning attempt, or refused with the reasons
     */
    public ProviderOutcome execute(List<AiProviderKind> chain, Prompt prompt, String model) {
        List<ProviderAttempt> attempts = new ArrayList<>();
        if (chain == null || chain.isEmpty()) {
            return ProviderOutcome.refused("NO_PROVIDER_CHAIN", attempts);
        }

        // Duplicate kinds are removed rather than retried: a chain that names OPENAI twice is a
        // configuration mistake, and attempting it twice would double both the latency and the budget
        // for one logical provider.
        Set<AiProviderKind> seen = new LinkedHashSet<>();
        int budget = Math.max(1, properties.getMaxProviderAttempts());
        for (AiProviderKind kind : chain) {
            if (kind == null || !seen.add(kind)) {
                continue;
            }
            if (attempts.size() >= budget) {
                log.info("provider chain exhausted after {} attempts budget={} chainSize={}", attempts.size(),
                    budget, chain.size());
                break;
            }
            java.util.Optional<AiChatProvider> adapter = registry.chat(kind);
            if (adapter.isEmpty()) {
                attempts.add(ProviderAttempt.failed(kind.wireValue(), model,
                    ProviderFailure.NOT_CONFIGURED, null, 0L));
                continue;
            }
            ProviderAttempt attempt = attempt(adapter.get(), prompt, model);
            attempts.add(attempt);
            if (attempt.succeeded()) {
                return ProviderOutcome.answered(attempt, attempts);
            }
            if (!attempt.failure().retryableOnNextProvider()) {
                break;
            }
        }
        return ProviderOutcome.refused(lastFailure(attempts), attempts);
    }

    /** One bounded attempt, classified. */
    private ProviderAttempt attempt(AiChatProvider provider, Prompt prompt, String model) {
        long started = System.nanoTime();
        Future<ChatResponse> future;
        try {
            future = pool.submit(() -> provider.call(prompt));
        } catch (RuntimeException e) {
            // Rejected: the pool is saturated, which is this module being under pressure rather than the
            // provider being down. Classified as UNAVAILABLE because it is retryable on the next hop.
            return ProviderAttempt.failed(provider.kind().wireValue(), model, ProviderFailure.UNAVAILABLE,
                e.getClass().getSimpleName(), elapsedMillis(started));
        }
        try {
            ChatResponse response = future.get(properties.getProviderTimeoutMillis(), TimeUnit.MILLISECONDS);
            return read(provider, model, response, started);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("provider attempt timed out provider={} model={} timeoutMs={}",
                provider.kind().wireValue(), model, properties.getProviderTimeoutMillis());
            return ProviderAttempt.failed(provider.kind().wireValue(), model, ProviderFailure.TIMEOUT,
                "no response within " + properties.getProviderTimeoutMillis() + "ms", elapsedMillis(started));
        } catch (InterruptedException e) {
            future.cancel(true);
            // Preserve the interrupt: a worker being shut down must not swallow it and keep claiming jobs.
            Thread.currentThread().interrupt();
            return ProviderAttempt.failed(provider.kind().wireValue(), model, ProviderFailure.DEADLINE_EXCEEDED,
                "attempt interrupted", elapsedMillis(started));
        } catch (Exception e) {
            return ProviderAttempt.failed(provider.kind().wireValue(), model, classify(e), e.getMessage(),
                elapsedMillis(started));
        }
    }

    /**
     * Turns a provider response into an attempt.
     *
     * <p>An <b>empty</b> answer is a failure, not a success. This is the rule that stops "the provider
     * failed" from becoming "the model had nothing to say": an empty completion is indistinguishable
     * downstream from a working provider that declined, and shipping it would put an empty customer reply
     * on a channel.
     *
     * <p>The text travels on the attempt and nowhere else. It is the only place in this package where
     * unredacted model output exists, and the orchestrator's next move is to hand it to the redaction
     * engine. It is never written to {@code ai_run_steps}, never logged, and never persisted — which is
     * why {@link ProviderAttempt} is an in-memory value and the trace's {@code detail} is a separate
     * declared type.
     */
    private ProviderAttempt read(AiChatProvider provider, String model, ChatResponse response, long started) {
        AssistantMessage output = output(response);
        String text = output == null ? null : output.getText();
        if (text == null || text.isBlank()) {
            return ProviderAttempt.failed(provider.kind().wireValue(), model,
                ProviderFailure.CONTENT_FILTERED, "provider returned no text", elapsedMillis(started));
        }
        List<ProviderAttempt.AssistantToolCall> requested = new ArrayList<>();
        if (output.hasToolCalls() && output.getToolCalls() != null) {
            for (AssistantMessage.ToolCall call : output.getToolCalls()) {
                // A provider's echo of a prompt into an argument string is the shape of a prompt-injection
                // payload arriving as structured data. Scrubbed and bounded here, and re-validated by the
                // executor regardless, so nothing downstream can treat it as a value.
                requested.add(new ProviderAttempt.AssistantToolCall(call.name(),
                    SecretScrubber.scrubAndBound(call.arguments()), call.id()));
            }
        }
        return ProviderAttempt.success(provider.kind().wireValue(), model, elapsedMillis(started),
            usageOf(response, true), usageOf(response, false), text, requested);
    }

    /**
     * Maps an exception onto the closed failure vocabulary.
     *
     * <p>Message-shape matching, deliberately, and with the reason recorded: an adapter's exception type is
     * not part of any interface we depend on, so the only portable signal is the status text every HTTP
     * client produces. Anything unrecognised is {@link ProviderFailure#UNKNOWN} rather than
     * {@code UNAVAILABLE}, because the two demand different operator responses and guessing "unavailable"
     * would page someone for a request the provider will never accept.
     */
    static ProviderFailure classify(Throwable error) {
        if (error == null) {
            return ProviderFailure.UNKNOWN;
        }
        StringBuilder text = new StringBuilder();
        for (Throwable cursor = error; cursor != null && cursor != cursor.getCause(); cursor = cursor.getCause()) {
            text.append(cursor.getClass().getSimpleName()).append(' ')
                .append(cursor.getMessage() == null ? "" : cursor.getMessage()).append(' ');
        }
        String haystack = text.toString().toLowerCase(java.util.Locale.ROOT);
        if (haystack.contains("timeout") || haystack.contains("timed out")) {
            return ProviderFailure.TIMEOUT;
        }
        if (haystack.contains("429") || haystack.contains("rate limit") || haystack.contains("quota")) {
            return ProviderFailure.RATE_LIMITED;
        }
        if (haystack.contains("401") || haystack.contains("403") || haystack.contains("unauthorized")
            || haystack.contains("forbidden") || haystack.contains("api key")) {
            // A credential problem is not a transient outage. Retrying it on the next provider with the
            // same missing key wastes the run's hops, so it is classified as NOT_CONFIGURED, which the
            // chain then skips cheaply.
            return ProviderFailure.NOT_CONFIGURED;
        }
        if (haystack.contains("content") && haystack.contains("filter")) {
            return ProviderFailure.CONTENT_FILTERED;
        }
        if (haystack.contains("400") || haystack.contains("422") || haystack.contains("bad request")
            || haystack.contains("invalid")) {
            return ProviderFailure.INVALID_REQUEST;
        }
        if (haystack.contains("500") || haystack.contains("502") || haystack.contains("503")
            || haystack.contains("connect") || haystack.contains("socket") || haystack.contains("io")) {
            return ProviderFailure.UNAVAILABLE;
        }
        return ProviderFailure.UNKNOWN;
    }

    private static String lastFailure(List<ProviderAttempt> attempts) {
        for (int i = attempts.size() - 1; i >= 0; i--) {
            if (attempts.get(i).failure() != null) {
                return attempts.get(i).failure().name();
            }
        }
        return ProviderFailure.UNKNOWN.name();
    }

    private static long elapsedMillis(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private static AssistantMessage output(ChatResponse response) {
        if (response == null || response.getResult() == null) {
            return null;
        }
        return response.getResult().getOutput();
    }

    /**
     * The provider's reported usage.
     *
     * <p>Read from the typed {@code Usage} rather than from a string-keyed metadata map, because a provider
     * that reports usage in a shape this build does not understand must show as <b>zero</b> rather than as a
     * run whose cost cannot be reconciled. The run's real cost is computed from the rate card over the
     * token counts, so a missing count here degrades the attribution and never the charge.
     */
    private static int usageOf(ChatResponse response, boolean prompt) {
        try {
            org.springframework.ai.chat.metadata.Usage usage = response.getMetadata().getUsage();
            if (usage == null) {
                return 0;
            }
            Integer value = prompt ? usage.getPromptTokens() : usage.getCompletionTokens();
            return value == null ? 0 : Math.max(0, value);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** Named daemon threads, so a stuck provider is identifiable in a thread dump. */
    private static final class NamedThreadFactory implements ThreadFactory {

        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "ai-provider-attempt-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}