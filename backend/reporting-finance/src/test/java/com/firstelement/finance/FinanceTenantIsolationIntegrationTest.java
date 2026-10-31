package com.firstelement.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstelement.finance.catalog.UsageDimension;
import com.firstelement.finance.drill.DrillThroughLevel;
import com.firstelement.finance.drill.DrillThroughService;
import com.firstelement.finance.ledger.LedgerPrincipal;
import com.firstelement.finance.legacy.LegacyAnalyticsComparisonService;
import com.firstelement.finance.money.AmountState;
import com.firstelement.finance.period.AdjustmentService;
import com.firstelement.finance.period.FinancePeriodRepository;
import com.firstelement.finance.period.PeriodLifecycleService;
import com.firstelement.finance.read.FinanceUsageReadService;
import com.firstelement.finance.read.FinanceUsageView;
import com.firstelement.finance.rollup.RollupBucket;
import com.firstelement.finance.rollup.RollupService;
import com.firstelement.security.TenantContext;
import com.firstelement.security.error.PolicyDeniedException;
import com.firstelement.security.query.TenantQueryGuard;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Tenant isolation: no membership ⇒ no rows, and an unauthorized aggregate reveals <em>nothing</em>.
 *
 * <h2>Why the assertions are on aggregates and not on lists</h2>
 *
 * <p>FR-8.2 requires "authorization before aggregation, not after", and the difference is observable. A
 * read-then-filter implementation satisfies "the list contains no foreign rows" while still returning a
 * different count, a different total and a different timing profile — so a test that only checks the list
 * passes against the implementation the requirement forbids.
 *
 * <p>So every case here asserts on what a caller can infer: the number of rows, the sums, the
 * {@code dataState}, and the drill-through's total count. Each foreign access is paired with a positive
 * control — the same shape inside the caller's own tenant — so a refusal cannot be mistaken for "this
 * table rejects everything".
 *
 * <h2>Two tenants and two projects in one of them</h2>
 *
 * <p>Tenant A has two projects, so the project-level filter is exercised independently of the tenant one.
 * A bug that only ever compared {@code organizationId} would pass every tenant test and be caught here.
 */
@SpringBootTest(classes = FinanceTestApplication.class)
class FinanceTenantIsolationIntegrationTest extends FinanceIntegrationTestBase {

    @Autowired
    private FinanceUsageReadService usage;
    @Autowired
    private DrillThroughService drillThrough;
    @Autowired
    private PeriodLifecycleService periods;
    @Autowired
    private AdjustmentService adjustments;
    @Autowired
    private RollupService rollups;
    @Autowired
    private LegacyAnalyticsComparisonService legacyComparison;
    @Autowired
    private TenantQueryGuard guard;
    @Autowired
    private FinanceFixture fixture;
    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    private TenantContext adminA;
    private TenantContext adminB;

    @BeforeEach
    void reset() {
        fixture.reset();
        adminA = FinancePrincipalScope.adminA();
        adminB = FinancePrincipalScope.adminB();
    }

    // ==================================================================
    // the aggregate
    // ==================================================================

    @Nested
    @DisplayName("an unauthorized aggregate reveals neither counts nor sums")
    class UnauthorizedAggregates {

