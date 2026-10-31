package com.firstelement.finance.money;

/**
 * The three-and-a-half states a finance figure can be in, and why three is the minimum.
 *
 * <h2>The defect this enum exists to prevent</h2>
 *
 * <p>ADR 0007 rule 5: "Zero is not 'no data'. An empty period and a zero-valued period are distinct
 * states in every read model." Two states — {@code null} and {@code 0} — cannot express that, so
 * every consumer invents its own convention: a dashboard prints {@code 0}, an API returns
 * {@code null}, a CSV writes an empty cell, and a reconciliation treats a missing amount as zero. Those
 * are four different numbers for one fact, and none of them is defensible to an auditor.
 *
 * <p>So the state is a first-class value, carried next to the amount and rendered by the read model
 * rather than inferred by the caller.
 *
 * <h2>The four states</h2>
 *
 * <table>
 *   <caption>Amount states</caption>
 *   <tr><th>State</th><th>Means</th><th>Amount</th></tr>
 *   <tr><td>{@link #NO_DATA}</td>
 *       <td>No usage was recorded for this bucket. The absence of evidence</td><td>none</td></tr>
 *   <tr><td>{@link #UNPRICED}</td>
 *       <td>Usage exists and has a cost, but <em>no rate was effective on the usage date</em>. The
 *           presence of evidence that nobody has agreed a price for</td><td>none</td></tr>
 *   <tr><td>{@link #PRICED_ZERO}</td>
 *       <td>A rate applied and the arithmetic produced exactly zero</td><td>0.000000</td></tr>
 *   <tr><td>{@link #PRICED_NONZERO}</td>
 *       <td>A rate applied and the arithmetic produced a positive amount</td><td>&gt; 0</td></tr>
 * </table>
 *
 * <p>The first two are the ones a two-state model cannot hold at once, and the third is the one that
 * is silently destroyed by a {@code coalesce(amount, 0)}: a legitimately priced zero is a real answer
 * that says "this happened and it cost nothing", while {@code NO_DATA} says "we do not know".
 *
 * <p>{@link #UNPRICED} is deliberately <em>not</em> a {@code UsageDimension} member, so it can never
 * be counted, summed or charted as though it were a kind of usage — see
 * {@link com.firstelement.finance.catalog.UsageDimension}.
 */
public enum AmountState {

    /** Nothing was recorded. An empty period. Distinct from a zero-valued one. */
    NO_DATA("no_data"),

    /**
     * Usage was recorded and costed, but no rate-card version was effective on the usage date.
     *
     * <p>First-class, not an error: ADR 0007's consequences section requires "unpriced usage is a
     * first-class view, not an error". Margin is <em>not</em> reported for an unpriced figure.
     */
    UNPRICED("unpriced"),

    /** A rate applied and the derived amount is exactly zero. */
    PRICED_ZERO("priced_zero"),

    /** A rate applied and the derived amount is positive. */
    PRICED_NONZERO("priced_non_zero");

    private final String label;

    AmountState(String label) {
        this.label = label;
    }

    /** The wire value. Stable, so a UI can branch on it without parsing prose. */
    public String label() {
        return label;
    }

    /** Whether an amount exists. False for {@link #NO_DATA} and {@link #UNPRICED}. */
    public boolean hasAmount() {
        return this == PRICED_ZERO || this == PRICED_NONZERO;
    }

    /**
     * Whether the state is {@link #PRICED_NONZERO}.
     *
     * <p>Distinct from {@link #hasAmount()}, which also admits a priced zero. A caller asking "is there
     * real money here?" wants this; one asking "is there a figure at all?" wants {@code hasAmount()}.
     */
    public boolean isNonZero() {
        return this == PRICED_NONZERO;
    }

    /** Whether no rate was effective. Margin is never reported for this state. */
    public boolean isUnpriced() {
        return this == UNPRICED;
    }

    /**
     * Whether the figure is a priced amount of exactly zero.
     *
     * <p>The state a {@code coalesce(amount, 0)} destroys, and the reason this enum exists: a caller that
     * asks "is this zero?" must be able to be told <em>no, there was no figure</em>, which is what
     * {@link #NO_DATA} and {@link #UNPRICED} mean.
     */
    public boolean isZero() {
        return this == PRICED_ZERO;
    }

    /** Whether nothing was recorded at all. */
    public boolean isNoData() {
        return this == NO_DATA;
    }

    /** Whether a rate applied, whatever the resulting number. */
    public boolean isPriced() {
        return hasAmount();
    }
}
