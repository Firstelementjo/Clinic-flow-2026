package com.firstelement.inbox.persistence;

import com.firstelement.crm.query.CrmQueryScope;
import com.firstelement.crm.query.InboxFilterDefinition;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The read model behind the unassigned queue.
 *
 * <h2>Why a separate repository</h2>
 *
 * <p>The queue is not a filter over the inbox list — it needs per-row facts (does the timeline
 * contain an {@code UNASSIGNED} entry? was this thread resolved and reopened?) and
 * scope-wide aggregates. Folding those into the inbox statement would make the inbox slower for
 * every request in order to serve a second surface, and would make the queue's membership rule
 * something a filter change could move.
 *
 * <h2>The membership rule</h2>
 *
 * <p>{@code "assignedEmployeeId" is null} and the status is OPEN or PENDING. It is written once,
 * here, and the inbox filter, the counts and the routing phase's eligibility checks all refer
 * back to it — two definitions of "unassigned" would produce two different numbers.
 *
 * <h2>Scope</h2>
 *
 * <p>Every statement carries {@link CrmQueryScope}, and the timeline sub-select reuses the
 * conversation's own {@code organizationId}/{@code projectId} columns, so a queue entry can never
 * be justified by another tenant's history.
 */
@Repository
public class UnassignedQueueRepository {

    private static final RowMapper<QueueFact> FACT = (rs, rowNum) -> new QueueFact(
        rs.getObject("id", UUID.class),
        rs.getObject("lastMessageAt", LocalDateTime.class),
        rs.getObject("createdAt", LocalDateTime.class),
        rs.getString("status"),
        rs.getObject("lastResolvedAt", LocalDateTime.class),
        rs.getObject("lastUnassignedAt", LocalDateTime.class),
        rs.getObject("lastAssignedAt", LocalDateTime.class));

    private static final String FACT_SELECT = """
        select c."id", c."lastMessageAt", c."createdAt", c."status",
               (select max(e."occurredAt") from conversation_events e
                 where e."conversationId" = c."id"
                   and e."organizationId" = c."organizationId"
                   and e."projectId" = c."projectId"
                   and e."type" = cast('STATUS_CHANGED' as "ConversationEventType")
                   and e."payload"->>'to' = 'RESOLVED') as "lastResolvedAt",
               (select max(e."occurredAt") from conversation_events e
                 where e."conversationId" = c."id"
                   and e."organizationId" = c."organizationId"
                   and e."projectId" = c."projectId"
                   and e."type" = cast('UNASSIGNED' as "ConversationEventType")) as "lastUnassignedAt",
               (select max(e."occurredAt") from conversation_events e
                 where e."conversationId" = c."id"
                   and e."organizationId" = c."organizationId"
                   and e."projectId" = c."projectId"
                   and e."type" = cast('ASSIGNED' as "ConversationEventType")) as "lastAssignedAt"
        from conversations c
        """;

    /**
     * The membership predicate, in one place -- which is now
     * {@link InboxFilterDefinition#UNASSIGNED_ACTIVE}, not a second copy of it.
     *
     * <p>This route predates the {@code unassigned} inbox filter, and a client can now ask the same
     * question two ways: {@code GET /inbox/unassigned} and
     * {@code GET /inbox/conversations?filter=unassigned}. Two definitions of "unassigned" is two
     * places to fix a bug and an invitation for a client to receive two different answers to one
     * question.
     *
     * <p>So the <b>filter is canonical</b>: it is in the wire vocabulary, it is counted by
     * {@code /inbox/counts}, and it is what the OpenAPI document publishes. This route takes its
     * membership rule from there by reference. It cannot become a full alias for the list, because
     * its response is richer -- a per-row diagnosis and queue totals, which the list endpoint has
     * no column for -- and Phase 11's journeys call it. But the <em>set</em> it returns is decided
     * in one place, and {@code InboxWorkQueueIntegrationTest} asserts the two are equal rather
     * than leaving the agreement to a comment.
     */
    public static final String UNASSIGNED_ACTIVE = InboxFilterDefinition.UNASSIGNED_ACTIVE;

