package com.firstelement.exports.persistence;

import com.firstelement.exports.catalog.ReportSubject;
import com.firstelement.exports.plan.ExportFilter;
import com.firstelement.exports.plan.FilterNotPushableException;
import com.firstelement.exports.redaction.ResolvedExportColumns;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The finance period state an export of money must respect.
 *
 * <h2>Why this is not "just read the ledger"</h2>
 *
 * <p>ADR 0007 rule 5 and the finance module's own arithmetic make three states distinguishable, and a
 * naive export erases all three:
 *
 * <ul>
 *   <li><b>UNPRICED</b> — usage exists, a cost exists, and no rate-card version was effective on the date.
 *       Not zero, and not {@code revenue = 0}.</li>
 *   <li><b>NO_DATA</b> — a bucket with no contributing events produces no snapshot row at all.</li>
 *   <li><b>PRICED</b> — a rate applied.</li>
 * </ul>
 *
 * <p>So the export carries {@code usage.unpricedQuantity} beside any revenue column, and a revenue that
 * is absent is absent for a reason.
 *
 * <h2>The period-lock rule, and why it is a refusal</h2>
 *
 * <p>A LOCKED period's figures have been reviewed, approved and (usually) reconciled. {@code usage_events}
 * is append-only but it is not frozen — a late-arriving event lands in the ledger for a locked period
 * without changing any approved total. So an export that recomputed a locked period from the ledger would
 * print a number that disagrees with the number that was signed off.
 *
 * <p>The rule this repository supports: <b>an export whose window intersects a LOCKED period is served
 * only from the pinned snapshots, and is refused if a requested bucket has no snapshot.</b> Refusing rather
 * than printing a partial window is deliberate — a locked-period export that silently omits an
 * un-materialised bucket looks complete and is not.
 *
 * <h2>Why this reads finance tables directly rather than through the finance module</h2>
 *
 * <p>Because the read is a <em>gate</em>, not a computation: it answers "may this export proceed against
 * this window", and it needs the period state, which is {@code finance_periods."status"} plus the snapshot
 * coverage. It writes nothing and computes no amount. The amounts themselves come from
 * {@code usage_rollup_snapshots} through {@code ReportSubject.USAGE_LEDGER}, which is the same read model
 * {@code FinanceUsageReadService} assembles from, so the export cannot disagree with the dashboard.
 */
@Repository
public class FinanceExportGateRepository {

    /** One finance period, as the export gate sees it. */
    public record PeriodState(UUID id,
                              String key,
                              String nameAr,
                              String nameEn,
                              LocalDate periodStart,
                              LocalDate periodEnd,
                              String currency,
                              String status,
                              LocalDateTime lockedAt) {

        /** Whether the period's figures are final. */
        public boolean isLocked() {
            return "LOCKED".equals(status);
        }
    }

    /** The gate's answer for one export window. */
    public record WindowGate(List<PeriodState> intersecting,
                             List<String> lockedPeriodKeys,
                             LocalDate earliestBucket,
                             LocalDate latestBucket,
                             long bucketsWithoutSnapshot) {

        /** Whether any intersecting period is closed. */
        public boolean intersectsLockedPeriod() {
            return !lockedPeriodKeys.isEmpty();
        }
    }

    /**
     * The finance periods covering a date window for one project.
     *
     * <p>Project-scoped by the caller's {@code TenantScope} plus an explicit project id, because
     * {@code finance_periods."projectId"} is nullable (an organisation-wide period) and a tenant-wide read
     * would otherwise be the only way to see one.
     */
    private static final String PERIODS_IN_WINDOW = """
        select p."id", p."key", p."nameAr", p."nameEn", p."periodStart", p."periodEnd", p."currency",
               p."status"::text as "status", p."lockedAt"
          from finance_periods p
         where p."organizationId" = :__fe_tenant_organization_id
           and (p."projectId" = cast(:projectId as uuid) or p."projectId" is null)
           and p."periodStart" <= cast(:toDate as date)
           and p."periodEnd" >= cast(:fromDate as date)
         order by p."periodStart"
         limit :limit
        """;

