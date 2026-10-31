package com.firstelement.finance.catalog;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The finance metric catalogue and the source-event taxonomy, in code.
 *
 * <h2>What this class is</h2>
 *
 * <p>The written catalogue is {@code docs/migration/finance-metric-catalogue.md}. This is the part of
 * it that is <em>enforced</em>: the registry every {@code UsageEvent} write is checked against, so
 * "what counts as usage" is a checked table rather than a paragraph that drifts from the code two
 * months later. {@code FinanceMetricCatalogueTest} fails if this table and the document's table
 * disagree in dimension, unit, family or source event type.
 *
 * <h2>The four invariants this class asserts at construction</h2>
 *
 * <ol>
 *   <li><b>Every declared dimension has a metric, and every metric a declared dimension.</b> A
 *       dimension that no metric describes cannot be emitted, so it would appear in the database
 *       enum and in nothing else — the shape of a configuration that looks supported.</li>
 *   <li><b>Canonical units are unique across dimensions.</b> Not for tidiness: the read model groups
 *       by dimension and a shared unit would make two dimensions' quantities comparable in a total
 *       nobody computed correctly.</li>
 *   <li><b>Every source event type belongs to exactly one dimension.</b>
 *       {@code sourceEventType} participates in the duplicate-suppression key together with
 *       {@code dimension}, so the same source row may legitimately produce two dimensions (an AI run
 *       produces {@code AI_REQUEST} and {@code AI_TOKEN}). What it may not do is produce
 *       {@code MESSAGE} and {@code AI_TOKEN}, which would make "count the messages" ambiguous.</li>
 *   <li><b>Every source event type names a table.</b> The value is {@code Table.operation} — the
 *       drill-through reads it as "which row produced this", and a bare table name would leave the
 *       row unidentifiable.</li>
 * </ol>
 *
 * <h2>Why the catalogue is closed</h2>
 *
 * <p>There is no {@code register()} method. A configurable billable dimension is configured through a
 * <em>rate card line</em> ({@code rate_card_version_lines}), which says what a dimension costs; the
 * taxonomy of what may be <em>measured</em> is an application fact and adding a dimension is a code
 * change with a migration behind it. "Configurable" here means the pricing and the enabled/disabled
 * set per tenant, not the set of things the system can count — an unknown dimension with no catalogue
 * entry is unrepresentable, which is the property that makes the taxonomy worth having.
 */
public final class FinanceMetricCatalogue {

    // ------------------------------------------------------------------
    // the catalogue
    // ------------------------------------------------------------------

    private static final Map<UsageDimension, BillableMetric> METRICS = build();

    private FinanceMetricCatalogue() {
    }

