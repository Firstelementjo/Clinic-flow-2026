package com.firstelement.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;

import com.firstelement.crm.api.AppointmentSummary;
import com.firstelement.crm.api.CustomerListItem;
import com.firstelement.crm.api.FollowUpSummary;
import com.firstelement.crm.api.Timestamps;
import com.firstelement.crm.audit.CrmAuditWriter;
import com.firstelement.crm.persistence.CustomerRepository;
import com.firstelement.crm.persistence.JsonCodec;
import com.firstelement.crm.query.CrmQueryScope;
import com.firstelement.crm.query.CursorPage;
import com.firstelement.crm.service.CustomerService;
import com.firstelement.security.TenantContext;
import com.firstelement.security.query.TenantQueryGuard;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * The customer list reads a <em>page</em>, not one customer at a time.
 *
 * <h2>The defect this suite exists for</h2>
 *
 * <p>{@code GET /api/v1/customers?limit=50} was measured at p50 877 ms / p95 2540 ms against the
 * section-7 budget of 500 ms, on the 40 000-customer dataset. The database was not the problem:
 * EXPLAIN (ANALYZE, BUFFERS) on the real seeded database put the page query itself at 25 ms,
 * with a bitmap heap scan, a top-N heapsort and an index-served correlated
 * {@code conversationCount}. The cost was the 50 statements that followed it — one per row, each
 * correct, each a separate round trip.
 *
 * <p><b>There were three of those, not one.</b> {@code CustomerService.toListItem} read the
 * {@code latestTicket}, the follow-ups and the appointments per row, so a 50-row page issued
 * {@code 1 + 3 × 50 = 151} statements. A count taken from the {@code latestTicket} call alone
 * reads as 51 and looks like the whole story; it is a third of it.
 *
 * <h2>What is asserted, and why none of it is a timing assertion</h2>
 *
 * <p>A latency assertion would be flaky on a shared machine and would pass for the wrong reason
 * often enough to be worthless. What is asserted instead is the property that <em>caused</em> the
 * latency, and it is asserted three ways:
 *
 * <ol>
 *   <li><b>Equivalence</b> — for the same rows, the batched projections equal the per-row ones,
 *       customer by customer, including the customers that have no conversations, no leads and
 *       no appointments. Asserted on the DTO the API actually publishes, not on a log line,
 *       because ADR 0016/0017 make the published shape the thing that must not move.</li>
 *   <li><b>Scope</b> — the batched reads carry the caller's scope. An id from a project the
 *       caller cannot reach yields nothing from the batch, with a positive control on a project
 *       the caller <em>can</em> reach so an all-empty result cannot satisfy it.</li>
 *   <li><b>Shape</b> — the statement count for a page, measured through a spy on the repository.
 *       This is the load-bearing one: it goes red the moment the per-row read comes back, and it
 *       does not depend on the wall clock.</li>
 * </ol>
 *
 * <p>The service under test is constructed by hand around a spy. {@code CustomerRepository} is a
 * {@code @Repository}, so the autowired bean is a CGLIB proxy of exception translation and cannot
 * itself be spied; a spy over the same {@code NamedParameterJdbcTemplate} is the same repository
 * with a call counter around it. The permission aspect is not on this instance — it is not
 * bypassed for convenience, it is simply not part of what this suite measures, and the
 * authorization of the list route is asserted in {@code CrmCoreIntegrationTest} and
 * {@code SecurityBoundaryHttpIntegrationTest} against the proxied bean.
 */
@SpringBootTest(classes = CrmTestApplication.class)
class CustomerListProjectionIntegrationTest extends CrmIntegrationTestBase {

    /** Customers beyond the fixture's two, so a 50-row page is a real page. */
    private static final int BULK = 60;

    @Autowired
    private CrmFixture fixture;

    @Autowired
    private JsonCodec json;

    @Autowired
    private TenantQueryGuard guard;

    @Autowired
    private CrmAuditWriter audit;

    private CustomerRepository repository;
    private TenantContext admin;
    private TenantContext staff;
    private List<UUID> bulk;

