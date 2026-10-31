package com.firstelement.automation.persistence;

import com.firstelement.automation.domain.RuleCondition;
import com.firstelement.automation.domain.ScoreSignal;
import com.firstelement.crm.persistence.JsonCodec;
import com.firstelement.security.query.ScopedWhere;
import com.firstelement.security.query.TenantScope;
import java.time.LocalDateTime;
import java.util.ArrayList;
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
 * {@code escalation_rules} and {@code escalations}: the rule store and the escalation record.
 *
 * <h2>Rules are read through their jsonb, strictly</h2>
 *
 * <p>{@code escalation_rules.conditions} is a jsonb array of
 * {@code {signal, operator, value}} objects. {@link #conditions} parses it and drops anything
 * unrecognised, but a rule whose conditions all dropped out is reported as <b>invalid</b> rather
 * than as "no conditions": an empty {@code ALL} rule matches vacuously, which is how an unfinished
 * draft escalates every customer turn. The rule row therefore carries the parse result, not just the
 * conditions, and the service refuses to evaluate an invalid rule.
 *
 * <h2>{@code scoreSnapshot} is a snapshot, not a join</h2>
 *
 * <p>Legacy: "Snapshot, so editing a rule later cannot rewrite why this fired." The snapshot holds
 * the scores and the rule's identity, so a handover summary stays true for the conversation it was
 * written for.
 */
@Repository
public class EscalationRepository {

    /** One rule row with its conditions already parsed. */
    public record RuleRow(UUID id,
                          String name,
                          boolean enabled,
                          int priority,
                          String combinator,
                          String department,
                          String conditionsJson,
                          List<Map<String, Object>> conditions) {
    }

    /** One escalation row. */
    public record EscalationRow(UUID id,
                                UUID conversationId,
                                String ruleId,
                                String trigger,
                                String status,
                                String reason,
                                String summary,
                                String intent,
                                String department,
                                UUID assignedEmployeeId,
                                LocalDateTime createdAt) {
    }
    /**
     * The rule row mapper.
     *
     * <p>An instance method rather than a static constant because parsing the {@code conditions}
     * jsonb needs the injected {@link JsonCodec}, and a static field cannot reference an instance.
     */
    private RowMapper<RuleRow> rule() {
        return (rs, rowNum) -> new RuleRow(
            rs.getObject("id", UUID.class),
            rs.getString("name"),
            rs.getBoolean("isEnabled"),
            rs.getInt("priority"),
            rs.getString("combinator"),
            rs.getString("department"),
            rs.getString("conditions"),
            parseConditions(rs.getString("conditions"), json));
    }

    private static final RowMapper<EscalationRow> ESCALATION = (rs, rowNum) -> new EscalationRow(
        rs.getObject("id", UUID.class),
        rs.getObject("conversationId", UUID.class),
        rs.getObject("ruleId", String.class),
        rs.getString("trigger"),
        rs.getString("status"),
        rs.getString("reason"),
        rs.getString("summary"),
        rs.getString("intent"),
        rs.getString("department"),
        rs.getObject("assignedEmployeeId", UUID.class),
        rs.getObject("createdAt", LocalDateTime.class));

    private final NamedParameterJdbcTemplate jdbc;
    private final JsonCodec json;

    public EscalationRepository(NamedParameterJdbcTemplate jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Enabled rules in explicit priority order. The ordering is a column, never a name. */
    public List<RuleRow> enabledRules(TenantScope scope, UUID projectId, int limit) {
        String sql = """
            select r."id", r."name", r."isEnabled", r."priority", r."combinator", r."department",
                   r."conditions"
              from escalation_rules r
             where r."organizationId" = :%s
               and r."projectId" = :%s
               and r."isEnabled"
             order by r."priority", r."id"
             limit :limit
            """.formatted(ScopedWhere.ORGANIZATION_PARAM, ScopedWhere.PROJECT_PARAM);
        return jdbc.query(sql, bind(scope, Map.of("limit", limit)), rule());
    }

    /** Every rule including the disabled ones, for the admin read model. */
    public List<RuleRow> allRules(TenantScope scope, UUID projectId, int limit) {
        String sql = """
            select r."id", r."name", r."isEnabled", r."priority", r."combinator", r."department",
                   r."conditions"
              from escalation_rules r
             where r."organizationId" = :%s
               and r."projectId" = :%s
             order by r."priority", r."id"
             limit :limit
            """.formatted(ScopedWhere.ORGANIZATION_PARAM, ScopedWhere.PROJECT_PARAM);
        return jdbc.query(sql, bind(scope, Map.of("limit", limit)), rule());
    }

    public Optional<RuleRow> findRule(TenantScope scope, UUID ruleId) {
        String sql = """
            select r."id", r."name", r."isEnabled", r."priority", r."combinator", r."department",
                   r."conditions"
              from escalation_rules r
             where r."organizationId" = :%s and r."id" = :id
            """.formatted(ScopedWhere.ORGANIZATION_PARAM);
        return jdbc.query(sql, bind(scope, Map.of("id", ruleId)), rule()).stream().findFirst();
    }

    /**
     * Records an escalation.
     *
     * @param trigger one of {@code EscalationTrigger}; a value outside it is refused by the cast
     * @param scoreSnapshot the scores as they were, so a later rule edit cannot rewrite the reason
     */
    public UUID insertEscalation(TenantScope scope, UUID escalationId, UUID conversationId,
                                 UUID ruleId, String trigger, String reason, String summary,
                                 String intent, String department, Object scoreSnapshot,
                                 LocalDateTime now) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", escalationId);
        values.put("conversationId", conversationId);
        values.put("trigger", trigger);
        values.put("reason", reason);
        values.put("summary", summary);
        values.put("intent", intent);
        values.put("department", department);
        values.put("scoreSnapshot", json.write(scoreSnapshot == null ? Map.of() : scoreSnapshot));
        values.put("now", now);

        MapSqlParameterSource parameters = bind(scope, values);
        parameters.addValue("ruleId", ruleId, java.sql.Types.OTHER);
        parameters.addValue("intent", intent, java.sql.Types.VARCHAR);
        parameters.addValue("department", department, java.sql.Types.VARCHAR);
        parameters.addValue("summary", summary, java.sql.Types.VARCHAR);

        jdbc.update("""
            insert into escalations
                ("id", "organizationId", "projectId", "conversationId", "ruleId", "trigger",
                 "status", "reason", "summary", "intent", "department", "scoreSnapshot", "createdAt")
            values (:id, :__fe_tenant_organization_id, :__fe_tenant_project_id, :conversationId,
                    :ruleId, cast(:trigger as "EscalationTrigger"),
                    cast('PENDING' as "EscalationStatus"), :reason, :summary, :intent, :department,
                    cast(:scoreSnapshot as jsonb), :now)
            """, parameters);
        return escalationId;
    }

    /** Puts an escalation back in {@code PENDING}, as the legacy router did when nobody qualified. */
    public int markPending(TenantScope scope, UUID escalationId) {
        String sql = """
            update escalations e
               set "status" = cast('PENDING' as "EscalationStatus")
             where e."organizationId" = :%s
               and e."projectId" = :%s
               and e."id" = :id
               and e."status" <> cast('RESOLVED' as "EscalationStatus")
            """.formatted(ScopedWhere.ORGANIZATION_PARAM, ScopedWhere.PROJECT_PARAM);
        return jdbc.update(sql, bind(scope, Map.of("id", escalationId)));
    }

    /** Marks an escalation ASSIGNED to an employee. */
    public int markAssigned(TenantScope scope, UUID escalationId, UUID employeeId, LocalDateTime at) {
        String sql = """
            update escalations e
               set "status" = cast('ASSIGNED' as "EscalationStatus"),
                   "assignedEmployeeId" = :employeeId,
                   "assignedAt" = :at
             where e."organizationId" = :%s
               and e."projectId" = :%s
               and e."id" = :id
            """.formatted(ScopedWhere.ORGANIZATION_PARAM, ScopedWhere.PROJECT_PARAM);
        return jdbc.update(sql, bind(scope, Map.of("id", escalationId, "employeeId", employeeId,
            "at", at)));
    }

    public Optional<EscalationRow> findEscalation(TenantScope scope, UUID escalationId) {
        String sql = """
            select e."id", e."conversationId", e."ruleId"::text as "ruleId", e."trigger", e."status",
                   e."reason", e."summary", e."intent", e."department", e."assignedEmployeeId",
                   e."createdAt"
              from escalations e
             where e."organizationId" = :%s
               and e."projectId" = :%s
               and e."id" = :id
            """.formatted(ScopedWhere.ORGANIZATION_PARAM, ScopedWhere.PROJECT_PARAM);
        return jdbc.query(sql, bind(scope, Map.of("id", escalationId)), ESCALATION)
            .stream().findFirst();
    }

    /** A conversation's escalations, newest first. */
    public List<EscalationRow> escalationsForConversation(TenantScope scope, UUID conversationId,
                                                          int limit) {
        String sql = """
            select e."id", e."conversationId", e."ruleId"::text as "ruleId", e."trigger", e."status",
                   e."reason", e."summary", e."intent", e."department", e."assignedEmployeeId",
                   e."createdAt"
              from escalations e
             where e."organizationId" = :%s
               and e."projectId" = :%s
               and e."conversationId" = :conversationId
             order by e."createdAt" desc
             limit :limit
            """.formatted(ScopedWhere.ORGANIZATION_PARAM, ScopedWhere.PROJECT_PARAM);
        return jdbc.query(sql, bind(scope, Map.of("conversationId", conversationId, "limit", limit)),
            ESCALATION);
    }

    /**
     * Stores a turn's scores.
     *
     * <p>The score history is kept per turn, as the legacy engine did: "Every customer turn is scored
     * and the scores are kept as history." The six normalized signals are the {@code scores} record's
     * columns; the cue map is {@code signals}; {@code aiConfidence} is null for a customer turn
     * because no AI run has produced one, which is exactly the case {@code RuleFacts} represents as
     * absent rather than zero.
     */
    public UUID insertScore(TenantScope scope, UUID scoreId, UUID conversationId, UUID messageId,
                            int frustration, int sentiment, int urgency, int risk,
                            int intentConfidence, Integer aiConfidence, String intent,
                            Map<String, Object> signals, LocalDateTime now) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", scoreId);
        values.put("conversationId", conversationId);
        values.put("messageId", messageId);
        values.put("frustration", frustration);
        values.put("sentiment", sentiment);
        values.put("urgency", urgency);
        values.put("risk", risk);
        values.put("intentConfidence", intentConfidence);
        values.put("aiConfidence", aiConfidence);
        values.put("intent", intent);
        values.put("evaluator", "heuristic");
        values.put("signals", json.write(signals == null ? Map.of() : signals));
        values.put("now", now);

        MapSqlParameterSource parameters = bind(scope, values);
        parameters.addValue("messageId", messageId, java.sql.Types.OTHER);
        parameters.addValue("aiConfidence", aiConfidence, java.sql.Types.INTEGER);
        parameters.addValue("intent", intent, java.sql.Types.VARCHAR);

        jdbc.update("""
            insert into conversation_scores
                ("id", "organizationId", "projectId", "conversationId", "messageId", "aiRunId",
                 "frustration", "sentiment", "urgency", "risk", "intentConfidence", "aiConfidence",
                 "intent", "evaluator", "signals", "createdAt")
            values (:id, :__fe_tenant_organization_id, :__fe_tenant_project_id, :conversationId,
                    :messageId, null, :frustration, :sentiment, :urgency, :risk, :intentConfidence,
                    :aiConfidence, :intent, :evaluator, cast(:signals as jsonb), :now)
            """, parameters);
        return scoreId;
    }

    /**
     * Parses a stored {@code conditions} array.
     *
     * <p>An entry that does not name a known signal/operator, or whose value is not a number, is
     * dropped rather than coerced — a coerced condition can silently fire. A jsonb column that will
     * not parse at all yields an empty list, which the service reports as an invalid rule; the
     * alternative is a project that cannot load any of its rules.
     */
    static List<Map<String, Object>> parseConditions(String conditionsJson, JsonCodec json) {
        List<Map<String, Object>> parsed = new ArrayList<>();
        if (conditionsJson == null || conditionsJson.isBlank()) {
            return parsed;
        }
        for (Object entry : json.readList(conditionsJson)) {
            if (entry instanceof Map<?, ?> raw
                && raw.get("signal") instanceof String signal
                && raw.get("operator") instanceof String operator
                && raw.get("value") instanceof Number value
                && ScoreSignal.fromWireValue(signal) != null
                && RuleCondition.RuleOperator.fromWireValue(operator) != null) {
                Map<String, Object> condition = new LinkedHashMap<>();
                condition.put("signal", signal);
                condition.put("operator", operator);
                condition.put("value", value.intValue());
                parsed.add(condition);
            }
        }
        return parsed;
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
