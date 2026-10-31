/**
 * `/app/clinic/patients/:id/treatment-plans` — Dental Treatment Plans (TRT-01 to TRT-05, Slice S5).
 *
 * Implements:
 * - TRT-01: Tooth FDI mapping (11-85) or full-mouth general procedures.
 * - TRT-02: Sequence ordering of treatment stages (PHASE_1, PHASE_2, ...) and multi-visit tracking.
 * - TRT-03: Financial tracking (Estimated Cost, Discounts, Insurance Coverage, Patient Portion, Invoiced, Collected).
 * - TRT-04: Patient consent tracking and approval workflow (DRAFT -> PRESENTED -> ACTIVE -> COMPLETED, CANCELLED).
 * - TRT-05: Immutability of approved plans with version forking (v1 -> v2) for mid-course clinical revisions.
 */
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  signal,
  untracked,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { map } from 'rxjs';
import { ProjectContextService } from '../../core/context/project-context.service';
import { ClinicApi } from '../../core/api/endpoints';
import { FormatService } from '../../core/format/format.service';
import { I18nService } from '../../core/i18n/i18n.service';
import type { Namespace } from '../../core/i18n/i18n.types';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { ResourceStore } from '../../core/state/resource';
import { ClinicRbacService } from '../../core/auth/clinic-rbac';
import { AsyncStateComponent } from '../../ui/async-state.component';
import { PageHeaderComponent } from '../../ui/page-header.component';
import { StatusChipComponent, type StatusTone } from '../../ui/status-chip.component';
import {
  ClinicPatientApi,
  type ClinicTreatmentPlanHeader,
  type ClinicTreatmentPlanItem,
  type ClinicTreatmentPlanDetail,
  type TreatmentPlanStatus,
  type TreatmentPlanItemStatus,
} from './clinic-patient.api';
import { clinicStrings } from './clinic.strings';

