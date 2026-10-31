/**
 * Slice S8 — Audit, Reporting, Access Control Hardening.
 *
 * Frontend tests for ClinicReportsComponent verifying:
 * 1. Initial page load and window filter controls.
 * 2. Active break-glass emergency banner rendering and early revocation.
 * 3. Break-glass emergency request modal with validation (minimum 5 chars).
 * 4. Clinician productivity report metrics and table rendering.
 * 5. Procedure utilization report categories, revenue, and counts.
 * 6. Financial collections report currency breakdown and collection rate.
 * 7. Patient retention & recall overdue status badges.
 * 8. Break-glass compliance review queue with approval and flagging actions.
 * 9. Immutable audit trail rendering and SHA-256 cryptographic chain verification.
 * 10. Report export with cryptographic signature feedback banner.
 */

import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { ClinicReportsComponent } from './clinic-reports.component';
import type {
  AppointmentProductivityRow,
  ProcedureUtilizationRow,
  FinancialCollectionSummaryRow,
  PatientRetentionRecallRow,
  BreakGlassComplianceRow,
  BreakGlassGrantRow,
  ClinicAuditRow,
  ClinicAuditExportView,
} from './clinic-patient.api';

afterEach(() => {
  localStorage.clear();
});

const MOCK_ACTIVE_GRANT: BreakGlassGrantRow = {
  id: 'grant-001',
  organizationId: 'org-test',
  projectId: 'proj-test',
  patientId: 'pat-001',
  requestedByUserId: 'user-doc-1',
  reason: 'Acute trauma emergency examination',
  grantedAt: '2026-10-20T08:00:00Z',
  expiresAt: '2026-10-20T12:00:00Z',
  revokedAt: null,
  reviewStatus: 'PENDING',
};

const MOCK_PRODUCTIVITY: AppointmentProductivityRow[] = [
  {
    doctorId: 'doc-001',
    doctorName: 'Dr. Sarah Smith',
    specialty: 'Orthodontics',
    totalAppointments: 25,
    completedAppointments: 22,
    cancelledAppointments: 2,
    noShowAppointments: 1,
    completionRatePercent: 88.0,
    totalInvoiced: 4500.0,
  },
];

const MOCK_PROCEDURES: ProcedureUtilizationRow[] = [
  {
    procedureCode: 'D0120',
    procedureName: 'Periodic Oral Evaluation',
    category: 'Diagnostic',
    procedureCount: 45,
    totalRevenue: 2250.0,
    averagePrice: 50.0,
  },
];

const MOCK_FINANCIALS: FinancialCollectionSummaryRow[] = [
  {
    currency: 'JOD',
    totalInvoiced: 12000.0,
    totalCollected: 10500.0,
    totalOutstanding: 1500.0,
    collectionRatePercent: 87.5,
    invoiceCount: 40,
    paymentCount: 38,
  },
];

const MOCK_RECALL: PatientRetentionRecallRow[] = [
  {
    patientId: 'pat-100',
    patientName: 'Layla Mansour',
    phone: '+962791112233',
    lastVisitDate: '2026-03-15T10:00:00Z',
    daysSinceLastVisit: 219,
    recallStatus: 'OVERDUE',
    recommendedProcedure: 'ROUTINE_PROPHYLAXIS_EXAM',
  },
];

const MOCK_COMPLIANCE: BreakGlassComplianceRow[] = [
  {
    grantId: 'grant-001',
    requesterName: 'Dr. Tariq',
    patientName: 'Emergency Trauma Patient',
    reason: 'Emergency pulp extirpation',
    grantedAt: '2026-10-20T08:00:00Z',
    expiresAt: '2026-10-20T12:00:00Z',
    active: true,
    reviewStatus: 'PENDING',
    reviewerName: null,
    reviewedAt: null,
    reviewNotes: null,
  },
];

const MOCK_AUDIT: ClinicAuditRow[] = [
  {
    id: 'audit-001',
    organizationId: 'org-test',
    projectId: 'proj-test',
    patientId: 'pat-001',
    actorUserId: 'user-001',
    actorUserName: 'Dr. Sarah',
    actorRole: 'DOCTOR',
    action: 'BREAK_GLASS_ACTIVATED',
    resourceType: 'BREAK_GLASS',
    resourceId: 'grant-001',
    tamperHash: 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
    occurredAt: '2026-10-20T08:00:00Z',
  },
];

