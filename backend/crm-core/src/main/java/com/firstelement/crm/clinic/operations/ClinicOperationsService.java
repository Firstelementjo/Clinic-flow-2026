package com.firstelement.crm.clinic.operations;

import com.firstelement.crm.clinic.ClinicScope;
import com.firstelement.crm.error.ValidationFailedException;
import com.firstelement.security.TenantContext;
import com.firstelement.security.UserRole;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rules for the operations screens.
 *
 * <p>The division of labour with the repository is deliberate: the repository
 * owns the tenant predicate and the SQL, and this class owns the decisions a
 * stored row cannot make for itself — which statuses exist, how long a payout
 * period may be, and who is allowed to delete a record.
 */
@Service
public class ClinicOperationsService {

    private static final Set<String> ALIGNER_STATUSES =
            Set.of("SENT_TO_LAB", "IN_PRODUCTION", "QUALITY_CHECK", "COMPLETED", "ON_HOLD");
    private static final Set<String> PAYOUT_STATUSES = Set.of("PENDING", "PAID");
    private static final int MAX_ALIGNER_STEPS = 200;
    private static final int MAX_PAYOUT_DAYS = 366;

    private final ClinicOperationsRepository repository;

    public ClinicOperationsService(ClinicOperationsRepository repository) {
        this.repository = repository;
    }

    // ------------------------------------------------------------------
    // Clear Aligner Lab
    // ------------------------------------------------------------------

    public List<ClinicOperationsModels.AlignerCaseRow> listAlignerCases(
            ClinicScope scope, String status, String q) {
        if (status != null && !status.isBlank() && !ALIGNER_STATUSES.contains(status.trim().toUpperCase())) {
            throw new ValidationFailedException(
                    "Unknown aligner status.",
                    java.util.Map.of("allowed", ALIGNER_STATUSES));
        }
        return repository.listAlignerCases(scope, status, q);
    }

    @Transactional
    public ClinicOperationsModels.AlignerCaseRow createAlignerCase(
            ClinicScope scope,
            ClinicOperationsModels.CreateAlignerCaseRequest request,
            TenantContext caller) {
        requireClinicalWriter(caller);
        if (request.patientId() == null) {
            throw new ValidationFailedException("patientId is required.");
        }
        if (request.caseType() == null || request.caseType().trim().isBlank()) {
            throw new ValidationFailedException("caseType is required.");
        }
        validateSteps(request.upperSteps(), "upperSteps");
        validateSteps(request.lowerSteps(), "lowerSteps");
        if (request.attachmentsCount() != null && request.attachmentsCount() < 0) {
            throw new ValidationFailedException("attachmentsCount cannot be negative.");
        }
        return repository.createAlignerCase(scope, request);
    }

    @Transactional
    public ClinicOperationsModels.AlignerCaseRow updateAlignerCaseStatus(
            ClinicScope scope, UUID caseId, String status, TenantContext caller) {
        requireClinicalWriter(caller);
        if (status == null || status.trim().isBlank()) {
            throw new ValidationFailedException("status is required.");
        }
        String normalized = status.trim().toUpperCase();
        if (!ALIGNER_STATUSES.contains(normalized)) {
            throw new ValidationFailedException(
                    "Unknown aligner status.",
                    java.util.Map.of("allowed", ALIGNER_STATUSES));
        }
        return repository.updateAlignerCaseStatus(scope, caseId, normalized);
    }

    @Transactional
    public boolean deleteAlignerCase(ClinicScope scope, UUID caseId, TenantContext caller) {
        requireAdmin(caller, "Only a clinic administrator can delete an aligner case.");
        return repository.deleteAlignerCase(scope, caseId);
    }

    // ------------------------------------------------------------------
    // Patient Feedback
    // ------------------------------------------------------------------

    public List<ClinicOperationsModels.FeedbackRow> listFeedback(
            ClinicScope scope, String q, UUID doctorId, Integer minRating) {
        if (minRating != null && (minRating < 0 || minRating > 10)) {
            throw new ValidationFailedException("minRating must be between 0 and 10.");
        }
        return repository.listFeedback(scope, q, doctorId, minRating);
    }

    @Transactional
    public ClinicOperationsModels.FeedbackRow createFeedback(
            ClinicScope scope,
            ClinicOperationsModels.CreateFeedbackRequest request,
            TenantContext caller) {
        if (caller == null) {
            throw new AccessDeniedException("An authenticated caller is required.");
        }
        if (request.patientName() == null || request.patientName().trim().isBlank()) {
            throw new ValidationFailedException("patientName is required.");
        }
        if (request.patientAge() != null && (request.patientAge() < 0 || request.patientAge() > 150)) {
            throw new ValidationFailedException("patientAge must be between 0 and 150.");
        }
        return repository.createFeedback(scope, request);
    }

