package com.firstelement.crm.clinic;

import com.firstelement.crm.clinic.ClinicLocationModels.ClinicLocation;
import com.firstelement.crm.clinic.ClinicLocationModels.ClinicLocationInput;
import com.firstelement.crm.clinic.ClinicLocationModels.ProcedureInput;
import com.firstelement.crm.error.ResourceNotFoundException;
import com.firstelement.crm.error.ValidationFailedException;
import com.firstelement.security.TenantContext;
import com.firstelement.security.annotation.RequirePermission;
import com.firstelement.security.permission.AuthorizationService;
import com.firstelement.security.permission.Permission;
import com.firstelement.security.permission.PolicyResource;
import com.firstelement.security.query.TenantQueryGuard;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Clinic master-data reads and writes: physical locations and the service catalogue.
 *
 * <h2>The two permissions, and why writes are not the read grant</h2>
 *
 * <p>Reading a clinic location is a roster-adjacent read: a booking form needs to
 * know which clinic and which slot size. It is therefore guarded by
 * {@link Permission#DOCTOR_READ}, the same grant the existing clinic read surface
 * uses. Writing a location or a service is an administrative act with a commercial
 * consequence — a wrong price becomes an invoice, a wrong discount becomes money
 * the clinic did not collect — so it has its own grants, {@code clinic:manage} and
 * {@code procedure:manage}, held by ADMIN and nobody else by default.
 *
 * <h2>Scope derivation</h2>
 *
 * <p>A read may span every project the caller can reach. A write belongs to exactly
 * one project, so it is refused unless the caller names one (or can reach exactly
 * one). That is the same rule {@code ClinicPatientController.writeScope} applies,
 * moved into the service so a future non-HTTP caller cannot bypass it.
 *
 * <h2>PATCH is a merge, not a replace</h2>
 *
 * <p>Every input field is nullable. On update, a null means "leave this column as it
 * is". That makes the row's status switch a one-field PATCH rather than a
 * read-modify-write in the browser, and it means a form that does not render a field
 * cannot erase it.
 */
@Service
public class ClinicManagementService {

    private static final Set<String> SERVICE_TYPES = Set.of("IN_CLINIC", "ONLINE", "HOME_VISIT");

    /**
     * A data URL is stored in a text column in this slice. 1.5 MB is enough for a
     * logo and small enough that a mistaken upload does not turn a row into a
     * response that every list request has to carry.
     */
    private static final int MAX_IMAGE_LENGTH = 1_500_000;

    private final ClinicManagementRepository repository;
    private final ClinicStaffRepository staff;
    private final ClinicRepository readModel;
    private final TenantQueryGuard guard;
    private final AuthorizationService authorization;
    private final PlanLimitEnforcementService limits;

    public ClinicManagementService(ClinicManagementRepository repository,
                                   ClinicStaffRepository staff,
                                   ClinicRepository readModel,
                                   TenantQueryGuard guard,
                                   AuthorizationService authorization) {
        this(repository, staff, readModel, guard, authorization, null);
    }

    @Autowired
    public ClinicManagementService(ClinicManagementRepository repository,
                                   ClinicStaffRepository staff,
                                   ClinicRepository readModel,
                                   TenantQueryGuard guard,
                                   AuthorizationService authorization,
                                   @Nullable PlanLimitEnforcementService limits) {
        this.repository = repository;
        this.staff = staff;
        this.readModel = readModel;
        this.guard = guard;
        this.authorization = authorization;
        this.limits = limits;
    }

    // ==================================================================
    // locations
    // ==================================================================

    /** Every location in scope, optionally filtered. */
    @RequirePermission(Permission.DOCTOR_READ)
    @Transactional(readOnly = true)
    public List<ClinicLocation> locations(TenantContext caller, UUID requestedProjectId,
                                          String q, String status) {
        require(caller, Permission.DOCTOR_READ);
        ClinicScope scope = ClinicScope.forRequestedProject(guard, caller, requestedProjectId);
        return repository.locations(scope, q, status, ClinicManagementRepository.LOCATION_PAGE);
    }

    /** One location, or empty. */
    @RequirePermission(Permission.DOCTOR_READ)
    @Transactional(readOnly = true)
    public Optional<ClinicLocation> location(TenantContext caller, UUID locationId) {
        require(caller, Permission.DOCTOR_READ);
        ClinicScope scope = ClinicScope.forCaller(caller);
        Optional<ClinicLocation> found = repository.location(scope, locationId);
        found.ifPresent(location ->
            ClinicScope.forRow(caller, location.organizationId(), location.projectId()));
        return found;
    }

    /** Creates a location. */
    @RequirePermission(Permission.CLINIC_MANAGE)
    @Transactional
    public ClinicLocation createLocation(TenantContext caller, UUID requestedProjectId,
                                         ClinicLocationInput input) {
        require(caller, Permission.CLINIC_MANAGE);
        ClinicScope scope = writeScope(caller, requestedProjectId);
        if (limits != null) {
            limits.enforceBranchLimit(scope.projectId());
        }
        ClinicLocationInput normalized = validateLocation(scope, input, false);
        UUID id;
        try {
            id = repository.insertLocation(scope, normalized,
                normalized.slotMinutes() == null ? 30 : normalized.slotMinutes(),
                normalized.isActive() == null || normalized.isActive());
        }
        catch (DuplicateKeyException conflict) {
            throw new ValidationFailedException("A clinic with that name already exists.",
                Map.of("field", "name"));
        }
        if (normalized.frontDeskOfficerId() != null) {
            staff.assignClinic(scope, normalized.frontDeskOfficerId(), id);
        }
        return repository.location(scope, id)
            .orElseThrow(() -> new IllegalStateException("The clinic was inserted but not read back."));
    }

    /** Updates a location, merging only the fields the caller sent. */
    @RequirePermission(Permission.CLINIC_MANAGE)
    @Transactional
    public ClinicLocation updateLocation(TenantContext caller, UUID requestedProjectId,
                                         UUID locationId, ClinicLocationInput input) {
        require(caller, Permission.CLINIC_MANAGE);
        ClinicScope lookupScope = requestedProjectId != null
            ? ClinicScope.forRequestedProject(guard, caller, requestedProjectId)
            : ClinicScope.forCaller(caller);
        ClinicLocation existing = repository.location(lookupScope, locationId)
            .orElseThrow(() -> new ResourceNotFoundException("Resource"));
        UUID targetProjectId = requestedProjectId != null ? requestedProjectId : existing.projectId();
        ClinicScope scope = ClinicScope.forRequestedProject(guard, caller, targetProjectId);
        if (limits != null && !Boolean.TRUE.equals(existing.isActive()) && Boolean.TRUE.equals(input.isActive())) {
            limits.enforceBranchLimit(scope.projectId());
        }
        ClinicLocationInput merged = mergeLocation(existing, input);
        ClinicLocationInput normalized = validateLocation(scope, merged, true);
        boolean updated = repository.updateLocation(scope, locationId, normalized,
            normalized.slotMinutes() == null ? existing.slotMinutes() : normalized.slotMinutes(),
            normalized.isActive() == null ? existing.isActive() : normalized.isActive());
        if (!updated) {
            throw new ResourceNotFoundException("Resource");
        }
        if (normalized.frontDeskOfficerId() != null) {
            staff.assignClinic(scope, normalized.frontDeskOfficerId(), locationId);
        }
        return repository.location(scope, locationId)
            .orElseThrow(() -> new ResourceNotFoundException("Resource"));
    }

    /** Deletes a clinic location in scope. */
    @RequirePermission(Permission.CLINIC_MANAGE)
    @Transactional
    public void deleteLocation(TenantContext caller, UUID requestedProjectId, UUID locationId) {
        require(caller, Permission.CLINIC_MANAGE);
        ClinicScope lookupScope = requestedProjectId != null
            ? ClinicScope.forRequestedProject(guard, caller, requestedProjectId)
            : ClinicScope.forCaller(caller);
        ClinicLocation existing = repository.location(lookupScope, locationId)
            .orElseThrow(() -> new ResourceNotFoundException("Resource"));
        UUID targetProjectId = requestedProjectId != null ? requestedProjectId : existing.projectId();
        ClinicScope scope = ClinicScope.forRequestedProject(guard, caller, targetProjectId);
        boolean deleted = repository.deleteLocation(scope, locationId);
        if (!deleted) {
            throw new ResourceNotFoundException("Resource");
        }
    }

    // ==================================================================
    // service catalogue
    // ==================================================================

    /** Creates a service-catalogue entry. */
    @RequirePermission(Permission.PROCEDURE_MANAGE)
    @Transactional
    public ClinicModels.Procedure createProcedure(TenantContext caller, UUID requestedProjectId,
                                                  ProcedureInput input) {
        require(caller, Permission.PROCEDURE_MANAGE);
        ClinicScope scope = writeScope(caller, requestedProjectId);
        ProcedureInput normalized = validateProcedure(scope, input, false);
        UUID id;
        try {
            id = repository.insertProcedure(scope, normalized,
                normalized.durationMinutes() == null ? 30 : normalized.durationMinutes(),
                normalized.bufferMinutes() == null ? 0 : normalized.bufferMinutes(),
                normalized.isActive() == null || normalized.isActive(),
                Boolean.TRUE.equals(normalized.advancedPayment()),
                normalizeType(normalized.type()));
        }
        catch (DuplicateKeyException conflict) {
            throw new ValidationFailedException("That product code is already used by another service.",
                Map.of("field", "productCode"));
        }
        return readModel.procedure(scope, id)
            .orElseThrow(() -> new IllegalStateException("The service was inserted but not read back."));
    }

    /** Updates a service, merging only the fields the caller sent. */
    @RequirePermission(Permission.PROCEDURE_MANAGE)
    @Transactional
    public ClinicModels.Procedure updateProcedure(TenantContext caller, UUID requestedProjectId,
                                                  UUID procedureId, ProcedureInput input) {
        require(caller, Permission.PROCEDURE_MANAGE);
        ClinicScope lookupScope = requestedProjectId != null
            ? ClinicScope.forRequestedProject(guard, caller, requestedProjectId)
            : ClinicScope.forCaller(caller);
        ClinicModels.Procedure existing = readModel.procedure(lookupScope, procedureId)
            .orElseThrow(() -> new ResourceNotFoundException("Resource"));
        UUID targetProjectId = requestedProjectId != null ? requestedProjectId : existing.projectId();
        ClinicScope scope = ClinicScope.forRequestedProject(guard, caller, targetProjectId);
        ProcedureInput merged = mergeProcedure(existing, input);
        ProcedureInput normalized = validateProcedure(scope, merged, true);
        boolean updated = repository.updateProcedure(scope, procedureId, normalized,
            normalized.durationMinutes() == null ? existing.durationMinutes()
                : normalized.durationMinutes(),
            normalized.bufferMinutes() == null ? existing.bufferMinutes()
                : normalized.bufferMinutes(),
            normalized.isActive() == null ? existing.isActive() : normalized.isActive(),
            normalized.advancedPayment() == null ? existing.advancedPayment()
                : normalized.advancedPayment(),
            normalizeType(normalized.type() == null ? existing.type() : normalized.type()));
        if (!updated) {
            throw new ResourceNotFoundException("Resource");
        }
        return readModel.procedure(scope, procedureId)
            .orElseThrow(() -> new ResourceNotFoundException("Resource"));
    }

    // ==================================================================
    // validation and merge
    // ==================================================================

    private ClinicLocationInput validateLocation(ClinicScope scope, ClinicLocationInput input,
                                                 boolean partial) {
        String name = trimToNull(input.name());
        if (!partial && name == null) {
            throw new ValidationFailedException("A clinic name is required.",
                Map.of("field", "name"));
        }
        if (name != null && name.length() > 160) {
            throw new ValidationFailedException("A clinic name cannot exceed 160 characters.",
                Map.of("field", "name"));
        }
        if (input.slotMinutes() != null && (input.slotMinutes() < 5 || input.slotMinutes() > 480)) {
            throw new ValidationFailedException("Slot minutes must be between 5 and 480.",
                Map.of("field", "slotMinutes"));
        }
        if (Boolean.TRUE.equals(input.clearFrontDeskOfficer())
                && input.frontDeskOfficerId() != null) {
            throw new ValidationFailedException(
                "Choose a front-desk officer or clear the assignment, not both.",
                Map.of("field", "frontDeskOfficerId"));
        }
        if (input.frontDeskOfficerId() != null
                && !repository.officerInScope(scope, input.frontDeskOfficerId())) {
            throw new ValidationFailedException("That front-desk officer is not in this clinic.",
                Map.of("field", "frontDeskOfficerId"));
        }
        if (input.brandImageUrl() != null && input.brandImageUrl().length() > MAX_IMAGE_LENGTH) {
            throw new ValidationFailedException("The brand image is too large. Choose a smaller file.",
                Map.of("field", "brandImageUrl"));
        }
        return new ClinicLocationInput(
            name,
            input.frontDeskOfficerId(),
            input.clearFrontDeskOfficer(),
            trimToNull(input.contactNumber()),
            trimToNull(input.description()),
            trimToNull(input.addressLine()),
            trimToNull(input.country()),
            trimToNull(input.state()),
            trimToNull(input.city()),
            trimToNull(input.postalCode()),
            input.slotMinutes(),
            input.brandImageUrl(),
            input.isActive());
    }

    private ProcedureInput validateProcedure(ClinicScope scope, ProcedureInput input,
                                             boolean partial) {
        String name = trimToNull(input.name());
        if (!partial && name == null) {
            throw new ValidationFailedException("A service name is required.",
                Map.of("field", "name"));
        }
        String specialty = trimToNull(input.specialty());
        if (!partial && specialty == null) {
            throw new ValidationFailedException("A category is required.",
                Map.of("field", "specialty"));
        }
        if (input.durationMinutes() != null
                && (input.durationMinutes() < 5 || input.durationMinutes() > 480)) {
            throw new ValidationFailedException("Service duration must be between 5 and 480 minutes.",
                Map.of("field", "durationMinutes"));
        }
        if (input.bufferMinutes() != null
                && (input.bufferMinutes() < 0 || input.bufferMinutes() > 240)) {
            throw new ValidationFailedException("Buffer minutes must be between 0 and 240.",
                Map.of("field", "bufferMinutes"));
        }
        if (input.timeSlotMinutes() != null
                && (input.timeSlotMinutes() < 5 || input.timeSlotMinutes() > 480)) {
            throw new ValidationFailedException("Time slot must be between 5 and 480 minutes.",
                Map.of("field", "timeSlotMinutes"));
        }
        if (input.price() != null && (input.price().isNaN() || input.price() < 0)) {
            throw new ValidationFailedException("Price cannot be negative.",
                Map.of("field", "price"));
        }
        if (input.discount() != null
                && (input.discount().isNaN() || input.discount() < 0 || input.discount() > 100)) {
            throw new ValidationFailedException("Discount must be between 0 and 100 percent.",
                Map.of("field", "discount"));
        }
        if (input.clinicId() != null && !repository.locationInScope(scope, input.clinicId())) {
            throw new ValidationFailedException("That clinic is not in this project.",
                Map.of("field", "clinicId"));
        }
        if (input.type() != null && !SERVICE_TYPES.contains(normalizeType(input.type()))) {
            throw new ValidationFailedException("Service type must be IN_CLINIC, ONLINE or HOME_VISIT.",
                Map.of("field", "type"));
        }
        if (input.productCode() != null && input.productCode().trim().length() > 64) {
            throw new ValidationFailedException("A product code cannot exceed 64 characters.",
                Map.of("field", "productCode"));
        }
        if (input.imageUrl() != null && input.imageUrl().length() > MAX_IMAGE_LENGTH) {
            throw new ValidationFailedException("The service image is too large. Choose a smaller file.",
                Map.of("field", "imageUrl"));
        }
        return new ProcedureInput(
            name,
            specialty,
            input.durationMinutes(),
            input.bufferMinutes(),
            input.price(),
            input.isActive(),
            trimToNull(input.productCode()),
            trimToNull(input.subCategory()),
            input.clinicId(),
            input.timeSlotMinutes(),
            input.type(),
            trimToNull(input.description()),
            input.discount(),
            input.imageUrl(),
            trimToNull(input.systemService()),
            input.advancedPayment());
    }

    private static ClinicLocationInput mergeLocation(ClinicLocation existing,
                                                     ClinicLocationInput input) {
        // A UUID field cannot tell "absent" from "explicitly null" in a Jackson
        // record, so clearing an assignment needs its own flag. Without it the
        // Unassigned option would be inert and a status-only PATCH could not
        // distinguish "keep the officer" from "remove the officer".
        boolean clearOfficer = Boolean.TRUE.equals(input.clearFrontDeskOfficer());
        // Keep an officer that was sent alongside the clear flag so validation can
        // reject the contradictory pair instead of silently preferring one.
        UUID officerId = input.frontDeskOfficerId() == null
            ? (clearOfficer ? null : existing.frontDeskOfficerId())
            : input.frontDeskOfficerId();
        return new ClinicLocationInput(
            input.name() == null ? existing.name() : input.name(),
            officerId,
            clearOfficer ? Boolean.TRUE : null,
            input.contactNumber() == null ? existing.contactNumber() : input.contactNumber(),
            input.description() == null ? existing.description() : input.description(),
            input.addressLine() == null ? existing.addressLine() : input.addressLine(),
            input.country() == null ? existing.country() : input.country(),
            input.state() == null ? existing.state() : input.state(),
            input.city() == null ? existing.city() : input.city(),
            input.postalCode() == null ? existing.postalCode() : input.postalCode(),
            input.slotMinutes() == null ? existing.slotMinutes() : input.slotMinutes(),
            input.brandImageUrl() == null ? existing.brandImageUrl() : input.brandImageUrl(),
            input.isActive() == null ? existing.isActive() : input.isActive());
    }

    private static ProcedureInput mergeProcedure(ClinicModels.Procedure existing,
                                                 ProcedureInput input) {
        return new ProcedureInput(
            input.name() == null ? existing.name() : input.name(),
            input.specialty() == null ? existing.specialty() : input.specialty(),
            input.durationMinutes() == null ? existing.durationMinutes() : input.durationMinutes(),
            input.bufferMinutes() == null ? existing.bufferMinutes() : input.bufferMinutes(),
            input.price() == null ? existing.price() : input.price(),
            input.isActive() == null ? existing.isActive() : input.isActive(),
            input.productCode() == null ? existing.productCode() : input.productCode(),
            input.subCategory() == null ? existing.subCategory() : input.subCategory(),
            input.clinicId() == null ? existing.clinicId() : input.clinicId(),
            input.timeSlotMinutes() == null ? existing.timeSlotMinutes() : input.timeSlotMinutes(),
            input.type() == null ? existing.type() : input.type(),
            input.description() == null ? existing.description() : input.description(),
            input.discount() == null ? existing.discount() : input.discount(),
            input.imageUrl() == null ? existing.imageUrl() : input.imageUrl(),
            input.systemService() == null ? existing.systemService() : input.systemService(),
            input.advancedPayment() == null ? existing.advancedPayment() : input.advancedPayment());
    }

    private ClinicScope writeScope(TenantContext caller, UUID requestedProjectId) {
        if (requestedProjectId != null) {
            return ClinicScope.forRequestedProject(guard, caller, requestedProjectId);
        }
        ClinicScope scope = ClinicScope.forCaller(caller);
        List<UUID> reachable = scope.accessibleProjectIds();
        if (reachable.size() == 1) {
            return ClinicScope.forRequestedProject(guard, caller, reachable.get(0));
        }
        throw new ValidationFailedException(
            "Choose a clinic before saving: this account can reach more than one.",
            Map.of("field", "projectId"));
    }

    private void require(TenantContext caller, Permission permission) {
        if (caller == null) {
            throw new IllegalStateException(
                "No TenantContext is bound; refusing to serve a clinic write unscoped.");
        }
        authorization.check(caller, permission, PolicyResource.NONE);
    }

    private static String normalizeType(String value) {
        return value == null ? "IN_CLINIC" : value.trim().toUpperCase();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
