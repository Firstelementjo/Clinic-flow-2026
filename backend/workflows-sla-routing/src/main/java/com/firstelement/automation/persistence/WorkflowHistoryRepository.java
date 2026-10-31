package com.firstelement.automation.persistence;

import com.firstelement.crm.persistence.JsonCodec;
import com.firstelement.security.query.ScopedWhere;
import com.firstelement.security.query.TenantScope;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The routing-explanation read model, the run history, and the collision counter.
 *
 * <h2>Why there is no explanations table</h2>
 *
 * <p>Recorded in {@code V20261005020000} and repeated here because this class is where that decision
 * becomes visible: an explanation <em>is</em> {@code (workflow_run_actions.inputSnapshot,
 * resultSnapshot)} joined to {@code (workflow_versions.capabilityScope, contentHash)}, so
 * materialising it would create a second copy of the decision that can disagree with the first, its
 * lifetime would have to be the decision's lifetime, FR-10.6 redaction is easier when it is assembled
 * from already-redacted columns at read time, and "an explanation that could contradict the decision
 * it explains is worse than none, because it looks authoritative."
 *
 * <p>{@link #explainConversation} is {@code workflow-sla-statements.sql} section 8, including its
 * index path: {@code workflow_runs_organizationId_conversationId_idx} then
 * {@code workflow_run_actions_organizationId_runId_idx}.
 *
 * <h2>Ordering</h2>
 *
 * <p>{@code r."startedAt", a."startedAt", a."attemptNumber"} — timestamps then the attempt number.
 * Never an action <em>name</em>: an operator reordering action keys in a workflow version would
 * otherwise silently rewrite history order, which is the "no alphabetical rule ordering" hazard
 * applied to a read model.
 */
@Repository
public class WorkflowHistoryRepository {

    /** One explained step: the run, the version in force, and one action attempt. */
    public record ExplanationRow(UUID runId,
                                 UUID workflowVersionId,
                                 int versionNumber,
                                 String capabilityScopeInForce,
                                 String versionContentHash,
                                 UUID principalUserId,
                                 LocalDateTime runStartedAt,
                                 LocalDateTime runFinishedAt,
                                 String runStatus,
                                 String actionKey,
                                 String actionType,
                                 int attemptNumber,
                                 String outcome,
                                 Object inputSnapshot,
                                 Object resultSnapshot,
                                 UUID actorUserId,
                                 String actorPrincipal,
                                 LocalDateTime actionStartedAt,
                                 LocalDateTime actionFinishedAt,
                                 String errorCode,
                                 String errorMessage) {
    }

    /** One row of the operator's run list. */
    public record RunSummaryRow(UUID runId,
                                String workflowName,
                                int versionNumber,
                                String status,
                                UUID principalUserId,
                                LocalDateTime startedAt,
                                LocalDateTime finishedAt,
                                String failureReason) {
    }

    /** A collision outcome with its first and last sighting. Canonical statement section 9. */
    public record CollisionTally(String outcome, int incidents, LocalDateTime firstSeen,
                                 LocalDateTime lastSeen) {
    }

    private static final String EXPLANATION = """
        select r."id" as run_id, r."workflowVersionId", v."versionNumber",
               v."capabilityScope" as capability_scope_in_force, v."contentHash",
               r."principalUserId", r."startedAt" as run_started_at,
               r."finishedAt" as run_finished_at, r."status" as run_status,
               a."actionKey", a."actionType", a."attemptNumber", a."outcome",
               a."inputSnapshot", a."resultSnapshot", a."actorUserId", a."actorPrincipal",
               a."startedAt" as action_started_at, a."finishedAt" as action_finished_at,
               a."errorCode", a."errorMessage"
          from workflow_runs r
          join workflow_versions v on v."id" = r."workflowVersionId"
          left join workflow_run_actions a on a."workflowRunId" = r."id"
         where r."organizationId" = :__fe_tenant_organization_id
           and r."conversationId" = :conversationId
         order by r."startedAt", a."startedAt", a."attemptNumber"
         limit :limit
        """;

    private static final String RUN_SUMMARY = """
        select r."id" as run_id, d."name" as workflow_name, v."versionNumber",
               r."status", r."principalUserId", r."startedAt", r."finishedAt", r."failureReason"
          from workflow_runs r
          join workflow_definitions d on d."id" = r."workflowDefinitionId"
          join workflow_versions v on v."id" = r."workflowVersionId"
         where r."organizationId" = :__fe_tenant_organization_id
           and r."projectId" = :__fe_tenant_project_id
        """;

    private static final String VERSION_COUNTS = """
        select r."status", count(*) as occurrences
          from workflow_runs r
         where r."organizationId" = :__fe_tenant_organization_id
           and r."workflowVersionId" = :versionId
         group by r."status"
         order by r."status"
        """;

    private static final String COLLISION_TALLY = """
        select c."outcome", count(*) as incidents, min(c."observedAt") as first_seen,
               max(c."observedAt") as last_seen
          from collision_records c
         where c."organizationId" = :__fe_tenant_organization_id
           and c."projectId" = :__fe_tenant_project_id
           and c."observedAt" >= :since
         group by c."outcome"
         order by c."outcome"
         limit :limit
        """;

    private static final String FAILURE_DASHBOARD = """
        select a."actionType" as action_type, a."outcome" as outcome, count(*) as occurrences
          from workflow_run_actions a
         where a."organizationId" = :__fe_tenant_organization_id
           and a."outcome" in (cast('FAILED' as "WorkflowActionOutcome"),
                               cast('RETRYABLE' as "WorkflowActionOutcome"))
           and a."createdAt" >= :since
         group by a."actionType", a."outcome"
         order by a."actionType", a."outcome"
         limit :limit
        """;

    private final NamedParameterJdbcTemplate jdbc;
    private final JsonCodec json;

    public WorkflowHistoryRepository(NamedParameterJdbcTemplate jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * The routing explanation for a conversation, assembled at read time.
     *
     * <p>One row per action attempt, so a run with no actions still yields its run row through the
     * {@code LEFT JOIN}.
     */
    public List<ExplanationRow> explainConversation(TenantScope scope, UUID conversationId, int limit) {
        MapSqlParameterSource parameters = bind(scope, Map.of("conversationId", conversationId,
            "limit", limit));
        return jdbc.query(EXPLANATION, parameters, (rs, rowNum) -> new ExplanationRow(
            rs.getObject("run_id", UUID.class),
            rs.getObject("workflowVersionId", UUID.class),
            rs.getInt("versionNumber"),
            rs.getString("capability_scope_in_force"),
            rs.getString("contentHash"),
            rs.getObject("principalUserId", UUID.class),
            rs.getObject("run_started_at", LocalDateTime.class),
            rs.getObject("run_finished_at", LocalDateTime.class),
            rs.getString("run_status"),
            rs.getString("actionKey"),
            rs.getString("actionType"),
            rs.getInt("attemptNumber"),
            rs.getString("outcome"),
            json.readMap(rs.getString("inputSnapshot")),
            json.readMap(rs.getString("resultSnapshot")),
            rs.getObject("actorUserId", UUID.class),
            rs.getString("actorPrincipal"),
            rs.getObject("action_started_at", LocalDateTime.class),
            rs.getObject("action_finished_at", LocalDateTime.class),
            rs.getString("errorCode"),
            rs.getString("errorMessage")));
    }

    /**
     * Section 11a: the operator's run list, newest first.
     *
     * @param statuses the statuses to include; empty means every status
     */
    /**
     * The statuses this module will filter by, mapped to their PostgreSQL enum labels.
 *
 * <p>An allow-list rather than a passthrough, and that is a correctness property rather than
 * tidiness: the filter is rendered into the statement as a typed array literal (see
 * {@link #enumArray}), so an unrecognised value would otherwise become a literal the database
 * refuses. Refusing it here names the offending value in the message instead.
 */
private static final Map<String, String> RUN_STATUS_LABELS = Map.of(
    "RUNNING", "RUNNING",
    "WAITING", "WAITING",
    "SUCCEEDED", "SUCCEEDED",
    "FAILED", "FAILED",
    "ABORTED", "ABORTED",
    "TAKEOVER", "TAKEOVER");

/**
 * Renders values as a PostgreSQL array literal of the given enum type.
 *
 * <p>Why not a bound {@code java.sql.Array} or a {@code List}: the driver renders a {@code List} as
 * its own {@code toString()} ("[RUNNING]"), which PostgreSQL rejects as a malformed array literal,
 * and building a {@code java.sql.Array} requires holding a live {@code Connection} across a call
 * that this class has no business owning. A literal composed from allow-listed labels has neither
 * problem, and because every label is a fixed token there is nothing to escape.
 */
private static String enumArray(List<String> statuses, Map<String, String> labels) {
    StringBuilder literal = new StringBuilder("{");
    for (String status : statuses) {
        String label = labels.get(status);
        if (label == null) {
            throw new IllegalArgumentException(
                "'" + status + "' is not a WorkflowRunStatus this module can filter by; expected one of "
                    + new java.util.TreeSet<>(labels.keySet()));
        }
        if (literal.length() > 1) {
            literal.append(',');
        }
        literal.append('"').append(label).append('"');
    }
    return literal.append('}').toString();
}

/**
 * Section 11a: the operator's run list, newest first.
 *
 * @param statuses the statuses to include; empty means every status
 */
public List<RunSummaryRow> runHistory(TenantScope scope, UUID projectId, List<String> statuses,
                                          int pageSize) {
        StringBuilder sql = new StringBuilder(RUN_SUMMARY);
        Map<String, Object> values = new LinkedHashMap<>();
        if (statuses != null && !statuses.isEmpty()) {
            sql.append(" and r.\"status\" = any('")
                .append(enumArray(statuses, RUN_STATUS_LABELS))
                .append("'::\"WorkflowRunStatus\"[])\n");
        }
        sql.append(" order by r.\"createdAt\" desc limit :pageSize\n");
        values.put("pageSize", pageSize);
        return jdbc.query(sql.toString(), bind(scope, values), (rs, rowNum) -> new RunSummaryRow(
            rs.getObject("run_id", UUID.class),
            rs.getString("workflow_name"),
            rs.getInt("versionNumber"),
            rs.getString("status"),
            rs.getObject("principalUserId", UUID.class),
            rs.getObject("startedAt", LocalDateTime.class),
            rs.getObject("finishedAt", LocalDateTime.class),
            rs.getString("failureReason")));
    }

    /**
     * Section 11b: how one version behaved, before rolling it back.
     *
     * <p>This is the query a rollback decision is supposed to be made from.
     */
    public Map<String, Integer> statusCountsForVersion(TenantScope scope, UUID versionId) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (var row : jdbc.query(VERSION_COUNTS, bind(scope, Map.of("versionId", versionId)),
            (rs, rowNum) -> Map.entry(rs.getString("status"), rs.getInt("occurrences")))) {
            counts.put(row.getKey(), row.getValue());
        }
        return counts;
    }

    /** Section 9: how often a human won, per outcome. The phase-6 acceptance-gate counter. */
    public List<CollisionTally> collisionTally(TenantScope scope, LocalDateTime since, int limit) {
        return jdbc.query(COLLISION_TALLY, bind(scope, Map.of("since", since, "limit", limit)),
            (rs, rowNum) -> new CollisionTally(rs.getString("outcome"), rs.getInt("incidents"),
                rs.getObject("first_seen", LocalDateTime.class),
                rs.getObject("last_seen", LocalDateTime.class)));
    }

    /** Section 11d: the failure dashboard, tenant-scoped. */
    public List<Map<String, Object>> failureDashboard(TenantScope scope, LocalDateTime since,
                                                      int limit) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (var row : jdbc.query(FAILURE_DASHBOARD, bind(scope, Map.of("since", since, "limit", limit)),
            (rs, rowNum) -> {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("actionType", rs.getString("action_type"));
                entry.put("outcome", rs.getString("outcome"));
                entry.put("occurrences", rs.getInt("occurrences"));
                return entry;
            })) {
            rows.add(row);
        }
        return rows;
    }

    private static MapSqlParameterSource bind(TenantScope scope, Map<String, ?> values) {
        return ScopedWhere.of(scope).bindNamed(values);
    }
}