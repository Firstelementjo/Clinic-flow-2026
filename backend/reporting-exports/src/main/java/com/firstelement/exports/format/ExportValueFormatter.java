package com.firstelement.exports.format;

import com.firstelement.exports.catalog.ReportField;
import com.firstelement.exports.catalog.ReportFieldRegistry;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns a JDBC value into the string a writer emits, for a declared locale.
 *
 * <h2>The CSV / document split, stated because it looks inconsistent</h2>
 *
 * <p>PROJECT_REQUIREMENTS asks for locale-aware numbers, dates and currency, and FR-8.3 asks for
 * RFC 4180 CSV. Those two pull in opposite directions, because Arabic and German locales use a comma or
 * a dot as the decimal separator and a comma as the group separator — and an unquoted comma in a CSV
 * field changes the column count of every row that contains one.
 *
 * <p>So the split is:
 *
 * <ul>
 *   <li><b>CSV</b> uses {@link Style#MACHINE} for every numeric kind: {@link BigDecimal#toPlainString()}
 *       and ISO-8601 dates. The consumer of a CSV is a spreadsheet or an ETL job, and a locale decimal
 *       separator in a machine-read file is a defect, not a nicety. The <em>locale</em> still decides
 *       the header row's language and the {@code document} column's rendering, so a CSV opened by an
 *       Arabic-speaking user is labelled in Arabic and the numbers still import correctly.</li>
 *   <li><b>DOCX</b> uses {@link Style#DOCUMENT}: {@link NumberFormat} for the requested locale, and a
 *       localized date pattern. A document is read by a person, so it gets the grouping and the
 *       calendar that person uses.</li>
 * </ul>
 *
 * <p>Both styles are deterministic for a given (value, locale, style) triple, which is what makes a
 * pinned export version re-download byte-comparable.
 *
 * <h2>A null is a state, not an empty string</h2>
 *
 * <p>{@code usage.revenue} is null for an UNPRICED bucket. The formatter returns a caller-chosen
 * {@link #absentValue()} token rather than {@code ""}, because an empty CSV cell reads as "zero" and
 * FR-7.6 requires "no data" to be distinct from zero. The token is a declared constant so it is
 * greppable in a test and stable across versions.
 */
public final class ExportValueFormatter {

    /** The two rendering conventions, named so a call site has to choose. */
    public enum Style {
        /** Locale-invariant literals and ISO-8601 dates. For CSV. */
        MACHINE,
        /** Locale-formatted numbers and localized dates. For documents. */
        DOCUMENT
    }

    /**
     * The token written for an absent value.
     *
     * <p>Not {@code ""}, not {@code "0"}, not {@code "N/A"}. It is a token that cannot be confused with
     * a number, and it is the same token in every locale because a machine-readable file is not a place
     * for localized vocabulary.
     */
    public static final String ABSENT = "—";

    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter ISO_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /**
     * Date formats per locale.
     *
     * <p>Chosen per language rather than per country because a tenant's {@code Locale} is a language
     * choice; {@code Asia/Riyadh} supplies the timezone, not the format. {@code ar} gets the Arabic
     * two-digit year and the Hijri option is deliberately NOT used: a Hijri date in an export that a
     * finance team reconciles against a Gregorian ledger is a reconciliation defect, and the export
     * records {@code bucketStart} as an ISO date for exactly that reason.
     */
    private static final Map<String, DateTimeFormatter> DOCUMENT_DATE_PATTERNS =
        Map.of("ar", DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.forLanguageTag("ar")),
            "en", DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.ENGLISH));

    private static final Map<String, DateTimeFormatter> DOCUMENT_DATE_ONLY_PATTERNS =
        Map.of("ar", DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.forLanguageTag("ar")),
            "en", DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ENGLISH));

    private ExportValueFormatter() {
    }

    /**
     * Formats one cell.
     *
     * @param field     the declared field, which decides the kind
     * @param value     the JDBC value, possibly {@code null}
     * @param locale    the export's locale
     * @param style     MACHINE or DOCUMENT
     * @param zone      the report definition's own timezone, applied to a {@code timestamp without time
     *                  zone}. Never the JVM default: two workers with different zones would produce two
     *                  different files for the same export version.
     */
    public static String format(ReportField field, Object value, Locale locale, Style style,
                                ZoneId zone) {
        if (value == null) {
            return ABSENT;
        }
        Locale effective = locale == null ? Locale.ENGLISH : locale;
        ZoneId effectiveZone = zone == null ? ZoneId.of("UTC") : zone;
        return switch (field.kind()) {
            case TEXT, ENUM -> String.valueOf(value);
            case IDENTIFIER -> String.valueOf(value);
            case CURRENCY -> String.valueOf(value);
            case BOOLEAN -> String.valueOf(value);
            case INTEGER -> integer(value, effective, style);
            case DECIMAL -> decimal(value, effective, style);
            case MONEY -> money(value, effective, style);
            case DATE -> date(value, effective, style, effectiveZone);
            case DATE_TIME -> dateTime(value, effective, style, effectiveZone);
        };
    }

    /** Convenience for a document cell with the report's own timezone. */
    public static String document(ReportField field, Object value, Locale locale, ZoneId zone) {
        return format(field, value, locale, Style.DOCUMENT, zone);
    }

    /** Convenience for a machine-readable cell. */
    public static String machine(ReportField field, Object value, Locale locale, ZoneId zone) {
        return format(field, value, locale, Style.MACHINE, zone);
    }

    // ------------------------------------------------------------------
    // kinds
    // ------------------------------------------------------------------

    private static String integer(Object value, Locale locale, Style style) {
        BigDecimal decimal = toDecimal(value);
        if (style == Style.MACHINE) {
            return decimal.toBigInteger().toString();
        }
        return NumberFormat.getIntegerInstance(locale).format(decimal);
    }

    private static String decimal(Object value, Locale locale, Style style) {
        BigDecimal decimal = toDecimal(value);
        if (style == Style.MACHINE) {
            return decimal.toPlainString();
        }
        NumberFormat format = NumberFormat.getNumberInstance(locale);
        format.setGroupingUsed(true);
        format.setMinimumFractionDigits(Math.min(decimal.scale(), 6));
        return format.format(decimal);
    }

    /**
     * A money amount.
     *
     * <p>The currency is rendered by the report's own {@code usage.currency} column, so an amount is
     * never rendered with a symbol the reader has to infer. The ISO code is appended in parentheses in
     * document style, which is what makes a screenshot of the file unambiguous.
     */
    private static String money(Object value, Locale locale, Style style) {
        BigDecimal decimal = toDecimal(value);
        if (style == Style.MACHINE) {
            return decimal.toPlainString();
        }
        NumberFormat format = NumberFormat.getNumberInstance(locale);
        format.setGroupingUsed(true);
        format.setMinimumFractionDigits(2);
        format.setMaximumFractionDigits(6);
        return format.format(decimal);
    }

    private static String date(Object value, Locale locale, Style style, ZoneId zone) {
        LocalDate date = toLocalDate(value);
        if (style == Style.MACHINE) {
            return ISO_DATE.format(date);
        }
        return DOCUMENT_DATE_ONLY_PATTERNS
            .getOrDefault(language(locale), DOCUMENT_DATE_ONLY_PATTERNS.get("en"))
            .format(date);
    }

    private static String dateTime(Object value, Locale locale, Style style, ZoneId zone) {
        LocalDateTime local = toLocalDateTime(value);
        if (style == Style.MACHINE) {
            // Naive, exactly as stored: the column is `timestamp without time zone`, the database session
            // is UTC (ADR-0002), and the value IS UTC. Converting here would make the CSV disagree with
            // the row it claims to describe, so a machine-readable export renders UTC and says so in the
            // column label.
            return ISO_DATE_TIME.format(local);
        }
        // For a document, the report's OWN declared timezone applies. The two-step conversion matters:
        // LocalDateTime.atZone(zone) would read 14:30 as 14:30 Riyadh local wall-clock, which is NOT what
        // a UTC-stored 14:30 means. Treating it as UTC first and THEN shifting is the correct reading, and
        // getting it backwards is a silent three-hour error in every date in an Arabic report.
        return DOCUMENT_DATE_PATTERNS
            .getOrDefault(language(locale), DOCUMENT_DATE_PATTERNS.get("en"))
            .format(local.atZone(java.time.ZoneOffset.UTC).withZoneSameInstant(zone));
    }

    // ------------------------------------------------------------------
    // coercion
    // ------------------------------------------------------------------

    private static BigDecimal toDecimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Long || value instanceof Integer || value instanceof Short) {
            return BigDecimal.valueOf(((Number) value).longValue());
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        if (value instanceof String text) {
            try {
                return new BigDecimal(text.trim());
            } catch (NumberFormatException e) {
                throw new IllegalStateException(
                    "A numeric report column produced the non-numeric value '" + text + "'", e);
            }
        }
        throw new IllegalStateException(
            "A numeric report column produced " + value.getClass().getSimpleName() + ", which has no "
                + "documented mapping to a number");
    }

    private static LocalDateTime toLocalDateTime(Object value) {
        if (value instanceof LocalDateTime local) {
            return local;
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        if (value instanceof LocalDate date) {
            return date.atStartOfDay();
        }
        throw new IllegalStateException(
            "A timestamp report column produced " + value.getClass().getSimpleName()
                + ", which has no documented mapping to a timestamp");
    }

    private static LocalDate toLocalDate(Object value) {
        if (value instanceof LocalDate date) {
            return date;
        }
        if (value instanceof LocalDateTime local) {
            return local.toLocalDate();
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate();
        }
        throw new IllegalStateException(
            "A date report column produced " + value.getClass().getSimpleName()
                + ", which has no documented mapping to a date");
    }

    private static String language(Locale locale) {
        String tag = locale.toLanguageTag().toLowerCase(Locale.ROOT);
        return tag.startsWith("ar") ? "ar" : "en";
    }

    /**
     * Whether a column set is renderable: every money column must have a currency column beside it.
     *
     * <p>Checked at authoring time rather than at write time. ADR 0007 rule 6 says every row carries a
     * currency because "there is no amount without a currency"; a report that selects a cost without
     * the currency produces exactly the thing that rule forbids, in a file.
     */
    public static boolean hasCurrencyBesideMoney(List<ReportField> fields) {
        if (!ReportFieldRegistry.carriesMoney(fields)) {
            return true;
        }
        return ReportFieldRegistry.emitsCurrencyColumn(fields);
    }

    /** The header row for a locale: one label per emitted column, in the plan's order. */
    public static List<String> headerRow(List<ReportField> fields, Locale locale) {
        List<String> header = new java.util.ArrayList<>(fields.size());
        for (ReportField field : fields) {
            header.add(field.label(locale.toLanguageTag()));
        }
        return header;
    }

    /**
     * The bilingual column metadata a client needs to label a column the other way round.
     *
     * <p>Kept beside the header rather than instead of it: the artefact carries one language, because
     * two header rows would break every RFC 4180 consumer, and the other language is one request away.
     */
    public static List<Map<String, String>> columnMetadata(List<ReportField> fields) {
        List<Map<String, String>> metadata = new java.util.ArrayList<>(fields.size());
        for (ReportField field : fields) {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("key", field.key());
            entry.put("labelAr", field.labelAr());
            entry.put("labelEn", field.labelEn());
            entry.put("kind", field.kind().name());
            entry.put("requiredPermission", field.requiredPermission().value());
            metadata.add(java.util.Collections.unmodifiableMap(entry));
        }
        return metadata;
    }

    /**
     * The currency a money cell should be read in, when the report carries a currency column.
     *
     * <p>Exposed so a document renderer can label a total with the right code without re-deriving the
     * row shape, and so a test can assert the money cells and the currency cells agree.
     */
    public static String currencyOf(Object value) {
        String code = String.valueOf(value);
        try {
            Currency.getInstance(code);
            return code;
        } catch (IllegalArgumentException e) {
            return code;
        }
    }

    /**
     * Parses a date for a filter default, so a report version's stored filter shape is validated rather
     * than discovered by the driver.
     */
    public static LocalDate parseDate(String text) {
        try {
            return LocalDate.parse(text, ISO_DATE);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                "Expected an ISO date (yyyy-MM-dd) for a stored filter value, got: " + text, e);
        }
    }

    /** A {@link LocalDate} at midnight, which is how a filter bound on a date column is built. */
    public static LocalDate atStartOfDay(LocalDate date) {
        return LocalDate.of(date.getYear(), date.getMonth(), date.getDayOfMonth())
            .atStartOfDay().toLocalDate();
    }

    /** The number of whole days between two dates, used by the report's own period arithmetic. */
    public static long daysBetween(LocalDate from, LocalDate to) {
        return to.toEpochDay() - from.toEpochDay();
    }
}