@Component({
  selector: 'fe-clinic-treatment-plans-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    AsyncStateComponent,
    FormsModule,
    PageHeaderComponent,
    RouterLink,
    StatusChipComponent,
    TranslatePipe,
  ],
  template: `
    <div class="fe-page" data-testid="clinic-treatment-plans">
      <fe-page-header
        [title]="'clinic.treatmentPlans.title'"
        [description]="'clinic.treatmentPlans.description'"
      >
        <div class="header-actions">
          @if (patientId()) {
            <a class="fe-btn fe-btn--ghost" [routerLink]="['/app/clinic/patients', patientId()]">
              {{ 'clinic.treatmentPlans.backToPatient' | t }}
            </a>
          }
          @if (canManagePlans()) {
            <button class="fe-btn fe-btn--primary" (click)="openCreatePlanModal()">
              + {{ 'clinic.treatmentPlans.newPlan' | t }}
            </button>
          }
        </div>
      </fe-page-header>

      @if (patientId() === null) {
        <p class="fe-sub">{{ 'clinic.patient.noId' | t }}</p>
      } @else {
        <fe-async-state
          [loading]="plansStore.loading() && plansStore.value() === null"
          [error]="plansStore.error()"
          [hasValue]="plansStore.value() !== null"
          [loadingLabel]="'state.loading' | t"
          [loadingHint]="'state.loadingHint' | t"
          [errorTitle]="'clinic.error.denied.title' | t"
          [errorBody]="'clinic.error.denied.body' | t"
          [retryLabel]="'clinic.retry' | t"
          (retry)="reloadPlans()"
        />

        @if (plansStore.value(); as plans) {
          @if (plans.length === 0) {
            <section class="fe-card empty-card">
              <div class="empty-icon"></div>
              <h3>{{ 'clinic.treatmentPlans.empty' | t }}</h3>
              <p class="fe-sub">{{ 'clinic.treatmentPlans.emptyHint' | t }}</p>
              @if (canManagePlans()) {
                <button class="fe-btn fe-btn--primary" (click)="openCreatePlanModal()">
                  {{ 'clinic.treatmentPlans.newPlan' | t }}
                </button>
              }
            </section>
          } @else {
            <!-- Plan Selector Tabs with Version Indicator -->
            <div class="plan-tabs-bar">
              <div class="plan-tabs">
                @for (p of plans; track p.id) {
                  <button
                    class="plan-tab"
                    [class.plan-tab--active]="selectedPlanId() === p.id"
                    (click)="selectPlan(p.id)"
                  >
                    <span class="plan-tab-title">{{ p.title }}</span>
                    <span class="version-tag">v{{ p.version }}</span>
                    <fe-status-chip [tone]="planStatusTone(p.status)">
                      {{ planStatusLabel(p.status) }}
                    </fe-status-chip>
                  </button>
                }
              </div>
            </div>

            <!-- Active Plan Content -->
            @if (activePlanDetail(); as detail) {
              <!-- Plan Header & Actions -->
              <section class="fe-card plan-summary-card">
                <div class="summary-top">
                  <div>
                    <div class="title-row">
                      <h2>{{ detail.plan.title }}</h2>
                      <span class="version-badge">v{{ detail.plan.version }}</span>
                      <fe-status-chip [tone]="planStatusTone(detail.plan.status)">
                        {{ planStatusLabel(detail.plan.status) }}
                      </fe-status-chip>
                      @if (detail.plan.parentPlanId) {
                        <span class="parent-ref-chip">
                          {{ 'clinic.treatmentPlans.parentPlan' | t }}
                        </span>
                      }
                    </div>
                    @if (detail.plan.notes) {
                      <p class="fe-sub plan-notes">{{ detail.plan.notes }}</p>
                    }
                  </div>

                  <div class="summary-actions">
                    <!-- Status selector (for draft/presented) -->
                    @if (canManagePlans()) {
                      @if (isPlanDraftOrPresented()) {
                        <select
                          class="fe-select status-select"
                          [ngModel]="detail.plan.status"
                          (ngModelChange)="onPlanStatusChange($event)"
                        >
                          <option value="DRAFT">{{ 'clinic.treatmentPlans.status.draft' | t }}</option>
                          <option value="PRESENTED">{{ 'clinic.treatmentPlans.status.presented' | t }}</option>
                        </select>

                        <!-- Direct Approve & Activate button -->
                        <button class="fe-btn fe-btn--primary fe-btn--sm" (click)="openApproveModal()">
                          {{ 'clinic.treatmentPlans.approve' | t }}
                        </button>

                        <button class="fe-btn fe-btn--danger-ghost fe-btn--sm" (click)="deleteCurrentPlan()">
                          {{ 'clinic.treatmentPlans.deletePlan' | t }}
                        </button>
                      } @else if (isPlanActive()) {
                        <!-- Approved / Active: Fork New Version or Cancel -->
                        <button class="fe-btn fe-btn--ghost fe-btn--sm" (click)="openForkModal()">
                          {{ 'clinic.treatmentPlans.forkVersion' | t }}
                        </button>
                        <button class="fe-btn fe-btn--danger-ghost fe-btn--sm" (click)="openCancelModal()">
                          {{ 'clinic.treatmentPlans.cancel' | t }}
                        </button>
                      } @else if (detail.plan.status === 'COMPLETED') {
                        <!-- Completed: Fork New Version if new maintenance treatment needed -->
                        <button class="fe-btn fe-btn--ghost fe-btn--sm" (click)="openForkModal()">
                          {{ 'clinic.treatmentPlans.forkVersion' | t }}
                        </button>
                      }
                    }
                  </div>
                </div>

                <!-- Immutability Notice for Approved Plans (Slice S5) -->
                @if (!isPlanDraftOrPresented()) {
                  <div class="immutable-banner">
                    <span class="banner-badge">IMMUTABLE</span>
                    <span>{{ 'clinic.treatmentPlans.immutableNotice' | t }}</span>
                  </div>
                }

                <!-- Patient Consent Banner (TRT-04) -->
                <div class="consent-box" [class.consent-box--approved]="detail.plan.patientConsent">
                  <div class="consent-info">
                    <div class="consent-status-badge">
                      @if (detail.plan.patientConsent) {
                        <span class="consent-icon">OK</span>
                        <strong>{{ 'clinic.treatmentPlans.consentRecorded' | t }}</strong>
                      } @else {
                        <span class="consent-icon pending">--</span>
                        <strong>{{ 'clinic.treatmentPlans.consentPending' | t }}</strong>
                      }
                    </div>

                    @if (detail.plan.patientConsent) {
                      <div class="consent-details">
                        @if (detail.plan.consentRegistrarName) {
                          <span>{{ 'clinic.treatmentPlans.registrar' | t }}: <strong>{{ detail.plan.consentRegistrarName }}</strong></span>
                        }
                        @if (detail.plan.consentAt) {
                          <span>{{ 'clinic.treatmentPlans.consentDate' | t }}: <strong>{{ format.date(detail.plan.consentAt) }}</strong></span>
                        }
                        @if (detail.plan.consentNotes) {
                          <span class="consent-note-text">« {{ detail.plan.consentNotes }} »</span>
                        }
                      </div>
                    }
                  </div>

                  <div class="consent-action-btns">
                    @if (!detail.plan.patientConsent && isPlanDraftOrPresented()) {
                      <button class="fe-btn fe-btn--primary fe-btn--sm" (click)="openConsentModal(true)">
                        {{ 'clinic.treatmentPlans.recordConsent' | t }}
                      </button>
                    } @else if (detail.plan.patientConsent && isPlanDraftOrPresented()) {
                      <button class="fe-btn fe-btn--ghost fe-btn--sm" (click)="openConsentModal(false)">
                        {{ 'clinic.treatmentPlans.revokeConsent' | t }}
                      </button>
                    }
                  </div>
                </div>

                <!-- Financial KPI Cards (TRT-03, Slice S5 Estimates) -->
                <div class="kpi-grid">
                  <div class="kpi-card">
                    <span class="fe-eyebrow">{{ 'clinic.treatmentPlans.kpi.estimated' | t }}</span>
                    <strong class="kpi-value">{{ format.currency(detail.plan.totalEstimatedCost) }}</strong>
                  </div>
                  <div class="kpi-card">
                    <span class="fe-eyebrow">{{ 'clinic.treatmentPlans.estimates.discount' | t }}</span>
                    <strong class="kpi-value kpi-discount">{{ format.currency(detail.plan.totalDiscount || 0) }}</strong>
                  </div>
                  <div class="kpi-card">
                    <span class="fe-eyebrow">{{ 'clinic.treatmentPlans.estimates.insurance' | t }}</span>
                    <strong class="kpi-value kpi-insurance">{{ format.currency(detail.plan.insuranceEstimatedCoverage || 0) }}</strong>
                  </div>
                  <div class="kpi-card">
                    <span class="fe-eyebrow">{{ 'clinic.treatmentPlans.estimates.patient' | t }}</span>
                    <strong class="kpi-value kpi-patient">{{ format.currency(detail.plan.patientEstimatedPortion || (detail.plan.totalEstimatedCost - (detail.plan.totalDiscount || 0) - (detail.plan.insuranceEstimatedCoverage || 0))) }}</strong>
                  </div>
                  <div class="kpi-card">
                    <span class="fe-eyebrow">{{ 'clinic.treatmentPlans.kpi.invoiced' | t }}</span>
                    <strong class="kpi-value kpi-invoiced">{{ format.currency(detail.plan.totalInvoicedAmount) }}</strong>
                  </div>
                  <div class="kpi-card">
                    <span class="fe-eyebrow">{{ 'clinic.treatmentPlans.kpi.collected' | t }}</span>
                    <strong class="kpi-value kpi-collected">{{ format.currency(detail.plan.totalCollectedAmount) }}</strong>
                  </div>
                  <div class="kpi-card">
                    <span class="fe-eyebrow">{{ 'clinic.treatmentPlans.kpi.balance' | t }}</span>
                    <strong class="kpi-value kpi-balance">
                      {{ format.currency(detail.plan.totalEstimatedCost - detail.plan.totalCollectedAmount) }}
                    </strong>
                  </div>
                </div>
              </section>

              <!-- Treatment Items Sequenced Table (TRT-01, TRT-02, TRT-05, Stages) -->
              <section class="fe-card items-card">
                <div class="items-header">
                  <div>
                    <h3>{{ 'clinic.treatmentPlans.kpi.steps' | t }} ({{ detail.items.length }})</h3>
                    <p class="fe-sub">مرتبة حسب المراحل العلاجية والتسلسل وأرقام الأسنان بنظام FDI الدولي (11-85).</p>
                  </div>
                  @if (canManagePlans()) {
                    @if (isPlanDraftOrPresented()) {
                      <button class="fe-btn fe-btn--primary" (click)="openAddItemModal()">
                        + {{ 'clinic.treatmentPlans.addItem' | t }}
                      </button>
                    } @else {
                      <button class="fe-btn fe-btn--ghost" (click)="openForkModal()">
                        {{ 'clinic.treatmentPlans.forkVersion' | t }}
                      </button>
                    }
                  }
                </div>

                @if (detail.items.length === 0) {
                  <div class="empty-items-box">
                    <p>لا توجد خطوات علاجية مضافة بعد في هذه الخطة.</p>
                    @if (canManagePlans() && isPlanDraftOrPresented()) {
                      <button class="fe-btn fe-btn--ghost fe-btn--sm" (click)="openAddItemModal()">
                        + {{ 'clinic.treatmentPlans.addItem' | t }}
                      </button>
                    }
                  </div>
                } @else {
                  <div class="table-container">
                    <table class="fe-table">
                      <thead>
                        <tr>
                          <th>{{ 'clinic.treatmentPlans.col.seq' | t }}</th>
                          <th>{{ 'clinic.treatmentPlans.col.stage' | t }}</th>
                          <th>{{ 'clinic.treatmentPlans.col.tooth' | t }}</th>
                          <th>{{ 'clinic.treatmentPlans.col.procedure' | t }}</th>
                          <th>{{ 'clinic.treatmentPlans.col.visits' | t }}</th>
                          <th>{{ 'clinic.treatmentPlans.col.doctor' | t }}</th>
                          <th>{{ 'clinic.treatmentPlans.col.cost' | t }}</th>
                          <th>{{ 'clinic.treatmentPlans.col.discount' | t }}</th>
                          <th>{{ 'clinic.treatmentPlans.col.insurance' | t }}</th>
                          <th>{{ 'clinic.treatmentPlans.col.patientPortion' | t }}</th>
                          <th>{{ 'clinic.treatmentPlans.col.status' | t }}</th>
                          @if (isPlanDraftOrPresented()) {
                            <th>{{ 'clinic.treatmentPlans.col.actions' | t }}</th>
                          }
                        </tr>
                      </thead>
                      <tbody>
                        @for (item of detail.items; track item.id) {
                          <tr [class.item-row--completed]="item.status === 'COMPLETED'">
                            <!-- Sequence -->
                            <td class="seq-cell">
                              <span class="seq-badge">#{{ item.sequenceOrder }}</span>
                            </td>

                            <!-- Stage -->
                            <td>
                              <span class="stage-badge">{{ item.stage || 'PHASE_1' }}</span>
                            </td>

                            <!-- Tooth FDI -->
                            <td>
                              @if (item.toothFdi) {
                                <span class="tooth-badge">
                                  FDI #{{ item.toothFdi }}
                                  <small class="tooth-sub">{{ toothFdiLabel(item.toothFdi) }}</small>
                                </span>
                              } @else {
                                <span class="general-tooth-badge">
                                  {{ 'clinic.treatmentPlans.col.generalTooth' | t }}
                                </span>
                              }
                            </td>

                            <!-- Procedure -->
                            <td>
                              <strong>{{ item.procedureName }}</strong>
                              @if (item.notes) {
                                <div class="item-notes">{{ item.notes }}</div>
                              }
                            </td>

                            <!-- Visits (Completed / Planned) -->
                            <td class="visits-cell">
                              <span class="visits-tag">{{ item.completedVisits || 0 }} / {{ item.plannedVisits || 1 }}</span>
                            </td>

                            <!-- Doctor -->
                            <td>
                              {{ item.doctorName || '—' }}
                            </td>

                            <!-- Financials -->
                            <td class="fe-mono">{{ format.currency(item.estimatedCost) }}</td>
                            <td class="fe-mono kpi-discount">{{ format.currency(item.discountAmount || 0) }}</td>
                            <td class="fe-mono kpi-insurance">{{ format.currency(item.insuranceCoverage || 0) }}</td>
                            <td class="fe-mono kpi-patient">{{ format.currency(item.patientPortion || (item.estimatedCost - (item.discountAmount || 0) - (item.insuranceCoverage || 0))) }}</td>

                            <!-- Status -->
                            <td>
                              @if (isPlanDraftOrPresented()) {
                                <select
                                  class="fe-select item-status-select"
                                  [ngModel]="item.status"
                                  (ngModelChange)="onItemStatusChange(item, $event)"
                                >
                                  <option value="PROPOSED">{{ 'clinic.treatmentPlans.itemStatus.proposed' | t }}</option>
                                  <option value="APPROVED">{{ 'clinic.treatmentPlans.itemStatus.approved' | t }}</option>
                                  <option value="IN_PROGRESS">{{ 'clinic.treatmentPlans.itemStatus.inProgress' | t }}</option>
                                  <option value="COMPLETED">{{ 'clinic.treatmentPlans.itemStatus.completed' | t }}</option>
                                  <option value="CANCELLED">{{ 'clinic.treatmentPlans.itemStatus.cancelled' | t }}</option>
                                </select>
                              } @else {
                                <fe-status-chip [tone]="itemStatusTone(item.status)">
                                  {{ itemStatusLabel(item.status) }}
                                </fe-status-chip>
                              }
                            </td>

                            <!-- Actions (only in draft/presented plans) -->
                            @if (isPlanDraftOrPresented()) {
                              <td>
                                <button
                                  class="action-icon-btn"
                                  title="حذف الخطوة"
                                  (click)="deleteItem(item.id)"
                                >
                                  -
                                </button>
                              </td>
                            }
                          </tr>
                        }
                      </tbody>
                    </table>
                  </div>
                }
              </section>
            }
          }
        }
      }

      <!-- Create Plan Modal -->
      @if (showCreatePlanModal()) {
        <div class="modal-backdrop" (click)="closeCreatePlanModal()">
          <div class="modal-card" (click)="$event.stopPropagation()">
            <div class="modal-header">
              <h3>{{ 'clinic.treatmentPlans.createModal.title' | t }}</h3>
              <button class="modal-close" (click)="closeCreatePlanModal()">&times;</button>
            </div>
            <div class="modal-body">
              <label class="form-field">
                <span class="fe-eyebrow">{{ 'clinic.treatmentPlans.planTitle' | t }}</span>
                <input
                  type="text"
                  class="fe-input"
                  [(ngModel)]="newPlanTitle"
                  placeholder="مثال: خطة إعادة تأهيل الفك العلوي والتقويم"
                />
              </label>

              <div class="form-row">
                <label class="form-field">
                  <span class="fe-eyebrow">{{ 'clinic.treatmentPlans.planType' | t }}</span>
                  <select class="fe-select" [(ngModel)]="newPlanType">
                    <option value="PRIMARY">{{ 'clinic.treatmentPlans.planType.primary' | t }}</option>
                    <option value="ALTERNATIVE">{{ 'clinic.treatmentPlans.planType.alternative' | t }}</option>
                  </select>
                </label>

                <label class="form-field">
                  <span class="fe-eyebrow">{{ 'clinic.treatmentPlans.estimates.discount' | t }}</span>
                  <input
                    type="number"
                    min="0"
                    step="0.5"
                    class="fe-input"
                    [(ngModel)]="newPlanTotalDiscount"
                  />
                </label>
              </div>

              <div class="form-row">
                <label class="form-field">
                  <span class="fe-eyebrow">{{ 'clinic.treatmentPlans.estimates.insurance' | t }}</span>
                  <input
                    type="number"
                    min="0"
                    step="0.5"
                    class="fe-input"
                    [(ngModel)]="newPlanInsuranceCoverage"
                  />
                </label>

                <label class="form-field">
                  <span class="fe-eyebrow">{{ 'clinic.treatmentPlans.estimates.patient' | t }}</span>
                  <input
                    type="number"
                    min="0"
                    step="0.5"
                    class="fe-input"
                    [(ngModel)]="newPlanPatientPortion"
                  />
                </label>
              </div>

              <label class="form-field">
                <span class="fe-eyebrow">ملاحظات وتشخيص أولي</span>
                <textarea
                  class="fe-textarea"
                  rows="3"
                  [(ngModel)]="newPlanNotes"
                  placeholder="ملاحظات سريرية حول أهداف الخطة العلاجية..."
                ></textarea>
              </label>
            </div>
            <div class="modal-footer">
              <button class="fe-btn fe-btn--ghost" (click)="closeCreatePlanModal()">إلغاء</button>
              <button class="fe-btn fe-btn--primary" [disabled]="!newPlanTitle.trim()" (click)="saveNewPlan()">
                {{ 'clinic.treatmentPlans.createModal.submit' | t }}
              </button>
            </div>
          </div>
        </div>
      }

      <!-- Fork Plan Modal (Slice S5) -->
      @if (showForkModal()) {
        <div class="modal-backdrop" (click)="closeForkModal()">
          <div class="modal-card" (click)="$event.stopPropagation()">
            <div class="modal-header">
              <h3>{{ 'clinic.treatmentPlans.forkModal.title' | t }}</h3>
              <button class="modal-close" (click)="closeForkModal()">&times;</button>
            </div>
            <div class="modal-body">
              <label class="form-field">
                <span class="fe-eyebrow">{{ 'clinic.treatmentPlans.planTitle' | t }}</span>
                <input
                  type="text"
                  class="fe-input"
                  [(ngModel)]="forkTitle"
                />
              </label>

              <label class="form-field">
                <span class="fe-eyebrow">{{ 'clinic.treatmentPlans.forkModal.reason' | t }}</span>
                <textarea
                  class="fe-textarea"
                  rows="3"
                  [(ngModel)]="forkReason"
                  placeholder="سبب التعديل السريري على الخطة المعتمدة..."
                ></textarea>
              </label>
            </div>
            <div class="modal-footer">
              <button class="fe-btn fe-btn--ghost" (click)="closeForkModal()">إلغاء</button>
              <button class="fe-btn fe-btn--primary" (click)="saveForkPlan()">
                {{ 'clinic.treatmentPlans.forkModal.submit' | t }}
              </button>
            </div>
          </div>
        </div>
      }

      <!-- Approve Plan Modal (Slice S5) -->
      @if (showApproveModal()) {
        <div class="modal-backdrop" (click)="closeApproveModal()">
          <div class="modal-card" (click)="$event.stopPropagation()">
            <div class="modal-header">
              <h3>{{ 'clinic.treatmentPlans.approveModal.title' | t }}</h3>
              <button class="modal-close" (click)="closeApproveModal()">&times;</button>
            </div>
            <div class="modal-body">
              <p class="fe-sub">
                سيتم اعتماد الخطة وتفعيلها (ACTIVE). تصبح بنود الخطة بعدها غير قابلة للتعديل أو الحذف المباشر لضمان النزاهة السريرية.
              </p>
              <label class="form-field">
                <span class="fe-eyebrow">{{ 'clinic.treatmentPlans.approveModal.consentNotes' | t }}</span>
                <textarea
                  class="fe-textarea"
                  rows="3"
                  [(ngModel)]="approveConsentNotes"
                  placeholder="توثيق شروط الموافقة وخطة الدفع المتفق عليها..."
                ></textarea>
              </label>
            </div>
            <div class="modal-footer">
              <button class="fe-btn fe-btn--ghost" (click)="closeApproveModal()">إلغاء</button>
              <button class="fe-btn fe-btn--primary" (click)="saveApprovePlan()">
                {{ 'clinic.treatmentPlans.approveModal.submit' | t }}
              </button>
            </div>
          </div>
        </div>
      }

      <!-- Cancel Plan Modal (Slice S5) -->
      @if (showCancelModal()) {
        <div class="modal-backdrop" (click)="closeCancelModal()">
          <div class="modal-card" (click)="$event.stopPropagation()">
            <div class="modal-header">
              <h3>{{ 'clinic.treatmentPlans.cancelModal.title' | t }}</h3>
              <button class="modal-close" (click)="closeCancelModal()">&times;</button>
            </div>
            <div class="modal-body">
              <label class="form-field">
                <span class="fe-eyebrow">{{ 'clinic.treatmentPlans.cancelModal.reason' | t }}</span>
                <textarea
                  class="fe-textarea"
                  rows="3"
                  [(ngModel)]="cancelReason"
                  placeholder="سبب إلغاء الخطة العلاجية..."
                ></textarea>
              </label>
            </div>
            <div class="modal-footer">
              <button class="fe-btn fe-btn--ghost" (click)="closeCancelModal()">إلغاء</button>
              <button class="fe-btn fe-btn--danger" (click)="saveCancelPlan()">
                {{ 'clinic.treatmentPlans.cancelModal.submit' | t }}
              </button>
            </div>
          </div>
        </div>
      }

      <!-- Add Procedure Item Modal (Stages & Multi-Visit) -->
      @if (showAddItemModal()) {
        <div class="modal-backdrop" (click)="closeAddItemModal()">
          <div class="modal-card modal-card--wide" (click)="$event.stopPropagation()">
            <div class="modal-header">
              <h3>{{ 'clinic.treatmentPlans.createItemModal.title' | t }}</h3>
              <button class="modal-close" (click)="closeAddItemModal()">&times;</button>
            </div>
            <div class="modal-body">
              <div class="form-row">
                <label class="form-field form-field--sm">
                  <span class="fe-eyebrow">الترتيب (Seq)</span>
                  <input
                    type="number"
                    min="1"
                    class="fe-input"
                    [(ngModel)]="newItemSeq"
                  />
                </label>

                <label class="form-field">
                  <span class="fe-eyebrow">المرحلة العلاجية (Stage)</span>
                  <select class="fe-select" [(ngModel)]="newItemStage">
                    <option value="PHASE_1">{{ 'clinic.treatmentPlans.stage.phaseOne' | t }}</option>
                    <option value="PHASE_2">{{ 'clinic.treatmentPlans.stage.phaseTwo' | t }}</option>
                    <option value="PHASE_3">{{ 'clinic.treatmentPlans.stage.phaseThree' | t }}</option>
                  </select>
                </label>

                <label class="form-field form-field--sm">
                  <span class="fe-eyebrow">الزيارات المخططة</span>
                  <input
                    type="number"
                    min="1"
                    class="fe-input"
                    [(ngModel)]="newItemPlannedVisits"
                  />
                </label>
              </div>

              <div class="form-row">
                <label class="form-field">
                  <span class="fe-eyebrow">رقم السن (نظام FDI 11-85)</span>
                  <input
                    type="number"
                    min="11"
                    max="85"
                    class="fe-input"
                    [(ngModel)]="newItemToothFdi"
                    placeholder="اتركه فارغاً لإجراء عام على كامل الفم"
                  />
                </label>
              </div>

              @if (newItemToothFdi) {
                <div class="tooth-helper-tip">
                  السن المحدد: <strong>{{ toothFdiLabel(newItemToothFdi) }}</strong>
                </div>
              }

              <label class="form-field">
                <span class="fe-eyebrow">اسم الإجراء العلاجي</span>
                <input
                  type="text"
                  class="fe-input"
                  [(ngModel)]="newItemProcedureName"
                  placeholder="مثال: حشوة ضوئية مركبة، علاج عصب، تركيب تاج زيركون"
                />
              </label>

              <div class="form-row">
                <label class="form-field">
                  <span class="fe-eyebrow">التكلفة التقديرية (د.أ)</span>
                  <input
                    type="number"
                    step="0.5"
                    min="0"
                    class="fe-input"
                    [(ngModel)]="newItemCost"
                  />
                </label>

                <label class="form-field">
                  <span class="fe-eyebrow">الخصم (د.أ)</span>
                  <input
                    type="number"
                    step="0.5"
                    min="0"
                    class="fe-input"
                    [(ngModel)]="newItemDiscount"
                  />
                </label>
              </div>

              <div class="form-row">
                <label class="form-field">
                  <span class="fe-eyebrow">تغطية التأمين (د.أ)</span>
                  <input
                    type="number"
                    step="0.5"
                    min="0"
                    class="fe-input"
                    [(ngModel)]="newItemInsurance"
                  />
                </label>

                <label class="form-field">
                  <span class="fe-eyebrow">حصة المريض (د.أ)</span>
                  <input
                    type="number"
                    step="0.5"
                    min="0"
                    class="fe-input"
                    [(ngModel)]="newItemPatientPortion"
                  />
                </label>
              </div>

              <label class="form-field">
                <span class="fe-eyebrow">الطبيب المعالج</span>
                <select class="fe-select" [(ngModel)]="newItemDoctorId">
                  <option [ngValue]="null">اختر الطبيب (اختياري)</option>
                  @for (doc of doctorsList(); track doc.id) {
                    <option [value]="doc.id">{{ doc.name }} ({{ doc.specialty }})</option>
                  }
                </select>
              </label>

              <label class="form-field">
                <span class="fe-eyebrow">ملاحظات الإجراء</span>
                <textarea
                  class="fe-textarea"
                  rows="2"
                  [(ngModel)]="newItemNotes"
                  placeholder="ملاحظات حول المواد المستخدمة، عدد القنوات، التخدير..."
                ></textarea>
              </label>
            </div>
            <div class="modal-footer">
              <button class="fe-btn fe-btn--ghost" (click)="closeAddItemModal()">إلغاء</button>
              <button
                class="fe-btn fe-btn--primary"
                [disabled]="!newItemProcedureName.trim()"
                (click)="saveNewItem()"
              >
                {{ 'clinic.treatmentPlans.createItemModal.submit' | t }}
              </button>
            </div>
          </div>
        </div>
      }

      <!-- Record Consent Modal -->
      @if (showConsentModal()) {
        <div class="modal-backdrop" (click)="closeConsentModal()">
          <div class="modal-card" (click)="$event.stopPropagation()">
            <div class="modal-header">
              <h3>توثيق موافقة المريض على الخطة</h3>
              <button class="modal-close" (click)="closeConsentModal()">&times;</button>
            </div>
            <div class="modal-body">
              <label class="consent-checkbox-row">
                <input type="checkbox" [(ngModel)]="consentValue" />
                <span>أقرّ بأن المريض قد تم اطلاعه على الخطة العلاجية والتكاليف ووافق عليها.</span>
              </label>

              <label class="form-field">
                <span class="fe-eyebrow">ملاحظات وشروط الموافقة</span>
                <textarea
                  class="fe-textarea"
                  rows="3"
                  [(ngModel)]="consentNotes"
                  placeholder="أي شروط متفق عليها، دفعات مسبقة، أو موافقة ولي الأمر..."
                ></textarea>
              </label>
            </div>
            <div class="modal-footer">
              <button class="fe-btn fe-btn--ghost" (click)="closeConsentModal()">إلغاء</button>
              <button class="fe-btn fe-btn--primary" (click)="saveConsent()">
                تأكيد التوثيق
              </button>
            </div>
          </div>
        </div>
      }
    </div>
  `,
  styles: [
    `
      .header-actions {
        display: flex;
        gap: var(--fe-space-2);
        align-items: center;
      }

      .empty-card {
        text-align: center;
        padding: var(--fe-space-8);
      }
      .empty-icon {
        font-size: 3rem;
        margin-block-end: var(--fe-space-3);
      }

      .plan-tabs-bar {
        margin-block-end: var(--fe-space-4);
        border-block-end: 1px solid var(--line);
      }
      .plan-tabs {
        display: flex;
        gap: var(--fe-space-2);
        overflow-x: auto;
      }
      .plan-tab {
        display: inline-flex;
        align-items: center;
        gap: var(--fe-space-2);
        padding: var(--fe-space-2) var(--fe-space-4);
        border: none;
        border-block-end: 3px solid transparent;
        background: transparent;
        cursor: pointer;
        font-family: inherit;
        font-size: var(--fe-text-sm);
        color: var(--mute);
        transition: all 0.2s ease;
      }
      .plan-tab:hover {
        color: var(--ink);
        background: var(--surf-soft);
      }
      .plan-tab--active {
        color: var(--pri);
        border-block-end-color: var(--pri);
        font-weight: 600;
      }

      .version-tag {
        font-size: var(--fe-text-xs);
        padding: 1px 6px;
        border-radius: var(--fe-radius-sm);
        background: var(--surf2);
        color: var(--ink);
        font-family: monospace;
      }

      .plan-summary-card {
        margin-block-end: var(--fe-space-4);
      }
      .summary-top {
        display: flex;
        justify-content: space-between;
        align-items: flex-start;
        gap: var(--fe-space-4);
        flex-wrap: wrap;
      }
      .title-row {
        display: flex;
        align-items: center;
        gap: var(--fe-space-3);
        flex-wrap: wrap;
      }
      .title-row h2 {
        margin: 0;
        font-size: var(--fe-text-xl);
      }
      .version-badge {
        font-size: var(--fe-text-sm);
        font-weight: 700;
        padding: 2px 8px;
        border-radius: var(--fe-radius-sm);
        background: var(--pri-soft);
        color: var(--pri);
        font-family: monospace;
      }
      .parent-ref-chip {
        font-size: var(--fe-text-xs);
        padding: 2px 8px;
        border-radius: var(--fe-radius-sm);
        background: var(--surf-soft);
        border: 1px solid var(--line);
        color: var(--mute);
      }
      .plan-notes {
        margin-block-start: var(--fe-space-2);
        margin-block-end: 0;
      }

      .summary-actions {
        display: flex;
        align-items: center;
        gap: var(--fe-space-2);
        flex-wrap: wrap;
      }
      .status-select {
        padding: 6px 12px;
        border-radius: var(--fe-radius-md);
        border: 1px solid var(--line);
        background: var(--surf);
        font-family: inherit;
        font-size: var(--fe-text-sm);
        color: var(--ink);
      }

      .immutable-banner {
        margin-block: var(--fe-space-3);
        padding: var(--fe-space-2) var(--fe-space-3);
        border-radius: var(--fe-radius-md);
        background: var(--surf-soft);
        border: 1px solid var(--line);
        color: var(--ink);
        display: flex;
        align-items: center;
        gap: var(--fe-space-2);
        font-size: var(--fe-text-xs);
      }
      .banner-badge {
        font-weight: 700;
        font-size: 10px;
        padding: 1px 6px;
        border-radius: var(--fe-radius-sm);
        background: var(--pri-soft);
        color: var(--pri);
      }

      .consent-box {
        margin-block: var(--fe-space-4);
        padding: var(--fe-space-3) var(--fe-space-4);
        border-radius: var(--fe-radius-lg);
        background: var(--warn-soft);
        border: 1px solid var(--warn);
        display: flex;
        justify-content: space-between;
        align-items: center;
        gap: var(--fe-space-4);
        flex-wrap: wrap;
      }
      .consent-box--approved {
        background: var(--ok-soft);
        border-color: var(--ok);
      }
      .consent-status-badge {
        display: flex;
        align-items: center;
        gap: var(--fe-space-2);
        font-size: var(--fe-text-sm);
      }
      .consent-icon {
        display: inline-flex;
        align-items: center;
        justify-content: center;
        width: 22px;
        height: 22px;
        border-radius: 50%;
        background: var(--ok);
        color: var(--surf);
        font-weight: bold;
      }
      .consent-icon.pending {
        background: var(--warn);
      }
      .consent-details {
        display: flex;
        gap: var(--fe-space-4);
        font-size: var(--fe-text-xs);
        margin-block-start: var(--fe-space-1);
        color: var(--ink);
        flex-wrap: wrap;
      }
      .consent-note-text {
        font-style: italic;
        color: var(--mute);
      }

      .kpi-grid {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(9.5rem, 1fr));
        gap: var(--fe-space-3);
        margin-block-start: var(--fe-space-4);
      }
      .kpi-card {
        padding: var(--fe-space-3);
        background: var(--surf-soft);
        border: 1px solid var(--line);
        border-radius: var(--fe-radius-md);
        display: flex;
        flex-direction: column;
        gap: var(--fe-space-1);
      }
      .kpi-value {
        font-size: 1.15rem;
        font-family: monospace;
        color: var(--ink);
      }
      .kpi-discount {
        color: var(--warn);
      }
      .kpi-insurance {
        color: var(--pri);
      }
      .kpi-patient {
        color: var(--ink);
        font-weight: 700;
      }
      .kpi-invoiced {
        color: var(--mute);
      }
      .kpi-collected {
        color: var(--ok);
      }
      .kpi-balance {
        color: var(--bad);
      }

      .items-header {
        display: flex;
        justify-content: space-between;
        align-items: center;
        margin-block-end: var(--fe-space-4);
        flex-wrap: wrap;
        gap: var(--fe-space-2);
      }
      .items-header h3 {
        margin: 0;
      }
      .items-header p {
        margin: 0;
      }

      .empty-items-box {
        text-align: center;
        padding: var(--fe-space-6);
        background: var(--surf-soft);
        border: 1px dashed var(--line);
        border-radius: var(--fe-radius-md);
      }

      .table-container {
        overflow-x: auto;
      }
      .fe-table {
        width: 100%;
        border-collapse: collapse;
        font-size: var(--fe-text-sm);
      }
      .fe-table th {
        text-align: right;
        padding: var(--fe-space-2) var(--fe-space-3);
        border-block-end: 2px solid var(--line);
        color: var(--mute);
        font-size: var(--fe-text-xs);
        font-weight: 600;
        text-transform: uppercase;
      }
      .fe-table td {
        padding: var(--fe-space-3);
        border-block-end: 1px solid var(--line);
        vertical-align: middle;
      }
      .item-row--completed {
        background: var(--ok-soft);
      }
      .seq-badge {
        display: inline-flex;
        align-items: center;
        justify-content: center;
        min-width: 28px;
        height: 28px;
        padding: 0 6px;
        border-radius: var(--fe-radius-md);
        background: var(--pri-soft);
        color: var(--pri);
        font-weight: 600;
        font-family: monospace;
      }
      .stage-badge {
        display: inline-flex;
        padding: 2px 8px;
        border-radius: var(--fe-radius-sm);
        background: var(--surf2);
        color: var(--ink);
        font-size: var(--fe-text-xs);
        font-weight: 600;
      }
      .tooth-badge {
        display: inline-flex;
        flex-direction: column;
        padding: 3px 8px;
        background: var(--surf-soft);
        border: 1px solid var(--line);
        border-radius: var(--fe-radius-sm);
        font-weight: 600;
        font-size: var(--fe-text-xs);
      }
      .tooth-sub {
        font-weight: normal;
        font-size: 10px;
        color: var(--mute);
      }
      .general-tooth-badge {
        font-size: var(--fe-text-xs);
        color: var(--mute);
        padding: 2px 6px;
        background: var(--surf-soft);
        border-radius: var(--fe-radius-sm);
      }
      .visits-tag {
        font-family: monospace;
        font-size: var(--fe-text-xs);
        padding: 2px 6px;
        background: var(--surf-soft);
        border-radius: var(--fe-radius-sm);
      }
      .item-notes {
        font-size: var(--fe-text-xs);
        color: var(--mute);
        margin-block-start: 2px;
      }
      .item-status-select {
        padding: 4px 8px;
        border-radius: var(--fe-radius-sm);
        border: 1px solid var(--line);
        font-size: var(--fe-text-xs);
        background: var(--surf);
        font-family: inherit;
      }
      .action-icon-btn {
        background: transparent;
        border: none;
        cursor: pointer;
        padding: 4px;
        font-size: 1rem;
        opacity: 0.6;
        transition: opacity 0.2s;
      }
      .action-icon-btn:hover {
        opacity: 1;
      }

      /* Modal styles */
      .modal-backdrop {
        position: fixed;
        inset: 0;
        background: var(--ink);
        opacity: 0.95;
        display: flex;
        align-items: center;
        justify-content: center;
        z-index: 1000;
      }
      .modal-card {
        background: var(--surf);
        border: 1px solid var(--line);
        border-radius: var(--fe-radius-lg);
        width: 90%;
        max-width: 480px;
        box-shadow: var(--shadow);
        display: flex;
        flex-direction: column;
        overflow: hidden;
      }
      .modal-card--wide {
        max-width: 600px;
      }
      .modal-header {
        display: flex;
        justify-content: space-between;
        align-items: center;
        padding: var(--fe-space-3) var(--fe-space-4);
        border-block-end: 1px solid var(--line);
      }
      .modal-header h3 {
        margin: 0;
        font-size: var(--fe-text-base);
      }
      .modal-close {
        background: transparent;
        border: none;
        font-size: 1.25rem;
        cursor: pointer;
        color: var(--mute);
      }
      .modal-body {
        padding: var(--fe-space-4);
        display: flex;
        flex-direction: column;
        gap: var(--fe-space-3);
      }
      .modal-footer {
        display: flex;
        justify-content: flex-end;
        gap: var(--fe-space-2);
        padding: var(--fe-space-3) var(--fe-space-4);
        border-block-start: 1px solid var(--line);
        background: var(--surf-soft);
      }

      .form-field {
        display: flex;
        flex-direction: column;
        gap: var(--fe-space-1);
      }
      .form-field--sm {
        max-width: 120px;
      }
      .form-row {
        display: flex;
        gap: var(--fe-space-3);
      }
      .form-row > * {
        flex: 1;
      }
      .fe-input, .fe-textarea, .fe-select {
        width: 100%;
        padding: 8px 12px;
        border: 1px solid var(--line);
        border-radius: var(--fe-radius-md);
        background: var(--surf);
        font-family: inherit;
        font-size: var(--fe-text-sm);
        color: var(--ink);
        box-sizing: border-box;
      }
      .tooth-helper-tip {
        padding: 6px 12px;
        background: var(--pri-soft);
        color: var(--pri);
        border-radius: var(--fe-radius-sm);
        font-size: var(--fe-text-xs);
      }
      .consent-checkbox-row {
        display: flex;
        align-items: flex-start;
        gap: var(--fe-space-2);
        font-size: var(--fe-text-sm);
        line-height: 1.4;
        cursor: pointer;
      }
      .consent-checkbox-row input {
        margin-block-start: 3px;
      }
    `,
  ],
})
export class ClinicTreatmentPlansComponent {
  private readonly api = inject(ClinicPatientApi);
  private readonly clinicApi = inject(ClinicApi);
  private readonly route = inject(ActivatedRoute);
  private readonly projects = inject(ProjectContextService);
  protected readonly rbac = inject(ClinicRbacService);

