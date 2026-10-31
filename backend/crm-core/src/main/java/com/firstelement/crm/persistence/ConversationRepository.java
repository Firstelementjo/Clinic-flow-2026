package com.firstelement.crm.persistence;

import com.firstelement.crm.domain.ChannelType;
import com.firstelement.crm.domain.ConversationMode;
import com.firstelement.crm.domain.ConversationPriority;
import com.firstelement.crm.domain.ConversationStatus;
import com.firstelement.crm.domain.TicketNumber;
import com.firstelement.crm.query.CrmQueryScope;
import com.firstelement.crm.query.InboxFilterDefinition;
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
 * {@code conversations} — the state machine, the inbox query model, and the write paths.
 *
 * <h2>Mapping rules (ADR-0002)</h2>
 *
 * <ul>
 *   <li>Quoted camelCase identifiers written by hand; no naming strategy exists in this runtime.</li>
 *   <li>Every timestamp is a {@link LocalDateTime} because all of them are
 *       {@code timestamp(3) without time zone}.</li>
 *   <li>Enum columns are written with an explicit {@code cast(:x as "Enum")}; the driver binds
 *       a String as {@code varchar} and PostgreSQL will not implicitly cast it.</li>
 * </ul>
 *
 * <h2>Every write is a conditional update</h2>
 *
 * <p>There is no read-then-write anywhere in this class. Each mutation is a single
 * {@code update ... where <tenant scope> and <the version token>} and reports the affected
 * row count, so the caller can tell "applied" from "someone else moved first". That is what
 * makes simultaneous replies, assignment changes and case edits detectable rather than
 * last-writer-wins.
 *
 * <h2>The version token is {@code updatedAt}</h2>
 *
 * <p>Prisma declared {@code updatedAt DateTime @updatedAt}, so every legacy update bumped it,
 * and {@code timestamp(3)} gives it millisecond resolution. It is therefore already a
 * monotonic per-row token with no migration. The one purpose-built counter,
 * {@code modeVersion}, stays the guard for the AI⇄HUMAN transition exactly as
 * {@code packages/messaging/src/ai-dispatch.ts} defined it.
 */
@Repository
public class ConversationRepository {

    /**
     * The joined projection. The {@code messages} lateral sub-select is scoped by the
     * conversation's own organization/project columns, so it can never read another tenant's
     * body.
     */
    public static final String SELECT_WITH_SCOPE = """
        select c."id", c."organizationId", c."projectId", c."customerId", c."ticketNumber",
               c."channel", c."status", c."mode", c."priority", c."subject",
               c."assignedEmployeeId", c."lastReplierUserId", c."modeVersion", c."messageCount",
               c."lastMessageAt", c."firstResponseAt", c."resolvedAt", c."closedAt",
               c."createdAt", c."updatedAt",
               p."name" as "projectName",
               cu."cxId" as "customerCxId", cu."displayName" as "customerDisplayName",
               eu."fullName" as "assigneeFullName",
               (select m."body" from messages m
                 where m."conversationId" = c."id"
                   and m."organizationId" = c."organizationId"
                   and m."projectId" = c."projectId"
                 order by m."sentAt" desc, m."id" desc
                 limit 1) as "lastMessageBody"
        from conversations c
        join projects p on p."id" = c."projectId" and p."organizationId" = c."organizationId"
        join customers cu on cu."id" = c."customerId"
             and cu."organizationId" = c."organizationId" and cu."projectId" = c."projectId"
        left join employees e on e."id" = c."assignedEmployeeId"
        left join users eu on eu."id" = e."userId"
        """;

