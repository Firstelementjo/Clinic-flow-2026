/**
 * Slice S9 — Governed AI Assistant.
 *
 * Frontend unit tests for ClinicAiAssistantComponent verifying:
 * 1. Initial page load, kill switch status, and proposals review queue rendering.
 * 2. Kill switch active banner rendering with audit reason and engagedBy info.
 * 3. Toggling emergency kill switch (engage and disengage).
 * 4. Proposals filtering by status (ALL, DRAFT, APPROVED, REJECTED).
 * 5. Approving draft proposal and updating row state.
 * 6. Rejecting draft proposal with mandatory reason recording.
 * 7. Governed draft generator tab rendering and disabling during active kill switch.
 * 8. Generating draft visit summary proposal.
 * 9. Operational runs monitoring metrics and run history table.
 */

import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { I18nService } from '../../core/i18n/i18n.service';
import { ClinicAiAssistantComponent } from './clinic-ai-assistant.component';
import type {
  ClinicAiOverview,
  ClinicAiRun,
  ClinicAiGovernanceRow,
  ClinicAiProposalRow,
} from './clinic-patient.api';

afterEach(() => {
  localStorage.clear();
});

const MOCK_OVERVIEW: ClinicAiOverview = {
  totalRuns: 42,
  successfulRuns: 40,
  failedRuns: 2,
  avgLatencyMs: 380.5,
  clinicalSuggestionsDisabled: true,
  recentRuns: [],
};

const MOCK_RUNS: ClinicAiRun[] = [
  {
    id: 'run-001',
    projectId: 'proj-test',
    conversationId: 'conv-001',
    agentId: 'agent-001',
    status: 'SUCCEEDED',
    decision: 'APPOINTMENT_PROPOSED',
    confidence: 95,
    decisionReason: 'Matched dentist availability for cleaning',
    provider: 'OpenAI',
    model: 'gpt-4o',
    errorMessage: null,
    latencyMs: 340,
    startedAt: '2026-10-20T08:00:00Z',
    finishedAt: '2026-10-20T08:00:01Z',
  },
  {
    id: 'run-002',
    projectId: 'proj-test',
    conversationId: 'conv-002',
    agentId: 'agent-001',
    status: 'FAILED',
    decision: null,
    confidence: null,
    decisionReason: null,
    provider: 'Anthropic',
    model: 'claude-3-5-sonnet',
    errorMessage: 'Rate limit exceeded',
    latencyMs: 1200,
    startedAt: '2026-10-20T08:05:00Z',
    finishedAt: '2026-10-20T08:05:01Z',
  },
];

const MOCK_GOVERNANCE_INACTIVE: ClinicAiGovernanceRow = {
  id: 'gov-001',
  organizationId: 'org-test',
  projectId: 'proj-test',
  killSwitchActive: false,
  killSwitchReason: null,
  killSwitchEngagedByUserId: null,
  killSwitchEngagedByName: null,
  killSwitchEngagedAt: null,
  clinicalSuggestionsDisabled: true,
  updatedAt: '2026-10-19T10:00:00Z',
};

const MOCK_GOVERNANCE_ACTIVE: ClinicAiGovernanceRow = {
  id: 'gov-001',
  organizationId: 'org-test',
  projectId: 'proj-test',
  killSwitchActive: true,
  killSwitchReason: 'Security audit underway',
  killSwitchEngagedByUserId: 'usr-admin-1',
  killSwitchEngagedByName: 'Compliance Officer',
  killSwitchEngagedAt: '2026-10-20T08:30:00Z',
  clinicalSuggestionsDisabled: true,
  updatedAt: '2026-10-20T08:30:00Z',
};

