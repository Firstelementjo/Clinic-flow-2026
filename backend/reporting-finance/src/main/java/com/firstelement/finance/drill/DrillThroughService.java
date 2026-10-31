package com.firstelement.finance.drill;

import com.firstelement.finance.catalog.UsageDimension;
import com.firstelement.finance.money.Money;
import com.firstelement.finance.rollup.RollupRepository;
import com.firstelement.security.TenantContext;
import com.firstelement.security.annotation.RequirePermission;
import com.firstelement.security.permission.AuthorizationService;
import com.firstelement.security.permission.Permission;
import com.firstelement.security.permission.PolicyResource;
import com.firstelement.security.query.TenantQueryGuard;
import com.firstelement.security.query.TenantScope;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The drill-through: from a total, to the snapshot that produced it, to the immutable events that fed
 * it, with the rate named at every step.
 *
 * <h2>The three questions a total has to answer, and the chain that answers them</h2>
 *
 * <ol>
 *   <li><b>"Which snapshot is this number from?"</b> — the snapshot id, its generation, and the pinned
 *       markup. A dashboard that shows a total and cannot name the generation is showing whichever row
 *       it happened to read.</li>
 *   <li><b>"Which rate priced it?"</b> — {@code rateCardVersionId} plus the resolved version number and
 *       its effective range. This is ADR 0007 rule 1: "A total that cannot name its rate definition is a
 *       bug, not a rounding artefact."</li>
 *   <li><b>"Which events fed it?"</b> — the {@code usage_events} rows in the bucket, by the dimension,
 *       in the period. Never a count: the rows themselves, so each one can name its own source.</li>
 * </ol>
 *
 * <h2>Why the event list is bounded and says so</h2>
 *
 * <p>A month of AI tokens is millions of events. Returning all of them would be an availability
 * incident on a page somebody opens to check a number, so the list is bounded and the response carries
 * the bound and the total count — so a reader knows they are looking at the first N rather than
 * believing they saw everything.
 *
 * <h2>Authorization happens three times, deliberately</h2>
 *
 * <p>Once for the permission, once for the requested project through {@link TenantQueryGuard}, and once
 * per row through the tenant predicate in each statement. The first two refuse; the third shapes. A
 * snapshot id from another tenant therefore produces the same empty answer as a nonexistent one, and
 * the aggregate it belongs to is never computed at all.
 */
@Service
public class DrillThroughService {

    /**
     * The immutable events behind one snapshot bucket.
     *
     * <p>Windowed on the snapshot's own bucket ({@code periodStart}/{@code periodEnd}) rather than on a
     * caller-supplied date range: the point of the drill-through is to answer "what is in <em>this</em>
     * row", and a range parameter would let a caller relabel the answer.
     *
     * <p>{@code metadata} is never selected — {@link #METADATA_KEYS} is instead, which projects the
     * <em>keys</em> of the jsonb so the row's shape is auditable without carrying its values. A usage
     * event's metadata is free-form and is exactly where a customer-shaped value would end up.
     *
     * <p><b>The window is an overlap, not a containment</b> — {@code periodStart <= bucketEnd and
     * periodEnd >= bucketStart}, the same predicate {@code RollupRepository.ledgerAggregate} uses. An
     * event's stored bucket is its billing bucket (typically the month), so a daily bucket sits
     * <em>inside</em> the event's bucket rather than containing it. A containment test would return
     * nothing for every day of the month except the first, and the drill-through would answer "show me
     * the events behind this total" with an empty list for 30 days a month — the one failure a reader
     * cannot distinguish from "this day had no usage".
     */
    private static final String BUCKET_EVENTS = """
        select u."id", u."organizationId", u."projectId", u."customerId",
               u."dimension"::text as "dimension", u."quantity", u."unit", u."currency",
               u."costAmount", u."fxRate", u."fxSource", u."fxRateDate", u."sourceEventType",
               u."sourceEventId", u."sourceEntityType", u."sourceEntityId", u."actorUserId",
               u."principalType", u."principalName", u."occurredAt", u."periodStart", u."periodEnd",
               u."billingPeriodId", u."taxClassification", u."correctionOfId", u."correctionReason",
               u."createdAt",
               (select coalesce(array_agg(k order by k), '{}'::text[])
                  from jsonb_object_keys(u."metadata") as k) as "metadataKeys"
          from usage_events u
         where u."organizationId" = :__fe_tenant_organization_id
           and u."projectId" = :projectId
           and u."dimension" = cast(:dimension as "UsageDimension")
           and u."periodStart" <= :bucketEnd
           and u."periodEnd" >= :bucketStart
         order by u."occurredAt", u."id"
         limit :limit
        """;

