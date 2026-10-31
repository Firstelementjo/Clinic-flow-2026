package com.firstelement.crm.clinic;

import java.util.List;
import java.util.UUID;

/**
 * The clinic metric rows, each one carrying the source rows that produced it.
 *
 * <h2>Why every row has an id list next to its count</h2>
 *
 * <p>ADR 0013 §1.3: "Every metric must <b>drill to source rows</b>, exactly as Phase 8's
 * finance totals do. A dashboard number a reader cannot trace is the same class of defect as
 * a hard-coded one."
 *
 * <p>The legacy {@code getExecutiveAnalytics} was the second half of that sentence's failure
 * mode. It computed real counts and then published {@code Math.max(75, rawAiRate)},
 * {@code Math.max(88, rawConfRate)}, {@code estimatedRevenueJod: Math.max(4850, totalRevenue)},
 * a {@code dailyTrends} array of {@code i % 3 === 0 ? 5 : 4 : 3}, and a clinician
 * utilization clamped into {@code [25, 100]}. A clinic manager was shown those figures. None
 * of them is reachable from a row.
 *
 * <p>So the shape here is fixed by one rule: <b>a number and the ids behind it are computed
 * by the same statement over the same rows</b>, and the unbounded count is carried next to
 * the bounded sample so a reader knows when they are looking at a sample.
 *
 * <h2>Why the sample is bounded at all</h2>
 *
 * <p>A busy clinic's month is thousands of appointments. Returning every id on every card
 * would make the analytics page the outage it exists to explain. So the list is capped, and
 * three fields make the cap honest:
 *
 * <ul>
 *   <li>{@code sourceRowCount} — the true, unbounded count of rows the number came from;</li>
 *   <li>{@code sourceRowSampleLimit} — the cap that was applied;</li>
 *   <li>{@code sourceRowIds} — the sample itself, newest first.</li>
 * </ul>
 *
 * <p>A reader with {@code sourceRowCount = 412} and {@code sourceRowIds.length = 50} is
 * being told the truth twice, rather than being handed 50 and left to assume it is
 * everything. A single {@code count} field with an unannounced truncation is how a funnel
 * silently under-reports, and that is the defect this whole module exists to remove.
 */
public final class MetricRows {

    private MetricRows() {
    }

    /**
     * Shared provenance: what this metric is, and how it was computed.
     *
     * <p>Every metric group publishes one of these alongside its rows, and it names the
     * source table and the window. Without it, a reader has a number and no way to tell which
     * of the three funnel definitions produced it.
     *
     * @param sourceTable     the physical relation the number was derived from
     * @param windowStart     inclusive lower bound on {@code appointmentDate}
     * @param windowEnd       inclusive upper bound
     * @param sourceRowSampleLimit the cap applied to every {@code sourceRowIds} list
     * @param derivation      prose naming the aggregation, so a reader can reproduce it by hand
     */
    public record Provenance(
            String sourceTable,
            LocalDateTimeHolder windowStart,
            LocalDateTimeHolder windowEnd,
            int sourceRowSampleLimit,
            String derivation
    ) {

        public Provenance {
            java.util.Objects.requireNonNull(sourceTable, "a metric must name its source table");
            java.util.Objects.requireNonNull(derivation,
                "a metric must state how it was derived; an undescribed number is the defect "
                    + "this module exists to prevent");
        }
    }

    /**
     * A timestamp on the wire.
     *
     * <p>A named wrapper rather than {@code LocalDateTime} so the generated client types the
     * field as a string, and so a window boundary is visibly a <em>declared boundary</em> in
     * the response rather than an implementation detail of the query.
     */
    public record LocalDateTimeHolder(String iso8601) {

        public LocalDateTimeHolder {
            if (iso8601 == null || iso8601.isBlank()) {
                throw new IllegalArgumentException(
                    "a window boundary must be published; an absent one leaves a reader unable "
                        + "to name the denominator");
            }
        }

        public static LocalDateTimeHolder of(java.time.LocalDateTime value) {
            if (value == null) {
                throw new IllegalArgumentException(
                    "a window boundary must be published; an absent one leaves a reader unable "
                        + "to name the denominator");
            }
            return new LocalDateTimeHolder(
                com.firstelement.crm.api.Timestamps.toIso(value));
        }
    }

    /**
     * One step of the appointment funnel.
     *
     * @param status          the stored status this row counts
     * @param appointmentCount {@code count(*)} over the rows in the window with this status —
     *                         the number itself, from the same statement as the ids
     * @param sourceRowIds    a bounded, newest-first sample of the appointment ids counted
     */
    public record FunnelRow(String status,
                            long appointmentCount,
                            List<UUID> sourceRowIds) {

        public FunnelRow {
            sourceRowIds = sourceRowIds == null ? List.of() : List.copyOf(sourceRowIds);
        }

        /** Whether the sample is the whole population, so a reader is never misled. */
        public boolean isCompleteSample() {
            return sourceRowIds.size() == appointmentCount;
        }
    }

    /**
     * Procedure demand.
     *
     * @param appointmentCount {@code count(a."id")} — counts appointments only, so a procedure
     *                         with no bookings reads zero rather than absent
     * @param bookedMinutes    {@code sum(a."durationMinutes")}: a sum over rows a reader can
     *                         list. <b>Not</b> a capacity figure and not billable time.
     * @param sourceRowIds     a bounded sample of the appointment ids counted
     */
    public record ProcedureDemandRow(UUID procedureId,
                                     String name,
                                     String specialty,
                                     long appointmentCount,
                                     long bookedMinutes,
                                     List<UUID> sourceRowIds) {

        public ProcedureDemandRow {
            sourceRowIds = sourceRowIds == null ? List.of() : List.copyOf(sourceRowIds);
        }
    }

    /**
     * Clinician workload.
     *
     * <p>Two counts and no percentage. A utilization ratio needs a capacity denominator —
     * working hours, slot length, days per week, and how many weeks the window covers — and
     * each of those is a clinic policy choice rather than a row. The legacy formula invented
     * all four and then clamped the answer into {@code [25, 100]}, so the floor it reported
     * was a constant, not an observation. Publishing {@code appointmentCount} and
     * {@code bookedMinutes} leaves a reader with two sourced facts and no invented third.
     *
     * @param sourceRowIds a bounded sample of the appointment ids counted
     */
    public record DoctorWorkloadRow(UUID doctorId,
                                    String name,
                                    String specialty,
                                    long appointmentCount,
                                    long bookedMinutes,
                                    List<UUID> sourceRowIds) {

        public DoctorWorkloadRow {
            sourceRowIds = sourceRowIds == null ? List.of() : List.copyOf(sourceRowIds);
        }
    }
}