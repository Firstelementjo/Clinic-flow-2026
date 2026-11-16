package com.firstelement.crm.clinic.media;

import com.firstelement.crm.clinic.ClinicScope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Service for dental clinic clinical media, radiographs, documents,
 * signed download URLs, legal hold retention, and HIPAA/DEC-007 access audit trail (Slice S7).
 */
@Service
public class ClinicMediaService {

    private static final String HMAC_ALGO = "HmacSHA256";
    private static final Base64.Encoder B64_ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64_DEC = Base64.getUrlDecoder();

    private final ClinicMediaRepository repository;
    private final ClinicMediaStorage storage;
    private final byte[] signingKey;
    private final Duration defaultSignedUrlTtl;

    public ClinicMediaService(
            ClinicMediaRepository repository,
            ClinicMediaStorage storage,
            @Value("${clinic.media.signing-key:firstelement-clinic-hmac-sha256-signing-secret-2026-s7}") String signingKeyStr,
            @Value("${clinic.media.signed-url-ttl-minutes:15}") int signedUrlTtlMinutes
    ) {
        this.repository = repository;
        this.storage = storage;
        this.signingKey = signingKeyStr.getBytes(StandardCharsets.UTF_8);
        this.defaultSignedUrlTtl = Duration.ofMinutes(signedUrlTtlMinutes > 0 ? signedUrlTtlMinutes : 15);
    }

    /**
     * Uploads and stores encrypted clinical media, computes SHA-256 checksum,
     * and records attachment row and audit event.
     */
    public ClinicMediaModels.MediaRow uploadMedia(
            ClinicScope scope,
            UUID patientId,
            ClinicMediaModels.UploadMediaRequest request,
            UUID userId,
            String userName,
            String ipAddress
    ) {
        if (request.content() == null || request.content().length == 0) {
            throw new IllegalArgumentException("Upload file content cannot be empty");
        }
        if (request.content().length > ClinicMediaModels.MAX_FILE_BYTES) {
            throw new IllegalArgumentException("File size exceeds maximum allowed limit (200MB)");
        }

        String contentType = request.contentType() != null ? request.contentType().toLowerCase() : "application/octet-stream";
        if (!ClinicMediaModels.ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new IllegalArgumentException("MIME type '" + contentType + "' is not permitted for clinical media");
        }

        String category = request.category() != null ? request.category().toUpperCase() : ClinicMediaModels.CAT_DOCUMENT_PDF;
        if (!ClinicMediaModels.ALLOWED_CATEGORIES.contains(category)) {
            throw new IllegalArgumentException("Invalid media category: " + category);
        }

        if (request.toothFdi() != null) {
            int tooth = request.toothFdi();
            if (tooth < 11 || tooth > 85) {
                throw new IllegalArgumentException("Invalid tooth FDI code: " + tooth);
            }
        }

        UUID orgId = scope.queryScope().organizationId();
        UUID projectId = scope.queryScope().projectId();
        UUID mediaId = UUID.randomUUID();

        String fileExt = extractExtension(request.fileName());
        String storageKey = storage.allocateKey(orgId, patientId, fileExt);

        String sha256;
        try {
            sha256 = storage.writeEncrypted(storageKey, request.content());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to securely store media file", e);
        }

        Instant now = Instant.now();
        Instant acqDate = request.acquisitionDate() != null ? request.acquisitionDate() : now;

        ClinicMediaModels.MediaRow mediaRow = new ClinicMediaModels.MediaRow(
                mediaId,
                orgId,
                projectId,
                patientId,
                request.fileName(),
                contentType,
                request.content().length,
                storageKey,
                sha256,
                category,
                request.toothFdi(),
                acqDate,
                request.radiographerName(),
                true,
                "AES-256-GCM",
                "CLEAN",
                request.retentionClass() != null ? request.retentionClass() : "CLINICAL_RECORD",
                null,
                false,
                request.notes(),
                now,
                now,
                null,
                null,
                null
        );

        ClinicMediaModels.MediaRow saved = repository.insertMedia(scope, mediaRow);

        repository.recordAccessAudit(
                scope,
                projectId,
                patientId,
                mediaId,
                ClinicMediaModels.ACTION_VIEW,
                userId,
                userName,
                ipAddress,
                "Uploaded media: " + request.fileName() + " (" + category + ")"
        );

        return saved;
    }