    @BeforeEach
    void setUp() {
        fixture.reset();
        repository = new CustomerRepository(fixture.jdbc());
        admin = fixture.adminAContext();
        staff = fixture.staffAContext();
        PrincipalScope.bind(admin);
        bulk = seedCustomers(BULK);
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        PrincipalScope.clear();
    }

    // ==================================================================
    // 1. the batched projections are the per-row projections
    // ==================================================================

    @Nested
    @DisplayName("the batched projections are the per-row projections")
    class BatchedProjectionsMatchThePerRowQueries {

        @Test
        @DisplayName("every customer on the page gets the same latestTicket the per-row query gives")
        void latestTicketBatchEqualsThePerRowQuery() {
            // Three conversations for one customer so 'newest wins' is a decision and not an
            // accident of insertion order, one for another, and none at all for the third.
            LocalDateTime base = LocalDateTime.of(2026, 3, 1, 9, 0);
            UUID busy = bulk.get(0);
            UUID quiet = bulk.get(1);
            UUID silent = bulk.get(2);
            insertConversation(busy, base.withHour(8), "#081400000001");
            insertConversation(busy, base.withHour(9), "#081400000002");
            insertConversation(busy, base.withHour(10), "#081400000003");
            insertConversation(quiet, base.withHour(11), "#081400000004");

            CrmQueryScope scope = CrmQueryScope.accessibleProjects(admin);
            Map<UUID, CustomerRepository.LatestTicketRow> expected = new LinkedHashMap<>();
            for (UUID id : List.of(busy, quiet, silent)) {
                repository.latestTicket(scope, id).ifPresent(ticket -> expected.put(id, ticket));
            }

            assertThat(repository.latestTickets(scope, List.of(busy, quiet, silent)))
                .as("the batched latestTicket is the per-row latestTicket, for every customer")
                .isEqualTo(expected);
            assertThat(expected).containsOnlyKeys(busy, quiet);
        }

        @Test
        @DisplayName("a customer with no conversation has no latestTicket at all, not a blank one")
        void aCustomerWithNoConversationIsAbsent() {
            UUID withOne = bulk.get(0);
            UUID withNone = bulk.get(1);
            insertConversation(withOne, LocalDateTime.of(2026, 3, 1, 9, 0), "#081400000001");

            CrmQueryScope scope = CrmQueryScope.accessibleProjects(admin);
            Map<UUID, CustomerRepository.LatestTicketRow> batch =
                repository.latestTickets(scope, List.of(withOne, withNone));

            // Absent from the map, not present-and-null: the two are different facts and only
            // one of them is true.
            assertThat(batch).containsOnlyKeys(withOne);
            assertThat(repository.latestTicket(scope, withNone)).isEmpty();
        }

        @Test
        @DisplayName("the published list item is identical whether the ticket came in a batch or a row")
        void thePublishedItemIsUnchanged() {
            LocalDateTime base = LocalDateTime.of(2026, 3, 1, 9, 0);
            UUID withTwo = bulk.get(0);
            UUID withNone = bulk.get(1);
            insertConversation(withTwo, base, "#081400000001", "PENDING", "First");
            insertConversation(withTwo, base.plusHours(4), "#081400000002", "OPEN", "Second");

            CursorPage<CustomerListItem> page = realService().list(admin, null, null, null, 50);
            Map<UUID, CustomerListItem> items = byId(page.items());

            CrmQueryScope scope = CrmQueryScope.accessibleProjects(admin);
            for (CustomerListItem item : page.items()) {
                // Recomputed from the per-row query, independently of the batch.
                CustomerListItem.LatestTicket expected = repository.latestTicket(scope, item.id())
                    .map(ticket -> new CustomerListItem.LatestTicket(
                        ticket.id(), ticket.ticketNumber(), Timestamps.toIso(ticket.createdAt()),
                        ticket.status(), ticket.subject()))
                    .orElse(null);

                assertThat(item.latestTicket())
                    .as("latestTicket for %s", item.cxId())
                    .isEqualTo(expected);
            }
            assertThat(items.get(withTwo).latestTicket().ticketNumber()).isEqualTo("#081400000002");
            assertThat(items.get(withNone).latestTicket()).isNull();
        }

