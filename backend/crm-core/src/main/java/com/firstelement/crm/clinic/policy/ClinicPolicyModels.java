package com.firstelement.crm.clinic.policy;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Domain models for clinic settings and policies (USR-09, BIL-07, APT-02).
 */
public final class ClinicPolicyModels {

    private ClinicPolicyModels() {
    }

    /**
     * Active clinic policy configuration.
     */
    public record ClinicPolicyRow(
            UUID organizationId,
            UUID projectId,
            boolean doctorSeesAllPatients,
            int defaultSlotDurationMinutes,
            boolean allowDoubleBooking,
            String primaryCurrency,
            String toothNotationSystem,
            LocalDateTime updatedAt,
            UUID updatedBy,
            String updatedByName) {
    }

    /**
     * Update request payload for clinic policies.
     */
    public record UpdatePolicyRequest(
            Boolean doctorSeesAllPatients,
            Integer defaultSlotDurationMinutes,
            Boolean allowDoubleBooking,
            String primaryCurrency,
            String toothNotationSystem) {
    }
}
