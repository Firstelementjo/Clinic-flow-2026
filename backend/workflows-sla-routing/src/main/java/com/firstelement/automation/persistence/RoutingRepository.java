package com.firstelement.automation.persistence;

import com.firstelement.automation.domain.AvailabilityWindow;
import com.firstelement.automation.domain.RoutingCandidate;
import com.firstelement.crm.query.CrmQueryScope;
import com.firstelement.inbox.persistence.UnassignedQueueRepository;
import com.firstelement.security.TenantContext;
import com.firstelement.security.query.TenantQueryGuard;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The routing inputs: the roster, the routing rules, and the unassigned queue's membership rule.
 *
 * <h2>UNASSIGNED_ACTIVE is reused, never re-derived</h2>
 *
 * <p>ADR 0006 §5: "{@code UnassignedQueueRepository.UNASSIGNED_ACTIVE} is the single definition of
 * 'unassigned and active' for Phase 4's queue read model. Phase 6's router must <b>reuse</b> it
 * rather than re-deriving membership rules, so the queue an operator sees and the queue the router
 * fills cannot drift apart." {@link #unassignedPredicate()} returns the constant; this module holds
 * no second copy of the string, and a test asserts the router and the queue agree on the same
 * conversation.
 *
 * <h2>Membership is the first filter, and it is a query, not a post-filter</h2>
 *
 * <p>The legacy comment on {@code loadCandidates}: "Membership is the first filter, so routing can
 * never hand a conversation to someone who would get a 404 opening it." The membership test is an
 * {@code exists} against {@code project_memberships} inside the {@code WHERE} carrying both tenant
 * columns, so a candidate from another tenant cannot appear even transiently.
 *
 * <h2>Rule ordering</h2>
 *
 * <p>{@code ORDER BY r."priority", r."id"} — the legacy {@code matchRule}: "First rule whose intent
 * and department match, by ascending priority", with {@code id} as the tie-break. Alphabetical
 * ordering is forbidden and the rule name is not consulted at all.
 */
@Repository
public class RoutingRepository {

    /**
     * The tenant's routing rules, in explicit priority order.
     *
     * @param terminal whether the rule ends routing (legacy {@code isTerminal})
     */
    public record RoutingRuleRow(UUID id,
                                 String name,
                                 boolean enabled,
                                 int priority,
                                 String intent,
                                 String department,
                                 String requiredSkill,
                                 String strategy,
                                 boolean terminal) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final TenantQueryGuard guard;

    public RoutingRepository(NamedParameterJdbcTemplate jdbc, TenantQueryGuard guard) {
        this.jdbc = jdbc;
        this.guard = guard;
    }

    /**
     * The shared membership predicate.
     *
     * <p>A method rather than a local constant so a caller cannot accidentally shadow it with its own
     * string, and so the reference is greppable from either module.
     */
    public static String unassignedPredicate() {
        return UnassignedQueueRepository.UNASSIGNED_ACTIVE;
    }

    /**
     * Every employee who could own a conversation in this project.
     *
     * <p>Ported from the legacy {@code loadCandidates}, including its two deliberate omissions: no
     * availability filter and no capacity filter here. Both are pipeline stages, and filtering in
     * the query would move a decision out of the auditable stage trace and into a WHERE clause.
     *
     * <p>Skills and windows arrive through two left joins, so one employee is several rows; they are
     * grouped here rather than with a subquery per employee because the batch is bounded and one
     * round trip beats N.
     */
    public List<RoutingCandidate> candidates(TenantContext context, UUID projectId, int limit) {
        CrmQueryScope scope = CrmQueryScope.forProject(guard, context, projectId);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("projectId", projectId);
        values.put("limit", limit);
        MapSqlParameterSource parameters = scope.bind(values);

        Map<UUID, CandidateRow> byId = new LinkedHashMap<>();
        Map<UUID, List<RoutingCandidate.Skill>> skills = new LinkedHashMap<>();
        Map<UUID, List<AvailabilityWindow>> windows = new LinkedHashMap<>();

        for (CandidateRow row : jdbc.query("""
            select e."id" as employee_id, e."userId" as user_id, u."fullName" as full_name,
                   e."department" as department, e."availabilityStatus" as availability_status,
                   e."activeConversationCount" as active_count,
                   e."maxConcurrentConversations" as max_count,
                   s."skill" as skill, s."level" as skill_level,
                   a."dayOfWeek" as day_of_week, a."startMinute" as start_minute,
                   a."endMinute" as end_minute, a."timezone" as window_timezone
              from employees e
              join users u on u."id" = e."userId" and u."organizationId" = e."organizationId"
              left join employee_skills s on s."employeeId" = e."id"
                   and s."organizationId" = e."organizationId"
              left join employee_availability a on a."employeeId" = e."id"
                   and a."organizationId" = e."organizationId"
             where e."organizationId" = :__fe_crm_organization_id
               and exists (select 1 from project_memberships m
                            where m."userId" = e."userId"
                              and m."projectId" = :projectId
                              and m."organizationId" = :__fe_crm_organization_id)
               and u."isActive"
             order by e."id"
             limit :limit
            """,
            parameters,
            (rs, rowNum) -> new CandidateRow(
            rs.getObject("employee_id", UUID.class),
            rs.getObject("user_id", UUID.class),
            rs.getString("full_name"),
            rs.getString("department"),
            rs.getString("availability_status"),
            rs.getInt("active_count"),
            rs.getInt("max_count"),
            rs.getString("skill"),
            rs.getObject("skill_level", Integer.class),
            rs.getObject("day_of_week", Integer.class),
            rs.getObject("start_minute", Integer.class),
            rs.getObject("end_minute", Integer.class),
            rs.getString("window_timezone")))) {

            byId.putIfAbsent(row.employeeId(), row);
            if (row.skill() != null) {
                skills.computeIfAbsent(row.employeeId(), key -> new ArrayList<>())
                    .add(new RoutingCandidate.Skill(row.skill(),
                        row.skillLevel() == null ? 0 : row.skillLevel()));
            }
            if (row.dayOfWeek() != null) {
                windows.computeIfAbsent(row.employeeId(), key -> new ArrayList<>())
                    .add(new AvailabilityWindow(row.dayOfWeek(), row.startMinute(), row.endMinute(),
                        row.windowTimezone()));
            }
        }

        List<RoutingCandidate> candidates = new ArrayList<>();
        for (Map.Entry<UUID, CandidateRow> entry : byId.entrySet()) {
            CandidateRow row = entry.getValue();
            candidates.add(new RoutingCandidate(row.employeeId(), row.userId(), row.fullName(),
                row.department(), row.availabilityStatus(), row.activeCount(), row.maxCount(),
                List.copyOf(skills.getOrDefault(entry.getKey(), List.of())),
                List.copyOf(windows.getOrDefault(entry.getKey(), List.of()))));
        }
        return candidates;
    }

