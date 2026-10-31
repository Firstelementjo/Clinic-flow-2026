package com.firstelement.exports.catalog;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The authoring surface's view of one declared field: everything needed to render a column picker.
 *
 * <h2>Why this is a projection and not the field itself</h2>
 *
 * <p>The picker needs the label in both languages, the kind, and the permission — because "this column
 * needs {@code analytics:read-organization}" is the single most useful thing an operator can be told when
 * an export they expected does not contain a figure. It must NOT need the SQL expression: exposing
 * {@link ReportField#expression()} to a client is how "a declared field" becomes "a column name a caller
 * can choose", and this module's whole boundary is that the caller chooses a <em>key</em>.
 *
 * <p>The expression is therefore absent here, and its absence is deliberate rather than an oversight.
 */
public final class ExportValueFormatterMetadata {

    private ExportValueFormatterMetadata() {
    }

    /** The client-visible metadata for one field. Never carries the SQL expression. */
    public static Map<String, String> of(ReportField field) {
        Map<String, String> entry = new LinkedHashMap<>();
        entry.put("key", field.key());
        entry.put("subject", field.subject().storageName());
        entry.put("role", field.role().name());
        entry.put("kind", field.kind().name());
        entry.put("labelAr", field.labelAr());
        entry.put("labelEn", field.labelEn());
        entry.put("requiredPermission", field.requiredPermission().value());
        entry.put("sensitive", String.valueOf(field.isSensitive()));
        return Collections.unmodifiableMap(entry);
    }

    /** The same, for a filter, for the filter editor. */
    public static Map<String, String> of(ReportFilterField filter) {
        Map<String, String> entry = new LinkedHashMap<>();
        entry.put("key", filter.key());
        entry.put("subject", filter.subject().storageName());
        entry.put("comparison", filter.comparison().name());
        entry.put("maxValues", String.valueOf(filter.arityLimit()));
        entry.put("labelAr", filter.labelAr());
        entry.put("labelEn", filter.labelEn());
        return Collections.unmodifiableMap(entry);
    }
}