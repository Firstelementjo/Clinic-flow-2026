package com.firstelement.inbox.web;

import com.firstelement.crm.clinic.ClinicScope;
import com.firstelement.crm.clinic.audit.ClinicAuditModels;
import com.firstelement.crm.clinic.audit.ClinicAuditService;
import com.firstelement.crm.error.ResourceNotFoundException;
import com.firstelement.security.TenantContext;
import com.firstelement.security.TenantContextHolder;
import com.firstelement.security.annotation.RequirePermission;
import com.firstelement.security.permission.Permission;
import com.firstelement.security.query.TenantQueryGuard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for Dental Clinic Slice S8:
 * Break-Glass Emergency Access, Immutable SHA-256 Audit Stream, and Curated Tenant Reports.
 */
@RestController
@RequestMapping("/api/v1/clinic")
public class ClinicAuditController {

    private final ClinicAuditService auditService;
    private final TenantQueryGuard guard;

    public ClinicAuditController(ClinicAuditService auditService, TenantQueryGuard guard) {
        this.auditService = auditService;
        this.guard = guard;
    }

    // ------------------------------------------------------------------
    // 1. Break-Glass Emergency Access
    // ------------------------------------------------------------------

    @PostMapping("/break-glass")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission(Permission.CUSTOMER_READ)
    @Operation(summary = "Request break-glass emergency access",
            description = "Grants immediate emergency access to patient records with mandatory reason and audit entry.")
    public ClinicAuditModels.BreakGlassGrantRow requestBreakGlass(
            @RequestBody ClinicAuditModels.CreateBreakGlassRequest request,
            @RequestParam(required = false) String projectId) {
        TenantContext caller = TenantContextHolder.get();
        return auditService.requestBreakGlass(
                writeScope(projectId),
                caller.userId(),
                actorName(caller),
                caller.role() != null ? caller.role().name() : "EMPLOYEE",
                request);
    }

