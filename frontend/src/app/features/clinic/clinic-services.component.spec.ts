/**
 * `/app/clinic/services-and-resources` — the Service catalogue.
 *
 * The claims this suite protects:
 *
 * 1. the screen reads the real procedures and locations endpoints;
 * 2. an absent price renders as "not priced", never as zero;
 * 3. New/Save and the row switch are gated on `procedure:manage`, and the
 *    create/edit path sends the fields the drawer owns;
 * 4. the row switch PATCHes only `isActive`, not a reconstructed row.
 */
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

afterEach(() => {
  localStorage.clear();
});

import { SessionStore } from '../../core/auth/session.store';
import { I18nService } from '../../core/i18n/i18n.service';
import { ClinicServicesComponent } from './clinic-services.component';

const PROCEDURES = {
  count: 2,
  items: [
    {
      id: 'p-1',
      name: 'Consultation',
      specialty: 'Preventive',
      durationMinutes: 30,
      price: 150,
      isActive: true,
      productCode: 'CONS-01',
      clinicId: 'c-1',
      clinicName: 'Downtown',
      type: 'IN_CLINIC',
    },
    // Absent price: the server means "not priced", which is NOT a price of zero.
    { id: 'p-2', name: 'MRI Scan', specialty: 'Radiology', durationMinutes: 45 },
  ],
};

const LOCATIONS = {
  count: 1,
  items: [{ id: 'c-1', name: 'Downtown', isActive: true, slotMinutes: 30 }],
};

describe('the clinic services screen', () => {
  let fixture: ComponentFixture<ClinicServicesComponent>;
  let http: HttpTestingController;
  let i18n: I18nService;
  let store: SessionStore;

  beforeEach(async () => {
    localStorage.clear();
    await TestBed.configureTestingModule({
      imports: [ClinicServicesComponent],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    store = TestBed.inject(SessionStore);
    store.setPermissions({
      effectivePermissions: ['procedure:read', 'procedure:manage'],
      missingPermissions: [],
    });

    i18n = TestBed.inject(I18nService);
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ClinicServicesComponent);
    fixture.detectChanges();
  });

  async function tick(): Promise<void> {
    await new Promise<void>((resolve) => setTimeout(resolve, 0));
    fixture.detectChanges();
  }

  function element(selector: string): HTMLElement | null {
    return (fixture.nativeElement as HTMLElement).querySelector(selector);
  }

  function flushCatalogue(
    procedures: object = PROCEDURES,
    locations: object = LOCATIONS,
  ): void {
    http.expectOne((r) => r.url.endsWith('/api/v1/clinic/procedures')).flush(procedures);
    http.expectOne((r) => r.url.endsWith('/api/v1/clinic/locations')).flush(locations);
  }

  async function openCreate(): Promise<void> {
    const button = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('button'),
    ).find((candidate) => candidate.textContent?.trim() === 'New');
    expect(button).toBeDefined();
    button!.click();
    fixture.detectChanges();
    await tick();
  }

  function setInput(selector: string, value: string): void {
    const input = element(selector) as HTMLInputElement | null;
    expect(input, `${selector} is not on the page`).not.toBeNull();
    input!.value = value;
    input!.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  it('reads the procedure catalogue and renders what the server sent', async () => {
    flushCatalogue();
    await tick();
    i18n.setLocale('en');
    await tick();

    const catalogue = element('[data-testid="clinic-procedures"]');
    expect(catalogue?.textContent).toContain('Consultation');
    expect(catalogue?.textContent).toContain('150');
    expect(catalogue?.textContent).toContain('Downtown');
  });

  it('renders an unpriced procedure as "not priced", never as a zero', async () => {
    flushCatalogue();
    await tick();
    i18n.setLocale('en');
    await tick();

    const unpriced = element('[data-testid="clinic-procedure-unpriced"]');
    expect(unpriced?.textContent).toContain('Not priced');
    // The half of the rule that matters: no zero where the server sent nothing.
    expect(unpriced?.textContent ?? '').not.toMatch(/\b0\b/);
  });

  it('creates a service through the drawer and reloads the catalogue', async () => {
    flushCatalogue();
    await tick();
    i18n.setLocale('en');
    await tick();

    await openCreate();
    setInput('#service-name', 'Root canal');
    setInput('#service-category', 'Endodontics');
    setInput('#service-price', '95');

    const form = element('#clinic-service-form') as HTMLFormElement;
    form.dispatchEvent(new Event('submit'));
    fixture.detectChanges();

    const request = http.expectOne(
      (r) => r.url.endsWith('/api/v1/clinic/procedures') && r.method === 'POST',
    );
    expect(request.request.body).toMatchObject({
      name: 'Root canal',
      specialty: 'Endodontics',
      price: 95,
      type: 'IN_CLINIC',
    });
    request.flush({ id: 'p-3', name: 'Root canal', specialty: 'Endodontics', price: 95 });
    await tick();

    // Saving closes the drawer and reloads both reads.
    flushCatalogue();
    await tick();
    expect(element('fe-tl-drawer .tl-drawer')).toBeNull();
  });

  it('sends only isActive when the row switch is toggled', async () => {
    flushCatalogue();
    await tick();

    const toggle = element('tbody .tl-switch input') as HTMLInputElement;
    expect(toggle).not.toBeNull();
    toggle.checked = false;
    toggle.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    const request = http.expectOne(
      (r) => r.url.endsWith('/api/v1/clinic/procedures/p-1') && r.method === 'PATCH',
    );
    expect(request.request.body).toEqual({ isActive: false });
    request.flush({ ...PROCEDURES.items[0], isActive: false });
    await tick();
  });

  it('disables New and the row switch without procedure:manage', async () => {
    flushCatalogue();
    await tick();
    store.setPermissions({
      effectivePermissions: ['procedure:read'],
      missingPermissions: ['procedure:manage'],
    });
    fixture.detectChanges();
    await tick();

    const newButton = element('.tl-toolbar-right .tl-btn-teal') as HTMLButtonElement;
    expect(newButton).not.toBeNull();
    expect(newButton.disabled).toBe(true);
    const toggle = element('tbody .tl-switch input') as HTMLInputElement;
    expect(toggle.disabled).toBe(true);

    http.verify();
  });
});