    /** Enabled rules in explicit priority order; the legacy matcher input. */
    public List<RoutingRuleRow> rules(TenantContext context, UUID projectId, int limit) {
        CrmQueryScope scope = CrmQueryScope.forProject(guard, context, projectId);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("limit", limit);
        MapSqlParameterSource parameters = scope.bind(values);
        // The scope predicate is concatenated rather than embedded: it is a complete fragment
        // produced by CrmQueryScope, and building the statement with a placeholder keeps the tenant
        // columns in the WHERE the reader can see.
        String sql = """
            select r."id", r."name", r."isEnabled", r."priority", r."intent", r."department",
                   r."requiredSkill", r."strategy", r."isTerminal"
              from routing_rules r
             where %s
               and r."isEnabled"
             order by r."priority", r."id"
             limit :limit
            """.formatted(scope.predicate("r"));
        return jdbc.query(sql, parameters, (rs, rowNum) -> new RoutingRuleRow(
            rs.getObject("id", UUID.class),
            rs.getString("name"),
            rs.getBoolean("isEnabled"),
            rs.getInt("priority"),
            rs.getString("intent"),
            rs.getString("department"),
            rs.getString("requiredSkill"),
            rs.getString("strategy"),
            rs.getBoolean("isTerminal")));
    }

    /**
     * The first rule whose intent and department match, in the order supplied.
     *
     * <p>A null or blank on the rule is a wildcard, not a requirement to be null (legacy
     * {@code matchRule}). The caller supplies the rules already ordered, so the ordering exists in
     * exactly one place and cannot be restated here.
     */
    public static RoutingRuleRow firstMatch(List<RoutingRuleRow> rules, String intent,
                                            String department) {
        for (RoutingRuleRow rule : rules) {
            boolean intentMatches = rule.intent() == null || rule.intent().isBlank()
                || rule.intent().equals(intent);
            boolean departmentMatches = rule.department() == null || rule.department().isBlank()
                || rule.department().equals(department);
            if (intentMatches && departmentMatches) {
                return rule;
            }
        }
        return null;
    }

    /**
     * Whether a conversation is in the unassigned queue, using the shared predicate.
     *
     * <p>Used to refuse a re-route and to name the blocking stage. The queue's own rows are read by
     * {@code UnassignedQueueRepository}; this is a membership question, not a second queue.
     */
    public boolean isUnassignedAndActive(TenantContext context, UUID projectId, UUID conversationId) {
        CrmQueryScope scope = CrmQueryScope.forProject(guard, context, projectId);
        MapSqlParameterSource parameters = scope.bind(Map.of("id", conversationId));
        // The shared predicate is concatenated as an ordinary string: it is already a complete
        // fragment, and embedding a text-block delimiter inside another text block does not compile.
        String sql = "select count(*) from conversations c"
            + " where " + scope.predicate("c")
            + " and c.\"id\" = :id"
            + " and (" + unassignedPredicate() + ")";
        Integer count = jdbc.queryForObject(sql, parameters, Integer.class);
        return count != null && count > 0;
    }

    private record CandidateRow(UUID employeeId,
                                UUID userId,
                                String fullName,
                                String department,
                                String availabilityStatus,
                                int activeCount,
                                int maxCount,
                                String skill,
                                Integer skillLevel,
                                Integer dayOfWeek,
                                Integer startMinute,
                                Integer endMinute,
                                String windowTimezone) {

        /** A skill/window-carrying row projected back to the employee itself. */
        CandidateRow self() {
            return new CandidateRow(employeeId, userId, fullName, department, availabilityStatus,
                activeCount, maxCount, skill, skillLevel, dayOfWeek, startMinute, endMinute,
                windowTimezone);
        }
    }

}
