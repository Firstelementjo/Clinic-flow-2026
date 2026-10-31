package com.firstelement.inbox.api;

import java.util.UUID;

/**
 * {@code PATCH /api/v1/conversations/{id}/assignment} body — the legacy
 * {@code assignConversationSchema}.
 *
 * <p>A JSON {@code null} is meaningful: it unassigns the conversation and returns it to the
 * queue, which is different from an absent field (no change).
 */
public record AssignConversationRequest(UUID employeeId) {
}