    @Transactional
    public boolean deleteFeedback(ClinicScope scope, UUID feedbackId, TenantContext caller) {
        requireAdmin(caller, "Only a clinic administrator can delete patient feedback.");
        return repository.deleteFeedback(scope, feedbackId);
    }

    // ------------------------------------------------------------------
    // Doctor Commission & Payouts
    // ------------------------------------------------------------------

    public List<ClinicOperationsModels.DoctorCommissionRow> doctorCommission(
            ClinicScope scope, LocalDateTime from, LocalDateTime to) {
        validateWindow(from, to);
        return repository.doctorCommission(scope, from, to);
    }

    public List<ClinicOperationsModels.DoctorPayoutRow> listPayouts(
            ClinicScope scope, UUID doctorId, String status) {
        if (status != null && !status.isBlank() && !PAYOUT_STATUSES.contains(status.trim().toUpperCase())) {
            throw new ValidationFailedException(
                    "Unknown payout status.",
                    java.util.Map.of("allowed", PAYOUT_STATUSES));
        }
        return repository.listPayouts(scope, doctorId, status);
    }

    @Transactional
    public ClinicOperationsModels.DoctorPayoutRow createPayout(
            ClinicScope scope,
            ClinicOperationsModels.CreateDoctorPayoutRequest request,
            TenantContext caller) {
        requireAdmin(caller, "Only a clinic administrator can settle a doctor payout.");
        if (request.doctorId() == null) {
            throw new ValidationFailedException("doctorId is required.");
        }
        LocalDate start = request.periodStart();
        LocalDate end = request.periodEnd();
        if (start == null || end == null) {
            throw new ValidationFailedException("periodStart and periodEnd are required.");
        }
        if (start.isAfter(end)) {
            throw new ValidationFailedException("periodStart must not be after periodEnd.");
        }
        if (start.plusDays(MAX_PAYOUT_DAYS).isBefore(end)) {
            throw new ValidationFailedException(
                    "A payout period cannot exceed " + MAX_PAYOUT_DAYS + " days.");
        }
        return repository.createPayout(scope, request, caller.userId());
    }

    @Transactional
    public ClinicOperationsModels.DoctorPayoutRow updatePayoutStatus(
            ClinicScope scope, UUID payoutId, String status, TenantContext caller) {
        requireAdmin(caller, "Only a clinic administrator can change a payout.");
        if (status == null || status.trim().isBlank()) {
            throw new ValidationFailedException("status is required.");
        }
        String normalized = status.trim().toUpperCase();
        if (!PAYOUT_STATUSES.contains(normalized)) {
            throw new ValidationFailedException(
                    "Unknown payout status.",
                    java.util.Map.of("allowed", PAYOUT_STATUSES));
        }
        return repository.updatePayoutStatus(scope, payoutId, normalized);
    }

    // ------------------------------------------------------------------
    // Requirement 1: Inventory Management & Automatic Material Deduction
    // ------------------------------------------------------------------

    public List<ClinicOperationsModels.InventoryItemRow> listInventoryItems(
            ClinicScope scope, UUID clinicId, String q, boolean lowStockOnly) {
        return repository.inventory().listItems(scope, clinicId, q, lowStockOnly);
    }

    public List<ClinicOperationsModels.ConsolidatedInventoryRow> listConsolidatedInventory(
            ClinicScope scope, String q) {
        return repository.inventory().listConsolidated(scope, q);
    }

    @Transactional
    public ClinicOperationsModels.InventoryItemRow createInventoryItem(
            ClinicScope scope, ClinicOperationsModels.CreateInventoryItemRequest request, TenantContext caller) {
        requireClinicalWriter(caller);
        if (request == null || request.name() == null || request.name().trim().isBlank()) {
            throw new ValidationFailedException("name is required.");
        }
        return repository.inventory().createItem(scope, request, caller.userId());
    }

    @Transactional
    public ClinicOperationsModels.InventoryItemRow updateInventoryItem(
            ClinicScope scope, UUID itemId, ClinicOperationsModels.UpdateInventoryItemRequest request, TenantContext caller) {
        requireClinicalWriter(caller);
        return repository.inventory().updateItem(scope, itemId, request);
    }

    @Transactional
    public ClinicOperationsModels.InventoryItemRow adjustInventoryStock(
            ClinicScope scope, UUID itemId, ClinicOperationsModels.AdjustInventoryStockRequest request, TenantContext caller) {
        requireClinicalWriter(caller);
        return repository.inventory().adjustStock(scope, itemId, request, caller.userId());
    }

