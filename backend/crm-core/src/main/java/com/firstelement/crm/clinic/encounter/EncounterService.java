package com.firstelement.crm.clinic.encounter;

import com.firstelement.crm.clinic.ClinicScope;
import com.firstelement.crm.error.ResourceNotFoundException;
import com.firstelement.security.TenantContext;
import com.firstelement.security.UserRole;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for dental clinical encounters, medical history, vitals, immutable signing,
 * and clinical note amendments (CLN-01 to CLN-09).
 */
@Service
public class EncounterService {

    private static final Set<String> VALID_STATUSES = Set.of("IN_PROGRESS", "COMPLETED", "CANCELLED");

    private final EncounterRepository repository;

    public EncounterService(EncounterRepository repository) {
        this.repository = repository;
    }

    /**
     * Lists all encounters for a patient.
     * Mask medical data for receptionist staff (CLN-08).
     */
    public List<EncounterModels.EncounterHeader> listEncountersForPatient(
            ClinicScope scope, UUID patientId, TenantContext caller) {
        boolean maskMedical = isReceptionist(caller);
        return repository.listEncountersForPatient(scope, patientId, maskMedical);
    }

    /**
     * Finds a single encounter header.
     */
    public Optional<EncounterModels.EncounterHeader> findEncounter(
            ClinicScope scope, UUID encounterId, TenantContext caller) {
        boolean maskMedical = isReceptionist(caller);
        return repository.findEncounter(scope, encounterId, maskMedical);
    }

    /**
     * Retrieves full encounter detail with prescriptions and note amendments.
     * If receptionist, prescriptions and amendments are empty and notes are masked (CLN-08).
     */
    public EncounterModels.EncounterDetail getEncounterDetail(
            ClinicScope scope, UUID encounterId, TenantContext caller) {
        boolean maskMedical = isReceptionist(caller);
        EncounterModels.EncounterHeader header = repository.findEncounter(scope, encounterId, maskMedical)
                .orElseThrow(() -> new ResourceNotFoundException("Clinical encounter not found"));

        List<EncounterModels.PrescriptionRow> prescriptions = maskMedical
                ? Collections.emptyList()
                : repository.listPrescriptions(scope, encounterId);

        List<EncounterModels.ClinicalNoteAmendmentRow> amendments = maskMedical
                ? Collections.emptyList()
                : repository.listAmendments(scope, encounterId);

        return new EncounterModels.EncounterDetail(header, prescriptions, amendments);
    }

