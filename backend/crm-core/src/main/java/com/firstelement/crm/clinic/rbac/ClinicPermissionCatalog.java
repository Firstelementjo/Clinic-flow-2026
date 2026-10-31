package com.firstelement.crm.clinic.rbac;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Multi-tenant Dental Clinic Permission Catalog & Default Role Mapping.
 *
 * <p>Implements the exact roles, permission keys, and default mappings specified in
 * the RBAC specification:
 * <ul>
 *   <li>{@code platform_admin}</li>
 *   <li>{@code clinic_admin}</li>
 *   <li>{@code receptionist}</li>
 *   <li>{@code doctor}</li>
 *   <li>{@code accountant}</li>
 * </ul>
 */
public final class ClinicPermissionCatalog {

    private ClinicPermissionCatalog() {
    }

    // ------------------------------------------------------------------
    // Role Keys
    // ------------------------------------------------------------------
    public static final String ROLE_PLATFORM_ADMIN = "platform_admin";
    public static final String ROLE_CLINIC_ADMIN   = "clinic_admin";
    public static final String ROLE_RECEPTIONIST   = "receptionist";
    public static final String ROLE_DOCTOR         = "doctor";
    public static final String ROLE_ACCOUNTANT     = "accountant";

    // ------------------------------------------------------------------
    // 1. Platform Permissions
    // ------------------------------------------------------------------
    public static final String CLINICS_MANAGE        = "clinics.manage";
    public static final String SUBSCRIPTIONS_MANAGE  = "subscriptions.manage";

    // ------------------------------------------------------------------
    // 2. Clinic Admin Permissions
    // ------------------------------------------------------------------
    public static final String CLINIC_SETTINGS_MANAGE = "clinic.settings.manage";
    public static final String USERS_MANAGE           = "users.manage";
    public static final String ROLES_MANAGE           = "roles.manage";

    // ------------------------------------------------------------------
    // 3. Patients Permissions
    // ------------------------------------------------------------------
    public static final String PATIENTS_VIEW_ADMIN   = "patients.view_admin";
    public static final String PATIENTS_MANAGE_ADMIN = "patients.manage_admin";

    // ------------------------------------------------------------------
    // 4. Appointments Permissions
    // ------------------------------------------------------------------
    public static final String APPOINTMENTS_VIEW     = "appointments.view";
    public static final String APPOINTMENTS_VIEW_OWN = "appointments.view_own";
    public static final String APPOINTMENTS_MANAGE   = "appointments.manage";

    // ------------------------------------------------------------------
    // 5. Clinical (Sensitive, Separate) Permissions
    // ------------------------------------------------------------------
    public static final String CLINICAL_VIEW_HISTORY = "clinical.view_history";
    public static final String CLINICAL_VIEW_RECORD  = "clinical.view_record";
    public static final String CLINICAL_WRITE_RECORD = "clinical.write_record";

    // ------------------------------------------------------------------
    // 6. Treatment Plans Permissions
    // ------------------------------------------------------------------
    public static final String TREATMENT_PLANS_VIEW_STATUS     = "treatment_plans.view_status";
    public static final String TREATMENT_PLANS_VIEW_FINANCIALS = "treatment_plans.view_financials";
    public static final String TREATMENT_PLANS_VIEW_FULL       = "treatment_plans.view_full";
    public static final String TREATMENT_PLANS_MANAGE          = "treatment_plans.manage";

    // ------------------------------------------------------------------
    // 7. Billing Permissions
    // ------------------------------------------------------------------
    public static final String INVOICES_VIEW              = "invoices.view";
    public static final String INVOICES_VIEW_OWN_PATIENTS = "invoices.view_own_patients";
    public static final String INVOICES_CREATE            = "invoices.create";
    public static final String PAYMENTS_RECORD            = "payments.record";
    public static final String DISCOUNTS_APPLY            = "discounts.apply";
    public static final String INVOICES_CANCEL            = "invoices.cancel";

    // ------------------------------------------------------------------
    // 8. Reports Permissions
    // ------------------------------------------------------------------
    public static final String REPORTS_OPERATIONAL_VIEW = "reports.operational.view";
    public static final String REPORTS_FINANCIAL_VIEW   = "reports.financial.view";

