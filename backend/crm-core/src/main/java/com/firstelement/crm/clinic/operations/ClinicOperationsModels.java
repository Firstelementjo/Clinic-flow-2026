package com.firstelement.crm.clinic.operations;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Domain models for the clinic operations screens: the clear-aligner lab, the
 * patient feedback register and doctor commission payouts.
 *
 * <p>The prosthetic lab pipeline already lives in
 * {@link com.firstelement.crm.clinic.encounter.EncounterModels} because it was
 * first reached from an encounter. These three were never part of a clinical
 * note, so they live together here rather than being spread across the
 * encounter package.
 *
 * <p>Every value is the stored value. Percentages are percentages (10.00 means
 * ten percent), money keeps its currency, and a list that was never written is
 * empty rather than null.
 */
public final class ClinicOperationsModels {

    private ClinicOperationsModels() {
    }

    // ------------------------------------------------------------------
    // Clear Aligner Lab
    // ------------------------------------------------------------------

    /** One aligner case as the pipeline list renders it. */
    public record AlignerCaseRow(
            UUID id,
            UUID organizationId,
            UUID projectId,
            UUID patientId,
            String patientName,
            UUID doctorId,
            String doctorName,
            String caseType,
            String impressionType,
            String shade,
            String status,
            int upperSteps,
            int lowerSteps,
            String teethSummary,
            String iprSummary,
            int attachmentsCount,
            String rxNotes,
            List<String> files,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }

    /** The Create / Edit drawer's payload. */
    public record CreateAlignerCaseRequest(
            UUID patientId,
            UUID doctorId,
            String caseType,
            String impressionType,
            String shade,
            Integer upperSteps,
            Integer lowerSteps,
            String teethSummary,
            String iprSummary,
            Integer attachmentsCount,
            String rxNotes,
            List<String> files,
            String projectId) {
    }

    public record UpdateAlignerCaseStatusRequest(String status) {
    }

    // ------------------------------------------------------------------
    // Patient Feedback
    // ------------------------------------------------------------------

    /** One feedback response, named against the patient, doctor and service. */
    public record FeedbackRow(
            UUID id,
            UUID organizationId,
            UUID projectId,
            UUID patientId,
            String patientName,
            String patientEmail,
            String patientPhone,
            Integer patientAge,
            UUID doctorId,
            String doctorName,
            String serviceName,
            String treatmentsReceived,
            String clinicLocation,
            String referralSource,
            int ratingExplanation,
            int ratingHygiene,
            int ratingCourtesy,
            int ratingComfort,
            int ratingRecommend,
            String message,
            LocalDateTime submittedAt,
            LocalDateTime createdAt) {
    }

    public record CreateFeedbackRequest(
            UUID patientId,
            UUID doctorId,
            String patientName,
            String patientEmail,
            String patientPhone,
            Integer patientAge,
            String doctorName,
            String serviceName,
            String treatmentsReceived,
            String clinicLocation,
            String referralSource,
            Integer ratingExplanation,
            Integer ratingHygiene,
            Integer ratingCourtesy,
            Integer ratingComfort,
            Integer ratingRecommend,
            String message,
            String projectId) {
    }

    // ------------------------------------------------------------------
    // Doctor Commission & Payouts
    // ------------------------------------------------------------------

    /**
     * One doctor's commission position for a window.
     *
     * <p>{@code grossRevenue} counts completed appointments only: an appointment
     * the clinic never delivered is not revenue, and paying commission on it
     * would pay a doctor for work that did not happen.
     */
    public record DoctorCommissionRow(
            UUID doctorId,
            String doctorName,
            String specialty,
            BigDecimal commissionRate,
            int appointmentCount,
            BigDecimal grossRevenue,
            BigDecimal commissionAmount,
            BigDecimal paidAmount,
            BigDecimal balanceDue,
            String currency) {
    }

    public record DoctorPayoutRow(
            UUID id,
            UUID organizationId,
            UUID projectId,
            UUID doctorId,
            String doctorName,
            LocalDate periodStart,
            LocalDate periodEnd,
            BigDecimal grossAmount,
            BigDecimal commissionRate,
            BigDecimal commissionAmount,
            String currency,
            String status,
            LocalDateTime paidAt,
            String notes,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }

    /**
     * A payout is computed from the appointments in the period, never typed in.
     *
     * <p>The client names who and when; the server sums what was completed and
     * snapshots the doctor's current rate. Letting the caller post the amount
     * would make the payout screen a free-text ledger of numbers nobody can
     * reconcile.
     */
    public record CreateDoctorPayoutRequest(
            UUID doctorId,
            LocalDate periodStart,
            LocalDate periodEnd,
            String notes,
            String projectId) {
    }

    public record UpdateDoctorPayoutStatusRequest(String status) {
    }
}