    /** The buckets in a window that have no current snapshot row. */
    private static final String BUCKETS_WITHOUT_SNAPSHOT = """
        select count(*)
          from generate_series(cast(:fromDate as date), cast(:toDate as date), interval '1 day') d(day)
         where not exists (
               select 1
                 from usage_rollup_snapshots s
                where s."organizationId" = :__fe_tenant_organization_id
                  and s."projectId" = cast(:projectId as uuid)
                  and s."supersedesSnapshotId" is null
                  and s."bucketStart" = d.day::date
           )
        """;

    /** The number of current snapshot rows in a window, which is what a complete window has. */
    private static final String SNAPSHOT_ROWS_IN_WINDOW = """
        select count(*)
          from usage_rollup_snapshots s
         where s."organizationId" = :__fe_tenant_organization_id
           and s."projectId" = cast(:projectId as uuid)
           and s."supersedesSnapshotId" is null
           and s."bucketStart" >= cast(:fromDate as date)
           and s."bucketStart" <= cast(:toDate as date)
        """;

    private final NamedParameterJdbcTemplate jdbc;

    public FinanceExportGateRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Answers whether an export of a date window may proceed.
     *
     * @param fromDate the window's first bucket
     * @param toDate   the window's last bucket, inclusive
     * @param limit    the bounded number of periods considered
     */
    public WindowGate gate(com.firstelement.security.query.TenantScope scope, UUID projectId,
                           LocalDate fromDate, LocalDate toDate, int limit) {
        MapSqlParameterSource parameters = com.firstelement.security.query.ScopedWhere.of(scope)
            .bindNamed(java.util.Map.of("limit", Math.max(1, limit)))
            .addValue("projectId", projectId, java.sql.Types.OTHER)
            .addValue("fromDate", fromDate)
            .addValue("toDate", toDate);
        List<PeriodState> periods = jdbc.query(PERIODS_IN_WINDOW, parameters,
            (rs, rowNum) -> new PeriodState(
                rs.getObject("id", UUID.class),
                rs.getString("key"),
                rs.getString("nameAr"),
                rs.getString("nameEn"),
                rs.getObject("periodStart", LocalDate.class),
                rs.getObject("periodEnd", LocalDate.class),
                rs.getString("currency"),
                rs.getString("status"),
                rs.getObject("lockedAt", LocalDateTime.class)));

        List<String> locked = new ArrayList<>();
        for (PeriodState period : periods) {
            if (period.isLocked()) {
                locked.add(period.key());
            }
        }

        long expected = com.firstelement.exports.format.ExportValueFormatter.daysBetween(fromDate, toDate) + 1;
        Long present = jdbc.queryForObject(SNAPSHOT_ROWS_IN_WINDOW,
            scopedWindowParameters(scope, projectId, fromDate, toDate), Long.class);
        long missing = expected - (present == null ? 0L : present);
        Long explicitlyMissing = jdbc.queryForObject(BUCKETS_WITHOUT_SNAPSHOT,
            scopedWindowParameters(scope, projectId, fromDate, toDate), Long.class);

        return new WindowGate(periods, locked, fromDate, toDate,
            Math.max(0L, explicitlyMissing == null ? missing : explicitlyMissing));
    }

    /** Whether an export subject needs this gate at all. */
    public static boolean appliesTo(ReportSubject subject) {
        return subject != null && subject.isFinance();
    }

    /**
     * Refuses an export of a locked window that is not fully snapshotted.
     *
     * @throws LockedPeriodIncompleteException when the window intersects a LOCKED period and at least one
     *         requested bucket has no current snapshot row
     */
    public void requireCompleteForLockedPeriods(WindowGate gate, LocalDate fromDate, LocalDate toDate) {
        if (!gate.intersectsLockedPeriod() || gate.bucketsWithoutSnapshot() == 0) {
            return;
        }
        throw new LockedPeriodIncompleteException(gate, fromDate, toDate);
    }

