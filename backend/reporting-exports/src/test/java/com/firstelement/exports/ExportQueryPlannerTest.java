package com.firstelement.exports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstelement.exports.catalog.ReportField;
import com.firstelement.exports.catalog.ReportFilterField;
import com.firstelement.exports.catalog.ReportSubject;
import com.firstelement.exports.plan.ExportFilter;
import com.firstelement.exports.plan.ExportQueryPlan;
import com.firstelement.exports.plan.ExportQueryPlanner;
import com.firstelement.exports.plan.FilterNotPushableException;
import com.firstelement.exports.redaction.ResolvedExportColumns;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Policy before aggregation, and the refusal of anything that cannot be pushed.
 *
 * <h2>What "before aggregation" is asserted as</h2>
 *
 * <p>Not as a claim about the process but as a claim about the FINISHED STATEMENT: the tenant and project
 * predicates appear in the same {@code SELECT} that computes the {@code count}/{@code sum}, and they appear
 * textually before any {@code group by}. A predicate that lands after an aggregate has already included the
 * rows it was meant to exclude, which is precisely the FR-8.2 failure — so the offset comparison below is
 * the assertion that makes the claim checkable rather than aspirational.
 *
 * <h2>What cannot be expressed is asserted too</h2>
 *
 * <p>Half of this suite is negative, and that is the point: a planner that is only ever tested on valid
 * input has not been shown to have a boundary. Every "no raw SQL, no arbitrary column, no free-form sort"
 * claim is a test here rather than a comment on an interface.
 */
@DisplayName("export planning applies tenant, row and field policy before aggregation, and refuses the rest")
class ExportQueryPlannerTest {

    private final ExportQueryPlanner planner = new ExportQueryPlanner();

    private static final ResolvedExportColumns CUSTOMER_COLUMNS =
        PlanShapeAssertions.employeeCustomerColumns();

    /**
     * The conversation column set, used wherever a {@code conversation.*} filter or sort is exercised.
     *
     * <p>A separate constant because a filter for one subject on a version of another is itself a refusal —
     * the planner enforces that, so a fixture that mixed the two would silently test the refusal instead of
     * the filter.
     */
    private static final ResolvedExportColumns CONVERSATION_COLUMNS =
        PlanShapeAssertions.employeeConversationColumns();

    // ==================================================================
    // policy before aggregation
    // ==================================================================

    @Nested
    @DisplayName("the tenant predicate is in the statement that aggregates")
    class PolicyBeforeAggregation {

        @Test
        @DisplayName("a project-scoped aggregate carries both tenant predicates and no grouping leak")
        void tenantPredicateIsPresent() {
            ExportQueryPlan plan = planner.plan(PlanShapeAssertions.adminScope(),
                PlanShapeAssertions.adminUsageColumns(), List.of(), List.of(), List.of(), List.of());

            assertThat(plan.sql())
                .as("an unauthorized row must never enter a count or a sum")
                .contains(PlanShapeAssertions.tenantFragment("s", "organizationId"))
                .contains(PlanShapeAssertions.tenantFragment("s", "projectId"));
            assertThat(PlanShapeAssertions.hasTenantPredicate(plan)).isTrue();
            assertThat(PlanShapeAssertions.hasProjectPredicate(plan)).isTrue();
        }

        @Test
        @DisplayName("the tenant predicate is TEXTUALLY before the group by, not after the aggregate")
        void tenantPredicatePrecedesTheGroupBy() {
            ExportQueryPlan plan = planner.plan(PlanShapeAssertions.adminScope(),
                PlanShapeAssertions.adminUsageColumns(), List.of(), List.of(),
                List.of("usage.dimension:desc"), List.of());

            assertThat(plan.sql())
                .as("this version aggregates, so the statement has a GROUP BY to be after")
                .contains("group by");

            int tenantAt = PlanShapeAssertions.offsetOf(plan,
                PlanShapeAssertions.tenantFragment("s", "organizationId"));
            int groupByAt = PlanShapeAssertions.offsetOf(plan, "group by");

            assertThat(tenantAt)
                .as("a predicate after a GROUP BY has already let the excluded rows into the totals, "
                    + "which is the FR-8.2 failure stated as an offset")
                .isLessThan(groupByAt);
        }