        @Test
        @DisplayName("the follow-up batch is the same five leads in the same order")
        void leadBatchEqualsThePerRowQuery() {
            UUID many = bulk.get(0);
            UUID few = bulk.get(1);
            UUID none = bulk.get(2);
            // Seven leads for one customer, so the per-customer bound of five is a bound and not
            // an accident of how much data the fixture happens to hold.
            for (int i = 0; i < 7; i++) {
                insertLead(many, "lead-" + i, LocalDateTime.of(2026, 3, 1, 9, 0).plusDays(i));
            }
            insertLead(few, "only-lead", LocalDateTime.of(2026, 3, 1, 9, 0));

            CrmQueryScope scope = CrmQueryScope.accessibleProjects(admin);
            List<UUID> ids = List.of(many, few, none);
            Map<UUID, List<CustomerRepository.LeadRow>> expected = new LinkedHashMap<>();
            for (UUID id : ids) {
                List<CustomerRepository.LeadRow> rows = repository.leads(scope, id, 5);
                if (!rows.isEmpty()) {
                    expected.put(id, rows);
                }
            }

            Map<UUID, List<CustomerRepository.LeadRow>> batch = repository.leadsFor(scope, ids, 5);

            assertThat(batch).isEqualTo(expected);
            assertThat(batch.get(many)).hasSize(5);
            assertThat(batch.get(many)).extracting(CustomerRepository.LeadRow::name)
                // createdAt desc: the two newest of the seven, newest first.
                .containsExactly("lead-6", "lead-5", "lead-4", "lead-3", "lead-2");
            assertThat(batch).containsOnlyKeys(many, few);
        }

        @Test
        @DisplayName("the appointment batch is the same five appointments in the same order")
        void appointmentBatchEqualsThePerRowQuery() {
            UUID many = bulk.get(0);
            UUID few = bulk.get(1);
            UUID none = bulk.get(2);
            for (int i = 0; i < 7; i++) {
                insertAppointment(many, "appt-" + i, LocalDateTime.of(2026, 4, 1, 9, 0).plusDays(i));
            }
            insertAppointment(few, "only-appt", LocalDateTime.of(2026, 4, 1, 9, 0));

            CrmQueryScope scope = CrmQueryScope.accessibleProjects(admin);
            List<UUID> ids = List.of(many, few, none);
            Map<UUID, List<CustomerRepository.AppointmentRow>> expected = new LinkedHashMap<>();
            for (UUID id : ids) {
                List<CustomerRepository.AppointmentRow> rows = repository.appointments(scope, id, 5);
                if (!rows.isEmpty()) {
                    expected.put(id, rows);
                }
            }

            Map<UUID, List<CustomerRepository.AppointmentRow>> batch =
                repository.appointmentsFor(scope, ids, 5);

            assertThat(batch).isEqualTo(expected);
            assertThat(batch.get(many)).extracting(CustomerRepository.AppointmentRow::title)
                .containsExactly("appt-6", "appt-5", "appt-4", "appt-3", "appt-2");
            assertThat(batch).containsOnlyKeys(many, few);
        }

        @Test
        @DisplayName("the published follow-ups and appointments are the ones the per-row query returns")
        void thePublishedChildrenAreUnchanged() {
            UUID many = bulk.get(0);
            UUID none = bulk.get(1);
            for (int i = 0; i < 7; i++) {
                insertLead(many, "lead-" + i, LocalDateTime.of(2026, 3, 1, 9, 0).plusDays(i));
                insertAppointment(many, "appt-" + i, LocalDateTime.of(2026, 4, 1, 9, 0).plusDays(i));
            }

            CursorPage<CustomerListItem> page = realService().list(admin, null, null, null, 50);
            CrmQueryScope scope = CrmQueryScope.accessibleProjects(admin);

            for (CustomerListItem item : page.items()) {
                assertThat(item.followUps())
                    .as("followUps for %s", item.cxId())
                    .containsExactlyElementsOf(expectedFollowUps(scope, item.id()));
                assertThat(item.appointments())
                    .as("appointments for %s", item.cxId())
                    .containsExactlyElementsOf(expectedAppointments(scope, item.id()));
            }
            assertThat(byId(page.items()).get(many).followUps()).hasSize(5);
            assertThat(byId(page.items()).get(none).followUps()).isEmpty();
            assertThat(byId(page.items()).get(none).appointments()).isEmpty();
        }

