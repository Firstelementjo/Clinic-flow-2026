package com.firstelement.inbox.api;

import java.util.List;
import java.util.UUID;

/**
 * The customer-facing transcript of a conversation.
 *
 * <h2>Why this exists at all</h2>
 *
 * <p>FR-3.4 requires "customer-facing history and internal/admin history must remain separate".
 * The legacy API had only the internal view, so the separation was implicit in the fact that
 * notes and timeline entries simply were not on it. A phase that adds a customer-facing surface
 * has to make the separation explicit rather than leave it to whoever builds the next screen.
 *
 * <h2>What is deliberately absent</h2>
 *
 * <ul>
 *   <li><b>No timeline events.</b> Assignments, takeovers, escalations, score updates and status
 *       changes are internal.</li>
 *   <li><b>No author names.</b> The customer sees "Support", not the employee's full name.</li>
 *   <li><b>No delivery failures.</b> A provider failure is operational state, not something the
 *       customer was told.</li>
 *   <li><b>No notes.</b> Notes are not messages; they cannot leak into a transcript by
 *       construction.</li>
 *   <li><b>No internal {@code SYSTEM} messages.</b> They are operational notices.</li>
 * </ul>
 *
 * <p>The absences are enforced in the {@code WHERE}/{@code SELECT} of the transcript query, not
 * by a response filter, so a future field added to {@code messages} cannot leak by default.
 */
public record CustomerTranscript(UUID conversationId,
                                 String ticketNumber,
                                 String channel,
                                 String status,
                                 String subject,
                                 List<Entry> messages) {

    /** One customer-visible message. */
    public record Entry(UUID id,
                        String direction,
                        String authorType,
                        String authorLabel,
                        String body,
                        String sentAt) {

        /** The label shown instead of an employee name. */
        public static String labelFor(String authorType) {
            return "CUSTOMER".equals(authorType) ? "You" : "Support";
        }
    }
}