    /**
     * The daily form of {@link #BUCKET_EVENTS}.
     *
     * <p>Same rows, different window, and deliberately: the two grains bucket differently, so the
     * drill-through must window with the same expression the snapshot was computed from. A DAILY
     * snapshot sums {@code occurredAt::date}; a MONTHLY one sums the stored billing bucket. Reusing
     * either predicate on the other grain either lists a whole month of events under one day, or — with
     * a containment test — lists none of them, and {@code totalSourceEvents} would stop agreeing with
     * the snapshot's own {@code sourceEventCount}.
     */
    private static final String BUCKET_EVENTS_BY_DAY = """
        select u."id", u."organizationId", u."projectId", u."customerId",
               u."dimension"::text as "dimension", u."quantity", u."unit", u."currency",
               u."costAmount", u."fxRate", u."fxSource", u."fxRateDate", u."sourceEventType",
               u."sourceEventId", u."sourceEntityType", u."sourceEntityId", u."actorUserId",
               u."principalType", u."principalName", u."occurredAt", u."periodStart", u."periodEnd",
               u."billingPeriodId", u."taxClassification", u."correctionOfId", u."correctionReason",
               u."createdAt",
               (select coalesce(array_agg(k order by k), '{}'::text[])
                  from jsonb_object_keys(u."metadata") as k) as "metadataKeys"
          from usage_events u
         where u."organizationId" = :__fe_tenant_organization_id
           and u."projectId" = :projectId
           and u."dimension" = cast(:dimension as "UsageDimension")
           and (u."occurredAt" AT TIME ZONE 'UTC')::date >= :bucketStart
           and (u."occurredAt" AT TIME ZONE 'UTC')::date <= :bucketEnd
         order by u."occurredAt", u."id"
         limit :limit
        """;

    /** How many events a bucket actually holds, so a bounded list can say it is bounded. */
    private static final String BUCKET_EVENT_COUNT = """
        select count(*)
          from usage_events u
         where u."organizationId" = :__fe_tenant_organization_id
           and u."projectId" = :projectId
           and u."dimension" = cast(:dimension as "UsageDimension")
           and u."periodStart" <= :bucketEnd
           and u."periodEnd" >= :bucketStart
        """;

    /** The daily form of {@link #BUCKET_EVENT_COUNT}, on the same reasoning as the event list. */
    private static final String BUCKET_EVENT_COUNT_BY_DAY = """
        select count(*)
          from usage_events u
         where u."organizationId" = :__fe_tenant_organization_id
           and u."projectId" = :projectId
           and u."dimension" = cast(:dimension as "UsageDimension")
           and (u."occurredAt" AT TIME ZONE 'UTC')::date >= :bucketStart
           and (u."occurredAt" AT TIME ZONE 'UTC')::date <= :bucketEnd
        """;

    /** The events a correction names, for the "show me what changed" question. */
    private static final String CORRECTION_CHAIN = """
        select u."id", u."correctionOfId", u."dimension"::text as "dimension", u."quantity",
               u."currency", u."costAmount", u."correctionReason", u."occurredAt",
               u."createdAt"
          from usage_events u
         where u."organizationId" = :__fe_tenant_organization_id
           and (u."id" = :eventId or u."correctionOfId" = :eventId)
         order by u."createdAt", u."id"
        """;

    private final NamedParameterJdbcTemplate jdbc;
    private final RollupRepository rollups;
    private final TenantQueryGuard guard;
    private final AuthorizationService authorization;

    public DrillThroughService(NamedParameterJdbcTemplate jdbc, RollupRepository rollups,
                               TenantQueryGuard guard, AuthorizationService authorization) {
        this.jdbc = jdbc;
        this.rollups = rollups;
        this.guard = guard;
        this.authorization = authorization;
    }