    private static Map<UsageDimension, BillableMetric> build() {
        EnumMap<UsageDimension, BillableMetric> metrics =
            new EnumMap<>(UsageDimension.class);

        metrics.put(UsageDimension.MESSAGE, sum(new BillableMetric(
            UsageDimension.MESSAGE,
            Set.of("messages.delivered", "messages.received", "messages.sent_by_agent"),
            BillableMetric.Aggregation.SUM,
            "One message delivered to, or received from, a customer channel.")));

        metrics.put(UsageDimension.CONVERSATION, sum(new BillableMetric(
            UsageDimension.CONVERSATION,
            Set.of("conversations.opened", "conversations.resolved",
                "conversations.taken_over", "conversations.returned_to_ai"),
            BillableMetric.Aggregation.SUM,
            "One conversation reaching a state a tenant can be charged for or measured against.")));

        metrics.put(UsageDimension.CHANNEL_TRAFFIC, sum(new BillableMetric(
            UsageDimension.CHANNEL_TRAFFIC,
            Set.of("channel_traffic.metered", "channel_connections.traffic_metered"),
            BillableMetric.Aggregation.SUM,
            "Kilobytes carried on a channel connection, metered by the provider.")));

        metrics.put(UsageDimension.SEAT, sum(new BillableMetric(
            UsageDimension.SEAT,
            Set.of("seat_licenses.day", "users.seat_day"),
            BillableMetric.Aggregation.SUM,
            "One licensed seat for one calendar day.")));

        metrics.put(UsageDimension.STORAGE, sum(new BillableMetric(
            UsageDimension.STORAGE,
            Set.of("storage.byte_day", "document_chunks.byte_day",
                "export_jobs.byte_day"),
            BillableMetric.Aggregation.SUM,
            "Retained bytes for one day, including export artefacts inside their retention window.")));

        metrics.put(UsageDimension.API_OPERATION, sum(new BillableMetric(
            UsageDimension.API_OPERATION,
            Set.of("api_operations.metered"),
            BillableMetric.Aggregation.SUM,
            "One metered API operation.")));

        metrics.put(UsageDimension.WORKFLOW_RUN, sum(new BillableMetric(
            UsageDimension.WORKFLOW_RUN,
            Set.of("workflow_runs.started", "workflow_runs.completed", "workflow_runs.failed"),
            BillableMetric.Aggregation.SUM,
            "One workflow run. Counted at start; completion and failure are separate source types on "
                + "the same run so a run is never counted twice.")));

        metrics.put(UsageDimension.REPORT_EXPORT, sum(new BillableMetric(
            UsageDimension.REPORT_EXPORT,
            Set.of("export_jobs.completed", "report_runs.produced"),
            BillableMetric.Aggregation.SUM,
            "One export or report artefact produced.")));

        metrics.put(UsageDimension.AI_REQUEST, sum(new BillableMetric(
            UsageDimension.AI_REQUEST,
            Set.of("ai_runs.started", "ai_runs.completed", "ai_runs.failed"),
            BillableMetric.Aggregation.SUM,
            "One AI run request. Counted at start so a failed run is still usage a provider billed.")));

        metrics.put(UsageDimension.AI_TOKEN, sum(new BillableMetric(
            UsageDimension.AI_TOKEN,
            Set.of("ai_usage.tokens_recorded", "ai_runs.tokens_recorded"),
            BillableMetric.Aggregation.SUM,
            "One AI token, input or output, as reported by the provider for the run.")));

        metrics.put(UsageDimension.TIME_ENTRY, sum(new BillableMetric(
            UsageDimension.TIME_ENTRY,
            Set.of("time_entries.approved", "time_entries.recorded"),
            BillableMetric.Aggregation.SUM,
            "One billable minute of effort from an approved time entry. Never derived from a "
                + "conversation count or a handle time: effort is entered, and this module refuses to "
                + "compute it from anything else.")));

        metrics.put(UsageDimension.DOCUMENT_CHUNK, sum(new BillableMetric(
            UsageDimension.DOCUMENT_CHUNK,
            Set.of("document_chunks.indexed", "document_chunks.embedded"),
            BillableMetric.Aggregation.SUM,
            "One indexed knowledge chunk.")));

        assertComplete(metrics);
        return Collections.unmodifiableMap(metrics);
    }

    private static BillableMetric sum(BillableMetric metric) {
        return metric;
    }

    // ------------------------------------------------------------------
    // construction-time invariants
    // ------------------------------------------------------------------

