import { CommonModule } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { ClinicApi, type ClinicDoctor } from '../../core/api/endpoints';
import { ClinicRbacService } from '../../core/auth/clinic-rbac';
import { ProjectContextService } from '../../core/context/project-context.service';
import { FormatService } from '../../core/format/format.service';
import { I18nService } from '../../core/i18n/i18n.service';
import { IconComponent } from '../../ui/icon.component';
import {
  ClinicPatientApi,
  type ClinicLetterheadSettings,
  type ClinicPatient,
  type ClinicPrescriptionSheet,
  type CreatePrescriptionPayload,
  type ClinicMedicationPreset,
  type ClinicDiagnosisPreset,
} from './clinic-patient.api';

interface DraftMedicationRow {
  id: string;
  medicationName: string;
  dosage: string;
  frequency: string;
  duration: string;
  instructions: string;
  selectedForDelete: boolean;
}

@Component({
  selector: 'fe-clinic-prescriptions-page',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CommonModule, FormsModule, RouterLink, IconComponent],
  template: `
    <div class="rx-page" data-testid="clinic-prescriptions-page">
      <!-- Top Page Header -->
      <header class="rx-page-header no-print">
        <div class="rx-header-titles">
          <div class="rx-eyebrow">{{ isAr() ? 'الرعاية السريرية' : 'CLINICAL CARE' }}</div>
          <div class="rx-title-row">
            <h1 class="rx-title">{{ isAr() ? 'الوصفات الطبية' : 'Prescriptions' }}</h1>
            <div class="tl-info-wrap">
              <button
                type="button"
                class="tl-info-trigger"
                [class.is-active]="showDescInfo()"
                (click)="toggleDescInfo()"
                title="Prescription details"
              >?</button>
              @if (showDescInfo()) {
                <div class="tl-info-popover">
                  {{ isAr()
                    ? 'إصدار وإدارة الوصفات الدوائية للمرضى مع نموذج الطباعة الرسمي والصفحة المروّسة الخاصة بالعيادة.'
                    : 'Issue and manage patient electronic prescriptions with official clinic letterhead and print-ready templates.'
                  }}
                </div>
              }
            </div>
          </div>
        </div>

        <div class="rx-header-actions">
          @if (canWritePrescription()) {
            <button
              type="button"
              class="fe-btn fe-btn--ghost"
              data-testid="open-presets-catalog-btn"
              (click)="openPresetsCatalogModal()"
            >
              <fe-icon name="knowledge" [size]="16" />
              <span>{{ isAr() ? 'دليل الأدوية والاختصارات' : 'Medications & Presets' }}</span>
            </button>
          }
          @if (canCustomizeLetterhead()) {
            <button
              type="button"
              class="fe-btn fe-btn--ghost"
              data-testid="open-letterhead-settings-btn"
              (click)="openLetterheadModal()"
            >
              <fe-icon name="settings" [size]="16" />
              <span>{{ isAr() ? 'تخصيص الورقة المروّسة' : 'Letterhead Settings' }}</span>
            </button>
          }
          @if (canWritePrescription()) {
            <button
              type="button"
              class="fe-btn fe-btn--primary"
              data-testid="new-prescription-btn"
              (click)="openNewPrescriptionModal()"
            >
              <span>{{ isAr() ? '+ وصفة جديدة' : '+ New Prescription' }}</span>
            </button>
          }
        </div>
      </header>

      @if (feedbackMessage()) {
        <div class="rx-banner rx-banner--success no-print" role="status">
          <span>{{ feedbackMessage() }}</span>
          <button type="button" class="rx-banner-close" (click)="feedbackMessage.set(null)">&times;</button>
        </div>
      }
      @if (errorMessage()) {
        <div class="rx-banner rx-banner--error no-print" role="alert">
          <span>{{ errorMessage() }}</span>
          <button type="button" class="rx-banner-close" (click)="errorMessage.set(null)">&times;</button>
        </div>
      }

      <!-- Filters & Search Card -->
      <section class="rx-card rx-toolbar-card no-print">
        <div class="rx-toolbar">
          <div class="rx-search-box">
            <fe-icon name="search" [size]="16" class="rx-search-icon" />
            <input
              type="search"
              class="rx-input rx-search-input"
              [placeholder]="isAr() ? 'بحث برقم الوصفة (RX)، اسم المريض، التشخيص، أو اسم الدواء...' : 'Search by Rx #, patient name, diagnosis, or medicine...'"
              [ngModel]="searchQuery()"
              (ngModelChange)="searchQuery.set($event)"
              data-testid="prescriptions-search-input"
            />
          </div>

          <div class="rx-filter-group">
            <select
              class="rx-select"
              [ngModel]="selectedDoctorFilter()"
              (ngModelChange)="selectedDoctorFilter.set($event)"
              data-testid="prescriptions-doctor-filter"
            >
              <option value="">{{ isAr() ? 'كل الأطباء' : 'All Dentists' }}</option>
              @for (doc of doctors(); track doc.id) {
                <option [value]="doc.id">{{ doc.name }}</option>
              }
            </select>

            <button type="button" class="fe-btn fe-btn--ghost fe-btn--sm" (click)="reloadAll()">
              {{ isAr() ? 'تحديث' : 'Refresh' }}
            </button>
          </div>
        </div>
      </section>

      <!-- Prescriptions Table Card (Matches Screenshot 1 in Clinic Flow Theme) -->
      <section class="rx-card rx-table-card no-print">
        <div class="rx-card-head">
          <div class="rx-card-title-wrap">
            <span class="rx-pill-icon">℞</span>
            <h2 class="rx-card-title">{{ isAr() ? 'سجل الوصفات الصادرة' : 'Issued Prescriptions' }}</h2>
            <span class="rx-count-badge">{{ filteredSheets().length }}</span>
          </div>
        </div>

        @if (loading()) {
          <div class="rx-empty-state">{{ isAr() ? 'جاري تحميل سجل الوصفات الطبية...' : 'Loading prescriptions log...' }}</div>
        } @else if (filteredSheets().length === 0) {
          <div class="rx-empty-state">
            <div class="rx-empty-icon">℞</div>
            <h3>{{ isAr() ? 'لا توجد وصفات طبية مسجلة حالياً' : 'No prescriptions recorded yet' }}</h3>
            <p>{{ isAr() ? 'اضغط على زر «+ وصفة جديدة» لإصدار وصفة طبية جديدة للمريض وطباعتها.' : 'Click "+ New Prescription" to issue a new prescription for a patient and print it.' }}</p>
          </div>
        } @else {
          <div class="rx-table-wrap">
            <table class="rx-table" data-testid="prescriptions-table">
              <thead>
                <tr>
                  <th>RX #</th>
                  <th>{{ isAr() ? 'المريض' : 'PATIENT' }}</th>
                  <th>{{ isAr() ? 'الطبيب' : 'DENTIST' }}</th>
                  <th>{{ isAr() ? 'التشخيص' : 'DIAGNOSIS' }}</th>
                  <th>{{ isAr() ? 'الأدوية' : 'MEDICINES' }}</th>
                  <th>{{ isAr() ? 'التاريخ' : 'DATE' }}</th>
                  <th class="rx-col-actions">{{ isAr() ? 'إجراءات' : 'ACTIONS' }}</th>
                </tr>
              </thead>
              <tbody>
                @for (sheet of filteredSheets(); track sheet.id) {
                  <tr data-testid="prescription-row">
                    <td>
                      <span class="rx-code-badge" dir="ltr">{{ sheet.rxNumber }}</span>
                    </td>
                    <td>
                      <div class="rx-patient-cell">
                        <a
                          class="rx-patient-link"
                          [routerLink]="['/app/clinic/patients', sheet.patientId]"
                        >
                          {{ sheet.patientName }}
                        </a>
                        @if (sheet.patientNo) {
                          <span class="rx-cell-sub" dir="ltr">{{ sheet.patientNo }}</span>
                        }
                      </div>
                    </td>
                    <td>
                      <div class="rx-doctor-cell">
                        <span class="rx-doctor-name">{{ sheet.doctorName || '—' }}</span>
                        @if (sheet.doctorSpecialty) {
                          <span class="rx-cell-sub">{{ sheet.doctorSpecialty }}</span>
                        }
                      </div>
                    </td>
                    <td>
                      <span class="rx-diagnosis-text">{{ sheet.diagnosis || '—' }}</span>
                    </td>
                    <td>
                      <div class="rx-meds-pills">
                        @for (med of sheet.medications.slice(0, 2); track med.id) {
                          <span class="rx-med-chip" dir="ltr">{{ med.medicationName }}</span>
                        }
                        @if (sheet.medications.length > 2) {
                          <span class="rx-med-more">+{{ sheet.medications.length - 2 }}</span>
                        }
                      </div>
                    </td>
                    <td>
                      <span class="rx-date-text" dir="ltr">{{ format.date(sheet.visitDate) }}</span>
                    </td>
                    <td class="rx-col-actions">
                      <div class="rx-row-actions">
                        <button
                          type="button"
                          class="rx-action-btn rx-action-btn--view"
                          [title]="isAr() ? 'معاينة وطباعة الوصفة' : 'View & Print Prescription'"
                          data-testid="view-prescription-btn"
                          (click)="openPreviewSheet(sheet)"
                        >
                          <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2">
                            <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"></path>
                            <circle cx="12" cy="12" r="3"></circle>
                          </svg>
                        </button>
                        @if (canWritePrescription()) {
                          <button
                            type="button"
                            class="rx-action-btn rx-action-btn--delete"
                            [title]="isAr() ? 'حذف الوصفة' : 'Delete Prescription'"
                            data-testid="delete-prescription-btn"
                            (click)="promptDeleteSheet(sheet)"
                          >
                            <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2">
                              <polyline points="3 6 5 6 21 6"></polyline>
                              <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"></path>
                            </svg>
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
      </section>

      <!-- ===================================================================== -->
      <!-- MODAL 1: NEW PRESCRIPTION (Matches Screenshot 4 in Clinic Flow Theme) -->
      <!-- ===================================================================== -->
      @if (isNewModalOpen()) {
        <div class="rx-modal-backdrop no-print" (click)="closeNewPrescriptionModal()">
          <div
            class="rx-modal-card rx-modal-card--wide"
            data-testid="new-prescription-modal"
            (click)="$event.stopPropagation()"
          >
            <div class="rx-modal-header">
              <div>
                <h2 class="rx-modal-title">{{ isAr() ? 'إصدار وصفة طبية جديدة' : 'New Prescription' }}</h2>
                <p class="rx-modal-sub">{{ isAr() ? 'اختر المريض والطبيب وأضف الأدوية والجرعات المعتمدة للوصفة.' : 'Select patient, dentist, diagnosis, and approved medications for the prescription.' }}</p>
              </div>
              <button type="button" class="rx-modal-close" (click)="closeNewPrescriptionModal()">&times;</button>
            </div>

            <div class="rx-modal-body">
              <div class="rx-new-grid">
                <!-- Left Column: Details -->
                <div class="rx-panel">
                  <div class="rx-panel-head">
                    <span class="rx-panel-badge">1</span>
                    <h3 class="rx-panel-title">{{ isAr() ? 'بيانات المريض والتشخيص' : 'Patient & Diagnosis Details' }}</h3>
                  </div>

                  <div class="rx-form-stack">
                    <div class="rx-field">
                      <label class="rx-label">{{ isAr() ? 'المريض *' : 'Patient *' }}</label>
                      <select
                        class="rx-select"
                        [(ngModel)]="newRxPatientId"
                        data-testid="new-rx-patient-select"
                        required
                      >
                        <option value="">{{ isAr() ? '— اختر المريض —' : '— Select Patient —' }}</option>
                        @for (p of patients(); track p.id) {
                          <option [value]="p.id">
                            {{ p.displayName }} {{ p.patientNo ? '(' + p.patientNo + ')' : '' }}
                          </option>
                        }
                      </select>
                    </div>

                    <div class="rx-field">
                      <label class="rx-label">{{ isAr() ? 'الطبيب المعالج *' : 'Dentist *' }}</label>
                      <select
                        class="rx-select"
                        [(ngModel)]="newRxDoctorId"
                        data-testid="new-rx-doctor-select"
                        required
                      >
                        <option value="">{{ isAr() ? '— اختر الطبيب —' : '— Select Dentist —' }}</option>
                        @for (d of doctors(); track d.id) {
                          <option [value]="d.id">{{ d.name }} — {{ d.specialty }}</option>
                        }
                      </select>
                    </div>

                    <div class="rx-field">
                      <label class="rx-label">{{ isAr() ? 'تاريخ الوصفة *' : 'Date *' }}</label>
                      <input
                        type="date"
                        class="rx-input"
                        [(ngModel)]="newRxDate"
                        data-testid="new-rx-date-input"
                      />
                    </div>

                    <div class="rx-field">
                      <div class="rx-label-row">
                        <label class="rx-label">{{ isAr() ? 'التشخيص الطبي *' : 'Diagnosis *' }}</label>
                        <button type="button" class="rx-mini-link" (click)="openPresetsCatalogWithTab('diagnoses')">
                          {{ isAr() ? '+ إدارة اختصارات التشخيص' : '+ Manage Presets' }}
                        </button>
                      </div>
                      <input
                        type="text"
                        class="rx-input"
                        list="dental-diagnoses-list"
                        [(ngModel)]="newRxDiagnosis"
                        [placeholder]="isAr() ? 'مثال: Pulpitis / Post-extraction care / التهاب عصب' : 'e.g., Pulpitis, Post-extraction care, Gingivitis'"
                        data-testid="new-rx-diagnosis-input"
                      />
                      <datalist id="dental-diagnoses-list">
                        @for (diag of diagnosisPresets(); track diag.id) {
                          <option [value]="diag.name"></option>
                        }
                      </datalist>
                    </div>

                    <div class="rx-field">
                      <label class="rx-label">{{ isAr() ? 'ملاحظات إضافية' : 'Notes & Instructions' }}</label>
                      <textarea
                        rows="3"
                        class="rx-textarea"
                        [(ngModel)]="newRxNotes"
                        [placeholder]="isAr() ? 'ملاحظات أو توصيات خاصة للمريض أو الصيدلي...' : 'Special notes or instructions for patient or pharmacist...'"
                        data-testid="new-rx-notes-input"
                      ></textarea>
                    </div>
                  </div>
                </div>

                <!-- Right Column: Medicines -->
                <div class="rx-panel">
                  <div class="rx-panel-head rx-panel-head--between">
                    <div class="rx-panel-head-left">
                      <span class="rx-panel-badge">℞</span>
                      <h3 class="rx-panel-title">{{ isAr() ? 'الأدوية والجرعات' : 'Medicines & Dosage' }}</h3>
                    </div>
                    <div class="rx-panel-head-actions">
                      <button
                        type="button"
                        class="fe-btn fe-btn--ghost fe-btn--sm"
                        data-testid="quick-add-med-btn"
                        (click)="openQuickAddMedModal()"
                      >
                        {{ isAr() ? '+ دواء جديد للاختصارات' : '+ New Preset' }}
                      </button>
                      <button
                        type="button"
                        class="fe-btn fe-btn--secondary fe-btn--sm"
                        data-testid="add-medicine-row-btn"
                        (click)="addDraftMedicationRow()"
                      >
                        {{ isAr() ? '+ سطر دواء' : '+ Add Row' }}
                      </button>
                    </div>
                  </div>

                  <datalist id="common-dental-meds">
                    @for (m of medicationPresets(); track m.id) {
                      <option [value]="m.medicationName"></option>
                    }
                  </datalist>

                  <div class="rx-med-rows">
                    <div class="rx-med-rows-header">
                      <span>{{ isAr() ? 'الدواء' : 'Medicine' }}</span>
                      <span>{{ isAr() ? 'الجرعة' : 'Dosage' }}</span>
                      <span>{{ isAr() ? 'التكرار' : 'Frequency' }}</span>
                      <span>{{ isAr() ? 'المدة' : 'Duration' }}</span>
                      <span>{{ isAr() ? 'تعليمات' : 'Instructions' }}</span>
                      <span>{{ isAr() ? 'حذف' : 'Del' }}</span>
                    </div>

                    @for (row of draftMedications(); track row.id; let i = $index) {
                      <div class="rx-med-row-item" data-testid="draft-medicine-row">
                        <input
                          type="text"
                          class="rx-input rx-input--sm"
                          list="common-dental-meds"
                          [(ngModel)]="row.medicationName"
                          (ngModelChange)="onMedicationNameChanged(row)"
                          [placeholder]="isAr() ? 'اسم الدواء' : 'Medicine name'"
                          [attr.data-testid]="'med-name-' + i"
                        />
                        <input
                          type="text"
                          class="rx-input rx-input--sm"
                          [(ngModel)]="row.dosage"
                          [placeholder]="isAr() ? '1 قرص' : '1 tablet'"
                        />
                        <input
                          type="text"
                          class="rx-input rx-input--sm"
                          [(ngModel)]="row.frequency"
                          [placeholder]="isAr() ? 'مرتين يومياً' : 'Twice a day'"
                        />
                        <input
                          type="text"
                          class="rx-input rx-input--sm"
                          [(ngModel)]="row.duration"
                          [placeholder]="isAr() ? '5 أيام' : '5 days'"
                        />
                        <input
                          type="text"
                          class="rx-input rx-input--sm"
                          [(ngModel)]="row.instructions"
                          [placeholder]="isAr() ? 'بعد الأكل...' : 'After meals...'"
                        />
                        <button
                          type="button"
                          class="rx-del-row-btn"
                          [title]="isAr() ? 'حذف الدواء' : 'Delete medicine'"
                          [disabled]="draftMedications().length <= 1"
                          (click)="removeDraftMedicationRow(i)"
                        >
                          &times;
                        </button>
                      </div>
                    }
                  </div>

                  <div class="rx-med-quick-presets">
                    <div class="rx-presets-header">
                      <span class="rx-presets-label">{{ isAr() ? 'اختصارات الأدوية السريعة (بنقرة واحدة):' : 'Quick Presets (1-click):' }}</span>
                      <button
                        type="button"
                        class="rx-mini-link"
                        (click)="openQuickAddMedModal()"
                      >
                        {{ isAr() ? '+ دواء جديد للاختصارات' : '+ New Preset' }}
                      </button>
                    </div>
                    <div class="rx-presets-chips-wrap">
                      @for (p of favoriteMedPresets(); track p.id) {
                        <button
                          type="button"
                          class="rx-preset-chip"
                          (click)="appendPresetObject(p)"
                        >
                          + {{ p.medicationName }}
                        </button>
                      }
                    </div>
                  </div>
                </div>
              </div>
            </div>

            <div class="rx-modal-footer">
              <button type="button" class="fe-btn fe-btn--ghost" (click)="closeNewPrescriptionModal()">
                {{ isAr() ? 'إلغاء' : 'Cancel' }}
              </button>
              <button
                type="button"
                class="fe-btn fe-btn--primary"
                data-testid="submit-new-prescription-btn"
                [disabled]="saving()"
                (click)="submitNewPrescription()"
              >
                {{ saving() ? (isAr() ? 'جاري الحفظ...' : 'Saving...') : (isAr() ? 'حفظ وإصدار الوصفة' : 'Save Prescription') }}
              </button>
            </div>
          </div>
        </div>
      }

      <!-- ===================================================================== -->
      <!-- MODAL 2: PRINTABLE PRESCRIPTION SHEET (Matches Screenshots 2 & 3)     -->
      <!-- ===================================================================== -->
      @if (activeSheet(); as sheet) {
        <div class="rx-modal-backdrop rx-print-backdrop" (click)="closePreviewSheet()">
          <div
            class="rx-sheet-wrapper"
            [class.rx-sheet-wrapper--a5]="letterhead().paperSize === 'A5'"
            (click)="$event.stopPropagation()"
          >
            <!-- Top Control Bar (Hidden in Print) -->
            <div class="rx-sheet-toolbar no-print">
              <div class="rx-sheet-toolbar-left">
                <span class="rx-code-badge" dir="ltr">Prescription {{ sheet.rxNumber }}</span>
                <span class="rx-toolbar-hint">
                  {{ isAr() ? 'مقاس الورق:' : 'Paper Size:' }} <strong>{{ letterhead().paperSize }}</strong>
                  {{ isAr()
                    ? (letterhead().prePrintedPaper ? '· وضع ورق مروّس مسبقاً' : '· ترويسة العيادة الرقمية')
                    : (letterhead().prePrintedPaper ? '· Pre-printed letterhead' : '· Digital clinic letterhead')
                  }}
                </span>
              </div>
              <div class="rx-sheet-toolbar-actions">
                @if (canCustomizeLetterhead()) {
                  <button
                    type="button"
                    class="fe-btn fe-btn--ghost fe-btn--sm"
                    data-testid="preview-customize-letterhead-btn"
                    (click)="openLetterheadModal()"
                  >
                    {{ isAr() ? 'تعديل الترويسة' : 'Customize Letterhead' }}
                  </button>
                }
                <button
                  type="button"
                  class="fe-btn fe-btn--primary fe-btn--sm"
                  data-testid="print-prescription-btn"
                  (click)="triggerPrint()"
                >
                  {{ isAr() ? 'طباعة الوصفة' : 'Print Prescription' }}
                </button>
                @if (canWritePrescription()) {
                  <button
                    type="button"
                    class="fe-btn fe-btn--danger fe-btn--sm"
                    data-testid="preview-delete-prescription-btn"
                    (click)="promptDeleteSheet(sheet)"
                  >
                    {{ isAr() ? 'حذف الوصفة' : 'Delete Prescription' }}
                  </button>
                }
                <button
                  type="button"
                  class="fe-btn fe-btn--ghost fe-btn--sm"
                  (click)="closePreviewSheet()"
                >
                  {{ isAr() ? 'إغلاق' : 'Close' }}
                </button>
              </div>
            </div>

            <!-- Official Printable Prescription Document -->
            <article
              class="rx-document"
              [class.rx-document--preprinted]="letterhead().prePrintedPaper"
              data-testid="printable-prescription-sheet"
            >
              <!-- Customizable Clinic Letterhead Header -->
              @if (!letterhead().prePrintedPaper) {
                <header class="rx-doc-letterhead">
                  <div class="rx-doc-brand">
                    @if (letterhead().logoUrl) {
                      <img
                        [src]="letterhead().logoUrl!"
                        alt="Clinic Logo"
                        class="rx-doc-logo-img"
                      />
                    } @else {
                      <div class="rx-doc-logo-box" aria-hidden="true">
                        <svg viewBox="0 0 24 24" width="26" height="26" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                          <path d="M12 4.5c-1.8-1.5-4.5-1.5-6.5 0-2 1.5-2.5 4.5-1.5 7 .5 1.5 1 3 1.5 5 .5 2 1.5 3.5 2.5 3.5 1 0 1.5-1.5 2-3.5.3-1.2.7-2 2-2s1.7.8 2 2c.5 2 1 3.5 2 3.5 1 0 2-1.5 2.5-3.5.5-2 1-3.5 1.5-5 1-2.5.5-5.5-1.5-7-2-1.5-4.7-1.5-6.5 0z"></path>
                        </svg>
                      </div>
                    }
                    <div class="rx-doc-brand-text">
                      <h2 class="rx-doc-clinic-name">{{ letterhead().clinicName }}</h2>
                      <p class="rx-doc-clinic-sub">{{ letterhead().clinicSubtitle }}</p>
                      <p class="rx-doc-clinic-contact">
                        <span>{{ letterhead().address }}</span>
                        @if (letterhead().phone) {
                          <span> · <bdi dir="ltr">{{ letterhead().phone }}</bdi></span>
                        }
                        @if (letterhead().email) {
                          <span> · {{ letterhead().email }}</span>
                        }
                      </p>
                      @if (letterhead().licenseNumber) {
                        <p class="rx-doc-license" dir="ltr">License: {{ letterhead().licenseNumber }}</p>
                      }
                    </div>
                  </div>

                  <div class="rx-doc-meta" dir="ltr">
                    <div class="rx-doc-rxnum">{{ sheet.rxNumber }}</div>
                    <div class="rx-doc-date">{{ format.date(sheet.visitDate) }}</div>
                  </div>
                </header>
              } @else {
                <!-- Spacer for pre-printed clinic letterhead paper -->
                <div class="rx-preprinted-spacer">
                  <div class="rx-doc-meta" dir="ltr">
                    <div class="rx-doc-rxnum">{{ sheet.rxNumber }}</div>
                    <div class="rx-doc-date">{{ format.date(sheet.visitDate) }}</div>
                  </div>
                </div>
              }

              <!-- Patient & Prescriber Row -->
              <section class="rx-doc-parties">
                <div class="rx-doc-party">
                  <span class="rx-doc-label">{{ isAr() ? 'المريض' : 'Patient' }}</span>
                  <strong class="rx-doc-value">{{ sheet.patientName }}</strong>
                  @if (sheet.patientPhone) {
                    <span class="rx-doc-subvalue" dir="ltr">{{ sheet.patientPhone }}</span>
                  }
                </div>

                <div class="rx-doc-party rx-doc-party--end">
                  <span class="rx-doc-label">{{ isAr() ? 'الطبيب المعالج' : 'Prescribed by' }}</span>
                  <strong class="rx-doc-value">{{ sheet.doctorName || '—' }}</strong>
                  @if (sheet.doctorSpecialty) {
                    <span class="rx-doc-subvalue">{{ sheet.doctorSpecialty }}</span>
                  }
                </div>
              </section>

              <!-- Diagnosis Section -->
              <section class="rx-doc-diagnosis">
                <span class="rx-doc-label">{{ isAr() ? 'التشخيص الطبي' : 'Diagnosis' }}</span>
                <div class="rx-doc-diagnosis-val">{{ sheet.diagnosis || '—' }}</div>
              </section>

              <!-- Iconic Rx Symbol -->
              <div class="rx-doc-symbol-row" dir="ltr">
                <span class="rx-doc-symbol">℞</span>
              </div>

              <!-- Medications Table -->
              <section class="rx-doc-meds-section">
                <table class="rx-doc-table">
                  <thead>
                    <tr>
                      <th>{{ isAr() ? 'الدواء' : 'MEDICINE' }}</th>
                      <th>{{ isAr() ? 'الجرعة' : 'DOSAGE' }}</th>
                      <th>{{ isAr() ? 'التكرار' : 'FREQUENCY' }}</th>
                      <th>{{ isAr() ? 'المدة' : 'DURATION' }}</th>
                      <th>{{ isAr() ? 'التعليمات' : 'INSTRUCTIONS' }}</th>
                    </tr>
                  </thead>
                  <tbody>
                    @for (med of sheet.medications; track med.id) {
                      <tr>
                        <td class="rx-doc-med-name" dir="ltr">{{ med.medicationName }}</td>
                        <td dir="ltr">{{ med.dosage }}</td>
                        <td dir="ltr">{{ med.frequency }}</td>
                        <td dir="ltr">{{ med.duration }}</td>
                        <td>{{ med.instructions || '—' }}</td>
                      </tr>
                    }
                  </tbody>
                </table>
              </section>

              <!-- Doctor Notes & Default Clinic Instructions -->
              @if (sheet.notes || letterhead().defaultInstructions) {
                <section class="rx-doc-notes">
                  @if (sheet.notes) {
                    <div class="rx-doc-note-item">
                      <strong>{{ isAr() ? 'ملاحظات الطبيب:' : 'Doctor Notes:' }}</strong> {{ sheet.notes }}
                    </div>
                  }
                  @if (letterhead().defaultInstructions) {
                    <div class="rx-doc-note-item rx-doc-note-item--muted">
                      {{ letterhead().defaultInstructions }}
                    </div>
                  }
                </section>
              }

              <!-- Bottom Print Button & Doctor's Signature Block (Matches Screenshot 3) -->
              <footer class="rx-doc-footer">
                <div class="rx-doc-footer-top">
                  <div class="no-print">
                    <button
                      type="button"
                      class="rx-inline-print-btn"
                      (click)="triggerPrint()"
                    >
                      <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2">
                        <polyline points="6 9 6 2 18 2 18 9"></polyline>
                        <path d="M6 18H4a2 2 0 0 1-2-2v-5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v5a2 2 0 0 1-2 2h-2"></path>
                        <rect x="6" y="14" width="12" height="8"></rect>
                      </svg>
                      <span>{{ isAr() ? 'طباعة' : 'Print' }}</span>
                    </button>
                  </div>

                  <div class="rx-doc-signature-box">
                    <div class="rx-doc-signature-line"></div>
                    <span class="rx-doc-signature-label">{{ letterhead().signatureLabel }}</span>
                  </div>
                </div>

                @if (!letterhead().prePrintedPaper && letterhead().footerText) {
                  <div class="rx-doc-footer-note">
                    {{ letterhead().footerText }}
                  </div>
                }
              </footer>
            </article>
          </div>
        </div>
      }

      <!-- ===================================================================== -->
      <!-- MODAL 3: CUSTOMIZE CLINIC LETTERHEAD SETTINGS                         -->
      <!-- ===================================================================== -->
      @if (isLetterheadModalOpen()) {
        <div class="rx-modal-backdrop no-print" (click)="closeLetterheadModal()">
          <div
            class="rx-modal-card rx-modal-card--medium"
            data-testid="letterhead-settings-modal"
            (click)="$event.stopPropagation()"
          >
            <div class="rx-modal-header">
              <div>
                <h2 class="rx-modal-title">{{ isAr() ? 'تخصيص الورقة المروّسة للعيادة' : 'Clinic Letterhead Settings' }}</h2>
                <p class="rx-modal-sub">
                  {{ isAr()
                    ? 'عدّل ترويسة وشعار وبيانات العيادة المعتمدة التي تظهر في جميع الوصفات المطبوعة لهذه العيادة.'
                    : 'Configure the official clinic header, logo, contact information, and print template.'
                  }}
                </p>
              </div>
              <button type="button" class="rx-modal-close" (click)="closeLetterheadModal()">&times;</button>
            </div>

            <div class="rx-modal-body">
              <div class="rx-letterhead-form-grid">
                <div class="rx-field">
                  <label class="rx-label">{{ isAr() ? 'اسم العيادة الرسمي *' : 'Official Clinic Name *' }}</label>
                  <input
                    type="text"
                    class="rx-input"
                    [(ngModel)]="lhClinicName"
                    data-testid="lh-clinic-name-input"
                  />
                </div>

                <div class="rx-field">
                  <label class="rx-label">{{ isAr() ? 'الوصف / التخصص الفرعي' : 'Subtitle / Specialty' }}</label>
                  <input
                    type="text"
                    class="rx-input"
                    [(ngModel)]="lhClinicSubtitle"
                    data-testid="lh-clinic-subtitle-input"
                  />
                </div>

                <div class="rx-field rx-field--full">
                  <label class="rx-label">{{ isAr() ? 'العنوان التفصيلي' : 'Clinic Address' }}</label>
                  <input
                    type="text"
                    class="rx-input"
                    [(ngModel)]="lhAddress"
                    data-testid="lh-address-input"
                  />
                </div>

                <div class="rx-field">
                  <label class="rx-label">{{ isAr() ? 'رقم الهاتف' : 'Phone' }}</label>
                  <input
                    type="text"
                    class="rx-input"
                    dir="ltr"
                    [(ngModel)]="lhPhone"
                    data-testid="lh-phone-input"
                  />
                </div>

                <div class="rx-field">
                  <label class="rx-label">{{ isAr() ? 'رقم الواتساب / الطوارئ' : 'WhatsApp / Emergency' }}</label>
                  <input
                    type="text"
                    class="rx-input"
                    dir="ltr"
                    [(ngModel)]="lhWhatsapp"
                  />
                </div>

                <div class="rx-field">
                  <label class="rx-label">{{ isAr() ? 'البريد الإلكتروني' : 'Email' }}</label>
                  <input
                    type="email"
                    class="rx-input"
                    dir="ltr"
                    [(ngModel)]="lhEmail"
                  />
                </div>

                <div class="rx-field">
                  <label class="rx-label">{{ isAr() ? 'رقم الترخيص الطبي' : 'License Number' }}</label>
                  <input
                    type="text"
                    class="rx-input"
                    dir="ltr"
                    [(ngModel)]="lhLicenseNumber"
                  />
                </div>

                <div class="rx-field">
                  <label class="rx-label">{{ isAr() ? 'شعار العيادة (رفع صورة أو رابط)' : 'Clinic Logo (Upload or URL)' }}</label>
                  <div class="rx-logo-upload-row">
                    <input
                      type="text"
                      class="rx-input"
                      dir="ltr"
                      [placeholder]="isAr() ? 'https://... أو ارفع شعاراً' : 'https://... or upload logo'"
                      [(ngModel)]="lhLogoUrl"
                    />
                    <label class="fe-btn fe-btn--ghost fe-btn--sm rx-upload-label">
                      {{ isAr() ? 'رفع شعار' : 'Upload' }}
                      <input
                        type="file"
                        accept="image/*"
                         hidden
                        (change)="onLogoFileSelected($event)"
                      />
                    </label>
                    @if (lhLogoUrl) {
                      <button type="button" class="fe-btn fe-btn--ghost fe-btn--sm" (click)="lhLogoUrl = ''">
                        {{ isAr() ? 'إزالة' : 'Remove' }}
                      </button>
                    }
                  </div>
                </div>

                <div class="rx-field">
                  <label class="rx-label">{{ isAr() ? 'مقاس ورق الطباعة' : 'Paper Size' }}</label>
                  <select class="rx-select" [(ngModel)]="lhPaperSize">
                    <option value="A4">{{ isAr() ? 'A4 (صفحة قياسية كاملة)' : 'A4 (Standard full page)' }}</option>
                    <option value="A5">{{ isAr() ? 'A5 (نصف صفحة للوصفات الطبية)' : 'A5 (Half page - Rx standard)' }}</option>
                  </select>
                </div>

                <div class="rx-field rx-field--full">
                  <label class="rx-label">{{ isAr() ? 'التعليمات الطبية الافتراضية على الوصفة' : 'Default Prescription Instructions' }}</label>
                  <textarea
                    rows="2"
                    class="rx-textarea"
                    [(ngModel)]="lhDefaultInstructions"
                  ></textarea>
                </div>

                <div class="rx-field rx-field--full">
                  <label class="rx-label">{{ isAr() ? 'نص تذييل الورقة المروّسة' : 'Letterhead Footer Text' }}</label>
                  <input
                    type="text"
                    class="rx-input"
                    [(ngModel)]="lhFooterText"
                  />
                </div>

                <div class="rx-field">
                  <label class="rx-label">{{ isAr() ? 'عنوان خانة التوقيع' : 'Signature Field Label' }}</label>
                  <input
                    type="text"
                    class="rx-input"
                    [(ngModel)]="lhSignatureLabel"
                  />
                </div>

                <div class="rx-field rx-checkbox-field">
                  <label class="rx-checkbox-label">
                    <input type="checkbox" [(ngModel)]="lhPrePrintedPaper" />
                    <span>
                      <strong>{{ isAr() ? 'الطباعة على ورق مروّس مسبقاً' : 'Pre-printed Paper Mode' }}</strong>
                      <small>{{ isAr() ? 'إخفاء الترويسة العلوية والتذييل الرقمي عند الطباعة لمطابقة ورق المطبعة الجاهز.' : 'Hide digital header and footer when printing to use pre-printed physical stationery.' }}</small>
                    </span>
                  </label>
                </div>
              </div>
            </div>

            <div class="rx-modal-footer">
              <button type="button" class="fe-btn fe-btn--ghost" (click)="closeLetterheadModal()">
                {{ isAr() ? 'إلغاء' : 'Cancel' }}
              </button>
              <button
                type="button"
                class="fe-btn fe-btn--primary"
                data-testid="save-letterhead-btn"
                [disabled]="savingLetterhead()"
                (click)="saveLetterheadSettings()"
              >
                {{ savingLetterhead() ? (isAr() ? 'جاري الحفظ...' : 'Saving...') : (isAr() ? 'حفظ واعتماد الترويسة' : 'Save Letterhead') }}
              </button>
            </div>
          </div>
        </div>
      }

      <!-- ===================================================================== -->
      <!-- MODAL 4: CONFIRM DELETE PRESCRIPTION MODAL                            -->
      <!-- ===================================================================== -->
      @if (sheetToDelete(); as delSheet) {
        <div class="rx-modal-backdrop no-print" (click)="cancelDeleteSheet()">
          <div
            class="rx-modal-card rx-modal-card--confirm"
            data-testid="delete-confirmation-modal"
            (click)="$event.stopPropagation()"
          >
            <div class="rx-confirm-icon-box">
              <svg viewBox="0 0 24 24" width="32" height="32" fill="none" stroke="currentColor" stroke-width="2">
                <polyline points="3 6 5 6 21 6"></polyline>
                <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"></path>
                <line x1="10" y1="11" x2="10" y2="17"></line>
                <line x1="14" y1="11" x2="14" y2="17"></line>
              </svg>
            </div>
            <h2 class="rx-confirm-title">{{ isAr() ? 'تأكيد حذف الوصفة الطبية' : 'Confirm Delete Prescription' }}</h2>
            <p class="rx-confirm-sub">
              {{ isAr()
                ? ('هل أنت متأكد من رغبتك في حذف الوصفة رقم ' + delSheet.rxNumber + ' للمريض ' + delSheet.patientName + '؟')
                : ('Are you sure you want to delete prescription ' + delSheet.rxNumber + ' for patient ' + delSheet.patientName + '?')
              }}
            </p>
            <div class="rx-confirm-details">
              <div><strong>{{ isAr() ? 'التشخيص:' : 'Diagnosis:' }}</strong> {{ delSheet.diagnosis || '—' }}</div>
              <div><strong>{{ isAr() ? 'تاريخ الوصفة:' : 'Prescription Date:' }}</strong> {{ format.date(delSheet.visitDate) }}</div>
              <div><strong>{{ isAr() ? 'عدد الأدوية:' : 'Medications Count:' }}</strong> {{ delSheet.medications.length }}</div>
            </div>
            <div class="rx-confirm-alert">
              {{ isAr()
                ? 'تحذير: سيتم حذف جميع بنود الأدوية ضمن هذه الوصفة بشكل نهائي ولا يمكن التراجع عن هذا الإجراء.'
                : 'Warning: All medication items in this prescription will be permanently deleted. This cannot be undone.'
              }}
            </div>
            <div class="rx-confirm-buttons">
              <button
                type="button"
                class="fe-btn fe-btn--ghost"
                [disabled]="deleting()"
                (click)="cancelDeleteSheet()"
              >
                {{ isAr() ? 'إلغاء' : 'Cancel' }}
              </button>
              <button
                type="button"
                class="fe-btn fe-btn--danger"
                [disabled]="deleting()"
                (click)="executeDeleteSheet(delSheet)"
              >
                {{ deleting() ? (isAr() ? 'جاري الحذف...' : 'Deleting...') : (isAr() ? 'تأكيد الحذف النهائي' : 'Confirm Delete') }}
              </button>
            </div>
          </div>
        </div>
      }

      <!-- ===================================================================== -->
      <!-- MODAL 5: MEDICATIONS & PRESETS CATALOG (دليل الأدوية والاختصارات)       -->
      <!-- ===================================================================== -->
      @if (isPresetsModalOpen()) {
        <div class="rx-modal-backdrop no-print" (click)="closePresetsCatalogModal()">
          <div
            class="rx-modal-card rx-modal-card--wide"
            data-testid="presets-catalog-modal"
            (click)="$event.stopPropagation()"
          >
            <div class="rx-modal-header">
              <div>
                <h2 class="rx-modal-title">{{ isAr() ? 'دليل الأدوية والاختصارات السريعة' : 'Medications & Presets Catalog' }}</h2>
                <p class="rx-modal-sub">
                  {{ isAr()
                    ? 'إدارة الأدوية المعتمدة بالعيادة وجرعاتها الافتراضية، وتخصيص الاختصارات السريعة لتسهيل وصف العلاج.'
                    : 'Manage approved clinic medications, default dosages, and quick shortcuts for prescriptions.'
                  }}
                </p>
              </div>
              <button type="button" class="rx-modal-close" (click)="closePresetsCatalogModal()">&times;</button>
            </div>

            <!-- Tab Headers -->
            <div class="rx-catalog-tabs">
              <button
                type="button"
                class="rx-catalog-tab"
                [class.rx-catalog-tab--active]="activePresetsTab === 'medications'"
                (click)="activePresetsTab = 'medications'"
              >
                {{ isAr() ? ('أدوية العيادة والاختصارات (' + medicationPresets().length + ')') : ('Clinic Medications (' + medicationPresets().length + ')') }}
              </button>
              <button
                type="button"
                class="rx-catalog-tab"
                [class.rx-catalog-tab--active]="activePresetsTab === 'diagnoses'"
                (click)="activePresetsTab = 'diagnoses'"
              >
                {{ isAr() ? ('اختصارات التشخيصات (' + diagnosisPresets().length + ')') : ('Diagnosis Presets (' + diagnosisPresets().length + ')') }}
              </button>
            </div>

            <div class="rx-modal-body">
              @if (activePresetsTab === 'medications') {
                <div class="rx-catalog-layout">
                  <!-- Left: List -->
                  <div class="rx-catalog-list-col">
                    <div class="rx-catalog-filter-bar">
                      <input
                        type="search"
                        class="rx-input rx-input--sm"
                        [placeholder]="isAr() ? 'بحث في الأدوية المحفوظة...' : 'Search saved medications...'"
                        [(ngModel)]="presetMedSearchQuery"
                      />
                      <select class="rx-select rx-select--sm" [(ngModel)]="presetMedCategoryFilter">
                        <option value="">{{ isAr() ? 'كل التصنيفات' : 'All Categories' }}</option>
                        @for (cat of medCategories(); track cat) {
                          <option [value]="cat">{{ cat }}</option>
                        }
                      </select>
                    </div>

                    <div class="rx-presets-table-wrap">
                      <table class="rx-table rx-table--compact">
                        <thead>
                          <tr>
                            <th>{{ isAr() ? 'الدواء' : 'Medicine' }}</th>
                            <th>{{ isAr() ? 'الجرعة' : 'Dosage' }}</th>
                            <th>{{ isAr() ? 'التكرار' : 'Frequency' }}</th>
                            <th>{{ isAr() ? 'المدة' : 'Duration' }}</th>
                            <th>{{ isAr() ? 'مفضل' : 'Favorite' }}</th>
                            <th>{{ isAr() ? 'حذف' : 'Del' }}</th>
                          </tr>
                        </thead>
                        <tbody>
                          @for (p of filteredMedPresets(); track p.id) {
                            <tr>
                              <td>
                                <strong dir="ltr">{{ p.medicationName }}</strong>
                                <div class="rx-cell-sub">{{ p.category }}</div>
                              </td>
                              <td dir="ltr">{{ p.dosage }}</td>
                              <td dir="ltr">{{ p.frequency }}</td>
                              <td dir="ltr">{{ p.duration }}</td>
                              <td>
                                <span
                                  class="rx-fav-badge"
                                  [class.rx-fav-badge--on]="p.isFavorite"
                                  [title]="p.isFavorite ? (isAr() ? 'يظهر في شريط الاختصارات السريعة' : 'Pinned to quick presets') : (isAr() ? 'غير مثبت' : 'Not pinned')"
                                >
                                  {{ p.isFavorite ? (isAr() ? '★ مثبت' : '★ Pinned') : '☆' }}
                                </span>
                              </td>
                              <td>
                                <button
                                  type="button"
                                  class="rx-del-row-btn"
                                  [title]="isAr() ? 'حذف الدواء من الدليل' : 'Delete medicine from catalog'"
                                  (click)="deleteMedicationPreset(p.id)"
                                >
                                  &times;
                                </button>
                              </td>
                            </tr>
                          }
                        </tbody>
                      </table>
                    </div>
                  </div>

                  <!-- Right: Add New Medication Form -->
                  <div class="rx-catalog-form-col">
                    <div class="rx-form-card">
                      <h4 class="rx-form-card-title">{{ isAr() ? '+ إضافة دواء جديد لدليل العيادة' : '+ Add New Medicine to Catalog' }}</h4>
                      <div class="rx-form-stack rx-form-stack--sm">
                        <div class="rx-field">
                          <label class="rx-label">{{ isAr() ? 'اسم الدواء والعيار *' : 'Medicine Name & Strength *' }}</label>
                          <input
                            type="text"
                            class="rx-input"
                            [(ngModel)]="newMedName"
                            [placeholder]="isAr() ? 'مثال: Augmentin 1g' : 'e.g., Augmentin 1g'"
                          />
                        </div>
                        <div class="rx-field">
                          <label class="rx-label">{{ isAr() ? 'الجرعة الافتراضية' : 'Default Dosage' }}</label>
                          <input
                            type="text"
                            class="rx-input"
                            [(ngModel)]="newMedDosage"
                            [placeholder]="isAr() ? 'مثال: 1 قرص' : 'e.g., 1 tablet / 1 capsule'"
                          />
                        </div>
                        <div class="rx-field">
                          <label class="rx-label">{{ isAr() ? 'التكرار الافتراضي' : 'Default Frequency' }}</label>
                          <input
                            type="text"
                            class="rx-input"
                            [(ngModel)]="newMedFrequency"
                            [placeholder]="isAr() ? 'مثال: مرتين يومياً / كل 12 ساعة' : 'e.g., Twice a day'"
                          />
                        </div>
                        <div class="rx-field">
                          <label class="rx-label">{{ isAr() ? 'المدة الافتراضية' : 'Default Duration' }}</label>
                          <input
                            type="text"
                            class="rx-input"
                            [(ngModel)]="newMedDuration"
                            [placeholder]="isAr() ? 'مثال: 5 أيام' : 'e.g., 5 days'"
                          />
                        </div>
                        <div class="rx-field">
                          <label class="rx-label">{{ isAr() ? 'التعليمات الافتراضية' : 'Default Instructions' }}</label>
                          <input
                            type="text"
                            class="rx-input"
                            [(ngModel)]="newMedInstructions"
                            [placeholder]="isAr() ? 'مثال: بعد الأكل مع كوب ماء كبير' : 'e.g., After meals with water'"
                          />
                        </div>
                        <div class="rx-field">
                          <label class="rx-label">{{ isAr() ? 'التصنيف' : 'Category' }}</label>
                          <input
                            type="text"
                            class="rx-input"
                            list="med-category-options"
                            [(ngModel)]="newMedCategory"
                            [placeholder]="isAr() ? 'المضادات الحيوية / المسكنات...' : 'Antibiotics / NSAIDs...'"
                          />
                          <datalist id="med-category-options">
                            <option [value]="isAr() ? 'المضادات الحيوية (Antibiotics)' : 'Antibiotics'"></option>
                            <option [value]="isAr() ? 'مسكنات ومضادات التهاب (NSAIDs)' : 'NSAIDs'"></option>
                            <option [value]="isAr() ? 'مسكنات وخافضات حرارة (Analgesics)' : 'Analgesics'"></option>
                            <option [value]="isAr() ? 'مضامض وغسول فم (Mouthwash)' : 'Mouthwash'"></option>
                            <option [value]="isAr() ? 'الستيرويدات ومضادات التورم' : 'Steroids'"></option>
                            <option [value]="isAr() ? 'فيتامينات ومكملات' : 'Supplements'"></option>
                          </datalist>
                        </div>
                        <div class="rx-field rx-field--checkbox">
                          <label class="rx-checkbox-label">
                            <input type="checkbox" [(ngModel)]="newMedIsFavorite" />
                            <span>{{ isAr() ? 'تثبيت في شريط الاختصارات السريعة للوصفة' : 'Pin to quick prescription shortcuts' }}</span>
                          </label>
                        </div>
                        <button
                          type="button"
                          class="fe-btn fe-btn--primary"
                          [disabled]="!newMedName.trim() || savingMedPreset()"
                          (click)="saveNewMedicationPreset()"
                        >
                          {{ savingMedPreset() ? (isAr() ? 'جاري الحفظ...' : 'Saving...') : (isAr() ? '+ حفظ الدواء في الدليل' : '+ Save Medicine to Catalog') }}
                        </button>
                      </div>
                    </div>
                  </div>
                </div>
              } @else {
                <!-- Diagnosis Presets Tab Content -->
                <div class="rx-catalog-layout">
                  <div class="rx-catalog-list-col">
                    <div class="rx-presets-table-wrap">
                      <table class="rx-table rx-table--compact">
                        <thead>
                          <tr>
                            <th>{{ isAr() ? 'التشخيص السريري' : 'Clinical Diagnosis' }}</th>
                            <th>{{ isAr() ? 'الرمز الطبي' : 'Medical Code' }}</th>
                            <th>{{ isAr() ? 'حذف' : 'Del' }}</th>
                          </tr>
                        </thead>
                        <tbody>
                          @for (d of diagnosisPresets(); track d.id) {
                            <tr>
                              <td><strong>{{ d.name }}</strong></td>
                              <td><span class="rx-code-badge" dir="ltr">{{ d.code || '—' }}</span></td>
                              <td>
                                <button
                                  type="button"
                                  class="rx-del-row-btn"
                                  [title]="isAr() ? 'حذف الاختصار' : 'Delete preset'"
                                  (click)="deleteDiagnosisPreset(d.id)"
                                >
                                  &times;
                                </button>
                              </td>
                            </tr>
                          }
                        </tbody>
                      </table>
                    </div>
                  </div>

                  <div class="rx-catalog-form-col">
                    <div class="rx-form-card">
                      <h4 class="rx-form-card-title">{{ isAr() ? '+ إضافة اختصار تشخيص جديد' : '+ Add Diagnosis Preset' }}</h4>
                      <div class="rx-form-stack rx-form-stack--sm">
                        <div class="rx-field">
                          <label class="rx-label">{{ isAr() ? 'اسم التشخيص *' : 'Diagnosis Name *' }}</label>
                          <input
                            type="text"
                            class="rx-input"
                            [(ngModel)]="newDiagName"
                            [placeholder]="isAr() ? 'مثال: Pulpitis · التهاب عصب السن' : 'e.g., Pulpitis'"
                          />
                        </div>
                        <div class="rx-field">
                          <label class="rx-label">{{ isAr() ? 'الرمز الطبي الاختياري (Code - ICD/Dental)' : 'Optional Code (ICD / Dental)' }}</label>
                          <input
                            type="text"
                            class="rx-input"
                            dir="ltr"
                            [(ngModel)]="newDiagCode"
                            placeholder="مثال: K04.0"
                          />
                        </div>
                        <button
                          type="button"
                          class="fe-btn fe-btn--primary"
                          [disabled]="!newDiagName.trim() || savingDiagPreset()"
                          (click)="saveNewDiagnosisPreset()"
                        >
                          {{ savingDiagPreset() ? (isAr() ? 'جاري الحفظ...' : 'Saving...') : (isAr() ? '+ إضافة التشخيص' : '+ Add Diagnosis') }}
                        </button>
                      </div>
                    </div>
                  </div>
                </div>
              }
            </div>

            <div class="rx-modal-footer">
              <button type="button" class="fe-btn fe-btn--ghost" (click)="closePresetsCatalogModal()">
                {{ isAr() ? 'إغلاق' : 'Close' }}
              </button>
            </div>
          </div>
        </div>
      }

      <!-- ===================================================================== -->
      <!-- MODAL 6: QUICK ADD MEDICATION MODAL (سريع من داخل الوصفة)             -->
      <!-- ===================================================================== -->
      @if (isQuickAddMedModalOpen()) {
        <div class="rx-modal-backdrop no-print" (click)="closeQuickAddMedModal()">
          <div
            class="rx-modal-card rx-modal-card--small"
            data-testid="quick-add-med-modal"
            (click)="$event.stopPropagation()"
          >
            <div class="rx-modal-header">
              <div>
                <h3 class="rx-modal-title">{{ isAr() ? 'إضافة دواء جديد للاختصارات' : 'Quick Add Medication' }}</h3>
                <p class="rx-modal-sub">{{ isAr() ? 'سيتم حفظ الدواء في دليل العيادة وشريط الاختصارات السريعة فوراً.' : 'Medicine will be saved to clinic catalog and quick shortcuts immediately.' }}</p>
              </div>
              <button type="button" class="rx-modal-close" (click)="closeQuickAddMedModal()">&times;</button>
            </div>

            <div class="rx-modal-body">
              <div class="rx-form-stack rx-form-stack--sm">
                <div class="rx-field">
                  <label class="rx-label">{{ isAr() ? 'اسم الدواء *' : 'Medicine Name *' }}</label>
                  <input
                    type="text"
                    class="rx-input"
                    [(ngModel)]="quickAddMedName"
                    [placeholder]="isAr() ? 'مثال: Cataflam 50mg' : 'e.g., Cataflam 50mg'"
                  />
                </div>
                <div class="rx-field">
                  <label class="rx-label">{{ isAr() ? 'الجرعة الافتراضية' : 'Default Dosage' }}</label>
                  <input
                    type="text"
                    class="rx-input"
                    [(ngModel)]="quickAddMedDosage"
                    [placeholder]="isAr() ? 'مثال: 1 قرص' : 'e.g., 1 tablet'"
                  />
                </div>
                <div class="rx-field">
                  <label class="rx-label">{{ isAr() ? 'التكرار' : 'Frequency' }}</label>
                  <input
                    type="text"
                    class="rx-input"
                    [(ngModel)]="quickAddMedFrequency"
                    [placeholder]="isAr() ? 'مثال: مرتين يومياً' : 'e.g., Twice a day'"
                  />
                </div>
                <div class="rx-field">
                  <label class="rx-label">{{ isAr() ? 'المدة' : 'Duration' }}</label>
                  <input
                    type="text"
                    class="rx-input"
                    [(ngModel)]="quickAddMedDuration"
                    [placeholder]="isAr() ? 'مثال: 3 أيام' : 'e.g., 3 days'"
                  />
                </div>
                <div class="rx-field">
                  <label class="rx-label">{{ isAr() ? 'التعليمات' : 'Instructions' }}</label>
                  <input
                    type="text"
                    class="rx-input"
                    [(ngModel)]="quickAddMedInstructions"
                    [placeholder]="isAr() ? 'مثال: بعد الأكل عند اللزوم' : 'e.g., After meals as needed'"
                  />
                </div>
              </div>
            </div>

            <div class="rx-modal-footer">
              <button type="button" class="fe-btn fe-btn--ghost" (click)="closeQuickAddMedModal()">
                {{ isAr() ? 'إلغاء' : 'Cancel' }}
              </button>
              <button
                type="button"
                class="fe-btn fe-btn--primary"
                [disabled]="!quickAddMedName.trim() || savingQuickAddMed()"
                (click)="submitQuickAddMed()"
              >
                {{ savingQuickAddMed() ? (isAr() ? 'جاري الحفظ...' : 'Saving...') : (isAr() ? 'حفظ وإدراج في الوصفة' : 'Save & Insert into Rx') }}
              </button>
            </div>
          </div>
        </div>
      }
    </div>
  `,
  styles: [
    `
      :host {
        display: flex !important;
        flex-direction: column !important;
        flex: 1 1 100% !important;
        height: 100% !important;
        max-height: 100% !important;
        min-height: 0 !important;
        overflow: hidden !important;
        box-sizing: border-box !important;
      }
      .rx-page {
        display: flex !important;
        flex-direction: column !important;
        flex: 1 1 100% !important;
        height: 100% !important;
        max-height: 100% !important;
        min-height: 0 !important;
        overflow: hidden !important;
        padding: 0.75rem 1.25rem 0.5rem !important;
        gap: 0.55rem !important;
        max-width: 1500px;
        margin: 0 auto;
        width: 100%;
        box-sizing: border-box !important;
      }
      .rx-page-header {
        flex-shrink: 0;
        display: flex;
        justify-content: space-between;
        align-items: center;
        flex-wrap: wrap;
        gap: 0.6rem;
        position: relative;
        z-index: 50;
        overflow: visible;
      }
      .rx-eyebrow {
        font-size: 0.7rem;
        font-weight: 700;
        letter-spacing: 0.05em;
        color: var(--pri, #009ca6);
        text-transform: uppercase;
        margin-bottom: 0.1rem;
      }
      .rx-title-row {
        display: flex;
        align-items: center;
        gap: 8px;
      }
      .rx-title {
        margin: 0;
        font-size: 1.35rem;
        font-weight: 800;
        color: var(--ink, #0f172a);
        line-height: 1.2;
      }
      .rx-header-actions {
        display: flex;
        align-items: center;
        gap: 0.5rem;
        flex-wrap: wrap;
      }
      .rx-banner {
        flex-shrink: 0;
        display: flex;
        justify-content: space-between;
        align-items: center;
        padding: 0.6rem 0.9rem;
        border-radius: 3px;
        font-size: 0.85rem;
        font-weight: 600;
      }
      .rx-banner--success {
        background: var(--ok-soft, rgba(16, 185, 129, 0.12));
        border: 1px solid var(--ok, rgba(16, 185, 129, 0.35));
        color: var(--ok, #065f46);
      }
      .rx-banner--error {
        background: var(--bad-soft, rgba(239, 68, 68, 0.12));
        border: 1px solid var(--bad, rgba(239, 68, 68, 0.35));
        color: var(--bad, #991b1b);
      }
      .rx-banner-close {
        background: none;
        border: none;
        font-size: 1.15rem;
        cursor: pointer;
        color: inherit;
      }
      .rx-card {
        background: var(--surf, #ffffff);
        border: 1px solid var(--line, #e2e8f0);
        border-radius: 4px;
        box-shadow: var(--shadow-xs);
      }
      .rx-toolbar-card {
        flex-shrink: 0;
        padding: 0.55rem 0.85rem;
      }
      .rx-toolbar {
        display: flex;
        justify-content: space-between;
        align-items: center;
        gap: 0.75rem;
        flex-wrap: wrap;
      }
      .rx-search-box {
        position: relative;
        flex: 1 1 300px;
      }
      .rx-search-icon {
        position: absolute;
        inset-inline-start: 0.85rem;
        top: 50%;
        transform: translateY(-50%);
        color: var(--mute, #64748b);
        pointer-events: none;
      }
      .rx-search-input {
        padding-inline-start: 2.3rem !important;
        height: 36px;
        border-radius: 2px !important;
      }
      .rx-filter-group {
        display: flex;
        align-items: center;
        gap: 0.55rem;
        flex-wrap: wrap;
      }
      .rx-table-card {
        flex: 1 1 0% !important;
        min-height: 0 !important;
        display: flex !important;
        flex-direction: column !important;
        overflow: hidden !important;
      }
      .rx-card-head {
        flex-shrink: 0;
        display: flex;
        justify-content: space-between;
        align-items: center;
        padding: 0.65rem 1rem;
        border-bottom: 1px solid var(--line, #e2e8f0);
      }
      .rx-card-title-wrap {
        display: flex;
        align-items: center;
        gap: 0.55rem;
      }
      .rx-pill-icon {
        display: inline-flex;
        align-items: center;
        justify-content: center;
        width: 26px;
        height: 26px;
        border-radius: 2px;
        background: rgba(0, 156, 166, 0.12);
        color: var(--pri, #009ca6);
        font-weight: 800;
        font-size: 0.95rem;
      }
      .rx-card-title {
        margin: 0;
        font-size: 0.95rem;
        font-weight: 700;
        color: var(--ink, #0f172a);
      }
      .rx-count-badge {
        background: var(--surf2, #f1f5f9);
        color: var(--ink, #0f172a);
        font-size: 0.74rem;
        font-weight: 700;
        padding: 0.12rem 0.5rem;
        border-radius: 2px;
      }
      .rx-table-wrap {
        flex: 1 1 0% !important;
        min-height: 0 !important;
        overflow-y: auto !important;
        overflow-x: auto !important;
        scroll-behavior: smooth;
      }
      .rx-table-wrap::-webkit-scrollbar {
        width: 6px;
        height: 6px;
      }
      .rx-table-wrap::-webkit-scrollbar-track {
        background: transparent;
      }
      .rx-table-wrap::-webkit-scrollbar-thumb {
        background: var(--line, #cbd5e1);
        border-radius: 4px;
      }
      .rx-table-wrap::-webkit-scrollbar-thumb:hover {
        background: var(--mute, #94a3b8);
      }
      .rx-table {
        width: 100%;
        border-collapse: collapse;
        text-align: start;
      }
      .rx-table th {
        position: sticky;
        top: 0;
        z-index: 5;
        padding: 10px 14px;
        font-size: 11px;
        font-weight: 700;
        text-transform: uppercase;
        letter-spacing: 0.05em;
        color: var(--mute, #64748b);
        background: var(--surf2, #f8fafc);
        border-bottom: 1px solid var(--line, #e2e8f0);
        box-shadow: 0 1px 0 var(--line, #e2e8f0);
        white-space: nowrap;
      }
      .rx-table td {
        padding: 10px 14px;
        border-bottom: 1px solid var(--line, #e2e8f0);
        vertical-align: middle;
        font-size: 12.5px;
        color: var(--ink, #0f172a);
        line-height: 1.4;
      }
      .rx-table tbody tr:hover {
        background: var(--surf2);
      }
      .rx-select option {
        background: var(--surf, #ffffff);
        color: var(--ink, #0f172a);
      }
      .rx-code-badge {
        display: inline-block;
        font-family: var(--fe-font-mono, monospace);
        font-weight: 700;
        font-size: 11.5px;
        color: var(--pri, #009ca6);
        background: rgba(0, 156, 166, 0.12);
        padding: 2px 6px;
        border-radius: 2px;
      }
      .rx-patient-cell,
      .rx-doctor-cell {
        display: flex;
        flex-direction: column;
        gap: 1px;
      }
      .rx-patient-link {
        color: var(--ink, #0f172a);
        font-weight: 600;
        font-size: 12.5px;
        text-decoration: none;
      }
      .rx-doctor-name {
        font-size: 12.5px;
        font-weight: 600;
        color: var(--ink, #0f172a);
      }
      .rx-patient-link:hover {
        color: var(--pri, #009ca6);
        text-decoration: underline;
      }
      .rx-cell-sub {
        font-size: 11px;
        color: var(--mute, #64748b);
      }
      .rx-diagnosis-text {
        font-weight: 500;
        font-size: 12.5px;
        color: var(--ink, #1e293b);
      }
      .rx-meds-pills {
        display: flex;
        flex-wrap: wrap;
        gap: 0.35rem;
      }
      .rx-med-chip {
        font-size: 11.5px;
        font-weight: 600;
        padding: 2px 6px;
        border-radius: 2px;
        background: var(--surf2, #f1f5f9);
        border: 1px solid var(--line, #e2e8f0);
        color: var(--ink, #334155);
      }
      .rx-med-more {
        font-size: 11px;
        font-weight: 700;
        color: var(--pri, #009ca6);
        padding: 2px 4px;
      }
      .rx-col-actions {
        text-align: end;
      }
      .rx-row-actions {
        display: inline-flex;
        align-items: center;
        gap: 0.35rem;
      }
      .rx-action-btn {
        display: inline-flex;
        align-items: center;
        justify-content: center;
        width: 26px;
        height: 26px;
        border-radius: 2px;
        border: 1px solid transparent;
        cursor: pointer;
        transition: all 0.15s ease;
      }
      .rx-action-btn--view {
        background: var(--pri, #009ca6);
        color: #ffffff;
      }
      .rx-action-btn--view:hover {
        opacity: 0.9;
      }
      .rx-action-btn--delete {
        background: var(--accent-red-soft, rgba(239, 68, 68, 0.1));
        color: var(--accent-red-ink, #dc2626);
        border-color: rgba(239, 68, 68, 0.25);
      }
      .rx-action-btn--delete:hover {
        background: #dc2626;
        color: #ffffff;
      }
      .rx-empty-state {
        padding: 3.5rem 1.5rem;
        text-align: center;
        color: var(--mute, #64748b);
      }
      .rx-empty-icon {
        font-size: 2.5rem;
        font-weight: 800;
        color: var(--pri, #009ca6);
        margin-bottom: 0.5rem;
      }

      /* Form Controls */
      .rx-input,
      .rx-select,
      .rx-textarea {
        width: 100%;
        padding: 0.58rem 0.8rem;
        border: 1px solid var(--line, #cbd5e1);
        border-radius: 9px;
        background: var(--surf, #ffffff);
        color: var(--ink, #0f172a);
        font-size: 0.9rem;
        font-family: inherit;
        box-sizing: border-box;
      }
      .rx-input--sm {
        padding: 0.45rem 0.6rem;
        font-size: 0.84rem;
      }
      .rx-input:focus,
      .rx-select:focus,
      .rx-textarea:focus {
        outline: 2px solid var(--pri, #009ca6);
        border-color: var(--pri, #009ca6);
      }
      .rx-label {
        display: block;
        font-size: 0.82rem;
        font-weight: 600;
        color: var(--ink, #334155);
        margin-bottom: 0.35rem;
      }

      /* Modals */
      .rx-modal-backdrop {
        position: fixed;
        inset: 0;
        background: rgba(15, 23, 42, 0.65);
        backdrop-filter: blur(4px);
        display: flex;
        align-items: center;
        justify-content: center;
        padding: 1.25rem;
        z-index: 1100;
        overflow-y: auto;
      }
      .rx-modal-card {
        background: var(--surf, #ffffff);
        border: 1px solid var(--line, #e2e8f0);
        border-radius: 16px;
        width: 100%;
        max-height: 92vh;
        display: flex;
        flex-direction: column;
        box-shadow: 0 20px 45px rgba(0, 0, 0, 0.4);
      }
      .rx-modal-card--wide {
        max-width: 1180px;
      }
      .rx-modal-card--medium {
        max-width: 820px;
      }
      .rx-modal-header {
        display: flex;
        justify-content: space-between;
        align-items: flex-start;
        padding: 1.25rem 1.5rem;
        border-bottom: 1px solid var(--line, #e2e8f0);
      }
      .rx-modal-title {
        margin: 0;
        font-size: 1.2rem;
        font-weight: 800;
        color: var(--ink, #0f172a);
      }
      .rx-modal-sub {
        margin: 0.2rem 0 0;
        font-size: 0.85rem;
        color: var(--mute, #64748b);
      }
      .rx-modal-close {
        background: none;
        border: none;
        font-size: 1.6rem;
        line-height: 1;
        color: var(--mute, #64748b);
        cursor: pointer;
      }
      .rx-modal-body {
        padding: 1.35rem 1.5rem;
        overflow-y: auto;
      }
      .rx-modal-footer {
        display: flex;
        justify-content: flex-end;
        gap: 0.75rem;
        padding: 1rem 1.5rem;
        border-top: 1px solid var(--line, #e2e8f0);
      }

      /* New Prescription 2-Column Layout */
      .rx-new-grid {
        display: grid;
        grid-template-columns: 340px 1fr;
        gap: 1.25rem;
        align-items: start;
      }
      @media (max-width: 960px) {
        .rx-new-grid {
          grid-template-columns: 1fr;
        }
      }
      .rx-panel {
        background: var(--surf2, #f8fafc);
        border: 1px solid var(--line, #e2e8f0);
        border-radius: 12px;
        padding: 1.15rem;
      }
      .rx-panel-head {
        display: flex;
        align-items: center;
        gap: 0.55rem;
        margin-bottom: 1rem;
      }
      .rx-panel-head--between {
        justify-content: space-between;
      }
      .rx-panel-head-left {
        display: flex;
        align-items: center;
        gap: 0.55rem;
      }
      .rx-panel-badge {
        display: inline-flex;
        align-items: center;
        justify-content: center;
        width: 24px;
        height: 24px;
        border-radius: 6px;
        background: var(--pri, #009ca6);
        color: #ffffff;
        font-size: 0.8rem;
        font-weight: 700;
      }
      .rx-panel-title {
        margin: 0;
        font-size: 0.98rem;
        font-weight: 700;
        color: var(--ink, #0f172a);
      }
      .rx-form-stack {
        display: flex;
        flex-direction: column;
        gap: 0.85rem;
      }
      .rx-med-rows {
        display: flex;
        flex-direction: column;
        gap: 0.55rem;
      }
      .rx-med-rows-header {
        display: grid;
        grid-template-columns: 1.5fr 0.9fr 1.1fr 0.85fr 1.2fr 34px;
        gap: 0.45rem;
        font-size: 0.74rem;
        font-weight: 700;
        color: var(--mute, #64748b);
        padding: 0 0.25rem;
      }
      .rx-med-row-item {
        display: grid;
        grid-template-columns: 1.5fr 0.9fr 1.1fr 0.85fr 1.2fr 34px;
        gap: 0.45rem;
        align-items: center;
      }
      .rx-del-row-btn {
        width: 32px;
        height: 32px;
        border-radius: 7px;
        border: 1px solid rgba(239, 68, 68, 0.3);
        background: rgba(239, 68, 68, 0.08);
        color: #dc2626;
        font-size: 1.1rem;
        cursor: pointer;
      }
      .rx-del-row-btn:disabled {
        opacity: 0.35;
        cursor: not-allowed;
      }
      .rx-med-quick-presets {
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        gap: 0.4rem;
        margin-top: 1rem;
        padding-top: 0.85rem;
        border-top: 1px dashed var(--line, #cbd5e1);
      }
      .rx-presets-label {
        font-size: 0.78rem;
        font-weight: 600;
        color: var(--mute, #64748b);
      }
      .rx-preset-chip {
        font-size: 0.76rem;
        font-weight: 600;
        padding: 0.25rem 0.6rem;
        border-radius: 999px;
        border: 1px solid rgba(0, 156, 166, 0.35);
        background: rgba(0, 156, 166, 0.08);
        color: var(--pri, #009ca6);
        cursor: pointer;
      }
      .rx-preset-chip:hover {
        background: var(--pri, #009ca6);
        color: #ffffff;
      }

      /* Printable Sheet Modal & Official Prescription Document */
      .rx-sheet-wrapper {
        width: 100%;
        max-width: 860px;
        display: flex;
        flex-direction: column;
        gap: 0.85rem;
        max-height: 94vh;
        overflow-y: auto;
      }
      .rx-sheet-wrapper--a5 {
        max-width: 650px;
      }
      .rx-sheet-toolbar {
        display: flex;
        justify-content: space-between;
        align-items: center;
        flex-wrap: wrap;
        gap: 0.75rem;
        background: var(--surf, #ffffff);
        padding: 0.85rem 1.25rem;
        border-radius: 12px;
        border: 1px solid var(--line, #e2e8f0);
      }
      .rx-sheet-toolbar-left {
        display: flex;
        align-items: center;
        gap: 0.75rem;
      }
      .rx-toolbar-hint {
        font-size: 0.82rem;
        color: var(--mute, #64748b);
      }
      .rx-sheet-toolbar-actions {
        display: flex;
        align-items: center;
        gap: 0.5rem;
      }

      .rx-document {
        background: var(--surf, #ffffff);
        color: var(--ink, #0f172a);
        border-radius: 16px;
        padding: 2.5rem 2.75rem;
        box-shadow: 0 20px 50px rgba(0, 0, 0, 0.35);
        border: 1px solid var(--line, #e2e8f0);
      }
      .rx-doc-letterhead {
        display: flex;
        justify-content: space-between;
        align-items: flex-start;
        gap: 1.5rem;
        padding-bottom: 1.5rem;
        border-bottom: 2px solid var(--pri, #009ca6);
        margin-bottom: 1.5rem;
      }
      .rx-preprinted-spacer {
        padding-top: 3.5rem;
        padding-bottom: 1rem;
        border-bottom: 1px solid var(--line, #e2e8f0);
        margin-bottom: 1.5rem;
        display: flex;
        justify-content: flex-end;
      }
      .rx-doc-brand {
        display: flex;
        align-items: flex-start;
        gap: 1rem;
      }
      .rx-doc-logo-box {
        width: 52px;
        height: 52px;
        border-radius: 12px;
        background: linear-gradient(135deg, var(--pri, #009ca6), #0284c7);
        color: #ffffff;
        display: flex;
        align-items: center;
        justify-content: center;
        flex-shrink: 0;
      }
      .rx-doc-logo-img {
        width: 56px;
        height: 56px;
        object-fit: contain;
        border-radius: 10px;
      }
      .rx-doc-clinic-name {
        margin: 0;
        font-size: 1.35rem;
        font-weight: 800;
        color: var(--ink, #0f172a);
      }
      .rx-doc-clinic-sub {
        margin: 0.2rem 0 0;
        font-size: 0.88rem;
        font-weight: 600;
        color: var(--pri, #009ca6);
      }
      .rx-doc-clinic-contact {
        margin: 0.3rem 0 0;
        font-size: 0.82rem;
        color: var(--mute, #64748b);
      }
      .rx-doc-license {
        margin: 0.2rem 0 0;
        font-size: 0.75rem;
        font-weight: 600;
        color: var(--mute, #475569);
      }
      .rx-doc-meta {
        text-align: right;
        flex-shrink: 0;
      }
      .rx-doc-rxnum {
        font-size: 1.15rem;
        font-weight: 800;
        color: var(--ink, #0f172a);
      }
      .rx-doc-date {
        font-size: 0.85rem;
        color: var(--mute, #64748b);
        margin-top: 0.2rem;
      }

      .rx-doc-parties {
        display: flex;
        justify-content: space-between;
        gap: 1.5rem;
        margin-bottom: 1.35rem;
      }
      .rx-doc-party {
        display: flex;
        flex-direction: column;
        gap: 0.15rem;
      }
      .rx-doc-party--end {
        text-align: end;
      }
      .rx-doc-label {
        font-size: 0.78rem;
        font-weight: 600;
        color: var(--mute, #64748b);
      }
      .rx-doc-value {
        font-size: 1.05rem;
        font-weight: 800;
        color: var(--ink, #0f172a);
      }
      .rx-doc-subvalue {
        font-size: 0.8rem;
        color: var(--mute, #64748b);
      }

      .rx-doc-diagnosis {
        margin-bottom: 1.25rem;
        padding: 0.75rem 1rem;
        background: var(--surf2, #f8fafc);
        border-radius: 10px;
        border-inline-start: 4px solid var(--pri, #009ca6);
      }
      .rx-doc-diagnosis-val {
        margin-top: 0.2rem;
        font-size: 1rem;
        font-weight: 700;
        color: var(--ink, #0f172a);
      }

      .rx-doc-symbol-row {
        margin: 0.75rem 0 0.5rem;
      }
      .rx-doc-symbol {
        font-size: 2rem;
        font-weight: 900;
        color: var(--pri, #009ca6);
        font-family: Georgia, serif;
      }

      .rx-doc-table {
        width: 100%;
        border-collapse: collapse;
        margin-bottom: 1.75rem;
      }
      .rx-doc-table th {
        padding: 0.7rem 0.75rem;
        font-size: 0.74rem;
        font-weight: 700;
        text-transform: uppercase;
        letter-spacing: 0.05em;
        color: var(--mute, #64748b);
        border-bottom: 2px solid var(--line, #e2e8f0);
        text-align: start;
      }
      .rx-doc-table td {
        padding: 1rem 0.75rem;
        border-bottom: 1px solid var(--line, #e2e8f0);
        font-size: 0.94rem;
        color: var(--ink, #1e293b);
      }
      .rx-doc-med-name {
        font-weight: 800;
        color: var(--ink, #0f172a);
      }

      .rx-doc-notes {
        margin-bottom: 2rem;
        padding: 0.85rem 1rem;
        background: var(--surf2, #f8fafc);
        border-radius: 8px;
        font-size: 0.86rem;
        color: var(--ink, #334155);
        display: flex;
        flex-direction: column;
        gap: 0.4rem;
      }
      .rx-doc-note-item--muted {
        color: var(--mute, #64748b);
        font-size: 0.8rem;
      }

      .rx-doc-footer {
        margin-top: 2.5rem;
        padding-top: 1.25rem;
      }
      .rx-doc-footer-top {
        display: flex;
        justify-content: space-between;
        align-items: flex-end;
        gap: 1.5rem;
      }
      .rx-inline-print-btn {
        display: inline-flex;
        align-items: center;
        gap: 0.5rem;
        padding: 0.6rem 1.2rem;
        border-radius: 9px;
        border: 1px solid var(--line, #cbd5e1);
        background: var(--surf, #ffffff);
        color: var(--pri, #009ca6);
        font-weight: 700;
        font-size: 0.9rem;
        cursor: pointer;
      }
      .rx-inline-print-btn:hover {
        background: var(--pri-soft, #f0fdfa);
        border-color: var(--pri, #009ca6);
      }
      .rx-doc-signature-box {
        text-align: center;
        min-width: 220px;
        margin-inline-start: auto;
      }
      .rx-doc-signature-line {
        border-bottom: 2px solid var(--ink, #0f172a);
        margin-bottom: 0.45rem;
        height: 28px;
      }
      .rx-doc-signature-label {
        font-size: 0.82rem;
        font-weight: 600;
        color: var(--mute, #475569);
      }
      .rx-doc-footer-note {
        margin-top: 1.75rem;
        padding-top: 0.85rem;
        border-top: 1px solid var(--line, #e2e8f0);
        text-align: center;
        font-size: 0.78rem;
        color: var(--mute, #64748b);
      }

      /* Letterhead Settings Form */
      .rx-letterhead-form-grid {
        display: grid;
        grid-template-columns: 1fr 1fr;
        gap: 1rem;
      }
      .rx-field--full {
        grid-column: 1 / -1;
      }
      .rx-logo-upload-row {
        display: flex;
        align-items: center;
        gap: 0.5rem;
      }
      .rx-upload-label {
        cursor: pointer;
        white-space: nowrap;
      }
      .rx-checkbox-field {
        grid-column: 1 / -1;
        padding: 0.85rem 1rem;
        background: var(--surf2, #f8fafc);
        border: 1px solid var(--line, #e2e8f0);
        border-radius: 10px;
      }
      .rx-checkbox-label {
        display: flex;
        align-items: flex-start;
        gap: 0.65rem;
        cursor: pointer;
      }
      .rx-checkbox-label span {
        display: flex;
        flex-direction: column;
        gap: 0.15rem;
        font-size: 0.88rem;
      }
      .rx-checkbox-label small {
        color: var(--mute, #64748b);
        font-size: 0.78rem;
      }

      /* Confirm Delete Modal */
      .rx-modal-card--confirm {
        max-width: 480px;
        text-align: center;
        padding: 2rem;
      }
      .rx-confirm-icon-box {
        display: inline-flex;
        align-items: center;
        justify-content: center;
        width: 64px;
        height: 64px;
        border-radius: 50%;
        background: rgba(239, 68, 68, 0.15);
        color: var(--bad, #ef4444);
        margin: 0 auto 1rem;
      }
      .rx-confirm-title {
        font-size: 1.25rem;
        font-weight: 800;
        color: var(--ink, #0f172a);
        margin: 0 0 0.5rem;
      }
      .rx-confirm-sub {
        font-size: 0.95rem;
        color: var(--mute, #64748b);
        margin: 0 0 1rem;
      }
      .rx-confirm-details {
        background: var(--surf2, #f8fafc);
        border: 1px solid var(--line, #e2e8f0);
        border-radius: 8px;
        padding: 0.75rem 1rem;
        text-align: right;
        font-size: 0.88rem;
        display: flex;
        flex-direction: column;
        gap: 0.35rem;
        margin-bottom: 1rem;
        color: var(--ink, #0f172a);
      }
      .rx-confirm-alert {
        background: var(--bad-soft, #fff1f2);
        border: 1px solid rgba(239, 68, 68, 0.35);
        border-radius: 8px;
        color: var(--bad, #be123c);
        padding: 0.75rem;
        font-size: 0.82rem;
        font-weight: 600;
        text-align: right;
        margin-bottom: 1.5rem;
      }
      .rx-confirm-buttons {
        display: flex;
        justify-content: center;
        gap: 0.75rem;
      }
      .fe-btn--danger {
        background: #ef4444 !important;
        color: #ffffff !important;
        border: 1px solid #ef4444 !important;
      }
      .fe-btn--danger:hover:not(:disabled) {
        background: #dc2626 !important;
      }

      /* Presets Catalog Modal & Tabs */
      .rx-catalog-tabs {
        display: flex;
        gap: 0.5rem;
        padding: 0.75rem 1.5rem 0;
        background: var(--surf2, #f8fafc);
        border-bottom: 1px solid var(--line, #e2e8f0);
      }
      .rx-catalog-tab {
        background: none;
        border: none;
        border-bottom: 2px solid transparent;
        padding: 0.5rem 1rem;
        font-size: 0.9rem;
        font-weight: 600;
        color: var(--mute, #64748b);
        cursor: pointer;
        transition: all 0.2s;
      }
      .rx-catalog-tab:hover {
        color: var(--ink, #0f172a);
      }
      .rx-catalog-tab--active {
        color: var(--pri, #0284c7);
        border-bottom-color: var(--pri, #0284c7);
      }
      .rx-catalog-layout {
        display: grid;
        grid-template-columns: 1fr 340px;
        gap: 1.25rem;
        align-items: flex-start;
      }
      @media (max-width: 900px) {
        .rx-catalog-layout {
          grid-template-columns: 1fr;
        }
      }
      .rx-catalog-list-col {
        display: flex;
        flex-direction: column;
        gap: 0.75rem;
      }
      .rx-catalog-filter-bar {
        display: flex;
        gap: 0.5rem;
      }
      .rx-presets-table-wrap {
        max-height: 400px;
        overflow-y: auto;
        border: 1px solid var(--line, #e2e8f0);
        border-radius: 8px;
        background: var(--surf, #ffffff);
      }
      .rx-table--compact th,
      .rx-table--compact td {
        padding: 0.5rem 0.75rem;
        font-size: 0.85rem;
      }
      .rx-fav-badge {
        display: inline-block;
        padding: 0.15rem 0.5rem;
        border-radius: 9999px;
        font-size: 0.75rem;
        font-weight: 600;
        background: var(--surf2, #f1f5f9);
        color: var(--mute, #94a3b8);
      }
      .rx-fav-badge--on {
        background: rgba(245, 158, 11, 0.2);
        color: var(--warn, #d97706);
      }
      .rx-code-badge {
        display: inline-block;
        background: var(--surf2, #e2e8f0);
        padding: 0.15rem 0.4rem;
        border-radius: 4px;
        font-family: monospace;
        font-size: 0.8rem;
        font-weight: 600;
        color: var(--ink, #334155);
        border: 1px solid var(--line, transparent);
      }
      .rx-catalog-form-col {
        background: var(--surf2, #f8fafc);
        border: 1px solid var(--line, #e2e8f0);
        border-radius: 8px;
        padding: 1rem;
      }
      .rx-form-card-title {
        margin: 0 0 0.75rem;
        font-size: 0.95rem;
        font-weight: 700;
        color: var(--ink, #0f172a);
      }
      .rx-form-stack--sm {
        display: flex;
        flex-direction: column;
        gap: 0.65rem;
      }
      .rx-input--sm,
      .rx-select--sm {
        padding: 0.35rem 0.6rem;
        font-size: 0.85rem;
      }
      .rx-modal-card--small {
        max-width: 480px;
      }
      .rx-presets-header {
        display: flex;
        justify-content: space-between;
        align-items: center;
        margin-bottom: 0.5rem;
      }
      .rx-presets-label {
        font-size: 0.8rem;
        font-weight: 700;
        color: var(--mute, #475569);
      }
      .rx-mini-link {
        background: none;
        border: none;
        color: var(--pri, #0284c7);
        font-size: 0.78rem;
        font-weight: 600;
        cursor: pointer;
        padding: 0;
        text-decoration: underline;
      }
      .rx-mini-link:hover {
        opacity: 0.85;
      }
      .rx-presets-chips-wrap {
        display: flex;
        flex-wrap: wrap;
        gap: 0.4rem;
      }

      /* Print Media Rules */
      @media print {
        .no-print {
          display: none !important;
        }
        .rx-page {
          padding: 0 !important;
          background: #ffffff !important;
        }
        .rx-modal-backdrop {
          position: static !important;
          background: none !important;
          padding: 0 !important;
        }
        .rx-sheet-wrapper {
          max-width: 100% !important;
          max-height: none !important;
          overflow: visible !important;
        }
        .rx-document {
          background: #ffffff !important;
          color: #000000 !important;
          box-shadow: none !important;
          border: none !important;
          padding: 0.5cm !important;
        }
        .rx-doc-clinic-name,
        .rx-doc-rxnum,
        .rx-doc-value,
        .rx-doc-diagnosis-val,
        .rx-doc-med-name,
        .rx-doc-table td,
        .rx-doc-notes,
        .rx-doc-signature-label {
          color: #000000 !important;
        }
        .rx-doc-diagnosis,
        .rx-doc-notes {
          background: #f8fafc !important;
          color: #000000 !important;
          border-color: #cbd5e1 !important;
        }
        .rx-doc-table th {
          color: #475569 !important;
          border-bottom: 2px solid #cbd5e1 !important;
        }
        .rx-doc-table td {
          border-bottom: 1px solid #e2e8f0 !important;
        }
        .rx-doc-signature-line {
          border-bottom: 2px solid #000000 !important;
        }
        .rx-doc-footer-note {
          color: #64748b !important;
          border-top: 1px solid #e2e8f0 !important;
        }
      }
    `,
  ],
})
export class ClinicPrescriptionsComponent {
  private readonly api = inject(ClinicPatientApi);
  private readonly clinicApi = inject(ClinicApi);
  private readonly projects = inject(ProjectContextService);
  private readonly router = inject(Router);
  protected readonly rbac = inject(ClinicRbacService);
  protected readonly format = inject(FormatService);
  protected readonly i18n = inject(I18nService);
  protected readonly isAr = computed(() => this.i18n.locale() === 'ar');

