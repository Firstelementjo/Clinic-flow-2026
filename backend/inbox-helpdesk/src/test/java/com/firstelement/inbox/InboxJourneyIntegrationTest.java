package com.firstelement.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstelement.crm.api.ConversationDetail;
import com.firstelement.crm.api.ConversationSummary;
import com.firstelement.crm.api.CustomerDetail;
import com.firstelement.crm.domain.ChannelType;
import com.firstelement.crm.domain.InboxFilter;
import com.firstelement.crm.error.ResourceNotFoundException;
import com.firstelement.crm.error.ValidationFailedException;
import com.firstelement.crm.error.VersionConflictException;
import com.firstelement.crm.query.CursorPage;
import com.firstelement.crm.service.CaseService;
import com.firstelement.crm.service.CustomerService;
import com.firstelement.inbox.api.AssignConversationRequest;
import com.firstelement.inbox.api.ChangeModeRequest;
import com.firstelement.inbox.api.CustomerTranscript;
import com.firstelement.inbox.api.InboxQuery;
import com.firstelement.inbox.api.InboundResult;
import com.firstelement.inbox.api.IncomingMessage;
import com.firstelement.inbox.api.ReplyResult;
import com.firstelement.inbox.api.SendMessageRequest;
import com.firstelement.inbox.api.UnassignedQueuePage;
import com.firstelement.inbox.api.UpdateConversationRequest;
import com.firstelement.inbox.service.ConversationService;
import com.firstelement.inbox.service.InboxQueryService;
import com.firstelement.inbox.service.InboundMessageService;
import com.firstelement.security.TenantContext;
import com.firstelement.security.error.PolicyDeniedException;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The inbox vertical slice against a real PostgreSQL 16 / pgvector container.
 *
 * <h2>What this suite is for</h2>
 *
 * <p>It walks the project's own acceptance journey end to end — customer message → AI/human
 * transition → escalation handoff → employee response → resolution — through the Spring services,
 * and asserts the isolation and concurrency properties on the way. Nothing here mocks the
 * database, so a wrong quoted column name or a wrong timestamp binding fails the build rather
 * than passing quietly.
 */
@SpringBootTest(classes = InboxTestApplication.class)
class InboxJourneyIntegrationTest extends InboxIntegrationTestBase {

    @Autowired
    private InboxFixture fixture;

    @Autowired
    private ConversationService conversations;

    @Autowired
    private InboxQueryService inbox;

    @Autowired
    private InboundMessageService inbound;

    @Autowired
    private CustomerService customers;

    @Autowired
    private CaseService cases;

    @Autowired
    private com.firstelement.crm.persistence.ConversationRepository conversationRepo;

    private TenantContext staff;
    private TenantContext admin;
    private TenantContext outsider;
    private TenantContext beta;

    @BeforeEach
    void setUp() {
        fixture.reset();
        staff = fixture.staffA();
        admin = fixture.adminA();
        outsider = fixture.outsiderA();
        beta = fixture.adminB();
        PrincipalScope.bind(staff);
    }

    @AfterEach
    void tearDown() {
        PrincipalScope.clear();
    }

    // ==================================================================
    // the acceptance journey
    // ==================================================================

