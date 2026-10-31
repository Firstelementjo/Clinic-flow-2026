package com.firstelement.crm.api;

/**
 * A recorded resolution for a case. Legacy {@code ResolutionView}, unchanged.
 *
 * <p>{@code customerIssue}, {@code agentAction} and {@code notes} are the narrative fields and
 * can contain customer detail, which is why the audit row written alongside them carries only
 * the outcome and the satisfaction flag.
 *
 * @param documentId          the cited solution; always from the conversation's own project
 * @param recordedByName      the recording employee's {@code users."fullName"}, or {@code null}
 * @param usedKnowledgeBase   whether a knowledge document was consulted
 */
public record ResolutionView(
    String id,
    String customerIssue,
    String agentAction,
    boolean usedKnowledgeBase,
    String documentId,
    Boolean customerAccepted,
    Boolean customerSatisfied,
    boolean escalationRequested,
    boolean escalatedToSupervisor,
    String outcome,
    String notes,
    String recordedByName,
    String createdAt
) {
}