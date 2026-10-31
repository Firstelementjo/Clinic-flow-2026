package com.firstelement.crm.clinic.treatment;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Models and transfer objects for dental treatment plans (TRT-01 to TRT-05, Slice S5).
 */
public final class TreatmentPlanModels {

    private TreatmentPlanModels() {
    }

    /**
     * A treatment plan header with calculated financial KPIs, versioning, and consent metadata.
     */
    public record TreatmentPlanHeader(
            UUID id,
            UUID organizationId,
            UUID projectId,
            UUID patientId,
            String title,
            String status,
            int version,
            UUID parentPlanId,
            String planType,
            BigDecimal totalDiscount,
            BigDecimal insuranceEstimatedCoverage,
            BigDecimal patientEstimatedPortion,
            boolean patientConsent,
            LocalDateTime consentAt,
            UUID consentByUserId,
            String consentRegistrarName,
            String consentNotes,
            LocalDateTime approvedAt,
            UUID approvedByUserId,
            String approvedByUserName,
            String cancellationReason,
            LocalDateTime cancelledAt,
            String notes,
            BigDecimal totalEstimatedCost,
            BigDecimal totalInvoicedAmount,
            BigDecimal totalCollectedAmount,
            int itemCount,
            int completedItemCount,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }

    /**
     * One procedure step in a treatment plan, ordered by sequence within a stage.
     */
    public record TreatmentPlanItemRow(
            UUID id,
            UUID organizationId,
            UUID projectId,
            UUID planId,
            int sequenceOrder,
            String stage,
            int plannedVisits,
            int completedVisits,
            UUID procedureId,
            String procedureName,
            Integer toothFdi,
            UUID doctorId,
            String doctorName,
            BigDecimal estimatedCost,
            BigDecimal discountAmount,
            BigDecimal insuranceCoverage,
            BigDecimal patientPortion,
            BigDecimal invoicedAmount,
            BigDecimal collectedAmount,
            String status,
            String notes,
            String outcomeNotes,
            LocalDateTime completedAt,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }

    /**
     * A complete treatment plan detail with all its procedure items.
     */
    public record TreatmentPlanDetail(
            TreatmentPlanHeader plan,
            List<TreatmentPlanItemRow> items) {
    }

    /**
     * Request payload to create a new treatment plan.
     */
    public record CreatePlanRequest(
            String title,
            String notes,
            String projectId,
            String planType,
            UUID parentPlanId,
            BigDecimal totalDiscount,
            BigDecimal insuranceEstimatedCoverage,
            BigDecimal patientEstimatedPortion) {
    }

    /**
     * Request payload to fork a new version of an existing approved or active plan.
     */
    public record ForkPlanVersionRequest(
            String title,
            String changeReason) {
    }

    /**
     * Request payload to approve / consent to a treatment plan.
     */
    public record ApprovePlanRequest(
            String consentNotes) {
    }

    /**
     * Request payload to cancel a treatment plan.
     */
    public record CancelPlanRequest(
            String cancellationReason) {
    }

    /**
     * Request payload to update plan status (DRAFT, PRESENTED, ACTIVE, COMPLETED, CANCELLED).
     */
    public record UpdatePlanStatusRequest(
            String status,
            String notes) {
    }

    /**
     * Request payload to record patient consent (TRT-04).
     */
    public record RecordConsentRequest(
            boolean patientConsent,
            String consentNotes) {
    }

    /**
     * Request payload to add a procedure item to a plan (TRT-01, TRT-02, TRT-03, multi-visit, stage).
     */
    public record CreatePlanItemRequest(
            Integer sequenceOrder,
            String stage,
            Integer plannedVisits,
            UUID procedureId,
            String procedureName,
            Integer toothFdi,
            UUID doctorId,
            BigDecimal estimatedCost,
            BigDecimal discountAmount,
            BigDecimal insuranceCoverage,
            BigDecimal patientPortion,
            String notes) {
    }

    /**
     * Request payload to update a treatment plan item.
     */
    public record UpdatePlanItemRequest(
            Integer sequenceOrder,
            String stage,
            Integer plannedVisits,
            Integer completedVisits,
            UUID procedureId,
            String procedureName,
            Integer toothFdi,
            UUID doctorId,
            BigDecimal estimatedCost,
            BigDecimal discountAmount,
            BigDecimal insuranceCoverage,
            BigDecimal patientPortion,
            BigDecimal invoicedAmount,
            BigDecimal collectedAmount,
            String status,
            String notes,
            String outcomeNotes) {
    }
}
