package com.firstelement.identity;

import com.firstelement.security.query.ScopedWhere;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The query surface the negative tests exercise.
 *
 * <p>Deliberately a <b>test</b> component. {@code :backend:tenant-security} owns the policy
 * primitives ({@link ScopedWhere}, the guard, the redactor) and no business queries; this
 * stands in for the CRM repository the Phase-4 owner will write, so the tests can prove the
 * primitives actually confine a real {@code SELECT}/{@code UPDATE}/{@code count} against
 * the real table without this phase implementing a business module.
 *
 * <p>Every method takes a pre-built {@link ScopedWhere.NamedSql}. There is no overload that
 * accepts a plain statement, so a query cannot be executed without a scope attached.
 */
@Component
public class ScopedCustomerQuery {

    /** Quoted camelCase projection, mapped by hand per ADR-0002. */
    public static final String CUSTOMER_COLUMNS = """
        c."id", c."cxId", c."displayName", c."primaryPhone", c."notes"
        """;

    private final NamedParameterJdbcTemplate jdbc;

    public ScopedCustomerQuery(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String customerColumns() {
        return CUSTOMER_COLUMNS;
    }

    /** The full {@code SELECT} prefix a caller passes to {@code ScopedWhere.select}. */
    public String customerSelect() {
        return "select " + CUSTOMER_COLUMNS + " from customers c";
    }

    public List<Map<String, Object>> listCustomers(ScopedWhere.NamedSql statement) {
        return jdbc.queryForList(statement.sql(), statement.parameters());
    }

    public Map<String, Object> single(ScopedWhere.NamedSql statement) {
        List<Map<String, Object>> rows = jdbc.queryForList(statement.sql(), statement.parameters());
        if (rows.isEmpty()) {
            throw new IllegalStateException("Expected exactly one row for: " + statement.sql());
        }
        return rows.get(0);
    }

    public long countCustomers(ScopedWhere.NamedSql statement) {
        return ((Number) single(statement).get("c")).longValue();
    }

    public int execute(ScopedWhere.NamedSql statement) {
        return jdbc.update(statement.sql(), statement.parameters());
    }

    /**
     * Reads one row <em>without</em> a tenant predicate.
     *
     * <p>Exists only so a test can prove the raw column values exist in the database before
     * redaction removes them, and that a tenant-scoped statement genuinely cannot reach
     * them. Never to be used by production code.
     */
    public Map<String, Object> readCustomer(UUID customerId) {
        return jdbc.queryForMap("""
            select c."id", c."cxId", c."displayName", c."primaryPhone", c."notes",
                   c."organizationId", c."projectId"
              from customers c where c."id" = :id
            """, new MapSqlParameterSource("id", customerId));
    }

    /** A map with every value stringified, which is how PostgreSQL returns uuid columns. */
    public static Map<String, Object> stringify(Map<String, Object> row) {
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        row.forEach((key, value) -> out.put(key, value == null ? null : value.toString()));
        return out;
    }
}