package com.firstelement.inbox.api;

import com.firstelement.crm.api.ConversationSummary;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * The unassigned queue: every active conversation with no owner, each with the reason it is
 * there, plus the counts an operator needs before they start working.
 *
 * <h2>What "unassigned" means here</h2>
 *
 * <p>{@code conversations."assignedEmployeeId" is null} and the conversation is in an active
 * state (OPEN or PENDING). Nothing else. This is the queue's <em>membership</em> rule and it is
 * deliberately identical to the one routing will use, because two definitions of "unassigned"
 * would produce two different numbers in the inbox and the dashboard.
 *
 * <h2>Explainability, without a router</h2>
 *
 * <p>Routing rules are Phase 6. This surface does <em>not</em> decide who should pick the work
 * up; it explains why the work is sitting there, from facts already stored on the thread:
 *
 * <ul>
 *   <li>{@code NEVER_ASSIGNED} — no owner has ever been set.</li>
 *   <li>{@code RELEASED} — the timeline records an {@code UNASSIGNED} entry, i.e. a person or
 *       the routing engine handed it back deliberately.</li>
 *   <li>{@code REOPENED} — the thread was resolved and a reply or a customer message moved it
 *       back to an active state, so the resolver who owned it no longer does.</li>
 * </ul>
 *
 * <p>A reason is always present, and it is always derived from stored data. No eligibility,
 * capacity or availability judgement is made here, because making one would be Phase 6's router
 * arriving early and quietly.
 *
 * @param entries      the queue rows
 * @param nextCursor   the cursor for the following page, or {@code null}
 * @param totals       the queue's shape, computed over the whole scope (not the page)
 * @param generatedAt  when these counts were computed
 */
public record UnassignedQueuePage(List<Entry> entries,
                                 String nextCursor,
                                 Totals totals,
                                 String generatedAt) {

    /** One queue row: the conversation summary plus why it is waiting. */
    public record Entry(ConversationSummary conversation,
                        Reason reason,
                        String reasonDetail,
                        String waitingSince,
                        long waitingSeconds,
                        Map<String, Object> signals) {
    }

    /**
     * @param total            every unassigned active conversation in scope
     * @param byStatus         counts per status
     * @param byChannel        counts per channel
     * @param oldestWaitingSeconds how long the oldest entry has been waiting
     */
    public record Totals(int total,
                         Map<String, Integer> byStatus,
                         Map<String, Integer> byChannel,
                         long oldestWaitingSeconds) {
    }

    /** Why a conversation is in the queue. */
    public enum Reason {
        /** No assignee has ever been set on this conversation. */
        NEVER_ASSIGNED,
        /** An {@code UNASSIGNED} entry exists on the timeline: it was handed back on purpose. */
        RELEASED,
        /**
         * The thread was resolved and has become active again, so whoever closed it is no longer
         * its owner.
         */
        REOPENED
    }

    /** An unassigned conversation with its diagnosis, before the page projection. */
    public record QueueRow(ConversationSummary conversation,
                           Reason reason,
                           String reasonDetail,
                           LocalDateTime waitingSince,
                           Map<String, Object> signals) {
    }
}