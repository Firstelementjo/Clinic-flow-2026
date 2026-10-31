package com.firstelement.crm.persistence;

import com.firstelement.crm.query.CrmQueryScope;
import com.firstelement.crm.query.LikePattern;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code customers} and their read projections.
 *
 * <h2>The list projection</h2>
 *
 * <p>Ordering is {@code lastSeenAt desc, id desc} — the legacy order, and the reason the
 * people who just wrote in are at the top. Search is case-insensitive over CX id, display
 * name, primary email and primary phone, with the wildcards escaped by {@link LikePattern}.
 *
 * <h2>Whole customer 360</h2>
 *
 * <p>{@link #findDetailInScope} composes four scoped reads — the customer row, its
 * identities, its conversations, and its leads/appointments — instead of one wide join.
 * A join with {@code take: 50} sub-selects is not expressible in plain SQL without window
 * functions per child, and four scoped statements are both clearer and easier to prove
 * tenant-safe. Every one of them carries the same {@link CrmQueryScope}.
 *
 * <h2>The list reads a page, not a row at a time</h2>
 *
 * <p>{@link #list} returns up to {@code limit + 1} customers, and each of those needs a
 * {@code latestTicket}, up to five leads and up to five appointments. Read per row that is
 * 1 + 3 × limit statements — 151 for the default page — and every one of them is index-served
 * and fast, which is what makes the shape so easy to miss: the database is not slow, the
 * request just pays a round trip per customer. {@link #latestTickets}, {@link #leadsFor} and
 * {@link #appointmentsFor} are the batched forms of those three reads; each returns a map keyed
 * by customer id, and a customer with no rows is absent from that map rather than present with
 * an empty one, so "nothing to show" and "not loaded" cannot be confused.
 */
@Repository
public class CustomerRepository {

    static final String SELECT_COLUMNS = """
        select c."id", c."organizationId", c."projectId", c."cxId", c."displayName",
               c."primaryEmail", c."primaryPhone", c."locale", c."tags", c."notes",
               c."metadata", c."firstSeenAt", c."lastSeenAt", c."createdAt", c."updatedAt",
               p."name" as "projectName",
               (select count(*) from conversations k where k."customerId" = c."id"
                  and k."organizationId" = c."organizationId" and k."projectId" = c."projectId")
                   as "conversationCount"
        from customers c
        join projects p on p."id" = c."projectId" and p."organizationId" = c."organizationId"
        """;

    /**
     * The batched lead projection for a whole page.
     *
     * <p>{@code %s} is the tenant predicate for the {@code l} alias, substituted by
     * {@link String#formatted} so the statement text is read once — the same idiom
     * {@code ClinicRepository} uses, and the same reason: two copies of a statement are exactly
     * the pair that drifts apart, and the copy nobody edits is the one that starts returning a
     * wider set.
     *
     * <p>There is no {@code order by} and no {@code limit} in the constant. The sort is appended
     * by the caller because it is not part of the projection, and the per-customer bound is
     * {@code :perCustomerLimit} inside the {@code WHERE} — a statement-wide {@code limit} here
     * would truncate <em>customers</em>, not rows.
     */
    private static final String BATCH_LEADS = """
        select f."customerId", f."id", f."name", f."status", f."priority", f."ticketNumber",
               f."ticketDate", f."followUpDate", f."createdAt"
          from (
              select l."customerId", l."id", l."name", l."status", l."priority", l."ticketNumber",
                     l."ticketDate", l."followUpDate", l."createdAt",
                     row_number() over (partition by l."customerId"
                                        order by l."createdAt" desc) as "withinCustomer"
                from "Lead" l
               where %s
                 and l."customerId" in (:customerIds)
          ) f
         where f."withinCustomer" <= :perCustomerLimit
        """;

    /** The batched appointment projection for a whole page; {@code %s} is the {@code a} predicate. */
    private static final String BATCH_APPOINTMENTS = """
        select f."customerId", f."id", f."title", f."appointmentDate", f."durationMinutes",
               f."status", f."service", f."doctorName", f."notes", f."ticketNumber",
               f."ticketDate", f."createdAt"
          from (
              select a."customerId", a."id", a."title", a."appointmentDate", a."durationMinutes",
                     a."status", a."service", a."doctorName", a."notes", a."ticketNumber",
                     a."ticketDate", a."createdAt",
                     row_number() over (partition by a."customerId"
                                        order by a."appointmentDate" desc) as "withinCustomer"
                from appointments a
               where %s
                 and a."customerId" in (:customerIds)
          ) f
         where f."withinCustomer" <= :perCustomerLimit
        """;

    static final RowMapper<CustomerRow> ROW = (rs, rowNum) -> new CustomerRow(
        rs.getObject("id", UUID.class),
        rs.getObject("organizationId", UUID.class),
        rs.getObject("projectId", UUID.class),
        rs.getString("cxId"),
        rs.getString("displayName"),
        rs.getString("primaryEmail"),
        rs.getString("primaryPhone"),
        rs.getString("locale"),
        readTextArray(rs, "tags"),
        rs.getString("notes"),
        rs.getString("metadata"),
        rs.getObject("firstSeenAt", LocalDateTime.class),
        rs.getObject("lastSeenAt", LocalDateTime.class),
        rs.getObject("createdAt", LocalDateTime.class),
        rs.getObject("updatedAt", LocalDateTime.class),
        rs.getString("projectName"),
        rs.getInt("conversationCount"));

    /**
     * Reads a {@code text[]} column.
     *
     * <p>{@code customers."tags"} is {@code text[]} and the driver hands it back as a
     * {@link java.sql.Array}; a NULL array is an empty list, not a failure, because the column
     * defaults to {@code ARRAY[]} but an older row may still hold null.
     */
    private static List<String> readTextArray(java.sql.ResultSet rs, String column)
        throws java.sql.SQLException {
        java.sql.Array array = rs.getArray(column);
        if (array == null) {
            return List.of();
        }
        Object raw = array.getArray();
        if (raw instanceof String[] values) {
            return List.of(values);
        }
        return List.of();
    }

    private final NamedParameterJdbcTemplate jdbc;

    public CustomerRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------
    // reads
    // ------------------------------------------------------------------

    /**
     * The customer list, one over-fetched page.
     *
     * @param cursorBoundary {@code null} for the first page
     */
    public List<CustomerRow> list(CrmQueryScope scope,
                                  String search,
                                  CustomerRepository.CursorBoundary cursorBoundary,
                                  int fetchLimit) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        List<String> conditions = new ArrayList<>();
        String trimmed = search == null ? null : search.trim();
        if (trimmed != null && !trimmed.isEmpty()) {
            // Case-insensitive on cxId / displayName / primaryEmail; primaryPhone was an exact
            // `contains` in the legacy filter and stays case-sensitive-safe by escaping too.
            conditions.add("(c.\"cxId\" ilike :customerSearch escape '\\'"
                + " or c.\"displayName\" ilike :customerSearch escape '\\'"
                + " or c.\"primaryEmail\" ilike :customerSearch escape '\\'"
                + " or c.\"primaryPhone\" like :customerSearch escape '\\')");
            parameters.put("customerSearch", LikePattern.contains(trimmed));
        }
        if (cursorBoundary != null) {
            conditions.add("(c.\"lastSeenAt\" < :cursorLastSeenAt"
                + " or (c.\"lastSeenAt\" = :cursorLastSeenAt and c.\"id\" < :cursorId))");
            parameters.put("cursorLastSeenAt", cursorBoundary.lastSeenAt());
            parameters.put("cursorId", cursorBoundary.id());
        }
        String additional = conditions.isEmpty() ? null : String.join(" and ", conditions);

        CrmQueryScope.Statement statement = scope.select(SELECT_COLUMNS, "c", additional, parameters)
            .orderedBy("c.\"lastSeenAt\" desc, c.\"id\" desc", fetchLimit);
        return jdbc.query(statement.sql(), statement.parameters(), ROW);
    }

    public Optional<CustomerRow> findInScope(CrmQueryScope scope, UUID customerId) {
        CrmQueryScope.Statement statement = scope.select(SELECT_COLUMNS, "c", "c.\"id\" = :customerId",
            CrmQueryScope.params("customerId", customerId));
        return jdbc.query(statement.sql(), statement.parameters(), ROW).stream().findFirst();
    }

    public Optional<CustomerRow> findByCxIdInScope(CrmQueryScope scope, String cxId) {
        CrmQueryScope.Statement statement = scope.select(SELECT_COLUMNS, "c",
            "c.\"cxId\" = :cxId", CrmQueryScope.params("cxId", cxId));
        return jdbc.query(statement.sql(), statement.parameters(), ROW).stream().findFirst();
    }

    /** The keyset boundary a customer-list cursor resolves to. */
    public Optional<CursorBoundary> cursorBoundary(CrmQueryScope scope, UUID cursor) {
        CrmQueryScope.Statement statement = scope.select(
            "select c.\"lastSeenAt\", c.\"id\" from customers c", "c", "c.\"id\" = :cursor",
            CrmQueryScope.params("cursor", cursor));
        return jdbc.query(statement.sql(), statement.parameters(),
            (rs, rowNum) -> new CursorBoundary(
                rs.getObject("lastSeenAt", LocalDateTime.class),
                rs.getObject("id", UUID.class))).stream().findFirst();
    }

    // ------------------------------------------------------------------
    // child projections
    // ------------------------------------------------------------------

    /** The customer's handles, oldest first. Legacy ordered by {@code createdAt asc}. */
    public List<IdentityRow> identities(CrmQueryScope scope, UUID customerId) {
        CrmQueryScope.Statement statement = scope.select(
            """
            select i."id", i."platform", i."externalId", i."displayName", i."isVerified", i."createdAt"
            from customer_identities i
            """, "i", "i.\"customerId\" = :customerId",
            CrmQueryScope.params("customerId", customerId))
            .orderedBy("i.\"createdAt\" asc, i.\"id\" asc", 200);
        return jdbc.query(statement.sql(), statement.parameters(),
            (rs, rowNum) -> new IdentityRow(
                rs.getObject("id", UUID.class),
                rs.getString("platform"),
                rs.getString("externalId"),
                rs.getString("displayName"),
                rs.getBoolean("isVerified"),
                rs.getObject("createdAt", LocalDateTime.class)));
    }

    /** The customer's conversations, most recently active first, capped at 50 as legacy did. */
    public List<CustomerConversationRow> conversations(CrmQueryScope scope, UUID customerId, int limit) {
        CrmQueryScope.Statement statement = scope.select(
            """
            select k."id", k."ticketNumber", k."channel", k."status", k."mode", k."priority",
                   k."subject", k."messageCount", k."lastMessageAt", k."createdAt",
                   eu."fullName" as "assigneeFullName"
            from conversations k
            left join employees e on e."id" = k."assignedEmployeeId"
            left join users eu on eu."id" = e."userId"
            """, "k", "k.\"customerId\" = :customerId",
            CrmQueryScope.params("customerId", customerId))
            .orderedBy("k.\"lastMessageAt\" desc", limit);
        return jdbc.query(statement.sql(), statement.parameters(),
            (rs, rowNum) -> new CustomerConversationRow(
                rs.getObject("id", UUID.class),
                rs.getString("ticketNumber"),
                rs.getString("channel"),
                rs.getString("status"),
                rs.getString("mode"),
                rs.getString("priority"),
                rs.getString("subject"),
                rs.getInt("messageCount"),
                rs.getObject("lastMessageAt", LocalDateTime.class),
                rs.getObject("createdAt", LocalDateTime.class),
                rs.getString("assigneeFullName")));
    }

    /**
     * The five most recent leads for this customer.
     *
     * <p>{@code Lead} is capitalised in the physical schema (no {@code @@map}); the identifier
     * is written by hand here, which is exactly the kind of detail the explicit-mapping rule
     * exists to keep visible.
     */
    public List<LeadRow> leads(CrmQueryScope scope, UUID customerId, int limit) {
        CrmQueryScope.Statement statement = scope.select(
            """
            select l."id", l."name", l."status", l."priority", l."ticketNumber", l."ticketDate",
                   l."followUpDate", l."createdAt"
            from "Lead" l
            """, "l", "l.\"customerId\" = :customerId",
            CrmQueryScope.params("customerId", customerId))
            .orderedBy("l.\"createdAt\" desc", limit);
        return jdbc.query(statement.sql(), statement.parameters(),
            (rs, rowNum) -> new LeadRow(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("status"),
                rs.getString("priority"),
                rs.getString("ticketNumber"),
                rs.getObject("ticketDate", LocalDateTime.class),
                rs.getObject("followUpDate", LocalDateTime.class),
                rs.getObject("createdAt", LocalDateTime.class)));
    }

    /** All of a customer's appointments, most recent first. The legacy detail view did not cap. */
    public List<AppointmentRow> appointments(CrmQueryScope scope, UUID customerId, int limit) {
        CrmQueryScope.Statement statement = scope.select(
            """
            select a."id", a."title", a."appointmentDate", a."durationMinutes", a."status",
                   a."service", a."doctorName", a."notes", a."ticketNumber", a."ticketDate",
                   a."createdAt"
            from appointments a
            """, "a", "a.\"customerId\" = :customerId",
            CrmQueryScope.params("customerId", customerId))
            .orderedBy("a.\"appointmentDate\" desc", limit);
        return jdbc.query(statement.sql(), statement.parameters(),
            (rs, rowNum) -> new AppointmentRow(
                rs.getObject("id", UUID.class),
                rs.getString("title"),
                rs.getObject("appointmentDate", LocalDateTime.class),
                rs.getObject("durationMinutes", Integer.class),
                rs.getString("status"),
                rs.getString("service"),
                rs.getString("doctorName"),
                rs.getString("notes"),
                rs.getString("ticketNumber"),
                rs.getObject("ticketDate", LocalDateTime.class),
                rs.getObject("createdAt", LocalDateTime.class)));
    }

    /**
     * The newest conversation for ONE customer: the customer-list {@code latestTicket}
     * projection.
     *
     * <p>Kept deliberately, next to the batched {@link #latestTickets}: it is the definition the
     * batched statement is proven equal to, and {@code CustomerListLatestTicketEquivalence} is
     * what keeps that promise honest. Deleting it as "now unused" would leave the batched
     * ordering with no reference to be compared against, which is the condition under which an
     * optimisation quietly stops being equivalent.
     */
    public Optional<LatestTicketRow> latestTicket(CrmQueryScope scope, UUID customerId) {
        CrmQueryScope.Statement statement = scope.select(
            """
            select k."id", k."ticketNumber", k."createdAt", k."status", k."subject"
            from conversations k
            """, "k", "k.\"customerId\" = :customerId",
            CrmQueryScope.params("customerId", customerId))
            .orderedBy("k.\"createdAt\" desc", 1);
        return jdbc.query(statement.sql(), statement.parameters(),
            (rs, rowNum) -> new LatestTicketRow(
                rs.getObject("id", UUID.class),
                rs.getString("ticketNumber"),
                rs.getObject("createdAt", LocalDateTime.class),
                rs.getString("status"),
                rs.getString("subject"))).stream().findFirst();
    }

    // ------------------------------------------------------------------
    // batched child projections
    // ------------------------------------------------------------------

    /**
     * The newest conversation for <em>every</em> customer on a page, in one statement.
     *
     * <h2>Why this exists</h2>
     *
     * <p>{@link #latestTicket(CrmQueryScope, UUID)} is the definition of the customer-list
     * {@code latestTicket}: newest conversation by {@code createdAt desc}, or absent. Calling it
     * once per row made {@code GET /api/v1/customers?limit=50} issue one statement per customer,
     * and the database answered all 50 correctly in about half a millisecond. The request cost
     * was 50 round trips, and the measured p95 was over the section-7 budget while every one of
     * those statements was index-served.
     *
     * <p>{@code DISTINCT ON (k."customerId")} is the batched form of the same decision: the sort
     * leads with the partitioned expression, so each customer keeps its own newest row and the
     * "newest wins" rule is the sort, not a post-processing step that could disagree with it.
     *
     * <h2>The scope is this repository's, never a caller's</h2>
     *
     * <p>The tenant predicate comes from {@link CrmQueryScope} exactly as it does in
     * {@link #latestTicket}, and the ids are the only thing a caller supplies. A caller that
     * passes an id from another tenant gets no row for it — the {@code in} list narrows a set
     * that is already scoped, it cannot widen one.
     *
     * @return newest conversation per customer; a customer with none is <em>absent</em> from the
     *     map rather than mapped to null, so "no conversation" cannot be confused with "a
     *     conversation whose fields are all null"
     */
    public Map<UUID, LatestTicketRow> latestTickets(CrmQueryScope scope, List<UUID> customerIds) {
        if (customerIds.isEmpty()) {
            return Map.of();
        }
        CrmQueryScope.Statement statement = scope.select(
            """
            select distinct on (k."customerId")
                   k."customerId", k."id", k."ticketNumber", k."createdAt", k."status", k."subject"
            from conversations k
            """, "k", "k.\"customerId\" in (:customerIds)",
            CrmQueryScope.params("customerIds", customerIds))
            .orderedBy("k.\"customerId\" asc, k.\"createdAt\" desc");
        Map<UUID, LatestTicketRow> byCustomer = new LinkedHashMap<>();
        for (LatestTicketBatchRow row : jdbc.query(statement.sql(), statement.parameters(),
            (rs, rowNum) -> new LatestTicketBatchRow(
                rs.getObject("customerId", UUID.class),
                new LatestTicketRow(
                    rs.getObject("id", UUID.class),
                    rs.getString("ticketNumber"),
                    rs.getObject("createdAt", LocalDateTime.class),
                    rs.getString("status"),
                    rs.getString("subject"))))) {
            byCustomer.put(row.customerId(), row.ticket());
        }
        return Map.copyOf(byCustomer);
    }

    /**
     * The most recent leads for every customer on a page, in one statement.
     *
     * <h2>Why a window function and not {@code DISTINCT ON}</h2>
     *
     * <p>The customer list carries up to {@code perCustomerLimit} leads per customer, so the
     * batched form has to reproduce a <em>per-group</em> bound. A statement-wide {@code limit}
     * would truncate customers rather than rows — a page of 50 whose first customer has 5 leads
     * would return 5 customers and 45 empty lists. So the bound is {@code row_number()} inside
     * the partition, the same shape {@code ClinicRepository.FUNNEL_BY_STATUS} uses, and
     * {@code :perCustomerLimit} is a bound parameter rather than spliced text.
     *
     * <p>The inner {@code order by l."createdAt" desc} is character-for-character the ordering
     * {@link #leads(CrmQueryScope, UUID, int)} uses, so a customer whose leads tie on
     * {@code createdAt} is as unspecified here as it was before — the batch does not invent a
     * tie-break the single-customer query did not have.
     *
     * @return leads per customer, each list in {@code createdAt desc} order; a customer with no
     *     lead is absent from the map
     */
    public Map<UUID, List<LeadRow>> leadsFor(CrmQueryScope scope, List<UUID> customerIds,
                                             int perCustomerLimit) {
        if (customerIds.isEmpty()) {
            return Map.of();
        }
        // The tenant predicate goes INSIDE the subquery, which is why this statement is not
        // built through scope.select: that helper appends its predicate to the statement's own
        // WHERE, and here the outer WHERE is the per-customer bound, not the tenant. The
        // predicate still comes from the scope and nowhere else, and it is applied before
        // row_number() so the bound counts in-scope leads only.
        CrmQueryScope.Statement statement = new CrmQueryScope.Statement(
            BATCH_LEADS.formatted(scope.predicate("l"))
                + " order by f.\"customerId\" asc, f.\"withinCustomer\" asc",
            scope.bind(CrmQueryScope.params(
                "customerIds", customerIds, "perCustomerLimit", perCustomerLimit)));
        return groupByCustomer(jdbc.query(statement.sql(), statement.parameters(),
            (rs, rowNum) -> new LeadBatchRow(
                rs.getObject("customerId", UUID.class),
                new LeadRow(
                    rs.getObject("id", UUID.class),
                    rs.getString("name"),
                    rs.getString("status"),
                    rs.getString("priority"),
                    rs.getString("ticketNumber"),
                    rs.getObject("ticketDate", LocalDateTime.class),
                    rs.getObject("followUpDate", LocalDateTime.class),
                    rs.getObject("createdAt", LocalDateTime.class)))));
    }

    /**
     * The most recent appointments for every customer on a page, in one statement.
     *
     * <p>The mirror of {@link #leadsFor}: same {@code row_number()} bound, same bound parameter,
     * same {@code appointmentDate desc} ordering the single-customer {@link #appointments} uses,
     * and the same scope predicate it uses.
     *
     * @return appointments per customer, each list in {@code appointmentDate desc} order
     */
    public Map<UUID, List<AppointmentRow>> appointmentsFor(CrmQueryScope scope, List<UUID> customerIds,
                                                           int perCustomerLimit) {
        if (customerIds.isEmpty()) {
            return Map.of();
        }
        CrmQueryScope.Statement statement = new CrmQueryScope.Statement(
            BATCH_APPOINTMENTS.formatted(scope.predicate("a"))
                + " order by f.\"customerId\" asc, f.\"withinCustomer\" asc",
            scope.bind(CrmQueryScope.params(
                "customerIds", customerIds, "perCustomerLimit", perCustomerLimit)));
        return groupByCustomer(jdbc.query(statement.sql(), statement.parameters(),
            (rs, rowNum) -> new AppointmentBatchRow(
                rs.getObject("customerId", UUID.class),
                new AppointmentRow(
                    rs.getObject("id", UUID.class),
                    rs.getString("title"),
                    rs.getObject("appointmentDate", LocalDateTime.class),
                    rs.getObject("durationMinutes", Integer.class),
                    rs.getString("status"),
                    rs.getString("service"),
                    rs.getString("doctorName"),
                    rs.getString("notes"),
                    rs.getString("ticketNumber"),
                    rs.getObject("ticketDate", LocalDateTime.class),
                    rs.getObject("createdAt", LocalDateTime.class)))));
    }

    /**
     * Collects a batch's rows into a per-customer map, preserving the order the statement
     * returned them in — which is the per-customer ordering the single-customer methods use.
     */
    private static <T> Map<UUID, List<T>> groupByCustomer(List<BatchRow<T>> rows) {
        Map<UUID, List<T>> byCustomer = new LinkedHashMap<>();
        for (BatchRow<T> row : rows) {
            byCustomer.computeIfAbsent(row.customerId(), id -> new ArrayList<>()).add(row.value());
        }
        Map<UUID, List<T>> result = new LinkedHashMap<>();
        byCustomer.forEach((id, values) -> result.put(id, List.copyOf(values)));
        return Map.copyOf(result);
    }

    /** A batched child row: which customer it belongs to, and the row itself. */
    private interface BatchRow<T> {
        UUID customerId();

        T value();
    }

    /** The newest conversation for one customer, carrying the id it was partitioned by. */
    private record LatestTicketBatchRow(UUID customerId, LatestTicketRow ticket)
        implements BatchRow<LatestTicketRow> {

        @Override
        public LatestTicketRow value() {
            return ticket;
        }
    }

    /** One lead of one customer, carrying the id it was partitioned by. */
    private record LeadBatchRow(UUID customerId, LeadRow lead) implements BatchRow<LeadRow> {

        @Override
        public LeadRow value() {
            return lead;
        }
    }

    /** One appointment of one customer, carrying the id it was partitioned by. */
    private record AppointmentBatchRow(UUID customerId, AppointmentRow appointment)
        implements BatchRow<AppointmentRow> {

        @Override
        public AppointmentRow value() {
            return appointment;
        }
    }

    // ------------------------------------------------------------------
    // writes
    // ------------------------------------------------------------------

    /**
     * The ids of customers matching an already-assembled statement.
     *
     * <p>Used by the identity resolver, which builds its own predicate from the verified signals
     * it was given (phone, email) and must not re-derive the predicate here.
     */
    public List<UUID> listIds(CrmQueryScope.Statement statement) {
        return jdbc.queryForList(statement.sql(), statement.parameters(), UUID.class);
    }

    /**
     * Customers whose display name equals {@code name}, case-insensitively.
     *
     * <p>Used only to produce the identity resolver's {@code possibleMatches}. The match is
     * reported to a human and never acted on — see {@code IdentityResolver}.
     */
    public List<NameRow> findByDisplayName(CrmQueryScope scope, String name, int limit) {
        // Case-insensitive equality, matching the legacy `equals, mode: 'insensitive'`.
        CrmQueryScope.Statement statement = scope.select(
            "select c.\"id\", c.\"displayName\" from customers c", "c",
            "lower(c.\"displayName\") = lower(:displayName)",
            CrmQueryScope.params("displayName", name))
            .orderedBy("c.\"lastSeenAt\" desc", limit);
        return jdbc.query(statement.sql(), statement.parameters(),
            (rs, rowNum) -> new NameRow(
                rs.getObject("id", UUID.class),
                rs.getString("displayName")));
    }

    /** A name-only customer candidate. */
    public record NameRow(UUID id, String displayName) {
    }

    /**
     * Applies a customer profile update.
     *
     * <p>Conditional on {@code updatedAt} when the caller supplied an {@code If-Match} token, so
     * two employees editing the same profile cannot silently overwrite each other.
     *
     * <p>{@code assignments} are complete {@code "column" = :parameter} fragments, built by
     * {@code CustomerService} from the fields the caller actually sent. The legacy code spread
     * the parsed input object into Prisma's {@code data}, which also meant an absent field was
     * not written; building the list explicitly keeps that rule with the field names under this
     * module's control instead of the parser's.
     *
     * @return rows changed: 1 applied, 0 collided, 0 not in scope
     */
    public int update(CrmQueryScope scope,
                      UUID customerId,
                      LocalDateTime expectedUpdatedAt,
                      List<String> assignments,
                      Map<String, Object> parameters,
                      LocalDateTime now) {
        if (assignments.isEmpty()) {
            return 1;
        }
        Map<String, Object> bound = new LinkedHashMap<>(parameters);
        bound.put("customerId", customerId);
        bound.put("now", now);

        List<String> sets = new ArrayList<>(assignments);
        sets.add("\"updatedAt\" = :now");

        StringBuilder predicate = new StringBuilder("c.\"id\" = :customerId");
        if (expectedUpdatedAt != null) {
            predicate.append(" and c.\"updatedAt\" = :expectedUpdatedAt");
            bound.put("expectedUpdatedAt", expectedUpdatedAt);
        }
        CrmQueryScope.Statement statement = scope.update(
            "update customers c set " + String.join(", ", sets), "c", predicate.toString(), bound);
        return jdbc.update(statement.sql(), statement.parameters());
    }

    /** Records that the customer was just seen; called by the identity resolver. */
    public int touchLastSeen(CrmQueryScope scope, UUID customerId, LocalDateTime seenAt) {
        CrmQueryScope.Statement statement = scope.update(
            "update customers c set \"lastSeenAt\" = :seenAt, \"updatedAt\" = :seenAt", "c",
            "c.\"id\" = :customerId",
            CrmQueryScope.params("customerId", customerId, "seenAt", seenAt));
        return jdbc.update(statement.sql(), statement.parameters());
    }

    /** Inserts a customer. The caller owns the {@code cxId} sequence and the collision retry. */
    public void insert(CrmQueryScope scope,
                       UUID customerId,
                       String cxId,
                       String displayName,
                       String primaryEmail,
                       String primaryPhone,
                       String locale,
                       String externalIdsJson,
                       LocalDateTime now) {
        jdbc.update("""
            insert into customers ("id", "organizationId", "projectId", "cxId", "displayName",
                "primaryEmail", "primaryPhone", "locale", "externalIds", "firstSeenAt",
                "lastSeenAt", "createdAt", "updatedAt")
            values (:id, :__fe_crm_organization_id, :__fe_crm_project_id, :cxId, :displayName,
                :primaryEmail, :primaryPhone, cast(:locale as "Locale"),
                CAST(:externalIds AS jsonb), :now, :now, :now, :now)
            """, scope.bind(CrmQueryScope.params(
                "id", customerId, "cxId", cxId, "displayName", displayName,
                "primaryEmail", primaryEmail, "primaryPhone", primaryPhone,
                "locale", locale, "externalIds", externalIdsJson, "now", now)));
    }

    /**
     * The next customer reference for this tenant: {@code #33} + six digits.
     *
     * <p><b>Why this is a function call and not arithmetic here (ADR-0014, defect CX-1).</b>
     *
     * <p>The previous {@code nextCxSequence} answered with a project-scoped
     * {@code select count(*) from customers} and the previous {@code cxIdExists} took no scope at
     * all. Together with a globally unique {@code cxId} and a five-attempt walk-forward they made
     * the reference namespace a <em>scarce, exhaustible resource</em>: every project started its
     * search at {@code #33000000}, five projects consumed {@code #33000000}…{@code #33000004}, and
     * the sixth project's first customer threw — so at most five references could ever exist across
     * the whole deployment, and every inbound message for that project answered 500 permanently.
     *
     * <p>A count is not a sequence, either. Deleting a customer reopens a gap <em>below</em> the
     * high-water mark, so a later customer can be issued a reference a deleted customer once held,
     * and any surviving URL, export, audit record or invoice naming it resolves to the wrong person.
     * The allocator holds the high-water mark in {@code customer_cx_sequences} instead, which is
     * exactly the fact a row count cannot express.
     *
     * <p>The function is one statement — {@code INSERT … ON CONFLICT ("organizationId") DO UPDATE
     * … RETURNING} — so two concurrent writers in one organization are serialised by the row lock
     * and receive different references. It contains no {@code count(*)}.
     *
     * <p><b>Do not reformat the result.</b> The function returns the finished {@code #33XXXXXX}
     * string; it has already applied the {@code '#33' || lpad(seq, 6, '0')} formatting. Feeding a
     * raw counter to {@link com.firstelement.crm.domain.TicketNumber#generateCxId} would
     * double-format it and produce a wrong-shaped id.
     *
     * <p>The organization comes from {@link CrmQueryScope#organizationId()}, which the caller can
     * only obtain from the authenticated {@link com.firstelement.security.TenantContext} — so the
     * allocator cannot be pointed at another tenant by a request. That is the entire reason this
     * method takes a scope rather than a bare {@link UUID}.
     *
     * @throws IllegalStateException if the allocator returns NULL, which would mean the function
     *     was replaced by something that is not this one
     */
    public String nextCxId(CrmQueryScope scope) {
        if (scope == null) {
            throw new IllegalStateException(
                "Allocating a customer reference requires a tenant scope; refusing to allocate unscoped.");
        }
        String cxId = jdbc.queryForObject(
            "select public.firstelement_next_customer_cx_id(:__fe_crm_organization_id)",
            new org.springframework.jdbc.core.namedparam.MapSqlParameterSource(
                CrmQueryScope.ORGANIZATION_PARAM, scope.organizationId()),
            String.class);
        if (cxId == null) {
            throw new IllegalStateException(
                "public.firstelement_next_customer_cx_id returned NULL for organization "
                    + scope.organizationId() + "; the allocator has been replaced by something "
                    + "that is not the ADR-0014 allocator.");
        }
        return cxId;
    }

    // ------------------------------------------------------------------

    public record CursorBoundary(LocalDateTime lastSeenAt, UUID id) {
    }

    /** One customer row. */
    public record CustomerRow(UUID id,
                              UUID organizationId,
                              UUID projectId,
                              String cxId,
                              String displayName,
                              String primaryEmail,
                              String primaryPhone,
                              String locale,
                              List<String> tags,
                              String notes,
                              String metadata,
                              LocalDateTime firstSeenAt,
                              LocalDateTime lastSeenAt,
                              LocalDateTime createdAt,
                              LocalDateTime updatedAt,
                              String projectName,
                              int conversationCount) {
    }

    public record IdentityRow(UUID id,
                              String platform,
                              String externalId,
                              String displayName,
                              boolean isVerified,
                              LocalDateTime createdAt) {
    }

    public record CustomerConversationRow(UUID id,
                                           String ticketNumber,
                                           String channel,
                                           String status,
                                           String mode,
                                           String priority,
                                           String subject,
                                           int messageCount,
                                           LocalDateTime lastMessageAt,
                                           LocalDateTime createdAt,
                                           String assigneeFullName) {
    }

    public record LeadRow(UUID id,
                          String name,
                          String status,
                          String priority,
                          String ticketNumber,
                          LocalDateTime ticketDate,
                          LocalDateTime followUpDate,
                          LocalDateTime createdAt) {
    }

    public record AppointmentRow(UUID id,
                                 String title,
                                 LocalDateTime appointmentDate,
                                 Integer durationMinutes,
                                 String status,
                                 String service,
                                 String doctorName,
                                 String notes,
                                 String ticketNumber,
                                 LocalDateTime ticketDate,
                                 LocalDateTime createdAt) {
    }

    public record LatestTicketRow(UUID id,
                                  String ticketNumber,
                                  LocalDateTime createdAt,
                                  String status,
                                  String subject) {
    }
}