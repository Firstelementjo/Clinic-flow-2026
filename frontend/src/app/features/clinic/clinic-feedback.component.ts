import { CommonModule } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

export interface PatientFeedbackRecord {
  readonly id: string;
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

interface PatientDirectoryOption {
  readonly name: string;
  readonly email: string;
  readonly phone: string;
  readonly age: number;
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
      <div class="tl-breadcrumbs">
        <a routerLink="/app/dashboard">Home</a>
        <span>»</span>
        <span class="active">Patient Feedback</span>
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
                    [value]="draftPatientName()"
                    (change)="onPatientSelect($any($event.target).value)"
                  >
                    @for (p of patientDirectory; track p.name) {
                      <option [value]="p.name">{{ p.name }}</option>
                    }
                  </select>
                </label>

                <label class="tl-field">
                  <span>Select Doctor *</span>
                  <select
                    class="tl-input"
                    [value]="draftDoctor()"
                    (change)="draftDoctor.set($any($event.target).value)"
                  >
                    <option value="Dr. Eman Khan">Dr. Eman Khan</option>
                    <option value="Dr. Bilal Ahmed">Dr. Bilal Ahmed</option>
                    <option value="Dr. Sara Malik">Dr. Sara Malik</option>
                  </select>
                </label>

                <label class="tl-field">
                  <span>Select Service *</span>
                  <select
                    class="tl-input"
                    [value]="draftService()"
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
                    (input)="draftPatientName.set($any($event.target).value)"
                  />
                </label>

                <label class="tl-field">
                  <span>Email</span>
                  <input
                    type="email"
                    class="tl-input"
                    [value]="draftEmail()"
                    (input)="draftEmail.set($any($event.target).value)"
                  />
                </label>

                <label class="tl-field">
                  <span>Phone</span>
                  <input
                    type="text"
                    class="tl-input"
                    [value]="draftPhone()"
                    (input)="draftPhone.set($any($event.target).value)"
                  />
                </label>

                <label class="tl-field">
                  <span>Age</span>
                  <input
                    type="number"
                    class="tl-input"
                    [value]="draftAge()"
                    (input)="draftAge.set(+$any($event.target).value || 28)"
                  />
                </label>

                <label class="tl-field">
                  <span>Clinic Location</span>
                  <input
                    type="text"
                    class="tl-input"
                    [value]="draftLocation()"
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
                        (click)="ratingExplanation.set(n)"
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
                        (click)="ratingHygiene.set(n)"
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
                        (click)="ratingCourtesy.set(n)"
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
                        (click)="ratingComfort.set(n)"
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
                        (click)="ratingRecommend.set(n)"
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
                  (input)="draftMessage.set($any($event.target).value)"
                ></textarea>
              </label>
            </div>

            <footer class="tl-drawer-footer">
              <button type="button" class="tl-btn tl-btn-outline" (click)="closeDrawer()">
                Cancel
              </button>
              <button
                type="button"
                class="tl-btn tl-btn-teal"
                data-testid="submit-patient-feedback-btn"
                (click)="saveFeedback()"
              >
                Save
              </button>
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
        background: var(--tl-teal);
        color: var(--on-accent);
        text-align: left;
        padding: 12px 14px;
        font-weight: 700;
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
export class ClinicFeedbackComponent {
  protected readonly tenScale = TEN_SCALE;
  protected readonly referralOptions = [
    'Social Media',
    'Friend / Family Referral',
    'Google Search',
    'Walk-In',
  ] as const;

  protected readonly patientDirectory: readonly PatientDirectoryOption[] = [
    { name: 'Mr. Awais', email: 'awais@patient.pk', phone: '+92 300 4112233', age: 34 },
    { name: 'Mrs. Sana', email: 'sana.khan@patient.pk', phone: '+92 321 8844210', age: 29 },
    { name: 'Ali Raza', email: 'aliraza@patient.pk', phone: '+92 333 9011223', age: 42 },
    { name: 'Zainab Tariq', email: 'zainab.t@patient.pk', phone: '+92 345 7712345', age: 24 },
  ];

  protected readonly feedbackList = signal<readonly PatientFeedbackRecord[]>([
    {
      id: 'FB-201',
      patientName: 'Mr. Awais',
      patientEmail: 'awais@patient.pk',
      patientPhone: '+92 300 4112233',
      patientAge: 34,
      doctorName: 'Dr. Eman Khan',
      serviceName: 'Consultation',
      treatmentsReceived: 'Digital OPG Review & Treatment Plan',
      clinicLocation: 'Clinic Flow Main Branch',
      referralSource: 'Google Search',
      ratingExplanation: 10,
      ratingHygiene: 10,
      ratingCourtesy: 9,
      ratingComfort: 10,
      ratingRecommend: 10,
      message: 'Dr. Eman explained the root canal and crown options very clearly. Spotless clinic!',
      dateTime: '2026-04-10 01:15 PM',
    },
    {
      id: 'FB-202',
      patientName: 'Mrs. Sana',
      patientEmail: 'sana.khan@patient.pk',
      patientPhone: '+92 321 8844210',
      patientAge: 29,
      doctorName: 'Dr. Eman Khan',
      serviceName: 'Scaling & Polishing',
      treatmentsReceived: 'Ultrasonic Scaling & Airflow Polishing',
      clinicLocation: 'Clinic Flow Main Branch',
      referralSource: 'Friend / Family Referral',
      ratingExplanation: 9,
      ratingHygiene: 10,
      ratingCourtesy: 10,
      ratingComfort: 9,
      ratingRecommend: 10,
      message: 'Completely painless ultrasonic scaling and very warm front desk reception.',
      dateTime: '2026-04-09 04:40 PM',
    },
  ]);