        @Test
        @DisplayName("the parameters are tenant-first, so a caller map cannot shadow the filter")
        void parametersAreTenantFirst() {
            var scope = PlanShapeAssertions.adminScope();
            ExportQueryPlan plan = planner.plan(scope, CUSTOMER_COLUMNS,
                List.of(ExportFilter.of(ReportFilterField.CUSTOMER_LOCALE, "ar")), List.of(), List.of(),
                List.of());

            assertThat(plan.parameters())
                .as("ScopedWrite writes the tenant values LAST, so a caller parameter of the same name is "
                    + "refused rather than honoured. The parameters therefore arrive tenant-first")
                .startsWith(scope.organizationId(), scope.projectId());
            assertThat(plan.parameters()).hasSize(3);
        }

        @Test
        @DisplayName("a tenant-scoped export carries the tenant predicate and not the project one")
        void tenantScopeOmitsTheProjectPredicate() {
            var tenantWide = new com.firstelement.security.query.TenantScope(
                PlanShapeAssertions.adminScope().organizationId(), null);
            ExportQueryPlan plan = planner.plan(tenantWide, CUSTOMER_COLUMNS, List.of(), List.of(),
                List.of(), List.of());

            assertThat(plan.sql())
                .contains(PlanShapeAssertions.tenantFragment("c", "organizationId"));
            assertThat(plan.sql())
                .as("a tenant-wide export is still tenant-scoped; the project predicate is absent only "
                    + "because the scope did not name one")
                .doesNotContain(PlanShapeAssertions.tenantFragment("c", "projectId"));
        }