    private final NamedParameterJdbcTemplate jdbc;

    public UnassignedQueueRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The queue's fact rows for one page, in the inbox's recency order. */
    public List<QueueFact> page(CrmQueryScope scope,
                                String additionalPredicate,
                                Map<String, Object> parameters,
                                Map<String, Object> extraParameters,
                                int fetchLimit) {
        Map<String, Object> bound = new LinkedHashMap<>(parameters);
        bound.putAll(extraParameters);
        String predicate = additionalPredicate == null || additionalPredicate.isBlank()
            ? UNASSIGNED_ACTIVE
            : "(" + UNASSIGNED_ACTIVE + ") and (" + additionalPredicate + ")";
        CrmQueryScope.Statement statement = scope.select(FACT_SELECT, "c", predicate, bound)
            .orderedBy("c.\"lastMessageAt\" desc, c.\"id\" desc", fetchLimit);
        return jdbc.query(statement.sql(), statement.parameters(), FACT);
    }

    /** The queue's total, its per-status and per-channel shape, and its oldest waiting age. */
    public Totals totals(CrmQueryScope scope, String additionalPredicate,
                         Map<String, Object> parameters) {
        Map<String, Object> bound = new LinkedHashMap<>(parameters);
        String predicate = additionalPredicate == null || additionalPredicate.isBlank()
            ? UNASSIGNED_ACTIVE
            : "(" + UNASSIGNED_ACTIVE + ") and (" + additionalPredicate + ")";

        Map<String, Integer> byStatus = group(scope, predicate, bound, "c.\"status\"");
        Map<String, Integer> byChannel = group(scope, predicate, bound, "c.\"channel\"");

        CrmQueryScope.Statement oldest = scope.select(
            "select min(c.\"lastMessageAt\") as \"oldest\" from conversations c", "c", predicate,
            bound);
        LocalDateTime oldestAt = jdbc.queryForObject(oldest.sql(), oldest.parameters(),
            LocalDateTime.class);

        CrmQueryScope.Statement total = scope.select(
            "select count(*) as \"count\" from conversations c", "c", predicate, bound);
        Integer count = jdbc.queryForObject(total.sql(), total.parameters(), Integer.class);

        return new Totals(count == null ? 0 : count, byStatus, byChannel, oldestAt);
    }

    /**
     * Runs one grouped count.
     *
     * <p>The statement is assembled here rather than through {@code CrmQueryScope#select}
     * because a grouped aggregate needs a {@code group by} between the predicate and the order,
     * and no builder in the scope helper models that. The tenant predicate still comes from the
     * scope, so the statement cannot be built without one.
     */
    private Map<String, Integer> group(CrmQueryScope scope, String predicate,
                                       Map<String, Object> parameters, String groupColumn) {
        String sql = "select " + groupColumn + " as \"key\", count(*) as \"count\" "
            + "from conversations c where " + scope.predicate("c") + " and (" + predicate + ") "
            + "group by " + groupColumn + " order by " + groupColumn + " asc";
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (GroupRow group : jdbc.query(sql, scope.bind(parameters),
            (rs, rowNum) -> new GroupRow(rs.getString("key"), rs.getInt("count")))) {
            counts.put(group.key(), group.count());
        }
        return counts;
    }

    /** One queue row's diagnostic facts. */
    public record QueueFact(UUID id,
                            LocalDateTime lastMessageAt,
                            LocalDateTime createdAt,
                            String status,
                            LocalDateTime lastResolvedAt,
                            LocalDateTime lastUnassignedAt,
                            LocalDateTime lastAssignedAt) {
    }

    /** The queue's aggregate shape. */
    public record Totals(int total,
                         Map<String, Integer> byStatus,
                         Map<String, Integer> byChannel,
                         LocalDateTime oldestWaitingAt) {
    }

    /** One counting group. */
    public record GroupRow(String key, int count) {
    }
}
