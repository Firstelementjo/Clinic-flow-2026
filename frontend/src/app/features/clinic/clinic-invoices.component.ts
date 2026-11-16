/**
 * `/app/clinic/invoices` — Dental Clinic Invoices Management (BIL-01 to BIL-09).
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
import { ActivatedRoute, RouterLink } from '@angular/router';
import { ProjectContextService } from '../../core/context/project-context.service';
import { FormatService } from '../../core/format/format.service';
import { I18nService } from '../../core/i18n/i18n.service';
import type { Namespace } from '../../core/i18n/i18n.types';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { AsyncStateComponent } from '../../ui/async-state.component';
import { PageHeaderComponent } from '../../ui/page-header.component';
import { StatusChipComponent, type StatusTone } from '../../ui/status-chip.component';
import {
  ClinicPatientApi,
  type ClinicInvoiceDetail,
  type ClinicInvoiceHeader,
  type ClinicInvoiceStatus,
  type ClinicPatient,
  type ClinicRefundRow,
  type ClinicTreatmentPlanHeader,
  type CreateInvoiceItemPayload,
  type PatientStatement,
} from './clinic-patient.api';
import { clinicStrings } from './clinic.strings';

@Component({
  selector: 'fe-clinic-invoices-page',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    AsyncStateComponent,
    CommonModule,
    FormsModule,
    PageHeaderComponent,
    RouterLink,
    StatusChipComponent,
    TranslatePipe,
  ],
  template: `
    <div class="fe-page" data-testid="clinic-invoices">
      <fe-page-header
        [title]="'clinic.invoices.title'"
        [description]="'clinic.invoices.description'"
      >
        <div class="header-actions">
          <button class="fe-btn fe-btn--secondary" (click)="exportInvoicesCsv()" [disabled]="filteredInvoices().length === 0">
            <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
              <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path>
              <polyline points="7 10 12 15 17 10"></polyline>
              <line x1="12" y1="15" x2="12" y2="3"></line>
            </svg>
            <span>{{ i18n.locale() === 'ar' ? 'تصدير كـ CSV' : 'Export CSV' }}</span>
          </button>
          <button class="fe-btn fe-btn--secondary" (click)="openPlanInvoiceModal()">
            + {{ 'clinic.invoices.fromPlan' | t }}
          </button>
          <button class="fe-btn fe-btn--secondary" (click)="openStatementSelector()">
            {{ 'clinic.invoices.statement' | t }}
          </button>
          <button class="fe-btn fe-btn--primary" (click)="openCreateModal()">
            + {{ 'clinic.invoices.issueNew' | t }}
          </button>
        </div>
      </fe-page-header>

      <!-- Status Filter Tabs -->
      <div class="filter-tabs">
        <button
          class="tab-btn"
          [class.active]="selectedStatus() === 'ALL'"
          (click)="setStatusFilter('ALL')"
        >
          {{ 'clinic.invoices.status.all' | t }}
        </button>
        <button
          class="tab-btn"
          [class.active]="selectedStatus() === 'ISSUED'"
          (click)="setStatusFilter('ISSUED')"
        >
          {{ 'clinic.invoices.status.issued' | t }}
        </button>
        <button
          class="tab-btn"
          [class.active]="selectedStatus() === 'PARTIALLY_PAID'"
          (click)="setStatusFilter('PARTIALLY_PAID')"
        >
          {{ 'clinic.invoices.status.partiallyPaid' | t }}
        </button>
        <button
          class="tab-btn"
          [class.active]="selectedStatus() === 'PAID'"
          (click)="setStatusFilter('PAID')"
        >
          {{ 'clinic.invoices.status.paid' | t }}
        </button>
        <button
          class="tab-btn"
          [class.active]="selectedStatus() === 'CANCELLED'"
          (click)="setStatusFilter('CANCELLED')"
        >
          {{ 'clinic.invoices.status.cancelled' | t }}
        </button>
      </div>

      <!-- Invoices List -->
      <fe-async-state
        [loading]="loading() && invoices().length === 0"
        [error]="error()"
        [empty]="invoices().length === 0"
        [hasValue]="invoices().length > 0"
        [loadingLabel]="'state.loading' | t"
        [loadingHint]="'state.loadingHint' | t"
        [emptyTitle]="'clinic.invoices.empty.title' | t"
        [emptyBody]="'clinic.invoices.empty.body' | t"
        [errorTitle]="'clinic.error.title' | t"
        [errorBody]="'clinic.error.body' | t"
        [retryLabel]="'clinic.retry' | t"
        (retry)="loadInvoices()"
      />

      @if (filteredInvoices().length > 0) {
        <div class="table-container">
          <table class="fe-table">
            <thead>
              <tr>
                <th>{{ 'clinic.invoices.number' | t }}</th>
                <th>{{ 'clinic.invoices.patient' | t }}</th>
                <th>{{ 'clinic.invoices.date' | t }}</th>
                <th>{{ 'clinic.invoices.currency' | t }}</th>
                <th>{{ 'clinic.invoices.total' | t }}</th>
                <th>{{ 'clinic.invoices.paid' | t }}</th>
                <th>{{ 'clinic.invoices.balanceDue' | t }}</th>
                <th>{{ 'clinic.invoices.status' | t }}</th>
                <th>{{ 'clinic.invoices.actions' | t }}</th>
              </tr>
            </thead>
            <tbody>
              @for (inv of filteredInvoices(); track inv.id) {
                <tr>
                  <td class="font-mono font-bold">{{ inv.invoiceNumber }}</td>
                  <td>
                    <a [routerLink]="['/app/clinic/patients', inv.patientId]" class="patient-link">
                      {{ inv.patientName || inv.patientNo || inv.patientId }}
                    </a>
                  </td>
                  <td>{{ formatDate(inv.issuedAt) }}</td>
                  <td><span class="badge-currency">{{ inv.currency }}</span></td>
                  <td class="font-bold">{{ inv.totalAmount | number:'1.3-3' }}</td>
                  <td class="text-success">{{ inv.paidAmount | number:'1.3-3' }}</td>
                  <td [class.text-danger]="inv.balanceDue > 0" class="font-bold">
                    {{ inv.balanceDue | number:'1.3-3' }}
                  </td>
                  <td>
                    <fe-status-chip [tone]="statusTone(inv.status)">
                      {{ statusLabel(inv.status) }}
                    </fe-status-chip>
                  </td>
                  <td>
                    <div class="action-buttons">
                      <button class="fe-btn fe-btn--xs fe-btn--ghost" (click)="viewDetail(inv.id)">
                        {{ 'clinic.invoices.viewDetails' | t }}
                      </button>
                      <button class="fe-btn fe-btn--xs fe-btn--ghost" (click)="openPatientStatement(inv.patientId)">
                        {{ 'clinic.invoices.statement' | t }}
                      </button>
                      @if (inv.status !== 'CANCELLED' && inv.balanceDue > 0) {
                        <button class="fe-btn fe-btn--xs fe-btn--secondary" (click)="openDiscountModal(inv)">
                          {{ 'clinic.invoices.applyDiscount' | t }}
                        </button>
                      }
                      @if (inv.paidAmount > 0) {
                        <button class="fe-btn fe-btn--xs fe-btn--secondary" (click)="openRefundModal(inv)">
                          {{ 'clinic.invoices.recordRefund' | t }}
                        </button>
                      }
                      @if (inv.status === 'ISSUED' && inv.paidAmount === 0) {
                        <button class="fe-btn fe-btn--xs fe-btn--danger" (click)="openCancelModal(inv)">
                          {{ 'clinic.invoices.cancelInvoice' | t }}
                        </button>
                      }
                    </div>
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      }

      <!-- Create Invoice Side Drawer (BIL-01) -->
      @if (isCreateOpen()) {
        <div class="tl-drawer-backdrop" (click)="isCreateOpen.set(false)">
          <aside
            class="tl-drawer tl-drawer--wide"
            (click)="$event.stopPropagation()"
            role="dialog"
            aria-modal="true"
            aria-labelledby="create-invoice-title"
          >
            <header class="tl-drawer-header">
              <h2 id="create-invoice-title">{{ 'clinic.invoices.createModal.title' | t }}</h2>
              <button type="button" class="tl-close-btn" (click)="isCreateOpen.set(false)" aria-label="Close">&times;</button>
            </header>

            <div class="tl-drawer-body">
              <div class="form-row">
                <div class="form-group flex-1">
                  <label>{{ 'clinic.invoices.createModal.selectPatient' | t }} *</label>
                  <select [(ngModel)]="createForm.patientId" class="fe-input">
                    <option value="" disabled>-- {{ 'clinic.invoices.createModal.selectPatient' | t }} --</option>
                    @for (p of patients(); track p.id) {
                      <option [value]="p.id">{{ p.displayName || p.patientNo }} ({{ p.patientNo }})</option>
                    }
                  </select>
                </div>
                <div class="form-group w-32">
                  <label>{{ 'clinic.invoices.currency' | t }} *</label>
                  <select [(ngModel)]="createForm.currency" class="fe-input">
                    <option value="JOD">JOD (دينار أردني)</option>
                    <option value="USD">USD (دولار أمريكي)</option>
                  </select>
                </div>
              </div>

              <!-- Line Items Table -->
              <div class="items-section">
                <div class="items-header">
                  <h4>{{ 'clinic.invoices.createModal.items' | t }}</h4>
                  <button type="button" class="fe-btn fe-btn--xs fe-btn--secondary" (click)="addItemRow()">
                    + {{ 'clinic.invoices.createModal.addItem' | t }}
                  </button>
                </div>

                <table class="items-table">
                  <thead>
                    <tr>
                      <th>{{ 'clinic.invoices.createModal.description' | t }}</th>
                      <th class="w-32">{{ 'clinic.invoices.createModal.type' | t }}</th>
                      <th class="w-20">{{ 'clinic.invoices.createModal.tooth' | t }}</th>
                      <th class="w-24">{{ 'clinic.invoices.createModal.unitPrice' | t }}</th>
                      <th class="w-16">{{ 'clinic.invoices.createModal.qty' | t }}</th>
                      <th class="w-24">{{ 'clinic.invoices.total' | t }}</th>
                      <th class="w-12"></th>
                    </tr>
                  </thead>
                  <tbody>
                    @for (item of createForm.items; track $index; let i = $index) {
                      <tr>
                        <td>
                          <input type="text" [(ngModel)]="item.description" class="fe-input" placeholder="اسم الإجراء أو الكشف" />
                        </td>
                        <td>
                          <select [(ngModel)]="item.itemType" class="fe-input">
                            <option value="PROCEDURE">{{ 'clinic.invoices.createModal.typeProcedure' | t }}</option>
                            <option value="CONSULTATION">{{ 'clinic.invoices.createModal.typeConsultation' | t }}</option>
                            <option value="MANUAL">{{ 'clinic.invoices.createModal.typeManual' | t }}</option>
                          </select>
                        </td>
                        <td>
                          <input type="number" [(ngModel)]="item.toothFdi" class="fe-input" min="11" max="85" placeholder="FDI" />
                        </td>
                        <td>
                          <input type="number" [(ngModel)]="item.unitPrice" class="fe-input" min="0" step="0.5" />
                        </td>
                        <td>
                          <input type="number" [(ngModel)]="item.quantity" class="fe-input" min="1" />
                        </td>
                        <td class="font-bold">
                          {{ (item.unitPrice || 0) * (item.quantity || 1) | number:'1.3-3' }}
                        </td>
                        <td>
                          @if (createForm.items.length > 1) {
                            <button type="button" class="btn-remove" (click)="removeItemRow(i)">&times;</button>
                          }
                        </td>
                      </tr>
                    }
                  </tbody>
                </table>

                <div class="subtotal-bar">
                  <span>{{ 'clinic.invoices.subtotal' | t }}:</span>
                  <strong class="text-xl">{{ calculatedSubtotal() | number:'1.3-3' }} {{ createForm.currency }}</strong>
                </div>
              </div>

              <div class="form-group mt-4">
                <label>{{ 'clinic.invoices.createModal.notes' | t }}</label>
                <textarea [(ngModel)]="createForm.notes" class="fe-input" rows="2" placeholder="ملاحظات اختيارية..."></textarea>
              </div>
            </div>

            <footer class="tl-drawer-footer">
              <button type="button" class="tl-btn tl-btn-outline" (click)="isCreateOpen.set(false)">إلغاء</button>
              <button
                type="button"
                class="tl-btn tl-btn-teal"
                [disabled]="!createForm.patientId || calculatedSubtotal() <= 0 || submitting()"
                (click)="submitCreateInvoice()"
              >
                {{ 'clinic.invoices.createModal.submit' | t }}
              </button>
            </footer>
          </aside>
        </div>
      }

      <!-- Apply Discount Modal (BIL-04) -->
      @if (isDiscountOpen() && selectedInvoiceHeader()) {
        <div class="modal-backdrop">
          <div class="modal-dialog">
            <div class="modal-header">
              <h3>{{ 'clinic.invoices.discountModal.title' | t }} - {{ selectedInvoiceHeader()?.invoiceNumber }}</h3>
              <button class="close-btn" (click)="isDiscountOpen.set(false)">&times;</button>
            </div>
            <div class="modal-body">
              <p class="mb-3 text-muted">
                المجموع قبل الخصم: <strong>{{ selectedInvoiceHeader()?.subtotal | number:'1.3-3' }} {{ selectedInvoiceHeader()?.currency }}</strong>
              </p>
              <div class="form-group">
                <label>{{ 'clinic.invoices.discountModal.amount' | t }} *</label>
                <input
                  type="number"
                  [(ngModel)]="discountForm.amount"
                  class="fe-input"
                  min="0.001"
                  [max]="selectedInvoiceHeader()?.subtotal || 99999"
                  step="0.5"
                />
              </div>
              <div class="form-group">
                <label>{{ 'clinic.invoices.discountModal.reason' | t }} *</label>
                <input
                  type="text"
                  [(ngModel)]="discountForm.reason"
                  class="fe-input"
                  placeholder="سبب تطبيق الخصم (للتدقيق المالي)"
                />
              </div>
            </div>
            <div class="modal-footer">
              <button class="fe-btn fe-btn--ghost" (click)="isDiscountOpen.set(false)">إلغاء</button>
              <button
                class="fe-btn fe-btn--primary"
                [disabled]="!discountForm.amount || !discountForm.reason || submitting()"
                (click)="submitDiscount()"
              >
                {{ 'clinic.invoices.discountModal.submit' | t }}
              </button>
            </div>
          </div>
        </div>
      }

      <!-- Cancel Invoice Modal (BIL-05) -->
      @if (isCancelOpen() && selectedInvoiceHeader()) {
        <div class="modal-backdrop">
          <div class="modal-dialog">
            <div class="modal-header">
              <h3 class="text-danger">{{ 'clinic.invoices.cancelModal.title' | t }} - {{ selectedInvoiceHeader()?.invoiceNumber }}</h3>
              <button class="close-btn" (click)="isCancelOpen.set(false)">&times;</button>
            </div>
            <div class="modal-body">
              <div class="alert-box alert-warning">
                {{ 'clinic.invoices.cancelModal.warning' | t }}
              </div>
              <div class="form-group mt-3">
                <label>{{ 'clinic.invoices.cancelModal.reason' | t }} *</label>
                <textarea
                  [(ngModel)]="cancelForm.reason"
                  class="fe-input"
                  rows="3"
                  placeholder="سبب إلغاء الفاتورة الإلزامي..."
                ></textarea>
              </div>
            </div>
            <div class="modal-footer">
              <button class="fe-btn fe-btn--ghost" (click)="isCancelOpen.set(false)">تراجع</button>
              <button
                class="fe-btn fe-btn--danger"
                [disabled]="!cancelForm.reason || submitting()"
                (click)="submitCancelInvoice()"
              >
                {{ 'clinic.invoices.cancelModal.submit' | t }}
              </button>
            </div>
          </div>
        </div>
      }

      <!-- Invoice Detail Modal (ToothLogic Billing Record View - Screenshot 12) -->
      @if (isDetailOpen() && detailData()) {
        <div class="modal-backdrop">
          <div class="modal-dialog modal-xl" id="printable-billing-record">
            <div class="modal-header no-print">
              <div>
                <h3>Billing Record — {{ detailData()?.invoice?.invoiceNumber }}</h3>
                <span class="subtext">
                  المريض: {{ detailData()?.invoice?.patientName }} ({{ detailData()?.invoice?.patientNo }}) |
                  التاريخ: {{ formatDate(detailData()?.invoice?.issuedAt) }}
                </span>
              </div>
              <div style="display: flex; gap: 8px; align-items: center;">
                <button type="button" class="fe-btn fe-btn--primary" (click)="printInvoice()">
                  Print
                </button>
                <button class="close-btn" (click)="isDetailOpen.set(false)">&times;</button>
              </div>
            </div>
            <div class="modal-body">
              <!-- ToothLogic Billing Record Top Header Bar (Screenshot 12) -->
              <div class="tl-billing-record-top">
                <div><strong>Invoice Id:</strong> #{{ detailData()?.invoice?.invoiceNumber }}</div>
                <div><strong>Date:</strong> {{ formatDate(detailData()?.invoice?.issuedAt) }}</div>
                <div>
                  <strong>Payment Status:</strong>
                  <span class="tl-paid-badge">{{ detailData()?.invoice?.status }}</span>
                </div>
              </div>

              <!-- 4 ToothLogic Info Cards (Clinic Info, Doctor Details, Patient Detail, Service Summary) -->
              <div class="tl-billing-cards-grid">
                <div class="tl-billing-info-card">
                  <h5>Clinic Info</h5>
                  <div><strong>Clinic Flow Dental Clinic</strong></div>
                  <div class="text-muted">info&#64;clinicflow.com</div>
                  <div class="text-muted">22 Park Lane, Gulberg II</div>
                </div>
                <div class="tl-billing-info-card">
                  <h5>Doctor Details</h5>
                  <div><strong>Dr. Eman Khan</strong></div>
                  <div class="text-muted">eman.khan&#64;clinicflow.com</div>
                  <div class="text-muted">Dental Surgeon</div>
                </div>
                <div class="tl-billing-info-card">
                  <h5>Patient Detail</h5>
                  <div><strong>{{ detailData()?.invoice?.patientName }}</strong></div>
                  <div class="text-muted">{{ detailData()?.invoice?.patientNo || 'PAT-001' }}</div>
                </div>
                <div class="tl-billing-info-card">
                  <h5>Service Summary</h5>
                  <div>{{ 'clinic.invoices.subtotal' | t }}: <strong>{{ detailData()?.invoice?.subtotal | number:'1.3-3' }} {{ detailData()?.invoice?.currency }}</strong></div>
                  <div>{{ 'clinic.invoices.discount' | t }}: <strong>{{ detailData()?.invoice?.discountAmount | number:'1.3-3' }} {{ detailData()?.invoice?.currency }}</strong></div>
                  <div>{{ 'clinic.invoices.total' | t }}: <strong>{{ detailData()?.invoice?.totalAmount | number:'1.3-3' }} {{ detailData()?.invoice?.currency }}</strong></div>
                  <div>{{ 'clinic.invoices.paid' | t }}: <strong class="text-success">{{ detailData()?.invoice?.paidAmount | number:'1.3-3' }} {{ detailData()?.invoice?.currency }}</strong></div>
                  <div>{{ 'clinic.invoices.balanceDue' | t }}: <strong class="text-danger">{{ detailData()?.invoice?.balanceDue | number:'1.3-3' }} {{ detailData()?.invoice?.currency }}</strong></div>
                </div>
              </div>

              <h4 class="mt-4 mb-2">Service</h4>
              <table class="fe-table tl-teal-head-table">
                <thead>
                  <tr>
                    <th>SR NO</th>
                    <th>Service Name</th>
                    <th>النوع</th>
                    <th>السن</th>
                    <th>Price</th>
                    <th>Quantity</th>
                    <th>Discount</th>
                    <th>Service Total</th>
                  </tr>
                </thead>
                <tbody>
                  @for (it of detailData()?.items; track it.id; let idx = $index) {
                    <tr>
                      <td>{{ idx + 1 }}</td>
                      <td>{{ it.description }}</td>
                      <td>{{ it.itemType }}</td>
                      <td>{{ it.toothFdi || '-' }}</td>
                      <td>{{ it.unitPrice | number:'1.3-3' }}</td>
                      <td>{{ it.quantity }}</td>
                      <td>0.000</td>
                      <td class="font-bold">{{ it.totalPrice | number:'1.3-3' }}</td>
                    </tr>
                  }
                </tbody>
              </table>

              @if ((detailData()?.payments?.length || 0) > 0) {
                <h4 class="mt-4 mb-2">Installments / الدفعات المسجلة</h4>
                <table class="fe-table tl-teal-head-table">
                  <thead>
                    <tr>
                      <th>Sr No</th>
                      <th>رقم الإيصال</th>
                      <th>Amount</th>
                      <th>Payment Mode</th>
                      <th>المرجع</th>
                      <th>Date</th>
                      <th>Invoice Detail</th>
                    </tr>
                  </thead>
                  <tbody>
                    @for (pmt of detailData()?.payments; track pmt.id; let idx = $index) {
                      <tr>
                        <td>{{ idx + 1 }}</td>
                        <td class="font-mono font-bold">{{ pmt.paymentNumber }}</td>
                        <td class="text-success font-bold">{{ pmt.amount | number:'1.3-3' }} {{ pmt.currency }}</td>
                        <td>{{ pmt.paymentMethod }}</td>
                        <td>{{ pmt.referenceNumber || '-' }}</td>
                        <td>{{ formatDate(pmt.paymentDate) }}</td>
                        <td>
                          <button
                            type="button"
                            class="fe-btn fe-btn--xs fe-btn--secondary"
                            title="Download Receipt PDF"
                            (click)="downloadInstallmentReceipt(pmt)"
                          >
                            PDF
                          </button>
                        </td>
                      </tr>
                    }
                  </tbody>
                </table>
              }

              @if (detailRefunds().length > 0) {
                <h4 class="mt-4 mb-2">{{ 'clinic.invoices.refunds' | t }}</h4>
                <table class="fe-table">
                  <thead>
                    <tr>
                      <th>{{ 'clinic.invoices.refundNumber' | t }}</th>
                      <th>{{ 'clinic.invoices.refundModal.amount' | t }}</th>
                      <th>{{ 'clinic.invoices.refundModal.method' | t }}</th>
                      <th>{{ 'clinic.invoices.refundModal.reason' | t }}</th>
                      <th>{{ 'clinic.invoices.date' | t }}</th>
                    </tr>
                  </thead>
                  <tbody>
                    @for (ref of detailRefunds(); track ref.id) {
                      <tr>
                        <td class="font-mono font-bold">{{ ref.refundNumber }}</td>
                        <td class="text-danger font-bold">-{{ ref.amount | number:'1.3-3' }} {{ ref.currency }}</td>
                        <td>{{ ref.refundMethod }}</td>
                        <td>{{ ref.reason }}</td>
                        <td>{{ formatDate(ref.refundDate) }}</td>
                      </tr>
                    }
                  </tbody>
                </table>
              }
            </div>
            <div class="modal-footer no-print">
              <button class="fe-btn fe-btn--ghost" (click)="isDetailOpen.set(false)">إغلاق</button>
              @if ((detailData()?.invoice?.paidAmount || 0) > 0) {
                <button
                  class="fe-btn fe-btn--secondary"
                  (click)="openRefundModal(detailData()!.invoice)"
                >
                  {{ 'clinic.invoices.recordRefund' | t }}
                </button>
              }
              <button class="fe-btn fe-btn--secondary" (click)="printInvoice()">
                طباعة الفاتورة
              </button>
            </div>
          </div>
        </div>
      }

      <!-- Generate Invoice from Treatment Plan Modal (BIL-09) -->
      @if (isPlanInvoiceOpen()) {
        <div class="modal-backdrop">
          <div class="modal-dialog">
            <div class="modal-header">
              <h3>{{ 'clinic.invoices.planModal.title' | t }}</h3>
              <button class="close-btn" (click)="isPlanInvoiceOpen.set(false)">&times;</button>
            </div>
            <div class="modal-body">
              <div class="form-group">
                <label>{{ 'clinic.invoices.planModal.selectPatient' | t }} *</label>
                <select
                  [(ngModel)]="planInvoiceForm.patientId"
                  (change)="onPlanPatientChanged()"
                  class="fe-input"
                >
                  <option value="" disabled>-- {{ 'clinic.invoices.planModal.selectPatient' | t }} --</option>
                  @for (p of patients(); track p.id) {
                    <option [value]="p.id">{{ p.displayName || p.patientNo }} ({{ p.patientNo }})</option>
                  }
                </select>
              </div>

              <div class="form-group">
                <label>{{ 'clinic.invoices.planModal.selectPlan' | t }} *</label>
                @if (patientPlans().length === 0) {
                  <p class="text-muted text-sm">{{ 'clinic.invoices.planModal.noPlans' | t }}</p>
                } @else {
                  <select [(ngModel)]="planInvoiceForm.treatmentPlanId" class="fe-input">
                    <option value="" disabled>-- {{ 'clinic.invoices.planModal.selectPlan' | t }} --</option>
                    @for (plan of patientPlans(); track plan.id) {
                      <option [value]="plan.id">
                        {{ plan.title }} (v{{ plan.version }}) - {{ plan.totalEstimatedCost | number:'1.3-3' }} JOD
                      </option>
                    }
                  </select>
                }
              </div>

              <div class="form-group">
                <label>{{ 'clinic.invoices.createModal.notes' | t }}</label>
                <textarea [(ngModel)]="planInvoiceForm.notes" class="fe-input" rows="2"></textarea>
              </div>
            </div>
            <div class="modal-footer">
              <button class="fe-btn fe-btn--ghost" (click)="isPlanInvoiceOpen.set(false)">إلغاء</button>
              <button
                class="fe-btn fe-btn--primary"
                [disabled]="!planInvoiceForm.patientId || !planInvoiceForm.treatmentPlanId || submitting()"
                (click)="submitPlanInvoice()"
              >
                {{ 'clinic.invoices.planModal.submit' | t }}
              </button>
            </div>
          </div>
        </div>
      }

      <!-- Record Refund Modal (BIL-08) -->
      @if (isRefundOpen() && selectedInvoiceHeader()) {
        <div class="modal-backdrop">
          <div class="modal-dialog">
            <div class="modal-header">
              <h3>{{ 'clinic.invoices.refundModal.title' | t }} - {{ selectedInvoiceHeader()?.invoiceNumber }}</h3>
              <button class="close-btn" (click)="isRefundOpen.set(false)">&times;</button>
            </div>
            <div class="modal-body">
              <p class="mb-3 text-muted">
                {{ 'clinic.invoices.paid' | t }}: <strong>{{ selectedInvoiceHeader()?.paidAmount | number:'1.3-3' }} {{ selectedInvoiceHeader()?.currency }}</strong>
              </p>
              <div class="form-row">
                <div class="form-group flex-1">
                  <label>{{ 'clinic.invoices.refundModal.amount' | t }} *</label>
                  <input
                    type="number"
                    [(ngModel)]="refundForm.amount"
                    class="fe-input"
                    min="0.001"
                    [max]="selectedInvoiceHeader()?.paidAmount || 99999"
                    step="0.5"
                  />
                </div>
                <div class="form-group flex-1">
                  <label>{{ 'clinic.invoices.refundModal.method' | t }} *</label>
                  <select [(ngModel)]="refundForm.refundMethod" class="fe-input">
                    <option value="CASH">نقدًا (كاش)</option>
                    <option value="CARD">بطاقة بنكية / POS</option>
                    <option value="BANK_TRANSFER">تحويل بنكي</option>
                    <option value="STORE_CREDIT">رصيد بالعيادة</option>
                  </select>
                </div>
              </div>
              <div class="form-group">
                <label>{{ 'clinic.invoices.refundModal.reason' | t }} *</label>
                <input
                  type="text"
                  [(ngModel)]="refundForm.reason"
                  class="fe-input"
                  placeholder="سبب الاسترداد الإلزامي للتدقيق المالي..."
                />
              </div>
            </div>
            <div class="modal-footer">
              <button class="fe-btn fe-btn--ghost" (click)="isRefundOpen.set(false)">إلغاء</button>
              <button
                class="fe-btn fe-btn--danger"
                [disabled]="!refundForm.amount || !refundForm.reason || submitting()"
                (click)="submitRefund()"
              >
                {{ 'clinic.invoices.refundModal.submit' | t }}
              </button>
            </div>
          </div>
        </div>
      }

      <!-- Patient Statement of Account Modal (BIL-07) -->
      @if (isStatementOpen()) {
        <div class="modal-backdrop">
          <div class="modal-dialog modal-xl">
            <div class="modal-header no-print">
              <div>
                <h3>{{ 'clinic.invoices.statement.title' | t }}</h3>
                <span class="subtext">{{ 'clinic.invoices.statement.subtitle' | t }}</span>
              </div>
              <button class="close-btn" (click)="isStatementOpen.set(false)">&times;</button>
            </div>

            <div class="modal-body">
              <div class="form-group no-print">
                <label>{{ 'clinic.invoices.createModal.selectPatient' | t }}</label>
                <select
                  [ngModel]="selectedStatementPatientId()"
                  (ngModelChange)="loadStatementForPatient($event)"
                  class="fe-input"
                >
                  <option value="" disabled>-- {{ 'clinic.invoices.createModal.selectPatient' | t }} --</option>
                  @for (p of patients(); track p.id) {
                    <option [value]="p.id">{{ p.displayName || p.patientNo }} ({{ p.patientNo }})</option>
                  }
                </select>
              </div>

              @if (statementData(); as stmt) {
                <div class="statement-card" id="printable-statement">
                  <!-- Statement Header -->
                  <div class="statement-header">
                    <div>
                      <h2 class="statement-clinic-name">عيادة طب وجراحة الأسنان</h2>
                      <div class="statement-subtitle">{{ 'clinic.invoices.statement.title' | t }}</div>
                    </div>
                    <div class="statement-meta">
                      <div><strong>{{ 'clinic.invoices.patient' | t }}:</strong> {{ stmt.patientName }}</div>
                      <div><strong>رقم الملف:</strong> {{ stmt.patientNo }}</div>
                      <div><strong>تاريخ الاستخراج:</strong> {{ formatDate(currentIsoDate) }}</div>
                    </div>
                  </div>

                  <!-- Financial KPI Summary -->
                  <div class="detail-kpi-grid">
                    <div class="kpi-card">
                      <span class="kpi-label">{{ 'clinic.invoices.statement.totalDebits' | t }}</span>
                      <span class="kpi-val text-danger font-bold">
                        {{ stmt.totalDebits | number:'1.3-3' }} {{ stmt.currency }}
                      </span>
                    </div>
                    <div class="kpi-card">
                      <span class="kpi-label">{{ 'clinic.invoices.statement.totalCredits' | t }}</span>
                      <span class="kpi-val text-success font-bold">
                        {{ stmt.totalCredits | number:'1.3-3' }} {{ stmt.currency }}
                      </span>
                    </div>
                    <div class="kpi-card">
                      <span class="kpi-label">{{ 'clinic.invoices.statement.netBalanceDue' | t }}</span>
                      <span class="kpi-val font-bold" [class.text-danger]="stmt.netBalanceDue > 0" [class.text-success]="stmt.netBalanceDue === 0">
                        {{ stmt.netBalanceDue | number:'1.3-3' }} {{ stmt.currency }}
                      </span>
                    </div>
                  </div>

                  <!-- Append-Only Dual-Entry Ledger -->
                  <h4 class="mt-4 mb-2">{{ 'clinic.invoices.statement.subtitle' | t }}</h4>
                  @if (stmt.entries.length === 0) {
                    <p class="text-muted">{{ 'clinic.invoices.statement.empty' | t }}</p>
                  } @else {
                    <table class="fe-table">
                      <thead>
                        <tr>
                          <th>{{ 'clinic.invoices.date' | t }}</th>
                          <th>{{ 'clinic.invoices.ledger.entryType' | t }}</th>
                          <th>{{ 'clinic.invoices.ledger.direction' | t }}</th>
                          <th>{{ 'clinic.invoices.total' | t }}</th>
                          <th>رقم المستند / المرجع</th>
                          <th>البيان والتفاصيل</th>
                        </tr>
                      </thead>
                      <tbody>
                        @for (entry of stmt.entries; track entry.id) {
                          <tr>
                            <td>{{ formatDate(entry.entryDate) }}</td>
                            <td>
                              <span class="badge-entry-type">{{ ledgerTypeLabel(entry.entryType) }}</span>
                            </td>
                            <td>
                              <fe-status-chip [tone]="entry.direction === 'DEBIT' ? 'warn' : 'ok'">
                                {{ entry.direction === 'DEBIT' ? ('clinic.invoices.ledger.debit' | t) : ('clinic.invoices.ledger.credit' | t) }}
                              </fe-status-chip>
                            </td>
                            <td class="font-bold" [class.text-danger]="entry.direction === 'DEBIT'" [class.text-success]="entry.direction === 'CREDIT'">
                              {{ entry.amount | number:'1.3-3' }} {{ entry.currency }}
                            </td>
                            <td class="font-mono">{{ entry.referenceNumber }}</td>
                            <td>{{ entry.description }}</td>
                          </tr>
                        }
                      </tbody>
                    </table>
                  }
                </div>
              }
            </div>

            <div class="modal-footer no-print">
              <button class="fe-btn fe-btn--ghost" (click)="isStatementOpen.set(false)">إغلاق</button>
              @if (statementData()) {
                <button class="fe-btn fe-btn--primary" (click)="printStatement()">
                  {{ 'clinic.invoices.statement.print' | t }}
                </button>
              }
            </div>
          </div>
        </div>
      }
    </div>
  `,
  styles: [`
    .fe-page { padding: 12px 18px; max-width: 1500px; margin: 0 auto; }
    .header-actions { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
    .filter-tabs { display: flex; gap: 6px; margin-bottom: 12px; border-bottom: 1px solid var(--line); padding-bottom: 8px; }
    .tab-btn { background: var(--surf2); border: 1px solid var(--line); padding: 6px 14px; cursor: pointer; font-size: 12.5px; font-weight: 600; border-radius: 2px; color: var(--mute); transition: all 0.15s ease; }
    .tab-btn:hover { background: var(--surf3); color: var(--ink); }
    .tab-btn.active { background: var(--pri); color: #fff; border-color: var(--pri); font-weight: 600; }
    .table-container { overflow-x: auto; background: var(--surf); border-radius: 3px; border: 1px solid var(--line); box-shadow: var(--shadow-panel); }
    .fe-table { width: 100%; border-collapse: collapse; text-align: start; }
    .fe-table th { background: var(--surf2); font-weight: 700; color: var(--mute); font-size: 11px; text-transform: uppercase; letter-spacing: 0.05em; padding: 10px 14px; border-bottom: 1px solid var(--line); white-space: nowrap; }
    .fe-table td { padding: 11px 14px; border-bottom: 1px solid var(--line); font-size: 13px; color: var(--ink); vertical-align: middle; }
    .fe-table tbody tr:hover td { background: var(--surf2); }
    .patient-link { color: var(--pri); text-decoration: none; font-weight: 600; }
    .patient-link:hover { text-decoration: underline; }
    .badge-currency { background: var(--surf2); border: 1px solid var(--line); padding: 2px 6px; border-radius: 2px; font-size: 11px; font-weight: 700; font-family: var(--fe-font-mono); }
    .text-success { color: var(--ok); }
    .text-danger { color: var(--bad); }
    .text-warning { color: var(--warn); }
    .action-buttons { display: flex; gap: 6px; align-items: center; flex-wrap: wrap; }
    .fe-btn { border-radius: 2px !important; }
    .modal-backdrop { position: fixed; inset: 0; background: var(--scrim); backdrop-filter: blur(4px); display: flex; align-items: center; justify-content: center; z-index: 1000; padding: 1rem; }
    .modal-dialog { background: var(--surf); border-radius: 4px; border: 1px solid var(--line); width: 100%; max-width: 600px; max-height: 90vh; overflow-y: auto; box-shadow: var(--shadow-panel); display: flex; flex-direction: column; }
    .modal-dialog.modal-lg { max-width: 900px; }
    .modal-dialog.modal-xl { max-width: 1000px; }
    .modal-header { display: flex; justify-content: space-between; align-items: center; padding: 1.25rem; border-bottom: 1px solid var(--line); }
    .modal-header h3 { margin: 0; font-size: 1.25rem; font-weight: 600; }
    .modal-header .subtext { font-size: 0.8rem; color: var(--mute); }
    .close-btn { background: none; border: none; font-size: 1.5rem; line-height: 1; cursor: pointer; color: var(--mute); }
    .modal-body { padding: 1.25rem; }
    .modal-footer { padding: 1rem 1.25rem; border-top: 1px solid var(--line); display: flex; justify-content: flex-end; gap: 0.5rem; }
    .form-row { display: flex; gap: 1rem; }
    .form-group { display: flex; flex-direction: column; gap: 0.35rem; margin-bottom: 1rem; }
    .form-group label { font-size: 0.85rem; font-weight: 500; color: var(--ink); }
    .fe-input, select.fe-input, input.fe-input {
      height: 38px;
      min-height: 38px;
      padding: 0 12px;
      line-height: normal;
      border: 1px solid var(--line);
      border-radius: 2px;
      font-size: 13.5px;
      background: var(--surf);
      color: var(--ink);
      box-sizing: border-box;
    }
    textarea.fe-input {
      height: auto;
      min-height: 60px;
      padding: 8px 12px;
    }

    /* Standard ToothLogic Side Drawer */
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
      width: min(680px, 95vw);
      height: 100vh;
      background: var(--surf);
      display: flex;
      flex-direction: column;
      box-shadow: var(--shadow-panel);
      animation: drawerSlideIn 0.22s cubic-bezier(0.16, 1, 0.3, 1);
    }
    .tl-drawer--wide {
      width: min(820px, 96vw);
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
    .items-section { margin-top: 1rem; background: var(--surf2); padding: 1rem; border-radius: 0.5rem; border: 1px solid var(--line); }
    .items-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 0.75rem; }
    .items-header h4 { margin: 0; font-size: 0.95rem; }
    .items-table { width: 100%; border-collapse: collapse; margin-bottom: 0.75rem; }
    .items-table th, .items-table td { padding: 0.4rem; font-size: 0.8rem; }
    .btn-remove { background: none; border: none; color: var(--bad); font-size: 1.25rem; cursor: pointer; }
    .subtotal-bar { display: flex; justify-content: flex-end; align-items: center; gap: 0.75rem; padding-top: 0.5rem; border-top: 1px solid var(--line); }
    .alert-box { padding: 0.75rem 1rem; border-radius: 0.375rem; font-size: 0.85rem; }
    .alert-warning { background: var(--warn-soft); color: var(--warn); border: 1px solid var(--warn); }
    .detail-kpi-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(130px, 1fr)); gap: 0.75rem; margin-bottom: 1.5rem; }
    .kpi-card { background: var(--surf2); padding: 0.75rem; border-radius: 0.5rem; border: 1px solid var(--line); display: flex; flex-direction: column; gap: 0.25rem; }
    .kpi-label { font-size: 0.75rem; color: var(--mute); }
    .kpi-val { font-size: 1.1rem; }
    .w-12 { width: 3rem; }
    .w-16 { width: 4rem; }
    .w-20 { width: 5rem; }
    .w-24 { width: 6rem; }
    .w-32 { width: 8rem; }
    .flex-1 { flex: 1; }
    .text-sm { font-size: 0.875rem; }
    .text-muted { color: var(--mute); }
    .statement-card { background: var(--surf); padding: 1.5rem; border-radius: 0.5rem; border: 1px solid var(--line); }
    .statement-header { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 1.5rem; border-bottom: 1px solid var(--line); padding-bottom: 1rem; }
    .statement-clinic-name { margin: 0; font-size: 1.35rem; color: var(--pri); }
    .statement-subtitle { font-size: 0.9rem; color: var(--mute); margin-top: 0.25rem; }
    .statement-meta { font-size: 0.85rem; display: flex; flex-direction: column; gap: 0.25rem; text-align: end; }
    .badge-entry-type { background: var(--surf2); padding: 0.2rem 0.5rem; border-radius: 0.25rem; font-size: 0.75rem; font-weight: 500; }
    .tl-billing-record-top {
      display: flex;
      justify-content: space-between;
      align-items: center;
      flex-wrap: wrap;
      gap: 12px;
      padding: 12px 16px;
      background: var(--surf2);
      border: 1px solid var(--line);
      border-radius: 8px;
      margin-bottom: 16px;
    }
    .tl-paid-badge {
      display: inline-block;
      margin-inline-start: 6px;
      padding: 2px 10px;
      border-radius: 999px;
      font-size: 0.75rem;
      font-weight: 700;
      background: var(--accent-green-soft);
      color: var(--accent-green-ink);
    }
    .tl-billing-cards-grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(190px, 1fr));
      gap: 12px;
      margin-bottom: 16px;
    }
    .tl-billing-info-card {
      background: var(--surf2);
      border: 1px solid var(--line);
      border-radius: 8px;
      padding: 12px 14px;
      font-size: 0.84rem;
      display: flex;
      flex-direction: column;
      gap: 4px;
    }
    .tl-billing-info-card h5 {
      margin: 0 0 6px;
      font-size: 0.9rem;
      color: var(--tl-teal);
    }
    .tl-teal-head-table th {
      background: var(--tl-teal) !important;
      color: var(--on-accent) !important;
    }
    @media print {
      body * { visibility: hidden; }
      #printable-statement, #printable-statement * { visibility: visible; }
      #printable-statement { position: absolute; left: 0; top: 0; width: 100%; border: none; padding: 0; }
      .no-print { display: none !important; }
    }
  `],
})
export class ClinicInvoicesComponent implements OnInit {
  private readonly api = inject(ClinicPatientApi);
  private readonly route = inject(ActivatedRoute);
  private readonly projectContext = inject(ProjectContextService);
  private readonly format = inject(FormatService);
  readonly i18n = inject(I18nService);

  readonly invoices = signal<ClinicInvoiceHeader[]>([]);
  readonly patients = signal<ClinicPatient[]>([]);
  readonly loading = signal<boolean>(true);
  readonly error = signal<boolean>(false);
  readonly submitting = signal<boolean>(false);
  readonly selectedStatus = signal<string>('ALL');

  readonly isCreateOpen = signal<boolean>(false);
  readonly isDiscountOpen = signal<boolean>(false);
  readonly isCancelOpen = signal<boolean>(false);
  readonly isDetailOpen = signal<boolean>(false);
  readonly isPlanInvoiceOpen = signal<boolean>(false);
  readonly isRefundOpen = signal<boolean>(false);
  readonly isStatementOpen = signal<boolean>(false);

  readonly selectedInvoiceHeader = signal<ClinicInvoiceHeader | null>(null);
  readonly detailData = signal<ClinicInvoiceDetail | null>(null);
  readonly detailRefunds = signal<ClinicRefundRow[]>([]);
  readonly patientPlans = signal<ClinicTreatmentPlanHeader[]>([]);
  readonly statementData = signal<PatientStatement | null>(null);
  readonly selectedStatementPatientId = signal<string>('');
  readonly currentIsoDate = new Date().toISOString();

  planInvoiceForm = {
    patientId: '',
    treatmentPlanId: '',
    notes: '',
  };

  refundForm = {
    amount: 0,
    refundMethod: 'CASH' as 'CASH' | 'CARD' | 'BANK_TRANSFER' | 'STORE_CREDIT',
    reason: '',
    paymentId: undefined as string | undefined,
  };

  createForm = {
    patientId: '',
    currency: 'JOD' as 'JOD' | 'USD',
    notes: '',
    items: [
      {
        description: '',
        itemType: 'PROCEDURE' as 'PROCEDURE' | 'CONSULTATION' | 'MANUAL',
        toothFdi: undefined as number | undefined,
        unitPrice: 0,
        quantity: 1,
      },
    ],
  };

  discountForm = {
    amount: 0,
    reason: '',
  };

  cancelForm = {
    reason: '',
  };

  readonly filteredInvoices = computed(() => {
    const status = this.selectedStatus();
    const list = this.invoices();
    if (status === 'ALL') {
      return list;
    }
    return list.filter((i) => i.status === status);
  });

  calculatedSubtotal(): number {
    return this.createForm.items.reduce(
      (acc, it) => acc + (Number(it.unitPrice) || 0) * (Number(it.quantity) || 1),
      0,
    );
  }

  constructor() {
    inject(I18nService).register({ clinic: clinicStrings as Namespace });
  }

  ngOnInit(): void {
    this.loadInvoices();
    this.loadPatients();
    const qp = this.route.snapshot?.queryParamMap;
    if (qp && qp.get('openGenerate') === '1') {
      this.createForm = {
        patientId: qp.get('patientId') || '',
        currency: 'JOD',
        notes: '',
        items: [
          {
            description: qp.get('service') || 'Scaling and Polishing',
            itemType: 'PROCEDURE',
            toothFdi: undefined,
            unitPrice: Number(qp.get('price') || 150),
            quantity: 1,
          },
        ],
      };
      this.isCreateOpen.set(true);
    }
  }

  async loadInvoices(): Promise<void> {
    this.loading.set(true);
    this.error.set(false);
    try {
      const proj = this.projectContext.selectedProjectId() || undefined;
      const list = await this.api.invoices(proj ? { projectId: proj } : {});
      this.invoices.set(list);
    } catch {
      this.error.set(true);
    } finally {
      this.loading.set(false);
    }
  }

  async loadPatients(): Promise<void> {
    try {
      const proj = this.projectContext.selectedProjectId() || undefined;
      const list = await this.api.patients(proj ? { projectId: proj } : {});
      this.patients.set(list);
    } catch {
      // non-blocking
    }
  }

  setStatusFilter(status: string): void {
    this.selectedStatus.set(status);
  }

  openCreateModal(): void {
    this.createForm = {
      patientId: this.patients().length > 0 ? this.patients()[0].id || '' : '',
      currency: 'JOD',
      notes: '',
      items: [
        {
          description: '',
          itemType: 'PROCEDURE',
          toothFdi: undefined,
          unitPrice: 0,
          quantity: 1,
        },
      ],
    };
    this.isCreateOpen.set(true);
  }

  addItemRow(): void {
    this.createForm.items.push({
      description: '',
      itemType: 'PROCEDURE',
      toothFdi: undefined,
      unitPrice: 0,
      quantity: 1,
    });
  }

  removeItemRow(index: number): void {
    if (this.createForm.items.length > 1) {
      this.createForm.items.splice(index, 1);
    }
  }

  async submitCreateInvoice(): Promise<void> {
    if (!this.createForm.patientId || this.calculatedSubtotal() <= 0) return;
    this.submitting.set(true);
    try {
      const proj = this.projectContext.selectedProjectId() || undefined;
      const itemsPayload: CreateInvoiceItemPayload[] = this.createForm.items.map((it) => ({
        description: it.description || 'Dental Procedure',
        itemType: it.itemType,
        toothFdi: it.toothFdi ? Number(it.toothFdi) : undefined,
        unitPrice: Number(it.unitPrice),
        quantity: Number(it.quantity || 1),
      }));

      await this.api.createInvoice(
        {
          patientId: this.createForm.patientId,
          currency: this.createForm.currency,
          notes: this.createForm.notes,
          items: itemsPayload,
        },
        proj ? { projectId: proj } : {},
      );

      this.isCreateOpen.set(false);
      await this.loadInvoices();
    } catch (err) {
      alert('تعذر إصدار الفاتورة: ' + (err as Error).message);
    } finally {
      this.submitting.set(false);
    }
  }

  openDiscountModal(inv: ClinicInvoiceHeader): void {
    this.selectedInvoiceHeader.set(inv);
    this.discountForm = {
      amount: 0,
      reason: '',
    };
    this.isDiscountOpen.set(true);
  }

  async submitDiscount(): Promise<void> {
    const inv = this.selectedInvoiceHeader();
    if (!inv || !this.discountForm.amount || !this.discountForm.reason) return;
    this.submitting.set(true);
    try {
      const proj = this.projectContext.selectedProjectId() || undefined;
      await this.api.applyDiscount(
        inv.id,
        {
          discountAmount: Number(this.discountForm.amount),
          discountReason: this.discountForm.reason,
        },
        proj ? { projectId: proj } : {},
      );

      this.isDiscountOpen.set(false);
      await this.loadInvoices();
    } catch (err) {
      alert('تعذر تطبيق الخصم: ' + (err as Error).message);
    } finally {
      this.submitting.set(false);
    }
  }

  openCancelModal(inv: ClinicInvoiceHeader): void {
    this.selectedInvoiceHeader.set(inv);
    this.cancelForm = { reason: '' };
    this.isCancelOpen.set(true);
  }

  async submitCancelInvoice(): Promise<void> {
    const inv = this.selectedInvoiceHeader();
    if (!inv || !this.cancelForm.reason) return;
    this.submitting.set(true);
    try {
      const proj = this.projectContext.selectedProjectId() || undefined;
      await this.api.cancelInvoice(
        inv.id,
        { cancellationReason: this.cancelForm.reason },
        proj ? { projectId: proj } : {},
      );

      this.isCancelOpen.set(false);
      await this.loadInvoices();
    } catch (err) {
      alert('تعذر إلغاء الفاتورة: ' + (err as Error).message);
    } finally {
      this.submitting.set(false);
    }
  }

  async viewDetail(invoiceId: string): Promise<void> {
    try {
      const proj = this.projectContext.selectedProjectId() || undefined;
      const opts = proj ? { projectId: proj } : {};
      const [d, refs] = await Promise.all([
        this.api.invoice(invoiceId, opts),
        this.api.invoiceRefunds(invoiceId, opts).catch(() => []),
      ]);
      this.detailData.set(d);
      this.detailRefunds.set(refs);
      this.isDetailOpen.set(true);
    } catch (err) {
      alert('تعذر قراءة تفاصيل الفاتورة: ' + (err as Error).message);
    }
  }

  async openPlanInvoiceModal(): Promise<void> {
    const firstPatientId =
      this.patients().length > 0 && this.patients()[0].id ? this.patients()[0].id! : '';
    this.planInvoiceForm = {
      patientId: firstPatientId,
      treatmentPlanId: '',
      notes: '',
    };
    this.patientPlans.set([]);
    this.isPlanInvoiceOpen.set(true);
    if (firstPatientId) {
      await this.onPlanPatientChanged();
    }
  }

  async onPlanPatientChanged(): Promise<void> {
    const pId = this.planInvoiceForm.patientId;
    if (!pId) {
      this.patientPlans.set([]);
      this.planInvoiceForm.treatmentPlanId = '';
      return;
    }
    try {
      const proj = this.projectContext.selectedProjectId() || undefined;
      const allPlans = await this.api.treatmentPlans(pId, proj ? { projectId: proj } : {});
      const billable = allPlans.filter(
        (p) => p.status === 'ACTIVE' || p.status === 'COMPLETED',
      );
      this.patientPlans.set(billable);
      if (billable.length > 0) {
        this.planInvoiceForm.treatmentPlanId = billable[0].id;
      } else {
        this.planInvoiceForm.treatmentPlanId = '';
      }
    } catch {
      this.patientPlans.set([]);
      this.planInvoiceForm.treatmentPlanId = '';
    }
  }

  async submitPlanInvoice(): Promise<void> {
    if (!this.planInvoiceForm.patientId || !this.planInvoiceForm.treatmentPlanId) return;
    this.submitting.set(true);
    try {
      const proj = this.projectContext.selectedProjectId() || undefined;
      await this.api.generateInvoiceFromPlan(
        {
          patientId: this.planInvoiceForm.patientId,
          treatmentPlanId: this.planInvoiceForm.treatmentPlanId,
          notes: this.planInvoiceForm.notes || undefined,
        },
        proj ? { projectId: proj } : {},
      );

      this.isPlanInvoiceOpen.set(false);
      await this.loadInvoices();
    } catch (err) {
      alert('تعذر إصدار الفاتورة من الخطة العلاجية: ' + (err as Error).message);
    } finally {
      this.submitting.set(false);
    }
  }

  openRefundModal(inv: ClinicInvoiceHeader): void {
    this.selectedInvoiceHeader.set(inv);
    this.refundForm = {
      amount: inv.paidAmount,
      refundMethod: 'CASH',
      reason: '',
      paymentId: undefined,
    };
    this.isRefundOpen.set(true);
  }

  async submitRefund(): Promise<void> {
    const inv = this.selectedInvoiceHeader();
    if (!inv || !this.refundForm.amount || !this.refundForm.reason) return;
    this.submitting.set(true);
    try {
      const proj = this.projectContext.selectedProjectId() || undefined;
      await this.api.recordRefund(
        inv.id,
        {
          amount: Number(this.refundForm.amount),
          refundMethod: this.refundForm.refundMethod,
          reason: this.refundForm.reason,
          paymentId: this.refundForm.paymentId,
        },
        proj ? { projectId: proj } : {},
      );

      this.isRefundOpen.set(false);
      await this.loadInvoices();
      if (this.isDetailOpen() && this.detailData()?.invoice?.id === inv.id) {
        await this.viewDetail(inv.id);
      }
    } catch (err) {
      alert('تعذر تسجيل عملية الاسترداد: ' + (err as Error).message);
    } finally {
      this.submitting.set(false);
    }
  }

  openStatementSelector(): void {
    const firstPatientId =
      this.patients().length > 0 && this.patients()[0].id ? this.patients()[0].id! : '';
    this.statementData.set(null);
    this.selectedStatementPatientId.set(firstPatientId);
    this.isStatementOpen.set(true);
    if (firstPatientId) {
      this.loadStatementForPatient(firstPatientId);
    }
  }

  async openPatientStatement(patientId: string): Promise<void> {
    this.selectedStatementPatientId.set(patientId);
    this.statementData.set(null);
    this.isStatementOpen.set(true);
    await this.loadStatementForPatient(patientId);
  }

  async loadStatementForPatient(patientId: string): Promise<void> {
    if (!patientId) return;
    this.selectedStatementPatientId.set(patientId);
    try {
      const proj = this.projectContext.selectedProjectId() || undefined;
      const stmt = await this.api.patientStatement(patientId, proj ? { projectId: proj } : {});
      this.statementData.set(stmt);
    } catch (err) {
      alert('تعذر تحميل كشف حساب المريض: ' + (err as Error).message);
    }
  }

  printStatement(): void {
    window.print();
  }

  ledgerTypeLabel(type: string): string {
    switch (type) {
      case 'INVOICE_CHARGE':
        return 'قيد فاتورة مستحقة';
      case 'PAYMENT_CREDIT':
        return 'دفعة مسددة';
      case 'DISCOUNT_CREDIT':
        return 'خصم معتمد';
      case 'REFUND_DEBIT':
        return 'استرداد مالي';
      case 'CANCELLATION_CREDIT':
        return 'إلغاء فاتورة';
      default:
        return type;
    }
  }

  printInvoice(): void {
    window.print();
  }

  downloadInstallmentReceipt(pmt: { paymentNumber?: string; amount?: number; currency?: string; paymentMethod?: string; paymentDate?: string }): void {
    const content = [
      'Clinic Flow Dental Clinic — Official Payment Receipt',
      `Receipt Number: ${pmt.paymentNumber || 'REC-001'}`,
      `Amount Paid: ${pmt.amount ?? 0} ${pmt.currency || 'JOD'}`,
      `Payment Mode: ${pmt.paymentMethod || 'CASH'}`,
      `Date: ${pmt.paymentDate || new Date().toISOString()}`,
    ].join('\r\n');
    const blob = new Blob([content], { type: 'text/plain;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `receipt-${pmt.paymentNumber || 'installment'}.txt`;
    a.click();
    URL.revokeObjectURL(url);
  }

  exportInvoicesCsv(): void {
    const list = this.filteredInvoices();
    if (list.length === 0) return;

    const isAr = this.i18n.locale() === 'ar';
    const headers = isAr
      ? ['رقم الفاتورة', 'تاريخ الإصدار', 'المريض', 'المبلغ الإجمالي', 'المدفوع', 'المتبقي', 'الحالة', 'العملة']
      : ['Invoice #', 'Issue Date', 'Patient Name', 'Total Amount', 'Paid Amount', 'Balance', 'Status', 'Currency'];

    const rows = list.map((inv) => [
      inv.invoiceNumber || inv.id,
      this.formatDate(inv.issuedAt),
      `"${(inv.patientName || '').replace(/"/g, '""')}"`,
      (inv.totalAmount ?? 0).toFixed(3),
      (inv.paidAmount ?? 0).toFixed(3),
      (inv.balanceDue ?? 0).toFixed(3),
      inv.status,
      inv.currency || 'JOD',
    ]);

    const csvContent = '\uFEFF' + [headers.join(','), ...rows.map((r) => r.join(','))].join('\r\n');
    const blob = new Blob([csvContent], { type: 'text/csv;charset=utf-8;' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.setAttribute('href', url);
    link.setAttribute('download', `clinic-invoices-${new Date().toISOString().slice(0, 10)}.csv`);
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    URL.revokeObjectURL(url);
  }

  formatDate(dateStr?: string): string {
    if (!dateStr) return '-';
    try {
      return this.format.dateTime(dateStr);
    } catch {
      return dateStr.substring(0, 10);
    }
  }

  statusTone(status: ClinicInvoiceStatus): StatusTone {
    switch (status) {
      case 'PAID':
        return 'ok';
      case 'PARTIALLY_PAID':
        return 'warn';
      case 'CANCELLED':
        return 'bad';
      case 'ISSUED':
      default:
        return 'neutral';
    }
  }

  statusLabel(status: ClinicInvoiceStatus): string {
    switch (status) {
      case 'PAID':
        return 'مدفوعة بالكامل';
      case 'PARTIALLY_PAID':
        return 'مدفوعة جزئياً';
      case 'CANCELLED':
        return 'ملغاة';
      case 'ISSUED':
      default:
        return 'مستحقة';
    }
  }
}
