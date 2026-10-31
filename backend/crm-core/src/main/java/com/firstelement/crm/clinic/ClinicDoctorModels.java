package com.firstelement.crm.clinic;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The doctor profile the management screen reads and writes.
 *
 * <h2>Why doctors stay profiles, not sign-in accounts</h2>
 *
 * <p>A doctor row is already the bookable clinician the roster, schedules and
 * appointments point at. This screen adds the contact and profile fields the
 * Create Doctor drawer records, but it deliberately does not add a password:
 * there is no {@code DOCTOR} role in this runtime, and inventing one here would
 * hand a clinician account a front-desk grant or require a new authorization
 * surface. The front-desk screen is the one that creates {@code CLINIC_STAFF}
 * accounts, because a receptionist genuinely signs in.
 *
 * <h2>Why the input record has no identifiers</h2>
 *
 * <p>The id, organization and project are derived from the caller's scope by
 * the service. Keeping them out of the input is the structural version of that
 * rule: there is no field to bind even if a client sends one.
 *
 * <h2>Partial updates</h2>
 *
 * <p>Every input field is nullable. On PATCH a null means "unchanged", which
 * is what lets the status switch send only {@code isActive}. A qualification
 * list that is null means "leave the existing rows alone"; an empty list means
 * "remove every row". Those two cases are different and are kept different.
 */
public final class ClinicDoctorModels {

    private ClinicDoctorModels() {
    }

    /** One qualification row in the drawer. */
    public record Qualification(
        UUID id,
        String degree,
        String university,
        Integer graduationYear) {
    }

    /** The writable qualification fields; the id belongs to the server. */
    public record QualificationInput(
        String degree,
        String university,
        Integer graduationYear) {
    }

    /**
     * A doctor profile as the list and the detail drawer read it.
     *
     * <p>{@code firstName} and {@code lastName} are backfilled from the
     * canonical {@code name} for rows that predate this screen, so the edit
     * form never opens with two blank fields over a name the list displays.
     */
    public record DoctorProfile(
        UUID id,
        UUID organizationId,
        UUID projectId,
        String firstName,
        String lastName,
        String name,
        String email,
        String phone,
        String gender,
        String imageUrl,
        String aboutSelf,
        String expert,
        String specialty,
        Double commission,
        UUID clinicId,
        String clinicName,
        UUID serviceId,
        String serviceName,
        Integer experienceYears,
        String country,
        String state,
        String city,
        String postalCode,
        Double latitude,
        Double longitude,
        String facebookUrl,
        String instagramUrl,
        String twitterUrl,
        String dribbbleUrl,
        String signatureUrl,
        String verificationStatus,
        boolean isActive,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<Qualification> qualifications) {

        /** The same profile with its qualification rows attached. */
        public DoctorProfile withQualifications(List<Qualification> rows) {
            return new DoctorProfile(id, organizationId, projectId, firstName, lastName, name,
                email, phone, gender, imageUrl, aboutSelf, expert, specialty, commission,
                clinicId, clinicName, serviceId, serviceName, experienceYears, country, state,
                city, postalCode, latitude, longitude, facebookUrl, instagramUrl, twitterUrl,
                dribbbleUrl, signatureUrl, verificationStatus, isActive, createdAt, updatedAt,
                rows);
        }
    }

    /**
     * The Create/Edit Doctor payload.
     *
     * <p>{@code expert} is the field the drawer labels "Expert". The service
     * mirrors it into the legacy non-null {@code specialty} column so the
     * existing roster and analytics reads keep working unchanged. There is no
     * password field: see the class comment.
     */
    public record DoctorProfileInput(
        String firstName,
        String lastName,
        String email,
        String phone,
        String gender,
        String imageUrl,
        String aboutSelf,
        String expert,
        Double commission,
        UUID clinicId,
        UUID serviceId,
        Integer experienceYears,
        String country,
        String state,
        String city,
        String postalCode,
        Double latitude,
        Double longitude,
        String facebookUrl,
        String instagramUrl,
        String twitterUrl,
        String dribbbleUrl,
        String signatureUrl,
        String verificationStatus,
        Boolean isActive,
        List<QualificationInput> qualifications) {
    }
}
