package com.firstelement.crm.clinic.audit;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Domain models for dental clinic break-glass emergency access, immutable audit streaming,
 * and curated compliance/operational reports (AUD-01 to AUD-06, RPT-01 to RPT-05).
 */
public final class ClinicAuditModels {

    private ClinicAuditModels() {
    }

    // ------------------------------------------------------------------
    // 1. Break-Glass Emergency Access
    // ------------------------------------------------------------------

    public record BreakGlassGrantRow(
            UUID id,
            UUID organizationId,
            UUID projectId,
            UUID patientId,
            UUID requestedByUserId,
            String reason,
            Instant grantedAt,
            Instant expiresAt,
            Instant revokedAt,
            UUID revokedByUserId,
            String revocationReason,
            String reviewStatus,
            UUID reviewedByUserId,
            Instant reviewedAt,
            String reviewNotes) {

        public boolean isActive(Instant now) {
            return revokedAt == null && expiresAt.isAfter(now);
        }
    }

    public record CreateBreakGlassRequest(
            UUID patientId,
            String reason,
            int durationHours) {
    }

    public record ReviewBreakGlassRequest(
            String reviewStatus, // APPROVED or FLAGGED
            String reviewNotes) {
    }

    public record RevokeBreakGlassRequest(
            String revocationReason) {
    }

    // ------------------------------------------------------------------
    // 2. Immutable / Tamper-Evident Clinical Audit Log
    // ------------------------------------------------------------------

    public record ClinicAuditRow(
            UUID id,
            UUID organizationId,
            UUID projectId,
            UUID patientId,
            UUID actorUserId,
            String actorUserName,
            String actorRole,
            String action,
            String resourceType,
            String resourceId,
            UUID breakGlassGrantId,
            String ipAddress,
            String userAgent,
            String metadataJson,
            String tamperHash,
            Instant occurredAt) {
    }

    public record RecordClinicAuditRequest(
            UUID patientId,
            UUID actorUserId,
            String actorUserName,
            String actorRole,
            String action,
            String resourceType,
            String resourceId,
            UUID breakGlassGrantId,
            String ipAddress,
            String userAgent,
            String metadataJson) {
    }

    // ------------------------------------------------------------------
    // 3. Curated Parameterized Reporting Rows
    // ------------------------------------------------------------------

    public record AppointmentProductivityRow(
            UUID doctorId,
            String doctorName,
            String specialty,
            long totalAppointments,
            long completedAppointments,
            long cancelledAppointments,
            long noShowAppointments,
            double completionRatePercent,
            BigDecimal totalInvoiced) {
    }

    public record ProcedureUtilizationRow(
            String procedureCode,
            String procedureName,
            String category,
            long procedureCount,
            BigDecimal totalRevenue,
            BigDecimal averagePrice) {
    }

    public record FinancialCollectionSummaryRow(
            String currency,
            BigDecimal totalInvoiced,
            BigDecimal totalCollected,
            BigDecimal totalOutstanding,
            double collectionRatePercent,
            long invoiceCount,
            long paymentCount) {
    }

    public record PatientRetentionRecallRow(
            UUID patientId,
            String patientName,
            String phone,
            Instant lastVisitDate,
            long daysSinceLastVisit,
            String recallStatus, // OVERDUE, DUE_SOON, UP_TO_DATE
            String recommendedProcedure) {
    }

    public record BreakGlassComplianceRow(
            UUID grantId,
            String requesterName,
            String patientName,
            String reason,
            Instant grantedAt,
            Instant expiresAt,
            boolean active,
            String reviewStatus,
            String reviewerName,
            Instant reviewedAt,
            String reviewNotes) {
    }

    public record ClinicAuditExportView(
            String exportType,
            Instant generatedAt,
            int totalRecords,
            String exportSignature,
            List<?> records) {
    }
}