        @Test
        @DisplayName("the batch is scoped: an unreachable project's id yields nothing from it")
        void theBatchCarriesTheCallersScope() {
            UUID mine = bulk.get(0);
            UUID theirs = fixture.customerA2;   // project A2, which staff cannot reach
            insertConversation(mine, LocalDateTime.of(2026, 3, 1, 9, 0), "#081400000001");
            fixture.insertConversation(fixture.orgA, fixture.projectA2, theirs,
                "#081400000002", "MOCK", "OPEN", "HUMAN");

            CrmQueryScope a1 = CrmQueryScope.forProject(guard, staff, fixture.projectA1);
            assertThat(repository.latestTickets(a1, List.of(mine, theirs)))
                .as("a project outside the caller's scope is filtered by the statement, not the caller")
                .containsOnlyKeys(mine);

            // The positive control. Without it, a batch that returned nothing at all would
            // satisfy the assertion above.
            CrmQueryScope both = CrmQueryScope.accessibleProjects(admin);
            assertThat(repository.latestTickets(both, List.of(mine, theirs)))
                .containsKeys(mine, theirs);
        }

        @Test
        @DisplayName("another tenant's id is absent from the batch, not merely its ticket")
        void theBatchCannotReachAnotherTenant() {
            UUID theirs = fixture.customerB1;
            UUID mine = bulk.get(0);
            fixture.insertConversation(fixture.orgB, fixture.projectB1, theirs,
                "#081400000002", "MOCK", "OPEN", "HUMAN");

            CrmQueryScope scope = CrmQueryScope.accessibleProjects(admin);

            assertThat(repository.latestTickets(scope, List.of(mine, theirs))).containsOnlyKeys();
            assertThat(repository.latestTickets(CrmQueryScope.accessibleProjects(fixture.adminBContext()),
                List.of(mine, theirs))).containsOnlyKeys(theirs);
        }

        @Test
        @DisplayName("an empty page asks for nothing rather than asking in ()")
        void anEmptyIdListIsNotAStatement() {
            CrmQueryScope scope = CrmQueryScope.accessibleProjects(admin);

            assertThat(repository.latestTickets(scope, List.of())).isEmpty();
            assertThat(repository.leadsFor(scope, List.of(), 5)).isEmpty();
            assertThat(repository.appointmentsFor(scope, List.of(), 5)).isEmpty();
        }
    }

    // ==================================================================
    // 2. the shape: a page is read once
    // ==================================================================

    @Nested
    @DisplayName("a page is read once, not once per row")
    class APageIsReadOnceNotOncePerRow {

        @Test
        @DisplayName("a 50-row page asks the batch once and the per-row query never")
        void aFiftyRowPageIsOneBatchedRead() {
            for (int i = 0; i < BULK; i += 2) {
                insertConversation(bulk.get(i), LocalDateTime.of(2026, 3, 1, 9, 0).plusMinutes(i),
                    "#0814" + String.format("%09d", i));
            }
            CustomerRepository spy = Mockito.spy(new CustomerRepository(fixture.jdbc()));

            CursorPage<CustomerListItem> page =
                new CustomerService(spy, json, guard, audit).list(admin, null, null, null, 50);

            assertThat(page.items()).hasSize(50);
            Mockito.verify(spy, Mockito.times(1)).latestTickets(any(), anyList());
            Mockito.verify(spy, Mockito.never()).latestTicket(any(), any());
            Mockito.verify(spy, Mockito.times(1)).leadsFor(any(), anyList(), Mockito.anyInt());
            Mockito.verify(spy, Mockito.never()).leads(any(), any(), Mockito.anyInt());
            Mockito.verify(spy, Mockito.times(1)).appointmentsFor(any(), anyList(), Mockito.anyInt());
            Mockito.verify(spy, Mockito.never()).appointments(any(), any(), Mockito.anyInt());
        }

