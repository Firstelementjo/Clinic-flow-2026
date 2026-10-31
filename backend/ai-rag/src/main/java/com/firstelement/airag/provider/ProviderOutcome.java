package com.firstelement.airag.provider;

import java.util.ArrayList;
import java.util.List;

/**
 * The provider chain: an ordered list of attempts, and the fact that every one of them failed.
 *
 * <h2>Fail closed, and say why</h2>
 *
 * <p>When the chain is exhausted the result is {@link ProviderOutcome#refused()} — not an empty answer,
 * not the first provider's partial text, and not an exception that a caller could swallow into a
 * fallback reply. ADR 0007 Part 2 rule 3: "the conversation falls back to a human or a clearly recorded
 * no-action state". A clearly recorded no-action state needs the reasons, so every attempt is kept with
 * its {@link ProviderFailure} and its scrubbed detail.
 *
 * <h2>Why a chain rather than one provider</h2>
 *
 * <p>Because "the CRM continues when the provider is down" is only true if something catches it, and the
 * something has to be ordered and bounded. {@code ai_policies."failoverChain"} names the order; the run
 * stops at the first success; and {@code maxProviderAttempts} bounds the cost of a chain that is entirely
 * unavailable, so a dead provider cannot be re-probed once per conversation forever.
 */
public record ProviderOutcome(boolean answered,
                              ProviderAttempt winningAttempt,
                              List<ProviderAttempt> attempts,
                              String refusalCode) {

    public ProviderOutcome {
        attempts = attempts == null ? List.of() : List.copyOf(attempts);
    }

    /** A successful outcome. */
    public static ProviderOutcome answered(ProviderAttempt winner, List<ProviderAttempt> attempts) {
        return new ProviderOutcome(true, winner, attempts, null);
    }

    /**
     * A refused outcome.
     *
     * <p>{@code refusalCode} is a {@link ProviderFailure} name or a policy code, never a message: this
     * string is written to {@code ai_runs."errorMessage"} and shown in an operator UI, and a message is
     * where a provider's echo of the prompt would end up.
     */
    public static ProviderOutcome refused(String refusalCode, List<ProviderAttempt> attempts) {
        return new ProviderOutcome(false, null, attempts, refusalCode);
    }

    public List<ProviderAttempt> failures() {
        List<ProviderAttempt> failed = new ArrayList<>();
        for (ProviderAttempt attempt : attempts) {
            if (attempt.failure() != null) {
                failed.add(attempt);
            }
        }
        return List.copyOf(failed);
    }

    /** The kinds that were tried, in order. What an operator reads first. */
    public List<String> attemptedProviders() {
        return attempts.stream().map(ProviderAttempt::provider).toList();
    }
}