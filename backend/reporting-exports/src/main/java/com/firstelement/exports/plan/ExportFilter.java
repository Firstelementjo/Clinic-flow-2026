package com.firstelement.exports.plan;

import com.firstelement.exports.catalog.ReportFilterField;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * One requested filter: a declared key and its bound values.
 *
 * <p>The whole filter surface of this module is this record plus {@link ReportFilterField}. There is no
 * {@code operator}, no {@code expression}, and no {@code raw} flag, because each of those would be a
 * place where a caller-supplied string reaches SQL text.
 *
 * @param field   the declared comparison. Its key names its own operator.
 * @param values  the bound values. Length is validated against {@link ReportFilterField#arityLimit()}.
 */
public record ExportFilter(ReportFilterField field, List<Object> values) {

    public ExportFilter {
        if (field == null) {
            throw new IllegalArgumentException("A filter must name a declared comparison");
        }
        List<Object> supplied = values == null ? List.of() : new ArrayList<>(values);
        // Validated BEFORE the defensive copy, because List.copyOf rejects a null element with a
        // NullPointerException — which would turn "this filter can never match" into an NPE with no mention
        // of the filter key, in the one case where naming the key matters most.
        for (Object value : supplied) {
            if (value == null) {
                throw new FilterNotPushableException(field.key(),
                    "a null filter value would compile to '= null', which is never true in SQL and would "
                        + "silently return no rows instead of the error this is");
            }
        }
        if (supplied.size() > field.arityLimit()) {
            throw new FilterNotPushableException(field.key(),
                "the comparison takes at most " + field.arityLimit() + " value(s) and "
                    + supplied.size() + " were supplied");
        }
        if (field.comparison() == ReportFilterField.Comparison.IS_NOT_NULL && !supplied.isEmpty()) {
            throw new FilterNotPushableException(field.key(),
                "the comparison is a null test and takes no value");
        }
        values = List.copyOf(supplied);
    }

    /** A single-value filter. */
    public static ExportFilter of(ReportFilterField field, Object value) {
        return new ExportFilter(field, List.of(value));
    }

    /** A membership filter. */
    public static ExportFilter of(ReportFilterField field, List<Object> values) {
        return new ExportFilter(field, values);
    }

    /** A null test, which carries no value. */
    public static ExportFilter isNotNull(ReportFilterField field) {
        return new ExportFilter(field, List.of());
    }

    /**
     * Reads a filter out of the jsonb object a report version stores in {@code "filters"}.
     *
     * <p>The stored shape is {@code {"<key>": ["v1","v2"]}} or {@code {"<key>": "v"}} or, for a null
     * test, {@code {"<key>": true}}. Anything else — a nested object, a number where a string is
     * declared, a key the registry does not know — is a refusal, because a shape this method cannot
     * read is a shape that would otherwise be ignored.
     */
    @SuppressWarnings("unchecked")
    public static ExportFilter fromStored(ReportFilterField field, Object stored) {
        if (field.comparison() == ReportFilterField.Comparison.IS_NOT_NULL) {
            if (Boolean.TRUE.equals(stored)) {
                return isNotNull(field);
            }
            throw new FilterNotPushableException(field.key(),
                "the null-test filter must be stored as the boolean true, got: " + describe(stored));
        }
        if (stored instanceof List<?> list) {
            List<Object> values = new ArrayList<>(list.size());
            for (Object value : list) {
                values.add(coerce(field, value));
            }
            return new ExportFilter(field, values);
        }
        if (stored instanceof Map<?, ?>) {
            throw new FilterNotPushableException(field.key(),
                "a filter must be a scalar or an array of scalars, not an object; an object here is "
                    + "the shape a query language would have");
        }
        return new ExportFilter(field, List.of(coerce(field, stored)));
    }

    /**
     * Coerces a stored/request value into the Java type the driver binds for this comparison.
     *
     * <p>Typed rather than passed through so a {@code :date} filter binds a {@code LocalDate} and not a
     * string the driver would have to guess at, and so a value that cannot be the declared type is a
     * refusal instead of a surprising comparison.
     */
    private static Object coerce(ReportFilterField field, Object value) {
        if (value == null) {
            throw new FilterNotPushableException(field.key(), "a filter value may not be null");
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            throw new FilterNotPushableException(field.key(), "a filter value may not be blank");
        }
        if (text.length() > ReportFilterField.ExportFilterBounds.MAX_VALUE_LENGTH) {
            throw new FilterNotPushableException(field.key(),
                "a filter value may be at most " + ReportFilterField.ExportFilterBounds.MAX_VALUE_LENGTH
                    + " characters; a filter is a bound parameter, not a document");
        }
        return switch (field.comparison()) {
            case EQ, IN -> {
                String upper = text.toUpperCase(Locale.ROOT);
                if (upper.equals("TRUE") || upper.equals("FALSE")) {
                    yield Boolean.parseBoolean(upper);
                }
                yield text;
            }
            case GTE, LTE -> {
                // A uuid comparison must bind a uuid, not a 36-character string, or the failure is a
                // type error from the driver rather than a refusal naming the key.
                if (field.key().endsWith("Id") || field.key().endsWith("customerId")) {
                    yield parseUuid(field.key(), text);
                }
                // A date bound must bind a date for the same reason. Bound as a String, PostgreSQL has no
                // `date <= character varying` operator and the export fails at run time with a type
                // error naming a column rather than the filter that was wrong. Every range filter in this
                // registry is a date (`usage.bucketFrom`, `usage.bucketTo`), so the comparison decides it.
                if (field.comparison() != ReportFilterField.Comparison.EQ) {
                    yield parseDate(field.key(), text);
                }
                yield text;
            }
            case IS_NOT_NULL -> null;
        };
    }

    private static UUID parseUuid(String key, String text) {
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            throw new FilterNotPushableException(key,
                "the value must be a uuid, got '" + text + "'");
        }
    }

    /** An ISO date, refused with the offending key rather than bound as a string. */
    private static LocalDate parseDate(String key, String text) {
        try {
            return LocalDate.parse(text);
        } catch (java.time.format.DateTimeParseException e) {
            throw new FilterNotPushableException(key,
                "the value must be an ISO date (yyyy-MM-dd), got '" + text + "'");
        }
    }

    private static String describe(Object value) {
        return value == null ? "null" : value.getClass().getSimpleName();
    }

    /** The stored jsonb form of this filter, so a re-run is byte-comparable. */
    public Object toStored() {
        if (field.comparison() == ReportFilterField.Comparison.IS_NOT_NULL) {
            return Boolean.TRUE;
        }
        return field.comparison() == ReportFilterField.Comparison.IN ? values : values.get(0);
    }

    /** The map form written to {@code export_jobs."filters"}. */
    public Map<String, Object> toStoredMap() {
        return Map.of(field.key(), toStored());
    }

    /** Every filter's bound values, in the order the planner emitted their placeholders. */
    public List<Object> parametersInOrder() {
        return values;
    }

    /** Filters are bounded: a statement with 400 predicates is a query language in disguise. */
    public static void assertWithinBudget(List<ExportFilter> filters) {
        if (filters != null && filters.size() > ReportFilterField.ExportFilterBounds.MAX_FILTERS) {
            throw new FilterNotPushableException("<all>",
                "an export may carry at most " + ReportFilterField.ExportFilterBounds.MAX_FILTERS
                    + " filters and " + filters.size() + " were requested");
        }
    }

    /** The declared filter budget, exposed so a test can assert the constant is meaningful. */
    public static int filterBudget() {
        return ReportFilterField.ExportFilterBounds.MAX_FILTERS;
    }

    /**
     * Merges declared defaults and request filters, request last so it wins on a duplicate key.
     *
     * <p>The budget is checked on each incoming list <em>and</em> on the merged result, not only on the
     * result. Deduplication is by key, so a caller sending five thousand copies of the same comparison
     * would otherwise collapse to one and never meet a budget — which is the wrong reason to accept it,
     * because the refusal exists to bound the work of assembling a statement, and a five-thousand-element
     * request is the assembly work even when the answer is small.
     */
    public static List<ExportFilter> merge(List<ExportFilter> declared, List<ExportFilter> requested) {
        assertWithinBudget(declared);
        assertWithinBudget(requested);
        Map<String, ExportFilter> merged = new java.util.LinkedHashMap<>();
        if (declared != null) {
            for (ExportFilter filter : declared) {
                merged.put(filter.field().key(), filter);
            }
        }
        if (requested != null) {
            for (ExportFilter filter : requested) {
                merged.put(filter.field().key(), filter);
            }
        }
        List<ExportFilter> result = Collections.unmodifiableList(new ArrayList<>(merged.values()));
        assertWithinBudget(result);
        return result;
    }
}