        @Test
        @DisplayName("the statement count is the same for a 5-row page and a 50-row page")
        void theStatementCountDoesNotGrowWithThePage() {
            CustomerRepository spy = Mockito.spy(new CustomerRepository(fixture.jdbc()));
            CustomerService service = new CustomerService(spy, json, guard, audit);

            service.list(admin, null, null, null, 5);
            int forFive = repositoryCalls(spy);
            Mockito.clearInvocations(spy);
            service.list(admin, null, null, null, 50);
            int forFifty = repositoryCalls(spy);

            // This is the assertion the N+1 cannot survive: ten times the customers, the same
            // number of statements. A per-row read fails here even when every row is cached.
            assertThat(forFive).isEqualTo(forFifty);
            assertThat(forFifty)
                .as("one page query plus one batched read per child projection")
                .isEqualTo(4);
        }

        @Test
        @DisplayName("the batch is handed exactly the ids on the page, once")
        void theBatchIsGivenThePagesIds() {
            CustomerRepository spy = Mockito.spy(new CustomerRepository(fixture.jdbc()));

            CursorPage<CustomerListItem> page =
                new CustomerService(spy, json, guard, audit).list(admin, null, "bulk-", null, 50);

            List<UUID> expected = page.items().stream().map(CustomerListItem::id).toList();
            assertThat(expected).hasSize(50);
            Mockito.verify(spy, Mockito.times(1))
                .latestTickets(any(), Mockito.eq(expected));
            Mockito.verify(spy, Mockito.times(1))
                .leadsFor(any(), Mockito.eq(expected), Mockito.eq(5));
            Mockito.verify(spy, Mockito.times(1))
                .appointmentsFor(any(), Mockito.eq(expected), Mockito.eq(5));
        }

        @Test
        @DisplayName("a page with no customers issues the page query and nothing else")
        void anEmptyPageIssuesNoChildStatement() {
            CustomerRepository spy = Mockito.spy(new CustomerRepository(fixture.jdbc()));

            CursorPage<CustomerListItem> page =
                new CustomerService(spy, json, guard, audit)
                    .list(fixture.outsiderAContext(), null, null, null, 50);

            assertThat(page.items()).isEmpty();
            assertThat(repositoryCalls(spy))
                .as("a principal with no membership gets a quiet empty page, not a batched crash")
                .isEqualTo(1);
            Mockito.verify(spy, Mockito.never()).latestTickets(any(), anyList());
        }
    }

    // ==================================================================
    // helpers
    // ==================================================================

    private CustomerService realService() {
        return new CustomerService(repository, json, guard, audit);
    }

    private static int repositoryCalls(CustomerRepository spy) {
        return Math.toIntExact(Mockito.mockingDetails(spy).getInvocations().stream()
            .filter(invocation -> invocation.getMethod().getDeclaringClass() == CustomerRepository.class)
            .count());
    }

    private static Map<UUID, CustomerListItem> byId(List<CustomerListItem> items) {
        Map<UUID, CustomerListItem> byId = new LinkedHashMap<>();
        items.forEach(item -> byId.put(item.id(), item));
        return byId;
    }

    /** The follow-ups the per-row query would give — the reference the batch is compared to. */
    private List<FollowUpSummary> expectedFollowUps(CrmQueryScope scope, UUID customerId) {
        return repository.leads(scope, customerId, 5).stream()
            .map(lead -> new FollowUpSummary(lead.id(), lead.name(), lead.status(), lead.priority(),
                lead.ticketNumber(), Timestamps.toIso(lead.ticketDate()),
                Timestamps.toIso(lead.followUpDate()), Timestamps.toIso(lead.createdAt())))
            .toList();
    }

    /** The appointments the per-row query would give, narrowed the way the list narrows them. */
    private List<AppointmentSummary> expectedAppointments(CrmQueryScope scope, UUID customerId) {
        return repository.appointments(scope, customerId, 5).stream()
            .map(appointment -> new AppointmentSummary(appointment.id(), appointment.title(),
                Timestamps.toIso(appointment.appointmentDate()), appointment.durationMinutes(),
                appointment.status(), appointment.service(), appointment.doctorName(),
                appointment.notes(), appointment.ticketNumber(),
                Timestamps.toIso(appointment.ticketDate()), Timestamps.toIso(appointment.createdAt()))
                .asListItem())
            .toList();
    }

