package com.firstelement.crm.clinic.treatment;

import com.firstelement.crm.clinic.ClinicScope;
import com.firstelement.crm.error.ResourceNotFoundException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Service for dental treatment plan management (TRT-01 to TRT-05, Slice S5).
 *
 * <p>Enforces state machine transitions, immutable approved versions,
 * version forking, stage management, and multi-visit tracking.
 */
@Service
public class TreatmentPlanService {

    public static final Set<String> VALID_PLAN_STATUSES = Set.of(
            "DRAFT", "PRESENTED", "ACTIVE", "COMPLETED", "CANCELLED");

    public static final Set<String> VALID_ITEM_STATUSES = Set.of(
            "PROPOSED", "APPROVED", "IN_PROGRESS", "COMPLETED", "CANCELLED");

    // Legal state machine transitions: fromStatus -> set of allowed toStatuses
    private static final Map<String, Set<String>> LEGAL_PLAN_TRANSITIONS = Map.of(
            "DRAFT", Set.of("PRESENTED", "ACTIVE", "CANCELLED"),
            "PRESENTED", Set.of("DRAFT", "ACTIVE", "CANCELLED"),
            "ACTIVE", Set.of("COMPLETED", "CANCELLED"),
            "COMPLETED", Set.of(), // Terminal state
            "CANCELLED", Set.of()  // Terminal state
    );

    private final TreatmentPlanRepository repository;

    public TreatmentPlanService(TreatmentPlanRepository repository) {
        this.repository = repository;
    }

    /**
     * Lists all treatment plans for a patient.
     */
    public List<TreatmentPlanModels.TreatmentPlanHeader> listPlansForPatient(ClinicScope scope, UUID patientId) {
        return repository.listPlansForPatient(scope, patientId);
    }

    /**
     * Finds a single treatment plan header.
     */
    public Optional<TreatmentPlanModels.TreatmentPlanHeader> findPlan(ClinicScope scope, UUID planId) {
        return repository.findPlan(scope, planId);
    }

    /**
     * Retrieves full treatment plan with all ordered items.
     */
    public TreatmentPlanModels.TreatmentPlanDetail getPlanDetail(ClinicScope scope, UUID planId) {
        TreatmentPlanModels.TreatmentPlanHeader header = repository.findPlan(scope, planId)
                .orElseThrow(() -> new ResourceNotFoundException("Treatment plan not found"));
        List<TreatmentPlanModels.TreatmentPlanItemRow> items = repository.listPlanItems(scope, planId);
        return new TreatmentPlanModels.TreatmentPlanDetail(header, items);
    }

    /**
     * Creates a new treatment plan for a patient.
     */
    public UUID createPlan(ClinicScope scope, UUID patientId, TreatmentPlanModels.CreatePlanRequest request) {
        String title = request.title() != null && !request.title().isBlank()
                ? request.title().trim()
                : "خطة علاج جديدة";
        String planType = request.planType() != null && !request.planType().isBlank()
                ? request.planType().toUpperCase()
                : "PRIMARY";

        return repository.insertPlan(scope, patientId, title, request.notes(), 1,
                request.parentPlanId(), planType,
                request.totalDiscount(), request.insuranceEstimatedCoverage(),
                request.patientEstimatedPortion());
    }

    /**
     * Forks a new version of an existing treatment plan.
     *
     * <p>Approved or active plans cannot be modified in place. Modifying them creates
     * version {@code N + 1} in {@code DRAFT} status, copying all items.
     */
    public UUID forkNewVersion(ClinicScope scope, UUID planId, TreatmentPlanModels.ForkPlanVersionRequest request) {
        TreatmentPlanModels.TreatmentPlanHeader current = repository.findPlan(scope, planId)
                .orElseThrow(() -> new ResourceNotFoundException("Treatment plan not found"));

        int nextVersion = current.version() + 1;
        String newTitle = request.title() != null && !request.title().isBlank()
                ? request.title().trim()
                : (current.title() + " (v" + nextVersion + ")");

        String notes = request.changeReason() != null && !request.changeReason().isBlank()
                ? request.changeReason().trim()
                : current.notes();

        // Create new version in DRAFT
        UUID newPlanId = repository.insertPlan(scope, current.patientId(), newTitle, notes,
                nextVersion, current.id(), current.planType(),
                current.totalDiscount(), current.insuranceEstimatedCoverage(),
                current.patientEstimatedPortion());

        // Copy existing procedure items into new plan
        List<TreatmentPlanModels.TreatmentPlanItemRow> items = repository.listPlanItems(scope, planId);
        for (TreatmentPlanModels.TreatmentPlanItemRow item : items) {
            repository.insertItem(scope, newPlanId, item.sequenceOrder(), item.stage(),
                    item.plannedVisits(), item.procedureId(), item.procedureName(),
                    item.toothFdi(), item.doctorId(), item.estimatedCost(),
                    item.discountAmount(), item.insuranceCoverage(), item.patientPortion(),
                    item.notes());
        }

        return newPlanId;
    }