        @Test
        @DisplayName("tenant A sees its own totals and nothing of tenant B's")
        void tenantASeesOnlyItsOwnTotals() {
            seedUsageInBothTenants();

            FinanceUsageView viewA = FinancePrincipalScope.call(adminA,
                () -> usage.usage(adminA, null, RollupBucket.Grain.DAILY,
                    FinanceFixture.MARCH_1, FinanceFixture.MARCH_31));
            FinanceUsageView viewB = FinancePrincipalScope.call(adminB,
                () -> usage.usage(adminB, null, RollupBucket.Grain.DAILY,
                    FinanceFixture.MARCH_1, FinanceFixture.MARCH_31));

            assertThat(viewA.organizationId()).isEqualTo(FinanceFixture.ORG_A);
            assertThat(viewB.organizationId()).isEqualTo(FinanceFixture.ORG_B);

            // POSITIVE CONTROL: each tenant really does have its own priced usage, so the emptiness below
            // cannot be "the fixture seeded nothing".
            assertThat(viewA.totals().cost().amount()).isEqualByComparingTo("4.000000");
            assertThat(viewB.totals().cost().amount()).isEqualByComparingTo("900.000000");

            assertThat(viewA.buckets())
                .as("the total is tenant A's alone, not the sum of both")
                .extracting(FinanceUsageView.BucketRow::cost)
                .allSatisfy(cost -> assertThat(cost.amount()).isLessThan(new BigDecimal("100")));
            assertThat(viewA.totals().sourceEventCount()).isEqualTo(1);
            assertThat(viewB.totals().sourceEventCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("a foreign project's id is refused, and the refusal does not disclose it")
        void aForeignProjectIdIsRefused() {
            seedUsageInBothTenants();

            assertThatThrownBy(() -> FinancePrincipalScope.run(adminA,
                () -> usage.usage(adminA, FinanceFixture.PROJECT_B, RollupBucket.Grain.DAILY,
                    FinanceFixture.MARCH_1, FinanceFixture.MARCH_31)))
                .isInstanceOf(PolicyDeniedException.class);

            // STATE, not the exception: nothing was computed for the refused project.
            assertThat(snapshotCount(FinanceFixture.ORG_B)).as("tenant B has its own snapshot")
                .isPositive();
            assertThat(snapshotCount(FinanceFixture.ORG_A)).isPositive();
        }

        @Test
        @DisplayName("a project the caller has no membership in yields no rows and no totals")
        void aProjectOutsideMembershipYieldsNothing() {
            // Admin A is a member of PROJECT_A and PROJECT_A2 but NOT of a project belonging to
            // tenant B — the guard compares the requested id against the server-resolved projectIds.
            seedUsageInBothTenants();

            assertThatThrownBy(() -> FinancePrincipalScope.run(adminA,
                () -> usage.usage(adminA, FinanceFixture.PROJECT_B, RollupBucket.Grain.DAILY,
                    FinanceFixture.MARCH_10, FinanceFixture.MARCH_10)))
                .isInstanceOf(PolicyDeniedException.class);

            assertThat(FinancePrincipalScope.call(adminA,
                () -> usage.usage(adminA, FinanceFixture.PROJECT_A, RollupBucket.Grain.DAILY,
                    FinanceFixture.MARCH_10, FinanceFixture.MARCH_10)))
                .as("and the project the caller DOES hold is answered, so the refusal is about the "
                    + "membership and not about the query")
                .satisfies(view -> assertThat(view.buckets()).hasSize(1));
        }

        @Test
        @DisplayName("two projects in one tenant are summed only over the ones the caller holds")
        void twoProjectsInOneTenantAreScopedSeparately() {
            seedTenantAUsageInBothProjects();

            FinanceUsageView projectA = FinancePrincipalScope.call(adminA,
                () -> usage.usage(adminA, FinanceFixture.PROJECT_A, RollupBucket.Grain.DAILY,
                    FinanceFixture.MARCH_10, FinanceFixture.MARCH_10));
            FinanceUsageView projectA2 = FinancePrincipalScope.call(adminA,
                () -> usage.usage(adminA, FinanceFixture.PROJECT_A2, RollupBucket.Grain.DAILY,
                    FinanceFixture.MARCH_10, FinanceFixture.MARCH_10));
            FinanceUsageView tenantWide = FinancePrincipalScope.call(adminA,
                () -> usage.usage(adminA, null, RollupBucket.Grain.DAILY,
                    FinanceFixture.MARCH_10, FinanceFixture.MARCH_10));

            assertThat(projectA.totals().cost().amount()).isEqualByComparingTo("4.000000");
            assertThat(projectA2.totals().cost().amount()).isEqualByComparingTo("7.000000");
            assertThat(tenantWide.totals().cost().amount())
                .as("the tenant-wide view is the union of the caller's own projects, never 'all rows'")
                .isEqualByComparingTo("11.000000");
            assertThat(tenantWide.totals().sourceEventCount()).isEqualTo(2);
        }
    }

    // ==================================================================
    // the drill-through
    // ==================================================================

    @Nested
    @DisplayName("the drill-through is tenant-scoped and redacted")
    class DrillThrough {

        @Test
        @DisplayName("a foreign snapshot id resolves to nothing, not to an error")
        void aForeignSnapshotResolvesToNothing() {
            UUID foreignSnapshot = seedAndCompute(FinanceFixture.ORG_B, FinanceFixture.PROJECT_B,
                new BigDecimal("900.000000"), FinanceFixture.ADMIN_B);

            Optional absent = FinancePrincipalScope.call(adminA,
                () -> drillThrough.drill(adminA, foreignSnapshot, DrillThroughLevel.EVENT, 50));

            // Empty rather than an exception, and identical to a nonexistent id — a caller cannot use it
            // to learn that the snapshot exists.
            assertThat(absent).isEmpty();
            assertThat(FinancePrincipalScope.call(adminA,
                () -> drillThrough.drill(adminA, UUID.randomUUID(), DrillThroughLevel.EVENT, 50)))
                .as("and identical to a nonexistent one")
                .isEmpty();
        }

        @Test
        @DisplayName("a snapshot in the caller's tenant but another project is refused by the guard")
        void aSnapshotInAnotherProjectIsRefused() {
            UUID snapshot = seedAndCompute(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A,
                new BigDecimal("4.000000"), FinanceFixture.ADMIN_A);
            // PROJECT_A2 needs usage of its own for the positive control below to have a row to find.
            priceMessages(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A2, "USD", BigDecimal.ONE);
            fixture.message(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A2, new BigDecimal("10"),
                FinanceFixture.inMarch(10, 9), new BigDecimal("7.000000"), "USD");
            computeFor(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A2);

            // A tenant-A administrator holding PROJECT_A2 but not PROJECT_A. ADMIN on purpose: an
            // EMPLOYEE would be stopped earlier by @RequirePermission and the test would pass without
            // the membership guard ever running — a false green for the layer under test.
            TenantContext siblingAdmin = FinancePrincipalScope.adminAWithoutProjectA();

            assertThatThrownBy(() -> FinancePrincipalScope.run(siblingAdmin,
                () -> drillThrough.drill(siblingAdmin, snapshot, DrillThroughLevel.EVENT, 50)))
                .as("the permission holds, so it is the project scope that refuses")
                .isInstanceOf(PolicyDeniedException.class);

            // POSITIVE CONTROL: the same principal, pointed at the project it does hold.
            assertThat(FinancePrincipalScope.call(siblingAdmin,
                () -> drillThrough.drillBucket(siblingAdmin, FinanceFixture.PROJECT_A2,
                    RollupBucket.Grain.DAILY, FinanceFixture.MARCH_10, UsageDimension.MESSAGE,
                    DrillThroughLevel.EVENT, 50)))
                .as("so the refusal is about PROJECT_A's membership, not about the caller's emptiness")
                .isPresent();
        }

        @Test
        @DisplayName("a drill-through resolves a total to its source events, naming the rate")
        void aDrillThroughResolvesToSourceEvents() {
            FinanceFixture.SeededUsageEvent seeded = fixture.usageEventWithMarker(FinanceFixture.ORG_A,
                FinanceFixture.PROJECT_A, "MESSAGE", new BigDecimal("10"),
                FinanceFixture.inMarch(10, 9), FinanceFixture.MARCH_1, FinanceFixture.MARCH_31,
                new BigDecimal("4.000000"), "USD");
            priceMessages();
            compute(FinanceFixture.MARCH_10, FinanceFixture.MARCH_10);

            DrillThroughService.DrillThrough through = FinancePrincipalScope.call(adminA,
                () -> drillThrough.drillBucket(adminA, FinanceFixture.PROJECT_A,
                    RollupBucket.Grain.DAILY, FinanceFixture.MARCH_10, UsageDimension.MESSAGE,
                    DrillThroughLevel.EVENT, 200)).orElseThrow();

            assertThat(through.totalSourceEvents()).isEqualTo(1);
            assertThat(through.truncated()).isFalse();
            assertThat(through.rateCardVersionId()).as("the rate that priced it").isNotNull();
            assertThat(through.rateDefinition())
                .contains("rate-card-version 1")
                .contains("markup 2.000000");
            assertThat(through.cost().amount()).isEqualByComparingTo("4.000000");
            assertThat(through.revenue().amount()).isEqualByComparingTo("8.000000");

            DrillThroughLevel.DrillThroughRow event = through.events().get(0);
            assertThat(event.id()).as("the immutable ledger row")
                .isEqualTo(seeded.usageEventId());
            assertThat(event.sourceEventType()).isEqualTo("messages.delivered");
            assertThat(event.sourceEventId()).as("the originating row, a different id from the ledger's")
                .isEqualTo(seeded.sourceEventId());
            assertThat(event.dimension()).isEqualTo("MESSAGE");
            assertThat(event.unit()).isEqualTo("message");
        }

        @Test
        @DisplayName("metadata values are never projected; its keys are")
        void metadataValuesAreNeverProjected() {
            fixture.usageEventWithMarker(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, "MESSAGE",
                new BigDecimal("10"), FinanceFixture.inMarch(10, 9), FinanceFixture.MARCH_1,
                FinanceFixture.MARCH_31, new BigDecimal("4.000000"), "USD");
            priceMessages();
            compute(FinanceFixture.MARCH_10, FinanceFixture.MARCH_10);

            for (DrillThroughLevel level : List.of(DrillThroughLevel.AGGREGATE,
                DrillThroughLevel.EVENT, DrillThroughLevel.PRINCIPAL)) {
                DrillThroughService.DrillThrough through = FinancePrincipalScope.call(adminA,
                    () -> drillThrough.drillBucket(adminA, FinanceFixture.PROJECT_A,
                        RollupBucket.Grain.DAILY, FinanceFixture.MARCH_10, UsageDimension.MESSAGE,
                        level, 200)).orElseThrow();

                assertThat(through.events().toString())
                    .as("%s must not carry a metadata VALUE", level)
                    .doesNotContain(FinanceFixture.METADATA_MARKER);

                if (level == DrillThroughLevel.EVENT || level == DrillThroughLevel.PRINCIPAL) {
                    assertThat(through.events().get(0).metadataKeys())
                        .as("but the KEYS are, so the row's shape is auditable")
                        .contains("patientName", "channel");
                }
            }
        }

        @Test
        @DisplayName("AGGREGATE level reveals no principal; PRINCIPAL reveals one")
        void aggregateLevelRevealsNoPrincipal() {
            fixture.usageEventWithMarker(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, "MESSAGE",
                new BigDecimal("10"), FinanceFixture.inMarch(10, 9), FinanceFixture.MARCH_1,
                FinanceFixture.MARCH_31, new BigDecimal("4.000000"), "USD");
            priceMessages();
            compute(FinanceFixture.MARCH_10, FinanceFixture.MARCH_10);

            DrillThroughService.DrillThrough aggregate = FinancePrincipalScope.call(adminA,
                () -> drillThrough.drillBucket(adminA, FinanceFixture.PROJECT_A,
                    RollupBucket.Grain.DAILY, FinanceFixture.MARCH_10, UsageDimension.MESSAGE,
                    DrillThroughLevel.AGGREGATE, 200)).orElseThrow();
            DrillThroughService.DrillThrough principal = FinancePrincipalScope.call(adminA,
                () -> drillThrough.drillBucket(adminA, FinanceFixture.PROJECT_A,
                    RollupBucket.Grain.DAILY, FinanceFixture.MARCH_10, UsageDimension.MESSAGE,
                    DrillThroughLevel.PRINCIPAL, 200)).orElseThrow();

            assertThat(aggregate.events().get(0).principalName()).isNull();
            assertThat(aggregate.events().get(0).actorUserId()).isNull();
            assertThat(aggregate.events().get(0).customerId()).isNull();
            assertThat(aggregate.events().get(0).level())
                .isEqualTo(DrillThroughLevel.AGGREGATE);

            assertThat(principal.events().get(0).principalName())
                .as("an opaque label, never an email")
                .isNotBlank()
                .doesNotContain("@");
            assertThat(principal.events().get(0).level())
                .isEqualTo(DrillThroughLevel.PRINCIPAL);
        }

        @Test
        @DisplayName("a machine principal is clamped to EVENT, not given PRINCIPAL")
        void aMachinePrincipalIsClampedToEvent() {
            fixture.usageEventWithMarker(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, "MESSAGE",
                new BigDecimal("10"), FinanceFixture.inMarch(10, 9), FinanceFixture.MARCH_1,
                FinanceFixture.MARCH_31, new BigDecimal("4.000000"), "USD");
            priceMessages();
            compute(FinanceFixture.MARCH_10, FinanceFixture.MARCH_10);
            TenantContext worker = FinancePrincipalScope.financeWorkerA();

            DrillThroughService.DrillThrough through = FinancePrincipalScope.call(worker,
                () -> drillThrough.drillBucket(worker, FinanceFixture.PROJECT_A,
                    RollupBucket.Grain.DAILY, FinanceFixture.MARCH_10, UsageDimension.MESSAGE,
                    DrillThroughLevel.PRINCIPAL, 200)).orElseThrow();

            assertThat(through.level())
                .as("the clamp is reported on the response, so a truncated drill-through is visible")
                .isEqualTo(DrillThroughLevel.EVENT);
            assertThat(through.events().get(0).principalName()).isNull();
        }

        @Test
        @DisplayName("the event list is bounded and says whether it was truncated")
        void theEventListIsBounded() {
            for (int index = 0; index < 250; index++) {
                fixture.message(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, BigDecimal.ONE,
                    FinanceFixture.inMarch(10, 9).plusSeconds(index), BigDecimal.ONE, "USD");
            }
            priceMessages();
            compute(FinanceFixture.MARCH_10, FinanceFixture.MARCH_10);

            DrillThroughService.DrillThrough through = FinancePrincipalScope.call(adminA,
                () -> drillThrough.drillBucket(adminA, FinanceFixture.PROJECT_A,
                    RollupBucket.Grain.DAILY, FinanceFixture.MARCH_10, UsageDimension.MESSAGE,
                    DrillThroughLevel.EVENT, 50)).orElseThrow();

            assertThat(through.totalSourceEvents()).isEqualTo(250);
            assertThat(through.events()).hasSize(50);
            assertThat(through.truncated())
                .as("a reader must not mistake the bound for the whole")
                .isTrue();
        }
    }

    // ==================================================================
    // periods and the legacy comparison
    // ==================================================================

    @Nested
    @DisplayName("periods and the legacy comparison are tenant-scoped")
    class PeriodsAndComparison {

        @Test
        @DisplayName("a foreign period is invisible, and its history is empty rather than an error")
        void aForeignPeriodIsInvisible() {
            UUID foreignPeriod = fixture.period(FinanceFixture.ORG_B, FinanceFixture.PROJECT_B,
                "2026-03-beta", FinanceFixture.MARCH_1, FinanceFixture.MARCH_31, "USD");

            assertThat(FinancePrincipalScope.call(adminA, () -> periods.find(adminA, foreignPeriod)))
                .isEmpty();
            assertThat(FinancePrincipalScope.call(adminA, () -> periods.history(adminA,
                foreignPeriod)))
                .as("the same empty answer as a nonexistent period")
                .isEmpty();
            assertThat(FinancePrincipalScope.call(adminA,
                () -> periods.list(adminA, null, 50)))
                .as("and it does not appear in the tenant's own list")
                .allSatisfy(period -> assertThat(period.organizationId())
                    .isEqualTo(FinanceFixture.ORG_A));
        }

        @Test
        @DisplayName("an adjustment cannot be read through another tenant's period")
        void anAdjustmentIsNotReadableAcrossTenants() {
            UUID periodId = fixture.period(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, "2026-03",
                FinanceFixture.MARCH_1, FinanceFixture.MARCH_31, "USD");
            FinancePrincipalScope.run(adminA, () -> periods.lock(adminA, periodId, "closed"));
            UUID adjustmentId = FinancePrincipalScope.call(adminA, () -> adjustments.propose(adminA,
                periodId, null, "a correction", new BigDecimal("1.000000"), "USD", null));

            assertThat(FinancePrincipalScope.call(adminB,
                () -> adjustments.find(adminB, adjustmentId))).isEmpty();
            assertThat(FinancePrincipalScope.call(adminB,
                () -> adjustments.list(adminB, periodId, null, 50))).isEmpty();
            assertThat(FinancePrincipalScope.call(adminA,
                () -> adjustments.list(adminA, periodId, null, 50)))
                .as("POSITIVE CONTROL: tenant A's own adjustment is readable")
                .hasSize(1);
        }

        @Test
        @DisplayName("the legacy comparison reads only the caller's own ai_usage rows")
        void theLegacyComparisonIsTenantScoped() {
            priceMessages();
            fixture.message(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, BigDecimal.ONE,
                FinanceFixture.inMarch(10, 9), new BigDecimal("4.000000"), "USD");
            compute(FinanceFixture.MARCH_10, FinanceFixture.MARCH_10);

            UUID runA = fixture.aiRun(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A,
                FinanceFixture.inMarch(10, 9));
            fixture.legacyUsage(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, runA, "gpt-4o-mini",
                100, 50, 0.005, FinanceFixture.inMarch(10, 9));
            UUID runB = fixture.aiRun(FinanceFixture.ORG_B, FinanceFixture.PROJECT_B,
                FinanceFixture.inMarch(10, 9));
            fixture.legacyUsage(FinanceFixture.ORG_B, FinanceFixture.PROJECT_B, runB, "gpt-4o",
                999_999, 999_999, 4242.0, FinanceFixture.inMarch(10, 9));

            var reportA = FinancePrincipalScope.call(adminA, () -> legacyComparison.compare(adminA,
                null, FinanceFixture.MARCH_1, FinanceFixture.MARCH_31, 50));
            var reportB = FinancePrincipalScope.call(adminB, () -> legacyComparison.compare(adminB,
                null, FinanceFixture.MARCH_1, FinanceFixture.MARCH_31, 50));

            assertThat(reportA.legacy().inputTokens()).isEqualTo(100);
            assertThat(reportA.legacy().outputTokens()).isEqualTo(50);
            assertThat(reportA.legacy().runCount()).isEqualTo(1);
            assertThat(reportA.models())
                .as("only the caller's own models")
                .hasSize(1);
            assertThat(reportA.runs()).hasSize(1);

            // POSITIVE CONTROL: tenant B really does have its own legacy rows, so tenant A's emptiness is
            // the filter and not an empty table.
            assertThat(reportB.legacy().inputTokens()).isEqualTo(999_999);
            assertThat(reportB.models()).hasSize(1);
        }
    }

    // ==================================================================
    // helpers
    // ==================================================================

    /** One priced message in each tenant, at deliberately different magnitudes. */
    private void seedUsageInBothTenants() {
        priceMessages(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, "USD", BigDecimal.ONE);
        fixture.message(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, new BigDecimal("10"),
            FinanceFixture.inMarch(10, 9), new BigDecimal("4.000000"), "USD");

        priceMessages(FinanceFixture.ORG_B, FinanceFixture.PROJECT_B, "USD", BigDecimal.ONE);
        fixture.message(FinanceFixture.ORG_B, FinanceFixture.PROJECT_B, new BigDecimal("10"),
            FinanceFixture.inMarch(10, 9), new BigDecimal("900.000000"), "USD");

        computeFor(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A);
        computeFor(FinanceFixture.ORG_B, FinanceFixture.PROJECT_B);
    }

    /** One priced message in each of tenant A's two projects, at different costs. */
    private void seedTenantAUsageInBothProjects() {
        priceMessages(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, "USD", BigDecimal.ONE);
        fixture.message(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, new BigDecimal("10"),
            FinanceFixture.inMarch(10, 9), new BigDecimal("4.000000"), "USD");
        fixture.message(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A2, new BigDecimal("10"),
            FinanceFixture.inMarch(10, 9), new BigDecimal("7.000000"), "USD");

        computeFor(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A);
        computeFor(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A2);
    }

    /** Seeds, prices and computes one bucket, returning the snapshot id. */
    private UUID seedAndCompute(UUID organizationId, UUID projectId, BigDecimal cost, UUID authorId) {
        priceMessages(organizationId, projectId, "USD", BigDecimal.ONE, authorId);
        fixture.message(organizationId, projectId, new BigDecimal("10"),
            FinanceFixture.inMarch(10, 9), cost, "USD");
        computeFor(organizationId, projectId);
        return jdbc.queryForObject("""
            select "id" from usage_rollup_snapshots
             where "organizationId" = :org and "projectId" = :project""",
            new MapSqlParameterSource().addValue("org", organizationId)
                .addValue("project", projectId), UUID.class);
    }

    private void priceMessages() {
        priceMessages(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, "USD",
            new BigDecimal("2"));
    }

    /**
     * An ACTIVE MESSAGE rate for one tenant and project.
     *
     * <p>The author must be a user of the card's own tenant — {@code rate_card_versions_author_tenant_fkey}
     * is a real cross-tenant foreign key — so callers name their own author.
     */
    private void priceMessages(UUID organizationId, UUID projectId, String currency, BigDecimal markup) {
        priceMessages(organizationId, projectId, currency, markup,
            FinanceFixture.ORG_B.equals(organizationId) ? FinanceFixture.ADMIN_B : FinanceFixture.ADMIN_A);
    }

    private void priceMessages(UUID organizationId, UUID projectId, String currency, BigDecimal markup,
                               UUID authorId) {
        // The card key carries the project, because a rate card is per-tenant: two projects in one
        // tenant cannot both hold version 1 of the same card.
        UUID version = fixture.activeVersionOnKey(organizationId, projectId,
            "standard-" + projectId, 1, currency, markup,
            FinanceFixture.MARCH_1, FinanceFixture.MARCH_31, "the March price", authorId);
        fixture.line(organizationId, projectId, version, "MESSAGE", "message", null, markup, currency);
    }

    private void compute(LocalDate from, LocalDate to) {
        FinancePrincipalScope.run(adminA, () -> rollups.computeDaily(adminA, FinanceFixture.PROJECT_A,
            from, to, LedgerPrincipal.job("test-rollup"), "corr-isolation"));
    }

    /**
     * Computes one bucket as a principal of the bucket's own tenant.
     *
     * <p>Not a convenience: {@code RollupService} authorizes the project against the caller's
     * memberships, so tenant B's snapshot can only be produced by tenant B's administrator. Computing
     * it as tenant A would be refused — which is the behaviour the sibling suite asserts, not a defect.
     */
    private void computeFor(UUID organizationId, UUID projectId) {
        TenantContext caller = principalFor(organizationId);
        FinancePrincipalScope.run(caller, () -> rollups.computeDaily(caller, projectId,
            FinanceFixture.MARCH_10, FinanceFixture.MARCH_10, LedgerPrincipal.job("test-rollup"),
            "corr-isolation-" + organizationId));
    }

    private TenantContext principalFor(UUID organizationId) {
        return FinanceFixture.ORG_B.equals(organizationId) ? adminB : adminA;
    }

    private int snapshotCount(UUID organizationId) {
        return fixture.count("""
            select 1 from usage_rollup_snapshots where "organizationId" = :org""",
            Map.of("org", organizationId));
    }
}