    @Transactional
    public boolean deleteInventoryItem(ClinicScope scope, UUID itemId, TenantContext caller) {
        requireAdmin(caller, "Only a clinic administrator can delete or archive inventory items.");
        return repository.inventory().deleteItem(scope, itemId);
    }

    public List<ClinicOperationsModels.InventoryTransactionRow> listInventoryTransactions(
            ClinicScope scope, UUID itemId, String transactionType, int limit) {
        return repository.inventory().listTransactions(scope, itemId, transactionType, limit);
    }

    public List<ClinicOperationsModels.ServiceMaterialRow> listServiceMaterials(
            ClinicScope scope, UUID procedureId) {
        return repository.inventory().listServiceMaterials(scope, procedureId);
    }

    @Transactional
    public List<ClinicOperationsModels.ServiceMaterialRow> replaceServiceMaterials(
            ClinicScope scope,
            UUID procedureId,
            List<ClinicOperationsModels.ServiceMaterialInput> materials,
            TenantContext caller) {
        requireClinicalWriter(caller);
        return repository.inventory().replaceServiceMaterials(scope, procedureId, materials);
    }

    public List<ClinicOperationsModels.InventoryStockWarning> checkProcedureAvailability(
            ClinicScope scope, UUID procedureId, int quantity) {
        return repository.inventory().checkProcedureAvailability(scope, procedureId, quantity);
    }

    @Transactional
    public ClinicOperationsModels.DeductionResult deductMaterialsForCompletedAppointment(
            ClinicScope scope, UUID appointmentId, UUID procedureId, int quantity, UUID performedByUserId) {
        return repository.inventory().deductMaterialsForProcedure(
                scope, procedureId, quantity, "APPOINTMENT", appointmentId, null, performedByUserId);
    }

    @Transactional
    public ClinicOperationsModels.DeductionResult deductMaterialsForCompletedEncounter(
            ClinicScope scope,
            UUID encounterId,
            UUID appointmentId,
            UUID treatmentPlanItemId,
            String performedProcedures,
            UUID performedByUserId) {
        UUID procedureId = repository.inventory().resolveProcedureIdForEncounter(
                scope, appointmentId, treatmentPlanItemId, performedProcedures);
        if (procedureId == null) {
            return new ClinicOperationsModels.DeductionResult(false, List.of());
        }
        return repository.inventory().deductMaterialsForProcedure(
                scope, procedureId, 1, "ENCOUNTER", encounterId, appointmentId, performedByUserId);
    }

    // ------------------------------------------------------------------
    // Requirement 2: HR Lite — Staff Scheduling & Leave Management
    // ------------------------------------------------------------------

    public List<ClinicOperationsModels.StaffScheduleRow> listStaffSchedules(
            ClinicScope scope, UUID userId, UUID doctorId, boolean mineOnly, String scheduleType, TenantContext caller) {
        UUID callerId = caller != null ? caller.userId() : null;
        return repository.hrLite().listSchedules(scope, userId, doctorId, mineOnly, callerId, scheduleType);
    }

    @Transactional
    public ClinicOperationsModels.StaffScheduleRow createStaffSchedule(
            ClinicScope scope, ClinicOperationsModels.CreateStaffScheduleRequest request, TenantContext caller) {
        requireAdmin(caller, "Only a clinic manager can create staff schedules.");
        return repository.hrLite().createSchedule(scope, request);
    }

    @Transactional
    public ClinicOperationsModels.StaffScheduleRow updateStaffSchedule(
            ClinicScope scope, UUID scheduleId, ClinicOperationsModels.CreateStaffScheduleRequest request, TenantContext caller) {
        requireAdmin(caller, "Only a clinic manager can edit staff schedules.");
        return repository.hrLite().updateSchedule(scope, scheduleId, request);
    }

    @Transactional
    public boolean deleteStaffSchedule(ClinicScope scope, UUID scheduleId, TenantContext caller) {
        requireAdmin(caller, "Only a clinic manager can delete staff schedules.");
        return repository.hrLite().deleteSchedule(scope, scheduleId);
    }

    public List<ClinicOperationsModels.LeaveRequestRow> listLeaveRequests(
            ClinicScope scope, String status, boolean mineOnly, TenantContext caller) {
        UUID callerId = caller != null ? caller.userId() : null;
        return repository.hrLite().listLeaveRequests(scope, status, mineOnly, callerId);
    }

