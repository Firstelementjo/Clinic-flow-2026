package com.firstelement.inbox.web;

import com.firstelement.crm.clinic.ClinicCataloguePage;
import com.firstelement.crm.clinic.ClinicLocationModels;
import com.firstelement.crm.clinic.ClinicLocationModels.ClinicLocation;
import com.firstelement.crm.clinic.ClinicLocationModels.ClinicLocationInput;
import com.firstelement.crm.clinic.ClinicLocationModels.ProcedureInput;
import com.firstelement.crm.clinic.ClinicManagementService;
import com.firstelement.crm.clinic.ClinicModels;
import com.firstelement.crm.error.ResourceNotFoundException;
import com.firstelement.security.TenantContextHolder;
import com.firstelement.security.annotation.RequirePermission;
import com.firstelement.security.permission.Permission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
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
 * The clinic administration writes.
 *
 * <pre>
 *   GET   /api/v1/clinic/locations                  DOCTOR_READ
 *   GET   /api/v1/clinic/locations/{locationId}     DOCTOR_READ
 *   POST  /api/v1/clinic/locations                  CLINIC_MANAGE
 *   PATCH /api/v1/clinic/locations/{locationId}     CLINIC_MANAGE
 *   POST  /api/v1/clinic/procedures                 PROCEDURE_MANAGE
 *   PATCH /api/v1/clinic/procedures/{procedureId}   PROCEDURE_MANAGE
 * </pre>
 *
 * <h2>Why writes are in their own controller</h2>
 *
 * <p>{@link ClinicController} is the bounded read slice, and its class comment
 * records that separating the write path from it was a deliberate deployment
 * decision after the 2026-10-08 cross-tenant breach. Keeping the writes here means
 * that promise stays true by inspection: the read controller still contains no
 * mutation, and this controller contains nothing but the two master-data writes and
 * their reads.
 *
 * <h2>Why PATCH, not PUT</h2>
 *
 * <p>The edit drawer sends the fields it owns, and the row status switch sends only
 * {@code isActive}. PATCH is the verb whose meaning includes "the fields I did not
 * send are unchanged", which is exactly what the screen does. A PUT would require
 * the client to reconstruct every column to flip one switch.
 */
@RestController
@RequestMapping("/api/v1/clinic")
@Tag(name = "Clinic administration",
    description = "Clinic locations and the service catalogue. Reads are roster-adjacent; "
        + "writes require clinic:manage or procedure:manage.")
public class ClinicManagementController {

    private final ClinicManagementService management;

    public ClinicManagementController(ClinicManagementService management) {
        this.management = management;
    }

    // ==================================================================
    // locations
    // ==================================================================

    /** {@code GET /api/v1/clinic/locations} - the Clinics list. */
    @GetMapping("/locations")
    @RequirePermission(Permission.DOCTOR_READ)
    @Operation(summary = "Clinic locations",
        description = "Physical clinics in the caller's server-resolved project scope. "
            + "An optional q matches name, contact, city and assigned officer; an optional "
            + "status is ACTIVE or INACTIVE.")
    public ClinicCataloguePage<ClinicLocation> locations(
            @Parameter(description = "Narrow to one project. A request, not an authorization.")
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status) {
        return ClinicCataloguePage.of(management.locations(
            TenantContextHolder.get(),
            RequestParams.optionalUuid(projectId, "projectId"),
            q,
            status));
    }

    /** {@code GET /api/v1/clinic/locations/{locationId}} - one clinic. */
    @GetMapping("/locations/{locationId}")
    @RequirePermission(Permission.DOCTOR_READ)
    @Operation(summary = "One clinic location",
        description = "Answered with 404 for a nonexistent location and for one outside the "
            + "caller's scope, identically.")
    public ClinicLocation location(@PathVariable UUID locationId) {
        return management.location(TenantContextHolder.get(), locationId)
            .orElseThrow(() -> new ResourceNotFoundException("Resource"));
    }

    /** {@code POST /api/v1/clinic/locations} - create a clinic. */
    @PostMapping("/locations")
    @RequirePermission(Permission.CLINIC_MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a clinic location",
        description = "Creates a physical clinic in the named project. Name is required; "
            + "slot minutes default to 30; a brand image is stored as a URL or data URL.")
    public ClinicLocation createLocation(
            @RequestBody ClinicLocationInput payload,
            @RequestParam(required = false) String projectId) {
        return management.createLocation(
            TenantContextHolder.get(),
            RequestParams.optionalUuid(projectId, "projectId"),
            payload);
    }

    /** {@code PATCH /api/v1/clinic/locations/{locationId}} - edit a clinic. */
    @PatchMapping("/locations/{locationId}")
    @RequirePermission(Permission.CLINIC_MANAGE)
    @Operation(summary = "Edit a clinic location",
        description = "Merges the fields present in the body. An absent or null field is "
            + "unchanged, so the row status switch sends only isActive. Send "
            + "clearFrontDeskOfficer=true to remove the assigned officer.")
    public ClinicLocation updateLocation(
            @PathVariable UUID locationId,
            @RequestBody ClinicLocationInput payload,
            @RequestParam(required = false) String projectId) {
        return management.updateLocation(
            TenantContextHolder.get(),
            RequestParams.optionalUuid(projectId, "projectId"),
            locationId,
            payload);
    }

    /** {@code DELETE /api/v1/clinic/locations/{locationId}} - delete a clinic location. */
    @DeleteMapping("/locations/{locationId}")
    @RequirePermission(Permission.CLINIC_MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a clinic location",
        description = "Deletes a physical clinic location, unlinking any assigned doctors, appointments and procedures.")
    public void deleteLocation(
            @PathVariable UUID locationId,
            @RequestParam(required = false) String projectId) {
        management.deleteLocation(
            TenantContextHolder.get(),
            RequestParams.optionalUuid(projectId, "projectId"),
            locationId);
    }

    // ==================================================================
    // service catalogue
    // ==================================================================

    /** {@code POST /api/v1/clinic/procedures} - create a service. */
    @PostMapping("/procedures")
    @RequirePermission(Permission.PROCEDURE_MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a service",
        description = "Creates a service-catalogue entry in the named project. Name and "
            + "category are required; duration defaults to 30 minutes; price and discount "
            + "are optional, and an absent price stays 'not priced' rather than becoming zero.")
    public ClinicModels.Procedure createProcedure(
            @RequestBody ProcedureInput payload,
            @RequestParam(required = false) String projectId) {
        return management.createProcedure(
            TenantContextHolder.get(),
            RequestParams.optionalUuid(projectId, "projectId"),
            payload);
    }

    /** {@code PATCH /api/v1/clinic/procedures/{procedureId}} - edit a service. */
    @PatchMapping("/procedures/{procedureId}")
    @RequirePermission(Permission.PROCEDURE_MANAGE)
    @Operation(summary = "Edit a service",
        description = "Merges the fields present in the body. An absent or null field is "
            + "unchanged, so the row status switch sends only isActive.")
    public ClinicModels.Procedure updateProcedure(
            @PathVariable UUID procedureId,
            @RequestBody ProcedureInput payload,
            @RequestParam(required = false) String projectId) {
        return management.updateProcedure(
            TenantContextHolder.get(),
            RequestParams.optionalUuid(projectId, "projectId"),
            procedureId,
            payload);
    }
}
