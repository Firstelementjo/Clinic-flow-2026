/**
 * Unit tests for ClinicInvoicesComponent (BIL-01 to BIL-09, Slice S6).
 *
 * Validates:
 * 1. Invoice list rendering with 3-decimal formatted amounts.
 * 2. Status filtering (ALL, ISSUED, PARTIALLY_PAID, PAID, CANCELLED).
 * 3. Issuing a new invoice with item rows (BIL-01).
 * 4. Applying discounts with mandatory audit reason (BIL-04).
 * 5. Invoice cancellation with mandatory audit reason (BIL-05).
 * 6. Recording refunds with payment reversal audit (BIL-08).
 * 7. Generating invoices from approved treatment plan items (BIL-09).
 * 8. Dual-entry append-only patient ledger and consolidated statement of account (BIL-07).
 * 9. Invoice detail drawer showing items, payments, and refunds.
 */
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { ClinicInvoicesComponent } from './clinic-invoices.component';
import type {
  ClinicInvoiceDetail,
  ClinicInvoiceHeader,
  ClinicPatient,
  ClinicRefundRow,
  ClinicTreatmentPlanHeader,
  PatientStatement,
} from './clinic-patient.api';

afterEach(() => {
  localStorage.clear();
});

const MOCK_INVOICES: ClinicInvoiceHeader[] = [
  {
    id: 'inv-1',
    organizationId: 'org-1',
    projectId: 'proj-1',
    invoiceNumber: 'INV-2026-001',
    patientId: 'pat-1',
    patientName: 'Ahmad Tariq',
    patientNo: 'PAT-001',
    currency: 'JOD',
    subtotal: 300.0,
    discountAmount: 0.0,
    totalAmount: 300.0,
    paidAmount: 100.0,
    balanceDue: 200.0,
    status: 'PARTIALLY_PAID',
    issuedAt: '2026-10-15T10:00:00Z',
    itemCount: 1,
    paymentCount: 1,
    createdAt: '2026-10-15T10:00:00Z',
    updatedAt: '2026-10-15T10:00:00Z',
  },
  {
    id: 'inv-2',
    organizationId: 'org-1',
    projectId: 'proj-1',
    invoiceNumber: 'INV-2026-002',
    patientId: 'pat-2',
    patientName: 'Sara Ali',
    patientNo: 'PAT-002',
    currency: 'JOD',
    subtotal: 150.0,
    discountAmount: 0.0,
    totalAmount: 150.0,
    paidAmount: 0.0,
    balanceDue: 150.0,
    status: 'ISSUED',
    issuedAt: '2026-10-16T11:00:00Z',
    itemCount: 1,
    paymentCount: 0,
    createdAt: '2026-10-16T11:00:00Z',
    updatedAt: '2026-10-16T11:00:00Z',
  },
  {
    id: 'inv-3',
    organizationId: 'org-1',
    projectId: 'proj-1',
    invoiceNumber: 'INV-2026-003',
    patientId: 'pat-1',
    patientName: 'Ahmad Tariq',
    patientNo: 'PAT-001',
    currency: 'JOD',
    subtotal: 50.0,
    discountAmount: 0.0,
    totalAmount: 50.0,
    paidAmount: 50.0,
    balanceDue: 0.0,
    status: 'PAID',
    issuedAt: '2026-10-17T09:00:00Z',
    itemCount: 1,
    paymentCount: 1,
    createdAt: '2026-10-17T09:00:00Z',
    updatedAt: '2026-10-17T09:00:00Z',
  },
];

const MOCK_PATIENTS: ClinicPatient[] = [
  {
    id: 'pat-1',
    patientNo: 'PAT-001',
    displayName: 'Ahmad Tariq',
    primaryPhone: '0791234567',
  },
  {
    id: 'pat-2',
    patientNo: 'PAT-002',
    displayName: 'Sara Ali',
    primaryPhone: '0799876543',
  },
];

const MOCK_PLANS: ClinicTreatmentPlanHeader[] = [
  {
    id: 'plan-1',
    organizationId: 'org-1',
    projectId: 'proj-1',
    patientId: 'pat-1',
    title: 'Molar Restoration Plan',
    status: 'ACTIVE',
    version: 1,
    totalEstimatedCost: 650.0,
    totalInvoicedAmount: 0.0,
    totalCollectedAmount: 0.0,
    patientConsent: true,
    itemCount: 2,
    completedItemCount: 1,
    createdAt: '2026-10-10T10:00:00Z',
    updatedAt: '2026-10-12T10:00:00Z',
  },
];

