/**
 * `/app/clinic/patients/:id/encounters` and `/app/clinic/encounters/:id`
 * ToothLogic Clinical Encounter Detail Dashboard, 9-Step Patient History Stepper,
 * Generate Invoice & Installments Modal, and Clinical Encounter Notes (CLN-01 to CLN-09).
 */

import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  signal,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { ProjectContextService } from '../../core/context/project-context.service';
import { ClinicApi } from '../../core/api/endpoints';
import { FormatService } from '../../core/format/format.service';
import { I18nService } from '../../core/i18n/i18n.service';
import type { Namespace } from '../../core/i18n/i18n.types';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { ResourceStore } from '../../core/state/resource';
import { ClinicRbacService } from '../../core/auth/clinic-rbac';
import { type StatusTone } from '../../ui/status-chip.component';
import {
  ClinicPatientApi,
  type ClinicEncounterDetail,
  type ClinicEncounterHeader,
  type ClinicPatient,
  type ClinicTreatmentPlanHeader,
  type ClinicTreatmentPlanItem,
  type CreateEncounterPayload,
  type CreateImagingRequestPayload,
  type CreatePrescriptionPayload,
  type EncounterStatus,
  type ImagingLabRow,
  type ImagingRequestRow,
  type InventoryStockWarning,
  type PatientHistoryIntakeRecord,
  type UpdateDiagnosisPayload,
} from './clinic-patient.api';
import { clinicStrings } from './clinic.strings';
import { isDicomFile, parseAndRenderDicom, detectDentalFileType } from '../../core/utils/dicom-parser';
import { is3DFile, Dental3DScene, type PerspectivePreset } from '../../core/utils/dental-3d-viewer';

export interface PatientHistoryRecord {
  id: string;
  date: string;
  name: string;
  isComplete: boolean;
  status?: 'PENDING_APPROVAL' | 'APPROVED' | 'COMPLETED' | string;
  source?: 'PATIENT_PORTAL' | 'CLINIC_STAFF' | string;
  demographics: {
    fullName: string;
    dob: string;
    gender: 'Male' | 'Female' | 'Other';
    occupation: string;
    address: string;
    phone: string;
    email: string;
    emergencyName: string;
    emergencyPhone: string;
  };
  medicalHistory: {
    underTreatment: 'Yes' | 'No';
    treatmentSpecify: string;
    hospitalizedLastYear: 'Yes' | 'No';
    hospitalizedReason: string;
    conditions: string[];
    otherCondition: string;
    pregnantOrBreastfeeding: 'Yes' | 'No' | 'N/A';
    medications: string[];
    otherMedications: string;
    allergies: string[];
    otherAllergies: string;
  };
  dentalHistory: {
    lastDentalVisitDate: string;
    lastVisitReason: string;
    symptoms: string[];
    pastProcedures: string;
    anxietyLevel: number;
  };
  chiefComplaint: {
    primaryComplaint: string;
    painScale: number;
    duration: string;
    triggers: string[];
  };
  clinicalExam: {
    faceSymmetry: 'Normal' | 'Abnormal';
    faceSymmetryDetails: string;
    tmj: 'Normal' | 'Clicking' | 'Pain' | 'Limited Opening';
    softTissues: 'Normal' | 'Abnormalities';
    softTissuesDetails: string;
    teethFindings: string[];
    gingivalHealth: 'Healthy' | 'Gingivitis' | 'Periodontitis';
    bleedingOnProbing: 'Yes' | 'No';
    pocketDepths: string;
    mobility: 'Present' | 'Absent';
    occlusion: 'Normal' | 'Malocclusion' | 'Class I' | 'Class II' | 'Class III';
    bruxism: 'Yes' | 'No';
  };
  radiographs: {
    types: string[];
    findings: string;
  };
  diagnosisAndPlan: {
    diagnosis: string;
    proposedPlans: string[];
    otherPlan: string;
    plannedTimeline: string;
    alternativesDiscussed: 'Yes' | 'No';
    risksExplained: 'Yes' | 'No';
    questionsAddressed: 'Yes' | 'No';
  };
  dentalChart: {
    selectedTeeth: number[];
    toothCondition: string;
    chartNotes: string;
  };
  informedConsent: {
    agreed: boolean;
    patientSignatureDataUrl: string;
    patientSignatureSaved: boolean;
    dentistSignatureDataUrl: string;
    dentistSignatureSaved: boolean;
    signedAt: string;
  };
}

export interface EncounterRadiographRow {
  id: string;
  date: string;
  radiographType: string;
  fileName: string;
  fileSize?: string;
  thumbnailUrl?: string;
  fileUrl?: string;
  toothFdi?: number | null;
  notes: string;
  rawFile?: File;
}

export interface EncounterFollowUpRow {
  id: string;
  date: string;
  title: string;
  note: string;
}

export interface BillingServiceRow {
  id: string;
  serviceName: string;
  discount: number;
  quantity: number;
  price: number;
  totalAmount: number;
}

export interface BillingInstallmentRow {
  id: string;
  amount: number;
  paymentMode: 'Cash' | 'Card' | 'Bank Transfer';
  date: string;
}

const HISTORY_STEPS = [
  { step: 1, label: 'Demographics' },
  { step: 2, label: 'Medical History' },
  { step: 3, label: 'Dental History' },
  { step: 4, label: 'Chief Complaint' },
  { step: 5, label: 'Clinical Exam' },
  { step: 6, label: 'Radiographs' },
  { step: 7, label: 'Diagnosis & Plan' },
  { step: 8, label: 'Dental Chart' },
  { step: 9, label: 'Informed Consent' },
] as const;

const MEDICAL_CONDITIONS = [
  'Heart Disease',
  'High/Low Blood Pressure',
  'Diabetes',
  'Asthma',
  'Bleeding Disorders',
  'Epilepsy/Seizures',
  'Hepatitis A/B/C',
  'HIV/AIDS',
  'Tuberculosis',
  'Kidney Disease',
  'Liver Disease',
  'Cancer',
  'Psychiatric Disorders',
  'Thyroid Problems',
  'Osteoporosis',
  'Other',
] as const;

const MEDICATION_TYPES = [
  'Blood Thinners',
  'Bisphosphonates',
  'Steroids',
  'Immunosuppressors',
  'Herbal Supplements',
  'Others',
] as const;

const ALLERGY_TYPES = [
  'Local Anesthetics',
  'Latex',
  'Penicillin/Antibiotics',
  'Other',
] as const;

const DENTAL_SYMPTOMS = [
  'Dry mouth',
  'Loose or shifting teeth',
  'Previous orthodontic treatment',
  'History of dental trauma',
  'Problems with past dental treatments',
] as const;

const RADIOGRAPH_TYPES = [
  'Bitewings',
  'Periapical',
  'OPG',
  'Lateral Cephalogram',
  'CBCT',
  '3D Dental Scan (STL)',
] as const;

const PROPOSED_PLANS = [
  'Oral Prophylaxis',
  'Fillings',
  'Root Canal Treatment',
  'Crowns/Bridges',
  'Implants',
  'Extractions',
  'Orthodontics (Braces/Aligners)',
  'Other',
] as const;

const UPPER_RIGHT_TEETH = [18, 17, 16, 15, 14, 13, 12, 11];
const UPPER_LEFT_TEETH = [21, 22, 23, 24, 25, 26, 27, 28];
const LOWER_RIGHT_TEETH = [48, 47, 46, 45, 44, 43, 42, 41];
const LOWER_LEFT_TEETH = [31, 32, 33, 34, 35, 36, 37, 38];

