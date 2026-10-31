package com.firstelement.finance.ledger;

import java.time.LocalDate;

/**
 * How a {@link UsageEvent} was obtained.
 *
 * <h2>Why a ledger event distinguishes these</h2>
 *
 * <p>ADR 0007 Part 1 rule 7 removes invoicing and payment from this migration, so every value here
 * describes what the ledger <em>observed</em>, never what a customer was charged or owes. The
 * distinction is worth a field because the drill-through has to answer "why does this row exist" and
 * a live metered event and a measured out-of-band quantity are different answers to that question
 * even when they are the same dimension.
 *
 * <p>{@link #ADJUSTMENT} is the important one: an approved, audited correction is not a usage event,
 * so it does not live here — but a reader of the ledger needs to be able to tell a usage row from a
 * row that was raised to correct one, and the correction mechanism itself is
 * {@code usage_events.correctionOfId}.
 */
public enum UsageEventOrigin {

    /** Recorded at the moment the usage happened, by the module that caused it. */
    LIVE_METERED,

    /**
     * Recorded after the fact by a metering job from a provider's own report — a carrier's
     * per-message invoice, a nightly seat count.
     *
     * <p>Distinct from {@link #LIVE_METERED} because the cost is a provider's figure rather than one
     * this system measured, which is the difference an auditor asks about first.
     */
    PROVIDER_REPORT,

    /**
     * Raised to correct an earlier event.
     *
     * <p>A correction is a NEW immutable row naming the row it corrects, never an update. It also
     * carries the dimension of the row it corrects, because
     * {@code usage_events_source_dimension_key} includes the dimension: without that, a correction
     * would collide with the original it is correcting.
     */
    ADJUSTMENT
}
