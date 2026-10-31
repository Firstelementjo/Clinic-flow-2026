/**
 * `/app/clinic/payments` — Dental Clinic Payments & Official Receipts (BIL-02, BIL-06).
 */

import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ProjectContextService } from '../../core/context/project-context.service';
import { FormatService } from '../../core/format/format.service';
import { I18nService } from '../../core/i18n/i18n.service';
import type { Namespace } from '../../core/i18n/i18n.types';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { AsyncStateComponent } from '../../ui/async-state.component';
import {
  ClinicPatientApi,
  type ClinicInvoiceHeader,
  type ClinicPaymentMethod,
  type ClinicPaymentReceipt,
  type ClinicPaymentRow,
} from './clinic-patient.api';
import { clinicStrings } from './clinic.strings';

@Component({
  selector: 'fe-clinic-payments-page',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    AsyncStateComponent,
    CommonModule,
    FormsModule,
    RouterLink,
    TranslatePipe,
  ],
  template: `
    <div class="tl-page" data-testid="clinic-payments">
      <!-- Breadcrumbs -->
      <nav class="tl-breadcrumbs" aria-label="Breadcrumb">
        <a routerLink="/app/dashboard">Home</a>
        <span>»</span>
        <a routerLink="/app/clinic/invoices">Finance</a>
        <span>»</span>
        <span class="active">{{ 'clinic.payments.title' | t }}</span>
      </nav>

      <section class="tl-card">
        <!-- Toolbar matching ToothLogic standard -->
        <div class="tl-toolbar">
          <div class="tl-toolbar-left">
            <div class="tl-search-box">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
                <circle cx="11" cy="11" r="8"></circle>
                <line x1="21" y1="21" x2="16.65" y2="16.65"></line>
              </svg>
              <input
                type="search"
                placeholder="بحث برقم السند، الفاتورة، أو المريض..."
                [value]="searchQuery()"
                (input)="searchQuery.set($any($event.target).value)"
              />
            </div>

            <select
              class="tl-select"
              [value]="selectedMethodFilter()"
              (change)="selectedMethodFilter.set($any($event.target).value)"
              aria-label="تصفية حسب طريقة الدفع"
            >
              <option value="">جميع طرق الدفع</option>
              <option value="CASH">نقدًا (كاش)</option>
              <option value="CARD">بطاقة بنكية / POS</option>
              <option value="BANK_TRANSFER">تحويل بنكي</option>
            </select>
          </div>

          <div class="tl-toolbar-right">
            <button type="button" class="tl-btn tl-btn-teal" (click)="openRecordPaymentModal()">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16">
                <line x1="12" y1="5" x2="12" y2="19"></line>
                <line x1="5" y1="12" x2="19" y2="12"></line>
              </svg>
              <span>{{ 'clinic.payments.recordModal.title' | t }}</span>
            </button>
          </div>
        </div>

        <!-- Payments Table -->
        <fe-async-state
          [loading]="loading() && payments().length === 0"
          [error]="error()"
          [empty]="filteredPayments().length === 0 && !loading()"
          [hasValue]="filteredPayments().length > 0"
          [loadingLabel]="'state.loading' | t"
          [loadingHint]="'state.loadingHint' | t"
          [emptyTitle]="'clinic.payments.empty.title' | t"
          [emptyBody]="'clinic.payments.empty.body' | t"
          [errorTitle]="'clinic.error.title' | t"
          [errorBody]="'clinic.error.body' | t"
          [retryLabel]="'clinic.retry' | t"
          (retry)="loadPayments()"
        />

        @if (filteredPayments().length > 0) {
          <div class="tl-table-wrap">
            <table class="tl-table">
              <thead>
                <tr>
                  <th>{{ 'clinic.payments.number' | t }}</th>
                  <th>{{ 'clinic.payments.invoiceNumber' | t }}</th>
                  <th>{{ 'clinic.payments.patient' | t }}</th>
                  <th>{{ 'clinic.payments.amount' | t }}</th>
                  <th>{{ 'clinic.payments.method' | t }}</th>
                  <th>{{ 'clinic.payments.reference' | t }}</th>
                  <th>{{ 'clinic.payments.date' | t }}</th>
                  <th>{{ 'clinic.payments.recordedBy' | t }}</th>
                  <th style="text-align: center;">إجراءات</th>
                </tr>
              </thead>
              <tbody>
                @for (pmt of filteredPayments(); track pmt.id) {
                  <tr>
                    <td class="tl-mono font-bold">{{ pmt.paymentNumber }}</td>
                    <td class="tl-mono">{{ pmt.invoiceNumber }}</td>
                    <td>
                      <a [routerLink]="['/app/clinic/patients', pmt.patientId]" class="tl-patient-link">
                        {{ pmt.patientName || pmt.patientId }}
                      </a>
                    </td>
                    <td class="font-bold tl-amount-cell">
                      {{ pmt.amount | number:'1.3-3' }} {{ pmt.currency }}
                    </td>
                    <td>
                      <span class="tl-badge-pill" [class.is-cash]="pmt.paymentMethod === 'CASH'" [class.is-card]="pmt.paymentMethod === 'CARD'">
                        {{ methodLabel(pmt.paymentMethod) }}
                      </span>
                    </td>
                    <td class="tl-subtle-text">{{ pmt.referenceNumber || '-' }}</td>
                    <td>{{ formatDate(pmt.paymentDate) }}</td>
                    <td class="tl-subtle-text">{{ pmt.recordedByUserName || '-' }}</td>
                    <td style="text-align: center;">
                      <button type="button" class="tl-btn tl-btn-outline tl-btn-sm" (click)="viewReceipt(pmt.id)" title="عرض السند الرسمي">
                        <svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2">
                          <polyline points="6 9 6 2 18 2 18 9"></polyline>
                          <path d="M6 18H4a2 2 0 0 1-2-2v-5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v5a2 2 0 0 1-2 2h-2"></path>
                          <rect x="6" y="14" width="12" height="8"></rect>
                        </svg>
                        <span>{{ 'clinic.payments.viewReceipt' | t }}</span>
                      </button>
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        }
      </section>

      <!-- Standard ToothLogic Side Drawer (Record Payment) -->
      @if (isRecordOpen()) {
        <div class="tl-drawer-backdrop" (click)="isRecordOpen.set(false)">
          <aside
            class="tl-drawer"
            (click)="$event.stopPropagation()"
            role="dialog"
            aria-modal="true"
            aria-labelledby="record-drawer-title"
          >
            <header class="tl-drawer-header">
              <h2 id="record-drawer-title">{{ 'clinic.payments.recordModal.title' | t }}</h2>
              <button type="button" class="tl-close-btn" (click)="isRecordOpen.set(false)" aria-label="Close drawer">&times;</button>
            </header>

            <div class="tl-drawer-body">
              <!-- Section 1: Invoice Selection -->
              <section class="tl-form-section">
                <h3 class="tl-section-heading">بيانات الفاتورة المستحقة</h3>
                <label class="tl-field">
                  <span>اختر الفاتورة المستحقة *</span>
                  <select
                    [(ngModel)]="paymentForm.invoiceId"
                    (change)="onInvoiceSelected()"
                    class="tl-select full-width"
                  >
                    <option value="" disabled>-- اختر فاتورة مستحقة للتحصيل --</option>
                    @for (inv of unpaidInvoices(); track inv.id) {
                      <option [value]="inv.id">
                        {{ inv.invoiceNumber }} - {{ inv.patientName }} (المستحق: {{ inv.balanceDue | number:'1.3-3' }} {{ inv.currency }})
                      </option>
                    }
                  </select>
                </label>

                @if (selectedInvoice()) {
                  <div class="invoice-summary-card">
                    <div class="summary-row">
                      <span class="summary-lbl">المريض:</span>
                      <strong class="summary-val">{{ selectedInvoice()?.patientName }}</strong>
                    </div>
                    <div class="summary-row">
                      <span class="summary-lbl">إجمالي الفاتورة:</span>
                      <span class="summary-val">{{ selectedInvoice()?.totalAmount | number:'1.3-3' }} {{ selectedInvoice()?.currency }}</span>
                    </div>
                    <div class="summary-row">
                      <span class="summary-lbl">الرصيد المتبقي المستحق:</span>
                      <strong class="summary-val text-danger">{{ selectedInvoice()?.balanceDue | number:'1.3-3' }} {{ selectedInvoice()?.currency }}</strong>
                    </div>
                  </div>
                }
              </section>

              <!-- Section 2: Payment Parameters -->
              <section class="tl-form-section">
                <h3 class="tl-section-heading">تفاصيل التحصيل والدفع</h3>
                <div class="tl-form-grid-2">
                  <label class="tl-field">
                    <span>{{ 'clinic.payments.recordModal.amount' | t }} *</span>
                    <input
                      type="number"
                      [(ngModel)]="paymentForm.amount"
                      class="tl-input font-bold"
                      min="0.001"
                      [max]="selectedInvoice()?.balanceDue || 99999"
                      step="0.5"
                    />
                  </label>

                  <label class="tl-field">
                    <span>{{ 'clinic.payments.recordModal.method' | t }} *</span>
                    <select [(ngModel)]="paymentForm.method" class="tl-select full-width">
                      <option value="CASH">نقدًا (كاش)</option>
                      <option value="CARD">بطاقة بنكية / POS</option>
                      <option value="BANK_TRANSFER">تحويل بنكي</option>
                    </select>
                  </label>
                </div>

                <label class="tl-field">
                  <span>{{ 'clinic.payments.recordModal.ref' | t }}</span>
                  <input
                    type="text"
                    [(ngModel)]="paymentForm.referenceNumber"
                    class="tl-input"
                    placeholder="رقم إيصال مدى، الحوالة، أو مرجع الشيك..."
                  />
                </label>

                <label class="tl-field">
                  <span>{{ 'clinic.payments.recordModal.notes' | t }}</span>
                  <textarea
                    [(ngModel)]="paymentForm.notes"
                    class="tl-input tl-textarea"
                    rows="3"
                    placeholder="ملاحظات اختيارية عن الدفعة..."
                  ></textarea>
                </label>
              </section>
            </div>

            <footer class="tl-drawer-footer">
              <button type="button" class="tl-btn tl-btn-outline" (click)="isRecordOpen.set(false)">إلغاء</button>
              <button
                type="button"
                class="tl-btn tl-btn-teal"
                [disabled]="!paymentForm.invoiceId || !paymentForm.amount || submitting()"
                (click)="submitPayment()"
              >
                {{ submitting() ? 'جاري التسجيل...' : ('clinic.payments.recordModal.submit' | t) }}
              </button>
            </footer>
          </aside>
        </div>
      }

      <!-- Official Payment Receipt Modal (BIL-06) -->
      @if (isReceiptOpen() && receipt()) {
        <div class="tl-modal-backdrop" (click)="isReceiptOpen.set(false)">
          <div class="tl-modal-dialog" (click)="$event.stopPropagation()">
            <div class="receipt-card" id="printable-receipt">
              <div class="receipt-header">
                <div class="receipt-logo">
                  <h2>{{ receipt()?.clinicName || 'عيادة طب الأسنان' }}</h2>
                  <span class="official-badge">{{ 'clinic.payments.receipt.officialTitle' | t }}</span>
                </div>
                <div class="receipt-meta">
                  <div><strong>{{ 'clinic.payments.number' | t }}:</strong> {{ receipt()?.paymentNumber }}</div>
                  <div><strong>{{ 'clinic.payments.date' | t }}:</strong> {{ formatDate(receipt()?.paymentDate) }}</div>
                </div>
              </div>

              <hr class="receipt-divider" />

              <div class="receipt-details">
                <div class="detail-row">
                  <span class="lbl">{{ 'clinic.payments.patient' | t }}:</span>
                  <span class="val font-bold">{{ receipt()?.patientName }} ({{ receipt()?.patientNo }})</span>
                </div>
                <div class="detail-row">
                  <span class="lbl">{{ 'clinic.payments.invoiceNumber' | t }}:</span>
                  <span class="val tl-mono">{{ receipt()?.invoiceNumber }}</span>
                </div>
                <div class="detail-row">
                  <span class="lbl">{{ 'clinic.payments.method' | t }}:</span>
                  <span class="val">{{ methodLabel(receipt()?.paymentMethod) }}</span>
                </div>
                @if (receipt()?.referenceNumber) {
                  <div class="detail-row">
                    <span class="lbl">{{ 'clinic.payments.reference' | t }}:</span>
                    <span class="val">{{ receipt()?.referenceNumber }}</span>
                  </div>
                }
              </div>

              <div class="receipt-amount-box">
                <span class="lbl">المبلغ المقبوض</span>
                <span class="amount-val">{{ receipt()?.amount | number:'1.3-3' }} {{ receipt()?.currency }}</span>
              </div>

              <div class="receipt-footer-info">
                <div>الرصيد المتبقي على الفاتورة: <strong>{{ receipt()?.remainingInvoiceBalance | number:'1.3-3' }} {{ receipt()?.currency }}</strong></div>
                <div>سُجل بواسطة: {{ receipt()?.recordedByUserName }}</div>
              </div>

              <div class="signature-section">
                <div class="sig-box">
                  <span>{{ 'clinic.payments.receipt.signature' | t }}</span>
                  <div class="sig-line"></div>
                </div>
              </div>
            </div>

            <div class="receipt-modal-actions no-print">
              <button type="button" class="tl-btn tl-btn-outline" (click)="isReceiptOpen.set(false)">
                {{ 'clinic.payments.receipt.close' | t }}
              </button>
              <button type="button" class="tl-btn tl-btn-teal" (click)="printReceipt()">
                <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2">
                  <polyline points="6 9 6 2 18 2 18 9"></polyline>
                  <path d="M6 18H4a2 2 0 0 1-2-2v-5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v5a2 2 0 0 1-2 2h-2"></path>
                  <rect x="6" y="14" width="12" height="8"></rect>
                </svg>
                <span>{{ 'clinic.payments.printReceipt' | t }}</span>
              </button>
            </div>
          </div>
        </div>
      }
    </div>
  `,
  styles: [`
    .tl-patient-link {
      color: var(--tl-teal);
      text-decoration: none;
      font-weight: 600;
    }
    .tl-patient-link:hover {
      text-decoration: underline;
    }
    .tl-amount-cell {
      color: var(--ok);
      font-size: 13.5px;
    }
    .tl-subtle-text {
      color: var(--mute);
      font-size: 12px;
    }

    .tl-badge-pill {
      display: inline-flex;
      align-items: center;
      padding: 2px 8px;
      border-radius: 999px;
      font-size: 11.5px;
      font-weight: 600;
      background: var(--surf2);
      color: var(--mute);
      border: 1px solid var(--line);
    }
    .tl-badge-pill.is-cash {
      background: var(--ok-soft);
      color: var(--ok);
      border-color: rgba(16, 185, 129, 0.2);
    }
    .tl-badge-pill.is-card {
      background: var(--pri-soft);
      color: var(--tl-teal);
      border-color: rgba(44, 181, 166, 0.2);
    }

    /* Side Drawer standard */
    .tl-drawer-backdrop {
      position: fixed;
      inset: 0;
      background: var(--scrim);
      z-index: 1200;
      display: flex;
      justify-content: flex-end;
      backdrop-filter: blur(2px);
    }
    .tl-drawer {
      width: min(520px, 95vw);
      height: 100vh;
      background: var(--surf);
      display: flex;
      flex-direction: column;
      box-shadow: var(--shadow-panel);
      animation: drawerSlideIn 0.22s cubic-bezier(0.16, 1, 0.3, 1);
    }
    @keyframes drawerSlideIn {
      from { transform: translateX(100%); }
      to { transform: translateX(0); }
    }
    [dir="rtl"] .tl-drawer {
      animation: drawerSlideInRtl 0.22s cubic-bezier(0.16, 1, 0.3, 1);
    }
    @keyframes drawerSlideInRtl {
      from { transform: translateX(-100%); }
      to { transform: translateX(0); }
    }

    .tl-drawer-header {
      padding: 14px 20px;
      border-bottom: 1px solid var(--line);
      display: flex;
      align-items: center;
      justify-content: space-between;
      background: var(--surf);
    }
    .tl-drawer-header h2 {
      margin: 0;
      font-size: 16.5px;
      font-weight: 700;
      color: var(--ink);
    }
    .tl-close-btn {
      border: none;
      background: transparent;
      font-size: 22px;
      line-height: 1;
      cursor: pointer;
      color: var(--mute);
      padding: 0 4px;
    }
    .tl-close-btn:hover {
      color: var(--ink);
    }

    .tl-drawer-body {
      padding: 16px 20px;
      overflow-y: auto;
      flex: 1;
      display: flex;
      flex-direction: column;
      gap: 16px;
    }
    .tl-drawer-footer {
      padding: 12px 20px;
      border-top: 1px solid var(--line);
      display: flex;
      justify-content: flex-end;
      gap: 8px;
      background: var(--surf);
    }

    .tl-form-section {
      display: flex;
      flex-direction: column;
      gap: 10px;
    }
    .tl-section-heading {
      margin: 0;
      font-size: 12px;
      font-weight: 700;
      color: var(--tl-teal);
      text-transform: uppercase;
      letter-spacing: 0.04em;
    }
    .tl-field {
      display: flex;
      flex-direction: column;
      gap: 4px;
      font-size: 12.5px;
      color: var(--ink);
      font-weight: 500;
    }
    .tl-form-grid-2 {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 10px;
    }
    .full-width {
      width: 100%;
    }
    .tl-textarea {
      resize: vertical;
      min-height: 60px;
      padding: 8px 10px;
      font-family: inherit;
    }

    .invoice-summary-card {
      background: var(--surf2);
      border: 1px solid var(--line);
      border-radius: 8px;
      padding: 10px 14px;
      display: flex;
      flex-direction: column;
      gap: 6px;
      font-size: 12.5px;
    }
    .summary-row {
      display: flex;
      justify-content: space-between;
      align-items: center;
    }
    .summary-lbl {
      color: var(--mute);
    }
    .text-danger {
      color: var(--bad);
    }

    /* Modal for Official Receipt */
    .tl-modal-backdrop {
      position: fixed;
      inset: 0;
      background: var(--scrim);
      backdrop-filter: blur(4px);
      display: flex;
      align-items: center;
      justify-content: center;
      z-index: 1300;
      padding: 16px;
    }
    .tl-modal-dialog {
      background: var(--surf);
      border-radius: 12px;
      width: 100%;
      max-width: 600px;
      max-height: 92vh;
      overflow-y: auto;
      box-shadow: var(--shadow-panel);
      border: 1px solid var(--line);
      display: flex;
      flex-direction: column;
    }
    .receipt-modal-actions {
      padding: 12px 18px;
      border-top: 1px solid var(--line);
      display: flex;
      justify-content: flex-end;
      gap: 8px;
      background: var(--surf2);
    }

    /* Official Receipt Paper Card */
    .receipt-card {
      padding: 24px;
      background: var(--surf);
    }
    .receipt-header {
      display: flex;
      justify-content: space-between;
      align-items: flex-start;
    }
    .receipt-logo h2 {
      margin: 0;
      font-size: 1.25rem;
      color: var(--ink);
    }
    .official-badge {
      display: inline-block;
      margin-top: 4px;
      background: var(--pri-soft);
      color: var(--tl-teal);
      padding: 2px 8px;
      border-radius: 4px;
      font-size: 11px;
      font-weight: 700;
    }
    .receipt-meta {
      text-align: end;
      font-size: 12px;
      color: var(--mute);
      display: flex;
      flex-direction: column;
      gap: 3px;
    }
    .receipt-divider {
      margin: 14px 0;
      border: none;
      border-top: 1px dashed var(--line);
    }
    .receipt-details {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 12px;
      margin-bottom: 16px;
    }
    .detail-row {
      display: flex;
      flex-direction: column;
      gap: 2px;
    }
    .detail-row .lbl {
      font-size: 11.5px;
      color: var(--mute);
    }
    .detail-row .val {
      font-size: 13px;
      color: var(--ink);
    }
    .receipt-amount-box {
      background: var(--ok-soft);
      border: 1px solid rgba(16, 185, 129, 0.3);
      border-radius: 8px;
      padding: 12px;
      text-align: center;
      margin-bottom: 16px;
    }
    .receipt-amount-box .lbl {
      font-size: 12px;
      color: var(--ok);
      font-weight: 600;
      display: block;
      margin-bottom: 2px;
    }
    .receipt-amount-box .amount-val {
      font-size: 1.5rem;
      font-weight: 800;
      color: var(--ok);
    }
    .receipt-footer-info {
      display: flex;
      justify-content: space-between;
      font-size: 11.5px;
      color: var(--mute);
      margin-bottom: 20px;
    }
    .signature-section {
      display: flex;
      justify-content: flex-end;
      padding-top: 10px;
    }
    .sig-box {
      text-align: center;
      width: 180px;
      font-size: 12px;
      color: var(--mute);
    }
    .sig-line {
      margin-top: 36px;
      border-bottom: 1px solid var(--line);
    }

    @media print {
      .no-print { display: none !important; }
      .tl-modal-backdrop { position: static; background: none; padding: 0; backdrop-filter: none; }
      .tl-modal-dialog { box-shadow: none; max-width: 100%; border: none; }
      .receipt-card { padding: 0; }
    }
  `],
})
export class ClinicPaymentsComponent implements OnInit {
  private readonly api = inject(ClinicPatientApi);
  private readonly projectContext = inject(ProjectContextService);
  private readonly format = inject(FormatService);

