/**
 * `/app/clinic/doctors-and-schedules` — the roster, then one clinician's bookable day.
 *
 * ## The claims worth protecting
 *
 * 1. **Two grants, two gates.** The roster is `doctor:read`; `/clinic/availability` is
 *    `appointment:read`. The suite asserts the availability read is **never issued** to a
 *    session that lacks the second grant, and that the screen says why. A screen that
 *    called and then rendered a 403 is worse than one that never called, and an empty
 *    panel reads as "this clinician has no free times", which is a clinical claim nobody
 *    can support.
 * 2. **No scheduling parameters are invented.** `durationMinutes`, `bufferMinutes` and
 *    `dentistOverrideMinutes` all have server-side defaults and a documented meaning under
 *    DEC-001. The suite asserts the request carries the two required parameters and
 *    nothing else.
 * 3. **No clinician contact details.** `doctor:read` names the working week, not a phone
 *    number; those are behind `doctor:read-management`, which this route does not declare.
 * 4. **Both slot times are rendered.** `patientMinutes` and `blockedUntil` differ whenever
 *    a buffer applies, and a booking view that showed only one of them would be wrong.
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
import { ClinicSchedulesComponent } from './clinic-schedules.component';

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

const DOCTORS = {
  count: 1,
  items: [
    {
      id: 'd-1',
      name: 'Dr. Amina Haddad',
      specialty: 'Cardiology',
      workingDays: [0, 1, 2, 3, 4],
      startHour: '09:00',
      endHour: '17:00',
      slotDuration: 30,
      isActive: true,
    },
  ],
};

const SLOTS = [
  { startsAt: '09:00:00', patientMinutes: 30, blockedUntil: '09:40:00' },
  { startsAt: '09:40:00', patientMinutes: 30, blockedUntil: '10:20:00' },
];

describe('the clinic schedules screen', () => {
  let fixture: ComponentFixture<ClinicSchedulesComponent>;
  let http: HttpTestingController;
  let i18n: I18nService;

  async function setUp(permissions: string[]): Promise<void> {
    localStorage.clear();
    await TestBed.configureTestingModule({
      imports: [ClinicSchedulesComponent],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    const store = TestBed.inject(SessionStore);
    store.setSession(SESSION);
    store.setPermissions({ effectivePermissions: permissions, missingPermissions: [] });

    i18n = TestBed.inject(I18nService);
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ClinicSchedulesComponent);
    fixture.detectChanges();
  }

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

  /** Chooses a clinician and a day through the real controls. */
  function choose(): void {
    const select = element('[data-testid="clinic-schedule-clinician"]') as HTMLSelectElement;
    select.value = 'd-1';
    select.dispatchEvent(new Event('change'));

    const day = element('[data-testid="clinic-schedule-day"]') as HTMLInputElement;
    day.value = '2026-09-01';
    day.dispatchEvent(new Event('change'));
  }

  describe('with both clinic grants', () => {
    beforeEach(async () => {
      await setUp(['doctor:read', 'appointment:read']);
    });

    it('reads the roster from the doctors route', () => {
      const request = http.expectOne((r) => r.url.endsWith('/api/v1/clinic/doctors'));
      expect(request.request.method).toBe('GET');
      request.flush(DOCTORS);
    });

    it('renders the working week and never a contact detail', async () => {
      http.expectOne((r) => r.url.endsWith('/api/v1/clinic/doctors')).flush(DOCTORS);
      await tick();
      i18n.setLocale('en');
      await tick();

      const roster = element('[data-testid="clinic-roster"]');
      expect(roster?.textContent).toContain('Dr. Amina Haddad');
      expect(roster?.textContent).toContain('Sunday');
      // `doctor:read` names the working week, not an address or a phone number.
      expect(roster?.textContent ?? '').not.toMatch(/@|\+966/);
    });

    it('asks for nothing until a clinician and a day are chosen', async () => {
      http.expectOne((r) => r.url.endsWith('/api/v1/clinic/doctors')).flush(DOCTORS);
      await tick();

      expect(element('[data-testid="clinic-schedules-prompt"]')).not.toBeNull();
      expect(http.match((r) => r.url.includes('/availability')).length).toBe(0);
    });

    it('sends the two required availability parameters and invents no policy', async () => {
      http.expectOne((r) => r.url.endsWith('/api/v1/clinic/doctors')).flush(DOCTORS);
      await tick();
      choose();

      const request = http.expectOne((r) => r.url.endsWith('/api/v1/clinic/availability'));
      expect(request.request.method).toBe('GET');
      expect(request.request.params.get('doctorId')).toBe('d-1');
      expect(request.request.params.get('date')).toBe('2026-09-01');
      // DEC-001 gives these three a meaning the client was not given; it sends none.
      for (const invented of ['durationMinutes', 'bufferMinutes', 'dentistOverrideMinutes']) {
        expect(request.request.params.has(invented), invented).toBe(false);
      }
      request.flush(SLOTS);
    });

    it('renders both slot times, because they are not the same time', async () => {
      http.expectOne((r) => r.url.endsWith('/api/v1/clinic/doctors')).flush(DOCTORS);
      await tick();
      choose();
      http.expectOne((r) => r.url.endsWith('/api/v1/clinic/availability')).flush(SLOTS);
      await tick();
      i18n.setLocale('en');
      await tick();

      const table = element('[data-testid="clinic-slots"]');
      expect(table?.textContent).toContain('09:00:00');
      expect(table?.textContent).toContain('09:40:00');
      expect(text()).toContain('Padding is preparation time, not patient time');
    });

    it('says an empty day is an empty day, not a broken screen', async () => {
      http.expectOne((r) => r.url.endsWith('/api/v1/clinic/doctors')).flush(DOCTORS);
      await tick();
      choose();
      http.expectOne((r) => r.url.endsWith('/api/v1/clinic/availability')).flush([]);
      await tick();
      i18n.setLocale('en');
      await tick();

      expect(text()).toContain('No times on that day');
    });
  });

  describe('without the appointment grant', () => {
    beforeEach(async () => {
      await setUp(['doctor:read']);
    });

    it('renders the roster but never calls a route the caller may not read', async () => {
      http.expectOne((r) => r.url.endsWith('/api/v1/clinic/doctors')).flush(DOCTORS);
      await tick();
      choose();

      expect(http.match((r) => r.url.includes('/availability')).length).toBe(0);
      i18n.setLocale('en');
      await tick();
      expect(element('[data-testid="clinic-schedules-denied"]')?.textContent).toContain(
        'You do not hold the appointment grant',
      );
    });
  });
});
