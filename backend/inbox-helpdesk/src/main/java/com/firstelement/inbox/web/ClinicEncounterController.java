package com.firstelement.inbox.web;

import com.firstelement.crm.clinic.ClinicScope;
import com.firstelement.crm.clinic.encounter.EncounterModels;
import com.firstelement.crm.clinic.encounter.EncounterService;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
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
 * Controller for Dental Clinical Encounters & Prescriptions (CLN-01 to CLN-09).
 *
 * <p>Enforces multi-tenancy boundaries and patient/clinician scoping.
 */
@RestController
@RequestMapping("/api/v1/clinic")
public class ClinicEncounterController {

    private final EncounterService encounterService;
    private final TenantQueryGuard guard;
    private final ClinicOperationsService operationsService;

    public ClinicEncounterController(EncounterService encounterService, TenantQueryGuard guard) {
        this(encounterService, guard, null);
    }

    @Autowired
    public ClinicEncounterController(
            EncounterService encounterService,
            TenantQueryGuard guard,
            ClinicOperationsService operationsService) {
        this.encounterService = encounterService;
        this.guard = guard;
        this.operationsService = operationsService;
    }

    /**
     * Lists all clinical encounters for a patient.
     */
    @GetMapping("/patients/{patientId}/encounters")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "List patient clinical encounters",
            description = "Returns visits history. Medical notes are masked for front-desk staff (CLN-08).")
    public List<EncounterModels.EncounterHeader> listEncounters(
            @PathVariable("patientId") String patientId,
            @Parameter(description = "Optional project scoping")
            @RequestParam(required = false) String projectId) {
        TenantContext caller = TenantContextHolder.get();
        return encounterService.listEncountersForPatient(
                scope(projectId), RequestParams.requiredUuid(patientId, "patientId"), caller);
    }

    /**
     * Retrieves full detail of a patient encounter including prescriptions.
     */
    @GetMapping("/patients/{patientId}/encounters/{encounterId}")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Get encounter detail by patient and encounter ID")
    public EncounterModels.EncounterDetail getEncounterDetail(
            @PathVariable("patientId") String patientId,
            @PathVariable("encounterId") String encounterId,
            @RequestParam(required = false) String projectId) {
        TenantContext caller = TenantContextHolder.get();
        UUID patId = RequestParams.requiredUuid(patientId, "patientId");
        UUID encId = RequestParams.requiredUuid(encounterId, "encounterId");
        EncounterModels.EncounterDetail detail = encounterService.getEncounterDetail(
                scope(projectId), encId, caller);
        if (!patId.equals(detail.encounter().patientId())) {
            throw new ResourceNotFoundException("Resource not found");
        }
        return detail;
    }

    /**
     * Direct encounter lookup by ID (CLN-01 / gap resolver).
     */
    @GetMapping("/encounters/{encounterId}")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Direct encounter lookup by ID")
    public EncounterModels.EncounterDetail getEncounterById(
            @PathVariable("encounterId") String encounterId,
            @RequestParam(required = false) String projectId) {
        TenantContext caller = TenantContextHolder.get();
        return encounterService.getEncounterDetail(
                scope(projectId), RequestParams.requiredUuid(encounterId, "encounterId"), caller);
    }

    /**
     * Creates a new clinical encounter visit (CLN-01 to CLN-05).
     */
    @PostMapping("/patients/{patientId}/encounters")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Record a new clinical encounter visit",
            description = "Clinicians only (CLN-07). Validates FDI notation (CLN-03) and updates linked treatment item (CLN-05).")
    public Map<String, Object> createEncounter(
            @PathVariable("patientId") String patientId,
            @RequestParam(required = false) String projectId,
            @RequestBody EncounterModels.CreateEncounterRequest request) {
        TenantContext caller = TenantContextHolder.get();
        String effectiveProj = request.projectId() != null ? request.projectId() : projectId;
        ClinicScope writeScope = writeScope(effectiveProj);
        UUID id = encounterService.createEncounter(
                writeScope, RequestParams.requiredUuid(patientId, "patientId"), request, caller);
        String effectiveStatus = request.status() != null ? request.status().toUpperCase() : "COMPLETED";
        List<ClinicOperationsModels.InventoryStockWarning> warnings = List.of();
        if ("COMPLETED".equals(effectiveStatus) && operationsService != null) {
            var result = operationsService.deductMaterialsForCompletedEncounter(
                    writeScope,
                    id,
                    request.appointmentId(),
                    request.treatmentPlanItemId(),
                    request.performedProcedures(),
                    caller != null ? caller.userId() : null);
            warnings = result.warnings();
        }
        return Map.of("id", id, "created", true, "inventoryWarnings", warnings);
    }

    /**
     * Updates diagnosis and clinical findings with clinician attribution (CLN-02).
     */
    @PatchMapping("/patients/{patientId}/encounters/{encounterId}/diagnosis")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Update clinical diagnosis and notes",
            description = "Permitted for doctor/clinician only. Records timestamp and updating doctor (CLN-02).")
    public Map<String, Object> updateDiagnosis(
            @PathVariable("patientId") String patientId,
            @PathVariable("encounterId") String encounterId,
            @RequestParam(required = false) String projectId,
            @RequestBody EncounterModels.UpdateDiagnosisRequest request) {
        TenantContext caller = TenantContextHolder.get();
        UUID patId = RequestParams.requiredUuid(patientId, "patientId");
        UUID encId = RequestParams.requiredUuid(encounterId, "encounterId");
        ClinicScope s = writeScope(projectId);
        requireEncounterBelongsToPatient(s, patId, encId, caller);
        try {
            encounterService.updateDiagnosis(s, encId, request, caller);
        } catch (IllegalStateException ex) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.CONFLICT, ex.getMessage(), ex);
        }
        return Map.of("id", encId, "updated", true);
    }

    /**
     * Issues a prescription for an encounter (CLN-06).
     */
    @PostMapping("/patients/{patientId}/encounters/{encounterId}/prescriptions")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Issue a prescription item",
            description = "Attaches drug, dosage, frequency, and instructions to the encounter (CLN-06).")
    public Map<String, Object> addPrescription(
            @PathVariable("patientId") String patientId,
            @PathVariable("encounterId") String encounterId,
            @RequestParam(required = false) String projectId,
            @RequestBody EncounterModels.CreatePrescriptionRequest request) {
        TenantContext caller = TenantContextHolder.get();
        UUID encId = RequestParams.requiredUuid(encounterId, "encounterId");
        UUID patId = RequestParams.requiredUuid(patientId, "patientId");
        ClinicScope s = writeScope(projectId);
        requireEncounterBelongsToPatient(s, patId, encId, caller);
        UUID rxId = encounterService.addPrescription(s, encId, patId, request, caller);
        return Map.of("id", rxId, "created", true);
    }

    /**
     * Deletes a prescription.
     */
    @DeleteMapping("/patients/{patientId}/encounters/{encounterId}/prescriptions/{prescriptionId}")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Delete a prescription item")
    public Map<String, Object> deletePrescription(
            @PathVariable("patientId") String patientId,
            @PathVariable("encounterId") String encounterId,
            @PathVariable("prescriptionId") String prescriptionId,
            @RequestParam(required = false) String projectId) {
        TenantContext caller = TenantContextHolder.get();
        UUID patId = RequestParams.requiredUuid(patientId, "patientId");
        UUID encId = RequestParams.requiredUuid(encounterId, "encounterId");
        UUID rxId = RequestParams.requiredUuid(prescriptionId, "prescriptionId");
        ClinicScope s = writeScope(projectId);
        requireEncounterBelongsToPatient(s, patId, encId, caller);
        encounterService.deletePrescription(s, rxId, caller);
        return Map.of("id", rxId, "deleted", true);
    }

    /**
     * Digitally signs a clinical encounter note (Slice S3).
     * Once signed, the note becomes immutable and cannot be edited.
     */
    @PostMapping("/encounters/{encounterId}/sign")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Digitally sign an encounter note",
            description = "Clinicians only. Once signed, the clinical note is immutable and cannot be modified.")
    public Map<String, Object> signEncounter(
            @PathVariable("encounterId") String encounterId,
            @RequestParam(required = false) String projectId) {
        TenantContext caller = TenantContextHolder.get();
        UUID encId = RequestParams.requiredUuid(encounterId, "encounterId");
        String signatureHash = encounterService.signEncounter(writeScope(projectId), encId, caller);
        return Map.of("id", encId, "signed", true, "signatureHash", signatureHash);
    }

    @PostMapping("/patients/{patientId}/encounters/{encounterId}/sign")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Digitally sign a patient encounter note")
    public Map<String, Object> signPatientEncounter(
            @PathVariable("patientId") String patientId,
            @PathVariable("encounterId") String encounterId,
            @RequestParam(required = false) String projectId) {
        TenantContext caller = TenantContextHolder.get();
        UUID patId = RequestParams.requiredUuid(patientId, "patientId");
        UUID encId = RequestParams.requiredUuid(encounterId, "encounterId");
        requireEncounterBelongsToPatient(scope(projectId), patId, encId, caller);
        return signEncounter(encounterId, projectId);
    }

    /**
     * Appends an immutable amendment to a signed clinical note (Slice S3).
     */
    @PostMapping("/encounters/{encounterId}/amendments")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Append an immutable amendment to a signed encounter",
            description = "Clinicians only. Can only be added after an encounter is signed. Includes audit reason.")
    public Map<String, Object> addAmendment(
            @PathVariable("encounterId") String encounterId,
            @RequestParam(required = false) String projectId,
            @RequestBody EncounterModels.CreateAmendmentRequest request) {
        TenantContext caller = TenantContextHolder.get();
        UUID encId = RequestParams.requiredUuid(encounterId, "encounterId");
        UUID amendmentId = encounterService.addAmendment(writeScope(projectId), encId, request, caller);
        return Map.of("id", amendmentId, "encounterId", encId, "created", true);
    }

    @PostMapping("/patients/{patientId}/encounters/{encounterId}/amendments")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Append an immutable amendment to a signed patient encounter")
    public Map<String, Object> addPatientAmendment(
            @PathVariable("patientId") String patientId,
            @PathVariable("encounterId") String encounterId,
            @RequestParam(required = false) String projectId,
            @RequestBody EncounterModels.CreateAmendmentRequest request) {
        TenantContext caller = TenantContextHolder.get();
        UUID patId = RequestParams.requiredUuid(patientId, "patientId");
        UUID encId = RequestParams.requiredUuid(encounterId, "encounterId");
        requireEncounterBelongsToPatient(scope(projectId), patId, encId, caller);
        return addAmendment(encounterId, projectId, request);
    }

    /**
     * Retrieves patient medical history and systemic alert flags (Slice S3).
     */
    @GetMapping("/patients/{patientId}/medical-history")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Get patient medical history and alert flags",
            description = "Returns allergies, systemic conditions, active medications, and bleeding/cardiac/diabetes alert flags.")
    public EncounterModels.MedicalHistoryRow getMedicalHistory(
            @PathVariable("patientId") String patientId,
            @RequestParam(required = false) String projectId) {
        TenantContext caller = TenantContextHolder.get();
        UUID patId = RequestParams.requiredUuid(patientId, "patientId");
        return encounterService.getMedicalHistory(scope(projectId), patId, caller)
                .orElse(null);
    }

    /**
     * Saves or updates patient medical history (Slice S3).
     */
    @PutMapping("/patients/{patientId}/medical-history")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Save or update patient medical history",
            description = "Clinicians only. Upserts medical history and systemic condition alerts.")
    public Map<String, Object> saveMedicalHistory(
            @PathVariable("patientId") String patientId,
            @RequestParam(required = false) String projectId,
            @RequestBody EncounterModels.SaveMedicalHistoryRequest request) {
        TenantContext caller = TenantContextHolder.get();
        UUID patId = RequestParams.requiredUuid(patientId, "patientId");
        UUID id = encounterService.saveMedicalHistory(writeScope(projectId), patId, request, caller);
        return Map.of("id", id, "patientId", patId, "saved", true);
    }

    /**
     * Lists all grouped clinic prescriptions for doctors and clinic admins.
     */
    @GetMapping("/prescriptions")
    @RequirePermission(Permission.DOCTOR_READ)
    @Operation(summary = "List all clinic prescriptions")
    public List<EncounterModels.ClinicPrescriptionSheet> listClinicPrescriptions(
            @RequestParam(required = false) String projectId) {
        TenantContext caller = TenantContextHolder.get();
        return encounterService.listClinicPrescriptions(scope(projectId), caller);
    }

    /**
     * Retrieves a single prescription sheet by ID.
     */
    @GetMapping("/prescriptions/{id}")
    @RequirePermission(Permission.DOCTOR_READ)
    @Operation(summary = "Get prescription sheet detail by ID")
    public EncounterModels.ClinicPrescriptionSheet getClinicPrescription(
            @PathVariable("id") String id,
            @RequestParam(required = false) String projectId) {
        TenantContext caller = TenantContextHolder.get();
        return encounterService.getClinicPrescription(
                scope(projectId), RequestParams.requiredUuid(id, "id"), caller);
    }

    /**
     * Issues a new standalone multi-item prescription.
     */
    @PostMapping("/prescriptions")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission(Permission.DOCTOR_READ)
    @Operation(summary = "Issue a new clinic prescription")
    public EncounterModels.ClinicPrescriptionSheet createStandalonePrescription(
            @RequestBody EncounterModels.CreateStandalonePrescriptionRequest request) {
        TenantContext caller = TenantContextHolder.get();
        return encounterService.createStandalonePrescription(
                writeScope(request.projectId()), request, caller);
    }

    /**
     * Deletes a grouped prescription sheet.
     */
    @DeleteMapping("/prescriptions/{id}")
    @RequirePermission(Permission.DOCTOR_READ)
    @Operation(summary = "Delete a clinic prescription sheet")
    public Map<String, Object> deletePrescriptionSheet(
            @PathVariable("id") String id,
            @RequestParam(required = false) String projectId) {
        TenantContext caller = TenantContextHolder.get();
        UUID rxSheetId = RequestParams.requiredUuid(id, "id");
        encounterService.deletePrescriptionSheet(scope(projectId), rxSheetId, caller);
        return Map.of("id", rxSheetId, "deleted", true);
    }

    /**
     * Lists saved medication presets and catalog for the clinic.
     */
    @GetMapping("/settings/medications")
    @RequirePermission(Permission.DOCTOR_READ)
    @Operation(summary = "List clinic medication presets")
    public List<EncounterModels.ClinicMedicationPreset> listMedicationPresets(
            @RequestParam(required = false) String projectId) {
        TenantContext caller = TenantContextHolder.get();
        return encounterService.listMedicationPresets(scope(projectId), caller);
    }

    /**
     * Adds a new medication preset to the clinic catalog.
     */
    @PostMapping("/settings/medications")
    @RequirePermission(Permission.DOCTOR_READ)
    @Operation(summary = "Add a clinic medication preset")
    public EncounterModels.ClinicMedicationPreset createMedicationPreset(
            @RequestBody EncounterModels.CreateMedicationPresetRequest request) {
        TenantContext caller = TenantContextHolder.get();
        return encounterService.createMedicationPreset(
                writeScope(request.projectId()), request, caller);
    }

    /**
     * Deletes a medication preset from the clinic catalog.
     */
    @DeleteMapping("/settings/medications/{id}")
    @RequirePermission(Permission.DOCTOR_READ)
    @Operation(summary = "Delete a clinic medication preset")
    public Map<String, Object> deleteMedicationPreset(
            @PathVariable("id") String id,
            @RequestParam(required = false) String projectId) {
        TenantContext caller = TenantContextHolder.get();
        UUID medId = RequestParams.requiredUuid(id, "id");
        encounterService.deleteMedicationPreset(scope(projectId), medId, caller);
        return Map.of("id", medId, "deleted", true);
    }

    /**
     * Lists quick diagnosis shortcuts for the clinic.
     */
    @GetMapping("/settings/diagnoses")
    @RequirePermission(Permission.DOCTOR_READ)
    @Operation(summary = "List clinic diagnosis shortcuts")
    public List<EncounterModels.ClinicDiagnosisPreset> listDiagnosisPresets(
            @RequestParam(required = false) String projectId) {
        TenantContext caller = TenantContextHolder.get();
        return encounterService.listDiagnosisPresets(scope(projectId), caller);
    }

    /**
     * Adds a new diagnosis shortcut to the clinic presets.
     */
    @PostMapping("/settings/diagnoses")
    @RequirePermission(Permission.DOCTOR_READ)
    @Operation(summary = "Add a clinic diagnosis shortcut")
    public EncounterModels.ClinicDiagnosisPreset createDiagnosisPreset(
            @RequestBody EncounterModels.CreateDiagnosisPresetRequest request) {
        TenantContext caller = TenantContextHolder.get();
        return encounterService.createDiagnosisPreset(
                writeScope(request.projectId()), request, caller);
    }

    /**
     * Deletes a diagnosis shortcut.
     */
    @DeleteMapping("/settings/diagnoses/{id}")
    @RequirePermission(Permission.DOCTOR_READ)
    @Operation(summary = "Delete a clinic diagnosis shortcut")
    public Map<String, Object> deleteDiagnosisPreset(
            @PathVariable("id") String id,
            @RequestParam(required = false) String projectId) {
        TenantContext caller = TenantContextHolder.get();
        UUID diagId = RequestParams.requiredUuid(id, "id");
        encounterService.deleteDiagnosisPreset(scope(projectId), diagId, caller);
        return Map.of("id", diagId, "deleted", true);
    }

    /**
     * Retrieves customizable clinic letterhead settings for prescription printing.
     */
    @GetMapping("/settings/letterhead")
    @RequirePermission(Permission.DOCTOR_READ)
    @Operation(summary = "Get clinic letterhead settings")
    public EncounterModels.ClinicLetterheadSettings getLetterheadSettings(
            @RequestParam(required = false) String projectId) {
        TenantContext caller = TenantContextHolder.get();
        return encounterService.getLetterheadSettings(scope(projectId), caller);
    }

    /**
     * Updates customizable clinic letterhead settings for prescription printing.
     */
    @PutMapping("/settings/letterhead")
    @RequirePermission(Permission.DOCTOR_READ)
    @Operation(summary = "Update clinic letterhead settings")
    public EncounterModels.ClinicLetterheadSettings updateLetterheadSettings(
            @RequestBody EncounterModels.UpdateLetterheadRequest request) {
        TenantContext caller = TenantContextHolder.get();
        return encounterService.updateLetterheadSettings(
                writeScope(request.projectId()), request, caller);
    }

    // ------------------------------------------------------------------
    // Dental Labs (Prosthetic & Lab Orders)
    // ------------------------------------------------------------------

    @GetMapping("/lab-orders")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "List dental lab orders")
    public List<EncounterModels.LabOrderRow> listLabOrders(
            @RequestParam(required = false) String patientId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String projectId) {
        return encounterService.listLabOrders(
                scope(projectId),
                RequestParams.optionalUuid(patientId, "patientId"),
                status);
    }

    @PostMapping("/lab-orders")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Create a dental lab order")
    public EncounterModels.LabOrderRow createLabOrder(
            @RequestBody EncounterModels.CreateLabOrderRequest request,
            @RequestParam(required = false) String projectId) {
        TenantContext caller = TenantContextHolder.get();
        String effectiveProject = projectId != null ? projectId : request.projectId();
        return encounterService.createLabOrder(writeScope(effectiveProject), request, caller);
    }

    @PatchMapping("/lab-orders/{orderId}/status")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Update dental lab order status")
    public EncounterModels.LabOrderRow updateLabOrderStatus(
            @PathVariable("orderId") UUID orderId,
            @RequestBody EncounterModels.UpdateLabOrderStatusRequest request,
            @RequestParam(required = false) String projectId) {
        return encounterService.updateLabOrderStatus(writeScope(projectId), orderId, request);
    }

    @DeleteMapping("/lab-orders/{orderId}")
    @RequirePermission(Permission.CLINIC_MANAGE)
    @Operation(summary = "Delete a dental lab order")
    public boolean deleteLabOrder(
            @PathVariable("orderId") UUID orderId,
            @RequestParam(required = false) String projectId) {
        return encounterService.deleteLabOrder(writeScope(projectId), orderId);
    }

    private void requireEncounterBelongsToPatient(
            ClinicScope scope, UUID patientId, UUID encounterId, TenantContext caller) {
        EncounterModels.EncounterDetail detail = encounterService.getEncounterDetail(scope, encounterId, caller);
        if (!patientId.equals(detail.encounter().patientId())) {
            throw new ResourceNotFoundException("Resource not found");
        }
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

