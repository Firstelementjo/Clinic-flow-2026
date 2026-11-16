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