  protected readonly canManagePlans = computed(() =>
    this.rbac.hasPermission('treatment_plans.manage')
  );

  protected readonly format = inject(FormatService);
  protected readonly plansStore = new ResourceStore<ClinicTreatmentPlanHeader[]>();
  protected readonly selectedPlanId = signal<string | null>(null);
  protected readonly activePlanDetail = signal<ClinicTreatmentPlanDetail | null>(null);
  protected readonly doctorsList = signal<Array<{ id: string; name: string; specialty: string }>>([]);

  // Modals state
  protected readonly showCreatePlanModal = signal<boolean>(false);
  protected newPlanTitle = '';
  protected newPlanNotes = '';
  protected newPlanType = 'PRIMARY';
  protected newPlanTotalDiscount = 0;
  protected newPlanInsuranceCoverage = 0;
  protected newPlanPatientPortion = 0;

  protected readonly showForkModal = signal<boolean>(false);
  protected forkTitle = '';
  protected forkReason = '';

  protected readonly showApproveModal = signal<boolean>(false);
  protected approveConsentNotes = '';

  protected readonly showCancelModal = signal<boolean>(false);
  protected cancelReason = '';

  protected readonly showAddItemModal = signal<boolean>(false);
  protected newItemSeq = 1;
  protected newItemStage = 'PHASE_1';
  protected newItemPlannedVisits = 1;
  protected newItemToothFdi: number | null = null;
  protected newItemProcedureName = '';
  protected newItemCost = 0;
  protected newItemDiscount = 0;
  protected newItemInsurance = 0;
  protected newItemPatientPortion = 0;
  protected newItemDoctorId: string | null = null;
  protected newItemNotes = '';

