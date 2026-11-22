/**
 * `/app/clinic/patients/:id` — patient workspace.
 *
 * S1: Patients, Family, Guardians, Consents, Disclosures, and Record Lifecycle.
 */
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
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
import { FormatService } from '../../core/format/format.service';
import { I18nService } from '../../core/i18n/i18n.service';
import type { Namespace } from '../../core/i18n/i18n.types';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { ResourceStore } from '../../core/state/resource';
import { ClinicRbacService } from '../../core/auth/clinic-rbac';
import { AsyncStateComponent } from '../../ui/async-state.component';
import { IconComponent } from '../../ui/icon.component';
import { PageHeaderComponent } from '../../ui/page-header.component';
import { StatusChipComponent } from '../../ui/status-chip.component';
import {
  ClinicPatientApi,
  type ActiveIntakeCodeStatus,
  type ClinicConsent,
  type ClinicDisclosure,
  type ClinicMediaAccessAuditRow,
  type ClinicMediaCategory,
  type ClinicMediaRow,
  type ClinicMedicalHistory,
  type ClinicPatient,
  type ClinicRelationship,
  type GeneratedIntakeCode,
  type MergePatientsResult,
} from './clinic-patient.api';
import { buildIntakePortalUrl } from '../../core/utils/qr-svg';
import { clinicStrings } from './clinic.strings';

