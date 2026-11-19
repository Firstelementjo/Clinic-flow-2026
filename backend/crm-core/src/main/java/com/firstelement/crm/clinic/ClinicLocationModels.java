package com.firstelement.crm.clinic;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The clinic master data the administration screens write: physical locations and
 * service-catalogue entries.
 *
 * <h2>Why locations are not projects</h2>
 *
 * <p>A project is the tenant-scoped workspace a caller signs into; a location is a
 * place a patient can walk into. One project may own several locations (a group
 * practice), and a location is the row the Clinics screen lists. Modelling a
 * location as a project would make selecting a clinic in the UI a tenant switch,
 * which is a much larger permission change than adding a name and an address.
 *
 * <h2>Why the input records are separate from the row records</h2>
 *
 * <p>A create request must not be able to name its own {@code id},
 * {@code organizationId} or {@code projectId}. Those are derived from the caller's
 * scope by the service, never taken from the body. Keeping the input record free of
 * them is the structural version of that rule: there is no field to bind even if a
 * client sends one.
 */
public final class ClinicLocationModels {

    private ClinicLocationModels() {
    }

    /** One physical clinic, as the list and the drawer read it. */
    public record ClinicLocation(
            UUID id,
            UUID organizationId,
            UUID projectId,
            String name,
            UUID frontDeskOfficerId,
            String frontDeskOfficerName,
            String contactNumber,
            String description,
            String addressLine,
            String country,
            String state,
            String city,
            String postalCode,
            int slotMinutes,
            String brandImageUrl,
            boolean isActive,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
    }

    /**
     * The fields the Create/Edit Clinic drawer owns.
     *
     * <p>Every field is nullable on the wire because the drawer is also used to
     * edit a partial record; the service applies the same defaults on create that
     * the database does. {@code name} is the one field it refuses to default.
     *
     * <p>{@code clearFrontDeskOfficer} exists because a null {@code frontDeskOfficerId}
     * already means "leave the current officer alone". The drawer sends it when the
     * operator chooses Unassigned, so clearing is explicit rather than a side effect
     * of a status-only PATCH.
     */
    public record ClinicLocationInput(
            String name,
            UUID frontDeskOfficerId,
            Boolean clearFrontDeskOfficer,
            String contactNumber,
            String description,
            String addressLine,
            String country,
            String state,
            String city,
            String postalCode,
            Integer slotMinutes,
            String brandImageUrl,
            Boolean isActive,
            Integer initialTreatmentRooms
    ) {
        public ClinicLocationInput(
                String name,
                UUID frontDeskOfficerId,
                Boolean clearFrontDeskOfficer,
                String contactNumber,
                String description,
                String addressLine,
                String country,
                String state,
                String city,
                String postalCode,
                Integer slotMinutes,
                String brandImageUrl,
                Boolean isActive) {
            this(name, frontDeskOfficerId, clearFrontDeskOfficer, contactNumber, description,
                 addressLine, country, state, city, postalCode, slotMinutes, brandImageUrl,
                 isActive, null);
        }
    }

    /**
     * The fields the Create/Edit Service drawer owns.
     *
     * <p>{@code specialty} is the stored category name; the screen calls it
     * "Category". Keeping the existing column means appointments, invoices and
     * treatment plans continue to reference the same catalogue row.
     */
    public record ProcedureInput(
            String name,
            String specialty,
            Integer durationMinutes,
            Integer bufferMinutes,
            Double price,
            Boolean isActive,
            String productCode,
            String subCategory,
            UUID clinicId,
            Integer timeSlotMinutes,
            String type,
            String description,
            Double discount,
            String imageUrl,
            String systemService,
            Boolean advancedPayment
    ) {
    }
}
