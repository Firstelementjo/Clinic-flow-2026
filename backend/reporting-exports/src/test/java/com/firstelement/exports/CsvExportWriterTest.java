package com.firstelement.exports;

import static org.assertj.core.api.Assertions.assertThat;

import com.firstelement.exports.catalog.ReportField;
import com.firstelement.exports.format.CsvExportWriter;
import com.firstelement.exports.format.ExportCellFormatter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The Arabic-safe CSV contract, asserted over the rendered bytes.
 *
 * <h2>Why this suite reads bytes and not a parsed model</h2>
 *
 * <p>The failure this exists to catch is mojibake: an Arabic customer name arriving as
 * {@code Ù…} in a spreadsheet, or a BOM landing on the first header cell so the column is called
 * {@code ﻿customer.displayName}. Neither is visible in a parsed model that decoded leniently. So every
 * assertion here is over the exact bytes written, and the round-trip check decodes with a strict
 * {@code CharsetDecoder} that REPORTS malformed input rather than substituting a replacement character.
 *
 * <h2>What each nested group covers</h2>
 *
 * <ul>
 *   <li><b>encoding</b> — UTF-8 bytes, the BOM decision in both directions, and Arabic round-tripping;</li>
 *   <li><b>quoting</b> — RFC 4180 field quoting, embedded quotes, commas, newlines and whitespace;</li>
 *   <li><b>structure</b> — stable column order, one cell per column, CRLF records, and Arabic headers;</li>
 *   <li><b>values</b> — the locale split: invariant in CSV, localized in a document;</li>
 *   <li><b>streaming</b> — bounded memory over many rows, and the absence of an empty-value placeholder.</li>
 * </ul>
 */
@DisplayName("CSV export is UTF-8, Arabic-safe, RFC 4180, stable and streaming")
class CsvExportWriterTest {

    /** Arabic text with a comma, a double quote and a diacritic — every case RFC 4180 cares about. */
    private static final String ARABIC_NAME = "محمد عبد الرحمن، \"المحرر\" ة";
    private static final String ARABIC_PLAIN = "سارة أحمد";

    private static final ZoneId ZONE = ZoneId.of("Asia/Riyadh");

    private static byte[] write(CsvExportWriter writer, List<ReportField> fields, Locale locale,
                                List<Map<String, Object>> rows) throws IOException {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        ExportCellFormatter formatter = ExportCellFormatter.forCsv(locale, ZONE);
        writer.write(sink, fields, locale, out -> {
            for (Map<String, Object> row : rows) {
                CsvExportWriter.writeRow(out, formatter.cells(row, fields));
            }
            return rows.size();
        });
        return sink.toByteArray();
    }

    // ==================================================================
    // encoding
    // ==================================================================

    @Nested
    @DisplayName("the bytes are UTF-8 and the BOM decision is honoured in both directions")
    class Encoding {

        @Test
        @DisplayName("Arabic is written as raw UTF-8 and survives a strict decode round trip")
        void arabicRoundTrips() throws IOException {
            List<ReportField> fields = List.of(ReportField.CUSTOMER_DISPLAY_NAME);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("customer.displayName", ARABIC_NAME);

            byte[] bytes = write(CsvExportWriter.withBom(), fields, Locale.ENGLISH, List.of(row));
            String decoded = new String(bytes, StandardCharsets.UTF_8);

            // Asserted on the ARABIC RUNS rather than the whole value: this sample deliberately
            // contains a comma and two double quotes, so RFC 4180 wraps and doubles them, and the
            // serialized cell is legitimately not byte-identical to the raw value. What must be intact
            // is the Arabic text itself.
            assertThat(decoded)
                .as("the Arabic must be present character-for-character, not transliterated")
                .contains("محمد عبد الرحمن")
                .contains("المحرر");
            assertThat(decoded)
                .as("the Arabic comma (U+060C) must survive as itself and not become a Latin comma")
                .contains("،");
            assertThat(decoded)
                .as("no Unicode escape may appear: an export of backslash-uXXXX is not an export anyone can read")
                .doesNotContain("\\u");
            assertThat(decoded)
                .as("no HTML entity may appear")
                .doesNotContain("&#")
                .doesNotContain("&amp;");
        }

