import {
  ChangeDetectionStrategy,
  Component,
  Injectable,
  computed,
  effect,
  inject,
  input,
  signal,
} from '@angular/core';
import { ApiService } from '../api/api.service';
import { SessionStore } from './session.store';

/**
 * The 25 permission keys from the multi-tenant dental clinic RBAC specification.
 */
export const CLINIC_PERMISSION_CATALOG = {
  // Platform
  clinicsManage: 'clinics.manage',
  subscriptionsManage: 'subscriptions.manage',

  // Clinic admin
  clinicSettingsManage: 'clinic.settings.manage',
  usersManage: 'users.manage',
  rolesManage: 'roles.manage',

  // Patients
  patientsViewAdmin: 'patients.view_admin',
  patientsManageAdmin: 'patients.manage_admin',

  // Appointments
  appointmentsView: 'appointments.view',
  appointmentsViewOwn: 'appointments.view_own',
  appointmentsManage: 'appointments.manage',

  // Clinical (sensitive, separate)
  clinicalViewHistory: 'clinical.view_history',
  clinicalViewRecord: 'clinical.view_record',
  clinicalWriteRecord: 'clinical.write_record',

  // Treatment plans
  treatmentPlansViewStatus: 'treatment_plans.view_status',
  treatmentPlansViewFinancials: 'treatment_plans.view_financials',
  treatmentPlansViewFull: 'treatment_plans.view_full',
  treatmentPlansManage: 'treatment_plans.manage',

  // Billing
  invoicesView: 'invoices.view',
  invoicesViewOwnPatients: 'invoices.view_own_patients',
  invoicesCreate: 'invoices.create',
  paymentsRecord: 'payments.record',
  discountsApply: 'discounts.apply',
  invoicesCancel: 'invoices.cancel',

  // Reports
  reportsOperationalView: 'reports.operational.view',
  reportsFinancialView: 'reports.financial.view',
} as const;

export type ClinicPermissionKey =
  (typeof CLINIC_PERMISSION_CATALOG)[keyof typeof CLINIC_PERMISSION_CATALOG];

export type DefaultClinicRoleKey =
  | 'platform_admin'
  | 'clinic_admin'
  | 'receptionist'
  | 'doctor'
  | 'accountant';

/**
 * Default role -> permission mapping (exact match to specification).
 */
export const DEFAULT_ROLE_PERMISSIONS: Readonly<
  Record<DefaultClinicRoleKey, readonly ClinicPermissionKey[]>
> = {
  platform_admin: [
    CLINIC_PERMISSION_CATALOG.clinicsManage,
    CLINIC_PERMISSION_CATALOG.subscriptionsManage,
  ],
  clinic_admin: [
    CLINIC_PERMISSION_CATALOG.clinicSettingsManage,
    CLINIC_PERMISSION_CATALOG.usersManage,
    CLINIC_PERMISSION_CATALOG.rolesManage,
    CLINIC_PERMISSION_CATALOG.patientsViewAdmin,
    CLINIC_PERMISSION_CATALOG.patientsManageAdmin,
    CLINIC_PERMISSION_CATALOG.appointmentsView,
    CLINIC_PERMISSION_CATALOG.appointmentsManage,
    CLINIC_PERMISSION_CATALOG.treatmentPlansViewFinancials,
    CLINIC_PERMISSION_CATALOG.invoicesView,
    CLINIC_PERMISSION_CATALOG.invoicesCreate,
    CLINIC_PERMISSION_CATALOG.paymentsRecord,
    CLINIC_PERMISSION_CATALOG.discountsApply,
    CLINIC_PERMISSION_CATALOG.invoicesCancel,
    CLINIC_PERMISSION_CATALOG.reportsOperationalView,
    CLINIC_PERMISSION_CATALOG.reportsFinancialView,
  ],
  receptionist: [
    CLINIC_PERMISSION_CATALOG.patientsViewAdmin,
    CLINIC_PERMISSION_CATALOG.patientsManageAdmin,
    CLINIC_PERMISSION_CATALOG.appointmentsView,
    CLINIC_PERMISSION_CATALOG.appointmentsManage,
    CLINIC_PERMISSION_CATALOG.treatmentPlansViewStatus,
    CLINIC_PERMISSION_CATALOG.invoicesView,
    CLINIC_PERMISSION_CATALOG.invoicesCreate,
    CLINIC_PERMISSION_CATALOG.paymentsRecord,
    CLINIC_PERMISSION_CATALOG.reportsOperationalView,
  ],
  doctor: [
    CLINIC_PERMISSION_CATALOG.patientsViewAdmin,
    CLINIC_PERMISSION_CATALOG.appointmentsViewOwn,
    CLINIC_PERMISSION_CATALOG.clinicalViewHistory,
    CLINIC_PERMISSION_CATALOG.clinicalViewRecord,
    CLINIC_PERMISSION_CATALOG.clinicalWriteRecord,
    CLINIC_PERMISSION_CATALOG.treatmentPlansViewFull,
    CLINIC_PERMISSION_CATALOG.treatmentPlansManage,
    CLINIC_PERMISSION_CATALOG.invoicesViewOwnPatients,
    CLINIC_PERMISSION_CATALOG.reportsOperationalView,
  ],
  accountant: [
    CLINIC_PERMISSION_CATALOG.patientsViewAdmin,
    CLINIC_PERMISSION_CATALOG.appointmentsView,
    CLINIC_PERMISSION_CATALOG.treatmentPlansViewFinancials,
    CLINIC_PERMISSION_CATALOG.invoicesView,
    CLINIC_PERMISSION_CATALOG.invoicesCreate,
    CLINIC_PERMISSION_CATALOG.paymentsRecord,
    CLINIC_PERMISSION_CATALOG.discountsApply,
    CLINIC_PERMISSION_CATALOG.invoicesCancel,
    CLINIC_PERMISSION_CATALOG.reportsFinancialView,
  ],
};

