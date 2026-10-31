package com.firstelement.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.firstelement.crm.api.CustomerDetail;
import com.firstelement.crm.api.CustomerListItem;
import com.firstelement.crm.api.NoteRequest;
import com.firstelement.crm.api.NoteViews;
import com.firstelement.crm.domain.ChannelType;
import com.firstelement.crm.query.CursorPage;
import com.firstelement.crm.service.CustomerService;
import com.firstelement.crm.service.NoteService;
import com.firstelement.inbox.api.AssignConversationRequest;
import com.firstelement.inbox.api.IncomingMessage;
import com.firstelement.inbox.api.SendMessageRequest;
import com.firstelement.inbox.service.ConversationService;
import com.firstelement.inbox.service.InboundMessageService;
import com.firstelement.security.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * The legacy behaviour comparison: each fixture is a response captured from the NestJS reference,
 * and this suite asserts the Spring services produce the same semantics.
 *
 * <h2>Why the comparison is a real gate</h2>
 *
 * <ol>
 *   <li><b>Key sets match exactly.</b> A renamed or dropped field fails with its JSON path. That
 *       is the failure mode a strangler migration actually has: not a 500, but a client that
 *       quietly stops rendering a column.</li>
 *   <li><b>Literal values are asserted.</b> The fixture seeds fixed ids and timestamps, so the
 *       rendered ISO-8601 string, the ticket number, the payload <em>keys</em> of a timeline entry
 *       and the nested author object are all compared byte for byte.</li>
 *   <li><b>The edge cases are fixtures too.</b> An empty 360 view, an unassigned inbox row and a
 *       one-note list are the shapes most likely to differ, because "nothing here" is where a
 *       hand-written projection usually invents a field.</li>
 * </ol>
 *
 * <h2>What a fixture deliberately does not claim</h2>
 *
 * <p>That the legacy <em>database</em> agreed with the legacy projection. The legacy response
 * builders are pure functions of the row, so the fixture pins the projection; the container suites
 * pin the row. Together they cover what each can.
 */