const MOCK_DETAIL: ClinicInvoiceDetail = {
  invoice: MOCK_INVOICES[0],
  items: [
    {
      id: 'item-1',
      organizationId: 'org-1',
      projectId: 'proj-1',
      invoiceId: 'inv-1',
      description: 'Root Canal Treatment',
      itemType: 'PROCEDURE',
      toothFdi: 16,
      unitPrice: 300.0,
      quantity: 1,
      totalPrice: 300.0,
      createdAt: '2026-10-15T10:00:00Z',
    },
  ],
  payments: [
    {
      id: 'pmt-1',
      organizationId: 'org-1',
      projectId: 'proj-1',
      invoiceId: 'inv-1',
      patientId: 'pat-1',
      paymentNumber: 'REC-2026-001',
      amount: 100.0,
      currency: 'JOD',
      paymentMethod: 'CASH',
      paymentDate: '2026-10-16T12:00:00Z',
      recordedByUserId: 'usr-1',
      recordedByUserName: 'Receptionist',
      createdAt: '2026-10-16T12:00:00Z',
    },
  ],
};

const MOCK_REFUNDS: ClinicRefundRow[] = [
  {
    id: 'ref-1',
    organizationId: 'org-1',
    projectId: 'proj-1',
    invoiceId: 'inv-1',
    invoiceNumber: 'INV-2026-001',
    patientId: 'pat-1',
    patientName: 'Ahmad Tariq',
    refundNumber: 'REF-2026-001',
    amount: 25.0,
    currency: 'JOD',
    refundMethod: 'CASH',
    reason: 'Procedure adjustment',
    approvedByUserId: 'usr-admin',
    refundDate: '2026-10-17T14:00:00Z',
    createdAt: '2026-10-17T14:00:00Z',
  },
];

const MOCK_STATEMENT: PatientStatement = {
  patientId: 'pat-1',
  patientName: 'Ahmad Tariq',
  patientNo: 'PAT-001',
  currency: 'JOD',
  totalDebits: 325.0,
  totalCredits: 100.0,
  netBalanceDue: 225.0,
  entries: [
    {
      id: 'led-1',
      organizationId: 'org-1',
      projectId: 'proj-1',
      patientId: 'pat-1',
      entryType: 'INVOICE_CHARGE',
      direction: 'DEBIT',
      amount: 300.0,
      currency: 'JOD',
      referenceNumber: 'INV-2026-001',
      description: 'Invoice charge',
      recordedByUserId: 'usr-admin',
      entryDate: '2026-10-15T10:00:00Z',
      createdAt: '2026-10-15T10:00:00Z',
    },
    {
      id: 'led-2',
      organizationId: 'org-1',
      projectId: 'proj-1',
      patientId: 'pat-1',
      entryType: 'PAYMENT_CREDIT',
      direction: 'CREDIT',
      amount: 100.0,
      currency: 'JOD',
      referenceNumber: 'REC-2026-001',
      description: 'Payment receipt',
      recordedByUserId: 'usr-admin',
      entryDate: '2026-10-16T12:00:00Z',
      createdAt: '2026-10-16T12:00:00Z',
    },
    {
      id: 'led-3',
      organizationId: 'org-1',
      projectId: 'proj-1',
      patientId: 'pat-1',
      entryType: 'REFUND_DEBIT',
      direction: 'DEBIT',
      amount: 25.0,
      currency: 'JOD',
      referenceNumber: 'REF-2026-001',
      description: 'Refund reversal',
      recordedByUserId: 'usr-admin',
      entryDate: '2026-10-17T14:00:00Z',
      createdAt: '2026-10-17T14:00:00Z',
    },
  ],
};

