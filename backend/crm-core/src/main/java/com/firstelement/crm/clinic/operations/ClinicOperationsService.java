package com.firstelement.crm.clinic.operations;

import com.firstelement.crm.clinic.ClinicScope;
import com.firstelement.crm.error.ValidationFailedException;
import com.firstelement.security.TenantContext;
import com.firstelement.security.UserRole;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rules for the operations screens.
 *
 * <p>The division of labour with the repository is deliberate: the repository
 * owns the tenant predicate and the SQL, and this class owns the decisions a
 * stored row cannot make for itself — which statuses exist, how long a payout
 * period may be, and who is allowed to delete a record.
 */
@Service
public class ClinicOperationsService {

    private static final Set<String> ALIGNER_STATUSES =
            Set.of("SENT_TO_LAB", "IN_PRODUCTION", "QUALITY_CHECK", "COMPLETED", "ON_HOLD");
    private static final Set<String> PAYOUT_STATUSES = Set.of("PENDING", "PAID");
    private static final int MAX_ALIGNER_STEPS = 200;
    private static final int MAX_PAYOUT_DAYS = 366;

    private final ClinicOperationsRepository repository;

    public ClinicOperationsService(ClinicOperationsRepository repository) {
        this.repository = repository;
    }

    // ------------------------------------------------------------------
    // Clear Aligner Lab
    // ------------------------------------------------------------------

    public List<ClinicOperationsModels.AlignerCaseRow> listAlignerCases(
            ClinicScope scope, String status, String q) {
        if (status != null && !status.isBlank() && !ALIGNER_STATUSES.contains(status.trim().toUpperCase())) {
            throw new ValidationFailedException(
                    "Unknown aligner status.",
                    java.util.Map.of("allowed", ALIGNER_STATUSES));
        }
        return repository.listAlignerCases(scope, status, q);
    }

    @Transactional
    public ClinicOperationsModels.AlignerCaseRow createAlignerCase(
            ClinicScope scope,
            ClinicOperationsModels.CreateAlignerCaseRequest request,
            TenantContext caller) {
        requireClinicalWriter(caller);
        if (request.patientId() == null) {
            throw new ValidationFailedException("patientId is required.");
        }
        if (request.caseType() == null || request.caseType().trim().isBlank()) {
            throw new ValidationFailedException("caseType is required.");
        }
        validateSteps(request.upperSteps(), "upperSteps");
        validateSteps(request.lowerSteps(), "lowerSteps");
        if (request.attachmentsCount() != null && request.attachmentsCount() < 0) {
            throw new ValidationFailedException("attachmentsCount cannot be negative.");
        }
        return repository.createAlignerCase(scope, request);
    }

    @Transactional
    public ClinicOperationsModels.AlignerCaseRow updateAlignerCaseStatus(
            ClinicScope scope, UUID caseId, String status, TenantContext caller) {
        requireClinicalWriter(caller);
        if (status == null || status.trim().isBlank()) {
            throw new ValidationFailedException("status is required.");
        }
        String normalized = status.trim().toUpperCase();
        if (!ALIGNER_STATUSES.contains(normalized)) {
            throw new ValidationFailedException(
                    "Unknown aligner status.",
                    java.util.Map.of("allowed", ALIGNER_STATUSES));
        }
        return repository.updateAlignerCaseStatus(scope, caseId, normalized);
    }

    @Transactional
    public boolean deleteAlignerCase(ClinicScope scope, UUID caseId, TenantContext caller) {
        requireAdmin(caller, "Only a clinic administrator can delete an aligner case.");
        return repository.deleteAlignerCase(scope, caseId);
    }

    // ------------------------------------------------------------------
    // Patient Feedback
    // ------------------------------------------------------------------

    public List<ClinicOperationsModels.FeedbackRow> listFeedback(
            ClinicScope scope, String q, UUID doctorId, Integer minRating) {
        if (minRating != null && (minRating < 0 || minRating > 10)) {
            throw new ValidationFailedException("minRating must be between 0 and 10.");
        }
        return repository.listFeedback(scope, q, doctorId, minRating);
    }

    @Transactional
    public ClinicOperationsModels.FeedbackRow createFeedback(
            ClinicScope scope,
            ClinicOperationsModels.CreateFeedbackRequest request,
            TenantContext caller) {
        if (caller == null) {
            throw new AccessDeniedException("An authenticated caller is required.");
        }
        if (request.patientName() == null || request.patientName().trim().isBlank()) {
            throw new ValidationFailedException("patientName is required.");
        }
        if (request.patientAge() != null && (request.patientAge() < 0 || request.patientAge() > 150)) {
            throw new ValidationFailedException("patientAge must be between 0 and 150.");
        }
        return repository.createFeedback(scope, request);
    }

