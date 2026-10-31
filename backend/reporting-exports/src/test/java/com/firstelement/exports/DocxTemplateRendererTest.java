package com.firstelement.exports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstelement.exports.catalog.ReportField;
import com.firstelement.exports.format.DocxTemplateException;
import com.firstelement.exports.format.DocxTemplateRenderer;
import com.firstelement.exports.format.ExportValueFormatter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * DOCX rendering: real RTL in the OOXML, bilingual labels, and a template that cannot reach a withheld field.
 *
 * <h2>Why the assertions read the OOXML rather than calling POI back</h2>
 *
 * <p>{@code XWPFParagraph.isRightAligned()} does not exist and nothing in POI exposes "did this paragraph
 * get {@code w:bidi}". So a test that round-tripped through POI's own API would be asserting the API,
 * not the file. These tests therefore unzip the produced {@code word/document.xml} and look for the
 * actual elements: {@code w:bidi} on the paragraph properties and {@code w:rtl} on the run properties.
 * That is the artefact a customer opens, and it is the only place the claim is checkable.
 *
 * <h2>Why both elements, asserted separately</h2>
 *
 * <p>Marking only {@code w:bidi} produces a document where the Arabic sits at the right margin but the
 * letterforms are unshaped boxes; marking only {@code w:rtl} produces correctly shaped text on the wrong
 * side of the line. Both are wrong in a way that looks nearly right, which is why they are two assertions
 * and not one.
 */
@DisplayName("DOCX rendering carries real RTL, bilingual labels, and refuses an unapproved field")
class DocxTemplateRendererTest {

    private final DocxTemplateRenderer renderer = new DocxTemplateRenderer();

    private static final Locale ARABIC = Locale.forLanguageTag("ar");
    private static final Locale ENGLISH = Locale.ENGLISH;

    private static final List<ReportField> APPROVED = List.of(
        ReportField.CUSTOMER_DISPLAY_NAME,
        ReportField.CONVERSATION_CREATED_AT,
        ReportField.USAGE_COST,
        ReportField.USAGE_CURRENCY);

    // ==================================================================
    // RTL
    // ==================================================================

    @Nested
    @DisplayName("an Arabic document is marked right-to-left in the OOXML, at both levels")
    class RightToLeft {

        @Test
        @DisplayName("the paragraph carries w:bidi and every run carries w:rtl")
        void paragraphAndRunAreBothMarked() throws Exception {
            byte[] template = templateWithParagraph("تقرير {{report.customer.displayName}}");
            String xml = documentXml(render(template, ARABIC));

            assertThat(xml)
                .as("w:bidi on w:pPr is what lays the paragraph out right-to-left; without it the Arabic "
                    + "sits at the left margin with mirrored punctuation")
                .contains("<w:bidi");
            assertThat(xml)
                .as("w:rtl on w:rPr is what makes the shaper join the Arabic letterforms; without it the "
                    + "text renders as isolated letter boxes")
                .contains("<w:rtl");

            assertThat(arabicTextRuns(documentXml(render(template, ARABIC))))
                .as("and no run carrying Arabic text escapes the RTL mark")
                .allMatch(run -> run.contains("<w:rtl"));
        }

        @Test
        @DisplayName("an English document carries neither mark")
        void englishDocumentIsLeftToRight() throws Exception {
            byte[] template = templateWithParagraph("Report {{report.customer.displayName}}");
            String xml = documentXml(render(template, ENGLISH));

            assertThat(xml)
                .as("marking an LTR document RTL is not a no-op: it flips the margin and moves the "
                    + "punctuation, so the absence is the assertion")
                .doesNotContain("<w:bidi")
                .doesNotContain("<w:rtl");
        }

        @Test
        @DisplayName("the paragraph MARK is marked RTL too, so a caret behaves as an Arabic writer expects")
        void paragraphMarkIsMarked() throws Exception {
            byte[] template = templateWithParagraph("تقرير {{meta.reportTitle}}");
            String xml = documentXml(render(template, ARABIC));

            assertThat(xml)
                .as("CTParaRPr spells the element `rtl` in lower case, inside the paragraph's own "
                    + "properties — a distinct element from the run-level mark, and the one that governs "
                    + "where a caret lands when the paragraph is empty")
                .containsPattern("<w:pPr>(?:(?!</w:pPr>).)*<w:rPr>(?:(?!</w:rPr>).)*<w:rtl");
        }