@Component({
  selector: 'fe-clinic-encounters-page',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    CommonModule,
    FormsModule,
    RouterLink,
    TranslatePipe,
  ],
  template: `
    <div class="tl-page" data-testid="clinic-encounters">
      <!-- ToothLogic Breadcrumb -->
      <div class="tl-page-top">
        <nav class="tl-breadcrumbs" aria-label="Breadcrumb">
          <a routerLink="/app/dashboard">Home</a>
          <span class="tl-bc-sep">&gt;&gt;</span>
          <span class="active">Encounter Dashboard</span>
        </nav>
      </div>

      <!-- ToothLogic 2-Column Encounter Dashboard -->
      <div class="tl-encounter-grid">
        <!-- Left Column: About Clinic & About Patient Cards -->
        <aside class="tl-encounter-left">
          <section class="tl-info-card">
            <h3 class="tl-info-heading">About Clinic</h3>
            <div class="tl-clinic-box">
              <div class="tl-clinic-thumbnail" aria-hidden="true">
                <svg viewBox="0 0 44 44" width="44" height="44" fill="none">
                  <rect width="44" height="44" rx="6" fill="var(--surf2)" stroke="var(--line)" stroke-width="1"/>
                  <path d="M12 32V16l10-5 10 5v16H12z" fill="var(--line)" stroke="var(--mute)" stroke-width="1.5" stroke-linejoin="round"/>
                  <path d="M19 22h6v10h-6z" fill="var(--surf)" stroke="var(--mute)" stroke-width="1.2"/>
                  <path d="M22 14v4M20 16h4" stroke="var(--tl-teal)" stroke-width="1.8" stroke-linecap="round"/>
                </svg>
              </div>
              <div class="tl-clinic-meta">
                <strong class="tl-clinic-title">{{ currentClinicName() }}</strong>
                <span class="tl-muted">{{ currentClinicEmail() }}</span>
                <span class="tl-clinic-doctor">{{ currentDoctorName() }}</span>
              </div>
            </div>
          </section>

          <section class="tl-info-card">
            <h3 class="tl-info-heading">About Patient</h3>
            <div class="tl-patient-box">
              <div class="tl-patient-avatar" aria-hidden="true">
                <svg viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="currentColor" stroke-width="1.8">
                  <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"></path>
                  <circle cx="12" cy="7" r="4"></circle>
                </svg>
              </div>
              <div class="tl-patient-meta">
                <strong class="tl-patient-name">{{ currentPatientName() }}</strong>
                <span class="tl-muted">{{ currentPatientEmail() }}</span>
              </div>
            </div>
            <div class="tl-info-divider"></div>
            <div class="tl-kv-list">
              <div class="tl-kv-row">
                <span class="tl-kv-label">Encounter Date Time:</span>
                <span class="tl-kv-value">{{ currentEncounterDateTime() }}</span>
              </div>
              <div class="tl-kv-row">
                <span class="tl-kv-label">Address:</span>
                <span class="tl-kv-value">{{ currentPatientAddress() }}</span>
              </div>
              <div class="tl-kv-row">
                <span class="tl-kv-label">Status:</span>
                <span class="tl-status-text" [class.tl-status-text--closed]="encounterClosed()">
                  {{ encounterClosed() ? 'Closed' : 'Open' }}
                </span>
              </div>
            </div>
          </section>
        </aside>

        <!-- Right Main Workspace -->
        <main class="tl-encounter-main">
          <!-- Main Clinical Workspace Top Bar: Clinical Details & Close Encounter & Check Out -->
          <div class="tl-main-top-bar">
            <h2 class="tl-clinical-heading">Clinical Details</h2>
            @if (canWriteClinical()) {
              <button
                type="button"
                class="tl-btn tl-btn-dark tl-btn-checkout"
                data-testid="close-encounter-checkout-btn"
                (click)="openGenerateInvoiceModal()"
              >
                + Close Encounter &amp; Check Out
              </button>
            }
          </div>

          <!-- 1. Patient History & Examination Section -->
          <section class="tl-section-card">
            <div class="tl-section-head">
              <h3 class="tl-section-title">Patient History &amp; Examination</h3>
              @if (canWriteClinical()) {
                <button
                  type="button"
                  class="tl-btn tl-btn-teal tl-btn-add"
                  data-testid="add-patient-history-btn"
                  (click)="openHistoryWizard()"
                >
                  + Add Patient History
                </button>
              }
            </div>
            @if (pendingIntakeRecord(); as pending) {
              <div class="tl-intake-pending-bar" data-testid="encounter-pending-intake-banner">
                <div>
                  <strong>استبيان المريض من البوابة بانتظار المراجعة والاعتماد / Patient Portal Intake Pending Approval</strong>
                  <div class="tl-muted">
                    {{ pending.name }} — {{ pending.chiefComplaint.primaryComplaint || 'تمت تعبئة الخطوات 1–4' }}
                  </div>
                </div>
                @if (canWriteClinical()) {
                  <button
                    type="button"
                    class="tl-btn tl-btn-teal"
                    data-testid="review-pending-intake-btn"
                    (click)="editHistoryRecord(pending)"
                  >
                    مراجعة واعتماد نموذج المريض / Review &amp; Approve
                  </button>
                }
              </div>
            }
            <div class="tl-table-wrap">
              <table class="tl-teal-table" data-testid="patient-history-table">
                <thead>
                  <tr>
                    <th>Name</th>
                    <th>Date</th>
                    <th>Treatment Details</th>
                    <th>Radiograph Type</th>
                    <th>Radiograph Findings</th>
                    <th>Status / Is Complete</th>
                    <th>Action</th>
                  </tr>
                </thead>
                <tbody>
                  @if (historyRecords().length === 0) {
                    <tr>
                      <td colspan="7" class="tl-empty-cell tl-empty-cell--red">No records found</td>
                    </tr>
                  } @else {
                    @for (rec of historyRecords(); track rec.id) {
                      <tr>
                        <td>
                          {{ rec.name }}
                          @if (rec.source === 'PATIENT_PORTAL') {
                            <span class="tl-source-chip">Portal</span>
                          }
                        </td>
                        <td>{{ rec.date }}</td>
                        <td>{{ rec.diagnosisAndPlan.proposedPlans.join(', ') || rec.chiefComplaint.primaryComplaint || rec.dentalChart.toothCondition || '—' }}</td>
                        <td>{{ rec.radiographs.types.join(', ') || '—' }}</td>
                        <td>{{ rec.radiographs.findings || '—' }}</td>
                        <td>
                          @if (rec.status === 'PENDING_APPROVAL') {
                            <span class="tl-complete-badge tl-badge-pending">Pending Approval</span>
                          } @else {
                            <span class="tl-complete-badge" [class.is-yes]="rec.isComplete">
                              {{ rec.isComplete ? 'Complete' : 'Approved' }}
                            </span>
                          }
                        </td>
                        <td>
                          <div class="tl-row-actions">
                            <button
                              type="button"
                              class="tl-action-icon"
                              title="Edit Patient History"
                              (click)="editHistoryRecord(rec)"
                            >
                              <svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 20h9"/><path d="M16.5 3.5a2.121 2.121 0 0 1 3 3L7 19l-4 1 1-4L16.5 3.5z"/></svg>
                            </button>
                            <button
                              type="button"
                              class="tl-action-icon tl-action-icon--danger"
                              title="Delete Patient History"
                              (click)="deleteHistoryRecord(rec.id)"
                            >
                              <svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>
                            </button>
                          </div>
                        </td>
                      </tr>
                    }
                  }
                </tbody>
              </table>
            </div>
          </section>

          <!-- 2. Chief Complaints, Observations & Notes 3-Column Section -->
          <div class="tl-three-col">
            <!-- Chief Complaints -->
            <section class="tl-col-card">
              <h4 class="tl-col-title">Chief Complaints</h4>
              @if (canWriteClinical()) {
                <p class="tl-col-hint">Note: Type and press enter to create new problem</p>
                <div class="tl-select-wrap">
                  <select class="tl-select-control" (change)="onSelectProblem($event)">
                    <option value="" disabled selected>Select Problems</option>
                    @for (opt of problemOptions; track opt) {
                      <option [value]="opt">{{ opt }}</option>
                    }
                  </select>
                </div>
              }
              @if (problemsList().length > 0) {
                <div class="tl-tag-list">
                  @for (prob of problemsList(); track prob; let i = $index) {
                    <div class="tl-tag-item">
                      <span>{{ idxLabel(i, prob) }}</span>
                      @if (canWriteClinical()) {
                        <button type="button" class="tl-tag-remove" (click)="removeProblem(i)">&times;</button>
                      }
                    </div>
                  }
                </div>
              }
            </section>

            <!-- Observations -->
            <section class="tl-col-card">
              <h4 class="tl-col-title">Observations</h4>
              @if (canWriteClinical()) {
                <p class="tl-col-hint">Note: Type and press enter to create new Observation</p>
                <div class="tl-select-wrap">
                  <select class="tl-select-control" (change)="onSelectObservation($event)">
                    <option value="" disabled selected>Select Observation</option>
                    @for (opt of observationOptions; track opt) {
                      <option [value]="opt">{{ opt }}</option>
                    }
                  </select>
                </div>
              }
              @if (observationsList().length > 0) {
                <div class="tl-tag-list">
                  @for (obs of observationsList(); track obs; let i = $index) {
                    <div class="tl-tag-item">
                      <span>{{ idxLabel(i, obs) }}</span>
                      @if (canWriteClinical()) {
                        <button type="button" class="tl-tag-remove" (click)="removeObservation(i)">&times;</button>
                      }
                    </div>
                  }
                </div>
              }
            </section>

            <!-- Notes -->
            <section class="tl-col-card">
              <h4 class="tl-col-title">Notes</h4>
              @if (canWriteClinical()) {
                <div class="tl-note-input-row">
                  <input
                    type="text"
                    class="tl-input tl-note-input"
                    [(ngModel)]="newQuickNoteText"
                    (keydown.enter)="addQuickNote()"
                    placeholder="Enter Notes"
                  />
                  <button type="button" class="tl-btn tl-btn-teal tl-btn-sm" (click)="addQuickNote()">+ Add</button>
                </div>
              }
              @if (quickNotesList().length > 0) {
                <div class="tl-tag-list tl-tag-list--notes">
                  @for (note of quickNotesList(); track note; let i = $index) {
                    <div class="tl-tag-item">
                      <span>{{ idxLabel(i, note) }}</span>
                      @if (canWriteClinical()) {
                        <button type="button" class="tl-tag-remove" (click)="removeQuickNote(i)">&times;</button>
                      }
                    </div>
                  }
                </div>
              }
            </section>
          </div>

          <!-- 3. Patient Records Section -->
          <section class="tl-section-card">
            <div class="tl-section-head">
              <div class="tl-section-title-group">
                <h3 class="tl-section-title">Patient Records &amp; Radiographs</h3>
                <span class="tl-section-counter">({{ radiographRecords().length }})</span>
              </div>
              @if (canWriteClinical()) {
                <div style="display:flex;gap:8px;flex-wrap:wrap;">
                  <button type="button" class="tl-btn tl-btn-outline" (click)="openOrderImagingModal()">
                    + Order Dental Imaging
                  </button>
                  <button type="button" class="tl-btn tl-btn-teal tl-btn-add" (click)="openAddRecordModal()">
                    <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2.2"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
                    + Add Patient Record
                  </button>
                </div>
              }
            </div>

            @if (patientImagingRequests().length > 0) {
              <div style="margin-bottom:12px;padding:10px 12px;background:#f0fdfa;border:1px solid #99f6e4;border-radius:8px;">
                <div style="font-size:12px;font-weight:700;color:#0f766e;margin-bottom:6px;">
                  External &amp; Cloud Imaging Orders ({{ patientImagingRequests().length }})
                </div>
                <div style="display:flex;flex-direction:column;gap:6px;">
                  @for (imgReq of patientImagingRequests(); track imgReq.id) {
                    <div style="display:flex;justify-content:space-between;align-items:center;font-size:12px;background:#ffffff;padding:6px 10px;border-radius:6px;border:1px solid var(--line);">
                      <div>
                        <strong>{{ imgReq.imagingType }}</strong> × {{ imgReq.proceduresCount }}
                        · {{ imgReq.requestDate }}
                        @if (imgReq.labName) { · Lab: {{ imgReq.labName }} }
                        @if (imgReq.teethSummary) { · Teeth: {{ imgReq.teethSummary }} }
                      </div>
                      <div style="display:flex;align-items:center;gap:8px;">
                        <span class="tl-badge-type">{{ imgReq.status }}</span>
                        @if (imgReq.cloudStorageUrl) {
                          <a [href]="imgReq.cloudStorageUrl" target="_blank" rel="noopener" style="color:#0d9488;font-weight:600;">
                            Cloud Study ↗
                          </a>
                          <button type="button" class="tl-btn tl-btn-outline" style="padding:2px 8px;font-size:11px;" (click)="openCloudImagingInViewer(imgReq)">
                            Inspect in MPR
                          </button>
                        }
                      </div>
                    </div>
                  }
                </div>
              </div>
            }

            @if (radiographRecords().length === 0) {
              <div class="tl-empty-records">
                <div class="tl-empty-records-icon">
                  <svg viewBox="0 0 24 24" width="40" height="40" fill="none" stroke="currentColor" stroke-width="1.6">
                    <rect x="3" y="3" width="18" height="18" rx="2" ry="2"/>
                    <circle cx="8.5" cy="8.5" r="1.5"/>
                    <polyline points="21 15 16 10 5 21"/>
                  </svg>
                </div>
                <p class="tl-empty-records-title">No radiographs or clinical files attached</p>
                <p class="tl-empty-records-sub">Attach OPG panoramic, Bitewing, Periapical x-rays, DICOM scans, or medical reports to this patient record.</p>
                @if (canWriteClinical()) {
                  <button type="button" class="tl-btn tl-btn-sm tl-btn-teal" (click)="openAddRecordModal()">
                    Upload File / Scan
                  </button>
                }
              </div>
            } @else {
              <div class="tl-table-wrap">
                <table class="tl-teal-table tl-records-table">
                  <thead>
                    <tr>
                      <th style="width: 56px;">Preview</th>
                      <th>Type</th>
                      <th>File Name</th>
                      <th>Date</th>
                      <th>Clinical Notes</th>
                      <th style="width: 100px; text-align: center;">Actions</th>
                    </tr>
                  </thead>
                  <tbody>
                    @for (rec of radiographRecords(); track rec.id) {
                      <tr>
                        <td>
                          <div class="tl-media-thumb" (click)="viewRadiograph(rec)">
                            @if (rec.thumbnailUrl || rec.fileUrl) {
                              <img [src]="rec.thumbnailUrl || rec.fileUrl" [alt]="rec.fileName" />
                            } @else {
                              <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="1.8">
                                <rect x="3" y="3" width="18" height="18" rx="2" ry="2"/>
                                <circle cx="8.5" cy="8.5" r="1.5"/>
                                <polyline points="21 15 16 10 5 21"/>
                              </svg>
                            }
                            <span class="tl-thumb-overlay" title="Click to view">
                              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
                            </span>
                          </div>
                        </td>
                        <td>
                          <div class="tl-type-badges">
                            <span class="tl-badge-type">{{ rec.radiographType }}</span>
                            @if (rec.toothFdi) {
                              <span class="tl-badge-fdi">FDI {{ rec.toothFdi }}</span>
                            }
                          </div>
                        </td>
                        <td>
                          <div class="tl-file-cell">
                            <span class="tl-file-cell-name" [title]="rec.fileName">{{ rec.fileName }}</span>
                            @if (rec.fileSize) {
                              <span class="tl-file-cell-size">{{ rec.fileSize }}</span>
                            }
                          </div>
                        </td>
                        <td class="tl-date-cell">{{ rec.date }}</td>
                        <td class="tl-notes-cell">
                          @if (rec.notes) {
                            <span class="tl-notes-text" [title]="rec.notes">{{ rec.notes }}</span>
                          } @else {
                            <span class="tl-muted-dash">—</span>
                          }
                        </td>
                        <td>
                          <div class="tl-row-actions">
                            <button
                              type="button"
                              class="tl-action-icon tl-action-icon--view"
                              title="View Radiograph"
                              (click)="viewRadiograph(rec)"
                            >
                              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>
                            </button>
                            @if (rec.fileUrl || rec.thumbnailUrl) {
                              <button
                                type="button"
                                class="tl-action-icon tl-action-icon--download"
                                title="Download File"
                                (click)="downloadRecordFile(rec)"
                              >
                                <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
                              </button>
                            }
                            <button
                              type="button"
                              class="tl-action-icon tl-action-icon--danger"
                              title="Delete Record"
                              (click)="deleteRadiographRecord(rec.id)"
                            >
                              <svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>
                            </button>
                          </div>
                        </td>
                      </tr>
                    }
                  </tbody>
                </table>
              </div>
            }
          </section>

          <!-- 4. Follow Up Notes Section -->
          <section class="tl-section-card">
            <div class="tl-section-head">
              <h3 class="tl-section-title">Follow Up Notes</h3>
              <button type="button" class="tl-btn tl-btn-teal tl-btn-add" (click)="showAddFollowUpModal.set(true)">
                + Add Follow Up Note
              </button>
            </div>
            <div class="tl-table-wrap">
              <table class="tl-teal-table">
                <thead>
                  <tr>
                    <th>Date</th>
                    <th>Title</th>
                    <th>Note</th>
                    <th>Action</th>
                  </tr>
                </thead>
                <tbody>
                  @if (followUpNotes().length === 0) {
                    <tr>
                      <td colspan="4" class="tl-empty-cell tl-empty-cell--red">No follow up notes recorded.</td>
                    </tr>
                  } @else {
                    @for (item of followUpNotes(); track item.id) {
                      <tr>
                        <td>{{ item.date }}</td>
                        <td><strong>{{ item.title }}</strong></td>
                        <td>{{ item.note }}</td>
                        <td>
                          <div class="tl-row-actions">
                            <button
                              type="button"
                              class="tl-action-icon"
                              title="Book Follow-up Appointment (حجز موعد المتابعة)"
                              (click)="bookFollowUpAppointment(item)"
                            >
                              <svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2">
                                <rect x="3" y="4" width="18" height="18" rx="2" ry="2"></rect>
                                <line x1="16" y1="2" x2="16" y2="6"></line>
                                <line x1="8" y1="2" x2="8" y2="6"></line>
                                <line x1="3" y1="10" x2="21" y2="10"></line>
                              </svg>
                            </button>
                            <button
                              type="button"
                              class="tl-action-icon tl-action-icon--danger"
                              title="Delete"
                              (click)="deleteFollowUpNote(item.id)"
                            >
                              <svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>
                            </button>
                          </div>
                        </td>
                      </tr>
                    }
                  }
                </tbody>
              </table>
            </div>
          </section>
        </main>
      </div>

      <!-- Tooth Logic Brand Footer (Bottom Left) -->
      <footer class="tl-page-footer">
        <span>Tooth Logic.</span>
      </footer>

    <!-- ===================================================================== -->
      <!-- 9-STEP INTERACTIVE PATIENT HISTORY STEPPER MODAL (Screenshots 03–10)  -->
      <!-- ===================================================================== -->
      @if (showHistoryWizard()) {
        <div class="modal-backdrop" data-testid="patient-history-wizard-modal" (click)="closeHistoryWizard()">
          <div class="tl-wizard-modal" (click)="$event.stopPropagation()">
            <div class="tl-wizard-header">
              <h3>Patient History</h3>
              <button type="button" class="modal-close" (click)="closeHistoryWizard()">&times;</button>
            </div>

            <!-- 9-Step Horizontal Progress Stepper -->
            <div class="tl-stepper-bar">
              @for (s of historySteps; track s.step) {
                <button
                  type="button"
                  class="tl-step-item"
                  [class.is-active]="wizardStep() === s.step"
                  [class.is-done]="wizardStep() > s.step"
                  (click)="goToWizardStep(s.step)"
                >
                  <span class="tl-step-circle">
                    @if (wizardStep() > s.step) {
                      
                    } @else {
                      {{ s.step }}
                    }
                  </span>
                  <span class="tl-step-label">{{ s.label }}</span>
                </button>
              }
            </div>

            <!-- Step Body -->
            <div class="tl-wizard-body">
              @if (draftHistory.status === 'PENDING_APPROVAL') {
                <div class="tl-wizard-intake-review-banner" data-testid="wizard-pending-review-banner">
                  <div>
                    <strong>بيانات مُعبأة من المريض عبر البوابة (بانتظار الاعتماد)</strong>
                    <span> — يمكنك مراجعة الخطوات 1–4 وتعديل أي خانة، ثم الضغط على «اعتماد والمتابعة للفحص» لحفظها في ملف المريض والانتقال للخطوة 5.</span>
                  </div>
                  @if (wizardStep() <= 4) {
                    <button
                      type="button"
                      class="tl-btn tl-btn-teal tl-btn-sm"
                      [disabled]="savingHistory()"
                      (click)="approveAndContinueWizard()"
                    >
                      اعتماد والمتابعة للفحص (Step 5)
                    </button>
                  }
                </div>
              }
              <!-- STEP 1: DEMOGRAPHICS (Screenshot 03) -->
              @if (wizardStep() === 1) {
                <h4 class="tl-step-heading">I. Patient Demographic Information</h4>
                <div class="tl-form-grid">
                  <label class="form-field">
                    <span>Full Name</span>
                    <input type="text" class="tl-input" [(ngModel)]="draftHistory.demographics.fullName" placeholder="Full Name" />
                  </label>
                  <label class="form-field">
                    <span>Date of Birth</span>
                    <input type="date" class="tl-input" [(ngModel)]="draftHistory.demographics.dob" />
                  </label>
                  <div class="form-field">
                    <span>Gender</span>
                    <div class="tl-radio-row">
                      <label><input type="radio" name="wizGender" value="Male" [(ngModel)]="draftHistory.demographics.gender" /> Male</label>
                      <label><input type="radio" name="wizGender" value="Female" [(ngModel)]="draftHistory.demographics.gender" /> Female</label>
                    </div>
                  </div>
                  <label class="form-field">
                    <span>Occupation</span>
                    <input type="text" class="tl-input" [(ngModel)]="draftHistory.demographics.occupation" placeholder="Occupation" />
                  </label>
                  <label class="form-field tl-col-span-2">
                    <span>Address</span>
                    <textarea class="tl-textarea" rows="2" [(ngModel)]="draftHistory.demographics.address"></textarea>
                  </label>
                  <label class="form-field">
                    <span>Phone Number</span>
                    <input type="text" class="tl-input" [(ngModel)]="draftHistory.demographics.phone" />
                  </label>
                  <label class="form-field">
                    <span>Email Address</span>
                    <input type="email" class="tl-input" [(ngModel)]="draftHistory.demographics.email" />
                  </label>
                  <label class="form-field tl-col-span-2">
                    <span>Emergency Contact (Name &amp; Phone)</span>
                    <div class="form-row">
                      <input type="text" class="tl-input" [(ngModel)]="draftHistory.demographics.emergencyName" placeholder="Emergency Contact Name" />
                      <input type="text" class="tl-input" [(ngModel)]="draftHistory.demographics.emergencyPhone" placeholder="Emergency Contact Phone" />
                    </div>
                  </label>
                </div>
              }

              <!-- STEP 2: MEDICAL HISTORY (Screenshot 04) -->
              @if (wizardStep() === 2) {
                <h4 class="tl-step-heading">II. Medical History</h4>
                <div class="tl-form-stack">
                  <div class="tl-form-grid">
                    <div class="form-field">
                      <span>Are you currently under medical treatment?</span>
                      <div class="tl-radio-row">
                        <label><input type="radio" name="underTx" value="Yes" [(ngModel)]="draftHistory.medicalHistory.underTreatment" /> Yes</label>
                        <label><input type="radio" name="underTx" value="No" [(ngModel)]="draftHistory.medicalHistory.underTreatment" /> No</label>
                      </div>
                      <input type="text" class="tl-input" [(ngModel)]="draftHistory.medicalHistory.treatmentSpecify" placeholder="If yes, specify" />
                    </div>
                    <div class="form-field">
                      <span>Have you been hospitalized in the last year?</span>
                      <div class="tl-radio-row">
                        <label><input type="radio" name="hospLastYr" value="Yes" [(ngModel)]="draftHistory.medicalHistory.hospitalizedLastYear" /> Yes</label>
                        <label><input type="radio" name="hospLastYr" value="No" [(ngModel)]="draftHistory.medicalHistory.hospitalizedLastYear" /> No</label>
                      </div>
                      <input type="text" class="tl-input" [(ngModel)]="draftHistory.medicalHistory.hospitalizedReason" placeholder="Reason" />
                    </div>
                  </div>

                  <div class="form-field">
                    <span>Do you have or have you ever had any of the following? (Check all that apply)</span>
                    <div class="tl-check-grid">
                      @for (cond of medicalConditions; track cond) {
                        <label class="tl-check-label">
                          <input
                            type="checkbox"
                            [checked]="draftHistory.medicalHistory.conditions.includes(cond)"
                            (change)="toggleArrayItem(draftHistory.medicalHistory.conditions, cond)"
                          />
                          {{ cond }}
                        </label>
                      }
                    </div>
                  </div>

                  <div class="form-field">
                    <span>Are you pregnant or breastfeeding?</span>
                    <div class="tl-radio-row">
                      <label><input type="radio" name="preg" value="Yes" [(ngModel)]="draftHistory.medicalHistory.pregnantOrBreastfeeding" /> Yes</label>
                      <label><input type="radio" name="preg" value="No" [(ngModel)]="draftHistory.medicalHistory.pregnantOrBreastfeeding" /> No</label>
                      <label><input type="radio" name="preg" value="N/A" [(ngModel)]="draftHistory.medicalHistory.pregnantOrBreastfeeding" /> N/A</label>
                    </div>
                  </div>

                  <div class="form-field">
                    <span>Are you taking any of the following medications?</span>
                    <div class="tl-check-grid">
                      @for (med of medicationTypes; track med) {
                        <label class="tl-check-label">
                          <input
                            type="checkbox"
                            [checked]="draftHistory.medicalHistory.medications.includes(med)"
                            (change)="toggleArrayItem(draftHistory.medicalHistory.medications, med)"
                          />
                          {{ med }}
                        </label>
                      }
                    </div>
                    <input type="text" class="tl-input" [(ngModel)]="draftHistory.medicalHistory.otherMedications" placeholder="Specify other medications" />
                  </div>

                  <div class="form-field">
                    <span>Allergies (Check all that apply)</span>
                    <div class="tl-check-grid">
                      @for (alg of allergyTypes; track alg) {
                        <label class="tl-check-label">
                          <input
                            type="checkbox"
                            [checked]="draftHistory.medicalHistory.allergies.includes(alg)"
                            (change)="toggleArrayItem(draftHistory.medicalHistory.allergies, alg)"
                          />
                          {{ alg }}
                        </label>
                      }
                    </div>
                    <input type="text" class="tl-input" [(ngModel)]="draftHistory.medicalHistory.otherAllergies" placeholder="Specify other allergies" />
                  </div>
                </div>
              }

              <!-- STEP 3: DENTAL HISTORY (Screenshot 05) -->
              @if (wizardStep() === 3) {
                <h4 class="tl-step-heading">III. Dental History</h4>
                <div class="tl-form-stack">
                  <div class="tl-form-grid">
                    <label class="form-field">
                      <span>Date of Last Dental Visit</span>
                      <input type="date" class="tl-input" [(ngModel)]="draftHistory.dentalHistory.lastDentalVisitDate" />
                    </label>
                    <label class="form-field">
                      <span>Reason for Last Visit</span>
                      <input type="text" class="tl-input" [(ngModel)]="draftHistory.dentalHistory.lastVisitReason" placeholder="e.g., Routine Checkup, Scaling" />
                    </label>
                  </div>
                  <div class="form-field">
                    <span>Do you experience any of the following?</span>
                    <div class="tl-check-grid">
                      @for (sym of dentalSymptoms; track sym) {
                        <label class="tl-check-label">
                          <input
                            type="checkbox"
                            [checked]="draftHistory.dentalHistory.symptoms.includes(sym)"
                            (change)="toggleArrayItem(draftHistory.dentalHistory.symptoms, sym)"
                          />
                          {{ sym }}
                        </label>
                      }
                    </div>
                  </div>
                  <label class="form-field">
                    <span>Past Dental Procedures / Complications</span>
                    <textarea class="tl-textarea" rows="2" [(ngModel)]="draftHistory.dentalHistory.pastProcedures" placeholder="Describe any past dental treatments or complications..."></textarea>
                  </label>
                  <label class="form-field">
                    <span>Dental Anxiety Level (1 = Calm, 5 = Severe Anxiety)</span>
                    <select class="tl-input" [(ngModel)]="draftHistory.dentalHistory.anxietyLevel">
                      <option [ngValue]="1">1 - Minimal / Calm</option>
                      <option [ngValue]="2">2 - Mild</option>
                      <option [ngValue]="3">3 - Moderate</option>
                      <option [ngValue]="4">4 - High</option>
                      <option [ngValue]="5">5 - Severe</option>
                    </select>
                  </label>
                </div>
              }

              <!-- STEP 4: CHIEF COMPLAINT -->
              @if (wizardStep() === 4) {
                <h4 class="tl-step-heading">IV. Chief Complaint</h4>
                <div class="tl-form-stack">
                  <label class="form-field">
                    <span>Patient's Primary Chief Complaint</span>
                    <textarea class="tl-textarea" rows="3" [(ngModel)]="draftHistory.chiefComplaint.primaryComplaint" placeholder="Describe the main reason for today's visit..."></textarea>
                  </label>
                  <div class="tl-form-grid">
                    <label class="form-field">
                      <span>Pain Scale (0 – 10)</span>
                      <input type="number" min="0" max="10" class="tl-input" [(ngModel)]="draftHistory.chiefComplaint.painScale" />
                    </label>
                    <label class="form-field">
                      <span>Onset &amp; Duration</span>
                      <input type="text" class="tl-input" [(ngModel)]="draftHistory.chiefComplaint.duration" placeholder="e.g., 3 days, intermittent" />
                    </label>
                  </div>
                </div>
              }

              <!-- STEP 5: CLINICAL EXAM (Screenshot 07) -->
              @if (wizardStep() === 5) {
                <h4 class="tl-step-heading">V. Clinical Examination</h4>
                <div class="tl-form-stack">
                  <div class="tl-sub-heading">Extraoral Examination</div>
                  <div class="tl-form-grid">
                    <div class="form-field">
                      <span>Face Symmetry</span>
                      <div class="tl-radio-row">
                        <label><input type="radio" name="faceSym" value="Normal" [(ngModel)]="draftHistory.clinicalExam.faceSymmetry" /> Normal</label>
                        <label><input type="radio" name="faceSym" value="Abnormal" [(ngModel)]="draftHistory.clinicalExam.faceSymmetry" /> Abnormal</label>
                      </div>
                      <input type="text" class="tl-input" [(ngModel)]="draftHistory.clinicalExam.faceSymmetryDetails" placeholder="Details" />
                    </div>
                    <div class="form-field">
                      <span>Temporomandibular Joint (TMJ)</span>
                      <div class="tl-radio-row">
                        <label><input type="radio" name="tmj" value="Normal" [(ngModel)]="draftHistory.clinicalExam.tmj" /> Normal</label>
                        <label><input type="radio" name="tmj" value="Clicking" [(ngModel)]="draftHistory.clinicalExam.tmj" /> Clicking</label>
                        <label><input type="radio" name="tmj" value="Pain" [(ngModel)]="draftHistory.clinicalExam.tmj" /> Pain</label>
                        <label><input type="radio" name="tmj" value="Limited Opening" [(ngModel)]="draftHistory.clinicalExam.tmj" /> Limited Opening</label>
                      </div>
                    </div>
                  </div>

                  <div class="tl-sub-heading">Intraoral Examination</div>
                  <div class="tl-form-grid">
                    <div class="form-field">
                      <span>Soft Tissues (Lips, Tongue, Palate, Floor of Mouth, Mucosa)</span>
                      <div class="tl-radio-row">
                        <label><input type="radio" name="softTis" value="Normal" [(ngModel)]="draftHistory.clinicalExam.softTissues" /> Normal</label>
                        <label><input type="radio" name="softTis" value="Abnormalities" [(ngModel)]="draftHistory.clinicalExam.softTissues" /> Abnormalities</label>
                      </div>
                      <input type="text" class="tl-input" [(ngModel)]="draftHistory.clinicalExam.softTissuesDetails" placeholder="Details" />
                    </div>
                    <div class="form-field">
                      <span>Teeth</span>
                      <div class="tl-check-grid">
                        @for (tf of ['Missing Teeth', 'Caries', 'Restorations', 'Wear (Attrition/Abrasion/Erosion)']; track tf) {
                          <label class="tl-check-label">
                            <input
                              type="checkbox"
                              [checked]="draftHistory.clinicalExam.teethFindings.includes(tf)"
                              (change)="toggleArrayItem(draftHistory.clinicalExam.teethFindings, tf)"
                            />
                            {{ tf }}
                          </label>
                        }
                      </div>
                    </div>
                  </div>

                  <div class="tl-form-grid">
                    <div class="form-field">
                      <span>Gingival/Periodontal Health</span>
                      <div class="tl-radio-row">
                        <label><input type="radio" name="ging" value="Healthy" [(ngModel)]="draftHistory.clinicalExam.gingivalHealth" /> Healthy</label>
                        <label><input type="radio" name="ging" value="Gingivitis" [(ngModel)]="draftHistory.clinicalExam.gingivalHealth" /> Gingivitis</label>
                        <label><input type="radio" name="ging" value="Periodontitis" [(ngModel)]="draftHistory.clinicalExam.gingivalHealth" /> Periodontitis</label>
                      </div>
                    </div>
                    <div class="form-field">
                      <span>Bleeding on Probing</span>
                      <div class="tl-radio-row">
                        <label><input type="radio" name="bop" value="Yes" [(ngModel)]="draftHistory.clinicalExam.bleedingOnProbing" /> Yes</label>
                        <label><input type="radio" name="bop" value="No" [(ngModel)]="draftHistory.clinicalExam.bleedingOnProbing" /> No</label>
                      </div>
                    </div>
                  </div>

                  <div class="tl-form-grid">
                    <label class="form-field">
                      <span>Pocket Depths (if applicable)</span>
                      <input type="text" class="tl-input" [(ngModel)]="draftHistory.clinicalExam.pocketDepths" />
                    </label>
                    <div class="form-field">
                      <span>Mobility</span>
                      <div class="tl-radio-row">
                        <label><input type="radio" name="mob" value="Present" [(ngModel)]="draftHistory.clinicalExam.mobility" /> Present</label>
                        <label><input type="radio" name="mob" value="Absent" [(ngModel)]="draftHistory.clinicalExam.mobility" /> Absent</label>
                      </div>
                    </div>
                  </div>

                  <div class="tl-form-grid">
                    <div class="form-field">
                      <span>Occlusion (Bite)</span>
                      <div class="tl-radio-row">
                        @for (occ of ['Normal', 'Malocclusion', 'Class I', 'Class II', 'Class III']; track occ) {
                          <label><input type="radio" name="occ" [value]="occ" [(ngModel)]="draftHistory.clinicalExam.occlusion" /> {{ occ }}</label>
                        }
                      </div>
                    </div>
                    <div class="form-field">
                      <span>Signs of bruxism or clenching</span>
                      <div class="tl-radio-row">
                        <label><input type="radio" name="brux" value="Yes" [(ngModel)]="draftHistory.clinicalExam.bruxism" /> Yes</label>
                        <label><input type="radio" name="brux" value="No" [(ngModel)]="draftHistory.clinicalExam.bruxism" /> No</label>
                      </div>
                    </div>
                  </div>
                </div>
              }

              <!-- STEP 6: RADIOGRAPHS (Screenshot 08) -->
              @if (wizardStep() === 6) {
                <h4 class="tl-step-heading">VI. Radiographic Examination</h4>
                <div class="tl-form-stack">
                  <div class="form-field">
                    <span>Radiograph Type</span>
                    <div class="tl-check-grid">
                      @for (rt of radiographTypes; track rt) {
                        <label class="tl-check-label">
                          <input
                            type="checkbox"
                            [checked]="draftHistory.radiographs.types.includes(rt)"
                            (change)="toggleArrayItem(draftHistory.radiographs.types, rt)"
                          />
                          {{ rt }}
                        </label>
                      }
                    </div>
                  </div>
                  <label class="form-field">
                    <span>Findings</span>
                    <textarea class="tl-textarea" rows="3" [(ngModel)]="draftHistory.radiographs.findings"></textarea>
                  </label>
                </div>
              }

              <!-- STEP 7: DIAGNOSIS & PLAN (Screenshot 09) -->
              @if (wizardStep() === 7) {
                <h4 class="tl-step-heading">VII. Diagnosis &amp; Treatment Plan</h4>
                <div class="tl-form-stack">
                  <label class="form-field">
                    <span>Diagnosis</span>
                    <textarea class="tl-textarea" rows="2" [(ngModel)]="draftHistory.diagnosisAndPlan.diagnosis"></textarea>
                  </label>
                  <div class="form-field">
                    <span>Proposed Treatment Plan</span>
                    <div class="tl-check-grid">
                      @for (pp of proposedPlans; track pp) {
                        <label class="tl-check-label">
                          <input
                            type="checkbox"
                            [checked]="draftHistory.diagnosisAndPlan.proposedPlans.includes(pp)"
                            (change)="toggleArrayItem(draftHistory.diagnosisAndPlan.proposedPlans, pp)"
                          />
                          {{ pp }}
                        </label>
                      }
                    </div>
                    <input type="text" class="tl-input" [(ngModel)]="draftHistory.diagnosisAndPlan.otherPlan" placeholder="Specify other" />
                  </div>
                  <label class="form-field">
                    <span>Planned Timeline</span>
                    <textarea class="tl-textarea" rows="2" [(ngModel)]="draftHistory.diagnosisAndPlan.plannedTimeline"></textarea>
                  </label>
                  <div class="tl-form-grid">
                    <div class="form-field">
                      <span>Alternative Options Discussed</span>
                      <div class="tl-radio-row">
                        <label><input type="radio" name="altOpt" value="Yes" [(ngModel)]="draftHistory.diagnosisAndPlan.alternativesDiscussed" /> Yes</label>
                        <label><input type="radio" name="altOpt" value="No" [(ngModel)]="draftHistory.diagnosisAndPlan.alternativesDiscussed" /> No</label>
                      </div>
                    </div>
                    <div class="form-field">
                      <span>Risks/Benefits Explained</span>
                      <div class="tl-radio-row">
                        <label><input type="radio" name="riskExp" value="Yes" [(ngModel)]="draftHistory.diagnosisAndPlan.risksExplained" /> Yes</label>
                        <label><input type="radio" name="riskExp" value="No" [(ngModel)]="draftHistory.diagnosisAndPlan.risksExplained" /> No</label>
                      </div>
                    </div>
                    <div class="form-field">
                      <span>Patient Questions Addressed</span>
                      <div class="tl-radio-row">
                        <label><input type="radio" name="qAddr" value="Yes" [(ngModel)]="draftHistory.diagnosisAndPlan.questionsAddressed" /> Yes</label>
                        <label><input type="radio" name="qAddr" value="No" [(ngModel)]="draftHistory.diagnosisAndPlan.questionsAddressed" /> No</label>
                      </div>
                    </div>
                  </div>
                </div>
              }

              <!-- STEP 8: DENTAL CHART -->
              @if (wizardStep() === 8) {
                <h4 class="tl-step-heading">VIII. Dental Charting Selection</h4>
                <div class="tl-form-stack">
                  <div class="tl-chart-quadrants">
                    <div class="tl-quad-box">
                      <span class="tl-quad-title">UR (Upper Right)</span>
                      <div class="tl-tooth-circles">
                        @for (t of upperRightTeeth; track t) {
                          <button
                            type="button"
                            class="tl-tooth-circle"
                            [class.is-selected]="draftHistory.dentalChart.selectedTeeth.includes(t)"
                            (click)="toggleToothInWizard(t)"
                          >
                            {{ t }}
                          </button>
                        }
                      </div>
                    </div>
                    <div class="tl-quad-box">
                      <span class="tl-quad-title">UL (Upper Left)</span>
                      <div class="tl-tooth-circles">
                        @for (t of upperLeftTeeth; track t) {
                          <button
                            type="button"
                            class="tl-tooth-circle"
                            [class.is-selected]="draftHistory.dentalChart.selectedTeeth.includes(t)"
                            (click)="toggleToothInWizard(t)"
                          >
                            {{ t }}
                          </button>
                        }
                      </div>
                    </div>
                    <div class="tl-quad-box">
                      <span class="tl-quad-title">LR (Lower Right)</span>
                      <div class="tl-tooth-circles">
                        @for (t of lowerRightTeeth; track t) {
                          <button
                            type="button"
                            class="tl-tooth-circle"
                            [class.is-selected]="draftHistory.dentalChart.selectedTeeth.includes(t)"
                            (click)="toggleToothInWizard(t)"
                          >
                            {{ t }}
                          </button>
                        }
                      </div>
                    </div>
                    <div class="tl-quad-box">
                      <span class="tl-quad-title">LL (Lower Left)</span>
                      <div class="tl-tooth-circles">
                        @for (t of lowerLeftTeeth; track t) {
                          <button
                            type="button"
                            class="tl-tooth-circle"
                            [class.is-selected]="draftHistory.dentalChart.selectedTeeth.includes(t)"
                            (click)="toggleToothInWizard(t)"
                          >
                            {{ t }}
                          </button>
                        }
                      </div>
                    </div>
                  </div>
                  <label class="form-field">
                    <span>Selected Teeth Condition / Charting Notes</span>
                    <textarea class="tl-textarea" rows="2" [(ngModel)]="draftHistory.dentalChart.chartNotes" placeholder="Notes on selected teeth..."></textarea>
                  </label>
                </div>
              }

              <!-- STEP 9: INFORMED CONSENT & DUAL SIGNATURE PADS (Screenshot 10) -->
              @if (wizardStep() === 9) {
                <h4 class="tl-step-heading">IX. Informed Consent</h4>
                <p class="tl-consent-copy">
                  I hereby acknowledge that I have provided an accurate medical and dental history. I have been informed of the diagnosis, proposed treatment plan, alternatives, and associated risks, and I consent to the dental treatment.
                </p>
                <div class="tl-sig-grid">
                  <!-- Patient Signature Pad -->
                  <div class="tl-sig-box">
                    <span class="tl-sig-label">Patient Signature</span>
                    <div
                      class="tl-sig-canvas"
                      [class.is-signed]="draftHistory.informedConsent.patientSignatureSaved"
                      (click)="signPatientPad()"
                    >
                      @if (draftHistory.informedConsent.patientSignatureDataUrl) {
                        <span class="tl-sig-script">{{ draftHistory.informedConsent.patientSignatureDataUrl }}</span>
                      } @else {
                        <span class="tl-sig-placeholder">Click or draw to sign as {{ draftHistory.demographics.fullName || currentPatientName() }}</span>
                      }
                    </div>
                    <div class="tl-sig-actions">
                      <button type="button" class="tl-btn tl-btn-gray tl-btn-sm" (click)="clearPatientPad()">Clear</button>
                      <button type="button" class="tl-btn tl-btn-teal tl-btn-sm" (click)="savePatientPad()">Save</button>
                    </div>
                  </div>

                  <!-- Dentist Signature Pad -->
                  <div class="tl-sig-box">
                    <span class="tl-sig-label">Dentist Signature</span>
                    <div
                      class="tl-sig-canvas"
                      [class.is-signed]="draftHistory.informedConsent.dentistSignatureSaved"
                      (click)="signDentistPad()"
                    >
                      @if (draftHistory.informedConsent.dentistSignatureDataUrl) {
                        <span class="tl-sig-script">{{ draftHistory.informedConsent.dentistSignatureDataUrl }}</span>
                      } @else {
                        <span class="tl-sig-placeholder">Click or draw to sign as {{ currentDoctorName() }}</span>
                      }
                    </div>
                    <div class="tl-sig-actions">
                      <button type="button" class="tl-btn tl-btn-gray tl-btn-sm" (click)="clearDentistPad()">Clear</button>
                      <button type="button" class="tl-btn tl-btn-teal tl-btn-sm" (click)="saveDentistPad()">Save</button>
                    </div>
                  </div>
                </div>
              }
            </div>

            <!-- Wizard Footer -->
            <div class="tl-wizard-footer">
              <div>
                @if (wizardStep() > 1) {
                  <button type="button" class="tl-btn tl-btn-dark" (click)="prevWizardStep()">Previous</button>
                }
              </div>
              <div class="tl-wizard-footer-right">
                @if (draftHistory.status === 'PENDING_APPROVAL' && wizardStep() <= 4) {
                  <button
                    type="button"
                    class="tl-btn tl-btn-teal"
                    data-testid="wizard-approve-continue-btn"
                    [disabled]="savingHistory()"
                    (click)="approveAndContinueWizard()"
                  >
                    {{ savingHistory() ? 'Saving...' : 'اعتماد والمتابعة (Approve & Next)' }}
                  </button>
                }
                <button
                  type="button"
                  class="tl-btn tl-btn-green"
                  data-testid="wizard-save-btn"
                  [disabled]="savingHistory()"
                  (click)="saveHistoryWizard(wizardStep() === 9)"
                >
                  {{ savingHistory() ? 'Saving...' : 'Save' }}
                </button>
                @if (wizardStep() < 9) {
                  <button
                    type="button"
                    class="tl-btn tl-btn-teal"
                    data-testid="wizard-next-btn"
                    (click)="nextWizardStep()"
                  >
                    Next
                  </button>
                }
              </div>
            </div>
          </div>
        </div>
      }

      <!-- ===================================================================== -->
      <!-- GENERATE INVOICE & INSTALLMENTS MODAL (Screenshot 11)                 -->
      <!-- ===================================================================== -->
      @if (showGenerateInvoiceModal()) {
        <div class="modal-backdrop" data-testid="generate-invoice-modal" (click)="showGenerateInvoiceModal.set(false)">
          <div class="tl-wizard-modal" (click)="$event.stopPropagation()">
            <div class="tl-wizard-header">
              <div class="tl-invoice-tabs-header">
                <button
                  type="button"
                  class="tl-tab-pill"
                  [class.is-active]="!invoiceEstimateOnly"
                  (click)="invoiceEstimateOnly = false"
                >
                  <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2">
                    <rect x="2" y="5" width="20" height="14" rx="2"></rect>
                    <line x1="2" y1="10" x2="22" y2="10"></line>
                  </svg>
                  Invoice (فاتورة علاج)
                </button>
                <button
                  type="button"
                  class="tl-tab-pill"
                  [class.is-active]="invoiceEstimateOnly"
                  (click)="invoiceEstimateOnly = true"
                >
                  <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2">
                    <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"></path>
                    <polyline points="14 2 14 8 20 8"></polyline>
                    <line x1="16" y1="13" x2="8" y2="13"></line>
                    <line x1="16" y1="17" x2="8" y2="17"></line>
                  </svg>
                  Estimate (عرض سعر تقديري)
                </button>
              </div>
              <button type="button" class="modal-close" (click)="showGenerateInvoiceModal.set(false)">&times;</button>
            </div>

            <div class="tl-wizard-body">
              @if (invoiceEstimateOnly) {
                <div class="tl-estimate-banner">
                  <div class="tl-estimate-icon">
                    <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2">
                      <circle cx="12" cy="12" r="10"></circle>
                      <line x1="12" y1="16" x2="12" y2="12"></line>
                      <line x1="12" y1="8" x2="12.01" y2="8"></line>
                    </svg>
                  </div>
                  <div>
                    <strong>Treatment Cost Estimate (عرض سعر تقديري لخطة العلاج المقترحة)</strong>
                    <p>هذا المستند يمثل خطة التكلفة التقديرية للإجراءات السريرية، صالح لمدة 30 يوماً، ولا يُلزم المريض بدفع فوري ولا يُنشئ ذمة مالية.</p>
                  </div>
                </div>
              }
              <!-- Add Item In Billing -->
              <div class="tl-billing-box">
                <h4 class="tl-sub-heading">Add Item In Billing</h4>
                <div class="tl-billing-form-row">
                  <label class="form-field">
                    <span>Service *</span>
                    <input type="text" class="tl-input" [(ngModel)]="newBillingService" placeholder="Scaling and Polishing" />
                  </label>
                  <label class="form-field">
                    <span>Price *</span>
                    <input type="number" class="tl-input" [(ngModel)]="newBillingPrice" min="0" />
                  </label>
                  <label class="form-field">
                    <span>Quantity *</span>
                    <input type="number" class="tl-input" [(ngModel)]="newBillingQty" min="1" />
                  </label>
                  <label class="form-field">
                    <span>Total Amount *</span>
                    <input type="number" class="tl-input" [value]="(newBillingPrice || 0) * (newBillingQty || 1)" readonly />
                  </label>
                </div>
                <div class="tl-billing-save-row">
                  <button type="button" class="tl-btn tl-btn-teal" (click)="addBillingItemRow()">Save</button>
                </div>
              </div>

              <!-- Services Table -->
              <h4 class="tl-sub-heading">Services</h4>
              <table class="tl-teal-table">
                <thead>
                  <tr>
                    <th>Sr No</th>
                    <th>Services</th>
                    <th>Discount</th>
                    <th>Quantity</th>
                    <th>Price</th>
                    <th>Total Amount</th>
                    <th>Action</th>
                  </tr>
                </thead>
                <tbody>
                  @for (row of billingItems(); track row.id; let idx = $index) {
                    <tr>
                      <td>{{ idx + 1 }}</td>
                      <td>{{ row.serviceName }}</td>
                      <td>{{ row.discount }}</td>
                      <td>{{ row.quantity }}</td>
                      <td>{{ row.price | number:'1.2-2' }}</td>
                      <td><strong>{{ row.totalAmount | number:'1.2-2' }}</strong></td>
                      <td>
                        <button type="button" class="tl-action-icon tl-action-icon--danger" (click)="removeBillingItemRow(row.id)">
                          <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true" focusable="false">
                            <polyline points="3 6 5 6 21 6"></polyline>
                            <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"></path>
                          </svg>
                        </button>
                      </td>
                    </tr>
                  }
                </tbody>
              </table>

              <!-- Discount Toggle & Tax Table -->
              <div class="tl-discount-row">
                <label class="tl-check-label">
                  <input type="checkbox" [(ngModel)]="enableBillingDiscount" />
                  <strong>Discount</strong>
                </label>
                @if (enableBillingDiscount) {
                  <input
                    type="number"
                    class="tl-input"
                    style="max-width: 160px;"
                    [(ngModel)]="billingDiscountAmount"
                    min="0"
                    placeholder="Discount amount"
                  />
                }
              </div>

              <h4 class="tl-sub-heading">Tax</h4>
              <table class="tl-teal-table">
                <thead>
                  <tr>
                    <th>Sr No</th>
                    <th>Name</th>
                    <th>Tax</th>
                  </tr>
                </thead>
                <tbody>
                  <tr>
                    <td colspan="3" class="tl-empty-cell">No tax applied (0%)</td>
                  </tr>
                </tbody>
              </table>

              <!-- Total / Payable / Remaining Summary -->
              <div class="tl-billing-totals">
                <div>Total Amount: <strong>{{ billingTotalAmount() | number:'1.2-2' }}</strong></div>
                <div>Payable Amount: <strong>{{ billingPayableAmount() | number:'1.2-2' }}</strong></div>
                <div>Remaining Amount: <strong>{{ billingRemainingAmount() | number:'1.2-2' }}</strong></div>
              </div>

              <!-- Installments Section -->
              @if (!invoiceEstimateOnly) {
                <div class="tl-installment-section">
                  <div class="tl-section-head">
                    <h4 class="tl-sub-heading">Installments</h4>
                  </div>
                  <div class="tl-billing-form-row">
                    <label class="form-field">
                      <span>Amount *</span>
                      <input type="number" class="tl-input" [(ngModel)]="newInstallmentAmount" min="0" />
                    </label>
                    <label class="form-field">
                      <span>Payment Mode *</span>
                      <select class="tl-input" [(ngModel)]="newInstallmentMode">
                        <option value="Cash">Cash</option>
                        <option value="Card">Card</option>
                        <option value="Bank Transfer">Bank Transfer</option>
                      </select>
                    </label>
                    <label class="form-field">
                      <span>Date *</span>
                      <input type="date" class="tl-input" [(ngModel)]="newInstallmentDate" />
                    </label>
                    <div class="form-field" style="justify-content: flex-end;">
                      <button type="button" class="tl-btn tl-btn-teal" (click)="addInstallmentRow()">+ Add Installment</button>
                    </div>
                  </div>

                  @if (installmentRows().length > 0) {
                    <table class="tl-teal-table" style="margin-top: 10px;">
                      <thead>
                        <tr>
                          <th>Sr No</th>
                          <th>Amount</th>
                          <th>Payment Mode</th>
                          <th>Date</th>
                        </tr>
                      </thead>
                      <tbody>
                        @for (inst of installmentRows(); track inst.id; let idx = $index) {
                          <tr>
                            <td>{{ idx + 1 }}</td>
                            <td>{{ inst.amount | number:'1.2-2' }}</td>
                            <td>{{ inst.paymentMode }}</td>
                            <td>{{ inst.date }}</td>
                          </tr>
                        }
                      </tbody>
                    </table>
                  }
                </div>
              }
            </div>

            <div class="tl-wizard-footer">
              <button type="button" class="tl-btn tl-btn-gray" (click)="showGenerateInvoiceModal.set(false)">Cancel</button>
              @if (invoiceEstimateOnly) {
                <button
                  type="button"
                  class="tl-btn tl-btn-dark"
                  (click)="openEstimatePrintSheet()"
                >
                  <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2">
                    <polyline points="6 9 6 2 18 2 18 9"></polyline>
                    <path d="M6 18H4a2 2 0 0 1-2-2v-5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v5a2 2 0 0 1-2 2h-2"></path>
                    <rect x="6" y="14" width="12" height="8"></rect>
                  </svg>
                  Print Estimate (طباعة عرض السعر)
                </button>
                <button
                  type="button"
                  class="tl-btn tl-btn-teal"
                  (click)="convertEstimateToInvoice()"
                >
                  Convert to Invoice (تحويل لفاتورة فعلية)
                </button>
              } @else {
                <button
                  type="button"
                  class="tl-btn tl-btn-teal"
                  data-testid="submit-encounter-invoice-btn"
                  (click)="submitEncounterInvoice()"
                >
                  Save &amp; Close Encounter
                </button>
              }
            </div>
          </div>
        </div>
      }

      <!-- Add Patient Record (Radiograph & Media) Modal -->
      @if (showAddRecordModal()) {
        <div class="modal-backdrop" (click)="closeAddRecordModal()">
          <div class="modal-card modal-card--record-upload" (click)="$event.stopPropagation()">
            <div class="modal-header">
              <div class="modal-title-with-icon">
                <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2">
                  <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/>
                  <polyline points="17 8 12 3 7 8"/>
                  <line x1="12" y1="3" x2="12" y2="15"/>
                </svg>
                <h3>Upload Patient Radiograph / Record</h3>
              </div>
              <button type="button" class="modal-close" (click)="closeAddRecordModal()">&times;</button>
            </div>
            <div class="modal-body">
              <!-- Drag and Drop / File Picker Area -->
              <div class="form-field">
                <span>Select File / Scan Image *</span>
                <div
                  class="tl-file-dropzone"
                  [class.is-dragover]="isDragOverRecordFile"
                  [class.has-file]="!!selectedRecordFile"
                  (dragover)="onRecordDragOver($event)"
                  (dragleave)="isDragOverRecordFile = false"
                  (drop)="onRecordFileDrop($event)"
                >
                  <input
                    type="file"
                    #recordFileInput
                    class="tl-file-hidden-input"
                    accept="image/png,image/jpeg,image/webp,image/tiff,application/pdf,application/dicom,.dcm,.stl,.obj,.ply"
                    (change)="onRecordFileChosen($event)"
                  />
                  @if (!selectedRecordFile) {
                    <div class="tl-dropzone-content" (click)="recordFileInput.click()">
                      <div class="tl-dropzone-icon-circle">
                        <svg viewBox="0 0 24 24" width="28" height="28" fill="none" stroke="currentColor" stroke-width="2">
                          <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/>
                          <polyline points="17 8 12 3 7 8"/>
                          <line x1="12" y1="3" x2="12" y2="15"/>
                        </svg>
                      </div>
                      <div class="tl-dropzone-texts">
                        <p class="tl-dropzone-primary">Click to browse or drag &amp; drop file here</p>
                        <p class="tl-dropzone-secondary">Supports 3D Scans (.stl, .obj, .ply), DICOM (.dcm), X-Ray images &amp; PDF (up to 200MB)</p>
                      </div>
                    </div>
                  } @else {
                    <div class="tl-dropzone-file-preview">
                      @if (selectedRecordPreviewUrl) {
                        <img [src]="selectedRecordPreviewUrl" class="tl-file-thumb-large" alt="Preview" />
                      } @else if (is3DFile(selectedRecordFile.name)) {
                        <div class="tl-file-icon-placeholder tl-file-icon-placeholder--3d">
                          <svg viewBox="0 0 24 24" width="28" height="28" fill="none" stroke="#38bdf8" stroke-width="2">
                            <polygon points="12 2 2 7 12 12 22 7 12 2"/>
                            <polyline points="2 17 12 22 22 17"/>
                            <polyline points="2 12 12 17 22 12"/>
                          </svg>
                          <span class="tl-badge-3d-tag">3D Mesh</span>
                        </div>
                      } @else {
                        <div class="tl-file-icon-placeholder">
                          <svg viewBox="0 0 24 24" width="36" height="36" fill="none" stroke="currentColor" stroke-width="1.8">
                            <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/>
                            <polyline points="14 2 14 8 20 8"/>
                          </svg>
                        </div>
                      }
                      <div class="tl-file-details">
                        <div class="tl-file-detail-name" [title]="selectedRecordFile.name">{{ selectedRecordFile.name }}</div>
                        <div class="tl-file-detail-meta">
                          <span class="tl-file-badge">{{ formatBytes(selectedRecordFile.size) }}</span>
                          <span class="tl-file-badge-type">{{ autoDetectedTypeInfo?.type || selectedRecordFile.type || 'DICOM/Binary' }}</span>
                        </div>
                      </div>
                      <button
                        type="button"
                        class="tl-btn-remove-selected-file"
                        title="Remove file"
                        (click)="removeRecordFile($event)"
                      >
                        &times;
                      </button>
                    </div>
                  }
                </div>
              </div>

              <!-- Two column row: Radiograph Type and Tooth FDI -->
              <div class="tl-form-row-2">
                <label class="form-field">
                  <div class="tl-field-header-row">
                    <span>Radiograph Type *</span>
                    @if (autoDetectedTypeInfo) {
                      <span class="tl-auto-detected-badge" [title]="autoDetectedTypeInfo.reason">
                        <svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2.5"><polyline points="20 6 9 17 4 12"/></svg>
                        {{ autoDetectedTypeInfo.reason }}
                      </span>
                    }
                  </div>
                  <select class="fe-select" [(ngModel)]="newRecordType">
                    @if (newRecordType === 'AUTO') {
                      <option value="AUTO" selected>Auto-detect from file (تحديد تلقائي)</option>
                    }
                    @for (rt of radiographTypes; track rt) {
                      <option [value]="rt">{{ rt }}</option>
                    }
                  </select>
                </label>
                <label class="form-field">
                  <span>Target Tooth (FDI - Optional)</span>
                  <input
                    type="number"
                    min="11"
                    max="85"
                    class="fe-input"
                    [(ngModel)]="newRecordToothFdi"
                    placeholder="e.g., 36, 46, 21"
                  />
                </label>
              </div>

              <label class="form-field">
                <span>Clinical Notes &amp; Findings</span>
                <textarea
                  class="fe-textarea"
                  rows="2"
                  [(ngModel)]="newRecordNotes"
                  placeholder="e.g., Radiolucency noted at periapical region of #36; bone level normal."
                ></textarea>
              </label>
            </div>
            <div class="modal-footer">
              <button type="button" class="tl-btn tl-btn-gray" (click)="closeAddRecordModal()">Cancel</button>
              <button
                type="button"
                class="tl-btn tl-btn-teal"
                [disabled]="isUploadingRecord"
                (click)="saveRadiographRecord()"
              >
                @if (isUploadingRecord) {
                  <span>Uploading...</span>
                } @else {
                  <span>Save Record</span>
                }
              </button>
            </div>
          </div>
        </div>
      }

      <!-- Viewer Lightbox Modal for Radiographs -->
      @if (viewingRadiograph(); as viewRec) {
        <div class="modal-backdrop tl-viewer-backdrop" (click)="closeRadiographViewer()">
          <div class="tl-viewer-modal" (click)="$event.stopPropagation()">
            <div class="tl-viewer-header">
              <div class="tl-viewer-title-info">
                <h4>{{ viewRec.radiographType }} — {{ viewRec.fileName }}</h4>
                <div class="tl-viewer-meta">
                  <span>Date: {{ viewRec.date }}</span>
                  @if (viewRec.toothFdi) {
                    <span>Tooth: FDI {{ viewRec.toothFdi }}</span>
                  }
                  @if (viewRec.fileSize) {
                    <span>Size: {{ viewRec.fileSize }}</span>
                  }
                  @if (is3DFile(viewRec.fileName)) {
                    <span class="tl-3d-badge">Interactive 3D</span>
                  }
                </div>
              </div>
              <div class="tl-viewer-tools">
                <div class="tl-viewer-mode-group">
                  <button
                    type="button"
                    class="tl-tool-btn"
                    [class.is-active]="viewerMode === '2D'"
                    (click)="setViewerMode('2D')"
                  >
                    2D X-Ray
                  </button>
                  <button
                    type="button"
                    class="tl-tool-btn"
                    [class.is-active]="viewerMode === 'CBCT_MPR'"
                    (click)="setViewerMode('CBCT_MPR')"
                  >
                    CBCT MPR
                  </button>
                  @if (is3DFile(viewRec.fileName)) {
                    <button
                      type="button"
                      class="tl-tool-btn"
                      [class.is-active]="viewerMode === '3D'"
                      (click)="setViewerMode('3D')"
                    >
                      3D Mesh
                    </button>
                  }
                </div>

                @if (viewerMode === '3D' && is3DFile(viewRec.fileName)) {
                  <!-- 3D Specific Perspective & Render Tools -->
                  <button
                    type="button"
                    class="tl-tool-btn"
                    [class.is-active]="is3DWireframe"
                    (click)="toggle3DWireframe()"
                    title="Toggle 3D Wireframe Mesh"
                  >
                    <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2">
                      <polygon points="12 2 2 7 12 12 22 7 12 2"/>
                      <polyline points="2 17 12 22 22 17"/>
                      <polyline points="2 12 12 17 22 12"/>
                    </svg>
                    <span>Wireframe</span>
                  </button>
                  <button
                    type="button"
                    class="tl-tool-btn"
                    [class.is-active]="is3DAutoRotate"
                    (click)="toggle3DAutoRotate()"
                    title="Toggle Continuous 360° Rotation"
                  >
                    <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2">
                      <path d="M21.5 2v6h-6M21.34 15.57a10 10 0 1 1-.57-8.38l5.67-5.67"/>
                    </svg>
                    <span>360° Rotate</span>
                  </button>
                  <div class="tl-3d-color-picker">
                    <button
                      type="button"
                      class="tl-color-dot tl-color-dot--ceramic"
                      [class.is-selected]="current3DColor === 'ceramic'"
                      (click)="set3DColor('ceramic')"
                      title="Ceramic Ivory Enamel"
                    ></button>
                    <button
                      type="button"
                      class="tl-color-dot tl-color-dot--blue"
                      [class.is-selected]="current3DColor === 'blue'"
                      (click)="set3DColor('blue')"
                      title="Medical Cyan"
                    ></button>
                    <button
                      type="button"
                      class="tl-color-dot tl-color-dot--gold"
                      [class.is-selected]="current3DColor === 'gold'"
                      (click)="set3DColor('gold')"
                      title="Anatomical Gold"
                    ></button>
                  </div>
                  <button type="button" class="tl-tool-btn" (click)="zoom3D(-1)" title="Zoom Out">
                    <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/><line x1="8" y1="11" x2="14" y2="11"/></svg>
                  </button>
                  <button type="button" class="tl-tool-btn" (click)="zoom3D(1)" title="Zoom In">
                    <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/><line x1="11" y1="8" x2="11" y2="14"/><line x1="8" y1="11" x2="14" y2="11"/></svg>
                  </button>
                  <button type="button" class="tl-tool-btn" (click)="set3DPerspective('reset')" title="Reset Perspective">
                    Reset View
                  </button>
                } @else {
                  <button
                    type="button"
                    class="tl-tool-btn"
                    [class.is-active]="measureTool === 'DISTANCE'"
                    (click)="toggleMeasureTool('DISTANCE')"
                    title="Distance Caliper (mm)"
                  >
                    Caliper (mm)
                  </button>
                  <button
                    type="button"
                    class="tl-tool-btn"
                    [class.is-active]="measureTool === 'ANGLE'"
                    (click)="toggleMeasureTool('ANGLE')"
                    title="3-Point Angle Tool (°)"
                  >
                    Angle (°)
                  </button>
                  <button
                    type="button"
                    class="tl-tool-btn"
                    [class.is-active]="measureTool === 'NERVE_CANAL'"
                    (click)="toggleMeasureTool('NERVE_CANAL')"
                    title="Mandibular Nerve Canal Tracing"
                  >
                    Nerve Canal
                  </button>
                  @if (distanceLines.length > 0 || angleShapes.length > 0 || nervePoints.length > 0) {
                    <button type="button" class="tl-tool-btn" (click)="clearViewerMeasurements()" title="Clear Measurements">
                      Clear
                    </button>
                  }
                  <select
                    class="tl-tool-select"
                    [(ngModel)]="huPreset"
                    aria-label="HU Window Preset"
                  >
                    <option value="BONE">HU: Bone (W:2000)</option>
                    <option value="ENAMEL">HU: Enamel (W:3500)</option>
                    <option value="SOFT">HU: Soft Tissue</option>
                    <option value="MIP">MIP Projection</option>
                  </select>
                  <button
                    type="button"
                    class="tl-tool-btn"
                    [class.is-active]="radiographInverted"
                    (click)="toggleRadiographInvert()"
                    title="Invert Colors (Dental X-Ray Contrast)"
                  >
                    <span>Invert</span>
                  </button>
                  <button type="button" class="tl-tool-btn" (click)="zoomOutRadiograph()" title="Zoom Out">
                    <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/><line x1="8" y1="11" x2="14" y2="11"/></svg>
                  </button>
                  <span class="tl-zoom-level">{{ (radiographZoom * 100).toFixed(0) }}%</span>
                  <button type="button" class="tl-tool-btn" (click)="zoomInRadiograph()" title="Zoom In">
                    <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/><line x1="11" y1="8" x2="11" y2="14"/><line x1="8" y1="11" x2="14" y2="11"/></svg>
                  </button>
                  <button type="button" class="tl-tool-btn" (click)="resetRadiographZoom()" title="Reset Zoom">
                    Reset
                  </button>
                }
                @if (viewRec.fileUrl || viewRec.thumbnailUrl) {
                  <button type="button" class="tl-tool-btn tl-tool-btn--accent" (click)="downloadRecordFile(viewRec)" title="Download Image">
                    <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
                    <span>Download</span>
                  </button>
                }
                <button type="button" class="tl-viewer-close" (click)="closeRadiographViewer()">&times;</button>
              </div>
            </div>
            <div class="tl-viewer-viewport">
              @if (viewerMode === '3D' && is3DFile(viewRec.fileName)) {
                <div class="tl-3d-viewport-container" id="dental3dContainer">
                  <canvas id="dental3dCanvas" class="tl-3d-canvas"></canvas>
                  @if (is3DLoading) {
                    <div class="tl-viewer-loading">
                      <div class="tl-spinner"></div>
                      <p>Loading &amp; Rendering 3D Dental Model...</p>
                    </div>
                  }
                  <div class="tl-3d-controls-overlay">
                    <div class="tl-3d-perspectives-pill">
                      <span class="tl-pill-label">Perspective:</span>
                      <button type="button" class="tl-pill-btn" (click)="set3DPerspective('front')" title="Front / Anterior View">Front</button>
                      <button type="button" class="tl-pill-btn" (click)="set3DPerspective('top')" title="Occlusal (Chewing Surface) View">Occlusal (Top)</button>
                      <button type="button" class="tl-pill-btn" (click)="set3DPerspective('bottom')" title="Basal / Lingual View">Basal</button>
                      <button type="button" class="tl-pill-btn" (click)="set3DPerspective('left')" title="Left Lateral View">Left</button>
                      <button type="button" class="tl-pill-btn" (click)="set3DPerspective('right')" title="Right Lateral View">Right</button>
                      <button type="button" class="tl-pill-btn tl-pill-btn--reset" (click)="set3DPerspective('reset')" title="Reset Camera">Reset</button>
                    </div>
                    <div class="tl-3d-hint">
                      <span>Rotate: Left Click + Drag &nbsp;|&nbsp; Pan: Right Click + Drag &nbsp;|&nbsp; Zoom: Mouse Wheel / Pinch</span>
                    </div>
                  </div>
                </div>
              } @else if (isDicomDecoding) {
                <div class="tl-viewer-loading">
                  <div class="tl-spinner"></div>
                  <p>Processing DICOM Radiograph Scan...</p>
                </div>
              } @else if (viewerMode === 'CBCT_MPR') {
                <div class="tl-mpr-grid">
                  <!-- Axial Plane -->
                  <div class="tl-mpr-pane" (click)="onViewportClick($event)">
                    <div class="tl-mpr-pane-head">
                      <span>AXIAL (Z: {{ axialSlice }} mm)</span>
                      <input type="range" min="0" max="100" [(ngModel)]="axialSlice" (click)="$event.stopPropagation()" aria-label="Axial Slice" />
                    </div>
                    <div class="tl-mpr-canvas">
                      @if (viewRec.fileUrl || viewRec.thumbnailUrl) {
                        <img
                          [src]="viewRec.fileUrl || viewRec.thumbnailUrl"
                          alt="Axial MPR"
                          class="tl-mpr-img"
                          [class]="'tl-mpr-img hu-' + huPreset + (radiographInverted ? ' is-inverted' : '')"
                          [style.transform]="'scale(' + (radiographZoom * (0.9 + axialSlice * 0.002)) + ')'"
                        />
                      }
                      <div class="tl-crosshair-h" [style.top.%]="coronalSlice" style="border-color:#22d3ee;"></div>
                      <div class="tl-crosshair-v" [style.left.%]="sagittalSlice" style="border-color:#facc15;"></div>
                      <svg class="tl-measure-svg" viewBox="0 0 1000 700" preserveAspectRatio="none">
                        @for (line of distanceLines; track $index) {
                          <line [attr.x1]="line.x1" [attr.y1]="line.y1" [attr.x2]="line.x2" [attr.y2]="line.y2" class="tl-caliper-line" />
                          <text [attr.x]="(line.x1 + line.x2) / 2" [attr.y]="(line.y1 + line.y2) / 2 - 10" class="tl-caliper-label">{{ line.mm }} mm</text>
                        }
                        @if (nervePoints.length > 1) {
                          <polyline [attr.points]="nervePolylineAttr()" class="tl-nerve-path" />
                        }
                      </svg>
                    </div>
                  </div>

                  <!-- Coronal Plane -->
                  <div class="tl-mpr-pane">
                    <div class="tl-mpr-pane-head">
                      <span>CORONAL (Y: {{ coronalSlice }} mm)</span>
                      <input type="range" min="0" max="100" [(ngModel)]="coronalSlice" aria-label="Coronal Slice" />
                    </div>
                    <div class="tl-mpr-canvas">
                      @if (viewRec.fileUrl || viewRec.thumbnailUrl) {
                        <img
                          [src]="viewRec.fileUrl || viewRec.thumbnailUrl"
                          alt="Coronal MPR"
                          class="tl-mpr-img"
                          [class]="'tl-mpr-img hu-' + huPreset + (radiographInverted ? ' is-inverted' : '')"
                          [style.transform]="'scale(' + radiographZoom + ') scaleY(' + (0.85 + coronalSlice * 0.003) + ')'"
                        />
                      }
                      <div class="tl-crosshair-h" [style.top.%]="axialSlice" style="border-color:#ef4444;"></div>
                      <div class="tl-crosshair-v" [style.left.%]="sagittalSlice" style="border-color:#facc15;"></div>
                    </div>
                  </div>

                  <!-- Sagittal / Cross-Sectional Plane -->
                  <div class="tl-mpr-pane">
                    <div class="tl-mpr-pane-head">
                      <span>SAGITTAL / CROSS-SECTION (X: {{ sagittalSlice }} mm)</span>
                      <input type="range" min="0" max="100" [(ngModel)]="sagittalSlice" aria-label="Sagittal Slice" />
                    </div>
                    <div class="tl-mpr-canvas">
                      @if (viewRec.fileUrl || viewRec.thumbnailUrl) {
                        <img
                          [src]="viewRec.fileUrl || viewRec.thumbnailUrl"
                          alt="Sagittal MPR"
                          class="tl-mpr-img"
                          [class]="'tl-mpr-img hu-' + huPreset + (radiographInverted ? ' is-inverted' : '')"
                          [style.transform]="'scale(' + radiographZoom + ') scaleX(' + (0.85 + sagittalSlice * 0.003) + ')'"
                        />
                      }
                      <div class="tl-crosshair-h" [style.top.%]="axialSlice" style="border-color:#ef4444;"></div>
                      <div class="tl-crosshair-v" [style.left.%]="coronalSlice" style="border-color:#22d3ee;"></div>
                    </div>
                  </div>

                  <!-- Panoramic Arch Curve & 3D Volume Summary -->
                  <div class="tl-mpr-pane">
                    <div class="tl-mpr-pane-head">
                      <span>PANORAMIC ARCH RECONSTRUCTION (Slab: {{ panArchDepth }} mm)</span>
                      <input type="range" min="2" max="40" [(ngModel)]="panArchDepth" aria-label="Panoramic Arch Depth" />
                    </div>
                    <div class="tl-mpr-canvas">
                      @if (viewRec.fileUrl || viewRec.thumbnailUrl) {
                        <img
                          [src]="viewRec.fileUrl || viewRec.thumbnailUrl"
                          alt="Panoramic Arch"
                          class="tl-mpr-img"
                          [class]="'tl-mpr-img hu-' + huPreset + (radiographInverted ? ' is-inverted' : '')"
                          [style.transform]="'scale(' + (radiographZoom * (0.95 + panArchDepth * 0.004)) + ')'"
                        />
                      }
                      <svg class="tl-measure-svg" viewBox="0 0 1000 700" preserveAspectRatio="none">
                        <path d="M 160 540 Q 500 120 840 540" fill="none" stroke="#14b8a6" stroke-width="4" stroke-dasharray="10 6" opacity="0.8" />
                        @for (ang of angleShapes; track $index) {
                          <polyline [attr.points]="ang.pointsAttr" class="tl-angle-line" />
                          <text [attr.x]="ang.bx + 12" [attr.y]="ang.by - 10" class="tl-caliper-label">{{ ang.deg }}°</text>
                        }
                      </svg>
                    </div>
                  </div>
                </div>
              } @else if (viewRec.fileUrl || viewRec.thumbnailUrl) {
                <div class="tl-2d-stage" [class.has-tool]="measureTool !== 'NONE'" (click)="onViewportClick($event)">
                  <img
                    [src]="viewRec.fileUrl || viewRec.thumbnailUrl"
                    [alt]="viewRec.fileName"
                    class="tl-viewer-img"
                    [class]="'tl-viewer-img hu-' + huPreset + (radiographInverted ? ' is-inverted' : '')"
                    [style.transform]="'scale(' + radiographZoom + ')'"
                  />
                  <svg class="tl-measure-svg" viewBox="0 0 1000 700" preserveAspectRatio="none">
                    @for (line of distanceLines; track $index) {
                      <line [attr.x1]="line.x1" [attr.y1]="line.y1" [attr.x2]="line.x2" [attr.y2]="line.y2" class="tl-caliper-line" />
                      <circle [attr.cx]="line.x1" [attr.cy]="line.y1" r="5" fill="#14b8a6" />
                      <circle [attr.cx]="line.x2" [attr.cy]="line.y2" r="5" fill="#14b8a6" />
                      <text [attr.x]="(line.x1 + line.x2) / 2" [attr.y]="(line.y1 + line.y2) / 2 - 12" class="tl-caliper-label">{{ line.mm }} mm</text>
                    }
                    @if (distanceStart; as ds) {
                      <circle [attr.cx]="ds.x" [attr.cy]="ds.y" r="6" fill="#22d3ee" />
                    }
                    @for (ang of angleShapes; track $index) {
                      <polyline [attr.points]="ang.pointsAttr" class="tl-angle-line" />
                      <text [attr.x]="ang.bx + 12" [attr.y]="ang.by - 10" class="tl-caliper-label">{{ ang.deg }}°</text>
                    }
                    @for (pt of anglePoints; track $index) {
                      <circle [attr.cx]="pt.x" [attr.cy]="pt.y" r="5" fill="#38bdf8" />
                    }
                    @if (nervePoints.length > 0) {
                      <polyline [attr.points]="nervePolylineAttr()" class="tl-nerve-path" />
                      @for (np of nervePoints; track $index) {
                        <circle [attr.cx]="np.x" [attr.cy]="np.y" r="5" fill="#ff6d00" />
                      }
                    }
                  </svg>
                </div>
              } @else {
                <div class="tl-viewer-fallback">
                  <p>Cloud or DICOM file is not rendered locally yet. Switch to <strong>CBCT MPR</strong> for slice planning or download the external study.</p>
                </div>
              }
            </div>
            @if (measureTool !== 'NONE' || distanceLines.length > 0 || angleShapes.length > 0 || nervePoints.length > 0) {
              <div class="tl-viewer-footer-notes" style="display:flex;justify-content:space-between;flex-wrap:wrap;gap:8px;">
                <span>
                  Active Tool: <strong>{{ measureTool === 'NONE' ? 'View Only' : measureTool }}</strong>
                  @if (measureTool === 'DISTANCE') { (Click 2 points to measure distance in mm) }
                  @if (measureTool === 'ANGLE') { (Click 3 points A→B→C to measure angle °) }
                  @if (measureTool === 'NERVE_CANAL') { (Click along the inferior alveolar nerve canal to trace) }
                </span>
                <span>
                  Calipers: {{ distanceLines.length }} · Angles: {{ angleShapes.length }} · Nerve Points: {{ nervePoints.length }}
                </span>
              </div>
            }
            @if (viewRec.notes) {
              <div class="tl-viewer-footer-notes">
                <strong>Clinical Notes:</strong> {{ viewRec.notes }}
              </div>
            }
          </div>
        </div>
      }

      @if (showOrderImagingModal()) {
        <div class="modal-backdrop" (click)="showOrderImagingModal.set(false)">
          <div class="modal-card" (click)="$event.stopPropagation()">
            <div class="modal-header">
              <h3>Order External / Cloud Dental Imaging</h3>
              <button type="button" class="modal-close" (click)="showOrderImagingModal.set(false)">&times;</button>
            </div>
            <div class="modal-body">
              <div class="form-row">
                <label class="form-field">
                  <span>Modality *</span>
                  <select class="fe-select" [(ngModel)]="orderImgType">
                    <option value="CBCT">3D CBCT Scan</option>
                    <option value="PANORAMIC">Panoramic (OPG)</option>
                    <option value="PERIAPICAL">Periapical X-Ray</option>
                    <option value="BITEWING">Bitewing</option>
                    <option value="CEPHALOMETRIC">Cephalometric</option>
                    <option value="OTHER">Other Imaging</option>
                  </select>
                </label>
                <label class="form-field">
                  <span>Procedures / Views Count</span>
                  <input class="fe-input" type="number" min="1" [(ngModel)]="orderImgCount" />
                </label>
              </div>
              <div class="form-row">
                <label class="form-field">
                  <span>Preferred External Lab</span>
                  <select class="fe-select" [(ngModel)]="orderImgLabId" (ngModelChange)="onOrderImgLabSelected($event)">
                    <option value="">Custom / Any Lab</option>
                    @for (lab of imagingLabsList(); track lab.id) {
                      <option [value]="lab.id">{{ lab.name }}</option>
                    }
                  </select>
                </label>
                <label class="form-field">
                  <span>Lab Name</span>
                  <input class="fe-input" [(ngModel)]="orderImgLabName" placeholder="External center name" />
                </label>
              </div>
              <div class="form-row">
                <label class="form-field">
                  <span>Cost to Clinic (JOD)</span>
                  <input class="fe-input" type="number" min="0" step="0.001" [(ngModel)]="orderImgCost" />
                </label>
                <label class="form-field">
                  <span>Charge to Patient (JOD)</span>
                  <input class="fe-input" type="number" min="0" step="0.001" [(ngModel)]="orderImgCharge" />
                </label>
              </div>
              <label class="form-field">
                <span>Teeth / Anatomical Region</span>
                <input class="fe-input" [(ngModel)]="orderImgTeeth" placeholder="e.g., Mandible #36-#38" />
              </label>
              <label class="form-field">
                <span>Clinical Indication</span>
                <input class="fe-input" [(ngModel)]="orderImgIndication" placeholder="Implant planning, nerve canal proximity" />
              </label>
              <label class="form-field">
                <span>Cloud Storage / DICOM URL (Optional)</span>
                <input class="fe-input" [(ngModel)]="orderImgCloudUrl" placeholder="https://..." />
              </label>
            </div>
            <div class="modal-footer">
              <button type="button" class="fe-btn fe-btn--secondary" (click)="showOrderImagingModal.set(false)">Cancel</button>
              <button type="button" class="fe-btn fe-btn--primary" (click)="submitOrderImaging()">Submit Order</button>
            </div>
          </div>
        </div>
      }

      @if (inventoryWarningsModal().length > 0) {
        <div class="modal-backdrop" (click)="inventoryWarningsModal.set([])">
          <div class="modal-card" (click)="$event.stopPropagation()">
            <div class="modal-header">
              <h3 style="color:#b45309;">Insufficient Inventory Warning</h3>
              <button type="button" class="modal-close" (click)="inventoryWarningsModal.set([])">&times;</button>
            </div>
            <div class="modal-body">
              <p style="margin:0;font-size:0.88rem;color:var(--ink);">
                The clinical encounter was saved as <strong>Completed</strong>, but branch inventory was below required quantities for:
              </p>
              <ul style="margin:0;padding-left:1.2rem;font-size:0.84rem;color:#991b1b;">
                @for (w of inventoryWarningsModal(); track w.itemId) {
                  <li>
                    <strong>{{ w.itemName }}</strong>: required {{ w.quantityRequired }}, available {{ w.quantityAvailable }} (shortfall {{ w.shortfallQuantity }})
                  </li>
                }
              </ul>
            </div>
            <div class="modal-footer">
              <button type="button" class="fe-btn fe-btn--primary" (click)="inventoryWarningsModal.set([])">
                Acknowledge &amp; Continue
              </button>
            </div>
          </div>
        </div>
      }

      <!-- Add Follow Up Note Modal -->
      @if (showAddFollowUpModal()) {
        <div class="modal-backdrop" (click)="showAddFollowUpModal.set(false)">
          <div class="modal-card" (click)="$event.stopPropagation()">
            <div class="modal-header">
              <h3>Add Follow Up Note</h3>
              <button type="button" class="modal-close" (click)="showAddFollowUpModal.set(false)">&times;</button>
            </div>
            <div class="modal-body">
              <label class="form-field">
                <span>Title *</span>
                <input type="text" class="fe-input" [(ngModel)]="newFollowUpTitle" placeholder="e.g., Post-Scaling Review in 2 Weeks" />
              </label>
              <label class="form-field">
                <span>Note *</span>
                <textarea class="fe-textarea" rows="3" [(ngModel)]="newFollowUpNote" placeholder="Enter follow-up instructions..."></textarea>
              </label>
            </div>
            <div class="modal-footer">
              <button type="button" class="tl-btn tl-btn-gray" (click)="showAddFollowUpModal.set(false)">Cancel</button>
              <button type="button" class="tl-btn tl-btn-teal" (click)="saveFollowUpNote()">Save Follow Up</button>
            </div>
          </div>
        </div>
      }

      <!-- New Encounter Modal (CLN-01 to CLN-06) -->
      @if (showCreateModal()) {
        <div class="modal-backdrop" (click)="closeCreateModal()">
          <div class="modal-card modal-card--lg" (click)="$event.stopPropagation()">
            <div class="modal-header">
              <h3>{{ 'clinic.encounters.createModal.title' | t }}</h3>
              <button class="modal-close" (click)="closeCreateModal()">&times;</button>
            </div>
            <div class="modal-body">
              <div class="form-row">
                <label class="form-field">
                  <span class="fe-eyebrow">{{ 'clinic.encounters.doctor' | t }} *</span>
                  <select class="fe-select" [(ngModel)]="newDoctorId">
                    <option [ngValue]="null">اختر الطبيب المعالج...</option>
                    @for (doc of doctorsList(); track doc.id) {
                      <option [value]="doc.id">{{ doc.name }} ({{ doc.specialty }})</option>
                    }
                  </select>
                </label>
                <label class="form-field form-field--sm">
                  <span class="fe-eyebrow">{{ 'clinic.encounters.duration' | t }} (دقائق)</span>
                  <input type="number" min="5" step="5" class="fe-input" [(ngModel)]="newDuration" />
                </label>
                <label class="form-field form-field--sm">
                  <span class="fe-eyebrow">{{ 'clinic.encounters.status' | t }}</span>
                  <select class="fe-select" [(ngModel)]="newStatus">
                    <option value="COMPLETED">مكتملة</option>
                    <option value="IN_PROGRESS">جارية</option>
                    <option value="CANCELLED">ملغاة</option>
                  </select>
                </label>
              </div>

              <label class="form-field">
                <span class="fe-eyebrow">{{ 'clinic.encounters.chiefComplaint' | t }} *</span>
                <input type="text" class="fe-input" [(ngModel)]="newChiefComplaint" />
              </label>

              <label class="form-field">
                <span class="fe-eyebrow">{{ 'clinic.encounters.diagnosis' | t }} *</span>
                <input type="text" class="fe-input" [(ngModel)]="newDiagnosis" />
              </label>

              <label class="form-field">
                <span class="fe-eyebrow">{{ 'clinic.encounters.examinationNotes' | t }}</span>
                <textarea class="fe-textarea" rows="2" [(ngModel)]="newExaminationNotes"></textarea>
              </label>
            </div>

            <div class="modal-footer">
              <button class="fe-btn fe-btn--ghost" (click)="closeCreateModal()">إلغاء</button>
              <button
                class="fe-btn fe-btn--primary"
                [disabled]="!newDoctorId || !newChiefComplaint.trim() || !newDiagnosis.trim()"
                (click)="submitCreateEncounter()"
              >
                {{ 'clinic.encounters.createModal.submit' | t }}
              </button>
            </div>
          </div>
        </div>
      }

      <!-- Edit Diagnosis Modal -->
      @if (showEditDiagnosisModal()) {
        <div class="modal-backdrop" (click)="closeEditDiagnosisModal()">
          <div class="modal-card" (click)="$event.stopPropagation()">
            <div class="modal-header">
              <h3>{{ 'clinic.encounters.editDiagnosisModalTitle' | t }}</h3>
              <button class="modal-close" (click)="closeEditDiagnosisModal()">&times;</button>
            </div>
            <div class="modal-body">
              <label class="form-field">
                <span class="fe-eyebrow">{{ 'clinic.encounters.diagnosis' | t }} *</span>
                <textarea class="fe-textarea" rows="2" [(ngModel)]="editDiagnosis"></textarea>
              </label>
              <label class="form-field">
                <span class="fe-eyebrow">{{ 'clinic.encounters.examinationNotes' | t }}</span>
                <textarea class="fe-textarea" rows="2" [(ngModel)]="editExamNotes"></textarea>
              </label>
              <label class="form-field">
                <span class="fe-eyebrow">{{ 'clinic.encounters.performedProcedures' | t }}</span>
                <input type="text" class="fe-input" [(ngModel)]="editPerformedProcedures" />
              </label>
              <label class="form-field">
                <span class="fe-eyebrow">{{ 'clinic.encounters.clinicalNotes' | t }}</span>
                <textarea class="fe-textarea" rows="3" [(ngModel)]="editClinicalNotes"></textarea>
              </label>
            </div>
            <div class="modal-footer">
              <button class="fe-btn fe-btn--ghost" (click)="closeEditDiagnosisModal()">إلغاء</button>
              <button class="fe-btn fe-btn--primary" [disabled]="!editDiagnosis.trim()" (click)="submitUpdateDiagnosis()">
                حفظ التعديلات
              </button>
            </div>
          </div>
        </div>
      }

      <!-- Add Amendment Modal -->
      @if (showAddAmendmentModal()) {
        <div class="modal-backdrop" (click)="closeAddAmendmentModal()">
          <div class="modal-card" (click)="$event.stopPropagation()">
            <div class="modal-header">
              <h3>{{ 'clinic.encounters.addAmendment' | t }}</h3>
              <button class="modal-close" (click)="closeAddAmendmentModal()">&times;</button>
            </div>
            <div class="modal-body">
              <label class="form-field">
                <span class="fe-eyebrow">{{ 'clinic.encounters.amendmentReason' | t }} *</span>
                <input type="text" class="fe-input" [(ngModel)]="amendmentReason" />
              </label>
              <label class="form-field">
                <span class="fe-eyebrow">{{ 'clinic.encounters.amendmentContent' | t }} *</span>
                <textarea class="fe-textarea" rows="4" [(ngModel)]="amendmentContent"></textarea>
              </label>
            </div>
            <div class="modal-footer">
              <button class="fe-btn fe-btn--ghost" (click)="closeAddAmendmentModal()">إلغاء</button>
              <button
                class="fe-btn fe-btn--primary"
                [disabled]="!amendmentReason.trim() || !amendmentContent.trim()"
                (click)="submitAddAmendment()"
              >
                {{ 'clinic.encounters.saveAmendment' | t }}
              </button>
            </div>
          </div>
        </div>
      }

      <!-- Add Prescription Modal -->
      @if (showAddRxModal()) {
        <div class="modal-backdrop" (click)="closeAddRxModal()">
          <div class="modal-card" (click)="$event.stopPropagation()">
            <div class="modal-header">
              <h3>{{ 'clinic.encounters.addPrescription' | t }}</h3>
              <button class="modal-close" (click)="closeAddRxModal()">&times;</button>
            </div>
            <div class="modal-body">
              <label class="form-field">
                <span class="fe-eyebrow">{{ 'clinic.encounters.medicationName' | t }} *</span>
                <input type="text" class="fe-input" [(ngModel)]="addRxName" />
              </label>
              <div class="form-row">
                <label class="form-field">
                  <span class="fe-eyebrow">{{ 'clinic.encounters.dosage' | t }}</span>
                  <input type="text" class="fe-input" [(ngModel)]="addRxDosage" />
                </label>
                <label class="form-field">
                  <span class="fe-eyebrow">{{ 'clinic.encounters.frequency' | t }}</span>
                  <input type="text" class="fe-input" [(ngModel)]="addRxFrequency" />
                </label>
              </div>
              <label class="form-field">
                <span class="fe-eyebrow">{{ 'clinic.encounters.durationDays' | t }}</span>
                <input type="text" class="fe-input" [(ngModel)]="addRxDuration" />
              </label>
              <label class="form-field">
                <span class="fe-eyebrow">{{ 'clinic.encounters.instructions' | t }}</span>
                <textarea class="fe-textarea" rows="2" [(ngModel)]="addRxInstructions"></textarea>
              </label>
            </div>
            <div class="modal-footer">
              <button class="fe-btn fe-btn--ghost" (click)="closeAddRxModal()">إلغاء</button>
              <button class="fe-btn fe-btn--primary" [disabled]="!addRxName.trim()" (click)="submitAddPrescription()">
                إضافة الدواء
              </button>
            </div>
          </div>
        </div>
      }

      <!-- Printable Estimate Sheet Modal (Tier 1: Estimate Quote) -->
      @if (showEstimatePrintModal()) {
        <div class="modal-backdrop print-backdrop" (click)="showEstimatePrintModal.set(false)">
          <div class="modal-card prescription-sheet" (click)="$event.stopPropagation()">
            <div class="prescription-header">
              <div class="clinic-brand">
                <div>
                  <h2 class="clinic-name">{{ currentClinicName() }}</h2>
                  <div class="clinic-sub">{{ currentClinicEmail() }} • Oral &amp; Dental Care Center</div>
                </div>
              </div>
              <div class="rx-date-stamp">
                <span class="estimate-badge">TREATMENT COST ESTIMATE</span>
                <strong>{{ currentEncounterDateTime() }}</strong>
              </div>
            </div>

            <div class="estimate-meta-grid">
              <div>
                <span class="tl-kv-label">Patient Name:</span>
                <strong>{{ currentPatientName() }}</strong>
              </div>
              <div>
                <span class="tl-kv-label">Attending Doctor:</span>
                <strong>{{ currentDoctorName() }}</strong>
              </div>
              <div>
                <span class="tl-kv-label">Validity:</span>
                <span style="color: var(--accent-green, #10b981);">Valid for 30 calendar days</span>
              </div>
              <div>
                <span class="tl-kv-label">Address:</span>
                <span>{{ currentPatientAddress() }}</span>
              </div>
            </div>

            <table class="tl-teal-table" style="margin: 20px 0;">
              <thead>
                <tr>
                  <th>#</th>
                  <th>Procedure / Treatment Details</th>
                  <th>Quantity</th>
                  <th>Unit Price</th>
                  <th>Total</th>
                </tr>
              </thead>
              <tbody>
                @for (item of billingItems(); track item.id; let idx = $index) {
                  <tr>
                    <td>{{ idx + 1 }}</td>
                    <td><strong>{{ item.serviceName }}</strong></td>
                    <td>{{ item.quantity }}</td>
                    <td>{{ item.price | number:'1.2-2' }} JOD</td>
                    <td><strong>{{ item.totalAmount | number:'1.2-2' }} JOD</strong></td>
                  </tr>
                }
              </tbody>
            </table>

            <div class="estimate-summary-box">
              <div class="tl-kv-row">
                <span>Subtotal:</span>
                <strong>{{ billingTotalAmount() | number:'1.2-2' }} JOD</strong>
              </div>
              @if (enableBillingDiscount && billingDiscountAmount > 0) {
                <div class="tl-kv-row" style="color: var(--bad, #ef4444);">
                  <span>Estimated Courtesy Discount:</span>
                  <strong>-{{ billingDiscountAmount | number:'1.2-2' }} JOD</strong>
                </div>
              }
              <div class="tl-kv-row" style="font-size: 1.1rem; border-top: 2px solid var(--line); padding-top: 8px; margin-top: 8px;">
                <span>Estimated Total Cost:</span>
                <strong style="color: var(--tl-teal);">{{ billingPayableAmount() | number:'1.2-2' }} JOD</strong>
              </div>
            </div>

            <div class="estimate-disclaimer">
              <p><strong>Clinical Notice:</strong> This document represents a professional cost estimate for the proposed treatment plan based on clinical and radiographical evaluation. Final charges may vary if unexpected intraoral conditions arise during clinical intervention.</p>
            </div>

            <div class="estimate-signatures">
              <div class="estimate-sig-col">
                <div class="sig-line"></div>
                <span>Dentist Signature: {{ currentDoctorName() }}</span>
              </div>
              <div class="estimate-sig-col">
                <div class="sig-line"></div>
                <span>Patient Acceptance Signature: {{ currentPatientName() }}</span>
              </div>
            </div>

            <div class="print-actions-bar no-print">
              <button type="button" class="fe-btn fe-btn--ghost" (click)="showEstimatePrintModal.set(false)">Close (إغلاق)</button>
              <button type="button" class="fe-btn fe-btn--primary" (click)="triggerBrowserPrint()">
                Print Quote Now (طباعة عرض السعر الآن)
              </button>
            </div>
          </div>
        </div>
      }

      <!-- Print Prescription Modal -->
      @if (showPrintModal() && printTargetDetail()) {
        <div class="modal-backdrop print-backdrop" (click)="closePrintModal()">
          <div class="modal-card prescription-sheet" (click)="$event.stopPropagation()">
            <div class="prescription-header">
              <div class="clinic-brand">
                <div>
                  <h2 class="clinic-name">Clinic Flow Dental Center</h2>
                  <div class="clinic-sub">Oral &amp; Dental Care Clinic Center</div>
                </div>
              </div>
              <div class="rx-date-stamp">
                <strong>{{ format.date(printTargetDetail()!.encounter.visitDate) }}</strong>
              </div>
            </div>
            <div class="print-actions-bar no-print">
              <button class="fe-btn fe-btn--ghost" (click)="closePrintModal()">إغلاق</button>
              <button class="fe-btn fe-btn--primary" (click)="triggerBrowserPrint()">طباعة الآن (Print)</button>
            </div>
          </div>
        </div>
      }
    </div>
  `,
  styles: [
    `
      :host {
        --tl-teal: var(--pri, #009ca6);
        display: block;
      }
      .tl-page {
        padding: 12px 18px 24px;
        background: var(--bg);
        min-height: 100%;
        box-sizing: border-box;
        font-family: inherit;
        color: var(--ink);
      }
      .tl-page-top {
        display: flex;
        justify-content: space-between;
        align-items: center;
        margin-bottom: 14px;
      }
      .tl-breadcrumbs {
        display: inline-flex;
        align-items: center;
        gap: 8px;
        font-size: 0.85rem;
        color: var(--mute);
      }
      .tl-breadcrumbs a {
        color: var(--mute);
        text-decoration: none;
        font-weight: 500;
        transition: color 0.15s ease;
      }
      .tl-breadcrumbs a:hover {
        color: var(--ink);
      }
      .tl-bc-sep {
        font-size: 0.75rem;
        color: var(--mute);
        user-select: none;
        opacity: 0.7;
      }
      .tl-breadcrumbs .active {
        color: var(--ink);
        font-weight: 600;
      }
      .tl-btn-appointment {
        background: var(--tl-dark, #27272a);
        color: #ffffff;
        border: 1px solid var(--line);
        border-radius: 6px;
        padding: 8px 16px;
        font-size: 0.82rem;
        font-weight: 500;
        display: inline-flex;
        align-items: center;
        gap: 8px;
        text-decoration: none;
        cursor: pointer;
        transition: background 0.15s ease;
      }
      .tl-btn-appointment:hover {
        background: var(--tl-dark-hover, #18181b);
      }
      .tl-btn-checkout {
        background: var(--tl-dark, #27272a);
        color: #ffffff;
        border: 1px solid var(--line);
        border-radius: 6px;
        padding: 8px 18px;
        font-size: 0.82rem;
        font-weight: 500;
        display: inline-flex;
        align-items: center;
        gap: 6px;
        cursor: pointer;
        transition: background 0.15s ease;
      }
      .tl-btn-checkout:hover {
        background: var(--tl-dark-hover, #18181b);
      }
      .tl-btn-teal {
        background: var(--tl-teal);
        color: var(--on-accent, #ffffff);
        border: none;
        border-radius: 6px;
        padding: 6px 14px;
        font-size: 0.78rem;
        font-weight: 600;
        display: inline-flex;
        align-items: center;
        gap: 4px;
        cursor: pointer;
        transition: filter 0.15s ease;
      }
      .tl-btn-teal:hover {
        filter: brightness(0.92);
      }
      .tl-main-top-bar {
        display: flex;
        justify-content: space-between;
        align-items: center;
        margin-bottom: 14px;
      }
      .tl-clinical-heading {
        margin: 0;
        font-size: 1.3rem;
        font-weight: 700;
        color: var(--ink);
      }
      .tl-encounter-grid {
        display: grid;
        grid-template-columns: 290px 1fr;
        gap: 24px;
        align-items: start;
      }
      @media (max-width: 980px) {
        .tl-encounter-grid {
          grid-template-columns: 1fr;
        }
      }
      .tl-encounter-left {
        display: flex;
        flex-direction: column;
        gap: 16px;
        position: sticky;
        top: 12px;
      }
      .tl-info-card,
      .tl-section-card {
        background: var(--surf);
        border: 1px solid var(--line);
        border-radius: 8px;
        padding: 18px 20px;
        box-shadow: var(--shadow);
        color: var(--ink);
      }
      .tl-info-heading {
        margin: 0 0 12px;
        font-size: 0.95rem;
        font-weight: 700;
        color: var(--ink);
      }
      .tl-clinic-box {
        display: flex;
        align-items: center;
        gap: 12px;
      }
      .tl-clinic-thumbnail {
        width: 44px;
        height: 44px;
        border-radius: 6px;
        overflow: hidden;
        flex: none;
        display: flex;
        align-items: center;
        justify-content: center;
        background: var(--surf2);
        border: 1px solid var(--line);
      }
      .tl-clinic-meta {
        display: flex;
        flex-direction: column;
        gap: 2px;
        min-width: 0;
      }
      .tl-clinic-title {
        font-size: 0.92rem;
        font-weight: 700;
        color: var(--ink);
      }
      .tl-clinic-doctor {
        font-size: 0.85rem;
        font-weight: 700;
        color: var(--ink);
        margin-top: 4px;
      }
      .tl-patient-box {
        display: flex;
        align-items: center;
        gap: 12px;
      }
      .tl-patient-avatar {
        width: 42px;
        height: 42px;
        border-radius: 50%;
        background: var(--surf2);
        color: var(--mute);
        border: 1px solid var(--line);
        display: inline-flex;
        align-items: center;
        justify-content: center;
        flex: none;
      }
      .tl-patient-meta {
        display: flex;
        flex-direction: column;
        gap: 2px;
        min-width: 0;
      }
      .tl-patient-name {
        font-size: 0.92rem;
        font-weight: 700;
        color: var(--ink);
      }
      .tl-info-divider {
        height: 1px;
        background: var(--line);
        margin: 14px 0;
      }
      .tl-kv-list {
        display: flex;
        flex-direction: column;
        gap: 10px;
      }
      .tl-kv-row {
        display: flex;
        justify-content: space-between;
        align-items: center;
        font-size: 0.82rem;
        gap: 8px;
      }
      .tl-kv-label {
        color: var(--mute);
        font-weight: 500;
      }
      .tl-kv-value {
        color: var(--ink);
        font-weight: 500;
        text-align: end;
      }
      .tl-status-text {
        font-size: 0.82rem;
        font-weight: 600;
        color: var(--accent-green, #10b981);
      }
      .tl-status-text--closed {
        color: var(--accent-red, #ef4444);
      }
      .tl-encounter-main {
        display: flex;
        flex-direction: column;
        gap: 16px;
      }
      .tl-section-head {
        display: flex;
        justify-content: space-between;
        align-items: center;
        margin-bottom: 12px;
        gap: 12px;
      }
      .tl-section-title {
        margin: 0;
        font-size: 0.95rem;
        font-weight: 700;
        color: var(--ink);
      }
      .tl-table-wrap {
        overflow-x: auto;
      }
      .tl-teal-table {
        width: 100%;
        border-collapse: collapse;
        font-size: 0.82rem;
      }
      .tl-teal-table thead th {
        background: var(--tl-teal);
        color: var(--on-accent, #ffffff);
        padding: 10px 14px;
        text-align: start;
        font-weight: 600;
        font-size: 0.82rem;
        border: none;
        white-space: nowrap;
      }
      .tl-teal-table thead th:first-child {
        border-top-left-radius: 4px;
        border-bottom-left-radius: 4px;
      }
      .tl-teal-table thead th:last-child {
        border-top-right-radius: 4px;
        border-bottom-right-radius: 4px;
      }
      .tl-teal-table tbody td {
        padding: 12px 14px;
        border-bottom: 1px solid var(--line);
        color: var(--ink);
        background: var(--surf);
      }
      .tl-empty-cell {
        text-align: center;
        padding: 24px 14px !important;
        font-size: 0.85rem;
        background: var(--surf);
      }
      .tl-empty-cell--red {
        color: var(--mute, #94a3b8) !important;
        font-weight: 500;
        text-align: center;
        background: var(--surf);
      }
      .tl-complete-badge {
        display: inline-block;
        padding: 2px 10px;
        border-radius: 999px;
        font-size: 0.75rem;
        font-weight: 600;
        background: var(--accent-amber-soft);
        color: var(--accent-amber-ink);
      }
      .tl-complete-badge.is-yes {
        background: var(--accent-green-soft);
        color: var(--accent-green-ink);
      }
      .tl-row-actions {
        display: inline-flex;
        gap: 6px;
        align-items: center;
      }
      .tl-action-icon {
        border: 1px solid var(--line);
        background: var(--surf2);
        color: var(--tl-teal);
        border-radius: 6px;
        width: 28px;
        height: 28px;
        display: inline-flex;
        align-items: center;
        justify-content: center;
        cursor: pointer;
        font-size: 0.85rem;
        transition: background 0.15s ease, border-color 0.15s ease;
      }
      .tl-action-icon:hover {
        background: var(--surf);
        border-color: var(--tl-teal);
      }
      .tl-action-icon--danger {
        color: var(--bad, #ef4444);
      }
      .tl-action-icon--danger:hover {
        background: var(--bad-soft);
        border-color: var(--bad);
      }
      .tl-action-icon--view {
        color: var(--tl-teal);
      }
      .tl-action-icon--view:hover {
        background: rgba(20, 184, 166, 0.15);
        border-color: var(--tl-teal);
      }
      .tl-action-icon--download {
        color: var(--ink);
      }
      .tl-action-icon--download:hover {
        background: var(--surf2);
      }

      /* Patient Records & Media Styles */
      .tl-section-counter {
        font-size: 0.85rem;
        color: var(--mute);
        font-weight: 500;
        margin-inline-start: 4px;
      }
      .tl-empty-records {
        padding: 32px 20px;
        text-align: center;
        background: var(--surf2);
        border: 1px dashed var(--line);
        border-radius: 8px;
        display: flex;
        flex-direction: column;
        align-items: center;
        gap: 6px;
        margin-block-start: 10px;
      }
      .tl-empty-records-icon {
        color: var(--mute);
        opacity: 0.6;
        margin-block-end: 2px;
      }
      .tl-empty-records-title {
        font-size: 0.95rem;
        font-weight: 600;
        color: var(--ink);
        margin: 0;
      }
      .tl-empty-records-sub {
        font-size: 0.78rem;
        color: var(--mute);
        max-width: 440px;
        margin: 0 0 8px;
        line-height: 1.45;
      }
      .tl-records-table {
        margin-block-start: 8px;
      }
      .tl-media-thumb {
        width: 44px;
        height: 44px;
        border-radius: 6px;
        background: var(--surf2);
        border: 1px solid var(--line);
        display: flex;
        align-items: center;
        justify-content: center;
        cursor: pointer;
        position: relative;
        overflow: hidden;
        color: var(--mute);
        margin: 0 auto;
      }
      .tl-media-thumb img {
        width: 100%;
        height: 100%;
        object-fit: cover;
      }
      .tl-thumb-overlay {
        position: absolute;
        inset: 0;
        background: rgba(0, 0, 0, 0.45);
        color: #ffffff;
        display: flex;
        align-items: center;
        justify-content: center;
        opacity: 0;
        transition: opacity 0.15s ease;
      }
      .tl-media-thumb:hover .tl-thumb-overlay {
        opacity: 1;
      }
      .tl-type-badges {
        display: flex;
        flex-wrap: wrap;
        gap: 4px;
        align-items: center;
      }
      .tl-badge-type {
        background: rgba(20, 184, 166, 0.12);
        color: var(--tl-teal);
        font-size: 0.72rem;
        font-weight: 700;
        padding: 2px 7px;
        border-radius: 4px;
        border: 1px solid rgba(20, 184, 166, 0.25);
      }
      .tl-badge-fdi {
        background: var(--surf2);
        color: var(--ink);
        font-size: 0.68rem;
        font-weight: 600;
        padding: 2px 6px;
        border-radius: 4px;
        border: 1px solid var(--line);
      }
      .tl-file-cell {
        display: flex;
        flex-direction: column;
        gap: 2px;
      }
      .tl-file-cell-name {
        font-weight: 600;
        font-size: 0.84rem;
        color: var(--ink);
      }
      .tl-file-cell-size {
        font-size: 0.72rem;
        color: var(--mute);
      }
      .tl-date-cell {
        font-size: 0.8rem;
        color: var(--ink);
        white-space: nowrap;
      }
      .tl-notes-cell {
        max-width: 220px;
        font-size: 0.8rem;
        color: var(--ink-dim, #cbd5e1);
      }
      .tl-notes-text {
        display: -webkit-box;
        -webkit-line-clamp: 2;
        -webkit-box-orient: vertical;
        overflow: hidden;
      }
      .tl-muted-dash {
        color: var(--mute);
      }

      /* Modal Upload & Dropzone */
      .modal-card--record-upload {
        max-width: 540px;
        width: 100%;
      }
      .modal-title-with-icon {
        display: flex;
        align-items: center;
        gap: 8px;
        color: var(--ink);
      }
      .modal-title-with-icon svg {
        color: var(--tl-teal);
      }
      .modal-title-with-icon h3 {
        margin: 0;
        font-size: 1.05rem;
        font-weight: 700;
      }
      .tl-file-dropzone {
        border: 2px dashed var(--line);
        border-radius: 8px;
        background: var(--surf2);
        padding: 16px;
        transition: all 0.2s ease;
        cursor: pointer;
        margin-block-start: 4px;
      }
      .tl-file-dropzone:hover,
      .tl-file-dropzone.is-dragover {
        border-color: var(--tl-teal);
        background: rgba(20, 184, 166, 0.06);
      }
      .tl-file-dropzone.has-file {
        border-style: solid;
        border-color: var(--line);
        padding: 12px;
      }
      .tl-file-hidden-input {
        display: none;
      }
      .tl-dropzone-content {
        display: flex;
        flex-direction: column;
        align-items: center;
        gap: 6px;
        text-align: center;
        padding: 6px 0;
      }
      .tl-dropzone-icon-circle {
        width: 44px;
        height: 44px;
        border-radius: 50%;
        background: rgba(20, 184, 166, 0.1);
        color: var(--tl-teal);
        display: flex;
        align-items: center;
        justify-content: center;
      }
      .tl-dropzone-primary {
        font-size: 0.85rem;
        font-weight: 600;
        color: var(--ink);
        margin: 0;
      }
      .tl-dropzone-secondary {
        font-size: 0.74rem;
        color: var(--mute);
        margin: 2px 0 0;
      }
      .tl-dropzone-file-preview {
        display: flex;
        align-items: center;
        gap: 12px;
      }
      .tl-file-thumb-large {
        width: 52px;
        height: 52px;
        border-radius: 6px;
        object-fit: cover;
        border: 1px solid var(--line);
      }
      .tl-file-icon-placeholder {
        width: 52px;
        height: 52px;
        border-radius: 6px;
        background: var(--surf);
        border: 1px solid var(--line);
        display: flex;
        align-items: center;
        justify-content: center;
        color: var(--mute);
      }
      .tl-file-details {
        flex: 1;
        min-width: 0;
        display: flex;
        flex-direction: column;
        gap: 4px;
      }
      .tl-file-detail-name {
        font-size: 0.85rem;
        font-weight: 600;
        color: var(--ink);
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
      }
      .tl-file-detail-meta {
        display: flex;
        gap: 8px;
        align-items: center;
      }
      .tl-file-badge {
        font-size: 0.7rem;
        color: var(--mute);
      }
      .tl-file-badge-type {
        font-size: 0.68rem;
        background: var(--surf);
        border: 1px solid var(--line);
        padding: 1px 6px;
        border-radius: 4px;
        color: var(--ink);
      }
      .tl-btn-remove-selected-file {
        border: none;
        background: none;
        font-size: 1.4rem;
        color: var(--mute);
        cursor: pointer;
        padding: 4px 8px;
        border-radius: 4px;
        line-height: 1;
      }
      .tl-btn-remove-selected-file:hover {
        color: var(--bad, #ef4444);
      }
      .tl-form-row-2 {
        display: grid;
        grid-template-columns: 1fr 1fr;
        gap: 12px;
      }
      .tl-field-header-row {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: 8px;
        margin-block-end: 2px;
      }
      .tl-auto-detected-badge {
        display: inline-flex;
        align-items: center;
        gap: 4px;
        font-size: 0.7rem;
        font-weight: 600;
        color: #38bdf8;
        background: rgba(56, 189, 248, 0.12);
        border: 1px solid rgba(56, 189, 248, 0.3);
        padding: 1px 7px;
        border-radius: 9999px;
        letter-spacing: 0.02em;
        white-space: nowrap;
      }
      .tl-file-icon-placeholder--3d {
        background: rgba(56, 189, 248, 0.1) !important;
        border-color: rgba(56, 189, 248, 0.35) !important;
        display: flex;
        flex-direction: column;
        align-items: center;
        justify-content: center;
        gap: 2px;
      }
      .tl-badge-3d-tag {
        font-size: 0.62rem;
        font-weight: 700;
        color: #38bdf8;
        line-height: 1;
      }

      /* Lightbox Viewer Modal for Radiographs */
      .tl-viewer-backdrop {
        position: fixed !important;
        inset: 0 !important;
        width: 100vw !important;
        height: 100vh !important;
        background: rgba(0, 0, 0, 0.92) !important;
        backdrop-filter: blur(10px);
        z-index: 999999 !important;
        display: flex !important;
        align-items: center !important;
        justify-content: center !important;
        padding: 24px !important;
        box-sizing: border-box !important;
      }
      .tl-viewer-modal {
        background: #0b1120;
        border: 1px solid #334155;
        border-radius: 12px;
        width: 92vw;
        max-width: 1150px;
        height: 86vh;
        max-height: 86vh;
        display: flex;
        flex-direction: column;
        box-shadow: 0 25px 60px -10px rgba(0, 0, 0, 0.9);
        overflow: hidden;
        position: relative;
        z-index: 1;
      }
      .tl-viewer-header {
        display: flex;
        justify-content: space-between;
        align-items: center;
        padding: 12px 20px;
        border-bottom: 1px solid #1e293b;
        background: #090d16;
        flex-shrink: 0;
      }
      .tl-viewer-title-info h4 {
        margin: 0;
        font-size: 1rem;
        color: #f1f5f9;
        font-weight: 700;
      }
      .tl-viewer-meta {
        display: flex;
        gap: 12px;
        font-size: 0.75rem;
        color: #94a3b8;
        margin-block-start: 2px;
      }
      .tl-viewer-tools {
        display: flex;
        align-items: center;
        gap: 8px;
      }
      .tl-tool-btn {
        background: #1e293b;
        color: #e2e8f0;
        border: 1px solid #334155;
        border-radius: 6px;
        padding: 6px 10px;
        font-size: 0.78rem;
        cursor: pointer;
        display: inline-flex;
        align-items: center;
        gap: 5px;
        transition: all 0.15s ease;
      }
      .tl-tool-btn:hover {
        background: #334155;
      }
      .tl-tool-btn.is-active {
        background: #0284c7;
        border-color: #38bdf8;
        color: #ffffff;
      }
      .tl-tool-btn--accent {
        background: var(--tl-teal, #14b8a6);
        color: #ffffff;
        border-color: transparent;
      }
      .tl-tool-btn--accent:hover {
        background: #0d9488;
      }
      .tl-zoom-level {
        font-size: 0.78rem;
        color: #94a3b8;
        min-width: 44px;
        text-align: center;
        font-family: monospace;
      }
      .tl-viewer-close {
        border: none;
        background: none;
        color: #94a3b8;
        font-size: 1.5rem;
        cursor: pointer;
        padding: 0 4px;
        line-height: 1;
        margin-inline-start: 8px;
      }
      .tl-viewer-close:hover {
        color: #ffffff;
      }
      .tl-viewer-viewport {
        flex: 1;
        min-height: 0;
        background: #020617;
        display: flex;
        align-items: center;
        justify-content: center;
        overflow: auto;
        padding: 24px;
      }
      .tl-viewer-img {
        max-width: 100%;
        max-height: 100%;
        object-fit: contain;
        transition: transform 0.2s ease, filter 0.2s ease;
        box-shadow: 0 8px 30px rgba(0, 0, 0, 0.5);
      }
      .tl-viewer-img.is-inverted {
        filter: invert(100%) hue-rotate(180deg);
      }
      .tl-viewer-loading {
        display: flex;
        flex-direction: column;
        align-items: center;
        gap: 12px;
        color: #94a3b8;
      }
      .tl-spinner {
        width: 36px;
        height: 36px;
        border: 3px solid rgba(20, 184, 166, 0.2);
        border-top-color: var(--tl-teal);
        border-radius: 50%;
        animation: spin 0.8s linear infinite;
      }
      @keyframes spin {
        to { transform: rotate(360deg); }
      }
      .tl-viewer-fallback {
        color: #94a3b8;
        text-align: center;
        font-size: 0.9rem;
      }
      .tl-viewer-footer-notes {
        padding: 10px 20px;
        background: #090d16;
        border-top: 1px solid #1e293b;
        font-size: 0.8rem;
        color: #cbd5e1;
        flex-shrink: 0;
      }
      /* 3D Dental Viewer Styles */
      .tl-3d-viewport-container {
        position: relative;
        width: 100%;
        height: 100%;
        min-height: 480px;
        background: radial-gradient(circle at 50% 40%, #0d1527 0%, #030712 100%);
        overflow: hidden;
        display: flex;
        align-items: center;
        justify-content: center;
      }
      .tl-3d-canvas {
        width: 100% !important;
        height: 100% !important;
        display: block;
        cursor: grab;
      }
      .tl-3d-canvas:active {
        cursor: grabbing;
      }
      .tl-3d-controls-overlay {
        position: absolute;
        bottom: 16px;
        left: 50%;
        transform: translateX(-50%);
        display: flex;
        flex-direction: column;
        align-items: center;
        gap: 8px;
        pointer-events: none;
        z-index: 10;
        width: 90%;
        max-width: 720px;
      }
      .tl-3d-perspectives-pill {
        pointer-events: auto;
        display: flex;
        align-items: center;
        flex-wrap: wrap;
        justify-content: center;
        gap: 6px;
        background: rgba(15, 23, 42, 0.9);
        backdrop-filter: blur(12px);
        border: 1px solid rgba(51, 65, 85, 0.85);
        border-radius: 9999px;
        padding: 6px 14px;
        box-shadow: 0 10px 25px rgba(0, 0, 0, 0.6);
      }
      .tl-pill-label {
        font-size: 0.72rem;
        font-weight: 700;
        color: #94a3b8;
        text-transform: uppercase;
        letter-spacing: 0.05em;
        margin-right: 4px;
      }
      .tl-pill-btn {
        background: #1e293b;
        color: #cbd5e1;
        border: 1px solid #334155;
        border-radius: 9999px;
        padding: 4px 10px;
        font-size: 0.75rem;
        font-weight: 500;
        cursor: pointer;
        transition: all 0.15s ease;
      }
      .tl-pill-btn:hover {
        background: #38bdf8;
        color: #030712;
        border-color: #38bdf8;
      }
      .tl-pill-btn--reset {
        background: rgba(239, 68, 68, 0.2);
        color: #fca5a5;
        border-color: rgba(239, 68, 68, 0.4);
      }
      .tl-pill-btn--reset:hover {
        background: #ef4444;
        color: #ffffff;
      }
      .tl-3d-hint {
        font-size: 0.7rem;
        color: #94a3b8;
        background: rgba(2, 6, 23, 0.85);
        padding: 4px 12px;
        border-radius: 6px;
        border: 1px solid rgba(30, 41, 59, 0.7);
        text-align: center;
      }
      .tl-3d-badge {
        display: inline-flex;
        align-items: center;
        background: rgba(56, 189, 248, 0.15);
        color: #38bdf8;
        border: 1px solid rgba(56, 189, 248, 0.35);
        border-radius: 4px;
        padding: 1px 6px;
        font-size: 0.68rem;
        font-weight: 700;
        letter-spacing: 0.04em;
      }
      .tl-3d-color-picker {
        display: inline-flex;
        align-items: center;
        gap: 6px;
        background: #1e293b;
        padding: 4px 8px;
        border-radius: 6px;
        border: 1px solid #334155;
      }
      .tl-color-dot {
        width: 14px;
        height: 14px;
        border-radius: 50%;
        border: 2px solid transparent;
        cursor: pointer;
        padding: 0;
        transition: transform 0.15s ease;
      }
      .tl-color-dot:hover {
        transform: scale(1.2);
      }
      .tl-color-dot.is-selected {
        border-color: #ffffff;
        box-shadow: 0 0 8px rgba(255, 255, 255, 0.5);
      }
      .tl-color-dot--ceramic {
        background: #f1f5f9;
      }
      .tl-color-dot--blue {
        background: #38bdf8;
      }
      .tl-color-dot--gold {
        background: #f59e0b;
      }
      .tl-three-col {
        display: grid;
        grid-template-columns: repeat(3, 1fr);
        gap: 16px;
      }
      @media (max-width: 900px) {
        .tl-three-col {
          grid-template-columns: 1fr;
        }
      }
      .tl-col-card {
        background: var(--surf);
        border: 1px solid var(--line);
        border-radius: 8px;
        padding: 16px;
        min-height: 190px;
        box-shadow: var(--shadow);
        color: var(--ink);
        display: flex;
        flex-direction: column;
      }
      .tl-col-title {
        margin: 0 0 6px;
        font-size: 0.95rem;
        font-weight: 700;
        color: var(--ink);
      }
      .tl-col-hint {
        margin: 0 0 10px;
        font-size: 0.76rem;
        color: var(--mute, #94a3b8);
        font-weight: 500;
      }
      .tl-select-wrap {
        position: relative;
        width: 100%;
      }
      .tl-select-control {
        width: 100%;
        padding: 8px 12px;
        border: 1px solid var(--line);
        border-radius: 6px;
        background-color: var(--surf2);
        color: var(--ink);
        font-size: 0.82rem;
        appearance: none;
        -webkit-appearance: none;
        background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='12' height='12' viewBox='0 0 24 24' fill='none' stroke='%238fa7b7' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'%3E%3Cpolyline points='6 9 12 15 18 9'%3E%3C/polyline%3E%3C/svg%3E");
        background-repeat: no-repeat;
        background-position: right 10px center;
        cursor: pointer;
        box-sizing: border-box;
      }
      .tl-select-control:focus {
        outline: none;
        border-color: var(--tl-teal);
      }
      .tl-select-control option {
        background: var(--surf);
        color: var(--ink);
      }
      .tl-note-input-row {
        display: flex;
        gap: 8px;
        align-items: center;
      }
      .tl-note-input {
        flex: 1;
        padding: 7px 12px;
        border: 1px solid var(--line);
        border-radius: 6px;
        background: var(--surf2);
        color: var(--ink);
        font-size: 0.82rem;
        box-sizing: border-box;
      }
      .tl-note-input::placeholder {
        color: var(--mute);
      }
      .tl-note-input:focus {
        outline: none;
        border-color: var(--tl-teal);
      }
      .tl-tag-list {
        display: flex;
        flex-direction: column;
        gap: 6px;
        margin-top: 10px;
      }
      .tl-tag-item {
        display: flex;
        justify-content: space-between;
        align-items: center;
        padding: 6px 10px;
        background: var(--surf2);
        border: 1px solid var(--line);
        border-radius: 6px;
        font-size: 0.8rem;
        color: var(--ink);
      }
      .tl-tag-remove {
        border: 0;
        background: none;
        color: var(--mute, #94a3b8);
        cursor: pointer;
        font-size: 1rem;
        line-height: 1;
        padding: 0 4px;
        transition: color 0.15s ease;
      }
      .tl-tag-remove:hover {
        color: var(--bad, #ef4444);
      }
      .tl-tag-list--notes {
        margin-top: 12px;
      }
      .tl-page-footer {
        margin-top: 36px;
        font-size: 0.88rem;
        font-weight: 600;
        color: var(--tl-teal);
        padding-bottom: 20px;
      }

      
      .tl-invoice-tabs-header {
        display: inline-flex;
        gap: 8px;
        background: var(--surf2);
        padding: 4px;
        border-radius: 8px;
        border: 1px solid var(--line);
      }
      .tl-tab-pill {
        display: inline-flex;
        align-items: center;
        gap: 6px;
        padding: 6px 14px;
        border: none;
        background: transparent;
        color: var(--mute);
        border-radius: 6px;
        font-size: 0.82rem;
        font-weight: 600;
        cursor: pointer;
        transition: background 0.15s ease, color 0.15s ease;
      }
      .tl-tab-pill.is-active {
        background: var(--surf);
        color: var(--tl-teal);
        box-shadow: var(--shadow);
      }
      .tl-estimate-banner {
        display: flex;
        align-items: flex-start;
        gap: 12px;
        background: var(--pri-soft, rgba(0, 156, 166, 0.08));
        border: 1px solid var(--tl-teal);
        border-radius: 8px;
        padding: 12px 16px;
        margin-bottom: 16px;
        font-size: 0.82rem;
        color: var(--ink);
      }
      .tl-estimate-banner p {
        margin: 4px 0 0;
        color: var(--mute);
        font-size: 0.78rem;
      }
      .tl-estimate-icon {
        color: var(--tl-teal);
        flex-shrink: 0;
        margin-top: 2px;
      }
      .estimate-meta-grid {
        display: grid;
        grid-template-columns: 1fr 1fr;
        gap: 12px;
        padding: 12px 16px;
        background: var(--surf2);
        border-radius: 8px;
        border: 1px solid var(--line);
        margin: 14px 0;
        font-size: 0.82rem;
      }
      .estimate-summary-box {
        margin-left: auto;
        max-width: 340px;
        padding: 14px 18px;
        background: var(--surf2);
        border: 1px solid var(--line);
        border-radius: 8px;
        font-size: 0.85rem;
      }
      .estimate-disclaimer {
        margin-top: 18px;
        font-size: 0.75rem;
        color: var(--mute);
        border-left: 3px solid var(--tl-teal);
        padding-left: 10px;
      }
      .estimate-signatures {
        display: grid;
        grid-template-columns: 1fr 1fr;
        gap: 40px;
        margin-top: 36px;
        padding-top: 20px;
      }
      .estimate-sig-col {
        display: flex;
        flex-direction: column;
        align-items: center;
        gap: 6px;
        font-size: 0.8rem;
        color: var(--mute);
      }
      .sig-line {
        width: 100%;
        border-bottom: 1px dashed var(--line);
        margin-bottom: 6px;
      }
      .estimate-badge {
        display: inline-block;
        padding: 2px 8px;
        border-radius: 4px;
        background: var(--pri-soft);
        color: var(--tl-teal);
        font-size: 0.72rem;
        font-weight: 700;
      }
      .tl-btn-instructions {
        background: var(--tl-dark, #27272a);
        color: #ffffff;
        border: 1px solid var(--line);
        border-radius: 6px;
        padding: 8px 14px;
        font-size: 0.82rem;
        font-weight: 500;
        display: inline-flex;
        align-items: center;
        gap: 6px;
        text-decoration: none;
        cursor: pointer;
        transition: background 0.15s ease;
      }
      .tl-btn-instructions:hover {
        background: var(--tl-dark-hover, #18181b);
      }

      /* 9-Step Wizard Modal */
      .tl-wizard-modal {
        position: relative;
        z-index: 100001;
        background: var(--surf);
        border-radius: 12px;
        width: 100%;
        max-width: 1040px;
        height: 90vh;
        max-height: 90vh;
        overflow: hidden;
        box-shadow: 0 25px 50px -12px rgba(0, 0, 0, 0.7);
        display: flex;
        flex-direction: column;
        border: 1px solid var(--line);
      }
      .tl-wizard-header {
        display: flex;
        justify-content: space-between;
        align-items: center;
        padding: 16px 24px;
        border-block-end: 1px solid var(--line);
        flex-shrink: 0;
      }
      .tl-wizard-header h3 {
        margin: 0;
        font-size: 1.15rem;
        font-weight: 700;
      }
      .tl-stepper-bar {
        display: grid;
        grid-template-columns: repeat(9, minmax(85px, 1fr));
        gap: 6px;
        padding: 14px 18px 12px;
        border-block-end: 1px solid var(--line);
        background: var(--surf2);
        overflow-x: auto;
        overflow-y: hidden;
        flex-shrink: 0;
        min-height: 84px;
      }
      .tl-step-item {
        border: 0;
        background: none;
        display: flex;
        flex-direction: column;
        align-items: center;
        gap: 6px;
        cursor: pointer;
        padding: 4px 2px;
        color: var(--mute);
        min-width: 0;
      }
      .tl-step-circle {
        width: 28px;
        height: 28px;
        min-width: 28px;
        min-height: 28px;
        border-radius: 50%;
        background: var(--line);
        color: var(--ink);
        display: inline-flex;
        align-items: center;
        justify-content: center;
        font-size: 0.78rem;
        font-weight: 700;
        flex-shrink: 0;
      }
      .tl-step-item.is-active .tl-step-circle {
        background: var(--tl-teal);
        color: var(--on-accent);
      }
      .tl-step-item.is-done .tl-step-circle {
        background: var(--accent-green);
        color: var(--on-accent);
      }
      .tl-step-label {
        font-size: 0.72rem;
        font-weight: 600;
        text-align: center;
        line-height: 1.25;
        white-space: normal;
        display: -webkit-box;
        -webkit-line-clamp: 2;
        -webkit-box-orient: vertical;
        overflow: hidden;
      }
      .tl-wizard-body {
        padding: 20px 24px;
        display: flex;
        flex-direction: column;
        gap: 16px;
        flex: 1 1 auto;
        overflow-y: auto;
        min-height: 0;
      }
      .tl-step-heading {
        margin: 0;
        font-size: 1.05rem;
        font-weight: 700;
        color: var(--tl-teal);
      }
      .tl-sub-heading {
        margin: 8px 0 4px;
        font-size: 0.92rem;
        font-weight: 700;
        color: var(--ink);
      }
      .tl-form-grid {
        display: grid;
        grid-template-columns: 1fr 1fr;
        gap: 16px;
      }
      .tl-col-span-2 {
        grid-column: 1 / -1;
      }
      .tl-form-stack {
        display: flex;
        flex-direction: column;
        gap: 16px;
      }
      .tl-radio-row {
        display: flex;
        flex-wrap: wrap;
        gap: 14px;
        align-items: center;
        padding: 4px 0;
        font-size: 0.85rem;
      }
      .tl-check-grid {
        display: grid;
        grid-template-columns: repeat(auto-fill, minmax(190px, 1fr));
        gap: 8px 14px;
        padding: 6px 0;
      }
      .tl-check-label {
        display: inline-flex;
        align-items: center;
        gap: 6px;
        font-size: 0.84rem;
        cursor: pointer;
      }
      .tl-chart-quadrants {
        display: grid;
        grid-template-columns: 1fr 1fr;
        gap: 16px;
      }
      .tl-quad-box {
        border: 1px solid var(--line);
        border-radius: 8px;
        padding: 12px;
        background: var(--surf2);
      }
      .tl-quad-title {
        display: block;
        font-size: 0.8rem;
        font-weight: 700;
        color: var(--tl-teal);
        margin-block-end: 8px;
        text-align: center;
      }
      .tl-tooth-circles {
        display: flex;
        flex-wrap: wrap;
        gap: 6px;
        justify-content: center;
      }
      .tl-tooth-circle {
        width: 34px;
        height: 34px;
        border-radius: 50%;
        border: 1px solid var(--line);
        background: var(--surf);
        color: var(--ink);
        font-size: 0.78rem;
        font-weight: 600;
        cursor: pointer;
      }
      .tl-tooth-circle.is-selected {
        background: var(--tl-teal);
        color: var(--on-accent);
        border-color: var(--tl-teal);
      }
      .tl-consent-copy {
        font-size: 0.88rem;
        line-height: 1.55;
        color: var(--ink);
        background: var(--surf2);
        padding: 12px 16px;
        border-radius: 8px;
        margin: 0;
      }
      .tl-sig-grid {
        display: grid;
        grid-template-columns: 1fr 1fr;
        gap: 20px;
      }
      .tl-sig-box {
        display: flex;
        flex-direction: column;
        gap: 8px;
      }
      .tl-sig-label {
        font-size: 0.85rem;
        font-weight: 600;
      }
      .tl-sig-canvas {
        height: 120px;
        border: 1px dashed var(--line);
        border-radius: 8px;
        background: var(--surf2);
        display: flex;
        align-items: center;
        justify-content: center;
        cursor: pointer;
        padding: 12px;
      }
      .tl-sig-canvas.is-signed {
        border-style: solid;
        border-color: var(--tl-teal);
        background: var(--accent-green-soft);
      }
      .tl-sig-script {
        font-family: 'Brush Script MT', 'Segoe Script', cursive;
        font-size: 1.65rem;
        color: var(--ink-strong);
      }
      .tl-sig-placeholder {
        font-size: 0.8rem;
        color: var(--mute);
      }
      .tl-sig-actions {
        display: flex;
        gap: 8px;
      }
      .tl-wizard-footer {
        display: flex;
        justify-content: space-between;
        align-items: center;
        padding: 14px 24px;
        border-block-start: 1px solid var(--line);
        background: var(--surf2);
        flex-shrink: 0;
      }
      .tl-wizard-footer-right {
        display: flex;
        gap: 8px;
      }
      .tl-invoice-modal-title {
        display: flex;
        align-items: center;
        gap: 18px;
      }
      .tl-billing-box {
        border: 1px solid var(--line);
        border-radius: 8px;
        padding: 14px;
        background: var(--surf2);
      }
      .tl-billing-form-row {
        display: grid;
        grid-template-columns: 2fr 1fr 1fr 1fr;
        gap: 12px;
      }
      .tl-billing-save-row {
        display: flex;
        justify-content: flex-end;
        margin-top: 10px;
      }
      .tl-discount-row {
        display: flex;
        align-items: center;
        gap: 16px;
      }
      .tl-billing-totals {
        display: flex;
        justify-content: space-between;
        flex-wrap: wrap;
        gap: 12px;
        padding: 12px 16px;
        background: var(--surf2);
        border-radius: 8px;
        border: 1px solid var(--line);
      }

      /* Preserved clinical encounter styles */
      .no-ai-banner {
        display: flex;
        align-items: center;
        padding: var(--fe-space-2) var(--fe-space-3);
        font-size: var(--fe-text-xs);
        border-radius: var(--fe-radius-md);
        background: var(--surf-soft);
        border: 1px solid var(--line);
        color: var(--mute);
        margin-block-end: 16px;
      }
      .encounters-layout {
        display: grid;
        grid-template-columns: 320px 1fr;
        gap: var(--fe-space-4);
        align-items: start;
      }
      .encounter-cards {
        display: flex;
        flex-direction: column;
        gap: var(--fe-space-2);
      }
      .encounter-card {
        padding: var(--fe-space-3);
        border-radius: var(--fe-radius-lg);
        background: var(--surf);
        border: 1px solid var(--line);
        cursor: pointer;
        display: flex;
        flex-direction: column;
        gap: var(--fe-space-2);
      }
      .encounter-card--active {
        border-color: var(--pri);
        background: var(--surf-soft);
      }
      .encounter-card-header {
        display: flex;
        justify-content: space-between;
        align-items: center;
      }
      .encounter-detail-area {
        display: flex;
        flex-direction: column;
        gap: var(--fe-space-4);
      }
      .encounter-main-card,
      .rx-card {
        padding: var(--fe-space-4);
      }
      .detail-header {
        display: flex;
        justify-content: space-between;
        align-items: flex-start;
        gap: var(--fe-space-3);
        margin-block-end: var(--fe-space-4);
        border-block-end: 1px solid var(--line);
        padding-block-end: var(--fe-space-3);
        flex-wrap: wrap;
      }
      .detail-badges {
        display: flex;
        align-items: center;
        gap: var(--fe-space-2);
        margin-block-end: var(--fe-space-2);
      }
      .detail-header-actions {
        display: flex;
        align-items: center;
        gap: var(--fe-space-2);
      }
      .clinical-grid {
        display: grid;
        grid-template-columns: 1fr 1fr;
        gap: var(--fe-space-3);
      }
      .clinical-block {
        padding: var(--fe-space-3);
        border-radius: var(--fe-radius-md);
        background: var(--surf-soft);
        border: 1px solid var(--line);
      }
      .rx-header {
        display: flex;
        justify-content: space-between;
        align-items: center;
        margin-block-end: var(--fe-space-3);
      }
      .modal-backdrop {
        position: fixed;
        inset: 0;
        background: var(--scrim);
        display: flex;
        align-items: center;
        justify-content: center;
        z-index: 100000;
        padding: var(--fe-space-4);
      }
      .modal-card {
        width: 100%;
        max-width: 500px;
        background: var(--surf);
        border-radius: var(--fe-radius-xl);
        border: 1px solid var(--line);
        box-shadow: var(--shadow);
        display: flex;
        flex-direction: column;
        max-height: 90vh;
        overflow-y: auto;
      }
      .modal-card--lg {
        max-width: 680px;
      }
      .modal-header {
        display: flex;
        justify-content: space-between;
        align-items: center;
        padding: var(--fe-space-3) var(--fe-space-4);
        border-block-end: 1px solid var(--line);
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
      }
      .form-field {
        display: flex;
        flex-direction: column;
        gap: 4px;
        font-size: 0.84rem;
      }
      .form-row {
        display: flex;
        gap: 12px;
      }
      .form-row > * {
        flex: 1;
      }
      .fe-input, .fe-textarea, .fe-select {
        width: 100%;
        padding: 8px 12px;
        border: 1px solid var(--line);
        border-radius: 6px;
        background: var(--surf);
        color: var(--ink);
        box-sizing: border-box;
      }
      .tl-viewer-mode-group {
        display: inline-flex;
        gap: 4px;
        padding-right: 8px;
        border-right: 1px solid rgba(255, 255, 255, 0.16);
      }
      .tl-tool-select {
        padding: 4px 8px;
        border-radius: 6px;
        border: 1px solid rgba(255, 255, 255, 0.2);
        background: #0f172a;
        color: #f8fafc;
        font-size: 12px;
      }
      .tl-2d-stage {
        position: relative;
        width: 100%;
        height: 100%;
        display: flex;
        align-items: center;
        justify-content: center;
        overflow: hidden;
      }
      .tl-2d-stage.has-tool { cursor: crosshair; }
      .tl-measure-svg {
        position: absolute;
        inset: 0;
        width: 100%;
        height: 100%;
        pointer-events: none;
      }
      .tl-caliper-line { stroke: #14b8a6; stroke-width: 3; }
      .tl-angle-line { fill: none; stroke: #38bdf8; stroke-width: 3; }
      .tl-nerve-path {
        fill: none;
        stroke: #ff6d00;
        stroke-width: 4;
        stroke-linecap: round;
        stroke-linejoin: round;
      }
      .tl-caliper-label {
        fill: #facc15;
        font-size: 18px;
        font-weight: 700;
        text-anchor: middle;
      }
      .tl-mpr-grid {
        display: grid;
        grid-template-columns: 1fr 1fr;
        grid-template-rows: 1fr 1fr;
        gap: 6px;
        width: 100%;
        height: 100%;
        padding: 6px;
        box-sizing: border-box;
      }
      .tl-mpr-pane {
        position: relative;
        background: #000000;
        border: 1px solid rgba(148, 163, 184, 0.25);
        border-radius: 6px;
        overflow: hidden;
        display: flex;
        flex-direction: column;
      }
      .tl-mpr-pane-head {
        display: flex;
        justify-content: space-between;
        align-items: center;
        gap: 8px;
        padding: 4px 8px;
        background: rgba(15, 23, 42, 0.88);
        color: #cbd5e1;
        font-size: 11px;
        font-weight: 700;
      }
      .tl-mpr-pane-head input[type="range"] { width: 110px; accent-color: #14b8a6; }
      .tl-mpr-canvas {
        position: relative;
        flex: 1;
        display: flex;
        align-items: center;
        justify-content: center;
        overflow: hidden;
      }
      .tl-mpr-img {
        max-width: 100%;
        max-height: 100%;
        object-fit: contain;
        transition: filter 0.15s ease;
      }
      .hu-BONE { filter: contrast(1.45) brightness(1.05); }
      .hu-ENAMEL { filter: contrast(1.85) brightness(0.95); }
      .hu-SOFT { filter: contrast(0.9) brightness(1.18); }
      .hu-MIP { filter: contrast(2.1) brightness(1.25); }
      .tl-crosshair-h {
        position: absolute;
        left: 0;
        right: 0;
        border-top: 1px dashed #22d3ee;
        pointer-events: none;
      }
      .tl-crosshair-v {
        position: absolute;
        top: 0;
        bottom: 0;
        border-left: 1px dashed #facc15;
        pointer-events: none;
      }
      .tl-intake-pending-bar {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: 12px;
        flex-wrap: wrap;
        padding: 12px 16px;
        margin-bottom: 12px;
        border-radius: 8px;
        background: #fef3c7;
        border: 1px solid #f59e0b;
        color: #78350f;
      }
      .tl-wizard-intake-review-banner {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: 12px;
        flex-wrap: wrap;
        padding: 10px 14px;
        margin-bottom: 14px;
        border-radius: 8px;
        background: #ecfeff;
        border: 1px solid #14b8a6;
        color: #134e4a;
        font-size: 0.88rem;
      }
      .tl-badge-pending {
        background: #fef3c7;
        color: #92400e;
        border: 1px solid #f59e0b;
      }
      .tl-source-chip {
        display: inline-block;
        margin-inline-start: 6px;
        padding: 1px 7px;
        font-size: 0.7rem;
        font-weight: 700;
        border-radius: 999px;
        background: #ccfbf1;
        color: #0f766e;
      }
    `,
  ],
})
export class ClinicEncountersComponent {
  private readonly api = inject(ClinicPatientApi);
  private readonly clinicApi = inject(ClinicApi);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly projects = inject(ProjectContextService);
  protected readonly rbac = inject(ClinicRbacService);
  protected readonly canWriteClinical = computed(() =>
    this.rbac.hasAnyPermission(['clinical.write_record']) ||
    this.rbac.activeRoles().includes('doctor') ||
    this.rbac.activeRoles().includes('clinic_admin')
  );
  protected readonly canViewClinical = computed(() =>
    this.rbac.hasAnyPermission(['clinical.view_record', 'clinical.write_record', 'clinical.view_history']) ||
    this.rbac.activeRoles().includes('doctor') ||
    this.rbac.activeRoles().includes('clinic_admin')
  );