    public static final Set<String> ALL_PERMISSION_KEYS = Collections.unmodifiableSet(new LinkedHashSet<>(List.of(
            CLINICS_MANAGE,
            SUBSCRIPTIONS_MANAGE,
            CLINIC_SETTINGS_MANAGE,
            USERS_MANAGE,
            ROLES_MANAGE,
            PATIENTS_VIEW_ADMIN,
            PATIENTS_MANAGE_ADMIN,
            APPOINTMENTS_VIEW,
            APPOINTMENTS_VIEW_OWN,
            APPOINTMENTS_MANAGE,
            CLINICAL_VIEW_HISTORY,
            CLINICAL_VIEW_RECORD,
            CLINICAL_WRITE_RECORD,
            TREATMENT_PLANS_VIEW_STATUS,
            TREATMENT_PLANS_VIEW_FINANCIALS,
            TREATMENT_PLANS_VIEW_FULL,
            TREATMENT_PLANS_MANAGE,
            INVOICES_VIEW,
            INVOICES_VIEW_OWN_PATIENTS,
            INVOICES_CREATE,
            PAYMENTS_RECORD,
            DISCOUNTS_APPLY,
            INVOICES_CANCEL,
            REPORTS_OPERATIONAL_VIEW,
            REPORTS_FINANCIAL_VIEW
    )));

    /** Sensitive clinical permission keys that clinic_admin does NOT hold by default. */
    public static final Set<String> SENSITIVE_CLINICAL_PERMISSIONS = Set.of(
            CLINICAL_VIEW_HISTORY,
            CLINICAL_VIEW_RECORD,
            CLINICAL_WRITE_RECORD,
            TREATMENT_PLANS_VIEW_FULL,
            TREATMENT_PLANS_MANAGE
    );

    // ------------------------------------------------------------------
    // Default Role -> Permission Mapping
    // ------------------------------------------------------------------

    public static final Set<String> PLATFORM_ADMIN_PERMISSIONS = Collections.unmodifiableSet(new LinkedHashSet<>(List.of(
            CLINICS_MANAGE,
            SUBSCRIPTIONS_MANAGE
    )));

    /**
     * clinic_admin: NO medical record or full treatment plan clinical access by default.
     */
    public static final Set<String> CLINIC_ADMIN_PERMISSIONS = Collections.unmodifiableSet(new LinkedHashSet<>(List.of(
            CLINIC_SETTINGS_MANAGE,
            USERS_MANAGE,
            ROLES_MANAGE,
            PATIENTS_VIEW_ADMIN,
            PATIENTS_MANAGE_ADMIN,
            APPOINTMENTS_VIEW,
            APPOINTMENTS_MANAGE,
            TREATMENT_PLANS_VIEW_FINANCIALS,
            INVOICES_VIEW,
            INVOICES_CREATE,
            PAYMENTS_RECORD,
            DISCOUNTS_APPLY,
            INVOICES_CANCEL,
            REPORTS_OPERATIONAL_VIEW,
            REPORTS_FINANCIAL_VIEW
    )));

    /**
     * receptionist: NO medical record access, NO discounts.apply, NO invoices.cancel,
     * treatment_plans.view_status only.
     */
    public static final Set<String> RECEPTIONIST_PERMISSIONS = Collections.unmodifiableSet(new LinkedHashSet<>(List.of(
            PATIENTS_VIEW_ADMIN,
            PATIENTS_MANAGE_ADMIN,
            APPOINTMENTS_VIEW,
            APPOINTMENTS_MANAGE,
            TREATMENT_PLANS_VIEW_STATUS,
            INVOICES_VIEW,
            INVOICES_CREATE,
            PAYMENTS_RECORD,
            REPORTS_OPERATIONAL_VIEW
    )));

    /**
     * doctor: own appointments, read-only admin patient data, full clinical + treatment plans,
     * own-patient invoices (read-only), operational reports (own data).
     */
    public static final Set<String> DOCTOR_PERMISSIONS = Collections.unmodifiableSet(new LinkedHashSet<>(List.of(
            PATIENTS_VIEW_ADMIN,
            APPOINTMENTS_VIEW_OWN,
            CLINICAL_VIEW_HISTORY,
            CLINICAL_VIEW_RECORD,
            CLINICAL_WRITE_RECORD,
            TREATMENT_PLANS_VIEW_FULL,
            TREATMENT_PLANS_MANAGE,
            INVOICES_VIEW_OWN_PATIENTS,
            REPORTS_OPERATIONAL_VIEW
    )));

