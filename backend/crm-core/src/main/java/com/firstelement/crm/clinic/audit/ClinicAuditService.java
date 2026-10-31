package com.firstelement.crm.clinic.audit;

import com.firstelement.crm.clinic.ClinicScope;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Service orchestrating emergency break-glass access, tamper-evident audit logging,
 * and curated compliance/operational reports (AUD-01 to AUD-06, RPT-01 to RPT-05).
 */
@Service
public class ClinicAuditService {

    private final ClinicAuditRepository repository;

    public ClinicAuditService(ClinicAuditRepository repository) {
        this.repository = repository;
    }

    // ------------------------------------------------------------------
    // 1. Break-Glass Emergency Access
    // ------------------------------------------------------------------

    public ClinicAuditModels.BreakGlassGrantRow requestBreakGlass(
            ClinicScope scope,
            UUID requestedByUserId,
            String actorUserName,
            String actorRole,
            ClinicAuditModels.CreateBreakGlassRequest request) {
        if (request == null || request.reason() == null || request.reason().trim().length() < 5) {
            throw new IllegalArgumentException("A valid, detailed emergency clinical justification (at least 5 characters) is required for break-glass access.");
        }

        int hours = request.durationHours();
        if (hours <= 0 || hours > 24) {
            hours = 4; // default 4-hour emergency window
        }

        Instant now = Instant.now();
        Instant expiresAt = now.plus(Duration.ofHours(hours));
        UUID grantId = UUID.randomUUID();

        ClinicAuditModels.BreakGlassGrantRow grant = repository.insertBreakGlass(
                scope, grantId, request.patientId(), requestedByUserId, request.reason().trim(), now, expiresAt);

        // Audit the emergency break-glass activation
        recordAuditEvent(scope, new ClinicAuditModels.RecordClinicAuditRequest(
                request.patientId(),
                requestedByUserId,
                actorUserName != null ? actorUserName : "Clinician",
                actorRole != null ? actorRole : "DOCTOR",
                "BREAK_GLASS_ACTIVATED",
                "BREAK_GLASS",
                grantId.toString(),
                grantId,
                null,
                null,
                "{\"durationHours\": " + hours + ", \"reason\": \"" + request.reason().replace("\"", "\\\"") + "\"}"
        ));

        return grant;
    }

    public boolean revokeBreakGlass(
            ClinicScope scope,
            UUID grantId,
            UUID revokerUserId,
            String actorUserName,
            String actorRole,
            ClinicAuditModels.RevokeBreakGlassRequest request) {
        String reason = (request != null && request.revocationReason() != null)
                ? request.revocationReason().trim()
                : "Emergency access terminated early by user";

        Instant now = Instant.now();
        boolean revoked = repository.revokeBreakGlass(scope, grantId, revokerUserId, now, reason);

        if (revoked) {
            recordAuditEvent(scope, new ClinicAuditModels.RecordClinicAuditRequest(
                    null,
                    revokerUserId,
                    actorUserName != null ? actorUserName : "Staff",
                    actorRole != null ? actorRole : "ADMIN",
                    "BREAK_GLASS_REVOKED",
                    "BREAK_GLASS",
                    grantId.toString(),
                    grantId,
                    null,
                    null,
                    "{\"revocationReason\": \"" + reason.replace("\"", "\\\"") + "\"}"
            ));
        }

        return revoked;
    }

    public boolean reviewBreakGlass(
            ClinicScope scope,
            UUID grantId,
            UUID reviewerUserId,
            String actorUserName,
            String actorRole,
            ClinicAuditModels.ReviewBreakGlassRequest request) {
        if (request == null || request.reviewStatus() == null
                || (!"APPROVED".equals(request.reviewStatus()) && !"FLAGGED".equals(request.reviewStatus()))) {
            throw new IllegalArgumentException("Review status must be either APPROVED or FLAGGED.");
        }
        if ("FLAGGED".equals(request.reviewStatus()) && (request.reviewNotes() == null || request.reviewNotes().trim().isEmpty())) {
            throw new IllegalArgumentException("Review notes are mandatory when flagging emergency break-glass access.");
        }

        Instant now = Instant.now();
        boolean reviewed = repository.reviewBreakGlass(
                scope, grantId, reviewerUserId, request.reviewStatus(), now, request.reviewNotes());

        if (reviewed) {
            recordAuditEvent(scope, new ClinicAuditModels.RecordClinicAuditRequest(
                    null,
                    reviewerUserId,
                    actorUserName != null ? actorUserName : "Compliance Officer",
                    actorRole != null ? actorRole : "ADMIN",
                    "BREAK_GLASS_REVIEWED",
                    "BREAK_GLASS",
                    grantId.toString(),
                    grantId,
                    null,
                    null,
                    "{\"status\": \"" + request.reviewStatus() + "\", \"notes\": \""
                            + (request.reviewNotes() != null ? request.reviewNotes().replace("\"", "\\\"") : "") + "\"}"
            ));
        }

        return reviewed;
    }

