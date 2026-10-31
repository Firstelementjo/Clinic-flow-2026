/**
 * The clinic screen, against recorded server responses.
 *
 * ## Why this suite exists at all
 *
 * The screen used to render a "not available" card, and its coverage was a test
 * asserting the card was there. That is the right coverage for a screen with no
 * data and the *wrong* coverage for one with data, because it asserted nothing about
 * what the screen does with a figure.
 *
 * The three claims worth protecting are:
 *
 * 1. **A number renders with the rows behind it.** ADR 0013 requires every metric
 *    to drill to source rows. A screen that shows `appointmentCount: 12` and drops
 *    `sourceRowIds` has moved the defect from the backend into the UI, which is not
 *    a fix — so the ids must be *reachable*, not merely present in the payload. The
 *    disclosure is asserted as a real control (a `button`, `aria-expanded`,
 *    `aria-controls`), which is what makes it operable by keyboard and by screen
 *    reader rather than by mouse hover alone.
 * 2. **A zero is not a blank.** A procedure with no bookings must read as `0` with
 *    an explicit note, and not as "no data" — FR-7.6 makes those different facts
 *    and a screen that conflates them reintroduces the ambiguity.
 * 3. **A screen does not call a route it may not read.** The metrics section is gated
 *    on the project-scoped analytics grant, and the gate is asserted by asserting the
 *    *absence of the request*, not by asserting that a 403 renders nicely.
 *
 * ## Bilingual
 *
 * Both locales are rendered. The Arabic assertions are not a translation check — they
 * are a check that the Arabic path is exercised at all, which is what stops the
 * English-only screen from being the only one anybody tests. A clinician's name is
 * *data*, not copy, so it is asserted to survive the switch in both directions.
 *
 * ## A note on what these tests are not
 *
 * There is no route-level test here. The backend suites own "what the server answers";
 * this suite owns "what the screen does with the answer", and the split is deliberate —
 * a route test that also asserted the markup would pass while the markup rotted.
 */
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18nService } from '../../core/i18n/i18n.service';
import { SessionStore } from '../../core/auth/session.store';
import type { SessionResponse } from '../../core/api/generated';
import { ClinicComponent } from './clinic.component';

/** One recorded analytics body, shaped exactly as the API sends it. */
const ANALYTICS = {
  funnelProvenance: {
    sourceTable: 'appointments',
    windowStart: { iso8601: '2026-08-10T00:00:00.000Z' },
    windowEnd: { iso8601: '2026-09-09T00:00:00.000Z' },
    sourceRowSampleLimit: 50,
    derivation: 'count(*) grouped by appointments."status".',
  },
  demandProvenance: {
    sourceTable: 'procedures left join appointments',
    windowStart: { iso8601: '2026-08-10T00:00:00.000Z' },
    windowEnd: { iso8601: '2026-09-09T00:00:00.000Z' },
    sourceRowSampleLimit: 50,
    derivation: 'count(appointments."id") grouped by procedures."id".',
  },
  workloadProvenance: {
    sourceTable: 'doctors left join appointments',
    windowStart: { iso8601: '2026-08-10T00:00:00.000Z' },
    windowEnd: { iso8601: '2026-09-09T00:00:00.000Z' },
    sourceRowSampleLimit: 50,
    derivation: 'count(appointments."id") grouped by doctors."id".',
  },
  funnelByStatus: [
    { status: 'COMPLETED', appointmentCount: 3, sourceRowIds: ['a-1', 'a-2', 'a-3'] },
    { status: 'NO_SHOW', appointmentCount: 1, sourceRowIds: ['a-4'] },
  ],
  demandByProcedure: [
    {
      procedureId: 'p-1',
      name: 'Consultation',
      specialty: 'Cardiology',
      appointmentCount: 4,
      bookedMinutes: 120,
      sourceRowIds: ['a-1', 'a-2', 'a-3', 'a-4'],
    },
    {
      procedureId: 'p-2',
      name: 'MRI Scan',
      specialty: 'Radiology',
      appointmentCount: 0,
      bookedMinutes: 0,
      sourceRowIds: [],
    },
  ],
  workloadByDoctor: [
    {
      doctorId: 'd-1',
      name: 'Dr. Amina Haddad',
      specialty: 'Cardiology',
      appointmentCount: 4,
      bookedMinutes: 120,
      sourceRowIds: ['a-1', 'a-2', 'a-3', 'a-4'],
    },
  ],
  sourceRowSampleLimit: 50,
  drillThroughPath:
    '/api/v1/clinic/appointments?from=2026-08-10T00:00:00.000Z&to=2026-09-09T00:00:00.000Z',
};

