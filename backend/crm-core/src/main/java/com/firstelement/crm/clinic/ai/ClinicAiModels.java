package com.firstelement.crm.clinic.ai;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Domain representations for Dental Clinic Governed AI Assistant (Slice S9).
 *
 * <p>Operational activity (appointment booking, patient triage, incoming message processing)
 * is recorded in {@code ai_runs}. Clinical proposals strictly remain in DRAFT status until confirmed
 * by authorized humans, complying with CLN-09 and ADR-0007 Part 2.
 */
public final class ClinicAiModels {

    private ClinicAiModels() {}

    public record AiRunRow(
            UUID id,
            UUID projectId,
            UUID conversationId,
            UUID agentId,
            String status,
            String decision,
            Integer confidence,
            String decisionReason,
            String provider,
            String model,
            String errorMessage,
            Integer latencyMs,
            Instant startedAt,
            Instant finishedAt
    ) {}

    public record ClinicAiOverview(
            int totalRuns,
            int successfulRuns,
            int failedRuns,
            double avgLatencyMs,
            boolean clinicalSuggestionsDisabled,
            List<AiRunRow> recentRuns
    ) {}

    public record ClinicAiProposalRow(
            UUID id,
            UUID organizationId,
            UUID projectId,
            UUID patientId,
            String patientName,
            String proposalType,
            String status,
            String model,
            String provider,
            String promptTemplate,
            String inputPayloadRedacted,
            String outputContent,
            String transparentFactors,
            Integer confidencePercent,
            boolean phiRedacted,
            UUID approvedByUserId,
            String approverName,
            Instant approvedAt,
            String rejectionReason,
            Instant createdAt
    ) {}

    public record ClinicAiGovernanceRow(
            UUID id,
            UUID organizationId,
            UUID projectId,
            boolean killSwitchActive,
            String killSwitchReason,
            UUID killSwitchEngagedByUserId,
            String killSwitchEngagedByName,
            Instant killSwitchEngagedAt,
            boolean clinicalSuggestionsDisabled,
            Instant updatedAt
    ) {}

    public record ToggleKillSwitchRequest(
            Boolean killSwitchActive,
            Boolean engaged,
            String reason
    ) {
        public ToggleKillSwitchRequest(boolean killSwitchActive, String reason) {
            this(killSwitchActive, killSwitchActive, reason);
        }

        public boolean isActive() {
            if (killSwitchActive != null) {
                return killSwitchActive;
            }
            return engaged != null && engaged;
        }
    }

    public record ReviewProposalRequest(
            String reviewDecision,
            String rejectionReason
    ) {}

    public record VisitSummaryDraftRequest(
            UUID patientId,
            UUID encounterId
    ) {}

    public record RecallPrioritizationRequest(
            Integer overdueDaysThreshold,
            Integer limit
    ) {}

    public record MissingFieldsAuditRequest(
            UUID patientId
    ) {}

    public record PaymentMatchProposalRequest(
            UUID paymentId
    ) {}

    public record PatientMessageDraftRequest(
            UUID patientId,
            String messageType,
            String language
    ) {}

    // -------------------------------------------------------------------------
    // AI Receptionist Agent Console & Tool Execution Models
    // -------------------------------------------------------------------------

    public record AgentConfig(
            String agentName,
            String personaTone,
            String welcomeMessage,
            String customInstructions,
            boolean medicalGuardrailEnabled,
            String selectedModel,
            double temperature,
            Map<String, ChannelConfig> channels
    ) {}

    public record ChannelConfig(
            String channelKey,
            String label,
            boolean enabled,
            String status,
            String identifier
    ) {}

    public record AgentMetrics(
            int customersAnswered,
            int bookingsCreated,
            int appointmentsRescheduled,
            int escalatedToHuman
    ) {}

    public record TestChatRequest(
            String message
    ) {}

    public record TestChatResponse(
            String reply,
            String toolUsed,
            boolean medicalGuardrailTriggered
    ) {}
}