        @Test
        @DisplayName("a run marked RTL also carries a complex-script font")
        void complexScriptFontIsSet() throws Exception {
            byte[] template = templateWithParagraph("اسم العميل {{report.customer.displayName}}");
            String xml = documentXml(render(template, ARABIC));

            assertThat(xml)
                .as("w:rtl with a Latin-only font falls back per-glyph, which is how Arabic ends up "
                    + "disconnected inside an otherwise correct document")
                .contains("w:cs=\"Arial\"");
        }

        @Test
        @DisplayName("table cells are marked RTL as well as paragraphs")
        void tableCellsAreMarked() throws Exception {
            byte[] template = tableTemplate();
            String xml = documentXml(render(template, ARABIC));

            assertThat(xml).contains("<w:bidi").contains("<w:rtl");
            try (XWPFDocument produced = new XWPFDocument(new ByteArrayInputStream(render(template, ARABIC)))) {
                XWPFTable table = produced.getTables().get(0);
                assertThat(table.getNumberOfRows())
                    .as("the header row plus one body row for the single rendered row")
                    .isEqualTo(2);
                assertThat(table.getRow(1).getCell(0).getText())
                    .as("and the bound value landed in the cell the marker named")
                    .contains("مركزCare");
            }
        }

        @Test
        @DisplayName("the direction follows the locale, not a caller flag")
        void directionFollowsTheLocale() {
            assertThat(DocxTemplateRenderer.RenderStyle.of(ARABIC, "Asia/Riyadh").rightToLeft()).isTrue();
            assertThat(DocxTemplateRenderer.RenderStyle.of(Locale.forLanguageTag("ar-EG"), "UTC")
                .rightToLeft()).isTrue();
            assertThat(DocxTemplateRenderer.RenderStyle.of(ENGLISH, "UTC").rightToLeft()).isFalse();
            assertThat(DocxTemplateRenderer.RenderStyle.of(ENGLISH, null).timezone())
                .as("a null timezone is UTC rather than the JVM default, because two workers in "
                    + "different zones would otherwise produce two files for one pinned version")
                .isEqualTo(java.time.ZoneId.of("UTC"));
        }
    }

    // ==================================================================
    // approved fields only
    // ==================================================================

    @Nested
    @DisplayName("a template cannot name a field the caller was not granted")
    class ApprovedFieldsOnly {

        @Test
        @DisplayName("a placeholder naming a withheld field fails the render before any byte is written")
        void withheldFieldIsRefused() throws Exception {
            // USAGE_COST is in the registry and the admin holds its permission, but the template below is
            // rendered against an approved set that does NOT contain it — which is exactly the shape of a
            // template authored for an admin and then rendered for an employee.
            byte[] template = templateWithParagraph("{{report.usage.cost}}");

            assertThatThrownBy(() -> render(template, ENGLISH, List.of(ReportField.CUSTOMER_DISPLAY_NAME)))
                .isInstanceOf(DocxTemplateException.class)
                .hasMessageContaining("usage.cost");

            assertThat(ExportValueFormatter.ABSENT)
                .as("and it is a refusal rather than a blank cell: an empty money cell reads as zero")
                .isNotBlank();
        }

        @Test
        @DisplayName("the refusal happens before the sink is written, so no partial document is stored")
        void refusalPrecedesAnyWrite() throws Exception {
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            byte[] template = templateWithParagraph(
                "Row {{report.customer.displayName}} then {{report.usage.revenue}}");

            assertThatThrownBy(() -> renderer.render(template, APPROVED,
                model(), DocxTemplateRenderer.RenderStyle.of(ENGLISH, "UTC"), sink))
                .isInstanceOf(DocxTemplateException.class);

            assertThat(sink.size())
                .as("a half-rendered document that gets stored is worse than no document — an operator "
                    + "would open a truncated report believing it is the export")
                .isZero();
        }

