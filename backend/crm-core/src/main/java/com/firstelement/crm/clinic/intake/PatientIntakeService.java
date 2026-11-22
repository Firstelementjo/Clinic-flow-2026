package com.firstelement.crm.clinic.intake;

import com.firstelement.crm.clinic.ClinicScope;
import com.firstelement.crm.clinic.intake.PatientIntakeModels.ActiveIntakeCodeStatus;
import com.firstelement.crm.clinic.intake.PatientIntakeModels.ClinicIntakeQrInfo;
import com.firstelement.crm.clinic.intake.PatientIntakeModels.GeneratedIntakeCode;
import com.firstelement.crm.clinic.intake.PatientIntakeModels.IntakeChiefComplaint;
import com.firstelement.crm.clinic.intake.PatientIntakeModels.IntakeDemographics;
import com.firstelement.crm.clinic.intake.PatientIntakeModels.IntakeDentalHistory;
import com.firstelement.crm.clinic.intake.PatientIntakeModels.IntakeMedicalHistory;
import com.firstelement.crm.clinic.intake.PatientIntakeModels.PatientHistoryIntakeRecord;
import com.firstelement.crm.clinic.intake.PatientIntakeModels.PublicClinicConfig;
import com.firstelement.crm.clinic.intake.PatientIntakeModels.PublicIntakeSchema;
import com.firstelement.crm.clinic.intake.PatientIntakeModels.SavePatientHistoryIntakeRequest;
import com.firstelement.crm.clinic.intake.PatientIntakeModels.SchemaOption;
import com.firstelement.crm.clinic.intake.PatientIntakeModels.SubmitIntakeRequest;
import com.firstelement.crm.clinic.intake.PatientIntakeModels.SubmitIntakeResult;
import com.firstelement.crm.clinic.intake.PatientIntakeModels.VerifyCodeResponse;
import com.firstelement.crm.clinic.intake.PatientIntakeRepository.IntakeCodeRow;
import com.firstelement.crm.clinic.intake.PatientIntakeRepository.ProjectIntakeTarget;
import com.firstelement.crm.clinic.patient.PatientModels;
import com.firstelement.crm.clinic.patient.PatientService;
import com.firstelement.crm.error.ResourceNotFoundException;
import com.firstelement.crm.error.ValidationFailedException;
import com.firstelement.security.TenantContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business logic for the Patient Intake Portal and 9-step Patient History persistence.
 *
 * <p>Security boundary: public verification returns only the clinic name, patient's first name,
 * and a short-lived opaque submission token. Public submission writes into a quarantined
 * {@code PENDING_APPROVAL} record in {@code patient_history_intakes} and never mutates
 * {@code patients} or {@code patient_medical_histories} directly until a clinician approves it.
 */
@Service
public class PatientIntakeService {

    private static final Pattern CLINIC_KEY_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{3,64}$");
    private static final char[] CODE_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ".toCharArray();
    private static final int CODE_LENGTH = 6;
    private static final Duration CODE_TTL = Duration.ofHours(1);
    private static final Duration SESSION_TTL = Duration.ofMinutes(30);

    private static final List<SchemaOption> MEDICAL_CONDITIONS = List.of(
            new SchemaOption("Heart Disease", "Heart Disease", "أمراض القلب"),
            new SchemaOption("High/Low Blood Pressure", "High/Low Blood Pressure", "ارتفاع أو انخفاض ضغط الدم"),
            new SchemaOption("Diabetes", "Diabetes", "مرض السكري"),
            new SchemaOption("Asthma", "Asthma", "الربو"),
            new SchemaOption("Bleeding Disorders", "Bleeding Disorders", "اضطرابات النزيف وسيولة الدم"),
            new SchemaOption("Epilepsy/Seizures", "Epilepsy/Seizures", "الصرع أو التشنجات"),
            new SchemaOption("Hepatitis A/B/C", "Hepatitis A/B/C", "التهاب الكبد الوبائي A/B/C"),
            new SchemaOption("HIV/AIDS", "HIV/AIDS", "نقص المناعة المكتسبة"),
            new SchemaOption("Tuberculosis", "Tuberculosis", "السل"),
            new SchemaOption("Kidney Disease", "Kidney Disease", "أمراض الكلى"),
            new SchemaOption("Liver Disease", "Liver Disease", "أمراض الكبد"),
            new SchemaOption("Cancer", "Cancer", "الأورام / السرطان"),
            new SchemaOption("Psychiatric Disorders", "Psychiatric Disorders", "اضطرابات نفسية"),
            new SchemaOption("Thyroid Problems", "Thyroid Problems", "مشاكل الغدة الدرقية"),
            new SchemaOption("Osteoporosis", "Osteoporosis", "هشاشة العظام"),
            new SchemaOption("Other", "Other", "أخرى")
    );