  protected readonly showConsentModal = signal<boolean>(false);
  protected consentValue = true;
  protected consentNotes = '';

  protected readonly patientId = toSignal(
    this.route.paramMap.pipe(map((params) => params.get('id'))),
    { initialValue: null },
  );

  protected readonly projectId = computed(() => this.projects.selectedProjectId() ?? undefined);

  constructor() {
    inject(I18nService).register({ clinic: clinicStrings as Namespace });

    // Load plans when patient changes
    effect(() => {
      const pId = this.patientId();
      if (!pId) return;

      untracked(() => {
        this.reloadPlans();
        this.loadDoctors();
      });
    });

    // Load active plan detail when selectedPlanId changes
    effect(() => {
      const planId = this.selectedPlanId();
      const pId = this.patientId();
      if (!planId || !pId) {
        this.activePlanDetail.set(null);
        return;
      }

      untracked(() => {
        this.loadPlanDetail(pId, planId);
      });
    });
  }

  protected isPlanDraftOrPresented(): boolean {
    const status = this.activePlanDetail()?.plan.status;
    return status === 'DRAFT' || status === 'PRESENTED';
  }

  protected isPlanActive(): boolean {
    return this.activePlanDetail()?.plan.status === 'ACTIVE';
  }

  protected async reloadPlans(): Promise<void> {
    const pId = this.patientId();
    if (!pId) return;

    await this.plansStore.load(async () => {
      const list = await this.api.treatmentPlans(pId, { projectId: this.projectId() });
      if (list.length > 0 && (!this.selectedPlanId() || !list.some((p) => p.id === this.selectedPlanId()))) {
        this.selectedPlanId.set(list[0].id);
      } else if (list.length === 0) {
        this.selectedPlanId.set(null);
      }
      return list;
    });
  }

