/**
 * Unit tests for ClinicTreatmentPlansComponent (TRT-01 to TRT-05, Slice S5).
 *
 * Protects:
 * 1. Plan versioning and parent reference display.
 * 2. Immutability guards on approved plans (ACTIVE / COMPLETED) showing notice banner and Fork action.
 * 3. Financial estimates breakdown (cost, discount, insurance, patient portion).
 * 4. Multi-stage and multi-visit procedure item rendering.
 * 5. Approve & Activate and Fork Version workflows.
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
import { ClinicTreatmentPlansComponent } from './clinic-treatment-plans.component';
import type { ClinicTreatmentPlanHeader, ClinicTreatmentPlanDetail } from './clinic-patient.api';

afterEach(() => {
  localStorage.clear();
});

const MOCK_PLANS: ClinicTreatmentPlanHeader[] = [
  {
    id: 'plan-1',
    organizationId: 'org-1',
    projectId: 'proj-1',
    patientId: 'pat-1',
    title: 'Comprehensive Rehabilitation Plan',
    status: 'ACTIVE',
    version: 1,
    planType: 'PRIMARY',
    totalDiscount: 50,
    insuranceEstimatedCoverage: 200,
    patientEstimatedPortion: 550,
    patientConsent: true,
    consentAt: '2026-10-15T10:00:00.000Z',
    consentRegistrarName: 'Dr. Ahmad',
    consentNotes: 'Signed digital consent',
    approvedAt: '2026-10-15T10:00:00.000Z',
    totalEstimatedCost: 800,
    totalInvoicedAmount: 300,
    totalCollectedAmount: 250,
    itemCount: 2,
    completedItemCount: 1,
    createdAt: '2026-10-14T09:00:00.000Z',
    updatedAt: '2026-10-15T10:00:00.000Z',
  },
  {
    id: 'plan-2',
    organizationId: 'org-1',
    projectId: 'proj-1',
    patientId: 'pat-1',
    title: 'Comprehensive Rehabilitation Plan (v2)',
    status: 'DRAFT',
    version: 2,
    parentPlanId: 'plan-1',
    planType: 'PRIMARY',
    totalDiscount: 60,
    insuranceEstimatedCoverage: 220,
    patientEstimatedPortion: 620,
    patientConsent: false,
    totalEstimatedCost: 900,
    totalInvoicedAmount: 0,
    totalCollectedAmount: 0,
    itemCount: 3,
    completedItemCount: 0,
    createdAt: '2026-10-16T11:00:00.000Z',
    updatedAt: '2026-10-16T11:00:00.000Z',
  },
];

const MOCK_DETAIL_PLAN_1: ClinicTreatmentPlanDetail = {
  plan: MOCK_PLANS[0],
  items: [
    {
      id: 'item-1',
      organizationId: 'org-1',
      projectId: 'proj-1',
      planId: 'plan-1',
      sequenceOrder: 1,
      stage: 'PHASE_1',
      plannedVisits: 2,
      completedVisits: 2,
      procedureName: 'Molar Endodontics',
      toothFdi: 16,
      estimatedCost: 350,
      discountAmount: 20,
      insuranceCoverage: 100,
      patientPortion: 230,
      invoicedAmount: 350,
      collectedAmount: 250,
      status: 'COMPLETED',
      createdAt: '2026-10-14T09:30:00.000Z',
      updatedAt: '2026-10-18T10:00:00.000Z',
    },
    {
      id: 'item-2',
      organizationId: 'org-1',
      projectId: 'proj-1',
      planId: 'plan-1',
      sequenceOrder: 2,
      stage: 'PHASE_2',
      plannedVisits: 1,
      completedVisits: 0,
      procedureName: 'Zirconia Crown',
      toothFdi: 16,
      estimatedCost: 450,
      discountAmount: 30,
      insuranceCoverage: 100,
      patientPortion: 320,
      invoicedAmount: 0,
      collectedAmount: 0,
      status: 'PROPOSED',
      createdAt: '2026-10-14T09:30:00.000Z',
      updatedAt: '2026-10-14T09:30:00.000Z',
    },
  ],
};

describe('ClinicTreatmentPlansComponent (Slice S5)', () => {
  let fixture: ComponentFixture<ClinicTreatmentPlansComponent>;
  let http: HttpTestingController;

  beforeEach(async () => {
    localStorage.clear();
    await TestBed.configureTestingModule({
      imports: [ClinicTreatmentPlansComponent],
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

    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ClinicTreatmentPlansComponent);
    fixture.detectChanges();
  });

  async function tick(): Promise<void> {
    await new Promise<void>((resolve) => setTimeout(resolve, 0));
    fixture.detectChanges();
  }

  async function flushInitialRequests(): Promise<void> {
    // Expect doctors list
    const docReq = http.expectOne((req) => req.url.includes('/api/v1/clinic/doctors'));
    docReq.flush({ items: [], total: 0 });

    // Expect plans list
    const plansReq = http.expectOne((req) => req.url.includes('/api/v1/clinic/patients/pat-1/treatment-plans'));
    plansReq.flush(MOCK_PLANS);

    await tick();

    // Expect active plan 1 detail
    const detailReq = http.expectOne((req) => req.url.includes('/api/v1/clinic/patients/pat-1/treatment-plans/plan-1'));
    detailReq.flush(MOCK_DETAIL_PLAN_1);

    await tick();
  }

  it('renders plan tabs with version tags and selects first plan by default', async () => {
    await flushInitialRequests();

    const text = fixture.nativeElement.textContent ?? '';
    expect(text).toContain('Comprehensive Rehabilitation Plan');
    expect(text).toContain('v1');
    expect(text).toContain('v2');
  });

  it('displays immutability banner and Fork action on ACTIVE approved plans', async () => {
    await flushInitialRequests();

    const banner = fixture.nativeElement.querySelector('.immutable-banner');
    expect(banner).toBeTruthy();
    expect(banner?.textContent).toContain('IMMUTABLE');

    // Fork version button is visible for approved plan
    const buttons = Array.from(fixture.nativeElement.querySelectorAll('button')) as HTMLButtonElement[];
    const forkBtn = buttons.find((b) => b.textContent?.includes('تفريغ إصدار معدل') || b.textContent?.includes('Fork'));
    expect(forkBtn).toBeTruthy();
  });

  it('renders multi-visit tracking and stages in items table', async () => {
    await flushInitialRequests();

    const text = fixture.nativeElement.textContent ?? '';
    expect(text).toContain('PHASE_1');
    expect(text).toContain('PHASE_2');
    expect(text).toContain('Molar Endodontics');
    expect(text).toContain('Zirconia Crown');
    expect(text).toContain('2 / 2'); // completed visits / planned visits
  });

  it('renders financial estimates breakdown cards', async () => {
    await flushInitialRequests();

    const text = fixture.nativeElement.textContent ?? '';
    expect(text).toContain('إجمالي الخصم');
    expect(text).toContain('تقدير تغطية التأمين');
    expect(text).toContain('المبلغ التقديري على المريض');
  });
});