    /**
     * Creates a new clinical encounter visit (CLN-01 to CLN-05).
     * Only clinicians/doctors/admins may record clinical encounters (CLN-07).
     */
    @Transactional
    public UUID createEncounter(
            ClinicScope scope, UUID patientId, EncounterModels.CreateEncounterRequest request, TenantContext caller) {
        assertClinicianOrAdmin(caller);

        if (request.doctorId() == null) {
            throw new IllegalArgumentException("Doctor ID is required for a clinical encounter");
        }
        if (request.chiefComplaint() == null || request.chiefComplaint().trim().isBlank()) {
            throw new IllegalArgumentException("Chief complaint (الشكوى الرئيسية) is required");
        }
        if (request.diagnosis() == null || request.diagnosis().trim().isBlank()) {
            throw new IllegalArgumentException("Diagnosis (التشخيص) is required");
        }
        validateToothFdi(request.toothFdi());

        String status = request.status() != null ? request.status().toUpperCase() : "COMPLETED";
        if (!VALID_STATUSES.contains(status)) {
            throw new IllegalArgumentException("Invalid encounter status: " + status);
        }

        int duration = request.durationMinutes() != null && request.durationMinutes() > 0
                ? request.durationMinutes()
                : 30;

        UUID encounterId = repository.insertEncounter(
                scope,
                patientId,
                request.doctorId(),
                request.appointmentId(),
                request.treatmentPlanId(),
                request.treatmentPlanItemId(),
                request.chiefComplaint().trim(),
                request.examinationNotes() != null ? request.examinationNotes().trim() : null,
                request.diagnosis().trim(),
                request.clinicalNotes() != null ? request.clinicalNotes().trim() : null,
                request.performedProcedures() != null ? request.performedProcedures().trim() : null,
                request.toothFdi(),
                request.toothLocation() != null ? request.toothLocation().trim() : null,
                status,
                request.visitDate(),
                duration,
                request.bloodPressureSystolic(),
                request.bloodPressureDiastolic(),
                request.pulseRate(),
                request.temperatureC());

        // CLN-05: Update linked treatment plan item if requested
        if (request.treatmentPlanItemId() != null && request.completeLinkedPlanItem()) {
            repository.markTreatmentPlanItemCompleted(scope, request.treatmentPlanItemId());
        }

        // CLN-06: Insert initial prescriptions if provided
        if (request.prescriptions() != null && !request.prescriptions().isEmpty()) {
            for (EncounterModels.CreatePrescriptionRequest rx : request.prescriptions()) {
                if (rx.medicationName() != null && !rx.medicationName().trim().isBlank()) {
                    UUID rxDoctor = rx.doctorId() != null ? rx.doctorId() : request.doctorId();
                    repository.insertPrescription(
                            scope,
                            encounterId,
                            patientId,
                            rxDoctor,
                            rx.medicationName().trim(),
                            rx.dosage() != null ? rx.dosage().trim() : "-",
                            rx.frequency() != null ? rx.frequency().trim() : "-",
                            rx.duration() != null ? rx.duration().trim() : "-",
                            rx.instructions() != null ? rx.instructions().trim() : null);
                }
            }
        }

        return encounterId;
    }

    /**
     * Updates diagnosis and clinical findings with clinician attribution (CLN-02).
     * Immutable note rule: signed encounters CANNOT be edited.
     */
    @Transactional
    public void updateDiagnosis(
            ClinicScope scope, UUID encounterId, EncounterModels.UpdateDiagnosisRequest request, TenantContext caller) {
        assertClinicianOrAdmin(caller);

        EncounterModels.EncounterHeader existing = repository.findEncounter(scope, encounterId, false)
                .orElseThrow(() -> new ResourceNotFoundException("Clinical encounter not found"));

        if (existing.isSigned()) {
            throw new IllegalStateException(
                    "A signed clinical note is immutable and cannot be edited. It can only be amended via an amendment record. (لا يمكن تعديل الملاحظة السريرية الموقعة - يرجى إضافة استدراك ملحق)");
        }

        if (request.diagnosis() == null || request.diagnosis().trim().isBlank()) {
            throw new IllegalArgumentException("Diagnosis cannot be empty");
        }
        validateToothFdi(request.toothFdi());

        boolean ok = repository.updateDiagnosis(
                scope,
                encounterId,
                request.diagnosis().trim(),
                request.clinicalNotes() != null ? request.clinicalNotes().trim() : null,
                request.examinationNotes() != null ? request.examinationNotes().trim() : null,
                request.performedProcedures() != null ? request.performedProcedures().trim() : null,
                request.toothFdi(),
                request.toothLocation() != null ? request.toothLocation().trim() : null,
                request.bloodPressureSystolic(),
                request.bloodPressureDiastolic(),
                request.pulseRate(),
                request.temperatureC(),
                caller.userId());

        if (!ok) {
            throw new ResourceNotFoundException("Clinical encounter not found or cannot be updated");
        }
    }