  private async loadDoctors(): Promise<void> {
    try {
      const page = await this.clinicApi.doctors({ projectId: this.projectId() });
      const docs = (page.items ?? [])
        .filter((d): d is typeof d & { id: string } => !!d.id)
        .map((d) => ({
          id: d.id,
          name: d.name ?? 'طبيب أسنان',
          specialty: d.specialty ?? '',
        }));
      this.doctorsList.set(docs);
    } catch {
      // Ignored if doctors list fails to load
    }
  }

  private async loadPlanDetail(patientId: string, planId: string): Promise<void> {
    try {
      const detail = await this.api.treatmentPlan(patientId, planId, { projectId: this.projectId() });
      this.activePlanDetail.set(detail);
    } catch (e) {
      console.error('Failed to load treatment plan detail', e);
    }
  }

  protected selectPlan(id: string): void {
    this.selectedPlanId.set(id);
  }

  protected planStatusTone(status: TreatmentPlanStatus): StatusTone {
    switch (status) {
      case 'DRAFT': return 'neutral';
      case 'PRESENTED': return 'warn';
      case 'ACTIVE': return 'human';
      case 'COMPLETED': return 'ok';
      case 'CANCELLED': return 'bad';
    }
  }

  protected planStatusLabel(status: TreatmentPlanStatus): string {
    switch (status) {
      case 'DRAFT': return 'مسودة';
      case 'PRESENTED': return 'معروضة على المريض';
      case 'ACTIVE': return 'معتمدة / قيد التنفيذ';
      case 'COMPLETED': return 'مكتملة';
      case 'CANCELLED': return 'ملغاة';
    }
  }

