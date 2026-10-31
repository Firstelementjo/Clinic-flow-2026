package com.firstelement.automation.persistence;

import com.firstelement.security.query.ScopedWhere;
import com.firstelement.security.query.TenantScope;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code workflow_runs}: one execution of one version by one named principal.
 *
 * <h2>Start is a loop-prevention write, not an insert</h2>
 *
 * <p>{@code workflow-sla-statements.sql} §4 is the contract and this method is its statement:
 * {@code INSERT ... ON CONFLICT ("organizationId","triggerInstanceId") DO NOTHING RETURNING id}.
 * "0 rows = this trigger instance already has a run. Do not start a second one." A caller therefore
 * cannot start two runs for one trigger instance even under concurrency, because the second one
 * loses on the unique index rather than on application ordering.
 *
 * <h2>The tenant comes from the row, not the payload</h2>
 *
 * <p>{@link #start} takes the scope it is given and binds {@code organizationId} and
 * {@code projectId} from it. A job that carries another tenant's ids in its payload cannot get
 * there: the caller builds the scope from the run's own tenant
 * ({@code WorkflowRunService#runTenantContext}), and the composite foreign keys
 * {@code workflow_runs_version_tenant_fkey} and the conversation FK refuse a cross-tenant
 * {@code conversationId} even if a caller tried.
 */
@Repository
public class WorkflowRunRepository {

    private static final String UTC_NOW = "(now() AT TIME ZONE 'UTC')";

    /** One run row. */
    public record RunRow(UUID id,
                         UUID organizationId,
                         UUID projectId,
                         UUID workflowDefinitionId,
                         UUID workflowVersionId,
                         UUID conversationId,
                         String triggerInstanceId,
                         String status,
                         UUID principalUserId,
                         String capabilityScope,
                         int loopDepth,
                         int maxLoopDepth,
                         LocalDateTime startedAt,
                         LocalDateTime finishedAt,
                         LocalDateTime abortRequestedAt,
                         LocalDateTime abortedAt,
                         UUID abortedByUserId,
                         String abortReason,
                         String failureReason,
                         String correlationId) {
    }

    private static final RowMapper<RunRow> RUN = (rs, rowNum) -> new RunRow(
        rs.getObject("id", UUID.class),
        rs.getObject("organizationId", UUID.class),
        rs.getObject("projectId", UUID.class),
        rs.getObject("workflowDefinitionId", UUID.class),
        rs.getObject("workflowVersionId", UUID.class),
        rs.getObject("conversationId", UUID.class),
        rs.getString("triggerInstanceId"),
        rs.getString("status"),
        rs.getObject("principalUserId", UUID.class),
        rs.getString("capabilityScope"),
        rs.getInt("loopDepth"),
        rs.getInt("maxLoopDepth"),
        rs.getObject("startedAt", LocalDateTime.class),
        rs.getObject("finishedAt", LocalDateTime.class),
        rs.getObject("abortRequestedAt", LocalDateTime.class),
        rs.getObject("abortedAt", LocalDateTime.class),
        rs.getObject("abortedByUserId", UUID.class),
        rs.getString("abortReason"),
        rs.getString("failureReason"),
        rs.getString("correlationId"));

    private static final String SELECT = """
        select r."id", r."organizationId", r."projectId", r."workflowDefinitionId",
               r."workflowVersionId", r."conversationId", r."triggerInstanceId", r."status",
               r."principalUserId", r."capabilityScope", r."loopDepth", r."maxLoopDepth",
               r."startedAt", r."finishedAt", r."abortRequestedAt", r."abortedAt",
               r."abortedByUserId", r."abortReason", r."failureReason", r."correlationId"
          from workflow_runs r
        """;

    private final NamedParameterJdbcTemplate jdbc;

    public WorkflowRunRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Starts a run, or returns empty because this trigger instance already has one.
     *
     * <p>The {@code capabilityScope} written here is the <b>version's</b>, copied: "a later widening
     * of the version's capability set must not retroactively enlarge what this run was allowed to
     * do."
     *
     * @param loopDepth the depth of this run in its trigger chain; the table CHECK refuses a depth
     *                  greater than {@code maxLoopDepth}, so a runaway trigger chain fails at the
     *                  write rather than in the executor
     */
    public Optional<UUID> start(TenantScope scope, UUID runId, UUID definitionId, UUID versionId,
                                UUID conversationId, String triggerInstanceId, UUID principalUserId,
                                String capabilityScopeJson, int loopDepth, int maxLoopDepth,
                                String correlationId) {
        Map<String, Object> values = new java.util.LinkedHashMap<>();
        values.put("id", runId);
        values.put("definitionId", definitionId);
        values.put("versionId", versionId);
        values.put("conversationId", conversationId);
        values.put("triggerInstanceId", triggerInstanceId);
        values.put("principalUserId", principalUserId);
        values.put("capabilityScope", capabilityScopeJson);
        values.put("loopDepth", loopDepth);
        values.put("maxLoopDepth", maxLoopDepth);
        MapSqlParameterSource parameters = bind(scope, values);
        parameters.addValue("correlationId", correlationId, java.sql.Types.VARCHAR);
        List<UUID> inserted = jdbc.query("""
            insert into workflow_runs
                ("id", "organizationId", "projectId", "workflowDefinitionId", "workflowVersionId",
                 "conversationId", "triggerInstanceId", "status", "principalUserId",
                 "capabilityScope", "loopDepth", "maxLoopDepth", "startedAt", "createdAt", "updatedAt",
                 "correlationId")
            values (:id, :__fe_tenant_organization_id, :__fe_tenant_project_id, :definitionId,
                    :versionId, :conversationId, :triggerInstanceId,
                    cast('RUNNING' as "WorkflowRunStatus"), :principalUserId,
                    cast(:capabilityScope as jsonb), :loopDepth, :maxLoopDepth, %s, %s, %s,
                    :correlationId)
            on conflict ("organizationId", "triggerInstanceId") do nothing
            returning "id"
            """.formatted(UTC_NOW, UTC_NOW, UTC_NOW), parameters,
            (rs, rowNum) -> rs.getObject("id", UUID.class));
        return inserted.stream().findFirst();
    }

    public Optional<RunRow> findRun(TenantScope scope, UUID runId) {
        String sql = SELECT + " where r.\"organizationId\" = :" + ScopedWhere.ORGANIZATION_PARAM
            + " and r.\"id\" = :id";
        return jdbc.query(sql, bind(scope, Map.of("id", runId)), RUN).stream().findFirst();
    }

    /** Whether this trigger instance already has a run in this tenant. */
    public boolean existsForTriggerInstance(TenantScope scope, String triggerInstanceId) {
        String sql = """
            select count(*) from workflow_runs r
             where r."organizationId" = :%s and r."triggerInstanceId" = :triggerInstanceId
            """.formatted(ScopedWhere.ORGANIZATION_PARAM);
        Integer count = jdbc.queryForObject(sql, bind(scope, Map.of(
            "triggerInstanceId", triggerInstanceId)), Integer.class);
        return count != null && count > 0;
    }

    /**
     * The runs in flight for a conversation, oldest first.
     *
     * <p>Used by the human-takeover path to find what it has to abort, and by the AI-send path to
     * find what it must check. Tenant-scoped on both columns, because a conversation id alone proves
     * nothing.
     */
    public List<RunRow> runningRunsForConversation(TenantScope scope, UUID conversationId) {
        String sql = SELECT + """
             where r."organizationId" = :%s
               and r."projectId" = :%s
               and r."conversationId" = :conversationId
               and r."status" = cast('RUNNING' as "WorkflowRunStatus")
             order by r."startedAt", r."id"
            """.formatted(ScopedWhere.ORGANIZATION_PARAM, ScopedWhere.PROJECT_PARAM);
        return jdbc.query(sql, bind(scope, Map.of("conversationId", conversationId)), RUN);
    }

    /**
     * Records the takeover REQUEST. Idempotent: a second request updates 0 rows
     * ({@code workflow-sla-statements.sql} §6a).
     *
     * @return the number of runs now marked; 0 when nothing was in flight
     */
    public int requestAbort(TenantScope scope, UUID conversationId, UUID humanUserId, String reason) {
        String sql = """
            update workflow_runs r
               set "abortRequestedAt" = %s,
                   "abortedByUserId" = :humanUserId,
                   "abortReason" = :reason,
                   "updatedAt" = %s
             where r."organizationId" = :%s
               and r."projectId" = :%s
               and r."conversationId" = :conversationId
               and r."status" = cast('RUNNING' as "WorkflowRunStatus")
               and r."abortRequestedAt" is null
            """.formatted(UTC_NOW, UTC_NOW, ScopedWhere.ORGANIZATION_PARAM, ScopedWhere.PROJECT_PARAM);
        return jdbc.update(sql, bind(scope, Map.of(
            "conversationId", conversationId, "humanUserId", humanUserId, "reason", reason)));
    }

    /**
     * Confirms the abort once the in-flight work has actually been dropped.
     *
     * <p>§6b. The predicate requires a prior request, so a run cannot claim it was cancelled when
     * nothing asked it to be.
     */
    public int confirmAbort(TenantScope scope, UUID runId) {
        String sql = """
            update workflow_runs r
               set "status" = cast('TAKEOVER' as "WorkflowRunStatus"),
                   "abortedAt" = %s,
                   "finishedAt" = %s,
                   "updatedAt" = %s
             where r."organizationId" = :%s
               and r."id" = :id
               and r."abortRequestedAt" is not null
               and r."abortedAt" is null
               and r."status" = cast('RUNNING' as "WorkflowRunStatus")
            """.formatted(UTC_NOW, UTC_NOW, UTC_NOW, ScopedWhere.ORGANIZATION_PARAM);
        return jdbc.update(sql, bind(scope, Map.of("id", runId)));
    }

    /** Finishes a run as SUCCEEDED, only while it is still RUNNING. */
    public int succeed(TenantScope scope, UUID runId) {
        String sql = """
            update workflow_runs r
               set "status" = cast('SUCCEEDED' as "WorkflowRunStatus"),
                   "finishedAt" = %s,
                   "updatedAt" = %s
             where r."organizationId" = :%s
               and r."id" = :id
               and r."status" in (cast('RUNNING' as "WorkflowRunStatus"),
                                  cast('WAITING' as "WorkflowRunStatus"))
            """.formatted(UTC_NOW, UTC_NOW, ScopedWhere.ORGANIZATION_PARAM);
        return jdbc.update(sql, bind(scope, Map.of("id", runId)));
    }

    /** Finishes a run as FAILED with a reason; the CHECK refuses a FAILED run with no reason. */
    public int fail(TenantScope scope, UUID runId, String reason) {
        String sql = """
            update workflow_runs r
               set "status" = cast('FAILED' as "WorkflowRunStatus"),
                   "failureReason" = :reason,
                   "finishedAt" = %s,
                   "updatedAt" = %s
             where r."organizationId" = :%s
               and r."id" = :id
               and r."status" in (cast('RUNNING' as "WorkflowRunStatus"),
                                  cast('WAITING' as "WorkflowRunStatus"))
            """.formatted(UTC_NOW, UTC_NOW, ScopedWhere.ORGANIZATION_PARAM);
        return jdbc.update(sql, bind(scope, Map.of("id", runId, "reason", reason)));
    }

    /**
     * Whether the run may still perform an AI send.
     *
     * <p>This is the predicate the phase gate turns on. It is a SELECT rather than a claim because
     * the claim is the conversation-mode lock taken by {@code AiSendService} in the same
     * transaction: the run-level half of the guard is "still RUNNING and nobody asked for an
     * abort", and the conversation half is "still in AI mode". Both are re-read at confirm time.
     */
    public Optional<RunRow> findSendableRun(TenantScope scope, UUID runId) {
        String sql = SELECT + """
             where r."organizationId" = :%s
               and r."projectId" = :%s
               and r."id" = :id
               and r."status" = cast('RUNNING' as "WorkflowRunStatus")
               and r."abortRequestedAt" is null
               and r."abortedAt" is null
            """.formatted(ScopedWhere.ORGANIZATION_PARAM, ScopedWhere.PROJECT_PARAM);
        return jdbc.query(sql, bind(scope, Map.of("id", runId)), RUN).stream().findFirst();
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
