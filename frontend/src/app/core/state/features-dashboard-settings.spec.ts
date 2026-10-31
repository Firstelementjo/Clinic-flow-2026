/**
 * The dashboard and the settings screen.
 *
 * The dashboard is a **replica** of the ToothLogic All-in-One dashboard, so the
 * tests here are about the *states* and the *honesty* of its figures rather
 * than about the pixels. Every figure on that screen comes from a route in
 * `tests/contracts/openapi.json`, which `api-contract.spec.ts` asserts, and a
 * figure that could not be read is drawn as an absence rather than as a zero.
 *
 * A note on what this screen is *not*: the frozen React dashboard fetched
 * `/clinic/analytics` and rendered hard-coded clinic figures whenever that
 * request failed or had not arrived. No such route exists in the target
 * contract, so those fallbacks are not reproduced here.
 *
 * The dashboard's own copy is translated, and the tests assert the Arabic,
 * because Arabic is the authoring language and the landing screen is the first
 * thing a clinician reads.
 */
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { DashboardComponent } from '../../features/dashboard/dashboard.component';
import { SettingsComponent } from '../../features/settings/settings.component';
import { SessionStore } from '../auth/session.store';

const SESSION = {
  userId: 'u-1',
  organizationId: 'o-1',
  email: 'a@b.c',
  fullName: 'ليلى المنصور',
  role: 'ADMIN',
  locale: 'ar',
  active: true,
  projectIds: ['p-1'],
  csrfHeaderName: 'X-XSRF-TOKEN',
};

const REPORT_URL = '/api/v1/clinic/reports';

/** A report with every metric in it. */
const REPORT = {
  windowStart: '2026-10-22T00:00:00',
  windowEnd: '2026-10-22T23:59:59',
  windowLabel: 'custom',
  financials: [
    {
      currency: 'JOD',
      totalInvoiced: 1200,
      totalCollected: 900,
      totalDiscount: 0,
      totalBalanceDue: 300,
      invoiceCount: 4,
      paymentCount: 3,
    },
  ],
  doctorProductivity: [],
  appointments: {
    totalAppointments: 18,
    completedCount: 10,
    confirmedCount: 5,
    cancelledCount: 2,
    noShowCount: 1,
    cancellationRatePercent: 11.1,
  },
  patients: { totalPatients: 64, newPatientsInWindow: 7 },
  patientAgeDistribution: { age0to25: 20, age26to50: 30, age50Plus: 14 },
};

/**
 * A report that carries no metrics at all.
 *
 * Not a report of zeros: a report with no `appointments`, no `patients`, no
 * `financials` and no age distribution. The screen must draw every one of
 * those as an absence, because "the server sent nothing" and "the server sent
 * a zero" are different facts.
 */
const SPARSE_REPORT = {
  windowStart: '2026-10-22T00:00:00',
  windowEnd: '2026-10-22T23:59:59',
  windowLabel: 'custom',
  financials: [],
  doctorProductivity: [],
};

const PROCEDURES = {
  count: 3,
  items: [
    { id: 'pr-1', name: 'Scaling', isActive: true },
    { id: 'pr-2', name: 'Filling', isActive: true },
    { id: 'pr-3', name: 'Retired procedure', isActive: false },
  ],
};

const DOCTORS = {
  count: 3,
  items: [
    { id: 'd-1', name: 'Dr Amira' },
    { id: 'd-2', name: 'Dr Bilal' },
    { id: 'd-3', name: 'Dr Salma' },
  ],
};

const APPOINTMENTS = {
  count: 1,
  items: [
    {
      id: 'apt-1',
      patientName: 'Layla Hassan',
      doctorName: 'Dr Amira',
      service: 'Scaling',
      appointmentDate: '2026-10-23T09:30:00',
    },
  ],
};

const STATE = {
  organizationId: 'o-1',
  serverTime: '2026-10-06T10:00:00',
  unreadCount: 3,
  notifications: [
    {
      id: 'n-1',
      title: 'Conversation assigned',
      read: false,
      createdAt: '2026-10-06T09:55:00',
    },
  ],
  projects: ['p-1'],
  realtime: { localSubscribers: 1, connectionsOpened: 4, resyncsSent: 2 },
};