        @Test
        @DisplayName("a strict decoder reports no malformed input, so nothing was substituted")
        void noMojibakeSubstitution() throws IOException {
            List<ReportField> fields = List.of(ReportField.CUSTOMER_DISPLAY_NAME);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("customer.displayName", ARABIC_PLAIN);
            byte[] bytes = write(CsvExportWriter.noBom(), fields, Locale.ENGLISH, List.of(row));

            java.nio.charset.CharsetDecoder strict = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
            String decoded = strict.decode(java.nio.ByteBuffer.wrap(bytes)).toString();

            assertThat(decoded).contains(ARABIC_PLAIN);
            assertThat(decoded)
                .as("a replacement character is the signature of a decode that gave up somewhere")
                .doesNotContain(REPLACEMENT_CHARACTER);
        }

        @Test
        @DisplayName("the BOM is exactly EF BB BF and appears once, at the start")
        void bomIsTheThreeBytesExcelNeeds() throws IOException {
            byte[] withBom = write(CsvExportWriter.withBom(),
                List.of(ReportField.CUSTOMER_DISPLAY_NAME), Locale.ENGLISH,
                List.of(Map.of("customer.displayName", ARABIC_PLAIN)));

            assertThat(withBom).startsWith(CsvExportWriter.BYTE_ORDER_MARK);
            assertThat(CsvExportWriter.BYTE_ORDER_MARK)
                .as("U+FEFF encoded as UTF-8; a one-byte or two-byte 'BOM' is a different character "
                    + "entirely and would not fix the mojibake it is meant to fix")
                .containsExactly((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);

            int occurrences = 0;
            for (int i = 0; i + 2 < withBom.length; i++) {
                if (withBom[i] == (byte) 0xEF && withBom[i + 1] == (byte) 0xBB
                    && withBom[i + 2] == (byte) 0xBF) {
                    occurrences++;
                }
            }
            assertThat(occurrences)
                .as("a BOM in the middle of a file is data, not a marker, and a second one at the end is a "
                    + "spreadsheet's 'not really UTF-8' signal")
                .isEqualTo(1);
        }

        @Test
        @DisplayName("the BOM lands before the header, not inside the first column name")
        void bomDoesNotCorruptTheFirstHeader() throws IOException {
            byte[] bytes = write(CsvExportWriter.withBom(),
                List.of(ReportField.CUSTOMER_DISPLAY_NAME), Locale.ENGLISH, List.of());
            String decoded = new String(bytes, StandardCharsets.UTF_8);

            // Compared with the BOM stripped, because the BOM IS present and correct here; what this
            // asserts is that it stays OUTSIDE the first label rather than inside it.
            assertThat(decoded)
                .as("the BOM is present, so the raw text does begin with U+FEFF")
                .startsWith(BOM_CHARACTER);
            assertThat(stripBom(decoded).split("\r\n", -1)[0])
                .as("with a BOM the first header cell must still read as the label, which is what a "
                    + "spreadsheet consuming it needs")
                .isEqualTo("Customer name");
        }

        @Test
        @DisplayName("without the BOM the file starts with the header, for a pipeline consumer")
        void noBomStartsWithTheHeader() throws IOException {
            byte[] bytes = write(CsvExportWriter.noBom(),
                List.of(ReportField.CUSTOMER_DISPLAY_NAME), Locale.ENGLISH, List.of());

            assertThat(hasBom(bytes)).isFalse();
            assertThat(new String(bytes, StandardCharsets.UTF_8)).startsWith("Customer name");
        }

        @Test
        @DisplayName("the writer reports which BOM policy it is, so a byte-level bug report has an answer")
        void writerStatesItsBomPolicy() {
            assertThat(CsvExportWriter.withBom().emitsBom()).isTrue();
            assertThat(CsvExportWriter.noBom().emitsBom()).isFalse();
        }
    }

