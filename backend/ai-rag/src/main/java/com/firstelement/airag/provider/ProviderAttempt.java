package com.firstelement.airag.provider;

/**
 * One attempt against one provider.
 *
 * <p>An attempt exists whether it succeeded or not, because "the chain was tried and all four hops were
 * unavailable" is a materially different fact from "no chain was configured" and an operator needs to tell
 * them apart.
 *
 * @param provider     the {@link AiProviderKind} wire value, never an adapter class name
 * @param model        the model asked for, or {@code null}
 * @param failure      {@code null} on success; otherwise why this attempt produced nothing
 * @param detail       the provider's own message, already scrubbed and bounded by
 *                     {@link ProviderFailure#detailSafe(String)}. {@code null} when there was none
 * @param latencyMillis wall-clock time spent on this attempt only
 * @param inputTokens  tokens reported by the provider, or {@code 0} when it reported none
 * @param outputTokens tokens reported by the provider, or {@code 0} when it reported none
 * @param text         the answer text, on success. <b>Never persisted and never logged</b>: it is the
 *                     model's words about a customer's document, and the run's step detail is a declared
 *                     type built from ids, counts and reason codes. It is carried here only so the
 *                     orchestrator can hand it to the redaction engine, which is the one component allowed
 *                     to see unredacted text on this path
 * @param toolCalls    the tools the provider asked for, on success. Each one is a request, never an
 *                     action, and each is re-authorized by the executor
 */
public record ProviderAttempt(String provider,
                              String model,
                              ProviderFailure failure,
                              String detail,
                              long latencyMillis,
                              int inputTokens,
                              int outputTokens,
                              String text,
                              java.util.List<AssistantToolCall> toolCalls) {

    public ProviderAttempt {
        provider = provider == null ? "UNKNOWN" : provider;
        if (detail != null) {
            // Defence in depth: the caller is expected to have scrubbed, and a value that reaches this
            // constructor from anywhere else is scrubbed here rather than trusted. A constructor that
            // sanitises its own input is the last place a secret can still be stopped.
            detail = SecretScrubber.scrubAndBound(detail);
        }
        latencyMillis = Math.max(0L, latencyMillis);
        inputTokens = Math.max(0, inputTokens);
        outputTokens = Math.max(0, outputTokens);
        toolCalls = toolCalls == null ? java.util.List.of() : java.util.List.copyOf(toolCalls);
    }

    /**
     * A successful attempt.
     *
     * <p>{@code text} is stored verbatim here and never leaves this record except into the redaction engine.
     * Callers that build a trace row must project the other fields by name, which is why
     * {@code ai_run_steps."detail"} is a declared type.
     */
    public static ProviderAttempt success(String provider, String model, long latencyMillis,
                                          int inputTokens, int outputTokens, String text,
                                          java.util.List<AssistantToolCall> toolCalls) {
        return new ProviderAttempt(provider, model, null, null, latencyMillis, inputTokens, outputTokens,
            text, toolCalls);
    }

    /** A failed attempt. No text, because there was none. */
    public static ProviderAttempt failed(String provider, String model, ProviderFailure failure,
                                         String rawDetail, long latencyMillis) {
        return new ProviderAttempt(provider, model, failure, rawDetail, latencyMillis, 0, 0, null,
            java.util.List.of());
    }

    public boolean succeeded() {
        return failure == null;
    }

    /**
     * A tool the provider asked for.
     *
     * <p>A name, a JSON argument string and a provider-local call id. Nothing here is trusted: an unknown
     * name is a denial, and the argument string is parsed and re-validated rather than merged into
     * anything.
     */
    public record AssistantToolCall(String name, String arguments, String providerCallId) {
    }
}