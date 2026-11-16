import { CommonModule } from '@angular/common';
import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ClinicApi, type ClinicDoctor } from '../../core/api/endpoints';
import { ProjectContextService } from '../../core/context/project-context.service';
import {
  ClinicPatientApi,
  type ClinicFeedback,
  type ClinicPatient,
} from './clinic-patient.api';

export interface PatientFeedbackRecord {
  readonly id: string;
  readonly patientId?: string | null;
  readonly doctorId?: string | null;
  readonly patientName: string;
  readonly patientEmail: string;
  readonly patientPhone: string;
  readonly patientAge: number;
  readonly doctorName: string;
  readonly serviceName: string;
  readonly treatmentsReceived: string;
  readonly clinicLocation: string;
  readonly referralSource: string;
  readonly ratingExplanation: number;
  readonly ratingHygiene: number;
  readonly ratingCourtesy: number;
  readonly ratingComfort: number;
  readonly ratingRecommend: number;
  readonly message: string;
  readonly dateTime: string;
}

const TEN_SCALE: readonly number[] = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10];

@Component({
  selector: 'fe-clinic-feedback',
  standalone: true,
  imports: [CommonModule, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="tl-page" data-testid="clinic-feedback">
      <!-- Breadcrumbs -->
      <div class="tl-breadcrumbs" style="display: flex; align-items: center; gap: 8px;">
        <a routerLink="/app/dashboard">Home</a>
        <span>»</span>
        <span class="active">Patient Feedback</span>
        <div class="tl-info-wrap">
          <button
            type="button"
            class="tl-info-trigger"
            [class.is-active]="showFeedbackInfo()"
            (click)="showFeedbackInfo.set(!showFeedbackInfo())"
            title="Feedback info"
          >?</button>
          @if (showFeedbackInfo()) {
            <div class="tl-info-popover">
              Review and manage patient ratings, clinical feedback, and service satisfaction scores.
            </div>
          }
        </div>
      </div>

      <div class="tl-card">
        <!-- Toolbar matching 17_patient_feedback_list_292s.png -->
        <div class="tl-toolbar">
          <div class="tl-toolbar-left">
            <select
              class="tl-select"
              [value]="bulkAction()"
              (change)="bulkAction.set($any($event.target).value)"
              aria-label="Bulk Action"
            >
              <option value="">No Action</option>
              <option value="DELETE">Delete Selected</option>
            </select>
            <button type="button" class="tl-btn tl-btn-teal" (click)="applyBulkAction()">
              Apply
            </button>
          </div>

          <div class="tl-toolbar-right">
            <input
              type="search"
              class="tl-input"
              placeholder="Search patient, doctor, service..."
              [value]="searchQuery()"
              (input)="searchQuery.set($any($event.target).value)"
            />
            <button
              type="button"
              class="tl-btn tl-btn-outline"
              (click)="exportFeedbackCsv()"
              [disabled]="filteredFeedback().length === 0"
            >
              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
                <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path>
                <polyline points="7 10 12 15 17 10"></polyline>
                <line x1="12" y1="15" x2="12" y2="3"></line>
              </svg>
              <span>Export CSV</span>
            </button>
            <button
              type="button"
              class="tl-btn tl-btn-teal"
              data-testid="open-add-feedback-btn"
              (click)="openAddDrawer()"
            >
              + Add Patient Feedback
            </button>
          </div>
        </div>

        <!-- Patient Feedback Table -->
        <div class="tl-table-wrap">
          <table class="tl-table">
            <thead>
              <tr>
                <th style="width: 38px;">
                  <input
                    type="checkbox"
                    [checked]="allSelected()"
                    (change)="toggleSelectAll($any($event.target).checked)"
                  />
                </th>
                <th>Patient</th>
                <th>Doctor</th>
                <th>Service</th>
                <th>Message</th>
                <th>Patient Feedback</th>
                <th>Date Time</th>
                <th>Action</th>
              </tr>
            </thead>
            <tbody>
              @if (filteredFeedback().length === 0) {
                <tr>
                  <td colspan="8" class="tl-empty-cell">No data found</td>
                </tr>
              } @else {
                @for (row of filteredFeedback(); track row.id) {
                  <tr>
                    <td>
                      <input
                        type="checkbox"
                        [checked]="selectedIds().has(row.id)"
                        (change)="toggleRowSelection(row.id, $any($event.target).checked)"
                      />
                    </td>
                    <td>
                      <div class="tl-patient-cell">
                        <strong>{{ row.patientName }}</strong>
                        <span>{{ row.patientEmail }}</span>
                      </div>
                    </td>
                    <td>{{ row.doctorName }}</td>
                    <td>{{ row.serviceName }}</td>
                    <td class="tl-msg-cell">{{ row.message }}</td>
                    <td>
                      <span class="tl-score-badge">
                        {{ averageScore(row) }} / 10
                      </span>
                    </td>
                    <td>{{ row.dateTime }}</td>
                    <td>
                      <div class="tl-row-actions">
                        <button
                          type="button"
                          class="tl-icon-btn is-teal"
                          title="View Survey Details"
                          (click)="viewFeedback(row)"
                        >
                          <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true" focusable="false">
                            <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"></path>
                            <circle cx="12" cy="12" r="3"></circle>
                          </svg>
                        </button>
                        <button
                          type="button"
                          class="tl-icon-btn is-danger"
                          title="Delete Feedback"
                          (click)="deleteFeedback(row.id)"
                        >
                          <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true" focusable="false">
                            <polyline points="3 6 5 6 21 6"></polyline>
                            <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"></path>
                          </svg>
                        </button>
                      </div>
                    </td>
                  </tr>
                }
              }
            </tbody>
          </table>
        </div>
      </div>

      <!-- Add Patient Feedback Right-Side Drawer (Screenshot 18: 18_patient_feedback_create_300s.png) -->
      @if (drawerOpen()) {
        <div class="tl-drawer-backdrop" (click)="closeDrawer()">
          <aside
            class="tl-drawer"
            data-testid="add-patient-feedback-drawer"
            (click)="$event.stopPropagation()"
          >
            <header class="tl-drawer-header">
              <h2>{{ viewingFeedback() ? 'Patient Feedback Details' : 'Add Patient Feedback' }}</h2>
              <button type="button" class="tl-close-btn" (click)="closeDrawer()">&times;</button>
            </header>

            <div class="tl-drawer-body">
              <div class="tl-form-grid-2">
                <label class="tl-field">
                  <span>Select Patient *</span>
                  <select
                    class="tl-input"
                    [value]="draftPatientId()"
                    [disabled]="viewingFeedback() !== null"
                    (change)="onPatientSelect($any($event.target).value)"
                  >
                    <option value="" disabled>Select patient</option>
                    @for (p of patients(); track p.id) {
                      <option [value]="p.id">{{ p.displayName }}</option>
                    }
                  </select>
                </label>

                <label class="tl-field">
                  <span>Select Doctor *</span>
                  <select
                    class="tl-input"
                    [value]="draftDoctorId()"
                    [disabled]="viewingFeedback() !== null"
                    (change)="onDoctorSelect($any($event.target).value)"
                  >
                    <option value="">No specific doctor</option>
                    @for (d of doctors(); track d.id) {
                      <option [value]="d.id">{{ d.name }}</option>
                    }
                  </select>
                </label>

                <label class="tl-field">
                  <span>Select Service *</span>
                  <select
                    class="tl-input"
                    [value]="draftService()"
                    [disabled]="viewingFeedback() !== null"
                    (change)="draftService.set($any($event.target).value)"
                  >
                    <option value="Consultation">Consultation</option>
                    <option value="Scaling & Polishing">Scaling & Polishing</option>
                    <option value="Root Canal Treatment">Root Canal Treatment</option>
                    <option value="Zirconia Crown">Zirconia Crown</option>
                    <option value="ClearPath Aligners">ClearPath Aligners</option>
                  </select>
                </label>

                <label class="tl-field">
                  <span>Name *</span>
                  <input
                    type="text"
                    class="tl-input"
                    [value]="draftPatientName()"
                    [disabled]="viewingFeedback() !== null"
                    (input)="draftPatientName.set($any($event.target).value)"
                  />
                </label>

                <label class="tl-field">
                  <span>Email</span>
                  <input
                    type="email"
                    class="tl-input"
                    [value]="draftEmail()"
                    [disabled]="viewingFeedback() !== null"
                    (input)="draftEmail.set($any($event.target).value)"
                  />
                </label>

                <label class="tl-field">
                  <span>Phone</span>
                  <input
                    type="text"
                    class="tl-input"
                    [value]="draftPhone()"
                    [disabled]="viewingFeedback() !== null"
                    (input)="draftPhone.set($any($event.target).value)"
                  />
                </label>

                <label class="tl-field">
                  <span>Age</span>
                  <input
                    type="number"
                    class="tl-input"
                    [value]="draftAge()"
                    [disabled]="viewingFeedback() !== null"
                    (input)="draftAge.set(+$any($event.target).value || 0)"
                  />
                </label>

                <label class="tl-field">
                  <span>Clinic Location</span>
                  <input
                    type="text"
                    class="tl-input"
                    [value]="draftLocation()"
                    [disabled]="viewingFeedback() !== null"
                    (input)="draftLocation.set($any($event.target).value)"
                  />
                </label>
              </div>

              <label class="tl-field">
                <span>Treatments Received</span>
                <input
                  type="text"
                  class="tl-input"
                  [value]="draftTreatments()"
                  [disabled]="viewingFeedback() !== null"
                  (input)="draftTreatments.set($any($event.target).value)"
                />
              </label>

              <!-- Referral Source Radio Options -->
              <div class="tl-field">
                <span>How did you hear about Clinic Flow Dental Clinic?</span>
                <div class="tl-radio-row">
                  @for (src of referralOptions; track src) {
                    <label class="tl-radio-item">
                      <input
                        type="radio"
                        name="referralSource"
                        [value]="src"
                        [checked]="draftReferral() === src"
                        [disabled]="viewingFeedback() !== null"
                        (change)="draftReferral.set(src)"
                      />
                      <span>{{ src }}</span>
                    </label>
                  }
                </div>
              </div>

              <!-- 5 Interactive 10-Star (1-10) Survey Rating Scales (Screenshot 18) -->
              <div class="tl-ratings-card">
                <h3 class="tl-ratings-heading">Clinical Quality & Experience Survey (1–10 Stars)</h3>

                <!-- Scale 1 -->
                <div class="tl-rating-item">
                  <div class="tl-rating-top">
                    <span>1. Satisfaction with explanation of treatment & options</span>
                    <strong>{{ ratingExplanation() }} / 10</strong>
                  </div>
                  <div class="tl-star-row">
                    @for (n of tenScale; track n) {
                      <button
                        type="button"
                        class="tl-star-btn"
                        [class.is-lit]="n <= ratingExplanation()"
                        [disabled]="viewingFeedback() !== null"
                        (click)="setRating('explanation', n)"
                      >
                        <span>{{ n }}</span>
                      </button>
                    }
                  </div>
                </div>

                <!-- Scale 2 -->
                <div class="tl-rating-item">
                  <div class="tl-rating-top">
                    <span>2. Satisfaction with clinic sterilization & hygiene</span>
                    <strong>{{ ratingHygiene() }} / 10</strong>
                  </div>
                  <div class="tl-star-row">
                    @for (n of tenScale; track n) {
                      <button
                        type="button"
                        class="tl-star-btn"
                        [class.is-lit]="n <= ratingHygiene()"
                        [disabled]="viewingFeedback() !== null"
                        (click)="setRating('hygiene', n)"
                      >
                        <span>{{ n }}</span>
                      </button>
                    }
                  </div>
                </div>

                <!-- Scale 3 -->
                <div class="tl-rating-item">
                  <div class="tl-rating-top">
                    <span>3. Reception & front-desk scheduling courtesy</span>
                    <strong>{{ ratingCourtesy() }} / 10</strong>
                  </div>
                  <div class="tl-star-row">
                    @for (n of tenScale; track n) {
                      <button
                        type="button"
                        class="tl-star-btn"
                        [class.is-lit]="n <= ratingCourtesy()"
                        [disabled]="viewingFeedback() !== null"
                        (click)="setRating('courtesy', n)"
                      >
                        <span>{{ n }}</span>
                      </button>
                    }
                  </div>
                </div>

                <!-- Scale 4 -->
                <div class="tl-rating-item">
                  <div class="tl-rating-top">
                    <span>4. Comfort & pain management during dental procedure</span>
                    <strong>{{ ratingComfort() }} / 10</strong>
                  </div>
                  <div class="tl-star-row">
                    @for (n of tenScale; track n) {
                      <button
                        type="button"
                        class="tl-star-btn"
                        [class.is-lit]="n <= ratingComfort()"
                        [disabled]="viewingFeedback() !== null"
                        (click)="setRating('comfort', n)"
                      >
                        <span>{{ n }}</span>
                      </button>
                    }
                  </div>
                </div>

                <!-- Scale 5 -->
                <div class="tl-rating-item">
                  <div class="tl-rating-top">
                    <span>5. Likelihood to recommend our clinic to family & friends</span>
                    <strong>{{ ratingRecommend() }} / 10</strong>
                  </div>
                  <div class="tl-star-row">
                    @for (n of tenScale; track n) {
                      <button
                        type="button"
                        class="tl-star-btn"
                        [class.is-lit]="n <= ratingRecommend()"
                        [disabled]="viewingFeedback() !== null"
                        (click)="setRating('recommend', n)"
                      >
                        <span>{{ n }}</span>
                      </button>
                    }
                  </div>
                </div>
              </div>

              <label class="tl-field">
                <span>Patient Message / Additional Comments *</span>
                <textarea
                  rows="3"
                  class="tl-textarea"
                  placeholder="Share patient's comments on their clinical visit..."
                  [value]="draftMessage()"
                  [disabled]="viewingFeedback() !== null"
                  (input)="draftMessage.set($any($event.target).value)"
                ></textarea>
              </label>
            </div>

            <footer class="tl-drawer-footer">
              <button type="button" class="tl-btn tl-btn-outline" (click)="closeDrawer()">
                {{ viewingFeedback() ? 'Close' : 'Cancel' }}
              </button>
              @if (!viewingFeedback()) {
                <button
                  type="button"
                  class="tl-btn tl-btn-teal"
                  data-testid="submit-patient-feedback-btn"
                  [disabled]="saveBusy()"
                  (click)="saveFeedback()"
                >
                  {{ saveBusy() ? 'Saving…' : 'Save' }}
                </button>
              }
            </footer>
          </aside>
        </div>
      }

      <!-- Bottom-left Toast Notification matching Screenshot 19 (19_patient_feedback_toast_308s.png) -->
      @if (toastVisible()) {
        <div class="tl-bottom-toast" data-testid="feedback-success-toast" role="status">
          <span>{{ toastText() }}</span>
          <button type="button" class="tl-toast-dismiss-btn" (click)="toastVisible.set(false)">
            DISMISS
          </button>
        </div>
      }
    </div>
  `,
  styles: [
    `
      .tl-page {
        padding: 12px 18px;
        display: flex;
        flex-direction: column;
        gap: 10px;
      }
      .tl-breadcrumbs {
        display: flex;
        align-items: center;
        gap: 6px;
        font-size: 12px;
        color: var(--mute);
        margin-bottom: 6px;
      }
      .tl-breadcrumbs a {
        color: var(--tl-teal);
        text-decoration: none;
        font-weight: 600;
      }
      .tl-breadcrumbs .active {
        color: var(--ink);
        font-weight: 700;
      }
      .tl-card {
        background: var(--surf);
        border: 1px solid var(--line);
        border-radius: 8px;
        padding: 12px 16px;
        box-shadow: var(--shadow-soft);
      }
      .tl-toolbar {
        display: flex;
        align-items: center;
        justify-content: space-between;
        flex-wrap: wrap;
        gap: 12px;
        margin-bottom: 18px;
      }
      .tl-toolbar-left,
      .tl-toolbar-right {
        display: flex;
        align-items: center;
        gap: 10px;
        flex-wrap: wrap;
      }
      .tl-select,
      .tl-input {
        height: 38px;
        padding: 0 12px;
        border: 1px solid var(--line);
        border-radius: 6px;
        background: var(--surf);
        color: var(--ink);
        font-size: 13px;
      }
      .tl-textarea {
        width: 100%;
        padding: 10px 12px;
        border: 1px solid var(--line);
        border-radius: 6px;
        background: var(--surf);
        color: var(--ink);
        font-size: 13px;
        font-family: inherit;
      }
      .tl-btn {
        height: 38px;
        padding: 0 16px;
        border-radius: 6px;
        font-size: 13px;
        font-weight: 600;
        cursor: pointer;
        border: 1px solid transparent;
        display: inline-flex;
        align-items: center;
        gap: 6px;
      }
      .tl-btn-teal {
        background: var(--tl-teal);
        color: var(--on-accent);
      }
      .tl-btn-outline {
        background: var(--surf2);
        border-color: var(--line);
        color: var(--ink);
      }
      .tl-table-wrap {
        overflow-x: auto;
      }
      .tl-table {
        width: 100%;
        border-collapse: collapse;
        font-size: 13px;
      }
      .tl-table thead th {
        background: var(--surf2);
        color: var(--mute);
        text-align: left;
        padding: 10px 14px;
        font-size: 11px;
        font-weight: 700;
        text-transform: uppercase;
        letter-spacing: 0.05em;
        white-space: nowrap;
        border: none;
        border-bottom: 1px solid var(--line);
      }
      .tl-table tbody td {
        padding: 12px 14px;
        border-bottom: 1px solid var(--line);
        color: var(--ink);
      }
      .tl-empty-cell {
        text-align: center;
        padding: 32px !important;
        color: var(--mute);
      }
      .tl-patient-cell {
        display: flex;
        flex-direction: column;
        gap: 2px;
      }
      .tl-patient-cell span {
        font-size: 11.5px;
        color: var(--mute);
      }
      .tl-msg-cell {
        max-width: 280px;
      }
      .tl-score-badge {
        display: inline-block;
        padding: 4px 10px;
        border-radius: 999px;
        background: var(--accent-yellow-soft);
        color: var(--accent-amber-ink);
        font-weight: 800;
        font-size: 12px;
      }
      .tl-row-actions {
        display: flex;
        gap: 6px;
      }
      .tl-icon-btn {
        width: 30px;
        height: 30px;
        border-radius: 6px;
        border: 1px solid var(--line);
        background: var(--surf2);
        cursor: pointer;
      }
      .tl-icon-btn.is-teal {
        background: var(--tl-teal);
        color: var(--on-accent);
        border-color: var(--tl-teal);
      }
      .tl-icon-btn.is-danger {
        color: var(--accent-red-500);
      }
      .tl-drawer-backdrop {
        position: fixed;
        inset: 0;
        background: var(--scrim);
        z-index: 1200;
        display: flex;
        justify-content: flex-end;
      }
      .tl-drawer {
        width: min(720px, 95vw);
        height: 100vh;
        background: var(--surf);
        display: flex;
        flex-direction: column;
        box-shadow: var(--shadow-panel);
      }
      .tl-drawer-header {
        padding: 18px 24px;
        border-bottom: 1px solid var(--line);
        display: flex;
        align-items: center;
        justify-content: space-between;
      }
      .tl-drawer-header h2 {
        margin: 0;
        font-size: 18px;
      }
      .tl-close-btn {
        border: none;
        background: transparent;
        font-size: 18px;
        cursor: pointer;
      }
      .tl-drawer-body {
        padding: 20px 24px;
        overflow-y: auto;
        flex: 1;
        display: flex;
        flex-direction: column;
        gap: 16px;
      }
      .tl-form-grid-2 {
        display: grid;
        grid-template-columns: repeat(2, 1fr);
        gap: 12px;
      }
      .tl-field {
        display: flex;
        flex-direction: column;
        gap: 6px;
        font-size: 12.5px;
        font-weight: 600;
        color: var(--ink-soft);
      }
      .tl-radio-row {
        display: flex;
        flex-wrap: wrap;
        gap: 14px;
        padding-top: 4px;
      }
      .tl-radio-item {
        display: inline-flex;
        align-items: center;
        gap: 6px;
        font-weight: 500;
        cursor: pointer;
      }
      .tl-ratings-card {
        border: 1px solid var(--line);
        border-radius: 8px;
        padding: 16px;
        background: var(--surf2);
        display: flex;
        flex-direction: column;
        gap: 14px;
      }
      .tl-ratings-heading {
        margin: 0;
        font-size: 13.5px;
        font-weight: 800;
        color: var(--tl-teal);
      }
      .tl-rating-item {
        display: flex;
        flex-direction: column;
        gap: 6px;
      }
      .tl-rating-top {
        display: flex;
        justify-content: space-between;
        font-size: 12.5px;
        font-weight: 600;
      }
      .tl-star-row {
        display: flex;
        gap: 5px;
        flex-wrap: wrap;
      }
      .tl-star-btn {
        height: 30px;
        min-width: 44px;
        border-radius: 6px;
        border: 1px solid var(--line);
        background: var(--surf);
        color: var(--mute);
        font-size: 12px;
        font-weight: 700;
        cursor: pointer;
        display: inline-flex;
        align-items: center;
        justify-content: center;
        gap: 3px;
      }
      .tl-star-btn.is-lit {
        background: var(--accent-amber-soft);
        border-color: var(--accent-amber);
        color: var(--accent-amber-ink);
      }
      .tl-drawer-footer {
        padding: 16px 24px;
        border-top: 1px solid var(--line);
        display: flex;
        justify-content: flex-end;
        gap: 10px;
      }
      /* Bottom-left Toast matching Screenshot 19 */
      .tl-bottom-toast {
        position: fixed;
        bottom: 24px;
        left: 24px;
        z-index: 1300;
        background: var(--tl-dark);
        color: var(--on-accent);
        padding: 12px 18px;
        border-radius: 8px;
        box-shadow: var(--shadow-card);
        display: flex;
        align-items: center;
        gap: 16px;
        font-size: 13.5px;
        font-weight: 600;
      }
      .tl-toast-dismiss-btn {
        border: none;
        background: transparent;
        color: var(--tl-teal);
        font-weight: 800;
        cursor: pointer;
      }
    `,
  ],
})
export class ClinicFeedbackComponent implements OnInit {
  private readonly api = inject(ClinicPatientApi);
  private readonly clinic = inject(ClinicApi);
  private readonly projects = inject(ProjectContextService);

  protected readonly tenScale = TEN_SCALE;
  protected readonly referralOptions = [
    'Social Media',
    'Friend / Family Referral',
    'Google Search',
    'Walk-In',
  ] as const;

  protected readonly doctors = signal<readonly ClinicDoctor[]>([]);
  protected readonly patients = signal<readonly ClinicPatient[]>([]);
  protected readonly feedbackList = signal<readonly PatientFeedbackRecord[]>([]);
  protected readonly showFeedbackInfo = signal(false);
  protected readonly loading = signal(false);
  protected readonly saveBusy = signal(false);

  protected readonly searchQuery = signal('');
  protected readonly bulkAction = signal('');
  protected readonly selectedIds = signal<ReadonlySet<string>>(new Set());

  protected readonly drawerOpen = signal(false);
  protected readonly viewingFeedback = signal<PatientFeedbackRecord | null>(null);

  protected readonly draftPatientId = signal('');
  protected readonly draftPatientName = signal('');
  protected readonly draftEmail = signal('');
  protected readonly draftPhone = signal('');
  protected readonly draftAge = signal(0);
  protected readonly draftDoctorId = signal('');
  protected readonly draftDoctorName = signal('');
  protected readonly draftService = signal('Consultation');
  protected readonly draftTreatments = signal('Consultation & Dental Examination');
  protected readonly draftLocation = signal('');
  protected readonly draftReferral = signal('Social Media');
  protected readonly draftMessage = signal('');

  protected readonly ratingExplanation = signal(10);
  protected readonly ratingHygiene = signal(10);
  protected readonly ratingCourtesy = signal(10);
  protected readonly ratingComfort = signal(10);
  protected readonly ratingRecommend = signal(10);

  protected readonly toastVisible = signal(false);
  protected readonly toastText = signal('Patient feedback submitted successfully!');

  protected readonly filteredFeedback = computed<readonly PatientFeedbackRecord[]>(() => {
    const q = this.searchQuery().trim().toLowerCase();
    if (!q) return this.feedbackList();
    return this.feedbackList().filter(
      (f) =>
        f.patientName.toLowerCase().includes(q) ||
        f.doctorName.toLowerCase().includes(q) ||
        f.serviceName.toLowerCase().includes(q) ||
        f.message.toLowerCase().includes(q),
    );
  });

  protected readonly allSelected = computed(() => {
    const rows = this.filteredFeedback();
    return rows.length > 0 && rows.every((r) => this.selectedIds().has(r.id));
  });

  ngOnInit(): void {
    void this.load();
  }

  private projectQuery(): { projectId?: string } {
    const projectId = this.projects.selectedProjectId();
    return projectId ? { projectId } : {};
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    try {
      const query = this.projectQuery();
      const [feedback, doctors, patients] = await Promise.all([
        this.api.feedbackList(query),
        this.clinic.doctors(query),
        this.api.patients(query),
      ]);
      this.feedbackList.set(feedback.map((row) => this.toRecord(row)));
      this.doctors.set(doctors.items ?? []);
      this.patients.set(patients);
    } catch {
      this.toastText.set('Could not load patient feedback. Check the clinic connection.');
      this.toastVisible.set(true);
    } finally {
      this.loading.set(false);
    }
  }

  private toRecord(row: ClinicFeedback): PatientFeedbackRecord {
    return {
      id: row.id,
      patientId: row.patientId ?? null,
      doctorId: row.doctorId ?? null,
      patientName: row.patientName ?? '—',
      patientEmail: row.patientEmail ?? '',
      patientPhone: row.patientPhone ?? '',
      patientAge: row.patientAge ?? 0,
      doctorName: row.doctorName ?? '—',
      serviceName: row.serviceName ?? '—',
      treatmentsReceived: row.treatmentsReceived ?? '',
      clinicLocation: row.clinicLocation ?? '',
      referralSource: row.referralSource ?? '',
      ratingExplanation: row.ratingExplanation,
      ratingHygiene: row.ratingHygiene,
      ratingCourtesy: row.ratingCourtesy,
      ratingComfort: row.ratingComfort,
      ratingRecommend: row.ratingRecommend,
      message: row.message ?? '',
      dateTime: formatDateTime(row.submittedAt ?? row.createdAt),
    };
  }

  protected exportFeedbackCsv(): void {
    const list = this.filteredFeedback();
    if (list.length === 0) return;

    const headers = [
      'ID',
      'Date & Time',
      'Patient Name',
      'Doctor',
      'Service',
      'Average Rating',
      'Hygiene',
      'Courtesy',
      'Comfort',
      'Recommend',
      'Message',
    ];
    const rows = list.map((f) => [
      f.id,
      `"${f.dateTime}"`,
      `"${f.patientName.replace(/"/g, '""')}"`,
      `"${f.doctorName.replace(/"/g, '""')}"`,
      `"${f.serviceName.replace(/"/g, '""')}"`,
      this.averageScore(f),
      f.ratingHygiene,
      f.ratingCourtesy,
      f.ratingComfort,
      f.ratingRecommend,
      `"${(f.message || '').replace(/"/g, '""')}"`,
    ]);

    const csvContent = '\uFEFF' + [headers.join(','), ...rows.map((r) => r.join(','))].join('\r\n');
    const blob = new Blob([csvContent], { type: 'text/csv;charset=utf-8;' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.setAttribute('href', url);
    link.setAttribute('download', `patient-feedback-${new Date().toISOString().slice(0, 10)}.csv`);
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    URL.revokeObjectURL(url);
  }

  protected averageScore(row: PatientFeedbackRecord): string {
    const avg =
      (row.ratingExplanation +
        row.ratingHygiene +
        row.ratingCourtesy +
        row.ratingComfort +
        row.ratingRecommend) /
      5;
    return avg.toFixed(1);
  }

  protected openAddDrawer(): void {
    this.viewingFeedback.set(null);
    this.draftPatientId.set('');
    this.draftPatientName.set('');
    this.draftEmail.set('');
    this.draftPhone.set('');
    this.draftAge.set(0);
    this.draftDoctorId.set('');
    this.draftDoctorName.set('');
    this.draftService.set('Consultation');
    this.draftTreatments.set('Consultation & Dental Examination');
    this.draftLocation.set('');
    this.draftReferral.set('Social Media');
    this.draftMessage.set('');
    this.ratingExplanation.set(10);
    this.ratingHygiene.set(10);
    this.ratingCourtesy.set(10);
    this.ratingComfort.set(10);
    this.ratingRecommend.set(10);
    const firstPatient = this.patients()[0];
    if (firstPatient?.id) {
      this.onPatientSelect(firstPatient.id);
    }
    this.drawerOpen.set(true);
  }

  protected viewFeedback(row: PatientFeedbackRecord): void {
    this.viewingFeedback.set(row);
    this.draftPatientId.set(row.patientId ?? '');
    this.draftPatientName.set(row.patientName);
    this.draftEmail.set(row.patientEmail);
    this.draftPhone.set(row.patientPhone);
    this.draftAge.set(row.patientAge);
    this.draftDoctorId.set(row.doctorId ?? '');
    this.draftDoctorName.set(row.doctorName === '—' ? '' : row.doctorName);
    this.draftService.set(row.serviceName === '—' ? 'Consultation' : row.serviceName);
    this.draftTreatments.set(row.treatmentsReceived);
    this.draftLocation.set(row.clinicLocation);
    this.draftReferral.set(row.referralSource);
    this.draftMessage.set(row.message);
    this.ratingExplanation.set(row.ratingExplanation);
    this.ratingHygiene.set(row.ratingHygiene);
    this.ratingCourtesy.set(row.ratingCourtesy);
    this.ratingComfort.set(row.ratingComfort);
    this.ratingRecommend.set(row.ratingRecommend);
    this.drawerOpen.set(true);
  }

  protected closeDrawer(): void {
    this.drawerOpen.set(false);
    this.viewingFeedback.set(null);
  }

  protected onPatientSelect(patientId: string): void {
    this.draftPatientId.set(patientId);
    const found = this.patients().find((p) => p.id === patientId);
    if (!found) return;
    const fallbackName = [found.givenName, found.familyName].filter(Boolean).join(' ').trim();
    this.draftPatientName.set(found.displayName ?? fallbackName);
    this.draftEmail.set(found.primaryEmail ?? '');
    this.draftPhone.set(found.primaryPhone ?? '');
    this.draftAge.set(ageFromDateOfBirth(found.dateOfBirth));
  }

  protected onDoctorSelect(doctorId: string): void {
    this.draftDoctorId.set(doctorId);
    const found = this.doctors().find((d) => d.id === doctorId);
    this.draftDoctorName.set(found?.name ?? '');
  }

  protected setRating(
    field: 'explanation' | 'hygiene' | 'courtesy' | 'comfort' | 'recommend',
    value: number,
  ): void {
    if (this.viewingFeedback() !== null) return;
    if (field === 'explanation') this.ratingExplanation.set(value);
    else if (field === 'hygiene') this.ratingHygiene.set(value);
    else if (field === 'courtesy') this.ratingCourtesy.set(value);
    else if (field === 'comfort') this.ratingComfort.set(value);
    else this.ratingRecommend.set(value);
  }

  protected async saveFeedback(): Promise<void> {
    if (this.saveBusy() || this.viewingFeedback() !== null) return;
    const patientName = this.draftPatientName().trim();
    if (!patientName) {
      this.toastText.set('Choose a patient before saving feedback.');
      this.toastVisible.set(true);
      return;
    }
    this.saveBusy.set(true);
    try {
      const created = await this.api.createFeedback(
        {
          patientId: this.draftPatientId() || null,
          doctorId: this.draftDoctorId() || null,
          patientName,
          patientEmail: this.draftEmail().trim() || undefined,
          patientPhone: this.draftPhone().trim() || undefined,
          patientAge: this.draftAge() > 0 ? this.draftAge() : null,
          doctorName: this.draftDoctorName().trim() || undefined,
          serviceName: this.draftService(),
          treatmentsReceived: this.draftTreatments(),
          clinicLocation: this.draftLocation(),
          referralSource: this.draftReferral(),
          ratingExplanation: this.ratingExplanation(),
          ratingHygiene: this.ratingHygiene(),
          ratingCourtesy: this.ratingCourtesy(),
          ratingComfort: this.ratingComfort(),
          ratingRecommend: this.ratingRecommend(),
          message: this.draftMessage().trim(),
          ...this.projectQuery(),
        },
        this.projectQuery(),
      );
      this.feedbackList.update((list) => [this.toRecord(created), ...list]);
      this.drawerOpen.set(false);
      this.toastText.set('Patient feedback submitted successfully!');
      this.toastVisible.set(true);
    } catch {
      this.toastText.set('Could not save the feedback. The server refused the request.');
      this.toastVisible.set(true);
    } finally {
      this.saveBusy.set(false);
    }
  }

  protected async deleteFeedback(id: string): Promise<void> {
    try {
      await this.api.deleteFeedback(id, this.projectQuery());
      this.feedbackList.update((list) => list.filter((item) => item.id !== id));
      this.selectedIds.update((set) => {
        const next = new Set(set);
        next.delete(id);
        return next;
      });
      this.toastText.set('Patient feedback entry removed.');
      this.toastVisible.set(true);
    } catch {
      this.toastText.set('Only a clinic administrator can delete patient feedback.');
      this.toastVisible.set(true);
    }
  }

  protected toggleRowSelection(id: string, checked: boolean): void {
    const next = new Set(this.selectedIds());
    if (checked) next.add(id);
    else next.delete(id);
    this.selectedIds.set(next);
  }

  protected toggleSelectAll(checked: boolean): void {
    if (!checked) {
      this.selectedIds.set(new Set());
      return;
    }
    this.selectedIds.set(new Set(this.filteredFeedback().map((r) => r.id)));
  }

  protected async applyBulkAction(): Promise<void> {
    if (this.bulkAction() !== 'DELETE' || this.selectedIds().size === 0) return;
    const ids = Array.from(this.selectedIds());
    let deleted = 0;
    for (const id of ids) {
      try {
        await this.api.deleteFeedback(id, this.projectQuery());
        deleted += 1;
      } catch {
        // A refused row does not abort the batch; the count reports what moved.
      }
    }
    await this.load();
    this.selectedIds.set(new Set());
    this.bulkAction.set('');
    this.toastText.set(`Deleted ${deleted} of ${ids.length} patient feedback entries.`);
    this.toastVisible.set(true);
  }
}

function ageFromDateOfBirth(dateOfBirth: string | undefined): number {
  if (!dateOfBirth) return 0;
  const born = new Date(dateOfBirth);
  if (Number.isNaN(born.getTime())) return 0;
  const now = new Date();
  let age = now.getFullYear() - born.getFullYear();
  const monthDiff = now.getMonth() - born.getMonth();
  if (monthDiff < 0 || (monthDiff === 0 && now.getDate() < born.getDate())) {
    age -= 1;
  }
  return age > 0 && age < 150 ? age : 0;
}

function formatDateTime(value: string | null | undefined): string {
  if (!value) return '';
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return '';
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${parsed.getFullYear()}-${pad(parsed.getMonth() + 1)}-${pad(parsed.getDate())} ${pad(parsed.getHours())}:${pad(parsed.getMinutes())}`;
}