    /**
     * Digitally signs a clinical encounter note. After signing, the note cannot be edited,
     * and only amendments can be appended (Slice S3).
     */
    @Transactional
    public String signEncounter(ClinicScope scope, UUID encounterId, TenantContext caller) {
        assertClinicianOrAdmin(caller);

        EncounterModels.EncounterHeader existing = repository.findEncounter(scope, encounterId, false)
                .orElseThrow(() -> new ResourceNotFoundException("Clinical encounter not found"));

        if (existing.isSigned()) {
            throw new IllegalStateException("Clinical encounter is already signed");
        }

        String dataToHash = encounterId + ":" + existing.diagnosis() + ":"
                + (existing.clinicalNotes() != null ? existing.clinicalNotes() : "") + ":"
                + (existing.examinationNotes() != null ? existing.examinationNotes() : "") + ":"
                + (existing.performedProcedures() != null ? existing.performedProcedures() : "") + ":"
                + caller.userId() + ":" + LocalDateTime.now();

        String signatureHash = computeSha256(dataToHash);

        boolean signed = repository.signEncounter(scope, encounterId, caller.userId(), signatureHash);
        if (!signed) {
            throw new IllegalStateException("Failed to sign clinical encounter");
        }
        return signatureHash;
    }

    /**
     * Appends an immutable amendment to a signed encounter note.
     */
    @Transactional
    public UUID addAmendment(
            ClinicScope scope, UUID encounterId, EncounterModels.CreateAmendmentRequest request, TenantContext caller) {
        assertClinicianOrAdmin(caller);

        EncounterModels.EncounterHeader existing = repository.findEncounter(scope, encounterId, false)
                .orElseThrow(() -> new ResourceNotFoundException("Clinical encounter not found"));

        if (!existing.isSigned()) {
            throw new IllegalStateException(
                    "Amendments can only be added to a signed clinical note. For unsigned drafts, edit the note directly.");
        }

        if (request.reason() == null || request.reason().trim().isBlank()) {
            throw new IllegalArgumentException("Amendment reason is required (سبب الاستدراك مطلوب)");
        }
        if (request.amendmentContent() == null || request.amendmentContent().trim().isBlank()) {
            throw new IllegalArgumentException("Amendment content is required (نص الاستدراك مطلوب)");
        }

        String dataToHash = encounterId + ":amendment:" + request.reason().trim() + ":"
                + request.amendmentContent().trim() + ":" + caller.userId() + ":" + LocalDateTime.now();
        String signatureHash = computeSha256(dataToHash);

        return repository.insertAmendment(
                scope,
                encounterId,
                caller.userId(),
                request.reason().trim(),
                request.amendmentContent().trim(),
                signatureHash);
    }

    /**
     * Finds patient medical history.
     */
    public Optional<EncounterModels.MedicalHistoryRow> getMedicalHistory(
            ClinicScope scope, UUID patientId, TenantContext caller) {
        return repository.findMedicalHistory(scope, patientId);
    }

    /**
     * Upserts patient medical history.
     */
    @Transactional
    public UUID saveMedicalHistory(
            ClinicScope scope, UUID patientId, EncounterModels.SaveMedicalHistoryRequest request, TenantContext caller) {
        assertClinicianOrAdmin(caller);
        return repository.upsertMedicalHistory(scope, patientId, caller.userId(), request);
    }

    /**
     * Adds a prescription to an existing encounter (CLN-06).
     */
    @Transactional
    public UUID addPrescription(
            ClinicScope scope, UUID encounterId, UUID patientId, EncounterModels.CreatePrescriptionRequest request, TenantContext caller) {
        assertClinicianOrAdmin(caller);

        if (request.medicationName() == null || request.medicationName().trim().isBlank()) {
            throw new IllegalArgumentException("Medication name is required");
        }

        EncounterModels.EncounterHeader encounter = repository.findEncounter(scope, encounterId, false)
                .orElseThrow(() -> new ResourceNotFoundException("Clinical encounter not found"));

        UUID doctorId = request.doctorId() != null ? request.doctorId() : encounter.doctorId();

        return repository.insertPrescription(
                scope,
                encounterId,
                patientId,
                doctorId,
                request.medicationName().trim(),
                request.dosage() != null ? request.dosage().trim() : "-",
                request.frequency() != null ? request.frequency().trim() : "-",
                request.duration() != null ? request.duration().trim() : "-",
                request.instructions() != null ? request.instructions().trim() : null);
    }

