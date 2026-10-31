package com.firstelement.airag.orchestration;

import java.util.List;
import java.util.UUID;

/**
 * What one run produced.
 *
 * <h2>The answer is nullable and that is the point</h2>
 *
 * <p>A refused run returns {@code answer = null} and a {@link #refusalCode()}. A caller that wanted
 * "something to show the customer" therefore has to notice the refusal, because there is no string to
 * accidentally send. This is the shape that stops "the provider failed, ship the empty string" from being
 * a one-line mistake.
 *
 * <p>{@link #citations()} is never null and never empty on an {@link AiRunDisposition#ANSWERED} result that
 * had retrievals — FR-5.8 requires the retrieved source ids, and acceptance gate 5 requires the exact
 * agent/prompt/retrieval versions. {@link #promptVersion()} is a content hash of the framed system
 * instructions, so "which prompt produced this answer" is answerable months later without storing the
 * prompt.
 *
 * @param runId             the {@code ai_runs} row, the join key for the whole trace
 * @param disposition       what the run did
 * @param answer            the redacted answer text, or {@code null} on a refusal
 * @param refusalCode       an {@code AiRefusalReason} name, or {@code null}
 * @param citations         document title and score for each chunk used
 * @param retrievalIds      the {@code ai_run_retrievals} row ids, in rank order
 * @param consideredChunkIds every chunk the search considered. Recorded because an empty result and a broken
 *                           predicate are otherwise indistinguishable
 * @param toolResults       one entry per tool the model asked for, including refusals
 * @param promptVersion     the content hash of the framed instructions
 * @param providerAttempted the provider kinds tried, in order
 * @param costAmount        the rate-card cost, or {@code null} when unpriced
 * @param inputTokens       the provider's reported input tokens
 * @param outputTokens      the provider's reported output tokens
 * @param redactionCount    how many values were redacted across the prompt and the response
 * @param injectionSignals  detected instruction-shaped text in retrieved content. Telemetry
 */
public record AiRunResult(UUID runId,
                          AiRunDisposition disposition,
                          String answer,
                          String refusalCode,
                          List<Citation> citations,
                          List<UUID> retrievalIds,
                          List<UUID> consideredChunkIds,
                          List<ToolOutcome> toolResults,
                          String promptVersion,
                          List<String> providerAttempted,
                          java.math.BigDecimal costAmount,
                          String costCurrency,
                          Integer inputTokens,
                          Integer outputTokens,
                          int redactionCount,
                          List<UntrustedContentAdvisor.InjectionSignal> injectionSignals) {

    public AiRunResult {
        citations = citations == null ? List.of() : List.copyOf(citations);
        retrievalIds = retrievalIds == null ? List.of() : List.copyOf(retrievalIds);
        consideredChunkIds = consideredChunkIds == null ? List.of() : List.copyOf(consideredChunkIds);
        toolResults = toolResults == null ? List.of() : List.copyOf(toolResults);
        providerAttempted = providerAttempted == null ? List.of() : List.copyOf(providerAttempted);
        injectionSignals = injectionSignals == null ? List.of() : List.copyOf(injectionSignals);
    }

    /** Whether the run was stopped by policy. */
    public boolean refused() {
        return disposition == AiRunDisposition.REFUSED;
    }

    /** Whether there is text a caller could show a customer. */
    public boolean hasAnswer() {
        return answer != null && !answer.isBlank();
    }

    /**
     * One cited source.
     *
     * <p>Title and score only — never the snippet. A citation that carried its content would defeat both
     * its purpose and the redaction applied to the snippet.
     */
    public record Citation(String documentTitle, double score) {
    }

    /**
     * What one tool request produced.
     *
     * @param decision ALLOW / REQUIRE_APPROVAL / DENY
     * @param approvalId the approval row, when one was created or relied on
     */
    public record ToolOutcome(String toolName, String decision, UUID approvalId, String refusalCode) {
    }
}