    /**
     * The full chain for one snapshot: the row, its rate, and the events behind it.
     *
     * @param requestedLevel the level asked for; clamped to what the caller's permission allows and
     *                       the response reports the level actually applied
     * @return empty when the snapshot is not in the caller's tenant or project
     */
    @RequirePermission(Permission.ANALYTICS_READ_ORGANIZATION)
    @Transactional(readOnly = true)
    public Optional<DrillThrough> drill(TenantContext caller, UUID snapshotId,
                                        DrillThroughLevel requestedLevel, int limit) {
        // Note the lookup deliberately does NOT require the snapshot to be the current generation: an
        // auditor asking about a superseded row is asking about the figure that was actually reported at
        // the time, and refusing it would leave that figure unexaminable. The row read names its own
        // generation and its supersede pointer, so a reader can see it is history.
        if (caller == null) {
            throw new IllegalStateException(
                "A drill-through requires a resolved TenantContext; refusing to run unscoped.");
        }
        authorization.check(caller, Permission.ANALYTICS_READ_ORGANIZATION, PolicyResource.NONE);

        DrillThroughLevel permitted = permittedLevel(caller);
        DrillThroughLevel level = requestedLevel == null ? DrillThroughLevel.EVENT
            : requestedLevel.clampTo(permitted);

        // The snapshot's project is read tenant-scoped FIRST, then turned into a scope. That ordering is
        // the point: a foreign snapshot is simply absent from the first read, so its project is never
        // considered and there is no value to probe. A client-supplied projectId on this route would be
        // a request, not authorization, which is why there is not one.
        List<UUID> projects = jdbc.query("""
            select s."projectId"
              from usage_rollup_snapshots s
             where s."organizationId" = :org
               and s."id" = :snapshotId
            """,
            new MapSqlParameterSource()
                .addValue("org", caller.organizationId())
                .addValue("snapshotId", snapshotId),
            (rs, rowNum) -> rs.getObject("projectId", UUID.class));
        if (projects.isEmpty()) {
            return Optional.empty();
        }

        // assertRowScope raises PolicyDeniedException when the caller has no membership in the
        // snapshot's project, which the web layer renders as 404 — identical to a nonexistent id.
        TenantScope scope = guard.assertRowScope(caller, caller.organizationId(), projects.get(0));

        return Optional.of(resolve(scope, snapshotId, level, limit));
    }

    /**
     * The drill-through for a named bucket rather than a snapshot id.
     *
     * <p>The form a dashboard links to, since a chart has a bucket rather than a row id.
     */
    @RequirePermission(Permission.ANALYTICS_READ_ORGANIZATION)
    @Transactional(readOnly = true)
    public Optional<DrillThrough> drillBucket(TenantContext caller, UUID projectId,
                                              com.firstelement.finance.rollup.RollupBucket.Grain grain,
                                              LocalDate bucketStart, UsageDimension dimension,
                                              DrillThroughLevel requestedLevel, int limit) {
        if (caller == null) {
            throw new IllegalStateException(
                "A drill-through requires a resolved TenantContext; refusing to run unscoped.");
        }
        authorization.check(caller, Permission.ANALYTICS_READ_ORGANIZATION, PolicyResource.NONE);
        TenantScope scope = guard.scope(caller, projectId);
        DrillThroughLevel permitted = permittedLevel(caller);
        DrillThroughLevel level = requestedLevel == null ? DrillThroughLevel.EVENT
            : requestedLevel.clampTo(permitted);

        return rollups.currentBucket(scope, scope.projectId(), grain, bucketStart, dimension)
            .map(snapshot -> assemble(scope, snapshot, level, limit));
    }

