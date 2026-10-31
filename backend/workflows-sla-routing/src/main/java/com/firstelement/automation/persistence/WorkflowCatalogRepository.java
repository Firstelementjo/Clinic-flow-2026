package com.firstelement.automation.persistence;

import com.firstelement.security.query.ScopedWhere;
import com.firstelement.security.query.TenantScope;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code workflow_definitions} and {@code workflow_versions}: authoring, activation and rollback.
 *
 * <h2>Every statement here is the canonical one</h2>
 *
 * <p>The shapes come from {@code ops/flyway/reference/workflow-sla-statements.sql} §1, §2 and §3
 * and are copied, not re-derived: the predicates, the {@code ORDER BY}s and the UTC comparisons
 * are a contract with the indexes {@code V20261005010000} created, and a re-derived query is how a
 * statement silently stops being index-supported.
 *
 * <h2>The three-step activation, and why the order is load-bearing</h2>
 *
 * <ol>
 *   <li>retire the incumbent (it becomes {@code SUPERSEDED} and names its successor), which frees
 *       {@code workflow_versions_one_active_per_definition};</li>
 *   <li>activate the new version, <b>accepting {@code DRAFT} or {@code SUPERSEDED}</b>;</li>
 *   <li>move the definition's pointer.</li>
 * </ol>
 *
 * <p>Step 2's second accepted value is the one that is easy to get wrong and is therefore called
 * out here: a {@code DRAFT}-only predicate would force the rollback path to be written a second time
 * and the two copies to drift, because "roll back to a previously superseded version" <em>is</em>
 * "activate it again". A 0-row step 2 means the version is {@code ROLLED_BACK} or
 * {@code ARCHIVED}, and the caller must roll back the whole transaction rather than continue —
 * {@link ActivationConflictException} carries that.
 *
 * <p>{@code ROLLED_BACK} and {@code ARCHIVED} are deliberately not accepted: a version that was
 * rolled away from, or retired, must not return to production through this statement.
 *
 * <h2>There is no DELETE, and there is no update of the body</h2>
 *
 * <p>"The abandoned version is the body an in-flight run bound to, and deleting it would make that
 * run unexplainable." Every state change here is an UPDATE of lifecycle columns; the body itself is
 * immutable once written.
 */
@Repository
public class WorkflowCatalogRepository {

    private static final String UTC_NOW = "(now() AT TIME ZONE 'UTC')";

    /** One definition row. */
    public record DefinitionRow(UUID id,
                                UUID organizationId,
                                UUID projectId,
                                String key,
                                String name,
                                String description,
                                String triggerType,
                                int priority,
                                boolean enabled,
                                int latestVersionNumber,
                                UUID activeVersionId,
                                UUID createdByUserId,
                                LocalDateTime createdAt,
                                LocalDateTime updatedAt) {
    }

    /** One version row, body included. */
    public record VersionRow(UUID id,
                             UUID organizationId,
                             UUID projectId,
                             UUID workflowDefinitionId,
                             int versionNumber,
                             String state,
                             String changeNote,
                             String stateMachine,
                             String triggerConfig,
                             String capabilityScope,
                             String contentHash,
                             LocalDateTime activatedAt,
                             LocalDateTime supersededAt,
                             UUID supersededByVersionId,
                             LocalDateTime rolledBackAt,
                             UUID rolledBackByUserId,
                             UUID rolledBackToVersionId,
                             LocalDateTime createdAt) {
    }

    private static final RowMapper<DefinitionRow> DEFINITION = (rs, rowNum) -> new DefinitionRow(
        rs.getObject("id", UUID.class),
        rs.getObject("organizationId", UUID.class),
        rs.getObject("projectId", UUID.class),
        rs.getString("key"),
        rs.getString("name"),
        rs.getString("description"),
        rs.getString("triggerType"),
        rs.getInt("priority"),
        rs.getBoolean("isEnabled"),
        rs.getInt("latestVersionNumber"),
        rs.getObject("activeVersionId", UUID.class),
        rs.getObject("createdByUserId", UUID.class),
        rs.getObject("createdAt", LocalDateTime.class),
        rs.getObject("updatedAt", LocalDateTime.class));

