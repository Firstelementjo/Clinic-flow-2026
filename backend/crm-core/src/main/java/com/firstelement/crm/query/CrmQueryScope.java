package com.firstelement.crm.query;

import com.firstelement.security.TenantContext;
import com.firstelement.security.query.TenantQueryGuard;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * The tenant/project predicate every CRM statement carries.
 *
 * <h2>Why this exists next to {@code ScopedWhere}</h2>
 *
 * <p>{@code ScopedWhere} (owned by {@code :backend:tenant-security}) models two shapes:
 * one pinned project, or the whole organization. The CRM needs a third — <em>every project
 * this principal may reach</em> — because the legacy runtime's
 * {@code accessibleProjectsScope} did exactly that:
 *
 * <pre>{@code
 * // apps/api/src/../database/src/tenant.ts
 * { organizationId: ctx.organizationId, projectId: { in: [...ctx.projectIds] } }
 * }</pre>
 *
 * <p>That third shape is a security property, not a convenience: <b>a principal with no
 * project memberships must receive no rows</b> (FR-1.4). An organization-only predicate
 * would return every row in the tenant to a user whose membership was revoked.
 *
 * <h2>Construction is the security boundary</h2>
 *
 * <p>There is no public constructor. A scope can only be obtained from
 * {@link #forProject}, {@link #accessibleProjects}, or {@link #tenant}, and the first
 * delegates to {@link TenantQueryGuard}, which refuses a client-supplied project id that
 * is not in the principal's server-resolved {@code projectIds}. A caller therefore cannot
 * widen the scope it is handed.
 *
 * <h2>Parameter names are reserved</h2>
 *
 * <p>{@link #bind(Map)} writes the tenant parameters <em>last</em> and refuses a caller
 * parameter of the same name, so a filter string cannot smuggle a different tenant into a
 * statement that looks scoped.
 *
 * <h2>Multi-alias composition</h2>
 *
 * <p>{@link #predicate(String)} works for any table alias, which is what joins across
 * {@code conversations}/{@code customers}/{@code employees} need: each alias gets its own
 * copy of the same tenant predicate rather than relying on a single aliased column.
 */
public final class CrmQueryScope {

    /**
     * Reserved parameter names. Public so an INSERT — which has no {@code WHERE} clause to hang
     * the predicate on — can still bind the tenant columns by name.
     */
    public static final String ORGANIZATION_PARAM = "__fe_crm_organization_id";
    public static final String PROJECT_PARAM = "__fe_crm_project_id";
    public static final String PROJECTS_PARAM = "__fe_crm_project_ids";

    private static final String ORG_COLUMN = "\"organizationId\"";
    private static final String PROJECT_COLUMN = "\"projectId\"";

    private final UUID organizationId;
    private final UUID projectId;
    private final List<UUID> accessibleProjectIds;

    private CrmQueryScope(UUID organizationId, UUID projectId, List<UUID> accessibleProjectIds) {
        this.organizationId = organizationId;
        this.projectId = projectId;
        this.accessibleProjectIds = List.copyOf(accessibleProjectIds);
    }

    // ------------------------------------------------------------------
    // construction
    // ------------------------------------------------------------------

    /**
     * Validates a requested project id and pins the scope to it.
     *
     * <p>A {@code null} request means "every project I may see", never "every project":
     * it degrades to {@link #accessibleProjects(TenantContext)}.
     *
     * @throws com.firstelement.security.error.PolicyDeniedException when the request names a
     *         project outside the principal's server-resolved scope
     */
    public static CrmQueryScope forProject(TenantQueryGuard guard, TenantContext context,
                                           UUID requestedProjectId) {
        // The guard is the only thing allowed to decide whether a requested project is legal.
        guard.scope(context, requestedProjectId);
        if (requestedProjectId == null) {
            return accessibleProjects(context);
        }
        return new CrmQueryScope(context.organizationId(), requestedProjectId,
            context.projectIds().stream().sorted().toList());
    }

    /** Every project in the principal's server-resolved scope; empty means "nothing". */
    public static CrmQueryScope accessibleProjects(TenantContext context) {
        if (context == null) {
            throw new IllegalStateException(
                "No TenantContext is bound; refusing to build an unscoped CRM query.");
        }
        return new CrmQueryScope(context.organizationId(), null,
            context.projectIds().stream().sorted().toList());
    }

    /**
     * Alias of {@link #accessibleProjects(TenantContext)} that reads better where the
     * guard call is made unconditionally by the caller.
     */
    public static CrmQueryScope tenant(TenantContext context, TenantQueryGuard guard) {
        return forProject(guard, context, null);
    }

    // ------------------------------------------------------------------
    // queries
    // ------------------------------------------------------------------

    public UUID organizationId() {
        return organizationId;
    }

    public UUID projectId() {
        return projectId;
    }

    public boolean isProjectScoped() {
        return projectId != null;
    }

    public List<UUID> accessibleProjectIds() {
        return accessibleProjectIds;
    }

    /**
     * The tenant predicate for one table alias.
     *
     * <pre>
     * c."organizationId" = :__fe_crm_organization_id and c."projectId" = :__fe_crm_project_id
     * c."organizationId" = :__fe_crm_organization_id and c."projectId" in (:__fe_crm_project_ids)
     * </pre>
     */
    public String predicate(String alias) {
        String prefix = qualify(alias);
        if (isProjectScoped()) {
            return prefix + ORG_COLUMN + " = :" + ORGANIZATION_PARAM
                + " and " + prefix + PROJECT_COLUMN + " = :" + PROJECT_PARAM;
        }
        if (accessibleProjectIds.isEmpty()) {
            // A principal with no memberships must receive nothing. Emitting `in ()` would be a
            // PostgreSQL syntax error — which would surface as a 500 on exactly the request that
            // most needs to be a quiet, empty, authorized answer. `1 = 0` is the same decision
            // expressed in a form the parser accepts.
            return prefix + ORG_COLUMN + " = :" + ORGANIZATION_PARAM + " and 1 = 0";
        }
        return prefix + ORG_COLUMN + " = :" + ORGANIZATION_PARAM
            + " and " + prefix + PROJECT_COLUMN + " in (:" + PROJECTS_PARAM + ")";
    }

    /** {@link #predicate(String)} joined with {@code and}; used for multi-alias statements. */
    public String predicate(String alias, String... additionalAliases) {
        StringBuilder sql = new StringBuilder(predicate(alias));
        for (String additional : additionalAliases) {
            sql.append(" and (").append(predicate(additional)).append(')');
        }
        return sql.toString();
    }

    /**
     * Merges caller parameters with the tenant parameters.
     *
     * <p>Tenant parameters are written last, so they win a name collision; supplying one of
     * the reserved names at all is an error rather than a silent override.
     */
    public MapSqlParameterSource bind(Map<String, ?> callerParameters) {
        MapSqlParameterSource source = new MapSqlParameterSource();
        if (callerParameters != null) {
            for (Map.Entry<String, ?> entry : callerParameters.entrySet()) {
                if (isReserved(entry.getKey())) {
                    throw new IllegalArgumentException(
                        "'" + entry.getKey() + "' is reserved for the tenant filter and cannot be "
                            + "supplied by a caller");
                }
                source.addValue(entry.getKey(), entry.getValue());
            }
        }
        source.addValue(ORGANIZATION_PARAM, organizationId);
        if (isProjectScoped()) {
            source.addValue(PROJECT_PARAM, projectId);
        }
        source.addValue(PROJECTS_PARAM, accessibleProjectIds);
        return source;
    }

    public MapSqlParameterSource bind() {
        return bind(Map.of());
    }

    /** Convenience for building a parameter map in insertion order. */
    public static Map<String, Object> params(Object... keyValuePairs) {
        if (keyValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("params() requires an even number of arguments");
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            map.put((String) keyValuePairs[i], keyValuePairs[i + 1]);
        }
        return map;
    }

    /** A complete statement with named parameters, scope first in the predicate. */
    public Statement select(String selectSql, String alias, String additionalPredicate,
                             Map<String, ?> callerParameters) {
        if (selectSql == null || selectSql.isBlank()) {
            throw new IllegalArgumentException("selectSql must not be blank");
        }
        StringBuilder sql = new StringBuilder(selectSql.trim())
            .append(" where ").append(predicate(alias));
        if (additionalPredicate != null && !additionalPredicate.isBlank()) {
            sql.append(" and (").append(additionalPredicate.trim()).append(')');
        }
        return new Statement(sql.toString(), bind(callerParameters));
    }

    public Statement select(String selectSql, String alias, String additionalPredicate) {
        return select(selectSql, alias, additionalPredicate, Map.of());
    }

    /** A tenant-scoped UPDATE. */
    public Statement update(String updatePrefix, String alias, String additionalPredicate,
                             Map<String, ?> callerParameters) {
        return select(updatePrefix, alias, additionalPredicate, callerParameters);
    }

    /** An INSERT with no caller parameters. */
    public Statement insert(String insertSql) {
        return insert(insertSql, Map.of());
    }

    /** A select with no additional predicate and no caller parameters. */
    public Statement select(String selectSql, String alias) {
        return select(selectSql, alias, null, Map.of());
    }

    /** A tenant-scoped DELETE. */
    public Statement delete(String deletePrefix, String alias, String additionalPredicate,
                            Map<String, ?> callerParameters) {
        return select(deletePrefix, alias, additionalPredicate, callerParameters);
    }

    /**
     * A tenant-scoped INSERT.
     *
     * <p>An INSERT has no predicate to hang the tenant filter on, so the statement must name
     * {@link #ORGANIZATION_PARAM} (and {@link #PROJECT_PARAM} when pinned) explicitly. Doing
     * it by name rather than by {@code String.format} keeps the values bound and keeps a caller
     * from ever writing the tenant id into SQL text.
     */
    public Statement insert(String insertSql, Map<String, ?> callerParameters) {
        if (insertSql == null || insertSql.isBlank()) {
            throw new IllegalArgumentException("insertSql must not be blank");
        }
        if (!insertSql.contains(":" + ORGANIZATION_PARAM)) {
            throw new IllegalArgumentException(
                "An INSERT through CrmQueryScope must bind :" + ORGANIZATION_PARAM);
        }
        if (isProjectScoped() && !insertSql.contains(":" + PROJECT_PARAM)) {
            throw new IllegalArgumentException(
                "A project-scoped INSERT through CrmQueryScope must bind :" + PROJECT_PARAM);
        }
        return new Statement(insertSql, bind(callerParameters));
    }

    // ------------------------------------------------------------------

    private static boolean isReserved(String name) {
        return ORGANIZATION_PARAM.equals(name) || PROJECT_PARAM.equals(name)
            || PROJECTS_PARAM.equals(name);
    }

    private static String qualify(String alias) {
        if (alias == null || !alias.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("SQL alias must be a plain identifier, got: " + alias);
        }
        return alias + ".";
    }

    /** An assembled statement plus its bound parameters. */
    public record Statement(String sql, MapSqlParameterSource parameters) {

        /** {@code order by ... limit n + 1} — the extra row is how the cursor detects "more". */
        public Statement orderedBy(String orderBy, int fetchLimit) {
            return orderedBy(orderBy).withLimit(fetchLimit);
        }

        /**
         * {@code order by ...} with no {@code limit}.
         *
         * <p>Two shapes need it and neither can be expressed by splicing a number onto the
         * text. {@code DISTINCT ON (k."customerId")} requires the sort to lead with the
         * DISTINCT expressions, and a {@code limit} on the batched customer-list child
         * projections would truncate <em>customers</em>, not rows — the same structural mistake
         * {@code ClinicRepository.FUNNEL_BY_STATUS} documents. The per-customer bound in those
         * projections is a bound parameter inside the statement, not a statement-wide limit.
         */
        public Statement orderedBy(String orderBy) {
            List<String> fragments = new ArrayList<>();
            fragments.add(sql);
            if (orderBy != null && !orderBy.isBlank()) {
                fragments.add("order by " + orderBy.trim());
            }
            return new Statement(String.join(" ", fragments), parameters);
        }

        /** Appends a {@code limit}, spliced as text exactly as {@link #orderedBy} splices text. */
        public Statement withLimit(int fetchLimit) {
            return new Statement(sql + " limit " + fetchLimit, parameters);
        }
    }
}