package com.firstelement.crm.clinic;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The front-desk officer, as the Users screen reads and writes it.
 *
 * <h2>Why this is a user, not a profile table</h2>
 *
 * <p>A front-desk officer signs in. The account therefore lives in
 * {@code users} with {@code role = CLINIC_STAFF}, and project reach lives in
 * {@code project_memberships}. A separate profile table would have to invent a
 * second identity, and the login path would not know which one to trust.
 *
 * <h2>Where the clinic assignment lives</h2>
 *
 * <p>Phase 38 already put {@code frontDeskOfficerId} on
 * {@code clinic_locations}. This screen reads that column and writes it through
 * one operation that first clears any previous assignment for the officer, so
 * the list's "Clinic" column has one answer rather than a fan-out.
 *
 * <h2>Delete is not a row delete</h2>
 *
 * <p>The delete action removes the project membership, clears the clinic
 * assignment and deactivates the account when it has no other memberships. A
 * hard delete would destroy audit history and any session attribution that
 * points at the user; deactivation keeps the row and removes the reach.
 */
public final class FrontDeskModels {

    private FrontDeskModels() {
    }

    /** One front-desk officer as the list and drawer read it. */
    public record FrontDeskOfficer(
        UUID userId,
        UUID organizationId,
        UUID projectId,
        String email,
        String fullName,
        String firstName,
        String lastName,
        String phone,
        String gender,
        LocalDate dateOfBirth,
        String profileImageUrl,
        String addressLine,
        String country,
        String state,
        String city,
        String postalCode,
        UUID clinicId,
        String clinicName,
        String verificationStatus,
        boolean isActive,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String role,
        boolean isAccountant) {

        public FrontDeskOfficer(
                UUID userId,
                UUID organizationId,
                UUID projectId,
                String email,
                String fullName,
                String firstName,
                String lastName,
                String phone,
                String gender,
                LocalDate dateOfBirth,
                String profileImageUrl,
                String addressLine,
                String country,
                String state,
                String city,
                String postalCode,
                UUID clinicId,
                String clinicName,
                String verificationStatus,
                boolean isActive,
                LocalDateTime createdAt,
                LocalDateTime updatedAt) {
            this(userId, organizationId, projectId, email, fullName, firstName, lastName,
                    phone, gender, dateOfBirth, profileImageUrl, addressLine, country, state,
                    city, postalCode, clinicId, clinicName, verificationStatus, isActive,
                    createdAt, updatedAt, "receptionist", false);
        }
    }

    /**
     * The Create/Edit Front Desk Officer payload.
     *
     * <p>{@code password} is required on create and optional on edit. A blank
     * password on edit means "keep the current one", which is why the service
     * normalizes it to null before hashing.
     */
    public record FrontDeskOfficerInput(
        String firstName,
        String lastName,
        String email,
        String gender,
        String phone,
        String password,
        LocalDate dateOfBirth,
        String profileImageUrl,
        String addressLine,
        String country,
        String state,
        String city,
        String postalCode,
        UUID clinicId,
        String verificationStatus,
        Boolean isActive,
        String role,
        Boolean isAccountant) {

        public FrontDeskOfficerInput(
                String firstName,
                String lastName,
                String email,
                String gender,
                String phone,
                String password,
                LocalDate dateOfBirth,
                String profileImageUrl,
                String addressLine,
                String country,
                String state,
                String city,
                String postalCode,
                UUID clinicId,
                String verificationStatus,
                Boolean isActive) {
            this(firstName, lastName, email, gender, phone, password, dateOfBirth,
                    profileImageUrl, addressLine, country, state, city, postalCode,
                    clinicId, verificationStatus, isActive, null, null);
        }
    }
}
