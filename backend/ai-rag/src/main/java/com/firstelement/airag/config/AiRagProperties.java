package com.firstelement.airag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The FirstElement AI runtime knobs, under {@code firstelement.ai}.
 *
 * <h2>What is deliberately NOT a property</h2>
 *
 * <p>There is no {@code apiKey}, {@code token} or {@code endpoint} property, and there is no map of
 * per-provider secrets. Two reasons, both of which are policy rather than tidiness:
 *
 * <ul>
 *   <li><b>ADR 0007 Part 2 rule 3</b> â€” "with no provider key the CRM stays fully usable". A key that
 *       can be configured is a key someone will put in {@code application.yml}, and this migration does
 *       not read a credential from a file in the repository.</li>
 *   <li><b>The registry is a registry.</b> {@code V20261005100000} deliberately gives
 *       {@code ai_model_registry} no credential column at all, and its post-condition guard fails the
 *       migration if one appears. A properties class is the same mistake in a different file.</li>
 * </ul>
 *
 * <p>A real provider adapter arrives in a later phase as a bean that is <em>conditional on a credential
 * supplied by the deployment</em>. Until then, {@link #providers} is empty and every provider kind
 * except {@code MOCK} resolves to a {@code NOT_CONFIGURED} refusal, which is a refusal with a
 * recorded reason, not an exception at boot.
 */
@ConfigurationProperties(prefix = "firstelement.ai")
public class AiRagProperties {

    /**
     * Whether AI runs may be started at all.
     *
     * <p>{@code false} is the value a deployment that wants the CRM with no AI surface should keep, and
     * it is the state that makes the "no provider key, CRM fully usable" claim structural rather than
     * incidental: the orchestrator refuses every request with {@code AI_DISABLED} and nothing reaches a
     * provider.
     */
    private boolean enabled = true;

    /**
     * The provider kind used when an agent version names none, and the first hop of the default chain.
     *
     * <p>{@code MOCK} by default, because a deployment that has configured nothing still runs the whole
     * CRM â€” FR-5.2. A provider outage therefore degrades to the mock chain rather than to a failure.
     */
    private String defaultProvider = "MOCK";

    /**
     * Per-attempt provider timeout, in milliseconds.
     *
     * <p>The rule "a provider outage must fail with a recorded reason, not a hang" is this number. It is
     * enforced twice: the executor bounds each attempt, and the request it makes carries its own
     * deadline, so a provider that ignores the deadline still cannot hold the run open.
     */
    private long providerTimeoutMillis = 5_000L;

    /**
     * The whole-run wall-clock ceiling, in milliseconds, checked before the provider is called.
     *
     * <p>Distinct from {@link #providerTimeoutMillis}: this is the budget's <em>time</em> dimension,
     * and it accounts for retrieval and tool time as well as generation. A run that would exceed it is
     * refused rather than started and abandoned later.
     */
    private long runWallClockMillis = 20_000L;

    /** How many providers one run may try before the chain is declared exhausted. */
    private int maxProviderAttempts = 3;

    /**
     * The residency zone this deployment is allowed to send customer content to.
     *
     * <p>Compared against the provider zone carried by {@code ai_model_registry.dataRetentionPolicy}.
     * A mismatch is a {@code RESIDENCY_VIOLATION} â€” a refusal, not a warning, because
     * a clinic that was told its data stays in-region cannot be told afterwards that it did not.
     */
    private String dataResidencyZone = "LOCAL_ONLY";

    /**
     * The maximum tokens one run may consume, applied before the call.
     *
     * <p>An upper bound on the <em>request</em>, so it is enforced against the estimate rather than
     * after the fact: a run whose estimated prompt already exceeds it is refused without a provider call,
     * which is the only point at which refusing is still free.
     */
    private int maxPromptTokensPerRun = 8_000;

    /**
     * Tool calls one run may make, enforced before each one.
     *
     * <p>Separate from {@code ai_policies.maxToolCallsPerRun}: this is the deployment's hard ceiling and
     * the policy's is the tenant's, and the effective limit is the lower of the two. A tenant cannot
     * raise a deployment's ceiling, and a deployment ceiling of zero means no tool runs at all.
     */
    private int maxToolCallsPerRun = 4;

    /**
     * Runs one tenant may start per minute, across all conversations.
     *
     * <p>The rate limit that protects the provider and the budget from a runaway conversation. Counted
     * in the orchestrator, keyed by organization, and deliberately coarse: it exists to stop a loop, not
     * to be a quota system (that is what {@code ai_budgets} is).
     */
    private int runsPerMinutePerTenant = 60;

    /**
     * Retrieval minimum similarity, applied <em>after</em> the tenant predicate and never instead of it.
     *
     * <p>FR-5.7: "pgvector is a retrieval index, not an authorization layer." This value can only make a
     * result set smaller; no configuration of it can widen one.
     */
    private double minRetrievalScore = 0.0d;

    /** Retrieved chunks per run. Bounded because every chunk is customer content that enters a prompt. */
    private int retrievalLimit = 5;

    /**
     * The chunk size used by asynchronous ingestion, in characters.
     *
     * <p>Arabic-aware: the splitter counts characters, not bytes and not whitespace words, because a
     * whitespace splitter on Arabic breaks inside a word.
     */
    private int ingestionChunkChars = 900;

    /**
     * The redaction policy applied when {@code ai_policies.redactionPolicy} names one this build does
     * not implement.
     *
     * <p>An unimplemented policy name is a refusal, not a fallback to "no redaction". The default below
     * is the strictest built-in policy, so a configuration typo degrades towards privacy rather than
     * towards disclosure.
     */
    private String fallbackRedactionPolicy = "STRICT_CLINICAL";

    /**
     * The declared provider kinds this build can serve.
     *
     * <p>Empty by default. {@code MOCK} is added unconditionally by
     * {@link com.firstelement.airag.config.AiConfiguration} because it needs no credential, and it is
     * listed here so a deployment can be explicit about it.
     */
    private java.util.List<String> providers = new java.util.ArrayList<>(java.util.List.of("MOCK"));

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getDefaultProvider() {
        return defaultProvider;
    }

    public void setDefaultProvider(String defaultProvider) {
        this.defaultProvider = defaultProvider;
    }

    public long getProviderTimeoutMillis() {
        return providerTimeoutMillis;
    }

    public void setProviderTimeoutMillis(long providerTimeoutMillis) {
        this.providerTimeoutMillis = providerTimeoutMillis;
    }

    public long getRunWallClockMillis() {
        return runWallClockMillis;
    }

    public void setRunWallClockMillis(long runWallClockMillis) {
        this.runWallClockMillis = runWallClockMillis;
    }

    public int getMaxProviderAttempts() {
        return maxProviderAttempts;
    }

    public void setMaxProviderAttempts(int maxProviderAttempts) {
        this.maxProviderAttempts = maxProviderAttempts;
    }

    public String getDataResidencyZone() {
        return dataResidencyZone;
    }

    public void setDataResidencyZone(String dataResidencyZone) {
        this.dataResidencyZone = dataResidencyZone;
    }

    public int getMaxPromptTokensPerRun() {
        return maxPromptTokensPerRun;
    }

    public void setMaxPromptTokensPerRun(int maxPromptTokensPerRun) {
        this.maxPromptTokensPerRun = maxPromptTokensPerRun;
    }

    public int getMaxToolCallsPerRun() {
        return maxToolCallsPerRun;
    }

    public void setMaxToolCallsPerRun(int maxToolCallsPerRun) {
        this.maxToolCallsPerRun = maxToolCallsPerRun;
    }

    public int getRunsPerMinutePerTenant() {
        return runsPerMinutePerTenant;
    }

    public void setRunsPerMinutePerTenant(int runsPerMinutePerTenant) {
        this.runsPerMinutePerTenant = runsPerMinutePerTenant;
    }

    public double getMinRetrievalScore() {
        return minRetrievalScore;
    }

    public void setMinRetrievalScore(double minRetrievalScore) {
        this.minRetrievalScore = minRetrievalScore;
    }

    public int getRetrievalLimit() {
        return retrievalLimit;
    }

    public void setRetrievalLimit(int retrievalLimit) {
        this.retrievalLimit = retrievalLimit;
    }

    public int getIngestionChunkChars() {
        return ingestionChunkChars;
    }

    public void setIngestionChunkChars(int ingestionChunkChars) {
        this.ingestionChunkChars = ingestionChunkChars;
    }

    public String getFallbackRedactionPolicy() {
        return fallbackRedactionPolicy;
    }

    public void setFallbackRedactionPolicy(String fallbackRedactionPolicy) {
        this.fallbackRedactionPolicy = fallbackRedactionPolicy;
    }

    public java.util.List<String> getProviders() {
        return java.util.List.copyOf(providers);
    }

    public void setProviders(java.util.List<String> providers) {
        this.providers = providers == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(providers);
    }
}