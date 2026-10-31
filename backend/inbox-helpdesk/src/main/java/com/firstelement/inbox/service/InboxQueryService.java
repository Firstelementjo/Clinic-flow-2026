package com.firstelement.inbox.service;

import com.firstelement.crm.api.ConversationSummary;
import com.firstelement.crm.api.Timestamps;
import com.firstelement.crm.domain.ChannelType;
import com.firstelement.crm.domain.InboxFilter;
import com.firstelement.crm.error.ValidationFailedException;
import com.firstelement.crm.persistence.ConversationRepository;
import com.firstelement.crm.persistence.ConversationRow;
import com.firstelement.crm.query.CrmQueryScope;
import com.firstelement.crm.query.CursorPage;
import com.firstelement.crm.query.InboxFilterDefinition;
import com.firstelement.inbox.api.InboxQuery;
import com.firstelement.inbox.api.UnassignedQueuePage;
import com.firstelement.inbox.persistence.UnassignedQueueRepository;
import com.firstelement.security.TenantContext;
import com.firstelement.security.annotation.RequirePermission;
import com.firstelement.security.permission.Permission;
import com.firstelement.security.query.TenantQueryGuard;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The inbox query model: the legacy list, and the explainable unassigned queue.
 *
 * <h2>The list</h2>
 *
 * <p>Scope first (a requested project, else the caller's accessible projects), then the saved
 * view, then channel and search, then the keyset cursor, then {@code order by lastMessageAt desc,
 * id desc} with a {@code limit + 1} over-fetch. Every one of those choices is the legacy one; see
 * {@code apps/api/src/conversations/inbox-filter.ts} and
 * {@link ConversationRepository#inbox}.
 *
 * <h2>The queue is exposed, not routed</h2>
 *
 * <p>Phase 6 owns routing. This service answers "what is waiting and why", never "who should
 * take it": each entry carries a {@link UnassignedQueuePage.Reason} derived only from facts
 * already on the thread. No eligibility, availability or capacity judgement is made here,
 * because making one would be the router arriving early and unlabelled.
 */
@Service
public class InboxQueryService {

    private final ConversationRepository conversations;
    private final UnassignedQueueRepository queue;
    private final TenantQueryGuard guard;

    public InboxQueryService(ConversationRepository conversations,
                             UnassignedQueueRepository queue,
                             TenantQueryGuard guard) {
        this.conversations = conversations;
        this.queue = queue;
        this.guard = guard;
    }

    // ------------------------------------------------------------------
    // list
    // ------------------------------------------------------------------

    @RequirePermission(Permission.CONVERSATION_READ)
    public CursorPage<ConversationSummary> inbox(TenantContext context, InboxQuery query) {
        CrmQueryScope scope = CrmQueryScope.forProject(guard, context, query.projectId());

        String predicate = InboxFilterDefinition.predicate(query.filter(), context.userId(),
            query.channel(), query.search(), InboxFilterDefinition.CUSTOMER_ALIAS);
        Map<String, Object> parameters = new LinkedHashMap<>(
            InboxFilterDefinition.parameters(query.filter(), context.userId(), query.channel(),
                query.search()));

        ConversationRepository.CursorBoundary boundary = null;
        if (query.cursor() != null) {
            boundary = conversations.cursorBoundary(scope, query.cursor())
                .orElseThrow(() -> ValidationFailedException.code("INVALID_CURSOR",
                    "cursor does not identify a conversation in scope", Map.of()));
        }

        List<ConversationRow> rows = conversations.inbox(scope, predicate, parameters, boundary,
            query.limit() + 1);

        CursorPage<ConversationRow> page = CursorPage.of(rows, query.limit(),
            row -> row.id().toString());
        List<ConversationSummary> items = page.items().stream()
            .map(ConversationService::toSummary)
            .toList();
        return new CursorPage<>(items, page.nextCursor());
    }

    // ------------------------------------------------------------------
    // unassigned queue
    // ------------------------------------------------------------------

    /**
     * The explainable unassigned queue.
     *
     * <p>Membership is {@link UnassignedQueueRepository#UNASSIGNED_ACTIVE}. Each row is then
     * diagnosed from its own timeline:
     *
     * <ul>
     *   <li>an {@code UNASSIGNED} entry newer than the last {@code ASSIGNED} entry means somebody
     *       handed it back — {@link UnassignedQueuePage.Reason#RELEASED};</li>
     *   <li>a {@code STATUS_CHANGED → RESOLVED} entry newer than the last {@code ASSIGNED} entry,
     *       with the thread now active again, means it came back from resolution —
     *       {@link UnassignedQueuePage.Reason#REOPENED};</li>
     *   <li>otherwise nobody has ever owned it —
     *       {@link UnassignedQueuePage.Reason#NEVER_ASSIGNED}.</li>
     * </ul>
     *
     * <p>Newest facts win, so "assigned then released" reads as released and "assigned, resolved,
     * reopened" reads as reopened. The signals the diagnosis used are returned alongside the
     * reason, so an operator can check the reasoning rather than trust it.
     */
    @RequirePermission(Permission.CONVERSATION_READ)
    public UnassignedQueuePage unassigned(TenantContext context,
                                          UUID projectId,
                                          ChannelType channel,
                                          UUID cursor,
                                          int limit) {
        CrmQueryScope scope = CrmQueryScope.forProject(guard, context, projectId);

        Map<String, Object> filterParameters = new LinkedHashMap<>();
        List<String> conditions = new ArrayList<>();
        if (channel != null) {
            conditions.add("c.\"channel\" = cast(:queueChannel as \"ChannelType\")");
            filterParameters.put("queueChannel", channel.name());
        }

        ConversationRepository.CursorBoundary boundary = null;
        if (cursor != null) {
            boundary = conversations.cursorBoundary(scope, cursor)
                .orElseThrow(() -> ValidationFailedException.code("INVALID_CURSOR",
                    "cursor does not identify a conversation in scope", Map.of()));
            conditions.add("(c.\"lastMessageAt\" < :cursorLastMessageAt"
                + " or (c.\"lastMessageAt\" = :cursorLastMessageAt and c.\"id\" < :cursorId))");
            filterParameters.put("cursorLastMessageAt", boundary.lastMessageAt());
            filterParameters.put("cursorId", boundary.id());
        }

        String predicate = conditions.isEmpty() ? null : String.join(" and ", conditions);

        List<UnassignedQueueRepository.QueueFact> facts =
            queue.page(scope, predicate, Map.of(), filterParameters, limit + 1);

        // Only the facts are fetched first: the summary projection is resolved per row so a page
        // of 30 does not run 30 aggregate sub-selects for rows that will be discarded.
        List<UnassignedQueueRepository.QueueFact> pageFacts =
            facts.size() > limit ? facts.subList(0, limit) : facts;
        boolean hasMore = facts.size() > limit;

        LocalDateTime now = LocalDateTime.now();
        List<UnassignedQueuePage.Entry> entries = new ArrayList<>();
        for (UnassignedQueueRepository.QueueFact fact : pageFacts) {
            ConversationRow row = conversations.findInScope(scope, fact.id()).orElse(null);
            if (row == null) {
                // The row vanished between the two statements (or left the caller's scope);
                // skipping it keeps the page consistent instead of showing a phantom entry.
                continue;
            }
            Diagnosis diagnosis = diagnose(fact);
            entries.add(new UnassignedQueuePage.Entry(
                ConversationService.toSummary(row),
                diagnosis.reason(),
                diagnosis.detail(),
                Timestamps.toIso(fact.lastMessageAt()),
                secondsWaiting(fact.lastMessageAt(), now),
                diagnosis.signals()));
        }

        String nextCursor = hasMore && !pageFacts.isEmpty()
            ? pageFacts.get(pageFacts.size() - 1).id().toString()
            : null;

        UnassignedQueueRepository.Totals totals = queue.totals(scope, predicate, Map.of());
        return new UnassignedQueuePage(
            List.copyOf(entries),
            nextCursor,
            new UnassignedQueuePage.Totals(
                totals.total(),
                totals.byStatus(),
                totals.byChannel(),
                totals.oldestWaitingAt() == null ? 0L
                    : secondsWaiting(totals.oldestWaitingAt(), now)),
            Timestamps.toIso(now));
    }

    // ------------------------------------------------------------------
    // counts
    // ------------------------------------------------------------------

    /**
     * The saved-view counts an operator's tabs show.
     *
     * <p>Counted over the whole scope rather than a page, so a tab badge never depends on which
     * page happens to be loaded. The counts run through the same filter definition as the list,
     * which is the whole reason that definition is a pure function in one place: "assigned to me"
     * must mean the same thing in the list, the badge and the analytics.
     *
     * <p><b>One key per {@link InboxFilter}, derived from the enum rather than restated.</b> The
     * loop used to name two filters explicitly, and the moment the list could filter three more
     * ways a client rendering a segmented control would have shown a permanent {@code 0} for each
     * new tab -- a number that is not wrong, only missing, which is exactly how a broken product
     * looks rather than how a broken endpoint announces itself. Iterating {@code values()} makes
     * the omission impossible to reintroduce: a filter cannot reach the list without also reaching
     * the counts.
     */
    @RequirePermission(Permission.CONVERSATION_READ)
    public Map<String, Integer> savedViewCounts(TenantContext context, UUID projectId,
                                                ChannelType channel, String search) {
        CrmQueryScope scope = CrmQueryScope.forProject(guard, context, projectId);
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (InboxFilter filter : InboxFilter.values()) {
            String predicate = InboxFilterDefinition.predicate(filter, context.userId(), channel,
                search, InboxFilterDefinition.CUSTOMER_ALIAS);
            Map<String, Object> parameters = InboxFilterDefinition.parameters(filter,
                context.userId(), channel, search);
            counts.put(filter.value(),
                conversations.countWithFilter(scope, predicate, parameters, true));
        }
        return counts;
    }

    // ------------------------------------------------------------------
    // diagnosis
    // ------------------------------------------------------------------

    /** Why one conversation is waiting, and the facts the diagnosis used. */
    record Diagnosis(UnassignedQueuePage.Reason reason, String detail, Map<String, Object> signals) {
    }

    /**
     * Diagnoses one queue row.
     *
     * <p>Pure: given the three "last event" timestamps and the current status it produces the
     * same reason every time, which is what makes the diagnosis testable without a database.
     *
     * <p>The tie-break is deliberate and load-bearing. Closing a thread releases its assignee and
     * records the resolution in the <em>same</em> write, so both facts are true at the same
     * instant. The resolution wins, because the queue's question is "why is this active thread
     * unowned?" and a thread that was resolved and is now active again is the case an operator
     * most needs explained. A plain unassign, with no resolution, is a release.
     */
    static Diagnosis diagnose(UnassignedQueueRepository.QueueFact fact) {
        Map<String, Object> signals = new LinkedHashMap<>();
        signals.put("lastAssignedAt", Timestamps.toIso(fact.lastAssignedAt()));
        signals.put("lastUnassignedAt", Timestamps.toIso(fact.lastUnassignedAt()));
        signals.put("lastResolvedAt", Timestamps.toIso(fact.lastResolvedAt()));
        signals.put("status", fact.status());

        LocalDateTime lastUnassigned = fact.lastUnassignedAt();
        LocalDateTime lastResolved = fact.lastResolvedAt();

        if (lastResolved != null
            && (lastUnassigned == null || !lastUnassigned.isAfter(lastResolved))) {
            return new Diagnosis(UnassignedQueuePage.Reason.REOPENED,
                "Resolved earlier and active again, so it has no owner.", signals);
        }
        if (lastUnassigned != null) {
            return new Diagnosis(UnassignedQueuePage.Reason.RELEASED,
                "Released back to the queue by an assignment change.", signals);
        }
        return new Diagnosis(UnassignedQueuePage.Reason.NEVER_ASSIGNED,
            "No owner has ever been assigned.", signals);
    }

    private static long secondsWaiting(LocalDateTime since, LocalDateTime now) {
        if (since == null) {
            return 0L;
        }
        return Math.max(0L, Duration.between(Timestamps.toInstant(since), Timestamps.toInstant(now))
            .getSeconds());
    }
}