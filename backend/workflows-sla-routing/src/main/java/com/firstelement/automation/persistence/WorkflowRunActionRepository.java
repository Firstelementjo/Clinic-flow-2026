package com.firstelement.automation.persistence;

import com.firstelement.crm.persistence.JsonCodec;
import com.firstelement.security.query.ScopedWhere;
import com.firstelement.security.query.TenantScope;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code workflow_run_actions}: the append-only execution history.
 *
 * <h2>Append-only means append-only</h2>
 *
 * <p>The table has no {@code updatedAt} and no mutable status
 * ({@code V20261005020000}: "Same rule and same reason as Phase 5a's {@code job_attempts}, and each
 * migration's own guard <b>fails if an {@code updatedAt} ever appears</b>"). A retry is a <b>new
 * row</b> with the next {@code attemptNumber}, and a replay is a new row with {@code isReplay} and
 * {@code replayOfId}. There is no UPDATE statement in this class, and there cannot be one.
 *
 * <h2>What makes a decision explainable</h2>
 *
 * <p>{@code inputSnapshot} is the acceptance gate: "the decision records its inputs." A routing choice
 * writes the requirement, the per-stage survivor counts and the winner's workload there; an escalation
 * writes the scores and the matched rule. The attempt number therefore comes from
 * {@link #nextAttemptNumber} rather than from an expression inside the INSERT: the caller has to know
 * the number it is about to use, and the unique index on
 * {@code ("organizationId","workflowRunId","actionKey","attemptNumber")} turns a duplicate into a
 * conflict rather than a double-counted history.
 *
 * <h2>One attempt, one row</h2>
 *
 * <ul>
 *   <li><b>In-flight</b> — {@code finishedAt} and {@code durationMs} both null. The
 *       {@code workflow_run_actions_finished_check} requires them together, so "started but not
 *       finished" is expressible and "finished with no duration" is not.</li>
 *   <li><b>Terminal</b> — {@code SUCCEEDED}, {@code FAILED}, {@code SKIPPED} or {@code RETRYABLE}, with
 *       a finish time and a duration, and an error code/message on the failure outcomes.</li>
 * </ul>
 */
@Repository
public class WorkflowRunActionRepository {

    /** One action row. */
    public record ActionRow(UUID id,
                            UUID organizationId,
                            UUID projectId,
                            UUID workflowRunId,
                            String actionKey,
                            String actionType,
                            int attemptNumber,
                            String outcome,
                            String inputSnapshot,
                            String resultSnapshot,
                            UUID actorUserId,
                            String actorPrincipal,
                            UUID outboxEventId,
                            LocalDateTime startedAt,
                            LocalDateTime finishedAt,
                            Integer durationMs,
                            String errorCode,
                            String errorMessage,
                            boolean replay,
                            UUID replayOfId,
                            String correlationId) {
    }

    private static final RowMapper<ActionRow> ACTION = (rs, rowNum) -> new ActionRow(
        rs.getObject("id", UUID.class),
        rs.getObject("organizationId", UUID.class),
        rs.getObject("projectId", UUID.class),
        rs.getObject("workflowRunId", UUID.class),
        rs.getString("actionKey"),
        rs.getString("actionType"),
        rs.getInt("attemptNumber"),
        rs.getString("outcome"),
        rs.getString("inputSnapshot"),
        rs.getString("resultSnapshot"),
        rs.getObject("actorUserId", UUID.class),
        rs.getString("actorPrincipal"),
        rs.getObject("outboxEventId", UUID.class),
        rs.getObject("startedAt", LocalDateTime.class),
        rs.getObject("finishedAt", LocalDateTime.class),
        rs.getObject("durationMs", Integer.class),
        rs.getString("errorCode"),
        rs.getString("errorMessage"),
        rs.getBoolean("isReplay"),
        rs.getObject("replayOfId", UUID.class),
        rs.getString("correlationId"));

    private static final String COLUMNS = """
        select a."id", a."organizationId", a."projectId", a."workflowRunId", a."actionKey",
               a."actionType", a."attemptNumber", a."outcome", a."inputSnapshot", a."resultSnapshot",
               a."actorUserId", a."actorPrincipal", a."outboxEventId", a."startedAt", a."finishedAt",
               a."durationMs", a."errorCode", a."errorMessage", a."isReplay", a."replayOfId",
               a."correlationId"
          from workflow_run_actions a
        """;

    private static final String INSERT = """
        insert into workflow_run_actions
            ("id", "organizationId", "projectId", "workflowRunId", "actionKey", "actionType",
             "attemptNumber", "outcome", "inputSnapshot", "resultSnapshot", "actorUserId",
             "actorPrincipal", "outboxEventId", "startedAt", "finishedAt", "durationMs",
             "errorCode", "errorMessage", "isReplay", "replayOfId", "correlationId", "createdAt")
        values (:id, :__fe_tenant_organization_id, :__fe_tenant_project_id, :runId, :actionKey,
                :actionType, :attemptNumber, cast(:outcome as "WorkflowActionOutcome"),
                cast(:inputSnapshot as jsonb), cast(:resultSnapshot as jsonb), :actorUserId,
                :actorPrincipal, :outboxEventId, :startedAt, :finishedAt, :durationMs,
                :errorCode, :errorMessage, :replay, :replayOfId, :correlationId,
                (now() AT TIME ZONE 'UTC'))
        """;

    private static final String NEXT_ATTEMPT = """
        select coalesce(max(a."attemptNumber"), 0) + 1
          from workflow_run_actions a
         where a."organizationId" = :%s
           and a."projectId" = :%s
           and a."workflowRunId" = :runId
           and a."actionKey" = :actionKey
        """;

    private static final String TIMELINE = """
        %s
         where a."organizationId" = :%s
           and a."projectId" = :%s
           and a."workflowRunId" = :runId
         order by a."startedAt", a."attemptNumber"
        """;

    private static final String LATEST_FOR_ACTION = """
        %s
         where a."organizationId" = :%s
           and a."projectId" = :%s
           and a."workflowRunId" = :runId
           and a."actionKey" = :actionKey
         order by a."attemptNumber" desc
         limit 1
        """;

    private final NamedParameterJdbcTemplate jdbc;
    private final JsonCodec json;

    public WorkflowRunActionRepository(NamedParameterJdbcTemplate jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** The next attempt number for an action, read under the run's scope. */
    public int nextAttemptNumber(TenantScope scope, UUID runId, String actionKey) {
        String sql = NEXT_ATTEMPT.formatted(ScopedWhere.ORGANIZATION_PARAM, ScopedWhere.PROJECT_PARAM);
        Integer next = jdbc.queryForObject(sql, bind(scope, Map.of("runId", runId,
            "actionKey", actionKey)), Integer.class);
        return next == null ? 1 : next;
    }

    /**
     * Appends one action row.
     *
     * <p>The caller supplies the whole attempt record; there is no partial-update path, by design.
     *
     * @param outcome    one of {@code WorkflowActionOutcome}; {@code RETRYABLE} must carry both an
     *                   error code and a message, which the table CHECK enforces
     * @param finishedAt and {@code durationMs} together, or neither
     * @param replayOfId for a replay; null otherwise
     */
    public UUID append(TenantScope scope, UUID actionId, UUID runId, String actionKey,
                       String actionType, int attemptNumber, String outcome,
                       Object inputSnapshot, Object resultSnapshot, UUID actorUserId,
                       String actorPrincipal, UUID outboxEventId, LocalDateTime startedAt,
                       LocalDateTime finishedAt, Integer durationMs, String errorCode,
                       String errorMessage, boolean replay, UUID replayOfId, String correlationId) {
        // A LinkedHashMap rather than Map.of: Map.of rejects a null value, and several of this
        // record's columns are legitimately null (no actor on a machine action, no duration on an
        // in-flight row). The null types are then named rather than left to the driver's guess.
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", actionId);
        values.put("runId", runId);
        values.put("actionKey", actionKey);
        values.put("actionType", actionType);
        values.put("attemptNumber", attemptNumber);
        values.put("outcome", outcome);
        values.put("inputSnapshot", json.write(inputSnapshot == null ? Map.of() : inputSnapshot));
        values.put("resultSnapshot", json.write(resultSnapshot == null ? Map.of() : resultSnapshot));
        values.put("startedAt", startedAt);
        values.put("replay", replay);

        MapSqlParameterSource parameters = bind(scope, values);
        parameters.addValue("actorUserId", actorUserId, java.sql.Types.OTHER);
        parameters.addValue("actorPrincipal", actorPrincipal, java.sql.Types.VARCHAR);
        parameters.addValue("outboxEventId", outboxEventId, java.sql.Types.OTHER);
        parameters.addValue("finishedAt", finishedAt, java.sql.Types.TIMESTAMP);
        parameters.addValue("durationMs", durationMs, java.sql.Types.INTEGER);
        parameters.addValue("errorCode", errorCode, java.sql.Types.VARCHAR);
        parameters.addValue("errorMessage", errorMessage, java.sql.Types.VARCHAR);
        parameters.addValue("replayOfId", replayOfId, java.sql.Types.OTHER);
        parameters.addValue("correlationId", correlationId, java.sql.Types.VARCHAR);

        jdbc.update(INSERT, parameters);
        return actionId;
    }

    /** One run's action timeline, in order. Canonical statement section 11c. */
    public List<ActionRow> timelineForRun(TenantScope scope, UUID runId) {
        String sql = TIMELINE.formatted(COLUMNS, ScopedWhere.ORGANIZATION_PARAM,
            ScopedWhere.PROJECT_PARAM);
        return jdbc.query(sql, bind(scope, Map.of("runId", runId)), ACTION);
    }

    /** The latest row for one action key, which is what a replay must name. */
    public Optional<ActionRow> latestForAction(TenantScope scope, UUID runId, String actionKey) {
        String sql = LATEST_FOR_ACTION.formatted(COLUMNS, ScopedWhere.ORGANIZATION_PARAM,
            ScopedWhere.PROJECT_PARAM);
        return jdbc.query(sql, bind(scope, Map.of("runId", runId, "actionKey", actionKey)), ACTION)
            .stream().findFirst();
    }

    /**
     * Binds caller values together with the scope's tenant parameters.
     *
     * <p>{@link ScopedWhere#bindNamed} is the only sanctioned way: it writes the tenant values last and
     * refuses a caller parameter of the reserved name.
     */
    private static MapSqlParameterSource bind(TenantScope scope, Map<String, ?> values) {
        return ScopedWhere.of(scope).bindNamed(values);
    }
}