  protected readonly canViewPrescriptions = computed(() =>
    this.rbac.hasAnyPermission(['clinical.view_record', 'clinical.write_record', 'clinical.view_history']) ||
    this.rbac.activeRoles().includes('doctor') ||
    this.rbac.activeRoles().includes('clinic_admin'),
  );

  protected readonly canWritePrescription = computed(() =>
    this.rbac.hasAnyPermission(['clinical.write_record']) ||
    this.rbac.activeRoles().includes('doctor') ||
    this.rbac.activeRoles().includes('clinic_admin'),
  );

  protected readonly canCustomizeLetterhead = computed(() =>
    this.rbac.hasAnyPermission(['clinic.settings.manage', 'clinical.write_record']) ||
    this.rbac.activeRoles().includes('clinic_admin') ||
    this.rbac.activeRoles().includes('doctor'),
  );

  protected readonly loading = signal<boolean>(true);
  protected readonly saving = signal<boolean>(false);
  protected readonly savingLetterhead = signal<boolean>(false);
  protected readonly feedbackMessage = signal<string | null>(null);
  protected readonly errorMessage = signal<string | null>(null);

  protected readonly showDescInfo = signal<boolean>(false);
  toggleDescInfo(): void {
    this.showDescInfo.update((v) => !v);
  }