    private static final List<SchemaOption> MEDICATION_TYPES = List.of(
            new SchemaOption("Blood Thinners", "Blood Thinners", "مميعات الدم"),
            new SchemaOption("Bisphosphonates", "Bisphosphonates", "أدوية هشاشة العظام (Bisphosphonates)"),
            new SchemaOption("Steroids", "Steroids", "الكورتيزون / الستيرويدات"),
            new SchemaOption("Immunosuppressors", "Immunosuppressors", "مثبطات المناعة"),
            new SchemaOption("Herbal Supplements", "Herbal Supplements", "مكملات عشبية"),
            new SchemaOption("Others", "Others", "أدوية أخرى")
    );

    private static final List<SchemaOption> ALLERGY_TYPES = List.of(
            new SchemaOption("Local Anesthetics", "Local Anesthetics", "المخدر الموضعي"),
            new SchemaOption("Latex", "Latex", "اللاتكس (القفازات المطاطية)"),
            new SchemaOption("Penicillin/Antibiotics", "Penicillin/Antibiotics", "البنسلين والمضادات الحيوية"),
            new SchemaOption("Other", "Other", "حساسية أخرى")
    );

    private static final List<SchemaOption> DENTAL_SYMPTOMS = List.of(
            new SchemaOption("Dry mouth", "Dry mouth", "جفاف الفم"),
            new SchemaOption("Loose or shifting teeth", "Loose or shifting teeth", "تخلخل أو تحرك الأسنان"),
            new SchemaOption("Previous orthodontic treatment", "Previous orthodontic treatment", "علاج تقويم أسنان سابق"),
            new SchemaOption("History of dental trauma", "History of dental trauma", "تاريخ إصابة أو صدمة للأسنان"),
            new SchemaOption("Problems with past dental treatments", "Problems with past dental treatments", "مضاعفات في علاجات أسنان سابقة")
    );

    private static final Set<String> VALID_MEDICAL_CONDITION_KEYS = Set.of(
            "Heart Disease", "High/Low Blood Pressure", "Diabetes", "Asthma",
            "Bleeding Disorders", "Epilepsy/Seizures", "Hepatitis A/B/C", "HIV/AIDS",
            "Tuberculosis", "Kidney Disease", "Liver Disease", "Cancer",
            "Psychiatric Disorders", "Thyroid Problems", "Osteoporosis", "Other"
    );

    private static final Set<String> VALID_MEDICATION_KEYS = Set.of(
            "Blood Thinners", "Bisphosphonates", "Steroids",
            "Immunosuppressors", "Herbal Supplements", "Others"
    );

    private static final Set<String> VALID_ALLERGY_KEYS = Set.of(
            "Local Anesthetics", "Latex", "Penicillin/Antibiotics", "Other"
    );

    private static final Set<String> VALID_SYMPTOM_KEYS = Set.of(
            "Dry mouth", "Loose or shifting teeth", "Previous orthodontic treatment",
            "History of dental trauma", "Problems with past dental treatments"
    );

    private final SecureRandom random = new SecureRandom();
    private final PatientIntakeRepository repository;
    private final PatientService patientService;

    public PatientIntakeService(PatientIntakeRepository repository, PatientService patientService) {
        this.repository = repository;
        this.patientService = patientService;
    }

    // ------------------------------------------------------------------
    // Clinic Staff: QR info, 1-hour code generation, status & history CRUD
    // ------------------------------------------------------------------