    public boolean hasActiveBreakGlass(ClinicScope scope, UUID userId, UUID patientId) {
        return repository.findActiveBreakGlassForUserAndPatient(scope, userId, patientId, Instant.now()).isPresent();
    }

    public Optional<ClinicAuditModels.BreakGlassGrantRow> getBreakGlassGrant(ClinicScope scope, UUID grantId) {
        return repository.findBreakGlassById(scope, grantId);
    }

    public List<ClinicAuditModels.BreakGlassGrantRow> listActiveBreakGlass(ClinicScope scope) {
        return repository.listActiveBreakGlass(scope, Instant.now());
    }

    public List<ClinicAuditModels.BreakGlassGrantRow> listBreakGlassPendingReview(ClinicScope scope) {
        return repository.listBreakGlassPendingReview(scope);
    }

    // ------------------------------------------------------------------
    // 2. Tamper-Evident Immutable Audit Log
    // ------------------------------------------------------------------

    public ClinicAuditModels.ClinicAuditRow recordAuditEvent(
            ClinicScope scope, ClinicAuditModels.RecordClinicAuditRequest request) {
        Objects.requireNonNull(request, "RecordClinicAuditRequest must not be null");

        UUID auditId = UUID.randomUUID();
        Instant now = Instant.now();
        String previousHash = repository.findLatestAuditHash(scope);
        String currentHash = computeHash(previousHash, scope.queryScope().organizationId(), request.actorUserId(),
                request.action(), request.resourceType(), request.resourceId(), now.toEpochMilli());

        return repository.insertAuditLog(
                scope,
                auditId,
                request.patientId(),
                request.actorUserId(),
                request.actorUserName(),
                request.actorRole(),
                request.action(),
                request.resourceType(),
                request.resourceId(),
                request.breakGlassGrantId(),
                request.ipAddress(),
                request.userAgent(),
                request.metadataJson(),
                currentHash,
                now);
    }

    public List<ClinicAuditModels.ClinicAuditRow> queryAuditLog(
            ClinicScope scope,
            UUID patientId,
            UUID actorUserId,
            String action,
            String resourceType,
            Instant from,
            Instant to,
            int limit) {
        return repository.queryAuditLog(scope, patientId, actorUserId, action, resourceType, from, to, limit);
    }

    /**
     * Cryptographic verification of the SHA-256 chain integrity.
     */
    public boolean verifyAuditChainIntegrity(ClinicScope scope) {
        List<ClinicAuditModels.ClinicAuditRow> logs = repository.queryAuditLog(
                scope, null, null, null, null, null, null, 500);

        // Reverse to oldest first for linear hash-chain evaluation
        List<ClinicAuditModels.ClinicAuditRow> chronological = logs.reversed();
        String prevHash = ClinicAuditRepository.GENESIS_HASH;

        for (ClinicAuditModels.ClinicAuditRow row : chronological) {
            String expectedHash = computeHash(
                    prevHash,
                    row.organizationId(),
                    row.actorUserId(),
                    row.action(),
                    row.resourceType(),
                    row.resourceId(),
                    row.occurredAt().toEpochMilli());

            if (!expectedHash.equalsIgnoreCase(row.tamperHash())) {
                return false;
            }
            prevHash = row.tamperHash();
        }
        return true;
    }

    // ------------------------------------------------------------------
    // 3. Curated Parameterized Reports
    // ------------------------------------------------------------------

