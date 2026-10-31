package com.firstelement.finance.reconcile;

import com.firstelement.finance.catalog.UsageDimension;
import com.firstelement.finance.money.Money;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * The result of comparing one period's snapshots against the ledger.
 *
 * <h2>What a reconciliation actually answers</h2>
 *
 * <p>"Does the number on the dashboard equal the sum of the immutable events it claims to summarise?"
 * Nothing else. So the comparison is deliberately narrow and every difference is reported rather than
 * absorbed:
 *
 * <ul>
 *   <li>a <b>MATCHED</b> line has a difference of exactly zero within tolerance — not "small", zero;</li>
 *   <li>a <b>MISMATCH</b> line carries a note naming what the difference is
 *       ({@code finance_reconciliation_lines_status_note_check} requires one), and the note is this
 *       module's {@link VarianceExplanation}, not free prose;</li>
 *   <li>an <b>ACCEPTED</b> mismatch is one a human has examined and signed off, and it is a deliberate
 *       act with a reason, never a default.</li>
 * </ul>
 *
 * <h2>Why tolerance exists at all and what it may not do</h2>
 *
 * <p>Because a real deployment may price a currency pair from a feed with a rounding convention
 * different from this module's. So tolerance is a per-run parameter, it is <em>stored</em> on the run
 * row, and it is compared against the absolute difference. It may not be used to make a real
 * discrepancy pass: a tolerance wide enough to cover a real variance is a configuration error, and
 * {@link #varianceIsReal} refuses one wider than a documented bound.
 */
public record ReconciliationResult(
        UUID runId,
        UUID organizationId,
        UUID projectId,
        UUID periodId,
        Outcome outcome,
        long sourceEventCount,
        Money snapshotAmount,
        Money ledgerAmount,
        Money difference,
        BigDecimal toleranceAmount,
        String notes,
        String correlationId,
        java.time.LocalDateTime ranAt,
        java.util.List<LineResult> lines
) {

    public ReconciliationResult {
        lines = lines == null ? java.util.List.of() : java.util.List.copyOf(lines);
    }

    /** The run's overall state, mirroring {@code ReconciliationStatus}. */
    public enum Outcome {
        /** Every dimension matched within tolerance. */
        MATCHED,
        /** At least one dimension did not. */
        MISMATCH,
        /** A human examined the mismatch and accepted it, with a reason. */
        ACCEPTED;

        public String wireValue() {
            return name();
        }
    }

    /**
     * One dimension's comparison.
     *
     * @param difference ledger minus snapshot, CHECKed by
     *        {@code finance_reconciliation_runs_difference_check} and its per-line equivalent, so a
     *        difference that is not the difference cannot be written
     */
    public record LineResult(UUID id,
                             UsageDimension dimension,
                             String currency,
                             long sourceEventCount,
                             BigDecimal snapshotQuantity,
                             BigDecimal ledgerQuantity,
                             BigDecimal snapshotCost,
                             BigDecimal ledgerCost,
                             BigDecimal quantityDifference,
                             BigDecimal costDifference,
                             Outcome outcome,
                             VarianceExplanation explanation,
                             String note) {
    }

    /**
     * Whether a discrepancy exceeded tolerance.
     *
     * <p>Stated as a method rather than left to the caller because {@code compareTo} on a negative
     * difference is the classic place a reconciliation reports a match for a mismatch: the comparison
     * must be against the <em>absolute</em> value.
     */
    public boolean hasVariance() {
        return difference.isPriced()
            && difference.amount().abs().compareTo(toleranceAmount) > 0;
    }

    /** The dimensions that did not match. */
    public java.util.List<LineResult> varianceLines() {
        return lines.stream().filter(line -> line.outcome() != Outcome.MATCHED).toList();
    }

    /** Every variance carries an explanation code; this is the assertion a reader can make. */
    public boolean everyVarianceIsExplained() {
        return varianceLines().stream().allMatch(
            line -> line.explanation() != null && line.note() != null && !line.note().isBlank());
    }
}