  protected readonly sheets = signal<ClinicPrescriptionSheet[]>([]);
  protected readonly patients = signal<ClinicPatient[]>([]);
  protected readonly doctors = signal<readonly ClinicDoctor[]>([]);

  protected readonly searchQuery = signal<string>('');
  protected readonly selectedDoctorFilter = signal<string>('');

  protected readonly filteredSheets = computed(() => {
    const q = this.searchQuery().trim().toLowerCase();
    const docId = this.selectedDoctorFilter();
    return this.sheets().filter((s) => {
      if (docId && s.doctorId !== docId) return false;
      if (!q) return true;
      const inBasic =
        s.rxNumber.toLowerCase().includes(q) ||
        (s.patientName ?? '').toLowerCase().includes(q) ||
        (s.doctorName ?? '').toLowerCase().includes(q) ||
        (s.diagnosis ?? '').toLowerCase().includes(q);
      if (inBasic) return true;
      return s.medications.some((m) => m.medicationName.toLowerCase().includes(q));
    });
  });

  // Active Preview / Print Sheet
  protected readonly activeSheet = signal<ClinicPrescriptionSheet | null>(null);

  // New Prescription Modal State
  protected readonly isNewModalOpen = signal<boolean>(false);
  protected newRxPatientId = '';
  protected newRxDoctorId = '';
  protected newRxDate = new Date().toISOString().slice(0, 10);
  protected newRxDiagnosis = '';
  protected newRxNotes = '';
  protected readonly draftMedications = signal<DraftMedicationRow[]>([]);