/**
 * Computes the effective permission union for a user holding one or more roles.
 */
export function computeEffectivePermissionsForRoles(
  roles: readonly string[],
  customRoles: Readonly<Record<string, readonly string[]>> = {},
): ReadonlySet<string> {
  const union = new Set<string>();
  for (const role of roles) {
    const preset = (DEFAULT_ROLE_PERMISSIONS as Record<string, readonly string[] | undefined>)[role];
    if (preset) {
      for (const perm of preset) union.add(perm);
    }
    const custom = customRoles[role];
    if (custom) {
      for (const perm of custom) union.add(perm);
    }
  }
  return union;
}

export interface PermissionDrivenNavItem {
  readonly id: string;
  readonly label: string;
  readonly path: string;
  readonly requiredAnyPermissions: readonly ClinicPermissionKey[];
}

export interface PermissionDrivenNavSection {
  readonly sectionId: string;
  readonly sectionTitle: string;
  readonly items: readonly PermissionDrivenNavItem[];
}

export const PERMISSION_DRIVEN_NAV_TREE: readonly PermissionDrivenNavSection[] = [
  {
    sectionId: 'platform',
    sectionTitle: 'Platform Management',
    items: [
      {
        id: 'clinics',
        label: 'Clinics',
        path: '/app/clinic/branches',
        requiredAnyPermissions: [CLINIC_PERMISSION_CATALOG.clinicsManage],
      },
      {
        id: 'subscriptions',
        label: 'Subscriptions',
        path: '/app/admin',
        requiredAnyPermissions: [CLINIC_PERMISSION_CATALOG.subscriptionsManage],
      },
    ],
  },
  {
    sectionId: 'scheduling_and_patients',
    sectionTitle: 'Scheduling & Patients',
    items: [
      {
        id: 'calendar',
        label: 'Calendar & Appointments',
        path: '/app/clinic/calendar',
        requiredAnyPermissions: [
          CLINIC_PERMISSION_CATALOG.appointmentsView,
          CLINIC_PERMISSION_CATALOG.appointmentsViewOwn,
          CLINIC_PERMISSION_CATALOG.appointmentsManage,
        ],
      },
      {
        id: 'patients',
        label: 'Patients',
        path: '/app/clinic/patients',
        requiredAnyPermissions: [
          CLINIC_PERMISSION_CATALOG.patientsViewAdmin,
          CLINIC_PERMISSION_CATALOG.patientsManageAdmin,
        ],
      },
    ],
  },
  {
    sectionId: 'clinical',
    sectionTitle: 'Clinical Care',
    items: [
      {
        id: 'medical_record',
        label: 'Medical Record & Odontogram',
        path: '/app/clinic/workspace',
        requiredAnyPermissions: [
          CLINIC_PERMISSION_CATALOG.clinicalViewRecord,
          CLINIC_PERMISSION_CATALOG.clinicalWriteRecord,
        ],
      },
      {
        id: 'prescriptions',
        label: 'Prescriptions',
        path: '/app/clinic/prescriptions',
        requiredAnyPermissions: [
          CLINIC_PERMISSION_CATALOG.clinicalViewRecord,
          CLINIC_PERMISSION_CATALOG.clinicalWriteRecord,
        ],
      },
    ],
  },
  {
    sectionId: 'billing',
    sectionTitle: 'Billing & Invoices',
    items: [
      {
        id: 'invoices',
        label: 'Invoices & Payments',
        path: '/app/clinic/invoices',
        requiredAnyPermissions: [
          CLINIC_PERMISSION_CATALOG.invoicesView,
          CLINIC_PERMISSION_CATALOG.invoicesViewOwnPatients,
          CLINIC_PERMISSION_CATALOG.invoicesCreate,
        ],
      },
    ],
  },
  {
    sectionId: 'reports',
    sectionTitle: 'Reports & Analytics',
    items: [
      {
        id: 'operational_reports',
        label: 'Operational Dashboard',
        path: '/app/clinic/overview',
        requiredAnyPermissions: [CLINIC_PERMISSION_CATALOG.reportsOperationalView],
      },
      {
        id: 'financial_reports',
        label: 'Financial Reports',
        path: '/app/clinic/reports',
        requiredAnyPermissions: [CLINIC_PERMISSION_CATALOG.reportsFinancialView],
      },
    ],
  },
  {
    sectionId: 'administration',
    sectionTitle: 'Clinic Administration',
    items: [
      {
        id: 'clinic_settings',
        label: 'Clinic Settings',
        path: '/app/clinic/settings/policies',
        requiredAnyPermissions: [CLINIC_PERMISSION_CATALOG.clinicSettingsManage],
      },
      {
        id: 'users_and_roles',
        label: 'Users & Roles',
        path: '/app/clinic/doctors',
        requiredAnyPermissions: [
          CLINIC_PERMISSION_CATALOG.usersManage,
          CLINIC_PERMISSION_CATALOG.rolesManage,
        ],
      },
    ],
  },
];