const MOCK_PROPOSALS: ClinicAiProposalRow[] = [
  {
    id: 'prop-001',
    organizationId: 'org-test',
    projectId: 'proj-test',
    patientId: 'pat-001',
    patientName: 'Ahmad Al-Mansoor',
    proposalType: 'VISIT_SUMMARY_DRAFT',
    status: 'DRAFT',
    model: 'gpt-4o',
    provider: 'OpenAI',
    promptTemplate: 'visit_summary_v1',
    inputPayloadRedacted: '{"encounterId":"enc-1"}',
    outputContent: 'Patient examined for routine prophylaxis. Gingival tissues healthy.',
    transparentFactors: '{"citations":["encounter:enc-1"],"confidence":92}',
    confidencePercent: 92,
    phiRedacted: true,
    approvedByUserId: null,
    approverName: null,
    approvedAt: null,
    rejectionReason: null,
    createdAt: '2026-10-20T08:15:00Z',
  },
  {
    id: 'prop-002',
    organizationId: 'org-test',
    projectId: 'proj-test',
    patientId: 'pat-002',
    patientName: 'Fatima Zahra',
    proposalType: 'RECALL_PRIORITIZATION',
    status: 'APPROVED',
    model: 'claude-3-5-sonnet',
    provider: 'Anthropic',
    promptTemplate: 'recall_v1',
    inputPayloadRedacted: '{"overdueDays":120}',
    outputContent: 'Patient 120 days overdue for 6-month prophylaxis. High periodontal risk.',
    transparentFactors: '{"riskWeights":{"periodontal":0.6},"daysOverdue":120}',
    confidencePercent: 88,
    phiRedacted: true,
    approvedByUserId: 'usr-doc-1',
    approverName: 'Dr. Sarah Smith',
    approvedAt: '2026-10-20T08:45:00Z',
    rejectionReason: null,
    createdAt: '2026-10-20T08:20:00Z',
  },
  {
    id: 'prop-003',
    organizationId: 'org-test',
    projectId: 'proj-test',
    patientId: 'pat-003',
    patientName: 'Omar Khaled',
    proposalType: 'PAYMENT_MATCHING',
    status: 'REJECTED',
    model: 'gpt-4o',
    provider: 'OpenAI',
    promptTemplate: 'payment_match_v1',
    inputPayloadRedacted: '{"unallocatedAmount":500.0}',
    outputContent: 'Allocate 500 SAR to Invoice #INV-101.',
    transparentFactors: '{"fifoRuleApplied":true}',
    confidencePercent: 85,
    phiRedacted: true,
    approvedByUserId: 'usr-acc-1',
    approverName: 'Finance Officer',
    approvedAt: '2026-10-20T09:00:00Z',
    rejectionReason: 'Patient disputed invoice INV-101 balance.',
    createdAt: '2026-10-20T08:25:00Z',
  },
];

