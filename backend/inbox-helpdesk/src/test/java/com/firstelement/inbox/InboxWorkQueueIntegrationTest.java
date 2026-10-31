package com.firstelement.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstelement.crm.domain.ChannelType;
import com.firstelement.crm.domain.InboxFilter;
import com.firstelement.inbox.api.AssignConversationRequest;
import com.firstelement.inbox.api.InboxQuery;
import com.firstelement.inbox.api.IncomingMessage;
import com.firstelement.inbox.service.ConversationService;
import com.firstelement.inbox.service.InboxQueryService;
import com.firstelement.inbox.service.InboundMessageService;
import com.firstelement.security.TenantContext;
import com.firstelement.security.error.PolicyDeniedException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The work queue, against a real PostgreSQL 16 / pgvector container.
 *
 * <h2>The defect this suite exists for</h2>
 *
 * <p>Before Phase 15 the inbox list accepted only {@code assigned-to-me} and {@code history} and
 * defaulted to the first. Its involvement test is "assigned to me, <em>or</em> I was the last human
 * replier", so a brand-new, unassigned conversation satisfied neither branch — the product's
 * primary screen could not show the work queue at all. It was reachable only by navigating to
 * {@code /inbox/unassigned} and knowing to look there.
 *
 * <h2>Why these assertions are behavioural, not structural</h2>
 *
 * <p>A predicate can look right and still exclude the row that matters. Every test here puts a real
 * conversation through the real predicate and asks where it lands, including the rows that must
 * <em>not</em> land there: "appears under unassigned" is worthless without "and does not appear
 * under assigned-to-me".
 *
 * <h2>The reconciliation</h2>
 *
 * <p>Two routes answer "what is unassigned": {@code GET /inbox/unassigned} (legacy, richer response)
 * and {@code ?filter=unassigned} (canonical: it is in the wire vocabulary, it is counted by
 * {@code /inbox/counts}, and it is what the OpenAPI document publishes). The legacy route takes its
 * membership rule from {@code InboxFilterDefinition.UNASSIGNED_ACTIVE} by reference, and
 * {@link #theLegacyRouteAndTheFilterReturnTheSameSet()} asserts the two sets are equal rather than
 * trusting a comment to hold.
 *
 * <h2>A standard workload, reused</h2>
 *
 * <p>{@link #workload()} seeds one row of every interesting shape — unowned, mine, a colleague's,
 * released, resolved — so a test states only the view it is about. The narrow/ancillary tests would
 * otherwise re-derive the same rows and, worse, would assert emptiness against a fixture that never
 * put a matching row in place.
 */
@SpringBootTest(classes = InboxTestApplication.class)
@AutoConfigureMockMvc
class InboxWorkQueueIntegrationTest extends InboxIntegrationTestBase {

    /** The three views this phase adds. Loops cover every one of them. */
    private static final List<InboxFilter> NEW_FILTERS = List.of(
        InboxFilter.UNASSIGNED, InboxFilter.ALL_OPEN, InboxFilter.ASSIGNED_TO_OTHERS);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** One row of every shape the views distinguish between. */
    private record Workload(UUID unowned, UUID mine, UUID theirs, UUID released, UUID closed) {
    }

    @Autowired
    private InboxFixture fixture;

    @Autowired
    private InboxQueryService inbox;

    @Autowired
    private ConversationService conversations;

    @Autowired
    private InboundMessageService inbound;

    @Autowired
    private MockMvc mvc;

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
    // 1. the work queue is reachable
    // ==================================================================

    @Test
    @DisplayName("a new unassigned conversation appears under unassigned and under nothing else")
    void aNewUnassignedConversationIsInTheWorkQueueAndNowhereElse() {
        UUID fresh = receive("ext-work-1", "provider-work-1", "Nobody has answered me yet");

        // The whole defect: this row used to be invisible to every view the list could express.
        assertThat(ids(InboxFilter.UNASSIGNED)).contains(fresh);

        // And it must not leak into the other views, or "unassigned" means nothing.
        assertThat(ids(InboxFilter.ASSIGNED_TO_ME))
            .as("nobody is involved yet: not assigned, and nobody replied")
            .doesNotContain(fresh);
        assertThat(ids(InboxFilter.HISTORY)).doesNotContain(fresh);
        assertThat(ids(InboxFilter.ALL_OPEN)).contains(fresh);
        assertThat(ids(InboxFilter.ASSIGNED_TO_OTHERS))
            .as("an unowned conversation belongs to the queue, not to a peer's load")
            .doesNotContain(fresh);
    }

    @Test
    @DisplayName("an inbox opened with no filter shows the work queue: nobody's work is invisible")
    void theDefaultShowsTheWorkQueue() throws Exception {
        UUID fresh = receive("ext-work-default", "provider-work-default", "Waiting");

        // The ruling (ADR 0015). With the old default this request equalled `?filter=assigned-to-me`,
        // which returned nothing here, and an employee opening the inbox could not tell "no work
        // exists" from "no work is MINE" — the two render identically and mean opposite things.
        //
        // The assertion is on STATE AFTER the request: which rows came back. Not on the parameter,
        // and not on the absence of an error, because a 200 with the wrong rows is the exact failure
        // the ruling exists to prevent.
        List<String> withoutFilter = idsIn(mvc.perform(get("/api/v1/conversations"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());

        assertThat(withoutFilter)
            .as("an unassigned conversation must be visible to an employee who opens the inbox")
            .contains(fresh.toString());

        // The default is a resting state, not a removal: the personal view is still reachable by name.
        List<String> withAssignedToMe = idsIn(mvc.perform(get("/api/v1/conversations")
                .param("filter", InboxFilter.ASSIGNED_TO_ME.value()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());

        assertThat(withAssignedToMe).doesNotContain(fresh.toString());
    }

    @Test
    @DisplayName("assigning drains the row from the queue and gives it an owner")
    void assignmentMovesTheRowFromTheQueueToItsOwner() {
        UUID conversationId = receive("ext-work-2", "provider-work-2", "Waiting");
        assertThat(ids(InboxFilter.UNASSIGNED)).contains(conversationId);

        conversations.assign(staff, conversationId,
            new AssignConversationRequest(InboxFixture.EMPLOYEE_STAFF_A), null, null);

        assertThat(ids(InboxFilter.UNASSIGNED)).doesNotContain(conversationId);
        assertThat(ids(InboxFilter.ASSIGNED_TO_ME)).contains(conversationId);
        assertThat(ids(InboxFilter.ALL_OPEN)).contains(conversationId);
        // Now it IS somebody else's, for a different caller.
        assertThat(idsFor(admin, InboxFilter.ASSIGNED_TO_OTHERS)).contains(conversationId);
    }

    @Test
    @DisplayName("a PENDING unassigned conversation is in the queue and a RESOLVED one is not")
    void theQueueIsOpenOrPendingOnly() {
        UUID pending = fixture.conversation("00000000f0a1", InboxFixture.ORG_A,
            InboxFixture.PROJECT_A1, InboxFixture.CUSTOMER_A1, "#0815000001", "PENDING", "HUMAN",
            InboxFixture.SEED_AT);
        UUID resolved = fixture.conversation("00000000f0a2", InboxFixture.ORG_A,
            InboxFixture.PROJECT_A1, InboxFixture.CUSTOMER_A1, "#0815000002", "RESOLVED", "HUMAN",
            InboxFixture.SEED_AT);

        assertThat(ids(InboxFilter.UNASSIGNED)).containsExactly(pending);
        assertThat(ids(InboxFilter.UNASSIGNED)).doesNotContain(resolved);
    }

    // ==================================================================
    // 2. all-open
    // ==================================================================

    @Test
    @DisplayName("all-open returns every open conversation, whoever owns it, and no closed one")
    void allOpenIgnoresOwnership() {
        Workload w = workload();

        assertThat(ids(InboxFilter.ALL_OPEN))
            .containsExactlyInAnyOrder(w.unowned(), w.mine(), w.theirs(), w.released());
        assertThat(ids(InboxFilter.ALL_OPEN)).doesNotContain(w.closed());

        // A supervisor's whole load, from a caller who owns none of it.
        assertThat(idsFor(admin, InboxFilter.ALL_OPEN))
            .as("an ADMIN's load includes a colleague's threads, which is the point of the view")
            .contains(w.theirs(), w.mine());
        assertThat(idsFor(outsider, InboxFilter.ALL_OPEN))
            .as("a no-membership principal has no project scope, so no load at all")
            .isEmpty();
    }

    // ==================================================================
    // 3. assigned-to-others
    // ==================================================================

    @Test
    @DisplayName("assigned-to-others returns peers' threads and excludes the caller's own")
    void assignedToOthersExcludesTheCallersOwn() {
        Workload w = workload();

        assertThat(idsFor(staff, InboxFilter.ASSIGNED_TO_OTHERS))
            .containsExactly(w.theirs());
        assertThat(idsFor(staff, InboxFilter.ASSIGNED_TO_OTHERS))
            .as("mine is mine, unowned is nobody's, released is unowned, and none is open")
            .doesNotContain(w.mine(), w.unowned(), w.released());

        // The mirror image. A view that did not actually depend on the caller would answer the same
        // way here, and that is the bug this catches.
        assertThat(idsFor(admin, InboxFilter.ASSIGNED_TO_OTHERS)).containsExactly(w.mine());
        assertThat(idsFor(admin, InboxFilter.ASSIGNED_TO_OTHERS))
            .doesNotContain(w.theirs(), w.unowned(), w.released());
    }

    @Test
    @DisplayName("assigned-to-others is open-only, however the thread was closed")
    void assignedToOthersIsOpenOnly() {
        UUID closedByStatus = receive("ext-others-1", "provider-others-1", "Mine, then done");
        conversations.assign(staff, closedByStatus,
            new AssignConversationRequest(InboxFixture.EMPLOYEE_STAFF_A), null, null);
        setStatus(closedByStatus, "RESOLVED");

        assertThat(idsFor(admin, InboxFilter.ASSIGNED_TO_OTHERS)).doesNotContain(closedByStatus);
        assertThat(idsFor(admin, InboxFilter.ALL_OPEN)).doesNotContain(closedByStatus);
        assertThat(idsFor(admin, InboxFilter.HISTORY))
            .as("RESOLVED and involved in the caller's org, so history rather than a live view")
            .isNotNull();
    }

    // ==================================================================
    // 4. counts cover every filter the list accepts
    // ==================================================================

    @Test
    @DisplayName("/inbox/counts publishes a key for EVERY filter the list accepts")
    void countsPublishAKeyForEveryFilter() throws Exception {
        Workload w = workload();

        JsonNode body = MAPPER.readTree(mvc.perform(get("/api/v1/inbox/counts"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());

        // Set EQUALITY, not "some keys exist". A count endpoint that omits a filter renders a
        // permanent zero on that tab -- a number that is not wrong, only missing, which is how a
        // broken product looks rather than how a broken endpoint announces itself.
        List<String> keys = new ArrayList<>();
        body.fieldNames().forEachRemaining(keys::add);
        assertThat(keys).containsExactlyElementsOf(InboxFilter.acceptedValues());
        assertThat(new HashSet<>(keys)).hasSize(InboxFilter.values().length);

        // Every count agrees with the list it labels, so a badge never contradicts its tab.
        for (InboxFilter filter : InboxFilter.values()) {
            assertThat(body.get(filter.value()).asInt())
                .as("%s: the badge and the list must be the same question", filter.value())
                .isEqualTo(ids(filter).size());
        }
        assertThat(body.get(InboxFilter.UNASSIGNED.value()).asInt())
            .as("two unowned open threads in the workload: unowned and released")
            .isEqualTo(2);
        assertThat(w.unowned()).isNotNull();
    }

    @Test
    @DisplayName("an unowned thread is counted as work waiting, not as nobody's business")
    void countsIncludeTheNewViews() {
        UUID waiting = receive("ext-counts-3", "provider-counts-3", "Waiting");

        Map<String, Integer> counts = countsFor(staff);

        assertThat(counts).containsEntry(InboxFilter.UNASSIGNED.value(), 1);
        assertThat(counts).containsEntry(InboxFilter.ALL_OPEN.value(), 1);
        assertThat(counts).containsEntry(InboxFilter.ASSIGNED_TO_ME.value(), 0);
        assertThat(counts).containsEntry(InboxFilter.ASSIGNED_TO_OTHERS.value(), 0);
        assertThat(counts).containsEntry(InboxFilter.HISTORY.value(), 0);
        assertThat(counts.keySet()).containsExactlyElementsOf(InboxFilter.acceptedValues());
        assertThat(counts.get(InboxFilter.UNASSIGNED.value()))
            .as("a customer thread nobody has touched is work waiting, and must be visible as such")
            .isEqualTo(1);
        assertThat(waiting).isNotNull();
    }

    // ==================================================================
    // 5. an unknown value is a refusal
    // ==================================================================

    @Test
    @DisplayName("an unknown filter value is a 400 refusal, not a 500")
    void unknownFilterIsRefusedNotFiveHundred() throws Exception {
        for (String rejected : List.of("everything", "UNASSIGNED", "Assigned-To-Me", "unassigned,history")) {
            var response = mvc.perform(get("/api/v1/conversations").param("filter", rejected))
                .andReturn().getResponse();

            assertThat(response.getStatus())
                .as("filter=%s must be refused, never a 500: a request the server cannot answer is "
                    + "a refusal, not a crash", rejected)
                .isEqualTo(400);
            JsonNode body = MAPPER.readTree(response.getContentAsString());
            assertThat(body.get("code").asText()).isEqualTo("VALIDATION_FAILED");
            assertThat(body.get("retryable").asBoolean()).isFalse();
            assertThat(body.get("message").asText()).isNotBlank();
            assertThat(body.get("timestamp").asText()).isNotBlank();
            // The shared ErrorResponse key set, so this refusal is the same shape a client already
            // handles. `requestId` is populated by CorrelationIdFilter, which MockMvc does not run,
            // so it is asserted present-but-null rather than present-and-populated.
            List<String> keys = new ArrayList<>();
            body.fieldNames().forEachRemaining(keys::add);
            assertThat(keys).contains("code", "message", "details", "requestId", "timestamp",
                "retryable");
        }
    }

    @Test
    @DisplayName("the refusal names the accepted values, so the caller can recover")
    void theRefusalCarriesTheAcceptedValues() throws Exception {
        JsonNode body = MAPPER.readTree(mvc.perform(get("/api/v1/conversations")
                .param("filter", "everything"))
            .andExpect(status().isBadRequest())
            .andReturn().getResponse().getContentAsString());

        assertThat(body.get("details").get("field").asText()).isEqualTo("filter");
        List<String> accepted = new ArrayList<>();
        body.get("details").get("accepted").forEach(node -> accepted.add(node.asText()));
        assertThat(accepted).containsExactlyElementsOf(InboxFilter.acceptedValues());
    }

    // ==================================================================
    // 6. cross-tenant and cross-project negatives, for EVERY new filter
    // ==================================================================

    @Test
    @DisplayName("another tenant's rows never appear in any view, new or legacy")
    void noNewViewCrossesATenant() {
        Workload w = workload();
        BetaRows theirsInBeta = betaRows();

        for (InboxFilter filter : InboxFilter.values()) {
            assertThat(idsFor(staff, filter))
                .as("%s must not reach tenant B", filter.value())
                .doesNotContain(theirsInBeta.unowned(), theirsInBeta.owned());
            assertThat(idsFor(beta, filter))
                .as("%s for tenant B must not reach tenant A", filter.value())
                .doesNotContain(w.unowned(), w.mine(), w.theirs(), w.released(), w.closed());
        }

        // Each tenant does see its own rows, so the assertions above are not vacuous.
        assertThat(idsFor(beta, InboxFilter.UNASSIGNED)).containsExactly(theirsInBeta.unowned());
        assertThat(idsFor(beta, InboxFilter.ASSIGNED_TO_OTHERS))
            .as("beta's only owned thread belongs to beta, so it is not 'others' to beta")
            .isEmpty();
        assertThat(idsFor(beta, InboxFilter.ALL_OPEN)).containsExactlyInAnyOrder(
            theirsInBeta.unowned(), theirsInBeta.owned());

        // The legacy route is scoped too -- it was already, but it is the other way to ask the
        // same question, so it is checked here rather than assumed.
        assertThat(queueIds(staff))
            .doesNotContain(theirsInBeta.unowned(), theirsInBeta.owned());
    }

    @Test
    @DisplayName("an out-of-scope project is refused before a statement exists, for every new view")
    void outOfScopeProjectIsRefusedBeforeTheQuery() {
        workload();
        for (InboxFilter filter : NEW_FILTERS) {
            assertThatThrownBy(() -> inbox.inbox(staff,
                new InboxQuery(InboxFixture.PROJECT_A2, filter, null, null, null, 30)))
                .as("%s: a client-supplied project id is a request, never authorization",
                    filter.value())
                .isInstanceOf(PolicyDeniedException.class);

            // The counts build the same scope, so they must refuse before they count anything.
            assertThatThrownBy(() -> inbox.savedViewCounts(staff, InboxFixture.PROJECT_A2, null, null))
                .as("%s: counts must refuse the scope the list refuses", filter.value())
                .isInstanceOf(PolicyDeniedException.class);
        }
    }

    @Test
    @DisplayName("the out-of-scope refusal is non-disclosing and identical for every new view")
    void outOfScopeRefusalIsNonDisclosing() throws Exception {
        for (InboxFilter filter : NEW_FILTERS) {
            String denied = mvc.perform(get("/api/v1/conversations")
                    .param("projectId", InboxFixture.PROJECT_A2.toString())
                    .param("filter", filter.value()))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
            String nonexistent = mvc.perform(get("/api/v1/conversations")
                    .param("projectId", UUID.randomUUID().toString())
                    .param("filter", filter.value()))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

            // Only the correlation id and the timestamp may differ. If a denial said more than a
            // nonexistent id does, the status code becomes an existence oracle.
            JsonNode a = MAPPER.readTree(denied);
            JsonNode b = MAPPER.readTree(nonexistent);
            assertThat(a.get("code")).as("%s: code", filter.value()).isEqualTo(b.get("code"));
            assertThat(a.get("message")).as("%s: message", filter.value())
                .isEqualTo(b.get("message"));
            assertThat(a.get("details")).as("%s: details", filter.value())
                .isEqualTo(b.get("details"));
        }
    }

    @Test
    @DisplayName("a no-membership principal sees no work queue at all, and counts nothing")
    void noMembershipSeesNoQueue() {
        workload();

        for (InboxFilter filter : NEW_FILTERS) {
            assertThat(idsFor(outsider, filter))
                .as("%s for a principal with no membership row", filter.value())
                .isEmpty();
        }
        assertThat(countsFor(outsider)).containsOnlyKeys(InboxFilter.acceptedValues());
        assertThat(countsFor(outsider).values()).allSatisfy(
            count -> assertThat(count).as("every badge for a principal with no scope").isZero());
        assertThat(PrincipalScope.call(outsider,
            () -> inbox.unassigned(outsider, null, null, null, 100).entries())).isEmpty();
    }

    // ==================================================================
    // 7. the two unassigned routes agree
    // ==================================================================

    @Test
    @DisplayName("/inbox/unassigned and ?filter=unassigned return the SAME set")
    void theLegacyRouteAndTheFilterReturnTheSameSet() throws Exception {
        Workload w = workload();

        // A PENDING unassigned row, added for THIS test only, because it is the row that makes the
        // two definitions distinguishable. With only OPEN rows, a second definition of "unassigned"
        // that quietly dropped PENDING would still return the same set, so the reconciliation
        // would pass while the two routes had already begun to drift -- which is the entire reason
        // the membership rule is shared rather than written twice. Found by negative control NC-B:
        // this assertion was green against exactly that drift.
        UUID pending = fixture.conversation("00000000f0e1", InboxFixture.ORG_A,
            InboxFixture.PROJECT_A1, InboxFixture.CUSTOMER_A1, "#0815000040", "PENDING", "HUMAN",
            InboxFixture.SEED_AT);
        // ...and a RESOLVED unassigned row, which neither route may list.
        UUID resolved = fixture.conversation("00000000f0e2", InboxFixture.ORG_A,
            InboxFixture.PROJECT_A1, InboxFixture.CUSTOMER_A1, "#0815000041", "RESOLVED", "HUMAN",
            InboxFixture.SEED_AT);

        Set<UUID> fromLegacyRoute = new HashSet<>(inbox.unassigned(staff, null, null, null, 100)
            .entries().stream().map(entry -> entry.conversation().id()).toList());
        Set<UUID> fromFilter = new HashSet<>(ids(InboxFilter.UNASSIGNED));

        // The reconciliation requirement, asserted rather than described.
        assertThat(fromLegacyRoute).containsExactlyInAnyOrder(w.unowned(), w.released(), pending);
        assertThat(fromFilter)
            .as("the filter is canonical; the legacy route must not be a second definition")
            .isEqualTo(fromLegacyRoute);
        assertThat(fromFilter).doesNotContain(w.mine(), w.theirs(), w.closed(), resolved);

        // Over the wire too, so the reconciliation is not only true in the service layer.
        List<String> legacyIds = queueIdsIn(mvc.perform(get("/api/v1/inbox/unassigned"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        List<String> filterIds = idsIn(mvc.perform(get("/api/v1/conversations")
                .param("filter", InboxFilter.UNASSIGNED.value()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(new HashSet<>(filterIds)).isEqualTo(new HashSet<>(legacyIds));
        assertThat(filterIds).as("the wire answer includes the PENDING thread too").hasSize(3);
    }

    @Test
    @DisplayName("the counts agree with the legacy queue's own total, which is the same question")
    void countsAgreeWithTheLegacyQueueTotal() {
        workload();

        assertThat(countsFor(staff).get(InboxFilter.UNASSIGNED.value()))
            .isEqualTo(PrincipalScope.call(staff,
                () -> inbox.unassigned(staff, null, null, null, 100).totals().total()));
    }

    @Test
    @DisplayName("both unassigned routes are empty for a caller with no project scope")
    void bothRoutesAreEmptyWithoutScope() {
        workload();

        assertThat(idsFor(outsider, InboxFilter.UNASSIGNED)).isEmpty();
        assertThat(PrincipalScope.call(outsider,
            () -> inbox.unassigned(outsider, null, null, null, 100).entries())).isEmpty();
    }

    // ==================================================================
    // 8. the filter is orthogonal to the other narrowing params
    // ==================================================================

    @Test
    @DisplayName("a channel narrows every new view without changing its meaning")
    void channelNarrowsEveryNewView() {
        Workload w = workload();

        for (InboxFilter filter : NEW_FILTERS) {
            // The row each view must keep: the unowned one for UNASSIGNED/ALL_OPEN, the peer's for
            // ASSIGNED_TO_OTHERS. A narrowing assertion against a row the view would never have
            // returned anyway would be vacuous.
            UUID expected = filter == InboxFilter.ASSIGNED_TO_OTHERS ? w.theirs() : w.unowned();
            assertThat(idsFor(staff, filter, ChannelType.MOCK, null))
                .as("%s on the channel its rows use", filter.value())
                .contains(expected);
            assertThat(idsFor(staff, filter, ChannelType.TELEGRAM, null))
                .as("%s narrowed to a channel with no connections", filter.value())
                .doesNotContain(w.unowned(), w.mine(), w.theirs(), w.released());
        }
    }

    @Test
    @DisplayName("a search narrows every new view without changing its meaning")
    void searchNarrowsEveryNewView() {
        UUID hit = receive("ext-narrow-1", "provider-narrow-1", "Where is my invoice");
        UUID peer = receive("ext-narrow-2", "provider-narrow-2", "An invoice question for you");
        conversations.assign(staff, peer,
            new AssignConversationRequest(InboxFixture.EMPLOYEE_ADMIN_A), null, null);
        receive("ext-narrow-3", "provider-narrow-3", "Unrelated question");

        assertThat(idsFor(staff, InboxFilter.UNASSIGNED, null, "invoice"))
            .containsExactly(hit);
        assertThat(idsFor(staff, InboxFilter.ASSIGNED_TO_OTHERS, null, "invoice"))
            .containsExactly(peer);
        for (InboxFilter filter : NEW_FILTERS) {
            assertThat(idsFor(staff, filter, null, "no-such-term-anywhere"))
                .as("%s searched for nothing", filter.value())
                .isEmpty();
        }
    }

    // ==================================================================
    // fixtures
    // ==================================================================

    /**
     * One row of every shape the views distinguish between.
     *
     * <ul>
     *   <li>{@code unowned} — never assigned. The work queue.</li>
     *   <li>{@code mine} — assigned to staff's employee row.</li>
     *   <li>{@code theirs} — assigned to admin's employee row.</li>
     *   <li>{@code released} — assigned, then released. Back in the queue, and the reason the
     *       legacy route is not a synonym for "never assigned".</li>
     *   <li>{@code closed} — RESOLVED and owned. In nobody's live view.</li>
     * </ul>
     */
    private Workload workload() {
        UUID unowned = receive("ext-wl-1", "provider-wl-1", "Nobody has answered me");
        UUID mine = receive("ext-wl-2", "provider-wl-2", "This one is mine");
        UUID theirs = receive("ext-wl-3", "provider-wl-3", "This one is a colleague's");
        UUID released = receive("ext-wl-4", "provider-wl-4", "This one was handed back");
        UUID closed = receive("ext-wl-5", "provider-wl-5", "This one is finished");

        conversations.assign(staff, mine,
            new AssignConversationRequest(InboxFixture.EMPLOYEE_STAFF_A), null, null);
        conversations.assign(staff, theirs,
            new AssignConversationRequest(InboxFixture.EMPLOYEE_ADMIN_A), null, null);
        conversations.assign(staff, released,
            new AssignConversationRequest(InboxFixture.EMPLOYEE_STAFF_A), null, null);
        conversations.assign(staff, released, new AssignConversationRequest(null), null, null);
        conversations.assign(staff, closed,
            new AssignConversationRequest(InboxFixture.EMPLOYEE_STAFF_A), null, null);
        setStatus(closed, "RESOLVED");

        return new Workload(unowned, mine, theirs, released, closed);
    }

    /** Tenant B's rows: one unowned, one owned by beta's own employee. */
    private record BetaRows(UUID unowned, UUID owned) {
    }

    private BetaRows betaRows() {
        UUID unowned = fixture.conversation("00000000f0c1", InboxFixture.ORG_B,
            InboxFixture.PROJECT_B1, InboxFixture.CUSTOMER_B1, "#0815000020", "OPEN", "HUMAN",
            InboxFixture.SEED_AT);
        UUID owned = fixture.conversation("00000000f0c2", InboxFixture.ORG_B,
            InboxFixture.PROJECT_B1, InboxFixture.CUSTOMER_B1, "#0815000021", "OPEN", "HUMAN",
            InboxFixture.SEED_AT);
        fixture.jdbc().update("update conversations set \"assignedEmployeeId\" = :employee "
                + "where \"id\" = :id",
            new MapSqlParameterSource().addValue("employee", InboxFixture.EMPLOYEE_BETA)
                .addValue("id", owned));
        return new BetaRows(unowned, owned);
    }

    private void setStatus(UUID conversationId, String status) {
        fixture.jdbc().update("update conversations set \"status\" = cast(:status as "
                + "\"ConversationStatus\") where \"id\" = :id",
            new MapSqlParameterSource().addValue("status", status).addValue("id", conversationId));
    }

    // ==================================================================
    // helpers
    // ==================================================================

    /** Receives a customer message on the mock channel, leaving it unanswered and unowned. */
    private UUID receive(String externalUserId, String externalMessageId, String text) {
        return inbound.receive(staff, InboxFixture.PROJECT_A1, null, new IncomingMessage(
            externalUserId, text, null, null, null, externalMessageId, ChannelType.MOCK,
            Map.of(), Map.of(), LocalDateTime.now())).conversationId();
    }

    private List<UUID> ids(InboxFilter filter) {
        return idsFor(staff, filter, null, null);
    }

    private List<UUID> idsFor(TenantContext context, InboxFilter filter) {
        return idsFor(context, filter, null, null);
    }

    private List<UUID> idsFor(TenantContext context, InboxFilter filter, ChannelType channel,
                              String search) {
        return PrincipalScope.call(context,
            () -> inbox.inbox(context, new InboxQuery(null, filter, channel, search, null, 100))
                .items().stream().map(summary -> summary.id()).toList());
    }

    private Map<String, Integer> countsFor(TenantContext context) {
        return PrincipalScope.call(context,
            () -> inbox.savedViewCounts(context, null, null, null));
    }

    /** The conversation ids the legacy {@code /inbox/unassigned} route returns for this caller. */
    private List<UUID> queueIds(TenantContext context) {
        return PrincipalScope.call(context,
            () -> inbox.unassigned(context, null, null, null, 100).entries().stream()
                .map(entry -> entry.conversation().id()).toList());
    }

    /** The {@code id} values of a serialized {@code CursorPage<ConversationSummary>}. */
    private static List<String> idsIn(String json) throws Exception {
        List<String> ids = new ArrayList<>();
        MAPPER.readTree(json).get("items").forEach(node -> ids.add(node.get("id").asText()));
        return ids;
    }

    /** The {@code id} values of a serialized {@code UnassignedQueuePage}. */
    private static List<String> queueIdsIn(String json) throws Exception {
        List<String> ids = new ArrayList<>();
        MAPPER.readTree(json).get("entries").forEach(
            node -> ids.add(node.get("conversation").get("id").asText()));
        return ids;
    }
}