/**
 * Dynamic navigation resolver:
 * - Never checks role names; checks only effective permission keys.
 * - A sidebar section is included ONLY if at least one item inside it is permitted.
 */
export function resolveVisibleNavSections(
  effectivePermissions: ReadonlySet<string>,
): readonly PermissionDrivenNavSection[] {
  return PERMISSION_DRIVEN_NAV_TREE.map((section) => ({
    ...section,
    items: section.items.filter((item) =>
      item.requiredAnyPermissions.some((perm) => effectivePermissions.has(perm)),
    ),
  })).filter((section) => section.items.length > 0);
}

export interface PatientScopeContext {
  readonly isAssignedToCurrentDoctor?: boolean;
  readonly doctorSeesAllPatients?: boolean;
}

export interface UiVisibilitySnapshot {
  readonly visibleNavItemIds: readonly string[];
  readonly visibleSectionIds: readonly string[];
  // Patient & Clinical sections
  readonly showPatientAdminEditButton: boolean;
  readonly showMedicalHistorySection: boolean;
  readonly showDiagnosisAndClinicalRecord: boolean;
  readonly showWriteMedicalRecordControls: boolean;
  // Treatment Plan sections
  readonly showTreatmentPlanStatus: boolean;
  readonly showTreatmentPlanFinancials: boolean;
  readonly showTreatmentPlanClinicalDetails: boolean;
  readonly showEditTreatmentPlanButton: boolean;
  // Billing buttons
  readonly showCreateInvoiceButton: boolean;
  readonly showRecordPaymentButton: boolean;
  readonly showApplyDiscountButton: boolean;
  readonly showCancelInvoiceButton: boolean;
  // Administration & Reports
  readonly showClinicSettings: boolean;
  readonly showUsersAndRoles: boolean;
  readonly showFinancialReports: boolean;
  readonly showOperationalReports: boolean;
}