    /**
     * Derives the export's date window from its pushed filters.
     *
     * <p>Only the two declared bucket filters can establish a window; a window that cannot be derived is
     * refused rather than defaulted, because a finance export with an unbounded window is a full-tenant
     * aggregation, which is the thing FR-8.2 exists to prevent.
     */
    public static LocalDate[] windowOf(List<ExportFilter> filters) {
        LocalDate from = null;
        LocalDate to = null;
        for (ExportFilter filter : filters) {
            String key = filter.field().key();
            if (com.firstelement.exports.catalog.ReportFilterField.USAGE_BUCKET_FROM.key().equals(key)
                && !filter.values().isEmpty()) {
                from = parseDate(key, String.valueOf(filter.values().get(0)));
            }
            if (com.firstelement.exports.catalog.ReportFilterField.USAGE_BUCKET_TO.key().equals(key)
                && !filter.values().isEmpty()) {
                to = parseDate(key, String.valueOf(filter.values().get(0)));
            }
        }
        if (from == null || to == null) {
            throw new FilterNotPushableException(
                com.firstelement.exports.catalog.ReportFilterField.USAGE_BUCKET_FROM.key(),
                "a finance export must declare both its window bounds (usage.bucketFrom and "
                    + "usage.bucketTo). An unbounded finance export is an organisation-wide aggregation, "
                    + "which is exactly what FR-8.2 forbids and what the FinanceModule row cap exists to "
                    + "bound.");
        }
        if (from.isAfter(to)) {
            throw new FilterNotPushableException(
                com.firstelement.exports.catalog.ReportFilterField.USAGE_BUCKET_TO.key(),
                "the window's end (" + to + ") is before its start (" + from + "), so it would enumerate "
                    + "no buckets and report an empty result for a period that has usage");
        }
        return new LocalDate[] {from, to};
    }

    private static LocalDate parseDate(String key, String value) {
        try {
            return LocalDate.parse(value.trim());
        } catch (java.time.format.DateTimeParseException e) {
            throw new FilterNotPushableException(key,
                "a date filter must be an ISO date (yyyy-MM-dd), got '" + value + "'");
        }
    }

    private MapSqlParameterSource scopedWindowParameters(
        com.firstelement.security.query.TenantScope scope, UUID projectId, LocalDate fromDate,
        LocalDate toDate) {
        return com.firstelement.security.query.ScopedWhere.of(scope).bindNamed(java.util.Map.of())
            .addValue("projectId", projectId, java.sql.Types.OTHER)
            .addValue("fromDate", fromDate)
            .addValue("toDate", toDate);
    }

    /**
     * A locked period whose window is not fully snapshotted.
     *
     * <p>A refusal rather than a partial file. The message names the period and the missing bucket count,
     * because the fix is an operator action (recompute the snapshot, or narrow the window) and not
     * something a caller can retry.
     */
    public static class LockedPeriodIncompleteException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final WindowGate gate;

        public LockedPeriodIncompleteException(WindowGate gate, LocalDate fromDate, LocalDate toDate) {
            super("The requested window " + fromDate + ".." + toDate + " intersects LOCKED finance "
                + "period(s) " + gate.lockedPeriodKeys() + " but "
                + gate.bucketsWithoutSnapshot() + " bucket(s) in it have no current usage snapshot. A "
                + "locked period's figures are approved, so this export is refused rather than "
                + "printing a window that silently disagrees with what was signed off. Recompute the "
                + "snapshots or narrow the window.");
            this.gate = gate;
        }

        /** The gate's answer, for the job's failure message and the tests. */
        public WindowGate gate() {
            return gate;
        }

        /** The stable error code recorded on the job. */
        public String code() {
            return "FINANCE_PERIOD_LOCKED_INCOMPLETE";
        }
    }

    /** The empty gate for a non-finance subject. */
    public static WindowGate notApplicable() {
        return new WindowGate(List.of(), List.of(), null, null, 0L);
    }

    /** A period's other-language name, for a bilingual footer. */
    public static String otherLanguageName(PeriodState period, String locale) {
        boolean arabic = com.firstelement.exports.catalog.ReportField.isArabic(locale);
        return arabic ? period.nameEn() : period.nameAr();
    }

    /** An optional period, for a caller that wants the gate without the refusal. */
    public static Optional<PeriodState> lockedFirst(List<PeriodState> periods) {
        return periods.stream().filter(PeriodState::isLocked).findFirst();
    }

    /** The locale key used by the gate, for a message. */
    public static String localeKey(Locale locale) {
        return locale == null ? "en" : locale.toLanguageTag();
    }
}