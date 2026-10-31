package com.firstelement.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstelement.finance.catalog.UsageDimension;
import com.firstelement.finance.rates.EffectiveRate;
import com.firstelement.finance.rates.RateCardRepository;
import com.firstelement.finance.rates.RateCardService;
import com.firstelement.finance.rates.RateCardResolver;
import com.firstelement.security.TenantContext;
import com.firstelement.security.permission.Permission;
import com.firstelement.security.query.TenantQueryGuard;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;

/**
 * Rate cards: effective dating, the pinned markup, currency, and the overlap rule DDL cannot express.
 *
 * <h2>What these tests are for</h2>
 *
 * <p>ADR 0007 rule 2: "Rates are versioned and effective-dated. A rate card version is pinned onto the
 * rollup row at computation time. Changing a rate card never mutates an already-computed snapshot." The
 * first two clauses are here; the third is
 * {@link com.firstelement.finance.RollupRateCardMutationTest}, which needs a snapshot to prove it.
 *
 * <p>Each effective-dating case is asserted in <em>both</em> directions — a date inside a range resolves
 * to that version, and the days either side of the boundary do not — because a resolver that ignores
 * {@code effectiveTo} passes a "resolves inside the range" test and fails in production on the day the
 * rate expires.
 */
@SpringBootTest(classes = FinanceTestApplication.class)
class RateCardEffectiveDatingIntegrationTest extends FinanceIntegrationTestBase {

    @Autowired
    private RateCardRepository cards;
    @Autowired
    private RateCardResolver resolver;
    @Autowired
    private RateCardService service;
    @Autowired
    private TenantQueryGuard guard;
    @Autowired
    private FinanceFixture fixture;
    @Autowired
    private org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc;

    private TenantContext adminA;

    @BeforeEach
    void reset() {
        fixture.reset();
        adminA = FinancePrincipalScope.adminA();
    }

    // ==================================================================
    // effective dating
    // ==================================================================

    @Nested
    @DisplayName("a rate is effective between its dates, inclusive")
    class EffectiveDating {

        @Test
        @DisplayName("the first and last day of a range both resolve to that version")
        void bothBoundsAreInclusive() {
            UUID version = priceMessages(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31),
                new BigDecimal("2.000000"));

            assertThat(resolveOn(UsageDimension.MESSAGE, LocalDate.of(2026, 3, 1))
                .rateCardVersionId()).isEqualTo(version);
            assertThat(resolveOn(UsageDimension.MESSAGE, LocalDate.of(2026, 3, 31))
                .rateCardVersionId()).isEqualTo(version);
        }

        @Test
        @DisplayName("a day before the range and a day after it resolve to nothing")
        void theDaysOutsideTheRangeResolveToNothing() {
            priceMessages(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31),
                new BigDecimal("2.000000"));