    static final RowMapper<ConversationRow> ROW = (rs, rowNum) -> new ConversationRow(
        rs.getObject("id", UUID.class),
        rs.getObject("organizationId", UUID.class),
        rs.getObject("projectId", UUID.class),
        rs.getObject("customerId", UUID.class),
        rs.getString("ticketNumber"),
        rs.getString("channel"),
        rs.getString("status"),
        rs.getString("mode"),
        rs.getString("priority"),
        rs.getString("subject"),
        rs.getObject("assignedEmployeeId", UUID.class),
        rs.getObject("lastReplierUserId", UUID.class),
        rs.getInt("modeVersion"),
        rs.getInt("messageCount"),
        rs.getObject("lastMessageAt", LocalDateTime.class),
        rs.getObject("firstResponseAt", LocalDateTime.class),
        rs.getObject("resolvedAt", LocalDateTime.class),
        rs.getObject("closedAt", LocalDateTime.class),
        rs.getObject("createdAt", LocalDateTime.class),
        rs.getObject("updatedAt", LocalDateTime.class),
        rs.getString("projectName"),
        rs.getString("customerCxId"),
        rs.getString("customerDisplayName"),
        rs.getString("assigneeFullName"),
        rs.getString("lastMessageBody"));

    /** Ordering for every list: recency first, id as the deterministic tie-break. */
    public static final String RECENCY_ORDER = "c.\"lastMessageAt\" desc, c.\"id\" desc";

    private final NamedParameterJdbcTemplate jdbc;

    public ConversationRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------
    // reads
    // ------------------------------------------------------------------

    /** One conversation inside the caller's scope; empty for "not mine" and "not there". */
    public Optional<ConversationRow> findInScope(CrmQueryScope scope, UUID conversationId) {
        CrmQueryScope.Statement statement = scope.select(SELECT_WITH_SCOPE, "c",
            "c.\"id\" = :conversationId",
            CrmQueryScope.params("conversationId", conversationId));
        return jdbc.query(statement.sql(), statement.parameters(), ROW).stream().findFirst();
    }

    /**
     * One conversation for the keyset cursor boundary: only the two columns a cursor needs.
     *
     * <p>Empty means the cursor names a conversation the caller may not see, which the service
     * reports as {@code INVALID_CURSOR} rather than as an empty page. Returning an empty page
     * would tell a client its cursor silently stopped working.
     */
    public Optional<CursorBoundary> cursorBoundary(CrmQueryScope scope, UUID cursor) {
        CrmQueryScope.Statement statement = scope.select(
            "select c.\"lastMessageAt\", c.\"id\" from conversations c", "c",
            "c.\"id\" = :cursor", CrmQueryScope.params("cursor", cursor));
        return jdbc.query(statement.sql(), statement.parameters(),
                (rs, rowNum) -> new CursorBoundary(
                    rs.getObject("lastMessageAt", LocalDateTime.class),
                    rs.getObject("id", UUID.class)))
            .stream().findFirst();
    }

    /** The keyset boundary a cursor id resolves to. */
    public record CursorBoundary(LocalDateTime lastMessageAt, UUID id) {
    }

    /**
     * The inbox page.
     *
     * @param filterParameters the fragment and parameters from {@link InboxFilterDefinition}
     * @param cursorBoundary   {@code null} for the first page, else the exclusive lower bound
     * @param limit            over-fetched as {@code limit + 1} by the caller
     */
    public List<ConversationRow> inbox(CrmQueryScope scope,
                                       String filterPredicate,
                                       Map<String, Object> filterParameters,
                                       CursorBoundary cursorBoundary,
                                       int fetchLimit) {
        Map<String, Object> parameters = new LinkedHashMap<>(filterParameters);
        List<String> conditions = new ArrayList<>();
        if (filterPredicate != null && !filterPredicate.isBlank()) {
            conditions.add("(" + filterPredicate + ")");
        }
        if (cursorBoundary != null) {
            // Keyset on (lastMessageAt, id): strictly older than the cursor row, with the id
            // tie-break so two rows sharing a timestamp cannot be served twice or skipped.
            conditions.add("(c.\"lastMessageAt\" < :cursorLastMessageAt"
                + " or (c.\"lastMessageAt\" = :cursorLastMessageAt and c.\"id\" < :cursorId))");
            parameters.put("cursorLastMessageAt", cursorBoundary.lastMessageAt());
            parameters.put("cursorId", cursorBoundary.id());
        }
        String additional = conditions.isEmpty() ? null : String.join(" and ", conditions);

        CrmQueryScope.Statement statement = scope
            // No extra join here: SELECT_WITH_SCOPE already carries `employees e` and
            // `customers cu`, which are exactly the two aliases the filter fragments reference.
            // Appending the join again is what produced "table name \"e\" specified more than
            // once", and the container test is what found it.
            .select(SELECT_WITH_SCOPE, "c", additional, parameters)
            .orderedBy(RECENCY_ORDER, fetchLimit);
        return jdbc.query(statement.sql(), statement.parameters(), ROW);
    }