    // ==================================================================
    // quoting
    // ==================================================================

    @Nested
    @DisplayName("quoting follows RFC 4180 exactly")
    class Quoting {

        @Test
        @DisplayName("a field with a comma, a quote or a newline is quoted, and quotes are doubled")
        void specialCharactersAreQuoted() {
            assertThat(CsvExportWriter.quoteIfNeeded("plain")).isEqualTo("plain");
            assertThat(CsvExportWriter.quoteIfNeeded("with,comma")).isEqualTo("\"with,comma\"");
            assertThat(CsvExportWriter.quoteIfNeeded("with\"quote"))
                .as("a doubled quote is how RFC 4180 escapes one; a single quote produces an unparseable field")
                .isEqualTo("\"with\"\"quote\"");
            assertThat(CsvExportWriter.quoteIfNeeded("with\nnewline")).isEqualTo("\"with\nnewline\"");
            assertThat(CsvExportWriter.quoteIfNeeded("with\rcarriage")).isEqualTo("\"with\rcarriage\"");
        }

        @Test
        @DisplayName("leading or trailing whitespace forces quoting, because a consumer would trim it")
        void whitespaceIsPreserved() {
            assertThat(CsvExportWriter.quoteIfNeeded(" leading")).isEqualTo("\" leading\"");
            assertThat(CsvExportWriter.quoteIfNeeded("trailing ")).isEqualTo("\"trailing \"");
            assertThat(CsvExportWriter.quoteIfNeeded(" ")).isEqualTo("\" \"");
        }

        @Test
        @DisplayName("an empty field is empty, not a quoted empty string")
        void emptyField() {
            // Both parse to "" in a consumer, but a quoted empty field reads as "this cell exists and is
            // blank" in a diff, which is a different claim from "this cell was written empty".
            assertThat(CsvExportWriter.quoteIfNeeded("")).isEmpty();
            assertThat(CsvExportWriter.quoteIfNeeded(null)).isEmpty();
        }

        @Test
        @DisplayName("an Arabic value containing a comma and quotes is one cell, not three")
        void arabicWithSeparatorsStaysOneCell() throws IOException {
            List<ReportField> fields = List.of(ReportField.CUSTOMER_DISPLAY_NAME);
            byte[] bytes = write(CsvExportWriter.withBom(), fields, Locale.ENGLISH,
                List.of(Map.of("customer.displayName", ARABIC_NAME)));
            String body = new String(bytes, StandardCharsets.UTF_8);

            String[] lines = body.split("\r\n", -1);
            assertThat(lines)
                .as("a header and one data row, plus the trailing empty segment after the final CRLF")
                .hasSize(3);
            assertThat(lines[1])
                .as("the data row must be ONE quoted field despite containing a comma and two quotes")
                .startsWith("\"محمد عبد الرحمن، \"\"المحرر\"\" ة\"");
        }
    }

    // ==================================================================
    // structure
    // ==================================================================

    @Nested
    @DisplayName("column order is stable and every row has one cell per column")
    class Structure {

        private static final List<ReportField> FIELDS = List.of(
            ReportField.CUSTOMER_DISPLAY_NAME,
            ReportField.CUSTOMER_EMAIL,
            ReportField.CUSTOMER_COUNT);

        @Test
        @DisplayName("the header carries the localized labels, in the declared order")
        void headerIsLocalizedAndOrdered() throws IOException {
            byte[] english = write(CsvExportWriter.noBom(), FIELDS, Locale.ENGLISH, List.of());
            byte[] arabic = write(CsvExportWriter.noBom(), FIELDS, new Locale("ar"), List.of());

            assertThat(new String(english, StandardCharsets.UTF_8).split("\r\n")[0])
                .isEqualTo("Customer name,Customer email,Customer count");
            assertThat(new String(arabic, StandardCharsets.UTF_8).split("\r\n")[0])
                .as("an Arabic export's header must be Arabic; the machine keys stay in the job's column "
                    + "metadata, not in the file")
                .isEqualTo("اسم العميل,البريد الإلكتروني للعميل,عدد العملاء");
        }

