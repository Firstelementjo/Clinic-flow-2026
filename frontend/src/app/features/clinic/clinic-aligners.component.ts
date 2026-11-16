import { CommonModule } from '@angular/common';
import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ClinicApi, type ClinicDoctor } from '../../core/api/endpoints';
import { ProjectContextService } from '../../core/context/project-context.service';
import {
  ClinicPatientApi,
  type ClinicAlignerCase,
  type ClinicPatient,
} from './clinic-patient.api';

export type AlignerStageCategory =
  | 'ALL'
  | 'WAITING_APPROVAL'
  | 'ON_HOLD'
  | 'INCOMPLETE_RX'
  | 'WAITING_IMPRESSIONS'
  | 'COMPLETED';

export interface AlignerCaseRecord {
  readonly id: string;
  readonly patientId?: string;
  readonly doctorId?: string | null;
  readonly doctorName: string;
  readonly patientName: string;
  readonly caseType: string;
  readonly impressionType: string;
  readonly shade: string;
  readonly status: 'Sent to Lab' | 'In Production' | 'Quality Check' | 'Completed' | 'On Hold';
  readonly createdAt: string;
  readonly upperSteps: number;
  readonly lowerSteps: number;
  readonly teethSummary: string;
  readonly iprSummary: string;
  readonly attachmentsCount: number;
  readonly rxNotes: string;
  readonly files: readonly string[];
}