@Component({
  selector: 'fe-clinic-patient-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    AsyncStateComponent,
    FormsModule,
    IconComponent,
    PageHeaderComponent,
    RouterLink,
    StatusChipComponent,
    TranslatePipe,
  ],
  template: `
    <div class="fe-page" data-testid="clinic-patient">
      <fe-page-header
        [title]="'clinic.patient.title'"
        [description]="'clinic.patient.description'"
      >
        <div class="header-actions">
          <a class="fe-btn fe-btn--ghost" routerLink="/app/clinic/patients">
            {{ 'clinic.patient.back' | t }}
          </a>
          <button
            type="button"
            class="fe-btn fe-btn--ghost"
            (click)="reload()"
            [disabled]="loadingData()"
          >
            {{ 'clinic.refresh' | t }}
          </button>
        </div>
      </fe-page-header>

      @if (patientId() === null) {
        <p class="fe-sub" data-testid="clinic-patient-no-id">
          {{ 'clinic.patient.noId' | t }}
        </p>
      } @else {
        <fe-async-state
          [loading]="patient.loading() && patient.value() === null"
          [error]="patient.error()"
          [hasValue]="patient.value() !== null"
          [loadingLabel]="'state.loading' | t"
          [loadingHint]="'state.loadingHint' | t"
          [errorTitle]="'clinic.error.denied.title' | t"
          [errorBody]="'clinic.error.denied.body' | t"
          [requestId]="errorRequestId()"
          [requestIdLabel]="'state.requestId' | t"
          [retryLabel]="'clinic.retry' | t"
          (retry)="reload()"
        />

        @if (patient.value(); as row) {
          <!-- Patient 360 Sticky Master Context Header -->
          <div class="patient-context-banner fe-card">
            <div class="banner-main">
              <div class="patient-avatar-circle">
                {{ patientInitials() }}
              </div>
              <div class="patient-meta">
                <div class="patient-title-row">
                  <h2 class="patient-display-name">{{ row.displayName }}</h2>
                  <span class="patient-file-no fe-mono">{{ row.patientNo }}</span>
                  @if (row.deleted === true) {
                    <fe-status-chip tone="bad">{{ 'clinic.patients.deleted' | t }}</fe-status-chip>
                  }
                </div>
                <div class="patient-sub-facts">
                  @if (patientAge(); as age) {
                    <span class="fact-tag">العمر: {{ age }} سنة</span>
                  }
                  @if (row.primaryPhone) {
                    <a class="fact-tag contact-link" [href]="'tel:' + row.primaryPhone" dir="ltr">
                      <fe-icon name="phone" class="fact-tag-icon" />
                      {{ row.primaryPhone }}
                    </a>
                  }
                  @if (row.primaryEmail) {
                    <a class="fact-tag contact-link" [href]="'mailto:' + row.primaryEmail">
                      <fe-icon name="mail" class="fact-tag-icon" />
                      {{ row.primaryEmail }}
                    </a>
                  }
                  @if (row.locale) {
                    <span class="fact-tag fe-mono">اللغة: {{ row.locale }}</span>
                  }
                </div>
              </div>
            </div>

            <!-- Clinical Alerts Strip -->
            @if (medicalHistory(); as med) {
              @if (med.allergies || med.bleedingDisorder || med.cardiacCondition || med.diabetes || med.conditions) {
                <div class="clinical-alerts-strip">
                  <span class="alerts-label">
                    <fe-icon name="warning" class="alerts-label-icon" />
                    تنبيهات سريرية حرجة:
                  </span>
                  @if (med.allergies) {
                    <span class="alert-chip alert-chip--danger">حساسية: {{ med.allergies }}</span>
                  }
                  @if (med.bleedingDisorder) {
                    <span class="alert-chip alert-chip--danger">سيولة دم / نزيف</span>
                  }
                  @if (med.cardiacCondition) {
                    <span class="alert-chip alert-chip--warning">أمراض القلب والشرايين</span>
                  }
                  @if (med.diabetes) {
                    <span class="alert-chip alert-chip--warning">السكري</span>
                  }
                  @if (med.conditions) {
                    <span class="alert-chip alert-chip--info">{{ med.conditions }}</span>
                  }
                </div>
              }
            }

            <!-- Quick Action Shortcuts -->
            <div class="patient-quick-actions">
              @if (canViewOdontogram()) {
                <a class="action-btn" [routerLink]="['/app/clinic/patients', patientId(), 'odontogram']">
                  <fe-icon name="tooth" class="action-btn-icon" />
                  مخطط الأسنان السريري
                </a>
              }
              @if (canViewTreatmentPlans()) {
                <a class="action-btn" [routerLink]="['/app/clinic/patients', patientId(), 'treatment-plans']">
                  <fe-icon name="clipboard" class="action-btn-icon" />
                  خطط العلاج
                </a>
              }
              @if (canViewEncounters()) {
                <a class="action-btn" [routerLink]="['/app/clinic/patients', patientId(), 'encounters']">
                  <fe-icon name="stethoscope" class="action-btn-icon" />
                  سجل الزيارات والوصفات
                </a>
              }
              @if (canBookAppointment()) {
                <a class="action-btn" routerLink="/app/clinic/calendar">
                  <fe-icon name="calendar" class="action-btn-icon" />
                  حجز موعد
                </a>
              }
              @if (canViewInvoices()) {
                <a class="action-btn" routerLink="/app/clinic/invoices">
                  <fe-icon name="card" class="action-btn-icon" />
                  الفواتير والمطالبات
                </a>
              }
              <button
                type="button"
                class="action-btn action-btn--intake"
                data-testid="generate-intake-code-btn"
                [disabled]="generatingIntakeCode()"
                (click)="generatePatientIntakeCode()"
              >
                <fe-icon name="clipboard" class="action-btn-icon" />
                {{ generatingIntakeCode() ? 'جاري التوليد...' : (activeIntakeCode() ? 'إعادة توليد كود البوابة' : 'توليد الكود (بوابة المريض)') }}
              </button>
            </div>

            @if (activeIntakeCode(); as codeInfo) {
              <div class="intake-code-banner" data-testid="patient-intake-code-banner">
                <div class="intake-code-banner__main">
                  <span class="intake-code-label">كود دخول المريض للاستبيان (صالح لمدة ساعة واحدة):</span>
                  <strong class="intake-code-value fe-mono" dir="ltr">{{ codeInfo.formattedCode }}</strong>
                  <span class="intake-code-timer fe-mono">المتبقي: {{ countdownFormatted() }}</span>
                </div>
                <div class="intake-code-banner__actions">
                  <button type="button" class="fe-btn fe-btn--ghost fe-btn--xs" (click)="copyIntakeCode(codeInfo.code)">
                    {{ intakeCodeCopied() ? 'تم النسخ!' : 'نسخ الكود' }}
                  </button>
                  @if (clinicPortalUrl()) {
                    <a class="fe-btn fe-btn--ghost fe-btn--xs" [href]="clinicPortalUrl()" target="_blank" rel="noopener">
                      فتح البوابة
                    </a>
                  }
                </div>
              </div>
            } @else if (intakeStatus()?.hasActiveCode && intakeStatus()?.remainingSeconds) {
              <div class="intake-code-banner intake-code-banner--muted">
                <span>يوجد كود دخول فعّال للمريض ({{ intakeStatus()?.codeHint }}) — المتبقي: <strong class="fe-mono">{{ countdownFormatted() }}</strong></span>
                <button type="button" class="fe-btn fe-btn--ghost fe-btn--xs" (click)="generatePatientIntakeCode()">
                  توليد كود جديد
                </button>
              </div>
            }

            @if (intakeStatus()?.latestIntakeStatus === 'PENDING_APPROVAL') {
              <div class="intake-pending-banner" data-testid="patient-intake-pending-banner">
                <div>
                  <strong>نموذج المريض مُرسل من البوابة وبانتظار الاعتماد</strong>
                  <span> — قام المريض بتعبئة بياناته والتاريخ الطبي والشكوى الرئيسية. يمكن للطبيب المراجعة والتعديل ثم الاعتماد.</span>
                </div>
                @if (canViewEncounters()) {
                  <a class="fe-btn fe-btn--primary fe-btn--xs" [routerLink]="['/app/clinic/patients', patientId(), 'encounters']">
                    مراجعة واعتماد في الـ Encounter
                  </a>
                }
              </div>
            }
          </div>

          <!-- Tabs Navigation -->
          <nav class="patient-tabs" aria-label="Patient workspace tabs">
            <button
              type="button"
              class="tab-btn"
              [class.tab-btn--active]="activeTab() === 'overview'"
              (click)="activeTab.set('overview')"
            >
              {{ 'clinic.nav.overview' | t }}
            </button>
            <button
              type="button"
              class="tab-btn"
              [class.tab-btn--active]="activeTab() === 'medical'"
              (click)="activeTab.set('medical')"
            >
              {{ 'clinic.patient.medical.title' | t }}
              @if (medicalHistory()?.bleedingDisorder || medicalHistory()?.cardiacCondition || medicalHistory()?.diabetes) {
                <span style="display: inline-block; width: 8px; height: 8px; border-radius: 50%; background: var(--bad); margin-inline-start: 4px;"></span>
              }
            </button>
            <button
              type="button"
              class="tab-btn"
              [class.tab-btn--active]="activeTab() === 'family'"
              (click)="activeTab.set('family')"
            >
              {{ 'clinic.patient.family.title' | t }} ({{ relationships().length }})
            </button>
            <button
              type="button"
              class="tab-btn"
              [class.tab-btn--active]="activeTab() === 'consents'"
              (click)="activeTab.set('consents')"
            >
              {{ 'clinic.patient.consents.title' | t }} ({{ consents().length }})
            </button>
            <button
              type="button"
              class="tab-btn"
              [class.tab-btn--active]="activeTab() === 'media'"
              (click)="activeTab.set('media')"
              data-testid="tab-media"
            >
              {{ 'clinic.media.title' | t }} ({{ mediaItems().length }})
            </button>
            <button
              type="button"
              class="tab-btn"
              [class.tab-btn--active]="activeTab() === 'actions'"
              (click)="activeTab.set('actions')"
            >
              {{ 'clinic.patient.actions.title' | t }}
            </button>
          </nav>

          <!-- TAB 1: Overview -->
          @if (activeTab() === 'overview') {
            <section class="fe-card" [attr.aria-labelledby]="'clinic-patient-identity'">
              <div class="card-header">
                <h3 id="clinic-patient-identity">{{ 'clinic.patient.identity' | t }}</h3>
                @if (row.deleted === true) {
                  <fe-status-chip tone="bad">{{ 'clinic.patients.deleted' | t }}</fe-status-chip>
                }
              </div>

              <dl class="facts">
                <div>
                  <dt class="fe-eyebrow">{{ 'clinic.patients.col.name' | t }}</dt>
                  <dd class="fact-strong">{{ row.displayName ?? '—' }}</dd>
                </div>
                <div>
                  <dt class="fe-eyebrow">{{ 'clinic.patient.patientNo' | t }}</dt>
                  <dd class="fe-mono" dir="ltr">{{ row.patientNo ?? '—' }}</dd>
                </div>
                <div>
                  <dt class="fe-eyebrow">{{ 'clinic.patient.dateOfBirth' | t }}</dt>
                  <dd class="fe-mono">
                    @if (row.dateOfBirth) {
                      <time [attr.datetime]="row.dateOfBirth">
                        {{ format.date(row.dateOfBirth) }}
                      </time>
                    } @else {
                      <span class="unset">{{ 'clinic.patients.notRecorded' | t }}</span>
                    }
                  </dd>
                </div>
                <div>
                  <dt class="fe-eyebrow">{{ 'clinic.patients.col.contact' | t }}</dt>
                  <dd dir="ltr">{{ row.primaryPhone ?? row.primaryEmail ?? '—' }}</dd>
                </div>
                @if (row.occupation) {
                  <div>
                    <dt class="fe-eyebrow">المهنة / Occupation</dt>
                    <dd>{{ row.occupation }}</dd>
                  </div>
                }
                <div>
                  <dt class="fe-eyebrow">{{ 'clinic.patient.locale' | t }}</dt>
                  <dd class="fe-mono" dir="ltr">{{ row.locale ?? '—' }}</dd>
                </div>
                <div>
                  <dt class="fe-eyebrow">{{ 'clinic.patient.customerLink' | t }}</dt>
                  <dd class="fe-mono" dir="ltr">{{ row.customerId ?? '—' }}</dd>
                </div>
              </dl>
              <p class="fe-sub">{{ 'clinic.patient.customerLinkHint' | t }}</p>
            </section>
          }

          <!-- TAB: Medical History (Slice S3) -->
          @if (activeTab() === 'medical') {
            <section class="fe-card" data-testid="clinic-patient-medical-history">
              <div class="card-header">
                <div>
                  <h3>{{ 'clinic.patient.medical.title' | t }}</h3>
                  <p class="fe-sub">{{ 'clinic.patient.medical.hint' | t }}</p>
                </div>
              </div>

              @if (medSavedSuccess()) {
                <div class="success-banner" style="background: var(--ok-soft); color: var(--ok); padding: var(--fe-space-3); border-radius: var(--fe-radius); margin-block-end: var(--fe-space-3); font-weight: 500;">
                  {{ 'clinic.patient.medical.saved' | t }}
                </div>
              }

              @if (medBleeding || medCardiac || medDiabetes) {
                <div class="warning-banner" style="background: var(--bad-soft); color: var(--bad); padding: var(--fe-space-3); border-radius: var(--fe-radius); border-inline-start: 4px solid var(--bad); margin-block-end: var(--fe-space-4); font-size: 0.9rem;">
                  <strong>{{ 'clinic.patient.medical.alertBanner' | t }}</strong>
                </div>
              }

              <div style="display: flex; flex-direction: column; gap: var(--fe-space-3);">
                <!-- Risk flags checkboxes -->
                <div style="display: flex; flex-wrap: wrap; gap: var(--fe-space-4); padding: var(--fe-space-3); background: var(--surf2); border-radius: var(--fe-radius); border: 1px solid var(--line);">
                  <label class="checkbox-label" style="display: flex; align-items: center; gap: var(--fe-space-2); cursor: pointer;">
                    <input type="checkbox" [(ngModel)]="medBleeding" />
                    <strong>{{ 'clinic.patient.medical.bleeding' | t }}</strong>
                  </label>
                  <label class="checkbox-label" style="display: flex; align-items: center; gap: var(--fe-space-2); cursor: pointer;">
                    <input type="checkbox" [(ngModel)]="medCardiac" />
                    <strong>{{ 'clinic.patient.medical.cardiac' | t }}</strong>
                  </label>
                  <label class="checkbox-label" style="display: flex; align-items: center; gap: var(--fe-space-2); cursor: pointer;">
                    <input type="checkbox" [(ngModel)]="medDiabetes" />
                    <strong>{{ 'clinic.patient.medical.diabetes' | t }}</strong>
                  </label>
                </div>

                <div class="form-field">
                  <label class="fe-label">{{ 'clinic.patient.medical.allergies' | t }}</label>
                  <textarea
                    class="fe-input"
                    rows="2"
                    [(ngModel)]="medAllergies"
                    placeholder="مثال: حساسية البنسلين، حساسية اللاتكس، حساسية مضادات الالتهاب..."
                  ></textarea>
                </div>

                <div class="form-field">
                  <label class="fe-label">{{ 'clinic.patient.medical.conditions' | t }}</label>
                  <textarea
                    class="fe-input"
                    rows="2"
                    [(ngModel)]="medConditions"
                    placeholder="مثال: ارتفاع ضغط الدم، ربو قصبي، التهاب كبد، هشاشة عظام..."
                  ></textarea>
                </div>

                <div class="form-field">
                  <label class="fe-label">{{ 'clinic.patient.medical.medications' | t }}</label>
                  <textarea
                    class="fe-input"
                    rows="2"
                    [(ngModel)]="medMedications"
                    placeholder="مثال: Aspirin, Metformin, Lisinopril..."
                  ></textarea>
                </div>

                <div class="form-field">
                  <label class="fe-label">{{ 'clinic.patient.medical.notes' | t }}</label>
                  <textarea
                    class="fe-input"
                    rows="3"
                    [(ngModel)]="medNotes"
                    placeholder="ملاحظات سريرية واحتياطات قبل التخدير أو الجراحة الفكية..."
                  ></textarea>
                </div>

                <div style="display: flex; justify-content: flex-end; margin-block-start: var(--fe-space-2);">
                  <button
                    type="button"
                    class="fe-btn fe-btn--primary"
                    [disabled]="actionPending()"
                    (click)="saveMedicalHistory()"
                  >
                    {{ 'clinic.patient.medical.save' | t }}
                  </button>
                </div>
              </div>
            </section>
          }

          <!-- TAB 2: Family & Relationships -->
          @if (activeTab() === 'family') {
            <section class="fe-card">
              <div class="card-header">
                <div>
                  <h3>{{ 'clinic.patient.family.title' | t }}</h3>
                  <p class="fe-sub">{{ 'clinic.patient.family.hint' | t }}</p>
                </div>
                <button
                  type="button"
                  class="fe-btn fe-btn--primary"
                  (click)="isAddRelOpen.set(true)"
                >
                  {{ 'clinic.patient.family.add' | t }}
                </button>
              </div>

              @if (relationships().length === 0) {
                <div class="empty-banner">
                  <p>{{ 'clinic.patient.family.empty' | t }}</p>
                </div>
              } @else {
                <div class="table-container">
                  <table class="fe-table">
                    <thead>
                      <tr>
                        <th>{{ 'clinic.patient.family.col.person' | t }}</th>
                        <th>{{ 'clinic.patient.family.col.role' | t }}</th>
                        <th>{{ 'clinic.patient.family.col.contact' | t }}</th>
                        <th>ملاحظات</th>
                        <th>{{ 'clinic.patient.family.col.actions' | t }}</th>
                      </tr>
                    </thead>
                    <tbody>
                      @for (rel of relationships(); track rel.id) {
                        <tr>
                          <td>
                            <strong>{{ rel.relatedPersonName || rel.relatedPatientName || '—' }}</strong>
                            <div class="fe-sub rel-type-tag">
                              {{ formatRelType(rel.relationshipType) }}
                              @if (rel.customRelationship) {
                                ({{ rel.customRelationship }})
                              }
                            </div>
                          </td>
                          <td>
                            <div class="badges-row">
                              @if (rel.guarantor) {
                                <fe-status-chip tone="ok">{{ 'clinic.patient.family.guarantor' | t }}</fe-status-chip>
                              }
                              @if (rel.emergencyContact) {
                                <fe-status-chip tone="warn">{{ 'clinic.patient.family.emergency' | t }}</fe-status-chip>
                              }
                              @if (!rel.guarantor && !rel.emergencyContact) {
                                <span class="fe-sub">صلة قرابة</span>
                              }
                            </div>
                          </td>
                          <td dir="ltr">{{ rel.relatedPersonPhone || '—' }}</td>
                          <td>{{ rel.notes || '—' }}</td>
                          <td>
                            <button
                              type="button"
                              class="fe-btn fe-btn--xs fe-btn--danger-ghost"
                              (click)="deleteRelationship(rel.id)"
                            >
                              {{ 'clinic.patient.family.unlink' | t }}
                            </button>
                          </td>
                        </tr>
                      }
                    </tbody>
                  </table>
                </div>
              }
            </section>
          }

          <!-- TAB 3: Consents & Disclosures -->
          @if (activeTab() === 'consents') {
            <!-- Consents -->
            <section class="fe-card">
              <div class="card-header">
                <div>
                  <h3>{{ 'clinic.patient.consents.title' | t }}</h3>
                  <p class="fe-sub">{{ 'clinic.patient.consents.hint' | t }}</p>
                </div>
                <button
                  type="button"
                  class="fe-btn fe-btn--primary"
                  (click)="isAddConsentOpen.set(true)"
                >
                  {{ 'clinic.patient.consents.grant' | t }}
                </button>
              </div>

              @if (consents().length === 0) {
                <div class="empty-banner">
                  <p>{{ 'clinic.patient.consents.empty' | t }}</p>
                </div>
              } @else {
                <div class="table-container">
                  <table class="fe-table">
                    <thead>
                      <tr>
                        <th>{{ 'clinic.patient.consents.col.type' | t }}</th>
                        <th>{{ 'clinic.patient.consents.col.status' | t }}</th>
                        <th>{{ 'clinic.patient.consents.col.signer' | t }}</th>
                        <th>{{ 'clinic.patient.consents.col.date' | t }}</th>
                        <th>{{ 'clinic.patient.family.col.actions' | t }}</th>
                      </tr>
                    </thead>
                    <tbody>
                      @for (c of consents(); track c.id) {
                        <tr>
                          <td>
                            <strong>{{ formatConsentType(c.consentType) }}</strong>
                            @if (c.documentReference) {
                              <div class="fe-mono fe-sub" dir="ltr">مرجع: {{ c.documentReference }}</div>
                            }
                          </td>
                          <td>
                            @if (c.status === 'GRANTED') {
                              <fe-status-chip tone="ok">{{ 'clinic.patient.consents.status.granted' | t }}</fe-status-chip>
                            } @else if (c.status === 'REVOKED') {
                              <fe-status-chip tone="bad">{{ 'clinic.patient.consents.status.revoked' | t }}</fe-status-chip>
                              @if (c.revocationReason) {
                                <div class="fe-sub">السبب: {{ c.revocationReason }}</div>
                              }
                            } @else {
                              <fe-status-chip tone="neutral">{{ c.status }}</fe-status-chip>
                            }
                          </td>
                          <td>
                            <div>{{ c.signedBy }}</div>
                            <div class="fe-sub">
                              {{ c.signerRelationship || 'المريض نفسه' }}
                              @if (c.witnessName) {
                                · شاهد: {{ c.witnessName }}
                              }
                            </div>
                          </td>
                          <td>
                            <div>منحت: {{ format.date(c.grantedAt) }}</div>
                            @if (c.expiresAt) {
                              <div class="fe-sub">تنتهي: {{ format.date(c.expiresAt) }}</div>
                            }
                          </td>
                          <td>
                            @if (c.status === 'GRANTED') {
                              <button
                                type="button"
                                class="fe-btn fe-btn--xs fe-btn--danger-ghost"
                                (click)="openRevokeModal(c.id)"
                              >
                                {{ 'clinic.patient.consents.revoke' | t }}
                              </button>
                            } @else {
                              <span class="fe-sub">—</span>
                            }
                          </td>
                        </tr>
                      }
                    </tbody>
                  </table>
                </div>
              }
            </section>

            <!-- Disclosures Log -->
            <section class="fe-card">
              <div class="card-header">
                <div>
                  <h3>{{ 'clinic.patient.disclosures.title' | t }}</h3>
                  <p class="fe-sub">سجل قانوني يوثق إفصاحات السجل الطبي الخارجي وفق المعايير الطبية المعتمدة.</p>
                </div>
                <button
                  type="button"
                  class="fe-btn fe-btn--ghost"
                  (click)="isAddDisclosureOpen.set(true)"
                >
                  {{ 'clinic.patient.disclosures.log' | t }}
                </button>
              </div>

              @if (disclosures().length === 0) {
                <div class="empty-banner">
                  <p>{{ 'clinic.patient.disclosures.empty' | t }}</p>
                </div>
              } @else {
                <div class="table-container">
                  <table class="fe-table">
                    <thead>
                      <tr>
                        <th>الجهة المستلمة</th>
                        <th>الغرض من الإفصاح</th>
                        <th>السجلات المفصح عنها</th>
                        <th>التاريخ والمسؤول</th>
                      </tr>
                    </thead>
                    <tbody>
                      @for (d of disclosures(); track d.id) {
                        <tr>
                          <td><strong>{{ d.disclosedTo }}</strong></td>
                          <td>{{ d.purpose }}</td>
                          <td><code>{{ d.recordsDisclosed }}</code></td>
                          <td>
                            <div>{{ format.date(d.disclosedAt) }}</div>
                            <div class="fe-sub">{{ d.disclosedByName || 'موظف مصرح' }}</div>
                          </td>
                        </tr>
                      }
                    </tbody>
                  </table>
                </div>
              }
            </section>
          }

          <!-- TAB 5: Clinical Media & Radiographs (Slice S7) -->
          @if (activeTab() === 'media') {
            <section class="fe-card" data-testid="clinic-media-section">
              <div class="card-header">
                <div>
                  <h3>{{ 'clinic.media.title' | t }}</h3>
                  <p class="fe-sub">{{ 'clinic.media.hint' | t }}</p>
                </div>
                <div class="header-actions">
                  <button
                    type="button"
                    class="fe-btn fe-btn--ghost"
                    data-testid="media-patient-audit-btn"
                    (click)="openPatientAuditTrail()"
                  >
                    {{ 'clinic.media.auditTrail' | t }}
                  </button>
                  <button
                    type="button"
                    class="fe-btn fe-btn--primary"
                    data-testid="media-upload-btn"
                    (click)="openUploadModal()"
                  >
                    {{ 'clinic.media.upload' | t }}
                  </button>
                </div>
              </div>

              <!-- Filter Toolbar -->
              <div class="media-filters" data-testid="media-filters">
                <div class="filter-item">
                  <label class="fe-eyebrow">{{ 'clinic.invoices.status' | t }} / {{ 'clinic.media.category.all' | t }}</label>
                  <select
                    class="fe-input"
                    data-testid="media-category-select"
                    [ngModel]="mediaCategoryFilter()"
                    (ngModelChange)="mediaCategoryFilter.set($event)"
                  >
                    <option value="ALL">{{ 'clinic.media.category.all' | t }}</option>
                    <option value="RADIOGRAPH_INTRAORAL">{{ 'clinic.media.category.radiographIntraoral' | t }}</option>
                    <option value="RADIOGRAPH_PANORAMIC">{{ 'clinic.media.category.radiographPanoramic' | t }}</option>
                    <option value="RADIOGRAPH_CEPHALOMETRIC">{{ 'clinic.media.category.radiographCephalometric' | t }}</option>
                    <option value="PHOTO_INTRAORAL">{{ 'clinic.media.category.photoIntraoral' | t }}</option>
                    <option value="PHOTO_EXTRAORAL">{{ 'clinic.media.category.photoExtraoral' | t }}</option>
                    <option value="DOCUMENT_PDF">{{ 'clinic.media.category.documentPdf' | t }}</option>
                    <option value="CONSENT_FORM">{{ 'clinic.media.category.consentForm' | t }}</option>
                    <option value="LAB_PRESCRIPTION">{{ 'clinic.media.category.labPrescription' | t }}</option>
                    <option value="OTHER">{{ 'clinic.media.category.other' | t }}</option>
                  </select>
                </div>

                <div class="filter-item">
                  <label class="fe-eyebrow">{{ 'clinic.media.tooth' | t }} (FDI)</label>
                  <select
                    class="fe-input"
                    data-testid="media-tooth-select"
                    [ngModel]="mediaToothFilter()"
                    (ngModelChange)="onToothFilterChange($event)"
                  >
                    <option value="">{{ 'clinic.media.toothAll' | t }}</option>
                    @for (tooth of fdiTeeth; track tooth) {
                      <option [value]="tooth">{{ tooth }}</option>
                    }
                  </select>
                </div>

                <div class="filter-item checkbox-filter">
                  <label class="checkbox-label" data-testid="media-legal-hold-filter-label">
                    <input
                      type="checkbox"
                      data-testid="media-legal-hold-filter"
                      [checked]="mediaLegalHoldFilter()"
                      (change)="mediaLegalHoldFilter.set(!mediaLegalHoldFilter())"
                    />
                    <span>{{ 'clinic.media.legalHoldActive' | t }}</span>
                  </label>
                </div>
              </div>

              <!-- Media Grid -->
              @if (filteredMediaItems().length === 0) {
                <div class="empty-banner" data-testid="media-empty">
                  <p>{{ 'clinic.media.empty' | t }}</p>
                </div>
              } @else {
                <div class="media-grid" data-testid="media-grid">
                  @for (m of filteredMediaItems(); track m.id) {
                    <article class="media-card" [attr.data-testid]="'media-card-' + m.id">
                      <div class="media-card-top">
                        <span class="media-category-badge">{{ categoryLabel(m.category) }}</span>
                        @if (m.toothFdi) {
                          <span class="tooth-fdi-badge" [attr.data-testid]="'media-tooth-' + m.id">FDI {{ m.toothFdi }}</span>
                        }
                        @if (m.legalHold) {
                          <fe-status-chip tone="bad" [attr.data-testid]="'media-hold-badge-' + m.id">
                            {{ 'clinic.media.legalHold' | t }}
                          </fe-status-chip>
                        }
                        <span class="enc-badge" title="AES-256-GCM Encrypted">AES-256</span>
                      </div>

                      <div class="media-card-body">
                        <h4 class="media-file-name" [title]="m.fileName">{{ m.fileName }}</h4>
                        <div class="media-meta-row">
                          <span class="fe-mono fe-sub">{{ formatBytes(m.byteSize) }}</span>
                          <span class="fe-sub">{{ format.date(m.acquisitionDate) }}</span>
                        </div>
                        @if (m.radiographerName) {
                          <p class="media-radiographer fe-sub">
                            {{ 'clinic.media.radiographer' | t }}: {{ m.radiographerName }}
                          </p>
                        }
                        @if (m.notes) {
                          <p class="media-notes fe-sub">{{ m.notes }}</p>
                        }
                      </div>

                      <div class="media-card-actions">
                        @if (isImage(m.contentType)) {
                          <button
                            type="button"
                            class="fe-btn fe-btn--ghost fe-btn--xs"
                            [attr.data-testid]="'media-preview-btn-' + m.id"
                            (click)="previewMediaItem(m)"
                          >
                            {{ 'clinic.media.preview' | t }}
                          </button>
                        }
                        <button
                          type="button"
                          class="fe-btn fe-btn--ghost fe-btn--xs"
                          [attr.data-testid]="'media-download-btn-' + m.id"
                          (click)="downloadMediaItem(m)"
                        >
                          {{ 'clinic.media.download' | t }}
                        </button>
                        <button
                          type="button"
                          class="fe-btn fe-btn--ghost fe-btn--xs"
                          [attr.data-testid]="'media-hold-btn-' + m.id"
                          (click)="openLegalHoldModal(m)"
                        >
                          {{ m.legalHold ? ('clinic.media.legalHoldRelease' | t) : ('clinic.media.legalHoldApply' | t) }}
                        </button>
                        <button
                          type="button"
                          class="fe-btn fe-btn--ghost fe-btn--xs"
                          [attr.data-testid]="'media-audit-btn-' + m.id"
                          (click)="openItemAuditTrail(m)"
                        >
                          {{ 'clinic.media.auditTrail' | t }}
                        </button>
                        <button
                          type="button"
                          class="fe-btn fe-btn--danger-ghost fe-btn--xs"
                          [attr.data-testid]="'media-delete-btn-' + m.id"
                          [disabled]="m.legalHold || actionPending()"
                          (click)="deleteMediaItem(m)"
                        >
                          {{ 'clinic.media.delete' | t }}
                        </button>
                      </div>
                    </article>
                  }
                </div>
              }
            </section>
          }

          <!-- TAB 4: Record Actions (Archive, Merge) -->
          @if (activeTab() === 'actions') {
            <section class="fe-card">
              <div class="card-header">
                <div>
                  <h3>{{ 'clinic.patient.actions.title' | t }}</h3>
                  <p class="fe-sub">إجراءات حوكمة دورة حياة الملف الطبي والأرشفة والدمج في حالات التكرار.</p>
                </div>
              </div>

              <div class="actions-grid">
                <!-- Archive / Restore Box -->
                <div class="action-box">
                  <h4>أرشفة واستعادة السجل الطبي</h4>
                  @if (row.deleted === true) {
                    <p class="fe-sub bad-text">
                      هذا السجل مؤرشف حالياً (محذوف منطقياً). يمكنك استعادته ليصبح نشطاً في العيادة.
                    </p>
                    <button
                      type="button"
                      class="fe-btn fe-btn--primary"
                      (click)="restorePatient()"
                      [disabled]="actionPending()"
                    >
                      {{ 'clinic.patient.actions.restore' | t }}
                    </button>
                  } @else {
                    <p class="fe-sub">
                      الأرشفة تحجب المريض من القوائم النشطة مع الإبقاء الكامل على السجل التاريخي وفق متطلبات الاحتفاظ القانوني (10 سنوات).
                    </p>
                    <div class="form-row">
                      <input
                        type="text"
                        class="fe-input"
                        placeholder="سبب الأرشفة (مطلوب)"
                        [(ngModel)]="archiveReason"
                      />
                      <button
                        type="button"
                        class="fe-btn fe-btn--danger"
                        [disabled]="!archiveReason.trim() || actionPending()"
                        (click)="archivePatient()"
                      >
                        {{ 'clinic.patient.actions.archive' | t }}
                      </button>
                    </div>
                  }
                </div>

                <!-- Merge Box -->
                <div class="action-box">
                  <h4>{{ 'clinic.patient.actions.merge' | t }}</h4>
                  <p class="fe-sub">
                    دمج هذا السجل (كمصدر) في سجل مريض آخر مستهدف لنقل كافة المواعيد والزيارات وخطط العلاج والفواتير إليه تلقائياً وبأمان.
                  </p>

                  @if (mergeSuccessMessage()) {
                    <div class="success-banner">
                      {{ mergeSuccessMessage() }}
                    </div>
                  }

                  <div class="form-vertical">
                    <div class="form-field">
                      <label class="fe-label">معرف المريض المستهدف (Target Patient UUID)</label>
                      <input
                        type="text"
                        class="fe-input fe-mono"
                        dir="ltr"
                        placeholder="00000000-0000-0000-0000-000000000000"
                        [(ngModel)]="mergeTargetId"
                      />
                    </div>
                    <div class="form-field">
                      <label class="fe-label">سبب الدمج</label>
                      <input
                        type="text"
                        class="fe-input"
                        placeholder="مثال: تسجيل مكرر تم تأكيد هوية المريض"
                        [(ngModel)]="mergeReason"
                      />
                    </div>
                    <button
                      type="button"
                      class="fe-btn fe-btn--danger"
                      [disabled]="!mergeTargetId.trim() || !mergeReason.trim() || actionPending()"
                      (click)="mergePatient()"
                    >
                      تنفيذ الدمج الآن
                    </button>
                  </div>
                </div>
              </div>
            </section>
          }
        }
      }

      <!-- MODAL: Add Relationship -->
      @if (isAddRelOpen()) {
        <div class="fe-modal-overlay" (click)="isAddRelOpen.set(false)">
          <div class="fe-modal-card" (click)="$event.stopPropagation()">
            <div class="modal-header">
              <h3>{{ 'clinic.patient.family.add' | t }}</h3>
              <button type="button" class="close-btn" (click)="isAddRelOpen.set(false)">x</button>
            </div>
            <div class="modal-body">
              <div class="form-field">
                <label class="fe-label">اسم الشخص / القريب (مطلوب)</label>
                <input type="text" class="fe-input" [(ngModel)]="relPersonName" placeholder="الاسم الكامل" />
              </div>
              <div class="form-field">
                <label class="fe-label">رقم الهاتف</label>
                <input type="tel" dir="ltr" class="fe-input" [(ngModel)]="relPersonPhone" placeholder="+9627..." />
              </div>
              <div class="form-field">
                <label class="fe-label">صلة القرابة</label>
                <select class="fe-input" [(ngModel)]="relType">
                  <option value="PARENT">{{ 'clinic.patient.family.rel.parent' | t }}</option>
                  <option value="SPOUSE">{{ 'clinic.patient.family.rel.spouse' | t }}</option>
                  <option value="CHILD">{{ 'clinic.patient.family.rel.child' | t }}</option>
                  <option value="SIBLING">{{ 'clinic.patient.family.rel.sibling' | t }}</option>
                  <option value="GUARDIAN">{{ 'clinic.patient.family.rel.guardian' | t }}</option>
                  <option value="OTHER">{{ 'clinic.patient.family.rel.other' | t }}</option>
                </select>
              </div>
              @if (relType === 'OTHER') {
                <div class="form-field">
                  <label class="fe-label">تحديد الصلة المخصصة</label>
                  <input type="text" class="fe-input" [(ngModel)]="relCustom" placeholder="مثال: خال، صديق العائلة..." />
                </div>
              }
              <div class="checkbox-group">
                <label class="checkbox-label">
                  <input type="checkbox" [(ngModel)]="relIsGuarantor" />
                  <span>{{ 'clinic.patient.family.guarantor' | t }} (مسؤول عن سداد المطالبات المالية)</span>
                </label>
                <label class="checkbox-label">
                  <input type="checkbox" [(ngModel)]="relIsEmergency" />
                  <span>{{ 'clinic.patient.family.emergency' | t }}</span>
                </label>
              </div>
              <div class="form-field">
                <label class="fe-label">ملاحظات إضافية</label>
                <textarea class="fe-input" rows="2" [(ngModel)]="relNotes" placeholder="أي تفاصيل خاصة"></textarea>
              </div>
            </div>
            <div class="modal-footer">
              <button type="button" class="fe-btn fe-btn--ghost" (click)="isAddRelOpen.set(false)">إلغاء</button>
              <button
                type="button"
                class="fe-btn fe-btn--primary"
                [disabled]="!relPersonName.trim() || actionPending()"
                (click)="saveRelationship()"
              >
                حفظ الصلة
              </button>
            </div>
          </div>
        </div>
      }

      <!-- MODAL: Add Consent -->
      @if (isAddConsentOpen()) {
        <div class="fe-modal-overlay" (click)="isAddConsentOpen.set(false)">
          <div class="fe-modal-card" (click)="$event.stopPropagation()">
            <div class="modal-header">
              <h3>{{ 'clinic.patient.consents.grant' | t }}</h3>
              <button type="button" class="close-btn" (click)="isAddConsentOpen.set(false)">x</button>
            </div>
            <div class="modal-body">
              <div class="form-field">
                <label class="fe-label">نوع الإقرار والموافقة (مطلوب)</label>
                <select class="fe-input" [(ngModel)]="consentType">
                  <option value="GENERAL_TREATMENT">موافقة علاج عامة واستشارة</option>
                  <option value="SURGERY">موافقة عمل جراحي وخلع</option>
                  <option value="ANESTHESIA">موافقة تخدير موضعي</option>
                  <option value="ORTHODONTICS">إقرار خطة تقويم أسنان</option>
                  <option value="IMPLANT">موافقة زراعة أسنان</option>
                  <option value="PHOTOGRAPHY">إقرار تصوير فوتوغرافي سريري</option>
                  <option value="DATA_DISCLOSURE">موافقة كشف بيانات طبية</option>
                </select>
              </div>
              <div class="form-field">
                <label class="fe-label">اسم الموقّع (المريض أو الوصي)</label>
                <input type="text" class="fe-input" [(ngModel)]="consentSignedBy" placeholder="الاسم الكامل" />
              </div>
              <div class="form-field">
                <label class="fe-label">صفة الموقّع</label>
                <input type="text" class="fe-input" [(ngModel)]="consentSignerRel" placeholder="المريض نفسه / الأب / الوصي" />
              </div>
              <div class="form-field">
                <label class="fe-label">اسم الشاهد (الكادر الطبي / التمريض)</label>
                <input type="text" class="fe-input" [(ngModel)]="consentWitness" placeholder="اسم الشاهد" />
              </div>
              <div class="form-field">
                <label class="fe-label">رقم المرجع / الملف الورقي الممسوح</label>
                <input type="text" class="fe-input fe-mono" [(ngModel)]="consentDocRef" placeholder="REF-2026-..." />
              </div>
              <div class="form-field">
                <label class="fe-label">ملاحظات إضافية</label>
                <textarea class="fe-input" rows="2" [(ngModel)]="consentNotes"></textarea>
              </div>
            </div>
            <div class="modal-footer">
              <button type="button" class="fe-btn fe-btn--ghost" (click)="isAddConsentOpen.set(false)">إلغاء</button>
              <button
                type="button"
                class="fe-btn fe-btn--primary"
                [disabled]="!consentSignedBy.trim() || actionPending()"
                (click)="saveConsent()"
              >
                توثيق الموافقة
              </button>
            </div>
          </div>
        </div>
      }

      <!-- MODAL: Revoke Consent -->
      @if (revokingConsentId()) {
        <div class="fe-modal-overlay" (click)="revokingConsentId.set(null)">
          <div class="fe-modal-card" (click)="$event.stopPropagation()">
            <div class="modal-header">
              <h3>{{ 'clinic.patient.consents.revoke' | t }}</h3>
              <button type="button" class="close-btn" (click)="revokingConsentId.set(null)">x</button>
            </div>
            <div class="modal-body">
              <p class="fe-sub bad-text">
                سحب الموافقة يوثق فوراً ولا يمكن التراجع عنه. يجب توثيق السبب بصورة واضحة.
              </p>
              <div class="form-field">
                <label class="fe-label">سبب سحب الموافقة (مطلوب)</label>
                <textarea
                  class="fe-input"
                  rows="3"
                  [(ngModel)]="revokeReason"
                  placeholder="طلب المريض إلغاء الإجراء..."
                ></textarea>
              </div>
            </div>
            <div class="modal-footer">
              <button type="button" class="fe-btn fe-btn--ghost" (click)="revokingConsentId.set(null)">إلغاء</button>
              <button
                type="button"
                class="fe-btn fe-btn--danger"
                [disabled]="!revokeReason.trim() || actionPending()"
                (click)="confirmRevokeConsent()"
              >
                تأكيد سحب الموافقة
              </button>
            </div>
          </div>
        </div>
      }

      <!-- MODAL: Log Disclosure -->
      @if (isAddDisclosureOpen()) {
        <div class="fe-modal-overlay" (click)="isAddDisclosureOpen.set(false)">
          <div class="fe-modal-card" (click)="$event.stopPropagation()">
            <div class="modal-header">
              <h3>{{ 'clinic.patient.disclosures.log' | t }}</h3>
              <button type="button" class="close-btn" (click)="isAddDisclosureOpen.set(false)">x</button>
            </div>
            <div class="modal-body">
              <div class="form-field">
                <label class="fe-label">الجهة أو الشخص المستلم (مطلوب)</label>
                <input type="text" class="fe-input" [(ngModel)]="discTo" placeholder="مثال: شركة التأمين الإسلامية، د. أحمد..." />
              </div>
              <div class="form-field">
                <label class="fe-label">الغرض من الإفصاح (مطلوب)</label>
                <input type="text" class="fe-input" [(ngModel)]="discPurpose" placeholder="مثال: مطالبة تأمين، إحالة جراحة فكية..." />
              </div>
              <div class="form-field">
                <label class="fe-label">السجلات والبيانات المفصح عنها (مطلوب)</label>
                <textarea class="fe-input" rows="2" [(ngModel)]="discRecords" placeholder="تقرير طبي + صورة أشعة بانورامية..."></textarea>
              </div>
            </div>
            <div class="modal-footer">
              <button type="button" class="fe-btn fe-btn--ghost" (click)="isAddDisclosureOpen.set(false)">إلغاء</button>
              <button
                type="button"
                class="fe-btn fe-btn--primary"
                [disabled]="!discTo.trim() || !discPurpose.trim() || !discRecords.trim() || actionPending()"
                (click)="saveDisclosure()"
              >
                تسجيل الإفصاح
              </button>
            </div>
          </div>
        </div>
      }

      <!-- MODAL: Radiograph & Photo Viewer -->
      @if (previewMedia(); as prev) {
        <div class="fe-modal-overlay" (click)="closePreview()">
          <div class="fe-modal-card viewer-modal-card" (click)="$event.stopPropagation()" data-testid="media-viewer-modal">
            <div class="modal-header">
              <h3>{{ 'clinic.media.preview' | t }}: {{ prev.row.fileName }}</h3>
              <button type="button" class="close-btn" data-testid="viewer-close-btn" (click)="closePreview()">x</button>
            </div>
            <div class="viewer-toolbar">
              <button type="button" class="fe-btn fe-btn--ghost fe-btn--xs" data-testid="viewer-zoom-in" (click)="zoomIn()">
                {{ 'clinic.media.viewer.zoomIn' | t }}
              </button>
              <button type="button" class="fe-btn fe-btn--ghost fe-btn--xs" data-testid="viewer-zoom-out" (click)="zoomOut()">
                {{ 'clinic.media.viewer.zoomOut' | t }}
              </button>
              <button type="button" class="fe-btn fe-btn--ghost fe-btn--xs" data-testid="viewer-invert" (click)="toggleInvert()">
                {{ 'clinic.media.viewer.invert' | t }}
              </button>
              <button type="button" class="fe-btn fe-btn--ghost fe-btn--xs" data-testid="viewer-reset" (click)="resetPreview()">
                {{ 'clinic.media.viewer.reset' | t }}
              </button>
            </div>
            <div class="viewer-viewport" data-testid="viewer-viewport">
              <img
                [src]="prev.url"
                [alt]="prev.row.fileName"
                class="viewer-img"
                data-testid="viewer-img"
                [style.transform]="'scale(' + previewZoom() + ')'"
                [style.filter]="previewInverted() ? 'invert(1)' : 'none'"
              />
            </div>
            <div class="modal-footer">
              <button type="button" class="fe-btn fe-btn--ghost" (click)="closePreview()">
                {{ 'clinic.media.viewer.close' | t }}
              </button>
            </div>
          </div>
        </div>
      }

      <!-- MODAL: Upload Media -->
      @if (isUploadMediaOpen()) {
        <div class="fe-modal-overlay" (click)="isUploadMediaOpen.set(false)">
          <div class="fe-modal-card" (click)="$event.stopPropagation()" data-testid="media-upload-modal">
            <div class="modal-header">
              <h3>{{ 'clinic.media.uploadModal.title' | t }}</h3>
              <button type="button" class="close-btn" data-testid="upload-modal-close" (click)="isUploadMediaOpen.set(false)">x</button>
            </div>
            <div class="modal-body">
              <div class="form-field">
                <label class="fe-label">{{ 'clinic.media.uploadModal.dropzone' | t }} (مطلوب)</label>
                <input
                  type="file"
                  class="fe-input"
                  data-testid="upload-file-input"
                  (change)="onFileSelected($event)"
                />
              </div>

              <div class="form-field">
                <label class="fe-label">{{ 'clinic.invoices.status' | t }} / التصنيف (مطلوب)</label>
                <select class="fe-input" data-testid="upload-category-input" [(ngModel)]="uploadCategory">
                  <option value="RADIOGRAPH_INTRAORAL">{{ 'clinic.media.category.radiographIntraoral' | t }}</option>
                  <option value="RADIOGRAPH_PANORAMIC">{{ 'clinic.media.category.radiographPanoramic' | t }}</option>
                  <option value="RADIOGRAPH_CEPHALOMETRIC">{{ 'clinic.media.category.radiographCephalometric' | t }}</option>
                  <option value="PHOTO_INTRAORAL">{{ 'clinic.media.category.photoIntraoral' | t }}</option>
                  <option value="PHOTO_EXTRAORAL">{{ 'clinic.media.category.photoExtraoral' | t }}</option>
                  <option value="DOCUMENT_PDF">{{ 'clinic.media.category.documentPdf' | t }}</option>
                  <option value="CONSENT_FORM">{{ 'clinic.media.category.consentForm' | t }}</option>
                  <option value="LAB_PRESCRIPTION">{{ 'clinic.media.category.labPrescription' | t }}</option>
                  <option value="OTHER">{{ 'clinic.media.category.other' | t }}</option>
                </select>
              </div>

              <div class="form-field">
                <label class="fe-label">{{ 'clinic.media.tooth' | t }} (FDI 11-48, 51-85)</label>
                <input
                  type="number"
                  class="fe-input fe-mono"
                  data-testid="upload-tooth-input"
                  dir="ltr"
                  [(ngModel)]="uploadToothFdi"
                  placeholder="مثال: 16"
                />
              </div>

              <div class="form-field">
                <label class="fe-label">{{ 'clinic.media.acquisitionDate' | t }}</label>
                <input
                  type="date"
                  class="fe-input"
                  data-testid="upload-date-input"
                  [(ngModel)]="uploadAcquisitionDate"
                />
              </div>

              <div class="form-field">
                <label class="fe-label">{{ 'clinic.media.radiographer' | t }}</label>
                <input
                  type="text"
                  class="fe-input"
                  data-testid="upload-radiographer-input"
                  [(ngModel)]="uploadRadiographer"
                  placeholder="اسم الأخصائي أو الفني"
                />
              </div>

              <div class="form-field">
                <label class="fe-label">{{ 'clinic.media.uploadModal.notes' | t }}</label>
                <textarea
                  class="fe-input"
                  rows="2"
                  data-testid="upload-notes-input"
                  [(ngModel)]="uploadNotes"
                  placeholder="ملاحظات سريرية حول الصورة أو التقرير"
                ></textarea>
              </div>
            </div>
            <div class="modal-footer">
              <button type="button" class="fe-btn fe-btn--ghost" (click)="isUploadMediaOpen.set(false)">إلغاء</button>
              <button
                type="button"
                class="fe-btn fe-btn--primary"
                data-testid="upload-submit-btn"
                [disabled]="!uploadFile || actionPending()"
                (click)="submitUpload()"
              >
                {{ 'clinic.media.uploadModal.submit' | t }}
              </button>
            </div>
          </div>
        </div>
      }

      <!-- MODAL: Toggle Legal Hold -->
      @if (legalHoldTarget(); as target) {
        <div class="fe-modal-overlay" (click)="legalHoldTarget.set(null)">
          <div class="fe-modal-card" (click)="$event.stopPropagation()" data-testid="legal-hold-modal">
            <div class="modal-header">
              <h3>
                {{ target.legalHold ? ('clinic.media.legalHoldRelease' | t) : ('clinic.media.legalHoldApply' | t) }}
              </h3>
              <button type="button" class="close-btn" data-testid="legal-hold-close" (click)="legalHoldTarget.set(null)">x</button>
            </div>
            <div class="modal-body">
              <p class="fe-sub">
                {{ target.legalHold ? 'رفع الحجز القانوني يسمح بحذف الملف لاحقاً وفق سياسة الاستبقاء.' : 'فرض الحجز القانوني يمنع حذف الملف نهائياً حتى يتم رفع الحجز بصورة رسمية.' }}
              </p>
              <div class="form-field">
                <label class="fe-label">{{ 'clinic.media.legalHoldReason' | t }} (مطلوب)</label>
                <textarea
                  class="fe-input"
                  rows="3"
                  data-testid="legal-hold-reason-input"
                  [(ngModel)]="legalHoldReason"
                  placeholder="وثق السبب القانوني أو القضائي..."
                ></textarea>
              </div>
            </div>
            <div class="modal-footer">
              <button type="button" class="fe-btn fe-btn--ghost" (click)="legalHoldTarget.set(null)">إلغاء</button>
              <button
                type="button"
                class="fe-btn"
                [class.fe-btn--danger]="!target.legalHold"
                [class.fe-btn--primary]="target.legalHold"
                data-testid="legal-hold-submit-btn"
                [disabled]="!legalHoldReason.trim() || actionPending()"
                (click)="submitLegalHold()"
              >
                {{ target.legalHold ? ('clinic.media.legalHoldRelease' | t) : ('clinic.media.legalHoldApply' | t) }}
              </button>
            </div>
          </div>
        </div>
      }

      <!-- MODAL: Access Audit Trail -->
      @if (isAuditTrailOpen()) {
        <div class="fe-modal-overlay" (click)="isAuditTrailOpen.set(false)">
          <div class="fe-modal-card audit-modal-card" (click)="$event.stopPropagation()" data-testid="media-audit-modal">
            <div class="modal-header">
              <h3>{{ 'clinic.media.auditTrail' | t }}: {{ auditTrailTitle() }}</h3>
              <button type="button" class="close-btn" data-testid="audit-close-btn" (click)="isAuditTrailOpen.set(false)">x</button>
            </div>
            <div class="modal-body audit-modal-body">
              @if (auditItems().length === 0) {
                <p class="fe-sub">{{ 'clinic.patient.disclosures.empty' | t }}</p>
              } @else {
                <div class="table-container">
                  <table class="fe-table" data-testid="media-audit-table">
                    <thead>
                      <tr>
                        <th>{{ 'clinic.media.auditAction' | t }}</th>
                        <th>{{ 'clinic.media.auditUser' | t }}</th>
                        <th>{{ 'clinic.media.auditDate' | t }}</th>
                        <th>{{ 'clinic.media.auditIp' | t }}</th>
                        <th>{{ 'clinic.invoices.cancelModal.reason' | t }}</th>
                      </tr>
                    </thead>
                    <tbody>
                      @for (a of auditItems(); track a.id) {
                        <tr>
                          <td><span class="audit-action-tag">{{ a.action }}</span></td>
                          <td><strong>{{ a.accessedByUserName || a.accessedByUserId }}</strong></td>
                          <td class="fe-mono">{{ format.date(a.accessedAt) }}</td>
                          <td class="fe-mono fe-sub" dir="ltr">{{ a.accessIp || '—' }}</td>
                          <td class="fe-sub">{{ a.notes || '—' }}</td>
                        </tr>
                      }
                    </tbody>
                  </table>
                </div>
              }
            </div>
            <div class="modal-footer">
              <button type="button" class="fe-btn fe-btn--ghost" (click)="isAuditTrailOpen.set(false)">إلغاء</button>
            </div>
          </div>
        </div>
      }
    </div>
  `,
  styles: [
    `
      .patient-context-banner {
        margin-block-end: var(--fe-space-3);
        padding: var(--fe-space-3) var(--fe-space-4);
        display: flex;
        flex-direction: column;
        gap: var(--fe-space-3);
        background: var(--surf);
        border: 1px solid var(--line);
        border-radius: var(--fe-radius);
      }
      .banner-main {
        display: flex;
        align-items: center;
        gap: var(--fe-space-3);
      }
      .patient-avatar-circle {
        width: 52px;
        height: 52px;
        border-radius: 50%;
        background: var(--pri, var(--accent-teal-strong));
        color: var(--on-accent);
        display: grid;
        place-items: center;
        font-size: 1.25rem;
        font-weight: 700;
        flex-shrink: 0;
      }
      .patient-meta {
        display: flex;
        flex-direction: column;
        gap: 4px;
        flex-grow: 1;
      }
      .patient-title-row {
        display: flex;
        align-items: center;
        gap: var(--fe-space-2);
        flex-wrap: wrap;
      }
      .patient-display-name {
        margin: 0;
        font-size: 1.35rem;
        font-weight: 700;
        color: var(--ink);
      }
      .patient-file-no {
        background: var(--line-soft);
        padding: 2px 8px;
        border-radius: 4px;
        font-size: 0.85rem;
        color: var(--mute);
      }
      .patient-sub-facts {
        display: flex;
        align-items: center;
        gap: var(--fe-space-2);
        flex-wrap: wrap;
        font-size: 0.85rem;
        color: var(--mute);
      }
      .fact-tag {
        background: var(--line-soft);
        padding: 2px 6px;
        border-radius: 4px;
      }
      .fact-tag-icon {
        color: var(--mute);
        flex: none;
      }
      .contact-link {
        color: var(--pri);
        text-decoration: none;
        display: inline-flex;
        align-items: center;
        gap: 4px;
      }
      .contact-link:hover {
        text-decoration: underline;
      }
      .clinical-alerts-strip {
        display: flex;
        align-items: center;
        gap: var(--fe-space-2);
        flex-wrap: wrap;
        padding: var(--fe-space-2);
        background: var(--accent-amber-wash);
        border: 1px solid var(--accent-amber-soft);
        border-radius: var(--fe-radius);
      }
      .alerts-label {
        display: inline-flex;
        align-items: center;
        gap: 4px;
        font-size: 0.82rem;
        font-weight: 700;
        color: var(--accent-amber-deep);
      }
      .alerts-label-icon {
        color: var(--accent-amber-deep);
        flex: none;
      }
      .alert-chip {
        padding: 2px 8px;
        border-radius: 999px;
        font-size: 0.78rem;
        font-weight: 600;
      }
      .alert-chip--danger {
        background: var(--accent-red-soft);
        color: var(--accent-red-ink);
      }
      .alert-chip--warning {
        background: var(--accent-amber-soft);
        color: var(--accent-amber-ink);
      }
      .alert-chip--info {
        background: var(--accent-sky-soft);
        color: var(--accent-sky-ink);
      }
      .patient-quick-actions {
        display: flex;
        align-items: center;
        gap: var(--fe-space-2);
        flex-wrap: wrap;
        border-top: 1px solid var(--line);
        padding-top: var(--fe-space-2);
      }
      .action-btn {
        background: var(--surf);
        border: 1px solid var(--line);
        color: var(--ink);
        padding: 4px 10px;
        border-radius: var(--fe-radius);
        font-size: 0.82rem;
        font-weight: 500;
        text-decoration: none;
        transition: background 0.15s ease;
        display: inline-flex;
        align-items: center;
        gap: 6px;
      }
      .action-btn-icon {
        color: var(--mute);
        flex: none;
      }
      .action-btn:hover {
        background: var(--line-soft);
        border-color: var(--pri);
        color: var(--pri);
      }
      .header-actions {
        display: flex;
        gap: var(--fe-space-2);
        align-items: center;
      }
      .patient-tabs {
        display: flex;
        gap: var(--fe-space-2);
        border-bottom: 1px solid var(--line);
        margin-block-end: var(--fe-space-4);
        overflow-x: auto;
      }
      .tab-btn {
        background: transparent;
        border: none;
        border-bottom: 2px solid transparent;
        color: var(--mute);
        font-weight: 500;
        padding-block: var(--fe-space-3);
        padding-inline: var(--fe-space-3);
        cursor: pointer;
        transition: all 0.15s ease-in-out;
        white-space: nowrap;
      }
      .tab-btn:hover {
        color: var(--ink);
      }
      .tab-btn--active {
        color: var(--pri);
        border-bottom-color: var(--pri);
        font-weight: 600;
      }
      .card-header {
        display: flex;
        justify-content: space-between;
        align-items: flex-start;
        margin-block-end: var(--fe-space-3);
      }
      .facts {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(14rem, 1fr));
        gap: var(--fe-space-4);
        margin-block: var(--fe-space-3);
      }
      .facts dt {
        margin-block-end: var(--fe-space-1);
      }
      .facts dd {
        margin: 0;
        color: var(--ink);
        overflow-wrap: anywhere;
      }
      .fact-strong {
        font-weight: 600;
        font-size: 1.1rem;
      }
      .unset {
        color: var(--mute);
        font-style: italic;
      }
      .cards-grid {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(18rem, 1fr));
        gap: var(--fe-space-3);
        margin-block-start: var(--fe-space-3);
      }
      .table-container {
        overflow-x: auto;
        margin-block-start: var(--fe-space-2);
      }
      .fe-table {
        width: 100%;
        border-collapse: collapse;
        text-align: start;
      }
      .fe-table th {
        background: var(--surf2);
        color: var(--mute);
        font-size: 0.85rem;
        font-weight: 600;
        padding: var(--fe-space-2) var(--fe-space-3);
        border-bottom: 1px solid var(--line);
      }
      .fe-table td {
        padding: var(--fe-space-3);
        border-bottom: 1px solid var(--line);
        vertical-align: middle;
      }
      .empty-banner {
        padding: var(--fe-space-6);
        text-align: center;
        color: var(--mute);
        background: var(--surf2);
        border-radius: var(--fe-radius);
      }
      .badges-row {
        display: flex;
        gap: var(--fe-space-1);
        flex-wrap: wrap;
      }
      .rel-type-tag {
        font-size: 0.8rem;
        margin-block-start: var(--fe-space-1);
      }
      .actions-grid {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(20rem, 1fr));
        gap: var(--fe-space-4);
      }
      .action-box {
        padding: var(--fe-space-4);
        background: var(--surf2);
        border: 1px solid var(--line);
        border-radius: var(--fe-radius);
      }
      .action-box h4 {
        margin: 0 0 var(--fe-space-2) 0;
      }
      .bad-text {
        color: var(--bad);
      }
      .form-row {
        display: flex;
        gap: var(--fe-space-2);
        margin-block-start: var(--fe-space-3);
      }
      .form-vertical {
        display: flex;
        flex-direction: column;
        gap: var(--fe-space-3);
        margin-block-start: var(--fe-space-3);
      }
      .form-field {
        display: flex;
        flex-direction: column;
        gap: var(--fe-space-1);
      }
      .fe-label {
        font-size: 0.85rem;
        font-weight: 500;
        color: var(--ink);
      }
      .fe-input {
        padding: var(--fe-space-2) var(--fe-space-3);
        border: 1px solid var(--line);
        border-radius: var(--fe-radius);
        background: var(--surf);
        color: var(--ink);
        font-family: inherit;
        font-size: 0.95rem;
      }
      .fe-input:focus {
        outline: 2px solid var(--pri);
        outline-offset: 1px;
        border-color: var(--pri);
      }
      .checkbox-group {
        display: flex;
        flex-direction: column;
        gap: var(--fe-space-2);
        margin-block: var(--fe-space-2);
      }
      .checkbox-label {
        display: flex;
        align-items: center;
        gap: var(--fe-space-2);
        font-size: 0.9rem;
        cursor: pointer;
      }
      .success-banner {
        padding: var(--fe-space-3);
        background: var(--ok-soft);
        color: var(--ok);
        border-radius: var(--fe-radius);
        margin-block-end: var(--fe-space-2);
        font-weight: 500;
      }
      .fe-modal-overlay {
        position: fixed;
        inset: 0;
        background: var(--ink);
        opacity: 0.96;
        display: grid;
        place-items: center;
        z-index: 1000;
        padding: var(--fe-space-4);
      }
      .fe-modal-card {
        background: var(--surf);
        border-radius: var(--fe-radius);
        border: 1px solid var(--line);
        width: 100%;
        max-width: 32rem;
        box-shadow: var(--shadow);
        display: flex;
        flex-direction: column;
      }
      .modal-header {
        display: flex;
        justify-content: space-between;
        align-items: center;
        padding: var(--fe-space-3) var(--fe-space-4);
        border-bottom: 1px solid var(--line);
      }
      .modal-header h3 {
        margin: 0;
      }
      .close-btn {
        background: transparent;
        border: none;
        color: var(--mute);
        font-size: 1.2rem;
        cursor: pointer;
      }
      .modal-body {
        padding: var(--fe-space-4);
        display: flex;
        flex-direction: column;
        gap: var(--fe-space-3);
        max-height: 70vh;
        overflow-y: auto;
      }
      .modal-footer {
        display: flex;
        justify-content: flex-end;
        gap: var(--fe-space-2);
        padding: var(--fe-space-3) var(--fe-space-4);
        border-top: 1px solid var(--line);
      }
      .fe-btn--danger {
        background: var(--bad);
        color: var(--pri-ink);
        border: none;
      }
      .fe-btn--danger-ghost {
        color: var(--bad);
        background: transparent;
        border: 1px solid var(--bad);
      }
      .fe-btn--danger-ghost:hover {
        background: var(--bad-soft);
      }
      .fe-btn--xs {
        padding: var(--fe-space-1) var(--fe-space-2);
        font-size: 0.8rem;
      }
      .media-filters {
        display: flex;
        gap: var(--fe-space-3);
        align-items: flex-end;
        flex-wrap: wrap;
        margin-block: var(--fe-space-3);
        padding: var(--fe-space-3);
        background: var(--surf2);
        border: 1px solid var(--line);
        border-radius: var(--fe-radius);
      }
      .filter-item {
        display: flex;
        flex-direction: column;
        gap: var(--fe-space-1);
        min-width: 12rem;
      }
      .checkbox-filter {
        min-width: auto;
        padding-block-end: var(--fe-space-2);
      }
      .media-grid {
        display: grid;
        grid-template-columns: repeat(auto-fill, minmax(18rem, 1fr));
        gap: var(--fe-space-4);
        margin-block-start: var(--fe-space-3);
      }
      .media-card {
        background: var(--surf2);
        border: 1px solid var(--line);
        border-radius: var(--fe-radius);
        padding: var(--fe-space-3);
        display: flex;
        flex-direction: column;
        gap: var(--fe-space-2);
        box-shadow: var(--shadow-sm);
        transition: border-color 0.15s ease-in-out;
      }
      .media-card:hover {
        border-color: var(--pri);
      }
      .media-card-top {
        display: flex;
        align-items: center;
        gap: var(--fe-space-1);
        flex-wrap: wrap;
      }
      .media-category-badge {
        font-size: 0.75rem;
        font-weight: 600;
        padding: var(--fe-space-1) var(--fe-space-2);
        background: var(--pri-soft);
        color: var(--pri);
        border-radius: var(--fe-radius-sm);
      }
      .tooth-fdi-badge {
        font-size: 0.75rem;
        font-weight: 600;
        padding: var(--fe-space-1) var(--fe-space-2);
        background: var(--surf);
        border: 1px solid var(--line);
        border-radius: var(--fe-radius-sm);
        font-family: monospace;
      }
      .enc-badge {
        font-size: 0.7rem;
        font-family: monospace;
        padding: var(--fe-space-1) var(--fe-space-2);
        background: var(--surf);
        border: 1px solid var(--line);
        border-radius: var(--fe-radius-sm);
        color: var(--mute);
        margin-inline-start: auto;
      }
      .media-card-body {
        display: flex;
        flex-direction: column;
        gap: var(--fe-space-1);
        flex: 1;
      }
      .media-file-name {
        margin: 0;
        font-size: 0.95rem;
        font-weight: 600;
        word-break: break-all;
      }
      .media-meta-row {
        display: flex;
        justify-content: space-between;
        font-size: 0.8rem;
      }
      .media-radiographer, .media-notes {
        margin: 0;
        font-size: 0.8rem;
      }
      .media-card-actions {
        display: flex;
        gap: var(--fe-space-1);
        flex-wrap: wrap;
        border-top: 1px solid var(--line);
        padding-block-start: var(--fe-space-2);
        margin-block-start: auto;
      }
      .viewer-modal-card {
        max-width: 50rem;
      }
      .viewer-toolbar {
        display: flex;
        gap: var(--fe-space-2);
        padding: var(--fe-space-2) var(--fe-space-4);
        background: var(--surf2);
        border-bottom: 1px solid var(--line);
      }
      .viewer-viewport {
        overflow: hidden;
        display: flex;
        align-items: center;
        justify-content: center;
        min-height: 20rem;
        max-height: 60vh;
        background: var(--surf2);
        padding: var(--fe-space-3);
      }
      .viewer-img {
        max-width: 100%;
        max-height: 55vh;
        object-fit: contain;
        transition: transform 0.2s ease-in-out, filter 0.2s ease-in-out;
      }
      .audit-modal-card {
        max-width: 48rem;
      }
      .audit-action-tag {
        font-size: 0.75rem;
        font-family: monospace;
        font-weight: 600;
        padding: var(--fe-space-1) var(--fe-space-2);
        background: var(--surf);
        border: 1px solid var(--line);
        border-radius: var(--fe-radius-sm);
      }
      .action-btn--intake {
        background: var(--pri-soft);
        border-color: var(--pri);
        color: var(--pri);
        font-weight: 700;
      }
      .intake-code-banner {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: var(--fe-space-3);
        flex-wrap: wrap;
        padding: var(--fe-space-3);
        border-radius: var(--fe-radius);
        background: var(--pri-soft);
        border: 1px solid var(--pri);
      }
      .intake-code-banner--muted {
        background: var(--surf2);
        border-color: var(--line);
      }
      .intake-code-banner__main {
        display: flex;
        align-items: center;
        gap: var(--fe-space-3);
        flex-wrap: wrap;
      }
      .intake-code-label {
        font-size: 0.88rem;
        font-weight: 600;
      }
      .intake-code-value {
        font-size: 1.35rem;
        letter-spacing: 0.12em;
        padding: 4px 14px;
        border-radius: var(--fe-radius);
        background: var(--surf);
        border: 1px solid var(--pri);
        color: var(--pri);
      }
      .intake-code-timer {
        font-size: 0.88rem;
        font-weight: 700;
        color: var(--ok);
      }
      .intake-code-banner__actions {
        display: flex;
        align-items: center;
        gap: var(--fe-space-2);
      }
      .intake-pending-banner {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: var(--fe-space-3);
        flex-wrap: wrap;
        padding: var(--fe-space-3);
        border-radius: var(--fe-radius);
        background: #fef3c7;
        border: 1px solid #f59e0b;
        color: #78350f;
        font-size: 0.9rem;
      }
    `,
  ],
})
export class ClinicPatientDetailComponent {
  private readonly api = inject(ClinicPatientApi);
  private readonly route = inject(ActivatedRoute);
  private readonly projects = inject(ProjectContextService);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly rbac = inject(ClinicRbacService);

