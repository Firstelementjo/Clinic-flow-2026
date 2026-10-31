/**
 * The route table.
 *
 * Shape preserved from the frozen client (`apps/web/src/App.tsx`):
 * `/login` outside the shell, `/app/*` inside it with an index that redirects to
 * the dashboard, and a wildcard that lands somewhere useful rather than on a
 * blank page. `/api/v1/**` is explicitly excluded from the SPA so a mistyped API
 * path returns the backend's 404 rather than the app shell â€” which matters,
 * because an operator who bookmarks an API URL should see what actually happened.
 *
 * Lazy `loadComponent` for every route: the strangler window is long, and a
 * route that has not been migrated yet must not be in the initial bundle.
 */
import { inject } from '@angular/core';
import { Router, type CanActivateFn, type Routes } from '@angular/router';
import { anonymousGuard, permissionGuard, sessionGuard } from './core/auth/guards';
import { ClinicRbacService } from './core/auth/clinic-rbac';
import { SessionStore } from './core/auth/session.store';
import { ShellComponent } from './shell/shell.component';

/** Paths that are API calls, not application routes. */
const API_PREFIXES = ['/api/', '/webhooks/', '/actuator/'];

/** Guard for routes that have been hidden per user request to prevent direct URL access */
const hiddenRouteGuard: CanActivateFn = () => {
  const router = inject(Router);
  return router.createUrlTree(['/app/dashboard']);
};

/** Guard for clinical encounters: strictly clinicians (doctor) and clinic admins */
const clinicalEncounterGuard: CanActivateFn = () => {
  const rbac = inject(ClinicRbacService);
  const router = inject(Router);
  const store = inject(SessionStore);

  const role = store.role();
  if (role === 'ADMIN' || rbac.activeRoles().includes('clinic_admin')) return true;
  if (
    rbac.hasAnyPermission(['clinical.view_record', 'clinical.write_record', 'clinical.view_history']) ||
    rbac.activeRoles().includes('doctor')
  ) {
    return true;
  }
  return router.createUrlTree(['/app/denied'], {
    queryParams: { permission: 'clinical.view_record' },
  });
};