    @Test
    @DisplayName("customer message -> AI/human -> escalation -> employee response -> resolution")
    void endToEndJourney() {
        // 1. A customer writes in on the mock channel. Identity resolution creates the customer,
        //    a thread is opened in HUMAN mode, and the message is stored.
        InboundResult received = inbound.receive(staff, InboxFixture.PROJECT_A1, null,
            new IncomingMessage("ext-journey-1", "I cannot book an appointment", "Fatima",
                "+966500007777", null, "provider-msg-1", ChannelType.MOCK, Map.of(), Map.of(),
                InboxFixture.at(0)));

        assertThat(received.status()).isEqualTo(InboundResult.Status.PROCESSED);
        assertThat(received.conversationCreated()).isTrue();
        UUID conversationId = received.conversationId();
        UUID customerId = received.customerId();

        // The resolved customer is a real customer with a CX id and a channel identity.
        CustomerDetail customer = customers.getById(staff, customerId);
        assertThat(customer.cxId()).matches("^#33\\d{6}$");
        assertThat(customer.identities()).hasSize(1);
        assertThat(customer.identities().get(0).platform()).isEqualTo("MOCK");

        ConversationDetail thread = conversations.getById(staff, conversationId);
        assertThat(thread.status()).isEqualTo("OPEN");
        assertThat(thread.mode()).isEqualTo("HUMAN");
        assertThat(thread.messageCount()).isEqualTo(1);
        assertThat(thread.assignedEmployee()).isNull();
        assertThat(thread.lastMessagePreview()).isEqualTo("I cannot book an appointment");

        // 2a. The thread is handed to the AI branch and then taken back. Phase 4 has no AI
        //     module, so a thread created by the inbound pipeline starts in HUMAN; returning it
        //     to AI and taking it over again exercises both directions of the guarded
        //     transition — and, more importantly, proves that a takeover of a thread the AI was
        //     answering moves it to PENDING and stamps the timeline.
        ConversationDetail toAi = conversations.returnToAi(staff, conversationId,
            new ChangeModeRequest(com.firstelement.crm.domain.ConversationMode.AI, 0), null);
        assertThat(toAi.mode()).isEqualTo("AI");
        assertThat(toAi.events()).extracting(e -> e.type()).contains("RETURNED_TO_AI");

        // 2b. An escalation takes the thread over. This is the guarded AI -> HUMAN transition:
        //     conditional on both the expected mode and modeVersion.
        ConversationDetail escalated = conversations.changeMode(staff, conversationId,
            new ChangeModeRequest(com.firstelement.crm.domain.ConversationMode.HUMAN, 1), null);

        assertThat(escalated.status()).isEqualTo("PENDING");
        assertThat(escalated.events()).extracting(e -> e.type()).contains("TAKEN_OVER");

        // 3. The thread is routed to an employee who belongs to the project.
        ConversationDetail assigned = conversations.assign(staff, conversationId,
            new AssignConversationRequest(InboxFixture.EMPLOYEE_STAFF_A), null, null);

        assertThat(assigned.assignedEmployee()).isNotNull();
        assertThat(assigned.assignedEmployee().fullName()).isEqualTo("Alpha Staff");
        assertThat(assigned.events()).extracting(e -> e.type()).contains("ASSIGNED");

        // The assignee's workload is recounted, not incremented: the legacy rule.
        assertThat(fixture.columnText("employees", InboxFixture.EMPLOYEE_STAFF_A,
            "activeConversationCount")).isEqualTo("1");

        // 4. The employee answers. The row is persisted before any delivery is attempted and
        //    starts PENDING.
        ReplyResult reply = conversations.reply(staff, conversationId,
            new SendMessageRequest("I have booked you in for Sunday."), null, null, null);

        assertThat(reply.replayed()).isFalse();
        assertThat(reply.collisionDetected()).isFalse();
        assertThat(reply.detail().messageCount()).isEqualTo(2);
        assertThat(reply.detail().messages()).hasSize(2);
        assertThat(reply.detail().messages().get(1).direction()).isEqualTo("OUTBOUND");
        assertThat(reply.detail().messages().get(1).deliveryStatus()).isEqualTo("PENDING");
        assertThat(reply.detail().messages().get(1).authorName()).isEqualTo("Alpha Staff");

        // 5. The employee records how the case was resolved and closes it.
        cases.saveResolution(staff, conversationId,
            new com.firstelement.crm.api.SaveResolutionRequest("Could not book online",
                "Booked by phone for Sunday", false, null, Boolean.TRUE, Boolean.TRUE, false,
                false, "RESOLVED", null));

        ConversationDetail closed = conversations.closeAndUnassign(staff, conversationId, null, null);

        assertThat(closed.status()).isEqualTo("RESOLVED");
        assertThat(closed.assignedEmployee()).isNull();
        assertThat(closed.events()).extracting(e -> e.type()).contains("STATUS_CHANGED");
        // The released assignee's workload is recounted back down.
        assertThat(fixture.columnText("employees", InboxFixture.EMPLOYEE_STAFF_A,
            "activeConversationCount")).isEqualTo("0");

        // 6. The audit stream recorded the operator's actions.
        assertThat(fixture.count("select count(*) from audit_logs where \"action\" in "
            + "('EMPLOYEE_REPLIED','EMPLOYEE_ASSIGNED_CONVERSATION',"
            + "'EMPLOYEE_RESOLVED_CONVERSATION','EMPLOYEE_TOOK_CONVERSATION')")).isEqualTo(5);
    }