    private static final RowMapper<VersionRow> VERSION = (rs, rowNum) -> new VersionRow(
        rs.getObject("id", UUID.class),
        rs.getObject("organizationId", UUID.class),
        rs.getObject("projectId", UUID.class),
        rs.getObject("workflowDefinitionId", UUID.class),
        rs.getInt("versionNumber"),
        rs.getString("state"),
        rs.getString("changeNote"),
        rs.getString("stateMachine"),
        rs.getString("triggerConfig"),
        rs.getString("capabilityScope"),
        rs.getString("contentHash"),
        rs.getObject("activatedAt", LocalDateTime.class),
        rs.getObject("supersededAt", LocalDateTime.class),
        rs.getObject("supersededByVersionId", UUID.class),
        rs.getObject("rolledBackAt", LocalDateTime.class),
        rs.getObject("rolledBackByUserId", UUID.class),
        rs.getObject("rolledBackToVersionId", UUID.class),
        rs.getObject("createdAt", LocalDateTime.class));

    private static final String DEFINITION_SELECT = """
        select d."id", d."organizationId", d."projectId", d."key", d."name", d."description",
               d."triggerType", d."priority", d."isEnabled", d."latestVersionNumber",
               d."activeVersionId", d."createdByUserId", d."createdAt", d."updatedAt"
          from workflow_definitions d
        """;

    private static final String VERSION_SELECT = """
        select v."id", v."organizationId", v."projectId", v."workflowDefinitionId",
               v."versionNumber", v."state", v."changeNote", v."stateMachine", v."triggerConfig",
               v."capabilityScope", v."contentHash", v."activatedAt", v."supersededAt",
               v."supersededByVersionId", v."rolledBackAt", v."rolledBackByUserId",
               v."rolledBackToVersionId", v."createdAt"
          from workflow_versions v
        """;

    private final NamedParameterJdbcTemplate jdbc;

    public WorkflowCatalogRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------
    // authoring
    // ------------------------------------------------------------------

