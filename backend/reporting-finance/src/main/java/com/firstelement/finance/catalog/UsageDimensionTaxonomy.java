package com.firstelement.finance.catalog;

/**
 * The families the twelve dimensions fall into.
 *
 * <h2>Why a family and not a flag</h2>
 *
 * <p>Two questions keep being asked of a usage dimension and neither is answered by the dimension
 * itself:
 *
 * <ul>
 *   <li><b>"Is this money the tenant pays us, or money we pay a provider?"</b> A delivery cost
 *       (a channel provider's per-message fee) and a subscription cost (a seat-day) are both real
 *       costs, but they are different claims on the business and they are reviewed by different
 *       people. A boolean would be re-decided per call site.</li>
 *   <li><b>"Does the emitter know the cost at write time?"</b> This is not a reporting question at
 *       all; it decides whether {@code usage_events.costAmount} may be populated or must stay NULL,
 *       and a NULL cost is what makes a bucket <em>unpriced</em> rather than zero. Getting this
 *       wrong is the difference between a first-class unpriced view and a zero nobody questions.</li>
 * </ul>
 *
 * <p>So the family is a closed set and it is load-bearing:
 * {@link com.firstelement.finance.ledger.UsageEventFactory} consults
 * {@link UsageDimensionTaxonomy#costKnownAtEmitTime()} before it accepts a cost, which is what stops
 * a caller from writing a fabricated cost onto an {@code AI_TOKEN} event.
 */
public enum UsageDimensionTaxonomy {

    /**
     * Usage a customer causes and a provider meters: messages, conversations, channel traffic.
     *
     * <p>The cost is known when the event is written, because the provider's tariff produced it
     * (and the provider's invoice is the evidence).
     */
    DELIVERY(true),

    /**
     * Consumption of a licensed resource: seats and retained storage.
     *
     * <p>The cost is known at emit time in a deployment that meters seats and storage daily. The
     * emit-time cost is the tenant's <em>charged</em> amount for that day, which is a rate-card
     * figure, so an emitter that has not consulted a rate card leaves the cost NULL and the bucket
     * is unpriced until one exists.
     */
    SUBSCRIPTION(true),

    /** Metered operations the tenant drives: API calls, workflow runs, exports, indexed chunks. */
    USAGE(false),

    /** AI provider calls. */
    AI(false),

    /** Approved human or automated effort. Never derived from a message count. */
    EFFORT(false);

    private final boolean costKnownAtEmitTime;

    UsageDimensionTaxonomy(boolean costKnownAtEmitTime) {
        this.costKnownAtEmitTime = costKnownAtEmitTime;
    }

    /**
     * Whether an emitter may supply {@code costAmount} for a dimension in this family.
     *
     * <p>False means "the cost is not a property of the event; it comes from the rate card, or from
     * a provider invoice recorded elsewhere". A caller supplying a cost anyway is refused with a
     * message naming the family, because an {@code AI_TOKEN} event carrying a hand-written dollar
     * figure is precisely the "no mutable counter becomes money" failure ADR 0007 rule 3 forbids.
     */
    public boolean costKnownAtEmitTime() {
        return costKnownAtEmitTime;
    }
}