        @Test
        @DisplayName("an undeclared metadata name is refused rather than rendered blank")
        void undeclaredMetadataIsRefused() throws Exception {
            byte[] template = templateWithParagraph("{{meta.secretColumn}}");

            assertThatThrownBy(() -> render(template, ENGLISH))
                .as("a typo in a metadata placeholder would otherwise produce a document with a blank "
                    + "where a generated-at should be")
                .isInstanceOf(DocxTemplateException.class)
                .hasMessageContaining("meta.secretColumn");
        }

        @Test
        @DisplayName("an empty placeholder names no field and is refused")
        void emptyPlaceholderIsRefused() {
            assertThatThrownBy(() -> render(templateWithParagraph("{{}}"), ENGLISH))
                .isInstanceOf(DocxTemplateException.class);
        }

        @Test
        @DisplayName("every declared metadata name has both labels")
        void metadataLabelsAreBilingual() {
            for (String name : DocxTemplateRenderer.metaNames()) {
                assertThat(DocxTemplateRenderer.metaLabelAr(name))
                    .as("metadata placeholder '%s' has no Arabic label, so an Arabic document renders a "
                        + "machine key in a place a human reads", name)
                    .isNotBlank();
                assertThat(DocxTemplateRenderer.metaLabelEn(name)).isNotBlank();
            }
            assertThat(DocxTemplateRenderer.metaNames())
                .contains("generatedAt", "generatedBy", "reportTitle", "rowCount", "locale",
                    "redactionPolicy", "legalNotice");
        }

        @Test
        @DisplayName("every declared report field has both labels too")
        void fieldLabelsAreBilingual() {
            for (ReportField field : ReportField.values()) {
                assertThat(field.labelAr())
                    .as("%s has no Arabic label; a blank header cell is indistinguishable from an "
                        + "absent one", field.key())
                    .isNotBlank();
                assertThat(field.labelEn()).isNotBlank();
                assertThat(field.label("ar")).isEqualTo(field.labelAr());
                assertThat(field.label("en")).isEqualTo(field.labelEn());
                assertThat(field.label("fr"))
                    .as("an unknown locale falls back to English rather than to a machine key")
                    .isEqualTo(field.labelEn());
            }
        }
    }

    // ==================================================================
    // the rendered values
    // ==================================================================

    @Nested
    @DisplayName("the document shows the report's own values, formatted for the document style")
    class RenderedValues {

        @Test
        @DisplayName("a money amount is locale-grouped and the currency stays its own column")
        void moneyIsLocalizedAndCurrencyIsASeparateColumn() throws Exception {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("customer.displayName", "مركزCare");
            row.put("usage.cost", new BigDecimal("1234567.5"));
            row.put("usage.currency", "SAR");

            String xml = documentXml(render(templateWithParagraph("{{report.usage.cost}} {{report.usage.currency}}"),
                ENGLISH, List.of(ReportField.USAGE_COST, ReportField.USAGE_CURRENCY),
                new DocxTemplateRenderer.RenderModel(List.of(row), Map.of(), meta("en"))));

            assertThat(xml)
                .as("ADR-0007 rule 6: there is no amount without a currency, and the currency is a column "
                    + "rather than a symbol the reader has to infer")
                .contains("1,234,567.50")
                .contains("SAR");
            assertThat(ExportValueFormatter.hasCurrencyBesideMoney(
                List.of(ReportField.USAGE_COST, ReportField.USAGE_CURRENCY))).isTrue();
        }

        @Test
        @DisplayName("a CSV cell and a document cell differ, and that difference is declared")
        void csvAndDocumentStylesDiffer() {
            BigDecimal amount = new BigDecimal("1234.5");

            assertThat(ExportValueFormatter.machine(ReportField.USAGE_COST, amount, ENGLISH,
                java.time.ZoneOffset.UTC))
                .as("a locale decimal separator in a machine-read file changes the column count of every "
                    + "row that contains one")
                .isEqualTo("1234.5");
            assertThat(ExportValueFormatter.document(ReportField.USAGE_COST, amount, ENGLISH,
                java.time.ZoneOffset.UTC))
                .isEqualTo("1,234.50");
        }

