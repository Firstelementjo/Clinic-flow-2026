package com.firstelement.exports.catalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Converts between the sort shape a report version <em>stores</em> and the shape the planner <em>reads</em>.
 *
 * <h2>Two shapes, and why they differ</h2>
 *
 * <p>The stored shape is fixed by the migration:
 * {@code report_versions.defaultSort} is a JSON array of
 * {@code {"field": …, "direction": "asc"|"desc"}} objects, checked by
 * {@code firstelement_json_list_is_sort}. That check is worth having — a sort entry with no direction is a
 * suggestion the reader cannot act on, and an object shape makes "which field" and "which way" two
 * required, named things rather than one string a caller has to parse.
 *
 * <p>The planner's shape is {@code "field:asc"}, because {@link com.firstelement.exports.plan.ExportQueryPlanner}
 * parses a single bounded string per sort key and refuses anything else.
 *
 * <p>Converting between them is this class's whole job, and it exists because the two shapes have to live in
 * different places: the storage contract is the migration's and cannot be edited from this module, and the
 * planner's contract is deliberately tiny. Without this class one of them leaks into the other, and the
 * version of the leak that bites is {@code ["customer.displayName:asc"]} being written where the schema
 * expects {@code [{"field": …, "direction": …}]} — a check violation on every publication, which is exactly
 * what running the real Flyway chain in an integration suite exists to catch.
 *
 * <h2>The direction is closed and the field must be declared</h2>
 *
 * <p>{@link #toStored} refuses a direction other than {@code asc}/{@code desc} and a field the registry does
 * not declare. Neither is a value a caller could have reached the SQL with, but both would be stored
 * configuration that resolves to nothing later, which is the state the migration's own comment calls "it
 * looks configured".
 */
public final class ReportSortShape {

    private ReportSortShape() {
    }

    /** The JSON key naming the sorted field. */
    public static final String FIELD = "field";

    /** The JSON key naming the direction. */
    public static final String DIRECTION = "direction";

    /**
     * Converts the planner's {@code "field:asc"} entries into the stored object form.
     *
     * @param entries   the sort keys, each {@code field} or {@code field:asc|desc}
     * @param subject   the version's subject, so an undeclared field names itself in the refusal
     * @throws IllegalArgumentException when an entry has no direction, an unknown direction, or a field the
     *         registry does not declare
     */
    public static List<Map<String, Object>> toStored(List<String> entries, ReportSubject subject) {
        List<Map<String, Object>> stored = new ArrayList<>();
        if (entries == null) {
            return stored;
        }
        for (String entry : entries) {
            // A null entry is refused here rather than allowed to reach ReportDefinitionService's
            // equivalent check with a half-parsed field: both are the same 400, and this one runs first for
            // any caller that uses the shape directly.
            if (entry == null || entry.isBlank()) {
                throw new IllegalArgumentException(
                    "Each 'defaultSort' entry must name a field, but one is "
                        + RefusalText.of(entry) + ". A sort entry with no field is a suggestion the reader "
                        + "cannot act on, which is what report_versions_sort_entries_check refuses.");
            }
            String field = entry;
            String direction = "asc";
            int colon = entry.lastIndexOf(':');
            if (colon > 0) {
                field = entry.substring(0, colon);
                direction = entry.substring(colon + 1).toLowerCase(Locale.ROOT);
            }
            if (!direction.equals("asc") && !direction.equals("desc")) {
                throw new IllegalArgumentException(
                    "Sort direction must be 'asc' or 'desc', got " + RefusalText.of(direction) + " in "
                        + RefusalText.of(entry));
            }
            // Copied into a final so the refusal can be a lambda: the message must name the offending field,
            // and an orElseThrow lambda cannot capture a variable that was assigned twice.
            final String offending = field;
            ReportField declared = ReportField.byKey(offending)
                .orElseThrow(() -> new ReportFieldRegistry.UnknownReportFieldException(offending,
                    "A report version may only sort by a declared field. Declared fields for "
                        + subject.storageName() + " are " + ReportFieldRegistry.fieldKeysOf(subject) + "."));
            if (declared.subject() != subject) {
                throw new IllegalArgumentException(
                    "Sort field '" + offending + "' belongs to the " + declared.subject() + " subject while "
                        + "the version declares " + subject.storageName());
            }
            Map<String, Object> stored0 = new LinkedHashMap<>();
            stored0.put(FIELD, offending);
            stored0.put(DIRECTION, direction);
            stored.add(java.util.Collections.unmodifiableMap(stored0));
        }
        return List.copyOf(stored);
    }

    /**
     * Reads the stored object form back into the planner's {@code "field:asc"} entries.
     *
     * <p>Fails loudly on a malformed entry rather than skipping it: a version whose sort this module cannot
     * read is a report whose order nobody can reproduce, and quietly exporting it unsorted would make the
     * file's row order a claim nothing backs.
     *
     * <p>A {@code null} or empty value is an empty list — the column is nullable and "no declared order" is a
     * legitimate answer.
     */
    public static List<String> fromStored(List<Object> stored) {
        List<String> entries = new ArrayList<>();
        if (stored == null) {
            return entries;
        }
        for (Object candidate : stored) {
            if (!(candidate instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException(
                    "A stored sort entry must be an object of {\"field\":…,\"direction\":…}, got "
                        + (candidate == null ? "null" : candidate.getClass().getSimpleName())
                        + ". The schema's check refuses this shape, so a value like it means a column was "
                        + "written outside the application.");
            }
            Object field = map.get(FIELD);
            Object direction = map.get(DIRECTION);
            if (field == null || String.valueOf(field).isBlank()) {
                throw new IllegalArgumentException(
                    "A stored sort entry names no field. The schema's check refuses this shape, so a value "
                        + "like it means a column was written outside the application.");
            }
            String normalizedDirection = direction == null
                ? "" : String.valueOf(direction).toLowerCase(Locale.ROOT);
            if (!normalizedDirection.equals("asc") && !normalizedDirection.equals("desc")) {
                throw new IllegalArgumentException(
                    "A stored sort entry names direction '" + direction + "', which is neither asc nor "
                        + "desc. The schema's check refuses this shape, so a value like it means a column "
                        + "was written outside the application.");
            }
            entries.add(field + ":" + normalizedDirection);
        }
        return List.copyOf(entries);
    }
}
