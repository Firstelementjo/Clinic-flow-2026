package com.firstelement.inbox.web;

import com.firstelement.crm.clinic.ClinicScope;
import com.firstelement.crm.clinic.treatment.TreatmentPlanModels;
import com.firstelement.crm.clinic.treatment.TreatmentPlanService;
import com.firstelement.crm.error.ResourceNotFoundException;
import com.firstelement.security.TenantContext;
import com.firstelement.security.TenantContextHolder;
import com.firstelement.security.annotation.RequirePermission;
import com.firstelement.security.permission.Permission;
import com.firstelement.security.query.TenantQueryGuard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * Controller for Dental Clinic Treatment Plans (TRT-01 to TRT-05, Slice S5).
 *
 * <p>All operations are securely multi-tenant scoped under the patient's clinic.
 */
@RestController
@RequestMapping("/api/v1/clinic/patients/{patientId}/treatment-plans")
public class ClinicTreatmentPlanController {

    private final TreatmentPlanService treatmentPlans;
    private final TenantQueryGuard guard;

    public ClinicTreatmentPlanController(TreatmentPlanService treatmentPlans, TenantQueryGuard guard) {
        this.treatmentPlans = treatmentPlans;
        this.guard = guard;
    }

    /**
     * Lists all treatment plans for a patient.
     */
    @GetMapping
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "List treatment plans for a patient",
            description = "Returns all plans with financial sums, versioning, and consent status.")
    public List<TreatmentPlanModels.TreatmentPlanHeader> listPlans(
            @PathVariable UUID patientId,
            @Parameter(description = "Optional project scoping")
            @RequestParam(required = false) String projectId) {
        return treatmentPlans.listPlansForPatient(scope(projectId), patientId);
    }

    /**
     * Gets a single treatment plan with its ordered items.
     */
    @GetMapping("/{planId}")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Get treatment plan details",
            description = "Returns the plan header and all sequence-ordered procedure items.")
    public TreatmentPlanModels.TreatmentPlanDetail getPlan(
            @PathVariable UUID patientId,
            @PathVariable UUID planId,
            @RequestParam(required = false) String projectId) {
        return treatmentPlans.getPlanDetail(scope(projectId), planId);
    }

    /**
     * Creates a new treatment plan.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Create a new treatment plan")
    public Map<String, Object> createPlan(
            @PathVariable UUID patientId,
            @RequestBody TreatmentPlanModels.CreatePlanRequest request) {
        ClinicScope writeScope = writeScope(request.projectId());
        UUID id = treatmentPlans.createPlan(writeScope, patientId, request);
        return Map.of("id", id, "status", "DRAFT");
    }

    /**
     * Forks a new version of an existing treatment plan.
     */
    @PostMapping("/{planId}/fork")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Fork a new version of a treatment plan",
            description = "Creates version N+1 in DRAFT status, copying all items from the parent plan.")
    public Map<String, Object> forkPlan(
            @PathVariable UUID patientId,
            @PathVariable UUID planId,
            @RequestParam(required = false) String projectId,
            @RequestBody TreatmentPlanModels.ForkPlanVersionRequest request) {
        UUID newPlanId = treatmentPlans.forkNewVersion(writeScope(projectId), planId, request);
        return Map.of("id", newPlanId, "status", "DRAFT", "parentPlanId", planId);
    }

    /**
     * Approves and activates a treatment plan with patient consent.
     */
    @PostMapping("/{planId}/approve")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Approve and activate a treatment plan")
    public Map<String, Object> approvePlan(
            @PathVariable UUID patientId,
            @PathVariable UUID planId,
            @RequestParam(required = false) String projectId,
            @RequestBody TreatmentPlanModels.ApprovePlanRequest request) {
        TenantContext ctx = TenantContextHolder.get();
        treatmentPlans.approvePlan(writeScope(projectId), planId, ctx.userId(), request);
        return Map.of("planId", planId, "status", "ACTIVE", "approved", true);
    }

    /**
     * Cancels a treatment plan with a recorded reason.
     */
    @PostMapping("/{planId}/cancel")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Cancel a treatment plan")
    public Map<String, Object> cancelPlan(
            @PathVariable UUID patientId,
            @PathVariable UUID planId,
            @RequestParam(required = false) String projectId,
            @RequestBody TreatmentPlanModels.CancelPlanRequest request) {
        treatmentPlans.cancelPlan(writeScope(projectId), planId, request);
        return Map.of("planId", planId, "status", "CANCELLED");
    }

    /**
     * Updates plan status (DRAFT, PRESENTED, ACTIVE, COMPLETED, CANCELLED).
     */
    @PatchMapping("/{planId}/status")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Update treatment plan status")
    public Map<String, Object> updatePlanStatus(
            @PathVariable UUID patientId,
            @PathVariable UUID planId,
            @RequestParam(required = false) String projectId,
            @RequestBody TreatmentPlanModels.UpdatePlanStatusRequest request) {
        treatmentPlans.updatePlanStatus(writeScope(projectId), planId, request);
        return Map.of("planId", planId, "status", request.status());
    }

    /**
     * Records patient consent (TRT-04).
     */
    @PostMapping("/{planId}/consent")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Record patient consent",
            description = "Captures consent flag, registrar user identity, and notes.")
    public Map<String, Object> recordConsent(
            @PathVariable UUID patientId,
            @PathVariable UUID planId,
            @RequestParam(required = false) String projectId,
            @RequestBody TreatmentPlanModels.RecordConsentRequest request) {
        TenantContext ctx = TenantContextHolder.get();
        UUID registrarUserId = ctx.userId();
        treatmentPlans.recordConsent(writeScope(projectId), planId, registrarUserId, request);
        return Map.of("planId", planId, "patientConsent", request.patientConsent());
    }

    /**
     * Adds a procedure item to the treatment plan (TRT-01, TRT-02, TRT-03, multi-visit, stage).
     */
    @PostMapping("/{planId}/items")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Add procedure item to treatment plan")
    public Map<String, Object> addItem(
            @PathVariable UUID patientId,
            @PathVariable UUID planId,
            @RequestParam(required = false) String projectId,
            @RequestBody TreatmentPlanModels.CreatePlanItemRequest request) {
        UUID itemId = treatmentPlans.addItem(writeScope(projectId), planId, request);
        return Map.of("id", itemId, "planId", planId);
    }

    /**
     * Updates an existing procedure item.
     */
    @PatchMapping("/{planId}/items/{itemId}")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Update procedure item in treatment plan")
    public Map<String, Object> updateItem(
            @PathVariable UUID patientId,
            @PathVariable UUID planId,
            @PathVariable UUID itemId,
            @RequestParam(required = false) String projectId,
            @RequestBody TreatmentPlanModels.UpdatePlanItemRequest request) {
        treatmentPlans.updateItem(writeScope(projectId), planId, itemId, request);
        return Map.of("id", itemId, "updated", true);
    }

    /**
     * Deletes a procedure item from the plan.
     */
    @DeleteMapping("/{planId}/items/{itemId}")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Delete procedure item from plan")
    public Map<String, Object> deleteItem(
            @PathVariable UUID patientId,
            @PathVariable UUID planId,
            @PathVariable UUID itemId,
            @RequestParam(required = false) String projectId) {
        treatmentPlans.deleteItem(writeScope(projectId), planId, itemId);
        return Map.of("id", itemId, "deleted", true);
    }

    /**
     * Deletes a treatment plan.
     */
    @DeleteMapping("/{planId}")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Delete a treatment plan")
    public Map<String, Object> deletePlan(
            @PathVariable UUID patientId,
            @PathVariable UUID planId,
            @RequestParam(required = false) String projectId) {
        treatmentPlans.deletePlan(writeScope(projectId), planId);
        return Map.of("id", planId, "deleted", true);
    }

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
}
