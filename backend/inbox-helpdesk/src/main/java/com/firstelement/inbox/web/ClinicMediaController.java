package com.firstelement.inbox.web;

import com.firstelement.crm.clinic.ClinicScope;
import com.firstelement.crm.clinic.media.ClinicMediaModels;
import com.firstelement.crm.clinic.media.ClinicMediaService;
import com.firstelement.crm.error.ResourceNotFoundException;
import com.firstelement.security.TenantContext;
import com.firstelement.security.TenantContextHolder;
import com.firstelement.security.annotation.RequirePermission;
import com.firstelement.security.permission.Permission;
import com.firstelement.security.query.TenantQueryGuard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Controller for Dental Clinic Media, Clinical Radiographs, Photos, Documents, and HIPAA/DEC-007 Access Auditing (Slice S7).
 */
@RestController
@RequestMapping("/api/v1/clinic")
public class ClinicMediaController {

    private final ClinicMediaService mediaService;
    private final TenantQueryGuard guard;

    public ClinicMediaController(ClinicMediaService mediaService, TenantQueryGuard guard) {
        this.mediaService = mediaService;
        this.guard = guard;
    }

    /**
     * Lists clinical media attachments for a patient.
     */
    @GetMapping("/patients/{patientId}/media")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "List patient media", description = "Returns radiographs, clinical photos, and documents for a patient.")
    public List<ClinicMediaModels.MediaRow> listPatientMedia(
            @PathVariable("patientId") String patientId,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Integer toothFdi,
            @RequestParam(required = false) Boolean legalHoldOnly,
            @RequestParam(required = false) String projectId
    ) {
        UUID patId = RequestParams.requiredUuid(patientId, "patientId");
        return mediaService.listPatientMedia(scope(projectId), patId, category, toothFdi, legalHoldOnly);
    }

    /**
     * Uploads a new clinical media file (radiograph, photo, document, PDF).
     */
    @PostMapping(value = "/patients/{patientId}/media", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission(Permission.CUSTOMER_UPDATE)
    @Operation(summary = "Upload patient media", description = "Uploads and encrypts radiograph, photo, or clinical document.")
    public ClinicMediaModels.MediaRow uploadMedia(
            @PathVariable("patientId") String patientId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "category", defaultValue = "DOCUMENT_PDF") String category,
            @RequestParam(value = "toothFdi", required = false) Integer toothFdi,
            @RequestParam(value = "acquisitionDate", required = false) String acquisitionDateStr,
            @RequestParam(value = "radiographerName", required = false) String radiographerName,
            @RequestParam(value = "notes", required = false) String notes,
            @RequestParam(value = "retentionClass", required = false) String retentionClass,
            @RequestParam(value = "projectId", required = false) String projectId,
            HttpServletRequest request
    ) throws IOException {
        UUID patId = RequestParams.requiredUuid(patientId, "patientId");
        ClinicScope scope = writeScope(projectId);
        TenantContext ctx = TenantContextHolder.get();

        Instant acqDate = acquisitionDateStr != null && !acquisitionDateStr.isBlank()
                ? Instant.parse(acquisitionDateStr)
                : Instant.now();

        ClinicMediaModels.UploadMediaRequest uploadReq = new ClinicMediaModels.UploadMediaRequest(
                file.getOriginalFilename() != null ? file.getOriginalFilename() : "attachment.bin",
                file.getContentType(),
                file.getBytes(),
                category,
                toothFdi,
                acqDate,
                radiographerName,
                notes,
                retentionClass
        );

        UUID userId = ctx != null ? ctx.userId() : UUID.fromString("00000000-0000-0000-0000-000000000000");
        String userName = actorName(ctx, "System User");
        String clientIp = request.getRemoteAddr();

        return mediaService.uploadMedia(scope, patId, uploadReq, userId, userName, clientIp);
    }

    /**
     * Retrieves media metadata.
     */
    @GetMapping("/patients/{patientId}/media/{mediaId}")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Get media metadata", description = "Retrieves metadata of a specific clinical media attachment.")
    public ClinicMediaModels.MediaRow getMedia(
            @PathVariable("patientId") String patientId,
            @PathVariable("mediaId") String mediaId,
            @RequestParam(required = false) String projectId
    ) {
        UUID mId = RequestParams.requiredUuid(mediaId, "mediaId");
        return mediaService.getMedia(scope(projectId), mId);
    }

    /**
     * Mints a short-lived signed download URL for secure streaming.
     */
    @GetMapping("/patients/{patientId}/media/{mediaId}/download-url")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Mint signed download URL", description = "Creates a short-lived HMAC-SHA256 signed download URL.")
    public ClinicMediaModels.SignedDownloadUrl mintSignedDownloadUrl(
            @PathVariable("patientId") String patientId,
            @PathVariable("mediaId") String mediaId,
            @RequestParam(value = "ttlMinutes", defaultValue = "15") int ttlMinutes,
            @RequestParam(required = false) String projectId,
            HttpServletRequest request
    ) {
        UUID mId = RequestParams.requiredUuid(mediaId, "mediaId");
        TenantContext ctx = TenantContextHolder.get();
        UUID userId = ctx != null ? ctx.userId() : UUID.fromString("00000000-0000-0000-0000-000000000000");
        String userName = actorName(ctx, "System User");

        return mediaService.createSignedDownloadUrl(
                scope(projectId),
                mId,
                userId,
                userName,
                request.getRemoteAddr(),
                Duration.ofMinutes(ttlMinutes > 0 ? ttlMinutes : 15)
        );
    }

    /**
     * Public/Token-authenticated streaming download endpoint.
     */
    @GetMapping("/media/download")
    @Operation(summary = "Download media via signed token", description = "Streams decrypted media file using HMAC-SHA256 signed token.")
    public ResponseEntity<byte[]> downloadMedia(
            @RequestParam("token") String token,
            HttpServletRequest request
    ) {
        TenantContext ctx = TenantContextHolder.get();
        UUID userId = ctx != null ? ctx.userId() : null;
        String userName = actorName(ctx, "Token Bearer");

        ClinicMediaService.DecryptedMediaPayload payload = mediaService.downloadBySignedToken(
                token,
                userId,
                userName,
                request.getRemoteAddr()
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(payload.contentType()));
        headers.setContentLength(payload.byteSize());
        headers.setContentDispositionFormData("inline", payload.fileName());
        headers.setCacheControl("private, no-cache, no-store, must-revalidate");
        headers.set("ETag", "\"" + payload.sha256() + "\"");

        return new ResponseEntity<>(payload.content(), headers, HttpStatus.OK);
    }

    /**
     * Toggles legal hold on media attachment (DEC-007).
     */
    @PostMapping("/patients/{patientId}/media/{mediaId}/legal-hold")
    @RequirePermission(Permission.CUSTOMER_UPDATE)
    @Operation(summary = "Toggle legal hold", description = "Sets or releases legal hold on a clinical media attachment.")
    public ResponseEntity<Void> toggleLegalHold(
            @PathVariable("patientId") String patientId,
            @PathVariable("mediaId") String mediaId,
            @RequestBody ClinicMediaModels.ToggleLegalHoldRequest body,
            @RequestParam(required = false) String projectId,
            HttpServletRequest request
    ) {
        UUID mId = RequestParams.requiredUuid(mediaId, "mediaId");
        TenantContext ctx = TenantContextHolder.get();
        UUID userId = ctx != null ? ctx.userId() : UUID.fromString("00000000-0000-0000-0000-000000000000");
        String userName = actorName(ctx, "System User");

        mediaService.toggleLegalHold(
                writeScope(projectId),
                mId,
                body.legalHold(),
                userId,
                userName,
                request.getRemoteAddr(),
                body.reason() != null ? body.reason() : "Legal hold toggle"
        );

        return ResponseEntity.noContent().build();
    }

    /**
     * Soft deletes media attachment (refused if legal hold is active).
     */
    @DeleteMapping("/patients/{patientId}/media/{mediaId}")
    @RequirePermission(Permission.CUSTOMER_UPDATE)
    @Operation(summary = "Delete media attachment", description = "Soft deletes media attachment. Refused if legal hold is active.")
    public ResponseEntity<Void> deleteMedia(
            @PathVariable("patientId") String patientId,
            @PathVariable("mediaId") String mediaId,
            @RequestParam(value = "reason", defaultValue = "User deleted") String reason,
            @RequestParam(required = false) String projectId,
            HttpServletRequest request
    ) {
        UUID mId = RequestParams.requiredUuid(mediaId, "mediaId");
        TenantContext ctx = TenantContextHolder.get();
        UUID userId = ctx != null ? ctx.userId() : UUID.fromString("00000000-0000-0000-0000-000000000000");
        String userName = actorName(ctx, "System User");

        mediaService.deleteMedia(
                writeScope(projectId),
                mId,
                userId,
                userName,
                request.getRemoteAddr(),
                reason
        );

        return ResponseEntity.noContent().build();
    }

    /**
     * Retrieves audit trail for a media attachment.
     */
    @GetMapping("/patients/{patientId}/media/{mediaId}/audit")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Get media access audit", description = "Retrieves chronological HIPAA/DEC-007 access audit history for media attachment.")
    public List<ClinicMediaModels.MediaAccessAuditRow> getAttachmentAuditTrail(
            @PathVariable("patientId") String patientId,
            @PathVariable("mediaId") String mediaId,
            @RequestParam(required = false) String projectId
    ) {
        UUID mId = RequestParams.requiredUuid(mediaId, "mediaId");
        return mediaService.getAttachmentAuditTrail(scope(projectId), mId);
    }

    /**
     * Retrieves audit trail for all patient media.
     */
    @GetMapping("/patients/{patientId}/media-audit")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Get patient media audit", description = "Retrieves all access audit entries for patient's media.")
    public List<ClinicMediaModels.MediaAccessAuditRow> getPatientMediaAuditTrail(
            @PathVariable("patientId") String patientId,
            @RequestParam(required = false) String projectId
    ) {
        UUID patId = RequestParams.requiredUuid(patientId, "patientId");
        return mediaService.getPatientMediaAuditTrail(scope(projectId), patId);
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

    private static String actorName(TenantContext ctx, String fallback) {
        if (ctx == null) {
            return fallback;
        }
        if (ctx.userId() != null) {
            return ctx.userId().toString();
        }
        if (ctx.principalId() != null) {
            return ctx.principalId().toString();
        }
        return fallback;
    }
}
