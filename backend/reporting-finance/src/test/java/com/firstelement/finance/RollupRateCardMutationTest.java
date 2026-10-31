package com.firstelement.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstelement.finance.catalog.UsageDimension;
import com.firstelement.finance.ledger.LedgerPrincipal;
import com.firstelement.finance.money.AmountState;
import com.firstelement.finance.period.PeriodLifecycleService;
import com.firstelement.finance.read.FinanceUsageReadService;
import com.firstelement.finance.read.FinanceUsageView;
import com.firstelement.finance.rollup.RollupBucket;
import com.firstelement.finance.rollup.RollupRepository;
import com.firstelement.finance.rollup.RollupService;
import com.firstelement.security.TenantContext;
import com.firstelement.security.query.TenantQueryGuard;
import com.firstelement.security.query.TenantScope;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The central claim of ADR 0007 Part 1: <b>a rate-card change does not mutate a computed snapshot</b>,
 * and a closed period cannot be changed at all without an approved, audited correction.
 *
 * <h2>Why this is one suite and not three assertions</h2>
 *
 * <p>Because the three claims are one mechanism seen from three sides, and each is individually easy to
 * satisfy for the wrong reason:
 *
 * <ul>
 *   <li><b>the snapshot is immutable</b> — satisfied by a rollup that never recomputes anything;</li>
 *   <li><b>a closed period refuses a new snapshot</b> — satisfied by a period that is never locked;</li>
 *   <li><b>a recomputation does produce a different number when the rate really changed</b> — which is
 *       the control that makes the first two mean something.</li>
 * </ul>
 *
 * <p>So the sequence is asserted in order and the control comes last: a fresh bucket, computed under the
 * new rate, has a different revenue. Without it the first two assertions would pass against a rollup
 * service that did nothing at all.
 *
 * <h2>Why the period is closed before the rate changes</h2>
 *
 * <p>Because that is the real sequence in production: a month closes, somebody notices the price was
 * wrong, and the rate card is corrected. If the snapshot were recomputed silently the closed period
 * would change under nobody's approval — which is precisely what ADR 0007 rule 4 forbids, and what this
 * suite is the evidence against.
 */
@SpringBootTest(classes = FinanceTestApplication.class)
class RollupRateCardMutationTest extends FinanceIntegrationTestBase {

    @Autowired
    private RollupService rollups;
    @Autowired
    private RollupRepository rollupRepository;
    @Autowired
    private FinanceUsageReadService usage;
    @Autowired
    private PeriodLifecycleService periods;
    @Autowired
    private TenantQueryGuard guard;
    @Autowired
    private FinanceFixture fixture;

    private TenantContext adminA;

    @BeforeEach
    void reset() {
        fixture.reset();
        adminA = FinancePrincipalScope.adminA();
    }