  protected itemStatusTone(status: TreatmentPlanItemStatus): StatusTone {
    switch (status) {
      case 'PROPOSED': return 'neutral';
      case 'APPROVED': return 'human';
      case 'IN_PROGRESS': return 'warn';
      case 'COMPLETED': return 'ok';
      case 'CANCELLED': return 'bad';
    }
  }

  protected itemStatusLabel(status: TreatmentPlanItemStatus): string {
    switch (status) {
      case 'PROPOSED': return 'مقترح';
      case 'APPROVED': return 'معتمد';
      case 'IN_PROGRESS': return 'قيد التنفيذ';
      case 'COMPLETED': return 'مكتمل';
      case 'CANCELLED': return 'ملغي';
    }
  }

  protected async onPlanStatusChange(newStatus: TreatmentPlanStatus): Promise<void> {
    const pId = this.patientId();
    const planId = this.selectedPlanId();
    if (!pId || !planId) return;

    try {
      await this.api.updateTreatmentPlanStatus(
        pId,
        planId,
        { status: newStatus },
        { projectId: this.projectId() },
      );
      await this.reloadPlans();
      await this.loadPlanDetail(pId, planId);
    } catch (e) {
      console.error('Failed to update plan status', e);
    }
  }

  protected async onItemStatusChange(item: ClinicTreatmentPlanItem, newStatus: TreatmentPlanItemStatus): Promise<void> {
    const pId = this.patientId();
    const planId = this.selectedPlanId();
    if (!pId || !planId) return;

    try {
      await this.api.updateTreatmentPlanItem(
        pId,
        planId,
        item.id,
        { status: newStatus },
        { projectId: this.projectId() },
      );
      await this.reloadPlans();
      await this.loadPlanDetail(pId, planId);
    } catch (e) {
      console.error('Failed to update item status', e);
    }
  }

