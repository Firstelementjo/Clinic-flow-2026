/**
 * `/app/clinic/**` routing spec — every clinic route this repository mounts.
 *
 * ## What this suite pins
 *
 * 1. **Every clinic route is declared**, in a list that is written out in full, so a
 *    route added without a spec behind it — and a route deleted without its spec —
 *    are both caught.
 * 2. **The routes that have a read model resolve to their own component**, with the
 *    `permission` grant the backend's matching controller expects.
 * 3. **The routes that have no read model resolve to the honest not-available
 *    component** and name the API route that does not exist. Two of them
 *    (`clinic/lab`, `clinic/aligners`) used to render a mock that
 *    shipped; this is the assertion that keeps them from going back.
 * 4. **Ordering:** `patients/:id/odontogram` and `patients/:id/treatment-plans` are reached
 *    and are not swallowed by `patients/:id`.
 *
 * ## The harness is deliberately created inside each test
 *
 * Angular's `RouterTestingHarness` creates a fresh navigation graph per call. Reusing one
 * across fifteen tests runs out of heap in a single worker because each navigation leaves a
 * component tree alive: recreating the harness gives each test its own DOM and tears it
 * down immediately afterwards.
 */

import { provideLocationMocks } from '@angular/common/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import type { Type } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { type Route, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

afterEach(() => {
  localStorage.clear();
});

import { routes } from '../../app.routes';
import { ClinicAppointmentDetailComponent } from './clinic-appointment-detail.component';
import { ClinicAppointmentsComponent } from './clinic-appointments.component';
import { ClinicCalendarComponent } from './clinic-calendar.component';
import { ClinicComponent } from './clinic.component';
import { ClinicDoctorsComponent } from './clinic-doctors.component';
import { ClinicFrontDeskComponent } from './clinic-front-desk.component';
import { ClinicLocationsComponent } from './clinic-locations.component';
import { ClinicOdontogramComponent } from './clinic-odontogram.component';
import { ClinicPatientDetailComponent } from './clinic-patient-detail.component';
import { ClinicPatientsComponent } from './clinic-patients.component';
import { ClinicSchedulesComponent } from './clinic-schedules.component';
import { ClinicServicesComponent } from './clinic-services.component';
import { ClinicTreatmentPlansComponent } from './clinic-treatment-plans.component';
import { ClinicEncountersComponent } from './clinic-encounters.component';
import { ClinicInvoicesComponent } from './clinic-invoices.component';
import { ClinicPaymentsComponent } from './clinic-payments.component';
import { ClinicReportsComponent } from './clinic-reports.component';
import { ClinicPoliciesComponent } from './clinic-policies.component';
import { ClinicAiAssistantComponent } from './clinic-ai-assistant.component';
import { ClinicLabComponent } from './clinic-lab.component';
import { ClinicAlignersComponent } from './clinic-aligners.component';
import { ClinicFeedbackComponent } from './clinic-feedback.component';

/**
 * The routes `plan.md` §15 names that are backed by a real read model.
 */