    private static void assertComplete(Map<UsageDimension, BillableMetric> metrics) {
        for (UsageDimension dimension : UsageDimension.values()) {
            if (!metrics.containsKey(dimension)) {
                throw new IllegalStateException(
                    "Finance metric catalogue is incomplete: UsageDimension." + dimension
                        + " has no BillableMetric. Every declared dimension must be measurable, or it "
                        + "is dead configuration.");
            }
        }
        if (metrics.size() != UsageDimension.values().length) {
            throw new IllegalStateException("Finance metric catalogue has an entry for an undeclared "
                + "dimension");
        }

        Map<String, UsageDimension> units = new java.util.LinkedHashMap<>();
        for (BillableMetric metric : metrics.values()) {
            String unit = metric.dimension().canonicalUnit();
            UsageDimension clash = units.put(unit, metric.dimension());
            if (clash != null) {
                throw new IllegalStateException("Canonical unit '" + unit + "' is claimed by both "
                    + clash + " and " + metric.dimension() + "; a shared unit invites a total across "
                    + "two different things.");
            }
        }

        Map<String, UsageDimension> owners = new java.util.LinkedHashMap<>();
        for (BillableMetric metric : metrics.values()) {
            for (String sourceEventType : metric.sourceEventTypes()) {
                if (!sourceEventType.contains(".")) {
                    throw new IllegalStateException("Source event type '" + sourceEventType
                        + "' on " + metric.dimension() + " is not 'Table.operation'; the "
                        + "drill-through needs to know which row produced the usage.");
                }
                UsageDimension previous = owners.put(sourceEventType, metric.dimension());
                if (previous != null) {
                    throw new IllegalStateException("Source event type '" + sourceEventType
                        + "' is claimed by both " + previous + " and " + metric.dimension()
                        + "; one source row may produce at most one dimension.");
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // lookups
    // ------------------------------------------------------------------

    /** Every metric, in declaration order. */
    public static Iterable<BillableMetric> all() {
        return METRICS.values();
    }

    /** The metric for a dimension. Never null: every declared dimension has one. */
    public static BillableMetric metric(UsageDimension dimension) {
        if (dimension == null) {
            throw new IllegalArgumentException("A metric lookup requires a dimension");
        }
        BillableMetric metric = METRICS.get(dimension);
        if (metric == null) {
            throw new IllegalArgumentException("UsageDimension." + dimension + " has no catalogue entry");
        }
        return metric;
    }

    /**
     * The metric a source event type produces.
     *
     * <p>The lookup the emitter uses, and the reason an undeclared source type is refused rather
     * than recorded: a usage event whose source type is outside the taxonomy has no defined meaning,
     * and an undefined row in an immutable ledger is a row nobody can ever interpret or correct.
     */
    public static BillableMetric metricForSourceEventType(String sourceEventType) {
        for (BillableMetric metric : METRICS.values()) {
            if (metric.acceptsSourceEventType(sourceEventType)) {
                return metric;
            }
        }
        throw new IllegalArgumentException("Source event type '" + sourceEventType
            + "' is not in the finance metric catalogue. Declared types are "
            + declaredSourceEventTypes());
    }

    /** The dimension a source event type produces, or {@code null} when it is undeclared. */
    public static UsageDimension dimensionForSourceEventType(String sourceEventType) {
        for (BillableMetric metric : METRICS.values()) {
            if (metric.acceptsSourceEventType(sourceEventType)) {
                return metric.dimension();
            }
        }
        return null;
    }

    /** Whether a source event type is declared anywhere in the catalogue. */
    public static boolean isDeclaredSourceEventType(String sourceEventType) {
        return dimensionForSourceEventType(sourceEventType) != null;
    }

    /** Every declared source event type, sorted, for the catalogue document and a test assertion. */
    public static Set<String> declaredSourceEventTypes() {
        Set<String> all = new TreeSet<>();
        for (BillableMetric metric : METRICS.values()) {
            all.addAll(metric.sourceEventTypes());
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(all));
    }

    /**
     * Whether the emitter may write a measured cost for this dimension at write time.
     *
     * <p>See {@link UsageDimensionTaxonomy#costKnownAtEmitTime()}. The rule exists so that the only
     * costs in the ledger are costs a provider or a meter produced, never costs a caller asserted.
     */
    public static boolean costMayBeSuppliedAtEmitTime(UsageDimension dimension) {
        return metric(dimension).dimension().taxonomy().costKnownAtEmitTime();
    }

    /** Whether an emitter may supply a cost for {@code sourceEventType}. */
    public static boolean costMayBeSuppliedAtEmitTime(String sourceEventType) {
        return costMayBeSuppliedAtEmitTime(metricForSourceEventType(sourceEventType).dimension());
    }

    /** The source types a dimension accepts, for a catalogue endpoint and for error messages. */
    public static Set<String> sourceEventTypesFor(UsageDimension dimension) {
        return metric(dimension).sourceEventTypes();
    }
}