  // FDI tooth description helper
  protected toothFdiLabel(fdi: number): string {
    const quadrant = Math.floor(fdi / 10);
    const tooth = fdi % 10;
    const names = ['', 'قاطع مركزي', 'قاطع جانبي', 'ناب', 'ضاحك أول', 'ضاحك ثانٍ', 'طاحن أول', 'طاحن ثانٍ', 'طاحن ثالث (العقل)'];
    const toothName = names[tooth] || `سن ${tooth}`;

    switch (quadrant) {
      case 1: return `${toothName} - علوي أيمن دائمة`;
      case 2: return `${toothName} - علوي أيسر دائمة`;
      case 3: return `${toothName} - سفلي أيسر دائمة`;
      case 4: return `${toothName} - سفلي أيمن دائمة`;
      case 5: return `${toothName} - علوي أيمن لبنية`;
      case 6: return `${toothName} - علوي أيسر لبنية`;
      case 7: return `${toothName} - سفلي أيسر لبنية`;
      case 8: return `${toothName} - سفلي أيمن لبنية`;
      default: return `FDI #${fdi}`;
    }
  }

  // Modals operations
  protected openCreatePlanModal(): void {
    this.newPlanTitle = '';
    this.newPlanNotes = '';
    this.newPlanType = 'PRIMARY';
    this.newPlanTotalDiscount = 0;
    this.newPlanInsuranceCoverage = 0;
    this.newPlanPatientPortion = 0;
    this.showCreatePlanModal.set(true);
  }

