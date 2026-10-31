package com.firstelement.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstelement.finance.catalog.FinanceMetricCatalogue;
import com.firstelement.finance.catalog.UsageDimension;
import com.firstelement.finance.ledger.LedgerPrincipal;
import com.firstelement.finance.ledger.LedgerPrincipalType;
import com.firstelement.finance.ledger.UsageEvent;
import com.firstelement.finance.ledger.UsageEventFactory;
import com.firstelement.finance.ledger.UsageEventWriter;
import com.firstelement.finance.ledger.UsageWriteResult;
import com.firstelement.finance.money.FxAttribution;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Immutable usage-event emission: idempotency, the taxonomy, cost, FX, and the same-transaction rule.
 *
 * <h2>What these tests are actually proving</h2>
 *
 * <p>That {@code usage_events} is a ledger rather than a log. A log accepts whatever it is given; a
 * ledger refuses a row it cannot interpret, refuses a second row for the same thing, and refuses a row
 * that would not have been committed with the thing it describes. Each of those is a negative here,
 * with a positive control alongside so a refusal cannot be mistaken for "this table rejects everything".
 */
@SpringBootTest(classes = FinanceTestApplication.class)
class UsageEventWriterIntegrationTest extends FinanceIntegrationTestBase {

    @Autowired
    private UsageEventWriter writer;
    @Autowired
    private UsageTransactionCaller caller;
    @Autowired
    private UsageTransactionCaller.RollingBackCaller rollingBack;
    @Autowired
    private UsageTransactionCaller.CommittedCaller committing;
    @Autowired
    private FinanceFixture fixture;
    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        fixture.reset();
    }

    // ==================================================================
    // idempotency
    // ==================================================================

    @Nested
    @DisplayName("a retried job cannot double-emit")
    class Idempotency {

        @Test
        @DisplayName("the same source row in the same dimension is written once")
        void theSameSourceEventIsWrittenOnce() {
            UUID sourceId = UUID.randomUUID();

            UsageWriteResult first = record(delivered(sourceId, new BigDecimal("1.00")));
            UsageWriteResult second = record(delivered(sourceId, new BigDecimal("1.00")));

            assertThat(first.isNew()).as("the first write inserts").isTrue();
            assertThat(second.isDuplicate()).as("the retry is suppressed, not inserted").isTrue();
            assertThat(first.eventId()).as("both name the same row").isEqualTo(second.eventId());
            assertThat(messageCount(sourceId)).isEqualTo(1);
        }

        @Test
        @DisplayName("a suppressed duplicate reports the row the database kept, not the one it refused")
        void aSuppressedDuplicateReportsTheStoredRow() {
            UUID sourceId = UUID.randomUUID();
            record(delivered(sourceId, new BigDecimal("1.00")));

            // A redelivered webhook carrying a different quantity must not be reported as if its own
            // values won. This is the assertion that would fail with a bare boolean result.
            UsageWriteResult retry = record(delivered(sourceId, new BigDecimal("7.00")));

            assertThat(retry.isDuplicate()).isTrue();
            assertThat(retry.event().quantity())
                .as("the stored quantity, not the attempted one")
                .isEqualByComparingTo("1.00");
            assertThat(messageCount(sourceId)).isEqualTo(1);
        }

        @Test
        @DisplayName("both outcomes are successes, so a retry is not an error to alert on")
        void bothOutcomesAreSuccesses() {
            UUID sourceId = UUID.randomUUID();

            UsageWriteResult first = record(delivered(sourceId, BigDecimal.ONE));
            UsageWriteResult second = record(delivered(sourceId, BigDecimal.ONE));

            assertThat(first.succeeded()).isTrue();
            assertThat(second.succeeded()).isTrue();
            assertThat(second.isNew()).as("recorded, not inserted").isFalse();
            assertThat(second.recorded()).isTrue();
        }

        @Test
        @DisplayName("one source row may produce two dimensions: an AI run is a request and tokens")
        void oneSourceRowMayProduceTwoDimensions() {
            UUID runId = UUID.randomUUID();

            // POSITIVE CONTROL for the suppression key's dimension component. Without the dimension in
            // the key these would collide and the second would be dropped, which is data loss on the
            // most expensive dimension in the catalogue.
            UsageWriteResult request = record(UsageEventFactory.aiRequest(
                FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, runId,
                FinanceFixture.inMarch(10, 9), FinanceFixture.MARCH_1, FinanceFixture.MARCH_31,
                LedgerPrincipal.job("ai-run-recorder"), "USD"));
            UsageWriteResult tokens = record(UsageEventFactory.aiToken(
                FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, runId, new BigDecimal("1500"),
                FinanceFixture.inMarch(10, 9), FinanceFixture.MARCH_1, FinanceFixture.MARCH_31,
                LedgerPrincipal.job("ai-run-recorder"), "USD"));

            assertThat(request.isNew()).isTrue();
            assertThat(tokens.isNew()).as("a token event is not a duplicate of the request event")
                .isTrue();
            assertThat(countFor(runId, "AI_REQUEST")).isEqualTo(1);
            assertThat(countFor(runId, "AI_TOKEN")).isEqualTo(1);
        }

        @Test
        @DisplayName("the suppression key is tenant-scoped, so two tenants may share a source id")
        void theSuppressionKeyIsTenantScoped() {
            // The migration's reason for including organizationId in the key: a global key would collide
            // two tenants whose providers both emit "evt-1", and the violation would disclose to the
            // second tenant that the first has an event of that name.
            UUID sharedSourceId = UUID.randomUUID();

            UsageWriteResult inA = record(UsageEventFactory.message(
                FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, sharedSourceId, BigDecimal.ONE,
                FinanceFixture.inMarch(10, 9), FinanceFixture.MARCH_1, FinanceFixture.MARCH_31,
                LedgerPrincipal.job("carrier-meter"), "USD", null, null));
            UsageWriteResult inB = record(UsageEventFactory.message(
                FinanceFixture.ORG_B, FinanceFixture.PROJECT_B, sharedSourceId, BigDecimal.ONE,
                FinanceFixture.inMarch(10, 9), FinanceFixture.MARCH_1, FinanceFixture.MARCH_31,
                LedgerPrincipal.job("carrier-meter"), "USD", null, null));

            assertThat(inA.isNew()).isTrue();
            assertThat(inB.isNew()).as("the same source id in another tenant is a different event")
                .isTrue();
        }

        private int messageCount(UUID sourceId) {
            return fixture.count(
                "select 1 from usage_events where \"sourceEventId\" = :id",
                Map.of("id", sourceId));
        }
    }

    // ==================================================================
    // the taxonomy
    // ==================================================================

    @Nested
    @DisplayName("the metric catalogue is enforced, not documented")
    class Taxonomy {

        @Test
        @DisplayName("an undeclared source event type is refused")
        void anUndeclaredSourceEventTypeIsRefused() {
            UsageEvent rogue = UsageEvent.builder(
                    FinanceFixture.ORG_A, FinanceFixture.PROJECT_A)
                .dimension(UsageDimension.MESSAGE)
                .quantity(BigDecimal.ONE)
                .unit(UsageDimension.MESSAGE.canonicalUnit())
                .sourceEventType("messages.something_nobody_declared")
                .sourceEventId(UUID.randomUUID())
                .occurredAt(FinanceFixture.inMarch(10, 9))
                .bucket(FinanceFixture.MARCH_1, FinanceFixture.MARCH_31)
                .principal(LedgerPrincipal.job("rogue"))
                .currency("USD")
                .build();

            assertThatThrownBy(() -> record(rogue))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finance metric catalogue");
        }

        @Test
        @DisplayName("a source event type belonging to another dimension is refused")
        void aSourceTypeFromAnotherDimensionIsRefused() {
            // A MESSAGE event claiming to come from ai_runs.started. The catalogue says that source type
            // produces AI_REQUEST, so accepting it would make "count the messages" ambiguous — and the
            // check is on the writer, not on the factory, because a hand-built event must be refused
            // too.
            UsageEvent crossed = UsageEvent.builder(
                    FinanceFixture.ORG_A, FinanceFixture.PROJECT_A)
                .dimension(UsageDimension.MESSAGE)
                .quantity(BigDecimal.ONE)
                .unit(UsageDimension.MESSAGE.canonicalUnit())
                .sourceEventType("ai_runs.started")
                .sourceEventId(UUID.randomUUID())
                .occurredAt(FinanceFixture.inMarch(10, 9))
                .bucket(FinanceFixture.MARCH_1, FinanceFixture.MARCH_31)
                .principal(LedgerPrincipal.job("rogue"))
                .currency("USD")
                .build();

            assertThatThrownBy(() -> record(crossed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not produce MESSAGE");
        }

        @Test
        @DisplayName("a dimension the taxonomy prices from a rate card cannot carry an emit-time cost")
        void anAiEventCannotCarryACost() {
            // ADR 0009 section 3: the legacy ai_usage.estimatedCost is not finance truth and is never
            // migrated into the ledger as an amount. There is deliberately no cost parameter on
            // aiRequest/aiToken, so the decision is enforced by the signature; this asserts the other
            // half, that the catalogue agrees — the family does not accept a measured cost.
            assertThat(FinanceMetricCatalogue.costMayBeSuppliedAtEmitTime(UsageDimension.AI_TOKEN))
                .isFalse();
            assertThat(FinanceMetricCatalogue.costMayBeSuppliedAtEmitTime(UsageDimension.AI_REQUEST))
                .isFalse();

            UsageEvent event = UsageEventFactory.aiToken(
                FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, UUID.randomUUID(),
                new BigDecimal("1500"), FinanceFixture.inMarch(10, 9), FinanceFixture.MARCH_1,
                FinanceFixture.MARCH_31, LedgerPrincipal.job("ai-run-recorder"), "USD");

            assertThat(event.costOrNull())
                .as("no cost, so the bucket is priced from the rate card or reported unpriced")
                .isNull();
            assertThat(event.cost().isUnpriced()).isTrue();
            assertThat(record(event).isNew()).isTrue();
        }

        @Test
        @DisplayName("every declared dimension has a catalogue entry, and no UNPRICED member exists")
        void everyDimensionIsInTheCatalogue() {
            for (UsageDimension dimension : UsageDimension.values()) {
                assertThat(FinanceMetricCatalogue.metric(dimension)).isNotNull();
            }
            // UNPRICED is deliberately absent: unpricedness is a property of the rollup, not a kind of
            // usage that could be counted, summed or charted.
            assertThat(Arrays.stream(UsageDimension.values())
                .map(Enum::name)
                .noneMatch("UNPRICED"::equals)).isTrue();
            for (String sourceEventType : FinanceMetricCatalogue.declaredSourceEventTypes()) {
                assertThat(sourceEventType)
                    .as("every source type names the row it came from")
                    .contains(".");
            }
        }

        @Test
        @DisplayName("a usage event's canonical unit is taken from the catalogue, not the caller")
        void theCanonicalUnitIsNotCallerSupplied() {
            UsageEvent event = UsageEventFactory.message(
                FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, UUID.randomUUID(), BigDecimal.ONE,
                FinanceFixture.inMarch(10, 9), FinanceFixture.MARCH_1, FinanceFixture.MARCH_31,
                LedgerPrincipal.job("carrier-meter"), "USD", null, null);

            assertThat(event.unit()).isEqualTo("message");
        }
    }

    // ==================================================================
    // cost and currency
    // ==================================================================

    @Nested
    @DisplayName("cost is stored, revenue is not")
    class CostAndCurrency {

        @Test
        @DisplayName("a measured cost is stored verbatim, in a named currency")
        void aMeasuredCostIsStored() {
            record(UsageEventFactory.message(
                FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, UUID.randomUUID(), BigDecimal.ONE,
                FinanceFixture.inMarch(10, 9), FinanceFixture.MARCH_1, FinanceFixture.MARCH_31,
                LedgerPrincipal.job("carrier-meter"), "SAR", new BigDecimal("0.250000"), "STANDARD"));

            Map<String, Object> row = jdbc.queryForMap(
                "select \"costAmount\", \"currency\", \"taxClassification\" from usage_events",
                new MapSqlParameterSource());
            assertThat((BigDecimal) row.get("costAmount"))
                .isEqualByComparingTo("0.250000");
            assertThat(row.get("currency")).isEqualTo("SAR");
            assertThat(row.get("taxClassification")).isEqualTo("STANDARD");
        }

        @Test
        @DisplayName("there is no revenue, billable, margin, price or rate column on the ledger")
        void thereIsNoRevenueColumnOnTheLedger() {
            // ADR 0007 Part 1: "a usage event stores cost… Revenue is a derived value, never a stored
            // input." The migration fails its own build if one appears; this asserts the schema the
            // application was written against really has none, from the running database rather than
            // from the file.
            List<String> forbidden = List.of("revenueAmount", "billableAmount", "marginAmount",
                "price", "markupRate", "rateCardVersionId", "unitPrice", "ratedAmount");
            for (String column : forbidden) {
                assertThat(fixture.count(
                    "select 1 from information_schema.columns where table_schema = 'public'"
                        + " and table_name = 'usage_events' and column_name = :column",
                    Map.of("column", column)))
                    .as("usage_events must not carry a %s column", column)
                    .isZero();
            }
        }

        @Test
        @DisplayName("a negative cost is refused by the schema")
        void aNegativeCostIsRefusedByTheSchema() {
            assertThatThrownBy(() -> jdbc.update("""
                insert into usage_events ("id", "organizationId", "projectId", "dimension",
                    "quantity", "unit", "sourceEventType", "sourceEventId", "principalType",
                    "principalName", "occurredAt", "periodStart", "periodEnd", "currency",
                    "costAmount", "createdAt")
                values (gen_random_uuid(), :org, :project, cast('MESSAGE' as "UsageDimension"), 1,
                    'message', 'messages.delivered', gen_random_uuid(), 'JOB', 'JOB:negative-test',
                    :now, :from, :to, 'USD', -1.00, :now)""",
                new MapSqlParameterSource()
                    .addValue("org", FinanceFixture.ORG_A)
                    .addValue("project", FinanceFixture.PROJECT_A)
                    .addValue("now", FinanceFixture.now())
                    .addValue("from", FinanceFixture.MARCH_1)
                    .addValue("to", FinanceFixture.MARCH_31)))
                .hasMessageContaining("usage_events_cost_check");
        }

        @Test
        @DisplayName("an event whose timestamp is outside its billing bucket is refused")
        void anEventOutsideItsBucketIsRefused() {
            // usage_events_period_contains_event_check: the bucket is derived at write and immutable,
            // because a period boundary that moves after the fact is how a locked period stops agreeing
            // with its own events.
            assertThatThrownBy(() -> jdbc.update("""
                insert into usage_events ("id", "organizationId", "projectId", "dimension",
                    "quantity", "unit", "sourceEventType", "sourceEventId", "principalType",
                    "principalName", "occurredAt", "periodStart", "periodEnd", "currency", "createdAt")
                values (gen_random_uuid(), :org, :project, cast('MESSAGE' as "UsageDimension"), 1,
                    'message', 'messages.delivered', gen_random_uuid(), 'JOB', 'JOB:bucket-test',
                    :occurredAt, :from, :to, 'USD', :now)""",
                new MapSqlParameterSource()
                    .addValue("org", FinanceFixture.ORG_A)
                    .addValue("project", FinanceFixture.PROJECT_A)
                    .addValue("occurredAt", FinanceFixture.inMarch(10, 9))
                    .addValue("from", FinanceFixture.APRIL_1)
                    .addValue("to", FinanceFixture.APRIL_30)
                    .addValue("now", FinanceFixture.now())))
                .hasMessageContaining("usage_events_period_contains_event_check");
        }
    }

    // ==================================================================
    // FX
    // ==================================================================

    @Nested
    @DisplayName("FX records source and rate date, or nothing at all")
    class Fx {

        @Test
        @DisplayName("a conversion records rate, source and rate date, and they travel together")
        void aConversionRecordsAllThree() {
            FxAttribution attribution = new FxAttribution(new BigDecimal("3.75"),
                "SAMA_REFERENCE", LocalDate.of(2026, 3, 5), "USD", "SAR");

            UsageEvent event = UsageEvent.builder(
                    FinanceFixture.ORG_A, FinanceFixture.PROJECT_A)
                .dimension(UsageDimension.CHANNEL_TRAFFIC)
                .quantity(new BigDecimal("2048"))
                .unit(UsageDimension.CHANNEL_TRAFFIC.canonicalUnit())
                .sourceEventType("channel_traffic.metered")
                .sourceEventId(UUID.randomUUID())
                .occurredAt(FinanceFixture.inMarch(10, 9))
                .bucket(FinanceFixture.MARCH_1, FinanceFixture.MARCH_31)
                .principal(LedgerPrincipal.job("carrier-meter"))
                .currency("SAR")
                .cost(new BigDecimal("12.500000"))
                .fx(attribution)
                .build();

            assertThat(record(event).isNew()).isTrue();

            Map<String, Object> row = jdbc.queryForMap(
                "select \"currency\", \"fxRate\", \"fxSource\", \"fxRateDate\" from usage_events",
                new MapSqlParameterSource());
            assertThat(row.get("currency")).isEqualTo("SAR");
            assertThat((BigDecimal) row.get("fxRate")).isEqualByComparingTo("3.75000000");
            assertThat(row.get("fxSource")).isEqualTo("SAMA_REFERENCE");
            // queryForMap hands back a java.sql.Date for a `date` column; compared as a LocalDate so the
            // assertion reads as the date rather than as the driver's type.
            assertThat(((java.sql.Date) row.get("fxRateDate")).toLocalDate())
                .isEqualTo(LocalDate.of(2026, 3, 5));
        }

        @Test
        @DisplayName("a same-currency attribution is refused: there is no rate to record")
        void aSameCurrencyAttributionIsRefused() {
            // A fabricated "rate 1.0 from USD to USD" would appear in a drill-through as evidence of a
            // conversion that never happened, and would make "did this amount cross a currency?"
            // unanswerable from the row.
            assertThatThrownBy(() -> new FxAttribution(BigDecimal.ONE, "SAMA",
                LocalDate.of(2026, 3, 5), "USD", "USD"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a conversion");
        }

        @Test
        @DisplayName("a rate with no source, and a source with no rate, are both refused")
        void anAttributionNeedsAllThreeFields() {
            assertThatThrownBy(() -> new FxAttribution(BigDecimal.ONE, "  ",
                LocalDate.of(2026, 3, 5), "USD", "SAR"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not reproducible");
            assertThatThrownBy(() -> new FxAttribution(BigDecimal.ZERO, "SAMA",
                LocalDate.of(2026, 3, 5), "USD", "SAR"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("greater than zero");
        }

        @Test
        @DisplayName("a rate with no source is refused by the schema")
        void aRateWithNoSourceIsRefused() {
            assertThatThrownBy(() -> jdbc.update("""
                insert into usage_events ("id", "organizationId", "projectId", "dimension",
                    "quantity", "unit", "sourceEventType", "sourceEventId", "principalType",
                    "principalName", "occurredAt", "periodStart", "periodEnd", "currency", "fxRate",
                    "createdAt")
                values (gen_random_uuid(), :org, :project, cast('MESSAGE' as "UsageDimension"), 1,
                    'message', 'messages.delivered', gen_random_uuid(), 'JOB', 'JOB:fx-test', :now,
                    :from, :to, 'USD', 3.75, :now)""",
                new MapSqlParameterSource()
                    .addValue("org", FinanceFixture.ORG_A)
                    .addValue("project", FinanceFixture.PROJECT_A)
                    .addValue("now", FinanceFixture.now())
                    .addValue("from", FinanceFixture.MARCH_1)
                    .addValue("to", FinanceFixture.MARCH_31)))
                .hasMessageContaining("usage_events_fx_pair_check");
        }

        @Test
        @DisplayName("an event with no conversion writes none of the three FX columns")
        void anEventWithNoConversionWritesNoFxColumns() {
            record(UsageEventFactory.message(
                FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, UUID.randomUUID(), BigDecimal.ONE,
                FinanceFixture.inMarch(10, 9), FinanceFixture.MARCH_1, FinanceFixture.MARCH_31,
                LedgerPrincipal.job("carrier-meter"), "USD", null, null));

            Map<String, Object> row = jdbc.queryForMap(
                "select \"fxRate\", \"fxSource\", \"fxRateDate\" from usage_events",
                new MapSqlParameterSource());
            assertThat(row.get("fxRate")).isNull();
            assertThat(row.get("fxSource")).isNull();
            assertThat(row.get("fxRateDate")).isNull();
        }

        @Test
        @DisplayName("a conversion whose currencies disagree with its rate is refused")
        void aMismatchedConversionIsRefused() {
            // The rate converts USD -> SAR and the amount is in USD, so the question is which currency
            // the caller asked for. Applying a 3.75 factor to a number that was never converted is the
            // failure the direction check exists to prevent.
            var rate = new com.firstelement.finance.money.FxConverter.FxRate(new BigDecimal("3.75"),
                "SAMA_REFERENCE", LocalDate.of(2026, 3, 5), "USD", "SAR");
            var amountInSar = com.firstelement.finance.money.Money.of(new BigDecimal("10"), "SAR");

            assertThatThrownBy(() -> com.firstelement.finance.money.FxConverter.convert(
                amountInSar, rate, "SAR"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("but the amount is in SAR");

            // POSITIVE CONTROL: the same rate applied to the right currency converts, and the result
            // carries the attribution so a drill-through can reproduce it.
            var converted = com.firstelement.finance.money.FxConverter.convert(
                com.firstelement.finance.money.Money.of(new BigDecimal("10"), "USD"), rate, "SAR");
            assertThat(converted.amount()).isEqualByComparingTo("37.500000");
            assertThat(converted.currency()).isEqualTo("SAR");
            assertThat(converted.fx().source()).isEqualTo("SAMA_REFERENCE");
            assertThat(converted.fx().rateDate()).isEqualTo(LocalDate.of(2026, 3, 5));
        }
    }

    // ==================================================================
    // immutability and transaction
    // ==================================================================

    @Nested
    @DisplayName("the ledger is append-only and transactional")
    class ImmutabilityAndTransaction {

        @Test
        @DisplayName("an update to a usage event's cost is refused by the database")
        void anUpdateIsRefused() {
            // firstelement_append_only() is attached to usage_events by V20261005090000. This asserts
            // the trigger, not a convention: the phase-7-10a report records that the first version of
            // the ledger was append-only "by shape only" and that `update usage_events set "costAmount"
            // = 999` succeeded.
            fixture.message(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, BigDecimal.ONE,
                FinanceFixture.inMarch(10, 9), new BigDecimal("1.00"), "USD");

            assertThatThrownBy(() -> jdbc.update(
                "update usage_events set \"costAmount\" = 999", new MapSqlParameterSource()))
                .hasMessageContaining("APPEND-ONLY");
        }

        @Test
        @DisplayName("a delete of a usage event is refused by the database")
        void aDeleteIsRefused() {
            fixture.message(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, BigDecimal.ONE,
                FinanceFixture.inMarch(10, 9), new BigDecimal("1.00"), "USD");

            assertThatThrownBy(() -> jdbc.update(
                "delete from usage_events", new MapSqlParameterSource()))
                .hasMessageContaining("APPEND-ONLY");
        }

        @Test
        @DisplayName("a usage event cannot be written outside a transaction")
        void aUsageEventCannotBeWrittenOutsideATransaction() {
            UsageEvent event = delivered(UUID.randomUUID(), BigDecimal.ONE);

            // Spring's MANDATORY enforcement, which runs before the method body. The assertion is on the
            // propagation rather than on a message of this module's own, because the refusal genuinely
            // is Spring's: the rule is expressed as a propagation, not as an if-statement.
            assertThatThrownBy(() -> writer.record(event))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class)
                .hasMessageContaining("mandatory");
            assertThat(fixture.tenantRows("usage_events", FinanceFixture.ORG_A))
                .as("a refused write leaves no row")
                .isZero();
        }

        @Test
        @DisplayName("usage written inside a transaction that rolls back leaves no row")
        void aRolledBackTransactionLeavesNoUsage() {
            int before = fixture.tenantRows("usage_events", FinanceFixture.ORG_A);
            UUID sourceId = UUID.randomUUID();

            assertThatThrownBy(() -> rollingBack
                .deliverAndFail(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, sourceId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("domain change failed");

            assertThat(fixture.tenantRows("usage_events", FinanceFixture.ORG_A))
                .as("usage that survived a rolled-back domain change would be money for a message "
                    + "that does not exist")
                .isEqualTo(before);
        }

        @Test
        @DisplayName("usage written alongside a committed domain change survives it")
        void aCommittedDomainChangeKeepsItsUsage() {
            int before = fixture.tenantRows("usage_events", FinanceFixture.ORG_A);

            committing.deliver(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, UUID.randomUUID());

            assertThat(fixture.tenantRows("usage_events", FinanceFixture.ORG_A)).isEqualTo(before + 1);
        }
    }

    // ==================================================================
    // helpers
    // ==================================================================

    /**
     * Runs the write inside its own transaction.
     *
     * <p>{@code UsageEventWriter.record} is {@code Propagation.MANDATORY}, so the caller has to own the
     * transaction — the module that caused the usage. {@link UsageTransactionCaller} is that module
     * here, and it is a bean so the {@code @Transactional} proxy applies; a {@code @Transactional}
     * method on the test class itself would be called directly and never proxied, which fails with a
     * transaction error rather than a real one.
     */
    private UsageWriteResult record(UsageEvent event) {
        return caller.record(event);
    }

    private static UsageEvent delivered(UUID sourceId, BigDecimal quantity) {
        return UsageEventFactory.message(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, sourceId,
            quantity, FinanceFixture.inMarch(10, 9), FinanceFixture.MARCH_1, FinanceFixture.MARCH_31,
            LedgerPrincipal.job("carrier-meter"), "USD", new BigDecimal("0.250000"), null);
    }

    private int countFor(UUID sourceId, String dimension) {
        return fixture.count("""
            select 1 from usage_events
             where "sourceEventId" = :id and "dimension" = cast(:dimension as "UsageDimension")""",
            Map.of("id", sourceId, "dimension", dimension));
    }

}