@SpringBootTest(classes = InboxTestApplication.class)
class LegacyFixtureComparisonTest extends InboxIntegrationTestBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private InboxFixture fixture;

    @Autowired
    private ConversationService conversations;

    @Autowired
    private InboundMessageService inbound;

    @Autowired
    private CustomerService customers;

    @Autowired
    private NoteService notes;

    private TenantContext staff;

    @BeforeEach
    void setUp() {
        fixture.reset();
        staff = fixture.staffA();
        PrincipalScope.bind(staff);
    }

    @AfterEach
    void tearDown() {
        PrincipalScope.clear();
    }

    // ==================================================================
    // the fixtures
    // ==================================================================

    @Test
    @DisplayName("a thread with a customer message, a reply and an assignment matches the legacy shape")
    void conversationThreadFixture() {
        LegacyFixtures legacy = LegacyFixtures.load("conversation-thread.json");

        // Seeded deterministically rather than through the inbound pipeline: the ticket number
        // embeds the creation date, so a thread created "today" would pin the fixture to the day
        // the suite runs. `TicketNumberTest` pins the format separately; here the row is fixed so
        // the projection is what is under comparison.
        UUID conversationId = fixture.conversation("000000000010", InboxFixture.ORG_A,
            InboxFixture.PROJECT_A1, InboxFixture.CUSTOMER_A1, "#0814000000001", "PENDING", "HUMAN",
            InboxFixture.at(2));
        fixture.jdbc().update("""
            update conversations set "assignedEmployeeId" = :employee, "lastReplierUserId" = :replier,
                "messageCount" = 2, "firstResponseAt" = :firstResponse, "subject" = null
            where "id" = :id
            """,
            new MapSqlParameterSource()
                .addValue("employee", InboxFixture.EMPLOYEE_STAFF_A)
                .addValue("replier", InboxFixture.USER_STAFF_A)
                .addValue("firstResponse", InboxFixture.at(2))
                .addValue("id", conversationId));

        UUID inbound = fixture.message("000000000010", InboxFixture.ORG_A, InboxFixture.PROJECT_A1,
            conversationId, "INBOUND", "CUSTOMER", "I cannot book an appointment",
            InboxFixture.SEED_AT, null);
        UUID outbound = fixture.message("000000000011", InboxFixture.ORG_A, InboxFixture.PROJECT_A1,
            conversationId, "OUTBOUND", "EMPLOYEE", "We have booked you in for Sunday.",
            InboxFixture.at(2), InboxFixture.USER_STAFF_A);
        fixture.jdbc().update(
            "update messages set \"deliveryStatus\" = cast('PENDING' as \"MessageDeliveryStatus\") "
                + "where \"id\" = :id", new MapSqlParameterSource("id", outbound));

        fixture.event("000000000010", InboxFixture.ORG_A, InboxFixture.PROJECT_A1, conversationId,
            "CONVERSATION_CREATED", "SYSTEM",
            "{\"channel\":\"MOCK\",\"mode\":\"HUMAN\"}", InboxFixture.SEED_AT, null);
        fixture.event("000000000011", InboxFixture.ORG_A, InboxFixture.PROJECT_A1, conversationId,
            "MESSAGE_RECEIVED", "CUSTOMER",
            "{\"messageId\":\"" + inbound + "\",\"bodyLength\":25}", InboxFixture.SEED_AT, null);
        fixture.event("000000000012", InboxFixture.ORG_A, InboxFixture.PROJECT_A1, conversationId,
            "MESSAGE_SENT", "EMPLOYEE",
            "{\"messageId\":\"" + outbound + "\",\"bodyLength\":33}", InboxFixture.at(2),
            InboxFixture.USER_STAFF_A);
        fixture.event("000000000013", InboxFixture.ORG_A, InboxFixture.PROJECT_A1, conversationId,
            "ASSIGNED", "EMPLOYEE",
            "{\"employeeId\":\"" + InboxFixture.EMPLOYEE_STAFF_A + "\",\"previousEmployeeId\":null,"
                + "\"reason\":\"MANUAL\"}", InboxFixture.at(1), InboxFixture.USER_STAFF_A);

        JsonNode actual = MAPPER.valueToTree(conversations.getById(staff, conversationId));
        legacy.assertMatches(actual);

        // The values most likely to drift are also asserted literally, so a fixture cannot be
        // satisfied by two implementations that are wrong in the same way.
        assertThat(actual.at("/lastMessagePreview").asText())
            .isEqualTo("We have booked you in for Sunday.");
        assertThat(actual.at("/messages/0/authorName").isNull()).isTrue();
        assertThat(actual.at("/messages/1/deliveryStatus").asText()).isEqualTo("PENDING");
        assertThat(actual.at("/events/0/payload").fieldNames()).toIterable()
            .containsExactlyInAnyOrder("channel", "mode");
        // The timeline carries a length, never the body: the body lives only in `messages`, and
        // duplicating it onto an event stream widens where customer text has to be protected.
        assertThat(actual.at("/events/1/payload").fieldNames()).toIterable()
            .containsExactlyInAnyOrder("messageId", "bodyLength");
        assertThat(actual.at("/events").toString()).doesNotContain("cannot book an appointment");
        assertThat(actual.at("/ticketNumber").asText()).isEqualTo("#0814000000001");
        assertThat(actual.at("/lastMessageAt").asText()).isEqualTo("2026-08-14T09:32:00.000Z");
    }

    @Test
    @DisplayName("an unassigned inbox row matches the legacy shape")
    void conversationSummaryFixture() {
        LegacyFixtures legacy = LegacyFixtures.load("conversation-summary.json");

        UUID conversationId = fixture.conversation("000000000001", InboxFixture.ORG_A,
            InboxFixture.PROJECT_A1, InboxFixture.CUSTOMER_A1, "#0814000000001", "OPEN", "HUMAN",
            InboxFixture.SEED_AT);
        fixture.jdbc().update("update conversations set \"subject\" = :subject, "
                + "\"messageCount\" = 1 where \"id\" = :id",
            new MapSqlParameterSource().addValue("subject", "Booking problem")
                .addValue("id", conversationId));
        fixture.message("000000000001", InboxFixture.ORG_A, InboxFixture.PROJECT_A1,
            conversationId, "INBOUND", "CUSTOMER", "I cannot book an appointment",
            InboxFixture.SEED_AT, null);

        JsonNode thread = MAPPER.valueToTree(conversations.getById(staff, conversationId));
        legacy.assertMatches(summaryOf(thread));

        assertThat(thread.at("/assignedEmployee").isNull()).isTrue();
        assertThat(thread.at("/lastMessagePreview").asText())
            .isEqualTo("I cannot book an appointment");
        assertThat(thread.at("/lastMessageAt").asText()).endsWith("Z");
    }

    @Test
    @DisplayName("an empty customer 360 matches the legacy shape, including the `leads` key")
    void emptyCustomerDetailFixture() {
        LegacyFixtures legacy = LegacyFixtures.load("customer-detail-empty.json");

        legacy.assertMatches(MAPPER.valueToTree(customers.getById(staff, InboxFixture.CUSTOMER_A1)));

        CustomerDetail detail = customers.getById(staff, InboxFixture.CUSTOMER_A1);
        assertThat(detail.leads()).isEmpty();
        assertThat(detail.appointments()).isEmpty();
        assertThat(detail.identities()).isEmpty();
        assertThat(detail.firstSeenAt()).isEqualTo("2026-08-14T09:30:00.000Z");
        // The 360 view has no organizationId and no createdAt; the legacy selection did not
        // return them, and adding them would break a strict client.
        assertThat(MAPPER.valueToTree(detail).has("organizationId")).isFalse();
        assertThat(MAPPER.valueToTree(detail).has("createdAt")).isFalse();
    }

    @Test
    @DisplayName("the customer list matches the legacy shape, including `followUps`")
    void customerListFixture() {
        LegacyFixtures legacy = LegacyFixtures.load("customer-list.json");

        UUID conversationId = fixture.conversation("000000000002", InboxFixture.ORG_A,
            InboxFixture.PROJECT_A1, InboxFixture.CUSTOMER_A1, "#0814000000001", "OPEN", "HUMAN",
            InboxFixture.SEED_AT);
        fixture.jdbc().update("update conversations set \"subject\" = :subject where \"id\" = :id",
            new MapSqlParameterSource().addValue("subject", "Booking problem")
                .addValue("id", conversationId));

        CursorPage<CustomerListItem> page =
            customers.list(staff, InboxFixture.PROJECT_A1, null, null, 25);

        legacy.assertMatches(MAPPER.valueToTree(page));

        assertThat(page.items().get(0).latestTicket().ticketNumber()).isEqualTo("#0814000000001");
        assertThat(page.items().get(0).conversationCount()).isEqualTo(1);
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    @DisplayName("the note list matches the legacy shape with its nested author object")
    void noteListFixture() {
        LegacyFixtures legacy = LegacyFixtures.load("note-list.json");

        notes.create(staff, new NoteRequest("Customer", InboxFixture.CUSTOMER_A1,
            "Asked to call back after 4pm.", InboxFixture.PROJECT_A1));

        List<NoteViews.ForResource> listed =
            notes.listForResource(staff, "Customer", InboxFixture.CUSTOMER_A1, null);

        legacy.assertMatches(MAPPER.valueToTree(listed));
        assertThat(listed.get(0).author().fullName()).isEqualTo("Alpha Staff");
        assertThat(listed.get(0).author().id()).isEqualTo(InboxFixture.USER_STAFF_A);
    }

    // ==================================================================
    // fixture hygiene
    // ==================================================================

    @Test
    @DisplayName("every legacy fixture is claimed by this suite and names its legacy source")
    void everyFixtureIsClaimedAndSourced() {
        assertThat(LegacyFixtures.all())
            .containsExactlyInAnyOrder(
                "conversation-thread.json",
                "conversation-summary.json",
                "customer-detail-empty.json",
                "customer-list.json",
                "note-list.json");

        for (String file : LegacyFixtures.all()) {
            LegacyFixtures fixtureUnderTest = LegacyFixtures.load(file);
            assertThat(fixtureUnderTest.source())
                .as("%s names the legacy file it was derived from", file)
                .contains("apps/api/src");
            assertThat(fixtureUnderTest.raw().isContainerNode()).isTrue();
            assertThat(fixtureUnderTest.volatileFields())
                .as("%s declares what it cannot pin")
                .isNotNull();
        }
    }

    // ==================================================================
    // helpers
    // ==================================================================

    /** The inbox row alone, without the thread view's {@code messages}/{@code events}. */
    private static JsonNode summaryOf(JsonNode thread) {
        ObjectNode summary = MAPPER.createObjectNode();
        thread.fieldNames().forEachRemaining(field -> {
            if (!"messages".equals(field) && !"events".equals(field)) {
                summary.set(field, thread.get(field));
            }
        });
        return summary;
    }

    private UUID receive(String externalUserId, String externalMessageId, String text) {
        return inbound.receive(staff, InboxFixture.PROJECT_A1, null, new IncomingMessage(
            externalUserId, text, null, null, null, externalMessageId, ChannelType.MOCK,
            Map.of(), Map.of(), java.time.LocalDateTime.now())).conversationId();
    }
}