    /**
     * The correction chain for one usage event: the row and anything correcting it.
     *
     * <p>The other half of "every amount drills down": a figure that was corrected must show the
     * correction, or a reader comparing the event against a snapshot has no way to know why they
     * differ.
     */
    @RequirePermission(Permission.ANALYTICS_READ_ORGANIZATION)
    @Transactional(readOnly = true)
    public List<Map<String, Object>> corrections(TenantContext caller, UUID eventId) {
        if (caller == null) {
            throw new IllegalStateException(
                "A drill-through requires a resolved TenantContext; refusing to run unscoped.");
        }
        authorization.check(caller, Permission.ANALYTICS_READ_ORGANIZATION, PolicyResource.NONE);
        TenantScope scope = guard.tenantScope(caller);
        return jdbc.queryForList(CORRECTION_CHAIN,
            com.firstelement.security.query.ScopedWhere.of(scope)
                .bindNamed(java.util.Map.of("eventId", eventId)));
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    private DrillThrough resolve(TenantScope scope, UUID snapshotId, DrillThroughLevel level,
                                 int limit) {
        List<RollupRepository.SnapshotRow> rows = jdbc.query("""
            select s."id", s."organizationId", s."projectId", s."periodId", s."grain"::text,
                   s."bucketStart", s."bucketEnd", s."dimension"::text, s."currency",
                   s."sourceEventCount", s."quantity", s."costAmount", s."revenueAmount",
                   s."markupRate", s."rateCardVersionId", s."unpricedQuantity",
                   s."unpricedCostAmount", s."generation", s."supersedesSnapshotId",
                   s."computedAt", s."computedByPrincipal", s."correlationId"
              from usage_rollup_snapshots s
             where s."organizationId" = :__fe_tenant_organization_id
               and s."id" = :snapshotId
            """,
            com.firstelement.security.query.ScopedWhere.of(scope)
                .bindNamed(java.util.Map.of("snapshotId", snapshotId)),
            (rs, rowNum) -> new RollupRepository.SnapshotRow(
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
                rs.getObject("computedAt", java.time.LocalDateTime.class),
                rs.getString("computedByPrincipal"),
                rs.getString("correlationId")));
        return assemble(scope, rows.get(0), level, limit);
    }

    private DrillThrough assemble(TenantScope scope, RollupRepository.SnapshotRow snapshot,
                                  DrillThroughLevel level, int limit) {
        UsageDimension dimension = UsageDimension.fromWire(snapshot.dimension());
        int bounded = limit < 1
            ? com.firstelement.finance.ReportingFinanceModule.Limits.DRILL_EVENT_PAGE
            : Math.min(limit, com.firstelement.finance.ReportingFinanceModule.Limits.DRILL_EVENT_PAGE);

        // The grain decides which window the event list uses, because the grain decided which window
        // the snapshot's own figures used. One shared predicate would make the two disagree.
        boolean byDay = "DAILY".equalsIgnoreCase(snapshot.grain());

        Integer totalEvents = jdbc.queryForObject(
            byDay ? BUCKET_EVENT_COUNT_BY_DAY : BUCKET_EVENT_COUNT,
            com.firstelement.security.query.ScopedWhere.of(scope).bindNamed(java.util.Map.of(
                "projectId", snapshot.projectId(),
                "dimension", dimension.wireValue(),
                "bucketStart", snapshot.bucketStart(),
                "bucketEnd", snapshot.bucketEnd())), Integer.class);

        List<DrillThroughLevel.DrillThroughRow> events = jdbc.query(
            byDay ? BUCKET_EVENTS_BY_DAY : BUCKET_EVENTS,
            com.firstelement.security.query.ScopedWhere.of(scope).bindNamed(java.util.Map.of(
                "projectId", snapshot.projectId(),
                "dimension", dimension.wireValue(),
                "bucketStart", snapshot.bucketStart(),
                "bucketEnd", snapshot.bucketEnd(),
                "limit", bounded)),
            (rs, rowNum) -> new DrillThroughLevel.DrillThroughRow(
                rs.getObject("id", UUID.class),
                rs.getObject("organizationId", UUID.class),
                rs.getObject("projectId", UUID.class),
                level.revealsPrincipal() ? rs.getObject("customerId", UUID.class) : null,
                rs.getString("dimension"),
                rs.getBigDecimal("quantity"),
                rs.getString("unit"),
                rs.getString("currency"),
                rs.getBigDecimal("costAmount"),
                rs.getBigDecimal("fxRate"),
                rs.getString("fxSource"),
                rs.getObject("fxRateDate", LocalDate.class),
                rs.getString("sourceEventType"),
                rs.getObject("sourceEventId", UUID.class),
                rs.getString("sourceEntityType"),
                rs.getObject("sourceEntityId", UUID.class),
                level.revealsPrincipal() ? rs.getObject("actorUserId", UUID.class) : null,
                level.revealsPrincipal() ? rs.getString("principalType") : null,
                level.revealsPrincipal() ? rs.getString("principalName") : null,
                rs.getObject("occurredAt", java.time.LocalDateTime.class),
                rs.getObject("periodStart", LocalDate.class),
                rs.getObject("periodEnd", LocalDate.class),
                rs.getObject("billingPeriodId", UUID.class),
                rs.getString("taxClassification"),
                java.util.Arrays.asList((String[]) rs.getArray("metadataKeys").getArray()),
                rs.getObject("correctionOfId", UUID.class),
                rs.getString("correctionReason"),
                rs.getObject("createdAt", java.time.LocalDateTime.class),
                level));

        String rateDefinition = snapshot.rateCardVersionId() == null
            ? "unpriced: no rate-card version was effective on " + snapshot.bucketStart()
            : describeRate(scope, snapshot);

        return new DrillThrough(
            snapshot,
            level,
            snapshot.rateCardVersionId(),
            rateDefinition,
            totalEvents == null ? 0 : totalEvents,
            events,
            bounded,
            "Every amount here resolves to this snapshot's generation and to the immutable usage_events "
                + "rows that fed it. metadata is never projected: its KEYS are, so the row's shape is "
                + "auditable without carrying customer-shaped values.");
    }

    /**
     * Names the rate that priced a snapshot: its version number and its effective range.
     *
     * <p>An opaque version id answers "which row", not "which price". The number and the dates do,
     * and they are read from the version itself rather than from whichever version happens to be ACTIVE
     * now — a rate card is superseded, never rewritten, so today's ACTIVE version is frequently not the
     * one that produced a figure from last quarter. The {@code markupRate} on the snapshot is the pinned
     * value and is reported alongside, because that is the number the snapshot actually used even if the
     * version row were ever corrected.
     *
     * <p>A version that has since been deleted is impossible (no delete path) but a version in another
     * tenant is unreadable here, so a miss degrades to the id rather than failing the whole drill-through.
     */
    private String describeRate(TenantScope scope, RollupRepository.SnapshotRow snapshot) {
        List<String> described = jdbc.query("""
            select v."versionNumber", v."effectiveFrom"::text as "effectiveFromText",
                   v."effectiveTo"::text as "effectiveToText"
              from rate_card_versions v
             where v."organizationId" = :__fe_tenant_organization_id
               and v."id" = :rateCardVersionId
            """,
            com.firstelement.security.query.ScopedWhere.of(scope).bindNamed(java.util.Map.of(
                "rateCardVersionId", snapshot.rateCardVersionId())),
            (rs, rowNum) -> "rate-card-version " + rs.getInt("versionNumber")
                + " effective " + rs.getString("effectiveFromText")
                + " .. " + rs.getString("effectiveToText")
                + " (markup " + snapshot.markupRate() + " pinned at computation time)");

        return described.isEmpty()
            ? "rate-card-version " + snapshot.rateCardVersionId() + " (markup "
                + snapshot.markupRate() + " pinned at computation time; version row not readable)"
            : described.get(0);
    }

    /**
     * The most revealing level the caller's permissions permit.
     *
     * <p>{@code ANALYTICS_READ_ORGANIZATION} covers all three levels in this schema — the levels are a
     * <em>policy</em> distinction, not three grants — so an {@code ADMIN} gets {@code PRINCIPAL} and a
     * worker holding {@code FINANCE_AGGREGATE} is clamped to {@code EVENT}. The clamp is reported on the
     * response so a truncated drill-through is visible rather than silently less than what was asked for.
     */
    private DrillThroughLevel permittedLevel(TenantContext caller) {
        Set<Permission> effective = authorization.effectivePermissions(caller);
        if (DrillThroughLevel.PRINCIPAL.isPermittedFor(effective) && caller.isAdmin()) {
            return DrillThroughLevel.PRINCIPAL;
        }
        return DrillThroughLevel.EVENT;
    }

    /** The whole chain, as one response. */
    public record DrillThrough(
            RollupRepository.SnapshotRow snapshot,
            DrillThroughLevel level,
            UUID rateCardVersionId,
            String rateDefinition,
            int totalSourceEvents,
            List<DrillThroughLevel.DrillThroughRow> events,
            int returnedLimit,
            String note
    ) {

        /** Whether the event list was truncated, so the reader knows. */
        public boolean truncated() {
            return totalSourceEvents > events.size();
        }

        /** The cost this snapshot row states. */
        public Money cost() {
            return Money.of(snapshot.costAmount(), snapshot.currency());
        }

        /** The revenue this snapshot row states, or unpriced. Never null-means-zero. */
        public Money revenue() {
            return snapshot.rateCardVersionId() == null ? Money.unpriced(snapshot.currency())
                : Money.of(snapshot.revenueAmount(), snapshot.currency());
        }
    }
}