    /** The open/pending/resolved thread a customer last used on a channel, if any. */
    public Optional<ConversationRow> findLatestThreadForCustomer(CrmQueryScope scope,
                                                                 UUID customerId,
                                                                 ChannelType channel) {
        CrmQueryScope.Statement statement = scope.select(
            """
            select c."id", c."organizationId", c."projectId", c."customerId", c."ticketNumber",
                   c."channel", c."status", c."mode", c."priority", c."subject",
                   c."assignedEmployeeId", c."lastReplierUserId", c."modeVersion", c."messageCount",
                   c."lastMessageAt", c."firstResponseAt", c."resolvedAt", c."closedAt",
                   c."createdAt", c."updatedAt",
                   p."name" as "projectName",
                   cu."cxId" as "customerCxId", cu."displayName" as "customerDisplayName",
                   eu."fullName" as "assigneeFullName", null::text as "lastMessageBody"
            from conversations c
            join projects p on p."id" = c."projectId" and p."organizationId" = c."organizationId"
            join customers cu on cu."id" = c."customerId"
                 and cu."organizationId" = c."organizationId" and cu."projectId" = c."projectId"
            left join employees e on e."id" = c."assignedEmployeeId"
            left join users eu on eu."id" = e."userId"
            """,
            "c",
            "c.\"customerId\" = :customerId"
                + " and c.\"channel\" = cast(:channel as \"ChannelType\")"
                + " and c.\"status\" in (cast('OPEN' as \"ConversationStatus\"),"
                + " cast('PENDING' as \"ConversationStatus\"), cast('RESOLVED' as \"ConversationStatus\"))",
            CrmQueryScope.params("customerId", customerId, "channel", channel.name()))
            .orderedBy("c.\"lastMessageAt\" desc", 1);
        return jdbc.query(statement.sql(), statement.parameters(), ROW).stream().findFirst();
    }

    // ------------------------------------------------------------------
    // writes
    // ------------------------------------------------------------------

    /**
     * Applies a status/priority/subject update.
     *
     * <p>The version predicate is what makes this safe: a caller that read the row 3 seconds
     * ago and a colleague who resolved it in between cannot both win. The legacy code had no
     * such check (it was a plain {@code update}), so this is the requested strengthening and
     * not a behaviour port; it is opt-in through {@code expectedUpdatedAt} so an existing
     * client that sends no {@code If-Match} keeps working exactly as before.
     *
     * @param expectedUpdatedAt the {@code If-Match} token, or {@code null} for "last write wins"
     * @return rows changed: 1 applied, 0 collided
     */
    public int updateState(CrmQueryScope scope,
                           UUID conversationId,
                           LocalDateTime expectedUpdatedAt,
                           ConversationStatus status,
                           ConversationPriority priority,
                           String subject,
                           boolean subjectProvided,
                           LocalDateTime resolvedAt,
                           LocalDateTime closedAt,
                           LocalDateTime now) {
        List<String> assignments = new ArrayList<>();
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("conversationId", conversationId);
        parameters.put("now", now);

        if (status != null) {
            assignments.add("\"status\" = cast(:status as \"ConversationStatus\")");
            parameters.put("status", status.name());
        }
        if (priority != null) {
            assignments.add("\"priority\" = cast(:priority as \"ConversationPriority\")");
            parameters.put("priority", priority.name());
        }
        if (subjectProvided) {
            assignments.add("\"subject\" = :subject");
            parameters.put("subject", subject);
        }
        if (resolvedAt != null) {
            assignments.add("\"resolvedAt\" = :resolvedAt");
            parameters.put("resolvedAt", resolvedAt);
        }
        if (closedAt != null) {
            assignments.add("\"closedAt\" = :closedAt");
            parameters.put("closedAt", closedAt);
        }
        if (assignments.isEmpty()) {
            return 1;
        }
        assignments.add("\"updatedAt\" = :now");
        return conditionalUpdate(scope, conversationId, expectedUpdatedAt, assignments, parameters);
    }

