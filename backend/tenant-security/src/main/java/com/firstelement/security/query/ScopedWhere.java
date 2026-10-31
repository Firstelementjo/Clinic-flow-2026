package com.firstelement.security.query;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * Builds SQL that already carries the tenant filter, so a query cannot be executed
 * without it.
 *
 * <h2>What it prevents</h2>
 *
 * <ol>
 *   <li><b>Forgetting the filter.</b> Every statement-producing method requires a
 *       {@link TenantScope}, which can only come from {@link TenantQueryGuard}. There is
 *       no API that produces an unscoped statement.</li>
 *   <li><b>String-concatenating the filter.</b> Column names are developer-supplied
 *       identifiers, quoted defensively here; every <em>value</em> is a bound parameter.
 *       A tenant id can never be pasted into SQL text.</li>
 *   <li><b>Filtering after aggregation.</b> FR-8.2 requires authorization before
 *       aggregation, not after. Because the predicate lands in {@code WHERE}, an
 *       unauthorized row never enters a {@code count}, {@code sum}, or {@code avg} —
 *       which is what makes "unauthorized aggregates reveal nothing" true rather than
 *       merely approximately true.</li>
 * </ol>
 *
 * <h2>Parameter names are reserved</h2>
 *
 * <p>The tenant predicates bind to {@link #ORGANIZATION_PARAM} and {@link #PROJECT_PARAM}.
 * A caller-supplied parameter map is applied <em>first</em> and the tenant values are
 * written afterwards, so a caller cannot shadow the filter by passing a parameter of the
 * same name. That would otherwise be a way to swap the tenant out of the predicate
 * without touching this class.
 *
 * <h2>Identifier quoting</h2>
 *
 * <p>ADR-0002: the physical schema uses quoted camelCase ({@code "organizationId"}).
 * Fragments are emitted with that exact spelling. Aliases are validated against
 * {@code [A-Za-z_][A-Za-z0-9_]*} and rejected otherwise, so a caller cannot smuggle SQL
 * through the alias parameter.
 *
 * <p>Both a named-parameter form (for {@code NamedParameterJdbcTemplate} and JdbcClient)
 * and a positional form (for {@code JdbcTemplate}) are provided.
 */
public final class ScopedWhere {

    public static final String ORGANIZATION_PARAM = "__fe_tenant_organization_id";
    public static final String PROJECT_PARAM = "__fe_tenant_project_id";

    private static final String ORG_COLUMN = "\"organizationId\"";
    private static final String PROJECT_COLUMN = "\"projectId\"";

    private final TenantScope scope;

    private ScopedWhere(TenantScope scope) {
        this.scope = scope;
    }

    public static ScopedWhere of(TenantScope scope) {
        if (scope == null) {
            throw new IllegalArgumentException(
                "ScopedWhere requires a TenantScope from TenantQueryGuard; an unscoped statement "
                    + "is not constructible.");
        }
        return new ScopedWhere(scope);
    }

    public TenantScope scope() {
        return scope;
    }

    // ------------------------------------------------------------------
    // named-parameter form (preferred)
    // ------------------------------------------------------------------

    /** e.g. {@code c."organizationId" = :__fe_tenant_organization_id}. */
    public String namedPredicate(String alias) {
        String prefix = qualify(alias);
        if (scope.isProjectScoped()) {
            return prefix + ORG_COLUMN + " = :" + ORGANIZATION_PARAM
                + " and " + prefix + PROJECT_COLUMN + " = :" + PROJECT_PARAM;
        }
        return prefix + ORG_COLUMN + " = :" + ORGANIZATION_PARAM;
    }

    /**
     * Merges caller parameters with the tenant parameters. The tenant values are written
     * last and therefore win any name collision.
     */
    public MapSqlParameterSource bindNamed(java.util.Map<String, ?> callerParameters) {
        MapSqlParameterSource source = new MapSqlParameterSource();
        if (callerParameters != null) {
            callerParameters.forEach((key, value) -> {
                if (ORGANIZATION_PARAM.equals(key) || PROJECT_PARAM.equals(key)) {
                    throw new IllegalArgumentException(
                        "'" + key + "' is reserved for the tenant filter and cannot be supplied by a caller");
                }
                source.addValue(key, value);
            });
        }
        source.addValue(ORGANIZATION_PARAM, scope.organizationId());
        if (scope.isProjectScoped()) {
            source.addValue(PROJECT_PARAM, scope.projectId());
        }
        return source;
    }

    /**
     * A complete statement with named parameters.
     *
     * @param selectSql            developer-authored SQL (never user input)
     * @param alias                a plain SQL identifier for the scope columns
     * @param additionalPredicate  developer-authored predicate, or {@code null}
     * @param callerParameters     values for {@code additionalPredicate}
     */
    public NamedSql select(String selectSql, String alias, String additionalPredicate,
                           java.util.Map<String, ?> callerParameters) {
        require(selectSql != null && !selectSql.isBlank(), "selectSql must not be blank");
        StringBuilder sql = new StringBuilder(selectSql.trim())
            .append(" where ").append(namedPredicate(alias));
        if (additionalPredicate != null && !additionalPredicate.isBlank()) {
            sql.append(" and (").append(additionalPredicate.trim()).append(')');
        }
        return new NamedSql(sql.toString(), bindNamed(callerParameters));
    }

    public NamedSql select(String selectSql, String alias, String additionalPredicate) {
        return select(selectSql, alias, additionalPredicate, java.util.Map.of());
    }

    public NamedSql select(String selectSql, String alias) {
        return select(selectSql, alias, null, java.util.Map.of());
    }

    /** A named-parameter UPDATE. */
    public NamedSql update(String updatePrefix, String alias, String additionalPredicate,
                           java.util.Map<String, ?> callerParameters) {
        return select(updatePrefix, alias, additionalPredicate, callerParameters);
    }

    /** A named-parameter DELETE. */
    public NamedSql delete(String deletePrefix, String alias, String additionalPredicate,
                           java.util.Map<String, ?> callerParameters) {
        return select(deletePrefix, alias, additionalPredicate, callerParameters);
    }

    // ------------------------------------------------------------------
    // positional form
    // ------------------------------------------------------------------

    /** e.g. {@code c."organizationId" = ? and c."projectId" = ?}. */
    public String positionalPredicate(String alias) {
        String prefix = qualify(alias);
        if (scope.isProjectScoped()) {
            return prefix + ORG_COLUMN + " = ? and " + prefix + PROJECT_COLUMN + " = ?";
        }
        return prefix + ORG_COLUMN + " = ?";
    }

    /** The bound values for {@link #positionalPredicate(String)}, in order. */
    public Object[] positionalParameters() {
        return scope.isProjectScoped()
            ? new Object[]{scope.organizationId(), scope.projectId()}
            : new Object[]{scope.organizationId()};
    }

    /** A statement with {@code ?} placeholders; values are tenant-first, then caller. */
    public ScopedSql positional(String selectSql, String alias, String additionalPredicate,
                                Object... callerParameters) {
        require(selectSql != null && !selectSql.isBlank(), "selectSql must not be blank");
        StringBuilder sql = new StringBuilder(selectSql.trim())
            .append(" where ").append(positionalPredicate(alias));
        Object[] parameters = positionalParameters();
        if (additionalPredicate != null && !additionalPredicate.isBlank()) {
            sql.append(" and (").append(additionalPredicate.trim()).append(')');
            Object[] merged = new Object[parameters.length + callerParameters.length];
            System.arraycopy(parameters, 0, merged, 0, parameters.length);
            System.arraycopy(callerParameters, 0, merged, parameters.length, callerParameters.length);
            parameters = merged;
        }
        return new ScopedSql(sql.toString(), parameters);
    }

    public ScopedSql positional(String selectSql, String alias) {
        return positional(selectSql, alias, null);
    }

    // ------------------------------------------------------------------

    private String qualify(String alias) {
        require(alias != null, "alias must not be null");
        require(alias.matches("[A-Za-z_][A-Za-z0-9_]*"),
            () -> "SQL alias must be a plain identifier, got: " + alias);
        return alias + ".";
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    private static void require(boolean condition, java.util.function.Supplier<String> message) {
        if (!condition) {
            throw new IllegalArgumentException(message.get());
        }
    }

    /** A statement with bound values in positional order. */
    public record ScopedSql(String sql, Object[] parameters) {
    }

    /** A statement with a prepared named-parameter source. */
    public record NamedSql(String sql, MapSqlParameterSource parameters) {
    }
}