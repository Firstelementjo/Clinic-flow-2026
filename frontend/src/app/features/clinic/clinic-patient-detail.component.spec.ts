/**
 * `/app/clinic/patients/:id` — one patient, read through the route.
 *
 * ## The claims worth protecting
 *
 * 1. **The route is the source of the id, and it is re-read.** The router *reuses* a
 *    component when only the id changes, so a screen that read the id once in `ngOnInit`
 *    would show patient A under patient B's URL after a Back button. The suite re-emits
 *    the param map and asserts the second patient is the one on screen.
 * 2. **A refused read says what the server decided, and does not guess why.** "Not found"
 *    and "not yours" are one 404 on purpose — on a surface carrying patient names, a
 *    per-resource message is itself a directory of who is a patient.
 * 3. **The CRM record is not joined.** `customerId` is rendered as an identifier and
 *    nothing else. A test that let the customer's details in here would be a test that
 *    approves widening `appointment:read`.
 */
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, provideRouter } from '@angular/router';
import { convertToParamMap, type ParamMap } from '@angular/router';
import { BehaviorSubject } from 'rxjs';
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
import { ClinicPatientDetailComponent } from './clinic-patient-detail.component';

const LAYLA = {
  id: 'pat-1',
  patientNo: 'P-0001',
  displayName: 'Layla Hassan',
  dateOfBirth: '1991-04-02',
  primaryPhone: '+966500000001',
  locale: 'ar',
  customerId: 'cus-1',
  deleted: false,
};

const SARA = { id: 'pat-2', patientNo: 'P-0002', displayName: 'Sara Ahmed', deleted: false };

describe('the clinic patient record', () => {
  let fixture: ComponentFixture<ClinicPatientDetailComponent>;
  let http: HttpTestingController;
  let i18n: I18nService;
  let params: BehaviorSubject<ParamMap>;

  beforeEach(async () => {
    localStorage.clear();
    params = new BehaviorSubject<ParamMap>(convertToParamMap({ id: 'pat-1' }));

    await TestBed.configureTestingModule({
      imports: [ClinicPatientDetailComponent],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: ActivatedRoute,
          // `snapshot.data` because the router hands a reused component its own snapshot;
          // `paramMap` because the component deliberately subscribes to the stream.
          useValue: { paramMap: params.asObservable(), snapshot: { data: {}, paramMap: params.value } },
        },
      ],
    }).compileComponents();

    i18n = TestBed.inject(I18nService);
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ClinicPatientDetailComponent);
    fixture.detectChanges();
  });

  async function tick(): Promise<void> {
    await new Promise<void>((resolve) => setTimeout(resolve, 0));
    fixture.detectChanges();
  }

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  it('reads the one-patient route the URL names', () => {
    const request = http.expectOne((r) => r.url.endsWith('/api/v1/clinic/patients/pat-1'));
    expect(request.request.method).toBe('GET');
    request.flush(LAYLA);
  });

  it('renders the fields the server sent', async () => {
    http.expectOne((r) => r.url.endsWith('/api/v1/clinic/patients/pat-1')).flush(LAYLA);
    await tick();
    i18n.setLocale('en');
    await tick();

    expect(text()).toContain('Layla Hassan');
    expect(text()).toContain('P-0001');
    expect(text()).toContain('+966500000001');
    expect(text()).toContain('cus-1');
  });

  it('re-reads the id when the router reuses the component for a different patient', async () => {
    http.expectOne((r) => r.url.endsWith('/api/v1/clinic/patients/pat-1')).flush(LAYLA);
    await tick();
    expect(text()).toContain('Layla Hassan');

    // The Back button, or a pasted link, changes only the id.
    params.next(convertToParamMap({ id: 'pat-2' }));
    await tick();
    http.expectOne((r) => r.url.endsWith('/api/v1/clinic/patients/pat-2')).flush(SARA);
    await tick();

    expect(text()).toContain('Sara Ahmed');
    expect(text()).not.toContain('Layla Hassan');
  });

  it('renders the customer link as an identifier and says it is only an identifier', async () => {
    http.expectOne((r) => r.url.endsWith('/api/v1/clinic/patients/pat-1')).flush(LAYLA);
    await tick();
    i18n.setLocale('en');
    await tick();

    // The id, once. No email, no conversation count, no CRM field joined onto it.
    expect(text()).toContain('cus-1');
    expect(text()).toContain('An identifier only');
    expect(text() ?? '').not.toMatch(/conversations|inboxCount/);
  });

  it('says the server decided on a refusal, and does not distinguish absent from foreign', async () => {
    http.expectOne((r) => r.url.endsWith('/api/v1/clinic/patients/pat-1')).flush(
      { code: 'NOT_FOUND', message: 'Resource not found', requestId: 'req-42' },
      { status: 404, statusText: 'Not Found' },
    );
    await tick();
    i18n.setLocale('en');
    await tick();

    expect(text()).toContain('not available to you');
    // The refusal reason is deliberately not restated as "no such patient".
    expect(text()).not.toContain('No such patient');
    expect(text()).toContain('req-42');
  });

  it('issues no read at all when the URL carries no id', async () => {
    // The id the suite started on; drained below so the assertion is about the SECOND
    // emission rather than about a queue that happened to be empty.
    params.next(convertToParamMap({}));
    await tick();
    i18n.setLocale('en');
    await tick();

    http.expectOne((r) => r.url.endsWith('/api/v1/clinic/patients/pat-1')).flush(LAYLA);
    http.verify();
    expect(text()).toContain('The link carries no patient id');
  });

  it('is Arabic RTL and English LTR on the same DOM', async () => {
    http.expectOne((r) => r.url.endsWith('/api/v1/clinic/patients/pat-1')).flush(LAYLA);
    await tick();

    i18n.setLocale('ar');
    await tick();
    expect(text()).toContain('سجلّ المريض');
    expect(i18n.direction()).toBe('rtl');
    expect(document.documentElement.dir).toBe('rtl');

    i18n.setLocale('en');
    await tick();
    expect(text()).toContain('Patient record');
    expect(i18n.direction()).toBe('ltr');
    expect(document.documentElement.dir).toBe('ltr');
  });
});