  protected readonly canViewOdontogram = computed(() =>
    this.rbac.hasAnyPermission(['clinical.view_record', 'clinical.write_record'])
  );

  protected readonly canViewTreatmentPlans = computed(() =>
    this.rbac.hasAnyPermission(['treatment_plans.manage', 'treatment_plans.view_full', 'treatment_plans.view_financials'])
  );

  protected readonly canViewEncounters = computed(() =>
    this.rbac.hasAnyPermission(['clinical.view_record', 'clinical.write_record'])
  );

  protected readonly canBookAppointment = computed(() =>
    this.rbac.hasAnyPermission(['appointments.view', 'appointments.manage', 'appointments.view_own'])
  );

  protected readonly canViewInvoices = computed(() =>
    this.rbac.hasAnyPermission(['invoices.view', 'invoices.create']) &&
    (this.rbac.activeRoles().includes('accountant') || this.rbac.activeRoles().includes('clinic_admin'))
  );

  protected readonly format = inject(FormatService);
  protected readonly patient = new ResourceStore<ClinicPatient>();

  protected readonly patientInitials = computed(() => {
    const name = this.patient.value()?.displayName ?? '';
    const parts = name.trim().split(/\s+/);
    if (parts.length >= 2) {
      return (parts[0][0] + parts[1][0]).toUpperCase();
    }
    return (name[0] ?? 'P').toUpperCase();
  });