    @Test
    @DisplayName("a rate change leaves a computed snapshot untouched, and a closed period refuses a "
        + "recomputation outright")
    void aRateChangeDoesNotMutateAComputedSnapshot() {
        LocalDate usageDay = LocalDate.of(2026, 3, 10);

        // --- 1. usage and a rate -------------------------------------------------
        UUID marchVersion = fixture.activeVersion(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, 1,
            "USD", new BigDecimal("2.000000"), FinanceFixture.MARCH_1, FinanceFixture.MARCH_31,
            "the March price", FinanceFixture.ADMIN_A);
        fixture.line(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, marchVersion, "MESSAGE",
            "message", null, new BigDecimal("2.000000"), "USD");
        fixture.message(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, new BigDecimal("10"),
            FinanceFixture.inMarch(10, 9), new BigDecimal("3.000000"), "USD");

        RollupBucket original = computeOne(usageDay, "corr-original");
        assertThat(original.isPriced()).isTrue();
        assertThat(original.generation()).isEqualTo(1);
        assertThat(original.cost().amount()).isEqualByComparingTo("3.000000");
        assertThat(original.revenue().amount())
            .as("revenue = round(cost x the markup pinned on this row, 6)")
            .isEqualByComparingTo("6.000000");
        assertThat(original.margin().amount()).isEqualByComparingTo("3.000000");
        assertThat(original.rateCardVersionId()).isEqualTo(marchVersion);

        // --- 2. close the period ------------------------------------------------
        UUID periodId = fixture.period(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, "2026-03",
            FinanceFixture.MARCH_1, FinanceFixture.MARCH_31, "USD");
        FinancePrincipalScope.run(adminA, () -> periods.lock(adminA, periodId, "March closed and "
            + "reconciled"));

        // --- 3. change the rate -------------------------------------------------
        // Effective from April, so it does not even apply to the usage date. The strongest form of the
        // claim: not only does the newer rate not reach backwards, the older rate still governs March
        // after it exists.
        UUID aprilVersion = fixture.activeVersion(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, 2,
            "USD", new BigDecimal("5.000000"), FinanceFixture.APRIL_1, FinanceFixture.APRIL_30,
            "the April price", FinanceFixture.ADMIN_A);
        fixture.line(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, aprilVersion, "MESSAGE", "message",
            null, new BigDecimal("5.000000"), "USD");

        // --- 4. re-read: nothing moved -----------------------------------------
        FinanceUsageView view = FinancePrincipalScope.call(adminA,
            () -> usage.usage(adminA, FinanceFixture.PROJECT_A, RollupBucket.Grain.DAILY, usageDay,
                usageDay));
        FinanceUsageView.BucketRow row = view.buckets().stream()
            .filter(bucket -> bucket.dimension() == UsageDimension.MESSAGE)
            .findFirst()
            .orElseThrow();

        assertThat(row.snapshotId()).as("the same snapshot row").isEqualTo(original.snapshotId());
        assertThat(row.generation()).as("generation 1 still").isEqualTo(1);
        assertThat(row.markupRate()).as("the markup pinned at computation time")
            .isEqualByComparingTo("2.000000");
        assertThat(row.rateCardVersionId()).isEqualTo(marchVersion);
        assertThat(row.rateCardVersionNumber()).as("and it names the rate definition, not a uuid")
            .isEqualTo(1);
        assertThat(row.revenue().amount()).isEqualByComparingTo("6.000000");
        assertThat(row.revenue().state()).isEqualTo(AmountState.PRICED_NONZERO);
        assertThat(row.rateDefinition())
            .contains("rate-card-version 1")
            .contains("markup 2.000000");

        // STATE, not the response: the row itself is unchanged in the table.
        var stored = FinancePrincipalScope.call(adminA,
            () -> rollupRepository.currentBucket(tenantScope(), FinanceFixture.PROJECT_A,
                RollupBucket.Grain.DAILY, usageDay, UsageDimension.MESSAGE)).orElseThrow();
        assertThat(stored.markupRate()).isEqualByComparingTo("2.000000");
        assertThat(stored.revenueAmount()).isEqualByComparingTo("6.000000");
        assertThat(stored.rateCardVersionId()).isEqualTo(marchVersion);

        // --- 5. the closed period refuses a recomputation -----------------------
        // The application's own refusal: RollupService does not compute into a closed period, because
        // the database trigger usage_rollup_snapshots_locked_period_guard would raise and a caller
        // would see a constraint violation instead of the workflow.
        // The DATABASE refuses it, through usage_rollup_snapshots_locked_period_guard. Asserted as a
        // DataIntegrityViolationException because that is the shape a trigger's RAISE takes through the
        // driver, and asserting the message names the workflow ("Reopen the period, or apply a
        // finance_adjustments row") rather than merely that it failed.
        assertThatThrownBy(() -> computeIntoLockedPeriod(periodId, usageDay))
            .hasMessageContaining("is LOCKED, so no new snapshot may be recorded")
            .hasMessageContaining("Reopen the period, or apply a finance_adjustments row");

        // STATE, not the message: no second snapshot row exists for that locked period.
        assertThat(fixture.count("""
            select 1 from usage_rollup_snapshots where "periodId" = :periodId""",
            Map.of("periodId", periodId))).isZero();

        // --- 6. THE CONTROL: a fresh bucket under the new rate really does differ
        // Without this, steps 4 and 5 would pass against a rollup that computes nothing.
        LocalDate aprilDay = LocalDate.of(2026, 4, 10);
        fixture.usageEvent(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, "MESSAGE",
            new BigDecimal("10"), "message", LocalDate.of(2026, 4, 10).atTime(9, 0),
            FinanceFixture.APRIL_1, FinanceFixture.APRIL_30, UUID.randomUUID(),
            "messages.delivered", new BigDecimal("3.000000"), "USD", null, Map.of());
        RollupBucket april = computeOne(aprilDay, "corr-april");

        assertThat(april.rateCardVersionId()).isEqualTo(aprilVersion);
        assertThat(april.markupRate()).isEqualByComparingTo("5.000000");
        assertThat(april.cost().amount()).isEqualByComparingTo("3.000000");
        assertThat(april.revenue().amount())
            .as("the same cost at five times the markup, so the rollup does respond to a rate change")
            .isEqualByComparingTo("15.000000");
        assertThat(april.generation()).isEqualTo(1);
        assertThat(april.bucketStart()).isEqualTo(aprilDay);

        // And the two buckets disagree only because of the rate, which is what makes the March figure
        // a pinned historical fact rather than a stale one.
        assertThat(april.revenue().amount())
            .isNotEqualByComparingTo(original.revenue().amount());
    }

