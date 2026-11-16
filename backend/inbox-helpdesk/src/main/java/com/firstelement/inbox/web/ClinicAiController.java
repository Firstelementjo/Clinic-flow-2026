package com.firstelement.inbox.web;

import com.firstelement.crm.clinic.ClinicScope;
import com.firstelement.crm.clinic.ai.ClinicAiModels;
import com.firstelement.crm.clinic.ai.ClinicAiService;
import com.firstelement.security.TenantContext;
import com.firstelement.security.TenantContextHolder;
import com.firstelement.security.annotation.RequirePermission;
import com.firstelement.security.permission.Permission;
import com.firstelement.security.query.TenantQueryGuard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Controller for Dental Clinic Governed AI Assistant (Slice S9).
 *
 * <p>Exposes:
 * <ul>
 *   <li>Operational AI run monitoring & metrics (CLN-09)</li>
 *   <li>Emergency AI kill switch & governance controls</li>
 *   <li>Governed clinical draft proposals (visit summaries, recall, missing fields, payment matching, patient messages)</li>
 *   <li>Human review & approval queue</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/clinic/ai")
public class ClinicAiController {

    private final ClinicAiService aiService;
    private final TenantQueryGuard guard;

    public ClinicAiController(ClinicAiService aiService, TenantQueryGuard guard) {
        this.aiService = aiService;
        this.guard = guard;
    }

