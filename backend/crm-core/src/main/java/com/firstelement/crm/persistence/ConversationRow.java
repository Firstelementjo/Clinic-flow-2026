package com.firstelement.crm.persistence;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A joined {@code conversations} row, as the inbox list, the thread view and the write paths
 * all need it.
 *
 * <p>One projection for all three is deliberate: the inbox list, the thread header and the
 * "load for write" check must agree about {@code status}, {@code mode} and
 * {@code assignedEmployeeId}, or the state machine decides on data the UI never showed.
 *
 * @param lastMessageBody newest message body, or {@code null}; truncated by the caller
 * @param updatedAt       the optimistic-concurrency token ({@code If-Match} / ETag)
 * @param modeVersion     the mode-transition counter; the guard for AI⇄HUMAN
 */
public record ConversationRow(
    UUID id,
    UUID organizationId,
    UUID projectId,
    UUID customerId,
    String ticketNumber,
    String channel,
    String status,
    String mode,
    String priority,
    String subject,
    UUID assignedEmployeeId,
    UUID lastReplierUserId,
    int modeVersion,
    int messageCount,
    LocalDateTime lastMessageAt,
    LocalDateTime firstResponseAt,
    LocalDateTime resolvedAt,
    LocalDateTime closedAt,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    String projectName,
    String customerCxId,
    String customerDisplayName,
    String assigneeFullName,
    String lastMessageBody
) {
}