  readonly payments = signal<ClinicPaymentRow[]>([]);
  readonly unpaidInvoices = signal<ClinicInvoiceHeader[]>([]);
  readonly selectedInvoice = signal<ClinicInvoiceHeader | null>(null);
  readonly receipt = signal<ClinicPaymentReceipt | null>(null);

  readonly searchQuery = signal<string>('');
  readonly selectedMethodFilter = signal<string>('');

  readonly loading = signal<boolean>(true);
  readonly error = signal<boolean>(false);
  readonly submitting = signal<boolean>(false);

  readonly isRecordOpen = signal<boolean>(false);
  readonly isReceiptOpen = signal<boolean>(false);

  paymentForm = {
    invoiceId: '',
    amount: 0,
    method: 'CASH' as ClinicPaymentMethod,
    referenceNumber: '',
    notes: '',
  };

  readonly filteredPayments = computed<ClinicPaymentRow[]>(() => {
    const list = this.payments();
    const query = this.searchQuery().trim().toLowerCase();
    const method = this.selectedMethodFilter();

    return list.filter((p) => {
      const matchMethod = !method || p.paymentMethod === method;
      if (!matchMethod) return false;

      if (!query) return true;
      const patient = (p.patientName || '').toLowerCase();
      const num = (p.paymentNumber || '').toLowerCase();
      const inv = (p.invoiceNumber || '').toLowerCase();
      const ref = (p.referenceNumber || '').toLowerCase();
      return patient.includes(query) || num.includes(query) || inv.includes(query) || ref.includes(query);
    });
  });