            // Not a rate of 1.0 and not a zero: empty. A fallback rate here would be the whole defect.
            assertThat(resolver.resolve(guard.tenantScope(adminA), FinanceFixture.PROJECT_A,
                UsageDimension.MESSAGE, LocalDate.of(2026, 2, 28))).isEmpty();
            assertThat(resolver.resolve(guard.tenantScope(adminA), FinanceFixture.PROJECT_A,
                UsageDimension.MESSAGE, LocalDate.of(2026, 4, 1))).isEmpty();
        }

        @Test
        @DisplayName("an open-ended version has no last day")
        void anOpenEndedVersionNeverExpires() {
            priceMessages(LocalDate.of(2026, 3, 1), null, new BigDecimal("2.000000"));

            assertThat(resolveOn(UsageDimension.MESSAGE, LocalDate.of(2030, 1, 1))).isNotNull();
        }

        @Test
        @DisplayName("the later of two adjacent versions wins on the boundary day")
        void theLaterVersionWinsOnTheBoundaryDay() {
            // 1..15 and 15..31 share the 15th. The resolver orders by effectiveFrom descending, so the
            // newer version takes the boundary day and the two never both claim it.
            priceMessagesOn(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 15),
                new BigDecimal("1.500000"), 1);
            priceMessagesOn(LocalDate.of(2026, 3, 15), LocalDate.of(2026, 3, 31),
                new BigDecimal("2.500000"), 2);

            assertThat(resolveOn(UsageDimension.MESSAGE, LocalDate.of(2026, 3, 15)).markupRate())
                .as("the newer version owns the shared day")
                .isEqualByComparingTo("2.500000");
            assertThat(resolveOn(UsageDimension.MESSAGE, LocalDate.of(2026, 3, 31)).markupRate())
                .as("the last day of the newer range")
                .isEqualByComparingTo("2.500000");
            assertThat(resolveOn(UsageDimension.MESSAGE, LocalDate.of(2026, 3, 14)).markupRate())
                .as("the day before the boundary still belongs to the older version")
                .isEqualByComparingTo("1.500000");
        }

        @Test
        @DisplayName("a DRAFT version prices nothing, even on a date inside its range")
        void aDraftVersionPricesNothing() {
            UUID cardId = fixture.rateCard(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, "standard",
                "USD");
            UUID draft = fixture.draftVersionOn(cardId, FinanceFixture.ORG_A, FinanceFixture.PROJECT_A,
                1, "USD", new BigDecimal("9.000000"), LocalDate.of(2026, 3, 1),
                LocalDate.of(2026, 3, 31), "proposed but not agreed", FinanceFixture.ADMIN_A);
            FinancePrincipalScope.run(adminA,
                () -> service.addLine(adminA, draft, UsageDimension.MESSAGE,
                    new BigDecimal("0.250000"), null, null));

            assertThat(resolver.resolve(guard.tenantScope(adminA), FinanceFixture.PROJECT_A,
                UsageDimension.MESSAGE, LocalDate.of(2026, 3, 15)))
                .as("a draft is a proposal, not a rate; the effective query filters on ACTIVE")
                .isEmpty();
        }

        @Test
        @DisplayName("a RETIRED version stops applying, and its successor takes over")
        void aRetiredVersionStopsApplying() {
            UUID cardId = fixture.rateCard(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, "standard",
                "USD");
            UUID retiring = fixture.activeVersionOn(cardId, FinanceFixture.ORG_A,
                FinanceFixture.PROJECT_A, 1, "USD", new BigDecimal("2.000000"),
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 28), "the old price",
                FinanceFixture.ADMIN_A);
            fixture.line(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, retiring, "MESSAGE", "message",
                new BigDecimal("0.250000"), null, "USD");
            UUID successor = fixture.activeVersionOn(cardId, FinanceFixture.ORG_A,
                FinanceFixture.PROJECT_A, 2, "USD", new BigDecimal("3.000000"),
                LocalDate.of(2026, 3, 1), null, "the new price", FinanceFixture.ADMIN_A);
            fixture.line(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, successor, "MESSAGE", "message",
                new BigDecimal("0.250000"), null, "USD");

            FinancePrincipalScope.run(adminA, () -> service.retire(adminA, retiring, successor,
                "superseded by the March price"));

            assertThat(resolver.resolve(guard.tenantScope(adminA), FinanceFixture.PROJECT_A,
                UsageDimension.MESSAGE, LocalDate.of(2026, 2, 1)))
                .as("a retired version no longer applies, so its own dates stop resolving")
                .isEmpty();
            assertThat(resolveOn(UsageDimension.MESSAGE, LocalDate.of(2026, 3, 15)).markupRate())
                .isEqualByComparingTo("3.000000");
        }
    }

    // ==================================================================
    // markup pinning and derivation
    // ==================================================================

    @Nested
    @DisplayName("the markup is a value on the row, not a lookup")
    class Markup {

        @Test
        @DisplayName("a line with no markup uses the version's default")
        void aLineWithoutAMarkupUsesTheVersionDefault() {
            UUID version = priceMessages(LocalDate.of(2026, 3, 1), null, new BigDecimal("2.500000"));

            EffectiveRate rate = resolveOn(UsageDimension.MESSAGE, LocalDate.of(2026, 3, 15));
            assertThat(rate.markupRate()).isEqualByComparingTo("2.500000");
            assertThat(rate.rateCardVersionId()).isEqualTo(version);
        }

        @Test
        @DisplayName("a line's own markup overrides the version default")
        void aLineMarkupOverridesTheVersionDefault() {
            UUID version = fixture.activeVersion(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, 1,
                "USD", new BigDecimal("2.500000"), LocalDate.of(2026, 3, 1), null, "base markup",
                FinanceFixture.ADMIN_A);
            // A line with a markup override and no unit cost, which is the shape an AI_TOKEN line takes:
            // the cost comes from the provider invoice and the card only supplies the multiplier.
            fixture.line(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, version, "MESSAGE", "message",
                null, new BigDecimal("3.750000"), "USD");

            assertThat(resolveOn(UsageDimension.MESSAGE, LocalDate.of(2026, 3, 15)).markupRate())
                .as("the per-line override, not the version default")
                .isEqualByComparingTo("3.750000");
        }

        @Test
        @DisplayName("a markup outside the schema's bounds is refused")
        void anOutOfBoundsMarkupIsRefused() {
            assertThatThrownBy(() -> com.firstelement.finance.rates.EffectiveRate.resolve(
                new com.firstelement.finance.rates.RateCardVersion(UUID.randomUUID(),
                    FinanceFixture.ORG_A, null, UUID.randomUUID(), 1, "USD", new BigDecimal("1000.1"),
                    LocalDate.of(2026, 3, 1), null, "ACTIVE", "too high", List.of()),
                new com.firstelement.finance.rates.RateCardVersionLine(UUID.randomUUID(), null,
                    UsageDimension.MESSAGE, "message", null, null, "USD", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most 1000");
        }

        @Test
        @DisplayName("a line that prices nothing is refused: it would look configured and price nothing")
        void aLineThatPricesNothingIsRefused() {
            assertThatThrownBy(() -> new com.firstelement.finance.rates.RateCardVersionLine(
                UUID.randomUUID(), null, UsageDimension.MESSAGE, "message", null, null, "USD", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("prices nothing while looking configured");
        }

        @Test
        @DisplayName("the rate definition a total names is the version number and the pinned markup")
        void theRateDefinitionIsNameable() {
            priceMessages(LocalDate.of(2026, 3, 1), null, new BigDecimal("2.500000"));

            assertThat(resolveOn(UsageDimension.MESSAGE, LocalDate.of(2026, 3, 15))
                .rateDefinitionLabel())
                .contains("rate-card-version 1")
                .contains("markup 2.500000")
                .contains("effective 2026-03-01");
        }
    }

    // ==================================================================
    // currency
    // ==================================================================

    @Nested
    @DisplayName("a card is single-currency, and a line may not disagree with it")
    class Currency {

        @Test
        @DisplayName("a line priced in another currency is refused before it is written")
        void aLineInAnotherCurrencyIsRefused() {
            UUID version = priceMessages(LocalDate.of(2026, 3, 1), null, new BigDecimal("2.000000"));

            assertThatThrownBy(() -> com.firstelement.finance.rates.RateCardVersionLine.forVersion(
                UUID.randomUUID(), version, UsageDimension.MESSAGE, "message",
                new BigDecimal("0.25"), null, "USD", "SAR", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must equal its version's currency");
        }

        @Test
        @DisplayName("a card's currency is what its versions price in")
        void aCardsCurrencyIsWhatItsVersionsPriceIn() {
            UUID version = priceMessagesOn(LocalDate.of(2026, 3, 1), null, new BigDecimal("2.000000"),
                1);

            assertThat(resolveOn(UsageDimension.MESSAGE, LocalDate.of(2026, 3, 15)).currency())
                .isEqualTo("USD");
            assertThat(cards.version(guard.tenantScope(adminA), version))
                .get()
                .extracting(com.firstelement.finance.rates.RateCardVersion::currency)
                .isEqualTo("USD");
        }

        @Test
        @DisplayName("a currency that is not ISO-4217-shaped is refused by the schema")
        void aMalformedCurrencyIsRefused() {
            // public.firstelement_currency_code() is the shared validator, and every money column in
            // both migrations checks it. A lower-case 'usd' would pass a length test and fail this one.
            assertThat(fixture.count("""
                select 1 from rate_cards
                 where "organizationId" = :org and "currency" = 'usd'""",
                Map.of("org", FinanceFixture.ORG_A))).isZero();
            assertThatThrownBy(() -> jdbc.update("""
                insert into rate_cards ("id", "organizationId", "projectId", "key", "nameAr", "nameEn",
                    "currency", "status", "createdByUserId", "version", "createdAt", "updatedAt")
                values (gen_random_uuid(), :org, null, 'bad-currency', 'x', 'x', 'usd',
                    cast('DRAFT' as "RateCardStatus"), :author, 0, :now, :now)""",
                new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                    .addValue("org", FinanceFixture.ORG_A)
                    .addValue("author", FinanceFixture.ADMIN_A)
                    .addValue("now", FinanceFixture.now())))
                .hasMessageContaining("rate_cards_currency_check");
        }
    }

    // ==================================================================
    // the overlap rule DDL cannot express
    // ==================================================================

    @Nested
    @DisplayName("two rates may not both be effective on one date")
    class Overlap {

        @Test
        @DisplayName("activating a range that overlaps an ACTIVE one is refused, naming both")
        void anOverlappingActivationIsRefused() {
            UUID cardId = fixture.rateCard(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, "standard",
                "USD");
            fixture.activeVersionOn(cardId, FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, 1, "USD",
                new BigDecimal("2.000000"), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                "the standing price", FinanceFixture.ADMIN_A);

            // Two rates effective on 1 July would make every snapshot pinned to one of them
            // uncheckable. DDL cannot express this without a btree_gist EXCLUDE constraint, so it is
            // checked on the write path and reported by V13 section 7.
            assertThatThrownBy(() -> FinancePrincipalScope.call(adminA,
                () -> service.activate(adminA, cardId, new BigDecimal("3.000000"),
                    LocalDate.of(2026, 6, 1), LocalDate.of(2026, 7, 31), "a mid-year change",
                    List.of(new RateCardService.ProposedLine(UsageDimension.MESSAGE,
                        new BigDecimal("0.250000"), null)))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("overlap an ACTIVE version");

            // STATE, not the message: the refusal left no second ACTIVE version behind, because the
            // whole activate runs in one transaction.
            assertThat(activeVersionCount(cardId)).isEqualTo(1);
        }

        @Test
        @DisplayName("the overlap check is real, not vacuous: a non-overlapping activation succeeds")
        void aNonOverlappingActivationSucceeds() {
            UUID cardId = fixture.rateCard(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, "standard",
                "USD");
            UUID standing = fixture.activeVersionOn(cardId, FinanceFixture.ORG_A,
                FinanceFixture.PROJECT_A, 1, "USD", new BigDecimal("2.000000"),
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 28), "the standing price",
                FinanceFixture.ADMIN_A);
            fixture.line(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, standing, "MESSAGE",
                "message", new BigDecimal("0.250000"), null, "USD");

            UUID activated = FinancePrincipalScope.call(adminA,
                () -> service.activate(adminA, cardId, new BigDecimal("3.000000"),
                    LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31), "the March price",
                    List.of(new RateCardService.ProposedLine(UsageDimension.MESSAGE,
                        new BigDecimal("0.250000"), null))));

            assertThat(activated).isNotNull();
            assertThat(activeVersionCount(cardId)).isEqualTo(2);
            assertThat(resolveOn(UsageDimension.MESSAGE, LocalDate.of(2026, 3, 15)).markupRate())
                .isEqualByComparingTo("3.000000");
            assertThat(resolveOn(UsageDimension.MESSAGE, LocalDate.of(2026, 2, 15)).markupRate())
                .as("the February rate is untouched")
                .isEqualByComparingTo("2.000000");
        }

        @Test
        @DisplayName("a draft cannot be published: the schema's own guard makes it inexpressible")
        void aDraftCannotBePublished() {
            // Two constraints together: firstelement_rate_card_version_guard() refuses DRAFT -> ACTIVE,
            // and rate_card_versions_one_start_per_day is unique per start day while the draft can
            // never be deleted. Asserted because the method exists and somebody will call it.
            UUID cardId = fixture.rateCard(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, "standard",
                "USD");
            UUID draft = fixture.draftVersionOn(cardId, FinanceFixture.ORG_A, FinanceFixture.PROJECT_A,
                1, "USD", new BigDecimal("3.000000"), LocalDate.of(2026, 3, 1),
                LocalDate.of(2026, 3, 31), "proposed", FinanceFixture.ADMIN_A);

            assertThatThrownBy(() -> FinancePrincipalScope.run(adminA,
                () -> service.publish(adminA, draft)))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("rate_card_versions_one_start_per_day");
        }

        private int activeVersionCount(UUID cardId) {
            return fixture.count("""
                select 1 from rate_card_versions
                 where "rateCardId" = :card and "status" = cast('ACTIVE' as "RateCardStatus")""",
                Map.of("card", cardId));
        }

        @Test
        @DisplayName("a version cannot start on a day another already starts")
        void oneVersionPerStartDay() {
            UUID cardId = fixture.rateCard(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, "standard",
                "USD");
            fixture.activeVersionOn(cardId, FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, 1, "USD",
                new BigDecimal("2.000000"), LocalDate.of(2026, 3, 1), null, "first",
                FinanceFixture.ADMIN_A);

            // rate_card_versions_one_start_per_day is the part DDL DOES express, so the database refuses
            // it. Asserted here because it is the half of the rule that is a real constraint rather
            // than an application check.
            assertThatThrownBy(() -> fixture.activeVersionOn(cardId, FinanceFixture.ORG_A,
                FinanceFixture.PROJECT_A, 2, "USD", new BigDecimal("3.000000"),
                LocalDate.of(2026, 3, 1), null, "same start day", FinanceFixture.ADMIN_A))
                .hasMessageContaining("rate_card_versions_one_start_per_day");
        }
    }

    // ==================================================================
    // authorization
    // ==================================================================

    @Nested
    @DisplayName("rate cards are finance-owned configuration")
    class Authorization {

        @Test
        @DisplayName("an employee may not read or author a rate card")
        void anEmployeeIsRefused() {
            TenantContext employee = FinancePrincipalScope.employeeA();

            assertThatThrownBy(() -> FinancePrincipalScope.call(employee,
                () -> service.cards(employee, null)))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
            assertThatThrownBy(() -> FinancePrincipalScope.call(employee,
                () -> service.createCard(employee, null, "k", "بطاقة", "Card", "USD")))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        }

        @Test
        @DisplayName("a FINANCE_AGGREGATE worker may read but not author: it computes, it does not price")
        void aFinanceWorkerMayReadButNotAuthor() {
            // The distinction the whole policy exists for: the capability grants
            // ANALYTICS_READ_ORGANIZATION, and the ADMIN role is what makes authoring an act.
            TenantContext worker = FinancePrincipalScope.financeWorkerA();
            assertThat(FinancePrincipalScope.effective(worker))
                .as("the capability grants the permission")
                .contains(Permission.ANALYTICS_READ_ORGANIZATION);

            assertThatThrownBy(() -> FinancePrincipalScope.call(worker,
                () -> service.createCard(worker, null, "k", "بطاقة", "Card", "USD")))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("finance-owned configuration");
        }

        @Test
        @DisplayName("authoring without a change note is refused")
        void authoringRequiresAChangeNote() {
            UUID cardId = fixture.rateCard(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, "standard",
                "USD");

            assertThatThrownBy(() -> FinancePrincipalScope.run(adminA,
                () -> service.draftVersion(adminA, cardId, new BigDecimal("3.000000"),
                    LocalDate.of(2026, 4, 1), null, "   ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("changeNote");
            assertThatThrownBy(() -> FinancePrincipalScope.run(adminA,
                () -> service.activate(adminA, cardId, new BigDecimal("3.000000"),
                    LocalDate.of(2026, 4, 1), null, null,
                    List.of(new RateCardService.ProposedLine(UsageDimension.MESSAGE,
                        new BigDecimal("0.250000"), null)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("changeNote");
        }

        @Test
        @DisplayName("activating a version that prices nothing is refused")
        void aVersionThatPricesNothingIsRefused() {
            UUID cardId = fixture.rateCard(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, "standard",
                "USD");

            assertThatThrownBy(() -> FinancePrincipalScope.run(adminA,
                () -> service.activate(adminA, cardId, new BigDecimal("3.000000"),
                    LocalDate.of(2026, 3, 1), null, "no lines",
                    List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must price at least one dimension");
            assertThatThrownBy(() -> FinancePrincipalScope.run(adminA,
                () -> service.activate(adminA, cardId, new BigDecimal("3.000000"),
                    LocalDate.of(2026, 3, 1), null, "a line that prices nothing",
                    List.of(new RateCardService.ProposedLine(UsageDimension.MESSAGE, null, null)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("prices nothing");
        }

        @Test
        @DisplayName("a line using a non-canonical unit is refused")
        void aNonCanonicalUnitIsRefused() {
            // usage_rollup_snapshots has no unit column and is unique per (project, grain, bucket,
            // dimension), so two units for one dimension would be summed into a quantity of nothing.
            UUID cardId = fixture.rateCard(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, "standard",
                "USD");

            assertThatThrownBy(() -> FinancePrincipalScope.run(adminA,
                () -> service.activate(adminA, cardId, new BigDecimal("3.000000"),
                    LocalDate.of(2026, 3, 1), null, "wrong unit",
                    List.of(new RateCardService.ProposedLine(UsageDimension.MESSAGE, "kilobyte",
                        new BigDecimal("0.25"), null, null)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("canonical unit is 'message'");
        }

        @Test
        @DisplayName("retiring without naming a real successor is refused")
        void retiringRequiresARealSuccessor() {
            UUID version = priceMessages(LocalDate.of(2026, 3, 1), null, new BigDecimal("2.000000"));

            // A satisfied NOT NULL would satisfy rate_card_versions_retired_check and leave a
            // historical snapshot pointing at a rate that says nothing about what replaced it.
            assertThatThrownBy(() -> FinancePrincipalScope.run(adminA,
                () -> service.retire(adminA, version, UUID.randomUUID(), "superseded")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not exist in this tenant");
        }
    }

    // ==================================================================
    // helpers
    // ==================================================================

    /** An ACTIVE version pricing MESSAGE at the given markup, with a bare markup line. */
    private UUID priceMessages(LocalDate from, LocalDate to, BigDecimal markup) {
        return priceMessagesOn(from, to, markup, 1);
    }

    /**
     * As above, with an explicit version number.
     *
     * <p>The version number is a parameter because {@code rate_card_versions_card_version_key} is unique
     * per (card, versionNumber) and the adjacent-range test needs two versions on one card.
     */
    private UUID priceMessagesOn(LocalDate from, LocalDate to, BigDecimal markup, int versionNumber) {
        UUID version = fixture.activeVersion(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A,
            versionNumber, "USD", markup, from, to, "seeded rate", FinanceFixture.ADMIN_A);
        // A line with a unit cost and NO markup of its own, so the version default is what applies.
        // A line with neither would be refused by rate_card_version_lines_prices_something_check.
        fixture.line(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, version, "MESSAGE", "message",
            new BigDecimal("0.250000"), null, "USD");
        return version;
    }

    private EffectiveRate resolveOn(UsageDimension dimension, LocalDate asOf) {
        return resolver.resolve(guard.tenantScope(adminA), FinanceFixture.PROJECT_A, dimension, asOf)
            .orElseThrow(() -> new AssertionError(
                "no rate was effective for " + dimension + " on " + asOf));
    }
}
