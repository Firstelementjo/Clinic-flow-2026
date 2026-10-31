package com.firstelement.inbox.web;

import com.firstelement.crm.clinic.ClinicScope;
import com.firstelement.crm.clinic.report.ClinicReportModels;
import com.firstelement.crm.clinic.report.ClinicReportService;
import com.firstelement.security.TenantContextHolder;
import com.firstelement.security.annotation.RequirePermission;
import com.firstelement.security.permission.Permission;
import com.firstelement.security.query.TenantQueryGuard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import java.time.LocalDateTime;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller for Dental Clinic Reports & Analytics (RPT-01 to RPT-05, BIL-08).
 */
@RestController
@RequestMapping("/api/v1/clinic")
public class ClinicReportsController {

    private final ClinicReportService reportService;
    private final TenantQueryGuard guard;

    public ClinicReportsController(ClinicReportService reportService, TenantQueryGuard guard) {
        this.reportService = reportService;
        this.guard = guard;
    }

    /**
     * Retrieves aggregated clinic operational, financial, and retention reports.
     */
    @GetMapping("/reports")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Get clinic reports and metrics",
            description = "Returns revenue breakdown, doctor productivity, appointment metrics, and patient retention (RPT-01 to RPT-05).")
    public ClinicReportModels.ClinicReportView getReports(
            @Parameter(description = "Window preset: 7d, 30d, 90d, 365d, custom")
            @RequestParam(required = false, defaultValue = "30d") String window,
            @Parameter(description = "Custom start timestamp")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @Parameter(description = "Custom end timestamp")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end,
            @Parameter(description = "Optional project scoping")
            @RequestParam(required = false) String projectId) {
        return reportService.getClinicReport(scope(projectId), window, start, end);
    }

    private ClinicScope scope(String projectId) {
        return ClinicScope.forRequestedProject(
                guard, TenantContextHolder.get(), RequestParams.optionalUuid(projectId, "projectId"));
    }
}