  // Letterhead Settings State
  protected readonly letterhead = signal<ClinicLetterheadSettings>({
    clinicName: 'BrightSmiles Dental Clinic',
    clinicSubtitle: 'مركز تخصصي لطب وجراحة وتجميل الأسنان · Oral & Dental Care Center',
    logoUrl: null,
    address: 'عمان - شارع المدينة المنورة - مجمع العيادات الطبية الحديثة',
    phone: '+962 6 555 0199',
    whatsapp: '+962 79 888 0199',
    email: 'contact@brightsmilesclinic.com',
    licenseNumber: 'LIC-JO-2026-8841',
    defaultInstructions:
      'يرجى الالتزام بالجرعات والمواعيد المحددة بدقة، وعدم إيقاف المضاد الحيوي قبل إتمام المدة المقررة، ومراجعة العيادة عند الحاجة.',
    footerText: 'نتمنى لكم دوام الصحة والعافية · للاستفسارات الطارئة يرجى التواصل مع العيادة على مدار الساعة',
    signatureLabel: "توقيع وختم الطبيب المعالج / Doctor's Signature",
    paperSize: 'A4',
    prePrintedPaper: false,
  });

  protected readonly isLetterheadModalOpen = signal<boolean>(false);
  protected lhClinicName = '';
  protected lhClinicSubtitle = '';
  protected lhLogoUrl = '';
  protected lhAddress = '';
  protected lhPhone = '';
  protected lhWhatsapp = '';
  protected lhEmail = '';
  protected lhLicenseNumber = '';
  protected lhDefaultInstructions = '';
  protected lhFooterText = '';
  protected lhSignatureLabel = '';
  protected lhPaperSize = 'A4';
  protected lhPrePrintedPaper = false;

