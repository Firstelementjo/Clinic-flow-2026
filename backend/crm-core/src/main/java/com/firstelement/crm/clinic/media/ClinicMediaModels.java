package com.firstelement.crm.clinic.media;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Dental Clinic Media & Documents Domain Models (Slice S7).
 * Radiographs, clinical photos, PDFs, consent forms, signed download tokens,
 * and immutable access audit entries.
 */
public final class ClinicMediaModels {

    private ClinicMediaModels() {}

    /** Supported clinical media categories */
    public static final String CAT_RADIOGRAPH_INTRAORAL = "RADIOGRAPH_INTRAORAL";
    public static final String CAT_RADIOGRAPH_PANORAMIC = "RADIOGRAPH_PANORAMIC";
    public static final String CAT_RADIOGRAPH_CEPHALOMETRIC = "RADIOGRAPH_CEPHALOMETRIC";
    public static final String CAT_PHOTO_INTRAORAL = "PHOTO_INTRAORAL";
    public static final String CAT_PHOTO_EXTRAORAL = "PHOTO_EXTRAORAL";
    public static final String CAT_DOCUMENT_PDF = "DOCUMENT_PDF";
    public static final String CAT_CONSENT_FORM = "CONSENT_FORM";
    public static final String CAT_LAB_PRESCRIPTION = "LAB_PRESCRIPTION";
    public static final String CAT_OTHER = "OTHER";

    public static final Set<String> ALLOWED_CATEGORIES = Set.of(
            CAT_RADIOGRAPH_INTRAORAL,
            CAT_RADIOGRAPH_PANORAMIC,
            CAT_RADIOGRAPH_CEPHALOMETRIC,
            CAT_PHOTO_INTRAORAL,
            CAT_PHOTO_EXTRAORAL,
            CAT_DOCUMENT_PDF,
            CAT_CONSENT_FORM,
            CAT_LAB_PRESCRIPTION,
            CAT_OTHER
    );

    /** Supported MIME types */
    public static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/jpeg",
            "image/png",
            "image/webp",
            "image/tiff",
            "application/pdf",
            "application/dicom",
            "image/dicom-rle"
    );

    /** Max file size: 25 megabytes */
    public static final long MAX_FILE_BYTES = 25 * 1024 * 1024;

    /** Access audit actions */
    public static final String ACTION_VIEW = "VIEW";
    public static final String ACTION_DOWNLOAD = "DOWNLOAD";
    public static final String ACTION_SIGNED_URL_MINTED = "SIGNED_URL_MINTED";
    public static final String ACTION_LEGAL_HOLD_SET = "LEGAL_HOLD_SET";
    public static final String ACTION_LEGAL_HOLD_REMOVED = "LEGAL_HOLD_REMOVED";
    public static final String ACTION_SOFT_DELETED = "SOFT_DELETED";

    /** Complete metadata row for patient media/attachment */
    public record MediaRow(
            UUID id,
            UUID organizationId,
            UUID projectId,
            UUID patientId,
            String fileName,
            String contentType,
            long byteSize,
            String storageKey,
            String sha256,
            String category,
            Integer toothFdi,
            Instant acquisitionDate,
            String radiographerName,
            boolean encrypted,
            String encryptionAlgorithm,
            String scanStatus,
            String retentionClass,
            Instant retentionExpiresAt,
            boolean legalHold,
            String notes,
            Instant createdAt,
            Instant updatedAt,
            Instant deletedAt,
            UUID deletedBy,
            String deletionReason
    ) {
        public boolean isDeleted() {
            return deletedAt != null;
        }
    }

    /** Request payload to upload media */
    public record UploadMediaRequest(
            String fileName,
            String contentType,
            byte[] content,
            String category,
            Integer toothFdi,
            Instant acquisitionDate,
            String radiographerName,
            String notes,
            String retentionClass
    ) {}

    /** Short-lived signed download token & URL */
    public record SignedDownloadUrl(
            String token,
            String downloadUrl,
            Instant expiresAt,
            UUID attachmentId,
            String fileName,
            String contentType,
            long byteSize
    ) {}

    /** Immutable access audit log row */
    public record MediaAccessAuditRow(
            UUID id,
            UUID organizationId,
            UUID projectId,
            UUID patientId,
            UUID attachmentId,
            String action,
            UUID accessedByUserId,
            String accessedByUserName,
            String accessIp,
            String notes,
            Instant accessedAt
    ) {}

    /** Filter options for querying clinical media */
    public record MediaFilter(
            String category,
            Integer toothFdi,
            Boolean includeLegalHoldOnly
    ) {}

    /** Legal hold toggle request */
    public record ToggleLegalHoldRequest(
            boolean legalHold,
            String reason
    ) {}
}