    /** Clears {@code resolvedAt} on a reopen; a NULL assignment cannot be expressed as a parameter. */
    public int clearResolvedAt(CrmQueryScope scope,
                               UUID conversationId,
                               LocalDateTime expectedUpdatedAt,
                               LocalDateTime now) {
        return conditionalUpdate(scope, conversationId, expectedUpdatedAt,
            List.of("\"resolvedAt\" = null", "\"closedAt\" = null", "\"updatedAt\" = :now"),
            new LinkedHashMap<>(Map.of("now", now)));
    }

    /**
     * Sets or clears the assignee.
     *
     * <p>{@code assignedEmployeeId} is nullable and the column is a uuid, so the "unassign"
     * case is a literal {@code null} rather than a bound parameter.
     */
    public int setAssignee(CrmQueryScope scope,
                           UUID conversationId,
                           LocalDateTime expectedUpdatedAt,
                           UUID employeeId,
                           LocalDateTime now) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("conversationId", conversationId);
        parameters.put("now", now);
        String assignment = employeeId == null
            ? "\"assignedEmployeeId\" = null"
            : "\"assignedEmployeeId\" = :employeeId";
        if (employeeId != null) {
            parameters.put("employeeId", employeeId);
        }
        return conditionalUpdate(scope, conversationId, expectedUpdatedAt,
            List.of(assignment, "\"updatedAt\" = :now"), parameters);
    }

    /**
     * The AI⇄HUMAN transition, guarded by {@code modeVersion}.
     *
     * <p>Ported from {@code ai-dispatch.ts} and {@code escalation-engine.ts}: the update is
     * conditional on <em>both</em> the expected mode and the {@code modeVersion} the caller
     * read, so an escalation and a manual takeover racing each other cannot both "win", and a
     * takeover cannot be undone by an AI reply that was already in flight.
     *
     * @return rows changed: 1 transitioned, 0 lost the race or the mode was already there
     */
    public int changeMode(CrmQueryScope scope,
                          UUID conversationId,
                          ConversationMode expectedMode,
                          int expectedModeVersion,
                          ConversationMode newMode,
                          ConversationStatus newStatus,
                          ConversationPriority newPriority,
                          LocalDateTime now) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("conversationId", conversationId);
        parameters.put("expectedMode", expectedMode.name());
        parameters.put("expectedModeVersion", expectedModeVersion);
        parameters.put("newMode", newMode.name());
        parameters.put("now", now);
        List<String> assignments = new ArrayList<>(List.of(
            "\"mode\" = cast(:newMode as \"ConversationMode\")",
            "\"modeVersion\" = \"modeVersion\" + 1",
            "\"updatedAt\" = :now"));
        if (newStatus != null) {
            assignments.add("\"status\" = cast(:newStatus as \"ConversationStatus\")");
            parameters.put("newStatus", newStatus.name());
        }
        if (newPriority != null) {
            assignments.add("\"priority\" = cast(:newPriority as \"ConversationPriority\")");
            parameters.put("newPriority", newPriority.name());
        }
        return conditionalUpdate(scope, conversationId, null, assignments, parameters,
            "c.\"mode\" = cast(:expectedMode as \"ConversationMode\")"
                + " and c.\"modeVersion\" = :expectedModeVersion");
    }

    /**
     * Applies the reply side effects in one statement.
     *
     * <p>Ported from {@code ConversationsService.reply}'s transaction body:
     *
     * <ul>
     *   <li>{@code messageCount} increments — the thread stays in the counter.</li>
     *   <li>{@code lastMessageAt} moves — the thread does not sink to the bottom of the inbox.</li>
     *   <li>{@code lastReplierUserId} is set — this is what keeps a released conversation in the
     *       replier's "assigned to me" view.</li>
     *   <li>{@code firstResponseAt} is captured once, at the moment it happens, because an ops
     *       manager is measured on it and it cannot be reconstructed later.</li>
     *   <li>A reply to a RESOLVED/CLOSED thread reopens it. Both in SQL, so two simultaneous
     *       replies cannot interleave a read and a write.</li>
     * </ul>
     *
     * <p>No version predicate: a reply is append-only, and refusing a reply because a colleague
     * answered first would drop a customer communication. Collision detection for replies is
     * the idempotency key, not a version check.
     */
    public int applyReplyCounters(CrmQueryScope scope,
                                  UUID conversationId,
                                  LocalDateTime now,
                                  UUID replyingUserId) {
        CrmQueryScope.Statement statement = scope.update(
            """
            update conversations c set
                "messageCount" = c."messageCount" + 1,
                "lastMessageAt" = :now,
                "lastReplierUserId" = :replyingUserId,
                "firstResponseAt" = coalesce(c."firstResponseAt", :now),
                "status" = case when c."status" in (cast('RESOLVED' as "ConversationStatus"),
                                                    cast('CLOSED' as "ConversationStatus"))
                                then cast('OPEN' as "ConversationStatus") else c."status" end,
                "updatedAt" = :now
            """,
            "c", "c.\"id\" = :conversationId",
            CrmQueryScope.params("conversationId", conversationId, "now", now,
                "replyingUserId", replyingUserId));
        return jdbc.update(statement.sql(), statement.parameters());
    }

    /** Applies the inbound side effects: the same counters, but the thread is not reopened. */
    public int applyInboundCounters(CrmQueryScope scope,
                                    UUID conversationId,
                                    LocalDateTime sentAt,
                                    LocalDateTime now) {
        CrmQueryScope.Statement statement = scope.update(
            """
            update conversations c set
                "messageCount" = c."messageCount" + 1,
                "lastMessageAt" = :sentAt,
                "updatedAt" = :now
            """,
            "c", "c.\"id\" = :conversationId",
            CrmQueryScope.params("conversationId", conversationId, "sentAt", sentAt, "now", now));
        return jdbc.update(statement.sql(), statement.parameters());
    }

    /** Reopens a RESOLVED thread so a new customer message continues it. */
    public int reopenResolved(CrmQueryScope scope, UUID conversationId, LocalDateTime now) {
        CrmQueryScope.Statement statement = scope.update(
            """
            update conversations c set
                "status" = cast('OPEN' as "ConversationStatus"),
                "resolvedAt" = null,
                "updatedAt" = :now
            """,
            "c", "c.\"id\" = :conversationId",
            CrmQueryScope.params("conversationId", conversationId, "now", now));
        return jdbc.update(statement.sql(), statement.parameters());
    }

    /**
     * Inserts a new thread.
     *
     * <p>The caller supplies the {@code ticketNumber} it obtained from
     * {@link #nextTicketSequence}. A {@code conversations_ticketNumber_key} violation is
     * propagated to the caller, which retries with the next sequence value — see
     * {@code ConversationService#create}.
     */
    public void insert(CrmQueryScope scope,
                       UUID conversationId,
                       UUID customerId,
                       ChannelType channel,
                       ConversationStatus status,
                       ConversationMode mode,
                       String ticketNumber,
                       LocalDateTime lastMessageAt,
                       LocalDateTime now) {
        CrmQueryScope.Statement statement = scope.insert(
            """
            insert into conversations ("id", "organizationId", "projectId", "customerId",
                "ticketNumber", "channel", "status", "mode", "priority", "messageCount",
                "lastMessageAt", "createdAt", "updatedAt")
            values (:id, :__fe_crm_organization_id, :__fe_crm_project_id, :customerId,
                :ticketNumber, cast(:channel as "ChannelType"),
                cast(:status as "ConversationStatus"),
                cast(:mode as "ConversationMode"), cast('NORMAL' as "ConversationPriority"),
                0, :lastMessageAt, :now, :now)
            """,
            CrmQueryScope.params("id", conversationId, "customerId", customerId,
                "ticketNumber", ticketNumber, "channel", channel.name(),
                "status", status.name(), "mode", mode.name(),
                "lastMessageAt", lastMessageAt, "now", now));
        jdbc.update(statement.sql(), statement.parameters());
    }

    // ------------------------------------------------------------------
    // sequences
    // ------------------------------------------------------------------

    /**
     * The next per-day ticket sequence: {@code count(conversations created today) + 1}.
     *
     * <p>Ported from {@code nextTicketSequence}, with one change: the count is <em>tenant
     * scoped</em> rather than global. A global count would be an unscoped read of another
     * tenant's row count, which AGENTS.md forbids. {@code conversations."ticketNumber"} is
     * globally unique, so a tenant-local starting point can collide; the collision is a unique
     * violation and {@link #insert} surfaces it, and the caller walks the sequence forward
     * until it finds a free number. The observable result — a unique, monotonic, per-day
     * ticket number — is unchanged.
     */
    public int nextTicketSequence(CrmQueryScope scope, LocalDateTime date) {
        CrmQueryScope.Statement statement = scope.select(
            "select count(*) as \"count\" from conversations c", "c",
            "c.\"createdAt\" >= :startOfDay and c.\"createdAt\" < :endOfDay",
            CrmQueryScope.params(
                "startOfDay", TicketNumber.startOfDay(date),
                "endOfDay", TicketNumber.startOfNextDay(date)));
        Integer count = jdbc.queryForObject(statement.sql(), statement.parameters(), Integer.class);
        return count == null ? 1 : count + 1;
    }

    /**
     * Counts conversations matching a saved-view filter.
     *
     * <p>Exists so the inbox badges and the queue totals are computed by the <em>same</em>
     * filter definition the list uses. A count computed by a second predicate is how a tab
     * badge and the list it labels drift apart, and once they do nobody trusts either.
     *
     * @param needsCustomerJoin {@code true} when the predicate references the customers alias
     */
    public int countWithFilter(CrmQueryScope scope,
                               String predicate,
                               Map<String, Object> parameters,
                               boolean needsCustomerJoin) {
        String where = predicate == null || predicate.isBlank() ? "1 = 1" : predicate;
        // The fragment may reference the customers alias (the CX-id search branch), so the join
        // is added only when that branch can be present.
        String from = "from conversations c " + InboxFilterDefinition.EMPLOYEE_JOIN
            + (needsCustomerJoin && where.contains(InboxFilterDefinition.CUSTOMER_ALIAS + ".")
                ? " left join customers " + InboxFilterDefinition.CUSTOMER_ALIAS + " on "
                    + InboxFilterDefinition.CUSTOMER_ALIAS + ".\"id\" = c.\"customerId\""
                : "");
        CrmQueryScope.Statement statement = scope.select(
            "select count(*) as \"count\" " + from, "c", where, parameters);
        Integer count = jdbc.queryForObject(statement.sql(), statement.parameters(), Integer.class);
        return count == null ? 0 : count;
    }

    /**
     * Whether a ticket number is already taken. Used to skip a colliding sequence.
     *
     * <p><b>Deliberately not tenant-scoped, unlike every other statement in this class.</b>
     * An audit flagged it as the one unscoped read in a tenant-facing repository, and on a
     * first reading that is right. It is not, and scoping it would introduce a bug rather
     * than remove one.
     *
     * <p>{@code conversations_ticketNumber_key} is a <b>globally</b> unique index --
     * uniqueness is global, not per organization -- while the ticket sequence is derived per
     * project. So two unrelated tenants can independently generate the same number. This
     * method is the pre-check that lets the caller walk to the next candidate instead of
     * letting the insert fail. Scope it to the caller's tenant and the pre-check stops
     * seeing the foreign collision: the insert proceeds and the database raises a unique
     * violation, converting a graceful "try attempt + 1" into an unhandled error on a
     * customer-visible inbound-message path.
     *
     * <p>What it exposes is a boolean about a system-minted sequence value: no row, no
     * column, no customer content, and the value is not a client-supplied identifier. That
     * is not the class of leak the scoping rule exists to prevent. Leave it global; the
     * reasoning is recorded here so the next audit does not "fix" it into a crash.
     */
    public boolean ticketNumberExists(String ticketNumber) {
        Integer count = jdbc.queryForObject(
            "select count(*) from conversations where \"ticketNumber\" = :ticketNumber",
            new MapSqlParameterSource("ticketNumber", ticketNumber), Integer.class);
        return count != null && count > 0;
    }

    // ------------------------------------------------------------------
    private int conditionalUpdate(CrmQueryScope scope,
                                  UUID conversationId,
                                  LocalDateTime expectedUpdatedAt,
                                  List<String> assignments,
                                  Map<String, Object> parameters) {
        // This helper builds the `c."id" = :conversationId` predicate, so it must also supply the
        // binding. It did not: only `expectedUpdatedAt` was ever put into the caller's map, so any
        // caller that passed a map without `conversationId` produced
        // `InvalidDataAccessApiUsageException: No value supplied for the SQL parameter
        // 'conversationId'` and the statement failed at run time. `clearResolvedAt` -- the reopen
        // path -- passed only `now`, so EVERY reopen answered 500 for every caller, in every tenant.
        //
        // The failure mode was invisible to compilation, to the repository's own unit tests, and to
        // every other conversation transition, because the other callers happened to include the id
        // themselves. Binding it here makes the helper satisfy its own predicate rather than relying
        // on each caller to guess. Putting it again where a caller already added it is harmless.
        parameters.put("conversationId", conversationId);
        StringBuilder predicate = new StringBuilder("c.\"id\" = :conversationId");
        if (expectedUpdatedAt != null) {
            predicate.append(" and c.\"updatedAt\" = :expectedUpdatedAt");
            parameters.put("expectedUpdatedAt", expectedUpdatedAt);
        }
        CrmQueryScope.Statement statement = scope.update(
            "update conversations c set " + String.join(", ", assignments), "c",
            predicate.toString(), parameters);
        return jdbc.update(statement.sql(), statement.parameters());
    }

    private int conditionalUpdate(CrmQueryScope scope,
                                  UUID conversationId,
                                  LocalDateTime expectedUpdatedAt,
                                  List<String> assignments,
                                  Map<String, Object> parameters,
                                  String extraPredicate) {
        // Same obligation as the overload above: this predicate names :conversationId, so this helper
        // binds it too. See the note there.
        parameters.put("conversationId", conversationId);
        StringBuilder predicate = new StringBuilder(extraPredicate);
        predicate.append(" and c.\"id\" = :conversationId");
        if (expectedUpdatedAt != null) {
            predicate.append(" and c.\"updatedAt\" = :expectedUpdatedAt");
            parameters.put("expectedUpdatedAt", expectedUpdatedAt);
        }
        CrmQueryScope.Statement statement = scope.update(
            "update conversations c set " + String.join(", ", assignments), "c",
            predicate.toString(), parameters);
        return jdbc.update(statement.sql(), statement.parameters());
    }
}
