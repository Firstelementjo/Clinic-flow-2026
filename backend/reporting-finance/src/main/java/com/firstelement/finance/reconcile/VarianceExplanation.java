package com.firstelement.finance.reconcile;

/**
 * Why a snapshot and the ledger disagree.
 *
 * <h2>Why this is a closed vocabulary and not a free-text note</h2>
 *
 * <p>ADR 0009 §3 is explicit about the AI case: "Where the two disagree, the difference is reported as
 * a variance in the comparison view, <b>not reconciled silently</b>." A free-text note satisfies the
 * letter of that and fails the purpose: "reconciled manually" explains nothing a month later and cannot
 * be counted. So every variance carries one of these codes, and the code is what a reviewer aggregates
 * by — which is how "the ledger has been 3% above the snapshots every week since March" becomes a
 * question somebody asks.
 *
 * <p>The schema enforces that a non-{@code MATCHED} line has <em>a</em> note
 * ({@code finance_reconciliation_lines_status_note_check}); this enum is what makes the note mean
 * something, and {@link #note()} is what the repository actually writes.
 */
public enum VarianceExplanation {

    /** The figures agree. Not a variance; present so a MATCHED line can also carry a note. */
    NONE("no variance", false),

    /**
     * A snapshot has not been computed for usage that exists in the ledger.
     *
     * <p>The most common real cause and the least alarming: the rollup job has not run for a bucket.
     * The fix is to compute it, not to adjust.
     */
    SNAPSHOT_MISSING("the ledger holds usage for a bucket with no computed snapshot; run the rollup",
        false),

    /**
     * A snapshot exists whose recomputation is now the current generation, and the read model is
     * showing a superseded row.
     *
     * <p>Points at a read-model bug rather than at the data: a recomputation adds a generation and
     * supersedes the previous one, and a reader that takes "any row" rather than "the unsuperseded row"
     * would show the old figures.
     */
    SUPERSEDED_GENERATION(
        "a newer snapshot generation supersedes the one that was compared; the read model must read the "
            + "unsuperseded row", false),

    /**
     * Usage arrived inside the period after it was locked, so it is recorded but unattached.
     *
     * <p>{@link com.firstelement.finance.ledger.UsagePeriodResolver} records late arrivals with no
     * {@code billingPeriodId} precisely so a closed period does not move. The ledger therefore
     * legitimately holds more than the period does, and the reconciliation is where that becomes
     * visible. The remedy is a reopen and an approved adjustment — never a silent inclusion.
     */
    LATE_ARRIVAL_AFTER_LOCK(
        "usage occurred inside the period after it was locked and is recorded unattached; reopen and "
            + "apply an approved adjustment", true),

    /**
     * The bucket is priced by a rate that did not cover the whole period.
     *
     * <p>A rate changed mid-period, so a monthly rollup cannot use one rate for the whole month.
     * {@link com.firstelement.finance.rollup.RollupService} records such a bucket as unpriced rather
     * than guessing; the difference between "unpriced" and "priced at the wrong version" is exactly
     * what this code names.
     */
    RATE_CHANGES_MID_PERIOD(
        "the rate effective on the period start did not cover the whole period; recompute at the DAILY "
            + "grain so each day is priced by its own rate", false),

    /**
     * An FX conversion differs between the ledger row and the snapshot.
     *
     * <p>Never a rounding artefact to be ignored: {@code usage_events} carries the rate, its source and
     * its rate date, so a difference means the snapshot used a different rate than the event recorded.
     */
    FX_RATE_DIFFERS(
        "the snapshot and the ledger disagree on an FX conversion; the events carry fxRate, fxSource "
            + "and fxRateDate and those are the authority", true),

    /**
     * A usage event was added to the ledger after the snapshot for its bucket was computed.
     *
     * <p>Ordinary lag, and the reason a period is only locked once the rollup is caught up. It becomes
     * a real problem only if it persists after the close run, which is what the {@code LOCKED} guard
     * prevents from being silently absorbed.
     */
    SNAPSHOT_STALE(
        "usage events were added after this snapshot was computed; recompute the bucket to a new "
            + "generation", false),

    /**
     * A usage event was corrected after its snapshot was computed.
     *
     * <p>A correction is a new immutable row, so the ledger holds both the original and the correction
     * and a snapshot computed before both will not agree. This is the expected shape of a corrected
     * period.
     */
    CORRECTION_AFTER_SNAPSHOT(
        "a usage event was corrected after this snapshot was computed; the correction is a new row "
            + "naming the one it corrects, so both are in the ledger", true),

    /**
     * An approved adjustment was applied to the period.
     *
     * <p>The ledger and the snapshot are both correct as of their own computation times; the difference
     * is the adjustment, which by design is not a usage event.
     */
    APPROVED_ADJUSTMENT_APPLIED(
        "an approved finance adjustment was applied to this period; an adjustment is a reasoned "
            + "correction to cost, not a usage event, so it appears here rather than in the ledger", true),

    /**
     * The snapshot's cost does not equal its own pinned inputs.
     *
     * <p>Should be impossible: {@code usage_rollup_snapshots_derivation_check} requires
     * {@code revenueAmount = round(costAmount × markupRate, 6)} exactly. If this code is ever produced,
     * a constraint was dropped and every number derived from that row is suspect.
     */
    DERIVATION_BROKEN(
        "the snapshot's stored revenue does not equal round(costAmount x markupRate, 6); the "
            + "derivation CHECK must have been dropped", true);

    private final String note;
    private final boolean requiresHumanReview;

    VarianceExplanation(String note, boolean requiresHumanReview) {
        this.note = note;
        this.requiresHumanReview = requiresHumanReview;
    }

    /** The note written to the reconciliation line. Never blank, per the schema's CHECK. */
    public String note() {
        return note;
    }

    public String wireValue() {
        return name();
    }

    /**
     * Whether this variance needs a human to decide something.
     *
     * <p>True for the cases where the right response is not "recompute the rollup" — a late arrival, an
     * FX disagreement, a broken derivation. A dashboard that treats those the same as a stale snapshot
     * will keep showing a number no amount of recomputing will fix.
     */
    public boolean requiresHumanReview() {
        return requiresHumanReview;
    }

    /** Whether this explanation is compatible with the given outcome. */
    public boolean appliesTo(ReconciliationResult.Outcome outcome) {
        return switch (outcome) {
            case MATCHED -> this == NONE;
            case MISMATCH, ACCEPTED -> this != NONE;
        };
    }
}
