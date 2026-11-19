import { CommonModule } from '@angular/common';
import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ClinicApi, type ClinicDoctor } from '../../core/api/endpoints';
import { ProjectContextService } from '../../core/context/project-context.service';
import {
  ClinicPatientApi,
  type ClinicLabOrder,
  type ClinicPatient,
  type CreateImagingLabPayload,
  type CreateImagingRequestPayload,
  type ImagingLabRow,
  type ImagingRequestRow,
  type ImagingSummaryAnalytics,
} from './clinic-patient.api';

export interface LabOrderRecord {
  readonly id: string;
  readonly patientId?: string;
  readonly doctorId?: string | null;
  readonly doctorName: string;
  readonly patientName: string;
  readonly caseType: string;
  readonly impressionType: string;
  readonly shade: string;
  readonly status: 'Sent to Lab' | 'In Production' | 'Quality Check' | 'Completed';
  readonly createdAt: string;
  readonly urTeeth: readonly number[];
  readonly ulTeeth: readonly number[];
  readonly lrTeeth: readonly number[];
  readonly llTeeth: readonly number[];
  readonly rxNotes: string;
  readonly files: readonly string[];
}

const UR_TEETH: readonly number[] = [18, 17, 16, 15, 14, 13, 12, 11];
const UL_TEETH: readonly number[] = [21, 22, 23, 24, 25, 26, 27, 28];
const LR_TEETH: readonly number[] = [48, 47, 46, 45, 44, 43, 42, 41];
const LL_TEETH: readonly number[] = [31, 32, 33, 34, 35, 36, 37, 38];