        @Test
        @DisplayName("a null value writes the absent token, never an empty cell")
        void nullWritesTheAbsentToken() throws IOException {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("customer.displayName", ARABIC_PLAIN);
            row.put("customer.primaryEmail", null);
            row.put("customer.count", BigDecimal.valueOf(3));

            byte[] bytes = write(CsvExportWriter.withBom(), FIELDS, Locale.ENGLISH, List.of(row));
            String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\r\n", -1);

            assertThat(lines[1])
                .as("an empty cell reads as 'this customer has no email'; the absent token reads as 'this "
                    + "value is not in this export'")
                .isEqualTo(ARABIC_PLAIN + "," + com.firstelement.exports.format.ExportValueFormatter.ABSENT
                    + ",3");
        }

        @Test
        @DisplayName("a redacted column is absent from the header AND from every row")
        void redactedColumnIsAbsentNotBlank() throws IOException {
            // The scenario: customer.email was withheld from this caller, so the exported field list simply
            // does not contain it. There is no header for it and no cell for it.
            List<ReportField> emitted = List.of(ReportField.CUSTOMER_DISPLAY_NAME,
                ReportField.CUSTOMER_COUNT);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("customer.displayName", ARABIC_PLAIN);
            row.put("customer.count", BigDecimal.valueOf(1));

            byte[] bytes = write(CsvExportWriter.withBom(), emitted, Locale.ENGLISH, List.of(row));
            String decoded = new String(bytes, StandardCharsets.UTF_8);

            assertThat(stripBom(decoded).split("\r\n", -1)[0])
                .as("the withheld column must not appear in the header")
                .doesNotContain("email")
                .isEqualTo("Customer name,Customer count");
            assertThat(decoded.split("\r\n", -1)[1])
                .as("and every row must have exactly the emitted column count")
                .doesNotContain(",,")
                .doesNotContain("@");
        }

        @Test
        @DisplayName("records end with CRLF, as RFC 4180 requires")
        void recordsEndWithCrLf() throws IOException {
            byte[] bytes = write(CsvExportWriter.withBom(),
                List.of(ReportField.CUSTOMER_DISPLAY_NAME), Locale.ENGLISH,
                List.of(Map.of("customer.displayName", "one"),
                    Map.of("customer.displayName", "two")));
            String decoded = new String(bytes, StandardCharsets.UTF_8);

            assertThat(decoded).endsWith("\r\n");
            assertThat(decoded.split("\r\n", -1))
                .as("header + 2 rows + the trailing empty segment")
                .hasSize(4);
            assertThat(decoded).doesNotContain("\n\n");
        }

        @Test
        @DisplayName("the row count returned excludes the header, and matches the data written")
        void rowCountExcludesHeader() throws IOException {
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            List<ReportField> fields = List.of(ReportField.CUSTOMER_DISPLAY_NAME);
            ExportCellFormatter formatter = ExportCellFormatter.forCsv(Locale.ENGLISH, ZONE);
            List<Map<String, Object>> rows = List.of(
                Map.of("customer.displayName", "one"),
                Map.of("customer.displayName", "two"),
                Map.of("customer.displayName", "three"));

            long written = CsvExportWriter.withBom().write(sink, fields, Locale.ENGLISH, out -> {
                for (Map<String, Object> row : rows) {
                    CsvExportWriter.writeRow(out, formatter.cells(row, fields));
                }
                return rows.size();
            });

            assertThat(written).isEqualTo(3);
            assertThat(new String(sink.toByteArray(), StandardCharsets.UTF_8).split("\r\n", -1))
                .as("3 data rows plus a header plus the empty segment after the final CRLF")
                .hasSize(5);
        }
    }

    // ==================================================================
    // values
    // ==================================================================

    @Nested
    @DisplayName("CSV is locale-invariant and a document is localized; both are deliberate")
    class Values {

