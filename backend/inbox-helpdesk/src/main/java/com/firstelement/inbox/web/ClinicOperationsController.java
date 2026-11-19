package com.firstelement.inbox.web;

import com.firstelement.crm.clinic.ClinicScope;
import com.firstelement.crm.clinic.operations.ClinicOperationsModels;
import com.firstelement.crm.clinic.operations.ClinicOperationsService;
import com.firstelement.crm.error.ResourceNotFoundException;
import com.firstelement.security.TenantContext;
import com.firstelement.security.TenantContextHolder;
import com.firstelement.security.annotation.RequirePermission;
import com.firstelement.security.permission.Permission;
import com.firstelement.security.query.TenantQueryGuard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The clinic operations routes: clear-aligner lab, patient feedback and doctor
 * commission payouts.
 *
 * <p>Reads are declared on the same permission the sidebar entry carries, so a
 * screen the shell offers is a screen the server answers. The two destructive
 * routes (deleting an aligner case, deleting a feedback row) are administrator
 * actions and are refused for a read-only clinic account; writing a case or a
 * feedback response is clinical work and is refused for anyone outside the
 * clinic.
 *
 * <p>Commission and payout routes are guarded on {@code REPORT_MANAGE}, which
 * only the administrator role holds. Doctor earnings are not a front-desk read.
 */
@RestController
@RequestMapping("/api/v1/clinic")
public class ClinicOperationsController {

    private final ClinicOperationsService operationsService;
    private final TenantQueryGuard guard;

    public ClinicOperationsController(ClinicOperationsService operationsService, TenantQueryGuard guard) {
        this.operationsService = operationsService;
        this.guard = guard;
    }

    // ------------------------------------------------------------------
    // Clear Aligner Lab
    // ------------------------------------------------------------------

    @GetMapping("/aligner-cases")
    @RequirePermission(Permission.DOCTOR_READ)
    @Operation(summary = "List clear-aligner cases")
    public List<ClinicOperationsModels.AlignerCaseRow> listAlignerCases(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String projectId) {
        return operationsService.listAlignerCases(scope(projectId), status, q);
    }

    @PostMapping("/aligner-cases")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Submit a clear-aligner case")
    public ClinicOperationsModels.AlignerCaseRow createAlignerCase(
            @RequestBody ClinicOperationsModels.CreateAlignerCaseRequest request,
            @RequestParam(required = false) String projectId) {
        String effectiveProject = projectId != null ? projectId : request.projectId();
        return operationsService.createAlignerCase(writeScope(effectiveProject), request, caller());
    }

    @PatchMapping("/aligner-cases/{caseId}/status")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Move an aligner case through the lab pipeline")
    public ClinicOperationsModels.AlignerCaseRow updateAlignerCaseStatus(
            @PathVariable("caseId") UUID caseId,
            @RequestBody ClinicOperationsModels.UpdateAlignerCaseStatusRequest request,
            @RequestParam(required = false) String projectId) {
        return operationsService.updateAlignerCaseStatus(
                writeScope(projectId), caseId, request.status(), caller());
    }

    @DeleteMapping("/aligner-cases/{caseId}")
    @RequirePermission(Permission.CLINIC_MANAGE)
    @Operation(summary = "Delete an aligner case")
    public boolean deleteAlignerCase(
            @PathVariable("caseId") UUID caseId,
            @RequestParam(required = false) String projectId) {
        return operationsService.deleteAlignerCase(writeScope(projectId), caseId, caller());
    }

    // ------------------------------------------------------------------
    // Patient Feedback
    // ------------------------------------------------------------------