  protected readonly format = inject(FormatService);
  protected readonly encountersStore = new ResourceStore<ClinicEncounterHeader[]>();
  protected readonly selectedEncounterId = signal<string | null>(null);
  protected readonly activeDetail = signal<ClinicEncounterDetail | null>(null);
  protected readonly doctorsList = signal<Array<{ id: string; name: string; specialty: string }>>([]);
  protected readonly patientPlans = signal<ClinicTreatmentPlanHeader[]>([]);
  protected readonly selectedPlanItems = signal<ClinicTreatmentPlanItem[]>([]);

  // ToothLogic Encounter Context (populated from API or Appointment queryParams)
  protected readonly currentClinicName = signal<string>('BrightSmiles Dental Clinic');
  protected readonly currentClinicEmail = signal<string>('contact@brightsmilesclinic.com');
  protected readonly queryEncounterDateTime = signal<string>('2025-11-27 3:18 PM');
  protected readonly currentEncounterDateTime = computed(() => {
    const visit = this.activeDetail()?.encounter.visitDate;
    if (visit) {
      try {
        const d = new Date(visit);
        if (!isNaN(d.getTime())) {
          const y = d.getFullYear();
          const m = String(d.getMonth() + 1).padStart(2, '0');
          const day = String(d.getDate()).padStart(2, '0');
          let hours = d.getHours();
          const ampm = hours >= 12 ? 'PM' : 'AM';
          hours = hours % 12 || 12;
          const mins = String(d.getMinutes()).padStart(2, '0');
          return `${y}-${m}-${day} ${hours}:${mins} ${ampm}`;
        }
      } catch {}
    }
    return this.queryEncounterDateTime() || '2025-11-27 3:18 PM';
  });