    /**
     * Updates plan status with state machine transition validation.
     */
    public void updatePlanStatus(ClinicScope scope, UUID planId, TreatmentPlanModels.UpdatePlanStatusRequest request) {
        TreatmentPlanModels.TreatmentPlanHeader plan = repository.findPlan(scope, planId)
                .orElseThrow(() -> new ResourceNotFoundException("Treatment plan not found"));

        String toStatus = request.status() != null ? request.status().toUpperCase() : "DRAFT";
        if (!VALID_PLAN_STATUSES.contains(toStatus)) {
            throw new IllegalArgumentException("Invalid plan status: " + toStatus);
        }

        validatePlanTransition(plan.status(), toStatus);

        boolean ok = repository.updatePlanStatus(scope, planId, toStatus, request.notes());
        if (!ok) {
            throw new ResourceNotFoundException("Treatment plan not found");
        }
    }

    /**
     * Approves a treatment plan with patient consent, moving it to ACTIVE.
     */
    public void approvePlan(ClinicScope scope, UUID planId, UUID approverUserId, TreatmentPlanModels.ApprovePlanRequest request) {
        TreatmentPlanModels.TreatmentPlanHeader plan = repository.findPlan(scope, planId)
                .orElseThrow(() -> new ResourceNotFoundException("Treatment plan not found"));

        if ("ACTIVE".equalsIgnoreCase(plan.status())) {
            throw new IllegalStateException("Treatment plan is already active and approved.");
        }
        if ("COMPLETED".equalsIgnoreCase(plan.status()) || "CANCELLED".equalsIgnoreCase(plan.status())) {
            throw new IllegalStateException("Cannot approve a " + plan.status() + " treatment plan.");
        }

        boolean ok = repository.approvePlan(scope, planId, approverUserId, request.consentNotes());
        if (!ok) {
            throw new ResourceNotFoundException("Treatment plan not found");
        }
    }

    /**
     * Cancels a treatment plan with a mandatory or optional reason.
     */
    public void cancelPlan(ClinicScope scope, UUID planId, TreatmentPlanModels.CancelPlanRequest request) {
        TreatmentPlanModels.TreatmentPlanHeader plan = repository.findPlan(scope, planId)
                .orElseThrow(() -> new ResourceNotFoundException("Treatment plan not found"));

        if ("CANCELLED".equalsIgnoreCase(plan.status())) {
            return; // Idempotent
        }
        if ("COMPLETED".equalsIgnoreCase(plan.status())) {
            throw new IllegalStateException("Cannot cancel an already completed treatment plan.");
        }

        String reason = request != null && request.cancellationReason() != null
                ? request.cancellationReason().trim()
                : "Plan cancelled";

        boolean ok = repository.cancelPlan(scope, planId, reason);
        if (!ok) {
            throw new ResourceNotFoundException("Treatment plan not found");
        }
    }

    /**
     * Records or revokes patient consent (TRT-04).
     */
    public void recordConsent(ClinicScope scope, UUID planId, UUID registrarUserId, TreatmentPlanModels.RecordConsentRequest request) {
        boolean ok = repository.recordConsent(scope, planId, request.patientConsent(), registrarUserId, request.consentNotes());
        if (!ok) {
            throw new ResourceNotFoundException("Treatment plan not found");
        }
    }

    /**
     * Adds a procedure item to a plan (TRT-01, TRT-02, TRT-03, multi-visit, stage).
     *
     * <p>Enforces immutability: items cannot be added to an already approved/active or completed plan.
     */
    public UUID addItem(ClinicScope scope, UUID planId, TreatmentPlanModels.CreatePlanItemRequest request) {
        TreatmentPlanModels.TreatmentPlanHeader plan = repository.findPlan(scope, planId)
                .orElseThrow(() -> new ResourceNotFoundException("Treatment plan not found"));

        assertPlanModifiable(plan);

        validateToothFdi(request.toothFdi());

        int seq = request.sequenceOrder() != null && request.sequenceOrder() > 0
                ? request.sequenceOrder()
                : (repository.listPlanItems(scope, planId).size() + 1);

        String stage = request.stage() != null && !request.stage().isBlank()
                ? request.stage().trim()
                : "PHASE_1";

        int plannedVisits = request.plannedVisits() != null && request.plannedVisits() > 0
                ? request.plannedVisits()
                : 1;

        String procedureName = request.procedureName() != null && !request.procedureName().isBlank()
                ? request.procedureName().trim()
                : "إجراء علاجي";

        BigDecimal estimatedCost = request.estimatedCost() != null ? request.estimatedCost() : BigDecimal.ZERO;
        BigDecimal discount = request.discountAmount() != null ? request.discountAmount() : BigDecimal.ZERO;
        BigDecimal insurance = request.insuranceCoverage() != null ? request.insuranceCoverage() : BigDecimal.ZERO;
        BigDecimal patientPortion = request.patientPortion() != null
                ? request.patientPortion()
                : estimatedCost.subtract(discount).subtract(insurance).max(BigDecimal.ZERO);

        return repository.insertItem(scope, planId, seq, stage, plannedVisits,
                request.procedureId(), procedureName, request.toothFdi(),
                request.doctorId(), estimatedCost, discount, insurance, patientPortion,
                request.notes());
    }