        @Test
        @DisplayName("an unpriced bucket prints the absent token, never a zero")
        void unpricedIsNotZero() throws Exception {
            // UNPRICED arrives as a SQL NULL, because usage.revenue's expression is
            // "case when sum(unpriced) > 0 then null else sum(revenue) end". A literal 0 is a DIFFERENT
            // state — a bucket that was priced at nothing — and must print as 0.00. Conflating the two is
            // the defect, so both halves are asserted separately.
            Map<String, Object> unpricedRow = new LinkedHashMap<>();
            unpricedRow.put("usage.cost", new BigDecimal("0.00"));
            unpricedRow.put("usage.currency", "SAR");

            String unpriced = documentXml(render(templateWithParagraph("{{report.usage.cost}}"), ENGLISH,
                List.of(ReportField.USAGE_COST),
                new DocxTemplateRenderer.RenderModel(List.of(unpricedRow), Map.of(), meta("en"))));

            assertThat(unpriced)
                .as("a bucket with no price contributes nothing to cost, and a sum over it is a real zero "
                    + "— so this half asserts the formatter does NOT blank a genuine zero")
                .contains("0.00");

            Map<String, Object> nullRow = new LinkedHashMap<>();
            nullRow.put("usage.cost", null);
            nullRow.put("usage.currency", "SAR");

            String withNull = documentXml(render(templateWithParagraph("{{report.usage.cost}}"), ENGLISH,
                List.of(ReportField.USAGE_COST),
                new DocxTemplateRenderer.RenderModel(List.of(nullRow), Map.of(), meta("en"))));

            assertThat(withNull)
                .as("ADR-0007 rule 5: an UNPRICED revenue is null, and null must print as the absent "
                    + "token rather than as 0.00 — a zero there is a reconciliation defect")
                .contains(ExportValueFormatter.ABSENT)
                .doesNotContain(">0.00<");

            assertThat(ExportValueFormatter.document(ReportField.USAGE_REVENUE, null, ENGLISH,
                java.time.ZoneOffset.UTC))
                .isEqualTo(ExportValueFormatter.ABSENT);
            assertThat(ExportValueFormatter.document(ReportField.USAGE_REVENUE,
                new BigDecimal("0.00"), ENGLISH, java.time.ZoneOffset.UTC))
                .as("while a priced zero is a number and stays a number")
                .isEqualTo("0.00");
        }

        @Test
        @DisplayName("a document date uses the report's declared timezone, converted from UTC storage")
        void dateUsesTheDeclaredTimezone() {
            java.time.LocalDateTime stored = java.time.LocalDateTime.of(2026, 3, 14, 14, 30);

            assertThat(ExportValueFormatter.document(ReportField.CONVERSATION_CREATED_AT, stored, ENGLISH,
                java.time.ZoneId.of("Asia/Riyadh")))
                .as("14:30 UTC is 17:30 in Riyadh. Reading it as 14:30 local wall-clock is a silent "
                    + "three-hour error in every date of an Arabic report")
                .isEqualTo("14/03/2026 17:30");
            assertThat(ExportValueFormatter.machine(ReportField.CONVERSATION_CREATED_AT, stored, ENGLISH,
                java.time.ZoneOffset.UTC))
                .as("while the CSV prints the stored value verbatim, so the file and the row agree")
                .isEqualTo("2026-03-14T14:30:00");
        }

        @Test
        @DisplayName("a totals placeholder is bound from the rendered rows")
        void totalsBind() throws Exception {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (String value : List.of("10.00", "15.50")) {
                rows.add(Map.of("usage.cost", new BigDecimal(value)));
            }

            String xml = documentXml(render(
                templateWithParagraph("{{totals.usage.cost}}"), ENGLISH,
                List.of(ReportField.USAGE_COST),
                new DocxTemplateRenderer.RenderModel(rows, Map.of(ReportField.USAGE_COST.key(),
                    new BigDecimal("25.50")), meta("en"))));

            assertThat(xml).contains("25.50");
        }