    @GetMapping("/break-glass/active")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "List active break-glass grants",
            description = "Returns current unexpired, unrevoked emergency access grants.")
    public List<ClinicAuditModels.BreakGlassGrantRow> listActiveBreakGlass(
            @RequestParam(required = false) String projectId) {
        return auditService.listActiveBreakGlass(scope(projectId));
    }

    @GetMapping("/break-glass/pending-review")
    @RequirePermission(Permission.AUDIT_LOG_READ)
    @Operation(summary = "List break-glass grants pending review",
            description = "Returns emergency grants requiring clinical director or compliance officer review.")
    public List<ClinicAuditModels.BreakGlassGrantRow> listBreakGlassPendingReview(
            @RequestParam(required = false) String projectId) {
        return auditService.listBreakGlassPendingReview(scope(projectId));
    }

    @PostMapping("/break-glass/{id}/revoke")
    @RequirePermission(Permission.CUSTOMER_READ)
    @Operation(summary = "Revoke break-glass grant early",
            description = "Revokes emergency access immediately upon completion of emergency treatment.")
    public Map<String, Object> revokeBreakGlass(
            @PathVariable("id") String id,
            @RequestBody(required = false) ClinicAuditModels.RevokeBreakGlassRequest request,
            @RequestParam(required = false) String projectId) {
        UUID grantId = RequestParams.requiredUuid(id, "grantId");
        TenantContext caller = TenantContextHolder.get();
        boolean revoked = auditService.revokeBreakGlass(
                scope(projectId),
                grantId,
                caller.userId(),
                actorName(caller),
                caller.role() != null ? caller.role().name() : "EMPLOYEE",
                request);
        return Map.of("revoked", revoked, "grantId", grantId);
    }

    @PostMapping("/break-glass/{id}/review")
    @RequirePermission(Permission.AUDIT_LOG_READ)
    @Operation(summary = "Complete break-glass compliance review",
            description = "Approves or flags emergency access for compliance and HIPAA review.")
    public Map<String, Object> reviewBreakGlass(
            @PathVariable("id") String id,
            @RequestBody ClinicAuditModels.ReviewBreakGlassRequest request,
            @RequestParam(required = false) String projectId) {
        UUID grantId = RequestParams.requiredUuid(id, "grantId");
        TenantContext caller = TenantContextHolder.get();
        boolean reviewed = auditService.reviewBreakGlass(
                scope(projectId),
                grantId,
                caller.userId(),
                actorName(caller),
                caller.role() != null ? caller.role().name() : "ADMIN",
                request);
        return Map.of("reviewed", reviewed, "grantId", grantId);
    }

    // ------------------------------------------------------------------
    // 2. Tamper-Evident Audit Stream
    // ------------------------------------------------------------------

    @GetMapping("/audit")
    @RequirePermission(Permission.AUDIT_LOG_READ)
    @Operation(summary = "Query clinic audit events",
            description = "Returns immutable, tamper-evident audit records filtered by actor, patient, or action.")
    public List<ClinicAuditModels.ClinicAuditRow> queryAuditLog(
            @RequestParam(required = false) String patientId,
            @RequestParam(required = false) String actorUserId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false, defaultValue = "100") int limit,
            @RequestParam(required = false) String projectId) {
        return auditService.queryAuditLog(
                scope(projectId),
                RequestParams.optionalUuid(patientId, "patientId"),
                RequestParams.optionalUuid(actorUserId, "actorUserId"),
                action,
                resourceType,
                from,
                to,
                limit);
    }

    @GetMapping("/audit/verify")
    @RequirePermission(Permission.AUDIT_LOG_READ)
    @Operation(summary = "Verify cryptographic audit chain integrity",
            description = "Recomputes and verifies SHA-256 hash chains across the tenant audit trail.")
    public Map<String, Object> verifyAuditChain(
            @RequestParam(required = false) String projectId) {
        boolean valid = auditService.verifyAuditChainIntegrity(scope(projectId));
        return Map.of("integrityVerified", valid, "algorithm", "SHA-256");
    }

    // ------------------------------------------------------------------
    // 3. Curated Parameterized Reports
    // ------------------------------------------------------------------

    @GetMapping("/reports/appointment-productivity")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Appointment productivity report",
            description = "Returns doctor-specific appointment completion, cancellation, and no-show metrics.")
    public List<ClinicAuditModels.AppointmentProductivityRow> getAppointmentProductivity(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String doctorId,
            @RequestParam(required = false) String projectId) {
        return auditService.getAppointmentProductivity(
                scope(projectId), from, to, RequestParams.optionalUuid(doctorId, "doctorId"));
    }

    @GetMapping("/reports/procedure-utilization")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Procedure utilization report",
            description = "Returns procedure volume, billed revenue, and average fees.")
    public List<ClinicAuditModels.ProcedureUtilizationRow> getProcedureUtilization(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String projectId) {
        return auditService.getProcedureUtilization(scope(projectId), from, to);
    }

    @GetMapping("/reports/financial-collection")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Financial collection summary report",
            description = "Returns currency-level invoiced, collected, outstanding, and collection rate %.")
    public List<ClinicAuditModels.FinancialCollectionSummaryRow> getFinancialCollectionSummary(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String projectId) {
        return auditService.getFinancialCollectionSummary(scope(projectId), from, to);
    }

    @GetMapping("/reports/patient-recall")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Patient retention and recall report",
            description = "Returns patients overdue for routine 6-month prophylaxis and recall examinations.")
    public List<ClinicAuditModels.PatientRetentionRecallRow> getPatientRetentionRecall(
            @RequestParam(required = false, defaultValue = "180") Integer overdueDaysThreshold,
            @RequestParam(required = false) String projectId) {
        return auditService.getPatientRetentionRecall(scope(projectId), overdueDaysThreshold);
    }

    @GetMapping("/reports/break-glass-compliance")
    @RequirePermission(Permission.AUDIT_LOG_READ)
    @Operation(summary = "Break-glass compliance report",
            description = "Returns all emergency access events with justification and compliance review outcomes.")
    public List<ClinicAuditModels.BreakGlassComplianceRow> getBreakGlassCompliance(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String projectId) {
        return auditService.getBreakGlassCompliance(scope(projectId), from, to);
    }

    @PostMapping("/reports/export")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Export report data with cryptographic audit signature",
            description = "Exports report data and automatically records an immutable DATA_EXPORTED audit event.")
    public ClinicAuditModels.ClinicAuditExportView exportReport(
            @RequestParam(required = false, defaultValue = "APPOINTMENTS") String exportType,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String projectId) {
        TenantContext caller = TenantContextHolder.get();
        return auditService.exportReport(
                scope(projectId),
                exportType,
                from,
                to,
                caller.userId(),
                actorName(caller),
                caller.role() != null ? caller.role().name() : "EMPLOYEE");
    }

    private static String actorName(TenantContext caller) {
        if (caller == null) {
            return "system";
        }
        if (caller.userId() != null) {
            return caller.userId().toString();
        }
        if (caller.principalId() != null) {
            return caller.principalId().toString();
        }
        return "system";
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
