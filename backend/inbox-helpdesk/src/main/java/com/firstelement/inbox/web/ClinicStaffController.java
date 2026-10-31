package com.firstelement.inbox.web;

import com.firstelement.crm.clinic.ClinicCataloguePage;
import com.firstelement.crm.clinic.ClinicDoctorModels.DoctorProfile;
import com.firstelement.crm.clinic.ClinicDoctorModels.DoctorProfileInput;
import com.firstelement.crm.clinic.ClinicStaffService;
import com.firstelement.crm.clinic.FrontDeskModels.FrontDeskOfficer;
import com.firstelement.crm.clinic.FrontDeskModels.FrontDeskOfficerInput;
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
 * Doctor-profile and front-desk administration.
 *
 * <pre>
 *   GET    /api/v1/clinic/doctor-profiles                  DOCTOR_READ_MANAGEMENT
 *   GET    /api/v1/clinic/doctor-profiles/{doctorId}       DOCTOR_READ_MANAGEMENT
 *   POST   /api/v1/clinic/doctor-profiles                  DOCTOR_MANAGE
 *   PATCH  /api/v1/clinic/doctor-profiles/{doctorId}       DOCTOR_MANAGE
 *   DELETE /api/v1/clinic/doctor-profiles/{doctorId}       DOCTOR_MANAGE
 *
 *   GET    /api/v1/clinic/front-desk                       FRONT_DESK_READ
 *   GET    /api/v1/clinic/front-desk/{userId}              FRONT_DESK_READ
 *   POST   /api/v1/clinic/front-desk                       FRONT_DESK_MANAGE
 *   PATCH  /api/v1/clinic/front-desk/{userId}              FRONT_DESK_MANAGE
 *   DELETE /api/v1/clinic/front-desk/{userId}              FRONT_DESK_MANAGE
 * </pre>
 *
 * <h2>Why this is not on {@link ClinicController}</h2>
 *
 * <p>{@code ClinicController} is the roster read. Its class comment records that it
 * carries no mutation and no management contact fields. These routes disclose
 * clinician email/phone and provision accounts, so they live behind their own
 * permissions in a controller whose whole surface is that management read/write.
 *
 * <h2>Why the doctor route is {@code doctor-profiles}</h2>
 *
 * <p>{@code GET /api/v1/clinic/doctors} already exists as the booking roster and is
 * contractually restricted to name, specialty and working hours. Reusing the path
 * for the management projection would either change what existing clients receive or
 * make the permission on one URL depend on a query parameter. A distinct path keeps
 * both contracts true at the same time.
 */
@RestController
@RequestMapping("/api/v1/clinic")
@Tag(name = "Clinic staff administration",
    description = "Doctor profiles and front-desk accounts. The doctor list discloses "
        + "contact details, so it requires doctor:read-management rather than doctor:read.")
public class ClinicStaffController {

    private final ClinicStaffService staff;

    public ClinicStaffController(ClinicStaffService staff) {
        this.staff = staff;
    }

    // ==================================================================
    // doctor profiles
    // ==================================================================

    /** {@code GET /api/v1/clinic/doctor-profiles} - the Doctors list. */
    @GetMapping("/doctor-profiles")
    @RequirePermission(Permission.DOCTOR_READ_MANAGEMENT)
    @Operation(summary = "Doctor profiles",
        description = "Non-deleted doctors in the caller's server-resolved project scope. "
            + "An optional q matches name, email, phone and specialty; an optional status is "
            + "ACTIVE or INACTIVE.")
    public ClinicCataloguePage<DoctorProfile> doctorProfiles(
            @Parameter(description = "Narrow to one project. A request, not an authorization.")
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status) {
        return ClinicCataloguePage.of(staff.doctorProfiles(
            TenantContextHolder.get(),
            RequestParams.optionalUuid(projectId, "projectId"),
            q,
            status));
    }

    /** {@code GET /api/v1/clinic/doctor-profiles/{doctorId}} - one doctor. */
    @GetMapping("/doctor-profiles/{doctorId}")
    @RequirePermission(Permission.DOCTOR_READ_MANAGEMENT)
    @Operation(summary = "One doctor profile",
        description = "Includes the qualification rows the edit drawer renders. Answered "
            + "with 404 for a nonexistent doctor and for one outside the caller's scope, "
            + "identically.")
    public DoctorProfile doctorProfile(
            @PathVariable UUID doctorId,
            @RequestParam(required = false) String projectId) {
        return staff.doctorProfile(
                TenantContextHolder.get(),
                RequestParams.optionalUuid(projectId, "projectId"),
                doctorId)
            .orElseThrow(() -> new ResourceNotFoundException("Resource"));
    }

    /** {@code POST /api/v1/clinic/doctor-profiles} - create a doctor. */
    @PostMapping("/doctor-profiles")
    @RequirePermission(Permission.DOCTOR_MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a doctor profile",
        description = "Creates a roster doctor in the named project. Name and email are "
            + "required; clinic and service must be in the same project. The profile has no "
            + "password: doctors are roster rows, not sign-in accounts in this runtime.")
    public DoctorProfile createDoctor(
            @RequestBody DoctorProfileInput payload,
            @RequestParam(required = false) String projectId) {
        return staff.createDoctor(
            TenantContextHolder.get(),
            RequestParams.optionalUuid(projectId, "projectId"),
            payload);
    }

