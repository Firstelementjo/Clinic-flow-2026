package com.firstelement.admin.audit;

import com.firstelement.security.query.ScopedWhere;
import com.firstelement.security.query.TenantScope;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Resolves the retention class an audit row should carry, from the tenant's own configuration.
 *
 * <h2>Why a resolver instead of a constant</h2>
 *
 * <p>{@code V20261005050000} puts {@code retentionClasses} in {@code "retentionClass"} on all three
 * append-only streams with the note that the class is "stored on the row, not assumed", and
 * ADR-0009 §4 rule 4 makes it a precondition of any purge: "No table in this ADR is purged by a
 * scheduled job until the retention-class column is populated for it." A writer that stamped a
 * hard-coded {@code DEFAULT} would satisfy the NOT NULL CHECK and defeat the rule, because the
 * value would look configured.
 *
 * <p>So a row whose tenant has declared no class for its stream is written as
 * {@link AuditVocabularies#UNDECLARED_RETENTION_CLASS}, which is visible on the governance read
 * model and is the signal an operator needs before enabling a purge.
 *
 * <h2>The most-specific-row rule, written down</h2>
 *
 * <p>{@code V20261005050000} says of {@code retention_classes}: "NULL = this class applies to every
 * project in the organisation. A per-project override is a second row with a projectId; the code
 * that resolves a class must prefer the most specific row, and that rule is recorded here rather
 * than left to whoever writes the resolver." This is that resolver: an exact
 * {@code (organizationId, projectId, classCode)} row wins over the organisation-wide
 * {@code (organizationId, NULL, classCode)} row, and the lookup order is expressed in the SQL
 * rather than in Java so a race between two writers cannot make the choice depend on row order.
 */
@Repository
public class RetentionClassResolver {

    private static final String RESOLVE = """
        select "classCode"
          from retention_classes
         where "organizationId" = :__fe_tenant_organization_id
           and "stream" = cast(:stream as "AuditStreamKind")
           and "classCode" = :classCode
           and (("projectId" is null) or ("projectId" = cast(:projectId as uuid)))
         order by ("projectId" is null)
         limit 1
        """;

    private final NamedParameterJdbcTemplate jdbc;

    public RetentionClassResolver(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The class code to stamp on a row of {@code stream} for {@code scope}.
     *
     * @param scope     a project-scoped or tenant-scoped scope from {@code TenantQueryGuard}. A
     *                  project-scoped scope narrows the override; a tenant-scoped one can only ever
     *                  reach the organisation-wide row, which is the correct answer for an
     *                  organisation-wide action.
     * @param stream    the {@code AuditStreamKind} label: ADMIN, SECURITY, JOB_INTEGRATION, …
     * @param classCode the conventional class code for this stream
     * @return the resolved code, or {@link AuditVocabularies#UNDECLARED_RETENTION_CLASS}
     */
    public String resolve(TenantScope scope, String stream, String classCode) {
        if (scope == null || stream == null || classCode == null) {
            return AuditVocabularies.UNDECLARED_RETENTION_CLASS;
        }
        MapSqlParameterSource parameters = ScopedWhere.of(scope)
            .bindNamed(Map.of("stream", stream, "classCode", classCode));
        if (scope.isProjectScoped()) {
            parameters.addValue("projectId", scope.projectId());
        } else {
            // A tenant-scoped lookup must not silently pick a project override, so the project arm
            // is bound to NULL rather than left out of the statement.
            parameters.addValue("projectId", null, java.sql.Types.OTHER);
        }
        return jdbc.query(RESOLVE, parameters,
            (rs, rowNum) -> rs.getString("classCode")).stream().findFirst()
            .orElse(AuditVocabularies.UNDECLARED_RETENTION_CLASS);
    }

    /**
     * The declared classes for a stream, newest configuration first, for the governance read model.
     *
     * <p>Keyed by {@code classCode} so the most-specific row overwrites the organisation-wide one,
     * which is the same rule {@link #resolve} applies and therefore the same picture.
     */
    public Map<String, RetentionClassRow> declared(TenantScope scope, String stream) {
        String sql = """
            select "id", "organizationId", "projectId", "classCode",
                   "retentionDays", "legalHoldExempt", "description"
              from retention_classes
             where "organizationId" = :__fe_tenant_organization_id
               and "stream" = cast(:stream as "AuditStreamKind")
             order by ("projectId" is null), "classCode"
            """;
        MapSqlParameterSource parameters = ScopedWhere.of(scope)
            .bindNamed(Map.of("stream", stream));
        Map<String, RetentionClassRow> byCode = new LinkedHashMap<>();
        jdbc.query(sql, parameters, (rs, rowNum) -> {
            RetentionClassRow row = new RetentionClassRow(
                rs.getObject("id", java.util.UUID.class),
                rs.getObject("projectId", java.util.UUID.class),
                rs.getString("classCode"),
                rs.getInt("retentionDays"),
                rs.getBoolean("legalHoldExempt"),
                rs.getString("description"));
            byCode.put(row.classCode(), row);
            return row;
        });
        return byCode;
    }

    /**
     * One retention class.
     *
     * @param projectId      {@code null} for the organisation-wide row
     * @param legalHoldExempt {@code true} means a purge skips this class while a hold covers it
     */
    public record RetentionClassRow(java.util.UUID id,
                                    java.util.UUID projectId,
                                    String classCode,
                                    int retentionDays,
                                    boolean legalHoldExempt,
                                    String description) {
    }
}