    /**
     * Removes a prescription.
     */
    @Transactional
    public void deletePrescription(ClinicScope scope, UUID prescriptionId, TenantContext caller) {
        assertClinicianOrAdmin(caller);
        boolean ok = repository.deletePrescription(scope, prescriptionId);
        if (!ok) {
            throw new ResourceNotFoundException("Prescription not found");
        }
    }

    /**
     * Lists all grouped clinic prescriptions for doctors and clinic admins.
     */
    public List<EncounterModels.ClinicPrescriptionSheet> listClinicPrescriptions(
            ClinicScope scope, TenantContext caller) {
        assertClinicianOrAdmin(caller);
        return repository.listClinicPrescriptionSheets(scope);
    }

    /**
     * Retrieves a single prescription sheet by ID.
     */
    public EncounterModels.ClinicPrescriptionSheet getClinicPrescription(
            ClinicScope scope, UUID encounterId, TenantContext caller) {
        assertClinicianOrAdmin(caller);
        return repository.findPrescriptionSheetById(scope, encounterId)
                .orElseThrow(() -> new ResourceNotFoundException("Prescription sheet not found"));
    }

    /**
     * Issues a new standalone multi-item prescription for a patient.
     */
    @Transactional
    public EncounterModels.ClinicPrescriptionSheet createStandalonePrescription(
            ClinicScope scope,
            EncounterModels.CreateStandalonePrescriptionRequest request,
            TenantContext caller) {
        assertClinicianOrAdmin(caller);

        if (request.patientId() == null) {
            throw new IllegalArgumentException("Patient ID is required");
        }
        if (request.medications() == null || request.medications().isEmpty()) {
            throw new IllegalArgumentException("At least one medication is required");
        }

        UUID doctorId = request.doctorId();
        if (doctorId == null) {
            doctorId = repository.resolveDefaultDoctorId(scope)
                    .orElseThrow(() -> new IllegalArgumentException("Doctor ID is required"));
        }

        String diagnosis = request.diagnosis() != null && !request.diagnosis().trim().isBlank()
                ? request.diagnosis().trim()
                : "وصفة طبية علاجية / Dental Prescription";

        LocalDateTime visitDate = LocalDateTime.now();
        if (request.visitDate() != null && !request.visitDate().isBlank()) {
            try {
                String raw = request.visitDate().trim();
                if (raw.length() == 10) {
                    visitDate = java.time.LocalDate.parse(raw).atTime(LocalDateTime.now().toLocalTime());
                } else {
                    visitDate = LocalDateTime.parse(raw.replace("Z", ""));
                }
            } catch (Exception ignored) {
                visitDate = LocalDateTime.now();
            }
        }

        UUID encounterId = repository.insertEncounter(
                scope,
                request.patientId(),
                doctorId,
                null,
                null,
                null,
                diagnosis,
                null,
                diagnosis,
                request.notes() != null ? request.notes().trim() : null,
                null,
                null,
                null,
                "COMPLETED",
                visitDate,
                15,
                null,
                null,
                null,
                null);

        for (EncounterModels.CreatePrescriptionRequest rx : request.medications()) {
            if (rx.medicationName() != null && !rx.medicationName().trim().isBlank()) {
                repository.insertPrescription(
                        scope,
                        encounterId,
                        request.patientId(),
                        doctorId,
                        rx.medicationName().trim(),
                        rx.dosage() != null && !rx.dosage().isBlank() ? rx.dosage().trim() : "1 tablet",
                        rx.frequency() != null && !rx.frequency().isBlank() ? rx.frequency().trim() : "Twice a day",
                        rx.duration() != null && !rx.duration().isBlank() ? rx.duration().trim() : "5 days",
                        rx.instructions() != null ? rx.instructions().trim() : null);
            }
        }

        return repository.findPrescriptionSheetById(scope, encounterId)
                .orElseThrow(() -> new ResourceNotFoundException("Created prescription sheet not found"));
    }

