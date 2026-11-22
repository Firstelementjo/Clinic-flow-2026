/**
 * ToothLogic Dental Practice Navigation Model.
 *
 * Each NavEntry carries an optional `roles` list. When present, only accounts
 * whose session role appears in that list will see the entry in the sidebar.
 * When absent, all authenticated roles can see it (subject to `permission`).
 *
 * Role values match the server's UserRole enum exactly:
 *   ADMIN        → Clinic Owner / Practice Manager
 *   EMPLOYEE     → Treating Dentist
 *   CLINIC_STAFF → Receptionist / Front-Desk
 *   AI_LEAD      → AI Governance Lead  (uses ADMIN role + system-health:read)
 *   PLATFORM     → Website Super-Admin (uses ADMIN role + WRITE_PLATFORM_CLINIC)
 *
 * Because AI_LEAD and PLATFORM share the ADMIN role enum value, we distinguish
 * them via their permission set rather than role alone — the shell applies both
 * the `roles` filter AND the `permission` check, so a plain ADMIN without
 * system-health:read never sees the Platform console entries.
 */

export interface NavEntry {
  readonly path: string;
  readonly labelKey: string;
  readonly icon: NavIcon;
  readonly permission?: string;
  /** When set, only these UserRole values may see this entry in the sidebar. */
  readonly roles?: readonly string[];
  readonly group: NavGroup;
  readonly placeholder?: boolean;
  readonly unavailableReasonKey?: string;
  readonly queryTab?: string;
}

export type NavGroup = 'main' | 'clinicCenters' | 'users' | 'finance' | 'system' | 'platform';

export type NavIcon =
  | 'dashboard'
  | 'inbox'
  | 'people'
  | 'team'
  | 'knowledge'
  | 'channels'
  | 'admin'
  | 'finance'
  | 'exports'
  | 'clinic'
  | 'settings'
  | 'audit';

export const NAV_GROUPS: readonly { id: NavGroup; labelKey: string }[] = [
  { id: 'main', labelKey: '' },
  { id: 'clinicCenters', labelKey: 'shell.nav.groupClinicCenters' },
  { id: 'users', labelKey: 'shell.nav.groupUsers' },
  { id: 'finance', labelKey: 'shell.nav.groupFinance' },
  { id: 'system', labelKey: 'shell.nav.groupSystem' },
  { id: 'platform', labelKey: 'shell.nav.groupPlatform' },
];

// ─────────────────────────────────────────────────────────────────────────────
// Role constants (mirrors UserRole.java)
// ─────────────────────────────────────────────────────────────────────────────
const ADMIN: readonly string[] = ['ADMIN'];
const EMPLOYEE: readonly string[] = ['ADMIN', 'EMPLOYEE'];
/**
 * The clinical front desk: the treating dentist and the receptionist work the same
 * screens with the same grants (`RolePermissions` gives both `doctor:read`,
 * `procedure:read` and `appointment:read`), so each screen is ONE entry carrying
 * both roles.
 *
 * It used to be one entry per role. That put two links to one screen in the
 * sidebar, and — because the shell tracks the `@for` by `path|labelKey` — Angular
 * dropped one of them from the DOM: a link that is in the model and absent from
 * the page, with no error to say so.
 */
const FRONT_DESK: readonly string[] = ['ADMIN', 'EMPLOYEE', 'CLINIC_STAFF'];
/** The practice manager and the receptionist share the billing and schedule screens. */
const MANAGER_AND_DESK: readonly string[] = ['ADMIN', 'CLINIC_STAFF'];

