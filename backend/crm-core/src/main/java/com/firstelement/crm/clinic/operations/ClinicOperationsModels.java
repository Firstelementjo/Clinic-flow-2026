package com.firstelement.crm.clinic.operations;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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

    // ------------------------------------------------------------------
    // Requirement 1: Inventory Management & Service-to-Material Mapping
    // ------------------------------------------------------------------

    public record InventoryItemRow(
            UUID id,
            UUID organizationId,
            UUID projectId,
            UUID clinicId,
            String clinicName,
            String name,
            String sku,
            String category,
            String unitOfMeasure,
            BigDecimal unitCost,
            int quantityOnHand,
            int minStockThreshold,
            boolean lowStock,
            boolean isActive,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }

    public record CreateInventoryItemRequest(
            String name,
            String sku,
            String category,
            String unitOfMeasure,
            BigDecimal unitCost,
            Integer quantityOnHand,
            Integer minStockThreshold,
            UUID clinicId,
            Boolean recordPurchaseExpense,
            String projectId) {
    }

    public record UpdateInventoryItemRequest(
            String name,
            String sku,
            String category,
            String unitOfMeasure,
            BigDecimal unitCost,
            Integer minStockThreshold,
            UUID clinicId,
            Boolean isActive) {
    }

    public record AdjustInventoryStockRequest(
            String transactionType,
            int quantity,
            BigDecimal unitCost,
            String notes,
            Boolean recordPurchaseExpense,
            String projectId) {
    }

    public record ServiceMaterialRow(
            UUID id,
            UUID procedureId,
            String procedureName,
            UUID itemId,
            String itemName,
            String unitOfMeasure,
            BigDecimal unitCost,
            int quantityRequired,
            int quantityOnHand) {
    }

    public record ServiceMaterialInput(
            UUID itemId,
            int quantityRequired) {
    }

    public record InventoryTransactionRow(
            UUID id,
            UUID organizationId,
            UUID projectId,
            UUID clinicId,
            String clinicName,
            UUID itemId,
            String itemName,
            String transactionType,
            int quantity,
            int quantityBefore,
            int quantityAfter,
            int shortfallQuantity,
            BigDecimal unitCost,
            String referenceType,
            UUID referenceId,
            String notes,
            LocalDateTime createdAt) {
    }

    public record InventoryStockWarning(
            UUID itemId,
            String itemName,
            int quantityRequired,
            int quantityAvailable,
            int shortfallQuantity,
            String message) {
    }

    public record DeductionResult(
            boolean deducted,
            List<InventoryStockWarning> warnings) {
    }

    public record ConsolidatedInventoryRow(
            String itemName,
            String sku,
            String category,
            String unitOfMeasure,
            int totalQuantityOnHand,
            int branchesCount,
            int lowStockBranchesCount,
            BigDecimal totalValuation) {
    }

    // ------------------------------------------------------------------
    // Requirement 2: HR Lite (Staff Scheduling & Leave Management)
    // ------------------------------------------------------------------

    public record StaffScheduleRow(
            UUID id,
            UUID organizationId,
            UUID projectId,
            UUID clinicId,
            String clinicName,
            UUID userId,
            UUID doctorId,
            String staffName,
            String staffRole,
            String scheduleType,
            Integer weekday,
            LocalDate shiftDate,
            String startTime,
            String endTime,
            String notes,
            boolean isActive,
            LocalDateTime createdAt) {
    }

    public record CreateStaffScheduleRequest(
            UUID userId,
            UUID doctorId,
            String staffName,
            String staffRole,
            String scheduleType,
            Integer weekday,
            LocalDate shiftDate,
            String startTime,
            String endTime,
            UUID clinicId,
            String notes,
            String projectId) {
    }

    public record LeaveRequestRow(
            UUID id,
            UUID organizationId,
            UUID projectId,
            UUID clinicId,
            String clinicName,
            UUID userId,
            UUID doctorId,
            String staffName,
            String staffRole,
            String leaveType,
            LocalDate startDate,
            LocalDate endDate,
            String reason,
            String status,
            UUID reviewedByUserId,
            String reviewedByName,
            LocalDateTime reviewedAt,
            String reviewNotes,
            LocalDateTime createdAt) {
    }

    public record CreateLeaveRequest(
            UUID userId,
            UUID doctorId,
            String staffName,
            String staffRole,
            String leaveType,
            LocalDate startDate,
            LocalDate endDate,
            UUID clinicId,
            String reason,
            String projectId) {
    }

    public record ReviewLeaveRequest(
            String status,
            String reviewNotes) {
    }

    public record LeaveConflictReport(
            int impactedAppointmentsCount,
            int impactedShiftsCount,
            List<String> impactedDetails,
            String warningMessage) {
    }

    // ------------------------------------------------------------------
    // Requirement 3: Financial Management & Performance Analytics
    // ------------------------------------------------------------------

    public record ExpenseRow(
            UUID id,
            UUID organizationId,
            UUID projectId,
            UUID clinicId,
            String clinicName,
            String category,
            String title,
            BigDecimal amount,
            String currency,
            String expenseFrequency,
            LocalDate paymentDate,
            UUID templateId,
            String notes,
            LocalDateTime createdAt) {
    }

    public record CreateExpenseRequest(
            String category,
            String title,
            BigDecimal amount,
            String expenseFrequency,
            LocalDate paymentDate,
            UUID clinicId,
            String notes,
            String projectId) {
    }

    public record RecurringExpenseTemplateRow(
            UUID id,
            UUID organizationId,
            UUID projectId,
            UUID clinicId,
            String clinicName,
            String category,
            String title,
            BigDecimal defaultAmount,
            int dayOfMonth,
            String lastConfirmedMonth,
            boolean dueThisMonth,
            String notes,
            boolean isActive) {
    }

    public record CreateRecurringTemplateRequest(
            String category,
            String title,
            BigDecimal defaultAmount,
            Integer dayOfMonth,
            UUID clinicId,
            String notes,
            String projectId) {
    }

    public record ConfirmRecurringTemplateRequest(
            BigDecimal amount,
            LocalDate paymentDate,
            String monthKey,
            String notes) {
    }

    public record PeriodFinancialBucket(
            String periodKey,
            String periodLabel,
            BigDecimal totalRevenue,
            BigDecimal operationalExpenses,
            BigDecimal doctorPayouts,
            BigDecimal totalExpenses,
            BigDecimal netProfit) {
    }

    public record BranchFinancialBreakdown(
            UUID clinicId,
            String branchName,
            BigDecimal totalRevenue,
            BigDecimal totalExpenses,
            BigDecimal netProfit) {
    }

    public record FinancialAnalyticsReport(
            int year,
            String periodType,
            BigDecimal totalRevenue,
            BigDecimal operationalExpenses,
            BigDecimal doctorPayouts,
            BigDecimal totalExpenses,
            BigDecimal netProfit,
            Map<String, BigDecimal> expensesByCategory,
            List<PeriodFinancialBucket> quarterlyBreakdown,
            List<PeriodFinancialBucket> monthlyBreakdown,
            List<BranchFinancialBreakdown> branchBreakdown) {
    }

    // ------------------------------------------------------------------
    // Requirement 4: Treatment Room Management
    // ------------------------------------------------------------------

    public record TreatmentRoomRow(
            UUID id,
            UUID organizationId,
            UUID projectId,
            UUID clinicId,
            String clinicName,
            String name,
            String roomNumber,
            String roomType,
            UUID assignedDoctorId,
            String assignedDoctorName,
            String operationalStartHour,
            String operationalEndHour,
            boolean isActive,
            String currentStatus,
            String currentOccupiedByDoctor,
            int totalAppointmentsCount,
            int completedProceduresCount,
            int bookedMinutes,
            double utilizationPercent,
            BigDecimal roomRevenue) {
    }

    public record CreateTreatmentRoomRequest(
            String name,
            String roomNumber,
            String roomType,
            UUID clinicId,
            UUID assignedDoctorId,
            String operationalStartHour,
            String operationalEndHour,
            Boolean isActive,
            String projectId) {
    }

    // ------------------------------------------------------------------
    // Requirement 5: Dental Imaging Integration & External Labs
    // ------------------------------------------------------------------

    public record ImagingLabRow(
            UUID id,
            UUID organizationId,
            UUID projectId,
            UUID clinicId,
            String clinicName,
            String name,
            String contactPhone,
            String contactEmail,
            String address,
            String integrationMode,
            String apiEndpointUrl,
            String notes,
            boolean isActive) {
    }

    public record CreateImagingLabRequest(
            String name,
            String contactPhone,
            String contactEmail,
            String address,
            String integrationMode,
            String apiEndpointUrl,
            UUID clinicId,
            String notes,
            String projectId) {
    }

    public record ImagingRequestRow(
            UUID id,
            UUID organizationId,
            UUID projectId,
            UUID clinicId,
            String clinicName,
            UUID patientId,
            String patientName,
            UUID doctorId,
            String doctorName,
            UUID labId,
            String labName,
            String imagingType,
            int proceduresCount,
            Integer toothFdi,
            String teethSummary,
            String clinicalIndication,
            String status,
            LocalDate requestDate,
            LocalDate completionDate,
            BigDecimal costToClinic,
            BigDecimal amountChargedToPatient,
            BigDecimal netProfit,
            UUID invoiceId,
            UUID expenseId,
            String cloudStorageUrl,
            List<String> relatedFiles,
            String findingsNotes,
            LocalDateTime createdAt) {
    }

    public record CreateImagingRequest(
            UUID patientId,
            UUID doctorId,
            UUID labId,
            String labName,
            String imagingType,
            Integer proceduresCount,
            Integer toothFdi,
            String teethSummary,
            String clinicalIndication,
            String status,
            LocalDate requestDate,
            LocalDate completionDate,
            BigDecimal costToClinic,
            BigDecimal amountChargedToPatient,
            String cloudStorageUrl,
            List<String> relatedFiles,
            String findingsNotes,
            UUID clinicId,
            String projectId) {
    }

    public record UpdateImagingRequest(
            String status,
            LocalDate completionDate,
            BigDecimal costToClinic,
            BigDecimal amountChargedToPatient,
            String cloudStorageUrl,
            List<String> relatedFiles,
            String findingsNotes,
            UUID labId,
            String labName) {
    }

    public record ImagingSummaryAnalytics(
            int totalRequests,
            int totalProceduresCount,
            BigDecimal totalCostToClinic,
            BigDecimal totalChargedToPatient,
            BigDecimal netProfit) {
    }
}
