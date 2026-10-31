package com.firstelement.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.firstelement.finance.catalog.BillableMetric;
import com.firstelement.finance.catalog.FinanceMetricCatalogue;
import com.firstelement.finance.catalog.UsageDimension;
import com.firstelement.finance.catalog.UsageDimensionTaxonomy;
import com.firstelement.finance.money.AmountState;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The catalogue is checked, and this suite is the check.
 *
 * <h2>Why a document needs a test</h2>
 *
 * <p>A catalogue in a repository is the artifact an operator, a support engineer and an auditor all
 * read, and it is also the artifact that goes stale silently: nothing fails when someone adds a
 * thirteenth dimension and forgets the prose, and the prose is what the reader trusts. So the table in
 * {@code docs/migration/finance-metric-catalogue.md} is parsed and compared against
 * {@link FinanceMetricCatalogue} — dimension, unit, family, aggregation, emit-time-cost rule and the
 * exact set of source event types. Any drift fails the build, here, rather than in an invoice.
 *
 * <h2>Why the file is located by walking up</h2>
 *
 * <p>A test must not pass because it read the wrong file, and a fixed relative path from the module
 * directory is a guess about the working directory. This walks up from the working directory to the
 * repository root and <em>fails</em> if it cannot find the document, so the suite cannot pass by not
 * finding anything.
 */
@DisplayName("the finance metric catalogue is enforced, not documented")
class FinanceMetricCatalogueTest {

    private static final String RELATIVE = "docs/migration/finance-metric-catalogue.md";

    private static Path document;
    private static List<String> lines;

    @BeforeAll
    static void readDocument() throws IOException {
        document = locate(RELATIVE);
        lines = Files.readAllLines(document, StandardCharsets.UTF_8);
    }

    // ==================================================================
    // the catalogue itself
    // ==================================================================

    @Nested
    @DisplayName("every declared dimension is measurable, and nothing else is")
    class Completeness {

        @Test
        @DisplayName("every UsageDimension has a metric with at least one source event type")
        void everyDimensionHasAMetric() {
            List<String> undescribed = new ArrayList<>();
            for (UsageDimension dimension : UsageDimension.values()) {
                BillableMetric metric = FinanceMetricCatalogue.metric(dimension);
                if (metric.sourceEventTypes().isEmpty()) {
                    undescribed.add(dimension.name());
                }
            }

            assertThat(undescribed)
                .as("a dimension nothing can emit is dead configuration that looks live in a dashboard")
                .isEmpty();
        }

        @Test
        @DisplayName("canonical units are unique, so no total can span two different things")
        void canonicalUnitsAreUnique() {
            Map<String, UsageDimension> claimedBy = new LinkedHashMap<>();
            List<String> clashes = new ArrayList<>();
            for (BillableMetric metric : FinanceMetricCatalogue.all()) {
                UsageDimension previous = claimedBy.put(metric.dimension().canonicalUnit(),
                    metric.dimension());
                if (previous != null) {
                    clashes.add(previous + " and " + metric.dimension() + " both claim "
                        + metric.dimension().canonicalUnit());
                }
            }

            assertThat(clashes).isEmpty();
        }

        @Test
        @DisplayName("one source event type belongs to exactly one dimension")
        void sourceEventTypesAreUnambiguous() {
            Map<String, UsageDimension> owner = new LinkedHashMap<>();
            List<String> doubles = new ArrayList<>();
            for (BillableMetric metric : FinanceMetricCatalogue.all()) {
                for (String type : metric.sourceEventTypes()) {
                    UsageDimension previous = owner.put(type, metric.dimension());
                    if (previous != null) {
                        doubles.add(type + " is claimed by " + previous + " and " + metric.dimension());
                    }
                }
            }

            assertThat(doubles)
                .as("an ambiguous source type makes 'count the messages' unanswerable")
                .isEmpty();
        }

        @Test
        @DisplayName("every source event type names a table and an operation")
        void sourceEventTypesAreQualified() {
            List<String> bare = new ArrayList<>();
            for (String type : FinanceMetricCatalogue.declaredSourceEventTypes()) {
                if (!type.contains(".")) {
                    bare.add(type);
                }
            }

            assertThat(bare)
                .as("the drill-through reads the source type as 'which row produced this'")
                .isEmpty();
        }

