package com.firstelement.finance.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * An amount, the currency it is in, and the state it is in. Never a bare number.
 *
 * <h2>Why the currency is a constructor argument</h2>
 *
 * <p>ADR 0007 rule 6: "Every amount carries a currency; conversion records the FX source and rate
 * date." A {@code double}, or a {@link BigDecimal} on its own, is an amount with an implied currency
 * and the implication is always the developer's own default. Putting the currency in the constructor
 * makes the mistake unrepresentable rather than merely discouraged.
 *
 * <h2>Why the state is a field and not an inference</h2>
 *
 * <p>{@link AmountState} carries the distinction ADR 0007 rule 5 requires — {@code NO_DATA},
 * {@code UNPRICED} and a genuine {@code PRICED_ZERO} are three different answers — so a caller reads
 * {@link #state()} instead of deciding what {@code null} means. {@link #amount()} throws for an
 * absent state rather than returning zero, because "no rate was effective" silently becoming
 * {@code 0} is the specific failure this type is built to make impossible.
 *
 * <h2>Scale and rounding</h2>
 *
 * <p>Six decimal places, matching {@code numeric(20,6)} on every money column in
 * {@code V20261005080000} and {@code V20261005090000}. Enforced here rather than left to the driver,
 * because {@code usage_rollup_snapshots_derivation_check} requires
 * {@code revenueAmount = round(costAmount * markupRate, 6)} <em>exactly</em>: a Java value with more
 * precision would be stored rounded and the stored row would then fail its own CHECK. The rounding
 * mode is {@link RoundingMode#HALF_UP}, which rounds ties away from zero — the same rule PostgreSQL's
 * {@code round(numeric, s)} uses. A different mode would make the application and the database
 * disagree about the last decimal place, and a database CHECK that the arithmetic does not hold is
 * not something to discover in production.
 *
 * <p>A final class rather than a record, because a record cannot carry the two behaviours that
 * differ by state: {@link #amount()} throws when absent, and {@link #marginOf} exists only for
 * priced amounts.
 */
public final class Money {

    /** The column scale on every money column in the finance migrations. */
    public static final int SCALE = 6;

    private static final BigDecimal MAXIMUM_INTEGER_DIGITS =
        new BigDecimal("99999999999999");

    private final AmountState state;
    private final BigDecimal amount;
    private final String currency;
    private final FxAttribution fx;

    /**
     * @param signed whether a negative value is permitted. True only for a derived margin; a cost, a
     *        revenue or a stored figure passes false and is refused if negative.
     */
    private Money(AmountState state, BigDecimal amount, String currency, FxAttribution fx,
                  boolean signed) {
        this.state = Objects.requireNonNull(state, "state");
        this.currency = requireCurrency(currency);
        this.fx = fx;
        if (state.hasAmount()) {
            this.amount = normalise(amount, signed);
        } else {
            this.amount = null;
        }
    }

    private Money(AmountState state, BigDecimal amount, String currency, FxAttribution fx) {
        this(state, amount, currency, fx, false);
    }

    // ------------------------------------------------------------------
    // factories
    // ------------------------------------------------------------------

    /** A priced amount. Rejects null, negatives and over-wide magnitudes. */
    public static Money of(BigDecimal amount, String currency) {
        return of(amount, currency, null);
    }

    /** A priced amount that carries the FX attribution of the conversion that produced it. */
    public static Money of(BigDecimal amount, String currency, FxAttribution fx) {
        Objects.requireNonNull(amount,
            "Money.of requires an amount. For the absence of one use Money.noData(...) or "
                + "Money.unpriced(...); null is not how 'we do not know' is spelled.");
        return new Money(amount.signum() == 0 ? AmountState.PRICED_ZERO : AmountState.PRICED_NONZERO,
            amount, currency, fx);
    }

    /** A priced zero. A real answer: it happened and it cost nothing. */
    public static Money zero(String currency) {
        return new Money(AmountState.PRICED_ZERO, BigDecimal.ZERO.setScale(SCALE), currency, null);
    }

    /**
     * Nothing was recorded.
     *
     * <p>The currency is still required, because a caller asking "what was February worth" is asking
     * in a currency and a currency-less absence would push that decision back onto them — which is how
     * a total in the wrong currency gets added to a total in the right one.
     */
    public static Money noData(String currency) {
        return new Money(AmountState.NO_DATA, null, currency, null);
    }

    /**
     * Usage exists but no rate was effective on the usage date.
     *
     * <p>Distinct from {@link #noData(String)} even though both carry no number, and the distinction
     * is the point of ADR 0007's unpriced requirement: one says "nothing happened", the other says
     * "something happened and nobody has agreed what it costs".
     */
    public static Money unpriced(String currency) {
        return new Money(AmountState.UNPRICED, null, currency, null);
    }

    // ------------------------------------------------------------------
    // accessors
    // ------------------------------------------------------------------

    /** The state. Read this before {@link #amount()}. */
    public AmountState state() {
        return state;
    }

    /**
     * The amount.
     *
     * @throws IllegalStateException when the state is {@link AmountState#NO_DATA} or
     *         {@link AmountState#UNPRICED} — reading an absent amount as zero is the defect this
     *         type exists to make impossible
     */
    public BigDecimal amount() {
        if (!state.hasAmount()) {
            throw new IllegalStateException("This figure is " + state.label()
                + " and has no number. NO_DATA means nothing was recorded; UNPRICED means usage "
                + "exists with no rate effective on the usage date. Neither may be read as 0.");
        }
        return amount;
    }

    /** The amount, or {@code null} for an absent state. The opt-in way to render. */
    public BigDecimal amountOrNull() {
        return amount;
    }

    /** Always the ISO code. Never null, whatever the state. */
    public String currency() {
        return currency;
    }

    /** The FX attribution of the conversion that produced this amount, or {@code null}. */
    public FxAttribution fx() {
        return fx;
    }

    public boolean isPriced() {
        return state.isPriced();
    }

    /**
     * Whether this is a priced amount of exactly zero.
     *
     * <p>False for an absent amount, so {@code isZero()} is a question about a <em>value</em> rather than
     * about a missing one — which is the whole point of the type. {@code Money.zero("USD").isZero()} is
     * true; {@code Money.unpriced("USD").isZero()} is false.
     */
    public boolean isZero() {
        return state.isZero();
    }

    /** Whether this is a priced, positive amount. */
    public boolean isNonZero() {
        return state.isNonZero();
    }

    public boolean isUnpriced() {
        return state.isUnpriced();
    }

    public boolean isNoData() {
        return state.isNoData();
    }

    /**
     * Whether this figure carries no number at all.
     *
     * <p>True for {@link AmountState#NO_DATA} and {@link AmountState#UNPRICED}, and distinct from
     * {@link #isPriced()}. Exposed because a caller assembling a projection has to branch on it, and
     * {@link #amount()} throwing is the safe default for a caller that forgets.
     */
    public boolean isAbsent() {
        return !state.hasAmount();
    }

    // ------------------------------------------------------------------
    // derivation
    // ------------------------------------------------------------------

    /**
     * Revenue: {@code round(cost * markupRate, 6)}, in this amount's currency.
     *
     * <p>The only way a revenue figure is ever produced anywhere in this system. It is a method, not
     * a column, and {@code V20261005080000} fails its own migration if a revenue column appears on
     * {@code usage_events}.
     */
    public Money multipliedBy(BigDecimal markupRate) {
        Objects.requireNonNull(markupRate, "markupRate");
        if (state == AmountState.UNPRICED) {
            throw new IllegalStateException(
                "Cannot derive revenue from an UNPRICED figure: there is no cost to multiply. "
                    + "Unpriced usage is reported as unpriced until a rate card version is effective.");
        }
        if (markupRate.signum() <= 0 || markupRate.compareTo(BigDecimal.valueOf(1000)) > 0) {
            throw new IllegalArgumentException(
                "A markup must be greater than 0 and at most 1000, which is the schema's own bound on "
                    + "rate_card_versions.markupRate; got " + markupRate.toPlainString());
        }
        BigDecimal revenue = amount.multiply(markupRate).setScale(SCALE, RoundingMode.HALF_UP);
        return new Money(revenue.signum() == 0 ? AmountState.PRICED_ZERO : AmountState.PRICED_NONZERO,
            revenue, currency, fx);
    }

    /**
     * Margin: {@code revenue - cost}.
     *
     * <p>Exists only where a rate applied. {@code V20261005090000} fails its own migration if a
     * margin column appears on any finance table, because "never displayed where the row is
     * unpriced" is not enforceable once a nullable margin column is in the schema.
     *
     * <p>A negative margin is returned as-is. It is the signal a finance reviewer most needs, and
     * clamping a loss to zero is exactly how a losing month reads as break-even.
     */
    /**
     * A signed difference: ledger minus snapshot.
     *
     * <p>The one other figure permitted to be negative, and for the same reason as a margin: a direction,
     * not a magnitude. A reconciliation where the snapshots exceed the ledger is a different finding from
     * one where the ledger exceeds them, and taking an absolute value would erase exactly the
     * distinction the reconciliation exists to make — the {@link #isNegative()} half says which side is
     * ahead.
     *
     * <p>Named rather than reusing {@link #marginOf}, because the sign means different things: a
     * negative margin is a loss, a negative reconciliation difference is a snapshot ahead of its ledger.
     */
    public static Money signedDifference(BigDecimal difference, String currency) {
        requireCurrency(currency);
        if (difference.abs().compareTo(MAXIMUM_INTEGER_DIGITS) >= 0) {
            throw new IllegalArgumentException(
                "Difference " + difference.toPlainString() + " does not fit numeric(20,6).");
        }
        BigDecimal scaled = difference.setScale(SCALE, RoundingMode.HALF_UP);
        return new Money(scaled.signum() == 0 ? AmountState.PRICED_ZERO : AmountState.PRICED_NONZERO,
            scaled, currency, null, true);
    }

    public Money marginOf(Money revenue) {
        requireSameCurrency(revenue);
        if (!isPriced()) {
            throw new IllegalStateException("Margin requires a priced cost; this figure is "
                + state.label() + ".");
        }
        if (!revenue.isPriced()) {
            throw new IllegalStateException("Margin requires a priced revenue; the revenue figure is "
                + revenue.state().label() + ".");
        }
        BigDecimal difference = revenue.amount().subtract(amount).setScale(SCALE, RoundingMode.HALF_UP);
        // The `signed` constructor argument is the whole point: a margin is the ONE figure that may be
        // below zero, and clamping it here is what would make a losing month read as break-even. Nothing
        // else in this class may construct a negative amount, and the flag is not reachable from outside.
        return new Money(difference.signum() == 0 ? AmountState.PRICED_ZERO
            : AmountState.PRICED_NONZERO, difference, currency, null, true);
    }

    /** Whether this amount is below zero. Only a margin can be. */
    public boolean isNegative() {
        return amount != null && amount.signum() < 0;
    }

    /** Adds another amount. Refuses a currency mismatch rather than inventing a rate. */
    public Money plus(Money other) {
        Objects.requireNonNull(other, "other");
        requireSameCurrency(other);
        if (isAbsent() || other.isAbsent()) {
            throw new IllegalStateException(
                "Cannot add " + state.label() + " to " + other.state().label() + ". An absent amount "
                    + "is not zero; summing across an unpriced row would silently price it at the "
                    + "other row's rate.");
        }
        BigDecimal sum = amount.add(other.amount()).setScale(SCALE, RoundingMode.HALF_UP);
        return of(sum, currency, fx);
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                "Cannot combine " + currency + " with " + other.currency + ". Convert explicitly, "
                    + "recording the FX source and rate date (ADR 0007 rule 6). There is no implicit "
                    + "rate in this system and there is not going to be one.");
        }
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    /**
     * Scales to the column precision and refuses what the column would refuse.
     *
     * @param signed true only for a derived margin, which is the one figure that may be below zero
     */
    private static BigDecimal normalise(BigDecimal amount, boolean signed) {
        if (amount.signum() < 0 && !signed) {
            throw new IllegalArgumentException(
                "A cost or revenue must not be negative (" + amount.toPlainString() + "). A negative "
                    + "cost is a sign error; a discount is a rate-card line or an approved finance "
                    + "adjustment, never a negated amount. Only a derived margin may be negative.");
        }
        if (amount.abs().compareTo(MAXIMUM_INTEGER_DIGITS) >= 0) {
            throw new IllegalArgumentException(
                "Amount " + amount.toPlainString() + " does not fit numeric(20,6); at most 14 integer "
                    + "digits are storable and an amount that cannot be stored is not a finance figure.");
        }
        return amount.setScale(SCALE, RoundingMode.HALF_UP);
    }

    private static String requireCurrency(String currency) {
        if (currency == null || !currency.matches("^[A-Z]{3}$")) {
            throw new IllegalArgumentException(
                "Currency '" + currency + "' is not an ISO-4217-shaped alphabetic code. The schema "
                    + "checks the same shape through public.firstelement_currency_code, so a code "
                    + "that fails here fails at the column as well.");
        }
        return currency;
    }

    @Override
    public String toString() {
        return state.hasAmount()
            ? amount.toPlainString() + " " + currency + " (" + state.label() + ")"
            : currency + " (" + state.label() + ")";
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Money that)) {
            return false;
        }
        return state == that.state && currency.equals(that.currency)
            && Objects.equals(amount, that.amount) && Objects.equals(fx, that.fx);
    }

    @Override
    public int hashCode() {
        return Objects.hash(state, amount, currency, fx);
    }
}