  // Presets State
  protected readonly medicationPresets = signal<ClinicMedicationPreset[]>([]);
  protected readonly diagnosisPresets = signal<ClinicDiagnosisPreset[]>([]);
  protected readonly isPresetsModalOpen = signal<boolean>(false);
  protected activePresetsTab: 'medications' | 'diagnoses' = 'medications';

  protected presetMedSearchQuery = '';
  protected presetMedCategoryFilter = '';

  protected readonly favoriteMedPresets = computed(() =>
    this.medicationPresets().filter((m) => m.isFavorite),
  );

  protected readonly medCategories = computed(() => {
    const cats = new Set<string>();
    for (const m of this.medicationPresets()) {
      if (m.category?.trim()) {
        cats.add(m.category.trim());
      }
    }
    return Array.from(cats);
  });

  protected readonly filteredMedPresets = computed(() => {
    const q = this.presetMedSearchQuery.trim().toLowerCase();
    const cat = this.presetMedCategoryFilter.trim();
    return this.medicationPresets().filter((m) => {
      if (cat && m.category !== cat) return false;
      if (!q) return true;
      return (
        m.medicationName.toLowerCase().includes(q) ||
        (m.category ?? '').toLowerCase().includes(q) ||
        (m.instructions ?? '').toLowerCase().includes(q)
      );
    });
  });

