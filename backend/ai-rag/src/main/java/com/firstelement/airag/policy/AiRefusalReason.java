package com.firstelement.airag.policy;

/**
 * Every way a FirstElement AI run may be refused, as a closed list.
 *
 * <h2>Why the reasons are named and closed</h2>
 *
 * <p>A refusal that says "no" is not actionable. A refusal that says {@code BUDGET_EXHAUSTED} is a
 * finance question, {@code MODEL_NOT_ALLOWED} is an administrator question, and {@code RESIDENCY_VIOLATION}
 * is a compliance question — and they need different people and different fixes. So this is an enum, the
 * value is written to {@code ai_run_steps."status"} / {@code ai_provenance_records."disposition"} and is
 * what the operator UI groups by.
 *
 * <h2>What a reason may carry</h2>
 *
 * <p>Only numbers and identifiers the caller already supplied: which provider was attempted, how many
 * tokens were estimated, which tool was refused. Never customer content, never a provider's echo of the
 * prompt, never a credential. {@link #detail} exists so a refusal is diagnosable, and its contract is that
 * it is <b>scrubbed and bounded</b> by
 * {@link com.firstelement.airag.provider.SecretScrubber#scrubAndBound(String)} before it is persisted.
 */
public enum AiRefusalReason {

    /** {@code firstelement.ai.enabled = false}. Nothing reaches a provider. */
    AI_DISABLED,

    /** No tenant policy row exists for this project, or the row exists with {@code isEnabled = false}. */
    POLICY_MISSING,

    /**
     * A policy row exists with no APPROVED version.
     *
     * <p>Distinct from {@link #POLICY_MISSING}: the tenant has expressed intent but nobody approved it.
     * "Not configured" and "configured but unapproved" are different conversations with an administrator.
     */
    POLICY_VERSION_NOT_APPROVED,

    /** The agent named a provider kind this build does not serve, and the chain is empty. */
    NO_PROVIDER_CHAIN,

    /** The deployment ceiling on prompt tokens would be exceeded by the request. */
    PROMPT_TOO_LARGE,

    /** The tenant's {@code maxTokensPerRun} would be exceeded by the request. */
    POLICY_TOKEN_LIMIT,

    /** A HARD budget's {@code limitAmount} is already reached for the period. */
    BUDGET_EXHAUSTED,

    /** A SOFT budget is past its ceiling and enforcement is HARD. Reported before the call, never after. */
    BUDGET_HARD_STOP,

    /** The run's wall-clock ceiling would be exceeded. */
    RUN_DEADLINE,

    /** The per-tenant run rate limit is reached. */
    RATE_LIMITED,

    /** The requested tool-call count exceeds the effective {@code maxToolCallsPerRun}. */
    TOOL_CALL_LIMIT,

    /** The model is not in {@code ai_model_registry} for this tenant, or its row is disabled. */
    MODEL_NOT_ALLOWED,

    /** The registered model's provider does not match the chain's first hop. */
    MODEL_PROVIDER_MISMATCH,

    /** The model the deployment declared cannot carry customer content (residency zone mismatch). */
    RESIDENCY_VIOLATION,

    /** Retrieval returned nothing above the threshold, and the agent version forbids an ungrounded answer. */
    RETRIEVAL_EMPTY,

    /** Retrieval returned chunks but the agent version's minimum score excluded all of them. */
    BELOW_MIN_SCORE,

    /** The model requested a tool that is not registered for this tenant. */
    UNKNOWN_TOOL,

    /** The model requested a tool that is registered and disabled. */
    TOOL_DISABLED,

    /** The model asked for a project the principal is not scoped to. */
    TOOL_PROJECT_OUT_OF_SCOPE,

    /** The tool arguments tried to set tenant or project scope. */
    SCOPE_OVERRIDE_ATTEMPT,

    /** The tool arguments contained a name the tool's schema does not declare. */
    ARGUMENT_NOT_ALLOWED,

    /** The principal does not hold the tool's required capability. */
    CAPABILITY_MISSING,

    /** The tool's risk class requires human approval and none was given. */
    HUMAN_APPROVAL_REQUIRED,

    /** Redaction of the prompt or the response failed. A failure, never a silent pass. */
    REDACTION_FAILED,

    /** The conversation is not in AI mode. The assistant may draft; it may not send. */
    NOT_AI_MODE,

    /** A human takeover aborted this run. */
    ABORTED_BY_HUMAN,

    /** Every provider in the chain failed. {@link #detail} carries the per-attempt reasons. */
    PROVIDER_UNAVAILABLE,

    /** The provider answered with nothing. Refused rather than shipped as an empty reply. */
    EMPTY_COMPLETION;

    /**
     * Whether a refusal of this kind is expected operational behaviour rather than a defect.
     *
     * <p>Used only by the alert threshold, never by the refusal itself: a budget that stops working
     * because it is out of money is correct, and paging for it every time is how alerts get ignored.
     */
    public boolean expected() {
        return this == BUDGET_EXHAUSTED || this == BUDGET_HARD_STOP || this == RATE_LIMITED
            || this == PROMPT_TOO_LARGE || this == POLICY_TOKEN_LIMIT || this == RETRIEVAL_EMPTY
            || this == BELOW_MIN_SCORE || this == NOT_AI_MODE || this == ABORTED_BY_HUMAN
            || this == PROVIDER_UNAVAILABLE || this == AI_DISABLED || this == POLICY_MISSING;
    }
}