    /**
     * {@code GET /api/v1/clinic/ai/runs}
     */
    @GetMapping("/runs")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "List clinic AI runs",
            description = "Returns AI assistant runs (appointment scheduling, reception triage, patient inquiries) in clinic scope.")
    public List<ClinicAiModels.AiRunRow> getRuns(
            @Parameter(description = "Optional status filter (RUNNING, SUCCEEDED, FAILED, TIMED_OUT, CANCELLED)")
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false, defaultValue = "50") int limit) {
        return aiService.listRuns(scope(projectId), status, limit);
    }

    /**
     * {@code GET /api/v1/clinic/ai/overview}
     */
    @GetMapping("/overview")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Get clinic AI assistant overview",
            description = "Returns AI assistant summary metrics and confirms clinical suggestion policy (CLN-09).")
    public ClinicAiModels.ClinicAiOverview getOverview(
            @RequestParam(required = false) String projectId) {
        return aiService.getOverview(scope(projectId));
    }

    // -------------------------------------------------------------------------
    // Governance & Emergency Kill Switch
    // -------------------------------------------------------------------------

    @GetMapping("/governance")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Get clinic AI governance status",
            description = "Returns emergency kill switch status and policy enforcement flags.")
    public ClinicAiModels.ClinicAiGovernanceRow getGovernance(
            @RequestParam(required = false) String projectId) {
        return aiService.getGovernance(scope(projectId));
    }

    @PostMapping("/governance/kill-switch")
    @RequirePermission(Permission.DOCTOR_READ_MANAGEMENT)
    @Operation(summary = "Toggle emergency AI kill switch",
            description = "Immediately halts or resumes all clinic AI assistant proposal generation.")
    public ClinicAiModels.ClinicAiGovernanceRow toggleKillSwitch(
            @RequestBody ClinicAiModels.ToggleKillSwitchRequest request,
            @RequestParam(required = false) String projectId) {
        TenantContext caller = TenantContextHolder.get();
        return aiService.toggleKillSwitch(
                scope(projectId),
                request.isActive(),
                request.reason(),
                caller.userId(),
                actorName(caller),
                caller.role() != null ? caller.role().name() : "ADMIN"
        );
    }

    // -------------------------------------------------------------------------
    // Governed Proposals Management
    // -------------------------------------------------------------------------

    @GetMapping("/proposals")
    @RequirePermission(Permission.CUSTOMER_READ)
    @Operation(summary = "List clinic AI proposals",
            description = "Returns draft and reviewed AI proposals with transparent factors.")
    public List<ClinicAiModels.ClinicAiProposalRow> listProposals(
            @RequestParam(required = false) String patientId,
            @RequestParam(required = false) String proposalType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false, defaultValue = "50") int limit) {
        return aiService.listProposals(
                scope(projectId),
                RequestParams.optionalUuid(patientId, "patientId"),
                proposalType,
                status,
                limit
        );
    }

    @GetMapping("/proposals/{id}")
    @RequirePermission(Permission.CUSTOMER_READ)
    @Operation(summary = "Get single AI proposal",
            description = "Returns proposal details with input redaction and transparent factors.")
    public ClinicAiModels.ClinicAiProposalRow getProposal(
            @PathVariable UUID id,
            @RequestParam(required = false) String projectId) {
        return aiService.getProposal(scope(projectId), id);
    }

    @PostMapping("/proposals/{id}/review")
    @RequirePermission(Permission.CUSTOMER_READ)
    @Operation(summary = "Human review of AI proposal",
            description = "Approves or rejects a draft proposal; records reviewer audit entry.")
    public ClinicAiModels.ClinicAiProposalRow reviewProposal(
            @PathVariable UUID id,
            @RequestBody ClinicAiModels.ReviewProposalRequest request,
            @RequestParam(required = false) String projectId) {
        TenantContext caller = TenantContextHolder.get();
        return aiService.reviewProposal(
                scope(projectId),
                id,
                request,
                caller.userId(),
                actorName(caller),
                caller.role() != null ? caller.role().name() : "DOCTOR"
        );
    }

    // -------------------------------------------------------------------------
    // Governed Generation Endpoints
    // -------------------------------------------------------------------------

    @PostMapping("/proposals/visit-summary")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission(Permission.CUSTOMER_READ)
    @Operation(summary = "Generate visit summary draft",
            description = "Produces a draft clinical visit summary with citations; requires human approval.")
    public ClinicAiModels.ClinicAiProposalRow generateVisitSummaryDraft(
            @RequestBody ClinicAiModels.VisitSummaryDraftRequest request,
            @RequestParam(required = false) String projectId) {
        TenantContext caller = TenantContextHolder.get();
        return aiService.generateVisitSummaryDraft(
                scope(projectId),
                request,
                caller.userId(),
                actorName(caller),
                caller.role() != null ? caller.role().name() : "DOCTOR"
        );
    }

    @PostMapping({"/proposals/recall", "/proposals/recall-prioritization"})
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission(Permission.CUSTOMER_READ)
    @Operation(summary = "Generate recall prioritization proposals",
            description = "Computes transparent hygiene recall risk factors and returns draft proposals.")
    public ClinicAiModels.ClinicAiProposalRow generateRecallPrioritization(
            @RequestBody(required = false) ClinicAiModels.RecallPrioritizationRequest request,
            @RequestParam(required = false) String projectId) {
        TenantContext caller = TenantContextHolder.get();
        return aiService.generateRecallPrioritization(
                scope(projectId),
                request,
                caller.userId(),
                actorName(caller),
                caller.role() != null ? caller.role().name() : "EMPLOYEE"
        );
    }

    @PostMapping("/proposals/missing-fields")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission(Permission.CUSTOMER_READ)
    @Operation(summary = "Audit patient chart for missing fields",
            description = "Checks consents, emergency contacts, and medical history; outputs draft proposal.")
    public ClinicAiModels.ClinicAiProposalRow auditMissingFields(
            @RequestBody ClinicAiModels.MissingFieldsAuditRequest request,
            @RequestParam(required = false) String projectId) {
        TenantContext caller = TenantContextHolder.get();
        return aiService.auditMissingFields(
                scope(projectId),
                request,
                caller.userId(),
                actorName(caller),
                caller.role() != null ? caller.role().name() : "EMPLOYEE"
        );
    }

    @PostMapping("/proposals/payment-matching")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission(Permission.CUSTOMER_READ)
    @Operation(summary = "Propose invoice payment match",
            description = "Generates FIFO payment-to-invoice match proposal for human accountant review.")
    public ClinicAiModels.ClinicAiProposalRow proposePaymentMatching(
            @RequestBody ClinicAiModels.PaymentMatchProposalRequest request,
            @RequestParam(required = false) String projectId) {
        TenantContext caller = TenantContextHolder.get();
        return aiService.proposePaymentMatching(
                scope(projectId),
                request,
                caller.userId(),
                actorName(caller),
                caller.role() != null ? caller.role().name() : "EMPLOYEE"
        );
    }

    @PostMapping("/proposals/patient-message")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission(Permission.CUSTOMER_READ)
    @Operation(summary = "Draft patient communication message",
            description = "Drafts appointment reminder, post-op instructions, or recall message in AR/EN.")
    public ClinicAiModels.ClinicAiProposalRow draftPatientMessage(
            @RequestBody ClinicAiModels.PatientMessageDraftRequest request,
            @RequestParam(required = false) String projectId) {
        TenantContext caller = TenantContextHolder.get();
        return aiService.draftPatientMessage(
                scope(projectId),
                request,
                caller.userId(),
                actorName(caller),
                caller.role() != null ? caller.role().name() : "EMPLOYEE"
        );
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

    // -------------------------------------------------------------------------
    // AI Receptionist Agent Console & Simulator Endpoints
    // -------------------------------------------------------------------------

    @GetMapping("/agent-config")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Get clinic AI receptionist configuration")
    public ClinicAiModels.AgentConfig getAgentConfig(@RequestParam(required = false) String projectId) {
        return aiService.getAgentConfig(scope(projectId));
    }

    @PutMapping("/agent-config")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Update clinic AI receptionist configuration")
    public ClinicAiModels.AgentConfig updateAgentConfig(
            @RequestParam(required = false) String projectId,
            @RequestBody ClinicAiModels.AgentConfig config) {
        return aiService.updateAgentConfig(scope(projectId), config);
    }

    @GetMapping("/agent-metrics")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Get clinic AI receptionist performance metrics")
    public ClinicAiModels.AgentMetrics getAgentMetrics(@RequestParam(required = false) String projectId) {
        return aiService.getAgentMetrics(scope(projectId));
    }

    @PostMapping("/test-chat")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Test AI receptionist conversation simulator")
    public ClinicAiModels.TestChatResponse testChat(
            @RequestParam(required = false) String projectId,
            @RequestBody ClinicAiModels.TestChatRequest req) {
        return aiService.testChat(scope(projectId), req != null ? req.message() : null);
    }
}