  protected readonly problemOptions = [
    'Select Problems',
    'Dental Caries',
    'Toothache',
    'Gingival Bleeding',
    'Fractured Tooth',
    'Tooth Sensitivity',
    'Halitosis',
    'Missing Teeth',
    'Malocclusion',
    'Aesthetic Concern',
  ];

  protected readonly observationOptions = [
    'Select Observation',
    'Poor Oral Hygiene',
    'Plaque & Calculus Accumulation',
    'Gingival Inflammation',
    'Deep Periodontal Pockets',
    'Enamel Erosion',
    'Bruxism Wear Facets',
    'Cervical Abrasion',
    'Third Molar Impaction',
  ];

  protected onSelectProblem(event: Event): void {
    const select = event.target as HTMLSelectElement;
    const val = select.value;
    if (val && val !== 'Select Problems') {
      if (!this.problemsList().includes(val)) {
        this.problemsList.update((list) => [...list, val]);
      }
      select.value = 'Select Problems';
    }
  }

  protected onSelectObservation(event: Event): void {
    const select = event.target as HTMLSelectElement;
    const val = select.value;
    if (val && val !== 'Select Observation') {
      if (!this.observationsList().includes(val)) {
        this.observationsList.update((list) => [...list, val]);
      }
      select.value = 'Select Observation';
    }
  }

