package com.firstelement.security.redaction;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Applies a {@link FieldRedactionPolicy} to a row before it leaves the process.
 *
 * <h2>Behaviour that matters for confidentiality</h2>
 *
 * <ul>
 *   <li>A redacted column is replaced with {@link FieldRedactionPolicy#PLACEHOLDER},
 *       never removed and never set to {@code null}. Removing it would let a caller
 *       distinguish "hidden" from "absent in this row", and {@code null} would too.
 *       The value is also identical for every redacted column, so the placeholder
 *       carries no information about the hidden value.</li>
 *   <li>Redaction happens <b>before</b> the row reaches a serializer, an export writer,
 *       or a log statement — which is why this operates on {@code Map}s rather than on
 *       already-serialized payloads.</li>
 *   <li>The set of redacted columns is returned alongside the row so the caller can
 *       record it on an export or a security event.</li>
 * </ul>
 */
public final class FieldRedactor {

    private FieldRedactor() {
    }

    /**
     * @return the row with every column in {@code policy.effectiveRedacted()} replaced
     */
    public static Map<String, Object> redact(Map<String, Object> row, FieldRedactionPolicy policy) {
        if (row == null) {
            return Map.of();
        }
        Set<String> hidden = policy.effectiveRedacted();
        if (hidden.isEmpty()) {
            return Map.copyOf(row);
        }
        Map<String, Object> result = new LinkedHashMap<>(row);
        hidden.forEach(column -> {
            if (result.containsKey(column)) {
                result.put(column, FieldRedactionPolicy.PLACEHOLDER);
            }
        });
        return java.util.Collections.unmodifiableMap(result);
    }

    /**
     * Projects a row down to a column set <em>and</em> redacts, for callers that read
     * with {@code SELECT *} and must not serialise the rest.
     *
     * <p>{@code visibleColumns} is an allow-list: anything not named is dropped.
     */
    public static Map<String, Object> project(Map<String, Object> row,
                                             Set<String> visibleColumns,
                                             FieldRedactionPolicy policy) {
        if (row == null) {
            return Map.of();
        }
        Set<String> hidden = policy.effectiveRedacted();
        Map<String, Object> result = new LinkedHashMap<>();
        for (String column : visibleColumns) {
            if (!hidden.contains(column)) {
                result.put(column, row.get(column));
            }
        }
        return java.util.Collections.unmodifiableMap(result);
    }

    /** The result of redacting, including which columns were hidden. */
    public record RedactedRow(Map<String, Object> row, Set<String> redactedColumns) {
    }

    /** Redacts and reports, for a caller that also writes an audit or export record. */
    public static RedactedRow redactWithReport(Map<String, Object> row, FieldRedactionPolicy policy) {
        Set<String> hidden = policy.effectiveRedacted();
        Set<String> actuallyHidden = new java.util.LinkedHashSet<>();
        if (row != null) {
            row.keySet().forEach(column -> {
                if (hidden.contains(column)) {
                    actuallyHidden.add(column);
                }
            });
        }
        return new RedactedRow(redact(row, policy), java.util.Collections.unmodifiableSet(actuallyHidden));
    }

    /**
     * Redacts a list of rows and reports the union of hidden columns. Used by report
     * and export writers, where the redaction policy is recorded once for the whole
     * output.
     */
    public static List<RedactedRow> redactAll(List<Map<String, Object>> rows,
                                              FieldRedactionPolicy policy) {
        return rows.stream().map(row -> redactWithReport(row, policy)).toList();
    }

    /** Convenience for typed rows: read to a map, redact, write back. */
    public static <T> T redact(T row,
                               Function<T, Map<String, Object>> reader,
                               Function<Map<String, Object>, T> writer,
                               FieldRedactionPolicy policy) {
        return writer.apply(redact(reader.apply(row), policy));
    }
}