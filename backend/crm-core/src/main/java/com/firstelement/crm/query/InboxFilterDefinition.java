package com.firstelement.crm.query;

import com.firstelement.crm.domain.ChannelType;
import com.firstelement.crm.domain.InboxFilter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The saved-view definitions an operator's whole day depends on, ported one-for-one from
 * {@code apps/api/src/conversations/inbox-filter.ts}.
 *
 * <h2>Why this is a pure function, separate from the service</h2>
 *
 * <p>"unassigned" and "assigned to me" must mean the same thing in the list, the counts
 * and the analytics. The legacy file states the same reason for keeping it pure: a pure
 * function is testable without a database, and every test in this module for
 * {@code InboxFilterDefinition} runs without PostgreSQL.
 *
 * <h2>What each view means</h2>
 *
 * <ul>
 *   <li>{@code UNASSIGNED} — OPEN/PENDING with <b>no owner and no involvement test at all</b>. The
 *       work queue. See {@link #UNASSIGNED_ACTIVE}.</li>
 *   <li>{@code ALL_OPEN} — OPEN/PENDING, anyone's. A supervisor's whole load.</li>
 *   <li>{@code ASSIGNED_TO_OTHERS} — OPEN/PENDING assigned to an employee who is not the caller.</li>
 *   <li>{@code ASSIGNED_TO_ME} — status in (OPEN, PENDING) <b>and</b> (the conversation is
 *       assigned to an employee whose {@code users.id} is the caller, <b>or</b> the caller
 *       was the last human replier). Being the last replier is what keeps a conversation an
 *       employee answered visible to them after it was released back to the queue.</li>
 *   <li>{@code HISTORY} — the same involvement test, restricted to RESOLVED/CLOSED.</li>
 *   <li>A channel filter narrows the row, and a search narrows it again. When a view was
 *       selected the search terms are ANDed with the view (legacy pushes the search OR into
 *       the existing {@code AND} array); with no view the search OR stands alone.</li>
 * </ul>
 *
 * <p>Assignment points at an {@code Employee}, not a {@code User}, so the involvement tests need
 * the {@code employees} join. This class emits those predicates' fragments and the caller supplies
 * the {@link #EMPLOYEE_JOIN}. Both {@code ConversationRepository#inbox} and
 * {@code ConversationRepository#countWithFilter} always add that join, so {@code e."userId"} is
 * available in every fragment below without the caller asking for it.
 *
 * <p>Never returns a tenant column; the caller always ANDs {@link CrmQueryScope} alongside.
 */
public final class InboxFilterDefinition {

    /** Conversation alias used by every fragment below. */
    public static final String CONVERSATION_ALIAS = "c";
    /** Customer alias (needed by the {@code cxId} search branch). */
    public static final String CUSTOMER_ALIAS = "cu";
    /** Employee alias (needed by the involvement tests). */
    public static final String EMPLOYEE_ALIAS = "e";

    /** The {@code employees} join every filter fragment assumes. */
    public static final String EMPLOYEE_JOIN =
        "left join employees e on e.\"id\" = c.\"assignedEmployeeId\"";

    /**
     * THE membership rule of the {@code unassigned} view, in one place, and the reason
     * {@code /inbox/unassigned} and {@code ?filter=unassigned} cannot disagree.
     *
     * <p>Two routes answering "what is unassigned" is two places to fix a bug and an invitation
     * for a client to receive two different answers to the same question. The legacy route predates
     * this filter and its response is richer — a per-row diagnosis and queue totals, which the list
     * endpoint has no column for — so it cannot become an alias for the list without breaking
     * Phase 11's journeys. What it <em>can</em> do, and does, is take its membership rule from
     * here: {@code UnassignedQueueRepository.UNASSIGNED_ACTIVE} is now an alias for this constant.
     * One definition, two routes, no drift.
     *
     * <p>OPEN or PENDING with no assignee. Deliberately <b>no involvement test</b>: "unassigned" is
     * a statement about ownership, and adding "and the caller was not the last replier" would hide
     * a conversation from the work queue for the very person who last touched it.
     */
    public static final String UNASSIGNED_ACTIVE =
        "c.\"assignedEmployeeId\" is null"
            + " and c.\"status\" in (cast('OPEN' as \"ConversationStatus\"),"
            + " cast('PENDING' as \"ConversationStatus\"))";

    /** OPEN or PENDING, whoever owns them. */
    private static final String OPEN_STATUSES =
        "c.\"status\" in (cast('OPEN' as \"ConversationStatus\"),"
            + " cast('PENDING' as \"ConversationStatus\"))";

    /** RESOLVED or CLOSED. */
    private static final String CLOSED_STATUSES =
        "c.\"status\" in (cast('RESOLVED' as \"ConversationStatus\"),"
            + " cast('CLOSED' as \"ConversationStatus\"))";

    private InboxFilterDefinition() {
    }

    /**
     * Builds the predicate for one saved view.
     *
     * @param filter   the saved view; {@code null} is treated as "no view selected"
     * @param userId   the caller's user id, used by every view that tests involvement
     * @param channel  optional channel narrowing
     * @param search   optional free text; trimmed, and {@code null}/blank means no search
     * @param customerAlias the customers alias available to the statement, or {@code null}
     *                      to omit the {@code cxId} search branch
     * @return a predicate fragment (never {@code null}); empty when nothing filters
     */
    public static String predicate(InboxFilter filter,
                                   UUID userId,
                                   ChannelType channel,
                                   String search,
                                   String customerAlias) {
        StringBuilder sql = new StringBuilder();

        String statusFragment = statusFragment(filter);
        boolean hasStatusFragment = statusFragment != null;
        if (hasStatusFragment) {
            sql.append('(').append(statusFragment).append(')');
        }

        if (channel != null) {
            append(sql, "c.\"channel\" = cast(:inboxChannel as \"ChannelType\")");
        }

        String searchFragment = searchFragment(search, customerAlias);
        if (searchFragment != null) {
            append(sql, "(" + searchFragment + ")");
        }

        return sql.toString();
    }

    /**
     * Parameter values the fragment above expects.
     *
     * <p>{@code inboxUserId} is bound for exactly the views whose predicate mentions it. Binding it
     * unconditionally would be harmless but misleading — a reader would have to work out which
     * views actually use it — and it would make a missing parameter impossible to spot, since an
     * unused extra parameter never fails loudly.
     */
    public static Map<String, Object> parameters(InboxFilter filter,
                                                  UUID userId,
                                                  ChannelType channel,
                                                  String search) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        if (usesInvolvementTest(filter)) {
            parameters.put("inboxUserId", userId);
        }
        if (channel != null) {
            parameters.put("inboxChannel", channel.name());
        }
        String trimmed = search == null ? null : search.trim();
        if (trimmed != null && !trimmed.isEmpty()) {
            parameters.put("inboxSearch", LikePattern.contains(trimmed));
        }
        return parameters;
    }

    /**
     * Whether this view's predicate references {@code :inboxUserId}.
     *
     * <p>{@code UNASSIGNED} and {@code ALL_OPEN} deliberately do not: the work queue is
     * ownership, not involvement, and a supervisor's load is everyone's. Binding a user id into a
     * predicate that ignores it would invite exactly the wrong reading later.
     */
    public static boolean usesInvolvementTest(InboxFilter filter) {
        return filter == InboxFilter.ASSIGNED_TO_ME
            || filter == InboxFilter.HISTORY
            || filter == InboxFilter.ASSIGNED_TO_OTHERS;
    }

    /**
     * {@code null} when the view adds no status restriction — the default inbox, which is
     * every conversation in scope, ordered by recency.
     */
    private static String statusFragment(InboxFilter filter) {
        if (filter == InboxFilter.UNASSIGNED) {
            return UNASSIGNED_ACTIVE;
        }
        if (filter == InboxFilter.ALL_OPEN) {
            return OPEN_STATUSES;
        }
        if (filter == InboxFilter.ASSIGNED_TO_OTHERS) {
            return OPEN_STATUSES
                + " and " + EMPLOYEE_ALIAS + ".\"userId\" is not null"
                + " and " + EMPLOYEE_ALIAS + ".\"userId\" <> :inboxUserId";
        }
        if (filter == InboxFilter.ASSIGNED_TO_ME) {
            return OPEN_STATUSES + " and (" + involvedIn() + ")";
        }
        if (filter == InboxFilter.HISTORY) {
            return CLOSED_STATUSES + " and (" + involvedIn() + ")";
        }
        return null;
    }

    /**
     * The involvement test, identical in {@code ASSIGNED_TO_ME} and {@code HISTORY}. The user id is
     * always the bound parameter {@code :inboxUserId}, never a literal.
     */
    private static String involvedIn() {
        return EMPLOYEE_ALIAS + ".\"userId\" = :inboxUserId"
            + " or c.\"lastReplierUserId\" = :inboxUserId";
    }

    /**
     * The legacy search branches, verbatim: ticket number, subject, the customer's CX id,
     * and any message body in the thread. All case-insensitive.
     */
    private static String searchFragment(String search, String customerAlias) {
        String trimmed = search == null ? null : search.trim();
        if (trimmed == null || trimmed.isEmpty()) {
            return null;
        }
        StringBuilder sql = new StringBuilder();
        sql.append("c.\"ticketNumber\" ilike :inboxSearch escape '\\'");
        sql.append(" or c.\"subject\" ilike :inboxSearch escape '\\'");
        if (customerAlias != null) {
            sql.append(" or ").append(customerAlias).append(".\"cxId\" ilike :inboxSearch escape '\\'");
        }
        // The message-body branch stays tenant-scoped by reusing the conversation's own
        // scope columns, so a search can never reach another tenant's message text.
        sql.append(" or exists (select 1 from messages m where m.\"conversationId\" = c.\"id\"")
            .append(" and m.\"organizationId\" = c.\"organizationId\"")
            .append(" and m.\"projectId\" = c.\"projectId\"")
            .append(" and m.\"body\" ilike :inboxSearch escape '\\')");
        return sql.toString();
    }

    private static void append(StringBuilder sql, String fragment) {
        if (!sql.isEmpty()) {
            sql.append(" and ");
        }
        sql.append(fragment);
    }
}