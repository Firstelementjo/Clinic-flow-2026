package com.firstelement.inbox.web;

import com.firstelement.crm.clinic.ClinicScope;
import com.firstelement.crm.clinic.odontogram.OdontogramModels;
import com.firstelement.crm.clinic.odontogram.OdontogramService;
import com.firstelement.crm.clinic.patient.PatientModels;
import com.firstelement.crm.clinic.patient.PatientService;
import com.firstelement.crm.clinic.schedule.AvailabilityService;
import com.firstelement.crm.clinic.schedule.ScheduleModels;
import com.firstelement.security.TenantContextHolder;
import com.firstelement.security.auth.PasswordVerifier;
import com.firstelement.security.annotation.RequirePermission;
import com.firstelement.security.permission.Permission;
import com.firstelement.security.query.TenantQueryGuard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Patients, the odontogram and bookable slots.
 *
 * <p>Sits beside {@link ClinicController} rather than beside the services it calls, for the same
 * reason: this module is the web layer, {@code crm-core} owns the domain, and nothing depends on
 * {@code crm-core} except this module and the bootstrap.
 *
 * <h2>A permission decision this makes visible, and needs sign-off</h2>
 *
 * <p>There is no {@code patient:read}. The existing clinic vocabulary is {@code doctor:read} and
 * {@code appointment:read}, and {@code GET /api/v1/clinic/appointments} already returns a patient's
 * name and phone as free text because {@code appointments.patientName} and
 * {@code appointments.patientPhone} are columns on a scheduling row.
 *
 * <p>These routes therefore declare {@code appointment:read}. That is CONSISTENT with what the
 * clinic screen can already read ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â it is not new exposure in kind ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â but it does make patient
 * identity, birth date and the odontogram reachable under one grant, and that is a security
 * posture question rather than an engineering one. {@link Permission} is deliberately not
 * extended here: DEC-014 says roles are configurable over the EXISTING permission vocabulary, so
 * inventing {@code patient:read} would change that vocabulary inside a routing commit.
 *
 * <p><b>This needs a security decision before the routes are enabled.</b> It is recorded rather
 * than resolved, because the alternative ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â shipping a narrower grant that nobody asked for ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â is
 * worse than shipping the honest one with the question attached.
 */
@RestController
@RequestMapping("/api/v1/clinic")
public class ClinicPatientController {

    private final PatientService patients;
    private final com.firstelement.crm.clinic.patient.PatientRepository patientRepository;
    private final OdontogramService odontogram;
    private final AvailabilityService availability;
    private final TenantQueryGuard guard;

    public ClinicPatientController(
            PatientService patients,
            com.firstelement.crm.clinic.patient.PatientRepository patientRepository,
            OdontogramService odontogram,
            AvailabilityService availability,
            TenantQueryGuard guard) {
        this.patients = patients;
        this.patientRepository = patientRepository;
        this.odontogram = odontogram;
        this.availability = availability;
        this.guard = guard;
    }

