package com.firstelement.crm.api;

import java.util.UUID;

/**
 * One row of the inbox list. Field names and types are the legacy
 * {@code ConversationSummary}.
 *
 * @param lastMessagePreview the newest message body truncated to 140 characters, or
 *                           {@code null} for an empty thread
 */
public record ConversationSummary(
    UUID id,
    UUID projectId,
    String projectName,
    String ticketNumber,
    String channel,
    String status,
    String mode,
    String priority,
    String subject,
    CustomerRef customer,
    AssignedEmployee assignedEmployee,
    String lastMessagePreview,
    int messageCount,
    String lastMessageAt
) {

    /** The legacy customer projection: id, CX id, display name. */
    public record CustomerRef(UUID id, String cxId, String displayName) {
    }

    /**
     * The legacy assignment projection: the employee id plus the employee's user's name.
     * {@code null} when unassigned — which is the unassigned queue's whole population.
     */
    public record AssignedEmployee(UUID id, String fullName) {
    }

    /** The legacy preview truncation: 140 characters, then a horizontal ellipsis. */
    public static String preview(String body) {
        if (body == null) {
            return null;
        }
        return body.length() > 140 ? body.substring(0, 140) + "…" : body;
    }
}