/**
 * `/app/clinic/patients` — the list, the search, and the states around them.
 *
 * ## What is asserted, and what each assertion is defending
 *
 * 1. **The read is the documented one.** `GET /api/v1/clinic/patients`, and the term goes
 *    out as `q` — never as a client-side filter over the rows already loaded, because a
 *    client-side filter over a server-paged list silently reports "no results" for a
 *    patient who is simply on the next page.
 * 2. **A blank term is an absent parameter, not an empty one.** `?q=` is a request whose
 *    meaning is its own absence; the screen sends no `q` at all.
 * 3. **Empty and failed are different sentences.** An empty clinic says "no patients in
 *    your scope"; a search that matched nothing says "no result for that search"; a 403
 *    says the server decided. FR-7.6 requires "no data" to look different from zero, and
 *    a screen that collapses all three into one empty table reintroduces the ambiguity.
 * 4. **Direction follows the locale.** Arabic renders Arabic and the document is RTL;
 *    English renders English and it is LTR. That is asserted here rather than assumed,
 *    because an English-only test suite is how an RTL screen ships.
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
import { ClinicPatientsComponent } from './clinic-patients.component';

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

const PATIENTS = [
  {
    id: 'pat-1',
    patientNo: 'P-0001',
    displayName: 'Layla Hassan',
    givenName: 'Layla',
    familyName: 'Hassan',
    dateOfBirth: '1991-04-02',
    primaryPhone: '+966500000001',
    locale: 'ar',
    customerId: 'cus-1',
    deleted: false,
  },
  {
    id: 'pat-2',
    patientNo: 'P-0002',
    // The birth date is ABSENT, which the server means as null. It is not a zero and it
    // is not a permission: the screen has to say so rather than print an empty cell.
    displayName: 'Sara Ahmed',
    locale: 'en',
    deleted: false,
  },
];

describe('the clinic patients screen', () => {
  let fixture: ComponentFixture<ClinicPatientsComponent>;
  let http: HttpTestingController;
  let i18n: I18nService;

  beforeEach(async () => {
    localStorage.clear();
    await TestBed.configureTestingModule({
      imports: [ClinicPatientsComponent],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    const store = TestBed.inject(SessionStore);
    store.setSession(SESSION);
    store.setPermissions({
      effectivePermissions: ['appointment:read'],
      missingPermissions: [],
    });

    http = TestBed.inject(HttpTestingController);
    i18n = TestBed.inject(I18nService);
    fixture = TestBed.createComponent(ClinicPatientsComponent);
    fixture.detectChanges();
  });

  async function tick(): Promise<void> {
    await new Promise<void>((resolve) => setTimeout(resolve, 0));
    fixture.detectChanges();
  }

  async function answer(body: object): Promise<void> {
    for (const request of http.match(() => true)) request.flush(body);
    await tick();
  }

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  function element(selector: string): HTMLElement | null {
    return (fixture.nativeElement as HTMLElement).querySelector(selector);
  }

  it('reads the documented patient route, with no term when the box is empty', () => {
    const request = http.expectOne((r) => r.url.endsWith('/api/v1/clinic/patients'));
    expect(request.request.method).toBe('GET');
    // `?q=` would be a request whose meaning is its own absence.
    expect(request.request.params.has('q')).toBe(false);
    request.flush(PATIENTS);
  });

  it('sends the term the reader typed, as q', async () => {
    await answer(PATIENTS);

    const input = element('#fe-clinic-patient-search') as HTMLInputElement;
    input.value = 'Hassan';
    input.dispatchEvent(new Event('input'));
    await tick();

    const request = http.expectOne((r) => r.url.endsWith('/api/v1/clinic/patients'));
    expect(request.request.params.get('q')).toBe('Hassan');
    request.flush([PATIENTS[0]]);
    await tick();
  });

  it('says on the screen that the term is never matched against a birth date', async () => {
    await answer(PATIENTS);
    i18n.setLocale('en');
    await tick();

    expect(text()).toContain('never a date of birth');
  });

  it('renders the rows the server sent, and nothing it did not', async () => {
    await answer(PATIENTS);
    i18n.setLocale('en');
    await tick();
    const table = element('[data-testid="clinic-patients-table"]');

    expect(table?.textContent).toContain('Layla Hassan');
    expect(table?.textContent).toContain('P-0001');
    expect(table?.textContent).toContain('+966500000001');
    // And the row whose birth date is absent says so in words.
    expect(table?.textContent).toContain('Not recorded');
    // An absent date is not a zero and not a year.
    expect(table?.textContent ?? '').not.toMatch(/Birth date:\s*0\b/);
  });

  it('links each row to that patient, and the link is the subject of the row', async () => {
    await answer(PATIENTS);
    const link = element('a[href="/app/clinic/patients/pat-1"]') as HTMLAnchorElement | null;

    expect(link).not.toBeNull();
    // The accessible name is the name, so the row is readable without the route.
    expect(link?.textContent?.trim()).toBe('Layla Hassan');
  });

  it('separates "no patients in your scope" from "this search matched nothing"', async () => {
    await answer([]);
    i18n.setLocale('en');
    await tick();
    expect(text()).toContain('No patients in this scope');

    // Same empty response, different question.
    const input = element('#fe-clinic-patient-search') as HTMLInputElement;
    input.value = 'Nobody';
    input.dispatchEvent(new Event('input'));
    await tick();
    http.expectOne((r) => r.url.endsWith('/api/v1/clinic/patients')).flush([]);
    await tick();

    expect(text()).toContain('No result for that search');
    expect(text()).not.toContain('No patients in this scope');
  });

  it('says the request failed when the read is refused, and keeps the request id', async () => {
    http.expectOne((r) => r.url.endsWith('/api/v1/clinic/patients')).flush(
      { code: 'ACCESS_DENIED', message: 'denied', requestId: 'req-9' },
      { status: 403, statusText: 'Forbidden' },
    );
    await tick();
    i18n.setLocale('en');
    await tick();

    expect(text()).toContain('The clinic data could not be loaded');
    expect(text()).toContain('req-9');
  });

  describe('direction', () => {
    it('renders Arabic and RTL in Arabic', async () => {
      await answer(PATIENTS);
      i18n.setLocale('ar');
      await tick();

      expect(text()).toContain('المرضى');
      expect(i18n.direction()).toBe('rtl');
      expect(document.documentElement.dir).toBe('rtl');
      // Data survives the switch: a patient's name is not copy.
      expect(text()).toContain('Layla Hassan');
    });

    it('renders English and LTR in English', async () => {
      await answer(PATIENTS);
      i18n.setLocale('en');
      await tick();

      expect(text()).toContain('Patients');
      expect(i18n.direction()).toBe('ltr');
      expect(document.documentElement.dir).toBe('ltr');
    });
  });
});