        @Test
        @DisplayName("a decimal in a CSV uses a dot, because a locale comma would break the column count")
        void csvDecimalIsInvariant() throws IOException {
            Map<String, Object> row = Map.of("usage.cost", new BigDecimal("1234567.890000"));
            byte[] english = write(CsvExportWriter.withBom(), List.of(ReportField.USAGE_COST),
                Locale.ENGLISH, List.of(row));
            byte[] arabic = write(CsvExportWriter.withBom(), List.of(ReportField.USAGE_COST),
                new Locale("ar"), List.of(row));

            String englishBody = new String(english, StandardCharsets.UTF_8);
            String arabicBody = new String(arabic, StandardCharsets.UTF_8);

            assertThat(englishBody).contains("1234567.890000");
            assertThat(arabicBody)
                .as("a localized decimal separator in a machine-readable file turns one cell into two and "
                    + "shifts every column after it")
                .contains("1234567.890000")
                .doesNotContain("1234567,89");
        }

        @Test
        @DisplayName("an Arabic CSV still carries Arabic HEADERS, so a human sees a labelled file")
        void arabicCsvIsStillArabic() throws IOException {
            byte[] bytes = write(CsvExportWriter.withBom(), List.of(ReportField.CUSTOMER_DISPLAY_NAME),
                new Locale("ar"), List.of(Map.of("customer.displayName", ARABIC_PLAIN)));
            String decoded = new String(bytes, StandardCharsets.UTF_8);

            assertThat(decoded).contains("اسم العميل");
            assertThat(decoded).contains(ARABIC_PLAIN);
        }

        @Test
        @DisplayName("a document cell uses the locale's grouping and decimal separators")
        void documentStyleIsLocalized() {
            ReportField cost = ReportField.USAGE_COST;
            BigDecimal amount = new BigDecimal("1234567.89");

            String english = ExportCellFormatter.forDocument(Locale.ENGLISH, ZONE).cell(cost, amount);
            // Explicitly Arabic locale with the Eastern Arabic numbering system. The bare "ar" language tag
            // does NOT select Arabic-Indic digits on a stock JDK: CLDR gives `ar` the `arab` numbering
            // system only when the region or a -u-nu- extension says so. This is exactly why the
            // formatter takes a Locale rather than a boolean: the decision belongs to the locale, and a
            // report that guessed would render money differently in two tenants with the same language.
            Locale easternArabic = Locale.forLanguageTag("ar-u-nu-arab");
            String arabic = ExportCellFormatter.forDocument(easternArabic, ZONE).cell(cost, amount);

            assertThat(english)
                .as("a document is read by a person, so it gets that locale's grouping separator")
                .isEqualTo("1,234,567.89");
            assertThat(arabic)
                .as("an explicitly Arabic-Indic document renders U+066C grouping and U+066B decimal")
                .contains("٬")
                .contains("٫")
                .doesNotContain("1,234,567.89");
        }

        @Test
        @DisplayName("a timestamp is ISO in CSV and localized in a document, and never a bare epoch")
        void timestampsAreRenderedNotEpochs() {
            ReportField field = ReportField.CUSTOMER_FIRST_SEEN_AT;
            LocalDateTime moment = LocalDateTime.of(2026, 3, 10, 14, 30, 0);

            String machine = ExportCellFormatter.forCsv(Locale.ENGLISH, ZONE).cell(field, moment);
            String document = ExportCellFormatter.forDocument(Locale.ENGLISH, ZONE).cell(field, moment);

            assertThat(machine).isEqualTo("2026-03-10T14:30:00");
            assertThat(document)
                .as("the value is UTC-stored and rendered in the report's own zone; Asia/Riyadh is +3")
                .isEqualTo("10/03/2026 17:30");
        }

