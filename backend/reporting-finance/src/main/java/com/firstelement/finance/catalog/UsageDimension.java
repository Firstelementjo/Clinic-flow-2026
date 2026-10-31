package com.firstelement.finance.catalog;

/**
 * The twelve usage dimensions {@code V20261005080000} declares, transcribed from
 * {@code public."UsageDimension"}.
 *
 * <h2>Why this enum exists and is not the database enum</h2>
 *
 * <p>There is no ORM in this runtime (ADR-0002: explicit quoted-column JDBC), so an enum column is a
 * {@code String} on the Java side and a {@code cast(:dimension as "UsageDimension")} in SQL. The
 * transcription is exact and in declaration order, and {@link #fromWire(String)} refuses anything
 * outside the list rather than passing it to the database — so a typo is a rejected command rather
 * than a PostgreSQL error surfaced from three frames down.
 *
 * <h2>There is deliberately no {@code UNPRICED} member</h2>
 *
 * <p>The migration says it and the reason is worth repeating, because it is the single easiest way
 * to defeat ADR 0007 rule 5. "Unpriced" is not a <em>kind</em> of usage; it is the absence of a rate
 * effective on the usage date. As an enum member it would become something you could count, sum,
 * chart and put next to real dimensions — and a category that can be counted is a category that gets
 * treated as zero. Unpricedness is a property of the rollup snapshot (no {@code rateCardVersionId},
 * no {@code revenueAmount}), so it is reported as a state by
 * {@link com.firstelement.finance.read.PricingState} and is not countable as a unit of usage.
 */
public enum UsageDimension {

    /** One delivered or received chat message. */
    MESSAGE("message", UsageDimensionTaxonomy.DELIVERY),

    /** One conversation thread that reached a terminal or human-handled state. */
    CONVERSATION("conversation", UsageDimensionTaxonomy.DELIVERY),

    /** Volume carried by a channel connection, measured in kilobytes. */
    CHANNEL_TRAFFIC("kb", UsageDimensionTaxonomy.DELIVERY),

    /** One licensed seat for one day. */
    SEAT("seat_day", UsageDimensionTaxonomy.SUBSCRIPTION),

    /** Retained bytes for one day, including derived artefacts and their retention tail. */
    STORAGE("byte_day", UsageDimensionTaxonomy.SUBSCRIPTION),

    /** One metered API operation. */
    API_OPERATION("operation", UsageDimensionTaxonomy.USAGE),

    /** One workflow run, whether or not it did anything consequential. */
    WORKFLOW_RUN("run", UsageDimensionTaxonomy.USAGE),

    /** One export artefact produced. */
    REPORT_EXPORT("export", UsageDimensionTaxonomy.USAGE),

    /**
     * One AI request. Counted separately from tokens because the request is what a provider charges
     * a minimum for and what a tenant's budget policy counts.
     */
    AI_REQUEST("request", UsageDimensionTaxonomy.AI),

    /**
     * One AI token.
     *
     * <p>Two events are written per AI run — one {@code AI_REQUEST} and one {@code AI_TOKEN} — because
     * the rate card prices them separately and a rollup that carried only tokens could not name which
     * of the two produced a cost.
     */
    AI_TOKEN("token", UsageDimensionTaxonomy.AI),

    /** One billable minute of human or automated effort, from an approved time entry. */
    TIME_ENTRY("minute", UsageDimensionTaxonomy.EFFORT),

    /** One indexed knowledge chunk. */
    DOCUMENT_CHUNK("chunk", UsageDimensionTaxonomy.USAGE);

    private final String canonicalUnit;
    private final UsageDimensionTaxonomy taxonomy;

    UsageDimension(String canonicalUnit, UsageDimensionTaxonomy taxonomy) {
        this.canonicalUnit = canonicalUnit;
        this.taxonomy = taxonomy;
    }

    /**
     * The one unit this dimension may be recorded in.
     *
     * <p>One, and enforced. {@code usage_rollup_snapshots} is unique per
     * {@code (organizationId, projectId, grain, bucketStart, dimension)} and carries no unit column,
     * so two events of the same dimension in different units would be added together and the total
     * would be a quantity of nothing. Refusing the second unit at write time is the only place the
     * mistake can be caught: by the time a snapshot exists, it has already been summed.
     */
    public String canonicalUnit() {
        return canonicalUnit;
    }

    /** Which family the dimension belongs to, for reporting and for the catalogue document. */
    public UsageDimensionTaxonomy taxonomy() {
        return taxonomy;
    }

    /** The wire value, which is the enum label. */
    public String wireValue() {
        return name();
    }

    public static UsageDimension fromWire(String value) {
        if (value == null) {
            throw new IllegalArgumentException("A usage dimension must not be null");
        }
        for (UsageDimension dimension : values()) {
            if (dimension.name().equals(value)) {
                return dimension;
            }
        }
        throw new IllegalArgumentException("Unknown UsageDimension '" + value + "'. Declared values are "
            + java.util.Arrays.stream(values()).map(UsageDimension::name)
            .collect(java.util.stream.Collectors.joining(",")));
    }
}
