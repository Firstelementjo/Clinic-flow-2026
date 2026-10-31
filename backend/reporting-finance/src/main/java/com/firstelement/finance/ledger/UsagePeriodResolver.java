package com.firstelement.finance.ledger;

import com.firstelement.finance.catalog.FinanceMetricCatalogue;
import com.firstelement.security.TenantContext;
import com.firstelement.security.query.TenantScope;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/**
 * Decides which billing bucket a usage event falls in, and which finance period — if any — is
 * currently accepting it.
 *
 * <h2>Why the bucket is derived here and not in the writer</h2>
 *
 * <p>{@code usage_events.periodStart}/{@code periodEnd} are stored, immutable and
 * {@code CHECK}-constrained to contain {@code occurredAt}. Three different things depend on them: the
 * rollup's bucket query, the reconciliation's ledger-side recomputation, and the closed-period guard.
 * If each derived them separately they would eventually disagree, and the disagreement would show up
 * as a variance in a reconciliation a week later with no way to say which of the three was wrong.
 *
 * <p>So there is one derivation, and it is configurable because a tenant's billing configuration is a
 * fact about that tenant: monthly for a monthly subscriber, daily for a daily one.
 *
 * <h2>The closed-period question, which is a real decision</h2>
 *
 * <p>A usage event that occurred <em>inside</em> a period which has since been locked is a genuine
 * case — a webhook that arrived a minute after midnight, a provider that reported yesterday's
 * tokens this morning. ADR 0007 rule 4 says a closed period changes only through an approved,
 * audited correction, and {@code usage_rollup_snapshots_locked_period_guard} already refuses a new
 * snapshot for a {@code LOCKED} period.
 *
 * <p>Three things are therefore decided here, and each is a policy rather than a fact:
 *
 * <ul>
 *   <li>{@link BucketPolicy#MONTHLY} / {@link BucketPolicy#DAILY} — the bucket width.</li>
 *   <li>{@link LateArrivalPolicy#RECORD_UNATTACHED} — the usage event is still recorded, because
 *       discarding evidence of real consumption is worse than recording it outside the closed period.
 *       Its {@code billingPeriodId} stays {@code null}, so it cannot alter the locked period, and
 *       {@link #attribution} marks it so the correction workflow and the reconciliation both see
 *       it. The alternative,
 *       {@link LateArrivalPolicy#RECORD_ATTACHED}, records it against the closed period and is
 *       <em>refused</em> here rather than trusted, because it would silently change a locked
 *       period.</li>
 *   <li>Whether a period is closed at all is read from {@code finance_periods}, and
 *       {@code IN_REVIEW} counts: a period under review is not yet locked but new usage against it
 *       would move the numbers somebody is reviewing.</li>
 * </ul>
 *
 * <h2>Why no lookup happens for the common case</h2>
 *
 * <p>A monthly-bucketed tenant whose periods are configured will resolve a period on every write, and
 * that is one indexed read. A tenant with no configured periods at all — the state before finance is
 * switched on for them — still gets correct usage, because the bucket is derived from the
 * configuration rather than from the period table. Usage does not depend on rate cards being set up.
 */
public class UsagePeriodResolver {

    /** How wide a billing bucket is. Configurable per deployment, not per request. */
    public enum BucketPolicy {
        /** {@code [firstOfMonth, lastOfMonth]}. The default: billing periods are monthly. */
        MONTHLY,
        /** {@code [date, date]}. */
        DAILY
    }

    /** What to do with usage that occurred inside a period which is no longer open. */
    public enum LateArrivalPolicy {

        /**
         * Record the event with no {@code billingPeriodId}.
         *
         * <p>The default, and the honest option. The usage is real — a provider billed for it, a
         * carrier metered it — and throwing it away would make the ledger disagree with the invoice
         * by exactly the amount that arrived late. Attaching it to the closed period is what ADR 0007
         * rule 4 forbids, so it is left unattached and marked
         * {@link UsageAttribution#LATE_ARRIVAL_UNATTACHED}, and the approved correction is how it
         * reaches the period.
         */
        RECORD_UNATTACHED,

        /**
         * Refuse the write.
         *
         * <p>Available for a tenant that would rather have no usage than unattributed usage. The
         * trade is explicit: the ledger then under-reports against the provider invoice, and the
         * reconciliation variance is the visible price of that choice.
         */
        REFUSE
    }

    /**
     * How a usage event relates to a finance period.
     *
     * @param periodId     the finance period the usage is billed to, or {@code null}
     * @param periodStatus that period's status at write time, or {@code null} when unattached
     */
    public record UsageAttribution(UUID periodId, String periodStatus) {

        /** Open, and billed to the period. The normal case. */
        public static UsageAttribution open(UUID periodId) {
            return new UsageAttribution(periodId, "OPEN");
        }

        /**
         * The usage occurred inside a period that has since closed or entered review.
         *
         * <p>Recorded without a {@code periodId}, so it cannot change the closed period. The
         * reconciliation will see it as a variance against the period the correction belongs to, which
         * is the mechanism ADR 0007 rule 4 prescribes.
         */
        public static UsageAttribution lateArrival() {
            return new UsageAttribution(null, "LATE_ARRIVAL_UNATTACHED");
        }

        /** No finance period covers this date; the usage is still recorded and correctly bucketed. */
        public static UsageAttribution noConfiguredPeriod() {
            return new UsageAttribution(null, "NO_CONFIGURED_PERIOD");
        }