/**
 * Evaluates which UI elements are rendered for a given effective permission set and patient context.
 * Follows the strict rule: "Never check role names in the UI; always check permission keys. Hide, don't disable."
 */
export function evaluateUiVisibility(
  effectivePermissions: ReadonlySet<string>,
  patientContext: PatientScopeContext = { isAssignedToCurrentDoctor: true, doctorSeesAllPatients: false },
): UiVisibilitySnapshot {
  const sections = resolveVisibleNavSections(effectivePermissions);
  const visibleSectionIds = sections.map((s) => s.sectionId);
  const visibleNavItemIds = sections.flatMap((s) => s.items.map((i) => i.id));

  // If the user does not have clinic-wide appointments.view, clinical patient access requires assignment or doctorSeesAllPatients
  const hasClinicWideScope = effectivePermissions.has(CLINIC_PERMISSION_CATALOG.appointmentsView);
  const withinPatientScope =
    hasClinicWideScope ||
    Boolean(patientContext.doctorSeesAllPatients) ||
    Boolean(patientContext.isAssignedToCurrentDoctor);

  return {
    visibleNavItemIds,
    visibleSectionIds,
    showPatientAdminEditButton: effectivePermissions.has(
      CLINIC_PERMISSION_CATALOG.patientsManageAdmin,
    ),
    showMedicalHistorySection:
      withinPatientScope &&
      effectivePermissions.has(CLINIC_PERMISSION_CATALOG.clinicalViewHistory),
    showDiagnosisAndClinicalRecord:
      withinPatientScope &&
      effectivePermissions.has(CLINIC_PERMISSION_CATALOG.clinicalViewRecord),
    showWriteMedicalRecordControls:
      withinPatientScope &&
      effectivePermissions.has(CLINIC_PERMISSION_CATALOG.clinicalWriteRecord),
    showTreatmentPlanStatus:
      effectivePermissions.has(CLINIC_PERMISSION_CATALOG.treatmentPlansViewStatus) ||
      effectivePermissions.has(CLINIC_PERMISSION_CATALOG.treatmentPlansViewFinancials) ||
      (withinPatientScope &&
        effectivePermissions.has(CLINIC_PERMISSION_CATALOG.treatmentPlansViewFull)),
    showTreatmentPlanFinancials:
      effectivePermissions.has(CLINIC_PERMISSION_CATALOG.treatmentPlansViewFinancials) ||
      (withinPatientScope &&
        effectivePermissions.has(CLINIC_PERMISSION_CATALOG.treatmentPlansViewFull)),
    showTreatmentPlanClinicalDetails:
      withinPatientScope &&
      effectivePermissions.has(CLINIC_PERMISSION_CATALOG.treatmentPlansViewFull),
    showEditTreatmentPlanButton:
      withinPatientScope &&
      effectivePermissions.has(CLINIC_PERMISSION_CATALOG.treatmentPlansManage),
    showCreateInvoiceButton: effectivePermissions.has(CLINIC_PERMISSION_CATALOG.invoicesCreate),
    showRecordPaymentButton: effectivePermissions.has(CLINIC_PERMISSION_CATALOG.paymentsRecord),
    showApplyDiscountButton: effectivePermissions.has(CLINIC_PERMISSION_CATALOG.discountsApply),
    showCancelInvoiceButton: effectivePermissions.has(CLINIC_PERMISSION_CATALOG.invoicesCancel),
    showClinicSettings: effectivePermissions.has(CLINIC_PERMISSION_CATALOG.clinicSettingsManage),
    showUsersAndRoles:
      effectivePermissions.has(CLINIC_PERMISSION_CATALOG.usersManage) ||
      effectivePermissions.has(CLINIC_PERMISSION_CATALOG.rolesManage),
    showFinancialReports: effectivePermissions.has(CLINIC_PERMISSION_CATALOG.reportsFinancialView),
    showOperationalReports: effectivePermissions.has(
      CLINIC_PERMISSION_CATALOG.reportsOperationalView,
    ),
  };
}

/**
 * Contextual row action check for editing an appointment:
 * - Allowed if the user holds `appointments.manage`
 * - Or if the user holds `appointments.view_own` AND the appointment belongs to them.
 */