  protected closeCreatePlanModal(): void {
    this.showCreatePlanModal.set(false);
  }

  protected async saveNewPlan(): Promise<void> {
    const pId = this.patientId();
    if (!pId || !this.newPlanTitle.trim()) return;

    try {
      const res = await this.api.createTreatmentPlan(pId, {
        title: this.newPlanTitle.trim(),
        notes: this.newPlanNotes.trim() || undefined,
        planType: this.newPlanType,
        totalDiscount: Number(this.newPlanTotalDiscount) || undefined,
        insuranceEstimatedCoverage: Number(this.newPlanInsuranceCoverage) || undefined,
        patientEstimatedPortion: Number(this.newPlanPatientPortion) || undefined,
        projectId: this.projectId(),
      });
      this.closeCreatePlanModal();
      await this.reloadPlans();
      this.selectedPlanId.set(res.id);
    } catch (e) {
      console.error('Failed to create plan', e);
    }
  }

  // Forking operations (Slice S5)
  protected openForkModal(): void {
    const plan = this.activePlanDetail()?.plan;
    const nextVer = (plan?.version ?? 1) + 1;
    this.forkTitle = plan ? `${plan.title} (v${nextVer})` : '';
    this.forkReason = '';
    this.showForkModal.set(true);
  }

  protected closeForkModal(): void {
    this.showForkModal.set(false);
  }

  protected async saveForkPlan(): Promise<void> {
    const pId = this.patientId();
    const planId = this.selectedPlanId();
    if (!pId || !planId) return;

    try {
      const res = await this.api.forkTreatmentPlan(
        pId,
        planId,
        {
          title: this.forkTitle.trim() || undefined,
          changeReason: this.forkReason.trim() || undefined,
        },
        { projectId: this.projectId() },
      );
      this.closeForkModal();
      await this.reloadPlans();
      this.selectedPlanId.set(res.id);
    } catch (e) {
      console.error('Failed to fork treatment plan', e);
    }
  }

  // Approval operations (Slice S5)
  protected openApproveModal(): void {
    this.approveConsentNotes = '';
    this.showApproveModal.set(true);
  }

  protected closeApproveModal(): void {
    this.showApproveModal.set(false);
  }

  protected async saveApprovePlan(): Promise<void> {
    const pId = this.patientId();
    const planId = this.selectedPlanId();
    if (!pId || !planId) return;

    try {
      await this.api.approveTreatmentPlan(
        pId,
        planId,
        {
          consentNotes: this.approveConsentNotes.trim() || undefined,
        },
        { projectId: this.projectId() },
      );
      this.closeApproveModal();
      await this.reloadPlans();
      await this.loadPlanDetail(pId, planId);
    } catch (e) {
      console.error('Failed to approve treatment plan', e);
    }
  }

  // Cancel operations (Slice S5)
  protected openCancelModal(): void {
    this.cancelReason = '';
    this.showCancelModal.set(true);
  }

  protected closeCancelModal(): void {
    this.showCancelModal.set(false);
  }

  protected async saveCancelPlan(): Promise<void> {
    const pId = this.patientId();
    const planId = this.selectedPlanId();
    if (!pId || !planId) return;

    try {
      await this.api.cancelTreatmentPlan(
        pId,
        planId,
        {
          cancellationReason: this.cancelReason.trim() || undefined,
        },
        { projectId: this.projectId() },
      );
      this.closeCancelModal();
      await this.reloadPlans();
      await this.loadPlanDetail(pId, planId);
    } catch (e) {
      console.error('Failed to cancel treatment plan', e);
    }
  }

  protected openAddItemModal(): void {
    const detail = this.activePlanDetail();
    this.newItemSeq = (detail?.items.length ?? 0) + 1;
    this.newItemStage = 'PHASE_1';
    this.newItemPlannedVisits = 1;
    this.newItemToothFdi = null;
    this.newItemProcedureName = '';
    this.newItemCost = 0;
    this.newItemDiscount = 0;
    this.newItemInsurance = 0;
    this.newItemPatientPortion = 0;
    this.newItemDoctorId = null;
    this.newItemNotes = '';
    this.showAddItemModal.set(true);
  }

  protected closeAddItemModal(): void {
    this.showAddItemModal.set(false);
  }

  protected async saveNewItem(): Promise<void> {
    const pId = this.patientId();
    const planId = this.selectedPlanId();
    if (!pId || !planId || !this.newItemProcedureName.trim()) return;

    try {
      await this.api.addTreatmentPlanItem(
        pId,
        planId,
        {
          sequenceOrder: this.newItemSeq,
          stage: this.newItemStage,
          plannedVisits: Number(this.newItemPlannedVisits) || 1,
          toothFdi: this.newItemToothFdi || undefined,
          procedureName: this.newItemProcedureName.trim(),
          estimatedCost: Number(this.newItemCost) || 0,
          discountAmount: Number(this.newItemDiscount) || undefined,
          insuranceCoverage: Number(this.newItemInsurance) || undefined,
          patientPortion: Number(this.newItemPatientPortion) || undefined,
          doctorId: this.newItemDoctorId || undefined,
          notes: this.newItemNotes.trim() || undefined,
        },
        { projectId: this.projectId() },
      );
      this.closeAddItemModal();
      await this.reloadPlans();
      await this.loadPlanDetail(pId, planId);
    } catch (e) {
      console.error('Failed to add item', e);
    }
  }

  protected openConsentModal(consented: boolean): void {
    this.consentValue = consented;
    this.consentNotes = '';
    this.showConsentModal.set(true);
  }

  protected closeConsentModal(): void {
    this.showConsentModal.set(false);
  }

  protected async saveConsent(): Promise<void> {
    const pId = this.patientId();
    const planId = this.selectedPlanId();
    if (!pId || !planId) return;

    try {
      await this.api.recordTreatmentPlanConsent(
        pId,
        planId,
        {
          patientConsent: this.consentValue,
          consentNotes: this.consentNotes.trim() || undefined,
        },
        { projectId: this.projectId() },
      );
      this.closeConsentModal();
      await this.reloadPlans();
      await this.loadPlanDetail(pId, planId);
    } catch (e) {
      console.error('Failed to record consent', e);
    }
  }

  protected async deleteItem(itemId: string): Promise<void> {
    const pId = this.patientId();
    const planId = this.selectedPlanId();
    if (!pId || !planId) return;

    if (!confirm('هل أنت متأكد من حذف هذا الإجراء العلاجي من الخطة؟')) return;

    try {
      await this.api.deleteTreatmentPlanItem(pId, planId, itemId, { projectId: this.projectId() });
      await this.reloadPlans();
      await this.loadPlanDetail(pId, planId);
    } catch (e) {
      console.error('Failed to delete item', e);
    }
  }

  protected async deleteCurrentPlan(): Promise<void> {
    const pId = this.patientId();
    const planId = this.selectedPlanId();
    if (!pId || !planId) return;

    if (!confirm('هل أنت متأكد من حذف هذه الخطة العلاجية بالكامل؟')) return;

    try {
      await this.api.deleteTreatmentPlan(pId, planId, { projectId: this.projectId() });
      await this.reloadPlans();
    } catch (e) {
      console.error('Failed to delete plan', e);
    }
  }
}
