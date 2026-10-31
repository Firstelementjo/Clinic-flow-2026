package com.firstelement.inbox.web;

import com.firstelement.crm.clinic.ClinicScope;
import com.firstelement.crm.clinic.policy.ClinicPolicyModels;
import com.firstelement.crm.clinic.policy.ClinicPolicyService;
import com.firstelement.crm.error.ResourceNotFoundException;
import com.firstelement.security.TenantContext;
import com.firstelement.security.TenantContextHolder;
import com.firstelement.security.annotation.RequirePermission;
import com.firstelement.security.permission.Permission;
import com.firstelement.security.query.TenantQueryGuard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller for Dental Clinic Policies & Settings (USR-09, BIL-07).
 */
@RestController
@RequestMapping("/api/v1/clinic/settings")
public class ClinicPoliciesController {

    private final ClinicPolicyService policyService;
    private final TenantQueryGuard guard;

    public ClinicPoliciesController(ClinicPolicyService policyService, TenantQueryGuard guard) {
        this.policyService = policyService;
        this.guard = guard;
    }

    /**
     * Reads current clinic policies.
     */
    @GetMapping("/policies")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Get clinic policies",
            description = "Returns patient visibility rules, primary currency, and slot duration defaults (USR-09, BIL-07).")
    public ClinicPolicyModels.ClinicPolicyRow getPolicies(
            @Parameter(description = "Optional project scoping")
            @RequestParam(required = false) String projectId) {
        return policyService.getPolicy(scope(projectId));
    }

    /**
     * Updates clinic policies (Admin only).
     */
    @PutMapping("/policies")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Update clinic policies",
            description = "Modifies patient visibility rules, default duration, and primary currency.")
    public ClinicPolicyModels.ClinicPolicyRow updatePolicies(
            @RequestParam(required = false) String projectId,
            @RequestBody ClinicPolicyModels.UpdatePolicyRequest request) {
        TenantContext caller = TenantContextHolder.get();
        return policyService.updatePolicy(writeScope(projectId), request, caller);
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