  protected readonly patientAge = computed(() => {
    const dob = this.patient.value()?.dateOfBirth;
    if (!dob) return null;
    const diff = Date.now() - new Date(dob).getTime();
    if (isNaN(diff)) return null;
    return Math.floor(diff / (365.25 * 24 * 60 * 60 * 1000));
  });

  protected readonly activeTab = signal<'overview' | 'medical' | 'family' | 'consents' | 'media' | 'actions'>('overview');
  protected readonly relationships = signal<ClinicRelationship[]>([]);
  protected readonly consents = signal<ClinicConsent[]>([]);
  protected readonly disclosures = signal<ClinicDisclosure[]>([]);
  protected readonly medicalHistory = signal<ClinicMedicalHistory | null>(null);
  protected readonly mediaItems = signal<ClinicMediaRow[]>([]);
  protected readonly mediaCategoryFilter = signal<string>('ALL');
  protected readonly mediaToothFilter = signal<number | null>(null);
  protected readonly mediaLegalHoldFilter = signal<boolean>(false);
  protected readonly filteredMediaItems = computed(() => {
    let list = this.mediaItems();
    const cat = this.mediaCategoryFilter();
    if (cat !== 'ALL') {
      list = list.filter((m) => m.category === cat);
    }
    const tooth = this.mediaToothFilter();
    if (tooth !== null && tooth !== undefined) {
      list = list.filter((m) => m.toothFdi === tooth);
    }
    if (this.mediaLegalHoldFilter()) {
      list = list.filter((m) => m.legalHold);
    }
    return list;
  });
  protected readonly fdiTeeth: readonly number[] = [
    18, 17, 16, 15, 14, 13, 12, 11,
    21, 22, 23, 24, 25, 26, 27, 28,
    48, 47, 46, 45, 44, 43, 42, 41,
    31, 32, 33, 34, 35, 36, 37, 38,
    55, 54, 53, 52, 51,
    61, 62, 63, 64, 65,
    85, 84, 83, 82, 81,
    71, 72, 73, 74, 75,
  ];