describe('ClinicReportsComponent (Slice S8)', () => {
  let fixture: ComponentFixture<ClinicReportsComponent>;
  let component: ClinicReportsComponent;
  let http: HttpTestingController;

  beforeEach(async () => {
    TestBed.resetTestingModule();
    await TestBed.configureTestingModule({
      imports: [ClinicReportsComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(ClinicReportsComponent);
    component = fixture.componentInstance;
    http = TestBed.inject(HttpTestingController);
  });

  async function tick(): Promise<void> {
    await new Promise<void>((resolve) => setTimeout(resolve, 0));
    fixture.detectChanges();
  }

  async function flushInitialRequests(activeGrants = [MOCK_ACTIVE_GRANT], productivity = MOCK_PRODUCTIVITY): Promise<void> {
    fixture.detectChanges();

    // 1. active break-glass
    const reqActive = http.expectOne((r) => r.url === '/api/v1/clinic/break-glass/active');
    reqActive.flush(activeGrants);

    // 2. initial productivity report
    const reqProd = http.expectOne((r) => r.url === '/api/v1/clinic/reports/appointment-productivity');
    reqProd.flush(productivity);

    await tick();
  }

  it('1. renders header, active emergency banner, and productivity metrics table', async () => {
    await flushInitialRequests();

    const banner = fixture.nativeElement.querySelector('[data-testid="active-break-glass-banner"]');
    expect(banner).not.toBeNull();
    expect(banner.textContent).toContain('Acute trauma emergency examination');

    const table = fixture.nativeElement.querySelector('[data-testid="productivity-table"]');
    expect(table).not.toBeNull();
    expect(table.textContent).toContain('Dr. Sarah Smith');
    expect(table.textContent).toContain('88%');
  });

  it('2. opens break-glass request modal and validates minimum 5 chars justification', async () => {
    await flushInitialRequests();

    component.openBreakGlassModal();
    fixture.detectChanges();

    const modal = fixture.nativeElement.querySelector('[data-testid="break-glass-modal"]');
    expect(modal).not.toBeNull();

    // Submit with short reason
    component.breakGlassReason = 'abc';
    component.submitBreakGlass();
    fixture.detectChanges();

    const err = fixture.nativeElement.querySelector('[data-testid="break-glass-error"]');
    expect(err).not.toBeNull();
    expect(err.textContent).toContain('at least 5 characters');
    expect(component.showBreakGlassModal()).toBe(true);

    // Submit with valid reason
    component.breakGlassReason = 'Urgent hemorrhage requiring full medical history';
    component.breakGlassDuration = 8;
    component.submitBreakGlass();

    const postReq = http.expectOne((r) => r.url === '/api/v1/clinic/break-glass' && r.method === 'POST');
    expect(postReq.request.body).toEqual({
      reason: 'Urgent hemorrhage requiring full medical history',
      durationHours: 8,
    });
    postReq.flush(MOCK_ACTIVE_GRANT);
    await tick();

    // Refreshes active grants and current tab
    const reqActive = http.expectOne((r) => r.url === '/api/v1/clinic/break-glass/active');
    reqActive.flush([MOCK_ACTIVE_GRANT]);
    await tick();
    const reqProd = http.expectOne((r) => r.url === '/api/v1/clinic/reports/appointment-productivity');
    reqProd.flush(MOCK_PRODUCTIVITY);

    await tick();
    expect(component.showBreakGlassModal()).toBe(false);
  });

  it('3. early revocation of break-glass access sends POST and refreshes banner', async () => {
    await flushInitialRequests();

    component.openRevokeModal('grant-001');
    fixture.detectChanges();

    expect(component.showRevokeModal()).toBe(true);
    component.revokeReason = 'Emergency stabilized by surgeon';
    component.submitRevoke();

    const postReq = http.expectOne((r) => r.url === '/api/v1/clinic/break-glass/grant-001/revoke' && r.method === 'POST');
    expect(postReq.request.body).toEqual({
      revocationReason: 'Emergency stabilized by surgeon',
    });
    postReq.flush({ revoked: true, grantId: 'grant-001' });
    await tick();

    // Refreshes active grants and tab
    const reqActive = http.expectOne((r) => r.url === '/api/v1/clinic/break-glass/active');
    reqActive.flush([]);
    await tick();
    const reqProd = http.expectOne((r) => r.url === '/api/v1/clinic/reports/appointment-productivity');
    reqProd.flush(MOCK_PRODUCTIVITY);

    await tick();
    expect(component.showRevokeModal()).toBe(false);
    expect(component.activeGrants().length).toBe(0);
  });

  it('4. switches to procedure utilization tab and renders table', async () => {
    await flushInitialRequests();

    component.setTab('procedures');
    fixture.detectChanges();

    const procReq = http.expectOne((r) => r.url === '/api/v1/clinic/reports/procedure-utilization');
    procReq.flush(MOCK_PROCEDURES);
    await tick();

    const table = fixture.nativeElement.querySelector('[data-testid="procedures-table"]');
    expect(table).not.toBeNull();
    expect(table.textContent).toContain('D0120');
    expect(table.textContent).toContain('Periodic Oral Evaluation');
    expect(table.textContent).toContain('Diagnostic');
  });

  it('5. switches to financial collections tab and renders currency cards', async () => {
    await flushInitialRequests();

    component.setTab('financials');
    fixture.detectChanges();

    const finReq = http.expectOne((r) => r.url === '/api/v1/clinic/reports/financial-collection');
    finReq.flush(MOCK_FINANCIALS);
    await tick();

    const panel = fixture.nativeElement.querySelector('[data-testid="panel-financials"]');
    expect(panel).not.toBeNull();
    expect(panel.textContent).toContain('JOD');
    expect(panel.textContent).toContain('87.5%');
  });

  it('6. switches to patient recall tab and renders overdue recall status badges', async () => {
    await flushInitialRequests();

    component.setTab('recall');
    fixture.detectChanges();

    const recallReq = http.expectOne((r) => r.url === '/api/v1/clinic/reports/patient-recall');
    recallReq.flush(MOCK_RECALL);
    await tick();

    const table = fixture.nativeElement.querySelector('[data-testid="recall-table"]');
    expect(table).not.toBeNull();
    expect(table.textContent).toContain('Layla Mansour');
    expect(table.textContent).toContain('219');
    expect(table.textContent).toContain('ROUTINE_PROPHYLAXIS_EXAM');
  });

  it('7. switches to compliance tab and allows approving review', async () => {
    await flushInitialRequests();

    component.setTab('compliance');
    fixture.detectChanges();

    const compReq = http.expectOne((r) => r.url === '/api/v1/clinic/reports/break-glass-compliance');
    compReq.flush(MOCK_COMPLIANCE);
    await tick();

    const table = fixture.nativeElement.querySelector('[data-testid="compliance-table"]');
    expect(table).not.toBeNull();
    expect(table.textContent).toContain('Dr. Tariq');
    expect(table.textContent).toContain('PENDING');

    component.reviewGrant('grant-001', 'APPROVED', 'Verified by clinical director');

    const revReq = http.expectOne((r) => r.url === '/api/v1/clinic/break-glass/grant-001/review' && r.method === 'POST');
    expect(revReq.request.body).toEqual({
      reviewStatus: 'APPROVED',
      reviewNotes: 'Verified by clinical director',
    });
    revReq.flush({ reviewed: true, grantId: 'grant-001' });
    await tick();

    // Refreshes compliance tab
    const compReq2 = http.expectOne((r) => r.url === '/api/v1/clinic/reports/break-glass-compliance');
    compReq2.flush([]);
    await tick();
  });

  it('8. switches to audit tab, renders records, and verifies cryptographic SHA-256 chain', async () => {
    await flushInitialRequests();

    component.setTab('audit');
    fixture.detectChanges();

    const auditReq = http.expectOne((r) => r.url === '/api/v1/clinic/audit');
    auditReq.flush(MOCK_AUDIT);
    await tick();

    const table = fixture.nativeElement.querySelector('[data-testid="audit-table"]');
    expect(table).not.toBeNull();
    expect(table.textContent).toContain('BREAK_GLASS_ACTIVATED');
    expect(table.textContent).toContain('e3b0c442');

    // Click verify chain
    component.verifyChainIntegrity();

    const verifyReq = http.expectOne((r) => r.url === '/api/v1/clinic/audit/verify');
    verifyReq.flush({ integrityVerified: true, algorithm: 'SHA-256' });
    await tick();

    expect(component.chainVerified()).toBe(true);
    const badge = fixture.nativeElement.querySelector('[data-testid="chain-status-badge"]');
    expect(badge).not.toBeNull();
    expect(badge.classList.contains('valid')).toBe(true);
  });

  it('9. exports report and renders signed cryptographic verification banner', async () => {
    await flushInitialRequests();

    component.exportCurrentReport();

    const expReq = http.expectOne((r) => r.url === '/api/v1/clinic/reports/export' && r.method === 'POST');
    expect(expReq.request.params.get('exportType')).toBe('APPOINTMENTS');

    const mockExport: ClinicAuditExportView = {
      exportType: 'APPOINTMENTS',
      generatedAt: '2026-10-20T10:00:00Z',
      totalRecords: 1,
      exportSignature: 'abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890',
      records: MOCK_PRODUCTIVITY,
    };
    expReq.flush(mockExport);
    await tick();

    expect(component.exportResult()).not.toBeNull();
    const banner = fixture.nativeElement.querySelector('[data-testid="export-signed-banner"]');
    expect(banner).not.toBeNull();
    expect(banner.textContent).toContain('abcdef1234567890');
  });
});