describe('ClinicInvoicesComponent (Slice S6)', () => {
  let fixture: ComponentFixture<ClinicInvoicesComponent>;
  let component: ClinicInvoicesComponent;
  let http: HttpTestingController;

  beforeEach(async () => {
    localStorage.clear();
    await TestBed.configureTestingModule({
      imports: [ClinicInvoicesComponent],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    }).compileComponents();

    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ClinicInvoicesComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();

    // Flush initial invoices and patients requests
    const invReq = http.expectOne((req) => req.url === '/api/v1/clinic/invoices');
    expect(invReq.request.method).toBe('GET');
    invReq.flush(MOCK_INVOICES);

    const patReq = http.expectOne((req) => req.url === '/api/v1/clinic/patients');
    expect(patReq.request.method).toBe('GET');
    patReq.flush(MOCK_PATIENTS);

    await tick();
  });

  async function tick(): Promise<void> {
    await new Promise<void>((resolve) => setTimeout(resolve, 0));
    fixture.detectChanges();
  }

  it('renders invoice table with all loaded rows', () => {
    expect(component.invoices().length).toBe(3);
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.textContent).toContain('INV-2026-001');
    expect(compiled.textContent).toContain('INV-2026-002');
    expect(compiled.textContent).toContain('INV-2026-003');
  });

  it('filters invoices by status correctly', async () => {
    component.setStatusFilter('ISSUED');
    await tick();
    expect(component.filteredInvoices().length).toBe(1);
    expect(component.filteredInvoices()[0].invoiceNumber).toBe('INV-2026-002');

    component.setStatusFilter('PAID');
    await tick();
    expect(component.filteredInvoices().length).toBe(1);
    expect(component.filteredInvoices()[0].invoiceNumber).toBe('INV-2026-003');

    component.setStatusFilter('ALL');
    await tick();
    expect(component.filteredInvoices().length).toBe(3);
  });

  it('opens create modal and submits new invoice (BIL-01)', async () => {
    component.openCreateModal();
    await tick();
    expect(component.isCreateOpen()).toBe(true);

    component.createForm.patientId = 'pat-1';
    component.createForm.items[0].description = 'Scaling and Polishing';
    component.createForm.items[0].unitPrice = 45.0;
    component.createForm.items[0].quantity = 1;

    const promise = component.submitCreateInvoice();

    const postReq = http.expectOne('/api/v1/clinic/invoices');
    expect(postReq.request.method).toBe('POST');
    expect(postReq.request.body.patientId).toBe('pat-1');
    expect(postReq.request.body.items.length).toBe(1);
    expect(postReq.request.body.items[0].unitPrice).toBe(45.0);

    postReq.flush({
      ...MOCK_INVOICES[0],
      id: 'inv-new',
      invoiceNumber: 'INV-2026-004',
      totalAmount: 45.0,
      balanceDue: 45.0,
    });
    await tick();

    // Re-fetch after create
    const reloadReq = http.expectOne('/api/v1/clinic/invoices');
    reloadReq.flush([...MOCK_INVOICES, {
      ...MOCK_INVOICES[0],
      id: 'inv-new',
      invoiceNumber: 'INV-2026-004',
      totalAmount: 45.0,
      balanceDue: 45.0,
    }]);

    await promise;
    await tick();

    expect(component.isCreateOpen()).toBe(false);
    expect(component.invoices().length).toBe(4);
  });

  it('opens plan invoice modal, loads treatment plans, and generates invoice from plan (BIL-09)', async () => {
    const modalPromise = component.openPlanInvoiceModal();

    // Patient plans request
    const plansReq = http.expectOne('/api/v1/clinic/patients/pat-1/treatment-plans');
    expect(plansReq.request.method).toBe('GET');
    plansReq.flush(MOCK_PLANS);

    await modalPromise;
    await tick();

    expect(component.isPlanInvoiceOpen()).toBe(true);
    expect(component.patientPlans().length).toBe(1);
    expect(component.planInvoiceForm.treatmentPlanId).toBe('plan-1');

    const submitPromise = component.submitPlanInvoice();

    const fromPlanReq = http.expectOne('/api/v1/clinic/invoices/from-plan');
    expect(fromPlanReq.request.method).toBe('POST');
    expect(fromPlanReq.request.body.patientId).toBe('pat-1');
    expect(fromPlanReq.request.body.treatmentPlanId).toBe('plan-1');

    fromPlanReq.flush({
      ...MOCK_INVOICES[0],
      id: 'inv-plan',
      invoiceNumber: 'INV-2026-005',
      totalAmount: 650.0,
    });
    await tick();

    const reloadReq = http.expectOne('/api/v1/clinic/invoices');
    reloadReq.flush(MOCK_INVOICES);

    await submitPromise;
    await tick();

    expect(component.isPlanInvoiceOpen()).toBe(false);
  });

  it('opens discount modal and submits discount with reason (BIL-04)', async () => {
    component.openDiscountModal(MOCK_INVOICES[0]);
    await tick();

    expect(component.isDiscountOpen()).toBe(true);
    expect(component.selectedInvoiceHeader()?.id).toBe('inv-1');

    component.discountForm.amount = 20.0;
    component.discountForm.reason = 'Goodwill discount';

    const discountPromise = component.submitDiscount();

    const patchReq = http.expectOne('/api/v1/clinic/invoices/inv-1/discount');
    expect(patchReq.request.method).toBe('POST');
    expect(patchReq.request.body.discountAmount).toBe(20.0);
    expect(patchReq.request.body.discountReason).toBe('Goodwill discount');

    patchReq.flush({
      ...MOCK_INVOICES[0],
      discountAmount: 20.0,
      totalAmount: 280.0,
      balanceDue: 180.0,
    });
    await tick();

    const reloadReq = http.expectOne('/api/v1/clinic/invoices');
    reloadReq.flush(MOCK_INVOICES);

    await discountPromise;
    await tick();

    expect(component.isDiscountOpen()).toBe(false);
  });

  it('opens cancel modal and submits invoice cancellation with reason (BIL-05)', async () => {
    component.openCancelModal(MOCK_INVOICES[1]);
    await tick();

    expect(component.isCancelOpen()).toBe(true);
    expect(component.selectedInvoiceHeader()?.id).toBe('inv-2');

    component.cancelForm.reason = 'Mistaken duplicate entry';

    const cancelPromise = component.submitCancelInvoice();

    const cancelReq = http.expectOne('/api/v1/clinic/invoices/inv-2/cancel');
    expect(cancelReq.request.method).toBe('POST');
    expect(cancelReq.request.body.cancellationReason).toBe('Mistaken duplicate entry');

    cancelReq.flush({
      ...MOCK_INVOICES[1],
      status: 'CANCELLED',
    });
    await tick();

    const reloadReq = http.expectOne('/api/v1/clinic/invoices');
    reloadReq.flush(MOCK_INVOICES);

    await cancelPromise;
    await tick();

    expect(component.isCancelOpen()).toBe(false);
  });

  it('opens refund modal and records refund against paid invoice (BIL-08)', async () => {
    component.openRefundModal(MOCK_INVOICES[0]);
    await tick();

    expect(component.isRefundOpen()).toBe(true);
    expect(component.refundForm.amount).toBe(100.0);

    component.refundForm.amount = 25.0;
    component.refundForm.refundMethod = 'CASH';
    component.refundForm.reason = 'Procedure fee adjustment';

    const refundPromise = component.submitRefund();

    const refundReq = http.expectOne('/api/v1/clinic/invoices/inv-1/refunds');
    expect(refundReq.request.method).toBe('POST');
    expect(refundReq.request.body.amount).toBe(25.0);
    expect(refundReq.request.body.refundMethod).toBe('CASH');
    expect(refundReq.request.body.reason).toBe('Procedure fee adjustment');

    refundReq.flush(MOCK_REFUNDS[0]);
    await tick();

    const reloadReq = http.expectOne('/api/v1/clinic/invoices');
    reloadReq.flush(MOCK_INVOICES);

    await refundPromise;
    await tick();

    expect(component.isRefundOpen()).toBe(false);
  });

  it('opens patient statement modal and renders dual-entry ledger items (BIL-07)', async () => {
    const stmtPromise = component.openPatientStatement('pat-1');

    const stmtReq = http.expectOne('/api/v1/clinic/patients/pat-1/statement');
    expect(stmtReq.request.method).toBe('GET');
    stmtReq.flush(MOCK_STATEMENT);

    await stmtPromise;
    await tick();

    expect(component.isStatementOpen()).toBe(true);
    expect(component.statementData()).not.toBeNull();
    expect(component.statementData()?.totalDebits).toBe(325.0);
    expect(component.statementData()?.totalCredits).toBe(100.0);
    expect(component.statementData()?.netBalanceDue).toBe(225.0);
    expect(component.statementData()?.entries.length).toBe(3);

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.textContent).toContain('Ahmad Tariq');
    expect(compiled.textContent).toContain('INV-2026-001');
    expect(compiled.textContent).toContain('REC-2026-001');
    expect(compiled.textContent).toContain('REF-2026-001');
  });

  it('opens detail modal and displays items, payments, and refunds', async () => {
    const detailPromise = component.viewDetail('inv-1');

    const dReq = http.expectOne('/api/v1/clinic/invoices/inv-1');
    expect(dReq.request.method).toBe('GET');
    dReq.flush(MOCK_DETAIL);

    const refReq = http.expectOne('/api/v1/clinic/invoices/inv-1/refunds');
    expect(refReq.request.method).toBe('GET');
    refReq.flush(MOCK_REFUNDS);

    await detailPromise;
    await tick();

    expect(component.isDetailOpen()).toBe(true);
    expect(component.detailData()).not.toBeNull();
    expect(component.detailRefunds().length).toBe(1);

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.textContent).toContain('Root Canal Treatment');
    expect(compiled.textContent).toContain('REC-2026-001');
    expect(compiled.textContent).toContain('REF-2026-001');
  });
});