  protected readonly isUploadMediaOpen = signal(false);
  protected uploadFile: File | null = null;
  protected uploadCategory: ClinicMediaCategory = 'RADIOGRAPH_INTRAORAL';
  protected uploadToothFdi: number | null = null;
  protected uploadAcquisitionDate: string = new Date().toISOString().slice(0, 10);
  protected uploadRadiographer: string = '';
  protected uploadNotes: string = '';

  protected readonly previewMedia = signal<{ row: ClinicMediaRow; url: string } | null>(null);
  protected readonly previewZoom = signal<number>(1);
  protected readonly previewInverted = signal<boolean>(false);

  protected readonly legalHoldTarget = signal<ClinicMediaRow | null>(null);
  protected legalHoldReason: string = '';

  protected readonly isAuditTrailOpen = signal(false);
  protected readonly auditTrailTitle = signal<string>('');
  protected readonly auditItems = signal<ClinicMediaAccessAuditRow[]>([]);

  protected readonly loadingData = signal(false);
  protected readonly actionPending = signal(false);

  // Patient Intake 1-hour code state
  protected readonly generatingIntakeCode = signal(false);
  protected readonly activeIntakeCode = signal<GeneratedIntakeCode | null>(null);
  protected readonly intakeStatus = signal<ActiveIntakeCodeStatus | null>(null);
  protected readonly remainingCodeSeconds = signal<number>(0);
  protected readonly intakeCodeCopied = signal(false);
  protected readonly clinicPortalUrl = signal<string | null>(null);
  private countdownTimerId: ReturnType<typeof setInterval> | null = null;
  protected readonly countdownFormatted = computed(() => {
    const secs = Math.max(0, this.remainingCodeSeconds());
    const mins = Math.floor(secs / 60);
    const rem = secs % 60;
    return `${String(mins).padStart(2, '0')}:${String(rem).padStart(2, '0')}`;
  });