export const NAV_ENTRIES: readonly NavEntry[] = [

  // ══════════════════════════════════════════════
  //  ADMIN — Clinic Owner / Practice Manager
  // ══════════════════════════════════════════════
  {
    // The dashboard. Role-agnostic, because the route is: it is guarded on
    // `doctor:read`, which the practice manager, the treating dentist and the
    // receptionist all hold. The entry carries that same grant so the sidebar
    // offers the screen to exactly the principals the server will answer.
    //
    // It used to say `conversation:read`, which the front desk does not hold, so
    // the receptionist — a role this dashboard is built for — never saw the link.
    path: '/app/dashboard',
    labelKey: 'shell.nav.dashboard',
    icon: 'dashboard',
    permission: 'doctor:read',
    group: 'main',
  },
  {
    path: '/app/clinic/branches',
    labelKey: 'shell.nav.clinics',
    icon: 'clinic',
    permission: 'doctor:read',
    roles: ADMIN,
    group: 'clinicCenters',
  },
  {
    path: '/app/clinic/services-and-resources',
    labelKey: 'shell.nav.services',
    icon: 'settings',
    permission: 'procedure:read',
    roles: ADMIN,
    group: 'clinicCenters',
  },
  {
    path: '/app/clinic/doctors',
    labelKey: 'shell.nav.doctors',
    icon: 'team',
    permission: 'doctor:read-management',
    roles: ADMIN,
    group: 'users',
  },
  {
    path: '/app/clinic/front-desk',
    labelKey: 'shell.nav.frontDesk',
    icon: 'people',
    permission: 'front-desk:read',
    roles: ADMIN,
    group: 'users',
  },
  {
    // Billing. The route is guarded on `appointment:read` (app.routes.ts) and so
    // is this entry — the sidebar offers a screen to exactly the principals whose
    // request the server will answer. It used to be two entries, one for the
    // manager and one for the receptionist, each with its own grant.
    path: '/app/clinic/invoices',
    labelKey: 'shell.nav.billingRecord',
    icon: 'finance',
    permission: 'appointment:read',
    roles: MANAGER_AND_DESK,
    group: 'finance',
  },
  {
    path: '/app/clinic/commission',
    labelKey: 'shell.nav.doctorCommission',
    icon: 'finance',
    permission: 'report:manage',
    roles: ADMIN,
    group: 'finance',
  },
  {
    path: '/app/clinic/payouts',
    labelKey: 'shell.nav.doctorPayout',
    icon: 'exports',
    permission: 'report:manage',
    roles: ADMIN,
    group: 'finance',
  },
  {
    // Revenue and commission. `ClinicReportsController` guards every one of its
    // routes on `appointment:read`, and the route declares the same grant, so the
    // entry declares it too: a screen the sidebar offers is a screen the server
    // will answer.
    path: '/app/clinic/reports',
    labelKey: 'shell.nav.revenueBreakdown',
    icon: 'dashboard',
    permission: 'appointment:read',
    roles: ADMIN,
    group: 'finance',
  },
  {
    path: '/app/clinic/appointments-overview',
    labelKey: 'shell.nav.appointmentOverview',
    icon: 'dashboard',
    permission: 'appointment:read',
    roles: ADMIN,
    group: 'finance',
  },
  {
    path: '/app/clinic/overview',
    labelKey: 'shell.nav.clinicOverview',
    icon: 'clinic',
    permission: 'appointment:read',
    roles: ADMIN,
    group: 'finance',
  },
  {
    path: '/app/settings',
    labelKey: 'shell.nav.systemSetting',
    icon: 'settings',
    permission: 'conversation:read',
    roles: ADMIN,
    group: 'system',
  },

  // ══════════════════════════════════════════════
  //  CLINICAL FRONT DESK — Treating Dentist (EMPLOYEE) + Receptionist (CLINIC_STAFF)
  // ══════════════════════════════════════════════
  //
  //  These two roles work the same screens with the same grants, so each screen
  //  is one entry carrying both roles rather than one entry per role.
  {
    path: '/app/clinic/calendar',
    labelKey: 'shell.nav.clinicCalendar',
    icon: 'clinic',
    permission: 'appointment:read',
    roles: FRONT_DESK,
    group: 'main',
  },
  {
    // The bookable list, not the grid. It is a separate destination because the
    // two screens answer different questions: the calendar answers "when is the
    // doctor free", the list answers "what was booked, for how much, and is it
    // paid". Both stay linked; neither pretends to be the other.
    path: '/app/clinic/appointments',
    labelKey: 'shell.nav.appointments',
    icon: 'exports',
    permission: 'appointment:read',
    roles: FRONT_DESK,
    group: 'main',
  },
  {
    path: '/app/clinic/patients',
    labelKey: 'shell.nav.patients',
    icon: 'people',
    permission: 'appointment:read',
    roles: FRONT_DESK,
    group: 'main',
  },
  {
    path: '/app/clinic/prescriptions',
    labelKey: 'shell.nav.prescriptions',
    icon: 'knowledge',
    permission: 'doctor:read',
    roles: EMPLOYEE,
    group: 'main',
  },
  {
    // The sessions and the aligner pipeline are the dentist's alone. The clinic
    // staff role does hold `doctor:read` — the routes are reachable — but a
    // receptionist has no place in a lab case, and the role filter is what says
    // so.
    path: '/app/clinic/lab',
    labelKey: 'shell.nav.lab',
    icon: 'knowledge',
    permission: 'doctor:read',
    roles: EMPLOYEE,
    group: 'main',
  },
  // Clear Aligner Lab hidden for now per user request
  // {
  //   path: '/app/clinic/aligners',
  //   labelKey: 'shell.nav.aligners',
  //   icon: 'clinic',
  //   permission: 'doctor:read',
  //   roles: EMPLOYEE,
  //   group: 'main',
  // },
  {
    path: '/app/clinic/sessions',
    labelKey: 'shell.nav.doctorSession',
    icon: 'dashboard',
    permission: 'doctor:read',
    roles: FRONT_DESK,
    group: 'clinicCenters',
  },
  {
    path: '/app/clinic/feedback',
    labelKey: 'shell.nav.feedback',
    icon: 'knowledge',
    permission: 'appointment:read',
    roles: FRONT_DESK,
    group: 'clinicCenters',
  },
  {
    path: '/app/clinic/ai-assistant',
    labelKey: 'shell.nav.clinicAi',
    icon: 'knowledge',
    permission: 'appointment:read',
    roles: ADMIN,
    group: 'clinicCenters',
  },

  // ─────────────────────────────────────────────────────────────────────────────
  // Platform Operator Console (Dedicated SaaS Administration)
  // ─────────────────────────────────────────────────────────────────────────────
  {
    path: '/app/platform/dashboard',
    labelKey: 'shell.nav.platformDashboard',
    icon: 'dashboard',
    group: 'platform',
  },
  {
    path: '/app/platform/clinics',
    labelKey: 'shell.nav.platformClinics',
    icon: 'clinic',
    group: 'platform',
  },
  {
    path: '/app/platform/subscriptions',
    labelKey: 'shell.nav.platformSubscriptions',
    icon: 'finance',
    group: 'platform',
  },
  {
    path: '/app/platform/plans',
    labelKey: 'shell.nav.platformPlans',
    icon: 'admin',
    group: 'platform',
  },
  {
    path: '/app/platform/audit',
    labelKey: 'shell.nav.platformAudit',
    icon: 'audit',
    group: 'platform',
  },
];

export function entriesForGroup(group: NavGroup): readonly NavEntry[] {
  return NAV_ENTRIES.filter((entry) => entry.group === group);
}