  // New medication preset form state
  protected newMedName = '';
  protected newMedDosage = '1 tablet';
  protected newMedFrequency = 'Twice a day';
  protected newMedDuration = '5 days';
  protected newMedInstructions = '';
  protected newMedCategory = 'المضادات الحيوية (Antibiotics)';
  protected newMedIsFavorite = true;
  protected readonly savingMedPreset = signal<boolean>(false);

  // New diagnosis preset form state
  protected newDiagName = '';
  protected newDiagCode = '';
  protected readonly savingDiagPreset = signal<boolean>(false);

  // Quick add medication modal state
  protected readonly isQuickAddMedModalOpen = signal<boolean>(false);
  protected quickAddMedName = '';
  protected quickAddMedDosage = '1 tablet';
  protected quickAddMedFrequency = 'Twice a day';
  protected quickAddMedDuration = '5 days';
  protected quickAddMedInstructions = '';
  protected readonly savingQuickAddMed = signal<boolean>(false);

  // Delete sheet confirmation state
  protected readonly sheetToDelete = signal<ClinicPrescriptionSheet | null>(null);
  protected readonly deleting = signal<boolean>(false);

  protected readonly commonDiagnoses = [
    'Pulpitis · التهاب عصب السن',
    'Post-extraction care · عناية ما بعد الخلع الجراحي',
    'Symptomatic Apical Periodontitis · التهاب الأنسجة حول الذروية',
    'Acute Pericoronitis · التهاب اللثة حول ضرس العقل',
    'Acute Dentoalveolar Abscess · خراج سني حاد',
    'Post-implant Surgery Prophylaxis · وقاية ما بعد زراعة الأسنان',
    'Gingivitis & Periodontal Care · التهاب اللثة والأنسجة الداعمة',
  ];

