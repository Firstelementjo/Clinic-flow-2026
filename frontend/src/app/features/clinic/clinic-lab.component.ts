import { CommonModule } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

export interface LabOrderRecord {
  readonly id: string;
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
  imports: [CommonModule, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="tl-page" data-testid="clinic-lab">
      <!-- Breadcrumbs -->
      <div class="tl-breadcrumbs">
        <a routerLink="/app/dashboard">Home</a>
        <span>»</span>
        <span class="active">Lab</span>
      </div>

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
                      [value]="draftDoctor()"
                      (change)="draftDoctor.set($any($event.target).value)"
                    >
                      <option value="Dr. Eman Khan">Dr. Eman Khan</option>
                      <option value="Dr. Bilal Ahmed">Dr. Bilal Ahmed</option>
                      <option value="Dr. Sara Malik">Dr. Sara Malik</option>
                    </select>
                  </label>

                  <label class="tl-field">
                    <span>Select Patient *</span>
                    <select
                      class="tl-input"
                      [value]="draftPatient()"
                      (change)="draftPatient.set($any($event.target).value)"
                    >
                      <option value="Mr. Awais">Mr. Awais</option>
                      <option value="Mrs. Sana">Mrs. Sana</option>
                      <option value="Ali Raza">Ali Raza</option>
                      <option value="Zainab Tariq">Zainab Tariq</option>
                      <option value="Hassan Mahmood">Hassan Mahmood</option>
                    </select>
                  </label>

                  <label class="tl-field">
                    <span>Case Type *</span>
                    <select
                      class="tl-input"
                      [value]="draftCaseType()"
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
export class ClinicLabComponent {
  protected readonly urTeethList = UR_TEETH;
  protected readonly ulTeethList = UL_TEETH;
  protected readonly lrTeethList = LR_TEETH;
  protected readonly llTeethList = LL_TEETH;

  protected readonly orders = signal<readonly LabOrderRecord[]>([
    {
      id: 'LAB-1041',
      doctorName: 'Dr. Eman Khan',
      patientName: 'Mr. Awais',
      caseType: 'Zirconia Crown (Monolithic)',
      impressionType: 'Digital Intraoral Scan (STL)',
      shade: 'A2',
      status: 'In Production',
      createdAt: '2026-04-08 11:20 AM',
      urTeeth: [16, 15],
      ulTeeth: [],
      lrTeeth: [],
      llTeeth: [36],
      rxNotes: 'Chamfer margin, light occlusal contact on #16, glazed zirconia.',
      files: ['Upper_Arch_Awais.stl', 'Lower_Arch_Awais.stl'],
    },
    {
      id: 'LAB-1042',
      doctorName: 'Dr. Eman Khan',
      patientName: 'Mrs. Sana',
      caseType: 'E-Max Porcelain Veneer',
      impressionType: 'PVS Elastomeric Putty/Wash',
      shade: 'B1',
      status: 'Sent to Lab',
      createdAt: '2026-04-09 02:45 PM',
      urTeeth: [13, 12, 11],
      ulTeeth: [21, 22, 23],
      lrTeeth: [],
      llTeeth: [],
      rxNotes: 'Natural incisal translucency, medium texture.',
      files: ['Sana_SmileDesign_Photos.zip'],
    },
    {
      id: 'LAB-1043',
      doctorName: 'Dr. Bilal Ahmed',
      patientName: 'Ali Raza',
      caseType: 'Custom Implant Abutment + Crown',
      impressionType: 'Digital Intraoral Scan (STL)',
      shade: 'A3',
      status: 'Completed',
      createdAt: '2026-04-05 09:15 AM',
      urTeeth: [],
      ulTeeth: [],
      lrTeeth: [46],
      llTeeth: [],
      rxNotes: 'Screw-retained titanium base abutment with occlusal access channel.',
      files: ['ScanBody_46.stl'],
    },
  ]);

  protected readonly searchQuery = signal('');
  protected readonly bulkAction = signal('');
  protected readonly selectedIds = signal<ReadonlySet<string>>(new Set());
  protected readonly bannerMessage = signal<string | null>(null);

  protected readonly drawerOpen = signal(false);
  protected readonly editingOrderId = signal<string | null>(null);

  // Drawer form signals
  protected readonly draftDoctor = signal('Dr. Eman Khan');
  protected readonly draftPatient = signal('Mr. Awais');
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

  protected openCreateDrawer(): void {
    this.editingOrderId.set(null);
    this.draftDoctor.set('Dr. Eman Khan');
    this.draftPatient.set('Mr. Awais');
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
    this.draftDoctor.set(row.doctorName);
    this.draftPatient.set(row.patientName);
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
    this.draftFiles.update((prev) => prev.filter((f) => f !== file));
  }

  protected saveLabReport(): void {
    const editingId = this.editingOrderId();
    const record: LabOrderRecord = {
      id: editingId ?? `LAB-${1044 + this.orders().length}`,
      doctorName: this.draftDoctor(),
      patientName: this.draftPatient(),
      caseType: this.draftCaseType(),
      impressionType: this.draftImpressionType(),
      shade: this.draftShade(),
      status: this.draftStatus(),
      createdAt: new Date().toISOString().slice(0, 16).replace('T', ' '),
      urTeeth: Array.from(this.selectedUrTeeth()),
      ulTeeth: Array.from(this.selectedUlTeeth()),
      lrTeeth: Array.from(this.selectedLrTeeth()),
      llTeeth: Array.from(this.selectedLlTeeth()),
      rxNotes: this.draftRxNotes(),
      files: this.draftFiles(),
    };

    if (editingId) {
      this.orders.update((list) => list.map((item) => (item.id === editingId ? record : item)));
      this.bannerMessage.set(`Lab report ${editingId} updated successfully!`);
    } else {
      this.orders.update((list) => [record, ...list]);
      this.bannerMessage.set(`Lab report ${record.id} created and sent to lab!`);
    }
    this.drawerOpen.set(false);
  }

  protected deleteOrder(id: string): void {
    this.orders.update((list) => list.filter((item) => item.id !== id));
    this.bannerMessage.set(`Lab report ${id} deleted.`);
  }

  protected printOrder(row: LabOrderRecord): void {
    this.bannerMessage.set(`Preparing printable Lab Rx sheet for ${row.id} (${row.patientName})...`);
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

  protected updateOrderStatus(id: string, newStatus: LabOrderRecord['status']): void {
    this.orders.update((list) =>
      list.map((r) => (r.id === id ? { ...r, status: newStatus } : r)),
    );
    this.bannerMessage.set(`Lab report ${id} status updated to "${newStatus}".`);
  }

  protected applyBulkAction(): void {
    const action = this.bulkAction();
    const ids = this.selectedIds();
    if (!action || ids.size === 0) return;
    if (action === 'DELETE') {
      this.orders.update((list) => list.filter((r) => !ids.has(r.id)));
      this.bannerMessage.set(`Deleted ${ids.size} lab report(s).`);
    } else if (action === 'SENT') {
      this.orders.update((list) =>
        list.map((r) => (ids.has(r.id) ? { ...r, status: 'Sent to Lab' } : r)),
      );
      this.bannerMessage.set(`Marked ${ids.size} lab report(s) as Sent to Lab.`);
    } else if (action === 'PRODUCTION') {
      this.orders.update((list) =>
        list.map((r) => (ids.has(r.id) ? { ...r, status: 'In Production' } : r)),
      );
      this.bannerMessage.set(`Marked ${ids.size} lab report(s) as In Production.`);
    } else if (action === 'QC') {
      this.orders.update((list) =>
        list.map((r) => (ids.has(r.id) ? { ...r, status: 'Quality Check' } : r)),
      );
      this.bannerMessage.set(`Marked ${ids.size} lab report(s) as Quality Check.`);
    } else if (action === 'COMPLETED') {
      this.orders.update((list) =>
        list.map((r) => (ids.has(r.id) ? { ...r, status: 'Completed' } : r)),
      );
      this.bannerMessage.set(`Marked ${ids.size} lab report(s) as Completed.`);
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