  protected readonly searchQuery = signal('');
  protected readonly bulkAction = signal('');
  protected readonly selectedIds = signal<ReadonlySet<string>>(new Set());

  protected readonly drawerOpen = signal(false);
  protected readonly viewingFeedback = signal<PatientFeedbackRecord | null>(null);

  protected readonly draftPatientName = signal('Mr. Awais');
  protected readonly draftEmail = signal('awais@patient.pk');
  protected readonly draftPhone = signal('+92 300 4112233');
  protected readonly draftAge = signal(34);
  protected readonly draftDoctor = signal('Dr. Eman Khan');
  protected readonly draftService = signal('Consultation');
  protected readonly draftTreatments = signal('Consultation & Dental Examination');
  protected readonly draftLocation = signal('Clinic Flow Main Branch');
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
    this.onPatientSelect('Mr. Awais');
    this.draftDoctor.set('Dr. Eman Khan');
    this.draftService.set('Consultation');
    this.draftTreatments.set('Consultation & Dental Examination');
    this.draftLocation.set('Clinic Flow Main Branch');
    this.draftReferral.set('Social Media');
    this.draftMessage.set('');
    this.ratingExplanation.set(10);
    this.ratingHygiene.set(10);
    this.ratingCourtesy.set(10);
    this.ratingComfort.set(10);
    this.ratingRecommend.set(10);
    this.drawerOpen.set(true);
  }

  protected viewFeedback(row: PatientFeedbackRecord): void {
    this.viewingFeedback.set(row);
    this.draftPatientName.set(row.patientName);
    this.draftEmail.set(row.patientEmail);
    this.draftPhone.set(row.patientPhone);
    this.draftAge.set(row.patientAge);
    this.draftDoctor.set(row.doctorName);
    this.draftService.set(row.serviceName);
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
  }

  protected onPatientSelect(name: string): void {
    this.draftPatientName.set(name);
    const found = this.patientDirectory.find((p) => p.name === name);
    if (found) {
      this.draftEmail.set(found.email);
      this.draftPhone.set(found.phone);
      this.draftAge.set(found.age);
    }
  }

  protected saveFeedback(): void {
    const existing = this.viewingFeedback();
    const record: PatientFeedbackRecord = {
      id: existing?.id ?? `FB-${203 + this.feedbackList().length}`,
      patientName: this.draftPatientName(),
      patientEmail: this.draftEmail(),
      patientPhone: this.draftPhone(),
      patientAge: this.draftAge(),
      doctorName: this.draftDoctor(),
      serviceName: this.draftService(),
      treatmentsReceived: this.draftTreatments(),
      clinicLocation: this.draftLocation(),
      referralSource: this.draftReferral(),
      ratingExplanation: this.ratingExplanation(),
      ratingHygiene: this.ratingHygiene(),
      ratingCourtesy: this.ratingCourtesy(),
      ratingComfort: this.ratingComfort(),
      ratingRecommend: this.ratingRecommend(),
      message: this.draftMessage().trim() || 'Very satisfied with the dental care provided.',
      dateTime: new Date().toISOString().slice(0, 16).replace('T', ' '),
    };

    if (existing) {
      this.feedbackList.update((list) => list.map((item) => (item.id === existing.id ? record : item)));
    } else {
      this.feedbackList.update((list) => [record, ...list]);
    }
    this.drawerOpen.set(false);
    this.toastText.set('Patient feedback submitted successfully!');
    this.toastVisible.set(true);
  }

  protected deleteFeedback(id: string): void {
    this.feedbackList.update((list) => list.filter((item) => item.id !== id));
    this.toastText.set('Patient feedback entry removed.');
    this.toastVisible.set(true);
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

  protected applyBulkAction(): void {
    if (this.bulkAction() === 'DELETE' && this.selectedIds().size > 0) {
      const ids = this.selectedIds();
      this.feedbackList.update((list) => list.filter((item) => !ids.has(item.id)));
      this.selectedIds.set(new Set());
      this.toastText.set('Selected patient feedback entries deleted.');
      this.toastVisible.set(true);
    }
  }
}