export const routes: Routes = [
  {
    path: 'login',
    canActivate: [anonymousGuard],
    loadComponent: () => import('./features/login/login.component').then((m) => m.LoginComponent),
    title: 'login.title',
  },
  {
    path: 'invite',
    loadComponent: () =>
      import('./features/invite/invite-accept.component').then((m) => m.InviteAcceptComponent),
    title: 'Clinic Flow - Accept Invitation',
  },
  {
    path: 'app',
    canActivate: [sessionGuard],
    component: ShellComponent,
    children: [
      // The root of the shell is the dashboard. Dashboard access is gated on `doctor:read`,
      // which every clinic role holds -- the practice manager, the treating dentist and the
      // receptionist alike -- so `/app` lands on a screen all three can actually open.
      //
      // It used to hang `landingGuard` here, and that guard redirected CLINIC_STAFF and
      // EMPLOYEE away from the dashboard: a dentist saw the Dashboard link, clicked it, and
      // was silently returned to the calendar, while the front desk never saw the link at
      // all because the navigation entry demanded `conversation:read`. A link that exists
      // and does nothing is worse than a link that is absent. The dashboard itself already
      // asks only for the clinic reports the caller may read and marks withheld cards, so
      // there is no reason to keep anybody out of the screen.
      { path: '', pathMatch: 'full', redirectTo: 'dashboard' },
      {
        path: 'dashboard',
        canActivate: [permissionGuard],
        data: { permission: 'doctor:read' },
        loadComponent: () =>
          import('./features/dashboard/dashboard.component').then((m) => m.DashboardComponent),
        title: 'dashboard.title',
      },
      {
        // Group 5 â€” the inbox.
        //
        // The selected conversation is a **query parameter on one static route**, not a
        // path segment, and that is a considered choice rather than a shortcut:
        //
        // - `path: 'inbox/:conversationId?'` was tried first and does not match `/app/inbox`
        //   in this Angular version â€” the bare path rendered not-found.
        // - Two sibling routes (`inbox` and `inbox/:conversationId`) that both load the
        //   same component are two distinct route configs, so the router **destroys and
        //   recreates** the component when the selection changes. Every click on a
        //   conversation would then refetch the list, the counts, the queue, the thread
        //   and the customer â€” and reset the scroll position. An inbox that re-fetches
        //   the queue every time you open something is not an inbox.
        //
        // A query parameter keeps a single matched route, so the instance is reused and
        // only `queryParamMap` changes. The URL is still shareable and still survives a
        // browser refresh, which is the property that matters.
        path: 'inbox',
        canActivate: [permissionGuard],
        data: { permission: 'conversation:read' },
        loadComponent: () =>
          import('./features/inbox/inbox.component').then((m) => m.InboxComponent),
        title: 'inbox.title',
      },
      {
        // Group 6 â€” people, and the 360 record behind one person. Same reason.
        path: 'people',
        canActivate: [permissionGuard],
        data: { permission: 'customer:read' },
        loadComponent: () =>
          import('./features/people/people.component').then((m) => m.PeopleComponent),
        title: 'people.title',
      },
      {
        // Group 7 â€” team. Reads the administration identity directory; the parts of
        // the frozen screen with no route are marked unavailable rather than drawn.
        path: 'team',
        canActivate: [permissionGuard],
        data: { permission: 'employee:read' },
        loadComponent: () =>
          import('./features/team/team.component').then((m) => m.TeamComponent),
        title: 'team.title',
      },
      {
        // Group 8 â€” knowledge, AI governance, channels and escalation.
        path: 'knowledge',
        canActivate: [permissionGuard],
        data: { permission: 'knowledge:read' },
        loadComponent: () =>
          import('./features/knowledge/knowledge.component').then((m) => m.KnowledgeComponent),
        title: 'knowledge.title',
      },
      {
        path: 'channels',
        canActivate: [permissionGuard],
        data: { permission: 'channel:read' },
        loadComponent: () =>
          import('./features/knowledge/knowledge.component').then((m) => m.KnowledgeComponent),
        title: 'knowledge.channels.title',
      },
      {
        path: 'platform/dashboard',
        canActivate: [permissionGuard],
        data: { permission: 'system-health:read' },
        loadComponent: () =>
          import('./features/platform/dashboard/platform-dashboard.component').then(
            (m) => m.PlatformDashboardComponent,
          ),
        title: 'Platform - Dashboard',
      },
      {
        path: 'admin/dashboard',
        canActivate: [permissionGuard],
        data: { permission: 'system-health:read' },
        loadComponent: () =>
          import('./features/platform/dashboard/platform-dashboard.component').then(
            (m) => m.PlatformDashboardComponent,
          ),
        title: 'Platform - Dashboard',
      },
      {
        path: 'platform/clinics',
        canActivate: [permissionGuard],
        data: { permission: 'system-health:read' },
        loadComponent: () =>
          import('./features/platform/clinics/platform-clinics.component').then(
            (m) => m.PlatformClinicsComponent,
          ),
        title: 'Platform - Clinics',
      },
      {
        path: 'platform/subscriptions',
        canActivate: [permissionGuard],
        data: { permission: 'system-health:read' },
        loadComponent: () =>
          import('./features/platform/subscriptions/platform-subscriptions.component').then(
            (m) => m.PlatformSubscriptionsComponent,
          ),
        title: 'Platform - Subscriptions',
      },
      {
        path: 'platform/plans',
        canActivate: [permissionGuard],
        data: { permission: 'system-health:read' },
        loadComponent: () =>
          import('./features/platform/plans/platform-plans.component').then(
            (m) => m.PlatformPlansComponent,
          ),
        title: 'Platform - Plans & Limits',
      },
      {
        path: 'platform/audit',
        canActivate: [permissionGuard],
        data: { permission: 'system-health:read' },
        loadComponent: () =>
          import('./features/platform/audit/platform-audit.component').then(
            (m) => m.PlatformAuditComponent,
          ),
        title: 'Platform - Audit Trail',
      },
      {
        path: 'platform/support-access',
        canActivate: [permissionGuard],
        data: { permission: 'system-health:read' },
        loadComponent: () =>
          import('./features/platform/support-access/platform-support-access.component').then(
            (m) => m.PlatformSupportAccessComponent,
          ),
        title: 'Platform - Support Access',
      },
      {
        // Group 9 â€” administration.
        //
        // Gated on `system-health:read`, the permission the backend's own
        // `AdminReadModelController` declares on `/admin/overview`. The screen then
        // checks a *further* permission per subject â€” audit-log, security-event,
        // session-revoke, analytics â€” so a caller with only the health permission sees
        // health, backups and migrations, and nothing they may not read.
        path: 'admin',
        canActivate: [permissionGuard],
        data: { permission: 'system-health:read' },
        loadComponent: () => import('./features/admin/admin.component').then((m) => m.AdminComponent),
        title: 'admin.title',
      },
      {
        // Groups 10 and 11 â€” finance, reports and exports.
        //
        // Gated on `analytics:read-organization`, because that is what every one of the
        // twenty-four finance routes requires and the backend says so deliberately. The
        // Exports tab additionally checks `analytics:read-project`, a weaker value
        // covering one project's report rows, so a project-scoped session can reach the
        // export list without reaching finance.
        path: 'finance',
        canActivate: [permissionGuard],
        data: { permission: 'analytics:read-organization' },
        loadComponent: () => import('./features/finance/finance.component').then((m) => m.FinanceComponent),
        title: 'finance.title',
      },
      {
        // Group 12 â€” clinic.
        //
        // PHASE 49. This was gated on `customer:read`, and the comment above it said
        // "There is no clinic permission, because there is no clinic route" â€” which was
        // true when written and stopped being true in Phase 37, when four clinic routes
        // landed and were never followed by an update here.
        //
        // The consequence was that CLINIC_STAFF â€” the role whose entire purpose is the
        // clinic front desk â€” could not reach this screen at all. It holds
        // doctor:read, procedure:read, appointment:read and analytics:read-project, and
        // NONE of customer:read, so the guard sent it to "denied" while the backend
        // answered every clinic route with 200. A human tester found this in the first
        // minute; no automated test did, because every automated test signed in as an
        // account that holds customer:read.
        //
        // Gated on `doctor:read` now: it is the grant this screen is actually about, the
        // component's own tests declare it as a clinic grant, and it is the one a
        // front-desk clerk is meant to hold. The backend remains authoritative â€” this is
        // a routing decision, not a permission.
        path: 'clinic',
        redirectTo: '/app/dashboard',
        pathMatch: 'full',
      },
      // ====================================================================
      // Group 12, phase 12 â€” the clinic routes.
      // ====================================================================
      //
      // ## THE PATH PREFIX IS `/app/clinic/...`, and `plan.md` Â§15 wrote `/clinic/...`
      //
      // Â§15 names fifteen routes as `/clinic/dashboard`, `/clinic/patients`, and so on.
      // **Every authenticated screen in this repository mounts under `/app/`**
      // (`/app/clinic`, `/app/inbox`, `/app/finance`), and the shell's navigation is
      // built from `/app/**` paths. So the fifteen are mounted at `/app/clinic/...`.
      //
      // Recorded here rather than silently substituted: if `/clinic/*` is wanted as a
      // bare alias, it is a deliberate addition with its own guard, not an accident to
      // paper over.
      //
      // ## The declarations are siblings, not children
      //
      // A `children: [...]` block on the `clinic` route above would inherit its
      // `doctor:read` guard onto every route below it, which is the wrong grant for a
      // patient list (which the backend guards on `appointment:read`) and the wrong one
      // for a procedure catalogue (`procedure:read`). Each route therefore declares its
      // own `canActivate` and its own `data.permission`, and is a sibling. The routing
      // spec for the group is `clinic-routing.spec.ts`.
      //
      // ## Ordering: most specific first
      //
      // `patients/:id/odontogram` and `patients/:id/treatment-plans` are declared before
      // `patients/:id`, which is declared before `patients`. The router backtracks on a
      // leftover segment, but a table whose correctness depends on backtracking is a
      // table somebody will reorder without noticing.
      //
      // ## Every `data.permission` below is UX only
      //
      // The backend is the authorization boundary and re-checks each of these on the
      // route the screen then calls. A wrong literal here costs a user a click; a wrong
      // literal in the server costs a tenant its data.
      //
      // ## The gap routes name the path that does not exist
      //
      // `title`, `body` and `missing` are read by `ClinicGapComponent` straight out of
      // this table, so there is exactly one place that names an absent route. `missing`
      // is a path, not copy: `NotAvailableComponent` renders it `fe-mono dir="ltr"`
      // because a route is code, and code does not flip direction with prose.
      //
      // Seven routes used to carry that data. All seven were closed â€” each got its
      // read model and its own component, and the gap table emptied. Three carry it
      // again now: `clinic/branches`, `clinic/lab` and `clinic/aligners`, which have
      // no backend to read. The table is a statement of fact about the contract, and
      // it is emptied by building the route, never by deleting the note.
      {
        path: 'clinic/dashboard',
        canActivate: [permissionGuard],
        data: { permission: 'doctor:read' },
        // The analytics screen `ClinicComponent` already is. Deliberately the SAME
        // component, not a copy: two components rendering one read model is how the two
        // drift, and `clinic.component.spec.ts` backs one screen.
        loadComponent: () => import('./features/clinic/clinic.component').then((m) => m.ClinicComponent),
        title: 'clinic.title',
      },
      {
        path: 'clinic/patients/:id/odontogram',
        canActivate: [permissionGuard],
        data: { permission: 'appointment:read' },
        loadComponent: () =>
          import('./features/clinic/clinic-odontogram.component').then((m) => m.ClinicOdontogramComponent),
        title: 'odontogram.title',
      },
      {
        path: 'clinic/patients/:id/treatment-plans',
        canActivate: [permissionGuard],
        data: { permission: 'appointment:read' },
        loadComponent: () =>
          import('./features/clinic/clinic-treatment-plans.component').then(
            (m) => m.ClinicTreatmentPlansComponent,
          ),
        title: 'clinic.treatmentPlans.title',
      },
      {
        path: 'clinic/patients/:id/encounters',
        canActivate: [clinicalEncounterGuard],
        loadComponent: () =>
          import('./features/clinic/clinic-encounters.component').then(
            (m) => m.ClinicEncountersComponent,
          ),
        title: 'Encounter Dashboard',
      },
      {
        path: 'clinic/patients/:id',
        canActivate: [permissionGuard],
        data: { permission: 'appointment:read' },
        loadComponent: () =>
          import('./features/clinic/clinic-patient-detail.component').then((m) => m.ClinicPatientDetailComponent),
        title: 'patient.title',
      },
      {
        path: 'clinic/patients',
        canActivate: [permissionGuard],
        data: { permission: 'appointment:read' },
        loadComponent: () =>
          import('./features/clinic/clinic-patients.component').then((m) => m.ClinicPatientsComponent),
        title: 'patients.title',
      },
      {
        // The Clinic List. Phase 38 added `clinic_locations`, so this screen reads
        // and writes real rows instead of rendering the old not-available card.
        // The read is guarded by `doctor:read` (the roster-adjacent grant the
        // location endpoint uses); New/Save are gated again inside the component
        // on `clinic:manage`.
        path: 'clinic/branches',
        canActivate: [permissionGuard],
        data: { permission: 'doctor:read' },
        loadComponent: () =>
          import('./features/clinic/clinic-locations.component').then(
            (m) => m.ClinicLocationsComponent,
          ),
        title: 'clinic.branches.title',
      },
      {
        path: 'clinic/lab',
        canActivate: [permissionGuard],
        data: { permission: 'doctor:read' },
        loadComponent: () =>
          import('./features/clinic/clinic-lab.component').then((m) => m.ClinicLabComponent),
        title: 'clinic.lab.title',
      },
      {
        path: 'clinic/aligners',
        canActivate: [hiddenRouteGuard],
        data: { permission: 'doctor:read' },
        loadComponent: () =>
          import('./features/clinic/clinic-aligners.component').then((m) => m.ClinicAlignersComponent),
        title: 'clinic.aligners.title',
      },
      {
        path: 'clinic/workspace',
        redirectTo: '/app/clinic/patients',
        pathMatch: 'full',
      },
      {
        path: 'clinic/commission',
        redirectTo: '/app/clinic/reports',
        pathMatch: 'full',
      },
      {
        path: 'clinic/payouts',
        canActivate: [permissionGuard],
        data: { permission: 'doctor:read' },
        loadComponent: () =>
          import('./features/clinic/clinic-payments.component').then((m) => m.ClinicPaymentsComponent),
        title: 'clinic.payouts.title',
      },
      {
        path: 'clinic/feedback',
        canActivate: [permissionGuard],
        data: { permission: 'appointment:read' },
        loadComponent: () =>
          import('./features/clinic/clinic-feedback.component').then((m) => m.ClinicFeedbackComponent),
        title: 'clinic.feedback.title',
      },
      {
        path: 'clinic/post-instructions',
        redirectTo: '/app/dashboard',
        pathMatch: 'full',
      },
      {
        path: 'clinic/sessions',
        canActivate: [permissionGuard],
        data: { permission: 'doctor:read' },
        loadComponent: () =>
          import('./features/clinic/clinic-schedules.component').then((m) => m.ClinicSchedulesComponent),
        title: 'clinic.sessions.title',
      },
      {
        // The management Doctors screen. It reads `/clinic/doctor-profiles`, which
        // is gated on `doctor:read-management` because it publishes clinician email
        // and phone. The roster read on the booking surfaces remains `doctor:read`.
        path: 'clinic/doctors',
        canActivate: [permissionGuard],
        data: { permission: 'doctor:read-management' },
        loadComponent: () =>
          import('./features/clinic/clinic-doctors.component').then(
            (m) => m.ClinicDoctorsComponent,
          ),
        title: 'shell.nav.doctors',
      },
      {
        // The Front Desk directory is a real account read: `/clinic/front-desk`
        // returns CLINIC_STAFF users and their one clinic assignment.
        path: 'clinic/front-desk',
        canActivate: [permissionGuard],
        data: { permission: 'front-desk:read' },
        loadComponent: () =>
          import('./features/clinic/clinic-front-desk.component').then(
            (m) => m.ClinicFrontDeskComponent,
          ),
        title: 'shell.nav.frontDesk',
      },
      {
        path: 'clinic/appointments-overview',
        redirectTo: '/app/clinic/calendar',
        pathMatch: 'full',
      },
      {
        path: 'clinic/overview',
        redirectTo: '/app/dashboard',
        pathMatch: 'full',
      },
      {
        path: 'clinic/calendar',
        canActivate: [permissionGuard],
        data: { permission: 'appointment:read' },
        loadComponent: () =>
          import('./features/clinic/clinic-calendar.component').then((m) => m.ClinicCalendarComponent),
        title: 'calendar.title',
      },
      {
        // The list the Create Appointment drawer saves into. Keyset-paginated on
        // the contract's cursor, with the status/payment/search filters the route
        // publishes. It is mounted before `appointments/:id` so the literal path
        // wins over the parameter.
        path: 'clinic/appointments',
        canActivate: [permissionGuard],
        data: { permission: 'appointment:read' },
        loadComponent: () =>
          import('./features/clinic/clinic-appointments.component').then(
            (m) => m.ClinicAppointmentsComponent,
          ),
        title: 'nav.appointments',
      },
      {
        path: 'clinic/appointments/:id',
        canActivate: [permissionGuard],
        data: { permission: 'appointment:read' },
        loadComponent: () =>
          import('./features/clinic/clinic-appointment-detail.component').then((m) => m.ClinicAppointmentDetailComponent),
        title: 'appointment.title',
      },
      {
        // `doctor:read` for the roster and `appointment:read` for `/availability`; the
        // component reads the second grant itself and says so on the screen rather than
        // calling a route the caller may not use.
        path: 'clinic/doctors-and-schedules',
        canActivate: [permissionGuard],
        data: { permission: 'doctor:read' },
        loadComponent: () =>
          import('./features/clinic/clinic-schedules.component').then((m) => m.ClinicSchedulesComponent),
        title: 'schedules.title',
      },
      {
        // PARTIAL by construction: `/procedures` is real, clinic resources have no
        // route at all. The screen renders both halves and says which is which.
        path: 'clinic/services-and-resources',
        canActivate: [permissionGuard],
        data: { permission: 'procedure:read' },
        loadComponent: () =>
          import('./features/clinic/clinic-services.component').then((m) => m.ClinicServicesComponent),
        title: 'services.title',
      },
      {
        path: 'clinic/encounters/:id',
        canActivate: [clinicalEncounterGuard],
        loadComponent: () =>
          import('./features/clinic/clinic-encounters.component').then(
            (m) => m.ClinicEncountersComponent,
          ),
        title: 'Encounter Dashboard',
      },
      {
        path: 'clinic/prescriptions',
        canActivate: [clinicalEncounterGuard],
        loadComponent: () =>
          import('./features/clinic/clinic-prescriptions.component').then(
            (m) => m.ClinicPrescriptionsComponent,
          ),
        title: 'shell.nav.prescriptions',
      },
      {
        path: 'clinic/invoices',
        canActivate: [permissionGuard],
        data: { permission: 'appointment:read' },
        loadComponent: () =>
          import('./features/clinic/clinic-invoices.component').then((m) => m.ClinicInvoicesComponent),
        title: 'clinic.invoices.title',
      },
      {
        path: 'clinic/payments',
        canActivate: [permissionGuard],
        data: { permission: 'appointment:read' },
        loadComponent: () =>
          import('./features/clinic/clinic-payments.component').then((m) => m.ClinicPaymentsComponent),
        title: 'clinic.payments.title',
      },
      {
        path: 'clinic/reports',
        canActivate: [permissionGuard],
        data: { permission: 'appointment:read' },
        loadComponent: () =>
          import('./features/clinic/clinic-reports.component').then((m) => m.ClinicReportsComponent),
        title: 'clinic.reports.title',
      },
      {
        path: 'clinic/settings/policies',
        canActivate: [permissionGuard],
        data: { permission: 'appointment:read' },
        loadComponent: () =>
          import('./features/clinic/clinic-policies.component').then((m) => m.ClinicPoliciesComponent),
        title: 'clinic.policies.title',
      },
      {
        path: 'clinic/ai-assistant',
        canActivate: [permissionGuard],
        data: { permission: 'appointment:read' },
        loadComponent: () =>
          import('./features/clinic/clinic-ai-assistant.component').then((m) => m.ClinicAiAssistantComponent),
        title: 'clinic.aiAssistant.title',
      },
      {
        path: 'settings',
        canActivate: [permissionGuard],
        data: { permission: 'conversation:read' },
        loadComponent: () =>
          import('./features/settings/settings.component').then((m) => m.SettingsComponent),
        title: 'settings.title',
      },
      {
        path: 'denied',
        loadComponent: () => import('./features/denied/denied.component').then((m) => m.DeniedComponent),
        title: 'state.denied.title',
      },
      // Anything else. Phase 11 groups 5-12 land here as real routes; a screen the
      // committed contract has no route for is marked unavailable in the navigation
      // rather than linked to a page that cannot exist.
      {
        path: '**',
        loadComponent: () =>
          import('./features/not-found/not-found.component').then((m) => m.NotFoundComponent),
        title: 'state.notFound.title',
      },
    ],
  },
  { path: '', pathMatch: 'full', redirectTo: 'app/dashboard' },
  { path: '**', loadComponent: () => import('./features/not-found/not-found.component').then((m) => m.NotFoundComponent) },
];

/** Whether a URL belongs to the API rather than to the application. */
export function isApiPath(url: string): boolean {
  return API_PREFIXES.some((prefix) => url.startsWith(prefix));
}
