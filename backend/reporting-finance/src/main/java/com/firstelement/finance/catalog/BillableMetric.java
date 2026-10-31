package com.firstelement.finance.catalog;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * One declared, measurable, priceable unit of usage: a {@link UsageDimension} plus everything the
 * ledger needs to know about it that the database does not.
 *
 * <h2>What this record adds over the enum</h2>
 *
 * <p>{@link UsageDimension} carries the wire value, the canonical unit and the family. A
 * {@code BillableMetric} adds the two things that make a dimension <em>usable</em> in finance:
 *
 * <ul>
 *   <li><b>{@code sourceEventTypes}</b> — the closed set of {@code usage_events."sourceEventType"}
 *       values that may produce this dimension. This is the taxonomy, and it is a closed set because
 *       {@code sourceEventType} is a free-text column: without a registry, "which tables emit usage"
 *       is answerable only by grepping every {@code insert into usage_events}, and a new emitter
 *       would simply not be found.</li>
 *   <li><b>{@code aggregation}</b> — whether quantities inside a bucket may be summed at all. Not
 *       all quantities are additive, and a snapshot sums them because the snapshot is unique per
 *       bucket and dimension.</li>
 * </ul>
 *
 * @param dimension            the dimension this metric measures
 * @param sourceEventTypes     the declared source event types that may produce it
 * @param aggregation          whether quantities are additive inside a rollup bucket
 * @param description          operator-facing meaning, carried into the catalogue document and the
 *                             read model's {@code note} so a figure is never shown without a sentence
 *                             saying what it counts
 */
public record BillableMetric(
        UsageDimension dimension,
        Set<String> sourceEventTypes,
        Aggregation aggregation,
        String description
) {

    /**
     * Whether quantities of this dimension may be summed inside one rollup bucket.
     *
     * <p>Everything in the current catalogue is additive, and the distinction is recorded anyway
     * because the alternative — a dimension whose quantity is a maximum, a level, or a gauge — would
     * make {@code sum(quantity)} in {@link com.firstelement.finance.rollup.UsageRollupRepository}
     * quietly wrong, and it would be wrong in a way no schema constraint can catch.
     */
    public enum Aggregation {
        /** {@code sum(quantity)}. */
        SUM,
        /** Not summable; the rollup must refuse this dimension rather than add it. */
        NOT_SUMMABLE
    }

    public BillableMetric {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(aggregation, "aggregation");
        Objects.requireNonNull(description, "description");
        if (description.isBlank()) {
            throw new IllegalArgumentException("A billable metric must say what it counts");
        }
        if (sourceEventTypes == null || sourceEventTypes.isEmpty()) {
            throw new IllegalArgumentException(
                "Metric " + dimension + " declares no source event type; a dimension nothing can emit "
                    + "is dead configuration that looks live in a dashboard.");
        }
        for (String type : sourceEventTypes) {
            if (type == null || type.isBlank()) {
                throw new IllegalArgumentException(
                    "Metric " + dimension + " declares a blank source event type");
            }
        }
        sourceEventTypes = Collections.unmodifiableSet(new LinkedHashSet<>(sourceEventTypes));
    }

    /** Whether {@code sourceEventType} is one this metric declares. */
    public boolean acceptsSourceEventType(String sourceEventType) {
        return sourceEventType != null && sourceEventTypes.contains(sourceEventType);
    }
}