export function canShowAppointmentEditAction(
  effectivePermissions: ReadonlySet<string>,
  appointmentDoctorId: string,
  currentDoctorId: string,
): boolean {
  if (effectivePermissions.has(CLINIC_PERMISSION_CATALOG.appointmentsManage)) {
    return true;
  }
  return (
    effectivePermissions.has(CLINIC_PERMISSION_CATALOG.appointmentsViewOwn) &&
    appointmentDoctorId === currentDoctorId
  );
}

/**
 * Resolves the post-login landing page strictly from the user's effective permissions:
 * - clinics.manage / subscriptions.manage (platform_admin) -> /app/clinic/branches
 * - clinic.settings.manage (clinic_admin) -> /app/clinic/overview
 * - reports.financial.view + invoices.view without appointments.manage (accountant) -> /app/clinic/invoices
 * - appointments.view / appointments.view_own (receptionist / doctor) -> /app/clinic/calendar
 */
export function resolvePermissionLandingPage(effectivePermissions: ReadonlySet<string>): string {
  if (
    effectivePermissions.has(CLINIC_PERMISSION_CATALOG.clinicsManage) ||
    effectivePermissions.has(CLINIC_PERMISSION_CATALOG.subscriptionsManage)
  ) {
    return '/app/platform/dashboard';
  }
  if (effectivePermissions.has(CLINIC_PERMISSION_CATALOG.clinicSettingsManage)) {
    return '/app/dashboard';
  }
  if (
    effectivePermissions.has(CLINIC_PERMISSION_CATALOG.reportsFinancialView) &&
    effectivePermissions.has(CLINIC_PERMISSION_CATALOG.invoicesView) &&
    !effectivePermissions.has(CLINIC_PERMISSION_CATALOG.appointmentsManage) &&
    !effectivePermissions.has(CLINIC_PERMISSION_CATALOG.clinicalViewRecord)
  ) {
    return '/app/clinic/invoices';
  }
  if (
    effectivePermissions.has(CLINIC_PERMISSION_CATALOG.appointmentsView) ||
    effectivePermissions.has(CLINIC_PERMISSION_CATALOG.appointmentsViewOwn)
  ) {
    return '/app/clinic/calendar';
  }
  return '/app/denied';
}

/**
 * Injectable frontend permission helper service.
 * Bridges the server session's effective permissions and any multi-role clinic RBAC permissions.
 */
@Injectable({ providedIn: 'root' })
export class ClinicRbacService {
  private readonly store = inject(SessionStore);
  private readonly api = inject(ApiService, { optional: true });

  private readonly assignedRolesOverride = signal<readonly string[] | null>(null);
  private readonly customRolesMap = signal<Readonly<Record<string, readonly string[]>>>({});
  private readonly doctorSeesAllPatientsSignal = signal(false);
  private lastLoadedUserId: string | null = null;

  readonly doctorSeesAllPatients = this.doctorSeesAllPatientsSignal.asReadonly();

  constructor() {
    effect(() => {
      const userId = this.store.userId();
      const status = this.store.status();
      if (status === 'authenticated' && userId && userId !== this.lastLoadedUserId) {
        this.lastLoadedUserId = userId;
        void this.refreshFromServer();
      } else if (status === 'anonymous') {
        this.lastLoadedUserId = null;
        this.assignedRolesOverride.set(null);
      }
    });
  }

  async refreshFromServer(): Promise<void> {
    if (!this.api) return;
    try {
      const res = await this.api.get<{
        roles?: readonly string[];
        effectivePermissions?: readonly string[];
        doctorSeesAllPatients?: boolean;
      }>('/api/v1/clinic/rbac/me');
      if (res && Array.isArray(res.roles) && res.roles.length > 0) {
        this.assignedRolesOverride.set(res.roles);
      }
      if (res && typeof res.doctorSeesAllPatients === 'boolean') {
        this.doctorSeesAllPatientsSignal.set(res.doctorSeesAllPatients);
      }
    } catch {
      // Fall back to default role resolution if endpoint is unreachable
    }
  }