    /**
     * Lists all clinical media for a patient within tenant scope.
     */
    public List<ClinicMediaModels.MediaRow> listPatientMedia(
            ClinicScope scope,
            UUID patientId,
            String category,
            Integer toothFdi,
            Boolean legalHoldOnly
    ) {
        return repository.listByPatient(scope, patientId, category, toothFdi, legalHoldOnly);
    }

    /**
     * Gets a single media row by ID.
     */
    public ClinicMediaModels.MediaRow getMedia(ClinicScope scope, UUID mediaId) {
        return repository.findById(scope, mediaId)
                .orElseThrow(() -> new IllegalArgumentException("Media attachment not found: " + mediaId));
    }

    /**
     * Mints a short-lived HMAC-SHA256 signed download URL.
     */
    public ClinicMediaModels.SignedDownloadUrl createSignedDownloadUrl(
            ClinicScope scope,
            UUID mediaId,
            UUID userId,
            String userName,
            String ipAddress,
            Duration customTtl
    ) {
        ClinicMediaModels.MediaRow media = getMedia(scope, mediaId);
        Duration ttl = (customTtl != null && !customTtl.isNegative() && !customTtl.isZero())
                ? customTtl
                : defaultSignedUrlTtl;

        Instant expiresAt = Instant.now().plus(ttl);
        UUID orgId = scope.queryScope().organizationId();

        String payload = orgId + ":" + media.id() + ":" + expiresAt.getEpochSecond() + ":" + UUID.randomUUID();
        String payloadB64 = B64_ENC.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        String signature = sign(payloadB64);
        String token = payloadB64 + "." + signature;

        repository.recordAccessAudit(
                scope,
                media.projectId(),
                media.patientId(),
                media.id(),
                ClinicMediaModels.ACTION_SIGNED_URL_MINTED,
                userId,
                userName,
                ipAddress,
                "Minted signed download URL expiring at: " + expiresAt
        );

        String downloadUrl = "/api/v1/clinic/media/download?token=" + token;

        return new ClinicMediaModels.SignedDownloadUrl(
                token,
                downloadUrl,
                expiresAt,
                media.id(),
                media.fileName(),
                media.contentType(),
                media.byteSize()
        );
    }

    /** Decrypted media payload for streaming to client */
    public record DecryptedMediaPayload(
            String fileName,
            String contentType,
            long byteSize,
            byte[] content,
            String sha256
    ) {}

    /**
     * Downloads and decrypts media by validating HMAC-SHA256 token and checking expiry.
     * Records download access audit entry.
     */
    public DecryptedMediaPayload downloadBySignedToken(
            String token,
            UUID accessingUserId,
            String accessingUserName,
            String clientIp
    ) {
        if (token == null || !token.contains(".")) {
            throw new IllegalArgumentException("Invalid signed download token format");
        }

        String[] parts = token.split("\\.");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Malformed signed download token");
        }

        String payloadB64 = parts[0];
        String expectedSig = sign(payloadB64);
        String actualSig = parts[1];

        // Constant-time signature comparison
        if (!MessageDigest.isEqual(expectedSig.getBytes(StandardCharsets.UTF_8), actualSig.getBytes(StandardCharsets.UTF_8))) {
            throw new SecurityException("Invalid download token signature");
        }

        String payload = new String(B64_DEC.decode(payloadB64), StandardCharsets.UTF_8);
        String[] fields = payload.split(":");
        if (fields.length < 3) {
            throw new IllegalArgumentException("Invalid download token payload");
        }

        UUID orgId = UUID.fromString(fields[0]);
        UUID mediaId = UUID.fromString(fields[1]);
        long expiresAtEpoch = Long.parseLong(fields[2]);

        if (Instant.now().getEpochSecond() > expiresAtEpoch) {
            throw new SecurityException("Signed download token has expired");
        }