    @GetMapping("/feedback")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "List patient feedback")
    public List<ClinicOperationsModels.FeedbackRow> listFeedback(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String doctorId,
            @RequestParam(required = false) Integer minRating,
            @RequestParam(required = false) String projectId) {
        return operationsService.listFeedback(
                scope(projectId),
                q,
                RequestParams.optionalUuid(doctorId, "doctorId"),
                minRating);
    }

    @PostMapping("/feedback")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Record a patient feedback response")
    public ClinicOperationsModels.FeedbackRow createFeedback(
            @RequestBody ClinicOperationsModels.CreateFeedbackRequest request,
            @RequestParam(required = false) String projectId) {
        String effectiveProject = projectId != null ? projectId : request.projectId();
        return operationsService.createFeedback(writeScope(effectiveProject), request, caller());
    }

    @DeleteMapping("/feedback/{feedbackId}")
    @RequirePermission(Permission.CLINIC_MANAGE)
    @Operation(summary = "Delete a patient feedback response")
    public boolean deleteFeedback(
            @PathVariable("feedbackId") UUID feedbackId,
            @RequestParam(required = false) String projectId) {
        return operationsService.deleteFeedback(writeScope(projectId), feedbackId, caller());
    }

    // ------------------------------------------------------------------
    // Doctor Commission & Payouts
    // ------------------------------------------------------------------

    @GetMapping("/doctor-commission")
    @RequirePermission(Permission.REPORT_MANAGE)
    @Operation(summary = "Doctor commission earned and paid in a window")
    public List<ClinicOperationsModels.DoctorCommissionRow> doctorCommission(
            @Parameter(description = "Window start; defaults to 30 days ago")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @Parameter(description = "Window end; defaults to now")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false) String projectId) {
        LocalDateTime end = to != null ? to : LocalDateTime.now();
        LocalDateTime start = from != null ? from : end.minusDays(30);
        return operationsService.doctorCommission(scope(projectId), start, end);
    }

    @GetMapping("/doctor-payouts")
    @RequirePermission(Permission.REPORT_MANAGE)
    @Operation(summary = "List doctor payouts")
    public List<ClinicOperationsModels.DoctorPayoutRow> listPayouts(
            @RequestParam(required = false) String doctorId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String projectId) {
        return operationsService.listPayouts(
                scope(projectId), RequestParams.optionalUuid(doctorId, "doctorId"), status);
    }

    @PostMapping("/doctor-payouts")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission(Permission.REPORT_MANAGE)
    @Operation(summary = "Settle a doctor payout for a period")
    public ClinicOperationsModels.DoctorPayoutRow createPayout(
            @RequestBody ClinicOperationsModels.CreateDoctorPayoutRequest request,
            @RequestParam(required = false) String projectId) {
        String effectiveProject = projectId != null ? projectId : request.projectId();
        return operationsService.createPayout(writeScope(effectiveProject), request, caller());
    }

    @PatchMapping("/doctor-payouts/{payoutId}/status")
    @RequirePermission(Permission.REPORT_MANAGE)
    @Operation(summary = "Mark a doctor payout paid or pending")
    public ClinicOperationsModels.DoctorPayoutRow updatePayoutStatus(
            @PathVariable("payoutId") UUID payoutId,
            @RequestBody ClinicOperationsModels.UpdateDoctorPayoutStatusRequest request,
            @RequestParam(required = false) String projectId) {
        return operationsService.updatePayoutStatus(
                writeScope(projectId), payoutId, request.status(), caller());
    }

    // ------------------------------------------------------------------
    // Requirement 1: Inventory Management & Service-to-Material Mapping
    // ------------------------------------------------------------------

    @GetMapping("/inventory/items")
    @RequirePermission(Permission.PROCEDURE_READ)
    @Operation(summary = "List branch inventory items")
    public List<ClinicOperationsModels.InventoryItemRow> listInventoryItems(
            @RequestParam(required = false) String clinicId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false, defaultValue = "false") boolean lowStockOnly,
            @RequestParam(required = false) String projectId) {
        return operationsService.listInventoryItems(
                scope(projectId), RequestParams.optionalUuid(clinicId, "clinicId"), q, lowStockOnly);
    }

    @GetMapping("/inventory/consolidated")
    @RequirePermission(Permission.PROCEDURE_READ)
    @Operation(summary = "Consolidated multi-branch inventory overview")
    public List<ClinicOperationsModels.ConsolidatedInventoryRow> listConsolidatedInventory(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String projectId) {
        return operationsService.listConsolidatedInventory(scope(projectId), q);
    }

    @PostMapping("/inventory/items")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission(Permission.PROCEDURE_MANAGE)
    @Operation(summary = "Create an inventory material item")
    public ClinicOperationsModels.InventoryItemRow createInventoryItem(
            @RequestBody ClinicOperationsModels.CreateInventoryItemRequest request,
            @RequestParam(required = false) String projectId) {
        String effectiveProject = projectId != null ? projectId : request.projectId();
        return operationsService.createInventoryItem(writeScope(effectiveProject), request, caller());
    }

    @PatchMapping("/inventory/items/{itemId}")
    @RequirePermission(Permission.PROCEDURE_MANAGE)
    @Operation(summary = "Update an inventory material item")
    public ClinicOperationsModels.InventoryItemRow updateInventoryItem(
            @PathVariable("itemId") UUID itemId,
            @RequestBody ClinicOperationsModels.UpdateInventoryItemRequest request,
            @RequestParam(required = false) String projectId) {
        return operationsService.updateInventoryItem(writeScope(projectId), itemId, request, caller());
    }

    @PostMapping("/inventory/items/{itemId}/adjust")
    @RequirePermission(Permission.PROCEDURE_MANAGE)
    @Operation(summary = "Adjust or purchase inventory stock")
    public ClinicOperationsModels.InventoryItemRow adjustInventoryStock(
            @PathVariable("itemId") UUID itemId,
            @RequestBody ClinicOperationsModels.AdjustInventoryStockRequest request,
            @RequestParam(required = false) String projectId) {
        String effectiveProject = projectId != null ? projectId : request.projectId();
        return operationsService.adjustInventoryStock(writeScope(effectiveProject), itemId, request, caller());
    }

    @DeleteMapping("/inventory/items/{itemId}")
    @RequirePermission(Permission.PROCEDURE_MANAGE)
    @Operation(summary = "Delete or archive an inventory item")
    public boolean deleteInventoryItem(
            @PathVariable("itemId") UUID itemId,
            @RequestParam(required = false) String projectId) {
        return operationsService.deleteInventoryItem(writeScope(projectId), itemId, caller());
    }

    @GetMapping("/inventory/transactions")
    @RequirePermission(Permission.PROCEDURE_READ)
    @Operation(summary = "List inventory transaction audit ledger")
    public List<ClinicOperationsModels.InventoryTransactionRow> listInventoryTransactions(
            @RequestParam(required = false) String itemId,
            @RequestParam(required = false) String transactionType,
            @RequestParam(defaultValue = "200") int limit,
            @RequestParam(required = false) String projectId) {
        return operationsService.listInventoryTransactions(
                scope(projectId), RequestParams.optionalUuid(itemId, "itemId"), transactionType, limit);
    }

    @GetMapping("/inventory/check-availability")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Check available stock for a procedure before completion")
    public List<ClinicOperationsModels.InventoryStockWarning> checkInventoryAvailability(
            @RequestParam String procedureId,
            @RequestParam(defaultValue = "1") int quantity,
            @RequestParam(required = false) String projectId) {
        return operationsService.checkProcedureAvailability(
                scope(projectId), RequestParams.requiredUuid(procedureId, "procedureId"), quantity);
    }

    @GetMapping("/procedures/{procedureId}/materials")
    @RequirePermission(Permission.PROCEDURE_READ)
    @Operation(summary = "List required materials configured for a service")
    public List<ClinicOperationsModels.ServiceMaterialRow> listServiceMaterials(
            @PathVariable("procedureId") UUID procedureId,
            @RequestParam(required = false) String projectId) {
        return operationsService.listServiceMaterials(scope(projectId), procedureId);
    }

    @PutMapping("/procedures/{procedureId}/materials")
    @RequirePermission(Permission.PROCEDURE_MANAGE)
    @Operation(summary = "Replace required inventory materials for a service")
    public List<ClinicOperationsModels.ServiceMaterialRow> replaceServiceMaterials(
            @PathVariable("procedureId") UUID procedureId,
            @RequestBody List<ClinicOperationsModels.ServiceMaterialInput> materials,
            @RequestParam(required = false) String projectId) {
        return operationsService.replaceServiceMaterials(writeScope(projectId), procedureId, materials, caller());
    }

    // ------------------------------------------------------------------
    // Requirement 2: HR Lite — Staff Scheduling & Leave Management
    // ------------------------------------------------------------------

    @GetMapping("/hr/schedules")
    @RequirePermission(Permission.DOCTOR_READ)
    @Operation(summary = "List staff work schedules (weekly templates and date rosters)")
    public List<ClinicOperationsModels.StaffScheduleRow> listStaffSchedules(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String doctorId,
            @RequestParam(required = false, defaultValue = "false") boolean mineOnly,
            @RequestParam(required = false) String scheduleType,
            @RequestParam(required = false) String projectId) {
        return operationsService.listStaffSchedules(
                scope(projectId),
                RequestParams.optionalUuid(userId, "userId"),
                RequestParams.optionalUuid(doctorId, "doctorId"),
                mineOnly,
                scheduleType,
                caller());
    }

    @PostMapping("/hr/schedules")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission(Permission.CLINIC_MANAGE)
    @Operation(summary = "Create a staff work schedule shift")
    public ClinicOperationsModels.StaffScheduleRow createStaffSchedule(
            @RequestBody ClinicOperationsModels.CreateStaffScheduleRequest request,
            @RequestParam(required = false) String projectId) {
        String effectiveProject = projectId != null ? projectId : request.projectId();
        return operationsService.createStaffSchedule(writeScope(effectiveProject), request, caller());
    }

    @PatchMapping("/hr/schedules/{scheduleId}")
    @RequirePermission(Permission.CLINIC_MANAGE)
    @Operation(summary = "Update a staff work schedule shift")
    public ClinicOperationsModels.StaffScheduleRow updateStaffSchedule(
            @PathVariable("scheduleId") UUID scheduleId,
            @RequestBody ClinicOperationsModels.CreateStaffScheduleRequest request,
            @RequestParam(required = false) String projectId) {
        String effectiveProject = projectId != null ? projectId : request.projectId();
        return operationsService.updateStaffSchedule(writeScope(effectiveProject), scheduleId, request, caller());
    }

    @DeleteMapping("/hr/schedules/{scheduleId}")
    @RequirePermission(Permission.CLINIC_MANAGE)
    @Operation(summary = "Delete a staff work schedule shift")
    public boolean deleteStaffSchedule(
            @PathVariable("scheduleId") UUID scheduleId,
            @RequestParam(required = false) String projectId) {
        return operationsService.deleteStaffSchedule(writeScope(projectId), scheduleId, caller());
    }

    @GetMapping("/hr/leave-requests")
    @RequirePermission(Permission.DOCTOR_READ)
    @Operation(summary = "List staff leave requests")
    public List<ClinicOperationsModels.LeaveRequestRow> listLeaveRequests(
            @RequestParam(required = false) String status,
            @RequestParam(required = false, defaultValue = "false") boolean mineOnly,
            @RequestParam(required = false) String projectId) {
        return operationsService.listLeaveRequests(scope(projectId), status, mineOnly, caller());
    }

    @PostMapping("/hr/leave-requests")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission(Permission.DOCTOR_READ)
    @Operation(summary = "Submit a staff leave request")
    public ClinicOperationsModels.LeaveRequestRow createLeaveRequest(
            @RequestBody ClinicOperationsModels.CreateLeaveRequest request,
            @RequestParam(required = false) String projectId) {
        String effectiveProject = projectId != null ? projectId : request.projectId();
        return operationsService.createLeaveRequest(writeScope(effectiveProject), request, caller());
    }

    @GetMapping("/hr/leave-requests/{requestId}/conflicts")
    @RequirePermission(Permission.DOCTOR_READ)
    @Operation(summary = "Inspect impacted appointments and shifts before approving leave")
    public ClinicOperationsModels.LeaveConflictReport getLeaveConflicts(
            @PathVariable("requestId") UUID requestId,
            @RequestParam(required = false) String projectId) {
        return operationsService.getLeaveConflicts(scope(projectId), requestId);
    }

    @PatchMapping("/hr/leave-requests/{requestId}/decision")
    @RequirePermission(Permission.CLINIC_MANAGE)
    @Operation(summary = "Approve or reject a staff leave request")
    public ClinicOperationsModels.LeaveRequestRow reviewLeaveRequest(
            @PathVariable("requestId") UUID requestId,
            @RequestBody ClinicOperationsModels.ReviewLeaveRequest request,
            @RequestParam(required = false) String projectId) {
        return operationsService.reviewLeaveRequest(writeScope(projectId), requestId, request, caller());
    }

    // ------------------------------------------------------------------
    // Requirement 3: Financial Management & Performance Analytics
    // ------------------------------------------------------------------

    @GetMapping("/finance/expenses")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "List clinic operational expenses")
    public List<ClinicOperationsModels.ExpenseRow> listExpenses(
            @RequestParam(required = false) String clinicId,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String projectId) {
        return operationsService.listExpenses(
                scope(projectId), RequestParams.optionalUuid(clinicId, "clinicId"), category, from, to);
    }

    @PostMapping("/finance/expenses")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Record a clinic operational expense")
    public ClinicOperationsModels.ExpenseRow createExpense(
            @RequestBody ClinicOperationsModels.CreateExpenseRequest request,
            @RequestParam(required = false) String projectId) {
        String effectiveProject = projectId != null ? projectId : request.projectId();
        return operationsService.createExpense(writeScope(effectiveProject), request, caller());
    }

    @DeleteMapping("/finance/expenses/{expenseId}")
    @RequirePermission(Permission.CLINIC_MANAGE)
    @Operation(summary = "Delete an operational expense")
    public boolean deleteExpense(
            @PathVariable("expenseId") UUID expenseId,
            @RequestParam(required = false) String projectId) {
        return operationsService.deleteExpense(writeScope(projectId), expenseId, caller());
    }

    @GetMapping("/finance/recurring-templates")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "List recurring monthly expense and salary templates")
    public List<ClinicOperationsModels.RecurringExpenseTemplateRow> listRecurringTemplates(
            @RequestParam(required = false) String clinicId,
            @RequestParam(required = false) String projectId) {
        return operationsService.listRecurringTemplates(
                scope(projectId), RequestParams.optionalUuid(clinicId, "clinicId"));
    }

    @PostMapping("/finance/recurring-templates")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Create a recurring monthly expense or salary template")
    public ClinicOperationsModels.RecurringExpenseTemplateRow createRecurringTemplate(
            @RequestBody ClinicOperationsModels.CreateRecurringTemplateRequest request,
            @RequestParam(required = false) String projectId) {
        String effectiveProject = projectId != null ? projectId : request.projectId();
        return operationsService.createRecurringTemplate(writeScope(effectiveProject), request, caller());
    }

    @PostMapping("/finance/recurring-templates/{templateId}/confirm")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Confirm and log a recurring monthly expense or salary into the ledger")
    public ClinicOperationsModels.ExpenseRow confirmRecurringTemplate(
            @PathVariable("templateId") UUID templateId,
            @RequestBody(required = false) ClinicOperationsModels.ConfirmRecurringTemplateRequest request,
            @RequestParam(required = false) String projectId) {
        return operationsService.confirmRecurringTemplate(writeScope(projectId), templateId, request, caller());
    }

    @DeleteMapping("/finance/recurring-templates/{templateId}")
    @RequirePermission(Permission.CLINIC_MANAGE)
    @Operation(summary = "Delete a recurring expense template")
    public boolean deleteRecurringTemplate(
            @PathVariable("templateId") UUID templateId,
            @RequestParam(required = false) String projectId) {
        return operationsService.deleteRecurringTemplate(writeScope(projectId), templateId, caller());
    }

    @GetMapping("/finance/analytics")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Get Gregorian quarterly/monthly/annual P&L financial analytics")
    public ClinicOperationsModels.FinancialAnalyticsReport getFinancialAnalytics(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false, defaultValue = "QUARTERLY") String periodType,
            @RequestParam(required = false) String clinicId,
            @RequestParam(required = false) String projectId) {
        int safeYear = year != null ? year : LocalDate.now().getYear();
        return operationsService.getFinancialAnalytics(
                scope(projectId), safeYear, periodType, RequestParams.optionalUuid(clinicId, "clinicId"));
    }

    // ------------------------------------------------------------------
    // Requirement 4: Treatment Room Management
    // ------------------------------------------------------------------

    @GetMapping("/treatment-rooms")
    @RequirePermission(Permission.DOCTOR_READ)
    @Operation(summary = "List branch treatment rooms with availability and utilization metrics")
    public List<ClinicOperationsModels.TreatmentRoomRow> listTreatmentRooms(
            @RequestParam(required = false) String clinicId,
            @RequestParam(required = false) String projectId) {
        return operationsService.listTreatmentRooms(
                scope(projectId), RequestParams.optionalUuid(clinicId, "clinicId"));
    }

    @PostMapping("/treatment-rooms")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission(Permission.CLINIC_MANAGE)
    @Operation(summary = "Create a treatment room")
    public ClinicOperationsModels.TreatmentRoomRow createTreatmentRoom(
            @RequestBody ClinicOperationsModels.CreateTreatmentRoomRequest request,
            @RequestParam(required = false) String projectId) {
        String effectiveProject = projectId != null ? projectId : request.projectId();
        return operationsService.createTreatmentRoom(writeScope(effectiveProject), request, caller());
    }

    @PatchMapping("/treatment-rooms/{roomId}")
    @RequirePermission(Permission.CLINIC_MANAGE)
    @Operation(summary = "Update a treatment room")
    public ClinicOperationsModels.TreatmentRoomRow updateTreatmentRoom(
            @PathVariable("roomId") UUID roomId,
            @RequestBody ClinicOperationsModels.CreateTreatmentRoomRequest request,
            @RequestParam(required = false) String projectId) {
        String effectiveProject = projectId != null ? projectId : request.projectId();
        return operationsService.updateTreatmentRoom(writeScope(effectiveProject), roomId, request, caller());
    }

    @DeleteMapping("/treatment-rooms/{roomId}")
    @RequirePermission(Permission.CLINIC_MANAGE)
    @Operation(summary = "Delete a treatment room")
    public boolean deleteTreatmentRoom(
            @PathVariable("roomId") UUID roomId,
            @RequestParam(required = false) String projectId) {
        return operationsService.deleteTreatmentRoom(writeScope(projectId), roomId, caller());
    }

    // ------------------------------------------------------------------
    // Requirement 5: Dental Imaging Integration & External Labs
    // ------------------------------------------------------------------

    @GetMapping("/imaging/labs")
    @RequirePermission(Permission.DOCTOR_READ)
    @Operation(summary = "List clinic preferred external imaging laboratories")
    public List<ClinicOperationsModels.ImagingLabRow> listImagingLabs(
            @RequestParam(required = false) String clinicId,
            @RequestParam(required = false) String projectId) {
        return operationsService.listImagingLabs(
                scope(projectId), RequestParams.optionalUuid(clinicId, "clinicId"));
    }

    @PostMapping("/imaging/labs")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission(Permission.DOCTOR_READ)
    @Operation(summary = "Create a preferred external imaging laboratory")
    public ClinicOperationsModels.ImagingLabRow createImagingLab(
            @RequestBody ClinicOperationsModels.CreateImagingLabRequest request,
            @RequestParam(required = false) String projectId) {
        String effectiveProject = projectId != null ? projectId : request.projectId();
        return operationsService.createImagingLab(writeScope(effectiveProject), request, caller());
    }

    @PatchMapping("/imaging/labs/{labId}")
    @RequirePermission(Permission.DOCTOR_READ)
    @Operation(summary = "Update an external imaging laboratory")
    public ClinicOperationsModels.ImagingLabRow updateImagingLab(
            @PathVariable("labId") UUID labId,
            @RequestBody ClinicOperationsModels.CreateImagingLabRequest request,
            @RequestParam(required = false) String projectId) {
        String effectiveProject = projectId != null ? projectId : request.projectId();
        return operationsService.updateImagingLab(writeScope(effectiveProject), labId, request, caller());
    }

    @DeleteMapping("/imaging/labs/{labId}")
    @RequirePermission(Permission.CLINIC_MANAGE)
    @Operation(summary = "Delete an external imaging laboratory")
    public boolean deleteImagingLab(
            @PathVariable("labId") UUID labId,
            @RequestParam(required = false) String projectId) {
        return operationsService.deleteImagingLab(writeScope(projectId), labId, caller());
    }

    @GetMapping("/imaging/requests")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "List dental imaging requests")
    public List<ClinicOperationsModels.ImagingRequestRow> listImagingRequests(
            @RequestParam(required = false) String patientId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String imagingType,
            @RequestParam(required = false) String projectId) {
        return operationsService.listImagingRequests(
                scope(projectId), RequestParams.optionalUuid(patientId, "patientId"), status, imagingType);
    }

    @GetMapping("/imaging/analytics")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Get dental imaging consumption, costs, revenue and profitability summary")
    public ClinicOperationsModels.ImagingSummaryAnalytics getImagingAnalytics(
            @RequestParam(required = false) String projectId) {
        return operationsService.getImagingAnalytics(scope(projectId));
    }

    @PostMapping("/imaging/requests")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Create a dental imaging request")
    public ClinicOperationsModels.ImagingRequestRow createImagingRequest(
            @RequestBody ClinicOperationsModels.CreateImagingRequest request,
            @RequestParam(required = false) String projectId) {
        String effectiveProject = projectId != null ? projectId : request.projectId();
        return operationsService.createImagingRequest(writeScope(effectiveProject), request, caller());
    }

    @PatchMapping("/imaging/requests/{requestId}")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Update a dental imaging request and sync billing/expenses on completion")
    public ClinicOperationsModels.ImagingRequestRow updateImagingRequest(
            @PathVariable("requestId") UUID requestId,
            @RequestBody ClinicOperationsModels.UpdateImagingRequest request,
            @RequestParam(required = false) String projectId) {
        return operationsService.updateImagingRequest(writeScope(projectId), requestId, request, caller());
    }

    @DeleteMapping("/imaging/requests/{requestId}")
    @RequirePermission(Permission.CLINIC_MANAGE)
    @Operation(summary = "Delete a dental imaging request")
    public boolean deleteImagingRequest(
            @PathVariable("requestId") UUID requestId,
            @RequestParam(required = false) String projectId) {
        return operationsService.deleteImagingRequest(writeScope(projectId), requestId, caller());
    }

    // ------------------------------------------------------------------
    // Scope helpers
    // ------------------------------------------------------------------

    private ClinicScope scope(String projectId) {
        return ClinicScope.forRequestedProject(
                guard, TenantContextHolder.get(), RequestParams.optionalUuid(projectId, "projectId"));
    }

    private ClinicScope writeScope(String projectId) {
        ClinicScope scope = scope(projectId);
        if (scope.isProjectScoped()) {
            return scope;
        }
        var reachable = scope.accessibleProjectIds();
        if (reachable.size() == 1) {
            return ClinicScope.forRequestedProject(guard, TenantContextHolder.get(), reachable.get(0));
        }
        throw new ResourceNotFoundException("Resource not found");
    }

    private static TenantContext caller() {
        return TenantContextHolder.get();
    }
}
