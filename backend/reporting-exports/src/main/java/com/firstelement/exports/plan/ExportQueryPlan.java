package com.firstelement.exports.plan;

import com.firstelement.exports.catalog.ReportField;
import com.firstelement.exports.catalog.ReportSubject;
import com.firstelement.exports.catalog.ReportFieldRegistry;
import java.util.Collections;
import java.util.List;

/**
 * A finished, immutable export statement and everything the caller needs to run it safely.
 *
 * <h2>What a plan carries, and why each part is here</h2>
 *
 * <ul>
 *   <li>{@code sql} — the assembled statement. Positional {@code ?} placeholders only; every value is
 *       a bound parameter and the tenant parameters come first, written by
 *       {@link com.firstelement.security.query.ScopedWhere} so a caller cannot shadow them.</li>
 *   <li>{@code parameters} — tenant, then project, then the filter values in the order their
 *       placeholders appear. The order is part of the contract: the runner binds positionally.</li>
 *   <li>{@code fields} — the emitted columns, in the order they were selected. This is the artefact's
 *       column order and it is stable, which is what FR-8.3's "stable column metadata" means.</li>
 *   <li>{@code subject} — which physical rows the statement reads.</li>
 *   <li>{@code financeLocked} — whether the statement is serving a LOCKED finance period, so the
 *       runner can refuse a group whose figures the locked period cannot support.</li>
 * </ul>
 *
 * @param sql          the statement, with {@code ?} placeholders
 * @param parameters   the bound values, tenant-first
 * @param fields       the emitted columns in select order
 * @param subject      the subject read
 * @param sortFields   the sort keys applied, as emitted columns, so a reader can see the order the
 *                     file is in without parsing the statement
 * @param lockedFinancePeriodKeys the finance periods in {@code LOCKED} state that intersect the
 *                     export's window; empty for a non-finance subject
 * @param appliedFilters the filters that were actually pushed, in placeholder order. Kept on the plan
 *                     so {@code export_jobs."filters"} records what ran rather than what was requested
 */
public record ExportQueryPlan(String sql,
                              List<Object> parameters,
                              List<ReportField> fields,
                              ReportSubject subject,
                              List<ReportField> sortFields,
                              List<String> lockedFinancePeriodKeys,
                              List<ExportFilter> appliedFilters) {

    public ExportQueryPlan {
        parameters = List.copyOf(parameters);
        fields = List.copyOf(fields);
        sortFields = List.copyOf(sortFields);
        lockedFinancePeriodKeys = List.copyOf(lockedFinancePeriodKeys);
        appliedFilters = List.copyOf(appliedFilters);
    }

    /** The number of distinct rows the statement produces is unknown until it runs; this is the cap. */
    public int columnCount() {
        return fields.size();
    }

    /**
     * The field a column ordinal maps to.
     *
     * @throws IndexOutOfBoundsException when the ordinal is outside the select list, which can only
     *         happen if a caller asks the plan for a column the plan does not have
     */
    public ReportField fieldAt(int oneBasedOrdinal) {
        return fields.get(oneBasedOrdinal - 1);
    }

    /** Whether this statement aggregates, and therefore whether it carries a {@code GROUP BY}. */
    public boolean aggregates() {
        return ReportFieldRegistry.aggregates(fields);
    }

    /** Whether this statement touches money. */
    public boolean carriesMoney() {
        return ReportFieldRegistry.carriesMoney(fields);
    }

    /**
     * The stored jsonb form of the applied filters, for {@code export_jobs."filters"}.
     *
     * <p>Written from the plan rather than reconstructed from the request, so the job records the
     * filters that were actually pushed and not the ones that were asked for. A filter that was
     * refused never reaches a job at all, because no statement is produced.
     */
    public java.util.Map<String, Object> appliedFiltersAsJson() {
        java.util.Map<String, Object> stored = new java.util.LinkedHashMap<>();
        for (ExportFilter filter : appliedFilters) {
            stored.put(filter.field().key(), filter.toStored());
        }
        return Collections.unmodifiableMap(stored);
    }
}