        @Test
        @DisplayName("a plan cannot be built without a scope at all")
        void scopeIsRequired() {
            assertThatThrownBy(() -> planner.plan(null, CUSTOMER_COLUMNS, List.of(), List.of(), List.of(),
                List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TenantScope");
        }
    }

    // ==================================================================
    // filter push-down
    // ==================================================================

    @Nested
    @DisplayName("every filter is pushed, or the export is refused")
    class FilterPushdown {

        @Test
        @DisplayName("an IN filter's placeholders are in the statement and its values are bound")
        void inFilterIsPushed() {
            ExportQueryPlan plan = planner.plan(PlanShapeAssertions.adminScope(), CONVERSATION_COLUMNS,
                List.of(), List.of(ExportFilter.of(ReportFilterField.CONVERSATION_STATUS,
                    List.of("OPEN", "RESOLVED"))), List.of(), List.of());

            assertThat(plan.sql()).contains("cv.\"status\"::text in (?, ?)");
            assertThat(plan.parameters())
                .as("tenant, project, then the filter values in placeholder order")
                .hasSize(4);
            assertThat(plan.parameters().subList(2, 4)).containsExactly("OPEN", "RESOLVED");
        }

        @Test
        @DisplayName("a date-range filter is pushed with its own comparison, never the other way round")
        void dateFilterKeepsItsDirection() {
            ExportQueryPlan plan = planner.plan(PlanShapeAssertions.adminScope(), CONVERSATION_COLUMNS,
                List.of(), List.of(
                    ExportFilter.of(ReportFilterField.CONVERSATION_OPENED_FROM, "2026-03-01"),
                    ExportFilter.of(ReportFilterField.CONVERSATION_OPENED_TO, "2026-03-31")),
                List.of(), List.of());

            assertThat(plan.sql())
                .as("the key names the comparison, so a caller cannot turn 'from' into 'to'")
                .contains("cv.\"createdAt\" >= ?")
                .contains("cv.\"createdAt\" <= ?");
        }

        @Test
        @DisplayName("an undeclared filter key resolves to nothing at all")
        void undeclaredFilterCannotBeExpressed() {
            // There is no way to NAME an undeclared comparison: ReportFilterField is a closed enum and
            // ExportFilter's constructor takes one of its constants. So the assertion is about the lookup:
            // an injected comparison string is simply not a declared key, and the vocabulary is small
            // enough to read in one sitting.
            assertThat(ReportFilterField.byKey("conversation.status = 'OPEN' --").isPresent())
                .as("an injected comparison is not a declared key, so there is nothing to construct a "
                    + "filter from")
                .isFalse();
            assertThat(ReportFilterField.byKey(null).isPresent()).isFalse();
            assertThat(ReportFilterField.byKey("").isPresent()).isFalse();

            assertThat(ReportFilterField.index().keySet())
                .as("the whole vocabulary is closed and reviewable, and no entry is a query fragment")
                .hasSize(ReportFilterField.values().length)
                .allSatisfy(key -> assertThat(key).matches("[a-z][a-zA-Z.]+"))
                .doesNotContain("conversation.rawSql", "customers.passwordHash");
        }

        @Test
        @DisplayName("a filter for another subject is refused, not silently dropped")
        void crossSubjectFilterIsRefused() {
            assertThatThrownBy(() -> planner.plan(PlanShapeAssertions.adminScope(), CUSTOMER_COLUMNS,
                List.of(), List.of(ExportFilter.of(ReportFilterField.USAGE_DIMENSION, "MESSAGE")),
                List.of(), List.of()))
                .as("a report that silently ignored a filter would answer a question nobody asked")
                .isInstanceOf(FilterNotPushableException.class)
                .hasMessageContaining("usage.dimension")
                .hasMessageContaining("CUSTOMERS");
        }

        @Test
        @DisplayName("a null filter value is refused rather than compiled to '= null'")
        void nullFilterValueIsRefused() {
            // List.of refuses a null element itself, so the value arrives through the list constructor.
            // Either refusal is correct — what must not happen is a filter that compiles to "= null" and
            // silently returns no rows, which reads as "this project has no data". FilterNotPushableException
            // is the type the HTTP layer turns into 422, and it extends RuntimeException, so the assertion
            // names it rather than the looser IllegalArgumentException it is not.
            assertThatThrownBy(() -> new ExportFilter(ReportFilterField.CONVERSATION_STATUS,
                java.util.Arrays.asList("OPEN", null)))
                .isInstanceOf(FilterNotPushableException.class)
                .hasMessageContaining("never true in SQL");
        }

        @Test
        @DisplayName("a blank filter value is refused")
        void blankFilterValueIsRefused() {
            assertThatThrownBy(() -> ExportFilter.fromStored(ReportFilterField.CONVERSATION_STATUS, "  "))
                .isInstanceOf(FilterNotPushableException.class)
                .hasMessageContaining("blank");
        }

        @Test
        @DisplayName("an IN filter is bounded, so a long list cannot become a statement of its own")
        void inFilterIsBounded() {
            List<Object> tooMany = new ArrayList<>();
            for (int i = 0; i <= ReportFilterField.ExportFilterBounds.MAX_IN_VALUES; i++) {
                tooMany.add("value-" + i);
            }

            assertThatThrownBy(() -> ExportFilter.of(ReportFilterField.CONVERSATION_STATUS, tooMany))
                .as("a refused filter raises FilterNotPushableException, which the job records as a FAILED "
                    + "attempt rather than as an IllegalArgumentException that never reaches the operator")
                .isInstanceOf(FilterNotPushableException.class)
                .hasMessageContaining("at most");
        }

        @Test
        @DisplayName("the filter list as a whole is bounded")
        void filterListIsBounded() {
            assertThat(ReportFilterField.ExportFilterBounds.MAX_FILTERS)
                .as("a budget at or above the whole declared vocabulary could never bind, which makes it "
                    + "decoration rather than a limit")
                .isLessThan(ReportFilterField.values().length);

            // Every entry is a DISTINCT key: merge deduplicates by key, so a repeated key would collapse
            // to one and the budget would be met without ever being exercised.
            List<ExportFilter> atBudget = filtersFor(ReportFilterField.ExportFilterBounds.MAX_FILTERS);
            List<ExportFilter> overBudget = filtersFor(ReportFilterField.ExportFilterBounds.MAX_FILTERS + 1);

            assertThat(atBudget)
                .as("the budget itself is allowed — a limit that refuses a legitimate list is not a limit")
                .hasSize(ReportFilterField.ExportFilterBounds.MAX_FILTERS);
            assertThat(overBudget)
                .hasSize(ReportFilterField.ExportFilterBounds.MAX_FILTERS + 1)
                .extracting(filter -> filter.field().key())
                .doesNotHaveDuplicates();

            assertThat(ExportFilter.merge(atBudget, List.of()))
                .as("declared defaults alone may use the whole budget")
                .hasSize(ReportFilterField.ExportFilterBounds.MAX_FILTERS);

            assertThatThrownBy(() -> ExportFilter.merge(overBudget, List.of()))
                .as("a statement with more predicates than the budget is a query language in disguise")
                .isInstanceOf(FilterNotPushableException.class)
                .hasMessageContaining("at most");

            assertThatThrownBy(() -> ExportFilter.merge(List.of(), overBudget))
                .as("and the bound is on each incoming list, not only on the merged result: a caller "
                    + "sending hundreds of duplicate keys would otherwise never meet it")
                .isInstanceOf(FilterNotPushableException.class);
        }

        /**
         * The first {@code count} declared comparisons, each with a value its own comparison accepts.
         *
         * <p>Built by arity rather than by a hand-written value, because a {@code null} test takes no
         * value and passing one would be refused for a reason unrelated to the budget being tested.
         */
        private List<ExportFilter> filtersFor(int count) {
            List<ExportFilter> filters = new ArrayList<>(count);
            for (ReportFilterField field : ReportFilterField.values()) {
                if (filters.size() == count) {
                    break;
                }
                filters.add(field.comparison() == ReportFilterField.Comparison.IS_NOT_NULL
                    ? ExportFilter.isNotNull(field)
                    : ExportFilter.of(field, "value-" + field.key()));
            }
            return filters;
        }

        @Test
        @DisplayName("a null test takes no value, so it cannot smuggle one in")
        void nullTestTakesNoValue() {
            // Asserted on conversation.resolved, which is the one declared IS_NOT_NULL comparison. The
            // check lives in ExportFilter.fromStored (the stored-shape reader), because the constructor
            // validates arity only when the comparison needs values. Asserting on the reader is asserting
            // on the path a report version's jsonb actually takes.
            assertThat(ReportFilterField.CONVERSATION_RESOLVED.comparison())
                .as("this is the comparison under test; if the registry renames it the test says so "
                    + "rather than silently passing against a comparison that no longer exists")
                .isEqualTo(ReportFilterField.Comparison.IS_NOT_NULL);

            assertThatThrownBy(() -> ExportFilter.fromStored(ReportFilterField.CONVERSATION_RESOLVED,
                "true"))
                .as("a null test is stored as the boolean true; a string is a different shape and is refused")
                .isInstanceOf(FilterNotPushableException.class)
                .hasMessageContaining("boolean true");

            assertThatThrownBy(() -> ExportFilter.fromStored(ReportFilterField.CONVERSATION_RESOLVED,
                java.util.Map.of("value", "anything")))
                .as("and an object where a scalar belongs is the shape a query language would have")
                .isInstanceOf(FilterNotPushableException.class);
        }

        @Test
        @DisplayName("a request filter replaces the version's declared default on the same key")
        void requestFilterOverridesDeclaredDefault() {
            ExportFilter declared = ExportFilter.of(ReportFilterField.CONVERSATION_STATUS, "OPEN");
            ExportFilter requested = ExportFilter.of(ReportFilterField.CONVERSATION_STATUS, "RESOLVED");

            List<ExportFilter> merged = ExportFilter.merge(List.of(declared), List.of(requested));

            assertThat(merged)
                .as("one entry per key, so the declared default does not ALSO apply")
                .hasSize(1);
            assertThat(merged.get(0).values()).containsExactly("RESOLVED");
        }
    }

    // ==================================================================
    // the closed vocabulary
    // ==================================================================

    @Nested
    @DisplayName("no arbitrary column list, no free-form sort, no query text")
    class ClosedVocabulary {

        @Test
        @DisplayName("an undeclared column is refused by the registry, before any statement exists")
        void undeclaredColumnIsRefused() {
            assertThatThrownBy(() -> com.firstelement.exports.catalog.ReportFieldRegistry
                .resolve(List.of("customer.passwordHash")))
                .isInstanceOf(com.firstelement.exports.catalog.ReportFieldRegistry
                    .UnknownReportFieldException.class)
                .hasMessageContaining("customer.passwordHash");

            assertThatThrownBy(() -> com.firstelement.exports.catalog.ReportFieldRegistry
                .resolve(List.of("c.*, (select passwordHash from users)")))
                .isInstanceOf(com.firstelement.exports.catalog.ReportFieldRegistry
                    .UnknownReportFieldException.class);
        }

        @Test
        @DisplayName("no declared field exposes a credential column, so none can be selected")
        void noCredentialFieldExists() {
            for (ReportField field : ReportField.values()) {
                assertThat(field.expression())
                    .as("%s selects a credential column, which no projection may do", field.key())
                    .doesNotContainIgnoringCase("passwordHash")
                    .doesNotContainIgnoringCase("tokenHash")
                    .doesNotContainIgnoringCase("credentials")
                    .doesNotContainIgnoringCase("secret");
            }
        }

        @Test
        @DisplayName("a version mixing two subjects is refused rather than joined")
        void mixedSubjectsAreRefused() {
            assertThatThrownBy(() -> com.firstelement.exports.catalog.ReportFieldRegistry
                .resolve(List.of("customer.displayName", "usage.cost")))
                .isInstanceOf(com.firstelement.exports.catalog.ReportFieldRegistry
                    .MixedSubjectException.class)
                .hasMessageContaining("join");
        }

        @Test
        @DisplayName("a duplicated column is refused, because it makes the column list ambiguous")
        void duplicatedColumnIsRefused() {
            assertThatThrownBy(() -> com.firstelement.exports.catalog.ReportFieldRegistry
                .resolve(List.of("customer.displayName", "customer.displayName")))
                .isInstanceOf(com.firstelement.exports.catalog.ReportFieldRegistry
                    .UnknownReportFieldException.class)
                .hasMessageContaining("twice");
        }

        @Test
        @DisplayName("an empty emitted column set is refused: a header row with no columns is not a report")
        void emptyColumnListIsRefused() {
            ResolvedExportColumns nothing = new ResolvedExportColumns(List.of(), List.of(),
                com.firstelement.exports.catalog.ExportRedactionPolicy.NONE, List.of());

            assertThatThrownBy(() -> planner.plan(PlanShapeAssertions.adminScope(), nothing,
                List.of(), List.of(), List.of(), List.of()))
                .as("every column was withheld from this caller, so the file would have a header row and "
                    + "no data — indistinguishable from a genuinely empty report")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no emitted columns");

            assertThatThrownBy(() -> com.firstelement.exports.catalog.ReportFieldRegistry
                .resolve(List.of()))
                .as("and the registry refuses to resolve an empty declared list in the first place")
                .isInstanceOf(com.firstelement.exports.catalog.ReportFieldRegistry
                    .UnknownReportFieldException.class);
        }

        @Test
        @DisplayName("a sort on a column the export does not emit is refused")
        void sortOnUnemittedColumnIsRefused() {
            // customer.primaryEmail is a DECLARED field but is NOT in this emitted set, so sorting by it is
            // an ordering oracle over data this caller cannot see. It must be an error, not an ignored sort.
            assertThat(PlanShapeAssertions.customerNameAndCountOnly().emitted())
                .as("the fixture really does omit the contact column, or the assertion below is vacuous")
                .extracting(field -> field.key())
                .doesNotContain("customer.primaryEmail");

            assertThatThrownBy(() -> planner.plan(PlanShapeAssertions.adminScope(),
                PlanShapeAssertions.customerNameAndCountOnly(),
                List.of(), List.of(), List.of("customer.primaryEmail:asc"), List.of()))
                .isInstanceOf(ExportQueryPlanner.SortNotProjectableException.class)
                .hasMessageContaining("customer.primaryEmail");
        }

        @Test
        @DisplayName("a sort direction is asc or desc, and nothing else")
        void sortDirectionIsClosed() {
            assertThatThrownBy(() -> planner.plan(PlanShapeAssertions.adminScope(),
                PlanShapeAssertions.adminUsageColumns(),
                List.of(), List.of(), List.of("usage.dimension:sideways"), List.of()))
                .isInstanceOf(ExportQueryPlanner.SortNotProjectableException.class)
                .hasMessageContaining("asc");

            assertThatThrownBy(() -> planner.plan(PlanShapeAssertions.adminScope(),
                PlanShapeAssertions.adminUsageColumns(),
                List.of(), List.of(), List.of("usage.dimension'; drop table users; --"), List.of()))
                .isInstanceOf(ExportQueryPlanner.SortNotProjectableException.class);
        }

        @Test
        @DisplayName("a sort on a measure is refused when the statement aggregates")
        void sortOnMeasureIsRefusedForAggregates() {
            assertThatThrownBy(() -> planner.plan(PlanShapeAssertions.adminScope(),
                PlanShapeAssertions.adminUsageColumns(),
                List.of(), List.of(), List.of("usage.cost:desc"), List.of()))
                .isInstanceOf(ExportQueryPlanner.SortNotProjectableException.class)
                .hasMessageContaining("grouping column");
        }
    }

    // ==================================================================
    // the finance subject
    // ==================================================================

    @Nested
    @DisplayName("the usage subject reads only the current generation, and revenue is never silently zero")
    class FinanceSubject {

        @Test
        @DisplayName("the anti-join on supersededSnapshotId is present, before the tenant predicate")
        void currentGenerationOnly() {
            ExportQueryPlan plan = planner.plan(PlanShapeAssertions.adminScope(),
                PlanShapeAssertions.adminUsageColumns(), List.of(), List.of(), List.of(), List.of());

            assertThat(plan.sql())
                .as("a superseded generation must never appear, or the export and the dashboard disagree")
                .contains("s.\"supersedesSnapshotId\" is null");
            assertThat(ReportSubject.USAGE_LEDGER.intrinsicPredicate())
                .isNotNull();
        }

        @Test
        @DisplayName("an unpriced bucket makes revenue null rather than a summed zero")
        void unpricedRevenueIsNull() {
            ReportField revenue = ReportField.USAGE_REVENUE;

            assertThat(revenue.expression())
                .as("ADR 0007 rule 5: unpriced is not zero. A sum over revenueAmount would treat an unpriced "
                    + "bucket as contributing nothing, which reads as a priced zero")
                .contains("rateCardVersionId")
                .contains("then null");
        }

        @Test
        @DisplayName("margin is derived in the statement as revenue minus cost, never stored")
        void marginIsDerived() {
            ReportField margin = ReportField.USAGE_MARGIN;

            assertThat(margin.expression())
                .as("ADR 0007: revenue is derived at rollup time and margin from it. A stored margin column "
                    + "would give the ledger two answers for one figure")
                .contains("revenueAmount")
                .contains("costAmount");
        }

        @Test
        @DisplayName("a money column with no currency column beside it is refused at authoring time")
        void moneyNeedsCurrency() {
            List<ReportField> moneyOnly = List.of(ReportField.USAGE_COST);

            assertThat(com.firstelement.exports.format.ExportValueFormatter
                .hasCurrencyBesideMoney(moneyOnly))
                .as("ADR 0007 rule 6: there is no amount without a currency")
                .isFalse();

            List<ReportField> withCurrency = List.of(ReportField.USAGE_COST,
                ReportField.USAGE_CURRENCY);
            assertThat(com.firstelement.exports.format.ExportValueFormatter
                .hasCurrencyBesideMoney(withCurrency)).isTrue();
        }

        @Test
        @DisplayName("a locked period's keys are carried on the plan, so the job records why it was gated")
        void lockedPeriodsTravelWithThePlan() {
            ExportQueryPlan plan = planner.plan(PlanShapeAssertions.adminScope(),
                PlanShapeAssertions.adminUsageColumns(), List.of(), List.of(), List.of(),
                List.of("2026-03"));

            assertThat(plan.lockedFinancePeriodKeys())
                .as("which locked periods applied is part of the export's evidence")
                .containsExactly("2026-03");
        }
    }

    // ==================================================================
    // declared metadata
    // ==================================================================

    @Nested
    @DisplayName("the emitted column order and metadata are stable")
    class StableMetadata {

        @Test
        @DisplayName("the plan's field order is the version's declared order, with ordinal aliases")
        void columnOrderIsStable() {
            List<ReportField> declared = List.of(ReportField.USAGE_DIMENSION, ReportField.USAGE_COST,
                ReportField.USAGE_CURRENCY);
            ResolvedExportColumns columns = PlanShapeAssertions.ExportRedactionResolverFixture
                .resolve(declared,
                    com.firstelement.exports.catalog.ExportRedactionPolicy.NONE,
                    PlanShapeAssertions.adminPermissions());

            ExportQueryPlan plan = planner.plan(PlanShapeAssertions.adminScope(), columns, List.of(),
                List.of(), List.of(), List.of());

            assertThat(plan.fields()).containsExactlyElementsOf(declared);
            assertThat(plan.sql())
                .as("each expression is aliased by its output position, which is what GROUP BY and ORDER BY "
                    + "refer to")
                .contains("as fe_col_1")
                .contains("as fe_col_2")
                .contains("as fe_col_3");
            assertThat(plan.fieldAt(2)).isEqualTo(ReportField.USAGE_COST);
        }

        @Test
        @DisplayName("the bilingual column metadata carries both labels and no SQL expression")
        void columnMetadataIsBilingualAndOpaque() {
            List<Map<String, String>> metadata =
                com.firstelement.exports.format.ExportValueFormatter.columnMetadata(
                    List.of(ReportField.CUSTOMER_DISPLAY_NAME));

            assertThat(metadata).hasSize(1);
            Map<String, String> entry = metadata.get(0);
            assertThat(entry)
                .containsEntry("key", "customer.displayName")
                .containsEntry("labelAr", "اسم العميل")
                .containsEntry("labelEn", "Customer name")
                .containsEntry("requiredPermission", "customer:read")
                .as("the metadata a client receives names the KEY; it never carries the SQL expression, "
                    + "because a client that could read the expression could choose columns")
                .doesNotContainValue("c.\"displayName\"");
        }

        @Test
        @DisplayName("the header row follows the plan's locale and order")
        void headerFollowsTheLocale() {
            List<ReportField> fields = List.of(ReportField.CUSTOMER_DISPLAY_NAME,
                ReportField.CUSTOMER_COUNT);

            assertThat(com.firstelement.exports.format.ExportValueFormatter
                .headerRow(fields, Locale.ENGLISH))
                .containsExactly("Customer name", "Customer count");
            assertThat(com.firstelement.exports.format.ExportValueFormatter
                .headerRow(fields, new Locale("ar")))
                .containsExactly("اسم العميل", "عدد العملاء");
        }
    }

    @Test
    @DisplayName("the plan records the filters that ran, not the ones that were asked for")
    void planRecordsAppliedFilters() {
        List<ExportFilter> declared = List.of(
            ExportFilter.of(ReportFilterField.CONVERSATION_STATUS, "OPEN"));
        List<ExportFilter> requested = List.of(
            ExportFilter.of(ReportFilterField.CONVERSATION_CHANNEL, "MOCK"));

        ExportQueryPlan plan = planner.plan(PlanShapeAssertions.adminScope(), CONVERSATION_COLUMNS,
            declared, requested, List.of(), List.of());

        assertThat(plan.appliedFiltersAsJson())
            .as("the job stores what was PUSHED, both the version's default and the caller's, so 'what "
                + "did this export apply' is answerable from the job row without re-running anything")
            .hasSize(2)
            // Both keys are IN comparisons, so each stores the array form. That is the shape
            // ExportFilter.fromStored reads back, which is what makes a stored filter re-runnable.
            .containsEntry("conversation.status", List.of("OPEN"))
            .containsEntry("conversation.channel", List.of("MOCK"))
            .as("and nothing that was merely requested without being pushed");
        assertThat(plan.appliedFilters()).hasSize(2);

        assertThat(ExportFilter.fromStored(ReportFilterField.CONVERSATION_STATUS, List.of("OPEN")))
            .as("what the job stored is what fromStored reads, so a re-run of a pinned version applies "
                + "exactly the same filters rather than a best guess at them")
            .isEqualTo(ExportFilter.of(ReportFilterField.CONVERSATION_STATUS, "OPEN"));
    }
}