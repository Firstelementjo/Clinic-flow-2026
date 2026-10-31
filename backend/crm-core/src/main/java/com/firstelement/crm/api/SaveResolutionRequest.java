package com.firstelement.crm.api;

import java.util.UUID;

/**
 * {@code PUT /api/v1/conversations/{id}/resolution} body — the legacy
 * {@code saveResolutionSchema}, unchanged.
 *
 * <p>{@code documentId} must name a document in the conversation's own project; the service
 * rejects it otherwise, because a case in project A must never cite project B's solution.
 */
public record SaveResolutionRequest(String customerIssue,
                                   String agentAction,
                                   Boolean usedKnowledgeBase,
                                   UUID documentId,
                                   Boolean customerAccepted,
                                   Boolean customerSatisfied,
                                   Boolean escalationRequested,
                                   Boolean escalatedToSupervisor,
                                   String outcome,
                                   String notes) {

    public static final int MAX_TEXT = 2000;
    public static final int MAX_NOTES = 4000;

    public boolean usedKnowledgeBaseOrDefault() {
        return usedKnowledgeBase != null && usedKnowledgeBase;
    }

    public boolean escalationRequestedOrDefault() {
        return escalationRequested != null && escalationRequested;
    }

    public boolean escalatedToSupervisorOrDefault() {
        return escalatedToSupervisor != null && escalatedToSupervisor;
    }
}