    @Transactional
    public ClinicOperationsModels.LeaveRequestRow createLeaveRequest(
            ClinicScope scope, ClinicOperationsModels.CreateLeaveRequest request, TenantContext caller) {
        requireClinicalWriter(caller);
        return repository.hrLite().createLeaveRequest(
                scope, request, caller.userId(), request.staffName());
    }

    public ClinicOperationsModels.LeaveConflictReport getLeaveConflicts(
            ClinicScope scope, UUID requestId) {
        return repository.hrLite().getLeaveConflicts(scope, requestId);
    }

    @Transactional
    public ClinicOperationsModels.LeaveRequestRow reviewLeaveRequest(
            ClinicScope scope, UUID requestId, ClinicOperationsModels.ReviewLeaveRequest request, TenantContext caller) {
        requireAdmin(caller, "Only a clinic manager can approve or reject leave requests.");
        return repository.hrLite().reviewLeaveRequest(
                scope, requestId, request, caller.userId(), null);
    }

    // ------------------------------------------------------------------
    // Requirement 3: Financial Management & Performance Analytics
    // ------------------------------------------------------------------

    public List<ClinicOperationsModels.ExpenseRow> listExpenses(
            ClinicScope scope, UUID clinicId, String category, LocalDate from, LocalDate to) {
        return repository.finance().listExpenses(scope, clinicId, category, from, to);
    }

    @Transactional
    public ClinicOperationsModels.ExpenseRow createExpense(
            ClinicScope scope, ClinicOperationsModels.CreateExpenseRequest request, TenantContext caller) {
        requireClinicalWriter(caller);
        return repository.finance().createExpense(scope, request, caller.userId());
    }

    @Transactional
    public boolean deleteExpense(ClinicScope scope, UUID expenseId, TenantContext caller) {
        requireAdmin(caller, "Only a clinic manager can delete operational expenses.");
        return repository.finance().deleteExpense(scope, expenseId);
    }

    public List<ClinicOperationsModels.RecurringExpenseTemplateRow> listRecurringTemplates(
            ClinicScope scope, UUID clinicId) {
        return repository.finance().listRecurringTemplates(scope, clinicId);
    }

    @Transactional
    public ClinicOperationsModels.RecurringExpenseTemplateRow createRecurringTemplate(
            ClinicScope scope, ClinicOperationsModels.CreateRecurringTemplateRequest request, TenantContext caller) {
        requireClinicalWriter(caller);
        return repository.finance().createRecurringTemplate(scope, request);
    }

    @Transactional
    public ClinicOperationsModels.ExpenseRow confirmRecurringTemplate(
            ClinicScope scope,
            UUID templateId,
            ClinicOperationsModels.ConfirmRecurringTemplateRequest request,
            TenantContext caller) {
        requireClinicalWriter(caller);
        return repository.finance().confirmRecurringTemplate(scope, templateId, request, caller.userId());
    }

    @Transactional
    public boolean deleteRecurringTemplate(ClinicScope scope, UUID templateId, TenantContext caller) {
        requireAdmin(caller, "Only a clinic manager can delete recurring expense templates.");
        return repository.finance().deleteRecurringTemplate(scope, templateId);
    }

    public ClinicOperationsModels.FinancialAnalyticsReport getFinancialAnalytics(
            ClinicScope scope, int year, String periodType, UUID clinicId) {
        return repository.finance().getFinancialAnalytics(scope, year, periodType, clinicId);
    }

    // ------------------------------------------------------------------
    // Requirement 4: Treatment Room Management
    // ------------------------------------------------------------------

    public List<ClinicOperationsModels.TreatmentRoomRow> listTreatmentRooms(ClinicScope scope, UUID clinicId) {
        return repository.treatmentRooms().listRooms(scope, clinicId);
    }

    @Transactional
    public ClinicOperationsModels.TreatmentRoomRow createTreatmentRoom(
            ClinicScope scope, ClinicOperationsModels.CreateTreatmentRoomRequest request, TenantContext caller) {
        requireAdmin(caller, "Only a clinic administrator can create treatment rooms.");
        if (request == null || request.name() == null || request.name().trim().isBlank()) {
            throw new ValidationFailedException("name is required.");
        }
        return repository.treatmentRooms().createRoom(scope, request);
    }

    @Transactional
    public ClinicOperationsModels.TreatmentRoomRow updateTreatmentRoom(
            ClinicScope scope, UUID roomId, ClinicOperationsModels.CreateTreatmentRoomRequest request, TenantContext caller) {
        requireAdmin(caller, "Only a clinic administrator can edit treatment rooms.");
        return repository.treatmentRooms().updateRoom(scope, roomId, request);
    }

