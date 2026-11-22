package com.firstelement.crm.clinic.intake;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Domain models and DTOs for the Patient Intake Portal and 9-step Patient History persistence.
 */
public final class PatientIntakeModels {

    private PatientIntakeModels() {
    }

    public record ClinicIntakeQrInfo(
            UUID projectId,
            UUID organizationId,
            String clinicName,
            String clinicSlug,
            String intakePortalKey,
            boolean enabled,
            String defaultLocale) {
    }

    public record GeneratedIntakeCode(
            String code,
            String formattedCode,
            Instant expiresAt,
            int expiresInSeconds,
            UUID patientId,
            String patientName) {
    }

    public record ActiveIntakeCodeStatus(
            boolean hasActiveCode,
            String codeHint,
            Instant expiresAt,
            long remainingSeconds,
            String latestIntakeStatus,
            UUID latestIntakeId,
            Instant lastSubmittedAt) {
    }

    public record SchemaOption(
            String key,
            String labelEn,
            String labelAr) {
    }

    public record PublicIntakeSchema(
            List<SchemaOption> medicalConditions,
            List<SchemaOption> medicationTypes,
            List<SchemaOption> allergyTypes,
            List<SchemaOption> dentalSymptoms) {
    }

    public record PublicClinicConfig(
            String clinicName,
            String clinicKey,
            String defaultLocale,
            boolean active,
            PublicIntakeSchema schema) {
    }

    public record VerifyCodeRequest(String code) {
    }

    public record VerifyCodeResponse(
            boolean valid,
            String clinicName,
            String patientFirstName,
            String intakeSessionToken,
            Instant sessionExpiresAt) {
    }

    public record IntakeDemographics(
            String fullName,
            String dob,
            String gender,
            String occupation,
            String address,
            String phone,
            String email,
            String emergencyName,
            String emergencyPhone) {
    }

    public record IntakeMedicalHistory(
            String underTreatment,
            String treatmentSpecify,
            String hospitalizedLastYear,
            String hospitalizedReason,
            List<String> conditions,
            String otherCondition,
            String pregnantOrBreastfeeding,
            List<String> medications,
            String otherMedications,
            List<String> allergies,
            String otherAllergies) {
    }

    public record IntakeDentalHistory(
            String lastDentalVisitDate,
            String lastVisitReason,
            List<String> symptoms,
            String pastProcedures,
            Integer anxietyLevel) {
    }

    public record IntakeChiefComplaint(
            String primaryComplaint,
            Integer painScale,
            String duration,
            List<String> triggers) {
    }

    public record SubmitIntakeRequest(
            String intakeSessionToken,
            IntakeDemographics demographics,
            IntakeMedicalHistory medicalHistory,
            IntakeDentalHistory dentalHistory,
            IntakeChiefComplaint chiefComplaint) {
    }

    public record SubmitIntakeResult(
            boolean submitted,
            UUID intakeId,
            String status) {
    }

    public record PatientHistoryIntakeRecord(
            UUID id,
            UUID patientId,
            UUID encounterId,
            String status,
            String source,
            boolean isComplete,
            String date,
            String name,
            Map<String, Object> demographics,
            Map<String, Object> medicalHistory,
            Map<String, Object> dentalHistory,
            Map<String, Object> chiefComplaint,
            Map<String, Object> clinicalExam,
            Map<String, Object> radiographs,
            Map<String, Object> diagnosisAndPlan,
            Map<String, Object> dentalChart,
            Map<String, Object> informedConsent,
            Instant submittedAt,
            Instant approvedAt,
            UUID approvedByUserId) {
    }

    public record SavePatientHistoryIntakeRequest(
            UUID id,
            UUID encounterId,
            String status,
            Boolean isComplete,
            Boolean approve,
            String date,
            String name,
            Map<String, Object> demographics,
            Map<String, Object> medicalHistory,
            Map<String, Object> dentalHistory,
            Map<String, Object> chiefComplaint,
            Map<String, Object> clinicalExam,
            Map<String, Object> radiographs,
            Map<String, Object> diagnosisAndPlan,
            Map<String, Object> dentalChart,
            Map<String, Object> informedConsent) {
    }
}