  protected readonly commonMedications = [
    'Amoxicillin 500mg',
    'Augmentin 1g (Amoxicillin/Clavulanate)',
    'Ibuprofen 400mg',
    'Ibuprofen 600mg',
    'Paracetamol 650mg',
    'Metronidazole 400mg',
    'Chlorhexidine Mouthwash 0.12%',
    'Diclofenac Potassium 50mg (Cataflam)',
    'Clindamycin 300mg',
    'Azithromycin 500mg',
  ];

  constructor() {
    if (!this.canViewPrescriptions()) {
      void this.router.navigate(['/app/denied']);
      return;
    }
    void this.reloadAll();
  }

  protected async reloadAll(): Promise<void> {
    this.loading.set(true);
    this.errorMessage.set(null);
    const projectId = this.projects.selectedProjectId() ?? undefined;
    try {
      const [sheetsList, lhSettings, patientsList, doctorsPage, medPresets, diagPresets] = await Promise.all([
        this.api.listClinicPrescriptions({ projectId }).catch(() => []),
        this.api.getLetterheadSettings({ projectId }).catch(() => null),
        this.api.patients({ projectId }).catch(() => []),
        this.clinicApi.doctors({ projectId, limit: 100 }).catch(() => ({ items: [] })),
        this.api.listMedicationPresets({ projectId }).catch(() => []),
        this.api.listDiagnosisPresets({ projectId }).catch(() => []),
      ]);

      this.sheets.set(sheetsList);
      if (lhSettings) {
        this.letterhead.set(lhSettings);
      }
      this.patients.set(patientsList);
      this.doctors.set(doctorsPage.items ?? []);
      this.medicationPresets.set(medPresets);
      this.diagnosisPresets.set(diagPresets);

      if (!this.newRxDoctorId && (doctorsPage.items?.length ?? 0) > 0) {
        this.newRxDoctorId = doctorsPage.items![0].id ?? '';
      }
    } catch {
      this.errorMessage.set(this.isAr() ? 'تعذر تحميل سجل الوصفات الطبية.' : 'Failed to load prescriptions log.');
    } finally {
      this.loading.set(false);
    }
  }

  protected openNewPrescriptionModal(): void {
    this.errorMessage.set(null);
    this.newRxDate = new Date().toISOString().slice(0, 10);
    this.newRxDiagnosis = '';
    this.newRxNotes = '';
    if (!this.newRxPatientId && this.patients().length > 0) {
      this.newRxPatientId = this.patients()[0].id ?? '';
    }
    if (!this.newRxDoctorId && this.doctors().length > 0) {
      this.newRxDoctorId = this.doctors()[0].id ?? '';
    }
    if (this.favoriteMedPresets().length >= 2) {
      const p1 = this.favoriteMedPresets()[0];
      const p2 = this.favoriteMedPresets()[1];
      this.draftMedications.set([
        this.createEmptyMedRow(p1.medicationName, p1.dosage, p1.frequency, p1.duration, p1.instructions ?? ''),
        this.createEmptyMedRow(p2.medicationName, p2.dosage, p2.frequency, p2.duration, p2.instructions ?? ''),
      ]);
    } else {
      this.draftMedications.set([
        this.createEmptyMedRow('Amoxicillin 500mg', '1 capsule', 'Three times a day', '5 days', this.isAr() ? 'بعد الأكل كل 8 ساعات' : 'After meals every 8h'),
        this.createEmptyMedRow('Paracetamol 650mg', '1 tablet', 'As needed', '3 days', this.isAr() ? 'عند اللزوم حد أقصى 3 مرات' : 'Max 3 per day as needed'),
      ]);
    }
    this.isNewModalOpen.set(true);
  }

  protected closeNewPrescriptionModal(): void {
    this.isNewModalOpen.set(false);
  }

  private createEmptyMedRow(
    medicationName = '',
    dosage = '1 tablet',
    frequency = 'Twice a day',
    duration = '5 days',
    instructions = '',
  ): DraftMedicationRow {
    return {
      id: 'med-' + Math.random().toString(36).slice(2, 9),
      medicationName,
      dosage,
      frequency,
      duration,
      instructions,
      selectedForDelete: false,
    };
  }