/**
 * Signs a principal in with an effective permission set.
 *
 * The dashboard reads its permissions from the same store the guard reads, so
 * this is the server's own answer to "what can this person do" rather than a
 * guess made by the test.
 */
function signedIn(list: string[], overrides: Partial<typeof SESSION> = {}): void {
  const store = TestBed.inject(SessionStore);
  store.setSession({ ...SESSION, ...overrides });
  store.setPermissions({
    subject: {},
    role: 'ADMIN',
    active: true,
    projectIds: SESSION.projectIds,
    projects: [],
    effectivePermissions: list,
    missingPermissions: [],
  });
}

function permissions(list: string[]): void {
  signedIn(list);
}

async function settle(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('DashboardComponent', () => {
  let fixture: ComponentFixture<DashboardComponent>;
  let http: HttpTestingController;

  beforeEach(async () => {
    localStorage.clear();
    TestBed.resetTestingModule();
    await TestBed.configureTestingModule({
      imports: [DashboardComponent],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(DashboardComponent);
  });

  afterEach(() => http.verify());

  function detect(): void {
    fixture.detectChanges();
  }

  /** The report request whose window is one whole day: the Performance card. */
  function isSingleDay(request: TestRequest): boolean {
    const start = request.request.params.get('start') ?? '';
    const end = request.request.params.get('end') ?? '';
    return start.length >= 10 && start.slice(0, 10) === end.slice(0, 10);
  }

  /**
   * Answers every request the dashboard has open, and returns the report
   * requests it matched.
   *
   * The performance report and the chart's buckets are the same route with
   * different windows, so they are told apart by the window: the performance
   * one covers a single day, a yearly bucket covers a month.
   */
  function answer(
    options: {
      readonly report?: unknown;
      readonly chartReport?: unknown;
      readonly procedures?: unknown;
      readonly doctors?: unknown;
      readonly appointments?: unknown;
    } = {},
  ): TestRequest[] {
    const performance = options.report ?? REPORT;
    const chart = options.chartReport ?? performance;
    const reports: TestRequest[] = [];
    for (const request of http.match(() => true)) {
      if (request.request.url === REPORT_URL) {
        reports.push(request);
        request.flush(isSingleDay(request) ? performance : chart);
      } else if (request.request.url === '/api/v1/clinic/procedures') {
        request.flush(options.procedures ?? PROCEDURES);
      } else if (request.request.url === '/api/v1/clinic/doctors') {
        request.flush(options.doctors ?? DOCTORS);
      } else if (request.request.url === '/api/v1/clinic/appointments') {
        request.flush(options.appointments ?? APPOINTMENTS);
      } else {
        request.flush({});
      }
    }
    return reports;
  }

  it('asks for no clinic route at all when the caller may read none of them', () => {
    permissions(['conversation:read']);
    detect();
    // A 403 on a card the server never offered is noise, so the dashboard does
    // not offer itself one.
    http.expectNone((r) => r.url.startsWith('/api/v1/clinic/'));
  });

  it('asks for the reports and the schedule, but not the catalogues it may not read', () => {
    permissions(['appointment:read']);
    detect();
    // One performance report and the twelve monthly buckets of the yearly chart.
    expect(http.match((r) => r.url === REPORT_URL).length).toBe(13);
    http.expectOne((r) => r.url === '/api/v1/clinic/appointments');
    http.expectNone((r) => r.url === '/api/v1/clinic/procedures');
    http.expectNone((r) => r.url === '/api/v1/clinic/doctors');
    answer();
  });

  it('asks for the catalogues too when their permissions are held', () => {
    permissions(['appointment:read', 'procedure:read', 'doctor:read']);
    detect();
    http.expectOne((r) => r.url === '/api/v1/clinic/procedures');
    http.expectOne((r) => r.url === '/api/v1/clinic/doctors');
    http.expectOne((r) => r.url === '/api/v1/clinic/appointments');
    answer();
  });

  it('shows a loading state before anything has arrived', () => {
    permissions(['appointment:read']);
    detect();
    const text = fixture.nativeElement.textContent ?? '';
    expect(text).toContain('جارٍ تحميل الأداء');
    expect(text).toContain('جارٍ تحميل الإيرادات');
    answer();
  });

  it('renders the figures the routes returned, and nothing else', async () => {
    permissions(['appointment:read', 'procedure:read', 'doctor:read']);
    detect();
    answer();
    await settle();
    detect();

    const page = fixture.nativeElement as HTMLElement;
    const values = [...page.querySelectorAll('.tl-stat-value')].map((node) =>
      (node.textContent ?? '').trim(),
    );
    // Clinics (the session's projects), active services, appointments, doctors,
    // patients, revenue.
    expect(values[0]).toBe('1');
    expect(values[1]).toBe('2');
    expect(values[2]).toBe('18');
    expect(values[3]).toBe('3');
    expect(values[4]).toBe('64');
    expect(values[5]).toContain('1,200');

    const ages = [...page.querySelectorAll('.tl-age-number')].map((node) =>
      (node.textContent ?? '').trim(),
    );
    expect(ages).toEqual(['20', '30', '14']);

    // The upcoming schedule names the people the server named.
    expect(page.textContent).toContain('Layla Hassan');
    expect(page.textContent).toContain('Dr Amira');
  });

  it('draws an absence, not a zero, for a metric the server did not send', async () => {
    permissions(['appointment:read', 'procedure:read', 'doctor:read']);
    detect();
    answer({ report: SPARSE_REPORT, chartReport: SPARSE_REPORT });
    await settle();
    detect();

    const page = fixture.nativeElement as HTMLElement;
    const values = [...page.querySelectorAll('.tl-stat-value')].map((node) =>
      (node.textContent ?? '').trim(),
    );
    // Appointments, patients and revenue are all absent in the sparse report.
    expect(values[2]).toBe('—');
    expect(values[4]).toBe('—');
    expect(values[5]).toBe('—');
    const ages = [...page.querySelectorAll('.tl-age-number')].map((node) =>
      (node.textContent ?? '').trim(),
    );
    expect(ages).toEqual(['—', '—', '—']);
  });

  it('says the chart has no revenue rather than drawing a flat line', async () => {
    permissions(['appointment:read']);
    detect();
    answer({ chartReport: SPARSE_REPORT });
    await settle();
    detect();

    const page = fixture.nativeElement as HTMLElement;
    expect(page.querySelector('path.tl-chart-line')).toBeNull();
    expect(page.textContent).toContain('لم يُسجَّل أي إيراد في هذه الفترة');
  });

  it('renders an empty state, not an error, when there is nothing scheduled', async () => {
    permissions(['appointment:read']);
    detect();
    answer({ appointments: { count: 0, items: [] } });
    await settle();
    detect();

    const page = fixture.nativeElement as HTMLElement;
    expect(page.querySelector('[data-testid="dashboard-upcoming-empty"]')).not.toBeNull();
    expect(page.textContent).toContain('لا توجد مواعيد خلال الأيام السبعة القادمة');
    expect(page.textContent).not.toContain('تعذّر تحميل');
  });

  it('renders an error with the correlation id, keeping the rest of the screen', async () => {
    permissions(['appointment:read']);
    detect();
    const reports = http.match((r) => r.url === REPORT_URL);
    const performance = reports.find(isSingleDay);
    expect(performance).toBeDefined();
    for (const request of reports) {
      if (request === performance) {
        request.flush(
          { code: 'INTERNAL', message: 'boom', requestId: 'corr-42' },
          { status: 500, statusText: 'Server Error' },
        );
      } else {
        request.flush(SPARSE_REPORT);
      }
    }
    http.expectOne((r) => r.url === '/api/v1/clinic/appointments').flush(APPOINTMENTS);
    await settle();
    detect();

    const page = fixture.nativeElement as HTMLElement;
    expect(page.querySelector('[role="alert"]')).not.toBeNull();
    expect(page.textContent).toContain('تعذّر تحميل تقرير أداء العيادة');
    expect(page.textContent).toContain('corr-42');
    // The server's own message is written for a log, not for a clinician.
    expect(page.textContent).not.toContain('boom');
    // One failing card must not blank the screen an operator is reading.
    expect(page.textContent).toContain('إجمالي المواعيد');
  });

  it('says the server decided, when the report read is refused', async () => {
    permissions(['appointment:read']);
    detect();
    const reports = http.match((r) => r.url === REPORT_URL);
    const performance = reports.find(isSingleDay);
    expect(performance).toBeDefined();
    for (const request of reports) {
      if (request === performance) {
        request.flush(
          { code: 'ACCESS_DENIED', message: 'forbidden', requestId: 'corr-7' },
          { status: 403, statusText: 'Forbidden' },
        );
      } else {
        request.flush(SPARSE_REPORT);
      }
    }
    http.expectOne((r) => r.url === '/api/v1/clinic/appointments').flush(APPOINTMENTS);
    await settle();
    detect();

    const page = fixture.nativeElement as HTMLElement;
    expect(page.textContent).toContain('لا تملك صلاحية قراءة تقرير أداء العيادة');
  });

  it('states that some cards are withheld by permission, rather than looking broken', () => {
    permissions(['conversation:read']);
    detect();
    answer();
    const page = fixture.nativeElement as HTMLElement;
    expect(page.querySelector('[data-testid="dashboard-withheld"]')).not.toBeNull();
    expect(page.textContent).toContain('بعض الأرقام لا تظهر');
  });

  it('does not refetch until the date is submitted', async () => {
    permissions(['appointment:read']);
    detect();
    for (const request of http.match((r) => r.url === REPORT_URL)) request.flush(REPORT);
    http.expectOne((r) => r.url === '/api/v1/clinic/appointments').flush(APPOINTMENTS);
    await settle();
    detect();

    // Changing the date in the input is not applying it.
    const input = fixture.nativeElement.querySelector('#dashboard-select-date') as HTMLInputElement;
    input.value = '2026-09-15';
    input.dispatchEvent(new Event('change'));
    detect();
    http.expectNone((r) => r.url === REPORT_URL);

    // Submitting applies the whole of that day.
    const submit = [...fixture.nativeElement.querySelectorAll('button')].find(
      (node) => (node.textContent ?? '').trim() === 'تطبيق',
    ) as HTMLButtonElement;
    expect(submit).toBeDefined();
    submit.click();
    detect();

    const second = http.match((r) => r.url === REPORT_URL);
    expect(second.length).toBe(13);
    const applied = second.find(isSingleDay);
    expect(applied?.request.params.get('start')).toBe('2026-09-15T00:00:00');
    expect(applied?.request.params.get('end')).toBe('2026-09-15T23:59:59');
    for (const request of second) request.flush(REPORT);
    await settle();
  });

  it('re-reads the chart when the period changes', async () => {
    permissions(['appointment:read']);
    detect();
    for (const request of http.match((r) => r.url === REPORT_URL)) request.flush(REPORT);
    http.expectOne((r) => r.url === '/api/v1/clinic/appointments').flush(APPOINTMENTS);
    await settle();
    detect();

    const trigger = fixture.nativeElement.querySelector('.tl-btn-year') as HTMLButtonElement;
    trigger.click();
    detect();
    const options = [...fixture.nativeElement.querySelectorAll('.tl-period-option')] as HTMLButtonElement[];
    const quarter = options.find((node) => (node.textContent ?? '').trim() === 'الربع');
    expect(quarter).toBeDefined();
    quarter!.click();
    detect();

    // Three monthly buckets instead of twelve. The period selector changes the chart's
    // span only -- the Performance section is still the day in the date filter, so
    // re-reading it here would be a request whose answer cannot change.
    const second = http.match((r) => r.url === REPORT_URL);
    expect(second.length).toBe(3);
    for (const request of second) request.flush(REPORT);
    await settle();
  });

  it('greets nobody it does not know', async () => {
    signedIn(['appointment:read'], { fullName: '' });
    detect();
    answer();
    await settle();
    detect();
    const page = fixture.nativeElement as HTMLElement;
    // An absence, not a name the client invented.
    expect(page.querySelector('.tl-greeting-bold')?.textContent?.trim()).toBe('—');
  });

  it('has no hard-coded clinic figures from the frozen client', () => {
    permissions(['appointment:read', 'procedure:read', 'doctor:read']);
    detect();
    answer();
    const text = fixture.nativeElement.textContent ?? '';
    // The frozen dashboard rendered these as literal fallbacks whenever
    // `/clinic/analytics` failed. Reproducing them would be a mock that ships.
    expect(text).not.toContain('AI Booking Conversion');
    expect(text).not.toContain('Estimated Revenue');
    expect(text).not.toContain('Front-desk hours saved');
    expect(text).not.toContain('Dr. Sarah Al-Ahmad');
    expect(text).not.toContain('Ms. Shanze Mirza');
  });
});

describe('SettingsComponent', () => {
  let fixture: ComponentFixture<SettingsComponent>;
  let http: HttpTestingController;

  beforeEach(async () => {
    localStorage.clear();
    TestBed.resetTestingModule();
    await TestBed.configureTestingModule({
      imports: [SettingsComponent],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(SettingsComponent);
  });

  afterEach(() => http.verify());

  it('shows the account and permission panel to any signed-in caller', async () => {
    // `/api/v1/permissions/me` needs only a live session, so this panel is the
    // server's own answer to "what can this person actually do".
    permissions(['conversation:read']);
    fixture.detectChanges();
    const text = fixture.nativeElement.textContent ?? '';
    expect(text).toContain('حسابك');
    expect(text).toContain('صلاحياتك الفعلية');
    expect(text).toContain('ليلى المنصور');
    expect(text).toContain('conversation:read');
    http.expectOne((r) => r.url === '/api/v1/status').flush({ status: 'UP' });
    await settle();
  });

  it('asks for the liveness probe, which needs no permission at all', () => {
    permissions(['conversation:read']);
    fixture.detectChanges();
    http.expectOne((r) => r.url === '/api/v1/status').flush({ status: 'UP', version: 'v1' });
  });

  it('does not ask for operations read models the caller cannot read', () => {
    permissions(['conversation:read']);
    fixture.detectChanges();
    http.expectOne((r) => r.url === '/api/v1/status').flush({ status: 'UP' });
    http.expectNone((r) => r.url === '/api/v1/operations/jobs');
    http.expectNone((r) => r.url === '/api/v1/admin/overview');
    http.expectNone((r) => r.url === '/api/v1/admin/audit/security');
  });

  it('reports "no attempts in the window" as its own sentence, not as 0%', async () => {
    // The backend uses -1 for "no attempts", and FR-7.6's whole point is that this
    // is not a zero rate.
    permissions(['conversation:read', 'system-health:read']);
    fixture.detectChanges();
    http.expectOne((r) => r.url === '/api/v1/status').flush({ status: 'UP' });
    http
      .expectOne((r) => r.url === '/api/v1/operations/jobs')
      .flush({ dueNow: 0, failureRate: -1, openDeadLetters: 0, oldestPendingSeconds: 0 });
    http.expectOne((r) => r.url === '/api/v1/realtime/state').flush(STATE);
    http
      .expectOne((r) => r.url === '/api/v1/operations/realtime')
      .flush({ document: { health: { localSubscribers: 1, multiInstanceCapable: true } } });
    await settle();
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('لا توجد محاولات في النافذة');
  });

  it('names the route it read, so an operator can quote it', async () => {
    permissions(['conversation:read']);
    fixture.detectChanges();
    http.expectOne((r) => r.url === '/api/v1/status').flush({ status: 'UP', version: 'v1' });
    await settle();
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('GET /api/v1/status');
  });
});