@Component({
  selector: 'fe-clinic-aligners',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="tl-page" data-testid="clinic-aligners">
      <!-- Breadcrumbs -->
      <div class="tl-breadcrumbs">
        <a routerLink="/app/dashboard">Home</a>
        <span>&raquo;</span>
        <span class="active">Clear Aligner Lab</span>
      </div>

      <div class="tl-card">
        <!-- Toolbar identical to Screenshot (14_lab_list / user reference) -->
        <div class="tl-toolbar">
          <div class="tl-toolbar-left">
            <button
              type="button"
              class="tl-btn tl-btn-teal"
              data-testid="submit-aligner-case-btn"
              (click)="openCreateDrawer()"
            >
              New +
            </button>
            <div class="tl-search-box">
              <input
                type="search"
                class="tl-input"
                placeholder="...Search lab report, doctor, patient"
                [ngModel]="searchQuery()"
                (ngModelChange)="searchQuery.set($event)"
              />
            </div>
          </div>

          <div class="tl-toolbar-right">
            <button type="button" class="tl-btn tl-btn-outline" (click)="exportCsv()">
              Export
            </button>
            <button type="button" class="tl-btn tl-btn-teal" (click)="applyBulkAction()">
              Apply
            </button>
            <select
              class="tl-select"
              [ngModel]="bulkAction()"
              (ngModelChange)="bulkAction.set($event)"
              aria-label="Bulk Action"
            >
              <option value="">No Action</option>
              <option value="COMPLETED">Mark Completed</option>
              <option value="PRODUCTION">Mark In Production</option>
              <option value="DELETE">Delete Selected</option>
            </select>
          </div>
        </div>

        @if (bannerMessage(); as msg) {
          <div class="tl-toast-banner" role="status">
            <span>{{ msg }}</span>
            <button type="button" class="tl-toast-dismiss" (click)="bannerMessage.set(null)">DISMISS</button>
          </div>
        }

        <!-- Aligner Orders Table (Exact Solid Teal Header matching reference screenshot) -->
        <div class="tl-table-wrap">
          <table class="tl-table">
            <thead>
              <tr>
                <th style="width: 120px;">Action</th>
                <th>Created</th>
                <th>Status</th>
                <th>Teeth (FDI)</th>
                <th>Impression Type</th>
                <th>Case Type</th>
                <th>Patient</th>
                <th>Doctor</th>
                <th>ID</th>
                <th style="width: 38px; text-align: center;">
                  <input
                    type="checkbox"
                    [checked]="allSelected()"
                    (change)="toggleSelectAll($any($event.target).checked)"
                    aria-label="Select all rows"
                  />
                </th>
              </tr>
            </thead>
            <tbody>
              @if (filteredOrders().length === 0) {
                <tr>
                  <td colspan="10" class="tl-empty-cell">No data found</td>
                </tr>
              } @else {
                @for (row of filteredOrders(); track row.id) {
                  <tr>
                    <td>
                      <div class="tl-row-actions">
                        <button
                          type="button"
                          class="tl-icon-btn is-danger"
                          title="Delete Aligner Case"
                          (click)="deleteOrder(row.id)"
                        >
                          <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2">
                            <polyline points="3 6 5 6 21 6"></polyline>
                            <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"></path>
                          </svg>
                        </button>
                        <button
                          type="button"
                          class="tl-icon-btn"
                          title="Print Aligner Case Sheet"
                          (click)="printOrder(row)"
                        >
                          <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2">
                            <polyline points="6 9 6 2 18 2 18 9"></polyline>
                            <path d="M6 18H4a2 2 0 0 1-2-2v-5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v5a2 2 0 0 1-2 2h-2"></path>
                            <rect x="6" y="14" width="12" height="8"></rect>
                          </svg>
                        </button>
                        <button
                          type="button"
                          class="tl-icon-btn is-teal"
                          title="View / Edit 3D Setup & Rx"
                          (click)="viewOrder(row)"
                        >
                          <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2">
                            <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"></path>
                            <circle cx="12" cy="12" r="3"></circle>
                          </svg>
                        </button>
                      </div>
                    </td>
                    <td class="tl-created-cell">{{ row.createdAt }}</td>
                    <td>
                      <span
                        class="tl-status-badge"
                        [class.is-completed]="row.status === 'Completed'"
                        [class.is-prod]="row.status === 'In Production'"
                        [class.is-hold]="row.status === 'On Hold'"
                      >
                        {{ row.status }}
                      </span>
                    </td>
                    <td>
                      <span class="tl-teeth-summary">
                        {{ row.teethSummary }}
                      </span>
                    </td>
                    <td>{{ row.impressionType }}</td>
                    <td>{{ row.caseType }}</td>
                    <td><strong>{{ row.patientName }}</strong></td>
                    <td>{{ row.doctorName }}</td>
                    <td class="tl-mono">{{ row.id }}</td>
                    <td style="text-align: center;">
                      <input
                        type="checkbox"
                        [checked]="selectedIds().has(row.id)"
                        (change)="toggleRowSelection(row.id, $any($event.target).checked)"
                      />
                    </td>
                  </tr>
                }
              }
            </tbody>
          </table>
        </div>
      </div>

      <!-- Slide-out Drawer for Create / View Case (Matches reference structure) -->
      @if (drawerOpen()) {
        <div class="tl-drawer-backdrop" (click)="closeDrawer()">
          <aside
            class="tl-drawer"
            data-testid="submit-aligner-modal"
            (click)="$event.stopPropagation()"
          >
            <header class="tl-drawer-header">
              <h2>{{ editingOrderId() ? 'Aligner Case Details & 3D Setup' : 'Create Clear Aligner Case' }}</h2>
              <button type="button" class="tl-close-btn" (click)="closeDrawer()">&times;</button>
            </header>

            <div class="tl-drawer-body">
              <!-- Section 1: Basic Information -->
              <section class="tl-form-section">
                <h3 class="tl-section-heading">Basic Information</h3>
                <div class="tl-form-grid-2">
                  <label class="tl-field">
                    <span>Select Doctor *</span>
                    <select
                      class="tl-input"
                      [ngModel]="draftDoctorId()"
                      [disabled]="editingOrderId() !== null"
                      (ngModelChange)="draftDoctorId.set($event)"
                    >
                      <option value="" disabled>Select doctor</option>
                      @for (doctor of doctors(); track doctor.id) {
                        <option [value]="doctor.id">{{ doctor.name }}</option>
                      }
                    </select>
                  </label>

                  <label class="tl-field">
                    <span>Select Patient *</span>
                    <select
                      class="tl-input"
                      [ngModel]="draftPatientId()"
                      [disabled]="editingOrderId() !== null"
                      (ngModelChange)="draftPatientId.set($event)"
                    >
                      <option value="" disabled>Select patient</option>
                      @for (patient of patients(); track patient.id) {
                        <option [value]="patient.id">{{ patient.displayName }}</option>
                      }
                    </select>
                  </label>

                  <label class="tl-field">
                    <span>Case Type *</span>
                    <select
                      class="tl-input"
                      [ngModel]="draftCaseType()"
                      [disabled]="editingOrderId() !== null"
                      (ngModelChange)="draftCaseType.set($event)"
                    >
                      <option value="Clear Aligner (Dual Arch)">Clear Aligner (Dual Arch)</option>
                      <option value="Clear Aligner (Upper Arch Only)">Clear Aligner (Upper Arch Only)</option>
                      <option value="Clear Aligner (Lower Arch Only)">Clear Aligner (Lower Arch Only)</option>
                      <option value="Express Aesthetic 12-Step Series">Express Aesthetic 12-Step Series</option>
                      <option value="Class II Deep Bite Aligner Series">Class II Deep Bite Aligner Series</option>
                      <option value="Clear Aligner Refinement">Clear Aligner Refinement</option>
                    </select>
                  </label>

                  <label class="tl-field">
                    <span>Status *</span>
                    <select
                      class="tl-input"
                      [ngModel]="draftStatus()"
                      (ngModelChange)="draftStatus.set($event)"
                    >
                      <option value="Sent to Lab">Sent to Lab</option>
                      <option value="In Production">In Production</option>
                      <option value="Quality Check">Quality Check</option>
                      <option value="Completed">Completed</option>
                      <option value="On Hold">On Hold</option>
                    </select>
                  </label>
                </div>
              </section>

              <!-- Section 2: Impression / Scan Source -->
              <section class="tl-form-section">
                <h3 class="tl-section-heading">Impression &amp; Intraoral Scan</h3>
                <div class="tl-form-grid-2">
                  <label class="tl-field">
                    <span>Impression / Scan Type *</span>
                    <select
                      class="tl-input"
                      [ngModel]="draftImpressionType()"
                      [disabled]="editingOrderId() !== null"
                      (ngModelChange)="draftImpressionType.set($event)"
                    >
                      <option value="Digital Intraoral Scan (STL) (A2)">Digital Intraoral Scan (STL) (A2)</option>
                      <option value="3Shape TRIOS Direct STL">3Shape TRIOS Direct STL</option>
                      <option value="iTero Element Cloud Sync">iTero Element Cloud Sync</option>
                      <option value="Medit i700 Cloud Export">Medit i700 Cloud Export</option>
                      <option value="PVS Elastomeric Putty/Wash (B1)">PVS Elastomeric Putty/Wash (B1)</option>
                    </select>
                  </label>

                  <label class="tl-field">
                    <span>Attachment Shade *</span>
                    <select
                      class="tl-input"
                      [ngModel]="draftShade()"
                      [disabled]="editingOrderId() !== null"
                      (ngModelChange)="draftShade.set($event)"
                    >
                      <option value="A1">A1</option>
                      <option value="A2">A2</option>
                      <option value="A3">A3</option>
                      <option value="B1">B1</option>
                      <option value="BL2 (Bleach)">BL2 (Bleach)</option>
                    </select>
                  </label>
                </div>
              </section>

              <!-- Section 3: 3D Staging & Biomechanics -->
              <section class="tl-form-section">
                <h3 class="tl-section-heading">3D Orthodontic Staging</h3>
                <div class="tl-form-grid-2">
                  <label class="tl-field">
                    <span>Upper Arch Steps</span>
                    <input
                      type="number"
                      min="1"
                      max="60"
                      class="tl-input"
                      [disabled]="editingOrderId() !== null"
                      [ngModel]="draftUpperSteps()"
                      (ngModelChange)="draftUpperSteps.set($event)"
                    />
                  </label>
                  <label class="tl-field">
                    <span>Lower Arch Steps</span>
                    <input
                      type="number"
                      min="1"
                      max="60"
                      class="tl-input"
                      [disabled]="editingOrderId() !== null"
                      [ngModel]="draftLowerSteps()"
                      (ngModelChange)="draftLowerSteps.set($event)"
                    />
                  </label>
                  <label class="tl-field">
                    <span>IPR Summary</span>
                    <input
                      type="text"
                      class="tl-input"
                      placeholder="e.g. 0.2mm anterior segment"
                      [disabled]="editingOrderId() !== null"
                      [ngModel]="draftIpr()"
                      (ngModelChange)="draftIpr.set($event)"
                    />
                  </label>
                  <label class="tl-field">
                    <span>Attachments Count</span>
                    <input
                      type="number"
                      min="0"
                      max="32"
                      class="tl-input"
                      [disabled]="editingOrderId() !== null"
                      [ngModel]="draftAttachments()"
                      (ngModelChange)="draftAttachments.set($event)"
                    />
                  </label>
                </div>
              </section>

              <!-- Section 4: Digital Uploads -->
              <section class="tl-form-section">
                <h3 class="tl-section-heading">Intraoral Scans (STL / Photos)</h3>
                <div class="tl-upload-grid">
                  <label class="tl-upload-box">
                    <span class="tl-upload-title">Maxilla (Upper Arch STL)</span>
                    <input type="file" [disabled]="editingOrderId() !== null" (change)="onFileSelected('Upper Arch STL', $event)" />
                  </label>
                  <label class="tl-upload-box">
                    <span class="tl-upload-title">Mandible (Lower Arch STL)</span>
                    <input type="file" [disabled]="editingOrderId() !== null" (change)="onFileSelected('Lower Arch STL', $event)" />
                  </label>
                  <label class="tl-upload-box">
                    <span class="tl-upload-title">Centric Occlusion Bite Scan</span>
                    <input type="file" multiple [disabled]="editingOrderId() !== null" (change)="onFileSelected('Buccal Bite Scan', $event)" />
                  </label>
                </div>
                @if (draftFiles().length > 0) {
                  <div class="tl-uploaded-pills">
                    @for (f of draftFiles(); track f) {
                      <span class="tl-file-pill">{{ f }}</span>
                    }
                  </div>
                }
              </section>

              <!-- Section 5: Rx Instructions -->
              <section class="tl-form-section">
                <h3 class="tl-section-heading">Rx Clinical Instructions</h3>
                <label class="tl-field">
                  <span>Prescription Notes for Ortho CAD Engineers</span>
                  <textarea
                    rows="3"
                    class="tl-textarea"
                    placeholder="Provide specific notes regarding teeth not to move, midline correction, and IPR restrictions..."
                    [disabled]="editingOrderId() !== null"
                    [ngModel]="draftRxNotes()"
                    (ngModelChange)="draftRxNotes.set($event)"
                  ></textarea>
                </label>
              </section>
            </div>

            <footer class="tl-drawer-footer">
              <button type="button" class="tl-btn tl-btn-outline" (click)="closeDrawer()">
                Cancel
              </button>
              <button
                type="button"
                class="tl-btn tl-btn-teal"
                data-testid="confirm-submit-aligner-btn"
                (click)="saveAlignerCase()"
              >
                Save Aligner Report
              </button>
            </footer>
          </aside>
        </div>
      }
    </div>
  `,
  styles: [
    `
      .tl-page {
        padding: 12px 18px;
        max-width: 1480px;
        margin: 0 auto;
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
        color: var(--tl-teal, #26c6da);
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
        box-shadow: var(--shadow-soft, 0 1px 3px rgba(0, 0, 0, 0.05));
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
      .tl-search-box {
        width: 320px;
      }
      .tl-search-box .tl-input {
        width: 100%;
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
        resize: vertical;
      }
      .tl-btn {
        height: 38px;
        padding: 0 18px;
        border-radius: 6px;
        font-size: 13px;
        font-weight: 700;
        cursor: pointer;
        border: 1px solid transparent;
        display: inline-flex;
        align-items: center;
        gap: 6px;
        transition: all 0.15s ease;
      }
      .tl-btn-teal {
        background: var(--tl-teal, #26c6da);
        color: #ffffff;
      }
      .tl-btn-teal:hover {
        opacity: 0.92;
        transform: translateY(-1px);
      }
      .tl-btn-outline {
        background: var(--surf2);
        border-color: var(--line);
        color: var(--ink);
      }
      .tl-btn-outline:hover {
        background: var(--line-soft);
      }
      .tl-toast-banner {
        display: flex;
        align-items: center;
        justify-content: space-between;
        background: var(--tl-dark, #0b1320);
        color: #ffffff;
        padding: 10px 16px;
        border-radius: 6px;
        margin-bottom: 14px;
        font-size: 13px;
        border: 1px solid var(--line);
      }
      .tl-toast-dismiss {
        background: transparent;
        border: none;
        color: #26c6da;
        font-weight: 700;
        cursor: pointer;
      }

      /* Exact Solid Teal Table Header Banner matching user screenshot */
      .tl-table-wrap {
        overflow-x: auto;
        border-radius: 8px;
        border: 1px solid var(--line);
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
        vertical-align: middle;
      }
      .tl-table tbody tr:hover {
        background: var(--surf2);
      }
      .tl-empty-cell {
        text-align: center;
        padding: 32px !important;
        color: var(--mute);
      }
      .tl-mono {
        font-family: var(--fe-font-mono, monospace);
        font-weight: 700;
        white-space: nowrap;
      }
      .tl-created-cell {
        white-space: nowrap;
        font-size: 12px;
        color: var(--mute);
      }
      .tl-teeth-summary {
        display: inline-block;
        padding: 4px 10px;
        border-radius: 4px;
        background: var(--surf2);
        border: 1px solid var(--line);
        font-size: 12px;
        font-weight: 600;
        white-space: nowrap;
      }
      .tl-status-badge {
        display: inline-block;
        padding: 4px 12px;
        border-radius: 999px;
        font-size: 11.5px;
        font-weight: 700;
        background: rgba(45, 212, 191, 0.15);
        color: #2dd4bf;
      }
      .tl-status-badge.is-completed {
        background: rgba(34, 197, 94, 0.15);
        color: #4ade80;
      }
      .tl-status-badge.is-prod {
        background: rgba(234, 179, 8, 0.18);
        color: #facc15;
      }
      .tl-status-badge.is-hold {
        background: rgba(245, 158, 11, 0.18);
        color: #fbbf24;
      }
      .tl-row-actions {
        display: flex;
        align-items: center;
        gap: 6px;
      }
      .tl-icon-btn {
        width: 32px;
        height: 32px;
        border-radius: 6px;
        border: 1px solid var(--line);
        background: var(--surf2);
        cursor: pointer;
        display: inline-flex;
        align-items: center;
        justify-content: center;
        color: var(--ink);
        transition: all 0.15s ease;
      }
      .tl-icon-btn:hover {
        background: var(--line-soft);
      }
      .tl-icon-btn.is-teal {
        background: var(--tl-teal, #26c6da);
        color: #ffffff;
        border-color: var(--tl-teal, #26c6da);
      }
      .tl-icon-btn.is-teal:hover {
        opacity: 0.9;
        transform: translateY(-1px);
      }
      .tl-icon-btn.is-danger {
        color: #ef4444;
        border-color: rgba(239, 68, 68, 0.35);
      }
      .tl-icon-btn.is-danger:hover {
        background: rgba(239, 68, 68, 0.12);
      }

      /* Slide-out Drawer */
      .tl-drawer-backdrop {
        position: fixed;
        inset: 0;
        background: var(--scrim, rgba(0, 0, 0, 0.55));
        z-index: 1200;
        display: flex;
        justify-content: flex-end;
        backdrop-filter: blur(2px);
      }
      .tl-drawer {
        width: min(760px, 95vw);
        height: 100vh;
        background: var(--surf);
        display: flex;
        flex-direction: column;
        box-shadow: var(--shadow-panel, -4px 0 24px rgba(0,0,0,0.25));
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
        font-weight: 700;
        color: var(--ink);
      }
      .tl-close-btn {
        border: none;
        background: transparent;
        font-size: 24px;
        cursor: pointer;
        color: var(--mute);
      }
      .tl-close-btn:hover {
        color: var(--ink);
      }
      .tl-drawer-body {
        padding: 20px 24px;
        overflow-y: auto;
        flex: 1;
        display: flex;
        flex-direction: column;
        gap: 20px;
      }
      .tl-form-section {
        border: 1px solid var(--line);
        border-radius: 8px;
        padding: 16px;
        background: var(--surf);
      }
      .tl-section-heading {
        margin: 0 0 14px 0;
        font-size: 13.5px;
        font-weight: 700;
        color: var(--tl-teal, #26c6da);
        text-transform: uppercase;
        letter-spacing: 0.03em;
      }
      .tl-form-grid-2 {
        display: grid;
        grid-template-columns: repeat(2, 1fr);
        gap: 14px;
      }
      @media (max-width: 600px) {
        .tl-form-grid-2 {
          grid-template-columns: 1fr;
        }
      }
      .tl-field {
        display: flex;
        flex-direction: column;
        gap: 6px;
        font-size: 12.5px;
        font-weight: 600;
        color: var(--ink);
      }
      .tl-upload-grid {
        display: grid;
        grid-template-columns: repeat(3, 1fr);
        gap: 12px;
      }
      @media (max-width: 700px) {
        .tl-upload-grid {
          grid-template-columns: 1fr;
        }
      }
      .tl-upload-box {
        border: 1px dashed var(--line);
        border-radius: 6px;
        padding: 16px 12px;
        text-align: center;
        background: var(--surf2);
        cursor: pointer;
        display: flex;
        flex-direction: column;
        gap: 6px;
        font-size: 12px;
        color: var(--mute);
      }
      .tl-upload-box:hover {
        border-color: var(--tl-teal, #26c6da);
        color: var(--tl-teal, #26c6da);
      }
      .tl-upload-box input {
        display: none;
      }
      .tl-upload-title {
        font-weight: 700;
        color: var(--ink);
      }
      .tl-uploaded-pills {
        display: flex;
        gap: 6px;
        flex-wrap: wrap;
        margin-top: 10px;
      }
      .tl-file-pill {
        font-size: 11px;
        padding: 3px 8px;
        border-radius: 4px;
        background: var(--line-soft);
        color: var(--ink);
      }
      .tl-drawer-footer {
        padding: 16px 24px;
        border-top: 1px solid var(--line);
        display: flex;
        justify-content: flex-end;
        gap: 10px;
        background: var(--surf);
      }
    `,
  ],
})
export class ClinicAlignersComponent implements OnInit {
  private readonly api = inject(ClinicPatientApi);
  private readonly clinic = inject(ClinicApi);
  private readonly projects = inject(ProjectContextService);

  protected readonly orders = signal<readonly AlignerCaseRecord[]>([]);
  protected readonly doctors = signal<readonly ClinicDoctor[]>([]);
  protected readonly patients = signal<readonly ClinicPatient[]>([]);
  protected readonly loading = signal(false);
  protected readonly saveBusy = signal(false);

  protected readonly searchQuery = signal('');
  protected readonly bulkAction = signal('');
  protected readonly selectedIds = signal<ReadonlySet<string>>(new Set());
  protected readonly bannerMessage = signal<string | null>(null);

  protected readonly drawerOpen = signal(false);
  protected readonly editingOrderId = signal<string | null>(null);

  // Drawer Form Signals
  protected readonly draftDoctorId = signal('');
  protected readonly draftPatientId = signal('');
  protected readonly draftCaseType = signal('Clear Aligner (Dual Arch)');
  protected readonly draftStatus = signal<AlignerCaseRecord['status']>('Sent to Lab');
  protected readonly draftImpressionType = signal('Digital Intraoral Scan (STL)');
  protected readonly draftShade = signal('A2');
  protected readonly draftUpperSteps = signal(18);
  protected readonly draftLowerSteps = signal(16);
  protected readonly draftIpr = signal('0.2mm anterior segment');
  protected readonly draftAttachments = signal(8);
  protected readonly draftRxNotes = signal('');
  protected readonly draftFiles = signal<readonly string[]>([]);

  protected readonly filteredOrders = computed<readonly AlignerCaseRecord[]>(() => {
    const q = this.searchQuery().trim().toLowerCase();
    const all = this.orders();
    if (!q) return all;
    return all.filter((r) =>
      r.id.toLowerCase().includes(q) ||
      r.doctorName.toLowerCase().includes(q) ||
      r.patientName.toLowerCase().includes(q) ||
      r.caseType.toLowerCase().includes(q) ||
      r.status.toLowerCase().includes(q) ||
      r.teethSummary.toLowerCase().includes(q),
    );
  });

  protected readonly allSelected = computed(() => {
    const rows = this.filteredOrders();
    const sel = this.selectedIds();
    return rows.length > 0 && rows.every((r) => sel.has(r.id));
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
      const [cases, doctors, patients] = await Promise.all([
        this.api.alignerCases(query),
        this.clinic.doctors(query),
        this.api.patients(query),
      ]);
      this.orders.set(cases.map((row) => this.toRecord(row)));
      this.doctors.set(doctors.items ?? []);
      this.patients.set(patients);
    } catch {
      this.bannerMessage.set('Could not load the aligner pipeline. Check the clinic connection.');
    } finally {
      this.loading.set(false);
    }
  }

  private toRecord(row: ClinicAlignerCase): AlignerCaseRecord {
    return {
      id: row.id,
      patientId: row.patientId,
      doctorId: row.doctorId ?? null,
      doctorName: row.doctorName ?? '—',
      patientName: row.patientName ?? '—',
      caseType: row.caseType,
      impressionType: row.impressionType ?? '',
      shade: row.shade ?? '',
      status: ALIGNER_STATUS_LABEL[row.status] ?? 'Sent to Lab',
      createdAt: formatTimestamp(row.createdAt),
      upperSteps: row.upperSteps,
      lowerSteps: row.lowerSteps,
      teethSummary:
        row.teethSummary ?? `#13-#23 (${row.upperSteps}U / ${row.lowerSteps}L)`,
      iprSummary: row.iprSummary ?? 'None',
      attachmentsCount: row.attachmentsCount,
      rxNotes: row.rxNotes ?? '',
      files: row.files ?? [],
    };
  }

  protected toggleRowSelection(id: string, checked: boolean): void {
    this.selectedIds.update((set) => {
      const next = new Set(set);
      if (checked) next.add(id);
      else next.delete(id);
      return next;
    });
  }

  protected toggleSelectAll(checked: boolean): void {
    if (checked) {
      this.selectedIds.set(new Set(this.filteredOrders().map((r) => r.id)));
    } else {
      this.selectedIds.set(new Set());
    }
  }

  protected async applyBulkAction(): Promise<void> {
    const act = this.bulkAction();
    const sel = Array.from(this.selectedIds());
    if (!act || sel.length === 0) return;

    if (act === 'DELETE') {
      let deleted = 0;
      for (const id of sel) {
        try {
          await this.api.deleteAlignerCase(id, this.projectQuery());
          deleted += 1;
        } catch {
          // A refused row does not abort the batch; the count reports what moved.
        }
      }
      await this.load();
      this.bannerMessage.set(`Deleted ${deleted} of ${sel.length} aligner case(s).`);
    } else {
      const status = act === 'COMPLETED' ? 'Completed' : 'In Production';
      let moved = 0;
      for (const id of sel) {
        const updated = await this.applyStatus(id, status);
        if (updated) moved += 1;
      }
      this.bannerMessage.set(`Moved ${moved} of ${sel.length} aligner case(s) to ${status}.`);
    }
    this.selectedIds.set(new Set());
    this.bulkAction.set('');
  }

  protected exportCsv(): void {
    const rows = this.filteredOrders();
    const csvContent =
      'ID,Doctor,Patient,CaseType,ImpressionType,Teeth,Status,Created\n' +
      rows
        .map(
          (r) =>
            `"${r.id}","${r.doctorName}","${r.patientName}","${r.caseType}","${r.impressionType}","${r.teethSummary}","${r.status}","${r.createdAt}"`,
        )
        .join('\n');

    const blob = new Blob([csvContent], { type: 'text/csv;charset=utf-8;' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `clear_aligner_reports_${Date.now()}.csv`;
    link.click();
    URL.revokeObjectURL(url);
    this.bannerMessage.set('Exported clear aligner cases to CSV.');
  }

  protected openCreateDrawer(): void {
    this.editingOrderId.set(null);
    this.draftDoctorId.set('');
    this.draftPatientId.set('');
    this.draftCaseType.set('Clear Aligner (Dual Arch)');
    this.draftStatus.set('Sent to Lab');
    this.draftImpressionType.set('Digital Intraoral Scan (STL)');
    this.draftShade.set('A2');
    this.draftUpperSteps.set(18);
    this.draftLowerSteps.set(16);
    this.draftIpr.set('0.2mm anterior segment');
    this.draftAttachments.set(8);
    this.draftRxNotes.set('');
    this.draftFiles.set([]);
    this.drawerOpen.set(true);
  }

  protected viewOrder(row: AlignerCaseRecord): void {
    this.editingOrderId.set(row.id);
    this.draftDoctorId.set(row.doctorId ?? '');
    this.draftPatientId.set(row.patientId ?? '');
    this.draftCaseType.set(row.caseType);
    this.draftStatus.set(row.status);
    this.draftImpressionType.set(row.impressionType);
    this.draftShade.set(row.shade);
    this.draftUpperSteps.set(row.upperSteps);
    this.draftLowerSteps.set(row.lowerSteps);
    this.draftIpr.set(row.iprSummary);
    this.draftAttachments.set(row.attachmentsCount);
    this.draftRxNotes.set(row.rxNotes);
    this.draftFiles.set([...row.files]);
    this.drawerOpen.set(true);
  }

  protected closeDrawer(): void {
    this.drawerOpen.set(false);
  }

  protected async deleteOrder(id: string): Promise<void> {
    try {
      await this.api.deleteAlignerCase(id, this.projectQuery());
      this.orders.update((list) => list.filter((r) => r.id !== id));
      this.bannerMessage.set(`Deleted aligner case ${shortRef(id)}.`);
    } catch {
      this.bannerMessage.set('Only a clinic administrator can delete an aligner case.');
    }
  }

  protected printOrder(_row: AlignerCaseRecord): void {
    window.print();
  }

  protected onFileSelected(label: string, event: Event): void {
    if (this.editingOrderId() !== null) return;
    const input = event.target as HTMLInputElement;
    if (input.files && input.files.length > 0) {
      const fileName = input.files[0].name;
      this.draftFiles.update((files) => [...files, `${label}: ${fileName}`]);
    }
  }

  protected async saveAlignerCase(): Promise<void> {
    if (this.saveBusy()) return;
    const editId = this.editingOrderId();
    if (editId) {
      await this.applyStatus(editId, this.draftStatus());
      this.drawerOpen.set(false);
      return;
    }

    const patientId = this.draftPatientId();
    if (!patientId) {
      this.bannerMessage.set('Choose a patient before saving the aligner case.');
      return;
    }
    this.saveBusy.set(true);
    try {
      const created = await this.api.createAlignerCase(
        {
          patientId,
          doctorId: this.draftDoctorId() || null,
          caseType: this.draftCaseType(),
          impressionType: this.draftImpressionType(),
          shade: this.draftShade(),
          upperSteps: this.draftUpperSteps(),
          lowerSteps: this.draftLowerSteps(),
          teethSummary: `#13-#23 (${this.draftUpperSteps()}U / ${this.draftLowerSteps()}L)`,
          iprSummary: this.draftIpr(),
          attachmentsCount: this.draftAttachments(),
          rxNotes: this.draftRxNotes(),
          files: this.draftFiles(),
          ...this.projectQuery(),
        },
        this.projectQuery(),
      );
      let record = this.toRecord(created);
      if (this.draftStatus() !== 'Sent to Lab') {
        const moved = await this.api.updateAlignerCaseStatus(
          created.id,
          ALIGNER_STATUS_WIRE[this.draftStatus()],
          this.projectQuery(),
        );
        record = this.toRecord(moved);
      }
      this.orders.update((list) => [record, ...list]);
      this.bannerMessage.set(`Created clear aligner case ${shortRef(record.id)}.`);
      this.drawerOpen.set(false);
    } catch {
      this.bannerMessage.set('Could not save the aligner case. The server refused the request.');
    } finally {
      this.saveBusy.set(false);
    }
  }

  private async applyStatus(
    id: string,
    status: AlignerCaseRecord['status'],
  ): Promise<boolean> {
    const current = this.orders().find((row) => row.id === id);
    if (current?.status === status) return true;
    try {
      const updated = await this.api.updateAlignerCaseStatus(
        id,
        ALIGNER_STATUS_WIRE[status],
        this.projectQuery(),
      );
      this.orders.update((list) =>
        list.map((row) => (row.id === id ? this.toRecord(updated) : row)),
      );
      return true;
    } catch {
      this.bannerMessage.set(`Could not move case ${shortRef(id)} to ${status}.`);
      return false;
    }
  }
}

const ALIGNER_STATUS_LABEL: Record<string, AlignerCaseRecord['status']> = {
  SENT_TO_LAB: 'Sent to Lab',
  IN_PRODUCTION: 'In Production',
  QUALITY_CHECK: 'Quality Check',
  COMPLETED: 'Completed',
  ON_HOLD: 'On Hold',
};

const ALIGNER_STATUS_WIRE: Record<AlignerCaseRecord['status'], string> = {
  'Sent to Lab': 'SENT_TO_LAB',
  'In Production': 'IN_PRODUCTION',
  'Quality Check': 'QUALITY_CHECK',
  Completed: 'COMPLETED',
  'On Hold': 'ON_HOLD',
};

function shortRef(id: string): string {
  return id.length > 8 ? id.slice(0, 8) : id;
}

function formatTimestamp(value: string | null | undefined): string {
  if (!value) return '';
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return '';
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${parsed.getFullYear()}-${pad(parsed.getMonth() + 1)}-${pad(parsed.getDate())} ${pad(parsed.getHours())}:${pad(parsed.getMinutes())}`;
}