    @Transactional
    public boolean deleteFeedback(ClinicScope scope, UUID feedbackId, TenantContext caller) {
        requireAdmin(caller, "Only a clinic administrator can delete patient feedback.");
        return repository.deleteFeedback(scope, feedbackId);
    }

    // ------------------------------------------------------------------
    // Doctor Commission & Payouts
    // ------------------------------------------------------------------

    public List<ClinicOperationsModels.DoctorCommissionRow> doctorCommission(
            ClinicScope scope, LocalDateTime from, LocalDateTime to) {
        validateWindow(from, to);
        return repository.doctorCommission(scope, from, to);
    }

    public List<ClinicOperationsModels.DoctorPayoutRow> listPayouts(
            ClinicScope scope, UUID doctorId, String status) {
        if (status != null && !status.isBlank() && !PAYOUT_STATUSES.contains(status.trim().toUpperCase())) {
            throw new ValidationFailedException(
                    "Unknown payout status.",
                    java.util.Map.of("allowed", PAYOUT_STATUSES));
        }
        return repository.listPayouts(scope, doctorId, status);
    }

    @Transactional
    public ClinicOperationsModels.DoctorPayoutRow createPayout(
            ClinicScope scope,
            ClinicOperationsModels.CreateDoctorPayoutRequest request,
            TenantContext caller) {
        requireAdmin(caller, "Only a clinic administrator can settle a doctor payout.");
        if (request.doctorId() == null) {
            throw new ValidationFailedException("doctorId is required.");
        }
        LocalDate start = request.periodStart();
        LocalDate end = request.periodEnd();
        if (start == null || end == null) {
            throw new ValidationFailedException("periodStart and periodEnd are required.");
        }
        if (start.isAfter(end)) {
            throw new ValidationFailedException("periodStart must not be after periodEnd.");
        }
        if (start.plusDays(MAX_PAYOUT_DAYS).isBefore(end)) {
            throw new ValidationFailedException(
                    "A payout period cannot exceed " + MAX_PAYOUT_DAYS + " days.");
        }
        return repository.createPayout(scope, request, caller.userId());
    }

    @Transactional
    public ClinicOperationsModels.DoctorPayoutRow updatePayoutStatus(
            ClinicScope scope, UUID payoutId, String status, TenantContext caller) {
        requireAdmin(caller, "Only a clinic administrator can change a payout.");
        if (status == null || status.trim().isBlank()) {
            throw new ValidationFailedException("status is required.");
        }
        String normalized = status.trim().toUpperCase();
        if (!PAYOUT_STATUSES.contains(normalized)) {
            throw new ValidationFailedException(
                    "Unknown payout status.",
                    java.util.Map.of("allowed", PAYOUT_STATUSES));
        }
        return repository.updatePayoutStatus(scope, payoutId, normalized);
    }

    // ------------------------------------------------------------------
    // Guards
    // ------------------------------------------------------------------

    private static void validateSteps(Integer steps, String field) {
        if (steps == null) {
            return;
        }
        if (steps < 0 || steps > MAX_ALIGNER_STEPS) {
            throw new ValidationFailedException(
                    field + " must be between 0 and " + MAX_ALIGNER_STEPS + ".");
        }
    }

    private static void validateWindow(LocalDateTime from, LocalDateTime to) {
        if (from == null || to == null) {
            throw new ValidationFailedException("from and to are required.");
        }
        if (from.isAfter(to)) {
            throw new ValidationFailedException("from must not be after to.");
        }
    }

    /**
     * Lab and aligner rows are written by the clinicians who produce them, so the
     * guard is the clinic write door (a member of this clinic) rather than an
     * administrator role. Destructive changes are the administrator's.
     */
    private static void requireClinicalWriter(TenantContext caller) {
        if (caller == null) {
            throw new AccessDeniedException("An authenticated caller is required.");
        }
        if (caller.role() != UserRole.ADMIN
                && caller.role() != UserRole.EMPLOYEE
                && caller.role() != UserRole.CLINIC_STAFF) {
            throw new AccessDeniedException("Only clinic staff can write clinical lab records.");
        }
    }

    private static void requireAdmin(TenantContext caller, String message) {
        if (caller == null || caller.role() != UserRole.ADMIN) {
            throw new AccessDeniedException(message);
        }
    }
}