  protected readonly queryPatientName = signal<string>('Ali Abbas');
  protected readonly queryPatientEmail = signal<string>('ali.abbas@gmail.com');
  protected readonly queryPatientAddress = signal<string>('22 Park Lane, Gulberg II');
  protected readonly queryDoctorName = signal<string>('Dr. Eman Khan');
  protected readonly queryEncounterDate = signal<string>(new Date().toISOString().slice(0, 10));
  protected readonly queryService = signal<string>('Scaling and Polishing');
  protected readonly queryPrice = signal<number>(150);
  protected readonly encounterClosed = signal<boolean>(false);

  protected readonly currentPatientName = computed(
    () =>
      this.activeDetail()?.encounter.patientName ||
      this.loadedPatient()?.displayName ||
      this.queryPatientName() ||
      'Ali Abbas',
  );
  protected readonly currentPatientEmail = computed(
    () => this.loadedPatient()?.primaryEmail || this.queryPatientEmail() || 'patient@clinicflow.com',
  );
  protected readonly currentPatientAddress = computed(
    () => this.loadedPatient()?.addressLine || this.queryPatientAddress() || '22 Park Lane, Gulberg II',
  );
  protected readonly currentDoctorName = computed(
    () => this.activeDetail()?.encounter.doctorName || this.queryDoctorName() || 'Dr. Eman Khan',
  );
  protected readonly currentEncounterDate = computed(() => {
    const visit = this.activeDetail()?.encounter.visitDate;
    return visit ? visit.slice(0, 10) : this.queryEncounterDate();
  });
  protected readonly patientInitials = computed(() => {
    const name = this.currentPatientName();
    return name
      .split(/\s+/)
      .slice(0, 2)
      .map((p) => p.charAt(0).toUpperCase())
      .join('') || 'PT';
  });

