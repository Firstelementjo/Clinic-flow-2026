package com.firstelement.crm.clinic;

import com.firstelement.crm.clinic.ClinicDoctorModels.DoctorProfile;
import com.firstelement.crm.clinic.ClinicDoctorModels.DoctorProfileInput;
import com.firstelement.crm.clinic.ClinicDoctorModels.Qualification;
import com.firstelement.crm.clinic.ClinicDoctorModels.QualificationInput;
import com.firstelement.crm.clinic.FrontDeskModels.FrontDeskOfficer;
import com.firstelement.crm.clinic.FrontDeskModels.FrontDeskOfficerInput;
import com.firstelement.crm.error.ResourceNotFoundException;
import com.firstelement.crm.error.ValidationFailedException;
import com.firstelement.security.TenantContext;
import com.firstelement.security.annotation.RequirePermission;
import com.firstelement.security.auth.PasswordVerifier;
import com.firstelement.security.permission.AuthorizationService;
import com.firstelement.security.permission.Permission;
import com.firstelement.security.permission.PolicyResource;
import com.firstelement.security.query.TenantQueryGuard;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
 * Doctor-profile and front-desk administration.
 *
 * <h2>Why the two screens share one service</h2>
 *
 * <p>They are one workflow: the clinic administrator adds a clinician to the roster
 * and adds the receptionist who books them. Separating the services would duplicate
 * the scope derivation, the PATCH merge rule and the validation vocabulary, and
 * would let the two drift on a rule that must be identical �?� a write belongs to
 * exactly one project. The repositories are still separate in spirit: doctors use
 * the doctor tables, front desk uses the account and membership tables.
 *
 * <h2>The permission split</h2>
 *
 * <p>{@link Permission#DOCTOR_READ_MANAGEMENT} is the contact-detail read Phase 33
 * split away from {@link Permission#DOCTOR_READ}. The existing roster endpoint keeps
 * using {@code doctor:read} and keeps withholding email and phone; this service is
 * the only caller of the management read. The front-desk grants are separate again
 * because a reception roster is an account directory.
 *
 * <h2>PATCH is a merge</h2>
 *
 * <p>Every input field is nullable. On update a null means "unchanged", so the
 * status switch can send only {@code isActive} and a form that omits a field cannot
 * erase it. The one deliberate exception is a qualification list: null leaves the
 * rows alone, an empty list removes them. Those are different requests and are kept
 * different.
 */
@Service
public class ClinicStaffService {

    private static final Set<String> GENDERS = Set.of("MALE", "FEMALE", "OTHER");
    private static final Set<String> VERIFICATIONS = Set.of("PENDING", "VERIFIED");

    /** Matches the image allowance in the clinic master-data service. */
    private static final int MAX_IMAGE_LENGTH = 1_500_000;
    private static final int MAX_QUALIFICATIONS = 20;
    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final int MAX_PASSWORD_LENGTH = 200;

    private final ClinicStaffRepository repository;
    private final TenantQueryGuard guard;
    private final AuthorizationService authorization;
    private final PasswordVerifier passwords;
    private final PlanLimitEnforcementService limits;

    public ClinicStaffService(ClinicStaffRepository repository,
                              TenantQueryGuard guard,
                              AuthorizationService authorization,
                              PasswordVerifier passwords) {
        this(repository, guard, authorization, passwords, null);
    }

    @Autowired
    public ClinicStaffService(ClinicStaffRepository repository,
                              TenantQueryGuard guard,
                              AuthorizationService authorization,
                              PasswordVerifier passwords,
                              @Nullable PlanLimitEnforcementService limits) {
        this.repository = repository;
        this.guard = guard;
        this.authorization = authorization;
        this.passwords = passwords;
        this.limits = limits;
    }

    // ==================================================================
    // doctor profiles
    // ==================================================================

    /** Every non-deleted doctor in scope, optionally filtered. */
    @RequirePermission(Permission.DOCTOR_READ_MANAGEMENT)
    @Transactional(readOnly = true)
    public List<DoctorProfile> doctorProfiles(TenantContext caller, UUID requestedProjectId,
                                              String q, String status) {
        require(caller, Permission.DOCTOR_READ_MANAGEMENT);
        ClinicScope scope = ClinicScope.forRequestedProject(guard, caller, requestedProjectId);
        return repository.doctors(scope, q, statusFilter(status),
            ClinicStaffRepository.STAFF_PAGE);
    }

    /** One doctor with the qualification rows the edit drawer renders. */
    @RequirePermission(Permission.DOCTOR_READ_MANAGEMENT)
    @Transactional(readOnly = true)
    public Optional<DoctorProfile> doctorProfile(TenantContext caller, UUID requestedProjectId,
                                                 UUID doctorId) {
        require(caller, Permission.DOCTOR_READ_MANAGEMENT);
        ClinicScope scope = ClinicScope.forRequestedProject(guard, caller, requestedProjectId);
        return doctorDetail(scope, doctorId);
    }

    /** Creates a doctor profile. There is deliberately no password field. */
    @RequirePermission(Permission.DOCTOR_MANAGE)
    @Transactional
    public DoctorProfile createDoctor(TenantContext caller, UUID requestedProjectId,
                                      DoctorProfileInput input) {
        require(caller, Permission.DOCTOR_MANAGE);
        ClinicScope scope = writeScope(caller, requestedProjectId);
        DoctorProfileInput normalized = validateDoctor(scope, input, false);
        String name = fullName(normalized.firstName(), normalized.lastName());
        String specialty = trimToNull(normalized.expert()) == null
            ? "General" : trimToNull(normalized.expert());
        boolean active = normalized.isActive() == null || normalized.isActive();
        String verification = normalizeVerification(normalized.verificationStatus(), "VERIFIED");
        UUID id;
        try {
            id = repository.insertDoctor(scope, normalized, name, specialty, active, verification);
        }
        catch (DuplicateKeyException conflict) {
            throw field("A doctor with that email already exists.", "email");
        }
        if (normalized.qualifications() != null) {
            repository.replaceQualifications(scope, id, normalized.qualifications());
        }
        return doctorDetail(scope, id)
            .orElseThrow(() -> new IllegalStateException("The doctor was inserted but not read back."));
    }

    /** Updates a doctor, merging only the fields the caller sent. */
    @RequirePermission(Permission.DOCTOR_MANAGE)
    @Transactional
    public DoctorProfile updateDoctor(TenantContext caller, UUID requestedProjectId,
                                      UUID doctorId, DoctorProfileInput input) {
        require(caller, Permission.DOCTOR_MANAGE);
        ClinicScope scope = writeScope(caller, requestedProjectId);
        DoctorProfile existing = doctorDetail(scope, doctorId)
            .orElseThrow(() -> new ResourceNotFoundException("Doctor"));
        DoctorProfileInput merged = mergeDoctor(existing, input);
        DoctorProfileInput normalized = validateDoctor(scope, merged, true);
        String name = fullName(normalized.firstName(), normalized.lastName());
        String specialty = input.expert() == null
            ? existing.specialty()
            : (trimToNull(input.expert()) == null ? "General" : trimToNull(input.expert()));
        boolean active = normalized.isActive() == null
            ? existing.isActive() : normalized.isActive();
        String verification = normalizeVerification(
            normalized.verificationStatus(), existing.verificationStatus());
        if (!repository.updateDoctor(scope, doctorId, normalized, name, specialty,
                active, verification)) {
            throw new ResourceNotFoundException("Doctor");
        }
        if (input.qualifications() != null) {
            repository.replaceQualifications(scope, doctorId, normalized.qualifications());
        }
        return doctorDetail(scope, doctorId)
            .orElseThrow(() -> new ResourceNotFoundException("Doctor"));
    }

    /**
     * Retires a doctor.
     *
     * <p>Soft delete, because clinical encounters and prescriptions reference the
     * row with {@code ON DELETE RESTRICT}. The list filters {@code deletedAt}, so
     * the doctor disappears from this screen while the audit history still names
     * the clinician.
     */
    @RequirePermission(Permission.DOCTOR_MANAGE)
    @Transactional
    public void deleteDoctor(TenantContext caller, UUID requestedProjectId, UUID doctorId) {
        require(caller, Permission.DOCTOR_MANAGE);
        ClinicScope scope = writeScope(caller, requestedProjectId);
        if (!repository.softDeleteDoctor(scope, doctorId)) {
            throw new ResourceNotFoundException("Doctor");
        }
    }

    // ==================================================================
    // front desk
    // ==================================================================

    /** Every front-desk officer in scope, optionally filtered. */
    @RequirePermission(Permission.FRONT_DESK_READ)
    @Transactional(readOnly = true)
    public List<FrontDeskOfficer> frontDeskOfficers(TenantContext caller, UUID requestedProjectId,
                                                    String q, String status) {
        require(caller, Permission.FRONT_DESK_READ);
        ClinicScope scope = ClinicScope.forRequestedProject(guard, caller, requestedProjectId);
        return repository.frontDeskOfficers(scope, q, statusFilter(status),
            ClinicStaffRepository.STAFF_PAGE);
    }

    /** One front-desk officer, or empty. */
    @RequirePermission(Permission.FRONT_DESK_READ)
    @Transactional(readOnly = true)
    public Optional<FrontDeskOfficer> frontDeskOfficer(TenantContext caller,
                                                       UUID requestedProjectId,
                                                       UUID userId) {
        require(caller, Permission.FRONT_DESK_READ);
        ClinicScope scope = ClinicScope.forRequestedProject(guard, caller, requestedProjectId);
        Optional<FrontDeskOfficer> found = repository.frontDeskOfficer(scope, userId);
        found.ifPresent(officer ->
            ClinicScope.forRow(caller, officer.organizationId(), officer.projectId()));
        return found;
    }

    /** Returns the active staff user count and maximum allowed staff quota for the clinic. */
    @RequirePermission(Permission.FRONT_DESK_READ)
    @Transactional(readOnly = true)
    public Map<String, Object> getStaffQuota(TenantContext caller, UUID requestedProjectId) {
        require(caller, Permission.FRONT_DESK_READ);
        ClinicScope scope = ClinicScope.forRequestedProject(guard, caller, requestedProjectId);
        UUID projectId = scope.projectId();
        if (projectId == null && !scope.accessibleProjectIds().isEmpty()) {
            projectId = scope.accessibleProjectIds().get(0);
        }
        List<FrontDeskOfficer> staffList = repository.frontDeskOfficers(
            scope, null, null, ClinicStaffRepository.STAFF_PAGE);
        int activeStaffCount = (int) staffList.stream().filter(FrontDeskOfficer::isActive).count();
        if (limits != null && projectId != null) {
            PlanLimitEnforcementService.EffectiveLimits effective = limits.getEffectiveLimits(projectId);
            if (effective != null) {
                return Map.of(
                    "activeUsersCount", effective.activeUsersCount(),
                    "activeFdoCount", activeStaffCount,
                    "effectiveMaxUsers", effective.effectiveMaxUsers(),
                    "planName", effective.planName() != null ? effective.planName() : "DEFAULT"
                );
            }
        }
        return Map.of(
            "activeUsersCount", activeStaffCount,
            "activeFdoCount", activeStaffCount,
            "effectiveMaxUsers", 50,
            "planName", "DEFAULT"
        );
    }

    /** Creates a CLINIC_STAFF account, its project membership and its clinic slot. */
    @RequirePermission(Permission.FRONT_DESK_MANAGE)
    @Transactional
    public FrontDeskOfficer createFrontDesk(TenantContext caller, UUID requestedProjectId,
                                            FrontDeskOfficerInput input) {
        require(caller, Permission.FRONT_DESK_MANAGE);
        ClinicScope scope = writeScope(caller, requestedProjectId);
        if (limits != null) {
            limits.enforceUserLimit(scope.projectId());
        }
        FrontDeskOfficerInput normalized = validateFrontDesk(scope, input, false);
        String email = normalized.email();
        if (repository.emailTaken(email, null)) {
            throw field("An account with that email already exists.", "email");
        }
        String hash = passwords.hash(normalized.password());
        boolean active = normalized.isActive() == null || normalized.isActive();
        String verification = normalizeVerification(normalized.verificationStatus(), "VERIFIED");
        UUID id;
        try {
            id = repository.insertFrontDesk(scope, normalized,
                fullName(normalized.firstName(), normalized.lastName()),
                email, hash, active, verification);
        }
        catch (DuplicateKeyException conflict) {
            throw field("An account with that email already exists.", "email");
        }
        repository.assignClinic(scope, id, normalized.clinicId());
        return repository.frontDeskOfficer(scope, id)
            .orElseThrow(() -> new IllegalStateException(
                "The front-desk officer was inserted but not read back."));
    }

    /** Updates a front-desk officer, merging only the fields the caller sent. */
    @RequirePermission(Permission.FRONT_DESK_MANAGE)
    @Transactional
    public FrontDeskOfficer updateFrontDesk(TenantContext caller, UUID requestedProjectId,
                                            UUID userId, FrontDeskOfficerInput input) {
        require(caller, Permission.FRONT_DESK_MANAGE);
        ClinicScope scope = writeScope(caller, requestedProjectId);
        if (limits != null) {
            limits.enforceUserLimit(scope.projectId());
        }
        FrontDeskOfficer existing = repository.frontDeskOfficer(scope, userId)
            .orElseThrow(() -> new ResourceNotFoundException("Front-desk officer"));
        FrontDeskOfficerInput merged = mergeFrontDesk(existing, input);
        FrontDeskOfficerInput normalized = validateFrontDesk(scope, merged, true);
        String email = normalized.email();
        if (repository.emailTaken(email, userId)) {
            throw field("An account with that email already exists.", "email");
        }
        String hash = normalized.password() == null ? null : passwords.hash(normalized.password());
        boolean active = normalized.isActive() == null
            ? existing.isActive() : normalized.isActive();
        String verification = normalizeVerification(
            normalized.verificationStatus(), existing.verificationStatus());
        if (!repository.updateFrontDesk(scope, userId, normalized,
                fullName(normalized.firstName(), normalized.lastName()),
                email, hash, active, verification)) {
            throw new ResourceNotFoundException("Front-desk officer");
        }
        if (input.clinicId() != null) {
            repository.assignClinic(scope, userId, input.clinicId());
        }
        return repository.frontDeskOfficer(scope, userId)
            .orElseThrow(() -> new ResourceNotFoundException("Front-desk officer"));
    }

    /**
     * Removes the officer from this project.
     *
     * <p>Not a row delete: the membership goes, the clinic slot is cleared, and the
     * account is deactivated only when it has no remaining memberships. A user who
     * also works in another project stays active there, and the audit trail still
     * has a name for every action the account took.
     */
    @RequirePermission(Permission.FRONT_DESK_MANAGE)
    @Transactional
    public void deleteFrontDesk(TenantContext caller, UUID requestedProjectId, UUID userId) {
        require(caller, Permission.FRONT_DESK_MANAGE);
        ClinicScope scope = writeScope(caller, requestedProjectId);
        if (limits != null) {
            limits.enforceUserLimit(scope.projectId());
        }
        repository.frontDeskOfficer(scope, userId)
            .orElseThrow(() -> new ResourceNotFoundException("Front-desk officer"));
        repository.removeMembership(scope, userId);
        repository.clearClinicAssignments(scope, userId);
        repository.deactivateIfNoMemberships(scope, userId);
    }

    // ==================================================================
    // merge, validation, scope
    // ==================================================================

    private Optional<DoctorProfile> doctorDetail(ClinicScope scope, UUID doctorId) {
        return repository.doctor(scope, doctorId)
            .map(doctor -> doctor.withQualifications(
                repository.qualifications(scope, doctorId)));
    }

    private static DoctorProfileInput mergeDoctor(DoctorProfile existing,
                                                  DoctorProfileInput input) {
        return new DoctorProfileInput(
            input.firstName() == null ? existing.firstName() : input.firstName(),
            input.lastName() == null ? existing.lastName() : input.lastName(),
            input.email() == null ? existing.email() : input.email(),
            input.phone() == null ? existing.phone() : input.phone(),
            input.gender() == null ? existing.gender() : input.gender(),
            input.imageUrl() == null ? existing.imageUrl() : input.imageUrl(),
            input.aboutSelf() == null ? existing.aboutSelf() : input.aboutSelf(),
            input.expert() == null ? existing.expert() : input.expert(),
            input.commission() == null ? existing.commission() : input.commission(),
            input.clinicId() == null ? existing.clinicId() : input.clinicId(),
            input.serviceId() == null ? existing.serviceId() : input.serviceId(),
            input.experienceYears() == null ? existing.experienceYears() : input.experienceYears(),
            input.country() == null ? existing.country() : input.country(),
            input.state() == null ? existing.state() : input.state(),
            input.city() == null ? existing.city() : input.city(),
            input.postalCode() == null ? existing.postalCode() : input.postalCode(),
            input.latitude() == null ? existing.latitude() : input.latitude(),
            input.longitude() == null ? existing.longitude() : input.longitude(),
            input.facebookUrl() == null ? existing.facebookUrl() : input.facebookUrl(),
            input.instagramUrl() == null ? existing.instagramUrl() : input.instagramUrl(),
            input.twitterUrl() == null ? existing.twitterUrl() : input.twitterUrl(),
            input.dribbbleUrl() == null ? existing.dribbbleUrl() : input.dribbbleUrl(),
            input.signatureUrl() == null ? existing.signatureUrl() : input.signatureUrl(),
            input.verificationStatus() == null
                ? existing.verificationStatus() : input.verificationStatus(),
            input.isActive() == null ? existing.isActive() : input.isActive(),
            input.qualifications() == null
                ? existing.qualifications().stream()
                    .map(row -> new QualificationInput(
                        row.degree(), row.university(), row.graduationYear()))
                    .toList()
                : input.qualifications());
    }

    private static FrontDeskOfficerInput mergeFrontDesk(FrontDeskOfficer existing,
                                                        FrontDeskOfficerInput input) {
        return new FrontDeskOfficerInput(
            input.firstName() == null ? existing.firstName() : input.firstName(),
            input.lastName() == null ? existing.lastName() : input.lastName(),
            input.email() == null ? existing.email() : input.email(),
            input.gender() == null ? existing.gender() : input.gender(),
            input.phone() == null ? existing.phone() : input.phone(),
            input.password(),
            input.dateOfBirth() == null ? existing.dateOfBirth() : input.dateOfBirth(),
            input.profileImageUrl() == null
                ? existing.profileImageUrl() : input.profileImageUrl(),
            input.addressLine() == null ? existing.addressLine() : input.addressLine(),
            input.country() == null ? existing.country() : input.country(),
            input.state() == null ? existing.state() : input.state(),
            input.city() == null ? existing.city() : input.city(),
            input.postalCode() == null ? existing.postalCode() : input.postalCode(),
            input.clinicId() == null ? existing.clinicId() : input.clinicId(),
            input.verificationStatus() == null
                ? existing.verificationStatus() : input.verificationStatus(),
            input.isActive() == null ? existing.isActive() : input.isActive(),
            input.role() == null ? existing.role() : input.role(),
            input.isAccountant() == null ? existing.isAccountant() : input.isAccountant());
    }

    private DoctorProfileInput validateDoctor(ClinicScope scope, DoctorProfileInput input,
                                              boolean partial) {
        String firstName = text(input.firstName(), 80, "First name", "firstName");
        String lastName = text(input.lastName(), 80, "Last name", "lastName");
        if (firstName == null) {
            throw field("First name is required.", "firstName");
        }
        if (!partial && lastName == null) {
            throw field("Last name is required.", "lastName");
        }

        String email = PasswordVerifier.normalizeEmail(trimToNull(input.email()));
        if (!partial && email == null) {
            throw field("Email is required.", "email");
        }
        if (email != null && email.length() > 320) {
            throw field("Email is too long.", "email");
        }
        if (email != null && !PasswordVerifier.isAcceptableEmail(email)) {
            throw field("Enter a valid email address.", "email");
        }

        String phone = text(input.phone(), 40, "Contact number", "phone");
        String gender = normalizeGender(input.gender());
        String imageUrl = text(input.imageUrl(), MAX_IMAGE_LENGTH, "Profile image", "imageUrl");
        String aboutSelf = text(input.aboutSelf(), 4000, "About", "aboutSelf");
        String expert = text(input.expert(), 160, "Expert", "expert");

        if (input.commission() != null && input.commission() < 0) {
            throw field("Commission cannot be negative.", "commission");
        }
        if (input.experienceYears() != null
            && (input.experienceYears() < 0 || input.experienceYears() > 80)) {
            throw field("Experience must be between 0 and 80 years.", "experienceYears");
        }
        if (input.latitude() != null && (input.latitude() < -90 || input.latitude() > 90)) {
            throw field("Latitude must be between -90 and 90.", "latitude");
        }
        if (input.longitude() != null
            && (input.longitude() < -180 || input.longitude() > 180)) {
            throw field("Longitude must be between -180 and 180.", "longitude");
        }
        if (input.clinicId() != null && !repository.clinicInScope(scope, input.clinicId())) {
            throw field("Choose a clinic in the selected project.", "clinicId");
        }
        if (input.serviceId() != null && !repository.serviceInScope(scope, input.serviceId())) {
            throw field("Choose a service in the selected project.", "serviceId");
        }

        String verification = trimToNull(input.verificationStatus());
        if (verification != null && !VERIFICATIONS.contains(verification.toUpperCase(Locale.ROOT))) {
            throw field("Choose a valid verification status.", "verificationStatus");
        }

        return new DoctorProfileInput(
            firstName,
            lastName,
            email,
            phone,
            gender,
            imageUrl,
            aboutSelf,
            expert,
            input.commission(),
            input.clinicId(),
            input.serviceId(),
            input.experienceYears(),
            text(input.country(), 120, "Country", "country"),
            text(input.state(), 120, "State", "state"),
            text(input.city(), 120, "City", "city"),
            text(input.postalCode(), 20, "Postal code", "postalCode"),
            input.latitude(),
            input.longitude(),
            text(input.facebookUrl(), 500, "Facebook link", "facebookUrl"),
            text(input.instagramUrl(), 500, "Instagram link", "instagramUrl"),
            text(input.twitterUrl(), 500, "Twitter link", "twitterUrl"),
            text(input.dribbbleUrl(), 500, "Dribbble link", "dribbbleUrl"),
            text(input.signatureUrl(), MAX_IMAGE_LENGTH, "Signature", "signatureUrl"),
            verification,
            input.isActive(),
            validateQualifications(input.qualifications()));
    }

    private FrontDeskOfficerInput validateFrontDesk(ClinicScope scope, FrontDeskOfficerInput input,
                                                    boolean partial) {
        String firstName = text(input.firstName(), 80, "First name", "firstName");
        String lastName = text(input.lastName(), 80, "Last name", "lastName");
        if (firstName == null) {
            throw field("First name is required.", "firstName");
        }
        if (!partial && lastName == null) {
            throw field("Last name is required.", "lastName");
        }

        String email = PasswordVerifier.normalizeEmail(trimToNull(input.email()));
        if (email == null) {
            throw field("Email is required.", "email");
        }
        if (email.length() > 320) {
            throw field("Email is too long.", "email");
        }
        if (!PasswordVerifier.isAcceptableEmail(email)) {
            throw field("Enter a valid email address.", "email");
        }

        String password = input.password();
        if (password != null && password.isBlank()) {
            password = null;
        }
        if (!partial && password == null) {
            throw field("Password is required.", "password");
        }
        if (password != null && password.length() < MIN_PASSWORD_LENGTH) {
            throw field("Password must be at least 8 characters.", "password");
        }
        if (password != null && password.length() > MAX_PASSWORD_LENGTH) {
            throw field("Password is too long.", "password");
        }

        String gender = normalizeGender(input.gender());
        String phone = text(input.phone(), 40, "Phone", "phone");
        if (input.dateOfBirth() != null) {
            if (input.dateOfBirth().isAfter(LocalDate.now())) {
                throw field("Date of birth cannot be in the future.", "dateOfBirth");
            }
            if (input.dateOfBirth().isBefore(LocalDate.of(1900, 1, 1))) {
                throw field("Date of birth is out of range.", "dateOfBirth");
            }
        }
        String imageUrl = text(input.profileImageUrl(), MAX_IMAGE_LENGTH,
            "Profile image", "profileImageUrl");
        if (!partial && input.clinicId() == null) {
            throw field("Clinic is required.", "clinicId");
        }
        if (input.clinicId() != null && !repository.clinicInScope(scope, input.clinicId())) {
            throw field("Choose a clinic in the selected project.", "clinicId");
        }

        String verification = trimToNull(input.verificationStatus());
        if (verification != null && !VERIFICATIONS.contains(verification.toUpperCase(Locale.ROOT))) {
            throw field("Choose a valid verification status.", "verificationStatus");
        }

        String rawRole = trimToNull(input.role());
        String role = rawRole == null ? "receptionist" : rawRole.toLowerCase(Locale.ROOT);
        if ("clinic_admin".equals(role) || "platform_admin".equals(role) || "admin".equals(role)) {
            throw field("Clinic Admin role can only be granted by the Platform Admin.", "role");
        }
        if (!Set.of("receptionist", "doctor", "accountant").contains(role)) {
            throw field("Choose a valid staff role (receptionist, doctor, or accountant).", "role");
        }
        boolean isAccountant = Boolean.TRUE.equals(input.isAccountant()) || "accountant".equals(role);

        return new FrontDeskOfficerInput(
            firstName,
            lastName,
            email,
            gender,
            phone,
            password,
            input.dateOfBirth(),
            imageUrl,
            text(input.addressLine(), 500, "Address", "addressLine"),
            text(input.country(), 120, "Country", "country"),
            text(input.state(), 120, "State", "state"),
            text(input.city(), 120, "City", "city"),
            text(input.postalCode(), 20, "Postal code", "postalCode"),
            input.clinicId(),
            verification,
            input.isActive(),
            role,
            isAccountant);
    }

    private static List<QualificationInput> validateQualifications(
            List<QualificationInput> qualifications) {
        if (qualifications == null) {
            return null;
        }
        if (qualifications.size() > MAX_QUALIFICATIONS) {
            throw field("Add at most 20 qualifications.", "qualifications");
        }
        List<QualificationInput> normalized = new ArrayList<>(qualifications.size());
        for (QualificationInput qualification : qualifications) {
            String degree = text(qualification.degree(), 160, "Degree", "qualifications");
            if (degree == null) {
                throw field("Each qualification needs a degree.", "qualifications");
            }
            String university = text(qualification.university(), 160,
                "University", "qualifications");
            Integer year = qualification.graduationYear();
            if (year != null && (year < 1950 || year > 2100)) {
                throw field("Graduation year must be between 1950 and 2100.",
                    "qualifications");
            }
            normalized.add(new QualificationInput(degree, university, year));
        }
        return List.copyOf(normalized);
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
        throw field("Choose a project before saving: this account can reach more than one.",
            "projectId");
    }

    private void require(TenantContext caller, Permission permission) {
        if (caller == null) {
            throw new IllegalStateException(
                "No TenantContext is bound; refusing to serve clinic staff unscoped.");
        }
        authorization.check(caller, permission, PolicyResource.NONE);
    }

    private static String fullName(String firstName, String lastName) {
        String first = trimToNull(firstName);
        String last = trimToNull(lastName);
        if (first == null) {
            return last == null ? "" : last;
        }
        return last == null ? first : first + " " + last;
    }

    private static String normalizeGender(String value) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            return null;
        }
        normalized = normalized.toUpperCase(Locale.ROOT);
        if (!GENDERS.contains(normalized)) {
            throw field("Choose a valid gender.", "gender");
        }
        return normalized;
    }

    private static String normalizeVerification(String value, String fallback) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            return fallback;
        }
        normalized = normalized.toUpperCase(Locale.ROOT);
        if (!VERIFICATIONS.contains(normalized)) {
            throw field("Choose a valid verification status.", "verificationStatus");
        }
        return normalized;
    }

    private static String statusFilter(String status) {
        String normalized = trimToNull(status);
        if (normalized == null) {
            return null;
        }
        normalized = normalized.toUpperCase(Locale.ROOT);
        return "ACTIVE".equals(normalized) || "INACTIVE".equals(normalized)
            ? normalized : null;
    }

    private static String text(String value, int maxLength, String label, String field) {
        String trimmed = trimToNull(value);
        if (trimmed != null && trimmed.length() > maxLength) {
            throw field(label + " is too long.", field);
        }
        return trimmed;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static ValidationFailedException field(String message, String field) {
        return new ValidationFailedException(message, Map.of("field", field));
    }
}