    @Transactional
    public boolean deleteTreatmentRoom(ClinicScope scope, UUID roomId, TenantContext caller) {
        requireAdmin(caller, "Only a clinic administrator can delete treatment rooms.");
        return repository.treatmentRooms().deleteRoom(scope, roomId);
    }

    // ------------------------------------------------------------------
    // Requirement 5: Dental Imaging Integration & External Labs
    // ------------------------------------------------------------------

    public List<ClinicOperationsModels.ImagingLabRow> listImagingLabs(ClinicScope scope, UUID clinicId) {
        return repository.imaging().listLabs(scope, clinicId);
    }

    @Transactional
    public ClinicOperationsModels.ImagingLabRow createImagingLab(
            ClinicScope scope, ClinicOperationsModels.CreateImagingLabRequest request, TenantContext caller) {
        requireClinicalWriter(caller);
        if (request == null || request.name() == null || request.name().trim().isBlank()) {
            throw new ValidationFailedException("name is required.");
        }
        return repository.imaging().createLab(scope, request);
    }

    @Transactional
    public ClinicOperationsModels.ImagingLabRow updateImagingLab(
            ClinicScope scope, UUID labId, ClinicOperationsModels.CreateImagingLabRequest request, TenantContext caller) {
        requireClinicalWriter(caller);
        return repository.imaging().updateLab(scope, labId, request);
    }

    @Transactional
    public boolean deleteImagingLab(ClinicScope scope, UUID labId, TenantContext caller) {
        requireAdmin(caller, "Only a clinic administrator can delete external imaging laboratories.");
        return repository.imaging().deleteLab(scope, labId);
    }

    public List<ClinicOperationsModels.ImagingRequestRow> listImagingRequests(
            ClinicScope scope, UUID patientId, String status, String imagingType) {
        return repository.imaging().listRequests(scope, patientId, status, imagingType);
    }

    public ClinicOperationsModels.ImagingSummaryAnalytics getImagingAnalytics(ClinicScope scope) {
        return repository.imaging().getAnalytics(scope);
    }

    @Transactional
    public ClinicOperationsModels.ImagingRequestRow createImagingRequest(
            ClinicScope scope, ClinicOperationsModels.CreateImagingRequest request, TenantContext caller) {
        requireClinicalWriter(caller);
        if (request == null || request.patientId() == null) {
            throw new ValidationFailedException("patientId is required.");
        }
        return repository.imaging().createRequest(scope, request, caller.userId());
    }

    @Transactional
    public ClinicOperationsModels.ImagingRequestRow updateImagingRequest(
            ClinicScope scope, UUID requestId, ClinicOperationsModels.UpdateImagingRequest request, TenantContext caller) {
        requireClinicalWriter(caller);
        return repository.imaging().updateRequest(scope, requestId, request, caller.userId());
    }

    @Transactional
    public boolean deleteImagingRequest(ClinicScope scope, UUID requestId, TenantContext caller) {
        requireAdmin(caller, "Only a clinic administrator can delete imaging requests.");
        return repository.imaging().deleteRequest(scope, requestId);
    }

    // ------------------------------------------------------------------
    // Guards
    // ------------------------------------------------------------------

    private static void validateSteps(Integer steps, String field) {
        if (steps == null) {
            return;
        }
        if (steps < 0 || steps > MAX_ALIGNER_STEPS) {
            throw new ValidationFailedException(
                    field + " must be between 0 and " + MAX_ALIGNER_STEPS + ".");
        }
    }

    private static void validateWindow(LocalDateTime from, LocalDateTime to) {
        if (from == null || to == null) {
            throw new ValidationFailedException("from and to are required.");
        }
        if (from.isAfter(to)) {
            throw new ValidationFailedException("from must not be after to.");
        }
    }

    /**
     * Lab and aligner rows are written by the clinicians who produce them, so the
     * guard is the clinic write door (a member of this clinic) rather than an
     * administrator role. Destructive changes are the administrator's.
     */
    private static void requireClinicalWriter(TenantContext caller) {
        if (caller == null) {
            throw new AccessDeniedException("An authenticated caller is required.");
        }
        if (caller.role() != UserRole.ADMIN
                && caller.role() != UserRole.EMPLOYEE
                && caller.role() != UserRole.CLINIC_STAFF) {
            throw new AccessDeniedException("Only clinic staff can write clinical lab records.");
        }
    }

    private static void requireAdmin(TenantContext caller, String message) {
        if (caller == null || caller.role() != UserRole.ADMIN) {
            throw new AccessDeniedException(message);
        }
    }
}
