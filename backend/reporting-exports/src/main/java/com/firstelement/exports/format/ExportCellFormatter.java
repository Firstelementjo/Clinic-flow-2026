package com.firstelement.exports.format;

import com.firstelement.exports.catalog.ReportField;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The per-cell rendering context for one export: a locale, a timezone and a style.
 *
 * <h2>Why this is a value rather than three parameters</h2>
 *
 * <p>The trio is passed together to every formatting decision, and getting the pair wrong produces a
 * report that is internally inconsistent in a way nobody notices until it is read: numbers grouped for
 * Arabic and dates rendered in UTC, or a CSV whose header is English because the locale defaulted on one
 * call path and not another. Holding the trio makes "which locale and which zone produced this file" a
 * single answerable question, and the job records it.
 *
 * <h2>The style is chosen once, not per call site</h2>
 *
 * <p>CSV is {@link ExportValueFormatter.Style#MACHINE} and a document is
 * {@link ExportValueFormatter.Style#DOCUMENT}, and this class is where that is decided. Two static
 * factories make the decision visible at every entry point:
 * {@link #forCsv(Locale, ZoneId)} and {@link #forDocument(Locale, ZoneId)}.
 */
public record ExportCellFormatter(Locale locale, ZoneId timezone, ExportValueFormatter.Style style) {

    /** The style for a machine-readable CSV: invariant numbers, ISO dates. */
    public static ExportCellFormatter forCsv(Locale locale, ZoneId timezone) {
        return new ExportCellFormatter(locale == null ? Locale.ENGLISH : locale,
            timezone == null ? ZoneId.of("UTC") : timezone, ExportValueFormatter.Style.MACHINE);
    }

    /** The style for a localized document: locale-grouped numbers, localized dates. */
    public static ExportCellFormatter forDocument(Locale locale, ZoneId timezone) {
        return new ExportCellFormatter(locale == null ? Locale.ENGLISH : locale,
            timezone == null ? ZoneId.of("UTC") : timezone, ExportValueFormatter.Style.DOCUMENT);
    }

    /** One cell, rendered. */
    public String cell(ReportField field, Object value) {
        return ExportValueFormatter.format(field, value, locale, style, timezone);
    }

    /**
     * A whole row, in the plan's column order.
     *
     * <p>Exactly one cell per emitted field, always. A short row would shift every column after the gap,
     * which is the same failure mode as a dropped column and the reason a redacted field is absent from
     * the list rather than rendered as null.
     */
    public List<String> cells(Map<String, Object> values, List<ReportField> fields) {
        List<String> cells = new ArrayList<>(fields.size());
        for (ReportField field : fields) {
            cells.add(cell(field, values.get(field.key())));
        }
        return cells;
    }

    /** A whole row, in the plan's column order, from a positional row. */
    public List<String> cells(com.firstelement.exports.plan.ExportRowStream.Row row,
                              List<ReportField> fields) {
        List<String> cells = new ArrayList<>(fields.size());
        for (int i = 0; i < fields.size(); i++) {
            cells.add(cell(fields.get(i), row.value(i + 1)));
        }
        return cells;
    }

    /** The header row for this formatter's locale. */
    public List<String> header(List<ReportField> fields) {
        return ExportValueFormatter.headerRow(fields, locale);
    }

    /** Whether the locale renders right-to-left. */
    public boolean isRightToLeft() {
        return ReportField.isArabic(locale.toLanguageTag());
    }

    /** The locale tag, recorded on the artefact's metadata. */
    public String localeTag() {
        return locale.toLanguageTag();
    }

    /** The timezone id, recorded on the artefact's metadata. */
    public String zoneId() {
        return timezone.getId();
    }
}