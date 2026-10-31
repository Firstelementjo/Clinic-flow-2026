package com.firstelement.exports.catalog;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves report keys into typed fields, and refuses anything the registry does not declare.
 *
 * <h2>The only way a report definition can name something</h2>
 *
 * <p>{@code report_versions."columns"}, {@code "dimensions"} and {@code "measures"} are JSON arrays of
 * strings, CHECKed by the migration to be arrays of non-empty strings. This class is what turns one of
 * those strings into a {@link ReportField}. There is no second resolution path, so a key the registry
 * does not declare cannot be exported by any route, and a key that <em>is</em> declared always
 * resolves to the same expression — which is what makes a re-download of a pinned export version
 * byte-comparable.
 *
 * <h2>Cross-subject contamination is refused, not ignored</h2>
 *
 * <p>A version that mixes {@code conversation.*} and {@code usage.*} keys is a configuration error,
 * not a union: the two subjects read different tables and a statement cannot span both without a join
 * graph, and a join graph in a report definition is the thing
 * {@code V20261005060000} refused to store. So resolution is per subject and a mixed list fails.
 */
public final class ReportFieldRegistry {

    private ReportFieldRegistry() {
    }

    /** A key the registry does not declare. Never a default, never a near-match. */
    public static class UnknownReportFieldException extends IllegalArgumentException {

        private static final long serialVersionUID = 1L;

        private final String key;

        public UnknownReportFieldException(String key, String reason) {
            // RefusalText, not concatenation: `{"columns": ["customer.displayName", null]}` binds a null
            // element, and a message quoting it as 'null' tells the caller they sent the word "null"
            // rather than that they sent an absent entry.
            super("Report column " + RefusalText.of(key)
                + " is not declared by the export field registry. " + reason);
            this.key = key;
        }

        /** The offending key, so a caller can name it in an error body. */
        public String key() {
            return key;
        }
    }

    /** One key that belongs to a different subject than the rest of the list. */
    public static class MixedSubjectException extends IllegalArgumentException {

        private static final long serialVersionUID = 1L;

        MixedSubjectException(String key, ReportSubject declared, ReportSubject found) {
            super("Report column " + RefusalText.of(key) + " belongs to the " + found
                + " subject but the version declares the " + declared + " subject. A version reads one "
                + "declared subject; a join across two would be a query graph, which a report definition "
                + "must not carry.");
        }
    }

    /**
     * Resolves a list of keys, all of which must belong to the same subject.
     *
     * @throws UnknownReportFieldException when a key is not declared
     * @throws MixedSubjectException when two keys name two subjects
     */
    public static List<ReportField> resolve(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            throw new UnknownReportFieldException("<none>",
                "A report version with no columns exports nothing, which is not a report.");
        }
        List<ReportField> resolved = new ArrayList<>(keys.size());
        ReportSubject subject = null;
        Set<String> seen = new LinkedHashSet<>();
        for (String key : keys) {
            ReportField field = ReportField.byKey(key).orElseThrow(() -> new UnknownReportFieldException(
                key, "Declared fields are " + String.join(", ", ReportField.index().keySet()) + "."));
            if (subject == null) {
                subject = field.subject();
            } else if (field.subject() != subject) {
                throw new MixedSubjectException(key, subject, field.subject());
            }
            if (!seen.add(key)) {
                throw new UnknownReportFieldException(key,
                    "The same column appears twice in one version; a duplicated column makes the "
                        + "artefact's column list ambiguous.");
            }
            resolved.add(field);
        }
        return List.copyOf(resolved);
    }

    /**
     * The subject a list of keys reads.
     *
     * <p>Separate from {@link #resolve} because a caller that only wants to know which physical rows an
     * export will touch — the planner, the finance gate — must not need the field list to be valid
     * first.
     */
    public static Optional<ReportSubject> subjectOf(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return Optional.empty();
        }
        ReportSubject subject = null;
        for (String key : keys) {
            ReportField field = ReportField.byKey(key).orElseThrow(() -> new UnknownReportFieldException(
                key, "The subject cannot be determined for an undeclared column."));
            if (subject == null) {
                subject = field.subject();
            } else if (field.subject() != subject) {
                throw new MixedSubjectException(key, subject, field.subject());
            }
        }
        return Optional.ofNullable(subject);
    }

    /**
     * Whether the emitted fields include a money column.
     *
     * <p>Drives the finance period-lock gate and the redaction report: an export with no money column
     * is not a finance export even when its subject is the usage ledger.
     */
    public static boolean carriesMoney(List<ReportField> fields) {
        return fields.stream().anyMatch(ReportField::isMoney);
    }

    /**
     * Whether the emitted fields include the currency column.
     *
     * <p>Checked by the author, not assumed: {@code usage.cost} beside no {@code usage.currency} is an
     * amount with no currency, which ADR 0007 rule 6 calls out as a number nobody can place. The
     * planner refuses such a version rather than rendering the amount bare.
     */
    public static boolean emitsCurrencyColumn(List<ReportField> fields) {
        return fields.stream().anyMatch(field -> field.kind() == ReportField.FieldKind.CURRENCY);
    }

    /**
     * Whether a version aggregates.
     *
     * <p>True when any emitted field is a measure. Decides whether the statement carries a
     * {@code GROUP BY}; getting this wrong in the permissive direction silently de-duplicates rows,
     * so it is a property of the fields rather than of a request flag.
     */
    public static boolean aggregates(List<ReportField> fields) {
        return fields.stream().anyMatch(ReportField::isMeasure);
    }

    /** The dimension fields of a resolved set, in declaration order. */
    public static List<ReportField> dimensionsOf(List<ReportField> fields) {
        return fields.stream().filter(ReportField::isDimension).toList();
    }

    /** The measure fields of a resolved set, in declaration order. */
    public static List<ReportField> measuresOf(List<ReportField> fields) {
        return fields.stream().filter(ReportField::isMeasure).toList();
    }

    /** Every filter key declared for a subject, for the authoring surface's catalogue. */
    public static List<String> filterKeysOf(ReportSubject subject) {
        return ReportFilterField.ofSubject(subject).stream().map(ReportFilterField::key).toList();
    }

    /** Every field key declared for a subject, for the authoring surface's catalogue. */
    public static List<String> fieldKeysOf(ReportSubject subject) {
        return ReportField.ofSubject(subject).stream().map(ReportField::key).toList();
    }
}