package com.firstelement.crm.clinic;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The clinic analytics response: three metric groups, each with its rows.
 *
 * <h2>The whole shape exists for one reason</h2>
 *
 * <p>Requirement 1 of this slice: "every aggregate must be derived from a query whose inputs
 * are identified and reachable, and the response must carry enough provenance for a reader to
 * get from the number to the underlying rows."
 *
 * <p>So this record carries, for every group:
 *
 * <ul>
 *   <li>the <b>window</b> that was actually applied — the same {@code LocalDateTime} objects the
 *       SQL bound, not a re-derived or rounded pair;</li>
 *   <li>the <b>source table</b> and a <b>derivation</b> sentence naming the aggregation;</li>
 *   <li>the <b>sample limit</b> that was applied to every row's id list;</li>
 *   <li>a <b>drill-through path</b> that reproduces the metric's own filters as a URL.</li>
 * </ul>
 *
 * <p>And for every row, the ids behind its number. A reader with a figure and this record can
 * open {@link #drillThroughPath()} with the same window and read the rows; a reader with only
 * a figure has nothing at all. That asymmetry is the defect ADR 0013 was raised about.
 *
 * <h2>Why there is no cached total and no materialized aggregate</h2>
 *
 * <p>The mission's instruction is explicit: "prefer computing on read over maintaining a
 * materialized aggregate; if you do maintain one, it must have a stated rebuild and staleness
 * story." This is computed on read, from {@code appointments}, every time. There is nothing
 * to rebuild, nothing to go stale, and no counter that can drift away from the rows it was
 * supposed to summarise. {@code appointments} is the only table every metric here reads, and
 * the {@code doctors} / {@code procedures} columns it names come from a row join rather than
 * from a denormalised copy that could disagree with them.
 *
 * <h2>Why no revenue, no margin, no currency</h2>
 *
 * <p>Finance semantics are a named stop condition for this mission. ADR 0007 Part 1 decides
 * that revenue is derived through a versioned rate card, and {@code procedures.price} is a
 * catalogue column with no version, no currency and no effective date. Multiplying it by a
 * booking count would produce a number that looks like revenue and is not one — which is the
 * precise failure this slice is asked to avoid. A money figure here needs a finance ruling
 * and is reported as one.
 */
/**
 * The whole record is the response body, so every field a reader needs is a record
 * <b>component</b>.
 *
 * <p>That was not the first draft. The provenance records were computed by methods on the
 * record ({@code funnelProvenance()} and friends), which reads more cleanly in Java and
 * serialises to <b>nothing</b>: Jackson renders record components, not accessors, so the
 * response carried the figures and silently dropped the only field that says where they came
 * from. The integration suite caught it because it asserts on the wire body rather than on the
 * service's return value — a test that called {@code analytics.funnelProvenance()} would have
 * passed while the client received no provenance at all.
 */
public record ClinicAnalytics(
        MetricRows.Provenance funnelProvenance,
        MetricRows.Provenance demandProvenance,
        MetricRows.Provenance workloadProvenance,
        List<MetricRows.FunnelRow> funnelByStatus,
        List<MetricRows.ProcedureDemandRow> demandByProcedure,
        List<MetricRows.DoctorWorkloadRow> workloadByDoctor,
        int sourceRowSampleLimit,
        String drillThroughPath
) {

    public ClinicAnalytics {
        funnelByStatus = funnelByStatus == null ? List.of() : List.copyOf(funnelByStatus);
        demandByProcedure = demandByProcedure == null ? List.of() : List.copyOf(demandByProcedure);
        workloadByDoctor = workloadByDoctor == null ? List.of() : List.copyOf(workloadByDoctor);
        java.util.Objects.requireNonNull(funnelProvenance,
            "the funnel must publish its provenance; an undescribed number is the defect this "
                + "read model exists to remove");
        java.util.Objects.requireNonNull(demandProvenance, "procedure demand must publish its provenance");
        java.util.Objects.requireNonNull(workloadProvenance, "clinician workload must publish its provenance");
        java.util.Objects.requireNonNull(drillThroughPath,
            "the response must carry a path from the number to the rows");
    }

    /** The window both bounds were resolved from, taken from the funnel's own provenance. */
    public LocalDateTime from() {
        return windowStart();
    }

    /** The window's upper bound, from the funnel's own provenance. */
    public LocalDateTime to() {
        return windowEnd();
    }

    private LocalDateTime windowStart() {
        return LocalDateTime.parse(funnelProvenance.windowStart().iso8601().replace("Z", ""));
    }

    private LocalDateTime windowEnd() {
        return LocalDateTime.parse(funnelProvenance.windowEnd().iso8601().replace("Z", ""));
    }

    /**
     * The three provenance records and the drill-through path, built once from the window.
     *
     * <p>Static so the service cannot construct a body whose provenance disagrees with its rows:
     * there is one place that knows what each group was derived from.
     */
    public static ClinicAnalytics of(LocalDateTime from, LocalDateTime to,
                                      List<MetricRows.FunnelRow> funnel,
                                      List<MetricRows.ProcedureDemandRow> demand,
                                      List<MetricRows.DoctorWorkloadRow> workload,
                                      int sampleLimit) {
        if (from == null || to == null) {
            throw new IllegalArgumentException(
                "A clinic metric must publish the window it was computed over. A number whose "
                    + "denominator cannot be named is the defect this read model exists to "
                    + "remove, and an absent boundary would be exactly that.");
        }
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException(
                "The analytics window must be non-empty: from " + from + " is not before to " + to);
        }
        return new ClinicAnalytics(
            new MetricRows.Provenance("appointments",
                MetricRows.LocalDateTimeHolder.of(from),
                MetricRows.LocalDateTimeHolder.of(to),
                sampleLimit,
                "count(*) grouped by appointments.\"status\", over appointments in the stated "
                    + "window. Each row's sourceRowIds is a newest-first sample of the appointment "
                    + "ids that produced its count, collected in the same statement, so the number "
                    + "and the rows cannot come from different predicates."),
            new MetricRows.Provenance("procedures left join appointments",
                MetricRows.LocalDateTimeHolder.of(from),
                MetricRows.LocalDateTimeHolder.of(to),
                sampleLimit,
                "count(appointments.\"id\") and sum(appointments.\"durationMinutes\") grouped by "
                    + "procedures.\"id\", over the stated window, left-joined so a procedure with "
                    + "no bookings reports zero rather than disappearing. bookedMinutes is a sum "
                    + "over the appointment rows, not billable time and not a capacity figure. No "
                    + "amount is derived: procedures.price carries no rate-card version, currency "
                    + "or effective date, so a revenue figure from it is a finance decision "
                    + "(ADR 0007 Part 1) and is not made here."),
            new MetricRows.Provenance("doctors left join appointments",
                MetricRows.LocalDateTimeHolder.of(from),
                MetricRows.LocalDateTimeHolder.of(to),
                sampleLimit,
                "count(appointments.\"id\") and sum(appointments.\"durationMinutes\") grouped by "
                    + "doctors.\"id\", over the stated window. No utilization percentage is "
                    + "published: its denominator is a clinic capacity policy (working hours, slot "
                    + "length, days per week, weeks covered), not a row."),
            funnel, demand, workload, sampleLimit,
            "/api/v1/clinic/appointments?from=" + MetricRows.LocalDateTimeHolder.of(from).iso8601()
                + "&to=" + MetricRows.LocalDateTimeHolder.of(to).iso8601());
    }

    /**
     * Every appointment id any metric counted, deduplicated.
     *
     * <p>Exposed so a caller — and the integration suite — can assert that the union of the
     * sampled source rows actually belongs to the population the counts describe, which is the
     * half of "drills to source rows" a count alone cannot demonstrate.
     */
    public List<UUID> sampledSourceRowIds() {
        List<UUID> ids = new java.util.ArrayList<>();
        funnelByStatus.forEach(row -> ids.addAll(row.sourceRowIds()));
        demandByProcedure.forEach(row -> ids.addAll(row.sourceRowIds()));
        workloadByDoctor.forEach(row -> ids.addAll(row.sourceRowIds()));
        return ids.stream().distinct().toList();
    }
}