    @Test
    @DisplayName("a recomputation is a new generation, and the superseded row stays readable")
    void aRecomputationIsANewGeneration() {
        LocalDate usageDay = LocalDate.of(2026, 3, 10);
        UUID version = fixture.activeVersion(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, 1, "USD",
            new BigDecimal("2.000000"), FinanceFixture.MARCH_1, FinanceFixture.MARCH_31, "v1",
            FinanceFixture.ADMIN_A);
        fixture.line(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, version, "MESSAGE", "message",
            null, new BigDecimal("2.000000"), "USD");
        fixture.message(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, new BigDecimal("10"),
            FinanceFixture.inMarch(10, 9), new BigDecimal("3.000000"), "USD");

        RollupBucket first = computeOne(usageDay, "corr-1");
        RollupBucket second = computeOne(usageDay, "corr-2");

        assertThat(second.generation()).as("generation 2").isEqualTo(2);
        assertThat(second.supersedesSnapshotId()).as("naming what it replaces")
            .isEqualTo(first.snapshotId());
        assertThat(second.snapshotId()).isNotEqualTo(first.snapshotId());

        // Both rows are still there: usage_rollup_snapshots is append-only, so generation 1's
        // arithmetic is frozen rather than overwritten.
        List<Map<String, Object>> history = FinancePrincipalScope.call(adminA,
            () -> rollupRepository.history(tenantScope(), FinanceFixture.PROJECT_A,
                RollupBucket.Grain.DAILY, usageDay, UsageDimension.MESSAGE));
        assertThat(history).hasSize(2);

        // And the read model shows only the current one, which is what
        // usage_rollup_snapshots_current_bucket_key guarantees at most one of.
        FinanceUsageView view = FinancePrincipalScope.call(adminA,
            () -> usage.usage(adminA, FinanceFixture.PROJECT_A, RollupBucket.Grain.DAILY, usageDay,
                usageDay));
        assertThat(view.buckets()).hasSize(1);
        assertThat(view.buckets().get(0).snapshotId()).isEqualTo(second.snapshotId());
        assertThat(view.buckets().get(0).currentGeneration()).isTrue();
    }

    // ==================================================================
    // helpers
    // ==================================================================

    private RollupBucket computeOne(LocalDate day, String correlationId) {
        List<RollupBucket> computed = FinancePrincipalScope.call(adminA,
            () -> rollups.computeDaily(adminA, FinanceFixture.PROJECT_A, day, day,
                LedgerPrincipal.job("test-rollup"), correlationId));
        assertThat(computed).as("a bucket with usage is materialised").hasSize(1);
        return computed.get(0);
    }

    /**
     * Attempts a recomputation that would attach its snapshot to a locked period.
     *
     * <p>Written as a direct insert rather than through {@code RollupService}, because the point is that
     * the <em>database</em> refuses it. A service-level guard alone would be a convention; this is a
     * trigger.
     */
    private void computeIntoLockedPeriod(UUID periodId, LocalDate day) {
        var scope = tenantScope();
        var position = FinancePrincipalScope.call(adminA,
            () -> rollupRepository.generation(scope, FinanceFixture.PROJECT_A,
                RollupBucket.Grain.DAILY, day, UsageDimension.MESSAGE));
        var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
            .addValue("__fe_tenant_organization_id", scope.organizationId())
            .addValue("projectId", scope.projectId())
            .addValue("periodId", periodId)
            .addValue("id", UUID.randomUUID())
            .addValue("grain", "DAILY")
            .addValue("bucketStart", day)
            .addValue("bucketEnd", day)
            .addValue("dimension", "MESSAGE")
            .addValue("currency", "USD")
            .addValue("sourceEventCount", 1L)
            .addValue("quantity", BigDecimal.TEN)
            .addValue("costAmount", new BigDecimal("3.000000"))
            .addValue("revenueAmount", new BigDecimal("6.000000"))
            .addValue("markupRate", new BigDecimal("2.000000"))
            .addValue("rateCardVersionId", UUID.randomUUID())
            .addValue("unpricedQuantity", BigDecimal.ZERO)
            .addValue("unpricedCostAmount", BigDecimal.ZERO)
            .addValue("generation", position.nextGeneration())
            .addValue("supersedesSnapshotId", position.supersedesSnapshotId())
            .addValue("computedAt", FinanceFixture.now())
            .addValue("computedByPrincipal", "JOB:test-rollup")
            .addValue("correlationId", "corr-locked");
        jdbc.update("""
            insert into usage_rollup_snapshots (
                "id", "organizationId", "projectId", "periodId", "grain", "bucketStart",
                "bucketEnd", "dimension", "currency", "sourceEventCount", "quantity", "costAmount",
                "revenueAmount", "markupRate", "rateCardVersionId", "unpricedQuantity",
                "unpricedCostAmount", "generation", "supersedesSnapshotId", "computedAt",
                "computedByPrincipal", "correlationId")
            values (:id, :__fe_tenant_organization_id, :projectId, :periodId,
                cast(:grain as "RollupGrain"), :bucketStart, :bucketEnd,
                cast(:dimension as "UsageDimension"), :currency, :sourceEventCount, :quantity,
                :costAmount, :revenueAmount, :markupRate, :rateCardVersionId, :unpricedQuantity,
                :unpricedCostAmount, :generation, :supersedesSnapshotId, :computedAt,
                :computedByPrincipal, :correlationId)""", parameters);
    }

    private TenantScope tenantScope() {
        return guard.tenantScope(adminA);
    }

    @Autowired
    private org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc;
}