  // Medical history form state (Slice S3)
  protected medAllergies = '';
  protected medConditions = '';
  protected medMedications = '';
  protected medBleeding = false;
  protected medCardiac = false;
  protected medDiabetes = false;
  protected medNotes = '';
  protected readonly medSavedSuccess = signal(false);

  // Form states
  protected readonly isAddRelOpen = signal(false);
  protected relPersonName = '';
  protected relPersonPhone = '';
  protected relType = 'PARENT';
  protected relCustom = '';
  protected relIsGuarantor = false;
  protected relIsEmergency = false;
  protected relNotes = '';

  protected readonly isAddConsentOpen = signal(false);
  protected consentType = 'GENERAL_TREATMENT';
  protected consentSignedBy = '';
  protected consentSignerRel = 'المريض نفسه';
  protected consentWitness = '';
  protected consentDocRef = '';
  protected consentNotes = '';

  protected readonly revokingConsentId = signal<string | null>(null);
  protected revokeReason = '';

  protected readonly isAddDisclosureOpen = signal(false);
  protected discTo = '';
  protected discPurpose = '';
  protected discRecords = '';

  protected archiveReason = '';
  protected mergeTargetId = '';
  protected mergeReason = '';
  protected readonly mergeSuccessMessage = signal<string | null>(null);

