package com.firstelement.crm.clinic.patient;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/** Patient records and the read models the clinic surface needs. */
public final class PatientModels {

    private PatientModels() {
    }

    /**
     * A patient as the clinic list and detail views see it.
     *
     * <p>Deliberately excludes {@code customerId}'s contact details and every retention column.
     * The contact link is an identifier, not a licence to join the CRM record here; a caller that
     * wants the customer's details goes through the customer surface, which is the boundary DEC-015
     * exists to keep.
     */
    public record PatientRow(
            UUID id,
            String patientNo,
            String displayName,
            String givenName,
            String familyName,
            String gender,
            LocalDate dateOfBirth,
            String primaryPhone,
            String primaryEmail,
            String cnic,
            String profileImageUrl,
            String addressLine,
            String country,
            String state,
            String city,
            String postalCode,
            boolean isActive,
            String locale,
            UUID customerId,
            LocalDateTime createdAt,
            boolean deleted) {
    }

    /**
     * Everything the Create New Patient drawer writes.
     *
     * <p>{@code portalPasswordHash} is already a bcrypt hash by the time it reaches the
     * repository: the service hashes, and no layer below it ever sees the plain password.
     * A null hash means "no portal password", which is different from an empty one.
     */
    public record CreatePatientCommand(
            String patientNo,
            String displayName,
            String givenName,
            String familyName,
            String gender,
            LocalDate dateOfBirth,
            String phone,
            String email,
            String cnic,
            String profileImageUrl,
            String addressLine,
            String country,
            String state,
            String city,
            String postalCode,
            boolean isActive,
            String locale,
            UUID customerId,
            String portalPasswordHash) {
    }

    /**
     * A possible duplicate, with the score that produced it.
     *
     * <p>A candidate is <em>evidence offered to a human</em>, never a decision. See
     * {@code PatientService#duplicateCandidates} for why nothing merges automatically.
     */
    public record DuplicateCandidate(
            UUID patientId,
            String patientNo,
            String displayName,
            int score,
            String matchedOn) {
    }

    /**
     * What a duplicate search matched on, and how strongly.
     *
     * <p>The weighting is deliberately coarse and explainable: a phone number is an identity, a
     * shared surname is not. A scoring function nobody can explain is one nobody will trust with a
     * patient list.
     */
    public record Match(String field, int score, String detail) {
    }

    public record RelationshipRow(
            UUID id,
            UUID organizationId,
            UUID projectId,
            UUID patientId,
            UUID relatedPatientId,
            String relatedPatientName,
            String relationshipType,
            String customRelationship,
            String relatedPersonName,
            String relatedPersonPhone,
            boolean isGuarantor,
            boolean isEmergencyContact,
            String notes,
            java.time.Instant createdAt) {
    }

    public record CreateRelationshipRequest(
            UUID relatedPatientId,
            String relationshipType,
            String customRelationship,
            String relatedPersonName,
            String relatedPersonPhone,
            Boolean isGuarantor,
            Boolean isEmergencyContact,
            String notes) {
    }

    public record ConsentRow(
            UUID id,
            UUID organizationId,
            UUID projectId,
            UUID patientId,
            String consentType,
            String status,
            java.time.Instant grantedAt,
            java.time.Instant revokedAt,
            String revocationReason,
            java.time.Instant expiresAt,
            String signedBy,
            String signerRelationship,
            String witnessName,
            String documentReference,
            String notes,
            java.time.Instant createdAt) {
    }

    public record CreateConsentRequest(
            String consentType,
            java.time.Instant expiresAt,
            String signedBy,
            String signerRelationship,
            String witnessName,
            String documentReference,
            String notes) {
    }

    public record RevokeConsentRequest(String revocationReason) {
    }

    public record DisclosureLogRow(
            UUID id,
            UUID organizationId,
            UUID projectId,
            UUID patientId,
            String disclosedTo,
            String purpose,
            String recordsDisclosed,
            java.time.Instant disclosedAt,
            UUID disclosedBy,
            String disclosedByName,
            java.time.Instant createdAt) {
    }

    public record LogDisclosureRequest(
            String disclosedTo,
            String purpose,
            String recordsDisclosed) {
    }

    public record MergePatientsRequest(
            UUID sourcePatientId,
            UUID targetPatientId,
            String reason) {
    }

    public record MergePatientsResult(
            UUID mergeId,
            UUID sourcePatientId,
            UUID targetPatientId,
            String status,
            int appointmentsMoved,
            int encountersMoved,
            int plansMoved,
            int invoicesMoved) {
    }

    public record ArchivePatientRequest(String reason) {
    }
}