  // 9-Step Patient History Wizard State
  protected readonly historySteps = HISTORY_STEPS;
  protected readonly medicalConditions = MEDICAL_CONDITIONS;
  protected readonly medicationTypes = MEDICATION_TYPES;
  protected readonly allergyTypes = ALLERGY_TYPES;
  protected readonly dentalSymptoms = DENTAL_SYMPTOMS;
  protected readonly radiographTypes = RADIOGRAPH_TYPES;
  protected readonly proposedPlans = PROPOSED_PLANS;
  protected readonly upperRightTeeth = UPPER_RIGHT_TEETH;
  protected readonly upperLeftTeeth = UPPER_LEFT_TEETH;
  protected readonly lowerRightTeeth = LOWER_RIGHT_TEETH;
  protected readonly lowerLeftTeeth = LOWER_LEFT_TEETH;

  protected readonly showHistoryWizard = signal<boolean>(false);
  protected readonly wizardStep = signal<number>(1);
  protected readonly editingHistoryId = signal<string | null>(null);
  protected readonly historyRecords = signal<PatientHistoryRecord[]>([]);
  protected readonly loadedPatient = signal<ClinicPatient | null>(null);
  protected readonly savingHistory = signal<boolean>(false);
  protected readonly pendingIntakeRecord = computed(
    () => this.historyRecords().find((r) => r.status === 'PENDING_APPROVAL') ?? null,
  );

  protected draftHistory: PatientHistoryRecord = this.createBlankHistory();

  // Problems, Observations, Quick Notes State
  protected readonly problemsList = signal<string[]>([]);
  protected newProblemText = '';
  protected readonly observationsList = signal<string[]>([]);
  protected newObservationText = '';
  protected readonly quickNotesList = signal<string[]>([]);
  protected newQuickNoteText = '';
  protected otherDetailsText = '';

  // Patient Records (Radiographs) & Follow-Up Notes
  protected readonly showAddRecordModal = signal<boolean>(false);
  protected newRecordType = 'AUTO';
  protected autoDetectedTypeInfo: { type: string; reason: string } | null = null;
  protected newRecordFileName = '';
  protected newRecordNotes = '';
  protected newRecordToothFdi: number | null = null;
  protected selectedRecordFile: File | null = null;
  protected selectedRecordPreviewUrl: string | null = null;
  protected isDragOverRecordFile = false;
  protected isUploadingRecord = false;
  protected readonly radiographRecords = signal<EncounterRadiographRow[]>([]);
  protected readonly viewingRadiograph = signal<EncounterRadiographRow | null>(null);
  protected radiographZoom = 1;
  protected radiographInverted = false;
  protected isDicomDecoding = false;
  protected is3DLoading = false;
  protected is3DWireframe = false;
  protected is3DAutoRotate = false;
  protected current3DColor: 'ceramic' | 'blue' | 'gold' = 'ceramic';
  private active3DScene: Dental3DScene | null = null;
  protected readonly is3DFile = is3DFile;

  // Req 1 & Req 5: Inventory Warnings, Imaging Orders & CBCT MPR / Measurement Viewer State
  protected readonly inventoryWarningsModal = signal<readonly InventoryStockWarning[]>([]);
  protected readonly patientImagingRequests = signal<readonly ImagingRequestRow[]>([]);
  protected readonly imagingLabsList = signal<readonly ImagingLabRow[]>([]);
  protected readonly showOrderImagingModal = signal(false);
  protected orderImgType = 'CBCT';
  protected orderImgCount = 1;
  protected orderImgLabId = '';
  protected orderImgLabName = '';
  protected orderImgCost = 0;
  protected orderImgCharge = 0;
  protected orderImgTeeth = '';
  protected orderImgIndication = '';
  protected orderImgCloudUrl = '';

  protected viewerMode: '2D' | 'CBCT_MPR' | '3D' = '2D';
  protected measureTool: 'NONE' | 'DISTANCE' | 'ANGLE' | 'NERVE_CANAL' = 'NONE';
  protected huPreset: 'BONE' | 'ENAMEL' | 'SOFT' | 'MIP' = 'BONE';
  protected axialSlice = 50;
  protected coronalSlice = 50;
  protected sagittalSlice = 50;
  protected panArchDepth = 16;
  protected distanceLines: Array<{ x1: number; y1: number; x2: number; y2: number; mm: string }> = [];
  protected distanceStart: { x: number; y: number } | null = null;
  protected angleShapes: Array<{ pointsAttr: string; bx: number; by: number; deg: string }> = [];
  protected anglePoints: Array<{ x: number; y: number }> = [];
  protected nervePoints: Array<{ x: number; y: number }> = [];

  protected readonly showAddFollowUpModal = signal<boolean>(false);
  protected newFollowUpTitle = '';
  protected newFollowUpNote = '';
  protected readonly followUpNotes = signal<EncounterFollowUpRow[]>([]);

  // Generate Invoice & Installments Modal State (Screenshot 11)
  protected readonly showEstimatePrintModal = signal<boolean>(false);

  protected bookFollowUpAppointment(item: EncounterFollowUpRow): void {
    void this.router.navigate(['/app/clinic/appointments'], {
      queryParams: {
        create: '1',
        patientId: this.patientId() ?? '',
        patientName: this.currentPatientName(),
        doctorId: this.activeDetail()?.encounter.doctorId ?? '',
        doctorName: this.currentDoctorName(),
        service: item.title || this.queryService(),
        notes: item.note ? `${item.title ? item.title + ': ' : ''}${item.note}` : 'Follow-up appointment',
        date: item.date || new Date().toISOString().slice(0, 10),
      },
    });
  }

  protected openEstimatePrintSheet(): void {
    this.showEstimatePrintModal.set(true);
  }

  protected convertEstimateToInvoice(): void {
    this.invoiceEstimateOnly = false;
  }

  protected readonly showGenerateInvoiceModal = signal<boolean>(false);
  protected invoiceEstimateOnly = false;
  protected newBillingService = 'Scaling and Polishing';
  protected newBillingPrice = 150;
  protected newBillingQty = 1;
  protected enableBillingDiscount = false;
  protected billingDiscountAmount = 0;
  protected readonly billingItems = signal<BillingServiceRow[]>([
    {
      id: 'bi-1',
      serviceName: 'Scaling and Polishing',
      discount: 0,
      quantity: 1,
      price: 150,
      totalAmount: 150,
    },
  ]);
  protected newInstallmentAmount = 150;
  protected newInstallmentMode: 'Cash' | 'Card' | 'Bank Transfer' = 'Cash';
  protected newInstallmentDate = new Date().toISOString().slice(0, 10);
  protected readonly installmentRows = signal<BillingInstallmentRow[]>([
    {
      id: 'inst-1',
      amount: 150,
      paymentMode: 'Cash',
      date: new Date().toISOString().slice(0, 10),
    },
  ]);