    /** Creates a definition. The key is tenant-unique, so a duplicate is a constraint violation. */
    public UUID insertDefinition(TenantScope scope, UUID id, String key, String name,
                                 String description, UUID projectId, String triggerType,
                                 int priority, UUID authorUserId) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("key", key)
            .addValue("name", name)
            .addValue("description", description)
            .addValue("projectId", projectId)
            .addValue("triggerType", triggerType)
            .addValue("priority", priority)
            .addValue("authorUserId", authorUserId)
            .addValue("now", LocalDateTime.now(java.time.ZoneOffset.UTC));
        parameters.addValue(com.firstelement.security.query.ScopedWhere.ORGANIZATION_PARAM,
            scope.organizationId());
        nullTyped(parameters, "description", java.sql.Types.VARCHAR);
        nullTyped(parameters, "projectId", java.sql.Types.OTHER);
        nullTyped(parameters, "authorUserId", java.sql.Types.OTHER);
        jdbc.update("""
            insert into workflow_definitions
                ("id", "organizationId", "projectId", "key", "name", "description", "triggerType",
                 "priority", "isEnabled", "latestVersionNumber", "createdByUserId", "createdAt", "updatedAt")
            values (:id, :__fe_tenant_organization_id, :projectId, :key, :name, :description,
                    :triggerType, :priority, true, 0, :authorUserId, :now, :now)
            """, parameters);
        return id;
    }

    /** Binds a possibly-null value with its JDBC type, so the driver never has to guess. */
    private static void nullTyped(MapSqlParameterSource parameters, String name, int sqlType) {
        if (parameters.getValue(name) == null) {
            parameters.addValue(name, null, sqlType);
        }
    }

    /**
     * The router's hot read: enabled definitions for a trigger, in explicit priority order.
     *
     * <p>{@code workflow-sla-statements.sql} §3: "No alphabetical ordering anywhere: priority is a
     * column and it is in the {@code ORDER BY}." {@code key} appears only as the deterministic
     * tie-break. Organisation-wide definitions (projectId NULL) are reached with a
     * {@code UNION ALL} of two index-friendly halves, never an {@code OR} over {@code projectId} —
     * an {@code OR} defeats both indexes.
     */
    public List<DefinitionRow> enabledDefinitionsForTrigger(TenantScope scope, UUID projectId,
                                                            String triggerType, int batchSize) {
        String organization = ":" + com.firstelement.security.query.ScopedWhere.ORGANIZATION_PARAM;
        MapSqlParameterSource parameters = bind(scope, Map.of(
            "projectId", projectId, "triggerType", triggerType));
        // Both halves are trimmed before being wrapped in parentheses: a text block ends with a
        // newline, and PostgreSQL reads "(select ...\n)" as a syntax error rather than as a parenthesised
        // sub-select.
        String half = """
            select d."id", d."organizationId", d."projectId", d."key", d."name", d."description",
                   d."triggerType", d."priority", d."isEnabled", d."latestVersionNumber",
                   d."activeVersionId", d."createdByUserId", d."createdAt", d."updatedAt"
              from workflow_definitions d
             where d."organizationId" = %s
               and d."projectId" = :projectId
               and d."triggerType" = :triggerType
               and d."isEnabled"
            """.formatted(organization).trim();
        String organizationWide = """
            select d."id", d."organizationId", d."projectId", d."key", d."name", d."description",
                   d."triggerType", d."priority", d."isEnabled", d."latestVersionNumber",
                   d."activeVersionId", d."createdByUserId", d."createdAt", d."updatedAt"
              from workflow_definitions d
             where d."organizationId" = %s
               and d."projectId" is null
               and d."triggerType" = :triggerType
               and d."isEnabled"
            """.formatted(organization).trim();
        // The halves are wrapped as two DERIVED TABLES rather than a parenthesised set operation. Both are
        // valid SQL, but only the derived-table form can carry an explicit column list, and the
        // outer ORDER BY needs those names; a parenthesised UNION would have to alias its output
        // with a bare ") u", which PostgreSQL reads as a syntax error rather than an alias.
        //
        // Assembled with String.format rather than by concatenating into a text block: a text block's
        // opening delimiter must be followed by a line terminator, so `""" + half + """` inline is
        // not legal Java.
        String sql = """
            select u."id", u."organizationId", u."projectId", u."key", u."name", u."description",
                   u."triggerType", u."priority", u."isEnabled", u."latestVersionNumber",
                   u."activeVersionId", u."createdByUserId", u."createdAt", u."updatedAt"
              from (%1$s) as u
             union all
            select u."id", u."organizationId", u."projectId", u."key", u."name", u."description",
                   u."triggerType", u."priority", u."isEnabled", u."latestVersionNumber",
                   u."activeVersionId", u."createdByUserId", u."createdAt", u."updatedAt"
              from (%2$s) as u
             order by "priority", "key"
             limit :batchSize
            """.formatted(half, organizationWide);
        return jdbc.query(sql, parameters.addValue("batchSize", batchSize), DEFINITION);
    }

    /** One definition, read through the tenant scope. Empty when it belongs to another tenant. */
    public Optional<DefinitionRow> findDefinition(TenantScope scope, UUID definitionId) {
        String sql = DEFINITION_SELECT + " where d.\"organizationId\" = :"
            + com.firstelement.security.query.ScopedWhere.ORGANIZATION_PARAM + " and d.\"id\" = :id";
        return jdbc.query(sql, bind(scope, Map.of("id", definitionId)), DEFINITION)
            .stream().findFirst();
    }

    public Optional<DefinitionRow> findDefinitionByKey(TenantScope scope, String key) {
        String sql = DEFINITION_SELECT + " where d.\"organizationId\" = :"
            + com.firstelement.security.query.ScopedWhere.ORGANIZATION_PARAM + " and d.\"key\" = :key";
        return jdbc.query(sql, bind(scope, Map.of("key", key)), DEFINITION).stream().findFirst();
    }

    // ------------------------------------------------------------------
    // versions
    // ------------------------------------------------------------------

    /**
     * Authors a version.
     *
     * <p>The version number comes from the definition's {@code latestVersionNumber} and the
     * {@code ("organizationId","workflowDefinitionId","versionNumber")} unique index is what makes a
     * concurrent author lose rather than overwrite.
     */
    public UUID insertVersion(TenantScope scope, UUID versionId, DefinitionRow definition,
                              int versionNumber, String changeNote, String stateMachineJson,
                              String triggerConfigJson, String capabilityScopeJson,
                              String contentHash, UUID authorUserId) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("id", versionId)
            .addValue("definitionId", definition.id())
            .addValue("projectId", definition.projectId())
            .addValue("versionNumber", versionNumber)
            .addValue("changeNote", changeNote)
            .addValue("stateMachine", stateMachineJson)
            .addValue("triggerConfig", triggerConfigJson)
            .addValue("capabilityScope", capabilityScopeJson)
            .addValue("contentHash", contentHash)
            .addValue("authorUserId", authorUserId)
            .addValue("now", LocalDateTime.now(java.time.ZoneOffset.UTC));
        parameters.addValue(com.firstelement.security.query.ScopedWhere.ORGANIZATION_PARAM,
            scope.organizationId());
        nullTyped(parameters, "changeNote", java.sql.Types.VARCHAR);
        nullTyped(parameters, "projectId", java.sql.Types.OTHER);
        nullTyped(parameters, "authorUserId", java.sql.Types.OTHER);
        jdbc.update("""
            insert into workflow_versions
                ("id", "organizationId", "projectId", "workflowDefinitionId", "versionNumber",
                 "state", "changeNote", "stateMachine", "triggerConfig", "capabilityScope",
                 "contentHash", "createdByUserId", "createdAt", "updatedAt")
            values (:id, :__fe_tenant_organization_id, :projectId, :definitionId, :versionNumber,
                    cast('DRAFT' as "WorkflowVersionState"), :changeNote, cast(:stateMachine as jsonb),
                    cast(:triggerConfig as jsonb), cast(:capabilityScope as jsonb), :contentHash,
                    :authorUserId, :now, :now)
            """, parameters);
        return versionId;
    }

    /** Bumps the definition's version counter. Fails on a lost update, which is the point. */
    public int advanceLatestVersionNumber(TenantScope scope, UUID definitionId, int toVersion) {
        String sql = """
            update workflow_definitions d
               set "latestVersionNumber" = greatest(d."latestVersionNumber", :toVersion),
                   "updatedAt" = %s
             where d."organizationId" = :%s and d."id" = :id
               and d."latestVersionNumber" < :toVersion
            """.formatted(UTC_NOW, com.firstelement.security.query.ScopedWhere.ORGANIZATION_PARAM);
        return jdbc.update(sql, bind(scope, Map.of("id", definitionId, "toVersion", toVersion)));
    }

    public Optional<VersionRow> findVersion(TenantScope scope, UUID versionId) {
        String sql = VERSION_SELECT + " where v.\"organizationId\" = :"
            + com.firstelement.security.query.ScopedWhere.ORGANIZATION_PARAM + " and v.\"id\" = :id";
        return jdbc.query(sql, bind(scope, Map.of("id", versionId)), VERSION).stream().findFirst();
    }

    /** The version an activation pointer names, resolved through the tenant scope. */
    public Optional<VersionRow> findActiveVersion(TenantScope scope, UUID definitionId) {
        String sql = VERSION_SELECT + """
             where v."organizationId" = :%s
               and v."workflowDefinitionId" = :definitionId
               and v."state" = cast('ACTIVE' as "WorkflowVersionState")
            """.formatted(com.firstelement.security.query.ScopedWhere.ORGANIZATION_PARAM);
        return jdbc.query(sql, bind(scope, Map.of("definitionId", definitionId)), VERSION)
            .stream().findFirst();
    }

    /**
     * The version history an operator opens after an incident, newest first.
     *
     * <p>{@code workflow_versions_organizationId_definitionId_state_idx}. Explicit ordering on the
     * version number, never on the name.
     */
    public List<VersionRow> versionHistory(TenantScope scope, UUID definitionId, int limit) {
        String sql = VERSION_SELECT + """
             where v."organizationId" = :%s and v."workflowDefinitionId" = :definitionId
             order by v."versionNumber" desc
             limit :limit
            """.formatted(com.firstelement.security.query.ScopedWhere.ORGANIZATION_PARAM);
        return jdbc.query(sql, bind(scope, Map.of("definitionId", definitionId, "limit", limit)),
            VERSION);
    }

    // ------------------------------------------------------------------
    // activation / rollback
    // ------------------------------------------------------------------

    /**
     * Step 1 of activation: retire the incumbent.
     *
     * <p>The successor is excluded explicitly, and that exclusion is what makes a double activation
     * <em>fail</em> rather than succeed quietly. Without it, re-activating the version that is already
     * {@code ACTIVE} would retire it to {@code SUPERSEDED} in step 1 and then immediately re-activate
     * it in step 2, so every row count would look healthy while the audit trail recorded a
     * supersession that never happened. With it, a second activation of the same version retires
     * nothing, step 2's predicate ({@code DRAFT} or {@code SUPERSEDED}) matches no row, and the
     * caller raises {@link ActivationConflictException} and rolls the transaction back.
     *
     * @return the number of rows retired; 0 when nothing was active, and anything other than 0 or 1
     *         means somebody else is activating and the caller must roll back
     */
    public int retireActiveVersion(TenantScope scope, UUID definitionId, UUID successorVersionId) {
        String sql = """
            update workflow_versions v
               set "state" = cast('SUPERSEDED' as "WorkflowVersionState"),
                   "supersededAt" = %s,
                   "supersededByVersionId" = :successorVersionId,
                   "updatedAt" = %s
             where v."organizationId" = :%s
               and v."workflowDefinitionId" = :definitionId
               and v."id" <> :successorVersionId
               and v."state" = cast('ACTIVE' as "WorkflowVersionState")
            """.formatted(UTC_NOW, UTC_NOW, com.firstelement.security.query.ScopedWhere.ORGANIZATION_PARAM);
        return jdbc.update(sql, bind(scope, Map.of(
            "definitionId", definitionId, "successorVersionId", successorVersionId)));
    }

    /**
     * Step 2 of activation.
     *
     * <p><b>DRAFT or SUPERSEDED.</b> See the class comment: a DRAFT-only predicate is a bug.
     *
     * @return 1 on success; 0 means already active, or {@code ROLLED_BACK}/{@code ARCHIVED}
     */
    public int activateVersion(TenantScope scope, UUID versionId, UUID actorUserId) {
        String sql = """
            update workflow_versions v
               set "state" = cast('ACTIVE' as "WorkflowVersionState"),
                   "activatedAt" = %s,
                   "activatedByUserId" = :actorUserId,
                   "updatedAt" = %s
             where v."id" = :id
               and v."organizationId" = :%s
               and v."state" in (cast('DRAFT' as "WorkflowVersionState"),
                                 cast('SUPERSEDED' as "WorkflowVersionState"))
            """.formatted(UTC_NOW, UTC_NOW, com.firstelement.security.query.ScopedWhere.ORGANIZATION_PARAM);
        return jdbc.update(sql, bind(scope, Map.of("id", versionId, "actorUserId", actorUserId)));
    }

    /** Step 3 of activation: move the definition's pointer. */
    public int moveActivePointer(TenantScope scope, UUID definitionId, UUID versionId, int versionNumber) {
        String sql = """
            update workflow_definitions d
               set "activeVersionId" = :versionId,
                   "latestVersionNumber" = greatest(d."latestVersionNumber", :versionNumber),
                   "updatedAt" = %s
             where d."id" = :id and d."organizationId" = :%s
            """.formatted(UTC_NOW, com.firstelement.security.query.ScopedWhere.ORGANIZATION_PARAM);
        return jdbc.update(sql, bind(scope, Map.of(
            "id", definitionId, "versionId", versionId, "versionNumber", versionNumber)));
    }

    /**
     * The rollback half: mark the abandoned version {@code ROLLED_BACK}, naming who, when and where
     * it went.
     *
     * <p>Never a DELETE. {@code workflow_versions_rollback_check} refuses the statement if any of
     * the three audit columns is missing, so a rollback that does not say where it landed cannot be
     * written at all.
     *
     * @return 1 on success; 0 means the version was not the active one
     */
    public int markRolledBack(TenantScope scope, UUID abandonedVersionId, UUID actorUserId,
                              UUID restoredVersionId) {
        String sql = """
            update workflow_versions v
               set "state" = cast('ROLLED_BACK' as "WorkflowVersionState"),
                   "rolledBackAt" = %s,
                   "rolledBackByUserId" = :actorUserId,
                   "rolledBackToVersionId" = :restoredVersionId,
                   "updatedAt" = %s
             where v."id" = :id
               and v."organizationId" = :%s
               and v."state" = cast('ACTIVE' as "WorkflowVersionState")
            """.formatted(UTC_NOW, UTC_NOW, com.firstelement.security.query.ScopedWhere.ORGANIZATION_PARAM);
        return jdbc.update(sql, bind(scope, Map.of(
            "id", abandonedVersionId, "actorUserId", actorUserId,
            "restoredVersionId", restoredVersionId)));
    }

    /** Enables or disables a definition. An explicit, audited act, never implicit. */
    public int setEnabled(TenantScope scope, UUID definitionId, boolean enabled) {
        String sql = """
            update workflow_definitions d
               set "isEnabled" = :enabled, "updatedAt" = %s
             where d."id" = :id and d."organizationId" = :%s
            """.formatted(UTC_NOW, com.firstelement.security.query.ScopedWhere.ORGANIZATION_PARAM);
        return jdbc.update(sql, bind(scope, Map.of("id", definitionId, "enabled", enabled)));
    }

    /** Definition ids and their active versions, for the dry-run preview's bounded batch. */
    public List<DefinitionRow> definitionsForDryRun(TenantScope scope, int limit) {
        String sql = DEFINITION_SELECT + """
             where d."organizationId" = :%s and d."activeVersionId" is not null
             order by d."priority", d."key"
             limit :limit
            """.formatted(com.firstelement.security.query.ScopedWhere.ORGANIZATION_PARAM);
        return jdbc.query(sql, bind(scope, Map.of("limit", limit)), DEFINITION);
    }

    /**
     * Every tenant that has at least one enabled definition, for a per-tenant scheduler sweep.
     *
     * <p>The one deliberately unscoped read in this module, and it reads no business data: it returns
     * organization <em>ids</em> so each tenant's work can then be executed under its own scope. The
     * sweep never uses this list to decide anything about a tenant's conversations; it only decides
     * which scopes to open, and {@code WorkflowRunService#runTenantContext} is what opens them. The
     * batch is bounded because an unbounded {@code select distinct} across every definition is how a
     * scheduler dies quietly on a large tenant.
     */
    public List<UUID> enabledDefinitionTenants(int batchSize) {
        List<UUID> ids = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList("""
            select distinct d."organizationId"
              from workflow_definitions d
             where d."isEnabled"
             limit :batchSize
            """, new MapSqlParameterSource("batchSize", batchSize))) {
            ids.add((UUID) row.get("organizationId"));
        }
        return ids;
    }

    /**
     * Binds caller values together with the scope's tenant parameters.
     *
     * <p>{@link ScopedWhere#bindNamed} is the only sanctioned way: it writes the tenant values last
     * and refuses a caller parameter of the reserved name, so a filter string cannot smuggle a
     * different tenant into a statement that looks scoped. {@code TenantScope} itself deliberately
     * carries no binding method, so the predicate and the parameters are always produced together.
     */
    private static MapSqlParameterSource bind(TenantScope scope, Map<String, ?> values) {
        return ScopedWhere.of(scope).bindNamed(values);
    }
}