        @Test
        @DisplayName("an undeclared source event type is refused rather than recorded")
        void anUndeclaredSourceTypeIsRefused() {
            // The refusal is the point: a usage event whose source type is outside the taxonomy has no
            // defined meaning, and an undefined row in an immutable ledger is a row nobody can ever
            // interpret or correct.
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    FinanceMetricCatalogue.metricForSourceEventType("mystery_table.mystery_operation"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not in the finance metric catalogue");

            assertThat(FinanceMetricCatalogue.isDeclaredSourceEventType("mystery_table.mystery_operation"))
                .as("and it is reported as undeclared rather than silently mapped to something")
                .isFalse();
        }
    }

    // ==================================================================
    // the document
    // ==================================================================

    @Nested
    @DisplayName("the written catalogue matches the enforced one")
    class Document {

        @Test
        @DisplayName("the document exists, so this suite cannot pass by finding nothing")
        void theDocumentExists() {
            assertThat(document)
                .as("the catalogue document must be findable from the repository root")
                .isNotNull();
            assertThat(Files.isRegularFile(document)).isTrue();
            assertThat(lines).as("and it must not be empty").isNotEmpty();
        }

        @Test
        @DisplayName("every metric appears once, with the unit, family and aggregation the code has")
        void theTableMatchesTheCode() {
            Map<String, List<String>> rows = metricRows();

            List<String> wrong = new ArrayList<>();
            for (BillableMetric metric : FinanceMetricCatalogue.all()) {
                List<String> row = rows.get(metric.dimension().name());
                if (row == null) {
                    wrong.add(metric.dimension() + " has no row in " + RELATIVE);
                    continue;
                }
                if (!cell(row, 1).equals("`" + metric.dimension().canonicalUnit() + "`")) {
                    wrong.add(metric.dimension() + ": unit is " + cell(row, 1) + ", code says `"
                        + metric.dimension().canonicalUnit() + "`");
                }
                if (!cell(row, 2).equals(metric.dimension().taxonomy().name())) {
                    wrong.add(metric.dimension() + ": family is " + cell(row, 2) + ", code says "
                        + metric.dimension().taxonomy());
                }
                if (!cell(row, 3).equals(metric.aggregation().name())) {
                    wrong.add(metric.dimension() + ": aggregation is " + cell(row, 3) + ", code says "
                        + metric.aggregation());
                }
                String costAtEmit = metric.dimension().taxonomy().costKnownAtEmitTime() ? "yes" : "no";
                if (!cell(row, 4).equals(costAtEmit)) {
                    wrong.add(metric.dimension() + ": emit-time cost is " + cell(row, 4)
                        + ", code says " + costAtEmit);
                }
            }

            assertThat(wrong).as("the prose is what an operator reads; the code is what is charged")
                .isEmpty();
        }

        @Test
        @DisplayName("the table has no dimension row the code does not have")
        void theTableHasNoExtraDimensions() {
            Set<String> declared = new TreeSet<>();
            for (UsageDimension dimension : UsageDimension.values()) {
                declared.add(dimension.name());
            }

            assertThat(new TreeSet<>(metricRows().keySet()))
                .as("a row in the document for a dimension that no longer exists is a promise nobody "
                    + "is keeping")
                .isEqualTo(declared);
        }

        @Test
        @DisplayName("each row lists exactly the source event types the code accepts")
        void sourceEventTypesMatch() {
            List<String> wrong = new ArrayList<>();
            for (BillableMetric metric : FinanceMetricCatalogue.all()) {
                List<String> row = metricRows().get(metric.dimension().name());
                if (row == null) {
                    continue;
                }
                Set<String> inDocument = new LinkedHashSet<>();
                for (String piece : cell(row, 5).split(",")) {
                    inDocument.add(piece.trim().replace("`", ""));
                }
                if (!inDocument.equals(new TreeSet<>(metric.sourceEventTypes()))) {
                    wrong.add(metric.dimension() + ": document says " + inDocument + ", code says "
                        + new TreeSet<>(metric.sourceEventTypes()));
                }
            }

            assertThat(wrong)
                .as("the source types are how a new emitter is found; a missing one is an emitter that "
                    + "silently never gets wired")
                .isEmpty();
        }

        @Test
        @DisplayName("every declared source event type appears somewhere in the document")
        void everySourceEventTypeIsMentioned() {
            String body = String.join("\n", lines);
            List<String> missing = new ArrayList<>();
            for (String type : FinanceMetricCatalogue.declaredSourceEventTypes()) {
                if (!body.contains(type)) {
                    missing.add(type);
                }
            }

            assertThat(missing).isEmpty();
        }