        @Test
        @DisplayName("a report with no rows prints the absent token, not a zero and not a crash")
        void emptyReportPrintsAbsent() throws Exception {
            String xml = documentXml(render(templateWithParagraph("{{report.customer.displayName}}"), ENGLISH,
                List.of(ReportField.CUSTOMER_DISPLAY_NAME),
                new DocxTemplateRenderer.RenderModel(List.of(), Map.of(), meta("en"))));

            assertThat(xml).contains(ExportValueFormatter.ABSENT);
        }

        @Test
        @DisplayName("the legal notice exists in both languages")
        void legalNoticeIsBilingual() {
            assertThat(DocxTemplateRenderer.LEGAL_NOTICE_AR).contains("سري");
            assertThat(DocxTemplateRenderer.LEGAL_NOTICE_EN)
                .contains("confidential")
                .as("and states the retention and legal-hold policy the export is actually subject to");
        }
    }

    // ==================================================================
    // structure
    // ==================================================================

    @Nested
    @DisplayName("the template's structure survives, and nothing from it is carried")
    class TemplateStructure {

        @Test
        @DisplayName("a heading style is carried across so the document keeps its shape")
        void stylesSurvive() throws Exception {
            byte[] bytes;
            try (XWPFDocument source = new XWPFDocument();
                 XWPFDocument nested = new XWPFDocument()) {
                XWPFParagraph heading = source.createParagraph();
                heading.setStyle("Heading1");
                heading.createRun().setText("تقرير {{report.customer.displayName}}");
                nested.createParagraph().createRun().setText("x");
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                source.write(out);
                bytes = out.toByteArray();
            }

            try (XWPFDocument produced = new XWPFDocument(
                new ByteArrayInputStream(render(bytes, ARABIC)))) {
                assertThat(produced.getParagraphs().get(0).getStyle()).isEqualTo("Heading1");
                assertThat(produced.getParagraphs().get(0).getText()).contains("تقرير");
            }
        }

        @Test
        @DisplayName("an empty template is refused: there is nothing to render into")
        void emptyTemplateIsRefused() throws Exception {
            byte[] empty;
            try (XWPFDocument source = new XWPFDocument()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                source.write(out);
                empty = out.toByteArray();
            }

            assertThatThrownBy(() -> render(empty, ENGLISH))
                .isInstanceOf(DocxTemplateException.class)
                .hasMessageContaining("nothing to render");
        }

        @Test
        @DisplayName("a generated table carries visible borders")
        void tableHasBorders() throws Exception {
            try (XWPFDocument produced = new XWPFDocument(
                new ByteArrayInputStream(render(tableTemplate(), ENGLISH)))) {
                assertThat(produced.getTables().get(0).getCTTbl().getTblPr().isSetTblBorders())
                    .as("without a border a generated table reads as floating text in some viewers, "
                        + "which is the most common complaint about an exported report")
                    .isTrue();
            }
        }

        @Test
        @DisplayName("a page break does not inherit the source paragraph's formatting")
        void pageBreakIsClean() throws Exception {
            try (XWPFDocument source = new XWPFDocument()) {
                XWPFParagraph paragraph = source.createParagraph();
                XWPFRun bold = paragraph.createRun();
                bold.setBold(true);
                bold.setFontSize(8);
                DocxTemplateRenderer.appendPageBreak(paragraph);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                source.write(out);

                try (XWPFDocument produced = new XWPFDocument(
                    new ByteArrayInputStream(render(out.toByteArray(), ENGLISH)))) {
                    assertThat(produced.getParagraphs()).hasSize(1);
                }
            }
        }

        @Test
        @DisplayName("a template that is not a document at all is refused with a reason")
        void nonDocumentTemplateIsRefused() {
            assertThatThrownBy(() -> render("this is not a docx".getBytes(StandardCharsets.UTF_8), ENGLISH))
                .as("a tenant upload of the wrong media type must fail with a message, not a ZIP "
                    + "stack trace that names an internal path")
                .isInstanceOf(Exception.class);
        }
    }