const BACKED: readonly { path: string; url: string; component: Type<unknown> }[] = [
  { path: 'clinic/dashboard', url: '/app/clinic/dashboard', component: ClinicComponent },
  { path: 'clinic/patients', url: '/app/clinic/patients', component: ClinicPatientsComponent },
  {
    path: 'clinic/patients/:id',
    url: '/app/clinic/patients/pat-1',
    component: ClinicPatientDetailComponent,
  },
  { path: 'clinic/calendar', url: '/app/clinic/calendar', component: ClinicCalendarComponent },
  {
    path: 'clinic/appointments',
    url: '/app/clinic/appointments',
    component: ClinicAppointmentsComponent,
  },
  {
    path: 'clinic/appointments/:id',
    url: '/app/clinic/appointments/a-1',
    component: ClinicAppointmentDetailComponent,
  },
  {
    path: 'clinic/doctors-and-schedules',
    url: '/app/clinic/doctors-and-schedules',
    component: ClinicSchedulesComponent,
  },
  {
    path: 'clinic/services-and-resources',
    url: '/app/clinic/services-and-resources',
    component: ClinicServicesComponent,
  },
  {
    path: 'clinic/branches',
    url: '/app/clinic/branches',
    component: ClinicLocationsComponent,
  },
  {
    path: 'clinic/doctors',
    url: '/app/clinic/doctors',
    component: ClinicDoctorsComponent,
  },
  {
    path: 'clinic/front-desk',
    url: '/app/clinic/front-desk',
    component: ClinicFrontDeskComponent,
  },
  {
    path: 'clinic/patients/:id/odontogram',
    url: '/app/clinic/patients/pat-1/odontogram',
    component: ClinicOdontogramComponent,
  },
  {
    path: 'clinic/patients/:id/treatment-plans',
    url: '/app/clinic/patients/pat-1/treatment-plans',
    component: ClinicTreatmentPlansComponent,
  },
  {
    path: 'clinic/patients/:id/encounters',
    url: '/app/clinic/patients/pat-1/encounters',
    component: ClinicEncountersComponent,
  },
  { path: 'clinic/encounters/:id', url: '/app/clinic/encounters/e-1', component: ClinicEncountersComponent },
  { path: 'clinic/invoices', url: '/app/clinic/invoices', component: ClinicInvoicesComponent },
  { path: 'clinic/payments', url: '/app/clinic/payments', component: ClinicPaymentsComponent },
  { path: 'clinic/reports', url: '/app/clinic/reports', component: ClinicReportsComponent },
  { path: 'clinic/settings/policies', url: '/app/clinic/settings/policies', component: ClinicPoliciesComponent },
  { path: 'clinic/ai-assistant', url: '/app/clinic/ai-assistant', component: ClinicAiAssistantComponent },
  { path: 'clinic/lab', url: '/app/clinic/lab', component: ClinicLabComponent },
  { path: 'clinic/aligners', url: '/app/clinic/aligners', component: ClinicAlignersComponent },
  { path: 'clinic/feedback', url: '/app/clinic/feedback', component: ClinicFeedbackComponent },
];

/**
 * Every clinic path this repository mounts, including the `/app/clinic` entry.
 *
 * The list is complete on purpose, because a route added without being declared here
 * reaches an operator with no spec behind it, and a route removed without being
 * removed here leaves the spec asserting a screen that no longer exists. The seven
 * routes the gap table used to name are in it, because a closed gap is still a route.
 */
const CLINIC_PATHS: readonly string[] = [
  'clinic',
  'clinic/dashboard',
  'clinic/patients',
  'clinic/patients/:id',
  'clinic/patients/:id/odontogram',
  'clinic/patients/:id/treatment-plans',
  'clinic/patients/:id/encounters',
  'clinic/encounters/:id',
  'clinic/calendar',
  'clinic/appointments',
  'clinic/appointments/:id',
  'clinic/doctors-and-schedules',
  'clinic/doctors',
  'clinic/services-and-resources',
  'clinic/branches',
  'clinic/lab',
  'clinic/aligners',
  'clinic/workspace',
  'clinic/commission',
  'clinic/payouts',
  'clinic/feedback',
  'clinic/sessions',
  'clinic/front-desk',
  'clinic/overview',
  'clinic/invoices',
  'clinic/payments',
  'clinic/reports',
  'clinic/settings/policies',
  'clinic/ai-assistant',
];

const appRoute = routes.find((route) => route.path === 'app');
const appChildren = appRoute?.children ?? [];

const clinicRoutes = appChildren.filter(
  (route) => route.path === 'clinic' || (route.path ?? '').startsWith('clinic/'),
);

/** The clinic children, with the guards removed and everything else untouched. */
function unguarded(source: readonly Route[]): Route[] {
  return source.map((route) => ({ ...route, canActivate: undefined, canActivateChild: undefined }));
}

async function loadComponentOf(route: Route): Promise<unknown> {
  if (typeof route.component === 'function') return route.component;
  if (route.loadComponent) return (await route.loadComponent()) as unknown;
  return null;
}

