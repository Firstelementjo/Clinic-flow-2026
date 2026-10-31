package com.firstelement.finance.legacy;

import com.firstelement.finance.money.Money;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Legacy {@code ai_usage} figures beside the ledger's, with every difference named.
 *
 * <h2>Why this exists at all</h2>
 *
 * <p>ADR 0009 §3 decided that {@code ai_usage.estimatedCost} is <b>not</b> finance truth and must not
 * be migrated into the ledger as an amount. Copying a mutable provider estimate into an immutable
 * ledger would import an untraceable number as if it were a source event, and every downstream total
 * would inherit it.
 *
 * <p>That decision leaves the legacy table readable as a <em>comparison source</em>, and this record is
 * where the comparison happens. The disagreement is not reconciled and it is not hidden: it is reported
 * as a variance with a {@link VarianceCause}, which is what ADR 0009 §3 requires — "Where the two
 * disagree, the difference is reported as a variance in the comparison view, not reconciled silently".
 *
 * <h2>Why a difference is expected rather than alarming</h2>
 *
 * <p>Because the two sides are computed differently on purpose:
 *
 * <ul>
 *   <li><b>the legacy figure</b> is {@code estimatedCost}, written at run time by the AI orchestrator
 *       from a hard-coded in-code price table, which returns {@code 0} for any model it does not
 *       know;</li>
 *   <li><b>the ledger figure</b> is a sum over immutable {@code usage_events} rows, priced by the
 *       rate-card version effective on the usage date.</li>
 * </ul>
 *
 * <p>So most variance codes describe the design rather than a fault, and each code's own text says
 * which is which.
 *
 * <h2>What this record deliberately does not do</h2>
 *
 * <p>It does not choose a winner. There is no "adjusted" or "corrected" column, because either would be
 * a second source of truth for the same figure — the exact two-sources failure ADR 0007 Part 1 rejects.
 * The comparison presents both numbers and names the cause.
 */
public record LegacyComparisonRow(
        String dimension,
        String currency,
        BigDecimal legacyTokens,
        BigDecimal ledgerTokens,
        BigDecimal legacyEstimatedCost,
        BigDecimal ledgerCost,
        Money ledgerRevenue,
        BigDecimal tokenDifference,
        Money costDifference,
        VarianceCause cause,
        String explanation
) {

    public LegacyComparisonRow {
        if (cause == null) {
            throw new IllegalArgumentException(
                "A comparison row must name why it differs, or state that it does not. An unexplained "
                    + "variance is how a legacy figure quietly replaces a ledger one.");
        }
        if (explanation == null || explanation.isBlank()) {
            throw new IllegalArgumentException(
                "A comparison row must carry an explanation as well as a cause code; the prose is what "
                    + "a reviewer reads six months later.");
        }
        if (!currency.matches("^[A-Z]{3}$")) {
            throw new IllegalArgumentException(
                "Every amount carries a currency; a comparison row whose currency is blank cannot be "
                    + "compared with anything, which is the failure it exists to detect.");
        }
    }

    /** Whether the two sides differ. */
    public boolean isVariance() {
        return cause.isVariance();
    }

    /** Whether this row needs a person to decide something rather than acknowledge it. */
    public boolean needsInvestigation() {
        return cause.needsInvestigation();
    }

    /** The size of the cost difference, as a plain decimal for grouping. */
    public BigDecimal costDifferenceAmount() {
        return costDifference.amountOrNull() == null ? BigDecimal.ZERO : costDifference.amount();
    }

    /** The dimension, as a uuid-free label. Kept so a caller never has to parse the wire value. */
    public String dimensionLabel() {
        return dimension;
    }

    /** The ledger's revenue for this dimension, or unpriced. Never null-means-zero. */
    public Money revenue() {
        return ledgerRevenue;
    }

    /** A stable key for a report grouping: dimension and currency. */
    public String key() {
        return dimension + "|" + currency + "|" + cause.wireValue();
    }

    /** Convenience for a caller holding a snapshot rather than a comparison. */
    public static LegacyComparisonRow of(String dimension, String currency, BigDecimal legacyTokens,
                                         BigDecimal ledgerTokens, BigDecimal legacyCost,
                                         BigDecimal ledgerCost, Money ledgerRevenue,
                                         VarianceCause cause) {
        BigDecimal tokenDifference = ledgerTokens.subtract(legacyTokens);
        Money costDifference = Money.of(ledgerCost.subtract(legacyCost).abs()
            .setScale(Money.SCALE, java.math.RoundingMode.HALF_UP), currency);
        return new LegacyComparisonRow(dimension, currency, legacyTokens, ledgerTokens, legacyCost,
            ledgerCost, ledgerRevenue, tokenDifference, costDifference, cause,
            cause.explanation());
    }

    /** The ledger snapshot id this row was derived from, when one exists. */
    public UUID sourceSnapshotId() {
        return null;
    }
}
