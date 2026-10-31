/**
 * `/app/clinic/calendar` — the schedule, grouped by day.
 *
 * ## The claims worth protecting
 *
 * 1. **The window on screen is the server's.** The screen asks for a window and the server
 *    resolves one; the pair rendered is the one echoed in the response. A reader comparing
 *    the calendar against the metrics has to be comparing the same range.
 * 2. **No grid is drawn.** A calendar grid is normally computed from working hours and slot
 *    lengths — clinic policy the client was not given. The suite asserts the refusal is on
 *    screen, because a silent absence would read as "the client forgot".
 * 3. **Pagination is keyset, and the window does not move under it.** "Load more" sends the
 *    server's cursor inside the **same** window as the first page. Recomputing `to` per
 *    page would move the end of the window by milliseconds and quietly turn "load more"
 *    into "load the same rows again".
 * 4. **The end of the list is stated.** An absent `nextCursor` means the server issued no
 *    cursor — which is what "the last page" means here — and the screen says so rather
 *    than dropping the button without explanation.
 */
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
/**
 * The locale is a persisted preference (`I18nService` writes it to `localStorage`).
 * Another suite in this worker asserts that the DEFAULT locale is Arabic, so leaving
 * 'en' behind on the way out would make that suite fail for a reason that has nothing
 * to do with it.
 */
afterEach(() => {
  localStorage.clear();
});

import { I18nService } from '../../core/i18n/i18n.service';
import { SessionStore } from '../../core/auth/session.store';
import type { SessionResponse } from '../../core/api/generated';
import { ClinicCalendarComponent } from './clinic-calendar.component';

const SESSION = {
  userId: 'u-1',
  organizationId: 'o-1',
  email: 'front@clinic.example',
  fullName: 'U',
  role: 'EMPLOYEE',
  locale: 'ar',
  active: true,
  projectIds: ['proj-1'],
  csrfHeaderName: 'X-XSRF-TOKEN',
} as unknown as SessionResponse;

const DAY_ONE = {
  count: 2,
  items: [
    {
      id: 'a-1',
      title: 'Consultation',
      patientName: 'Layla Hassan',
      appointmentDate: '2026-09-01T09:00:00.000Z',
      durationMinutes: 30,
      status: 'COMPLETED',
      service: 'Consultation',
      doctorName: 'Dr. Amina Haddad',
      isAiProposed: false,
    },
    {
      id: 'a-2',
      patientName: 'Sara Ahmed',
      appointmentDate: '2026-09-01T11:00:00.000Z',
      durationMinutes: 45,
      status: 'BOOKED',
      service: 'MRI Scan',
      doctorName: 'Dr. Amina Haddad',
      isAiProposed: true,
    },
  ],
  from: '2026-08-02T00:00:00.000Z',
  to: '2026-09-01T00:00:00.000Z',
};

const DAY_TWO = {
  count: 1,
  items: [
    {
      id: 'a-3',
      patientName: 'Omar Nasser',
      appointmentDate: '2026-09-03T08:00:00.000Z',
      durationMinutes: 20,
      status: 'BOOKED',
      doctorName: 'Dr. Bilal Idris',
      isAiProposed: false,
    },
  ],
  from: '2026-08-02T00:00:00.000Z',
  to: '2026-09-01T00:00:00.000Z',
};