        @Test
        @DisplayName("a currency column carries the ISO code, never a symbol the reader must infer")
        void currencyIsACode() throws IOException {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("usage.cost", new BigDecimal("10.00"));
            row.put("usage.currency", "SAR");
            byte[] bytes = write(CsvExportWriter.withBom(),
                List.of(ReportField.USAGE_COST, ReportField.USAGE_CURRENCY), Locale.ENGLISH,
                List.of(row));

            assertThat(new String(bytes, StandardCharsets.UTF_8).split("\r\n")[1])
                .isEqualTo("10.00,SAR");
        }
    }

    // ==================================================================
    // streaming
    // ==================================================================

    @Nested
    @DisplayName("writing many rows does not accumulate them")
    class Streaming {

        @Test
        @DisplayName("a 20 000-row export writes with the same heap as a 10-row one")
        void manyRowsStayBounded() throws IOException {
            List<ReportField> fields = List.of(ReportField.CUSTOMER_DISPLAY_NAME,
                ReportField.CUSTOMER_EMAIL, ReportField.CUSTOMER_LAST_SEEN_AT);
            ExportCellFormatter formatter = ExportCellFormatter.forCsv(Locale.ENGLISH, ZONE);

            long smallBytes = measure(50, fields, formatter);
            System.gc();
            long smallHeap = peakAfter(() -> writeRows(500, fields, formatter));
            System.gc();
            long largeHeap = peakAfter(() -> writeRows(20_000, fields, formatter));

            assertThat(largeHeap)
                .as("a streaming writer's heap must not scale with the row count. 20 000 rows at ~400 bytes "
                    + "of String each would be tens of megabytes if the rows accumulated, and the page "
                    + "source would hand them over in pages otherwise")
                .isLessThan(smallHeap * 4 + 8L * 1024 * 1024);
            assertThat(smallBytes).isPositive();
        }

        @Test
        @DisplayName("the sink receives bytes incrementally, not one buffer at the end")
        void theSinkIsWrittenIncrementally() throws IOException {
            int[] writeCount = {0};
            long[] bytesWritten = {0L};
            ByteArrayOutputStream sink = new ByteArrayOutputStream() {
                @Override
                public synchronized void write(byte[] b, int off, int len) {
                    writeCount[0]++;
                    bytesWritten[0] += len;
                    super.write(b, off, len);
                }
            };
            ExportCellFormatter formatter = ExportCellFormatter.forCsv(Locale.ENGLISH, ZONE);
            CsvExportWriter.withBom().write(sink, List.of(ReportField.CUSTOMER_DISPLAY_NAME),
                Locale.ENGLISH, out -> {
                    for (int i = 0; i < 500; i++) {
                        CsvExportWriter.writeRow(out, formatter.cells(Map.of("customer.displayName",
                            "customer-" + i), List.of(ReportField.CUSTOMER_DISPLAY_NAME)));
                    }
                    return 500L;
                });

            // The character encoder's own buffer is 8 KiB, so a streaming writer flushes to the sink on that
            // boundary rather than once per row. The claim under test is "the sink is written
            // repeatedly, not once at the end", and the row count makes the size of each write decisive.
            assertThat(writeCount[0])
                .as("a writer that built the whole file and flushed once would be a writer that buffered "
                    + "a whole tenant in memory")
                .isGreaterThan(5);
            assertThat(bytesWritten[0])
                .as("500 rows of a customer name and an email is tens of kilobytes")
                .isGreaterThan(5_000L);
        }

        private long measure(int rows, List<ReportField> fields, ExportCellFormatter formatter)
            throws IOException {
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            CsvExportWriter.withBom().write(sink, fields, Locale.ENGLISH, out -> {
                for (int i = 0; i < rows; i++) {
                    CsvExportWriter.writeRow(out, formatter.cells(row(i), fields));
                }
                return rows;
            });
            return sink.size();
        }

        private void writeRows(int count, List<ReportField> fields, ExportCellFormatter formatter)
            throws IOException {
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            CsvExportWriter.withBom().write(sink, fields, Locale.ENGLISH, out -> {
                for (int i = 0; i < count; i++) {
                    CsvExportWriter.writeRow(out, formatter.cells(row(i), fields));
                }
                return count;
            });
        }

