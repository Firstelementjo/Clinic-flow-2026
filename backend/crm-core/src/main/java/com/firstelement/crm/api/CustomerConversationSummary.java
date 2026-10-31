package com.firstelement.crm.api;

import java.util.UUID;

/**
 * A conversation as it appears on the customer 360 view.
 *
 * <p>{@code assignedAgent} is the employee's {@code users."fullName"}, matching the legacy
 * mapping {@code conversation.assignedEmployee?.user?.fullName ?? null} — the UI shows a
 * person's name here, not an employee id.
 */
public record CustomerConversationSummary(
    UUID id,
    String ticketNumber,
    String channel,
    String status,
    String mode,
    String priority,
    String subject,
    int messageCount,
    String lastMessageAt,
    String createdAt,
    String assignedAgent
) {
}