    public List<ClinicAuditModels.AppointmentProductivityRow> getAppointmentProductivity(
            ClinicScope scope, Instant from, Instant to, UUID doctorId) {
        Instant start = from != null ? from : Instant.now().minus(Duration.ofDays(30));
        Instant end = to != null ? to : Instant.now();
        return repository.queryAppointmentProductivity(scope, start, end, doctorId);
    }

    public List<ClinicAuditModels.ProcedureUtilizationRow> getProcedureUtilization(
            ClinicScope scope, Instant from, Instant to) {
        Instant start = from != null ? from : Instant.now().minus(Duration.ofDays(30));
        Instant end = to != null ? to : Instant.now();
        return repository.queryProcedureUtilization(scope, start, end);
    }

    public List<ClinicAuditModels.FinancialCollectionSummaryRow> getFinancialCollectionSummary(
            ClinicScope scope, Instant from, Instant to) {
        Instant start = from != null ? from : Instant.now().minus(Duration.ofDays(30));
        Instant end = to != null ? to : Instant.now();
        return repository.queryFinancialCollectionSummary(scope, start, end);
    }

    public List<ClinicAuditModels.PatientRetentionRecallRow> getPatientRetentionRecall(
            ClinicScope scope, Integer overdueDaysThreshold) {
        int threshold = overdueDaysThreshold != null ? overdueDaysThreshold : 180; // 6 months standard recall
        return repository.queryPatientRetentionRecall(scope, threshold);
    }

    public List<ClinicAuditModels.BreakGlassComplianceRow> getBreakGlassCompliance(
            ClinicScope scope, Instant from, Instant to) {
        Instant start = from != null ? from : Instant.now().minus(Duration.ofDays(30));
        Instant end = to != null ? to : Instant.now();
        return repository.queryBreakGlassCompliance(scope, start, end);
    }

    public ClinicAuditModels.ClinicAuditExportView exportReport(
            ClinicScope scope,
            String exportType,
            Instant from,
            Instant to,
            UUID actorUserId,
            String actorUserName,
            String actorRole) {
        Instant start = from != null ? from : Instant.now().minus(Duration.ofDays(30));
        Instant end = to != null ? to : Instant.now();
        Instant now = Instant.now();

        List<?> records;
        String type = exportType != null ? exportType.toUpperCase() : "APPOINTMENTS";

        switch (type) {
            case "PROCEDURES" -> records = repository.queryProcedureUtilization(scope, start, end);
            case "FINANCIALS" -> records = repository.queryFinancialCollectionSummary(scope, start, end);
            case "PATIENT_RECALL" -> records = repository.queryPatientRetentionRecall(scope, 180);
            case "BREAK_GLASS" -> records = repository.queryBreakGlassCompliance(scope, start, end);
            case "APPOINTMENTS" -> records = repository.queryAppointmentProductivity(scope, start, end, null);
            default -> throw new IllegalArgumentException("Unknown report export type: " + exportType);
        }

        String signature = computeHash("EXPORT", scope.queryScope().organizationId(), actorUserId, type, "EXPORT", String.valueOf(records.size()), now.toEpochMilli());

        // Audit the export action
        recordAuditEvent(scope, new ClinicAuditModels.RecordClinicAuditRequest(
                null,
                actorUserId,
                actorUserName != null ? actorUserName : "Staff",
                actorRole != null ? actorRole : "ADMIN",
                "DATA_EXPORTED",
                "REPORT",
                type,
                null,
                null,
                null,
                "{\"recordCount\": " + records.size() + ", \"signature\": \"" + signature + "\"}"
        ));

        return new ClinicAuditModels.ClinicAuditExportView(type, now, records.size(), signature, records);
    }

    // ------------------------------------------------------------------
    // Cryptographic Helpers
    // ------------------------------------------------------------------

    private String computeHash(
            String prevHash,
            UUID orgId,
            UUID actorUserId,
            String action,
            String resourceType,
            String resourceId,
            long epochMilli) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String payload = String.format("%s:%s:%s:%s:%s:%s:%d",
                    prevHash,
                    orgId != null ? orgId.toString() : "null",
                    actorUserId != null ? actorUserId.toString() : "null",
                    action,
                    resourceType,
                    resourceId != null ? resourceId : "null",
                    epochMilli);
            byte[] encodedhash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(encodedhash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