        @Test
        @DisplayName("the four amount states are documented, including the one that is not a dimension")
        void theAmountStatesAreDocumented() {
            String body = String.join("\n", lines);
            for (AmountState state : AmountState.values()) {
                assertThat(body)
                    .as("%s must be documented; a state nobody knows about is a state nobody reads",
                        state.label())
                    .contains("`" + state.name() + "`");
            }
            assertThat(body)
                .as("and the document must say UNPRICED is not a dimension, which is the whole point")
                .contains("not** a `UsageDimension`");
        }

        @Test
        @DisplayName("the document records what is refused: payments, estimatedCost, derived effort")
        void theRefusalsAreDocumented() {
            String body = String.join("\n", lines);

            assertThat(body)
                .as("ADR 0009 §3: ai_usage.estimatedCost is a comparison source, never a ledger amount")
                .contains("estimatedCost");
            assertThat(body)
                .as("ADR 0007 rule 7: no payment execution and no invoicing")
                .contains("No payment execution");
            assertThat(body)
                .as("ADR 0007 rule 3: effort is entered, never derived from a conversation count")
                .contains("derived effort");
        }

        @Test
        @DisplayName("the document names the family table, so 'cost at emit time' is not folklore")
        void theFamilyTableMatchesTheCode() {
            String body = String.join("\n", lines);
            for (UsageDimensionTaxonomy family : UsageDimensionTaxonomy.values()) {
                assertThat(body).as("%s must appear in the family table", family).contains(family.name());
            }
            // And the yes/no column must agree with the code, family by family.
            for (BillableMetric metric : FinanceMetricCatalogue.all()) {
                UsageDimensionTaxonomy family = metric.dimension().taxonomy();
                List<List<String>> familyRows = rowsAfterHeader("| Family | Cost known at emit time");
                String expected = family.costKnownAtEmitTime() ? "yes" : "no";
                assertThat(familyRows)
                    .as("%s", family)
                    .anySatisfy(row -> assertThat(cell(row, 1)).isEqualTo(expected));
            }
        }
    }

    // ==================================================================
    // helpers
    // ==================================================================

    /**
     * The metric table, keyed by dimension.
     *
     * <p>A row is recognised by its first cell being a backticked identifier that is not the header's
     * own label. That keeps the prose tables in §2 and §7 — which are also pipe tables — out of the
     * comparison, without hard-coding a line number that moves every time the document is edited.
     */
    private static Map<String, List<String>> metricRows() {
        Map<String, List<String>> byDimension = new LinkedHashMap<>();
        for (List<String> row : rowsAfterHeader(
            "| Dimension | Unit | Family | Aggregation | Cost known at emit time")) {
            byDimension.put(cell(row, 0).replace("`", ""), row);
        }
        return byDimension;
    }

    private static List<List<String>> rowsAfterHeader(String headerPrefix) {
        List<List<String>> rows = new ArrayList<>();
        boolean started = false;
        for (String line : lines) {
            String trimmed = line.strip();
            if (!started) {
                started = trimmed.startsWith(headerPrefix);
                continue;
            }
            if (!trimmed.startsWith("|")) {
                break;
            }
            if (trimmed.startsWith("|---") || trimmed.startsWith("| ---")) {
                continue;
            }
            rows.add(cells(trimmed));
        }
        if (!started) {
            fail("No table starting with '" + headerPrefix + "' was found in " + RELATIVE
                + "; the catalogue test cannot pass by finding nothing.");
        }
        return rows;
    }

    private static String cell(List<String> cells, int index) {
        return index < cells.size() ? cells.get(index).strip() : "";
    }

    private static List<String> cells(String tableRow) {
        List<String> cells = new ArrayList<>();
        for (String piece : tableRow.strip().split("\\|")) {
            cells.add(piece.strip());
        }
        // A leading and a trailing pipe produce two empty ends; drop them and keep the real cells.
        if (!cells.isEmpty() && cells.get(0).isEmpty()) {
            cells.remove(0);
        }
        if (!cells.isEmpty() && cells.get(cells.size() - 1).isEmpty()) {
            cells.remove(cells.size() - 1);
        }
        return cells;
    }

    private static Path locate(String relative) {
        Path directory = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 8 && directory != null; depth++) {
            Path candidate = directory.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException("Could not find " + relative + " by walking up from "
            + Path.of("").toAbsolutePath() + ". The catalogue test must fail rather than pass without "
            + "reading the document.");
    }
}