  constructor() {
    inject(I18nService).register({ clinic: clinicStrings as Namespace });
  }

  ngOnInit(): void {
    this.loadPayments();
  }

  async loadPayments(): Promise<void> {
    this.loading.set(true);
    this.error.set(false);
    try {
      const proj = this.projectContext.selectedProjectId() || undefined;
      const list = await this.api.payments(proj ? { projectId: proj } : {});
      this.payments.set(list);
    } catch {
      this.error.set(true);
    } finally {
      this.loading.set(false);
    }
  }

  async openRecordPaymentModal(): Promise<void> {
    this.paymentForm = {
      invoiceId: '',
      amount: 0,
      method: 'CASH',
      referenceNumber: '',
      notes: '',
    };
    this.selectedInvoice.set(null);
    this.isRecordOpen.set(true);

    try {
      const proj = this.projectContext.selectedProjectId() || undefined;
      const allInvoices = await this.api.invoices(proj ? { projectId: proj } : {});
      this.unpaidInvoices.set(allInvoices.filter((i) => i.status !== 'CANCELLED' && i.balanceDue > 0));
    } catch {
      // non-blocking
    }
  }

  onInvoiceSelected(): void {
    const inv = this.unpaidInvoices().find((i) => i.id === this.paymentForm.invoiceId) || null;
    this.selectedInvoice.set(inv);
    if (inv) {
      this.paymentForm.amount = inv.balanceDue;
    }
  }

