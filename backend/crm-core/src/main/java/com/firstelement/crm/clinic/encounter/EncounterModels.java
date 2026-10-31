package com.firstelement.crm.clinic.encounter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Domain models and DTOs for dental clinical encounters, medical history, vitals,
 * immutable digital signing, and clinical note amendments (Slice S3 / CLN-01 to CLN-09).
 */
public final class EncounterModels {

    private EncounterModels() {
    }

    /**
     * Header row for a clinical encounter visit.
     */
    public record EncounterHeader(
            UUID id,
            UUID organizationId,
            UUID projectId,
            UUID patientId,
            String patientName,
            String patientNo,
            UUID appointmentId,
            UUID doctorId,
            String doctorName,
            UUID treatmentPlanId,
            String treatmentPlanTitle,
            UUID treatmentPlanItemId,
            String treatmentPlanProcedureName,
            String chiefComplaint,
            String examinationNotes,
            String diagnosis,
            LocalDateTime diagnosisUpdatedAt,
            UUID diagnosisUpdatedBy,
            String diagnosisUpdatedByName,
            String clinicalNotes,
            String performedProcedures,
            Integer toothFdi,
            String toothLocation,
            String status,
            LocalDateTime visitDate,
            int durationMinutes,
            int prescriptionCount,
            boolean isMedicalDataMasked,
            LocalDateTime signedAt,
            UUID signedByUserId,
            String signedByUserName,
            String signatureHash,
            Integer bloodPressureSystolic,
            Integer bloodPressureDiastolic,
            Integer pulseRate,
            BigDecimal temperatureC,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {

        public boolean isSigned() {
            return signedAt != null;
        }
    }

    /**
     * Prescription item issued during an encounter (CLN-06).
     */
    public record PrescriptionRow(
            UUID id,
            UUID organizationId,
            UUID projectId,
            UUID encounterId,
            UUID patientId,
            UUID doctorId,
            String doctorName,
            String medicationName,
            String dosage,
            String frequency,
            String duration,
            String instructions,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }

    /**
     * Immutable signed note amendment audit entry.
     */
    public record ClinicalNoteAmendmentRow(
            UUID id,
            UUID organizationId,
            UUID projectId,
            UUID encounterId,
            UUID amendedByUserId,
            String amendedByUserName,
            LocalDateTime amendedAt,
            String reason,
            String amendmentContent,
            String signatureHash,
            LocalDateTime createdAt) {
    }

    /**
     * Full detail of an encounter including issued prescriptions and note amendments.
     */
    public record EncounterDetail(
            EncounterHeader encounter,
            List<PrescriptionRow> prescriptions,
            List<ClinicalNoteAmendmentRow> amendments) {
    }

    /**
     * Comprehensive patient medical history record.
     */
    public record MedicalHistoryRow(
            UUID id,
            UUID organizationId,
            UUID projectId,
            UUID patientId,
            String allergies,
            String conditions,
            String currentMedications,
            boolean bleedingDisorder,
            boolean cardiacCondition,
            boolean diabetes,
            String notes,
            UUID recordedByUserId,
            String recordedByUserName,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }

    /**
     * Request payload to save/update patient medical history.
     */
    public record SaveMedicalHistoryRequest(
            String allergies,
            String conditions,
            String currentMedications,
            Boolean bleedingDisorder,
            Boolean cardiacCondition,
            Boolean diabetes,
            String notes) {
    }

    /**
     * Request payload to record a new clinical encounter visit.
     */
    public record CreateEncounterRequest(
            UUID doctorId,
            UUID appointmentId,
            UUID treatmentPlanId,
            UUID treatmentPlanItemId,
            String chiefComplaint,
            String examinationNotes,
            String diagnosis,
            String clinicalNotes,
            String performedProcedures,
            Integer toothFdi,
            String toothLocation,
            String status,
            LocalDateTime visitDate,
            Integer durationMinutes,
            String projectId,
            boolean completeLinkedPlanItem,
            Integer bloodPressureSystolic,
            Integer bloodPressureDiastolic,
            Integer pulseRate,
            BigDecimal temperatureC,
            List<CreatePrescriptionRequest> prescriptions) {
    }

    /**
     * Request payload to update diagnosis by the attending doctor (CLN-02).
     */
    public record UpdateDiagnosisRequest(
            String diagnosis,
            String clinicalNotes,
            String examinationNotes,
            String performedProcedures,
            Integer toothFdi,
            String toothLocation,
            Integer bloodPressureSystolic,
            Integer bloodPressureDiastolic,
            Integer pulseRate,
            BigDecimal temperatureC) {
    }

    /**
     * Request payload to issue a prescription (CLN-06).
     */
    public record CreatePrescriptionRequest(
            String medicationName,
            String dosage,
            String frequency,
            String duration,
            String instructions,
            UUID doctorId) {
    }

    /**
     * Request payload to create an amendment on a signed note.
     */
    public record CreateAmendmentRequest(
            String reason,
            String amendmentContent) {
    }

    /**
     * Grouped clinic prescription summary and full medications list for the Prescriptions module.
     */
    public record ClinicPrescriptionSheet(
            UUID id,
            String rxNumber,
            UUID organizationId,
            UUID projectId,
            UUID patientId,
            String patientName,
            String patientNo,
            String patientPhone,
            UUID doctorId,
            String doctorName,
            String doctorSpecialty,
            String diagnosis,
            String notes,
            LocalDateTime visitDate,
            LocalDateTime createdAt,
            int medicationCount,
            List<PrescriptionRow> medications) {
    }

    /**
     * Request payload to issue a standalone multi-item prescription from the Prescriptions screen.
     */
    public record CreateStandalonePrescriptionRequest(
            UUID patientId,
            UUID doctorId,
            String diagnosis,
            String notes,
            String visitDate,
            String projectId,
            List<CreatePrescriptionRequest> medications) {
    }

    /**
     * Per-clinic customizable letterhead and print template settings for prescriptions.
     */
    public record ClinicLetterheadSettings(
            UUID organizationId,
            UUID projectId,
            String clinicName,
            String clinicSubtitle,
            String logoUrl,
            String address,
            String phone,
            String whatsapp,
            String email,
            String licenseNumber,
            String defaultInstructions,
            String footerText,
            String signatureLabel,
            String paperSize,
            boolean prePrintedPaper,
            LocalDateTime updatedAt) {
    }

    /**
     * Request payload to update clinic letterhead settings.
     */
    public record UpdateLetterheadRequest(
            String clinicName,
            String clinicSubtitle,
            String logoUrl,
            String address,
            String phone,
            String whatsapp,
            String email,
            String licenseNumber,
            String defaultInstructions,
            String footerText,
            String signatureLabel,
            String paperSize,
            Boolean prePrintedPaper,
            String projectId) {
    }

    /**
     * Saved medication preset / catalog entry for the clinic.
     */
    public record ClinicMedicationPreset(
            UUID id,
            UUID organizationId,
            UUID projectId,
            String medicationName,
            String dosage,
            String frequency,
            String duration,
            String instructions,
            String category,
            boolean isFavorite,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }

    /**
     * Request payload to create or update a medication preset.
     */
    public record CreateMedicationPresetRequest(
            String medicationName,
            String dosage,
            String frequency,
            String duration,
            String instructions,
            String category,
            Boolean isFavorite,
            String projectId) {
    }

    /**
     * Saved quick diagnosis preset for the clinic.
     */
    public record ClinicDiagnosisPreset(
            UUID id,
            UUID organizationId,
            UUID projectId,
            String name,
            String code,
            LocalDateTime createdAt) {
    }

    /**
     * Request payload to add a quick diagnosis preset.
     */
    public record CreateDiagnosisPresetRequest(
            String name,
            String code,
            String projectId) {
    }
}