    /**
     * {@code GET /api/v1/clinic/patients}
     *
     * <p>A blank or absent {@code q} lists the clinic's patients; a term searches name and phone.
     * The term is NEVER matched against a date of birth, because a birth date is near-universal
     * among patients and searching it returns most of the clinic while telling the caller nothing.
     */
    @GetMapping("/patients")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "The clinic's patients",
        description = "Patients in the caller's server-resolved scope. Soft-deleted rows are "
            + "excluded in SQL, not in the response. The optional term matches name and phone and "
            + "never a date of birth. A projectId outside the caller's membership is a "
            + "non-disclosing 404.")
    public List<PatientModels.PatientRow> patients(
            @RequestParam(required = false) String q,
            @Parameter(description = "Narrow to one project. A request, not an authorization.")
            @RequestParam(required = false) String projectId,
            @RequestParam(defaultValue = "50") int limit) {
        ClinicScope scope = scope(projectId);
        String term = q == null ? "" : q.trim();
        return term.isEmpty()
                ? patients.list(scope, limit)
                : patients.search(scope, term, limit);
    }

    /**
     * {@code GET /api/v1/clinic/patients/duplicates}
     *
     * <p>PAT-04: Returns scored candidate matches based on phone, name, and date of birth.
     */
    @GetMapping("/patients/duplicates")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Duplicate patient candidates",
        description = "PAT-04: Returns scored candidate matches based on phone, name, and date of birth.")
    public List<PatientModels.DuplicateCandidate> duplicates(
            @RequestParam(required = false) String givenName,
            @RequestParam(required = false) String familyName,
            @RequestParam(required = false) LocalDate dateOfBirth,
            @RequestParam(required = false) String phone,
            @RequestParam(required = false) UUID excludePatientId,
            @RequestParam(required = false) String projectId) {
        return patients.duplicateCandidates(
                scope(projectId), givenName, familyName, dateOfBirth, phone, excludePatientId);
    }

    public record CreatePatientPayload(
        String patientNo,
        String displayName,
        String givenName,
        String familyName,
        String gender,
        LocalDate dateOfBirth,
        String phone,
        String email,
        String cnic,
        String password,
        String confirmPassword,
        String profileImageUrl,
        String addressLine,
        String country,
        String state,
        String city,
        String postalCode,
        Boolean isActive,
        String locale,
        UUID customerId
    ) {}

    /**
     * {@code POST /api/v1/clinic/patients}
     *
     * <p>Registers a new patient in the scoped clinic.
     */
    @PostMapping("/patients")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a patient",
        description = "Creates a new patient in the current clinic scope.")
    public PatientModels.PatientRow createPatient(
            @RequestBody CreatePatientPayload payload,
            @RequestParam(required = false) String projectId) {
        ClinicScope scope = writeScope(projectId);

        String givenName = trim(payload.givenName());
        String familyName = trim(payload.familyName());
        String phone = trim(payload.phone());
        String gender = trim(payload.gender());
        if (gender != null) {
            gender = gender.toUpperCase(java.util.Locale.ROOT);
        }

        requireField(givenName, "givenName");
        requireField(familyName, "familyName");
        requireField(gender, "gender");
        requireField(phone, "phone");
        if (payload.dateOfBirth() == null) {
            throw new com.firstelement.crm.error.ValidationFailedException(
                    "dateOfBirth is required", java.util.Map.of("field", "dateOfBirth"));
        }

        if (gender != null && !gender.isEmpty() && !java.util.Set.of("MALE", "FEMALE", "OTHER").contains(gender)) {
            throw new com.firstelement.crm.error.ValidationFailedException(
                    "gender must be one of MALE, FEMALE",
                    java.util.Map.of("field", "gender"));
        }
        if (payload.dateOfBirth() != null && payload.dateOfBirth().isAfter(LocalDate.now())) {
            throw new com.firstelement.crm.error.ValidationFailedException(
                    "dateOfBirth cannot be in the future",
                    java.util.Map.of("field", "dateOfBirth"));
        }

        String password = trim(payload.password());
        if (password != null && !password.isEmpty()) {
            if (password.length() < 8) {
                throw new com.firstelement.crm.error.ValidationFailedException(
                        "password must be at least 8 characters",
                        java.util.Map.of("field", "password"));
            }
            if (!password.equals(trim(payload.confirmPassword()))) {
                throw new com.firstelement.crm.error.ValidationFailedException(
                        "password and confirmPassword must match",
                        java.util.Map.of("field", "confirmPassword"));
            }
        }

        String patientNo = trim(payload.patientNo());
        if (patientNo == null) {
            patientNo = "P-" + (System.currentTimeMillis() % 1000000);
        }
        String displayName = trim(payload.displayName());
        if (displayName == null) {
            displayName = ((givenName == null ? "" : givenName) + " " + (familyName == null ? "" : familyName)).trim();
            if (displayName.isEmpty()) {
                displayName = "Patient";
            }
        }

        String profileImageUrl = trim(payload.profileImageUrl());
        if (profileImageUrl != null && profileImageUrl.length() > 1_400_000) {
            throw new com.firstelement.crm.error.ValidationFailedException(
                    "profileImageUrl is too large",
                    java.util.Map.of("field", "profileImageUrl"));
        }

        return patients.create(scope, new PatientModels.CreatePatientCommand(
                patientNo,
                displayName,
                givenName,
                familyName,
                gender,
                payload.dateOfBirth(),
                phone,
                trim(payload.email()),
                trim(payload.cnic()),
                profileImageUrl,
                trim(payload.addressLine()),
                trim(payload.country()),
                trim(payload.state()),
                trim(payload.city()),
                trim(payload.postalCode()),
                payload.isActive() == null || payload.isActive(),
                payload.locale(),
                payload.customerId(),
                password == null ? null : new PasswordVerifier().hash(password)));
    }

    private static String trim(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static void requireField(String value, String field) {
        if (value == null) {
            throw new com.firstelement.crm.error.ValidationFailedException(
                    field + " is required", java.util.Map.of("field", field));
        }
    }

    /**
     * {@code GET /api/v1/clinic/patients/{patientId}}
     *
     * <p>A foreign id and a nonexistent id are indistinguishable: both are absent, never forbidden,
     * so an id cannot be probed across tenants.
     */
    @GetMapping("/patients/{patientId}")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "One patient",
        description = "Absent and unauthorized are the same answer, so a patient id cannot be "
            + "probed to discover another tenant's records.")
    public PatientModels.PatientRow patient(
            @PathVariable UUID patientId,
            @RequestParam(required = false) String projectId) {
        return patients.find(scope(projectId), patientId)
                .orElseThrow(() -> new ResourceNotFound());
    }

    /**
     * {@code PUT /api/v1/clinic/patients/{patientId}}
     *
     * <p>Updates an existing patient file in the current clinic scope.
     */
    @PutMapping("/patients/{patientId}")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Update a patient",
        description = "Updates an existing patient file in the current clinic scope.")
    public PatientModels.PatientRow updatePatient(
            @PathVariable UUID patientId,
            @RequestBody CreatePatientPayload payload,
            @RequestParam(required = false) String projectId) {
        ClinicScope scope = writeScope(projectId);
        PatientModels.PatientRow existing = patients.find(scope, patientId)
                .orElseThrow(ResourceNotFound::new);

        String givenName = trim(payload.givenName()) != null ? trim(payload.givenName()) : existing.givenName();
        String familyName = trim(payload.familyName()) != null ? trim(payload.familyName()) : existing.familyName();
        String phone = trim(payload.phone()) != null ? trim(payload.phone()) : existing.primaryPhone();
        String gender = trim(payload.gender()) != null
                ? trim(payload.gender()).toUpperCase(java.util.Locale.ROOT)
                : existing.gender();
        LocalDate dob = payload.dateOfBirth() != null ? payload.dateOfBirth() : existing.dateOfBirth();

        if (gender != null && !gender.isEmpty() && !java.util.Set.of("MALE", "FEMALE", "OTHER").contains(gender)) {
            throw new com.firstelement.crm.error.ValidationFailedException(
                    "gender must be one of MALE, FEMALE",
                    java.util.Map.of("field", "gender"));
        }
        if (dob != null && dob.isAfter(LocalDate.now())) {
            throw new com.firstelement.crm.error.ValidationFailedException(
                    "dateOfBirth cannot be in the future",
                    java.util.Map.of("field", "dateOfBirth"));
        }

        String displayName = trim(payload.displayName());
        if (displayName == null) {
            displayName = ((givenName == null ? "" : givenName) + " " + (familyName == null ? "" : familyName)).trim();
            if (displayName.isEmpty()) {
                displayName = existing.displayName();
            }
        }

        return patients.update(scope, patientId, new PatientModels.CreatePatientCommand(
                trim(payload.patientNo()) != null ? trim(payload.patientNo()) : existing.patientNo(),
                displayName,
                givenName,
                familyName,
                gender,
                dob,
                phone,
                trim(payload.email()) != null ? trim(payload.email()) : existing.primaryEmail(),
                trim(payload.cnic()) != null ? trim(payload.cnic()) : existing.cnic(),
                trim(payload.profileImageUrl()) != null ? trim(payload.profileImageUrl()) : existing.profileImageUrl(),
                trim(payload.addressLine()) != null ? trim(payload.addressLine()) : existing.addressLine(),
                trim(payload.country()) != null ? trim(payload.country()) : existing.country(),
                trim(payload.state()) != null ? trim(payload.state()) : existing.state(),
                trim(payload.city()) != null ? trim(payload.city()) : existing.city(),
                trim(payload.postalCode()) != null ? trim(payload.postalCode()) : existing.postalCode(),
                payload.isActive() != null ? payload.isActive() : existing.isActive(),
                payload.locale() != null ? payload.locale() : existing.locale(),
                payload.customerId() != null ? payload.customerId() : existing.customerId(),
                null));
    }

    /**
     * {@code GET /api/v1/clinic/patients/{patientId}/odontogram}
     *
     * <p>Newest observation first, each flagged with whether it still stands. The flag is computed
     * at read time from {@code supersededBy} rather than stored, because a stored flag would need
     * updating and this table is append-only.
     */
    @GetMapping("/patients/{patientId}/odontogram")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "A patient's dental chart",
        description = "Findings, newest first, each marked superseded or still standing. A finding "
            + "is never rewritten: a correction is a new row pointing back at the row it replaces.")
    public List<OdontogramModels.ChartEntry> odontogram(
            @PathVariable UUID patientId,
            @RequestParam(required = false) String projectId,
            @RequestParam(defaultValue = "200") int limit) {
        return odontogram.chart(scope(projectId), patientId, limit);
    }

    /** One tooth's history, oldest first, which is the order a chart reads in. */
    @GetMapping("/patients/{patientId}/odontogram/tooth/{toothFdi}")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "One tooth's history",
        description = "The canonical FDI number, 11-85. A number outside the DEC-009 sets matches "
            + "no rows rather than being refused, because a chart of nothing and a chart of a "
            + "mistyped tooth look the same to a reader and both mean 'nothing recorded'.")
    public List<OdontogramModels.FindingRow> toothHistory(
            @PathVariable UUID patientId,
            @PathVariable int toothFdi,
            @RequestParam(required = false) String projectId) {
        return odontogram.toothHistory(scope(projectId), patientId, toothFdi);
    }

    /**
     * {@code GET /api/v1/clinic/odontogram/vocabulary}
     *
     * <p>Seeds the clinic's catalogue from the published template on first call, so a screen can
     * render the finding picker without a clinician having prepared anything.
     */
    @GetMapping("/odontogram/vocabulary")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "This clinic's finding vocabulary",
        description = "Copies the published template on first call. Every term is marked "
            + "isClinicianReviewed=false until a clinician confirms it, and trimming the catalogue "
            + "is an update rather than a migration.")
    public List<OdontogramModels.VocabularyTerm> vocabulary(
            @RequestParam(required = false) String projectId) {
        ClinicScope scope = writeScope(projectId);
        odontogram.ensureVocabulary(scope);
        return odontogram.vocabulary(scope);
    }

    /**
     * {@code POST /api/v1/clinic/patients/{patientId}/odontogram/findings}
     *
     * <p>CLN-03: Records a new odontogram observation on a tooth or tooth surface.
     */
    @PostMapping("/patients/{patientId}/odontogram/findings")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Record a dental finding observation")
    public OdontogramModels.FindingRow recordFinding(
            @PathVariable UUID patientId,
            @RequestBody OdontogramModels.RecordFindingRequest req,
            @RequestParam(required = false) String projectId) {
        ClinicScope scope = writeScope(projectId);
        UUID userId = TenantContextHolder.get().userId() != null
                ? TenantContextHolder.get().userId()
                : TenantContextHolder.get().principalId();
        return odontogram.record(
                scope,
                patientId,
                req.toothFdi(),
                req.scope(),
                req.surfaces(),
                req.code(),
                req.attributesJson(),
                req.state() == null ? "EXISTING" : req.state(),
                req.source() == null ? "CLINICAL" : req.source(),
                req.plannedProcedureId(),
                userId,
                java.time.LocalDateTime.now(),
                req.note());
    }

    /**
     * {@code POST /api/v1/clinic/patients/{patientId}/odontogram/findings/{findingId}/correct}
     *
     * <p>Corrects a finding by appending a superseding observation (append-only history).
     */
    @PostMapping("/patients/{patientId}/odontogram/findings/{findingId}/correct")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Correct an odontogram finding by superseding it")
    public OdontogramModels.FindingRow correctFinding(
            @PathVariable UUID patientId,
            @PathVariable UUID findingId,
            @RequestBody OdontogramModels.CorrectFindingRequest req,
            @RequestParam(required = false) String projectId) {
        ClinicScope scope = writeScope(projectId);
        return odontogram.correct(scope, findingId, req.code(), req.attributesJson(), req.note());
    }

    /**
     * {@code GET /api/v1/clinic/patients/{patientId}/periodontal/exams}
     *
     * <p>Lists periodontal examination sessions for a patient, newest first.
     */
    @GetMapping("/patients/{patientId}/periodontal/exams")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "List periodontal exams for a patient")
    public List<OdontogramModels.PeriodontalExamHeader> periodontalExams(
            @PathVariable UUID patientId,
            @RequestParam(required = false) String projectId) {
        return odontogram.listPeriodontalExams(scope(projectId), patientId);
    }

    /**
     * {@code GET /api/v1/clinic/patients/{patientId}/periodontal/exams/{examId}}
     *
     * <p>Detail for a periodontal examination session, including 6-site probing measurements.
     */
    @GetMapping("/patients/{patientId}/periodontal/exams/{examId}")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Get periodontal exam details with 6-site measurements")
    public OdontogramModels.PeriodontalExamDetail periodontalExamDetail(
            @PathVariable UUID patientId,
            @PathVariable UUID examId,
            @RequestParam(required = false) String projectId) {
        OdontogramModels.PeriodontalExamDetail detail = odontogram.getPeriodontalExam(scope(projectId), examId);
        if (detail == null) {
            throw new ResourceNotFound();
        }
        return detail;
    }

    /**
     * {@code POST /api/v1/clinic/patients/{patientId}/periodontal/exams}
     *
     * <p>Records a new periodontal exam session with 6-site probing measurements and derived CAL.
     */
    @PostMapping("/patients/{patientId}/periodontal/exams")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Record a periodontal exam session with measurements")
    public OdontogramModels.PeriodontalExamDetail createPeriodontalExam(
            @PathVariable UUID patientId,
            @RequestBody OdontogramModels.CreatePeriodontalExamRequest req,
            @RequestParam(required = false) String projectId) {
        ClinicScope scope = writeScope(projectId);
        UUID userId = req.examinedByUserId() != null
                ? req.examinedByUserId()
                : (TenantContextHolder.get().userId() != null
                    ? TenantContextHolder.get().userId()
                    : TenantContextHolder.get().principalId());
        OdontogramModels.CreatePeriodontalExamRequest effectiveReq = new OdontogramModels.CreatePeriodontalExamRequest(
                userId,
                req.examinedAt() != null ? req.examinedAt() : java.time.LocalDateTime.now(),
                req.notes(),
                req.measurements());
        return odontogram.createPeriodontalExam(scope, patientId, effectiveReq);
    }

    /**
     * {@code GET /api/v1/clinic/patients/{patientId}/periodontal/latest}
     *
     * <p>Returns the latest periodontal measurements per tooth for chart overlay.
     */
    @GetMapping("/patients/{patientId}/periodontal/latest")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Latest periodontal measurements per tooth for patient")
    public List<OdontogramModels.PeriodontalMeasurement> latestPeriodontalChart(
            @PathVariable UUID patientId,
            @RequestParam(required = false) String projectId) {
        return odontogram.getLatestPeriodontalChart(scope(projectId), patientId);
    }


    /**
     * {@code GET /api/v1/clinic/availability}
     *
     * <p>DEC-001: the buffer is padding, not patient time. A 30-minute service with a 10-minute
     * buffer offers slots 40 minutes apart and reports {@code blockedUntil} separately from
     * {@code patientMinutes}, because those two differ whenever a buffer applies and a booking form
     * that showed only one of them would be wrong.
     *
     * <p>Leave, holidays and training days remove the whole day before any window is consulted, so
     * "not working" and "not scheduled" stay distinguishable.
     */
    @GetMapping("/availability")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Bookable slots for a dentist",
        description = "Slots honour split shifts, leave, effective date ranges and the "
            + "per-dentist duration override. Existing bookings are expanded by their own "
            + "procedure's buffer, which is what stops a chair being double-booked across a "
            + "procedure that carries one.")
    public List<ScheduleModels.Slot> availability(
            @RequestParam UUID doctorId,
            @RequestParam LocalDate date,
            @RequestParam(defaultValue = "30") int durationMinutes,
            @RequestParam(defaultValue = "0") int bufferMinutes,
            @Parameter(description = "DEC-001 per-dentist override. Absent means use the "
                + "service duration.")
            @RequestParam(required = false) Integer dentistOverrideMinutes,
            @RequestParam(required = false) String projectId) {
        return availability.slotsFor(scope(projectId), doctorId, date,
                durationMinutes, bufferMinutes, dentistOverrideMinutes);
    }

    // --- S1: Relationships ---
    @GetMapping("/patients/{patientId}/relationships")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Patient relationships and guardians")
    public List<PatientModels.RelationshipRow> relationships(
            @PathVariable UUID patientId,
            @RequestParam(required = false) String projectId) {
        return patients.listRelationships(scope(projectId), patientId);
    }

    @PostMapping("/patients/{patientId}/relationships")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add patient relationship")
    public UUID addRelationship(
            @PathVariable UUID patientId,
            @RequestBody PatientModels.CreateRelationshipRequest req,
            @RequestParam(required = false) String projectId) {
        return patients.addRelationship(writeScope(projectId), patientId, req);
    }

    @DeleteMapping("/patients/{patientId}/relationships/{relationshipId}")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete patient relationship")
    public void deleteRelationship(
            @PathVariable UUID patientId,
            @PathVariable UUID relationshipId,
            @RequestParam(required = false) String projectId) {
        patients.removeRelationship(writeScope(projectId), patientId, relationshipId);
    }

    // --- S1: Consents ---
    @GetMapping("/patients/{patientId}/consents")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Patient consents")
    public List<PatientModels.ConsentRow> consents(
            @PathVariable UUID patientId,
            @RequestParam(required = false) String projectId) {
        return patients.listConsents(scope(projectId), patientId);
    }

    @PostMapping("/patients/{patientId}/consents")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Grant patient consent")
    public UUID addConsent(
            @PathVariable UUID patientId,
            @RequestBody PatientModels.CreateConsentRequest req,
            @RequestParam(required = false) String projectId) {
        return patients.addConsent(writeScope(projectId), patientId, req);
    }

    @PostMapping("/patients/{patientId}/consents/{consentId}/revoke")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Revoke patient consent")
    public boolean revokeConsent(
            @PathVariable UUID patientId,
            @PathVariable UUID consentId,
            @RequestBody(required = false) PatientModels.RevokeConsentRequest req,
            @RequestParam(required = false) String projectId) {
        String reason = req != null ? req.revocationReason() : "Revoked by clinician";
        return patients.revokeConsent(writeScope(projectId), patientId, consentId, reason);
    }

    // --- S1: Disclosures ---
    @GetMapping("/patients/{patientId}/disclosures")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Patient disclosure logs")
    public List<PatientModels.DisclosureLogRow> disclosures(
            @PathVariable UUID patientId,
            @RequestParam(required = false) String projectId) {
        return patients.listDisclosures(scope(projectId), patientId);
    }

    @PostMapping("/patients/{patientId}/disclosures")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Log record disclosure")
    public UUID logDisclosure(
            @PathVariable UUID patientId,
            @RequestBody PatientModels.LogDisclosureRequest req,
            @RequestParam(required = false) String projectId) {
        UUID userId = TenantContextHolder.get().userId() != null
                ? TenantContextHolder.get().userId()
                : TenantContextHolder.get().principalId();
        return patients.logDisclosure(writeScope(projectId), patientId, userId, req);
    }

    // --- S1: Archive & Restore ---
    @PostMapping("/patients/{patientId}/archive")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Archive patient record (soft delete)")
    public boolean archivePatient(
            @PathVariable UUID patientId,
            @RequestBody(required = false) PatientModels.ArchivePatientRequest req,
            @RequestParam(required = false) String projectId) {
        UUID userId = TenantContextHolder.get().userId() != null
                ? TenantContextHolder.get().userId()
                : TenantContextHolder.get().principalId();
        String reason = req != null && req.reason() != null ? req.reason() : "Archived by clinician";
        return patients.archive(writeScope(projectId), patientId, userId, reason);
    }

    @PostMapping("/patients/{patientId}/restore")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Restore archived patient record")
    public boolean restorePatient(
            @PathVariable UUID patientId,
            @RequestParam(required = false) String projectId) {
        return patients.restore(writeScope(projectId), patientId);
    }

    // --- S1: Merge ---
    @PostMapping("/patients/merge")
    @RequirePermission(Permission.APPOINTMENT_READ)
    @Operation(summary = "Explicitly merge two patient records")
    public PatientModels.MergePatientsResult mergePatients(
            @RequestBody PatientModels.MergePatientsRequest req,
            @RequestParam(required = false) String projectId) {
        UUID userId = TenantContextHolder.get().userId() != null
                ? TenantContextHolder.get().userId()
                : TenantContextHolder.get().principalId();
        return patientRepository.mergePatients(writeScope(projectId), req.sourcePatientId(), req.targetPatientId(), userId, req.reason());
    }

    /**
     * The scope every READ is bound by.
     *
     * <p>Built through {@link ClinicScope}'s checked construction paths, never from a raw project
     * id, so a client-supplied project is a request and not an authorization.
     *
     * <p>A caller with no membership gets an EMPTY scope rather than a refusal, so an outsider
     * reads an empty list. That is the same non-disclosure property a 404 would give, and it is
     * what the rest of the clinic surface already does.
     */
    private ClinicScope scope(String projectId) {
        return ClinicScope.forRequestedProject(
                guard, TenantContextHolder.get(), RequestParams.optionalUuid(projectId, "projectId"));
    }

    /**
     * The scope a WRITE is bound by, which is narrower.
     *
     * <p>A write belongs to exactly one clinic, so a scope that is not project-scoped has no
     * correct {@code projectId} to write. Writing "the first accessible project" would file the
     * row under a clinic the caller never named, and a caller with several clinics would have the
     * choice made for them.
     *
     * <p>So: a named project is used, and otherwise a caller who can reach EXACTLY ONE clinic is
     * unambiguous - that is not a default being guessed, it is the only answer there is. A caller
     * who can reach several is refused, because there is no correct answer to pick.
     *
     * <p>Added because the vocabulary route answered 500 in exactly this case: the template copy
     * needs a project and the caller had named none while holding two.
     */
    private ClinicScope writeScope(String projectId) {
        ClinicScope scope = scope(projectId);
        if (scope.isProjectScoped()) {
            return scope;
        }
        var reachable = scope.accessibleProjectIds();
        if (!reachable.isEmpty()) {
            return ClinicScope.forRequestedProject(guard, TenantContextHolder.get(), reachable.get(0));
        }
        throw new ResourceNotFound();
    }

    /**
     * A non-disclosing miss.
     *
     * <p>Reuses {@link com.firstelement.crm.error.ResourceNotFoundException} so this controller
     * produces byte-identical output to {@link ClinicController} and to a cross-tenant
     * {@code PolicyDeniedException}. A private exception carrying "patient not found" would have
     * been the more obvious choice and would have been wrong: a per-resource message is itself an
     * existence oracle, separating "no such patient" from "that patient is not yours" from "you may
     * not ask". On a surface carrying patient names that is a directory of who is a patient.
     */
    private static final class ResourceNotFound
            extends com.firstelement.crm.error.ResourceNotFoundException {

        private static final long serialVersionUID = 1L;

        ResourceNotFound() {
            super("Resource not found");
        }
    }
}