  async submitPayment(): Promise<void> {
    if (!this.paymentForm.invoiceId || !this.paymentForm.amount) return;
    this.submitting.set(true);
    try {
      const proj = this.projectContext.selectedProjectId() || undefined;
      const rec = await this.api.recordPayment(
        this.paymentForm.invoiceId,
        {
          amount: Number(this.paymentForm.amount),
          paymentMethod: this.paymentForm.method,
          referenceNumber: this.paymentForm.referenceNumber,
          notes: this.paymentForm.notes,
          idempotencyKey: crypto.randomUUID(),
        },
        proj ? { projectId: proj } : {},
      );

      this.isRecordOpen.set(false);
      this.receipt.set(rec);
      this.isReceiptOpen.set(true);
      await this.loadPayments();
    } catch (err) {
      alert('تعذر تسجيل الدفعة: ' + (err as Error).message);
    } finally {
      this.submitting.set(false);
    }
  }

  async viewReceipt(paymentId: string): Promise<void> {
    try {
      const proj = this.projectContext.selectedProjectId() || undefined;
      const rec = await this.api.paymentReceipt(paymentId, proj ? { projectId: proj } : {});
      this.receipt.set(rec);
      this.isReceiptOpen.set(true);
    } catch (err) {
      alert('تعذر قراءة بيانات الإيصال: ' + (err as Error).message);
    }
  }

  printReceipt(): void {
    window.print();
  }

  formatDate(dateStr?: string): string {
    if (!dateStr) return '-';
    try {
      return this.format.dateTime(dateStr);
    } catch {
      return dateStr.substring(0, 10);
    }
  }

  methodLabel(m?: string): string {
    switch (m) {
      case 'CASH':
        return 'نقدًا (كاش)';
      case 'CARD':
        return 'بطاقة بنكية';
      case 'BANK_TRANSFER':
        return 'تحويل بنكي';
      default:
        return m || '-';
    }
  }
}