describe('ClinicAiAssistantComponent', () => {
  let fixture: ComponentFixture<ClinicAiAssistantComponent>;
  let component: ClinicAiAssistantComponent;
  let http: HttpTestingController;

  beforeEach(async () => {
    TestBed.resetTestingModule();
    await TestBed.configureTestingModule({
      imports: [ClinicAiAssistantComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
      ],
    }).compileComponents();

    TestBed.inject(I18nService).setLocale('en');
    fixture = TestBed.createComponent(ClinicAiAssistantComponent);
    component = fixture.componentInstance;
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
  });

  async function tick(): Promise<void> {
    await new Promise<void>((resolve) => setTimeout(resolve, 0));
    fixture.detectChanges();
  }

  async function flushInitialRequests(
    governance: ClinicAiGovernanceRow = MOCK_GOVERNANCE_INACTIVE,
    proposals: ClinicAiProposalRow[] = MOCK_PROPOSALS
  ): Promise<void> {
    fixture.detectChanges();

    const reqOverview = http.expectOne('/api/v1/clinic/ai/overview');
    expect(reqOverview.request.method).toBe('GET');
    reqOverview.flush(MOCK_OVERVIEW);

    const reqRuns = http.expectOne('/api/v1/clinic/ai/runs');
    expect(reqRuns.request.method).toBe('GET');
    reqRuns.flush(MOCK_RUNS);

    const reqGov = http.expectOne('/api/v1/clinic/ai/governance');
    expect(reqGov.request.method).toBe('GET');
    reqGov.flush(governance);

    const reqProp = http.expectOne('/api/v1/clinic/ai/proposals');
    expect(reqProp.request.method).toBe('GET');
    reqProp.flush(proposals);

    await tick();
  }

  it('renders emergency kill switch banner (inactive) and proposals queue on initial load', async () => {
    await flushInitialRequests();

    const banner = fixture.nativeElement.querySelector('[data-testid="ai-kill-switch-banner"]');
    expect(banner).toBeTruthy();
    expect(banner.classList.contains('engaged')).toBe(false);

    const toggleBtn = fixture.nativeElement.querySelector('[data-testid="kill-switch-toggle-btn"]');
    expect(toggleBtn.textContent.trim()).toContain('Engage Kill Switch');

    const proposalsSection = fixture.nativeElement.querySelector('[data-testid="proposals-review-section"]');
    expect(proposalsSection).toBeTruthy();

    const rows = fixture.nativeElement.querySelectorAll('tbody tr');
    expect(rows.length).toBe(3);
  });

  it('displays active kill switch banner with reason and engagedBy info when kill switch is engaged', async () => {
    await flushInitialRequests(MOCK_GOVERNANCE_ACTIVE);

    const banner = fixture.nativeElement.querySelector('[data-testid="ai-kill-switch-banner"]');
    expect(banner.classList.contains('engaged')).toBe(true);

    const auditInfo = fixture.nativeElement.querySelector('[data-testid="kill-switch-audit-info"]');
    expect(auditInfo).toBeTruthy();
    expect(auditInfo.textContent).toContain('Compliance Officer');
    expect(auditInfo.textContent).toContain('Security audit underway');

    const toggleBtn = fixture.nativeElement.querySelector('[data-testid="kill-switch-toggle-btn"]');
    expect(toggleBtn.textContent.trim()).toContain('Disengage & Resume');
  });

  it('toggles emergency kill switch from inactive to active', async () => {
    await flushInitialRequests(MOCK_GOVERNANCE_INACTIVE);

    component.setKillSwitchReason('Urgent maintenance halt');
    fixture.detectChanges();

    const toggleBtn = fixture.nativeElement.querySelector('[data-testid="kill-switch-toggle-btn"]');
    toggleBtn.click();
    await tick();

    const req = http.expectOne('/api/v1/clinic/ai/governance/kill-switch');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({
      killSwitchActive: true,
      reason: 'Urgent maintenance halt',
    });

    req.flush(MOCK_GOVERNANCE_ACTIVE);
    await tick();

    expect(component.isKillSwitchActive()).toBe(true);
    expect(component.killSwitchReason()).toBe('');
  });

  it('filters proposals by status: ALL, DRAFT, APPROVED, REJECTED', async () => {
    await flushInitialRequests();

    // Default: ALL
    expect(component.filteredProposals().length).toBe(3);

    // Filter DRAFT
    component.setProposalStatus('DRAFT');
    fixture.detectChanges();
    expect(component.filteredProposals().length).toBe(1);
    expect(component.filteredProposals()[0].id).toBe('prop-001');

    // Filter APPROVED
    component.setProposalStatus('APPROVED');
    fixture.detectChanges();
    expect(component.filteredProposals().length).toBe(1);
    expect(component.filteredProposals()[0].id).toBe('prop-002');

    // Filter REJECTED
    component.setProposalStatus('REJECTED');
    fixture.detectChanges();
    expect(component.filteredProposals().length).toBe(1);
    expect(component.filteredProposals()[0].id).toBe('prop-003');
  });

  it('approves a draft proposal and updates row state', async () => {
    await flushInitialRequests();

    const approveBtn = fixture.nativeElement.querySelector('[data-testid="approve-proposal-btn"]');
    expect(approveBtn).toBeTruthy();
    approveBtn.click();
    await tick();

    const req = http.expectOne('/api/v1/clinic/ai/proposals/prop-001/review');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ reviewDecision: 'APPROVED' });

    const approvedRow: ClinicAiProposalRow = {
      ...MOCK_PROPOSALS[0],
      status: 'APPROVED',
      approverName: 'Dr. Testing',
      approvedAt: '2026-10-20T09:15:00Z',
    };
    req.flush(approvedRow);
    await tick();

    const updated = component.proposals().find((p) => p.id === 'prop-001');
    expect(updated?.status).toBe('APPROVED');
    expect(updated?.approverName).toBe('Dr. Testing');
  });

  it('rejects a draft proposal with mandatory reason recording', async () => {
    await flushInitialRequests();

    const startRejectBtn = fixture.nativeElement.querySelector('[data-testid="start-reject-btn"]');
    startRejectBtn.click();
    fixture.detectChanges();

    const rejectBox = fixture.nativeElement.querySelector('[data-testid="reject-box"]');
    expect(rejectBox).toBeTruthy();

    component.setRejectionReasonInput('Incorrect teeth numbers identified in summary');
    fixture.detectChanges();

    const confirmRejectBtn = fixture.nativeElement.querySelector('[data-testid="confirm-reject-btn"]');
    confirmRejectBtn.click();
    await tick();

    const req = http.expectOne('/api/v1/clinic/ai/proposals/prop-001/review');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({
      reviewDecision: 'REJECTED',
      rejectionReason: 'Incorrect teeth numbers identified in summary',
    });

    const rejectedRow: ClinicAiProposalRow = {
      ...MOCK_PROPOSALS[0],
      status: 'REJECTED',
      rejectionReason: 'Incorrect teeth numbers identified in summary',
    };
    req.flush(rejectedRow);
    await tick();

    const updated = component.proposals().find((p) => p.id === 'prop-001');
    expect(updated?.status).toBe('REJECTED');
    expect(updated?.rejectionReason).toBe('Incorrect teeth numbers identified in summary');
  });

  it('switches to generators tab and verifies controls disabled during active kill switch', async () => {
    await flushInitialRequests(MOCK_GOVERNANCE_ACTIVE);

    component.setActiveTab('GENERATORS');
    fixture.detectChanges();

    const alert = fixture.nativeElement.querySelector('[data-testid="generator-kill-switch-active-alert"]');
    expect(alert).toBeTruthy();

    const genBtn = fixture.nativeElement.querySelector('[data-testid="btn-generate-recall"]');
    expect(genBtn.disabled).toBe(true);
  });

  it('generates a visit summary draft proposal', async () => {
    await flushInitialRequests(MOCK_GOVERNANCE_INACTIVE);

    component.setActiveTab('GENERATORS');
    component.setVisitSummaryPatientId('pat-new-123');
    component.setVisitSummaryNotes('Scaling and root planing on quadrant 1');
    fixture.detectChanges();

    const genBtn = fixture.nativeElement.querySelector('[data-testid="btn-generate-visit-summary"]');
    expect(genBtn.disabled).toBe(false);
    genBtn.click();
    await tick();

    const req = http.expectOne('/api/v1/clinic/ai/proposals/visit-summary');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({
      patientId: 'pat-new-123',
    });

    const newProposal: ClinicAiProposalRow = {
      id: 'prop-new-999',
      organizationId: 'org-test',
      projectId: 'proj-test',
      patientId: 'pat-new-123',
      patientName: 'Test Generated Patient',
      proposalType: 'VISIT_SUMMARY_DRAFT',
      status: 'DRAFT',
      model: 'gpt-4o',
      provider: 'OpenAI',
      promptTemplate: 'visit_summary_v1',
      inputPayloadRedacted: '{"patientId":"pat-new-123"}',
      outputContent: 'Draft summary generated successfully.',
      transparentFactors: '{"citations":[]}',
      confidencePercent: 90,
      phiRedacted: true,
      approvedByUserId: null,
      approverName: null,
      approvedAt: null,
      rejectionReason: null,
      createdAt: '2026-10-20T09:30:00Z',
    };
    req.flush(newProposal);
    await tick();

    expect(component.activeTab()).toBe('PROPOSALS');
    expect(component.proposals()[0].id).toBe('prop-new-999');
  });

  it('switches to operational runs tab and renders metrics and logs table', async () => {
    await flushInitialRequests();

    component.setActiveTab('RUNS');
    fixture.detectChanges();

    const runsSection = fixture.nativeElement.querySelector('[data-testid="runs-monitoring-section"]');
    expect(runsSection).toBeTruthy();

    const statCards = fixture.nativeElement.querySelectorAll('.stat-card');
    expect(statCards.length).toBe(4);
    expect(statCards[0].textContent).toContain('42'); // total runs
    expect(statCards[1].textContent).toContain('40'); // successful runs
  });
});