  /** The id in the URL, re-read whenever the router reuses this component. */
  protected readonly patientId = toSignal(
    this.route.paramMap.pipe(map((params) => params.get('id'))),
    { initialValue: null },
  );

  /** The scope this read is asked for. A request, never an authorization. */
  protected readonly projectId = computed(() => this.projects.selectedProjectId() ?? undefined);

  constructor() {
    inject(I18nService).register({ clinic: clinicStrings as Namespace });

    this.destroyRef.onDestroy(() => {
      if (this.countdownTimerId) {
        clearInterval(this.countdownTimerId);
        this.countdownTimerId = null;
      }
    });

    effect(() => {
      const id = this.patientId();
      untracked(() => {
        if (id === null) {
          this.patient.reset();
          this.relationships.set([]);
          this.consents.set([]);
          this.disclosures.set([]);
          this.medicalHistory.set(null);
          this.mediaItems.set([]);
          this.activeIntakeCode.set(null);
          this.intakeStatus.set(null);
          this.stopCountdownTimer();
          return;
        }
        void this.load(id);
      });
    });
  }

  protected async reload(): Promise<void> {
    const id = this.patientId();
    if (id !== null) await this.load(id);
  }

  protected errorRequestId(): string | null {
    return this.patient.error()?.requestId ?? null;
  }

  private async load(patientId: string): Promise<void> {
    this.loadingData.set(true);
    try {
      await this.patient.load(() =>
        this.api.patient(patientId, { projectId: this.projectId() }),
      );

      // Pre-fill signer name if empty
      const p = this.patient.value();
      if (!p) {
        return;
      }
      if (!this.consentSignedBy) {
        this.consentSignedBy = p.displayName || '';
      }

      await Promise.allSettled([
        this.api.relationships(patientId, { projectId: this.projectId() }).then((r) => this.relationships.set(r)),
        this.api.consents(patientId, { projectId: this.projectId() }).then((c) => this.consents.set(c)),
        this.api.disclosures(patientId, { projectId: this.projectId() }).then((d) => this.disclosures.set(d)),
        this.api.patientMedia(patientId, { projectId: this.projectId() }).then((m) => this.mediaItems.set(m)),
        this.api.getIntakeStatus(patientId, { projectId: this.projectId() }).then((st) => {
          this.intakeStatus.set(st);
          if (st.hasActiveCode && st.remainingSeconds > 0 && !this.activeIntakeCode()) {
            this.startCountdown(st.remainingSeconds);
          }
        }),
        this.api.medicalHistory(patientId, { projectId: this.projectId() }).then((m) => {
          this.medicalHistory.set(m);
          if (m) {
            this.medAllergies = m.allergies || '';
            this.medConditions = m.conditions || '';
            this.medMedications = m.currentMedications || '';
            this.medBleeding = !!m.bleedingDisorder;
            this.medCardiac = !!m.cardiacCondition;
            this.medDiabetes = !!m.diabetes;
            this.medNotes = m.notes || '';
          }
        }),
      ]);
    } finally {
      this.loadingData.set(false);
    }
  }

  // --- Actions ---

  protected async generatePatientIntakeCode(): Promise<void> {
    const id = this.patientId();
    if (!id || this.generatingIntakeCode()) return;

    this.generatingIntakeCode.set(true);
    try {
      const generated = await this.api.generateIntakeCode(id, { projectId: this.projectId() });
      this.activeIntakeCode.set(generated);
      this.startCountdown(generated.expiresInSeconds || 3600);

      try {
        const qr = await this.api.getClinicIntakeQr({ projectId: this.projectId() });
        if (qr.intakePortalKey) {
          this.clinicPortalUrl.set(buildIntakePortalUrl(qr.intakePortalKey));
        }
      } catch {
        // Optional link shortcut
      }
    } finally {
      this.generatingIntakeCode.set(false);
    }
  }

  protected copyIntakeCode(code: string): void {
    navigator.clipboard.writeText(code).then(() => {
      this.intakeCodeCopied.set(true);
      setTimeout(() => this.intakeCodeCopied.set(false), 2000);
    });
  }

  private startCountdown(seconds: number): void {
    this.stopCountdownTimer();
    this.remainingCodeSeconds.set(Math.max(0, Math.floor(seconds)));
    if (seconds <= 0) return;
    this.countdownTimerId = setInterval(() => {
      const next = this.remainingCodeSeconds() - 1;
      if (next <= 0) {
        this.remainingCodeSeconds.set(0);
        this.activeIntakeCode.set(null);
        this.stopCountdownTimer();
      } else {
        this.remainingCodeSeconds.set(next);
      }
    }, 1000);
  }

  private stopCountdownTimer(): void {
    if (this.countdownTimerId) {
      clearInterval(this.countdownTimerId);
      this.countdownTimerId = null;
    }
  }

  protected async saveRelationship(): Promise<void> {
    const id = this.patientId();
    if (!id || !this.relPersonName.trim()) return;

    this.actionPending.set(true);
    try {
      await this.api.addRelationship(id, {
        relationshipType: this.relType,
        customRelationship: this.relType === 'OTHER' ? this.relCustom : undefined,
        relatedPersonName: this.relPersonName.trim(),
        relatedPersonPhone: this.relPersonPhone.trim() || undefined,
        isGuarantor: this.relIsGuarantor,
        isEmergencyContact: this.relIsEmergency,
        notes: this.relNotes.trim() || undefined,
      }, { projectId: this.projectId() });

      this.isAddRelOpen.set(false);
      this.relPersonName = '';
      this.relPersonPhone = '';
      this.relNotes = '';
      this.relIsGuarantor = false;
      this.relIsEmergency = false;

      const updated = await this.api.relationships(id, { projectId: this.projectId() });
      this.relationships.set(updated);
    } finally {
      this.actionPending.set(false);
    }
  }

  protected async deleteRelationship(relId: string): Promise<void> {
    const id = this.patientId();
    if (!id) return;
    this.actionPending.set(true);
    try {
      await this.api.deleteRelationship(id, relId, { projectId: this.projectId() });
      const updated = await this.api.relationships(id, { projectId: this.projectId() });
      this.relationships.set(updated);
    } finally {
      this.actionPending.set(false);
    }
  }

