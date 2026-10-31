/**
 * `/app/clinic/patients/:id/odontogram` — the chart.
 *
 * ## The claims worth protecting
 *
 * 1. **A corrected finding stays on screen.** `OdontogramModels` is append-only and
 *    `superseded` is computed at read time, so a chart that hides a corrected row has
 *    erased the history the design exists to preserve. The suite asserts both rows are
 *    rendered and that each is *marked*, in words.
 * 2. **No tooth diagram, and no invented notation.** The screen renders the server's own
 *    `toothFdi`. A chart that laid teeth out on a grid would be the client deciding where
 *    a tooth sits, which is clinic notation policy this product has not been given.
 * 3. **A tooth's history is a real control.** It is a `button` with `aria-expanded` and
 *    `aria-controls`, and it requests **only a tooth number the server published on a
 *    finding** — never one the client typed, because the route answers "nothing found"
 *    rather than "bad number", and a guessed tooth would render a confident empty chart.
 * 4. **Vocabulary is not fetched.** `/odontogram/vocabulary` seeds a table on first call;
 *    a read screen that triggered a write is not a read screen.
 */
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import {
  ActivatedRoute,
  convertToParamMap,
  provideRouter,
  type ParamMap,
} from '@angular/router';
import { of } from 'rxjs';
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
import { ClinicOdontogramComponent } from './clinic-odontogram.component';

const CHART = [
  {
    finding: {
      id: 'f-2',
      patientId: 'pat-1',
      toothFdi: 16,
      scope: 'CERVICAL',
      surfaces: ['M', 'D'],
      code: 'ABSENT',
      state: 'RECORDED',
      source: 'CLINICIAN',
      recordedAt: '2026-09-02T08:00:00.000Z',
    },
    superseded: false,
  },
  {
    finding: {
      id: 'f-1',
      patientId: 'pat-1',
      toothFdi: 16,
      scope: 'OCCLUSAL',
      surfaces: [],
      code: 'CARIES',
      state: 'RECORDED',
      source: 'CLINICIAN',
      recordedAt: '2026-08-01T08:00:00.000Z',
      supersededBy: 'f-2',
    },
    superseded: true,
  },
];

const TOOTH_HISTORY = [
  {
    id: 'f-0',
    patientId: 'pat-1',
    toothFdi: 16,
    code: 'SEALANT',
    state: 'RECORDED',
    source: 'CLINICIAN',
    recordedAt: '2026-01-05T08:00:00.000Z',
    note: 'placed at the chair',
  },
];