  protected readonly billingTotalAmount = computed(() =>
    this.billingItems().reduce((sum, item) => sum + item.totalAmount, 0),
  );
  protected readonly billingPayableAmount = computed(() => {
    const discount = this.enableBillingDiscount ? Number(this.billingDiscountAmount || 0) : 0;
    return Math.max(0, this.billingTotalAmount() - discount);
  });
  protected readonly billingPaidAmount = computed(() =>
    this.installmentRows().reduce((sum, item) => sum + Number(item.amount || 0), 0),
  );
  protected readonly billingRemainingAmount = computed(() =>
    Math.max(0, this.billingPayableAmount() - this.billingPaidAmount()),
  );

  // Create Encounter modal state
  protected readonly showCreateModal = signal<boolean>(false);
  protected newDoctorId: string | null = null;
  protected newChiefComplaint = '';
  protected newDiagnosis = '';
  protected newExaminationNotes = '';
  protected newPerformedProcedures = '';
  protected newToothFdi: number | null = null;
  protected newToothLocation = '';
  protected newClinicalNotes = '';
  protected newStatus: EncounterStatus = 'COMPLETED';
  protected newDuration = 30;
  protected newTreatmentPlanId: string | null = null;
  protected newTreatmentPlanItemId: string | null = null;
  protected newCompleteLinkedStep = true;
  protected quickRxName = '';
  protected quickRxDosage = '';
  protected quickRxFrequency = '';
  protected quickRxDuration = '';
  protected newBpSystolic: number | null = null;
  protected newBpDiastolic: number | null = null;
  protected newPulse: number | null = null;
  protected newTemp: number | null = null;

  // Edit Diagnosis modal state
  protected readonly showEditDiagnosisModal = signal<boolean>(false);
  protected editDiagnosis = '';
  protected editExamNotes = '';
  protected editPerformedProcedures = '';
  protected editClinicalNotes = '';
  protected editBpSystolic: number | null = null;
  protected editBpDiastolic: number | null = null;
  protected editPulse: number | null = null;
  protected editTemp: number | null = null;

  // Add Amendment modal state
  protected readonly showAddAmendmentModal = signal<boolean>(false);
  protected amendmentReason = '';
  protected amendmentContent = '';

  // Add Prescription modal state
  protected readonly showAddRxModal = signal<boolean>(false);
  protected addRxName = '';
  protected addRxDosage = '';
  protected addRxFrequency = '';
  protected addRxDuration = '';
  protected addRxInstructions = '';

  // Print modal state
  protected readonly showPrintModal = signal<boolean>(false);
  protected readonly printTargetDetail = signal<ClinicEncounterDetail | null>(null);

  protected readonly patientId = signal<string | null>(null);
  protected readonly directEncounterId = signal<string | null>(null);
  protected readonly projectId = computed(() => this.projects.selectedProjectId() ?? undefined);

  constructor() {
    inject(I18nService).register({ clinic: clinicStrings as Namespace });

    if (!this.canViewClinical()) {
      void this.router.navigate(['/app/clinic/calendar']);
      return;
    }

    this.route.queryParamMap.subscribe((qp) => {
      const pName = qp.get('patientName');
      const pEmail = qp.get('patientEmail');
      const dName = qp.get('doctorName');
      const svc = qp.get('service');
      const prc = qp.get('price');
      const dt = qp.get('date');
      const pId = qp.get('patientId');
      if (pName) this.queryPatientName.set(pName);
      if (pEmail) this.queryPatientEmail.set(pEmail);
      if (dName) this.queryDoctorName.set(dName);
      if (dt) {
        this.queryEncounterDate.set(dt.slice(0, 10));
        try {
          const d = new Date(dt);
          if (!isNaN(d.getTime())) {
            const y = d.getFullYear();
            const m = String(d.getMonth() + 1).padStart(2, '0');
            const day = String(d.getDate()).padStart(2, '0');
            let hours = d.getHours();
            const ampm = hours >= 12 ? 'PM' : 'AM';
            hours = hours % 12 || 12;
            const mins = String(d.getMinutes()).padStart(2, '0');
            this.queryEncounterDateTime.set(`${y}-${m}-${day} ${hours}:${mins} ${ampm}`);
          }
        } catch {}
      }
      if (pId) this.patientId.set(pId);
      if (svc) {
        this.queryService.set(svc);
        this.newBillingService = svc;
      }
      if (prc && !Number.isNaN(Number(prc))) {
        const num = Number(prc);
        this.queryPrice.set(num);
        this.newBillingPrice = num;
        this.billingItems.set([
          {
            id: 'bi-1',
            serviceName: svc || 'Dental Procedure',
            discount: 0,
            quantity: 1,
            price: num,
            totalAmount: num,
          },
        ]);
        this.newInstallmentAmount = num;
        this.installmentRows.set([
          {
            id: 'inst-1',
            amount: num,
            paymentMode: 'Cash',
            date: new Date().toISOString().slice(0, 10),
          },
        ]);
      }
    });

    this.route.paramMap.subscribe((params) => {
      const pId = params.get('id');
      const isDirectEncounter = this.route.snapshot.routeConfig?.path?.startsWith('clinic/encounters');
      if (isDirectEncounter && pId) {
        this.directEncounterId.set(pId);
        void this.loadDirectEncounter(pId);
      } else if (pId) {
        this.patientId.set(pId);
        void this.reloadEncounters();
        void this.loadPatientPlans(pId);
        void this.loadPatientRadiographs(pId);
        void this.loadPatientImagingRequests(pId);
        void this.loadPatientContextAndHistory(pId);
      }
    });

    void this.loadDoctors();
    void this.loadImagingLabs();
  }

  private createBlankHistory(): PatientHistoryRecord {
    const p = this.loadedPatient();
    const pName = p?.displayName || this.currentPatientName();
    const rawGender = (p?.gender || '').toUpperCase();
    const wizGender: 'Male' | 'Female' | 'Other' =
      rawGender === 'FEMALE' ? 'Female' : rawGender === 'OTHER' ? 'Other' : 'Male';
    return {
      id: `hist-${Date.now()}`,
      date: new Date().toISOString().slice(0, 10),
      name: pName,
      isComplete: false,
      status: 'APPROVED',
      source: 'CLINIC_STAFF',
      demographics: {
        fullName: pName,
        dob: p?.dateOfBirth || '',
        gender: wizGender,
        occupation: p?.occupation || '',
        address: p?.addressLine || this.currentPatientAddress(),
        phone: p?.primaryPhone || '',
        email: p?.primaryEmail || this.currentPatientEmail(),
        emergencyName: '',
        emergencyPhone: '',
      },
      medicalHistory: {
        underTreatment: 'No',
        treatmentSpecify: '',
        hospitalizedLastYear: 'No',
        hospitalizedReason: '',
        conditions: [],
        otherCondition: '',
        pregnantOrBreastfeeding: 'N/A',
        medications: [],
        otherMedications: '',
        allergies: [],
        otherAllergies: '',
      },
      dentalHistory: {
        lastDentalVisitDate: new Date().toISOString().slice(0, 10),
        lastVisitReason: '',
        symptoms: [],
        pastProcedures: '',
        anxietyLevel: 1,
      },
      chiefComplaint: {
        primaryComplaint: '',
        painScale: 0,
        duration: '',
        triggers: [],
      },
      clinicalExam: {
        faceSymmetry: 'Normal',
        faceSymmetryDetails: '',
        tmj: 'Normal',
        softTissues: 'Normal',
        softTissuesDetails: '',
        teethFindings: [],
        gingivalHealth: 'Healthy',
        bleedingOnProbing: 'No',
        pocketDepths: '',
        mobility: 'Absent',
        occlusion: 'Normal',
        bruxism: 'No',
      },
      radiographs: {
        types: [],
        findings: '',
      },
      diagnosisAndPlan: {
        diagnosis: '',
        proposedPlans: [],
        otherPlan: '',
        plannedTimeline: '',
        alternativesDiscussed: 'Yes',
        risksExplained: 'Yes',
        questionsAddressed: 'Yes',
      },
      dentalChart: {
        selectedTeeth: [],
        toothCondition: '',
        chartNotes: '',
      },
      informedConsent: {
        agreed: true,
        patientSignatureDataUrl: '',
        patientSignatureSaved: false,
        dentistSignatureDataUrl: '',
        dentistSignatureSaved: false,
        signedAt: '',
      },
    };
  }

  protected openHistoryWizard(): void {
    this.editingHistoryId.set(null);
    this.draftHistory = this.createBlankHistory();
    this.wizardStep.set(1);
    this.showHistoryWizard.set(true);
  }

  protected editHistoryRecord(rec: PatientHistoryRecord): void {
    this.editingHistoryId.set(rec.id);
    this.draftHistory = JSON.parse(JSON.stringify(rec)) as PatientHistoryRecord;
    this.wizardStep.set(1);
    this.showHistoryWizard.set(true);
  }

  protected closeHistoryWizard(): void {
    this.showHistoryWizard.set(false);
  }

  protected goToWizardStep(step: number): void {
    if (step >= 1 && step <= 9) {
      this.wizardStep.set(step);
    }
  }

  protected nextWizardStep(): void {
    if (this.wizardStep() < 9) {
      this.wizardStep.update((s) => s + 1);
    }
  }

  protected prevWizardStep(): void {
    if (this.wizardStep() > 1) {
      this.wizardStep.update((s) => s - 1);
    }
  }

  protected toggleArrayItem(arr: string[], value: string): void {
    const idx = arr.indexOf(value);
    if (idx >= 0) {
      arr.splice(idx, 1);
    } else {
      arr.push(value);
    }
  }

  protected toggleToothInWizard(tooth: number): void {
    const list = this.draftHistory.dentalChart.selectedTeeth;
    const idx = list.indexOf(tooth);
    if (idx >= 0) {
      list.splice(idx, 1);
    } else {
      list.push(tooth);
    }
  }

  protected signPatientPad(): void {
    this.draftHistory.informedConsent.patientSignatureDataUrl =
      this.draftHistory.demographics.fullName || this.currentPatientName();
  }

  protected clearPatientPad(): void {
    this.draftHistory.informedConsent.patientSignatureDataUrl = '';
    this.draftHistory.informedConsent.patientSignatureSaved = false;
  }

  protected savePatientPad(): void {
    if (!this.draftHistory.informedConsent.patientSignatureDataUrl) {
      this.signPatientPad();
    }
    this.draftHistory.informedConsent.patientSignatureSaved = true;
  }

  protected signDentistPad(): void {
    this.draftHistory.informedConsent.dentistSignatureDataUrl = this.currentDoctorName();
  }

  protected clearDentistPad(): void {
    this.draftHistory.informedConsent.dentistSignatureDataUrl = '';
    this.draftHistory.informedConsent.dentistSignatureSaved = false;
  }

  protected saveDentistPad(): void {
    if (!this.draftHistory.informedConsent.dentistSignatureDataUrl) {
      this.signDentistPad();
    }
    this.draftHistory.informedConsent.dentistSignatureSaved = true;
  }

  protected async saveHistoryWizard(markComplete = false, approvePending = false): Promise<void> {
    const isUuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(this.draftHistory.id || '');
    const shouldApprove =
      approvePending ||
      markComplete ||
      this.draftHistory.status !== 'PENDING_APPROVAL';
    const nextIsComplete =
      markComplete ||
      this.draftHistory.isComplete ||
      (this.draftHistory.informedConsent.patientSignatureSaved &&
        this.draftHistory.informedConsent.dentistSignatureSaved);

    let record: PatientHistoryRecord = {
      ...this.draftHistory,
      name: this.draftHistory.demographics.fullName || this.currentPatientName(),
      isComplete: nextIsComplete,
      status: shouldApprove ? (nextIsComplete ? 'COMPLETED' : 'APPROVED') : 'PENDING_APPROVAL',
    };

    const pId = this.patientId();
    if (pId) {
      this.savingHistory.set(true);
      try {
        const saved = await this.api.saveHistoryRecord(
          pId,
          {
            id: isUuid ? this.draftHistory.id : undefined,
            encounterId: this.selectedEncounterId() ?? undefined,
            status: record.status,
            isComplete: record.isComplete,
            approve: shouldApprove,
            date: record.date,
            name: record.name,
            demographics: record.demographics,
            medicalHistory: record.medicalHistory,
            dentalHistory: record.dentalHistory,
            chiefComplaint: record.chiefComplaint,
            clinicalExam: record.clinicalExam,
            radiographs: record.radiographs,
            diagnosisAndPlan: record.diagnosisAndPlan,
            dentalChart: record.dentalChart,
            informedConsent: record.informedConsent,
          },
          { projectId: this.projectId() },
        );
        record = this.mapBackendHistoryRecord(saved);
        this.draftHistory = JSON.parse(JSON.stringify(record)) as PatientHistoryRecord;
      } catch {
        // Keep local wizard record if backend save call fails
      } finally {
        this.savingHistory.set(false);
      }
    }

    const editingId = this.editingHistoryId();
    if (editingId) {
      this.historyRecords.update((list) => list.map((item) => (item.id === editingId ? record : item)));
      this.editingHistoryId.set(record.id);
    } else {
      this.historyRecords.update((list) => [record, ...list]);
      this.editingHistoryId.set(record.id);
    }

    if (markComplete) {
      this.showHistoryWizard.set(false);
    }
  }

  protected async approveAndContinueWizard(): Promise<void> {
    await this.saveHistoryWizard(false, true);
    if (this.wizardStep() < 5) {
      this.wizardStep.set(5);
    } else {
      this.nextWizardStep();
    }
  }

  protected async deleteHistoryRecord(id: string): Promise<void> {
    this.historyRecords.update((list) => list.filter((item) => item.id !== id));
    const pId = this.patientId();
    const isUuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(id);
    if (pId && isUuid) {
      try {
        await this.api.deleteHistoryRecord(pId, id, { projectId: this.projectId() });
      } catch {
        // Ignore delete failure
      }
    }
  }

  private async loadPatientContextAndHistory(pId: string): Promise<void> {
    try {
      const patient = await this.api.patient(pId, { projectId: this.projectId() });
      this.loadedPatient.set(patient);
    } catch {
      // Ignore if unavailable
    }

    try {
      const records = await this.api.listHistoryRecords(pId, { projectId: this.projectId() });
      const mapped = records.map((r) => this.mapBackendHistoryRecord(r));
      this.historyRecords.set(mapped);
    } catch {
      // Keep empty
    }
  }

  private mapBackendHistoryRecord(r: PatientHistoryIntakeRecord): PatientHistoryRecord {
    const blank = this.createBlankHistory();
    const demo = r.demographics ?? {};
    const med = r.medicalHistory ?? {};
    const dent = r.dentalHistory ?? {};
    const cc = r.chiefComplaint ?? {};
    const exam = r.clinicalExam ?? {};
    const rad = r.radiographs ?? {};
    const dx = r.diagnosisAndPlan ?? {};
    const chart = r.dentalChart ?? {};
    const consent = r.informedConsent ?? {};

    return {
      id: r.id,
      date: r.date || blank.date,
      name: r.name || blank.name,
      isComplete: Boolean(r.isComplete),
      status: r.status,
      source: r.source,
      demographics: { ...blank.demographics, ...demo },
      medicalHistory: {
        ...blank.medicalHistory,
        ...med,
        conditions: Array.isArray(med['conditions']) ? [...med['conditions']] : [],
        medications: Array.isArray(med['medications']) ? [...med['medications']] : [],
        allergies: Array.isArray(med['allergies']) ? [...med['allergies']] : [],
      },
      dentalHistory: {
        ...blank.dentalHistory,
        ...dent,
        symptoms: Array.isArray(dent['symptoms']) ? [...dent['symptoms']] : [],
        anxietyLevel: typeof dent['anxietyLevel'] === 'number' ? dent['anxietyLevel'] : 1,
      },
      chiefComplaint: {
        ...blank.chiefComplaint,
        ...cc,
        painScale: typeof cc['painScale'] === 'number' ? cc['painScale'] : 0,
        triggers: Array.isArray(cc['triggers']) ? [...cc['triggers']] : [],
      },
      clinicalExam: {
        ...blank.clinicalExam,
        ...exam,
        teethFindings: Array.isArray(exam['teethFindings']) ? [...exam['teethFindings']] : [],
      },
      radiographs: {
        ...blank.radiographs,
        ...rad,
        types: Array.isArray(rad['types']) ? [...rad['types']] : [],
      },
      diagnosisAndPlan: {
        ...blank.diagnosisAndPlan,
        ...dx,
        proposedPlans: Array.isArray(dx['proposedPlans']) ? [...dx['proposedPlans']] : [],
      },
      dentalChart: {
        ...blank.dentalChart,
        ...chart,
        selectedTeeth: Array.isArray(chart['selectedTeeth']) ? [...chart['selectedTeeth']] : [],
      },
      informedConsent: {
        ...blank.informedConsent,
        ...consent,
      },
    };
  }

  protected printHistoryRecord(rec: PatientHistoryRecord): void {
    this.editHistoryRecord(rec);
    setTimeout(() => window.print(), 100);
  }

  protected viewHistorySummary(rec: PatientHistoryRecord): void {
    this.editHistoryRecord(rec);
    this.wizardStep.set(7);
  }

  protected idxLabel(index: number, text: string): string {
    return `${index + 1}. ${text}`;
  }

  protected addProblem(): void {
    const value = this.newProblemText.trim();
    if (!value) return;
    this.problemsList.update((list) => [...list, value]);
    this.newProblemText = '';
  }

  protected removeProblem(index: number): void {
    this.problemsList.update((list) => list.filter((_, i) => i !== index));
  }

  protected addObservation(): void {
    const value = this.newObservationText.trim();
    if (!value) return;
    this.observationsList.update((list) => [...list, value]);
    this.newObservationText = '';
  }

  protected removeObservation(index: number): void {
    this.observationsList.update((list) => list.filter((_, i) => i !== index));
  }

  protected addQuickNote(): void {
    const value = this.newQuickNoteText.trim();
    if (!value) return;
    this.quickNotesList.update((list) => [...list, value]);
    this.newQuickNoteText = '';
  }

  protected removeQuickNote(index: number): void {
    this.quickNotesList.update((list) => list.filter((_, i) => i !== index));
  }