        /** Whether the event is attached to a finance period at all. */
        public boolean isAttached() {
            return periodId != null;
        }

        /** Whether the usage landed after its period closed. */
        public boolean isLateArrival() {
            return "LATE_ARRIVAL_UNATTACHED".equals(periodStatus);
        }
    }

    private final BucketPolicy bucketPolicy;
    private final LateArrivalPolicy lateArrivalPolicy;
    private final FinancePeriodLookup periods;

    /**
     * The one thing this class needs from the period tables.
     *
     * <p>An interface rather than a repository dependency so the bucket arithmetic is unit-testable
     * without a database, and so the dependency is a single documented call. It returns
     * {@code Optional.empty()} when no configured period covers the date, which is a legitimate state
     * and not an error.
     */
    @FunctionalInterface
    public interface FinancePeriodLookup {

        /**
         * @param organizationId the tenant
         * @param date          the usage date
         * @return the period covering {@code date} with its status, or empty when none is configured
         */
        Optional<PeriodStatusRef> periodCovering(UUID organizationId, LocalDate date);

        /** The pair this lookup answers with: a period id and its current status. */
        record PeriodStatusRef(UUID periodId, String status) {
        }
    }

    public UsagePeriodResolver(BucketPolicy bucketPolicy, LateArrivalPolicy lateArrivalPolicy,
                               FinancePeriodLookup periods) {
        this.bucketPolicy = bucketPolicy == null ? BucketPolicy.MONTHLY : bucketPolicy;
        this.lateArrivalPolicy =
            lateArrivalPolicy == null ? LateArrivalPolicy.RECORD_UNATTACHED : lateArrivalPolicy;
        this.periods = periods;
    }

    /** The bucket this instant falls in. {@code occurredAt} is a UTC {@link LocalDateTime}. */
    public LocalDate bucketStart(LocalDateTime occurredAt) {
        return switch (bucketPolicy) {
            case MONTHLY -> occurredAt.toLocalDate().withDayOfMonth(1);
            case DAILY -> occurredAt.toLocalDate();
        };
    }

    /** The inclusive last day of the bucket {@code bucketStart} names. */
    public LocalDate bucketEnd(LocalDate bucketStart) {
        return switch (bucketPolicy) {
            case MONTHLY -> bucketStart.withDayOfMonth(bucketStart.lengthOfMonth());
            case DAILY -> bucketStart;
        };
    }

    /** The bucket this instant falls in, as a pair. */
    public Bucket bucketOf(LocalDateTime occurredAt) {
        LocalDate start = bucketStart(occurredAt);
        return new Bucket(start, bucketEnd(start));
    }

    /**
     * Resolves which finance period this usage belongs to.
     *
     * @throws FinancePeriodClosedException when a late arrival arrives and the policy is
     *         {@link LateArrivalPolicy#REFUSE}
     */
    public UsageAttribution attribute(TenantScope scope, LocalDateTime occurredAt) {
        LocalDate usageDate = occurredAt.toLocalDate();
        Optional<FinancePeriodLookup.PeriodStatusRef> covering =
            periods == null ? Optional.empty()
                : periods.periodCovering(scope.organizationId(), usageDate);

        if (covering.isEmpty()) {
            return UsageAttribution.noConfiguredPeriod();
        }
        FinancePeriodLookup.PeriodStatusRef period = covering.get();
        if (isAccepting(period.status())) {
            return UsageAttribution.open(period.periodId());
        }
        if (lateArrivalPolicy == LateArrivalPolicy.REFUSE) {
            throw new FinancePeriodClosedException(scope.organizationId(), period.periodId(),
                period.status(), usageDate);
        }
        return UsageAttribution.lateArrival();
    }

    /**
     * Whether a period in this status still accepts new usage.
     *
     * <p>{@code OPEN} only. {@code IN_REVIEW} is refused because the numbers under review would move
     * underneath the reviewer, and {@code REOPENED} is refused for the same reason a lock is: the
     * period is being corrected and a new usage event silently entering it defeats the correction.
     * The path for late usage into either is {@link LateArrivalPolicy#RECORD_UNATTACHED} plus an
     * approved adjustment.
     */
    static boolean isAccepting(String periodStatus) {
        return "OPEN".equals(periodStatus);
    }

    /** A billing bucket. Both ends are inclusive, matching the schema. */
    public record Bucket(LocalDate start, LocalDate end) {
    }

    /** The current UTC wall clock in the shape the schema stores. */
    public static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    /** The start of a UTC day, for the seat and storage meters. */
    public static LocalDateTime startOfDay(LocalDate date) {
        return date.atTime(LocalTime.MIN);
    }

    /**
     * A {@link TenantContext}-derived principal, for callers that already hold one.
     *
     * <p>Exposed on this class so a module emitting usage does not have to know that a principal is
     * also a tenant-scope concern.
     */
    public static LedgerPrincipal principalOf(TenantContext context) {
        return LedgerPrincipal.from(context);
    }

    /** The catalogue check a writer applies before it accepts an event. */
    public static void requireCatalogueDeclared(UsageEvent event) {
        if (!FinanceMetricCatalogue.metricForSourceEventType(event.sourceEventType())
            .dimension().equals(event.dimension())) {
            throw new IllegalArgumentException(
                "Source event type '" + event.sourceEventType() + "' does not produce "
                    + event.dimension());
        }
    }
}
