package com.firstelement.crm.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A conversation thread as the employee sees it: the summary, the message transcript, and the
 * internal timeline.
 *
 * <h2>This is the internal view</h2>
 *
 * <p>{@link #events()} is the internal/admin history: assignments, takeovers, escalations,
 * status and priority changes, score updates. Nothing in it is shown to a customer. The
 * customer-facing projection is
 * {@code com.firstelement.inbox.api.CustomerTranscript}, which carries no events, no author
 * names and no delivery failures.
 *
 * <p>The legacy response capped both collections at 300 rows and ordered them ascending; both
 * are preserved so an existing client keeps receiving the same thread.
 *
 * @param modeVersion the mode-transition counter the caller must quote on
 *                    {@code PATCH /api/v1/conversations/{id}/mode}. It is the guard for the
 *                    AI?HUMAN transition, so it has to be **readable**: a client that cannot
 *                    see it cannot make the transition at all, which is what a dead
 *                    "take over" button looks like from the operator's side.
 */
public record ConversationDetail(
    UUID id,
    UUID projectId,
    String projectName,
    String ticketNumber,
    String channel,
    String status,
    String mode,
    int modeVersion,
    String priority,
    String subject,
    ConversationSummary.CustomerRef customer,
    ConversationSummary.AssignedEmployee assignedEmployee,
    String lastMessagePreview,
    int messageCount,
    String lastMessageAt,
    List<MessageView> messages,
    List<ConversationEventView> events
) {

    /** One message. {@code authorName} is the employee's {@code users."fullName"}. */
    public record MessageView(UUID id,
                              String direction,
                              String authorType,
                              String authorName,
                              String body,
                              String deliveryStatus,
                              String failureReason,
                              String sentAt) {
    }

    /**
     * One internal timeline entry.
     *
     * <p>{@code actorName} is always {@code null}: the legacy projection hard-coded it,
     * because resolving it would leak employee names onto a list the customer-facing surface
     * does not use.
     */
    public record ConversationEventView(UUID id,
                                        String type,
                                        String actorType,
                                        String actorName,
                                        Map<String, Object> payload,
                                        String occurredAt) {
    }
}