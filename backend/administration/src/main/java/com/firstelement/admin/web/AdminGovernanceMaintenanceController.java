package com.firstelement.admin.web;

import com.firstelement.admin.governance.LegalHoldService;
import com.firstelement.admin.governance.PrivacyDeletionService;
import com.firstelement.admin.maintenance.MaintenanceCommandService;
import com.firstelement.admin.maintenance.MaintenanceOutcome;
import com.firstelement.security.TenantContextHolder;
import com.firstelement.security.annotation.RequirePermission;
import com.firstelement.security.permission.Permission;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/admin/governance/maintenance} — the typed maintenance routes.
 *
 * <h2>One route per command, one permission per route</h2>
 *
 * <p>There is no {@code POST /commands/{name}} here, and there never will be: a route that took a
 * command name from a request would have to declare a single permission for every capability behind
 * it, which is the exact shape that produces "one route, one permission, seven capabilities". Each
 * route below names one command, declares that command's permission at the HTTP edge (FE-WEB-003),
 * and calls one service method.
 *
 * <h2>Nothing here deletes anything</h2>
 *
 * <p>The two routes that would ({@code /privacy-deletions/{id}/execute} and {@code /purge}) are
 * present and <b>refuse</b>, and each refusal writes an audit row. Their presence is what makes the
 * capability visible to an operator and reviewable by a regulator; their refusal is what keeps it
 * from being a button.
 */
@RestController
@RequestMapping("/api/v1/admin/governance/maintenance")
public class AdminGovernanceMaintenanceController {

    private final MaintenanceCommandService commands;

    public AdminGovernanceMaintenanceController(MaintenanceCommandService commands) {
        this.commands = commands;
    }

    // ------------------------------------------------------------------
    // legal holds
    // ------------------------------------------------------------------

    /** {@code POST /api/v1/admin/governance/maintenance/legal-holds} */
    public record PlaceHoldRequest(
        @NotNull LegalHoldService.HoldScopeType scopeType,
        UUID projectId,
        String scopeRef,
        @NotBlank String reason,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime expiresAt) {
    }

    /**
     * Places a legal hold.
     *
     * <p>A tenancy-level act with no project id on the route, so it is scoped to the caller's own
     * organization and requires {@code ORGANIZATION_UPDATE} rather than a project-scoped permission:
     * changing what a purge may never touch is not a project decision.
     */
    @PostMapping("/legal-holds")
    @RequirePermission(Permission.ORGANIZATION_UPDATE)
    public ResponseEntity<MaintenanceOutcome> placeHold(@Valid @RequestBody PlaceHoldRequest body) {
        return ResponseEntity.ok(commands.placeLegalHold(TenantContextHolder.get(),
            body.scopeType(), body.projectId(), body.scopeRef(), body.reason(), body.expiresAt()));
    }

    /** {@code POST /api/v1/admin/governance/maintenance/legal-holds/{holdId}/release} */
    public record ReleaseHoldRequest(@NotBlank String reason, int expectedVersion) {
    }

    /**
     * Releases a legal hold.
     *
     * <p>{@code expectedVersion} is the optimistic-concurrency token: two operators releasing the
     * same hold produce one success and one recorded refusal rather than two silent overwrites.
     */
    @PostMapping("/legal-holds/{holdId}/release")
    @RequirePermission(Permission.ORGANIZATION_UPDATE)
    public ResponseEntity<MaintenanceOutcome> releaseHold(@PathVariable UUID holdId,
                                                          @Valid @RequestBody ReleaseHoldRequest body) {
        return ResponseEntity.ok(commands.releaseLegalHold(TenantContextHolder.get(), holdId,
            body.reason(), body.expectedVersion()));
    }

    /**
     * {@code GET /api/v1/admin/governance/maintenance/purge-gate}
     *
     * <p>The ADR-0008 rule 5 answer, on its own route, so a UI can ask "could this tenant ever be
     * purged?" without attempting a purge. Read-only.
     */
    @GetMapping("/purge-gate")
    @RequirePermission(Permission.SYSTEM_HEALTH_READ)
    public LegalHoldService.PurgeGate purgeGate(
        @RequestParam(name = "projectId", required = false) UUID projectId) {
        return commands.purgeGate(TenantContextHolder.get(), projectId);
    }

    // ------------------------------------------------------------------
    // privacy deletion
    // ------------------------------------------------------------------

    /** {@code POST /api/v1/admin/governance/maintenance/privacy-deletions/preview} */
    public record PreviewDeletionRequest(@NotNull UUID subjectUserId, UUID projectId) {
    }

    /**
     * Measures a deletion without writing anything.
     *
     * <p>Safe to call while an operator is still typing, and it returns the impact and the hold gate
     * together, because "what would this delete" and "what would stop it" are one question.
     */
    @PostMapping("/privacy-deletions/preview")
    @RequirePermission(Permission.ORGANIZATION_UPDATE)
    public ResponseEntity<PrivacyDeletionService.DeletionRequestOutcome> previewDeletion(
        @Valid @RequestBody PreviewDeletionRequest body) {
        return ResponseEntity.ok(commands.previewPrivacyDeletion(TenantContextHolder.get(),
            body.subjectUserId(), body.projectId()));
    }

    /** {@code POST /api/v1/admin/governance/maintenance/privacy-deletions} */
    public record RequestDeletionRequest(@NotNull UUID subjectUserId,
                                         UUID projectId,
                                         @NotBlank String subjectType,
                                         @NotBlank String reason,
                                         String evidenceReference) {
    }

    /**
     * Opens a typed deletion request.
     *
     * <p>Records a request, an impact count and one retained target per covering legal hold. Removes
     * nothing, and the response says so.
     */
    @PostMapping("/privacy-deletions")
    @RequirePermission(Permission.ORGANIZATION_UPDATE)
    public ResponseEntity<MaintenanceOutcome> requestDeletion(
        @Valid @RequestBody RequestDeletionRequest body) {
        return ResponseEntity.ok(commands.requestPrivacyDeletion(TenantContextHolder.get(),
            body.subjectUserId(), body.projectId(), body.subjectType(), body.reason(),
            body.evidenceReference()));
    }

    /** {@code POST /api/v1/admin/governance/maintenance/privacy-deletions/{requestId}/execute} */
    @PostMapping("/privacy-deletions/{requestId}/execute")
    @RequirePermission(Permission.ORGANIZATION_UPDATE)
    public ResponseEntity<MaintenanceOutcome> executeDeletion(
        @PathVariable UUID requestId,
        @RequestParam(name = "projectId", required = false) UUID projectId) {
        return ResponseEntity.ok(commands.executePrivacyDeletion(TenantContextHolder.get(),
            requestId, projectId));
    }

    /** {@code POST /api/v1/admin/governance/maintenance/purge} */
    @PostMapping("/purge")
    @RequirePermission(Permission.ORGANIZATION_UPDATE)
    public ResponseEntity<MaintenanceOutcome> purge(
        @RequestParam(name = "projectId", required = false) UUID projectId,
        @RequestParam(name = "classCode", required = false) String classCode) {
        return ResponseEntity.ok(commands.purgeRetention(TenantContextHolder.get(), projectId,
            classCode));
    }
}