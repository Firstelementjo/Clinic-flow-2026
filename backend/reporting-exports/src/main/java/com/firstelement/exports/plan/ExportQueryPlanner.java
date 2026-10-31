package com.firstelement.exports.plan;

import com.firstelement.exports.catalog.ReportField;
import com.firstelement.exports.catalog.ReportFieldRegistry;
import com.firstelement.exports.catalog.ReportFilterField;
import com.firstelement.exports.catalog.ReportSubject;
import com.firstelement.exports.redaction.ResolvedExportColumns;
import com.firstelement.security.query.ScopedWhere;
import com.firstelement.security.query.TenantScope;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Assembles the statement an export runs, with authorization already inside it.
 *
 * <h2>The property this class exists to guarantee</h2>
 *
 * <p>FR-8.2 / ADR-0007 §2: tenant, row and field authorization is applied <strong>before</strong>
 * aggregation, never after. Three mechanisms make that true of the produced SQL rather than of the
 * process around it:
 *
 * <ol>
 *   <li><b>The tenant predicate is written by {@link ScopedWhere}, not here.</b> The planner cannot
 *       forget it, cannot place it after the {@code GROUP BY}, and cannot override it: the tenant
 *       parameters are bound <em>last</em> into the parameter source, so a caller-supplied map with a
 *       colliding name is refused outright.</li>
 *   <li><b>Field authorization happens before the select list exists.</b> The planner receives a
 *       {@link ResolvedExportColumns} whose withheld fields have already been dropped, so an
 *       unauthorized expression is not merely unused — it was never in the statement.</li>
 *   <li><b>Every filter is pushed.</b> A filter that cannot be pushed raises
 *       {@link FilterNotPushableException} and no statement is produced.</li>
 * </ol>
 *
 * <h2>The only SQL in the class is concatenation of compile-time constants</h2>
 *
 * <p>{@link ReportSubject#fromBody()}, {@link ReportFilterField#predicate(int)} and
 * {@link ReportField#expression()} are all constants. The one dynamic part is the number of
 * {@code ?} placeholders in an {@code IN} list, which comes from an {@code int}. No method accepts a
 * table name, a column name, a predicate fragment or a sort expression, so there is no parameter a
 * caller could use to reach the statement text.
 *
 * <h2>Ordering and grouping</h2>
 *
 * <p>{@code GROUP BY} uses <b>output ordinals</b> ({@code GROUP BY 1, 2}) rather than repeating the
 * expressions. That matters for the finance subject, where a measure expression is a
 * {@code case when sum(case …) > 0 then null else sum … end}: repeating it in the {@code GROUP BY}
 * would be legal PostgreSQL but a place where the aggregation could be re-derived differently from
 * the select list. An ordinal names the same expression by construction.
 *
 * <p>{@code ORDER BY} likewise uses ordinals, and only over emitted columns — so a sort can never
 * reference a withheld field, and the file's row order always corresponds to columns a reader can
 * actually see.
 */
@Component
public class ExportQueryPlanner {

    /**
     * Assembles the statement.
     *
     * @param scope      the already-authorized tenant/project scope from {@code TenantQueryGuard}
     * @param columns    the redacted, emitted column set. A withheld field is not present here.
     * @param declaredFilters the report version's own default filters, read from
     *                       {@code report_versions."filters"} and resolved through the registry
     * @param requestedFilters the caller's filters; a duplicate key replaces the declared default
     * @param sortKeys   requested sort keys, each of which must name an emitted column
     * @param lockedFinancePeriodKeys the LOCKED finance periods intersecting the window, for the record
     */
    public ExportQueryPlan plan(TenantScope scope,
                                ResolvedExportColumns columns,
                                List<ExportFilter> declaredFilters,
                                List<ExportFilter> requestedFilters,
                                List<String> sortKeys,
                                List<String> lockedFinancePeriodKeys) {
        if (scope == null) {
            throw new IllegalStateException(
                "An export plan requires a TenantScope from TenantQueryGuard. There is no path that "
                    + "builds an export statement without one.");
        }
        if (columns == null || columns.emitted().isEmpty()) {
            throw new IllegalArgumentException(
                "An export with no emitted columns would write an empty header row, which a reader "
                    + "cannot tell apart from a genuinely empty report. The export is refused; "
                    + "resolved=" + (columns == null ? "null" : columns.withheldKeys()));
        }
        List<ReportField> fields = columns.emitted();
        ReportSubject subject = fields.get(0).subject();

        List<ExportFilter> applied = ExportFilter.merge(declaredFilters, requestedFilters);
        validateSubjectAgreement(applied, subject);

        StringBuilder select = new StringBuilder("select ");
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                select.append(", ");
            }
            // The expression is an enum constant. Nothing else can reach this string.
            select.append(fields.get(i).expression());
            select.append(" as fe_col_").append(i + 1);
        }

        boolean aggregates = ReportFieldRegistry.aggregates(fields);
        // A measure with no grouping key aggregates the whole authorized set into one row, which is a
        // legitimate "how many" report. The clause is emitted in both cases so the statement reads the same
        // way regardless of whether it aggregates.
        //
        // The ordinals are the POSITIONS OF THE DIMENSION COLUMNS, not the first N positions. A version
        // may interleave dimensions and measures (usage.dimension, usage.eventCount, usage.currency), and
        // grouping by 1..N would then group by an aggregate — which PostgreSQL refuses at run time, so it
        // would surface as a failed job rather than as a refused authoring call. Each ordinal names its
        // expression by construction, so the GROUP BY cannot re-derive one differently from the select list.
        String dimensionOrdinals = aggregates ? dimensionOrdinals(fields) : "";
        // A version with measures and no dimension aggregates the whole authorized set into ONE row, which
        // needs no GROUP BY at all. Emitting "group by " with nothing after it is a syntax error, so the
        // clause is omitted in exactly that case rather than being emitted empty.
        String groupBy = dimensionOrdinals.isEmpty() ? "" : " group by " + dimensionOrdinals;

        String additional = buildAdditionalPredicate(subject, applied);
        // ScopedWhere.positional appends: "... where <tenant> and <project> and (<additional>)" and
        // returns tenant-first parameters. The tenant predicate therefore lands in the same statement
        // that computes the aggregate, ahead of every filter.
        ScopedWhere.ScopedSql scoped = ScopedWhere.of(scope)
            .positional(select + " " + subject.fromBody(), subject.alias(), additional,
                additionalParameters(applied));

        String orderBy = buildOrderBy(fields, sortKeys, aggregates);
        String sql = scoped.sql() + groupBy + orderBy;

        return new ExportQueryPlan(sql, java.util.Arrays.asList(scoped.parameters()), fields, subject,
            resolveSortFields(fields, sortKeys), lockedFinancePeriodKeys, applied);
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    /**
     * A filter for a different subject than the one the columns name.
     *
     * <p>Refused rather than dropped: a report that silently ignores a {@code conversation.status}
     * filter because its columns are {@code usage.*} is a report that answers a question nobody asked.
     */
    private static void validateSubjectAgreement(List<ExportFilter> filters, ReportSubject subject) {
        if (filters == null) {
            return;
        }
        for (ExportFilter filter : filters) {
            if (filter.field().subject() != subject) {
                throw new FilterNotPushableException(filter.field().key(),
                    "it filters the " + filter.field().subject() + " subject while the report's columns "
                        + "are from the " + subject + " subject");
            }
        }
    }

    /** The version's declared default filters are merged by the caller, from the version's own jsonb. */

    /**
     * The subject's intrinsic predicate, AND every pushed filter, in one parenthesised group.
     *
     * <p>The intrinsic predicate comes first for {@link ReportSubject#USAGE_LEDGER} because "this is the
     * current generation" is a statement about which rows exist rather than about the caller's
     * question. Both sit behind the tenant predicate, which {@link ScopedWhere} has already written.
     */
    private static String buildAdditionalPredicate(ReportSubject subject, List<ExportFilter> filters) {
        StringBuilder sql = new StringBuilder();
        if (subject.intrinsicPredicate() != null) {
            sql.append(subject.intrinsicPredicate());
        }
        for (ExportFilter filter : filters) {
            if (sql.length() > 0) {
                sql.append(" and ");
            }
            sql.append(filter.field().predicate(filter.values().size()));
        }
        return sql.length() == 0 ? null : sql.toString();
    }

    /** The bound values for the pushed filters, in the order their placeholders were emitted. */
    private static Object[] additionalParameters(List<ExportFilter> filters) {
        List<Object> parameters = new ArrayList<>();
        for (ExportFilter filter : filters) {
            parameters.addAll(filter.parametersInOrder());
        }
        return parameters.toArray();
    }

    /**
     * The output ordinals of the dimension columns, comma separated.
     *
     * <p>Empty when the version declares no dimension, which means "aggregate the whole authorized set into
     * one row" — a legitimate "how many customers" report with no {@code GROUP BY} clause at all.
     */
    private static String dimensionOrdinals(List<ReportField> fields) {
        StringBuilder sql = new StringBuilder();
        for (int i = 0; i < fields.size(); i++) {
            if (!fields.get(i).isDimension()) {
                continue;
            }
            if (sql.length() > 0) {
                sql.append(", ");
            }
            sql.append(i + 1);
        }
        return sql.toString();
    }

    /**
     * The {@code ORDER BY}, over emitted columns only.
     *
     * <p>A sort key that is not an emitted column is refused. That covers two cases with one rule: a
     * sort on a column the report does not declare, and a sort on a column the <em>caller</em> may not
     * see — the second being the interesting one, because sorting by a withheld field is an ordering
     * oracle over data the caller is not entitled to.
     */
    private static String buildOrderBy(List<ReportField> fields, List<String> sortKeys,
                                       boolean aggregates) {
        if (sortKeys == null || sortKeys.isEmpty()) {
            return "";
        }
        Set<String> emittedKeys = new LinkedHashSet<>();
        for (ReportField field : fields) {
            emittedKeys.add(field.key());
        }
        List<String> orderTerms = new ArrayList<>(sortKeys.size());
        for (String sortKey : sortKeys) {
            String fieldKey = sortKey;
            String direction = "asc";
            int colon = sortKey.lastIndexOf(':');
            if (colon > 0) {
                fieldKey = sortKey.substring(0, colon);
                String requested = sortKey.substring(colon + 1).toLowerCase(java.util.Locale.ROOT);
                if (!requested.equals("asc") && !requested.equals("desc")) {
                    throw new SortNotProjectableException(fieldKey,
                        "direction must be 'asc' or 'desc', got '" + requested + "'");
                }
                direction = requested;
            }
            final String resolvedKey = fieldKey;
            ReportField field = com.firstelement.exports.catalog.ReportField.byKey(resolvedKey)
                .orElseThrow(() -> new SortNotProjectableException(resolvedKey,
                    "no such report column is declared"));
            if (!emittedKeys.contains(fieldKey)) {
                throw new SortNotProjectableException(fieldKey,
                    "the column is not part of this export's emitted set, so it is either not "
                        + "declared by the report or was withheld from this caller");
            }
            int ordinal = ordinalOf(fields, fieldKey);
            if (aggregates && field.isMeasure()) {
                throw new SortNotProjectableException(fieldKey,
                    "an aggregate column cannot be the primary sort of an aggregating report; sort by "
                        + "a grouping column instead");
            }
            orderTerms.add(ordinal + " " + direction);
        }
        return " order by " + String.join(", ", orderTerms);
    }

    private static List<ReportField> resolveSortFields(List<ReportField> fields, List<String> sortKeys) {
        List<ReportField> resolved = new ArrayList<>();
        if (sortKeys == null) {
            return resolved;
        }
        for (String sortKey : sortKeys) {
            String fieldKey = sortKey;
            int colon = sortKey.lastIndexOf(':');
            if (colon > 0) {
                fieldKey = sortKey.substring(0, colon);
            }
            com.firstelement.exports.catalog.ReportField.byKey(fieldKey)
                .ifPresent(resolved::add);
        }
        return resolved;
    }

    private static int ordinalOf(List<ReportField> fields, String key) {
        for (int i = 0; i < fields.size(); i++) {
            if (fields.get(i).key().equals(key)) {
                return i + 1;
            }
        }
        throw new IllegalStateException("Field " + key + " is in the emitted set but was not found; "
            + "the plan and the column set disagree");
    }

    /**
     * A sort the plan cannot honour.
     *
     * <p>Separate from {@link FilterNotPushableException} because the remedy differs — a filter needs a
     * declared comparison, a sort needs the column to be emitted — and because a sort on a withheld
     * column is a distinct security event worth naming on its own.
     */
    public static class SortNotProjectableException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final String fieldKey;

        public SortNotProjectableException(String fieldKey, String reason) {
            super("Export sort on '" + fieldKey + "' cannot be projected: " + reason
                + ". Refused rather than ignored, because an ignored sort produces a file whose order "
                    + "no longer matches the report definition it claims to have come from.");
            this.fieldKey = fieldKey;
        }

        public String fieldKey() {
            return fieldKey;
        }

        public String code() {
            return "SORT_NOT_PROJECTABLE";
        }
    }

    /** The filter keys the planner accepted, for the job's stored filters. */
    public static Map<String, Object> storedFilters(List<ExportFilter> filters) {
        Map<String, Object> stored = new java.util.LinkedHashMap<>();
        for (ExportFilter filter : filters) {
            stored.put(filter.field().key(), filter.toStored());
        }
        return java.util.Collections.unmodifiableMap(stored);
    }
}