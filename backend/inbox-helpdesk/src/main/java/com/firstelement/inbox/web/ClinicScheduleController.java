package com.firstelement.inbox.web;

import com.firstelement.crm.clinic.ClinicScope;
import com.firstelement.crm.clinic.schedule.ScheduleModels;
import com.firstelement.crm.clinic.schedule.ScheduleRepository;
import com.firstelement.security.TenantContext;
import com.firstelement.security.TenantContextHolder;
import com.firstelement.security.annotation.RequirePermission;
import com.firstelement.security.permission.Permission;
import com.firstelement.security.query.TenantQueryGuard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
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
 * Controller for clinic scheduling resources, slot holds, waitlist, and reminder dispatches.
 *
 * <p>Endpoints are placed under {@code /api/v1/clinic/schedules/}.
 */
@RestController
@RequestMapping("/api/v1/clinic/schedules")
@Tag(name = "Clinic Schedules", description = "Resources, slot reservations, waitlist and dispatch queue.")
public class ClinicScheduleController {

    private final ScheduleRepository scheduleRepository;
    private final TenantQueryGuard guard;

    public ClinicScheduleController(ScheduleRepository scheduleRepository, TenantQueryGuard guard) {
        this.scheduleRepository = scheduleRepository;
        this.guard = guard;
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
        throw new com.firstelement.crm.error.ResourceNotFoundException("Resource not found");
    }

    // ------------------------------------------------------------------
    // Resources & Capabilities
    // ------------------------------------------------------------------

