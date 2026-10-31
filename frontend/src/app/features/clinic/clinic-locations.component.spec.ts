/**
 * `/app/clinic/branches` — the Clinic List.
 *
 * This route used to be the honest "not available" card because a clinic was a
 * project. Phase 38 gives it a real table and a real drawer; the tests below pin
 * the parts that could silently turn back into a mock: the list reads the server,
 * New/Save are gated on `clinic:manage`, create and edit hit the API, and the row
 * switch sends only `isActive`.
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
import { ClinicLocationsComponent } from './clinic-locations.component';

const LOCATIONS = {
  count: 2,
  items: [
    {
      id: 'c-1',
      name: 'Downtown Clinic',
      contactNumber: '+962 6 000 0000',
      city: 'Amman',
      slotMinutes: 30,
      isActive: true,
      frontDeskOfficerId: 'u-1',
      frontDeskOfficerName: 'Rana',
    },
    { id: 'c-2', name: 'West Branch', slotMinutes: 20, isActive: false },
  ],
};

describe('the clinic locations screen', () => {
  let fixture: ComponentFixture<ClinicLocationsComponent>;
  let http: HttpTestingController;
  let i18n: I18nService;
  let store: SessionStore;

  beforeEach(async () => {
    localStorage.clear();
    await TestBed.configureTestingModule({
      imports: [ClinicLocationsComponent],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    store = TestBed.inject(SessionStore);
    store.setPermissions({
      effectivePermissions: ['doctor:read', 'clinic:manage'],
      missingPermissions: [],
    });

    i18n = TestBed.inject(I18nService);
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ClinicLocationsComponent);
    fixture.detectChanges();
  });

  async function tick(): Promise<void> {
    await new Promise<void>((resolve) => setTimeout(resolve, 0));
    fixture.detectChanges();
  }

  function element(selector: string): HTMLElement | null {
    return (fixture.nativeElement as HTMLElement).querySelector(selector);
  }

  function flushLocations(
    body: object = LOCATIONS,
    frontDesk: object = { count: 0, items: [] },
  ): void {
    http.expectOne((r) => r.url.endsWith('/api/v1/clinic/locations')).flush(body);
    // The drawer's officer select is loaded beside the list. It is an account read
    // gated on front-desk:read, so it can legitimately be empty here.
    for (const request of http.match((r) => r.url.endsWith('/api/v1/clinic/front-desk'))) {
      request.flush(frontDesk);
    }
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

  it('reads the locations and renders the staff-facing columns', async () => {
    flushLocations();
    await tick();
    i18n.setLocale('en');
    await tick();

    const table = element('[data-testid="clinic-locations-table"]');
    expect(table?.textContent).toContain('Downtown Clinic');
    expect(table?.textContent).toContain('+962 6 000 0000');
    expect(table?.textContent).toContain('Rana');
  });

  it('creates a clinic through the drawer', async () => {
    flushLocations();
    await tick();
    i18n.setLocale('en');
    await tick();

    await openCreate();
    setInput('#clinic-name', 'North Clinic');
    setInput('#clinic-contact', '+962 6 111 1111');
    setInput('#clinic-slot', '25');

    const form = element('#clinic-location-form') as HTMLFormElement;
    form.dispatchEvent(new Event('submit'));
    fixture.detectChanges();

    const request = http.expectOne(
      (r) => r.url.endsWith('/api/v1/clinic/locations') && r.method === 'POST',
    );
    expect(request.request.body).toMatchObject({
      name: 'North Clinic',
      contactNumber: '+962 6 111 1111',
      slotMinutes: 25,
      isActive: true,
    });
    request.flush({ id: 'c-3', name: 'North Clinic' });
    await tick();

    flushLocations({ count: 3, items: [...LOCATIONS.items, { id: 'c-3', name: 'North Clinic' }] });
    await tick();
    expect(element('fe-tl-drawer .tl-drawer')).toBeNull();
  });

  it('edits a clinic with PATCH', async () => {
    flushLocations();
    await tick();
    i18n.setLocale('en');
    await tick();

    const edit = element('tbody .tl-action-icon.edit') as HTMLButtonElement;
    edit.click();
    fixture.detectChanges();
    await tick();

    setInput('#clinic-city', 'Irbid');
    const form = element('#clinic-location-form') as HTMLFormElement;
    form.dispatchEvent(new Event('submit'));
    fixture.detectChanges();

    const request = http.expectOne(
      (r) => r.url.endsWith('/api/v1/clinic/locations/c-1') && r.method === 'PATCH',
    );
    expect(request.request.body).toMatchObject({ name: 'Downtown Clinic', city: 'Irbid' });
    request.flush({ ...LOCATIONS.items[0], city: 'Irbid' });
    await tick();
    flushLocations();
    await tick();
  });

  it('clears an assigned officer explicitly when Unassigned is chosen', async () => {
    flushLocations(
      { ...LOCATIONS, count: 1, items: [LOCATIONS.items[0]] },
      { count: 1, items: [{ userId: 'u-1', fullName: 'Rana', isActive: true }] },
    );
    await tick();
    i18n.setLocale('en');
    await tick();

    const edit = element('tbody .tl-action-icon.edit') as HTMLButtonElement;
    edit.click();
    fixture.detectChanges();
    await tick();

    const select = element('#clinic-officer') as HTMLSelectElement;
    expect(select.value).toBe('u-1');
    select.value = '';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    const form = element('#clinic-location-form') as HTMLFormElement;
    form.dispatchEvent(new Event('submit'));
    fixture.detectChanges();

    const request = http.expectOne(
      (r) => r.url.endsWith('/api/v1/clinic/locations/c-1') && r.method === 'PATCH',
    );
    expect(request.request.body).toMatchObject({
      frontDeskOfficerId: null,
      clearFrontDeskOfficer: true,
    });
    request.flush({ ...LOCATIONS.items[0], frontDeskOfficerId: null, frontDeskOfficerName: null });
    await tick();
    flushLocations({ ...LOCATIONS, count: 1, items: [LOCATIONS.items[0]] });
    await tick();
  });

  it('sends only isActive when the row switch is toggled', async () => {
    flushLocations();
    await tick();

    const toggle = element('tbody .tl-switch input') as HTMLInputElement;
    expect(toggle).not.toBeNull();
    toggle.checked = false;
    toggle.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    const request = http.expectOne(
      (r) => r.url.endsWith('/api/v1/clinic/locations/c-1') && r.method === 'PATCH',
    );
    expect(request.request.body).toEqual({ isActive: false });
    request.flush({ ...LOCATIONS.items[0], isActive: false });
    await tick();
  });

  it('disables New and the row switch without clinic:manage', async () => {
    flushLocations();
    await tick();
    store.setPermissions({
      effectivePermissions: ['doctor:read'],
      missingPermissions: ['clinic:manage'],
    });
    fixture.detectChanges();
    await tick();

    const newButton = element('.tl-toolbar-right .tl-btn-teal') as HTMLButtonElement;
    expect(newButton).not.toBeNull();
    expect(newButton.disabled).toBe(true);
    expect((element('tbody .tl-switch input') as HTMLInputElement).disabled).toBe(true);
    http.verify();
  });
});