  protected formatBytes(bytes: number | undefined): string {
    if (!bytes || bytes <= 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return `${parseFloat((bytes / Math.pow(k, i)).toFixed(1))} ${sizes[i]}`;
  }

  protected openAddRecordModal(): void {
    this.newRecordType = 'AUTO';
    this.autoDetectedTypeInfo = null;
    this.newRecordFileName = '';
    this.newRecordNotes = '';
    this.newRecordToothFdi = null;
    this.selectedRecordFile = null;
    this.selectedRecordPreviewUrl = null;
    this.isDragOverRecordFile = false;
    this.isUploadingRecord = false;
    this.showAddRecordModal.set(true);
  }

  protected closeAddRecordModal(): void {
    this.showAddRecordModal.set(false);
    this.autoDetectedTypeInfo = null;
    this.selectedRecordFile = null;
    this.selectedRecordPreviewUrl = null;
    this.isDragOverRecordFile = false;
    this.isUploadingRecord = false;
  }

  protected onRecordFileChosen(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (input.files && input.files[0]) {
      this.handleRecordFile(input.files[0]);
    }
  }

  protected onRecordDragOver(event: DragEvent): void {
    event.preventDefault();
    this.isDragOverRecordFile = true;
  }

  protected onRecordFileDrop(event: DragEvent): void {
    event.preventDefault();
    this.isDragOverRecordFile = false;
    if (event.dataTransfer?.files && event.dataTransfer.files[0]) {
      this.handleRecordFile(event.dataTransfer.files[0]);
    }
  }

  protected async handleRecordFile(file: File): Promise<void> {
    this.selectedRecordFile = file;
    this.newRecordFileName = file.name;

    // 1. Initial quick auto-detection from filename and MIME
    const initialDetect = detectDentalFileType(file.name, file.type);
    this.newRecordType = initialDetect.type;
    this.autoDetectedTypeInfo = initialDetect;

    // 2. Binary / DICOM deep header detection
    if (isDicomFile(file.name, file.type)) {
      try {
        const buf = await file.arrayBuffer();
        const dicomRes = await parseAndRenderDicom(buf);
        this.selectedRecordPreviewUrl = dicomRes.dataUrl;

        // Refine detection with actual DICOM modality, study and series description
        const refined = detectDentalFileType(file.name, file.type, dicomRes);
        this.newRecordType = refined.type;
        this.autoDetectedTypeInfo = refined;
      } catch (err) {
        console.warn('Failed to parse DICOM file:', err);
        this.selectedRecordPreviewUrl = null;
      }
    } else if (file.type.startsWith('image/')) {
      const reader = new FileReader();
      reader.onload = (e) => {
        this.selectedRecordPreviewUrl = e.target?.result as string;
      };
      reader.readAsDataURL(file);
    } else {
      this.selectedRecordPreviewUrl = null;
    }
  }

  protected removeRecordFile(event: Event): void {
    event.stopPropagation();
    this.selectedRecordFile = null;
    this.selectedRecordPreviewUrl = null;
    this.newRecordFileName = '';
    this.autoDetectedTypeInfo = null;
    this.newRecordType = 'AUTO';
  }

  private mapCategoryToType(cat: string, fileName?: string): string {
    if (fileName && is3DFile(fileName)) {
      return '3D Dental Scan (STL)';
    }
    switch (cat) {
      case 'RADIOGRAPH_PANORAMIC':
        return 'OPG';
      case 'RADIOGRAPH_INTRAORAL':
        return 'Periapical';
      case 'RADIOGRAPH_CEPHALOMETRIC':
        return 'Lateral Cephalogram';
      default:
        return 'Radiograph';
    }
  }

  private mapTypeToCategory(type: string): string {
    switch (type) {
      case '3D Dental Scan (STL)':
        return 'PHOTO_INTRAORAL';
      case 'OPG':
      case 'CBCT':
        return 'RADIOGRAPH_PANORAMIC';
      case 'Bitewings':
      case 'Periapical':
        return 'RADIOGRAPH_INTRAORAL';
      case 'Lateral Cephalogram':
        return 'RADIOGRAPH_CEPHALOMETRIC';
      default:
        return 'RADIOGRAPH_INTRAORAL';
    }
  }

  protected async saveRadiographRecord(): Promise<void> {
    const file = this.selectedRecordFile;
    const effectiveType =
      this.newRecordType === 'AUTO' || !this.newRecordType
        ? (this.autoDetectedTypeInfo?.type || 'OPG')
        : this.newRecordType;
    const fileName =
      file?.name ||
      this.newRecordFileName.trim() ||
      `${effectiveType.toLowerCase().replace(/[^a-z0-9]/g, '-')}-scan.png`;
    const toothFdi = this.newRecordToothFdi ?? undefined;
    const notes = this.newRecordNotes.trim();
    const dateStr = new Date().toISOString().slice(0, 10);
    const previewUrl = this.selectedRecordPreviewUrl;
    const fileSizeStr = file ? this.formatBytes(file.size) : undefined;

    this.isUploadingRecord = true;
    let backendMediaId: string | null = null;
    let backendDownloadUrl: string | null = null;

    try {
      const pId = this.patientId() || this.activeDetail()?.encounter.patientId;
      if (pId && file) {
        const category = this.mapTypeToCategory(effectiveType);
        const uploaded = await this.api.uploadMedia(
          pId,
          {
            file,
            category,
            toothFdi,
            notes,
            acquisitionDate: new Date().toISOString(),
          },
          { projectId: this.projectId() }
        );
        if (uploaded?.id) {
          backendMediaId = uploaded.id;
          try {
            const signed = await this.api.mediaDownloadUrl(pId, uploaded.id, { projectId: this.projectId() });
            backendDownloadUrl = signed.downloadUrl;
          } catch {
            // ignore signed URL error
          }
        }
      }
    } catch {
      // offline / mock fallback
    } finally {
      this.isUploadingRecord = false;
    }

    const newRec: EncounterRadiographRow = {
      id: backendMediaId || `rad-${Date.now()}`,
      date: dateStr,
      radiographType: effectiveType,
      fileName,
      fileSize: fileSizeStr,
      thumbnailUrl: previewUrl || backendDownloadUrl || undefined,
      fileUrl: previewUrl || backendDownloadUrl || undefined,
      toothFdi: toothFdi ?? null,
      notes,
      rawFile: file || undefined,
    };

    this.radiographRecords.update((list) => [newRec, ...list]);
    this.closeAddRecordModal();
  }

  protected async viewRadiograph(rec: EncounterRadiographRow): Promise<void> {
    this.radiographZoom = 1;
    this.radiographInverted = false;
    this.measureTool = 'NONE';
    this.clearViewerMeasurements();
    const upperType = (rec.radiographType || '').toUpperCase();
    this.viewerMode = is3DFile(rec.fileName)
      ? '3D'
      : upperType.includes('CBCT') || upperType.includes('3D')
        ? 'CBCT_MPR'
        : '2D';
    this.viewingRadiograph.set(rec);

    // If it's an Interactive 3D dental model (.stl, .obj, .ply)
    if (is3DFile(rec.fileName)) {
      this.is3DLoading = true;
      this.is3DWireframe = false;
      this.is3DAutoRotate = false;
      this.current3DColor = 'ceramic';

      setTimeout(async () => {
        try {
          const container = document.getElementById('dental3dContainer');
          const canvas = document.getElementById('dental3dCanvas') as HTMLCanvasElement | null;
          if (!container || !canvas) {
            console.warn('3D canvas/container element not found');
            return;
          }

          if (this.active3DScene) {
            this.active3DScene.dispose();
            this.active3DScene = null;
          }

          this.active3DScene = new Dental3DScene({
            canvas,
            container,
            initialWireframe: this.is3DWireframe,
            initialAutoRotate: this.is3DAutoRotate,
          });

          let buf: ArrayBuffer | null = null;
          if (rec.rawFile) {
            buf = await rec.rawFile.arrayBuffer();
          } else {
            let url = rec.fileUrl;
            if (!url && rec.id && !rec.id.startsWith('rad-')) {
              const pId = this.patientId() || this.activeDetail()?.encounter.patientId;
              if (pId) {
                const signed = await this.api.mediaDownloadUrl(pId, rec.id, { projectId: this.projectId() });
                url = signed.downloadUrl;
              }
            }
            if (url) {
              const resp = await fetch(url);
              buf = await resp.arrayBuffer();
            }
          }

          if (buf) {
            await this.active3DScene.loadModel(buf, rec.fileName);
          }
        } catch (err) {
          console.error('Failed to load 3D dental model:', err);
        } finally {
          this.is3DLoading = false;
        }
      }, 50);
      return;
    }

    const isDcm = isDicomFile(rec.fileName);

    // If it's a DICOM file and we need to decode its binary to a visible dataUrl
    if (isDcm && (!rec.fileUrl || !rec.fileUrl.startsWith('data:image/'))) {
      this.isDicomDecoding = true;
      try {
        let buf: ArrayBuffer | null = null;
        if (rec.rawFile) {
          buf = await rec.rawFile.arrayBuffer();
        } else {
          let url = rec.fileUrl || rec.thumbnailUrl;
          if (!url && rec.id && !rec.id.startsWith('rad-')) {
            const pId = this.patientId() || this.activeDetail()?.encounter.patientId;
            if (pId) {
              const signed = await this.api.mediaDownloadUrl(pId, rec.id, { projectId: this.projectId() });
              url = signed.downloadUrl;
            }
          }
          if (url) {
            const resp = await fetch(url);
            buf = await resp.arrayBuffer();
          }
        }
        if (buf) {
          const dicomRes = await parseAndRenderDicom(buf);
          rec.fileUrl = dicomRes.dataUrl;
          rec.thumbnailUrl = dicomRes.dataUrl;
          this.viewingRadiograph.set({ ...rec });
        }
      } catch (err) {
        console.warn('DICOM decode failed:', err);
      } finally {
        this.isDicomDecoding = false;
      }
      return;
    }

    if (!rec.fileUrl && !rec.thumbnailUrl && rec.id && !rec.id.startsWith('rad-')) {
      const pId = this.patientId() || this.activeDetail()?.encounter.patientId;
      if (pId) {
        try {
          const signed = await this.api.mediaDownloadUrl(pId, rec.id, { projectId: this.projectId() });
          rec.fileUrl = signed.downloadUrl;
          rec.thumbnailUrl = signed.downloadUrl;
          this.viewingRadiograph.set({ ...rec });
        } catch {
          // ignore
        }
      }
    }
  }

  protected set3DPerspective(preset: PerspectivePreset): void {
    this.active3DScene?.setPerspective(preset);
  }

  protected toggle3DWireframe(): void {
    if (this.active3DScene) {
      this.is3DWireframe = this.active3DScene.toggleWireframe();
    }
  }

  protected toggle3DAutoRotate(): void {
    if (this.active3DScene) {
      this.is3DAutoRotate = this.active3DScene.toggleAutoRotate();
    }
  }

  protected set3DColor(mode: 'ceramic' | 'blue' | 'gold'): void {
    this.current3DColor = mode;
    if (this.active3DScene) {
      const colors = {
        ceramic: 0xf1f5f9,
        blue: 0x38bdf8,
        gold: 0xf59e0b,
      };
      this.active3DScene.setMaterialColor(colors[mode] || 0xf1f5f9);
    }
  }

  protected zoom3D(delta: number): void {
    this.active3DScene?.zoom(delta);
  }

  protected closeRadiographViewer(): void {
    if (this.active3DScene) {
      this.active3DScene.dispose();
      this.active3DScene = null;
    }
    this.viewingRadiograph.set(null);
  }

  protected zoomInRadiograph(): void {
    this.radiographZoom = Math.min(3, +(this.radiographZoom + 0.25).toFixed(2));
  }

  protected zoomOutRadiograph(): void {
    this.radiographZoom = Math.max(0.5, +(this.radiographZoom - 0.25).toFixed(2));
  }

  protected resetRadiographZoom(): void {
    this.radiographZoom = 1;
  }

  protected toggleRadiographInvert(): void {
    this.radiographInverted = !this.radiographInverted;
  }

  protected setViewerMode(mode: '2D' | 'CBCT_MPR' | '3D'): void {
    this.viewerMode = mode;
  }

  protected toggleMeasureTool(tool: 'DISTANCE' | 'ANGLE' | 'NERVE_CANAL'): void {
    this.measureTool = this.measureTool === tool ? 'NONE' : tool;
    this.distanceStart = null;
    this.anglePoints = [];
  }

  protected clearViewerMeasurements(): void {
    this.distanceLines = [];
    this.distanceStart = null;
    this.angleShapes = [];
    this.anglePoints = [];
    this.nervePoints = [];
  }

  protected nervePolylineAttr(): string {
    return this.nervePoints.map((p) => `${p.x},${p.y}`).join(' ');
  }

  protected onViewportClick(event: MouseEvent): void {
    if (this.measureTool === 'NONE') return;
    const host = event.currentTarget as HTMLElement | null;
    if (!host) return;
    const rect = host.getBoundingClientRect();
    if (rect.width <= 0 || rect.height <= 0) return;
    const x = Math.round(((event.clientX - rect.left) / rect.width) * 1000);
    const y = Math.round(((event.clientY - rect.top) / rect.height) * 700);

    if (this.measureTool === 'DISTANCE') {
      if (!this.distanceStart) {
        this.distanceStart = { x, y };
      } else {
        const dx = (x - this.distanceStart.x) * 0.085;
        const dy = (y - this.distanceStart.y) * 0.085;
        const mm = Math.hypot(dx, dy).toFixed(1);
        this.distanceLines = [
          ...this.distanceLines,
          { x1: this.distanceStart.x, y1: this.distanceStart.y, x2: x, y2: y, mm },
        ];
        this.distanceStart = null;
      }
      return;
    }

    if (this.measureTool === 'ANGLE') {
      const next = [...this.anglePoints, { x, y }];
      if (next.length < 3) {
        this.anglePoints = next;
      } else {
        const [a, b, c] = next;
        const v1x = a.x - b.x;
        const v1y = a.y - b.y;
        const v2x = c.x - b.x;
        const v2y = c.y - b.y;
        const mag1 = Math.hypot(v1x, v1y);
        const mag2 = Math.hypot(v2x, v2y);
        const cos = mag1 > 0 && mag2 > 0 ? Math.max(-1, Math.min(1, (v1x * v2x + v1y * v2y) / (mag1 * mag2))) : 1;
        const deg = ((Math.acos(cos) * 180) / Math.PI).toFixed(1);
        this.angleShapes = [
          ...this.angleShapes,
          { pointsAttr: `${a.x},${a.y} ${b.x},${b.y} ${c.x},${c.y}`, bx: b.x, by: b.y, deg },
        ];
        this.anglePoints = [];
      }
      return;
    }

    if (this.measureTool === 'NERVE_CANAL') {
      this.nervePoints = [...this.nervePoints, { x, y }];
    }
  }

  private async loadImagingLabs(): Promise<void> {
    try {
      const labs = await this.api.imagingLabs({ projectId: this.projectId() });
      this.imagingLabsList.set(labs ?? []);
    } catch {
      this.imagingLabsList.set([]);
    }
  }

  private async loadPatientImagingRequests(pId: string): Promise<void> {
    try {
      const list = await this.api.imagingRequests({ patientId: pId, projectId: this.projectId() });
      this.patientImagingRequests.set(list ?? []);
    } catch {
      this.patientImagingRequests.set([]);
    }
  }

  protected openOrderImagingModal(): void {
    this.orderImgType = 'CBCT';
    this.orderImgCount = 1;
    const defaultLab = this.imagingLabsList()[0];
    this.orderImgLabId = defaultLab?.id ?? '';
    this.orderImgLabName = defaultLab?.name ?? '';
    this.orderImgCost = 0;
    this.orderImgCharge = 0;
    this.orderImgTeeth = '';
    this.orderImgIndication = '';
    this.orderImgCloudUrl = '';
    if (this.imagingLabsList().length === 0) void this.loadImagingLabs();
    this.showOrderImagingModal.set(true);
  }

  protected onOrderImgLabSelected(labId: string): void {
    const match = this.imagingLabsList().find((l) => l.id === labId);
    if (match) this.orderImgLabName = match.name;
  }

  protected async submitOrderImaging(): Promise<void> {
    const pId = this.patientId() || this.activeDetail()?.encounter.patientId;
    if (!pId) return;
    const payload: CreateImagingRequestPayload = {
      patientId: pId,
      doctorId: this.activeDetail()?.encounter.doctorId || this.doctorsList()[0]?.id || null,
      labId: this.orderImgLabId || null,
      labName: this.orderImgLabName.trim() || null,
      imagingType: this.orderImgType,
      proceduresCount: Math.max(1, Number(this.orderImgCount) || 1),
      teethSummary: this.orderImgTeeth.trim() || null,
      clinicalIndication: this.orderImgIndication.trim() || null,
      costToClinic: Number(this.orderImgCost) || 0,
      amountChargedToPatient: Number(this.orderImgCharge) || 0,
      cloudStorageUrl: this.orderImgCloudUrl.trim() || null,
      status: 'REQUESTED',
    };
    try {
      await this.api.createImagingRequest(payload, { projectId: this.projectId() });
      this.showOrderImagingModal.set(false);
      await this.loadPatientImagingRequests(pId);
    } catch {
      this.showOrderImagingModal.set(false);
    }
  }

  protected openCloudImagingInViewer(req: ImagingRequestRow): void {
    const rec: EncounterRadiographRow = {
      id: `img-${req.id}`,
      date: req.requestDate,
      radiographType: req.imagingType,
      fileName: `${req.imagingType}_${req.requestDate}.dcm`,
      fileUrl: req.cloudStorageUrl ?? undefined,
      thumbnailUrl: req.cloudStorageUrl ?? undefined,
      toothFdi: req.toothFdi ?? null,
      notes: req.findingsNotes || req.clinicalIndication || '',
    };
    void this.viewRadiograph(rec);
  }

  protected async downloadRecordFile(rec: EncounterRadiographRow): Promise<void> {
    let url = rec.fileUrl || rec.thumbnailUrl;
    if (!url && rec.id && !rec.id.startsWith('rad-')) {
      const pId = this.patientId() || this.activeDetail()?.encounter.patientId;
      if (pId) {
        try {
          const signed = await this.api.mediaDownloadUrl(pId, rec.id, { projectId: this.projectId() });
          url = signed.downloadUrl;
        } catch {
          // ignore
        }
      }
    }
    if (url) {
      const a = document.createElement('a');
      a.href = url;
      a.download = rec.fileName || 'radiograph.png';
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
    }
  }

  protected deleteRadiographRecord(id: string): void {
    this.radiographRecords.update((list) => list.filter((r) => r.id !== id));
  }

  private async loadPatientRadiographs(pId: string): Promise<void> {
    try {
      const media = await this.api.patientMedia(pId, { projectId: this.projectId() });
      if (media && media.length > 0) {
        const rows: EncounterRadiographRow[] = media.map((m) => ({
          id: m.id,
          date: m.acquisitionDate ? m.acquisitionDate.slice(0, 10) : new Date().toISOString().slice(0, 10),
          radiographType: this.mapCategoryToType(m.category, m.fileName),
          fileName: m.fileName,
          fileSize: this.formatBytes(m.byteSize),
          toothFdi: m.toothFdi ?? null,
          notes: m.notes ?? '',
        }));
        this.radiographRecords.set(rows);
      }
    } catch {
      // ignore
    }
  }

  protected saveFollowUpNote(): void {
    if (!this.newFollowUpTitle.trim() && !this.newFollowUpNote.trim()) return;
    this.followUpNotes.update((list) => [
      ...list,
      {
        id: `fu-${Date.now()}`,
        date: new Date().toISOString().slice(0, 10),
        title: this.newFollowUpTitle.trim() || 'Follow Up',
        note: this.newFollowUpNote.trim(),
      },
    ]);
    this.newFollowUpTitle = '';
    this.newFollowUpNote = '';
    this.showAddFollowUpModal.set(false);
  }

  protected deleteFollowUpNote(id: string): void {
    this.followUpNotes.update((list) => list.filter((f) => f.id !== id));
  }

  // Generate Invoice & Check Out Modal Operations (Screenshot 11)
  protected openGenerateInvoiceModal(): void {
    this.showGenerateInvoiceModal.set(true);
  }

  protected addBillingItemRow(): void {
    const name = this.newBillingService.trim();
    const price = Number(this.newBillingPrice || 0);
    const qty = Math.max(1, Number(this.newBillingQty || 1));
    if (!name || price <= 0) return;
    this.billingItems.update((list) => [
      ...list,
      {
        id: `bi-${Date.now()}`,
        serviceName: name,
        discount: 0,
        quantity: qty,
        price,
        totalAmount: price * qty,
      },
    ]);
  }

  protected removeBillingItemRow(id: string): void {
    this.billingItems.update((list) => list.filter((item) => item.id !== id));
  }

  protected addInstallmentRow(): void {
    const amount = Number(this.newInstallmentAmount || 0);
    if (amount <= 0) return;
    this.installmentRows.update((list) => [
      ...list,
      {
        id: `inst-${Date.now()}`,
        amount,
        paymentMode: this.newInstallmentMode,
        date: this.newInstallmentDate || new Date().toISOString().slice(0, 10),
      },
    ]);
  }

  protected async submitEncounterInvoice(): Promise<void> {
    this.encounterClosed.set(true);
    const pId = this.patientId();
    if (pId && this.billingItems().length > 0) {
      try {
        await this.api.createInvoice({
          patientId: pId,
          encounterId: this.selectedEncounterId() ?? undefined,
          currency: 'JOD',
          notes: this.otherDetailsText || 'Generated at Encounter Check Out',
          items: this.billingItems().map((row) => ({
            description: row.serviceName,
            itemType: 'PROCEDURE',
            unitPrice: row.price,
            quantity: row.quantity,
          })),
        });
      } catch {
        // Proceed to Billing Record view even if offline/demo
      }
    }
    this.showGenerateInvoiceModal.set(false);
    void this.router.navigate(['/app/clinic/invoices'], {
      queryParams: {
        patientId: pId ?? 'pat-1',
        patientName: this.currentPatientName(),
        doctorName: this.currentDoctorName(),
        service: this.billingItems()[0]?.serviceName ?? this.queryService(),
        price: this.billingPayableAmount(),
      },
    });
  }

  private async loadDoctors(): Promise<void> {
    try {
      const page = await this.clinicApi.doctors();
      const items = page.items ?? [];
      this.doctorsList.set(items.map((d: any) => ({ id: d.id, name: d.name, specialty: d.specialty })));
    } catch {
      // Keep empty if unprivileged
    }
  }

  private async loadPatientPlans(pId: string): Promise<void> {
    try {
      const plans = await this.api.treatmentPlans(pId);
      this.patientPlans.set(plans);
    } catch {
      // Keep empty
    }
  }

  protected async reloadEncounters(): Promise<void> {
    const pId = this.patientId();
    if (!pId) return;

    this.encountersStore.load(async () => {
      try {
        const list = await this.api.listEncounters(pId);
        if (list.length > 0) {
          const targetId = this.selectedEncounterId() ?? list[0].id;
          void this.selectEncounter(targetId);
        } else {
          this.selectedEncounterId.set(null);
          this.activeDetail.set(null);
        }
        return list;
      } catch {
        return [];
      }
    });
  }

  protected async loadDirectEncounter(encounterId: string): Promise<void> {
    this.encountersStore.load(async () => {
      try {
        const detail = await this.api.getEncounterById(encounterId);
        this.patientId.set(detail.encounter.patientId);
        this.selectedEncounterId.set(detail.encounter.id);
        this.activeDetail.set(detail);
        if (detail.encounter.patientId) {
          void this.loadPatientPlans(detail.encounter.patientId);
          void this.loadPatientRadiographs(detail.encounter.patientId);
          void this.loadPatientContextAndHistory(detail.encounter.patientId);
        }
        return [detail.encounter];
      } catch {
        // When opened from an appointment row that has no backend encounter row yet,
        // synthesize a live clinical encounter session so the screen works seamlessly.
        const fallbackEncounter: ClinicEncounterHeader = {
          id: encounterId,
          organizationId: 'org-1',
          projectId: this.projectId() ?? 'proj-1',
          patientId: this.patientId() ?? 'pat-1',
          patientName: this.currentPatientName(),
          patientNo: 'PAT-001',
          doctorId: 'doc-1',
          doctorName: this.currentDoctorName(),
          chiefComplaint: this.queryService() || 'Routine Dental Assessment',
          diagnosis: 'Clinical Evaluation in Progress',
          examinationNotes: 'Extraoral & intraoral examination normal.',
          performedProcedures: this.queryService() || 'Scaling & Polishing',
          status: 'IN_PROGRESS',
          visitDate: new Date().toISOString(),
          durationMinutes: 30,
          prescriptionCount: 0,
          isMedicalDataMasked: false,
          createdAt: new Date().toISOString(),
          updatedAt: new Date().toISOString(),
        };
        this.selectedEncounterId.set(encounterId);
        this.activeDetail.set({
          encounter: fallbackEncounter,
          prescriptions: [],
          amendments: [],
        });
        return [fallbackEncounter];
      }
    });
  }

  protected async selectEncounter(encounterId: string): Promise<void> {
    this.selectedEncounterId.set(encounterId);
    const pId = this.patientId();
    if (!pId) return;

    try {
      const detail = await this.api.getEncounterDetail(pId, encounterId);
      this.activeDetail.set(detail);
    } catch {
      // Handle error gracefully
    }
  }

  protected async onPlanSelect(planId: string | null): Promise<void> {
    const pId = this.patientId();
    if (!pId || !planId) {
      this.selectedPlanItems.set([]);
      this.newTreatmentPlanItemId = null;
      return;
    }

    try {
      const detail = await this.api.treatmentPlan(pId, planId);
      this.selectedPlanItems.set(detail.items.filter((it: ClinicTreatmentPlanItem) => it.status !== 'COMPLETED'));
    } catch {
      this.selectedPlanItems.set([]);
    }
  }

  protected openCreateModal(): void {
    this.newDoctorId = this.doctorsList()[0]?.id ?? null;
    this.newChiefComplaint = '';
    this.newDiagnosis = '';
    this.newExaminationNotes = '';
    this.newPerformedProcedures = '';
    this.newToothFdi = null;
    this.newToothLocation = '';
    this.newClinicalNotes = '';
    this.newStatus = 'COMPLETED';
    this.newDuration = 30;
    this.newTreatmentPlanId = null;
    this.newTreatmentPlanItemId = null;
    this.newCompleteLinkedStep = true;
    this.quickRxName = '';
    this.quickRxDosage = '';
    this.quickRxFrequency = '';
    this.quickRxDuration = '';
    this.newBpSystolic = null;
    this.newBpDiastolic = null;
    this.newPulse = null;
    this.newTemp = null;
    this.showCreateModal.set(true);
  }

  protected closeCreateModal(): void {
    this.showCreateModal.set(false);
  }

  protected async submitCreateEncounter(): Promise<void> {
    const pId = this.patientId();
    if (!pId || !this.newDoctorId || !this.newChiefComplaint.trim() || !this.newDiagnosis.trim()) {
      return;
    }

    const rxList: CreatePrescriptionPayload[] = [];
    if (this.quickRxName.trim()) {
      rxList.push({
        medicationName: this.quickRxName.trim(),
        dosage: this.quickRxDosage.trim() || '-',
        frequency: this.quickRxFrequency.trim() || '-',
        duration: this.quickRxDuration.trim() || '-',
        doctorId: this.newDoctorId,
      });
    }

    const payload: CreateEncounterPayload = {
      doctorId: this.newDoctorId,
      chiefComplaint: this.newChiefComplaint.trim(),
      diagnosis: this.newDiagnosis.trim(),
      examinationNotes: this.newExaminationNotes.trim() || undefined,
      performedProcedures: this.newPerformedProcedures.trim() || undefined,
      toothFdi: this.newToothFdi || undefined,
      toothLocation: this.newToothLocation.trim() || undefined,
      clinicalNotes: this.newClinicalNotes.trim() || undefined,
      status: this.newStatus,
      durationMinutes: this.newDuration,
      treatmentPlanId: this.newTreatmentPlanId || undefined,
      treatmentPlanItemId: this.newTreatmentPlanItemId || undefined,
      completeLinkedPlanItem: this.newCompleteLinkedStep,
      bloodPressureSystolic: this.newBpSystolic ?? undefined,
      bloodPressureDiastolic: this.newBpDiastolic ?? undefined,
      pulseRate: this.newPulse ?? undefined,
      temperatureC: this.newTemp ?? undefined,
      prescriptions: rxList.length > 0 ? rxList : undefined,
    };

    try {
      const res = await this.api.createEncounter(pId, payload);
      this.closeCreateModal();
      if (res.inventoryWarnings && res.inventoryWarnings.length > 0) {
        this.inventoryWarningsModal.set(res.inventoryWarnings);
      }
      this.selectedEncounterId.set(res.id);
      await this.reloadEncounters();
      await this.selectEncounter(res.id);
    } catch {
      this.closeCreateModal();
    }
  }

  protected openEditDiagnosisModal(encounter: ClinicEncounterHeader): void {
    this.editDiagnosis = encounter.diagnosis;
    this.editExamNotes = encounter.examinationNotes || '';
    this.editPerformedProcedures = encounter.performedProcedures || '';
    this.editClinicalNotes = encounter.clinicalNotes || '';
    this.editBpSystolic = encounter.bloodPressureSystolic ?? null;
    this.editBpDiastolic = encounter.bloodPressureDiastolic ?? null;
    this.editPulse = encounter.pulseRate ?? null;
    this.editTemp = encounter.temperatureC ?? null;
    this.showEditDiagnosisModal.set(true);
  }

  protected closeEditDiagnosisModal(): void {
    this.showEditDiagnosisModal.set(false);
  }

  protected async submitUpdateDiagnosis(): Promise<void> {
    const pId = this.patientId();
    const encId = this.selectedEncounterId();
    if (!pId || !encId || !this.editDiagnosis.trim()) return;

    const payload: UpdateDiagnosisPayload = {
      diagnosis: this.editDiagnosis.trim(),
      examinationNotes: this.editExamNotes.trim() || undefined,
      performedProcedures: this.editPerformedProcedures.trim() || undefined,
      clinicalNotes: this.editClinicalNotes.trim() || undefined,
      bloodPressureSystolic: this.editBpSystolic ?? undefined,
      bloodPressureDiastolic: this.editBpDiastolic ?? undefined,
      pulseRate: this.editPulse ?? undefined,
      temperatureC: this.editTemp ?? undefined,
    };

    try {
      await this.api.updateDiagnosis(pId, encId, payload);
      this.closeEditDiagnosisModal();
      await this.reloadEncounters();
      await this.selectEncounter(encId);
    } catch {
      this.closeEditDiagnosisModal();
    }
  }

  protected async signActiveEncounter(): Promise<void> {
    const encId = this.selectedEncounterId();
    if (!encId) return;
    try {
      await this.api.signEncounter(encId);
      await this.reloadEncounters();
      await this.selectEncounter(encId);
    } catch {
      // Ignore if offline
    }
  }

  protected openAddAmendmentModal(): void {
    this.amendmentReason = '';
    this.amendmentContent = '';
    this.showAddAmendmentModal.set(true);
  }

  protected closeAddAmendmentModal(): void {
    this.showAddAmendmentModal.set(false);
  }

  protected async submitAddAmendment(): Promise<void> {
    const encId = this.selectedEncounterId();
    if (!encId || !this.amendmentReason.trim() || !this.amendmentContent.trim()) return;

    try {
      await this.api.addAmendment(encId, {
        reason: this.amendmentReason.trim(),
        amendmentContent: this.amendmentContent.trim(),
      });
      this.closeAddAmendmentModal();
      await this.selectEncounter(encId);
    } catch {
      this.closeAddAmendmentModal();
    }
  }

  protected openAddRxModal(): void {
    this.addRxName = '';
    this.addRxDosage = '';
    this.addRxFrequency = '';
    this.addRxDuration = '';
    this.addRxInstructions = '';
    this.showAddRxModal.set(true);
  }

  protected closeAddRxModal(): void {
    this.showAddRxModal.set(false);
  }

  protected async submitAddPrescription(): Promise<void> {
    const pId = this.patientId();
    const encId = this.selectedEncounterId();
    if (!pId || !encId || !this.addRxName.trim()) return;

    const payload: CreatePrescriptionPayload = {
      medicationName: this.addRxName.trim(),
      dosage: this.addRxDosage.trim() || '-',
      frequency: this.addRxFrequency.trim() || '-',
      duration: this.addRxDuration.trim() || '-',
      instructions: this.addRxInstructions.trim() || undefined,
    };

    try {
      await this.api.addPrescription(pId, encId, payload);
      this.closeAddRxModal();
      await this.selectEncounter(encId);
    } catch {
      this.closeAddRxModal();
    }
  }

  protected async removePrescription(rxId: string): Promise<void> {
    const pId = this.patientId();
    const encId = this.selectedEncounterId();
    if (!pId || !encId) return;
    try {
      await this.api.deletePrescription(pId, encId, rxId);
      await this.selectEncounter(encId);
    } catch {
      // Ignore
    }
  }

  protected openPrintPrescriptionModal(detail: ClinicEncounterDetail): void {
    this.printTargetDetail.set(detail);
    this.showPrintModal.set(true);
  }

  protected closePrintModal(): void {
    this.showPrintModal.set(false);
    this.printTargetDetail.set(null);
  }

  protected triggerBrowserPrint(): void {
    window.print();
  }

  protected statusTone(status: EncounterStatus): StatusTone {
    switch (status) {
      case 'COMPLETED':
        return 'ok';
      case 'IN_PROGRESS':
        return 'human';
      case 'CANCELLED':
        return 'neutral';
    }
  }

  protected statusLabel(status: EncounterStatus): string {
    switch (status) {
      case 'COMPLETED':
        return 'مكتملة';
      case 'IN_PROGRESS':
        return 'جارية';
      case 'CANCELLED':
        return 'ملغاة';
    }
  }

  protected formatDoctorHeading(name: string | null | undefined): string {
    if (!name || !name.trim()) return '—';
    const trimmed = name.trim();
    if (trimmed.startsWith('د.') || trimmed.startsWith('د/') || /^dr\.?\s+/i.test(trimmed)) {
      return trimmed;
    }
    return `د. ${trimmed}`;
  }

  protected planStatusLabel(status: string): string {
    switch (status) {
      case 'DRAFT':
        return 'مسودة';
      case 'ACTIVE':
        return 'معتمدة / جارية';
      case 'COMPLETED':
        return 'مكتملة';
      case 'CANCELLED':
        return 'ملغاة';
      default:
        return status;
    }
  }

  protected toothFdiLabel(fdi: number): string {
    const quadrant = Math.floor(fdi / 10);
    const position = fdi % 10;
    const names = ['', 'ثنية مركزية', 'رباعية', 'ناب', 'ضاحك أول', 'ضاحك ثان', 'طاحن أول', 'طاحن ثان', 'طاحن ثالث'];
    const toothName = names[position] || `سن ${position}`;

    switch (quadrant) {
      case 1:
        return `${toothName} - علوي أيمن (دائم)`;
      case 2:
        return `${toothName} - علوي أيسر (دائم)`;
      case 3:
        return `${toothName} - سفلي أيسر (دائم)`;
      case 4:
        return `${toothName} - سفلي أيمن (دائم)`;
      case 5:
        return `${toothName} - علوي أيمن (لبني)`;
      case 6:
        return `${toothName} - علوي أيسر (لبني)`;
      case 7:
        return `${toothName} - سفلي أيسر (لبني)`;
      case 8:
        return `${toothName} - سفلي أيمن (لبني)`;
      default:
        return `سن FDI #${fdi}`;
    }
  }
}