        // Retrieve media row scoped to this organization
        ClinicMediaModels.MediaRow media = repository.findByIdAndOrg(orgId, mediaId)
                .orElseThrow(() -> new IllegalArgumentException("Media attachment not found for tenant: " + mediaId));

        byte[] decryptedBytes;
        try {
            decryptedBytes = storage.readDecrypted(media.storageKey());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read and decrypt media file from storage", e);
        }

        // Verify SHA-256 integrity
        String computedSha256 = ClinicMediaStorage.computeSha256(decryptedBytes);
        if (media.sha256() != null && !computedSha256.equalsIgnoreCase(media.sha256())) {
            throw new IllegalStateException("Media integrity verification failed: SHA-256 checksum mismatch");
        }

        UUID actorId = accessingUserId != null ? accessingUserId : UUID.fromString("00000000-0000-0000-0000-000000000000");
        String actorName = accessingUserName != null ? accessingUserName : "Signed Token Consumer";

        repository.recordAccessAuditForOrg(
                orgId,
                media.projectId(),
                media.patientId(),
                media.id(),
                ClinicMediaModels.ACTION_DOWNLOAD,
                actorId,
                actorName,
                clientIp,
                "Downloaded media file via signed token"
        );

        return new DecryptedMediaPayload(
                media.fileName(),
                media.contentType(),
                decryptedBytes.length,
                decryptedBytes,
                computedSha256
        );
    }

    /**
     * Toggles legal hold on an attachment and records audit entry.
     */
    public boolean toggleLegalHold(
            ClinicScope scope,
            UUID mediaId,
            boolean legalHold,
            UUID userId,
            String userName,
            String clientIp,
            String reason
    ) {
        ClinicMediaModels.MediaRow media = getMedia(scope, mediaId);
        boolean updated = repository.updateLegalHold(scope, mediaId, legalHold);
        if (updated) {
            String action = legalHold ? ClinicMediaModels.ACTION_LEGAL_HOLD_SET : ClinicMediaModels.ACTION_LEGAL_HOLD_REMOVED;
            repository.recordAccessAudit(
                    scope,
                    media.projectId(),
                    media.patientId(),
                    media.id(),
                    action,
                    userId,
                    userName,
                    clientIp,
                    "Legal hold " + (legalHold ? "applied" : "released") + ": " + reason
            );
        }
        return updated;
    }

    /**
     * Deletes media attachment, strictly obeying DEC-007 legal hold protection.
     */
    public boolean deleteMedia(
            ClinicScope scope,
            UUID mediaId,
            UUID userId,
            String userName,
            String clientIp,
            String deletionReason
    ) {
        ClinicMediaModels.MediaRow media = getMedia(scope, mediaId);

        if (media.legalHold()) {
            throw new IllegalStateException("Refusing deletion: Media attachment is protected under active legal hold (DEC-007)");
        }

        boolean softDeleted = repository.softDelete(scope, mediaId, userId, deletionReason);
        if (softDeleted) {
            repository.recordAccessAudit(
                    scope,
                    media.projectId(),
                    media.patientId(),
                    media.id(),
                    ClinicMediaModels.ACTION_SOFT_DELETED,
                    userId,
                    userName,
                    clientIp,
                    "Soft deleted media: " + deletionReason
            );
        }
        return softDeleted;
    }

    /**
     * Returns access audit history for an attachment.
     */
    public List<ClinicMediaModels.MediaAccessAuditRow> getAttachmentAuditTrail(ClinicScope scope, UUID mediaId) {
        return repository.listAccessAudit(scope, mediaId);
    }

    /**
     * Returns access audit history for all media of a patient.
     */
    public List<ClinicMediaModels.MediaAccessAuditRow> getPatientMediaAuditTrail(ClinicScope scope, UUID patientId) {
        return repository.listPatientAccessAudit(scope, patientId);
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(signingKey, HMAC_ALGO));
            byte[] signature = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return B64_ENC.encodeToString(signature);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC signing failed", e);
        }
    }

    private static String extractExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "bin";
        }
        return filename.substring(filename.lastIndexOf('.') + 1);
    }
}
