/**
 * `/app/clinic/appointments/:id` — one appointment.
 *
 * ## The claims worth protecting
 *
 * 1. **The read takes no `projectId`.** `GET /api/v1/clinic/appointments/{id}` declares
 *    none, so a screen that sent one would be inventing a scope parameter the controller
 *    never read — and would look, in a network log, like a client trying to widen its own
 *    reach.
 * 2. **The status is printed, not interpreted.** `Appointment.status` is a stored wire
 *    value. A client that coloured it red for `NO_SHOW` would be asserting a cancellation
 *    and no-show policy nobody has approved, so the screen prints it in monospace and says
 *    in words that it neither translates it nor infers a clinical meaning.
 * 3. **An AI-proposed row is marked, and marked as uncommitted.** A confirmation the AI
 *    proposed but which is not yet persisted must not read as a booking that exists.
 */
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter, type ParamMap } from '@angular/router';
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
import { ClinicAppointmentDetailComponent } from './clinic-appointment-detail.component';

const APPOINTMENT = {
  id: 'a-1',
  title: 'Consultation',
  patientName: 'Layla Hassan',
  appointmentDate: '2026-09-01T09:00:00.000Z',
  durationMinutes: 30,
  status: 'NO_SHOW',
  service: 'Consultation',
  doctorName: 'Dr. Amina Haddad',
  doctorId: 'd-1',
  isAiProposed: false,
  ticketNumber: 'T-77',
};

describe('the clinic appointment record', () => {
  let fixture: ComponentFixture<ClinicAppointmentDetailComponent>;
  let http: HttpTestingController;
  let i18n: I18nService;
  let params: BehaviorSubject<ParamMap>;

  beforeEach(async () => {
    localStorage.clear();
    params = new BehaviorSubject<ParamMap>(convertToParamMap({ id: 'a-1' }));
    await TestBed.configureTestingModule({
      imports: [ClinicAppointmentDetailComponent],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: ActivatedRoute,
          useValue: {
            paramMap: params.asObservable(),
            snapshot: { data: {}, paramMap: params.value },
          },
        },
      ],
    }).compileComponents();

    i18n = TestBed.inject(I18nService);
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ClinicAppointmentDetailComponent);
    fixture.detectChanges();
  });

  async function tick(): Promise<void> {
    await new Promise<void>((resolve) => setTimeout(resolve, 0));
    fixture.detectChanges();
  }

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  function element(selector: string): HTMLElement | null {
    return (fixture.nativeElement as HTMLElement).querySelector(selector);
  }

  it('reads the one-appointment route and sends no project scope', () => {
    const request = http.expectOne((r) => r.url.endsWith('/api/v1/clinic/appointments/a-1'));
    expect(request.request.method).toBe('GET');
    // This route declares no `projectId`; the controller resolves the scope itself.
    expect(request.request.params.has('projectId')).toBe(false);
    request.flush(APPOINTMENT);
  });

  it('prints the stored status rather than interpreting it', async () => {
    http.expectOne((r) => r.url.endsWith('/api/v1/clinic/appointments/a-1')).flush(APPOINTMENT);
    await tick();
    i18n.setLocale('en');
    await tick();

    const status = element('[data-testid="clinic-appointment-status"]') as HTMLElement | null;
    expect(status?.textContent).toContain('NO_SHOW');
    // Wire value, LTR island inside English and Arabic alike.
    expect(status?.getAttribute('dir')).toBe('ltr');
    expect(status?.className).toContain('fe-mono');
    // And the screen says, in words, that it is not interpreting it.
    expect(text()).toContain('neither translates it nor infers a clinical meaning');
  });

  it('renders the fields the server sent', async () => {
    http.expectOne((r) => r.url.endsWith('/api/v1/clinic/appointments/a-1')).flush(APPOINTMENT);
    await tick();
    i18n.setLocale('en');
    await tick();

    expect(text()).toContain('Layla Hassan');
    expect(text()).toContain('Dr. Amina Haddad');
    expect(text()).toContain('Consultation');
    expect(text()).toContain('T-77');
  });

  it('marks an AI-proposed appointment and says it is not committed', async () => {
    http.expectOne((r) => r.url.endsWith('/api/v1/clinic/appointments/a-1')).flush({
      ...APPOINTMENT,
      isAiProposed: true,
    });
    await tick();
    i18n.setLocale('en');
    await tick();

    expect(text()).toContain('AI-proposed');
    expect(element('[data-testid="clinic-appointment-ai"]')?.textContent).toContain(
      'not yet persisted',
    );
  });

  it('says the server decided on a refusal', async () => {
    http.expectOne((r) => r.url.endsWith('/api/v1/clinic/appointments/a-1')).flush(
      { code: 'NOT_FOUND', message: 'Resource not found', requestId: 'req-7' },
      { status: 404, statusText: 'Not Found' },
    );
    await tick();
    i18n.setLocale('en');
    await tick();

    expect(text()).toContain('not available to you');
    expect(text()).not.toContain('No such appointment');
    expect(text()).toContain('req-7');
  });

  it('issues no read at all when the URL carries no id', async () => {
    // The id the suite started on; drained below so the assertion is about the SECOND
    // emission rather than about a queue that happened to be empty.
    params.next(convertToParamMap({}));
    await tick();
    i18n.setLocale('en');
    await tick();

    expect(element('[data-testid="clinic-appointment-no-id"]')?.textContent).toContain(
      'The link carries no appointment id',
    );

    http.expectOne((r) => r.url.endsWith('/api/v1/clinic/appointments/a-1')).flush(APPOINTMENT);
    http.verify();
  });
});
