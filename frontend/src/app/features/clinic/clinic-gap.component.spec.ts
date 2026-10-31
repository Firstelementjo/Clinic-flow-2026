/**
 * Unit tests for `ClinicGapComponent`.
 *
 * Verifies which clinic screens have no read model, and tests `ClinicGapComponent`'s
 * presentation and accessibility behavior.
 */

import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, provideRouter } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

afterEach(() => {
  localStorage.clear();
});

import { routes } from '../../app.routes';
import { I18nService } from '../../core/i18n/i18n.service';
import { ClinicGapComponent } from './clinic-gap.component';

interface GapRoute {
  readonly path: string;
  readonly title: string;
  readonly body: string;
  readonly missing: string;
}

const clinicRoutes = routes.find((route) => route.path === 'app')?.children ?? [];

/** Every clinic route that declares a `missing` path — the gap screens, by construction. */
const GAP_ROUTES: readonly GapRoute[] = clinicRoutes
  .filter((route) => typeof (route.data ?? {})['missing'] === 'string')
  .map((route) => ({
    path: `/app/${route.path}`,
    title: String((route.data ?? {})['title']),
    body: String((route.data ?? {})['body']),
    missing: String((route.data ?? {})['missing']),
  }));

/**
 * The seven screens the gap table used to name, all of which were closed.
 *
 * They are listed so the assertion below is about **those** seven rather than about
 * a count: a count goes stale the moment a route is added, and a stale count is the
 * kind of assertion that stops meaning anything. A closed gap is still a route, and
 * it still has a component with a read model behind it.
 */
const CLOSED_GAPS: readonly string[] = [
  'clinic/patients/:id/treatment-plans',
  'clinic/patients/:id/encounters',
  'clinic/invoices',
  'clinic/payments',
  'clinic/reports',
  'clinic/settings/policies',
  'clinic/ai-assistant',
];

const SAMPLE_GAP_ROUTE: GapRoute = {
  path: '/app/clinic/sample-gap',
  title: 'clinic.gaps.reports.title',
  body: 'clinic.gaps.reports.body',
  missing: 'GET /api/v1/clinic/sample-gap',
};

/** Digits inside a sentence, after route identifiers are removed. */
function figuresIn(text: string): string[] {
  const withoutRoutes = text.replace(/\/api\/v\d\/[\w{}/-]*/g, '');
  return withoutRoutes.match(/[0-9٠-٩]/g) ?? [];
}

describe('the clinic screens gap component and migration', () => {
  let http: HttpTestingController;

  async function render(route: GapRoute): Promise<ComponentFixture<ClinicGapComponent>> {
    TestBed.resetTestingModule();
    await TestBed.configureTestingModule({
      imports: [ClinicGapComponent],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { data: { title: route.title, body: route.body, missing: route.missing } } },
        },
      ],
    }).compileComponents();
    TestBed.inject(I18nService).setLocale('en');
    http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(ClinicGapComponent);
    fixture.detectChanges();
    await new Promise<void>((resolve) => setTimeout(resolve, 0));
    fixture.detectChanges();
    return fixture;
  }

  beforeEach(() => {
    localStorage.clear();
  });

  it('confirms the legacy gaps and lab/aligners routes stay closed', () => {
    for (const path of [...CLOSED_GAPS, 'clinic/lab', 'clinic/aligners']) {
      const route = clinicRoutes.find((candidate) => candidate.path === path);
      expect(route, `${path} is not declared`).toBeDefined();
      expect(
        (route?.data ?? {})['missing'],
        `${path} was closed; it must not claim a missing route again`,
      ).toBeUndefined();
    }

    expect(GAP_ROUTES).toEqual([]);
  });

  describe('ClinicGapComponent behavior', () => {
    it('renders an unavailable card naming the exact missing route', async () => {
      const fixture = await render(SAMPLE_GAP_ROUTE);
      const text = (fixture.nativeElement as HTMLElement).textContent ?? '';

      expect(text).toContain('Not available yet');
      expect(text).toContain(SAMPLE_GAP_ROUTE.missing);
      const heading = (fixture.nativeElement as HTMLElement).querySelector('fe-not-available h3');
      expect(heading?.id).toBe('clinic-gap-heading');
      http.verify();
    });

    it('contains no numeric figure, in either language', async () => {
      const fixture = await render(SAMPLE_GAP_ROUTE);
      const element = fixture.nativeElement as HTMLElement;

      const i18n = TestBed.inject(I18nService);
      i18n.setLocale('en');
      fixture.detectChanges();
      expect(figuresIn(element.textContent ?? ''), `a figure in (en)`).toEqual([]);

      i18n.setLocale('ar');
      fixture.detectChanges();
      expect(figuresIn(element.textContent ?? ''), `a figure in (ar)`).toEqual([]);
      http.verify();
    });

    it('issues no request, because there is no route to call', async () => {
      await render(SAMPLE_GAP_ROUTE);
      expect(http.match(() => true).length).toBe(0);
      http.verify();
    });

    it('routes Arabic RTL and English LTR on the same DOM', async () => {
      const fixture = await render(SAMPLE_GAP_ROUTE);
      const i18n = TestBed.inject(I18nService);
      const element = fixture.nativeElement as HTMLElement;

      i18n.setLocale('ar');
      fixture.detectChanges();
      expect(element.textContent).toContain('تقارير العيادة');
      expect(i18n.direction()).toBe('rtl');
      expect(document.documentElement.dir).toBe('rtl');

      i18n.setLocale('en');
      fixture.detectChanges();
      expect(element.textContent).toContain('Clinic reports');
      expect(i18n.direction()).toBe('ltr');
      expect(document.documentElement.dir).toBe('ltr');
      http.verify();
    });
  });
});