    @GetMapping("/resources")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "List clinic chairs and shared equipment")
    public List<ScheduleModels.ResourceDetail> listResources(
            @Parameter(description = "Scope to one clinic project")
            @RequestParam(required = false) String projectId,
            @RequestParam(defaultValue = "50") int limit) {
        ClinicScope scope = scope(projectId);
        return scheduleRepository.listResourceDetails(scope, limit);
    }

    @PostMapping("/resources")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create clinic chair or equipment")
    public Map<String, Object> createResource(
            @RequestParam(required = false) String projectId,
            @RequestBody ScheduleModels.CreateResourceRequest req) {
        ClinicScope scope = writeScope(projectId);
        UUID id = scheduleRepository.insertResource(scope, Map.of(
                "name", req.name(),
                "isActive", true));
        if (req.capabilities() != null) {
            for (String cap : req.capabilities()) {
                scheduleRepository.insertCapability(scope, Map.of(
                        "resourceId", id,
                        "capability", cap));
            }
        }
        return Map.of("id", id, "name", req.name());
    }

    @PostMapping("/resources/{id}/capabilities")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add capability to resource")
    public Map<String, Object> addCapability(
            @PathVariable UUID id,
            @RequestParam(required = false) String projectId,
            @RequestBody Map<String, String> body) {
        ClinicScope scope = writeScope(projectId);
        String capability = body.get("capability");
        UUID capId = scheduleRepository.insertCapability(scope, Map.of(
                "resourceId", id,
                "capability", capability));
        return Map.of("id", capId, "capability", capability);
    }

    @PostMapping("/appointments/{id}/assign-resource")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Assign resource and buffer to an appointment")
    public Map<String, Object> assignResource(
            @PathVariable UUID id,
            @RequestParam(required = false) String projectId,
            @RequestBody Map<String, Object> body) {
        ClinicScope scope = scope(projectId);
        UUID resourceId = body.get("resourceId") == null ? null : UUID.fromString(body.get("resourceId").toString());
        int bufferMinutes = body.get("bufferMinutes") == null ? 0 : Integer.parseInt(body.get("bufferMinutes").toString());
        int updated = scheduleRepository.assignResource(scope, id, resourceId, bufferMinutes);
        return Map.of("updated", updated, "appointmentId", id, "resourceId", resourceId != null ? resourceId : "", "bufferMinutes", bufferMinutes);
    }

    @PostMapping("/appointments")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Book a new clinic appointment")
    public ScheduleModels.AppointmentCreatedResponse createAppointment(
            @RequestParam(required = false) String projectId,
            @RequestBody ScheduleModels.CreateAppointmentRequest req) {
        ClinicScope scope = writeScope(projectId);
        TenantContext ctx = TenantContextHolder.get();
        UUID userId = ctx != null ? (ctx.userId() != null ? ctx.userId() : ctx.principalId()) : null;
        return scheduleRepository.insertAppointment(scope, req, userId);
    }

    @PatchMapping("/appointments/{id}/status")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Update appointment status")
    public Map<String, Object> updateAppointmentStatus(
            @PathVariable UUID id,
            @RequestParam(required = false) String projectId,
            @RequestBody ScheduleModels.UpdateAppointmentStatusRequest req) {
        ClinicScope scope = scope(projectId);
        TenantContext ctx = TenantContextHolder.get();
        UUID userId = ctx != null ? (ctx.userId() != null ? ctx.userId() : ctx.principalId()) : null;
        int count = scheduleRepository.updateAppointmentStatus(scope, id, req.status(), req.cancelledReason(), userId);
        return Map.of("id", id, "status", req.status(), "updated", count > 0);
    }

    /**
     * {@code PATCH /api/v1/clinic/schedules/appointments/{id}/payment}
     *
     * <p>The payment state is a separate decision from the appointment status: a
     * completed visit can be unpaid, and a cancelled visit can carry a refund. An
     * unknown value is a 400 naming the accepted set.
     */
    @PatchMapping("/appointments/{id}/payment")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Update appointment payment status")
    public Map<String, Object> updateAppointmentPayment(
            @PathVariable UUID id,
            @RequestParam(required = false) String projectId,
            @RequestBody Map<String, String> body) {
        ClinicScope scope = writeScope(projectId);
        String paymentStatus = body.get("paymentStatus");
        int count = scheduleRepository.updatePaymentStatus(scope, id, paymentStatus);
        return Map.of("id", id, "paymentStatus", paymentStatus, "updated", count > 0);
    }

    // ------------------------------------------------------------------
    // Holds (TTL Slot Reservations)
    // ------------------------------------------------------------------

    @GetMapping("/holds")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "List active holds for a doctor and day")
    public List<ScheduleModels.HoldRow> listHolds(
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) UUID doctorId,
            @RequestParam(required = false) String date) {
        ClinicScope scope = scope(projectId);
        LocalDate day = date != null ? LocalDate.parse(date) : LocalDate.now();
        return scheduleRepository.listActiveHolds(scope, doctorId, day);
    }

    @PostMapping("/holds")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create slot hold reservation")
    public Map<String, Object> createHold(
            @RequestParam(required = false) String projectId,
            @RequestBody ScheduleModels.CreateHoldRequest req) {
        ClinicScope scope = writeScope(projectId);
        TenantContext ctx = TenantContextHolder.get();
        UUID userId = ctx != null ? (ctx.userId() != null ? ctx.userId() : ctx.principalId()) : null;
        UUID id = scheduleRepository.insertHold(scope, req, userId);
        return Map.of("id", id, "status", "ACTIVE");
    }

    @DeleteMapping("/holds/{id}")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Release active slot hold")
    public Map<String, Object> releaseHold(
            @PathVariable UUID id,
            @RequestParam(required = false) String projectId) {
        ClinicScope scope = scope(projectId);
        int count = scheduleRepository.releaseHold(scope, id);
        return Map.of("id", id, "released", count > 0);
    }

    // ------------------------------------------------------------------
    // Waitlist
    // ------------------------------------------------------------------

    @GetMapping("/waitlist")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "List waitlist patients")
    public List<ScheduleModels.WaitlistRow> listWaitlist(
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "50") int limit) {
        ClinicScope scope = scope(projectId);
        return scheduleRepository.listWaitlist(scope, status, limit);
    }

    @PostMapping("/waitlist")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add patient to waitlist")
    public Map<String, Object> createWaitlist(
            @RequestParam(required = false) String projectId,
            @RequestBody ScheduleModels.CreateWaitlistRequest req) {
        ClinicScope scope = writeScope(projectId);
        UUID id = scheduleRepository.insertWaitlist(scope, req);
        return Map.of("id", id, "status", "WAITING");
    }

    @PatchMapping("/waitlist/{id}")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Update waitlist status or offered slot")
    public Map<String, Object> updateWaitlist(
            @PathVariable UUID id,
            @RequestParam(required = false) String projectId,
            @RequestBody ScheduleModels.UpdateWaitlistStatusRequest req) {
        ClinicScope scope = scope(projectId);
        int updated = scheduleRepository.updateWaitlistStatus(scope, id, req);
        return Map.of("id", id, "updated", updated > 0);
    }

    // ------------------------------------------------------------------
    // Reminders
    // ------------------------------------------------------------------

    @GetMapping("/reminders")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "List reminder dispatch queue")
    public List<ScheduleModels.ReminderRow> listReminders(
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "50") int limit) {
        ClinicScope scope = scope(projectId);
        return scheduleRepository.listReminders(scope, status, limit);
    }

    @PostMapping("/reminders")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Queue an appointment reminder")
    public Map<String, Object> createReminder(
            @RequestParam(required = false) String projectId,
            @RequestBody ScheduleModels.CreateReminderRequest req) {
        ClinicScope scope = writeScope(projectId);
        UUID id = scheduleRepository.insertReminder(scope, req);
        return Map.of("id", id, "status", "PENDING");
    }

    @PatchMapping("/reminders/{id}")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Update reminder status")
    public Map<String, Object> updateReminder(
            @PathVariable UUID id,
            @RequestParam(required = false) String projectId,
            @RequestBody Map<String, String> body) {
        ClinicScope scope = scope(projectId);
        String status = body.get("status");
        String failureReason = body.get("failureReason");
        int updated = scheduleRepository.updateReminderStatus(scope, id, status, failureReason);
        return Map.of("id", id, "updated", updated > 0);
    }
}