    /**
     * Customers in project A1, all named {@code bulk-N} so a search can find exactly them.
     *
     * <p>{@code lastSeenAt} counts <em>down</em>, so {@code bulk.get(0)} is the newest customer in
     * the tenant and is therefore on the first page. The list orders by {@code lastSeenAt desc,
     * id desc}, and a fixture that put the interesting customers at the bottom of 60 would have
     * every "the published item is unchanged" assertion pass against an empty map.
     */
    private List<UUID> seedCustomers(int count) {
        List<UUID> ids = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < count; i++) {
            UUID id = UUID.randomUUID();
            ids.add(id);
            fixture.jdbc().update("""
                insert into customers ("id", "organizationId", "projectId", "cxId", "displayName",
                    "primaryPhone", "firstSeenAt", "lastSeenAt", "createdAt", "updatedAt")
                values (:id, :org, :project, :cx, :name, :phone, :now, :seen, :now, :now)
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("org", fixture.orgA)
                .addValue("project", fixture.projectA1)
                .addValue("cx", String.format("#3399%04d", i))
                .addValue("name", "bulk-" + i)
                .addValue("phone", "+9665900" + String.format("%04d", i))
                .addValue("now", now)
                .addValue("seen", now.plusSeconds(count - i)));
        }
        return ids;
    }

    /**
     * A conversation with an explicit {@code createdAt}.
     *
     * <p>Not {@code CrmFixture.insertConversation}, which stamps {@code now}: "newest wins" has
     * to be decided by data the test chose, not by how fast the loop ran, and
     * {@code timestamp(3)} makes a same-millisecond tie entirely possible.
     */
    private UUID insertConversation(UUID customer, LocalDateTime createdAt, String ticketNumber) {
        return insertConversation(customer, createdAt, ticketNumber, "OPEN", null);
    }

    private UUID insertConversation(UUID customer, LocalDateTime createdAt, String ticketNumber,
                                    String status, String subject) {
        UUID id = UUID.randomUUID();
        fixture.jdbc().update("""
            insert into conversations ("id", "organizationId", "projectId", "customerId",
                "ticketNumber", "channel", "status", "mode", "priority", "subject", "messageCount",
                "lastMessageAt", "createdAt", "updatedAt")
            values (:id, :org, :project, :customer, :ticket, cast('MOCK' as "ChannelType"),
                    cast(:status as "ConversationStatus"), cast('HUMAN' as "ConversationMode"),
                    cast('NORMAL' as "ConversationPriority"), :subject, 0, :created, :created, :created)
            """, new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("org", fixture.orgA)
            .addValue("project", fixture.projectA1)
            .addValue("customer", customer)
            .addValue("ticket", ticketNumber)
            .addValue("status", status)
            .addValue("subject", subject)
            .addValue("created", createdAt));
        return id;
    }

    private UUID insertLead(UUID customer, String name, LocalDateTime createdAt) {
        UUID id = UUID.randomUUID();
        fixture.jdbc().update("""
            insert into "Lead" ("id", "organizationId", "projectId", "customerId", "name", "status",
                "createdAt", "updatedAt")
            values (:id, :org, :project, :customer, :name, 'New', :created, :created)
            """, new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("org", fixture.orgA)
            .addValue("project", fixture.projectA1)
            .addValue("customer", customer)
            .addValue("name", name)
            .addValue("created", createdAt));
        return id;
    }

    private UUID insertAppointment(UUID customer, String title, LocalDateTime appointmentDate) {
        UUID id = UUID.randomUUID();
        fixture.jdbc().update("""
            insert into appointments ("id", "organizationId", "projectId", "customerId", "title",
                "appointmentDate", "status", "createdAt", "updatedAt")
            values (:id, :org, :project, :customer, :title, :date, 'PENDING', :date, :date)
            """, new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("org", fixture.orgA)
            .addValue("project", fixture.projectA1)
            .addValue("customer", customer)
            .addValue("title", title)
            .addValue("date", appointmentDate));
        return id;
    }
}