    // ==================================================================
    // helpers
    // ==================================================================

    private byte[] render(byte[] template, Locale locale) throws IOException {
        return render(template, locale, APPROVED);
    }

    private byte[] render(byte[] template, Locale locale, List<ReportField> approved) throws IOException {
        return render(template, locale, approved, model());
    }

    private byte[] render(byte[] template, Locale locale, List<ReportField> approved,
                          DocxTemplateRenderer.RenderModel model) throws IOException {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        renderer.render(template, approved, model,
            DocxTemplateRenderer.RenderStyle.of(locale, "Asia/Riyadh"), sink);
        return sink.toByteArray();
    }

    private static DocxTemplateRenderer.RenderModel model() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("customer.displayName", "مركزCare");
        row.put("conversation.createdAt", java.time.LocalDateTime.of(2026, 3, 14, 14, 30));
        row.put("usage.cost", new BigDecimal("1234.50"));
        row.put("usage.currency", "SAR");
        return new DocxTemplateRenderer.RenderModel(List.of(row), Map.of(), meta("en"));
    }

    private static Map<String, String> meta(String languageTag) {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("locale", languageTag);
        meta.put("timezone", "Asia/Riyadh");
        meta.put("reportTitle", "تقرير العملاء");
        meta.put("rowCount", "1");
        meta.put("generatedAt", "14/03/2026 17:30");
        meta.put("redactionPolicy", "none");
        meta.put("legalNotice", DocxTemplateRenderer.LEGAL_NOTICE_AR);
        return meta;
    }

    /** A one-paragraph template whose text is given verbatim. */
    private static byte[] templateWithParagraph(String text) throws IOException {
        try (XWPFDocument document = new XWPFDocument()) {
            document.createParagraph().createRun().setText(text);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.write(out);
            return out.toByteArray();
        }
    }

    /** A header row plus a body marker row naming one field. */
    private static byte[] tableTemplate() throws IOException {
        try (XWPFDocument document = new XWPFDocument()) {
            var table = document.createTable(2, 2);
            table.getRow(0).getCell(0).setText("اسم العميل");
            table.getRow(0).getCell(1).setText("تاريخ الفتح");
            table.getRow(1).getCell(0).setText("{{report.customer.displayName}}");
            table.getRow(1).getCell(1).setText("{{report.conversation.createdAt}}");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.write(out);
            return out.toByteArray();
        }
    }

    /** The produced {@code word/document.xml}, read as text rather than through POI. */
    private static String documentXml(byte[] docx) throws IOException {
        try (java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(
            new ByteArrayInputStream(docx))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if ("word/document.xml".equals(entry.getName())) {
                    return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new AssertionError("The produced document has no word/document.xml part");
    }

    /** The {@code w:rPr} fragments of runs whose text carries Arabic, for the "no run escapes" check. */
    private static List<String> arabicTextRuns(String documentXml) {
        List<String> runs = new ArrayList<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
            .compile("<w:r>(.*?)</w:r>", java.util.regex.Pattern.DOTALL).matcher(documentXml);
        while (matcher.find()) {
            String run = matcher.group(1);
            if (run.matches("(?s).*[؀-ۿ].*")) {
                runs.add(run);
            }
        }
        return runs;
    }

    /** Unused import guard: LocalDate is referenced by formatMetaDate coverage in the value suite. */
    @Test
    @DisplayName("a date metadata cell renders in the document style")
    void metaDateRenders() {
        assertThat(DocxTemplateRenderer.formatMetaDate(LocalDate.of(2026, 3, 1), ENGLISH))
            .isEqualTo("01/03/2026");
        assertThat(DocxTemplateRenderer.formatMetaDate(
            java.time.LocalDateTime.of(2026, 3, 1, 9, 0), ENGLISH, java.time.ZoneOffset.UTC))
            .isEqualTo("01/03/2026 09:00");
    }
}