describe('the clinic calendar', () => {
  let fixture: ComponentFixture<ClinicCalendarComponent>;
  let http: HttpTestingController;
  let i18n: I18nService;

  beforeEach(async () => {
    localStorage.clear();
    await TestBed.configureTestingModule({
      imports: [ClinicCalendarComponent],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    const store = TestBed.inject(SessionStore);
    store.setSession(SESSION);
    store.setPermissions({ effectivePermissions: ['appointment:read'], missingPermissions: [] });

    i18n = TestBed.inject(I18nService);
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ClinicCalendarComponent);
    fixture.detectChanges();
  });

  async function tick(): Promise<void> {
    await new Promise<void>((resolve) => setTimeout(resolve, 0));
    fixture.detectChanges();
  }

  function element(selector: string): HTMLElement | null {
    return (fixture.nativeElement as HTMLElement).querySelector(selector);
  }

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  it('reads the schedule route with a window, and echoes the window the server used', async () => {
    const request = http.expectOne((r) => r.url.endsWith('/api/v1/clinic/appointments'));
    expect(request.request.method).toBe('GET');
    expect(request.request.params.has('from')).toBe(true);
    expect(request.request.params.has('to')).toBe(true);
    request.flush(DAY_ONE);
    await tick();

    // The SERVER's pair, not the pair this screen sent.
    const window = element('[data-testid="clinic-calendar-window"]');
    expect(window?.textContent).toContain('2026-08-02T00:00:00.000Z');
    expect(window?.textContent).toContain('2026-09-01T00:00:00.000Z');
  });

  it('groups the rows the server returned by day, in one section per day', async () => {
    http.expectOne((r) => r.url.endsWith('/api/v1/clinic/appointments')).flush(DAY_ONE);
    await tick();

    const sections = (fixture.nativeElement as HTMLElement).querySelectorAll('section.fe-card');
    expect(sections.length).toBe(1);
    expect(sections[0]?.textContent).toContain('Layla Hassan');
    expect(sections[0]?.textContent).toContain('Sara Ahmed');
  });

  it('marks an AI-proposed appointment in words', async () => {
    http.expectOne((r) => r.url.endsWith('/api/v1/clinic/appointments')).flush(DAY_ONE);
    await tick();
    i18n.setLocale('en');
    await tick();

    expect(text()).toContain('AI-proposed');
  });

  it('draws no grid and says so, rather than leaving the absence unexplained', async () => {
    http.expectOne((r) => r.url.endsWith('/api/v1/clinic/appointments')).flush(DAY_ONE);
    await tick();
    i18n.setLocale('en');
    await tick();

    expect(text()).toContain('draws no appointment grid');
    expect(text()).toContain('not ones interpolated between them');
  });

  it('states the day boundary it is using, because the server sends no clinic timezone', async () => {
    http.expectOne((r) => r.url.endsWith('/api/v1/clinic/appointments')).flush(DAY_ONE);
    await tick();
    i18n.setLocale('en');
    await tick();

    expect(text()).toContain('Day boundaries follow the timezone this screen reads in');
  });

  it('appends the next page by the server cursor, inside the same window', async () => {
    const first = http.expectOne((r) => r.url.endsWith('/api/v1/clinic/appointments'));
    const from = first.request.params.get('from');
    const to = first.request.params.get('to');
    first.flush({ ...DAY_ONE, nextCursor: 'cursor-1' });
    await tick();

    const more = element('[data-testid="clinic-calendar-more"]') as HTMLButtonElement | null;
    expect(more).not.toBeNull();
    more?.click();
    await tick();

    const next = http.expectOne((r) => r.url.endsWith('/api/v1/clinic/appointments'));
    expect(next.request.params.get('cursor')).toBe('cursor-1');
    // The window is fixed by the first load. Moving it here is the bug this asserts.
    expect(next.request.params.get('from')).toBe(from);
    expect(next.request.params.get('to')).toBe(to);
    next.flush(DAY_TWO);
    await tick();

    expect(text()).toContain('Omar Nasser');
  });

  it('says the list has ended when the server issues no cursor', async () => {
    http.expectOne((r) => r.url.endsWith('/api/v1/clinic/appointments')).flush(DAY_ONE);
    await tick();
    i18n.setLocale('en');
    await tick();

    expect(element('[data-testid="clinic-calendar-more"]')).toBeNull();
    expect(element('[data-testid="clinic-calendar-no-more"]')?.textContent).toContain(
      'That is the last page',
    );
  });

  it('distinguishes an empty window from a failure', async () => {
    http.expectOne((r) => r.url.endsWith('/api/v1/clinic/appointments')).flush({
      count: 0,
      items: [],
      from: '2026-08-02T00:00:00.000Z',
      to: '2026-09-01T00:00:00.000Z',
    });
    await tick();
    i18n.setLocale('en');
    await tick();

    expect(text()).toContain('No appointments in this window');
    expect(element('[data-testid="clinic-calendar-no-more"]')).toBeNull();
    http.verify();
  });

  it('links every row to its appointment', async () => {
    http.expectOne((r) => r.url.endsWith('/api/v1/clinic/appointments')).flush(DAY_ONE);
    await tick();

    expect(element('a[href="/app/clinic/appointments/a-1"]')).not.toBeNull();
    expect(element('a[href="/app/clinic/appointments/a-2"]')).not.toBeNull();
  });
});
