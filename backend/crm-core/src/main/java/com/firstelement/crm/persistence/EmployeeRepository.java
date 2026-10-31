package com.firstelement.crm.persistence;

import com.firstelement.crm.domain.ConversationStatus;
import com.firstelement.security.TenantContext;
import com.firstelement.security.query.TenantQueryGuard;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code employees} — assignee eligibility and workload.
 *
 * <h2>The two reads assignment needs</h2>
 *
 * <ol>
 *   <li>{@link #findEligibleAssignee} — an assignee id alone proves nothing, so the row must be
 *       in this organization <em>and</em> have a {@code project_memberships} row for the
 *       conversation's project. Legacy checked exactly this and rejected with
 *       "That employee is not a member of this project".</li>
 *   <li>{@link #recountActiveConversations} — the per-agent conversation limit.</li>
 * </ol>
 *
 * <h2>Why the count is a recount and not a decrement</h2>
 *
 * <p>Ported from {@code packages/routing/src/engine.ts} with its reasoning intact: "a counter
 * that misses one write starves an agent forever". Both sides of a reassignment and every
 * status change recount, so a process dying between two writes cannot leave a drifted number
 * that silently overloads the busiest agent.
 *
 * <h2>Workload is tenant-scoped too</h2>
 *
 * <p>The recount filter pins {@code organizationId} from the caller's context. An employee id
 * from another tenant therefore recounts nothing instead of another tenant's conversations.
 */
@Repository
public class EmployeeRepository {

    static final RowMapper<EmployeeRow> ROW = (rs, rowNum) -> new EmployeeRow(
        rs.getObject("id", UUID.class),
        rs.getObject("organizationId", UUID.class),
        rs.getObject("userId", UUID.class),
        rs.getInt("activeConversationCount"),
        rs.getInt("maxConcurrentConversations"),
        rs.getString("availabilityStatus"));

    private final NamedParameterJdbcTemplate jdbc;
    private final TenantQueryGuard guard;

    public EmployeeRepository(NamedParameterJdbcTemplate jdbc, TenantQueryGuard guard) {
        this.jdbc = jdbc;
        this.guard = guard;
    }

    /**
     * Whether {@code employeeId} may own a conversation in {@code projectId}.
     *
     * @return the employee's workload, or empty when the id is not an eligible assignee here
     */
    public Optional<EmployeeRow> findEligibleAssignee(TenantContext context,
                                                      UUID projectId,
                                                      UUID employeeId) {
        // The project comes from the conversation row this caller already proved they may read;
        // assertRowScope re-checks it, so a caller cannot smuggle another project in.
        guard.assertRowScope(context, context.organizationId(), projectId);
        Integer count = jdbc.queryForObject("""
            select count(*) from employees e
            where e."id" = :employeeId
              and e."organizationId" = :organizationId
              and exists (select 1 from project_memberships m
                          where m."userId" = e."userId"
                            and m."projectId" = :projectId
                            and m."organizationId" = :organizationId)
            """,
            new MapSqlParameterSource()
                .addValue("employeeId", employeeId)
                .addValue("organizationId", context.organizationId())
                .addValue("projectId", projectId),
            Integer.class);
        if (count == null || count == 0) {
            return Optional.empty();
        }
        return jdbc.query("""
            select e."id", e."organizationId", e."userId", e."activeConversationCount",
                   e."maxConcurrentConversations", e."availabilityStatus"
            from employees e
            where e."id" = :employeeId and e."organizationId" = :organizationId
            """,
            new MapSqlParameterSource()
                .addValue("employeeId", employeeId)
                .addValue("organizationId", context.organizationId()),
            ROW).stream().findFirst();
    }

    /**
     * Recomputes an employee's active conversation count and stores it.
     *
     * <p>Counts OPEN and PENDING only — the same two statuses the legacy recount used, and the
     * same two {@code ConversationStatus.isActive()} reports.
     *
     * @return the new count
     */
    public int recountActiveConversations(TenantContext context, UUID employeeId,
                                          LocalDateTime now) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("employeeId", employeeId);
        parameters.put("organizationId", context.organizationId());
        parameters.put("now", now);

        Integer count = jdbc.queryForObject("""
            select count(*) from conversations c
            where c."assignedEmployeeId" = :employeeId
              and c."organizationId" = :organizationId
              and c."status" in (cast('OPEN' as "ConversationStatus"),
                                 cast('PENDING' as "ConversationStatus"))
            """,
            new MapSqlParameterSource(parameters), Integer.class);

        int active = count == null ? 0 : count;
        jdbc.update("""
            update employees e set "activeConversationCount" = :active, "updatedAt" = :now
            where e."id" = :employeeId and e."organizationId" = :organizationId
            """,
            new MapSqlParameterSource(parameters).addValue("active", active));
        return active;
    }

    /** The status values routing may assign work to; the rest are stated reasons for being away. */
    public static boolean isRoutable(String availabilityStatus) {
        return "ONLINE".equals(availabilityStatus) || "BUSY".equals(availabilityStatus);
    }

    /** One employee row, projected for the assignment decision. */
    public record EmployeeRow(UUID id,
                              UUID organizationId,
                              UUID userId,
                              int activeConversationCount,
                              int maxConcurrentConversations,
                              String availabilityStatus) {

        /** The legacy capacity test: at or above the limit counts as full. */
        public boolean atCapacity() {
            return activeConversationCount >= maxConcurrentConversations;
        }
    }

    /** Kept so the enum's own predicate and the SQL literal stay in one place. */
    public static final java.util.List<ConversationStatus> ACTIVE_STATUSES =
        java.util.List.of(ConversationStatus.OPEN, ConversationStatus.PENDING);
}