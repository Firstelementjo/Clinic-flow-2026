package com.firstelement.exports.catalog;

import com.firstelement.exports.plan.ExportFilter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Validates the {@code filters} object a report version stores, before it is written.
 *
 * <h2>Why the shape is checked here and not only by the schema</h2>
 *
 * <p>{@code report_versions_filters_shape_check} proves the column is a JSON <em>object</em> and
 * {@code firstelement_json_list_is_string_array} does not apply to it — so a stored filter could in
 * principle be {@code {"anything": {"nested": true}}}. A version like that is not merely unreadable: it
 * is a report whose behaviour depends on a document nothing can execute, which is the shape a query
 * language would have.
 *
 * <p>So each key must be a {@link ReportFilterField}, each value must be a scalar or an array of scalars,
 * and each comparison's arity must be legal. An unknown key is a refusal naming it, which is the whole
 * point of storing keys rather than predicates.
 */
public final class ReportFilterShape {

    private ReportFilterShape() {
    }

    /** The maximum number of declared default filters on one version. */
    public static final int MAX_DECLARED_FILTERS = ReportFilterField.ExportFilterBounds.MAX_FILTERS;

    /**
     * Validates a stored/request filter object.
     *
     * @param filters  the object, or {@code null}/empty for none
     * @param subject  the version's subject; a filter for another subject is refused
     * @throws IllegalArgumentException when a key is unknown, nested, over-long, or the wrong arity
     */
    public static void validate(Map<String, Object> filters, ReportSubject subject) {
        if (filters == null || filters.isEmpty()) {
            return;
        }
        if (filters.size() > MAX_DECLARED_FILTERS) {
            throw new IllegalArgumentException(
                "A report version may declare at most " + MAX_DECLARED_FILTERS + " default filters; "
                    + filters.size() + " were supplied. A long filter list is a query in disguise.");
        }
        for (Map.Entry<String, Object> entry : filters.entrySet()) {
            ReportFilterField field = ReportFilterField.byKey(entry.getKey())
                .orElseThrow(() -> new IllegalArgumentException(
                    "Filter key '" + entry.getKey() + "' is not declared. Declared filters for "
                        + subject.storageName() + " are " + ReportFieldRegistry.filterKeysOf(subject)
                        + "."));
            if (field.subject() != subject) {
                throw new IllegalArgumentException(
                    "Filter '" + field.key() + "' belongs to the " + field.subject() + " subject while "
                        + "the version declares " + subject.storageName());
            }
            // Coercion here is what proves the stored value is a legal scalar for the comparison.
            ExportFilter.fromStored(field, entry.getValue());
        }
    }

    /**
     * Converts a validated filter object into the planner's filter list.
     *
     * <p>Separate from {@link #validate} so a caller that has already validated (the authoring path) does
     * not validate twice, and so the conversion has one implementation.
     */
    public static List<ExportFilter> toFilters(Map<String, Object> filters, ReportSubject subject) {
        validate(filters, subject);
        List<ExportFilter> converted = new ArrayList<>();
        if (filters == null) {
            return converted;
        }
        for (Map.Entry<String, Object> entry : filters.entrySet()) {
            ReportFilterField field = ReportFilterField.byKey(entry.getKey()).orElseThrow();
            converted.add(ExportFilter.fromStored(field, entry.getValue()));
        }
        return List.copyOf(converted);
    }

    /**
     * Reads a client-supplied filter request into the same shape as a stored one.
     *
     * <p>The request is a map of {@code key -> scalar | array}, which is deliberately not a query string:
     * there is no way to express {@code and}, {@code or}, {@code like} or a subquery, because no key
     * resolves to one.
     */
    public static Map<String, Object> normalizeRequest(Map<String, Object> request) {
        if (request == null) {
            return Map.of();
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        request.forEach((key, value) -> {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("A filter key must not be blank");
            }
            normalized.put(key.trim(), value);
        });
        return normalized;
    }
}