  protected async saveConsent(): Promise<void> {
    const id = this.patientId();
    if (!id || !this.consentSignedBy.trim()) return;

    this.actionPending.set(true);
    try {
      await this.api.addConsent(id, {
        consentType: this.consentType,
        signedBy: this.consentSignedBy.trim(),
        signerRelationship: this.consentSignerRel.trim() || undefined,
        witnessName: this.consentWitness.trim() || undefined,
        documentReference: this.consentDocRef.trim() || undefined,
        notes: this.consentNotes.trim() || undefined,
      }, { projectId: this.projectId() });

      this.isAddConsentOpen.set(false);
      this.consentWitness = '';
      this.consentDocRef = '';
      this.consentNotes = '';

      const updated = await this.api.consents(id, { projectId: this.projectId() });
      this.consents.set(updated);
    } finally {
      this.actionPending.set(false);
    }
  }

  protected openRevokeModal(consentId: string): void {
    this.revokingConsentId.set(consentId);
    this.revokeReason = '';
  }

  protected async confirmRevokeConsent(): Promise<void> {
    const pId = this.patientId();
    const cId = this.revokingConsentId();
    if (!pId || !cId || !this.revokeReason.trim()) return;

    this.actionPending.set(true);
    try {
      await this.api.revokeConsent(pId, cId, this.revokeReason.trim(), { projectId: this.projectId() });
      this.revokingConsentId.set(null);
      this.revokeReason = '';

      const updated = await this.api.consents(pId, { projectId: this.projectId() });
      this.consents.set(updated);
    } finally {
      this.actionPending.set(false);
    }
  }

  protected async saveDisclosure(): Promise<void> {
    const id = this.patientId();
    if (!id || !this.discTo.trim() || !this.discPurpose.trim() || !this.discRecords.trim()) return;

    this.actionPending.set(true);
    try {
      await this.api.logDisclosure(id, {
        disclosedTo: this.discTo.trim(),
        purpose: this.discPurpose.trim(),
        recordsDisclosed: this.discRecords.trim(),
      }, { projectId: this.projectId() });

      this.isAddDisclosureOpen.set(false);
      this.discTo = '';
      this.discPurpose = '';
      this.discRecords = '';

      const updated = await this.api.disclosures(id, { projectId: this.projectId() });
      this.disclosures.set(updated);
    } finally {
      this.actionPending.set(false);
    }
  }

  protected async saveMedicalHistory(): Promise<void> {
    const id = this.patientId();
    if (!id) return;

    this.actionPending.set(true);
    try {
      await this.api.saveMedicalHistory(id, {
        allergies: this.medAllergies.trim() || undefined,
        conditions: this.medConditions.trim() || undefined,
        currentMedications: this.medMedications.trim() || undefined,
        bleedingDisorder: this.medBleeding,
        cardiacCondition: this.medCardiac,
        diabetes: this.medDiabetes,
        notes: this.medNotes.trim() || undefined,
      }, { projectId: this.projectId() });

      const updated = await this.api.medicalHistory(id, { projectId: this.projectId() });
      this.medicalHistory.set(updated);
      this.medSavedSuccess.set(true);
      setTimeout(() => this.medSavedSuccess.set(false), 4000);
    } finally {
      this.actionPending.set(false);
    }
  }

  protected async archivePatient(): Promise<void> {
    const id = this.patientId();
    if (!id || !this.archiveReason.trim()) return;
    this.actionPending.set(true);
    try {
      await this.api.archivePatient(id, this.archiveReason.trim(), { projectId: this.projectId() });
      this.archiveReason = '';
      await this.reload();
    } finally {
      this.actionPending.set(false);
    }
  }

  protected async restorePatient(): Promise<void> {
    const id = this.patientId();
    if (!id) return;
    this.actionPending.set(true);
    try {
      await this.api.restorePatient(id, { projectId: this.projectId() });
      await this.reload();
    } finally {
      this.actionPending.set(false);
    }
  }

  protected async mergePatient(): Promise<void> {
    const id = this.patientId();
    if (!id || !this.mergeTargetId.trim() || !this.mergeReason.trim()) return;
    this.actionPending.set(true);
    try {
      const res: MergePatientsResult = await this.api.mergePatients({
        sourcePatientId: id,
        targetPatientId: this.mergeTargetId.trim(),
        reason: this.mergeReason.trim(),
      }, { projectId: this.projectId() });

      this.mergeSuccessMessage.set(
        `تم دمج السجل بنجاح! تم نقل ${res.appointmentsMoved} موعد، ${res.encountersMoved} زيارة، ${res.plansMoved} خطة علاج، و ${res.invoicesMoved} فاتورة إلى المريض المستهدف.`,
      );
      await this.reload();
    } finally {
      this.actionPending.set(false);
    }
  }

  protected formatRelType(type: string): string {
    switch (type) {
      case 'PARENT':
        return 'أب / أم (ولي أمر)';
      case 'SPOUSE':
        return 'زوج / زوجة';
      case 'CHILD':
        return 'ابن / ابنة';
      case 'SIBLING':
        return 'أخ / أخت';
      case 'GUARDIAN':
        return 'وصي قانوني';
      default:
        return type || 'صلة قرابة';
    }
  }

  protected formatConsentType(type: string): string {
    switch (type) {
      case 'GENERAL_TREATMENT':
        return 'موافقة علاج عامة واستشارة';
      case 'SURGERY':
        return 'موافقة عمل جراحي وخلع';
      case 'ANESTHESIA':
        return 'موافقة تخدير موضعي';
      case 'ORTHODONTICS':
        return 'إقرار خطة تقويم أسنان';
      case 'IMPLANT':
        return 'موافقة زراعة أسنان';
      case 'PHOTOGRAPHY':
        return 'إقرار تصوير فوتوغرافي سريري';
      case 'DATA_DISCLOSURE':
        return 'موافقة كشف بيانات طبية';
      default:
        return type || 'موافقة طبية';
    }
  }

  // --- S7 Media Actions & Helpers ---

  protected onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (input.files && input.files.length > 0) {
      this.uploadFile = input.files[0];
    }
  }

  protected onToothFilterChange(val: any): void {
    const parsed = val === null || val === 'null' || val === undefined ? null : Number(val);
    this.mediaToothFilter.set(Number.isNaN(parsed) ? null : parsed);
  }

  protected openUploadModal(): void {
    this.uploadFile = null;
    this.uploadCategory = 'RADIOGRAPH_INTRAORAL';
    this.uploadToothFdi = null;
    this.uploadAcquisitionDate = new Date().toISOString().slice(0, 10);
    this.uploadRadiographer = '';
    this.uploadNotes = '';
    this.isUploadMediaOpen.set(true);
  }

  protected async submitUpload(): Promise<void> {
    const patientId = this.patientId();
    if (!patientId || !this.uploadFile) return;

    this.actionPending.set(true);
    try {
      await this.api.uploadMedia(
        patientId,
        {
          file: this.uploadFile,
          category: this.uploadCategory,
          toothFdi: this.uploadToothFdi ?? undefined,
          acquisitionDate: this.uploadAcquisitionDate || undefined,
          radiographerName: this.uploadRadiographer.trim() || undefined,
          notes: this.uploadNotes.trim() || undefined,
        },
        { projectId: this.projectId() },
      );

      this.isUploadMediaOpen.set(false);
      this.uploadFile = null;
      this.uploadToothFdi = null;
      this.uploadRadiographer = '';
      this.uploadNotes = '';

      const updated = await this.api.patientMedia(patientId, { projectId: this.projectId() });
      this.mediaItems.set(updated);
    } finally {
      this.actionPending.set(false);
    }
  }

  protected async previewMediaItem(row: ClinicMediaRow): Promise<void> {
    const patientId = this.patientId();
    if (!patientId) return;

    this.actionPending.set(true);
    try {
      const signed = await this.api.mediaDownloadUrl(patientId, row.id, { projectId: this.projectId() });
      this.previewZoom.set(1);
      this.previewInverted.set(false);
      this.previewMedia.set({ row, url: signed.downloadUrl });
    } finally {
      this.actionPending.set(false);
    }
  }

  protected closePreview(): void {
    this.previewMedia.set(null);
  }

  protected zoomIn(): void {
    this.previewZoom.update((z) => Math.min(3, +(z + 0.25).toFixed(2)));
  }

  protected zoomOut(): void {
    this.previewZoom.update((z) => Math.max(0.5, +(z - 0.25).toFixed(2)));
  }

  protected toggleInvert(): void {
    this.previewInverted.update((v) => !v);
  }

  protected resetPreview(): void {
    this.previewZoom.set(1);
    this.previewInverted.set(false);
  }

  protected async downloadMediaItem(row: ClinicMediaRow): Promise<void> {
    const patientId = this.patientId();
    if (!patientId) return;

    this.actionPending.set(true);
    try {
      const signed = await this.api.mediaDownloadUrl(patientId, row.id, { projectId: this.projectId() });
      if (typeof window !== 'undefined') {
        window.open(signed.downloadUrl, '_blank');
      }
    } finally {
      this.actionPending.set(false);
    }
  }

  protected openLegalHoldModal(row: ClinicMediaRow): void {
    this.legalHoldTarget.set(row);
    this.legalHoldReason = '';
  }

  protected async submitLegalHold(): Promise<void> {
    const patientId = this.patientId();
    const target = this.legalHoldTarget();
    if (!patientId || !target || !this.legalHoldReason.trim()) return;

    this.actionPending.set(true);
    try {
      await this.api.toggleLegalHold(
        patientId,
        target.id,
        !target.legalHold,
        this.legalHoldReason.trim(),
        { projectId: this.projectId() },
      );
      this.legalHoldTarget.set(null);
      this.legalHoldReason = '';

      const updated = await this.api.patientMedia(patientId, { projectId: this.projectId() });
      this.mediaItems.set(updated);
    } finally {
      this.actionPending.set(false);
    }
  }

  protected async deleteMediaItem(row: ClinicMediaRow): Promise<void> {
    const patientId = this.patientId();
    if (!patientId || row.legalHold) return;

    this.actionPending.set(true);
    try {
      await this.api.deleteMedia(patientId, row.id, 'User deletion', { projectId: this.projectId() });
      const updated = await this.api.patientMedia(patientId, { projectId: this.projectId() });
      this.mediaItems.set(updated);
    } finally {
      this.actionPending.set(false);
    }
  }

  protected async openItemAuditTrail(row: ClinicMediaRow): Promise<void> {
    const patientId = this.patientId();
    if (!patientId) return;

    this.actionPending.set(true);
    try {
      const list = await this.api.mediaAudit(patientId, row.id, { projectId: this.projectId() });
      this.auditTrailTitle.set(row.fileName);
      this.auditItems.set(list);
      this.isAuditTrailOpen.set(true);
    } finally {
      this.actionPending.set(false);
    }
  }

  protected async openPatientAuditTrail(): Promise<void> {
    const patientId = this.patientId();
    if (!patientId) return;

    this.actionPending.set(true);
    try {
      const list = await this.api.patientMediaAudit(patientId, { projectId: this.projectId() });
      this.auditTrailTitle.set('ملف المريض الشامل');
      this.auditItems.set(list);
      this.isAuditTrailOpen.set(true);
    } finally {
      this.actionPending.set(false);
    }
  }

  protected categoryLabel(cat: string): string {
    switch (cat) {
      case 'RADIOGRAPH_INTRAORAL':
        return 'أشعة سينية داخل فموية';
      case 'RADIOGRAPH_PANORAMIC':
        return 'أشعة بانورامية';
      case 'RADIOGRAPH_CEPHALOMETRIC':
        return 'أشعة سيفالومترية';
      case 'PHOTO_INTRAORAL':
        return 'صورة داخل فموية';
      case 'PHOTO_EXTRAORAL':
        return 'صورة الوجه والابتسامة';
      case 'DOCUMENT_PDF':
        return 'مستند طبي PDF';
      case 'CONSENT_FORM':
        return 'نموذج موافقة';
      case 'LAB_PRESCRIPTION':
        return 'وصفة المختبر';
      default:
        return 'مستند سريري';
    }
  }

  protected isImage(contentType: string): boolean {
    return !!contentType && contentType.startsWith('image/');
  }

  protected formatBytes(bytes: number): string {
    if (!bytes || bytes <= 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return `${parseFloat((bytes / Math.pow(k, i)).toFixed(1))} ${sizes[i]}`;
  }
}