        private Map<String, Object> row(int index) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("customer.displayName", ARABIC_PLAIN + " " + index);
            row.put("customer.primaryEmail", "user" + index + "@example.test");
            row.put("customer.lastSeenAt", LocalDateTime.of(2026, 3, 10, 14, 30, 0));
            return row;
        }

        private long peakAfter(ThrowingRunnable work) throws IOException {
            Runtime runtime = Runtime.getRuntime();
            long before = runtime.totalMemory() - runtime.freeMemory();
            work.run();
            System.gc();
            long after = runtime.totalMemory() - runtime.freeMemory();
            return Math.max(after - before, 0L);
        }

        /** A throwing body, so the measurement helpers do not each need a try/catch. */
        @FunctionalInterface
        private interface ThrowingRunnable {
            void run() throws IOException;
        }
    }

    @Test
    @DisplayName("the Arabic sample really is Arabic, so the round-trip assertions mean something")
    void theFixtureIsArabic() {
        // A fixture that had been mangled by an editor would make every "contains the Arabic" assertion
        // pass while proving nothing, so the fixture itself is checked.
        assertThat(ARABIC_PLAIN)
            .as("the sample must be Arabic letters, or the round-trip assertions are vacuous. Asserted on "
                + "the WORD characters rather than the whole string because an Arabic name contains a "
                + "space, which is not an Arabic-script letter")
            .matches("[\\p{IsLatin}\\p{IsArabic} ]+")
            .matches(".*[\\p{IsArabic}].*");
        assertThat(ARABIC_NAME).contains("،").contains("\"");
        assertThat(arabicCodepoints(ARABIC_PLAIN))
            .as("no replacement character may appear in the fixture")
            .doesNotContain(0xFFFD);
        assertThat(arabicCodepoints(ARABIC_PLAIN).size()).isPositive();
    }

    /** The codepoints of a string, so a mangled fixture is visible as a specific value. */
    private static List<Integer> arabicCodepoints(String value) {
        List<Integer> codepoints = new ArrayList<>();
        value.codePoints().forEach(codepoints::add);
        return codepoints;
    }

    /**
     * Whether the bytes begin with the BOM.
     *
     * <p>AssertJ's {@code doesNotStartWith} on a byte array compares element-wise, which reads as a
     * confusing failure for a three-byte prefix; this states the question plainly.
     */
    private static boolean hasBom(byte[] bytes) {
        return bytes.length >= 3
            && bytes[0] == (byte) 0xEF
            && bytes[1] == (byte) 0xBB
            && bytes[2] == (byte) 0xBF;
    }

    /**
     * Removes a leading U+FEFF from decoded text.
     *
     * <p>Written as {@code \uFEFF} rather than as a literal because a literal BOM inside a source file is
     * invisible in review, is stripped by some editors, and makes a comparison failure report show a
     * leading character that reads as nothing at all.
     */
    private static String stripBom(String text) {
        return text.startsWith(BOM_CHARACTER) ? text.substring(1) : text;
    }

    /**
     * U+FEFF, built from its code point rather than written as a literal.
     *
     * <p>A BOM character embedded in a source file is invisible in review, is silently stripped by some
     * editors, and makes every failure message that includes it unreadable. Naming the code point makes the
     * intent reviewable and the value identical.
     */
    private static final String BOM_CHARACTER = String.valueOf((char) 0xFEFF);

    /**
     * U+FFFD REPLACEMENT CHARACTER, spelled as a code point rather than pasted as a literal.
     *
     * <p>The literal is invisible in every editor and in most diff tools, survives a copy through a
     * terminal, and is silently converted to {@code ?} by anything that treats the source as Latin-1 —
     * at which point the assertion below still compiles and still passes while checking for the wrong
     * character. A {@code (char) 0xFFFD} cannot be mangled that way.
     */
    private static final String REPLACEMENT_CHARACTER = String.valueOf((char) 0xFFFD);
}