    @Transactional
    public ClinicIntakeQrInfo getClinicQrInfo(ClinicScope scope) {
        UUID projectId = requireSingleProject(scope);
        ProjectIntakeTarget target = repository.findProjectById(scope, projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Resource not found"));

        String portalKey = target.intakePortalKey();
        if (portalKey == null || portalKey.isBlank()) {
            portalKey = generateUniqueKeyFromSlug(target.slug());
            repository.updateIntakePortalKey(scope, projectId, portalKey);
        }

        return new ClinicIntakeQrInfo(
                target.projectId(),
                target.organizationId(),
                target.clinicName(),
                target.slug(),
                portalKey,
                target.intakePortalEnabled(),
                target.defaultLocale());
    }

    @Transactional
    public GeneratedIntakeCode generateIntakeCode(ClinicScope scope, UUID patientId, TenantContext caller) {
        UUID projectId = requireSingleProject(scope);
        PatientModels.PatientRow patient = patientService.find(scope, patientId)
                .orElseThrow(() -> new ResourceNotFoundException("Resource not found"));

        repository.revokeUnusedCodesForPatient(scope, patientId);

        String plainCode = generatePlainCode();
        String codeHash = sha256Hex(plainCode);
        String codeHint = plainCode.substring(0, 2) + "****";
        Instant expiresAt = Instant.now().plus(CODE_TTL);
        UUID actorUserId = caller != null ? caller.userId() : null;

        repository.insertIntakeCode(scope, projectId, patientId, codeHash, codeHint, expiresAt, actorUserId);

        return new GeneratedIntakeCode(
                plainCode,
                formatCode(plainCode),
                expiresAt,
                (int) CODE_TTL.toSeconds(),
                patient.id(),
                patient.displayName());
    }

    public ActiveIntakeCodeStatus getIntakeStatus(ClinicScope scope, UUID patientId) {
        patientService.find(scope, patientId)
                .orElseThrow(() -> new ResourceNotFoundException("Resource not found"));

        Instant now = Instant.now();
        Optional<IntakeCodeRow> latestCode = repository.findLatestUnusedCodeForPatient(scope, patientId)
                .filter(c -> c.expiresAt() != null && c.expiresAt().isAfter(now));

        List<PatientHistoryIntakeRecord> records = repository.listHistoryRecords(scope, patientId);
        PatientHistoryIntakeRecord latestRecord = records.isEmpty() ? null : records.get(0);

        boolean hasActive = latestCode.isPresent();
        String codeHint = latestCode.map(IntakeCodeRow::codeHint).orElse(null);
        Instant expiresAt = latestCode.map(IntakeCodeRow::expiresAt).orElse(null);
        long remaining = latestCode
                .map(c -> Math.max(0L, Duration.between(now, c.expiresAt()).getSeconds()))
                .orElse(0L);

        return new ActiveIntakeCodeStatus(
                hasActive,
                codeHint,
                expiresAt,
                remaining,
                latestRecord != null ? latestRecord.status() : null,
                latestRecord != null ? latestRecord.id() : null,
                latestRecord != null ? latestRecord.submittedAt() : null);
    }

    public List<PatientHistoryIntakeRecord> listHistoryRecords(ClinicScope scope, UUID patientId) {
        patientService.find(scope, patientId)
                .orElseThrow(() -> new ResourceNotFoundException("Resource not found"));
        return repository.listHistoryRecords(scope, patientId);
    }

    @Transactional
    public PatientHistoryIntakeRecord saveOrApproveHistoryRecord(
            ClinicScope scope,
            UUID patientId,
            SavePatientHistoryIntakeRequest req,
            TenantContext caller) {
        UUID projectId = requireSingleProject(scope);
        PatientModels.PatientRow patient = patientService.find(scope, patientId)
                .orElseThrow(() -> new ResourceNotFoundException("Resource not found"));

        PatientHistoryIntakeRecord existing = req.id() != null
                ? repository.findHistoryRecordById(scope, patientId, req.id()).orElse(null)
                : null;

        boolean approve = Boolean.TRUE.equals(req.approve());
        boolean isComplete = Boolean.TRUE.equals(req.isComplete());

        String effectiveStatus;
        if (approve) {
            effectiveStatus = isComplete ? "COMPLETED" : "APPROVED";
        } else if (req.status() != null && Set.of("PENDING_APPROVAL", "APPROVED", "COMPLETED").contains(req.status())) {
            effectiveStatus = req.status();
        } else if (existing != null) {
            effectiveStatus = existing.status();
        } else {
            effectiveStatus = isComplete ? "COMPLETED" : "APPROVED";
        }

        String source = existing != null ? existing.source() : "CLINIC_STAFF";
        boolean shouldSyncToPatientRecord = approve
                || "APPROVED".equals(effectiveStatus)
                || "COMPLETED".equals(effectiveStatus);

        UUID actorUserId = caller != null ? caller.userId() : null;
        Instant approvedAt = shouldSyncToPatientRecord ? Instant.now() : null;

        Map<String, Object> demographics = req.demographics() != null
                ? req.demographics()
                : (existing != null ? existing.demographics() : Map.of());
        Map<String, Object> medical = req.medicalHistory() != null
                ? req.medicalHistory()
                : (existing != null ? existing.medicalHistory() : Map.of());
        Map<String, Object> dental = req.dentalHistory() != null
                ? req.dentalHistory()
                : (existing != null ? existing.dentalHistory() : Map.of());
        Map<String, Object> complaint = req.chiefComplaint() != null
                ? req.chiefComplaint()
                : (existing != null ? existing.chiefComplaint() : Map.of());
        Map<String, Object> clinicalExam = req.clinicalExam() != null
                ? req.clinicalExam()
                : (existing != null ? existing.clinicalExam() : defaultClinicalExamMap());
        Map<String, Object> radiographs = req.radiographs() != null
                ? req.radiographs()
                : (existing != null ? existing.radiographs() : defaultRadiographsMap());
        Map<String, Object> diagnosisAndPlan = req.diagnosisAndPlan() != null
                ? req.diagnosisAndPlan()
                : (existing != null ? existing.diagnosisAndPlan() : defaultDiagnosisAndPlanMap());
        Map<String, Object> dentalChart = req.dentalChart() != null
                ? req.dentalChart()
                : (existing != null ? existing.dentalChart() : defaultDentalChartMap());
        Map<String, Object> informedConsent = req.informedConsent() != null
                ? req.informedConsent()
                : (existing != null ? existing.informedConsent() : defaultInformedConsentMap());

        UUID savedId = repository.upsertHistoryRecord(
                scope,
                projectId,
                patientId,
                existing != null ? existing.id() : null,
                req.encounterId(),
                effectiveStatus,
                source,
                isComplete,
                demographics,
                medical,
                dental,
                complaint,
                clinicalExam,
                radiographs,
                diagnosisAndPlan,
                dentalChart,
                informedConsent,
                approvedAt,
                shouldSyncToPatientRecord ? actorUserId : null);

        if (shouldSyncToPatientRecord) {
            syncApprovedRecordToPatientProfile(scope, projectId, patient, demographics, medical, dental, complaint, actorUserId);
        }

        return repository.findHistoryRecordById(scope, patientId, savedId)
                .orElseThrow(() -> new ResourceNotFoundException("Resource not found"));
    }

    @Transactional
    public boolean deleteHistoryRecord(ClinicScope scope, UUID patientId, UUID recordId) {
        patientService.find(scope, patientId)
                .orElseThrow(() -> new ResourceNotFoundException("Resource not found"));
        return repository.deleteHistoryRecord(scope, patientId, recordId);
    }

    // ------------------------------------------------------------------
    // Public Portal Endpoints (No session cookie, clinicKey + 1h code)
    // ------------------------------------------------------------------

    public PublicClinicConfig getPublicConfig(String clinicKey) {
        ProjectIntakeTarget clinic = requireClinicByKey(clinicKey);
        return new PublicClinicConfig(
                clinic.clinicName(),
                clinic.intakePortalKey() != null ? clinic.intakePortalKey() : clinic.slug(),
                clinic.defaultLocale() != null ? clinic.defaultLocale() : "ar",
                clinic.isActiveForIntake(),
                new PublicIntakeSchema(MEDICAL_CONDITIONS, MEDICATION_TYPES, ALLERGY_TYPES, DENTAL_SYMPTOMS));
    }

    @Transactional
    public VerifyCodeResponse verifyCode(String clinicKey, String rawCode) {
        ProjectIntakeTarget clinic = requireActiveClinicByKey(clinicKey);
        String normalized = normalizeCode(rawCode);
        if (normalized.length() < 4 || normalized.length() > 16) {
            throw new ValidationFailedException(
                    "Invalid patient intake code.",
                    Map.of("field", "code", "reason", "INVALID_CODE"));
        }

        String hash = sha256Hex(normalized);
        IntakeCodeRow codeRow = repository.findActiveCodeByHash(clinic.projectId(), hash)
                .orElseThrow(() -> new ValidationFailedException(
                        "Invalid or expired patient intake code.",
                        Map.of("field", "code", "reason", "INVALID_CODE")));

        Instant now = Instant.now();
        if (codeRow.expiresAt() == null || !codeRow.expiresAt().isAfter(now)) {
            throw new ValidationFailedException(
                    "This intake code has expired. Please ask reception for a new code.",
                    Map.of("field", "code", "reason", "CODE_EXPIRED"));
        }

        String firstName = repository.findPatientFirstNamePublic(clinic.organizationId(), codeRow.patientId())
                .filter(name -> !name.isBlank())
                .orElse("Patient");

        String sessionToken = generateSessionToken();
        String sessionTokenHash = sha256Hex(sessionToken);
        Instant sessionExpiresAt = now.plus(SESSION_TTL);

        repository.attachSessionToken(codeRow.id(), sessionTokenHash, sessionExpiresAt);

        return new VerifyCodeResponse(
                true,
                clinic.clinicName(),
                firstName,
                sessionToken,
                sessionExpiresAt);
    }

    @Transactional
    public SubmitIntakeResult submitPublicIntake(String clinicKey, SubmitIntakeRequest req) {
        ProjectIntakeTarget clinic = requireActiveClinicByKey(clinicKey);
        if (req == null || req.intakeSessionToken() == null || req.intakeSessionToken().isBlank()) {
            throw new ValidationFailedException(
                    "Intake session token is required.",
                    Map.of("field", "intakeSessionToken", "reason", "SESSION_REQUIRED"));
        }

        String tokenHash = sha256Hex(req.intakeSessionToken().trim());
        IntakeCodeRow codeRow = repository.findActiveSessionByTokenHash(clinic.projectId(), tokenHash)
                .orElseThrow(() -> new ValidationFailedException(
                        "Your intake session has expired or was already submitted.",
                        Map.of("field", "intakeSessionToken", "reason", "SESSION_INVALID")));

        Instant now = Instant.now();
        if (codeRow.sessionExpiresAt() == null || !codeRow.sessionExpiresAt().isAfter(now)) {
            throw new ValidationFailedException(
                    "Your intake session has expired. Please request a new code.",
                    Map.of("field", "intakeSessionToken", "reason", "SESSION_EXPIRED"));
        }

        IntakeDemographics demo = req.demographics();
        if (demo == null) {
            throw new ValidationFailedException("Demographics are required.", Map.of("field", "demographics"));
        }
        LocalDate parsedDob = parseRequiredDob(demo.dob());
        String normalizedGender = normalizeWizardGender(demo.gender());
        if (normalizedGender == null) {
            throw new ValidationFailedException("Gender is required.", Map.of("field", "gender"));
        }

        IntakeChiefComplaint cc = req.chiefComplaint();
        String primaryComplaint = cc != null ? cleanText(cc.primaryComplaint(), 2000) : null;
        if (primaryComplaint == null || primaryComplaint.isBlank()) {
            throw new ValidationFailedException("Primary chief complaint is required.", Map.of("field", "primaryComplaint"));
        }

        // Burn code atomically before persisting so duplicate/concurrent submits are impossible.
        boolean consumed = repository.markCodeAsUsed(codeRow.id());
        if (!consumed) {
            throw new ValidationFailedException(
                    "This intake form was already submitted.",
                    Map.of("field", "intakeSessionToken", "reason", "ALREADY_SUBMITTED"));
        }

        Map<String, Object> demographicsMap = new LinkedHashMap<>();
        demographicsMap.put("fullName", cleanOrEmpty(demo.fullName(), 160));
        demographicsMap.put("dob", parsedDob.toString());
        demographicsMap.put("gender", normalizedGender);
        demographicsMap.put("occupation", cleanOrEmpty(demo.occupation(), 120));
        demographicsMap.put("address", cleanOrEmpty(demo.address(), 500));
        demographicsMap.put("phone", cleanOrEmpty(demo.phone(), 40));
        demographicsMap.put("email", cleanOrEmpty(demo.email(), 160));
        demographicsMap.put("emergencyName", cleanOrEmpty(demo.emergencyName(), 160));
        demographicsMap.put("emergencyPhone", cleanOrEmpty(demo.emergencyPhone(), 40));

        IntakeMedicalHistory med = req.medicalHistory();
        Map<String, Object> medicalMap = new LinkedHashMap<>();
        medicalMap.put("underTreatment", normalizeYesNo(med != null ? med.underTreatment() : null, "No"));
        medicalMap.put("treatmentSpecify", cleanOrEmpty(med != null ? med.treatmentSpecify() : null, 400));
        medicalMap.put("hospitalizedLastYear", normalizeYesNo(med != null ? med.hospitalizedLastYear() : null, "No"));
        medicalMap.put("hospitalizedReason", cleanOrEmpty(med != null ? med.hospitalizedReason() : null, 400));
        medicalMap.put("conditions", filterAllowed(med != null ? med.conditions() : null, VALID_MEDICAL_CONDITION_KEYS));
        medicalMap.put("otherCondition", cleanOrEmpty(med != null ? med.otherCondition() : null, 400));
        medicalMap.put("pregnantOrBreastfeeding", normalizePregnancy(med != null ? med.pregnantOrBreastfeeding() : null));
        medicalMap.put("medications", filterAllowed(med != null ? med.medications() : null, VALID_MEDICATION_KEYS));
        medicalMap.put("otherMedications", cleanOrEmpty(med != null ? med.otherMedications() : null, 400));
        medicalMap.put("allergies", filterAllowed(med != null ? med.allergies() : null, VALID_ALLERGY_KEYS));
        medicalMap.put("otherAllergies", cleanOrEmpty(med != null ? med.otherAllergies() : null, 400));

        IntakeDentalHistory dent = req.dentalHistory();
        Map<String, Object> dentalMap = new LinkedHashMap<>();
        dentalMap.put("lastDentalVisitDate", cleanDateOrEmpty(dent != null ? dent.lastDentalVisitDate() : null));
        dentalMap.put("lastVisitReason", cleanOrEmpty(dent != null ? dent.lastVisitReason() : null, 300));
        dentalMap.put("symptoms", filterAllowed(dent != null ? dent.symptoms() : null, VALID_SYMPTOM_KEYS));
        dentalMap.put("pastProcedures", cleanOrEmpty(dent != null ? dent.pastProcedures() : null, 1000));
        int anxiety = dent != null && dent.anxietyLevel() != null
                ? Math.min(5, Math.max(1, dent.anxietyLevel()))
                : 1;
        dentalMap.put("anxietyLevel", anxiety);

        Map<String, Object> complaintMap = new LinkedHashMap<>();
        complaintMap.put("primaryComplaint", primaryComplaint);
        int painScale = cc.painScale() != null ? Math.min(10, Math.max(0, cc.painScale())) : 0;
        complaintMap.put("painScale", painScale);
        complaintMap.put("duration", cleanOrEmpty(cc.duration(), 200));
        complaintMap.put("triggers", sanitizeStringList(cc.triggers(), 10, 80));

        UUID intakeId = repository.insertPublicIntakeHistory(
                clinic.organizationId(),
                clinic.projectId(),
                codeRow.patientId(),
                demographicsMap,
                medicalMap,
                dentalMap,
                complaintMap,
                defaultClinicalExamMap(),
                defaultRadiographsMap(),
                defaultDiagnosisAndPlanMap(),
                defaultDentalChartMap(),
                defaultInformedConsentMap());

        return new SubmitIntakeResult(true, intakeId, "PENDING_APPROVAL");
    }

    // ------------------------------------------------------------------
    // Internal sync & helpers
    // ------------------------------------------------------------------

    private void syncApprovedRecordToPatientProfile(
            ClinicScope scope,
            UUID projectId,
            PatientModels.PatientRow currentPatient,
            Map<String, Object> demographics,
            Map<String, Object> medical,
            Map<String, Object> dental,
            Map<String, Object> complaint,
            UUID actorUserId) {
        String fullName = str(demographics.get("fullName"));
        String givenName = null;
        String familyName = null;
        if (fullName != null) {
            String[] parts = fullName.trim().split("\\s+", 2);
            givenName = parts[0];
            if (parts.length > 1 && !parts[1].isBlank()) {
                familyName = parts[1];
            }
        }

        LocalDate dob = null;
        String dobStr = str(demographics.get("dob"));
        if (dobStr != null) {
            try {
                LocalDate candidate = LocalDate.parse(dobStr);
                if (!candidate.isAfter(LocalDate.now())) {
                    dob = candidate;
                }
            } catch (DateTimeParseException ignored) {
            }
        }

        String dbGender = normalizeDbGender(str(demographics.get("gender")));
        String occupation = str(demographics.get("occupation"));
        String address = str(demographics.get("address"));
        String email = str(demographics.get("email"));
        String submittedPhone = str(demographics.get("phone"));
        String phoneToWrite = null;
        if (submittedPhone != null && !repository.isPhoneTakenByAnotherPatient(scope, currentPatient.id(), submittedPhone)) {
            phoneToWrite = submittedPhone;
        }

        repository.syncPatientDemographics(
                scope,
                currentPatient.id(),
                fullName,
                givenName,
                familyName,
                dob,
                dbGender,
                occupation,
                address,
                email,
                phoneToWrite);

        String emergencyName = str(demographics.get("emergencyName"));
        String emergencyPhone = str(demographics.get("emergencyPhone"));
        repository.upsertEmergencyContact(scope, projectId, currentPatient.id(), emergencyName, emergencyPhone);

        List<String> conditions = strList(medical.get("conditions"));
        String otherCondition = str(medical.get("otherCondition"));
        List<String> medications = strList(medical.get("medications"));
        String otherMedications = str(medical.get("otherMedications"));
        List<String> allergies = strList(medical.get("allergies"));
        String otherAllergies = str(medical.get("otherAllergies"));

        String conditionsCsv = joinWithOther(conditions, otherCondition);
        String medicationsCsv = joinWithOther(medications, otherMedications);
        String allergiesCsv = joinWithOther(allergies, otherAllergies);

        boolean bleeding = conditions.contains("Bleeding Disorders");
        boolean cardiac = conditions.contains("Heart Disease") || conditions.contains("High/Low Blood Pressure");
        boolean diabetes = conditions.contains("Diabetes");

        List<String> notesParts = new ArrayList<>();
        String underTx = str(medical.get("underTreatment"));
        String txSpec = str(medical.get("treatmentSpecify"));
        if ("Yes".equalsIgnoreCase(underTx)) {
            notesParts.add("Under treatment" + (txSpec != null ? ": " + txSpec : ""));
        }
        String hosp = str(medical.get("hospitalizedLastYear"));
        String hospReason = str(medical.get("hospitalizedReason"));
        if ("Yes".equalsIgnoreCase(hosp)) {
            notesParts.add("Hospitalized last year" + (hospReason != null ? ": " + hospReason : ""));
        }
        String preg = str(medical.get("pregnantOrBreastfeeding"));
        if ("Yes".equalsIgnoreCase(preg)) {
            notesParts.add("Pregnant/Breastfeeding: Yes");
        }
        String primaryComplaint = str(complaint.get("primaryComplaint"));
        if (primaryComplaint != null) {
            notesParts.add("Chief Complaint: " + primaryComplaint);
        }
        Object anxietyObj = dental.get("anxietyLevel");
        if (anxietyObj != null) {
            notesParts.add("Dental Anxiety Level: " + anxietyObj + "/5");
        }

        repository.upsertMedicalHistorySummary(
                scope,
                projectId,
                currentPatient.id(),
                actorUserId,
                allergiesCsv,
                conditionsCsv,
                medicationsCsv,
                bleeding,
                cardiac,
                diabetes,
                notesParts.isEmpty() ? null : String.join(" | ", notesParts));
    }

    private ProjectIntakeTarget requireClinicByKey(String clinicKey) {
        if (clinicKey == null || !CLINIC_KEY_PATTERN.matcher(clinicKey.trim()).matches()) {
            throw new ResourceNotFoundException("Resource not found");
        }
        return repository.findProjectByIntakeKey(clinicKey.trim())
                .orElseThrow(() -> new ResourceNotFoundException("Resource not found"));
    }

    private ProjectIntakeTarget requireActiveClinicByKey(String clinicKey) {
        ProjectIntakeTarget target = requireClinicByKey(clinicKey);
        if (!target.isActiveForIntake()) {
            throw new ValidationFailedException(
                    "The patient intake portal is currently unavailable for this clinic.",
                    Map.of("reason", "PORTAL_DISABLED"));
        }
        return target;
    }

    private UUID requireSingleProject(ClinicScope scope) {
        if (scope.isProjectScoped() && scope.projectId() != null) {
            return scope.projectId();
        }
        List<UUID> reachable = scope.accessibleProjectIds();
        if (!reachable.isEmpty()) {
            return reachable.get(0);
        }
        throw new ResourceNotFoundException("Resource not found");
    }

    private String generatePlainCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)]);
        }
        return sb.toString();
    }

    private static String formatCode(String code) {
        if (code == null || code.length() != 6) {
            return code;
        }
        return code.substring(0, 3) + "-" + code.substring(3);
    }

    private static String normalizeCode(String rawCode) {
        if (rawCode == null) {
            return "";
        }
        return rawCode.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
    }

    private String generateSessionToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String generateUniqueKeyFromSlug(String slug) {
        String base = (slug == null || slug.isBlank()) ? "clinic" : slug.replaceAll("[^a-zA-Z0-9_-]", "-");
        if (base.length() > 48) {
            base = base.substring(0, 48);
        }
        return base + "-" + generatePlainCode().toLowerCase(Locale.ROOT);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static LocalDate parseRequiredDob(String rawDob) {
        if (rawDob == null || rawDob.isBlank()) {
            throw new ValidationFailedException("Date of birth is required.", Map.of("field", "dob"));
        }
        try {
            LocalDate dob = LocalDate.parse(rawDob.trim());
            if (dob.isAfter(LocalDate.now())) {
                throw new ValidationFailedException("Date of birth cannot be in the future.", Map.of("field", "dob"));
            }
            if (dob.isBefore(LocalDate.of(1900, 1, 1))) {
                throw new ValidationFailedException("Date of birth is invalid.", Map.of("field", "dob"));
            }
            return dob;
        } catch (DateTimeParseException ex) {
            throw new ValidationFailedException("Invalid date of birth format (YYYY-MM-DD).", Map.of("field", "dob"));
        }
    }

    private static String normalizeWizardGender(String rawGender) {
        if (rawGender == null || rawGender.isBlank()) {
            return null;
        }
        return switch (rawGender.trim().toUpperCase(Locale.ROOT)) {
            case "MALE", "M" -> "Male";
            case "FEMALE", "F" -> "Female";
            case "OTHER" -> "Other";
            default -> null;
        };
    }

    private static String normalizeDbGender(String rawGender) {
        if (rawGender == null || rawGender.isBlank()) {
            return null;
        }
        return switch (rawGender.trim().toUpperCase(Locale.ROOT)) {
            case "MALE", "M" -> "MALE";
            case "FEMALE", "F" -> "FEMALE";
            case "OTHER" -> "OTHER";
            default -> null;
        };
    }

    private static String normalizeYesNo(String raw, String fallback) {
        if (raw == null) {
            return fallback;
        }
        return "YES".equalsIgnoreCase(raw.trim()) ? "Yes" : "No";
    }

    private static String normalizePregnancy(String raw) {
        if (raw == null) {
            return "N/A";
        }
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "YES" -> "Yes";
            case "NO" -> "No";
            default -> "N/A";
        };
    }

    private static String cleanText(String raw, int maxLen) {
        if (raw == null) {
            return null;
        }
        // Strip control chars (except \n and \t) and angle brackets to harden against HTML/script injection.
        String cleaned = raw.replaceAll("[\\p{Cntrl}&&[^\n\t]]", "")
                .replace("<", "")
                .replace(">", "")
                .trim();
        if (cleaned.isEmpty()) {
            return null;
        }
        return cleaned.length() > maxLen ? cleaned.substring(0, maxLen) : cleaned;
    }

    private static String cleanOrEmpty(String raw, int maxLen) {
        String c = cleanText(raw, maxLen);
        return c == null ? "" : c;
    }

    private static String cleanDateOrEmpty(String raw) {
        String c = cleanText(raw, 20);
        if (c == null) {
            return "";
        }
        try {
            return LocalDate.parse(c).toString();
        } catch (DateTimeParseException ex) {
            return "";
        }
    }

    private static List<String> filterAllowed(List<String> input, Set<String> allowed) {
        if (input == null || input.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String item : input) {
            if (item != null && allowed.contains(item) && !out.contains(item)) {
                out.add(item);
            }
        }
        return out;
    }

    private static List<String> sanitizeStringList(List<String> input, int maxItems, int maxItemLen) {
        if (input == null || input.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String item : input) {
            if (out.size() >= maxItems) {
                break;
            }
            String clean = cleanText(item, maxItemLen);
            if (clean != null && !out.contains(clean)) {
                out.add(clean);
            }
        }
        return out;
    }

    private static String str(Object obj) {
        if (obj == null) {
            return null;
        }
        String s = String.valueOf(obj).trim();
        return s.isEmpty() ? null : s;
    }

    private static List<String> strList(Object obj) {
        if (!(obj instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            if (item != null) {
                String s = String.valueOf(item).trim();
                if (!s.isEmpty() && !out.contains(s)) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    private static String joinWithOther(List<String> items, String otherText) {
        List<String> combined = new ArrayList<>();
        for (String item : items) {
            if (!"Other".equalsIgnoreCase(item) && !"Others".equalsIgnoreCase(item)) {
                combined.add(item);
            }
        }
        if (otherText != null && !otherText.isBlank()) {
            combined.add(otherText.trim());
        }
        return combined.isEmpty() ? null : String.join(", ", combined);
    }

    private static Map<String, Object> defaultClinicalExamMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("faceSymmetry", "Normal");
        m.put("faceSymmetryDetails", "");
        m.put("tmj", "Normal");
        m.put("softTissues", "Normal");
        m.put("softTissuesDetails", "");
        m.put("teethFindings", List.of());
        m.put("gingivalHealth", "Healthy");
        m.put("bleedingOnProbing", "No");
        m.put("pocketDepths", "");
        m.put("mobility", "Absent");
        m.put("occlusion", "Normal");
        m.put("bruxism", "No");
        return m;
    }

    private static Map<String, Object> defaultRadiographsMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("types", List.of());
        m.put("findings", "");
        return m;
    }

    private static Map<String, Object> defaultDiagnosisAndPlanMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("diagnosis", "");
        m.put("proposedPlans", List.of());
        m.put("otherPlan", "");
        m.put("plannedTimeline", "");
        m.put("alternativesDiscussed", "Yes");
        m.put("risksExplained", "Yes");
        m.put("questionsAddressed", "Yes");
        return m;
    }

    private static Map<String, Object> defaultDentalChartMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("selectedTeeth", List.of());
        m.put("toothCondition", "");
        m.put("chartNotes", "");
        return m;
    }

    private static Map<String, Object> defaultInformedConsentMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("agreed", true);
        m.put("patientSignatureDataUrl", "");
        m.put("patientSignatureSaved", false);
        m.put("dentistSignatureDataUrl", "");
        m.put("dentistSignatureSaved", false);
        m.put("signedAt", "");
        return m;
    }
}