    /** {@code PATCH /api/v1/clinic/doctor-profiles/{doctorId}} - edit a doctor. */
    @PatchMapping("/doctor-profiles/{doctorId}")
    @RequirePermission(Permission.DOCTOR_MANAGE)
    @Operation(summary = "Edit a doctor profile",
        description = "Merges the fields present in the body. An absent or null field is "
            + "unchanged, so the row status switch sends only isActive. A null qualifications "
            + "list leaves the rows alone; an empty list removes them.")
    public DoctorProfile updateDoctor(
            @PathVariable UUID doctorId,
            @RequestBody DoctorProfileInput payload,
            @RequestParam(required = false) String projectId) {
        return staff.updateDoctor(
            TenantContextHolder.get(),
            RequestParams.optionalUuid(projectId, "projectId"),
            doctorId,
            payload);
    }

    /** {@code DELETE /api/v1/clinic/doctor-profiles/{doctorId}} - retire a doctor. */
    @DeleteMapping("/doctor-profiles/{doctorId}")
    @RequirePermission(Permission.DOCTOR_MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Retire a doctor profile",
        description = "Soft-deletes the roster row so appointment and clinical history keep "
            + "a name for the clinician. The row disappears from this list but is not destroyed.")
    public void deleteDoctor(
            @PathVariable UUID doctorId,
            @RequestParam(required = false) String projectId) {
        staff.deleteDoctor(
            TenantContextHolder.get(),
            RequestParams.optionalUuid(projectId, "projectId"),
            doctorId);
    }

    // ==================================================================
    // front desk
    // ==================================================================

    /** {@code GET /api/v1/clinic/front-desk} - the Front Desk list. */
    @GetMapping("/front-desk")
    @RequirePermission(Permission.FRONT_DESK_READ)
    @Operation(summary = "Front-desk officers",
        description = "CLINIC_STAFF accounts with a membership in the caller's project scope. "
            + "An optional q matches name, email, phone and clinic; an optional status is "
            + "ACTIVE or INACTIVE.")
    public ClinicCataloguePage<FrontDeskOfficer> frontDeskOfficers(
            @Parameter(description = "Narrow to one project. A request, not an authorization.")
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status) {
        return ClinicCataloguePage.of(staff.frontDeskOfficers(
            TenantContextHolder.get(),
            RequestParams.optionalUuid(projectId, "projectId"),
            q,
            status));
    }

    /** {@code GET /api/v1/clinic/front-desk/quota} - active staff count and max allowed staff limit. */
    @GetMapping("/front-desk/quota")
    @RequirePermission(Permission.FRONT_DESK_READ)
    @Operation(summary = "Front-desk staff quota",
        description = "Returns the clinic's active staff count and maximum allowed staff quota.")
    public java.util.Map<String, Object> frontDeskQuota(
            @RequestParam(required = false) String projectId) {
        return staff.getStaffQuota(
            TenantContextHolder.get(),
            RequestParams.optionalUuid(projectId, "projectId"));
    }

    /** {@code GET /api/v1/clinic/front-desk/{userId}} - one officer. */
    @GetMapping("/front-desk/{userId}")
    @RequirePermission(Permission.FRONT_DESK_READ)
    @Operation(summary = "One front-desk officer",
        description = "The account profile and assigned clinic. Answered with 404 when the "
            + "user has no membership in the caller's scope, identically to a nonexistent id.")
    public FrontDeskOfficer frontDeskOfficer(
            @PathVariable UUID userId,
            @RequestParam(required = false) String projectId) {
        return staff.frontDeskOfficer(
                TenantContextHolder.get(),
                RequestParams.optionalUuid(projectId, "projectId"),
                userId)
            .orElseThrow(() -> new ResourceNotFoundException("Resource"));
    }

    /** {@code POST /api/v1/clinic/front-desk} - create an officer. */
    @PostMapping("/front-desk")
    @RequirePermission(Permission.FRONT_DESK_MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a front-desk officer",
        description = "Creates a CLINIC_STAFF account with a bcrypt password, a project "
            + "membership and one clinic assignment. The response never contains the hash.")
    public FrontDeskOfficer createFrontDesk(
            @RequestBody FrontDeskOfficerInput payload,
            @RequestParam(required = false) String projectId) {
        return staff.createFrontDesk(
            TenantContextHolder.get(),
            RequestParams.optionalUuid(projectId, "projectId"),
            payload);
    }

    /** {@code PATCH /api/v1/clinic/front-desk/{userId}} - edit an officer. */
    @PatchMapping("/front-desk/{userId}")
    @RequirePermission(Permission.FRONT_DESK_MANAGE)
    @Operation(summary = "Edit a front-desk officer",
        description = "Merges the fields present in the body. A null or blank password keeps "
            + "the current one; the row status switch sends only isActive.")
    public FrontDeskOfficer updateFrontDesk(
            @PathVariable UUID userId,
            @RequestBody FrontDeskOfficerInput payload,
            @RequestParam(required = false) String projectId) {
        return staff.updateFrontDesk(
            TenantContextHolder.get(),
            RequestParams.optionalUuid(projectId, "projectId"),
            userId,
            payload);
    }

    /** {@code DELETE /api/v1/clinic/front-desk/{userId}} - remove from this project. */
    @DeleteMapping("/front-desk/{userId}")
    @RequirePermission(Permission.FRONT_DESK_MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove a front-desk officer",
        description = "Deletes the project membership and clears the clinic slot. The account "
            + "is deactivated only when it has no other memberships, so audit history survives.")
    public void deleteFrontDesk(
            @PathVariable UUID userId,
            @RequestParam(required = false) String projectId) {
        staff.deleteFrontDesk(
            TenantContextHolder.get(),
            RequestParams.optionalUuid(projectId, "projectId"),
            userId);
    }
}