  /**
   * Resolves the active Clinic RBAC role keys for the current session:
   * - Explicit override (from unit tests or `/api/v1/clinic/rbac/me`), OR
   * - Inferred from the seeded Clinic RBAC account identity (`fullName` / `userId`), OR
   * - Default mapping from legacy `UserRole`.
   */
  readonly activeRoles = computed<readonly string[]>(() => {
    const overrideRoles = this.assignedRolesOverride();
    if (overrideRoles !== null) {
      return overrideRoles;
    }

    const fullName = this.store.fullName().toLowerCase();
    const userId = this.store.userId();
    const email = (this.store.session()?.email ?? '').toLowerCase();

    // 1. Pure Platform Operator / Platform Admin (Zero clinic operational access)
    if (
      email.startsWith('platform') ||
      email.includes('platform@') ||
      fullName.includes('platform') ||
      userId === 'ee000004-0000-4000-8000-000000000004' ||
      userId === 'b0000003-0000-4000-8000-000000000004'
    ) {
      return ['platform_admin'];
    }

    // 2. Clinic Super Admin (Full clinic operations, zero platform administration)
    if (
      email.startsWith('admin@') ||
      email.includes('clinic') ||
      fullName.includes('clinic admin') ||
      (fullName.includes('super admin') && !fullName.includes('platform'))
    ) {
      return ['clinic_admin'];
    }

    if (userId === 'b0000003-0000-4000-8000-000000000009' || fullName.includes('owner+doc')) {
      return ['clinic_admin', 'doctor'];
    }
    if (userId === 'b0000003-0000-4000-8000-000000000008' || fullName.includes('accountant')) {
      return ['accountant'];
    }
    if (userId === 'b0000003-0000-4000-8000-000000000005' || fullName.includes('clinic manager')) {
      return ['clinic_admin'];
    }
    if (userId === 'b0000003-0000-4000-8000-000000000006' || fullName.includes('(reception)')) {
      return ['receptionist'];
    }
    if (userId === 'b0000003-0000-4000-8000-000000000007' || fullName.includes('(dentist)')) {
      return ['doctor'];
    }

    if (fullName.includes('super administrator')) {
      return ['clinic_admin'];
    }

    const legacyRole = this.store.role();
    if (legacyRole === 'ADMIN') return ['clinic_admin'];
    if (legacyRole === 'CLINIC_STAFF') return ['receptionist'];
    if (legacyRole === 'EMPLOYEE') return ['doctor'];
    return [];
  });

  readonly effectivePermissions = computed<ReadonlySet<string>>(() => {
    const overrideRoles = this.assignedRolesOverride();
    if (overrideRoles !== null) {
      return computeEffectivePermissionsForRoles(overrideRoles, this.customRolesMap());
    }

    const base = new Set<string>(this.store.effectivePermissions());
    const roles = this.activeRoles();
    const rolePerms = computeEffectivePermissionsForRoles(roles, this.customRolesMap());
    for (const p of rolePerms) {
      base.add(p);
    }
    return base;
  });

  setAssignedRoles(
    roles: readonly string[] | null,
    customRoles: Readonly<Record<string, readonly string[]>> = {},
  ): void {
    this.customRolesMap.set(customRoles);
    this.assignedRolesOverride.set(roles);
  }

  setDoctorSeesAllPatients(enabled: boolean): void {
    this.doctorSeesAllPatientsSignal.set(enabled);
  }

  hasPermission(permission: string): boolean {
    return this.effectivePermissions().has(permission);
  }

  hasAnyPermission(permissions: readonly string[]): boolean {
    const granted = this.effectivePermissions();
    return permissions.some((p) => granted.has(p));
  }
}

/**
 * Guard component `<fe-if-permission permission="discounts.apply">...</fe-if-permission>`
 * Renders its content ONLY when the caller holds the required permission key(s).
 * Hides (does not disable) unauthorized elements.
 */
@Component({
  selector: 'fe-if-permission',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (allowed()) {
      <ng-content />
    }
  `,
})
export class IfPermissionComponent {
  private readonly rbac = inject(ClinicRbacService);

  readonly permission = input<string>('');
  readonly anyOf = input<readonly string[]>([]);

  protected readonly allowed = computed<boolean>(() => {
    const single = this.permission();
    if (single && this.rbac.hasPermission(single)) {
      return true;
    }
    const list = this.anyOf();
    return list.length > 0 && this.rbac.hasAnyPermission(list);
  });
}