    /**
     * accountant: billing-relevant patient fields, read-only appointments, treatment plan financials,
     * full invoices/payments/discounts/cancel, financial reports. NO clinical access, NO clinic settings.
     */
    public static final Set<String> ACCOUNTANT_PERMISSIONS = Collections.unmodifiableSet(new LinkedHashSet<>(List.of(
            PATIENTS_VIEW_ADMIN,
            APPOINTMENTS_VIEW,
            TREATMENT_PLANS_VIEW_FINANCIALS,
            INVOICES_VIEW,
            INVOICES_CREATE,
            PAYMENTS_RECORD,
            DISCOUNTS_APPLY,
            INVOICES_CANCEL,
            REPORTS_FINANCIAL_VIEW
    )));

    public static final Map<String, Set<String>> DEFAULT_CLINIC_ROLES;
    static {
        Map<String, Set<String>> map = new LinkedHashMap<>();
        map.put(ROLE_CLINIC_ADMIN, CLINIC_ADMIN_PERMISSIONS);
        map.put(ROLE_RECEPTIONIST, RECEPTIONIST_PERMISSIONS);
        map.put(ROLE_DOCTOR, DOCTOR_PERMISSIONS);
        map.put(ROLE_ACCOUNTANT, ACCOUNTANT_PERMISSIONS);
        DEFAULT_CLINIC_ROLES = Collections.unmodifiableMap(map);
    }

    public static Set<String> defaultPermissionsForRole(String roleKey) {
        if (roleKey == null) {
            return Set.of();
        }
        return switch (roleKey.toLowerCase()) {
            case ROLE_PLATFORM_ADMIN -> PLATFORM_ADMIN_PERMISSIONS;
            case ROLE_CLINIC_ADMIN -> CLINIC_ADMIN_PERMISSIONS;
            case ROLE_RECEPTIONIST -> RECEPTIONIST_PERMISSIONS;
            case ROLE_DOCTOR -> DOCTOR_PERMISSIONS;
            case ROLE_ACCOUNTANT -> ACCOUNTANT_PERMISSIONS;
            default -> Set.of();
        };
    }

    public static boolean isValidPermissionKey(String key) {
        return key != null && ALL_PERMISSION_KEYS.contains(key);
    }

    public static boolean isSensitiveClinicalPermission(String key) {
        return key != null && SENSITIVE_CLINICAL_PERMISSIONS.contains(key);
    }

    /**
     * Permission-driven landing route after login:
     * - platform_admin (clinics.manage) -> /app/clinic/branches
     * - clinic_admin (clinic.settings.manage) -> /app/clinic/overview
     * - accountant (discounts.apply + reports.financial.view without appointments.manage) -> /app/clinic/invoices
     * - receptionist / doctor (appointments.view or appointments.view_own) -> /app/clinic/calendar
     */
    public static String resolveLandingPath(Set<String> effectivePermissions) {
        if (effectivePermissions == null || effectivePermissions.isEmpty()) {
            return "/app/denied";
        }
        if (effectivePermissions.contains(CLINICS_MANAGE) || effectivePermissions.contains(SUBSCRIPTIONS_MANAGE)) {
            return "/app/clinic/branches";
        }
        if (effectivePermissions.contains(CLINIC_SETTINGS_MANAGE)) {
            return "/app/clinic/overview";
        }
        if (effectivePermissions.contains(REPORTS_FINANCIAL_VIEW)
                && effectivePermissions.contains(INVOICES_VIEW)
                && !effectivePermissions.contains(APPOINTMENTS_MANAGE)
                && !effectivePermissions.contains(CLINICAL_VIEW_RECORD)) {
            return "/app/clinic/invoices";
        }
        if (effectivePermissions.contains(APPOINTMENTS_VIEW) || effectivePermissions.contains(APPOINTMENTS_VIEW_OWN)) {
            return "/app/clinic/calendar";
        }
        if (effectivePermissions.contains(INVOICES_VIEW)) {
            return "/app/clinic/invoices";
        }
        return "/app/dashboard";
    }
}