  protected addDraftMedicationRow(): void {
    this.draftMedications.update((rows) => [...rows, this.createEmptyMedRow()]);
  }

  protected removeDraftMedicationRow(index: number): void {
    this.draftMedications.update((rows) =>
      rows.length > 1 ? rows.filter((_, idx) => idx !== index) : rows,
    );
  }

  protected appendPresetMed(
    name: string,
    dosage: string,
    frequency: string,
    duration: string,
    instructions: string,
  ): void {
    this.draftMedications.update((rows) => {
      const emptyIdx = rows.findIndex((r) => !r.medicationName.trim());
      if (emptyIdx >= 0) {
        const copy = [...rows];
        copy[emptyIdx] = {
          ...copy[emptyIdx],
          medicationName: name,
          dosage,
          frequency,
          duration,
          instructions,
        };
        return copy;
      }
      return [...rows, this.createEmptyMedRow(name, dosage, frequency, duration, instructions)];
    });
  }

  protected async submitNewPrescription(): Promise<void> {
    if (!this.newRxPatientId) {
      this.errorMessage.set(this.isAr() ? 'يرجى اختيار المريض قبل حفظ الوصفة.' : 'Please select a patient before saving prescription.');
      return;
    }
    const validMeds: CreatePrescriptionPayload[] = this.draftMedications()
      .filter((m) => m.medicationName.trim().length > 0)
      .map((m) => ({
        medicationName: m.medicationName.trim(),
        dosage: m.dosage.trim() || '1 tablet',
        frequency: m.frequency.trim() || 'Twice a day',
        duration: m.duration.trim() || '5 days',
        instructions: m.instructions.trim() || undefined,
        doctorId: this.newRxDoctorId || undefined,
      }));

    if (validMeds.length === 0) {
      this.errorMessage.set(this.isAr() ? 'يرجى إدخال دواء واحد على الأقل في الوصفة الطبية.' : 'Please add at least one medication to the prescription.');
      return;
    }

    this.saving.set(true);
    this.errorMessage.set(null);
    const projectId = this.projects.selectedProjectId() ?? undefined;
    try {
      const created = await this.api.createStandalonePrescription(
        {
          patientId: this.newRxPatientId,
          doctorId: this.newRxDoctorId || undefined,
          diagnosis: this.newRxDiagnosis.trim() || 'Post-extraction care',
          notes: this.newRxNotes.trim() || undefined,
          visitDate: this.newRxDate,
          projectId,
          medications: validMeds,
        },
        { projectId },
      );

      this.isNewModalOpen.set(false);
      this.feedbackMessage.set(
        this.isAr()
          ? `تم إصدار الوصفة الطبية (${created.rxNumber}) للمريض ${created.patientName} بنجاح.`
          : `Prescription (${created.rxNumber}) for patient ${created.patientName} issued successfully.`
      );
      await this.reloadAll();
      this.activeSheet.set(created);
    } catch {
      this.errorMessage.set(this.isAr() ? 'حدث خطأ أثناء حفظ الوصفة الطبية. يرجى المحاولة مرة أخرى.' : 'Error saving prescription. Please try again.');
    } finally {
      this.saving.set(false);
    }
  }

  protected openPreviewSheet(sheet: ClinicPrescriptionSheet): void {
    this.activeSheet.set(sheet);
  }

  protected closePreviewSheet(): void {
    this.activeSheet.set(null);
  }

  protected triggerPrint(): void {
    window.print();
  }

  // Delete Prescription Sheet Confirmation Flow
  protected promptDeleteSheet(sheet: ClinicPrescriptionSheet): void {
    this.sheetToDelete.set(sheet);
  }

  protected cancelDeleteSheet(): void {
    this.sheetToDelete.set(null);
  }

  protected async executeDeleteSheet(sheet: ClinicPrescriptionSheet): Promise<void> {
    this.deleting.set(true);
    const projectId = this.projects.selectedProjectId() ?? undefined;
    try {
      await this.api.deletePrescriptionSheet(sheet.id, { projectId });
      this.sheets.update((list) => list.filter((item) => item.id !== sheet.id));
      if (this.activeSheet()?.id === sheet.id) {
        this.activeSheet.set(null);
      }
      this.sheetToDelete.set(null);
      this.feedbackMessage.set(
        this.isAr()
          ? `تم حذف الوصفة الطبية (${sheet.rxNumber}) بنجاح.`
          : `Prescription (${sheet.rxNumber}) deleted successfully.`
      );
    } catch {
      this.errorMessage.set(this.isAr() ? 'تعذر حذف الوصفة الطبية.' : 'Failed to delete prescription.');
    } finally {
      this.deleting.set(false);
    }
  }

  protected async confirmDeleteSheet(sheet: ClinicPrescriptionSheet): Promise<void> {
    this.promptDeleteSheet(sheet);
  }

  // Presets Catalog Modal handlers
  protected openPresetsCatalogModal(): void {
    this.activePresetsTab = 'medications';
    this.isPresetsModalOpen.set(true);
  }

  protected openPresetsCatalogWithTab(tab: 'medications' | 'diagnoses'): void {
    this.activePresetsTab = tab;
    this.isPresetsModalOpen.set(true);
  }

  protected closePresetsCatalogModal(): void {
    this.isPresetsModalOpen.set(false);
  }

  protected async saveNewMedicationPreset(): Promise<void> {
    if (!this.newMedName.trim()) return;
    this.savingMedPreset.set(true);
    const projectId = this.projects.selectedProjectId() ?? undefined;
    try {
      const created = await this.api.createMedicationPreset(
        {
          medicationName: this.newMedName.trim(),
          dosage: this.newMedDosage.trim() || '1 tablet',
          frequency: this.newMedFrequency.trim() || 'Twice a day',
          duration: this.newMedDuration.trim() || '5 days',
          instructions: this.newMedInstructions.trim() || undefined,
          category: this.newMedCategory.trim() || undefined,
          isFavorite: this.newMedIsFavorite,
          sortOrder: this.medicationPresets().length,
          projectId,
        },
        { projectId },
      );
      this.medicationPresets.update((prev) => [...prev, created]);
      this.newMedName = '';
      this.newMedInstructions = '';
      this.feedbackMessage.set(
        this.isAr()
          ? `تم إضافة الدواء (${created.medicationName}) إلى دليل العيادة بنجاح.`
          : `Medication (${created.medicationName}) added to catalog successfully.`
      );
    } catch {
      this.errorMessage.set(this.isAr() ? 'تعذر إضافة الدواء إلى الدليل.' : 'Failed to add medication to catalog.');
    } finally {
      this.savingMedPreset.set(false);
    }
  }

  protected async deleteMedicationPreset(id: string): Promise<void> {
    const projectId = this.projects.selectedProjectId() ?? undefined;
    try {
      await this.api.deleteMedicationPreset(id, { projectId });
      this.medicationPresets.update((prev) => prev.filter((m) => m.id !== id));
      this.feedbackMessage.set(this.isAr() ? 'تم حذف الدواء من الدليل.' : 'Medication deleted from catalog.');
    } catch {
      this.errorMessage.set(this.isAr() ? 'تعذر حذف الدواء.' : 'Failed to delete medication.');
    }
  }

  protected async saveNewDiagnosisPreset(): Promise<void> {
    if (!this.newDiagName.trim()) return;
    this.savingDiagPreset.set(true);
    const projectId = this.projects.selectedProjectId() ?? undefined;
    try {
      const created = await this.api.createDiagnosisPreset(
        {
          name: this.newDiagName.trim(),
          code: this.newDiagCode.trim() || undefined,
          sortOrder: this.diagnosisPresets().length,
          projectId,
        },
        { projectId },
      );
      this.diagnosisPresets.update((prev) => [...prev, created]);
      this.newDiagName = '';
      this.newDiagCode = '';
      this.feedbackMessage.set(
        this.isAr()
          ? `تم إضافة التشخيص (${created.name}) بنجاح.`
          : `Diagnosis preset (${created.name}) added successfully.`
      );
    } catch {
      this.errorMessage.set(this.isAr() ? 'تعذر إضافة التشخيص.' : 'Failed to add diagnosis preset.');
    } finally {
      this.savingDiagPreset.set(false);
    }
  }

  protected async deleteDiagnosisPreset(id: string): Promise<void> {
    const projectId = this.projects.selectedProjectId() ?? undefined;
    try {
      await this.api.deleteDiagnosisPreset(id, { projectId });
      this.diagnosisPresets.update((prev) => prev.filter((d) => d.id !== id));
      this.feedbackMessage.set(this.isAr() ? 'تم حذف التشخيص بنجاح.' : 'Diagnosis preset deleted.');
    } catch {
      this.errorMessage.set(this.isAr() ? 'تعذر حذف التشخيص.' : 'Failed to delete diagnosis preset.');
    }
  }

  // Quick Add Medication Modal handlers
  protected openQuickAddMedModal(): void {
    this.quickAddMedName = '';
    this.quickAddMedDosage = '1 tablet';
    this.quickAddMedFrequency = 'Twice a day';
    this.quickAddMedDuration = '5 days';
    this.quickAddMedInstructions = '';
    this.isQuickAddMedModalOpen.set(true);
  }

  protected closeQuickAddMedModal(): void {
    this.isQuickAddMedModalOpen.set(false);
  }

  protected async submitQuickAddMed(): Promise<void> {
    if (!this.quickAddMedName.trim()) return;
    this.savingQuickAddMed.set(true);
    const projectId = this.projects.selectedProjectId() ?? undefined;
    try {
      const created = await this.api.createMedicationPreset(
        {
          medicationName: this.quickAddMedName.trim(),
          dosage: this.quickAddMedDosage.trim() || '1 tablet',
          frequency: this.quickAddMedFrequency.trim() || 'Twice a day',
          duration: this.quickAddMedDuration.trim() || '5 days',
          instructions: this.quickAddMedInstructions.trim() || undefined,
          category: this.isAr() ? 'أدوية العيادة المضافة' : 'Added Medications',
          isFavorite: true,
          sortOrder: 0,
          projectId,
        },
        { projectId },
      );
      this.medicationPresets.update((prev) => [created, ...prev]);
      this.appendPresetObject(created);
      this.isQuickAddMedModalOpen.set(false);
      this.feedbackMessage.set(
        this.isAr()
          ? `تم حفظ الدواء (${created.medicationName}) وإدراجه في الوصفة.`
          : `Medication (${created.medicationName}) saved and added to prescription.`
      );
    } catch {
      this.errorMessage.set(this.isAr() ? 'تعذر حفظ الدواء الجديد.' : 'Failed to save new medication.');
    } finally {
      this.savingQuickAddMed.set(false);
    }
  }

  protected appendPresetObject(p: ClinicMedicationPreset): void {
    this.appendPresetMed(
      p.medicationName,
      p.dosage || '1 tablet',
      p.frequency || 'Twice a day',
      p.duration || '5 days',
      p.instructions || '',
    );
  }

  protected onMedicationNameChanged(row: DraftMedicationRow): void {
    const val = row.medicationName.trim().toLowerCase();
    if (!val) return;
    const match = this.medicationPresets().find(
      (m) => m.medicationName.toLowerCase() === val,
    );
    if (match) {
      if (match.dosage) row.dosage = match.dosage;
      if (match.frequency) row.frequency = match.frequency;
      if (match.duration) row.duration = match.duration;
      if (match.instructions) row.instructions = match.instructions;
    }
  }

  protected openLetterheadModal(): void {
    const cur = this.letterhead();
    this.lhClinicName = cur.clinicName;
    this.lhClinicSubtitle = cur.clinicSubtitle;
    this.lhLogoUrl = cur.logoUrl ?? '';
    this.lhAddress = cur.address;
    this.lhPhone = cur.phone;
    this.lhWhatsapp = cur.whatsapp ?? '';
    this.lhEmail = cur.email ?? '';
    this.lhLicenseNumber = cur.licenseNumber ?? '';
    this.lhDefaultInstructions = cur.defaultInstructions ?? '';
    this.lhFooterText = cur.footerText ?? '';
    this.lhSignatureLabel = cur.signatureLabel;
    this.lhPaperSize = cur.paperSize || 'A4';
    this.lhPrePrintedPaper = Boolean(cur.prePrintedPaper);
    this.isLetterheadModalOpen.set(true);
  }

  protected closeLetterheadModal(): void {
    this.isLetterheadModalOpen.set(false);
  }

  protected onLogoFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = () => {
      if (typeof reader.result === 'string') {
        this.lhLogoUrl = reader.result;
      }
    };
    reader.readAsDataURL(file);
  }

  protected async saveLetterheadSettings(): Promise<void> {
    this.savingLetterhead.set(true);
    const projectId = this.projects.selectedProjectId() ?? undefined;
    try {
      const updated = await this.api.updateLetterheadSettings(
        {
          clinicName: this.lhClinicName,
          clinicSubtitle: this.lhClinicSubtitle,
          logoUrl: this.lhLogoUrl || null,
          address: this.lhAddress,
          phone: this.lhPhone,
          whatsapp: this.lhWhatsapp || null,
          email: this.lhEmail || null,
          licenseNumber: this.lhLicenseNumber || null,
          defaultInstructions: this.lhDefaultInstructions || null,
          footerText: this.lhFooterText || null,
          signatureLabel: this.lhSignatureLabel,
          paperSize: this.lhPaperSize,
          prePrintedPaper: this.lhPrePrintedPaper,
          projectId,
        },
        { projectId },
      );
      this.letterhead.set(updated);
      this.isLetterheadModalOpen.set(false);
      this.feedbackMessage.set(
        this.isAr()
          ? 'تم حفظ وتحديث ترويسة الورقة المروسة للعيادة بنجاح.'
          : 'Clinic letterhead settings saved successfully.'
      );
    } catch {
      this.errorMessage.set(this.isAr() ? 'تعذر حفظ إعدادات الترويسة.' : 'Failed to save letterhead settings.');
    } finally {
      this.savingLetterhead.set(false);
    }
  }
}