    /**
     * Updates a treatment plan item.
     *
     * <p>In ACTIVE plans, execution status (e.g. IN_PROGRESS, COMPLETED) can be updated,
     * but structural parameters cannot be rewritten without forking.
     */
    public void updateItem(ClinicScope scope, UUID planId, UUID itemId, TreatmentPlanModels.UpdatePlanItemRequest request) {
        TreatmentPlanModels.TreatmentPlanHeader plan = repository.findPlan(scope, planId)
                .orElseThrow(() -> new ResourceNotFoundException("Treatment plan not found"));

        if ("COMPLETED".equalsIgnoreCase(plan.status()) || "CANCELLED".equalsIgnoreCase(plan.status())) {
            throw new IllegalStateException("Cannot update items in a " + plan.status() + " treatment plan.");
        }

        if (request.status() != null && !VALID_ITEM_STATUSES.contains(request.status().toUpperCase())) {
            throw new IllegalArgumentException("Invalid item status: " + request.status());
        }

        validateToothFdi(request.toothFdi());

        boolean ok = repository.updateItem(scope, itemId, request.sequenceOrder(), request.stage(),
                request.plannedVisits(), request.completedVisits(), request.procedureId(),
                request.procedureName(), request.toothFdi(), request.doctorId(),
                request.estimatedCost(), request.discountAmount(), request.insuranceCoverage(),
                request.patientPortion(), request.invoicedAmount(), request.collectedAmount(),
                request.status() != null ? request.status().toUpperCase() : null,
                request.notes(), request.outcomeNotes());

        if (!ok) {
            throw new ResourceNotFoundException("Treatment plan item not found");
        }
    }

    /**
     * Deletes an item from a plan.
     */
    public void deleteItem(ClinicScope scope, UUID planId, UUID itemId) {
        TreatmentPlanModels.TreatmentPlanHeader plan = repository.findPlan(scope, planId)
                .orElseThrow(() -> new ResourceNotFoundException("Treatment plan not found"));

        assertPlanModifiable(plan);

        boolean ok = repository.deleteItem(scope, itemId);
        if (!ok) {
            throw new ResourceNotFoundException("Treatment plan item not found");
        }
    }

    /**
     * Deletes a treatment plan.
     */
    public void deletePlan(ClinicScope scope, UUID planId) {
        TreatmentPlanModels.TreatmentPlanHeader plan = repository.findPlan(scope, planId)
                .orElseThrow(() -> new ResourceNotFoundException("Treatment plan not found"));

        if ("ACTIVE".equalsIgnoreCase(plan.status()) || "COMPLETED".equalsIgnoreCase(plan.status())) {
            throw new IllegalStateException("Approved or active treatment plans cannot be deleted. Cancel the plan instead.");
        }

        boolean ok = repository.deletePlan(scope, planId);
        if (!ok) {
            throw new ResourceNotFoundException("Treatment plan not found");
        }
    }

    private static void assertPlanModifiable(TreatmentPlanModels.TreatmentPlanHeader plan) {
        if ("ACTIVE".equalsIgnoreCase(plan.status()) || "COMPLETED".equalsIgnoreCase(plan.status())) {
            throw new IllegalStateException(
                    "Approved treatment plan (v" + plan.version() + ") is immutable. Fork a new version to modify.");
        }
        if ("CANCELLED".equalsIgnoreCase(plan.status())) {
            throw new IllegalStateException("Cannot modify a cancelled treatment plan.");
        }
    }

    public static void validatePlanTransition(String currentStatus, String targetStatus) {
        if (currentStatus.equalsIgnoreCase(targetStatus)) {
            return;
        }
        Set<String> allowed = LEGAL_PLAN_TRANSITIONS.getOrDefault(currentStatus.toUpperCase(), Set.of());
        if (!allowed.contains(targetStatus.toUpperCase())) {
            throw new IllegalStateException(
                    "Illegal treatment plan status transition from " + currentStatus + " to " + targetStatus);
        }
    }

    private static void validateToothFdi(Integer toothFdi) {
        if (toothFdi == null) {
            return; // Whole mouth / arch procedure
        }
        if (toothFdi < 11 || toothFdi > 85) {
            throw new IllegalArgumentException("Tooth FDI number must be between 11 and 85, or null for general procedures.");
        }
    }
}
