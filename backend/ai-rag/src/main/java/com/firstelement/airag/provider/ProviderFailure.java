package com.firstelement.airag.provider;

/**
 * Why a provider attempt did not produce an answer.
 *
 * <h2>Why this is a closed vocabulary</h2>
 *
 * <p>Every value here is persisted, appears in {@code ai_run_steps.detail}, and is shown in an operator
 * read model. An open string would make "the provider failed" indistinguishable from "we wrote a typo",
 * and the difference is exactly what an on-call engineer needs at 03:00. So the reasons are named, and
 * {@link #detailSafe(String)} is the only way any free text joins one.
 *
 * <p>Note what is <b>not</b> a value: the provider's own message. A provider error is untrusted input
 * that routinely embeds the request — including the authorization header that authenticated the call.
 * It is scrubbed by {@link SecretScrubber} and truncated before it can reach a log line, an audit
 * metadata field or {@code ai_runs."errorMessage"}.
 */
public enum ProviderFailure {

    /** No adapter is registered for this provider kind: no key, or the adapter is not in this build. */
    NOT_CONFIGURED,

    /** The attempt exceeded {@code firstelement.ai.provider-timeout-millis} and was abandoned. */
    TIMEOUT,

    /** The provider answered with a 5xx or the connection failed. */
    UNAVAILABLE,

    /** The provider answered 429, or its quota is exhausted. */
    RATE_LIMITED,

    /**
     * The request itself was refused: a bad model name, a context window exceeded, a malformed tool
     * declaration. Retrying it on the next provider in the chain would fail identically, so the failover
     * executor stops here unless the policy says otherwise.
     */
    INVALID_REQUEST,

    /** The provider's own safety filter fired. Never retried: the content is the problem. */
    CONTENT_FILTERED,

    /** The run's wall-clock ceiling expired before or during generation. */
    DEADLINE_EXCEEDED,

    /**
     * The provider raised something this build cannot classify.
     *
     * <p>Exists so an unclassified error is still <em>recorded</em> rather than being allowed to read as
     * success. The distinction from {@link #UNAVAILABLE} matters operationally: an unknown failure is
     * not something the failover chain should keep walking, because walking it costs the run its budget.
     */
    UNKNOWN;

    /** Whether the failover chain should try the next provider after this failure. */
    public boolean retryableOnNextProvider() {
        return this == TIMEOUT || this == UNAVAILABLE || this == RATE_LIMITED || this == NOT_CONFIGURED;
    }

    /**
     * The free-text detail a failure may carry, made safe to persist and to log.
     *
     * <p>Scrubbed, control characters removed, whitespace collapsed and truncated. The truncation is the
     * load-bearing part: a provider message can be arbitrarily long and can contain a customer prompt by
     * design ("your prompt was: ..."), so a length cap is what stops a persisted error message from
     * becoming a copy of the request.
     */
    public String detailSafe(String raw) {
        return SecretScrubber.scrubAndBound(raw);
    }
}