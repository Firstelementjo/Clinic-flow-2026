package com.firstelement.finance.legacy;

/**
 * Why the legacy {@code ai_usage} figures and the ledger's disagree.
 *
 * <h2>Why this is a closed vocabulary and not a free-text note</h2>
 *
 * <p>ADR 0009 §3: "Where the two disagree, the difference is reported as a variance in the comparison
 * view, <b>not reconciled silently</b>." A free-text note satisfies the letter of that and fails the
 * purpose — "reconciled manually" explains nothing a month later and cannot be counted. So every
 * variance carries one of these codes, and the code is what a reviewer aggregates by, which is how
 * "the ledger has been 3% above the snapshots every week since March" becomes a question somebody
 * asks.
 *
 * <p>Its own top-level file rather than a nested type, because an enum declared inside a record is
 * implicitly {@code static} and cannot then carry per-constant instance state — and per-constant state
 * is what makes each code carry its own explanation rather than a shared {@code note()} method.
 *
 * <p>The codes describe the <em>expected</em> differences of two systems that compute cost
 * differently on purpose, so most of them are not symptoms of a defect. That is stated per code, because
 * a comparison whose every variance reads as alarming gets ignored.
 */
public enum VarianceCause {

    /** The two sides agree. */
    NONE(false, "the legacy estimate and the ledger agree for this dimension"),

    /**
     * The legacy estimator had no price for the model, so it recorded zero.
     *
     * <p>{@code estimateCostUsd} in {@code packages/ai/src/providers/types.ts} returns {@code 0} when
     * a model is absent from its hard-coded {@code PRICES} table. A legacy zero therefore means "no
     * price for this model", not "free", and treating it as free is how a model that does cost money
     * reads as free in a legacy dashboard.
     */
    LEGACY_MODEL_UNPRICED(true,
        "the legacy ai_usage.estimatedCost came from an in-code price table that returns 0 for a model "
            + "it does not know, so the legacy zero means 'no price', not 'free'"),

    /**
     * The ledger holds usage the legacy table does not.
     *
     * <p>Expected after cutover: the ledger records every AI run from the moment emission was wired,
     * while {@code ai_usage} only has rows the legacy orchestrator wrote.
     */
    LEDGER_ONLY(true,
        "the ledger holds usage the legacy table does not; expected for any run after usage emission "
            + "was wired, since ai_usage was only written by the legacy orchestrator"),

    /**
     * The legacy table holds a run the ledger does not.
     *
     * <p>Either a pre-cutover window — where the ledger is right to be empty — or an emission gap,
     * which is a defect. Named separately from {@link #LEDGER_ONLY} because the two mean opposite
     * things and a combined code would hide the difference.
     */
    LEGACY_ONLY(true,
        "the legacy table holds a run the ledger does not; expected before usage emission was wired, "
            + "and a defect after it"),

    /**
     * Tokens agree and costs do not.
     *
     * <p>The strongest signal available: the same usage priced two ways. The difference is the legacy
     * in-code price against the rate-card version effective on the usage date, which is precisely the
     * difference ADR 0007 §3 decided in favour of the rate card.
     */
    PRICE_BASIS_DIFFERS(true,
        "the same token count is priced differently: the legacy figure used an in-code price table, the "
            + "ledger used the rate-card version effective on the usage date"),

    /**
     * Neither tokens nor costs agree.
     *
     * <p>The widest case, and the only one flagged for investigation: a token-count difference means the
     * two systems did not even record the same runs, which is not expected after cutover.
     */
    QUANTITY_AND_PRICE_DIFFER(true,
        "both the token count and the cost differ; the legacy table counts runs the ledger may have "
            + "recorded differently, and prices them by a different basis");

    private final boolean variance;

    /** The prose written onto the comparison row, so the cause is readable without the enum. */
    private final String explanation;

    VarianceCause(boolean variance, String explanation) {
        this.variance = variance;
        this.explanation = explanation;
    }

    /** Whether this cause is a difference rather than an agreement. */
    public boolean isVariance() {
        return variance;
    }

    /** The wire value. Stable, so a client can branch on it without parsing prose. */
    public String wireValue() {
        return name();
    }

    /** The explanation written onto the row. Never blank — the comparison requires a reason. */
    public String explanation() {
        return explanation;
    }

    /**
     * Whether this cause needs somebody to decide something.
     *
     * <p>True only for the two cases where the right response is not "this is how the two systems
     * differ". A dashboard that escalates every expected variance trains its readers to ignore it.
     */
    public boolean needsInvestigation() {
        return this == QUANTITY_AND_PRICE_DIFFER || this == LEGACY_ONLY;
    }
}