    @Test
    @DisplayName("a new customer message continues the live thread instead of starting another")
    void secondMessageContinuesTheThread() {
        InboundResult first = receive("ext-cont", "provider-1", "First question");
        InboundResult second = receive("ext-cont", "provider-2", "Follow-up question");

        assertThat(second.conversationId()).isEqualTo(first.conversationId());
        assertThat(second.conversationCreated()).isFalse();
        assertThat(conversations.getById(staff, first.conversationId()).messageCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("a redelivered provider message is stored once and reported as a duplicate")
    void redeliveryIsIdempotent() {
        InboundResult first = receive("ext-dup", "provider-dup", "Only once please");
        InboundResult second = receive("ext-dup", "provider-dup", "Only once please");

        assertThat(second.status()).isEqualTo(InboundResult.Status.DUPLICATE);
        assertThat(second.externalMessageId()).isEqualTo("provider-dup");
        assertThat(conversations.getById(staff, first.conversationId()).messageCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a reply to a resolved thread reopens it")
    void replyReopensAResolvedThread() {
        UUID conversationId = receive("ext-reopen", "provider-r", "Hello").conversationId();
        conversations.closeAndUnassign(staff, conversationId, null, null);
        assertThat(conversations.getById(staff, conversationId).status()).isEqualTo("RESOLVED");

        ReplyResult reply = conversations.reply(staff, conversationId,
            new SendMessageRequest("Actually, one more thing"), null, null, null);

        assertThat(reply.detail().status()).isEqualTo("OPEN");
    }

    // ==================================================================
    // idempotency and collision
    // ==================================================================

    @Test
    @DisplayName("an Idempotency-Key replay returns the stored message and writes nothing")
    void replyIdempotency() {
        UUID conversationId = receive("ext-idem", "provider-i", "Question").conversationId();

        ReplyResult first = conversations.reply(staff, conversationId,
            new SendMessageRequest("We have fixed it."), "key-1", null, null);
        ReplyResult replay = conversations.reply(staff, conversationId,
            new SendMessageRequest("We have fixed it."), "key-1", null, null);

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.messageId()).isEqualTo(first.messageId());
        assertThat(replay.detail().messageCount()).isEqualTo(2);
        assertThat(fixture.count("select count(*) from messages where \"conversationId\" = '"
            + conversationId + "'")).isEqualTo(2);
    }

    @Test
    @DisplayName("two different keys are two different replies")
    void distinctKeysAreDistinctReplies() {
        UUID conversationId = receive("ext-idem2", "provider-i2", "Question").conversationId();

        conversations.reply(staff, conversationId, new SendMessageRequest("First"), "key-a", null, null);
        ReplyResult second = conversations.reply(staff, conversationId,
            new SendMessageRequest("Second"), "key-b", null, null);

        assertThat(second.replayed()).isFalse();
        assertThat(second.detail().messageCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("a reply that finds the thread moved is reported, not dropped")
    void replyCollisionIsReportedNotFatal() {
        UUID conversationId = receive("ext-coll", "provider-c", "Question").conversationId();
        int before = conversations.getById(staff, conversationId).messageCount();

        // Two employees answering at once: the second caller sends a stale message count.
        ReplyResult result = conversations.reply(staff, conversationId,
            new SendMessageRequest("Answering as well"), "key-coll", before - 5, null);

        // Dropping the reply would lose a customer communication, so it is written and flagged.
        assertThat(result.collisionDetected()).isTrue();
        assertThat(result.replayed()).isFalse();
        assertThat(result.detail().messageCount()).isEqualTo(before + 1);
    }

    // ==================================================================
    // optimistic concurrency
    // ==================================================================

    @Test
    @DisplayName("a stale status update is a 409 and changes nothing")
    void staleStatusUpdateCollides() {
        UUID conversationId = receive("ext-ver", "provider-v", "Question").conversationId();
        LocalDateTime stale = fixture.updatedAtOf("conversations", conversationId);

        conversations.update(staff, conversationId,
            new UpdateConversationRequest(null, com.firstelement.crm.domain.ConversationPriority.HIGH, null), null, null);

        assertThatThrownBy(() -> conversations.update(staff, conversationId,
            new UpdateConversationRequest(null, com.firstelement.crm.domain.ConversationPriority.URGENT, null), stale, null))
            .isInstanceOf(VersionConflictException.class);

        assertThat(conversations.getById(staff, conversationId).priority()).isEqualTo("HIGH");
    }

    @Test
    @DisplayName("a fresh If-Match applies")
    void freshStatusUpdateApplies() {
        UUID conversationId = receive("ext-ver2", "provider-v2", "Question").conversationId();
        LocalDateTime current = fixture.updatedAtOf("conversations", conversationId);

        ConversationDetail updated = conversations.update(staff, conversationId,
            new UpdateConversationRequest(null,
                com.firstelement.crm.domain.ConversationPriority.URGENT, "Escalated by phone"),
            current, null);

        assertThat(updated.priority()).isEqualTo("URGENT");
        assertThat(updated.subject()).isEqualTo("Escalated by phone");
        assertThat(updated.events()).extracting(e -> e.type()).contains("PRIORITY_CHANGED");
    }

    @Test
    @DisplayName("a stale assignment is a 409 and the previous owner keeps the thread")
    void staleAssignmentCollides() {
        UUID conversationId = receive("ext-asg", "provider-a", "Question").conversationId();
        LocalDateTime stale = fixture.updatedAtOf("conversations", conversationId);

        conversations.assign(staff, conversationId,
            new AssignConversationRequest(InboxFixture.EMPLOYEE_STAFF_A), null, null);

        assertThatThrownBy(() -> conversations.assign(staff, conversationId,
            new AssignConversationRequest(InboxFixture.EMPLOYEE_ADMIN_A), stale, null))
            .isInstanceOf(VersionConflictException.class);

        assertThat(conversations.getById(staff, conversationId).assignedEmployee().id())
            .isEqualTo(InboxFixture.EMPLOYEE_STAFF_A);
    }

    @Test
    @DisplayName("a stale modeVersion cannot take over a thread someone else already took")
    void modeVersionGuardsTakeover() {
        UUID conversationId = receive("ext-mode", "provider-m", "Question").conversationId();
        conversations.returnToAi(staff, conversationId,
            new ChangeModeRequest(com.firstelement.crm.domain.ConversationMode.AI, 0), null);

        ConversationDetail taken = conversations.changeMode(staff, conversationId,
            new ChangeModeRequest(com.firstelement.crm.domain.ConversationMode.HUMAN, 1), null);
        assertThat(taken.mode()).isEqualTo("HUMAN");
        assertThat(fixture.columnText("conversations", conversationId, "modeVersion")).isEqualTo("2");

        // The thread is AI again. An in-flight send that still holds version 1 tries to take it
        // over and loses: the stored version is 2, and the conditional update matches nothing.
        conversations.returnToAi(staff, conversationId,
            new ChangeModeRequest(com.firstelement.crm.domain.ConversationMode.AI, 2), null);

        assertThatThrownBy(() -> conversations.changeMode(staff, conversationId,
            new ChangeModeRequest(com.firstelement.crm.domain.ConversationMode.HUMAN, 1), null))
            .isInstanceOf(VersionConflictException.class);

        // The losing send changed nothing.
        assertThat(conversations.getById(staff, conversationId).mode()).isEqualTo("AI");
    }

    @Test
    @DisplayName("re-taking a thread that is already HUMAN is idempotent, not a collision")
    void retakingIsIdempotent() {
        UUID conversationId = receive("ext-mode4", "provider-m4", "Question").conversationId();

        // The thread starts in HUMAN because Phase 4 has no AI agent, so asking for HUMAN is a
        // no-op. A worker retry must not fail on it.
        ConversationDetail detail = conversations.changeMode(staff, conversationId,
            new ChangeModeRequest(com.firstelement.crm.domain.ConversationMode.HUMAN, 0), null);

        assertThat(detail.mode()).isEqualTo("HUMAN");
        assertThat(detail.events()).extracting(e -> e.type()).doesNotContain("TAKEN_OVER");
    }

    @Test
    @DisplayName("return-to-AI is a legal transition and needs its modeVersion")
    void returnToAi() {
        UUID conversationId = receive("ext-mode2", "provider-m2", "Question").conversationId();

        ConversationDetail returned = conversations.returnToAi(staff, conversationId,
            new ChangeModeRequest(com.firstelement.crm.domain.ConversationMode.AI, 0), null);

        assertThat(returned.mode()).isEqualTo("AI");
        assertThat(returned.events()).extracting(e -> e.type()).contains("RETURNED_TO_AI");
    }

    @Test
    @DisplayName("a mode change with no modeVersion is refused")
    void modeChangeNeedsAVersion() {
        UUID conversationId = receive("ext-mode3", "provider-m3", "Question").conversationId();

        assertThatThrownBy(() -> conversations.changeMode(staff, conversationId,
            new ChangeModeRequest(com.firstelement.crm.domain.ConversationMode.HUMAN, null), null))
            .isInstanceOf(ValidationFailedException.class);
    }

    // ==================================================================
    // assignment semantics
    // ==================================================================

    @Test
    @DisplayName("an employee from another project cannot be made the assignee")
    void crossProjectAssigneeIsRefused() {
        UUID conversationId = receive("ext-asg2", "provider-a2", "Question").conversationId();
        // EMPLOYEE_BETA belongs to tenant B; its user has no membership in A1.
        assertThatThrownBy(() -> conversations.assign(staff, conversationId,
            new AssignConversationRequest(InboxFixture.EMPLOYEE_BETA), null, null))
            .isInstanceOf(ValidationFailedException.class)
            .hasMessageContaining("not a member of this project");

        assertThat(conversations.getById(staff, conversationId).assignedEmployee()).isNull();
    }

    @Test
    @DisplayName("an employee at their conversation limit cannot take another thread")
    void capacityIsEnforced() {
        UUID first = receive("ext-cap1", "provider-cap1", "One").conversationId();
        UUID second = receive("ext-cap2", "provider-cap2", "Two").conversationId();

        conversations.assign(staff, first,
            new AssignConversationRequest(InboxFixture.EMPLOYEE_ADMIN_A), null, null);
        assertThat(fixture.columnText("employees", InboxFixture.EMPLOYEE_ADMIN_A,
            "activeConversationCount")).isEqualTo("1");

        // EMPLOYEE_ADMIN_A has a limit of 1.
        assertThatThrownBy(() -> conversations.assign(staff, second,
            new AssignConversationRequest(InboxFixture.EMPLOYEE_ADMIN_A), null, null))
            .isInstanceOf(ValidationFailedException.class)
            .hasMessageContaining("conversation limit");
    }

    @Test
    @DisplayName("reassigning to the same employee is not blocked by their own limit")
    void reassignmentToTheSameEmployeeIsANoOp() {
        UUID conversationId = receive("ext-cap3", "provider-cap3", "One").conversationId();
        conversations.assign(staff, conversationId,
            new AssignConversationRequest(InboxFixture.EMPLOYEE_ADMIN_A), null, null);

        ConversationDetail again = conversations.assign(staff, conversationId,
            new AssignConversationRequest(InboxFixture.EMPLOYEE_ADMIN_A), null, null);

        assertThat(again.assignedEmployee().id()).isEqualTo(InboxFixture.EMPLOYEE_ADMIN_A);
    }

    @Test
    @DisplayName("unassigning releases the thread and recounts both workloads")
    void unassign() {
        UUID conversationId = receive("ext-un", "provider-u", "One").conversationId();
        conversations.assign(staff, conversationId,
            new AssignConversationRequest(InboxFixture.EMPLOYEE_STAFF_A), null, null);

        ConversationDetail released = conversations.assign(staff, conversationId,
            new AssignConversationRequest(null), null, null);

        assertThat(released.assignedEmployee()).isNull();
        assertThat(released.events()).extracting(e -> e.type()).contains("UNASSIGNED");
        assertThat(fixture.columnText("employees", InboxFixture.EMPLOYEE_STAFF_A,
            "activeConversationCount")).isEqualTo("0");
    }

    // ==================================================================
    // inbox query model
    // ==================================================================

    @Test
    @DisplayName("assigned-to-me keeps a released thread visible to the person who last replied")
    void assignedToMeFollowsTheLastReplier() {
        UUID conversationId = receiveAndReply("ext-inbox", "provider-inbox", "Question");
        conversations.assign(staff, conversationId,
            new AssignConversationRequest(InboxFixture.EMPLOYEE_STAFF_A), null, null);
        conversations.assign(staff, conversationId, new AssignConversationRequest(null), null, null);

        // A released thread has no owner, and it is still in this employee's list because they
        // were the last human to answer it.
        CursorPage<ConversationSummary> page = inbox.inbox(staff,
            new InboxQuery(null, InboxFilter.ASSIGNED_TO_ME, null, null, null, 30));

        assertThat(page.items()).extracting(ConversationSummary::id).contains(conversationId);
    }

    @Test
    @DisplayName("an untouched customer message is in the queue, and the queue is the default view")
    void untouchedThreadIsQueueWorkAndTheQueueIsTheDefault() {
        // This test originally asserted the OPPOSITE of its own name: that an untouched message was
        // in nobody's inbox and only reachable through the unassigned route. That was true while
        // the default was `assigned-to-me`, and it is the invisibility ADR 0015 removed -- a new
        // conversation nobody had answered was unreachable from the product's primary screen.
        //
        // The default is now the work queue, so the second half of the assertion is the important
        // one and the first half is what it displaced: an unfiltered request must reach the row,
        // while the personal view must still exclude it, because "no work is mine" and "this is not
        // my work" are different facts and both must remain expressible.
        UUID conversationId = receive("ext-untouched", "provider-untouched", "Hello").conversationId();

        assertThat(inbox.inbox(staff, new InboxQuery(null, null, null, null, null, 30)).items())
            .as("an employee opening the inbox must see work nobody has picked up")
            .extracting(ConversationSummary::id).contains(conversationId);

        assertThat(inbox.inbox(staff, new InboxQuery(null, InboxFilter.ASSIGNED_TO_ME, null, null, null, 30))
            .items())
            .as("but it is not the caller's own work, and the personal view says so")
            .extracting(ConversationSummary::id).doesNotContain(conversationId);

        assertThat(inbox.unassigned(staff, null, null, null, 30).entries())
            .extracting(e -> e.conversation().id()).contains(conversationId);
    }

    @Test
    @DisplayName("reopening clears BOTH stamps: a reopened case is neither closed nor resolved")
    void reopeningClearsResolvedAndClosedAt() {
        // The regression for a defect that made EVERY reopen answer 500 for EVERY caller.
        //
        // `ConversationRepository.conditionalUpdate` built the predicate `c."id" = :conversationId`
        // but only ever bound `expectedUpdatedAt` into the caller's map. `clearResolvedAt` passed a
        // map containing only `now`, so the statement failed with "No value supplied for the SQL
        // parameter 'conversationId'". Every other conversation transition happened to pass the id
        // itself, which is why compilation, the repository's own tests and every other transition
        // stayed green while reopen was broken in production.
        //
        // The assertion that matters is the STATE, not the absence of an exception: a reopen that
        // quietly left `resolvedAt` in place would report OPEN while still carrying the stamp of a
        // resolved case, and every downstream read of "is this resolved?" would disagree with the
        // status column.
        UUID conversationId = receiveAndReply("ext-reopen2", "provider-reopen2", "Question");
        conversations.closeAndUnassign(staff, conversationId, null, null);
        assertThat(conversations.getById(staff, conversationId).status()).isEqualTo("RESOLVED");

        ConversationDetail reopened = conversations.updateCaseState(staff, conversationId,
            "OPEN", null);

        assertThat(reopened.status()).isEqualTo("OPEN");
        // Read the stored stamps rather than a projection: `ConversationDetail` does not carry them,
        // and asserting on the row is the stronger claim anyway -- it is the column every other read
        // of "is this resolved?" consults.
        assertThat(fixture.columnText("conversations", conversationId, "resolvedAt"))
            .as("a reopened conversation must not still carry the instant it was resolved")
            .isNull();
        assertThat(fixture.columnText("conversations", conversationId, "closedAt"))
            .as("and must not still carry the instant it was closed")
            .isNull();
    }

    @Test
    @DisplayName("history holds the resolved and closed threads the employee was involved in")
    void historyView() {
        UUID conversationId = receiveAndReply("ext-hist", "provider-hist", "Question");
        conversations.closeAndUnassign(staff, conversationId, null, null);

        CursorPage<ConversationSummary> history = inbox.inbox(staff,
            new InboxQuery(null, InboxFilter.HISTORY, null, null, null, 30));
        assertThat(history.items()).extracting(ConversationSummary::id).contains(conversationId);

        CursorPage<ConversationSummary> active = inbox.inbox(staff,
            new InboxQuery(null, InboxFilter.ASSIGNED_TO_ME, null, null, null, 30));
        assertThat(active.items()).extracting(ConversationSummary::id).doesNotContain(conversationId);
    }

    @Test
    @DisplayName("the search covers ticket number, subject, CX id and message bodies")
    void search() {
        UUID conversationId = receiveAndReply("ext-search", "provider-search",
            "Where is my invoice?");

        // The legacy search branches, one assertion each: a message body, the customer's CX id,
        // the ticket number, and a term that matches nothing.
        String cxId = customers.getById(staff,
            inbox.inbox(staff, new InboxQuery(null, null, null, null, null, 30)).items().get(0)
                .customer().id())
            .cxId();
        String ticketNumber = conversations.getById(staff, conversationId).ticketNumber();

        assertThat(searchHits("invoice")).contains(conversationId);
        assertThat(searchHits(cxId)).contains(conversationId);
        assertThat(searchHits(ticketNumber)).contains(conversationId);
        assertThat(searchHits("Ack")).contains(conversationId);
        assertThat(searchHits("nothing-matches-this")).isEmpty();
    }

    /** The ids the inbox returns for a search term, in the caller's scope. */
    private java.util.List<UUID> searchHits(String term) {
        return inbox.inbox(staff, new InboxQuery(null, null, null, term, null, 30)).items()
            .stream().map(ConversationSummary::id).toList();
    }

    @Test
    @DisplayName("a channel filter narrows the inbox")
    void channelFilter() {
        receiveAndReply("ext-chan", "provider-chan", "On mock");

        assertThat(inbox.inbox(staff, new InboxQuery(null, null, ChannelType.MOCK, null, null, 30))
            .items()).isNotEmpty();
        assertThat(inbox.inbox(staff,
            new InboxQuery(null, null, ChannelType.TELEGRAM, null, null, 30)).items()).isEmpty();
    }

    @Test
    @DisplayName("cursor pagination walks every row exactly once")
    void cursorPagination() {
        UUID first = receiveAndReply("ext-page", "provider-p1", "One");
        UUID second = receiveAndReply("ext-page2", "provider-p2", "Two");
        UUID third = receiveAndReply("ext-page3", "provider-p3", "Three");

        CursorPage<ConversationSummary> pageOne = inbox.inbox(staff,
            new InboxQuery(null, null, null, null, null, 2));
        assertThat(pageOne.items()).hasSize(2);
        assertThat(pageOne.nextCursor()).isEqualTo(pageOne.items().get(1).id().toString());

        CursorPage<ConversationSummary> pageTwo = inbox.inbox(staff,
            new InboxQuery(null, null, null, null, UUID.fromString(pageOne.nextCursor()), 2));
        assertThat(pageTwo.items()).hasSize(1);
        assertThat(pageTwo.nextCursor()).isNull();

        java.util.List<UUID> seen = new java.util.ArrayList<>();
        pageOne.items().forEach(item -> seen.add(item.id()));
        pageTwo.items().forEach(item -> seen.add(item.id()));
        assertThat(seen).containsExactlyInAnyOrder(first, second, third);
        assertThat(seen).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("an out-of-scope cursor is INVALID_CURSOR, not a silently empty page")
    void invalidCursorIsLoud() {
        assertThatThrownBy(() -> inbox.inbox(staff,
            new InboxQuery(null, null, null, null, InboxFixture.CUSTOMER_B1, 30)))
            .isInstanceOf(ValidationFailedException.class)
            .satisfies(thrown -> assertThat(((ValidationFailedException) thrown).code())
                .isEqualTo("INVALID_CURSOR"));
    }

    @Test
    @DisplayName("the inbox never crosses a tenant or an inaccessible project")
    void inboxIsScoped() {
        UUID mine = receiveAndReply("ext-scope", "provider-scope", "Mine");
        // A thread in tenant B, with no assignee and no replier, so only the tenant check can
        // keep it out of tenant A's list.
        UUID theirs = fixture.conversation("0000000000b1", InboxFixture.ORG_B, InboxFixture.PROJECT_B1,
            InboxFixture.CUSTOMER_B1, "#0814000000b1", "OPEN", "HUMAN", InboxFixture.SEED_AT);

        assertThat(inbox.inbox(staff, new InboxQuery(null, null, null, null, null, 30)).items())
            .extracting(ConversationSummary::id)
            .contains(mine)
            .doesNotContain(theirs);

        // Tenant B's admin cannot see tenant A's thread either: the isolation is symmetric, not a
        // one-way filter.
        assertThat(inbox.unassigned(beta, null, null, null, 30).entries())
            .extracting(e -> e.conversation().id())
            .contains(theirs)
            .doesNotContain(mine);

        assertThat(inbox.inbox(outsider, new InboxQuery(null, null, null, null, null, 30)).items())
            .isEmpty();
        assertThat(inbox.unassigned(outsider, null, null, null, 30).entries()).isEmpty();
    }

    @Test
    @DisplayName("a requested project outside the scope is refused before a statement exists")
    void requestedProjectIsValidated() {
        assertThatThrownBy(() -> inbox.inbox(staff,
            new InboxQuery(InboxFixture.PROJECT_A2, null, null, null, null, 30)))
            .isInstanceOf(PolicyDeniedException.class);
    }

    @Test
    @DisplayName("the saved-view counts agree with the lists they label")
    void savedViewCountsMatchTheLists() {
        UUID open = receiveAndReply("ext-count1", "provider-count1", "Open thread");
        UUID closed = receiveAndReply("ext-count2", "provider-count2", "Closed thread");
        conversations.closeAndUnassign(staff, closed, null, null);

        Map<String, Integer> counts = inbox.savedViewCounts(staff, null, null, null);

        assertThat(counts.get(InboxFilter.ASSIGNED_TO_ME.value()))
            .isEqualTo(inbox.inbox(staff,
                new InboxQuery(null, InboxFilter.ASSIGNED_TO_ME, null, null, null, 100)).items().size());
        assertThat(counts.get(InboxFilter.HISTORY.value()))
            .isEqualTo(inbox.inbox(staff,
                new InboxQuery(null, InboxFilter.HISTORY, null, null, null, 100)).items().size());
        assertThat(counts.get(InboxFilter.HISTORY.value())).isGreaterThanOrEqualTo(1);
        assertThat(open).isNotNull();
    }

    // ==================================================================
    // the unassigned queue
    // ==================================================================

    @Test
    @DisplayName("a thread nobody ever owned is explained as NEVER_ASSIGNED")
    void queueExplainsNeverAssigned() {
        UUID conversationId = receive("ext-q1", "provider-q1", "Waiting").conversationId();

        UnassignedQueuePage page = inbox.unassigned(staff, null, null, null, 30);

        UnassignedQueuePage.Entry entry = page.entries().stream()
            .filter(e -> e.conversation().id().equals(conversationId))
            .findFirst()
            .orElseThrow();
        assertThat(entry.reason()).isEqualTo(UnassignedQueuePage.Reason.NEVER_ASSIGNED);
        assertThat(entry.signals()).containsEntry("lastAssignedAt", null);
        assertThat(entry.waitingSeconds()).isGreaterThanOrEqualTo(0L);
        assertThat(page.totals().total()).isEqualTo(1);
        assertThat(page.totals().byStatus()).containsEntry("OPEN", 1);
    }

    @Test
    @DisplayName("a released thread is explained as RELEASED, with the facts that decided it")
    void queueExplainsReleased() {
        UUID conversationId = receive("ext-q2", "provider-q2", "Waiting").conversationId();
        conversations.assign(staff, conversationId,
            new AssignConversationRequest(InboxFixture.EMPLOYEE_STAFF_A), null, null);
        conversations.assign(staff, conversationId, new AssignConversationRequest(null), null, null);

        UnassignedQueuePage.Entry entry = entryFor(inbox.unassigned(staff, null, null, null, 30),
            conversationId);

        assertThat(entry.reason()).isEqualTo(UnassignedQueuePage.Reason.RELEASED);
        assertThat(entry.signals().get("lastAssignedAt")).isNotNull();
        assertThat(entry.signals().get("lastUnassignedAt")).isNotNull();
    }

    @Test
    @DisplayName("a resolved thread that became active again is explained as REOPENED")
    void queueExplainsReopened() {
        UUID conversationId = receive("ext-q3", "provider-q3", "Waiting").conversationId();
        conversations.assign(staff, conversationId,
            new AssignConversationRequest(InboxFixture.EMPLOYEE_STAFF_A), null, null);
        conversations.closeAndUnassign(staff, conversationId, null, null);
        conversations.reply(staff, conversationId, new SendMessageRequest("Back again"), null, null,
            null);

        UnassignedQueuePage.Entry entry = entryFor(inbox.unassigned(staff, null, null, null, 30),
            conversationId);

        assertThat(entry.reason()).isEqualTo(UnassignedQueuePage.Reason.REOPENED);
    }

    @Test
    @DisplayName("an assigned, resolved thread is not in the queue")
    void resolvedThreadsAreNotQueued() {
        UUID conversationId = receive("ext-q4", "provider-q4", "Waiting").conversationId();
        conversations.assign(staff, conversationId,
            new AssignConversationRequest(InboxFixture.EMPLOYEE_STAFF_A), null, null);
        conversations.closeAndUnassign(staff, conversationId, null, null);

        assertThat(inbox.unassigned(staff, null, null, null, 30).entries())
            .extracting(e -> e.conversation().id()).doesNotContain(conversationId);
    }

    @Test
    @DisplayName("the queue never crosses a tenant or an inaccessible project")
    void queueIsScoped() {
        UUID mine = receive("ext-q5", "provider-q5", "Mine").conversationId();
        fixture.conversation("0000000000b2", InboxFixture.ORG_B, InboxFixture.PROJECT_B1,
            InboxFixture.CUSTOMER_B1, "#0814000000b2", "OPEN", "HUMAN", InboxFixture.SEED_AT);

        assertThat(inbox.unassigned(staff, null, null, null, 30).entries())
            .extracting(e -> e.conversation().id()).containsExactly(mine);
        assertThat(inbox.unassigned(outsider, null, null, null, 30).entries()).isEmpty();
        assertThat(inbox.unassigned(beta, null, null, null, 30).entries()).hasSize(1);
        assertThatThrownBy(() -> inbox.unassigned(staff, InboxFixture.PROJECT_A2, null, null, 30))
            .isInstanceOf(PolicyDeniedException.class);
    }

    // ==================================================================
    // customer-facing vs internal history
    // ==================================================================

    @Test
    @DisplayName("the customer transcript hides events, author names and delivery failures")
    void transcriptIsSeparatedFromTheInternalView() {
        UUID conversationId = receive("ext-tr", "provider-tr", "Where is my invoice?").conversationId();
        conversations.reply(staff, conversationId,
            new SendMessageRequest("Sent by email yesterday"), null, null, null);
        conversations.assign(staff, conversationId,
            new AssignConversationRequest(InboxFixture.EMPLOYEE_STAFF_A), null, null);

        ConversationDetail internal = conversations.getById(staff, conversationId);
        CustomerTranscript transcript = conversations.transcript(staff, conversationId);

        // The internal view has the timeline the customer must never see.
        assertThat(internal.events()).extracting(e -> e.type()).contains("ASSIGNED");

        assertThat(transcript.messages()).hasSize(2);
        assertThat(transcript.messages().stream().map(CustomerTranscript.Entry::authorLabel))
            .containsExactly("You", "Support");
        assertThat(transcript.messages()).allSatisfy(message ->
            assertThat(message.authorLabel()).isNotEqualTo("Alpha Staff"));
        assertThat(transcript.ticketNumber()).isEqualTo(internal.ticketNumber());
        assertThat(transcript.subject()).isEqualTo(internal.subject());
    }

    @Test
    @DisplayName("an internal SYSTEM message is not part of the customer transcript")
    void systemMessagesStayInternal() {
        UUID conversationId = receive("ext-tr2", "provider-tr2", "Hello").conversationId();
        fixture.jdbc().update("""
            insert into messages ("id", "organizationId", "projectId", "conversationId", "channel",
                "direction", "authorType", "body", "deliveryStatus", "sentAt", "createdAt")
            values (gen_random_uuid(), :org, :project, :conversation, cast('MOCK' as "ChannelType"),
                    cast('OUTBOUND' as "MessageDirection"), cast('SYSTEM' as "MessageAuthorType"),
                    'internal notice', cast('SENT' as "MessageDeliveryStatus"), :now, :now)
            """,
            new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                .addValue("org", InboxFixture.ORG_A).addValue("project", InboxFixture.PROJECT_A1)
                .addValue("conversation", conversationId).addValue("now", InboxFixture.SEED_AT));

        assertThat(conversations.getById(staff, conversationId).messages()).hasSize(2);
        assertThat(conversations.transcript(staff, conversationId).messages()).hasSize(1);
    }

    @Test
    @DisplayName("a transcript of another tenant's conversation is a 404")
    void transcriptIsScoped() {
        UUID betaConversation = fixture.conversation("0000000000b3", InboxFixture.ORG_B,
            InboxFixture.PROJECT_B1, InboxFixture.CUSTOMER_B1, "#0814000000b3", "OPEN", "HUMAN",
            InboxFixture.SEED_AT);

        assertThatThrownBy(() -> conversations.transcript(staff, betaConversation))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    // ==================================================================
    // helpers
    // ==================================================================

    private InboundResult receive(String externalUserId, String externalMessageId, String text) {
        return inbound.receive(staff, InboxFixture.PROJECT_A1, null, new IncomingMessage(
            externalUserId, text, null, null, null, externalMessageId, ChannelType.MOCK,
            Map.of(), Map.of(), LocalDateTime.now()));
    }

    /**
     * Receives a message and answers it once.
     *
     * <p>The legacy inbox default view is {@code assigned-to-me}, whose involvement test is
     * "assigned to an employee whose user is me, <em>or</em> I was the last human replier". An
     * unanswered thread is therefore in nobody's list, which is correct: a customer message nobody
     * has touched belongs to the unassigned queue, not to an employee's inbox.
     */
    private UUID receiveAndReply(String externalUserId, String externalMessageId,
                                 String inboundText) {
        UUID conversationId = receive(externalUserId, externalMessageId, inboundText)
            .conversationId();
        conversations.reply(staff, conversationId, new SendMessageRequest("Ack"),
            null, null, null);
        return conversationId;
    }

    private static UnassignedQueuePage.Entry entryFor(UnassignedQueuePage page, UUID id) {
        return page.entries().stream()
            .filter(entry -> entry.conversation().id().equals(id))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Queue entry not found for " + id
                + "; entries=" + page.entries().stream()
                    .map(e -> e.conversation().id().toString()).toList()));
    }

    /** Keeps the admin context referenced so the fixture's shape stays visible in this suite. */
    @SuppressWarnings("unused")
    private TenantContext adminContext() {
        return admin;
    }
}