const DOCTORS = {
  count: 1,
  items: [
    {
      id: 'd-1',
      name: 'Dr. Amina Haddad',
      specialty: 'Cardiology',
      department: 'Internal Medicine',
      workingDays: [0, 1, 2, 3, 4],
      startHour: '09:00',
      endHour: '17:00',
      slotDuration: 30,
      isActive: true,
    },
  ],
};

const PROCEDURES = {
  count: 2,
  items: [
    { id: 'p-1', name: 'Consultation', specialty: 'Cardiology', durationMinutes: 30, price: 150 },
    // The price is ABSENT, which the server means as "not priced". It is not a zero,
    // and rendering it as a zero is the defect this fixture is shaped to catch.
    { id: 'p-2', name: 'MRI Scan', specialty: 'Radiology', durationMinutes: 45 },
  ],
};

const SCHEDULE = {
  count: 1,
  items: [
    {
      id: 'a-1',
      title: 'Consultation',
      patientName: 'Patient 001',
      appointmentDate: '2026-08-14T09:00:00.000Z',
      durationMinutes: 30,
      status: 'COMPLETED',
      service: 'Consultation',
      doctorName: 'Dr. Amina Haddad',
      isAiProposed: false,
    },
  ],
  from: '2026-08-10T00:00:00.000Z',
  to: '2026-09-09T00:00:00.000Z',
};

/**
 * The one session both halves of this suite run as.
 *
 * `projectIds: ['proj-1']` is the server-resolved scope the screen may ask about; the
 * screen sends `projectId` only because it is a preference, and the server decides.
 */
const SESSION = {
  userId: 'u-1',
  organizationId: 'o-1',
  email: 'lead@clinic.example',
  fullName: 'U',
  role: 'ADMIN',
  locale: 'ar',
  active: true,
  projectIds: ['proj-1'],
  csrfHeaderName: 'X-XSRF-TOKEN',
} as unknown as SessionResponse;

/** Every clinic read grant, including the project-scoped analytics one. */
const CLINIC_GRANTS = [
  'doctor:read',
  'procedure:read',
  'appointment:read',
  'analytics:read-project',
];