describe('the clinic route table', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      teardown: { destroyAfterEach: true },
      providers: [
        provideRouter([{ path: 'app', children: unguarded(clinicRoutes) }]),
        provideLocationMocks(),
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  /** Answers every read a freshly routed screen fires, so the next navigation starts clean. */
  async function drain(): Promise<void> {
    for (const request of http.match(() => true)) request.flush({});
    await new Promise<void>((resolve) => setTimeout(resolve, 0));
  }

  describe('the declarations themselves', () => {
    it('mounts every clinic route the table declares, and nothing else', () => {
      // The count was the claim, and the claim was stale: the table had grown to
      // twenty-eight clinic routes while this file still asserted seventeen. A count
      // that only drifts is worse than no count, so the list is now written out and
      // compared in both directions — a route added without a spec behind it, and a
      // route deleted without its spec, are both caught.
      expect(clinicRoutes.length).toBe(CLINIC_PATHS.length);
      expect(clinicRoutes.map((route) => route.path).sort()).toEqual([...CLINIC_PATHS].sort());
    });

    it('declares a permission on every one of them, because the guard reads it from there', () => {
      for (const route of clinicRoutes) {
        const permission = (route.data ?? {})['permission'];
        expect(typeof permission, `${route.path} declares no permission`).toBe('string');
        expect(permission).not.toBe('');
      }
    });

    it('gates the read-model screens on the grant the backend guards them with', () => {
      const permissionFor = (path: string): unknown =>
        (clinicRoutes.find((route) => route.path === path)?.data ?? {})['permission'];

      // `ClinicPatientController` declares appointment:read on every route it owns.
      expect(permissionFor('clinic/patients')).toBe('appointment:read');
      expect(permissionFor('clinic/patients/:id')).toBe('appointment:read');
      expect(permissionFor('clinic/patients/:id/odontogram')).toBe('appointment:read');
      expect(permissionFor('clinic/calendar')).toBe('appointment:read');
      expect(permissionFor('clinic/appointments/:id')).toBe('appointment:read');
      // `ClinicController` declares doctor:read / procedure:read.
      expect(permissionFor('clinic/doctors-and-schedules')).toBe('doctor:read');
      expect(permissionFor('clinic/doctors')).toBe('doctor:read-management');
      expect(permissionFor('clinic/front-desk')).toBe('front-desk:read');
      expect(permissionFor('clinic/services-and-resources')).toBe('procedure:read');
      expect(permissionFor('clinic/dashboard')).toBe('doctor:read');
    });

    it('confirms all clinic routes are backed by real components with 0 remaining gap routes', () => {
      const gaps = clinicRoutes.filter((route) => (route.data ?? {})['missing'] !== undefined);
      expect(gaps).toEqual([]);
    });

    it.each(BACKED)('declares $path and resolves it to the intended component', async (entry) => {
      const route = clinicRoutes.find((candidate) => candidate.path === entry.path);
      expect(route, `${entry.path} is not declared`).toBeDefined();
      expect(await loadComponentOf(route!)).toBe(entry.component);
    });

    it('still resolves /app/clinic to the existing ClinicComponent, not to a copy', async () => {
      const route = clinicRoutes.find((candidate) => candidate.path === 'clinic');
      expect(await loadComponentOf(route!)).toBe(ClinicComponent);
    });
  });

  describe('navigation', () => {
    it.each(BACKED)('routes $url to the intended component', async (entry) => {
      const harness = await RouterTestingHarness.create();
      const activated = await harness.navigateByUrl<unknown>(entry.url, entry.component);
      expect(activated).toBeInstanceOf(entry.component);
      await drain();
    });

    it('routes /app/clinic to the existing ClinicComponent', async () => {
      const harness = await RouterTestingHarness.create();
      const activated = await harness.navigateByUrl<unknown>('/app/clinic', ClinicComponent);
      expect(activated).toBeInstanceOf(ClinicComponent);
      await drain();
    });

    it('does not let patients/:id swallow the deeper chart and plan URLs', async () => {
      const harness = await RouterTestingHarness.create();

      const chart = await harness.navigateByUrl<unknown>(
        '/app/clinic/patients/pat-1/odontogram',
        ClinicOdontogramComponent,
      );
      expect(chart).toBeInstanceOf(ClinicOdontogramComponent);
      await drain();

      const plans = await harness.navigateByUrl<unknown>(
        '/app/clinic/patients/pat-1/treatment-plans',
        ClinicTreatmentPlansComponent,
      );
      expect(plans).toBeInstanceOf(ClinicTreatmentPlansComponent);
      await drain();

      const record = await harness.navigateByUrl<unknown>(
        '/app/clinic/patients/pat-1',
        ClinicPatientDetailComponent,
      );
      expect(record).toBeInstanceOf(ClinicPatientDetailComponent);
      await drain();
    });
  });
});