@Component({
  selector: 'fe-clinic-lab',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="tl-page" data-testid="clinic-lab">
      <!-- Breadcrumbs -->
      <div class="tl-breadcrumbs">
        <a routerLink="/app/dashboard">Home</a>
        <span>»</span>
        <span class="active">Lab &amp; Dental Imaging</span>
      </div>

      <nav class="tl-tabs-nav" aria-label="Lab and Imaging Sections">
        <button
          type="button"
          class="tl-tab-btn"
          [class.tl-tab-btn--active]="activeLabTab() === 'prostheticOrders'"
          (click)="switchLabTab('prostheticOrders')"
        >
          Prosthetic Lab Orders ({{ orders().length }})
        </button>
        <button
          type="button"
          class="tl-tab-btn"
          [class.tl-tab-btn--active]="activeLabTab() === 'imagingRequests'"
          (click)="switchLabTab('imagingRequests')"
        >
          Dental Imaging &amp; X-Rays ({{ imagingRequestsList().length }})
        </button>
        <button
          type="button"
          class="tl-tab-btn"
          [class.tl-tab-btn--active]="activeLabTab() === 'imagingLabs'"
          (click)="switchLabTab('imagingLabs')"
        >
          Preferred External Imaging Labs ({{ imagingLabsList().length }})
        </button>
      </nav>

      @if (activeLabTab() === 'prostheticOrders') {
      <div class="tl-card">
        <!-- Toolbar matching 14_lab_list_220s.png -->
        <div class="tl-toolbar">
          <div class="tl-toolbar-left">
            <select
              class="tl-select"
              [value]="bulkAction()"
              (change)="bulkAction.set($any($event.target).value)"
              aria-label="Bulk Action"
            >
              <option value="">No Action</option>
              <option value="SENT">Mark Sent to Lab</option>
              <option value="PRODUCTION">Mark In Production</option>
              <option value="QC">Mark Quality Check</option>
              <option value="COMPLETED">Mark Completed</option>
              <option value="DELETE">Delete Selected</option>
            </select>
            <button type="button" class="tl-btn tl-btn-teal" (click)="applyBulkAction()">
              Apply
            </button>
            <button type="button" class="tl-btn tl-btn-outline" (click)="exportCsv()">
              Export
            </button>
          </div>

          <div class="tl-toolbar-right">
            <div class="tl-search-box">
              <input
                type="search"
                class="tl-input"
                placeholder="Search lab report, doctor, patient..."
                [value]="searchQuery()"
                (input)="searchQuery.set($any($event.target).value)"
              />
            </div>
            <button
              type="button"
              class="tl-btn tl-btn-teal"
              data-testid="lab-open-create"
              (click)="openCreateDrawer()"
            >
              + New
            </button>
          </div>
        </div>

        @if (bannerMessage(); as msg) {
          <div class="tl-toast-banner" role="status">
            <span>{{ msg }}</span>
            <button type="button" class="tl-toast-dismiss" (click)="bannerMessage.set(null)">DISMISS</button>
          </div>
        }

        <!-- Lab Orders Table -->
        <div class="tl-table-wrap">
          <table class="tl-table">
            <thead>
              <tr>
                <th style="width: 38px;">
                  <input
                    type="checkbox"
                    [checked]="allSelected()"
                    (change)="toggleSelectAll($any($event.target).checked)"
                    aria-label="Select all lab rows"
                  />
                </th>
                <th>ID</th>
                <th>Doctor</th>
                <th>Patient</th>
                <th>Case Type</th>
                <th>Impression Type</th>
                <th>Teeth (FDI)</th>
                <th>Status</th>
                <th>Created</th>
                <th>Action</th>
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
                      <input
                        type="checkbox"
                        [checked]="selectedIds().has(row.id)"
                        (change)="toggleRowSelection(row.id, $any($event.target).checked)"
                      />
                    </td>
                    <td class="tl-mono">{{ row.id }}</td>
                    <td>{{ row.doctorName }}</td>
                    <td><strong>{{ row.patientName }}</strong></td>
                    <td>{{ row.caseType }}</td>
                    <td>{{ row.impressionType }} ({{ row.shade }})</td>
                    <td>
                      <span class="tl-teeth-summary">
                        {{ formatRowTeeth(row) }}
                      </span>
                    </td>
                    <td>
                      <select
                        class="tl-status-select"
                        [class.is-completed]="row.status === 'Completed'"
                        [class.is-prod]="row.status === 'In Production'"
                        [class.is-qc]="row.status === 'Quality Check'"
                        [class.is-sent]="row.status === 'Sent to Lab'"
                        [value]="row.status"
                        (change)="updateOrderStatus(row.id, $any($event.target).value)"
                        aria-label="Change lab order status"
                      >
                        <option value="Sent to Lab">Sent to Lab</option>
                        <option value="In Production">In Production</option>
                        <option value="Quality Check">Quality Check</option>
                        <option value="Completed">Completed</option>
                      </select>
                    </td>
                    <td>{{ row.createdAt }}</td>
                    <td>
                      <div class="tl-row-actions">
                        <button
                          type="button"
                          class="tl-icon-btn is-teal"
                          title="View / Edit Lab Rx"
                          (click)="viewOrder(row)"
                        >
                          <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true" focusable="false">
                            <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"></path>
                            <circle cx="12" cy="12" r="3"></circle>
                          </svg>
                        </button>
                        <button
                          type="button"
                          class="tl-icon-btn"
                          title="Print Lab Sheet"
                          (click)="printOrder(row)"
                        >
                          <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true" focusable="false">
                            <polyline points="6 9 6 2 18 2 18 9"></polyline>
                            <path d="M6 18H4a2 2 0 0 1-2-2v-5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v5a2 2 0 0 1-2 2h-2"></path>
                            <rect x="6" y="14" width="12" height="8"></rect>
                          </svg>
                        </button>
                        <button
                          type="button"
                          class="tl-icon-btn is-danger"
                          title="Delete Lab Order"
                          (click)="deleteOrder(row.id)"
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
      }

      @if (activeLabTab() === 'imagingRequests') {
        @if (imagingAnalytics(); as stats) {
          <div style="display:grid;grid-template-columns:repeat(auto-fit,minmax(160px,1fr));gap:10px;">
            <div class="tl-card" style="padding:10px 14px;">
              <div style="font-size:11px;color:var(--mute);">Imaging Orders</div>
              <strong style="font-size:18px;">{{ stats.totalRequests }}</strong>
            </div>
            <div class="tl-card" style="padding:10px 14px;">
              <div style="font-size:11px;color:var(--mute);">Procedures Count</div>
              <strong style="font-size:18px;">{{ stats.totalProceduresCount }}</strong>
            </div>
            <div class="tl-card" style="padding:10px 14px;">
              <div style="font-size:11px;color:var(--mute);">Lab / Scan Cost</div>
              <strong style="font-size:18px;color:#c62828;">{{ stats.totalCostToClinic | number:'1.3-3' }} JOD</strong>
            </div>
            <div class="tl-card" style="padding:10px 14px;">
              <div style="font-size:11px;color:var(--mute);">Patient Charges</div>
              <strong style="font-size:18px;color:#0f766e;">{{ stats.totalChargedToPatient | number:'1.3-3' }} JOD</strong>
            </div>
            <div class="tl-card" style="padding:10px 14px;">
              <div style="font-size:11px;color:var(--mute);">Net Imaging Profit</div>
              <strong style="font-size:18px;" [style.color]="stats.netProfit >= 0 ? '#2e7d32' : '#c62828'">
                {{ stats.netProfit | number:'1.3-3' }} JOD
              </strong>
            </div>
          </div>
        }

        <div class="tl-card">
          <div class="tl-toolbar">
            <div class="tl-toolbar-left">
              <select
                class="tl-select"
                [ngModel]="imgTypeFilter()"
                (ngModelChange)="imgTypeFilter.set($event); loadImagingRequests()"
              >
                <option value="">All Modalities</option>
                <option value="CBCT">3D CBCT</option>
                <option value="PANORAMIC">Panoramic (OPG)</option>
                <option value="PERIAPICAL">Periapical X-Ray</option>
                <option value="BITEWING">Bitewing</option>
                <option value="CEPHALOMETRIC">Cephalometric</option>
                <option value="OTHER">Other Imaging</option>
              </select>
              <select
                class="tl-select"
                [ngModel]="imgStatusFilter()"
                (ngModelChange)="imgStatusFilter.set($event); loadImagingRequests()"
              >
                <option value="">All Statuses</option>
                <option value="REQUESTED">Requested / Referred</option>
                <option value="IN_PROGRESS">In Progress</option>
                <option value="COMPLETED">Completed</option>
                <option value="CANCELLED">Cancelled</option>
              </select>
            </div>
            <div class="tl-toolbar-right">
              <button type="button" class="tl-btn tl-btn-teal" (click)="openCreateImagingModal()">
                + New Dental Imaging Request
              </button>
            </div>
          </div>

          <div class="tl-table-wrap">
            <table class="tl-table">
              <thead>
                <tr>
                  <th>Request Date</th>
                  <th>Patient &amp; Doctor</th>
                  <th>Modality / Count</th>
                  <th>External Lab</th>
                  <th>Clinic Cost</th>
                  <th>Patient Charge</th>
                  <th>Status</th>
                  <th>Cloud / DICOM</th>
                  <th>Actions</th>
                </tr>
              </thead>
              <tbody>
                @for (req of imagingRequestsList(); track req.id) {
                  <tr>
                    <td>
                      <strong>{{ req.requestDate }}</strong>
                      @if (req.completionDate) {
                        <div style="font-size:11px;color:#0f766e;">Done: {{ req.completionDate }}</div>
                      }
                    </td>
                    <td>
                      <strong>{{ req.patientName || 'Patient' }}</strong>
                      <div style="font-size:11px;color:var(--mute);">{{ req.doctorName || '—' }}</div>
                    </td>
                    <td>
                      <strong>{{ req.imagingType }}</strong> × {{ req.proceduresCount }}
                      @if (req.teethSummary) {
                        <div style="font-size:11px;color:var(--mute);">Teeth: {{ req.teethSummary }}</div>
                      }
                    </td>
                    <td>{{ req.labName || 'External Lab' }}</td>
                    <td style="color:#c62828;">{{ req.costToClinic | number:'1.3-3' }} JOD</td>
                    <td style="color:#0f766e;">{{ req.amountChargedToPatient | number:'1.3-3' }} JOD</td>
                    <td><span class="tl-pill">{{ req.status }}</span></td>
                    <td>
                      @if (req.cloudStorageUrl) {
                        <a [href]="req.cloudStorageUrl" target="_blank" rel="noopener" style="color:#00796b;font-weight:600;font-size:12px;">
                          Open Cloud Study ↗
                        </a>
                      } @else {
                        <span style="color:var(--mute);font-size:12px;">Pending upload</span>
                      }
                    </td>
                    <td>
                      <div style="display:flex;gap:4px;flex-wrap:wrap;">
                        <button type="button" class="tl-btn tl-btn-outline" style="padding:3px 7px;font-size:11px;" (click)="openEditImagingModal(req)">
                          Edit / Complete
                        </button>
                        <button type="button" class="tl-btn tl-btn-outline" style="padding:3px 7px;font-size:11px;" (click)="referralTarget.set(req)">
                          Referral
                        </button>
                        <button type="button" class="tl-btn tl-btn-outline" style="padding:3px 7px;font-size:11px;color:#c62828;" (click)="deleteImagingReq(req.id)">
                          Delete
                        </button>
                      </div>
                    </td>
                  </tr>
                } @empty {
                  <tr><td colspan="9" style="text-align:center;color:var(--mute);padding:24px;">No dental imaging requests yet</td></tr>
                }
              </tbody>
            </table>
          </div>
        </div>
      }

      @if (activeLabTab() === 'imagingLabs') {
        <div class="tl-card">
          <div class="tl-toolbar">
            <div class="tl-toolbar-left">
              <strong>Branch Preferred External Imaging Laboratories</strong>
            </div>
            <div class="tl-toolbar-right">
              <button type="button" class="tl-btn tl-btn-teal" (click)="openCreateLabModal()">
                + Add Preferred External Lab
              </button>
            </div>
          </div>

          <div class="tl-table-wrap">
            <table class="tl-table">
              <thead>
                <tr>
                  <th>Laboratory Name</th>
                  <th>Integration Mode</th>
                  <th>Phone</th>
                  <th>Email</th>
                  <th>Address</th>
                  <th>Cloud API URL</th>
                  <th>Actions</th>
                </tr>
              </thead>
              <tbody>
                @for (lab of imagingLabsList(); track lab.id) {
                  <tr>
                    <td><strong>{{ lab.name }}</strong></td>
                    <td><span class="tl-pill">{{ lab.integrationMode }}</span></td>
                    <td>{{ lab.contactPhone || '—' }}</td>
                    <td>{{ lab.contactEmail || '—' }}</td>
                    <td>{{ lab.address || '—' }}</td>
                    <td><small>{{ lab.apiEndpointUrl || '—' }}</small></td>
                    <td>
                      <div style="display:flex;gap:4px;">
                        <button type="button" class="tl-btn tl-btn-outline" style="padding:3px 7px;font-size:11px;" (click)="openEditLabModal(lab)">
                          Edit
                        </button>
                        <button type="button" class="tl-btn tl-btn-outline" style="padding:3px 7px;font-size:11px;color:#c62828;" (click)="deleteImagingLab(lab.id)">
                          Delete
                        </button>
                      </div>
                    </td>
                  </tr>
                } @empty {
                  <tr><td colspan="7" style="text-align:center;color:var(--mute);padding:24px;">No preferred external imaging labs configured for this branch</td></tr>
                }
              </tbody>
            </table>
          </div>
        </div>
      }

      <!-- Create Lab Report Drawer (Screenshot 15: 15_lab_report_create_teeth_260s.png) -->
      @if (drawerOpen()) {
        <div class="tl-drawer-backdrop" (click)="closeDrawer()">
          <aside
            class="tl-drawer"
            data-testid="create-lab-report-drawer"
            (click)="$event.stopPropagation()"
          >
            <header class="tl-drawer-header">
              <h2>{{ editingOrderId() ? 'Edit Lab Report' : 'Create Lab Report' }}</h2>
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
                      [value]="draftDoctorId()"
                      [disabled]="editingOrderId() !== null"
                      (change)="draftDoctorId.set($any($event.target).value)"
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
                      [value]="draftPatientId()"
                      [disabled]="editingOrderId() !== null"
                      (change)="draftPatientId.set($any($event.target).value)"
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
                      [value]="draftCaseType()"
                      [disabled]="editingOrderId() !== null"
                      (change)="draftCaseType.set($any($event.target).value)"
                    >
                      <option value="Zirconia Crown (Monolithic)">Zirconia Crown (Monolithic)</option>
                      <option value="PFM Crown & Bridge">PFM Crown & Bridge</option>
                      <option value="E-Max Porcelain Veneer">E-Max Porcelain Veneer</option>
                      <option value="Custom Implant Abutment + Crown">Custom Implant Abutment + Crown</option>
                      <option value="Acrylic Complete Denture">Acrylic Complete Denture</option>
                      <option value="Night Guard / Occlusal Splint">Night Guard / Occlusal Splint</option>
                    </select>
                  </label>

                  <label class="tl-field">
                    <span>Status *</span>
                    <select
                      class="tl-input"
                      [value]="draftStatus()"
                      (change)="draftStatus.set($any($event.target).value)"
                    >
                      <option value="Sent to Lab">Sent to Lab</option>
                      <option value="In Production">In Production</option>
                      <option value="Quality Check">Quality Check</option>
                      <option value="Completed">Completed</option>
                    </select>
                  </label>
                </div>
              </section>

              <!-- Section 2: Impression Types -->
              <section class="tl-form-section">
                <h3 class="tl-section-heading">Impression Types</h3>
                <div class="tl-form-grid-2">
                  <label class="tl-field">
                    <span>Impression Type *</span>
                    <select
                      class="tl-input"
                      [value]="draftImpressionType()"
                      [disabled]="editingOrderId() !== null"
                      (change)="draftImpressionType.set($any($event.target).value)"
                    >
                      <option value="Digital Intraoral Scan (STL)">Digital Intraoral Scan (STL)</option>
                      <option value="PVS Elastomeric Putty/Wash">PVS Elastomeric Putty/Wash</option>
                      <option value="Alginate Physical Impression">Alginate Physical Impression</option>
                      <option value="Polyether Implant Impression">Polyether Implant Impression</option>
                    </select>
                  </label>

                  <label class="tl-field">
                    <span>VITA Classical Shade *</span>
                    <select
                      class="tl-input"
                      [value]="draftShade()"
                      [disabled]="editingOrderId() !== null"
                      (change)="draftShade.set($any($event.target).value)"
                    >
                      <option value="A1">A1</option>
                      <option value="A2">A2</option>
                      <option value="A3">A3</option>
                      <option value="B1">B1</option>
                      <option value="B2">B2</option>
                      <option value="BL2 (Bleach)">BL2 (Bleach)</option>
                    </select>
                  </label>
                </div>
              </section>

              <!-- Section 3: Prosthodontic Uploads -->
              <section class="tl-form-section">
                <h3 class="tl-section-heading">Prosthodontic Uploads</h3>
                <div class="tl-upload-grid">
                  <label class="tl-upload-box">
                    <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" class="tl-upload-icon" aria-hidden="true">
                      <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/>
                      <polyline points="17 8 12 3 7 8"/>
                      <line x1="12" y1="3" x2="12" y2="15"/>
                    </svg>
                    <span class="tl-upload-title">Upper Arch Scan / STL</span>
                    <span class="tl-upload-sub">Click to select (.stl, .obj)</span>
                    <input type="file" accept=".stl,.obj,.ply,.zip" (change)="onFileSelected('Upper Arch STL', $event)" />
                  </label>
                  <label class="tl-upload-box">
                    <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" class="tl-upload-icon" aria-hidden="true">
                      <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/>
                      <polyline points="17 8 12 3 7 8"/>
                      <line x1="12" y1="3" x2="12" y2="15"/>
                    </svg>
                    <span class="tl-upload-title">Lower Arch Scan / STL</span>
                    <span class="tl-upload-sub">Click to select (.stl, .obj)</span>
                    <input type="file" accept=".stl,.obj,.ply,.zip" (change)="onFileSelected('Lower Arch STL', $event)" />
                  </label>
                  <label class="tl-upload-box">
                    <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" class="tl-upload-icon" aria-hidden="true">
                      <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/>
                      <polyline points="17 8 12 3 7 8"/>
                      <line x1="12" y1="3" x2="12" y2="15"/>
                    </svg>
                    <span class="tl-upload-title">Bite Registration / Photos</span>
                    <span class="tl-upload-sub">Click to select files (.stl, .jpg)</span>
                    <input type="file" multiple accept=".stl,.obj,.jpg,.jpeg,.png,.zip" (change)="onFileSelected('Clinical Bite', $event)" />
                  </label>
                </div>
                @if (draftFiles().length > 0) {
                  <div class="tl-uploaded-pills">
                    @for (f of draftFiles(); track f) {
                      <span class="tl-file-pill">
                        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" aria-hidden="true"><polyline points="20 6 9 17 4 12"/></svg>
                        <span>{{ f }}</span>
                        <button type="button" class="tl-file-remove" (click)="removeDraftFile(f, $event)" aria-label="Remove file">&times;</button>
                      </span>
                    }
                  </div>
                }
              </section>

              <!-- Section 4: Rx Instructions — Select Teeth for Treatment (Screenshot 15) -->
              <section class="tl-form-section">
                <h3 class="tl-section-heading">Rx Instructions — Select Teeth for Treatment</h3>

                <div class="tl-fdi-selector-card">
                  <!-- Upper Arch: UR (18-11) | UL (21-28) -->
                  <div class="tl-arch-row">
                    <div class="tl-quadrant-block">
                      <div class="tl-quadrant-title">UR (Upper Right)</div>
                      <div class="tl-teeth-row">
                        @for (tooth of urTeethList; track tooth) {
                          <button
                            type="button"
                            class="tl-tooth-circle"
                            [class.is-selected]="selectedUrTeeth().has(tooth)"
                            (click)="toggleTooth('UR', tooth)"
                          >
                            {{ tooth }}
                          </button>
                        }
                      </div>
                    </div>

                    <div class="tl-midline-divider"></div>

                    <div class="tl-quadrant-block">
                      <div class="tl-quadrant-title">UL (Upper Left)</div>
                      <div class="tl-teeth-row">
                        @for (tooth of ulTeethList; track tooth) {
                          <button
                            type="button"
                            class="tl-tooth-circle"
                            [class.is-selected]="selectedUlTeeth().has(tooth)"
                            (click)="toggleTooth('UL', tooth)"
                          >
                            {{ tooth }}
                          </button>
                        }
                      </div>
                    </div>
                  </div>

                  <div class="tl-horizontal-arch-divider"></div>

                  <!-- Lower Arch: LR (48-41) | LL (31-38) -->
                  <div class="tl-arch-row">
                    <div class="tl-quadrant-block">
                      <div class="tl-quadrant-title">LR (Lower Right)</div>
                      <div class="tl-teeth-row">
                        @for (tooth of lrTeethList; track tooth) {
                          <button
                            type="button"
                            class="tl-tooth-circle"
                            [class.is-selected]="selectedLrTeeth().has(tooth)"
                            (click)="toggleTooth('LR', tooth)"
                          >
                            {{ tooth }}
                          </button>
                        }
                      </div>
                    </div>

                    <div class="tl-midline-divider"></div>

                    <div class="tl-quadrant-block">
                      <div class="tl-quadrant-title">LL (Lower Left)</div>
                      <div class="tl-teeth-row">
                        @for (tooth of llTeethList; track tooth) {
                          <button
                            type="button"
                            class="tl-tooth-circle"
                            [class.is-selected]="selectedLlTeeth().has(tooth)"
                            (click)="toggleTooth('LL', tooth)"
                          >
                            {{ tooth }}
                          </button>
                        }
                      </div>
                    </div>
                  </div>
                </div>

                <!-- 4 Quadrant Teeth For Treatment Inputs matching Screenshot 15 -->
                <div class="tl-form-grid-2" style="margin-top: 14px;">
                  <label class="tl-field">
                    <span>UR — Teeth For Treatment</span>
                    <input
                      type="text"
                      class="tl-input"
                      readonly
                      [value]="formatQuadrantSet(selectedUrTeeth())"
                      placeholder="Click UR teeth above (18–11)"
                    />
                  </label>
                  <label class="tl-field">
                    <span>UL — Teeth For Treatment</span>
                    <input
                      type="text"
                      class="tl-input"
                      readonly
                      [value]="formatQuadrantSet(selectedUlTeeth())"
                      placeholder="Click UL teeth above (21–28)"
                    />
                  </label>
                  <label class="tl-field">
                    <span>LR — Teeth For Treatment</span>
                    <input
                      type="text"
                      class="tl-input"
                      readonly
                      [value]="formatQuadrantSet(selectedLrTeeth())"
                      placeholder="Click LR teeth above (48–41)"
                    />
                  </label>
                  <label class="tl-field">
                    <span>LL — Teeth For Treatment</span>
                    <input
                      type="text"
                      class="tl-input"
                      readonly
                      [value]="formatQuadrantSet(selectedLlTeeth())"
                      placeholder="Click LL teeth above (31–38)"
                    />
                  </label>
                </div>

                <label class="tl-field" style="margin-top: 12px;">
                  <span>Detailed Rx Instructions & Margin / Pontic Design</span>
                  <textarea
                    rows="3"
                    class="tl-textarea"
                    placeholder="Specify occlusal clearance, margin design (chamfer/shoulder), pontic type (modified ridge lap), and proximal contact tightness..."
                    [value]="draftRxNotes()"
                    (input)="draftRxNotes.set($any($event.target).value)"
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
                data-testid="save-lab-report-btn"
                (click)="saveLabReport()"
              >
                Save Lab Report
              </button>
            </footer>
          </aside>
        </div>
      }

      @if (imagingModalOpen()) {
        <div class="tl-modal-backdrop" (click)="imagingModalOpen.set(false)">
          <div class="tl-modal-card" (click)="$event.stopPropagation()">
            <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:12px;">
              <h3 style="margin:0;font-size:16px;">
                {{ editingImagingReq() ? 'Update / Complete Imaging Request' : 'New Dental Imaging Request' }}
              </h3>
              <button type="button" class="tl-btn tl-btn-outline" (click)="imagingModalOpen.set(false)">✕</button>
            </div>
            <div class="tl-form-grid-2">
              @if (!editingImagingReq()) {
                <label class="tl-field">
                  <span>Patient *</span>
                  <select class="tl-input" [(ngModel)]="imgFormPatientId">
                    <option value="">Select patient...</option>
                    @for (p of patients(); track p.id) {
                      <option [value]="p.id">{{ p.displayName }}</option>
                    }
                  </select>
                </label>
                <label class="tl-field">
                  <span>Requesting Doctor</span>
                  <select class="tl-input" [(ngModel)]="imgFormDoctorId">
                    <option value="">Unassigned</option>
                    @for (d of doctors(); track d.id) {
                      <option [value]="d.id">{{ d.name }}</option>
                    }
                  </select>
                </label>
                <label class="tl-field">
                  <span>Imaging Modality *</span>
                  <select class="tl-input" [(ngModel)]="imgFormType">
                    <option value="CBCT">3D CBCT</option>
                    <option value="PANORAMIC">Panoramic (OPG)</option>
                    <option value="PERIAPICAL">Periapical X-Ray</option>
                    <option value="BITEWING">Bitewing</option>
                    <option value="CEPHALOMETRIC">Cephalometric</option>
                    <option value="OTHER">Other</option>
                  </select>
                </label>
                <label class="tl-field">
                  <span>Procedures / Views Count</span>
                  <input class="tl-input" type="number" min="1" [(ngModel)]="imgFormCount" />
                </label>
              }
              <label class="tl-field">
                <span>Preferred External Lab</span>
                <select class="tl-input" [(ngModel)]="imgFormLabId" (ngModelChange)="onImgLabSelected($event)">
                  <option value="">Custom / Walk-in Lab</option>
                  @for (lab of imagingLabsList(); track lab.id) {
                    <option [value]="lab.id">{{ lab.name }}</option>
                  }
                </select>
              </label>
              <label class="tl-field">
                <span>Lab Name</span>
                <input class="tl-input" [(ngModel)]="imgFormLabName" placeholder="External imaging center" />
              </label>
              <label class="tl-field">
                <span>Cost to Clinic (JOD)</span>
                <input class="tl-input" type="number" min="0" step="0.001" [(ngModel)]="imgFormCost" />
              </label>
              <label class="tl-field">
                <span>Charge to Patient (JOD)</span>
                <input class="tl-input" type="number" min="0" step="0.001" [(ngModel)]="imgFormCharge" />
              </label>
              <label class="tl-field">
                <span>Status</span>
                <select class="tl-input" [(ngModel)]="imgFormStatus">
                  <option value="REQUESTED">Requested / Referred</option>
                  <option value="IN_PROGRESS">In Progress</option>
                  <option value="COMPLETED">Completed (Logs Expense &amp; Invoice)</option>
                  <option value="CANCELLED">Cancelled</option>
                </select>
              </label>
              <label class="tl-field">
                <span>Cloud Storage / DICOM URL</span>
                <input class="tl-input" [(ngModel)]="imgFormCloudUrl" placeholder="https://cloud-pacs.example.com/study/..." />
              </label>
              @if (!editingImagingReq()) {
                <label class="tl-field">
                  <span>Teeth / Arch Summary</span>
                  <input class="tl-input" [(ngModel)]="imgFormTeethSummary" placeholder="e.g., Mandible / #36-#38" />
                </label>
                <label class="tl-field">
                  <span>Clinical Indication</span>
                  <input class="tl-input" [(ngModel)]="imgFormIndication" placeholder="Implant assessment / Nerve proximity" />
                </label>
              }
              <label class="tl-field" style="grid-column:1 / -1;">
                <span>Radiologist Findings / Notes</span>
                <textarea class="tl-input" rows="2" [(ngModel)]="imgFormNotes"></textarea>
              </label>
            </div>
            <div style="display:flex;justify-content:flex-end;gap:8px;margin-top:14px;">
              <button type="button" class="tl-btn tl-btn-outline" (click)="imagingModalOpen.set(false)">Cancel</button>
              <button type="button" class="tl-btn tl-btn-teal" (click)="saveImagingRequest()">Save Request</button>
            </div>
          </div>
        </div>
      }

      @if (labModalOpen()) {
        <div class="tl-modal-backdrop" (click)="labModalOpen.set(false)">
          <div class="tl-modal-card" (click)="$event.stopPropagation()">
            <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:12px;">
              <h3 style="margin:0;font-size:16px;">{{ editingLab() ? 'Edit External Lab' : 'Add Preferred External Lab' }}</h3>
              <button type="button" class="tl-btn tl-btn-outline" (click)="labModalOpen.set(false)">✕</button>
            </div>
            <div class="tl-form-grid-2">
              <label class="tl-field">
                <span>Lab Name *</span>
                <input class="tl-input" [(ngModel)]="labFormName" placeholder="e.g., Carestream 3D Center" />
              </label>
              <label class="tl-field">
                <span>Integration Mode</span>
                <select class="tl-input" [(ngModel)]="labFormMode">
                  <option value="MANUAL">Manual Referral &amp; Upload</option>
                  <option value="DIRECT_CLOUD_API">Direct Cloud API Link</option>
                </select>
              </label>
              <label class="tl-field">
                <span>Phone</span>
                <input class="tl-input" [(ngModel)]="labFormPhone" />
              </label>
              <label class="tl-field">
                <span>Email</span>
                <input class="tl-input" [(ngModel)]="labFormEmail" />
              </label>
              <label class="tl-field" style="grid-column:1 / -1;">
                <span>Address</span>
                <input class="tl-input" [(ngModel)]="labFormAddress" />
              </label>
              <label class="tl-field" style="grid-column:1 / -1;">
                <span>Cloud API Endpoint URL (Optional)</span>
                <input class="tl-input" [(ngModel)]="labFormApiUrl" placeholder="https://..." />
              </label>
            </div>
            <div style="display:flex;justify-content:flex-end;gap:8px;margin-top:14px;">
              <button type="button" class="tl-btn tl-btn-outline" (click)="labModalOpen.set(false)">Cancel</button>
              <button type="button" class="tl-btn tl-btn-teal" (click)="saveImagingLab()">Save Lab</button>
            </div>
          </div>
        </div>
      }

      @if (referralTarget(); as refReq) {
        <div class="tl-modal-backdrop" (click)="referralTarget.set(null)">
          <div class="tl-modal-card" (click)="$event.stopPropagation()">
            <div style="display:flex;justify-content:space-between;align-items:center;border-bottom:1px solid var(--line);padding-bottom:8px;margin-bottom:12px;">
              <div>
                <h3 style="margin:0;font-size:17px;">Dental Imaging Referral Form</h3>
                <small style="color:var(--mute);">ClinicFlow Radiology Request #{{ refReq.id.slice(0, 8) }}</small>
              </div>
              <button type="button" class="tl-btn tl-btn-outline" (click)="referralTarget.set(null)">Close</button>
            </div>
            <div style="display:grid;grid-template-columns:1fr 1fr;gap:8px;font-size:13px;margin-bottom:12px;">
              <div><strong>Patient:</strong> {{ refReq.patientName || 'Patient' }}</div>
              <div><strong>Request Date:</strong> {{ refReq.requestDate }}</div>
              <div><strong>Referring Doctor:</strong> {{ refReq.doctorName || '—' }}</div>
              <div><strong>External Lab:</strong> {{ refReq.labName || 'Any Preferred Lab' }}</div>
              <div><strong>Modality:</strong> {{ refReq.imagingType }} (× {{ refReq.proceduresCount }})</div>
              <div><strong>Teeth / Region:</strong> {{ refReq.teethSummary || 'Full Arch' }}</div>
            </div>
            <div style="font-size:13px;background:#f8fafc;padding:10px;border-radius:6px;border:1px solid var(--line);margin-bottom:12px;">
              <strong>Clinical Indication:</strong> {{ refReq.clinicalIndication || 'Diagnostic imaging assessment' }}
            </div>
            <div style="display:flex;justify-content:flex-end;gap:8px;">
              <button type="button" class="tl-btn tl-btn-teal" (click)="printReferral()">Print / Save PDF</button>
            </div>
          </div>
        </div>
      }
    </div>
  `,
  styles: [
    `
      .tl-tabs-nav {
        display: flex;
        gap: 6px;
        border-bottom: 1px solid var(--line);
        padding-bottom: 6px;
      }
      .tl-tab-btn {
        padding: 6px 14px;
        border-radius: 6px;
        border: 1px solid transparent;
        background: transparent;
        color: var(--mute);
        font-size: 13px;
        font-weight: 600;
        cursor: pointer;
      }
      .tl-tab-btn:hover { background: var(--line); color: var(--ink); }
      .tl-tab-btn--active {
        background: rgba(0, 137, 123, 0.1);
        color: #00796b;
        border-color: rgba(0, 137, 123, 0.25);
      }
      .tl-pill {
        display: inline-block;
        padding: 2px 8px;
        border-radius: 999px;
        background: #e0f2f1;
        color: #00695c;
        font-size: 11px;
        font-weight: 700;
      }
      .tl-modal-backdrop {
        position: fixed;
        inset: 0;
        background: rgba(15, 23, 42, 0.45);
        display: flex;
        align-items: center;
        justify-content: center;
        z-index: 1200;
        padding: 16px;
      }
      .tl-modal-card {
        background: var(--surf, #ffffff);
        border-radius: 10px;
        padding: 18px;
        width: min(580px, 100%);
        max-height: 90vh;
        overflow-y: auto;
        box-shadow: 0 20px 50px rgba(0, 0, 0, 0.22);
      }
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
      .tl-toast-banner {
        display: flex;
        align-items: center;
        justify-content: space-between;
        background: var(--tl-dark);
        color: var(--on-accent);
        padding: 10px 16px;
        border-radius: 6px;
        margin-bottom: 14px;
        font-size: 13px;
      }
      .tl-toast-dismiss {
        background: transparent;
        border: none;
        color: var(--accent-blue-400);
        font-weight: 700;
        cursor: pointer;
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
      .tl-mono {
        font-family: var(--fe-font-mono, monospace);
        font-weight: 700;
      }
      .tl-teeth-summary {
        display: inline-block;
        padding: 2px 8px;
        border-radius: 4px;
        background: var(--surf2);
        border: 1px solid var(--line);
        font-size: 12px;
        font-weight: 600;
      }
      .tl-status-select {
        appearance: auto;
        border: 1px solid transparent;
        border-radius: 999px;
        padding: 4px 8px;
        font-size: 11.5px;
        font-weight: 700;
        font-family: inherit;
        cursor: pointer;
        background: var(--pri-soft, #ccfbf1);
        color: var(--tl-teal, #0d9488);
        transition: all 0.15s ease;
        outline: none;
      }
      .tl-status-select:hover,
      .tl-status-select:focus {
        filter: brightness(0.96);
        outline: 2px solid var(--tl-teal, #0d9488);
        outline-offset: 1px;
      }
      .tl-status-select.is-completed {
        background: var(--accent-green-soft, #dcfce7);
        color: var(--accent-green, #15803d);
      }
      .tl-status-select.is-prod {
        background: var(--accent-yellow-soft, #fef9c3);
        color: var(--accent-amber-ink, #a16207);
      }
      .tl-status-select.is-qc {
        background: #f3e8ff;
        color: #7e22ce;
      }
      .tl-status-select.is-sent {
        background: var(--pri-soft, #ccfbf1);
        color: var(--tl-teal, #0d9488);
      }
      .tl-status-select option {
        background: var(--surf, #ffffff);
        color: var(--ink, #1e293b);
        font-weight: 600;
      }
      .tl-row-actions {
        display: flex;
        align-items: center;
        gap: 6px;
      }
      .tl-icon-btn {
        width: 30px;
        height: 30px;
        border-radius: 6px;
        border: 1px solid var(--line);
        background: var(--surf2);
        cursor: pointer;
        display: inline-flex;
        align-items: center;
        justify-content: center;
      }
      .tl-icon-btn.is-teal {
        background: var(--tl-teal);
        color: var(--on-accent);
        border-color: var(--tl-teal);
      }
      .tl-icon-btn.is-danger {
        color: var(--accent-red-500);
      }
      /* Right-side Drawer */
      .tl-drawer-backdrop {
        position: fixed;
        inset: 0;
        background: var(--scrim);
        z-index: 1200;
        display: flex;
        justify-content: flex-end;
      }
      .tl-drawer {
        width: min(760px, 95vw);
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
        font-weight: 700;
      }
      .tl-close-btn {
        border: none;
        background: transparent;
        font-size: 18px;
        cursor: pointer;
        color: var(--mute);
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
        font-size: 14px;
        font-weight: 700;
        color: var(--tl-teal);
        text-transform: uppercase;
        letter-spacing: 0.03em;
      }
      .tl-form-grid-2 {
        display: grid;
        grid-template-columns: repeat(2, 1fr);
        gap: 14px;
      }
      .tl-field {
        display: flex;
        flex-direction: column;
        gap: 6px;
        font-size: 12.5px;
        font-weight: 600;
        color: var(--ink-soft);
      }
      .tl-upload-grid {
        display: grid;
        grid-template-columns: repeat(3, minmax(0, 1fr));
        gap: 12px;
        width: 100%;
        box-sizing: border-box;
      }
      @media (max-width: 680px) {
        .tl-upload-grid {
          grid-template-columns: 1fr;
        }
      }
      .tl-upload-box {
        border: 1.5px dashed var(--line);
        border-radius: 8px;
        padding: 16px 10px;
        display: flex;
        flex-direction: column;
        align-items: center;
        text-align: center;
        gap: 6px;
        background: var(--surf2);
        cursor: pointer;
        min-width: 0;
        box-sizing: border-box;
        transition: all 0.15s ease;
      }
      .tl-upload-box:hover {
        border-color: var(--tl-teal, #0d9488);
        background: rgba(13, 148, 136, 0.06);
      }
      .tl-upload-box input {
        display: none;
      }
      .tl-upload-icon {
        color: var(--mute);
        transition: color 0.15s ease;
      }
      .tl-upload-box:hover .tl-upload-icon {
        color: var(--tl-teal, #0d9488);
      }
      .tl-upload-title {
        font-weight: 700;
        font-size: 12px;
        color: var(--ink);
        line-height: 1.3;
        white-space: normal;
        word-break: break-word;
      }
      .tl-upload-sub {
        font-size: 11px;
        color: var(--mute);
        line-height: 1.2;
      }
      .tl-uploaded-pills {
        display: flex;
        flex-wrap: wrap;
        gap: 8px;
        margin-top: 12px;
      }
      .tl-file-pill {
        display: inline-flex;
        align-items: center;
        gap: 6px;
        padding: 4px 10px;
        border-radius: 999px;
        background: var(--pri-soft, rgba(13, 148, 136, 0.12));
        color: var(--tl-teal, #0d9488);
        font-size: 11.5px;
        font-weight: 600;
      }
      .tl-file-remove {
        border: none;
        background: transparent;
        color: inherit;
        font-size: 14px;
        cursor: pointer;
        padding: 0 2px;
        line-height: 1;
        opacity: 0.7;
        transition: opacity 0.15s ease;
      }
      .tl-file-remove:hover {
        opacity: 1;
      }
      /* Interactive 4-Quadrant FDI Tooth Selector (Screenshot 15) */
      .tl-fdi-selector-card {
        border: 1px solid var(--line);
        border-radius: 8px;
        padding: 14px;
        background: var(--surf2);
      }
      .tl-arch-row {
        display: grid;
        grid-template-columns: 1fr 2px 1fr;
        gap: 12px;
        align-items: center;
      }
      .tl-midline-divider {
        width: 2px;
        height: 100%;
        background: var(--tl-teal);
        opacity: 0.45;
      }
      .tl-horizontal-arch-divider {
        height: 2px;
        background: var(--tl-teal);
        opacity: 0.45;
        margin: 12px 0;
      }
      .tl-quadrant-block {
        display: flex;
        flex-direction: column;
        gap: 8px;
      }
      .tl-quadrant-title {
        font-size: 11.5px;
        font-weight: 700;
        color: var(--mute);
        text-align: center;
      }
      .tl-teeth-row {
        display: flex;
        justify-content: center;
        gap: 5px;
        flex-wrap: wrap;
      }
      .tl-tooth-circle {
        width: 32px;
        height: 32px;
        border-radius: 50%;
        border: 1.5px solid var(--line);
        background: var(--surf);
        color: var(--ink);
        font-size: 11.5px;
        font-weight: 700;
        cursor: pointer;
        transition: all 0.12s ease;
      }
      .tl-tooth-circle:hover {
        border-color: var(--tl-teal);
      }
      .tl-tooth-circle.is-selected {
        background: var(--tl-teal);
        border-color: var(--tl-teal);
        color: var(--on-accent);
        box-shadow: 0 2px 6px var(--pri-soft);
      }
      .tl-drawer-footer {
        padding: 16px 24px;
        border-top: 1px solid var(--line);
        display: flex;
        justify-content: flex-end;
        gap: 10px;
      }
    `,
  ],
})
export class ClinicLabComponent implements OnInit {
  private readonly api = inject(ClinicPatientApi);
  private readonly clinic = inject(ClinicApi);
  private readonly projects = inject(ProjectContextService);

  protected readonly urTeethList = UR_TEETH;
  protected readonly ulTeethList = UL_TEETH;
  protected readonly lrTeethList = LR_TEETH;
  protected readonly llTeethList = LL_TEETH;

  protected readonly activeLabTab = signal<'prostheticOrders' | 'imagingRequests' | 'imagingLabs'>('prostheticOrders');
  protected readonly imagingRequestsList = signal<readonly ImagingRequestRow[]>([]);
  protected readonly imagingLabsList = signal<readonly ImagingLabRow[]>([]);
  protected readonly imagingAnalytics = signal<ImagingSummaryAnalytics | null>(null);
  protected readonly imgTypeFilter = signal('');
  protected readonly imgStatusFilter = signal('');
  protected readonly imagingModalOpen = signal(false);
  protected readonly editingImagingReq = signal<ImagingRequestRow | null>(null);
  protected imgFormPatientId = '';
  protected imgFormDoctorId = '';
  protected imgFormType = 'CBCT';
  protected imgFormCount = 1;
  protected imgFormLabId = '';
  protected imgFormLabName = '';
  protected imgFormCost = 0;
  protected imgFormCharge = 0;
  protected imgFormStatus = 'REQUESTED';
  protected imgFormCloudUrl = '';
  protected imgFormTeethSummary = '';
  protected imgFormIndication = '';
  protected imgFormNotes = '';

  protected readonly labModalOpen = signal(false);
  protected readonly editingLab = signal<ImagingLabRow | null>(null);
  protected labFormName = '';
  protected labFormMode = 'MANUAL';
  protected labFormPhone = '';
  protected labFormEmail = '';
  protected labFormAddress = '';
  protected labFormApiUrl = '';
  protected readonly referralTarget = signal<ImagingRequestRow | null>(null);

  protected readonly orders = signal<readonly LabOrderRecord[]>([]);
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

  // Drawer form signals
  protected readonly draftDoctorId = signal('');
  protected readonly draftPatientId = signal('');
  protected readonly draftCaseType = signal('Zirconia Crown (Monolithic)');
  protected readonly draftStatus = signal<LabOrderRecord['status']>('Sent to Lab');
  protected readonly draftImpressionType = signal('Digital Intraoral Scan (STL)');
  protected readonly draftShade = signal('A2');
  protected readonly draftRxNotes = signal('');
  protected readonly draftFiles = signal<readonly string[]>([]);

  protected readonly selectedUrTeeth = signal<ReadonlySet<number>>(new Set());
  protected readonly selectedUlTeeth = signal<ReadonlySet<number>>(new Set());
  protected readonly selectedLrTeeth = signal<ReadonlySet<number>>(new Set());
  protected readonly selectedLlTeeth = signal<ReadonlySet<number>>(new Set());

  protected readonly filteredOrders = computed<readonly LabOrderRecord[]>(() => {
    const q = this.searchQuery().trim().toLowerCase();
    if (!q) return this.orders();
    return this.orders().filter(
      (o) =>
        o.id.toLowerCase().includes(q) ||
        o.doctorName.toLowerCase().includes(q) ||
        o.patientName.toLowerCase().includes(q) ||
        o.caseType.toLowerCase().includes(q) ||
        o.impressionType.toLowerCase().includes(q),
    );
  });

  protected readonly allSelected = computed(() => {
    const rows = this.filteredOrders();
    return rows.length > 0 && rows.every((r) => this.selectedIds().has(r.id));
  });

  ngOnInit(): void {
    void this.load();
  }

  private projectQuery(): { projectId?: string } {
    const projectId = this.projects.selectedProjectId();
    return projectId ? { projectId } : {};
  }

  protected switchLabTab(tab: 'prostheticOrders' | 'imagingRequests' | 'imagingLabs'): void {
    this.activeLabTab.set(tab);
    if (tab === 'imagingRequests') {
      void this.loadImagingRequests();
    } else if (tab === 'imagingLabs') {
      void this.loadImagingLabs();
    }
  }

  protected async loadImagingRequests(): Promise<void> {
    const query = this.projectQuery();
    try {
      const [reqs, stats, labs] = await Promise.all([
        this.api.imagingRequests({
          ...query,
          imagingType: this.imgTypeFilter() || undefined,
          status: this.imgStatusFilter() || undefined,
        }),
        this.api.imagingAnalytics(query),
        this.api.imagingLabs(query),
      ]);
      this.imagingRequestsList.set(reqs ?? []);
      this.imagingAnalytics.set(stats ?? null);
      this.imagingLabsList.set(labs ?? []);
    } catch {
      this.bannerMessage.set('Failed to load dental imaging requests.');
    }
  }

  protected async loadImagingLabs(): Promise<void> {
    try {
      const labs = await this.api.imagingLabs(this.projectQuery());
      this.imagingLabsList.set(labs ?? []);
    } catch {
      this.bannerMessage.set('Failed to load preferred imaging labs.');
    }
  }

  protected openCreateImagingModal(): void {
    this.editingImagingReq.set(null);
    this.imgFormPatientId = this.patients()[0]?.id ?? '';
    this.imgFormDoctorId = this.doctors()[0]?.id ?? '';
    this.imgFormType = 'CBCT';
    this.imgFormCount = 1;
    const defaultLab = this.imagingLabsList()[0];
    this.imgFormLabId = defaultLab?.id ?? '';
    this.imgFormLabName = defaultLab?.name ?? '';
    this.imgFormCost = 0;
    this.imgFormCharge = 0;
    this.imgFormStatus = 'REQUESTED';
    this.imgFormCloudUrl = '';
    this.imgFormTeethSummary = '';
    this.imgFormIndication = '';
    this.imgFormNotes = '';
    if (this.imagingLabsList().length === 0) void this.loadImagingLabs();
    this.imagingModalOpen.set(true);
  }

  protected openEditImagingModal(req: ImagingRequestRow): void {
    this.editingImagingReq.set(req);
    this.imgFormPatientId = req.patientId;
    this.imgFormDoctorId = req.doctorId ?? '';
    this.imgFormType = req.imagingType;
    this.imgFormCount = req.proceduresCount;
    this.imgFormLabId = req.labId ?? '';
    this.imgFormLabName = req.labName ?? '';
    this.imgFormCost = req.costToClinic;
    this.imgFormCharge = req.amountChargedToPatient;
    this.imgFormStatus = req.status;
    this.imgFormCloudUrl = req.cloudStorageUrl ?? '';
    this.imgFormTeethSummary = req.teethSummary ?? '';
    this.imgFormIndication = req.clinicalIndication ?? '';
    this.imgFormNotes = req.findingsNotes ?? '';
    this.imagingModalOpen.set(true);
  }

  protected onImgLabSelected(labId: string): void {
    const match = this.imagingLabsList().find((l) => l.id === labId);
    if (match) this.imgFormLabName = match.name;
  }

  protected async saveImagingRequest(): Promise<void> {
    this.saveBusy.set(true);
    try {
      const target = this.editingImagingReq();
      if (target) {
        await this.api.updateImagingRequest(
          target.id,
          {
            status: this.imgFormStatus,
            costToClinic: Number(this.imgFormCost) || 0,
            amountChargedToPatient: Number(this.imgFormCharge) || 0,
            cloudStorageUrl: this.imgFormCloudUrl.trim() || null,
            findingsNotes: this.imgFormNotes.trim() || null,
            labId: this.imgFormLabId || null,
            labName: this.imgFormLabName.trim() || null,
            completionDate: this.imgFormStatus === 'COMPLETED' ? new Date().toISOString().slice(0, 10) : null,
          },
          this.projectQuery(),
        );
      } else {
        if (!this.imgFormPatientId) {
          this.bannerMessage.set('Please select a patient for the imaging request.');
          return;
        }
        const payload: CreateImagingRequestPayload = {
          patientId: this.imgFormPatientId,
          doctorId: this.imgFormDoctorId || null,
          labId: this.imgFormLabId || null,
          labName: this.imgFormLabName.trim() || null,
          imagingType: this.imgFormType,
          proceduresCount: Math.max(1, Number(this.imgFormCount) || 1),
          teethSummary: this.imgFormTeethSummary.trim() || null,
          clinicalIndication: this.imgFormIndication.trim() || null,
          status: this.imgFormStatus,
          costToClinic: Number(this.imgFormCost) || 0,
          amountChargedToPatient: Number(this.imgFormCharge) || 0,
          cloudStorageUrl: this.imgFormCloudUrl.trim() || null,
          findingsNotes: this.imgFormNotes.trim() || null,
        };
        await this.api.createImagingRequest(payload, this.projectQuery());
      }
      this.imagingModalOpen.set(false);
      await this.loadImagingRequests();
    } catch {
      this.bannerMessage.set('Failed to save dental imaging request.');
    } finally {
      this.saveBusy.set(false);
    }
  }

  protected async deleteImagingReq(requestId: string): Promise<void> {
    try {
      await this.api.deleteImagingRequest(requestId, this.projectQuery());
      await this.loadImagingRequests();
    } catch {
      this.bannerMessage.set('Failed to delete imaging request.');
    }
  }

  protected openCreateLabModal(): void {
    this.editingLab.set(null);
    this.labFormName = '';
    this.labFormMode = 'MANUAL';
    this.labFormPhone = '';
    this.labFormEmail = '';
    this.labFormAddress = '';
    this.labFormApiUrl = '';
    this.labModalOpen.set(true);
  }

  protected openEditLabModal(lab: ImagingLabRow): void {
    this.editingLab.set(lab);
    this.labFormName = lab.name;
    this.labFormMode = lab.integrationMode;
    this.labFormPhone = lab.contactPhone ?? '';
    this.labFormEmail = lab.contactEmail ?? '';
    this.labFormAddress = lab.address ?? '';
    this.labFormApiUrl = lab.apiEndpointUrl ?? '';
    this.labModalOpen.set(true);
  }

  protected async saveImagingLab(): Promise<void> {
    const name = this.labFormName.trim();
    if (!name) return;
    this.saveBusy.set(true);
    try {
      const payload: CreateImagingLabPayload = {
        name,
        integrationMode: this.labFormMode,
        contactPhone: this.labFormPhone.trim() || null,
        contactEmail: this.labFormEmail.trim() || null,
        address: this.labFormAddress.trim() || null,
        apiEndpointUrl: this.labFormApiUrl.trim() || null,
      };
      const target = this.editingLab();
      if (target) {
        await this.api.updateImagingLab(target.id, payload, this.projectQuery());
      } else {
        await this.api.createImagingLab(payload, this.projectQuery());
      }
      this.labModalOpen.set(false);
      await this.loadImagingLabs();
    } catch {
      this.bannerMessage.set('Failed to save external imaging lab.');
    } finally {
      this.saveBusy.set(false);
    }
  }

  protected async deleteImagingLab(labId: string): Promise<void> {
    try {
      await this.api.deleteImagingLab(labId, this.projectQuery());
      await this.loadImagingLabs();
    } catch {
      this.bannerMessage.set('Failed to delete imaging lab.');
    }
  }

  protected printReferral(): void {
    window.print();
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    try {
      const query = this.projectQuery();
      const [orders, doctors, patients, reqs, labs] = await Promise.all([
        this.api.labOrders(query),
        this.clinic.doctors(query),
        this.api.patients(query),
        this.api.imagingRequests(query).catch(() => []),
        this.api.imagingLabs(query).catch(() => []),
      ]);
      this.orders.set(orders.map((row) => this.toRecord(row)));
      this.doctors.set(doctors.items ?? []);
      this.patients.set(patients);
      this.imagingRequestsList.set(reqs ?? []);
      this.imagingLabsList.set(labs ?? []);
    } catch {
      this.bannerMessage.set('Could not load the lab pipeline. Check the clinic connection.');
    } finally {
      this.loading.set(false);
    }
  }

  private toRecord(row: ClinicLabOrder): LabOrderRecord {
    const teeth = row.teethFdi && row.teethFdi.length > 0
      ? row.teethFdi
      : row.toothFdi != null
        ? [row.toothFdi]
        : [];
    const urTeeth: number[] = [];
    const ulTeeth: number[] = [];
    const lrTeeth: number[] = [];
    const llTeeth: number[] = [];
    for (const tooth of teeth) {
      const quadrant = Math.floor(tooth / 10);
      if (quadrant === 1) urTeeth.push(tooth);
      else if (quadrant === 2) ulTeeth.push(tooth);
      else if (quadrant === 3) llTeeth.push(tooth);
      else if (quadrant === 4) lrTeeth.push(tooth);
    }
    return {
      id: row.id,
      patientId: row.patientId,
      doctorId: row.doctorId ?? null,
      doctorName: row.doctorName ?? '—',
      patientName: row.patientName ?? '—',
      caseType: row.workType,
      impressionType: row.impressionType ?? '',
      shade: row.shade ?? '',
      status: LAB_STATUS_LABEL[row.status] ?? 'Sent to Lab',
      createdAt: formatTimestamp(row.createdAt ?? row.sentDate),
      urTeeth,
      ulTeeth,
      lrTeeth,
      llTeeth,
      rxNotes: row.notes ?? '',
      files: row.files ?? [],
    };
  }

  protected openCreateDrawer(): void {
    this.editingOrderId.set(null);
    this.draftDoctorId.set('');
    this.draftPatientId.set('');
    this.draftCaseType.set('Zirconia Crown (Monolithic)');
    this.draftStatus.set('Sent to Lab');
    this.draftImpressionType.set('Digital Intraoral Scan (STL)');
    this.draftShade.set('A2');
    this.draftRxNotes.set('');
    this.draftFiles.set([]);
    this.selectedUrTeeth.set(new Set());
    this.selectedUlTeeth.set(new Set());
    this.selectedLrTeeth.set(new Set());
    this.selectedLlTeeth.set(new Set());
    this.drawerOpen.set(true);
  }

  protected viewOrder(row: LabOrderRecord): void {
    this.editingOrderId.set(row.id);
    this.draftDoctorId.set(row.doctorId ?? '');
    this.draftPatientId.set(row.patientId ?? '');
    this.draftCaseType.set(row.caseType);
    this.draftStatus.set(row.status);
    this.draftImpressionType.set(row.impressionType);
    this.draftShade.set(row.shade);
    this.draftRxNotes.set(row.rxNotes);
    this.draftFiles.set(row.files);
    this.selectedUrTeeth.set(new Set(row.urTeeth));
    this.selectedUlTeeth.set(new Set(row.ulTeeth));
    this.selectedLrTeeth.set(new Set(row.lrTeeth));
    this.selectedLlTeeth.set(new Set(row.llTeeth));
    this.drawerOpen.set(true);
  }

  protected closeDrawer(): void {
    this.drawerOpen.set(false);
  }

  protected toggleTooth(quadrant: 'UR' | 'UL' | 'LR' | 'LL', tooth: number): void {
    if (this.editingOrderId() !== null) return;
    const target =
      quadrant === 'UR'
        ? this.selectedUrTeeth
        : quadrant === 'UL'
          ? this.selectedUlTeeth
          : quadrant === 'LR'
            ? this.selectedLrTeeth
            : this.selectedLlTeeth;
    const next = new Set(target());
    if (next.has(tooth)) next.delete(tooth);
    else next.add(tooth);
    target.set(next);
  }

  protected formatQuadrantSet(set: ReadonlySet<number>): string {
    return Array.from(set).sort((a, b) => a - b).join(', ');
  }

  protected formatRowTeeth(row: LabOrderRecord): string {
    const all = [...row.urTeeth, ...row.ulTeeth, ...row.lrTeeth, ...row.llTeeth];
    return all.length > 0 ? all.map((t) => `#${t}`).join(', ') : 'Full Arch';
  }

  protected onFileSelected(label: string, event: Event): void {
    if (this.editingOrderId() !== null) return;
    const input = event.target as HTMLInputElement;
    const files = Array.from(input.files ?? []).map((f) => f.name);
    if (files.length > 0) {
      this.draftFiles.update((prev) => [...prev, ...files]);
    } else {
      this.draftFiles.update((prev) => [...prev, `${label}.stl`]);
    }
  }

  protected removeDraftFile(file: string, event: Event): void {
    event.stopPropagation();
    if (this.editingOrderId() !== null) return;
    this.draftFiles.update((prev) => prev.filter((f) => f !== file));
  }

  protected async saveLabReport(): Promise<void> {
    if (this.saveBusy()) return;
    const editingId = this.editingOrderId();
    if (editingId) {
      await this.applyStatus(editingId, this.draftStatus());
      this.drawerOpen.set(false);
      return;
    }

    const patientId = this.draftPatientId();
    if (!patientId) {
      this.bannerMessage.set('Choose a patient before saving the lab report.');
      return;
    }
    const teeth = [
      ...Array.from(this.selectedUrTeeth()).sort((a, b) => a - b),
      ...Array.from(this.selectedUlTeeth()).sort((a, b) => a - b),
      ...Array.from(this.selectedLrTeeth()).sort((a, b) => a - b),
      ...Array.from(this.selectedLlTeeth()).sort((a, b) => a - b),
    ];
    this.saveBusy.set(true);
    try {
      const created = await this.api.createLabOrder(
        {
          patientId,
          doctorId: this.draftDoctorId() || null,
          labName: DEFAULT_LAB_NAME,
          workType: this.draftCaseType(),
          impressionType: this.draftImpressionType(),
          shade: this.draftShade(),
          teethFdi: teeth,
          toothFdi: teeth.length > 0 ? teeth[0] : null,
          files: this.draftFiles(),
          notes: this.draftRxNotes(),
          ...this.projectQuery(),
        },
        this.projectQuery(),
      );
      let record = this.toRecord(created);
      if (this.draftStatus() !== 'Sent to Lab') {
        const moved = await this.api.updateLabOrderStatus(
          created.id,
          LAB_STATUS_WIRE[this.draftStatus()],
          this.projectQuery(),
        );
        record = this.toRecord(moved);
      }
      this.orders.update((list) => [record, ...list]);
      this.bannerMessage.set(`Lab report ${shortRef(record.id)} created and sent to the lab.`);
      this.drawerOpen.set(false);
    } catch {
      this.bannerMessage.set('Could not save the lab report. The server refused the request.');
    } finally {
      this.saveBusy.set(false);
    }
  }

  protected async deleteOrder(id: string): Promise<void> {
    this.selectedIds.set(new Set());
    try {
      await this.api.deleteLabOrder(id, this.projectQuery());
      this.orders.update((list) => list.filter((item) => item.id !== id));
      this.bannerMessage.set(`Lab report ${shortRef(id)} deleted.`);
    } catch {
      this.bannerMessage.set('Could not delete the lab report.');
    }
  }

  protected printOrder(row: LabOrderRecord): void {
    this.bannerMessage.set(`Preparing printable Lab Rx sheet for ${shortRef(row.id)} (${row.patientName})...`);
    if (typeof window !== 'undefined' && typeof window.print === 'function') {
      window.print();
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
    this.selectedIds.set(new Set(this.filteredOrders().map((r) => r.id)));
  }

  protected async updateOrderStatus(
    id: string,
    newStatus: LabOrderRecord['status'],
  ): Promise<void> {
    await this.applyStatus(id, newStatus);
  }

  private async applyStatus(
    id: string,
    newStatus: LabOrderRecord['status'],
  ): Promise<void> {
    const current = this.orders().find((row) => row.id === id);
    if (current?.status === newStatus) return;
    try {
      const updated = await this.api.updateLabOrderStatus(
        id,
        LAB_STATUS_WIRE[newStatus],
        this.projectQuery(),
      );
      this.orders.update((list) =>
        list.map((row) => (row.id === id ? this.toRecord(updated) : row)),
      );
      this.bannerMessage.set(`Lab report ${shortRef(id)} moved to "${newStatus}".`);
    } catch {
      this.bannerMessage.set(`Could not move ${shortRef(id)} to "${newStatus}".`);
    }
  }

  protected async applyBulkAction(): Promise<void> {
    const action = this.bulkAction();
    const ids = Array.from(this.selectedIds());
    if (!action || ids.length === 0) return;
    if (action === 'DELETE') {
      for (const id of ids) {
        try {
          await this.api.deleteLabOrder(id, this.projectQuery());
        } catch {
          // Keep going: one refused row must not abort the rest of the batch.
        }
      }
      await this.load();
      this.bannerMessage.set(`Deleted ${ids.length} lab report(s).`);
    } else {
      const status = BULK_STATUS[action];
      if (status) {
        for (const id of ids) {
          await this.applyStatus(id, status);
        }
      }
    }
    this.selectedIds.set(new Set());
  }

  protected exportCsv(): void {
    const rows = this.filteredOrders();
    const csv = [
      'ID,Doctor,Patient,Case Type,Impression Type,Shade,Status,Created',
      ...rows.map(
        (r) =>
          `"${r.id}","${r.doctorName}","${r.patientName}","${r.caseType}","${r.impressionType}","${r.shade}","${r.status}","${r.createdAt}"`,
      ),
    ].join('\n');
    if (typeof Blob !== 'undefined' && typeof URL !== 'undefined' && typeof URL.createObjectURL === 'function') {
      const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = 'clinicflow-lab-reports.csv';
      a.click();
      URL.revokeObjectURL(url);
    }
    this.bannerMessage.set(`Exported ${rows.length} lab report(s) to CSV.`);
  }
}

const DEFAULT_LAB_NAME = 'Partner Dental Lab';

const LAB_STATUS_LABEL: Record<string, LabOrderRecord['status']> = {
  SENT_TO_LAB: 'Sent to Lab',
  IN_PRODUCTION: 'In Production',
  QUALITY_CHECK: 'Quality Check',
  COMPLETED: 'Completed',
};

const LAB_STATUS_WIRE: Record<LabOrderRecord['status'], string> = {
  'Sent to Lab': 'SENT_TO_LAB',
  'In Production': 'IN_PRODUCTION',
  'Quality Check': 'QUALITY_CHECK',
  Completed: 'COMPLETED',
};

const BULK_STATUS: Record<string, LabOrderRecord['status'] | undefined> = {
  SENT: 'Sent to Lab',
  PRODUCTION: 'In Production',
  QC: 'Quality Check',
  COMPLETED: 'Completed',
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