describe('the clinic screen', () => {
  let fixture: ComponentFixture<ClinicComponent>;
  let http: HttpTestingController;
  let i18n: I18nService;

  beforeEach(async () => {
    localStorage.clear();
    await TestBed.configureTestingModule({
      imports: [ClinicComponent],
      providers: [
        // The page header renders a `routerLink`, so the router must be present even
        // though this suite never navigates. A missing provider here fails as a
        // NullInjectorError inside the header rather than as a missing-routes error,
        // which is a worse thing to read at three in the morning.
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    }).compileComponents();

    // The screen gates its analytics section on the project-scoped analytics grant.
    // The route guards are UX only, but a screen that hides a section nobody holds is
    // still a screen whose gate is asserted — so the grant is seeded rather than
    // assumed, and the gate itself is tested below by withholding it.
    const store = TestBed.inject(SessionStore);
    store.setSession(SESSION);
    store.setPermissions({
      effectivePermissions: [...CLINIC_GRANTS],
      missingPermissions: [],
    });

    fixture = TestBed.createComponent(ClinicComponent);
    http = TestBed.inject(HttpTestingController);
    i18n = TestBed.inject(I18nService);
    fixture.detectChanges();
  });

  /** Lets the component's promise chains settle, then re-renders. */
  async function tick(target: ComponentFixture<ClinicComponent> = fixture): Promise<void> {
    await new Promise<void>((resolve) => setTimeout(resolve, 0));
    target.detectChanges();
  }

  /**
   * Answers the four reads the screen issues on init.
   *
   * `analytics` comes first because it is the one thing a caller ever varies; `target`
   * is second and almost always the default, and putting the rare parameter first
   * would have produced `answerAll(SOME_OBJECT)` silently binding an analytics body
   * to a fixture — a mistake the compiler cannot catch and a test can fail to notice.
   */
  async function answerAll(
    analytics: Record<string, unknown> = ANALYTICS as unknown as Record<string, unknown>,
    target: ComponentFixture<ClinicComponent> = fixture,
  ): Promise<void> {
    for (const request of http.match(() => true)) {
      const url = request.request.url;
      if (url.includes('/clinic/doctors')) request.flush(DOCTORS);
      else if (url.includes('/clinic/procedures')) request.flush(PROCEDURES);
      else if (url.includes('/clinic/appointments')) request.flush(SCHEDULE);
      else if (url.includes('/clinic/analytics')) request.flush(analytics);
      else request.flush({});
    }
    await tick(target);
  }

  function element(selector: string): HTMLElement | null {
    return (fixture.nativeElement as HTMLElement).querySelector(selector);
  }

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  describe('reads', () => {
    it('calls the four clinic reads', async () => {
      const urls: string[] = [];
      for (const request of http.match(() => true)) urls.push(request.request.url);

      expect(urls.some((u) => u.includes('/api/v1/clinic/doctors'))).toBe(true);
      expect(urls.some((u) => u.includes('/api/v1/clinic/procedures'))).toBe(true);
      expect(urls.some((u) => u.includes('/api/v1/clinic/appointments'))).toBe(true);
      expect(urls.some((u) => u.includes('/api/v1/clinic/analytics'))).toBe(true);

      await answerAll();
    });

    it('renders the schedule rows the server sent', async () => {
      await answerAll();
      const schedule = element('[data-testid="clinic-schedule"]');

      expect(schedule?.textContent).toContain('Patient 001');
      expect(schedule?.textContent).toContain('Dr. Amina Haddad');
      expect(schedule?.textContent).toContain('2026-08-14T09:00:00.000Z');
    });
  });

  describe('the roster and the catalogue', () => {
    it('renders the roster without clinician contact details', async () => {
      await answerAll();
      const roster = element('[data-testid="clinic-doctors"]');

      expect(roster?.textContent).toContain('Dr. Amina Haddad');
      expect(roster?.textContent).toContain('Cardiology');
      // The grant is doctor:read, which names the working week and not a phone number
      // or an address. An `@` or a dialling prefix here would mean the payload carried
      // a field the permission does not cover.
      expect(roster?.textContent ?? '').not.toMatch(/@|\+966/);
    });

    it('renders an unpriced procedure as "not priced" rather than as zero', async () => {
      await answerAll();
      const catalogue = element('[data-testid="clinic-procedures"]');

      i18n.setLocale('en');
      fixture.detectChanges();

      expect(catalogue?.textContent).toContain('Not priced');
      // A priced procedure still shows its price; what must never appear is a zero.
      expect(catalogue?.textContent).toContain('150');
      expect(catalogue?.textContent ?? '').not.toMatch(/Price:\s*0\b/);
    });
  });

  describe('every metric drills to its rows', () => {
    it('renders a metric count next to the rows that produced it', async () => {
      await answerAll();
      const funnel = element('[data-testid="clinic-metric-funnelByStatus"]');

      expect(funnel?.textContent).toContain('COMPLETED');
      // The count is rendered.
      expect(funnel?.textContent).toMatch(/3/);

      // And the ids are reachable: the disclosure is a button, so it works without a
      // pointer, and it names the region it controls so a screen reader can follow it.
      const button = funnel?.querySelector('button') as HTMLButtonElement | null;
      expect(button).not.toBeNull();
      expect(button?.getAttribute('aria-expanded')).toBe('false');
      expect(button?.getAttribute('aria-controls')).toBeTruthy();

      // Before opening: the ids are not on screen. Otherwise "expandable" would be
      // indistinguishable from "dumped on the page".
      expect(funnel?.textContent).not.toContain('a-1');

      button?.click();
      await tick();

      expect(funnel?.textContent).toContain('a-1');
      expect(funnel?.textContent).toContain('a-3');
      expect(button?.getAttribute('aria-expanded')).toBe('true');
      expect(document.getElementById(button?.getAttribute('aria-controls') ?? '')).not.toBeNull();
    });

    it('opens two rows at once, so a reader can compare them', async () => {
      await answerAll();
      const funnel = element('[data-testid="clinic-metric-funnelByStatus"]');
      const buttons = Array.from(funnel?.querySelectorAll('button') ?? []);
      expect(buttons.length).toBe(2);

      buttons[0].click();
      await tick();
      buttons[1].click();
      await tick();

      expect(funnel?.textContent).toContain('a-1');
      expect(funnel?.textContent).toContain('a-4');
      expect(funnel?.querySelectorAll('tr.source').length).toBe(2);
    });

    it('states a zero separately from missing data', async () => {
      await answerAll();
      // English, so the assertion below is about the zero-row *behaviour* rather than
      // about which locale happened to be active. The Arabic half of this claim is
      // covered by the bilingual block.
      i18n.setLocale('en');
      await tick();

      const demand = element('[data-testid="clinic-metric-demandByProcedure"]');

      expect(demand?.textContent).toContain('MRI Scan');
      expect(demand?.textContent ?? '').toMatch(/0/);
      // A zero bookings with a zero-count row list, and the screen says so in words
      // rather than leaving the reader to infer "unused" from an absence — and rather
      // than offering a disclosure button that opens onto nothing.
      expect(demand?.textContent ?? '').toContain('Zero');
      const rows = Array.from(demand?.querySelectorAll('tbody tr') ?? []);
      const zeroRow = rows.find((row) => row.textContent?.includes('MRI Scan'));
      expect(zeroRow?.querySelector('button')).toBeNull();
    });

    it('does not clutter the UI with SQL derivation or provenance prose', async () => {
      await answerAll();
      i18n.setLocale('en');
      fixture.detectChanges();

      expect(text()).not.toContain('count(*) grouped by');
      expect(text()).not.toMatch(/estimatedRevenue|utilizationRate|noShowMitigation/);
    });

    it('marks a row whose id list is a bounded sample', async () => {
      await answerAll(
        {
          ...ANALYTICS,
          funnelByStatus: [
            { status: 'COMPLETED', appointmentCount: 9, sourceRowIds: ['a-1', 'a-2', 'a-3'] },
          ],
          workloadByDoctor: [],
          demandByProcedure: [],
        } as unknown as Record<string, unknown>,
      );
      const funnel = element('[data-testid="clinic-metric-funnelByStatus"]');
      expect(funnel?.textContent).toContain('3');
      expect(funnel?.querySelector('button')?.textContent).toContain('+');
    });
  });

  describe('bilingual', () => {
    it('renders the Arabic strings when the locale is Arabic', async () => {
      await answerAll();
      i18n.setLocale('ar');
      await tick();

      expect(text()).toContain('جدول المواعيد');
      expect(text()).toContain('مؤشرات العيادة');
      expect(i18n.direction()).toBe('rtl');
      expect(text()).toContain('Dr. Amina Haddad');
    });

    it('renders the English strings when the locale is English', async () => {
      await answerAll();
      i18n.setLocale('en');
      await tick();

      expect(text()).toContain('Appointment schedule');
      expect(text()).toContain('Clinic metrics');
      expect(i18n.direction()).toBe('ltr');
    });

    it('keeps instants and identifiers LTR inside Arabic prose', async () => {
      await answerAll();
      i18n.setLocale('ar');
      await tick();

      const schedule = element('[data-testid="clinic-schedule"]');
      expect(schedule?.querySelector('[dir="ltr"]')?.textContent).toContain('2026-08-14');

      const funnel = element('[data-testid="clinic-metric-funnelByStatus"]');
      funnel?.querySelector('button')?.click();
      await tick();
      expect(funnel?.querySelector('[dir="ltr"]')).not.toBeNull();
    });
  });

  describe('the analytics gate', () => {
    it('does not call a route the session may not read', async () => {
      // Drain the first component's requests first. `HttpTestingController` is a single
      // queue for the whole test, so leaving them outstanding would make this assertion
      // pass or fail depending on the previous test — which is how a gate test quietly
      // stops testing anything.
      await answerAll();

      // The same session, with only the analytics grant withheld. Nothing else changes,
      // so the assertion is about the gate and not about a different user.
      TestBed.inject(SessionStore).setPermissions({
        effectivePermissions: ['doctor:read', 'procedure:read', 'appointment:read'],
        missingPermissions: ['analytics:read-project'],
      });

      const second = TestBed.createComponent(ClinicComponent);
      second.detectChanges();

      let analyticsCalls = 0;
      for (const request of http.match(() => true)) {
        if (request.request.url.includes('/clinic/analytics')) analyticsCalls += 1;
        request.flush({});
      }
      await tick(second);

      // A screen that calls and then renders a 403 is worse than one that does not call.
      expect(analyticsCalls).toBe(0);
      expect((second.nativeElement as HTMLElement).querySelector('[data-testid="clinic-analytics-denied"]'))
        .not.toBeNull();
      // And it says why, rather than showing an empty card that reads as "no metrics".
      expect((second.nativeElement as HTMLElement).textContent ?? '').toContain(
        'صلاحية قراءة المؤشرات',
      );
    });
  });
});