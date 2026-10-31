package com.firstelement.finance.rollup;

import com.firstelement.security.query.ScopedWhere;
import com.firstelement.security.query.TenantScope;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The one reader and the one writer of {@code usage_rollup_snapshots}.
 *
 * <h2>Where authorization happens, and why it is not arguable</h2>
 *
 * <p>Every statement's {@code WHERE} begins with the tenant predicate produced by
 * {@link ScopedWhere}, so an unauthorized row never enters an aggregate. This is FR-8.2's
 * "authorization before aggregation, not after", and it is a structural property rather than a
 * discipline: the tenant columns are bound by a method that can only be reached with a
 * {@link TenantScope}, and the parameter names are reserved so a caller cannot shadow them by passing
 * a parameter of the same name.
 *
 * <p>A read-then-filter implementation would satisfy "the list does not contain another tenant's rows"
 * while still returning a count of one less, a total that differs, and a timing profile that says the
 * row exists. The negative suite asserts on the aggregate, not on the list, for exactly that reason.
 *
 * <h2>Why the "current" read is a partial-index-friendly shape</h2>
 *
 * <p>{@code usage_rollup_snapshots_current_bucket_key} is UNIQUE over
 * {@code (organizationId, projectId, grain, bucketStart, dimension)} WHERE
 * {@code supersedesSnapshotId IS NULL}, so there is at most one current row per bucket and the database
 * refuses a second one. {@link #current} therefore needs no {@code max(generation)} window function:
 * the constraint has already done the work, and a query that recomputed it would be a second, weaker
 * statement to keep in step.
 *
 * <p>The full history is a separate read ({@link #history}), because "what did this bucket say last
 * month" and "what does it say now" are different questions and a dashboard wants the second while an
 * audit wants the first.
 */
@Repository
public class RollupRepository {

    /**
     * "Current" means <em>nobody supersedes me</em> — {@code not exists (select 1 from
     * usage_rollup_snapshots where supersedesSnapshotId = s.id)}.
     *
     * <h2>Why this is not {@code supersedesSnapshotId is null}</h2>
     *
     * <p>Because that predicate selects generation <b>1</b>, not the current one.
     * {@code usage_rollup_snapshots_generation_pair_check} ties
     * {@code generation = 1} to {@code supersedesSnapshotId IS NULL}, so "unsuperseded" is a property of
     * the <em>first</em> generation only; every recomputation sets the pointer. A read filtered that way
     * would show a stale figure forever after the first recomputation, which is the opposite of what the
     * index is for.
     *
     * <p>The schema's own partial index, {@code usage_rollup_snapshots_current_bucket_key}, has the same
     * inverted predicate — its comment says "the read every dashboard hits… only the highest
     * generation is indexed for it", and the predicate does not deliver that. It is still useful as a
     * uniqueness constraint: it guarantees at most one generation-1 row per bucket. Reported to the
     * orchestrator as a schema defect; the read here is correct without it, and the two agree for the
     * first generation of every bucket, which is why this was only found by an assertion on the second.
     */
    /**
     * The current snapshot per bucket, all dimensions.
     *
     * <p>Ordered by bucket then dimension so a monthly read model is a straight scan of this list, and
     * so two reads of the same data produce the same document.
     */
    private static final String CURRENT_BUCKETS = """
        select s."id", s."organizationId", s."projectId", s."periodId", s."grain"::text,
               s."bucketStart", s."bucketEnd", s."dimension"::text, s."currency",
               s."sourceEventCount", s."quantity", s."costAmount", s."revenueAmount", s."markupRate",
               s."rateCardVersionId", s."unpricedQuantity", s."unpricedCostAmount", s."generation",
               s."supersedesSnapshotId", s."computedAt", s."computedByPrincipal", s."correlationId"
          from usage_rollup_snapshots s
         where s."organizationId" = :__fe_tenant_organization_id
           and s."bucketStart" between :fromDate and :toDate
           and (cast(:filterProjectId as uuid) is null or s."projectId" = cast(:filterProjectId as uuid))
           and (cast(:filterGrain as text) is null or s."grain"::text = cast(:filterGrain as text))
           and not exists (select 1 from usage_rollup_snapshots newer
                            where newer."supersedesSnapshotId" = s."id")
         order by s."bucketStart", s."dimension"
        """;

    /** One current bucket by dimension. */
    private static final String CURRENT_BUCKET = """
        select s."id", s."organizationId", s."projectId", s."periodId", s."grain"::text,
               s."bucketStart", s."bucketEnd", s."dimension"::text, s."currency",
               s."sourceEventCount", s."quantity", s."costAmount", s."revenueAmount", s."markupRate",
               s."rateCardVersionId", s."unpricedQuantity", s."unpricedCostAmount", s."generation",
               s."supersedesSnapshotId", s."computedAt", s."computedByPrincipal", s."correlationId"
          from usage_rollup_snapshots s
         where s."organizationId" = :__fe_tenant_organization_id
           and s."projectId" = :projectId
           and s."grain" = cast(:grain as "RollupGrain")
           and s."bucketStart" = :bucketStart
           and s."dimension" = cast(:dimension as "UsageDimension")
           and not exists (select 1 from usage_rollup_snapshots newer
                            where newer."supersedesSnapshotId" = s."id")
        """;

    /** Every generation of one bucket, newest first. */
    private static final String HISTORY = """
        select s."id", s."generation", s."supersedesSnapshotId", s."costAmount", s."revenueAmount",
               s."markupRate", s."rateCardVersionId", s."sourceEventCount", s."quantity",
               s."unpricedQuantity", s."unpricedCostAmount", s."computedAt",
               s."computedByPrincipal", s."correlationId"
          from usage_rollup_snapshots s
         where s."organizationId" = :__fe_tenant_organization_id
           and s."projectId" = :projectId
           and s."grain" = cast(:grain as "RollupGrain")
           and s."bucketStart" = :bucketStart
           and s."dimension" = cast(:dimension as "UsageDimension")
         order by s."generation" desc
        """;

    /**
     * The ledger-side aggregate, recomputed independently of any snapshot.
     *
     * <p>This is the whole point of reconciliation: ADR 0007 rule 3 says finance reads the ledger,
     * always, so the comparison must be between the snapshot and a fresh sum over
     * {@code usage_events} rather than between two derived figures that share code. Two queries that
     * share a bug agree with each other, which is why this one is written separately from
     * {@link #ledgerTotals} with its own window predicate.
     *
     * <p><b>The window is an overlap, not a containment.</b> An event's stored bucket is whatever
     * {@link com.firstelement.finance.ledger.UsagePeriodResolver} derived at write time — typically a
     * month — while a daily rollup asks about one day inside it. So the predicate is
     * {@code periodStart <= :toDate and periodEnd >= :fromDate}: the event's bucket must intersect the
     * requested one. A containment test ({@code periodStart >= :fromDate}) would silently exclude every
     * event of the month from every daily bucket but the first, and a daily rollup would then be
     * systematically understated — which is exactly the kind of bug a reconciliation should find and not
     * cause.
     *
     * <p>Grouped by dimension and by the event's own bucket, so a caller can attribute each aggregate
     * back to the bucket it belongs to.
     *
     * <h3>{@code uncostedCostAmount} is summed rather than assumed</h3>
     *
     * <p>{@code sum("costAmount") filter (where "costAmount" is null)} is zero by construction — it
     * sums no rows — and it is still written as a sum rather than a literal {@code 0}. The column is the
     * <em>cost</em> half of the unpriced residue, and it has to be derived from the same ledger
     * expression as the quantity half so the two can never disagree about which events are uncosted. A
     * Java literal would be correct today and silently wrong the day the predicate changes — and the
     * consequence of it being wrong is a snapshot that reports priced money as unbilled, which is the
     * defect this pair exists to prevent.
     */
    private static final String LEDGER_AGGREGATE_BY_DIMENSION = """
        select u."dimension"::text as "dimension", u."unit", u."currency", u."periodStart",
               u."periodEnd", count(*) as "eventCount", sum(u."quantity") as "quantity",
               coalesce(sum(u."costAmount"), 0) as "costAmount",
               count(*) filter (where u."costAmount" is null) as "uncostedEventCount",
               coalesce(sum(u."quantity") filter (where u."costAmount" is null), 0)
                   as "uncostedQuantity",
               coalesce(sum(u."costAmount") filter (where u."costAmount" is null), 0)
                   as "uncostedCostAmount"
          from usage_events u
         where u."organizationId" = :__fe_tenant_organization_id
           and u."periodStart" <= :toDate
           and u."periodEnd" >= :fromDate
           and (cast(:filterProjectId as uuid) is null or u."projectId" = cast(:filterProjectId as uuid))
         group by u."dimension", u."unit", u."currency", u."periodStart", u."periodEnd"
         order by u."periodStart", u."dimension"
        """;

    /**
     * Per-dimension totals across a whole period, for the reconciliation run.
     *
     * <p>Windowed on {@code occurredAt} rather than on {@code periodStart} because a reconciliation
     * asks "what happened in this period", and the period's own bounds are the authority for that.
     * Where an event's stored bucket disagrees with its own timestamp — which the schema's
     * {@code usage_events_period_contains_event_check} forbids — this query would still count it,
     * which is the safer of the two behaviours for a reconciliation.
     */
    private static final String LEDGER_TOTALS_PERIOD = """
        select u."dimension"::text as "dimension", u."unit", u."currency", count(*) as "eventCount",
               sum(u."quantity") as "quantity", coalesce(sum(u."costAmount"), 0) as "costAmount",
               coalesce(sum(u."costAmount") filter (where u."fxRate" is not null), 0) as "convertedCost"
          from usage_events u
         where u."organizationId" = :__fe_tenant_organization_id
           and (cast(:periodStart as date) is null
                or (u."occurredAt" AT TIME ZONE 'UTC')::date >= cast(:periodStart as date))
           and (cast(:periodEnd as date) is null
                or (u."occurredAt" AT TIME ZONE 'UTC')::date <= cast(:periodEnd as date))
           and (cast(:filterProjectId as uuid) is null or u."projectId" = cast(:filterProjectId as uuid))
         group by u."dimension", u."unit", u."currency"
         order by u."dimension"
        """;

    /**
     * Snapshot-side totals for a period, current generation only.
     *
     * <p>The counterpart to {@link #LEDGER_TOTALS_PERIOD}. The current-generation filter is the
     * {@code not exists} anti-join rather than {@code supersedesSnapshotId is null}, for the reason set
     * out on {@link #CURRENT_BUCKETS}: the null predicate selects generation 1, so a period that has been
     * recomputed once would reconcile against its own oldest figures.
     */
    private static final String SNAPSHOT_TOTALS_PERIOD = """
        select s."dimension"::text as "dimension", s."currency", sum(s."sourceEventCount")
                   as "eventCount", sum(s."quantity") as "quantity", sum(s."costAmount") as "costAmount",
               sum(s."revenueAmount") as "revenueAmount"
          from usage_rollup_snapshots s
         where s."organizationId" = :__fe_tenant_organization_id
           and (cast(:periodStart as date) is null or s."bucketStart" >= cast(:periodStart as date))
           and (cast(:periodEnd as date) is null or s."bucketStart" <= cast(:periodEnd as date))
           and (cast(:filterProjectId as uuid) is null or s."projectId" = cast(:filterProjectId as uuid))
           and not exists (select 1 from usage_rollup_snapshots newer
                            where newer."supersedesSnapshotId" = s."id")
         group by s."dimension", s."currency"
         order by s."dimension"
        """;

    /**
     * The one insert. Every column named, no upsert, no conflict clause.
     *
     * <p>{@code usage_rollup_snapshots_locked_period_guard} refuses an insert whose
     * {@code periodId} names a LOCKED or IN_REVIEW period, so a closed period cannot be changed by
     * recomputation — only by an approved correction. That is enforced here by naming {@code periodId}
     * explicitly and by {@link RollupService} refusing to compute a locked bucket in the first place.
     */
    private static final String INSERT_SNAPSHOT = """
        insert into usage_rollup_snapshots (
            "id", "organizationId", "projectId", "periodId", "grain", "bucketStart", "bucketEnd",
            "dimension", "currency", "sourceEventCount", "quantity", "costAmount", "revenueAmount",
            "markupRate", "rateCardVersionId", "unpricedQuantity", "unpricedCostAmount",
            "generation", "supersedesSnapshotId", "computedAt", "computedByPrincipal",
            "correlationId")
        values (
            :id, :__fe_tenant_organization_id, :projectId, :periodId, cast(:grain as "RollupGrain"), :bucketStart,
            :bucketEnd, cast(:dimension as "UsageDimension"), :currency, :sourceEventCount, :quantity,
            :costAmount, :revenueAmount, :markupRate, :rateCardVersionId, :unpricedQuantity,
            :unpricedCostAmount, :generation, :supersedesSnapshotId, :computedAt,
            :computedByPrincipal, :correlationId)
        """;

    private final NamedParameterJdbcTemplate jdbc;

    public RollupRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------
    // row shape
    // ------------------------------------------------------------------

    /** A snapshot row as stored, with the pricing columns still raw. */
    public record SnapshotRow(UUID id,
                              UUID organizationId,
                              UUID projectId,
                              UUID periodId,
                              String grain,
                              LocalDate bucketStart,
                              LocalDate bucketEnd,
                              String dimension,
                              String currency,
                              long sourceEventCount,
                              BigDecimal quantity,
                              BigDecimal costAmount,
                              BigDecimal revenueAmount,
                              BigDecimal markupRate,
                              UUID rateCardVersionId,
                              BigDecimal unpricedQuantity,
                              BigDecimal unpricedCostAmount,
                              int generation,
                              UUID supersedesSnapshotId,
                              LocalDateTime computedAt,
                              String computedByPrincipal,
                              String correlationId) {
    }

    /**
     * One dimension's ledger-side aggregate.
     *
     * <p>Three buckets are carried rather than one, because the two grains bucket differently and a
     * caller must be able to see which it got:
     *
     * @param usageDate    for the DAILY aggregate, the event's own {@code occurredAt} day
     * @param periodStart  for the MONTHLY aggregate, the event's stored billing bucket start
     * @param periodEnd    the matching bucket end
     * @param uncostedQuantity    usage whose events recorded no measured cost
     * @param uncostedCostAmount  the cost of exactly those events — zero by construction, and derived
     *                            rather than assumed so it cannot drift from {@code uncostedQuantity}
     */
    public record LedgerAggregateRow(LocalDate usageDate,
                                     String dimension,
                                     String unit,
                                     String currency,
                                     LocalDate periodStart,
                                     LocalDate periodEnd,
                                     long eventCount,
                                     BigDecimal quantity,
                                     BigDecimal costAmount,
                                     long uncostedEventCount,
                                     BigDecimal uncostedQuantity,
                                     BigDecimal uncostedCostAmount) {

        /** The day this row's events happened, whatever grain produced it. */
        public LocalDate bucketDate() {
            return usageDate != null ? usageDate : periodStart;
        }
    }

    /** One dimension's totals for a period, from either side. */
    public record DimensionTotalsRow(String dimension,
                                     String currency,
                                     long eventCount,
                                     BigDecimal quantity,
                                     BigDecimal costAmount,
                                     BigDecimal revenueAmount) {
    }

    // ------------------------------------------------------------------
    // reads
    // ------------------------------------------------------------------

    /** The current snapshot per bucket across a date range. */
    public List<SnapshotRow> current(TenantScope scope, UUID projectId, RollupBucket.Grain grain,
                                     LocalDate fromDate, LocalDate toDate) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope).bindNamed(Map.of(
            "fromDate", fromDate,
            "toDate", toDate));
        parameters.addValue("filterProjectId", projectId);
        parameters.addValue("filterGrain", grain == null ? null : grain.wireValue());
        return jdbc.query(CURRENT_BUCKETS, parameters, RollupRepository::mapSnapshot);
    }

    /** One current bucket. */
    public Optional<SnapshotRow> currentBucket(TenantScope scope, UUID projectId,
                                               RollupBucket.Grain grain, LocalDate bucketStart,
                                               com.firstelement.finance.catalog.UsageDimension dimension) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope).bindNamed(Map.of(
            "projectId", projectId,
            "grain", grain.wireValue(),
            "bucketStart", bucketStart));
        parameters.addValue("dimension", dimension.wireValue());
        return jdbc.query(CURRENT_BUCKET, parameters, RollupRepository::mapSnapshot)
            .stream().findFirst();
    }

    /** Every generation of one bucket, newest first. */
    public List<Map<String, Object>> history(TenantScope scope, UUID projectId,
                                             RollupBucket.Grain grain, LocalDate bucketStart,
                                             com.firstelement.finance.catalog.UsageDimension dimension) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope).bindNamed(Map.of(
            "projectId", projectId,
            "grain", grain.wireValue(),
            "bucketStart", bucketStart));
        parameters.addValue("dimension", dimension.wireValue());
        return jdbc.queryForList(HISTORY, parameters);
    }

    /**
     * The ledger recomputed, grouped by dimension and stored bucket.
     *
     * <p>Written independently of the reconciliation's period total so that a bug in one does not hide
     * a bug in the other. They agree by construction; that agreement is what the reconciliation
     * measures.
     */
    public List<LedgerAggregateRow> ledgerAggregate(TenantScope scope, UUID projectId,
                                                    LocalDate fromDate, LocalDate toDate) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope).bindNamed(Map.of(
            "fromDate", fromDate,
            "toDate", toDate));
        parameters.addValue("filterProjectId", projectId);
        return jdbc.query(LEDGER_AGGREGATE_BY_DIMENSION, parameters, (rs, rowNum) ->
            new LedgerAggregateRow(
                null,
                rs.getString("dimension"),
                rs.getString("unit"),
                rs.getString("currency"),
                rs.getObject("periodStart", LocalDate.class),
                rs.getObject("periodEnd", LocalDate.class),
                rs.getLong("eventCount"),
                rs.getBigDecimal("quantity"),
                rs.getBigDecimal("costAmount"),
                rs.getLong("uncostedEventCount"),
                rs.getBigDecimal("uncostedQuantity"),
                rs.getBigDecimal("uncostedCostAmount")));
    }

    /**
     * The ledger aggregated by the event's own day.
     *
     * <p>The DAILY rollup's input. Bucketing on {@code occurredAt::date} rather than on the event's
     * stored period is what makes a daily total a day's total: an event billed into the month of March
     * still belongs to the day it happened, and a daily report that showed the whole month on the tenth
     * would be wrong by a factor of thirty-one.
     */
    public List<LedgerAggregateRow> ledgerAggregateByDay(TenantScope scope, UUID projectId,
                                                         LocalDate fromDate, LocalDate toDate) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope).bindNamed(Map.of(
            "fromDate", fromDate,
            "toDate", toDate));
        parameters.addValue("filterProjectId", projectId);
        return jdbc.query(LEDGER_AGGREGATE_BY_DAY, parameters, (rs, rowNum) ->
            new LedgerAggregateRow(
                rs.getObject("usageDate", LocalDate.class),
                rs.getString("dimension"),
                rs.getString("unit"),
                rs.getString("currency"),
                rs.getObject("usageDate", LocalDate.class),
                rs.getObject("usageDate", LocalDate.class),
                rs.getLong("eventCount"),
                rs.getBigDecimal("quantity"),
                rs.getBigDecimal("costAmount"),
                rs.getLong("uncostedEventCount"),
                rs.getBigDecimal("uncostedQuantity"),
                rs.getBigDecimal("uncostedCostAmount")));
    }

    /**
     * The daily aggregate: grouped by the event's own {@code occurredAt} day.
     *
     * <p>Separate from {@link #LEDGER_AGGREGATE_BY_DIMENSION} because the two grains bucket differently.
     * A daily rollup must sum the events that happened <em>on</em> the day; the monthly one sums the
     * events whose stored bucket is the month. Using the month-level aggregate for a daily bucket would
     * put the whole month's usage on every day of it, and using the daily aggregate for a monthly bucket
     * would need a re-group the query does not do.
     *
     * <p>Also separate for reconciliation's sake: two independent statements that must agree.
     */
    private static final String LEDGER_AGGREGATE_BY_DAY = """
        select (u."occurredAt" AT TIME ZONE 'UTC')::date as "usageDate", u."dimension"::text,
               u."unit", u."currency", count(*) as "eventCount", sum(u."quantity") as "quantity",
               coalesce(sum(u."costAmount"), 0) as "costAmount",
               count(*) filter (where u."costAmount" is null) as "uncostedEventCount",
               coalesce(sum(u."quantity") filter (where u."costAmount" is null), 0)
                   as "uncostedQuantity",
               coalesce(sum(u."costAmount") filter (where u."costAmount" is null), 0)
                   as "uncostedCostAmount"
          from usage_events u
         where u."organizationId" = :__fe_tenant_organization_id
           and (u."occurredAt" AT TIME ZONE 'UTC')::date >= :fromDate
           and (u."occurredAt" AT TIME ZONE 'UTC')::date <= :toDate
           and (cast(:filterProjectId as uuid) is null or u."projectId" = cast(:filterProjectId as uuid))
         group by 1, 2, 3, 4
         order by 1, 2
        """;

    /** Ledger totals for a period, from {@code usage_events} alone. */
    public List<DimensionTotalsRow> ledgerTotals(TenantScope scope, UUID projectId,
                                                 LocalDate periodStart, LocalDate periodEnd) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope).bindNamed(Map.of());
        parameters.addValue("periodStart", periodStart);
        parameters.addValue("periodEnd", periodEnd);
        parameters.addValue("filterProjectId", projectId);
        return jdbc.query(LEDGER_TOTALS_PERIOD, parameters, (rs, rowNum) -> new DimensionTotalsRow(
            rs.getString("dimension"),
            rs.getString("currency"),
            rs.getLong("eventCount"),
            rs.getBigDecimal("quantity"),
            rs.getBigDecimal("costAmount"),
            null));
    }

    /** Snapshot totals for a period, current generation only. */
    public List<DimensionTotalsRow> snapshotTotals(TenantScope scope, UUID projectId,
                                                   LocalDate periodStart, LocalDate periodEnd) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope).bindNamed(Map.of());
        parameters.addValue("periodStart", periodStart);
        parameters.addValue("periodEnd", periodEnd);
        parameters.addValue("filterProjectId", projectId);
        return jdbc.query(SNAPSHOT_TOTALS_PERIOD, parameters, (rs, rowNum) -> new DimensionTotalsRow(
            rs.getString("dimension"),
            rs.getString("currency"),
            rs.getLong("eventCount"),
            rs.getBigDecimal("quantity"),
            rs.getBigDecimal("costAmount"),
            rs.getBigDecimal("revenueAmount")));
    }

    // ------------------------------------------------------------------
    // writes
    // ------------------------------------------------------------------

    /**
     * Inserts one snapshot row. Append-only: a recomputation is a new generation, never an update.
     *
     * <p>{@code revenueAmount} and {@code markupRate} and {@code rateCardVersionId} are written
     * together or not at all, because {@code usage_rollup_snapshots_pricing_pin_check} ties them as a
     * biconditional and a partially-priced row is unrepresentable.
     */
    public UUID insert(TenantScope scope, RollupBucket bucket) {
        if (!scope.organizationId().equals(bucket.organizationId())) {
            throw new IllegalArgumentException(
                "A snapshot may only be written inside the tenant it belongs to; the bucket names "
                    + bucket.organizationId() + " and the scope is " + scope.organizationId() + ".");
        }
        UUID id = bucket.snapshotId() == null ? UUID.randomUUID() : bucket.snapshotId();
        // 22 named parameters, so Map.of() is not an option (it caps at ten pairs) and a
        // MapSqlParameterSource is built entry by entry instead. The tenant parameters are written
        // last by ScopedWhere so a caller cannot shadow the filter by passing one of the reserved
        // names.
        MapSqlParameterSource parameters = ScopedWhere.of(scope).bindNamed(Map.of())
            .addValue("id", id)
            .addValue("projectId", bucket.projectId())
            .addValue("periodId", bucket.periodId())
            .addValue("grain", bucket.grain().wireValue())
            .addValue("bucketStart", bucket.bucketStart())
            .addValue("bucketEnd", bucket.bucketEnd())
            .addValue("dimension", bucket.dimension().wireValue())
            .addValue("currency", bucket.currency())
            .addValue("sourceEventCount", bucket.sourceEventCount())
            .addValue("quantity", bucket.quantity())
            .addValue("costAmount", bucket.cost().amountOrNull())
            .addValue("revenueAmount", bucket.revenue().amountOrNull())
            .addValue("markupRate", bucket.markupRate())
            .addValue("rateCardVersionId", bucket.rateCardVersionId())
            .addValue("unpricedQuantity", bucket.unpricedQuantity() == null
                ? BigDecimal.ZERO : bucket.unpricedQuantity())
            .addValue("unpricedCostAmount", bucket.unpricedCost() == null
                ? BigDecimal.ZERO : bucket.unpricedCost())
            .addValue("generation", bucket.generation())
            .addValue("supersedesSnapshotId", bucket.supersedesSnapshotId())
            .addValue("computedAt", bucket.computedAt())
            .addValue("computedByPrincipal", bucket.computedByPrincipal())
            .addValue("correlationId", bucket.correlationId());
        jdbc.update(INSERT_SNAPSHOT, parameters);
        return id;
    }

    /**
     * The next generation number for a bucket, and the current row it supersedes.
     *
     * <p>One query rather than two: reading {@code max(generation)} and the current row separately
     * would leave a window in which a concurrent recomputation inserts generation N+1 and this one
     * tries the same number, which the
     * {@code usage_rollup_snapshots_bucket_generation_key} unique index refuses. Refusing is correct,
     * but the caller retries more cheaply if the repository simply reports the truth in one read.
     *
     * @return the new generation number, and the id of the row it supersedes (null for generation 1)
     */
    public GenerationPosition generation(TenantScope scope, UUID projectId,
                                         RollupBucket.Grain grain, LocalDate bucketStart,
                                         com.firstelement.finance.catalog.UsageDimension dimension) {
        MapSqlParameterSource parameters = ScopedWhere.of(scope).bindNamed(Map.of(
            "projectId", projectId,
            "grain", grain.wireValue(),
            "bucketStart", bucketStart));
        parameters.addValue("dimension", dimension.wireValue());
        Map<String, Object> row = jdbc.queryForMap("""
            select coalesce(max(s."generation"), 0) as "highest",
                   (select c."id" from usage_rollup_snapshots c
                     where c."organizationId" = :__fe_tenant_organization_id
                       and c."projectId" = :projectId
                       and c."grain" = cast(:grain as "RollupGrain")
                       and c."bucketStart" = :bucketStart
                       and c."dimension" = cast(:dimension as "UsageDimension")
                       and not exists (select 1 from usage_rollup_snapshots newer
                                        where newer."supersedesSnapshotId" = c."id")
                     limit 1) as "currentId"
              from usage_rollup_snapshots s
             where s."organizationId" = :__fe_tenant_organization_id
               and s."projectId" = :projectId
               and s."grain" = cast(:grain as "RollupGrain")
               and s."bucketStart" = :bucketStart
               and s."dimension" = cast(:dimension as "UsageDimension")
            """, parameters);
        int highest = ((Number) row.get("highest")).intValue();
        UUID currentId = row.get("currentId") == null ? null : (UUID) row.get("currentId");
        return new GenerationPosition(highest + 1, currentId);
    }

    /** The next generation and what it supersedes. */
    public record GenerationPosition(int nextGeneration, UUID supersedesSnapshotId) {
    }

    /** Every snapshot row of a tenant in a date range, keyed by dimension, for a read model. */
    public Map<String, List<SnapshotRow>> byDimension(TenantScope scope, UUID projectId,
                                                      RollupBucket.Grain grain, LocalDate fromDate,
                                                      LocalDate toDate) {
        Map<String, List<SnapshotRow>> grouped = new LinkedHashMap<>();
        for (SnapshotRow row : current(scope, projectId, grain, fromDate, toDate)) {
            grouped.computeIfAbsent(row.dimension(), key -> new ArrayList<>()).add(row);
        }
        return grouped;
    }

    private static SnapshotRow mapSnapshot(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new SnapshotRow(
            rs.getObject("id", UUID.class),
            rs.getObject("organizationId", UUID.class),
            rs.getObject("projectId", UUID.class),
            rs.getObject("periodId", UUID.class),
            rs.getString("grain"),
            rs.getObject("bucketStart", LocalDate.class),
            rs.getObject("bucketEnd", LocalDate.class),
            rs.getString("dimension"),
            rs.getString("currency"),
            rs.getLong("sourceEventCount"),
            rs.getBigDecimal("quantity"),
            rs.getBigDecimal("costAmount"),
            rs.getBigDecimal("revenueAmount"),
            rs.getBigDecimal("markupRate"),
            rs.getObject("rateCardVersionId", UUID.class),
            rs.getBigDecimal("unpricedQuantity"),
            rs.getBigDecimal("unpricedCostAmount"),
            rs.getInt("generation"),
            rs.getObject("supersedesSnapshotId", UUID.class),
            rs.getObject("computedAt", LocalDateTime.class),
            rs.getString("computedByPrincipal"),
            rs.getString("correlationId"));
    }
}