    /**
     * Deletes a grouped prescription sheet.
     */
    @Transactional
    public void deletePrescriptionSheet(ClinicScope scope, UUID encounterId, TenantContext caller) {
        assertClinicianOrAdmin(caller);
        repository.deletePrescriptionSheet(scope, encounterId);
    }

    /**
     * Lists medication presets for the clinic.
     */
    public List<EncounterModels.ClinicMedicationPreset> listMedicationPresets(
            ClinicScope scope, TenantContext caller) {
        assertClinicianOrAdmin(caller);
        return repository.listMedicationPresets(scope);
    }

    /**
     * Adds a new medication preset for the clinic.
     */
    @Transactional
    public EncounterModels.ClinicMedicationPreset createMedicationPreset(
            ClinicScope scope, EncounterModels.CreateMedicationPresetRequest request, TenantContext caller) {
        assertClinicianOrAdmin(caller);
        return repository.createMedicationPreset(scope, request);
    }

    /**
     * Deletes a medication preset from the clinic catalog.
     */
    @Transactional
    public void deleteMedicationPreset(ClinicScope scope, UUID id, TenantContext caller) {
        assertClinicianOrAdmin(caller);
        repository.deleteMedicationPreset(scope, id);
    }

    /**
     * Lists diagnosis presets for the clinic.
     */
    public List<EncounterModels.ClinicDiagnosisPreset> listDiagnosisPresets(
            ClinicScope scope, TenantContext caller) {
        assertClinicianOrAdmin(caller);
        return repository.listDiagnosisPresets(scope);
    }

    /**
     * Adds a new diagnosis shortcut for the clinic.
     */
    @Transactional
    public EncounterModels.ClinicDiagnosisPreset createDiagnosisPreset(
            ClinicScope scope, EncounterModels.CreateDiagnosisPresetRequest request, TenantContext caller) {
        assertClinicianOrAdmin(caller);
        return repository.createDiagnosisPreset(scope, request);
    }

    /**
     * Deletes a diagnosis shortcut.
     */
    @Transactional
    public void deleteDiagnosisPreset(ClinicScope scope, UUID id, TenantContext caller) {
        assertClinicianOrAdmin(caller);
        repository.deleteDiagnosisPreset(scope, id);
    }

    /**
     * Gets clinic letterhead settings for prescription printing.
     */
    public EncounterModels.ClinicLetterheadSettings getLetterheadSettings(
            ClinicScope scope, TenantContext caller) {
        assertClinicianOrAdmin(caller);
        return repository.getLetterheadSettings(scope);
    }

    /**
     * Updates clinic letterhead settings for prescription printing.
     */
    @Transactional
    public EncounterModels.ClinicLetterheadSettings updateLetterheadSettings(
            ClinicScope scope,
            EncounterModels.UpdateLetterheadRequest request,
            TenantContext caller) {
        assertClinicianOrAdmin(caller);
        return repository.upsertLetterheadSettings(scope, request);
    }

    private static void validateToothFdi(Integer fdi) {
        if (fdi != null && (fdi < 11 || fdi > 85)) {
            throw new IllegalArgumentException("Tooth notation must follow FDI range 11-85: " + fdi);
        }
    }

    private static boolean isReceptionist(TenantContext caller) {
        return caller != null && caller.role() == UserRole.CLINIC_STAFF;
    }

    private static void assertClinicianOrAdmin(TenantContext caller) {
        if (caller != null && caller.role() == UserRole.CLINIC_STAFF) {
            throw new AccessDeniedException("موظفو الاستقبال لا يملكون صلاحية تعديل أو كتابة السجلات السريرية والوصفات (CLN-07, CLN-08)");
        }
    }

    private static String computeSha256(String data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(2 * digest.length);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }
}