describe('the clinic dental chart', () => {
  let fixture: ComponentFixture<ClinicOdontogramComponent>;
  let http: HttpTestingController;
  let i18n: I18nService;

  beforeEach(async () => {
    localStorage.clear();
    await TestBed.configureTestingModule({
      imports: [ClinicOdontogramComponent],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: ActivatedRoute,
          useValue: {
            paramMap: of<ParamMap>(convertToParamMap({ id: 'pat-1' })),
            snapshot: { data: {}, paramMap: convertToParamMap({ id: 'pat-1' }) },
          },
        },
      ],
    }).compileComponents();

    i18n = TestBed.inject(I18nService);
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ClinicOdontogramComponent);
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

  function answerChart(body: object): void {
    http.expectOne((r) => r.url.endsWith('/api/v1/clinic/patients/pat-1/odontogram')).flush(body);
  }

  it('reads the chart for the patient the URL names', () => {
    const request = http.expectOne((r) => r.url.endsWith('/api/v1/clinic/patients/pat-1/odontogram'));
    expect(request.request.method).toBe('GET');
    request.flush(CHART);
  });

  it('keeps a corrected finding on screen and marks it in words', async () => {
    answerChart(CHART);
    await tick();
    i18n.setLocale('en');
    await tick();

    const chart = element('[data-testid="clinic-chart"]');
    // Both rows, not just the standing one.
    expect(chart?.textContent).toContain('CARIES');
    expect(chart?.textContent).toContain('ABSENT');
    // And each carries a word, because a chip that only changes colour is not a status.
    expect(chart?.textContent).toContain('Corrected');
    expect(chart?.textContent).toContain('Standing');
  });

  it('draws no tooth diagram and says why', async () => {
    answerChart(CHART);
    await tick();
    i18n.setLocale('en');
    await tick();

    expect(text()).toContain('draws no tooth diagram');
    // The tooth number is the server's, rendered as an LTR island of code.
    const tooth = element('[data-testid="clinic-chart"] .fe-mono[dir="ltr"]') as HTMLElement | null;
    expect(tooth?.textContent).toContain('16');
  });

  it('opens one tooth history as a keyboard-reachable control', async () => {
    answerChart(CHART);
    await tick();

    const button = element(
      '[data-testid="clinic-chart"] button[aria-controls="clinic-tooth-history"]',
    ) as HTMLButtonElement | null;
    expect(button).not.toBeNull();
    expect(button?.getAttribute('aria-expanded')).toBe('false');

    button?.click();
    await tick();

    // Only a tooth the server published is ever requested — the number is the finding's.
    const request = http.expectOne((r) =>
      r.url.endsWith('/api/v1/clinic/patients/pat-1/odontogram/tooth/16'),
    );
    expect(request.request.method).toBe('GET');
    request.flush(TOOTH_HISTORY);
    await tick();

    expect(element('[data-testid="clinic-tooth-history"]')?.textContent).toContain('SEALANT');
    expect(button?.getAttribute('aria-expanded')).toBe('true');
    expect(document.getElementById(button?.getAttribute('aria-controls') ?? '')).not.toBeNull();
  });

  it('says an empty chart is an empty record, not a failed read', async () => {
    answerChart([]);
    await tick();
    i18n.setLocale('en');
    await tick();

    expect(text()).toContain('No findings recorded');
    expect(text()).toContain('That is an empty record, not a failed read');
  });

  it('never calls the vocabulary route, which seeds a table on first read', async () => {
    answerChart(CHART);
    await tick();

    const urls = http.match(() => true).map((request) => request.request.url);
    expect(urls.some((url) => url.includes('/odontogram/vocabulary'))).toBe(false);
    http.verify();
  });

  // ---- Slice S4: Notation switching --------------------------------------

  it('switches tooth notation at the UI boundary between FDI, Universal, and Palmer', async () => {
    answerChart(CHART);
    await tick();
    i18n.setLocale('en');
    await tick();

    // Default is FDI: tooth 16 is displayed
    const chart = element('[data-testid="clinic-chart"]');
    expect(chart?.textContent).toContain('16');

    // Switch to Universal: tooth 16 becomes Universal 3
    const buttons = fixture.nativeElement.querySelectorAll('.seg-btn');
    const universalBtn = Array.from(buttons).find(
      (b: any) => b.textContent?.includes('Universal') || b.textContent?.includes('الشامل'),
    ) as HTMLButtonElement | undefined;
    expect(universalBtn).toBeDefined();

    universalBtn?.click();
    await tick();

    // Tooth 16 in Universal numbering is '3'
    expect(chart?.textContent).toContain('3');

    // Switch to Palmer: tooth 16 becomes UR6
    const palmerBtn = Array.from(buttons).find(
      (b: any) => b.textContent?.includes('Palmer') || b.textContent?.includes('بالمر'),
    ) as HTMLButtonElement | undefined;
    expect(palmerBtn).toBeDefined();

    palmerBtn?.click();
    await tick();

    expect(chart?.textContent).toContain('UR6');
  });

  // ---- Slice S4: Periodontal probing chart view & derived CAL -------------

  it('switches to periodontal probing view and loads 6-site probing chart with derived CAL', async () => {
    answerChart(CHART);
    await tick();
    i18n.setLocale('en');
    await tick();

    // Switch tab to Periodontal view
    const tabButtons = fixture.nativeElement.querySelectorAll('.tab-btn');
    const perioTab = Array.from(tabButtons).find(
      (b: any) => b.textContent?.includes('Periodontal') || b.textContent?.includes('دواعم'),
    ) as HTMLButtonElement | undefined;
    expect(perioTab).toBeDefined();

    perioTab?.click();
    await tick();

    // Expects calls to periodontal/exams and periodontal/latest
    const examsReq = http.expectOne((r) => r.url.endsWith('/api/v1/clinic/patients/pat-1/periodontal/exams'));
    expect(examsReq.request.method).toBe('GET');
    examsReq.flush([
      {
        id: 'exam-1',
        organizationId: 'org-1',
        projectId: 'proj-1',
        patientId: 'pat-1',
        examinedAt: '2026-10-20T10:00:00Z',
        measurementCount: 1,
        createdAt: '2026-10-20T10:00:00Z',
      },
    ]);

    const latestReq = http.expectOne((r) => r.url.endsWith('/api/v1/clinic/patients/pat-1/periodontal/latest'));
    expect(latestReq.request.method).toBe('GET');
    latestReq.flush([
      {
        id: 'm-1',
        toothFdi: 16,
        probingDepthMb: 5,
        probingDepthB: 3,
        probingDepthDb: 4,
        probingDepthMl: 3,
        probingDepthL: 2,
        probingDepthDl: 4,
        gingivalMarginMb: 2,
        gingivalMarginB: 0,
        gingivalMarginDb: 1,
        gingivalMarginMl: 0,
        gingivalMarginL: 0,
        gingivalMarginDl: 1,
        calMb: 7, // 5 + 2
        calB: 3,
        calDb: 5,
        calMl: 3,
        calL: 2,
        calDl: 5,
        bopMb: true,
        bopDb: true,
      },
    ]);
    await tick();

    const perioTable = element('.perio-table');
    expect(perioTable).not.toBeNull();
    // Deep pocket warning (>3mm on PD MB=5, DB=4, DL=4)
    expect(perioTable?.textContent).toContain('Deep pocket');
    expect(perioTable?.textContent).toContain('CAL: 7');
    expect(perioTable?.textContent).toContain('BOP');
  });
});

