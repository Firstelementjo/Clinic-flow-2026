package com.firstelement.crm.clinic.policy;

import com.firstelement.crm.clinic.ClinicScope;
import com.firstelement.security.TenantContext;
import com.firstelement.security.UserRole;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for managing clinic settings and operational policies (USR-09, BIL-07).
 */
@Service
public class ClinicPolicyService {

    private static final Set<String> ALLOWED_CURRENCIES = Set.of("JOD", "USD");
    private static final Set<String> ALLOWED_NOTATIONS = Set.of("FDI", "UNIVERSAL");

    private final ClinicPolicyRepository repository;

    public ClinicPolicyService(ClinicPolicyRepository repository) {
        this.repository = repository;
    }

    /**
     * Reads current clinic policies.
     */
    public ClinicPolicyModels.ClinicPolicyRow getPolicy(ClinicScope scope) {
        return repository.findPolicy(scope);
    }

    /**
     * Updates clinic policies (Admin or Clinic Manager only, USR-01/04).
     */
    @Transactional
    public ClinicPolicyModels.ClinicPolicyRow updatePolicy(
            ClinicScope scope,
            ClinicPolicyModels.UpdatePolicyRequest request,
            TenantContext caller) {
        assertManagerOrAdmin(caller, "Only clinic administrators can modify clinic policies");

        ClinicPolicyModels.ClinicPolicyRow current = repository.findPolicy(scope);

        boolean doctorSeesAll = request.doctorSeesAllPatients() != null
                ? request.doctorSeesAllPatients()
                : current.doctorSeesAllPatients();

        int defaultDuration = request.defaultSlotDurationMinutes() != null && request.defaultSlotDurationMinutes() > 0
                ? request.defaultSlotDurationMinutes()
                : current.defaultSlotDurationMinutes();

        boolean allowDoubleBooking = request.allowDoubleBooking() != null
                ? request.allowDoubleBooking()
                : current.allowDoubleBooking();

        String currency = request.primaryCurrency() != null
                ? request.primaryCurrency().toUpperCase()
                : current.primaryCurrency();
        if (!ALLOWED_CURRENCIES.contains(currency)) {
            throw new IllegalArgumentException("Unsupported currency: " + currency + ". Allowed: JOD, USD.");
        }

        String notation = request.toothNotationSystem() != null
                ? request.toothNotationSystem().toUpperCase()
                : current.toothNotationSystem();
        if (!ALLOWED_NOTATIONS.contains(notation)) {
            throw new IllegalArgumentException("Unsupported tooth notation: " + notation + ". Allowed: FDI, UNIVERSAL.");
        }

        repository.upsertPolicy(
                scope,
                doctorSeesAll,
                defaultDuration,
                allowDoubleBooking,
                currency,
                notation,
                caller.userId()
        );

        return repository.findPolicy(scope);
    }

    private static void assertManagerOrAdmin(TenantContext caller, String message) {
        if (caller == null) {
            throw new AccessDeniedException(message);
        }
        if (caller.role() != UserRole.ADMIN) {
            throw new AccessDeniedException(message);
        }
    }
}
