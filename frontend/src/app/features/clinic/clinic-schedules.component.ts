/**
 * `/app/clinic/doctors-and-schedules` — the roster, bookable times, clinic resources,
 * waitlist, and reminder queue (Dental Clinic Slice S2).
 *
 * ## Which routes this reads
 *
 * - `GET /api/v1/clinic/doctors` — the roster, behind `doctor:read`. Working days, working
 *   hours and slot length. **Never** contact details: Phase 33 moved those behind
 *   `doctor:read-management` and this route does not declare it, so a "call the clinic"
 *   affordance here would show a field the permission does not cover.
 * - `GET /api/v1/clinic/availability?doctorId=&date=` — the bookable times, behind
 *   `appointment:read`.
 * - `GET /api/v1/clinic/schedules/resources` — operatory chairs, equipment, and shared assets.
 * - `GET /api/v1/clinic/schedules/waitlist` — patient waitlist priority queue.
 * - `GET /api/v1/clinic/schedules/reminders` — appointment confirmation and reminder dispatch status.
 */
import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormControl, FormsModule, ReactiveFormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { ClinicApi, type ClinicDoctor } from '../../core/api/endpoints';
import { SessionService } from '../../core/auth/session.service';
import { ProjectContextService } from '../../core/context/project-context.service';
import { FormatService } from '../../core/format/format.service';
import { I18nService } from '../../core/i18n/i18n.service';
import type { Namespace } from '../../core/i18n/i18n.types';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { ResourceStore } from '../../core/state/resource';
import { AsyncStateComponent } from '../../ui/async-state.component';
import { ClinicPatientApi, type ClinicSlot } from './clinic-patient.api';
import { clinicStrings } from './clinic.strings';

/** The grant `/clinic/availability` declares. Named here because it is not in `PERMISSIONS`. */
const APPOINTMENT_READ = 'appointment:read';

@Component({
  selector: 'fe-clinic-schedules-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    AsyncStateComponent,
    FormsModule,
    ReactiveFormsModule,
    RouterLink,
    TranslatePipe,
  ],
  template: `
    <div class="fe-page tl-page" data-testid="clinic-schedules">
      <!-- Breadcrumbs -->
      <div class="tl-breadcrumbs">
        <a routerLink="/app/dashboard">Home</a>
        <span>»</span>
        <span class="active">Doctor Sessions</span>
      </div>

      <!-- Action Toolbar matching ToothLogic-Role-based-access.png -->
      <div class="tl-toolbar">
        <div class="tl-toolbar-left">
          <select class="tl-select" [value]="bulkAction()" (change)="onBulkActionChange($event)">
            <option value="none">No Action</option>
            <option value="activate">Activate</option>
            <option value="deactivate">Deactivate</option>
          </select>
          <button type="button" class="tl-btn tl-btn-gray" (click)="applyBulkAction()">Apply</button>
          <button type="button" class="tl-btn tl-btn-teal" (click)="exportDoctorsCsv()">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16">
              <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path>
              <polyline points="7 10 12 15 17 10"></polyline>
              <line x1="12" y1="15" x2="12" y2="3"></line>
            </svg>
            <span>Export</span>
          </button>
        </div>

        <div class="tl-toolbar-right">
          <select class="tl-select" [value]="specialtyFilter()" (change)="onSpecialtyFilterChange($event)">
            <option value="all">All</option>
            <option value="surgery">Oral Surgery</option>
            <option value="ortho">Orthodontics</option>
            <option value="pediatric">Pediatric</option>
          </select>

          <div class="tl-search-box">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <circle cx="11" cy="11" r="8"></circle>
              <line x1="21" y1="21" x2="16.65" y2="16.65"></line>
            </svg>
            <input
              type="text"
              placeholder="Search doctor, specialty..."
              [value]="searchTerm()"
              (input)="onSearchInput($event)"
            />
          </div>

          <button type="button" class="tl-btn tl-btn-dark" (click)="toggleAdvancedFilter()">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="15" height="15">
              <polygon points="22 3 2 3 10 12.46 10 19 14 21 14 12.46 22 3"></polygon>
            </svg>
            <span>Advanced Filter</span>
          </button>

          <button type="button" class="tl-btn tl-btn-teal" (click)="onNewDoctorClick()">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16">
              <circle cx="12" cy="12" r="10"></circle>
              <line x1="12" y1="8" x2="12" y2="16"></line>
              <line x1="8" y1="12" x2="16" y2="12"></line>
            </svg>
            <span>New</span>
          </button>
        </div>
      </div>

      @if (showAdvancedFilter()) {
        <div class="tl-toolbar" style="margin-bottom: 16px;">
          <div class="tl-toolbar-left">
            <span class="fe-eyebrow">Status Filter:</span>
            <select class="tl-select" [value]="statusFilter()" (change)="onStatusFilterChange($event)">
              <option value="all">All Statuses</option>
              <option value="active">Active Only</option>
              <option value="inactive">Inactive Only</option>
            </select>
            <button type="button" class="tl-btn tl-btn-gray" (click)="resetDoctorFilters()">
              Reset Filters
            </button>
          </div>
        </div>
      }

      @if (scheduleToast(); as msg) {
        <div class="fe-card" role="status" style="margin-bottom: 16px; padding: 12px 16px; border-inline-start: 4px solid var(--pri);">
          <strong>{{ msg }}</strong>
        </div>
      }

      @if (selectedProfileDoctor(); as selectedDoc) {
        <div class="fe-card" role="dialog" aria-label="Doctor Profile" style="margin-bottom: 16px;">
          <div style="display: flex; justify-content: space-between; align-items: flex-start; gap: 16px;">
            <div>
              <h3 style="margin: 0 0 4px;">{{ selectedDoc.name }}</h3>
              <p class="fe-sub" style="margin: 0;">{{ selectedDoc.specialty ?? 'General Dentistry' }}</p>
              <p class="fe-sub" style="margin: 8px 0 0;">
                Working Hours: <span class="fe-mono" dir="ltr">{{ getDoctorStartHour(selectedDoc) }}–{{ getDoctorEndHour(selectedDoc) }}</span>
                · Slot: <span class="fe-mono" dir="ltr">{{ getDoctorSlotDuration(selectedDoc) }}m</span>
              </p>
            </div>
            <div class="row-actions">
              <button type="button" class="tl-btn tl-btn-teal" (click)="openDoctorSession(selectedDoc)">
                Open Session Slots
              </button>
              <button type="button" class="tl-btn tl-btn-gray" (click)="selectedProfileDoctor.set(null)">
                Close
              </button>
            </div>
          </div>
        </div>
      }

      <!-- ToothLogic Teal Header Doctor Session Table -->
      <div class="tl-table-wrap" style="margin-bottom: 24px;">
        <table class="tl-table">
          <thead>
            <tr>
              <th style="width: 40px;">
                <input
                  type="checkbox"
                  [checked]="allDoctorsSelected()"
                  (change)="toggleSelectAllDoctors($event)"
                />
              </th>
              <th class="sortable">Doctor <span>▲▼</span></th>
              <th class="sortable">Working Days <span>▲▼</span></th>
              <th class="sortable">Session Hours <span>▲▼</span></th>
              <th class="sortable">Slot Length <span>▲▼</span></th>
              <th class="sortable">Operatory / Room <span>▲▼</span></th>
              <th class="sortable">Status <span>▲▼</span></th>
              <th style="text-align: center; width: 140px;">Action</th>
            </tr>
          </thead>
          <tbody data-testid="clinic-roster">
            @for (doc of pagedDoctors(); track doc.id) {
              <tr>
                <td>
                  <input
                    type="checkbox"
                    [checked]="isDoctorSelected(doc.id)"
                    (change)="toggleDoctorSelection(doc.id, $event)"
                  />
                </td>
                <td>
                  <div class="tl-cell-profile">
                    <div class="tl-avatar">
                      <svg viewBox="0 0 24 24" fill="currentColor" width="20" height="20">
                        <path d="M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z"/>
                      </svg>
                    </div>
                    <div class="tl-profile-info">
                      <span class="tl-profile-name">{{ doc.name ?? '—' }}</span>
                      <span class="tl-profile-sub">{{ doc.specialty ?? 'General Dentistry' }}</span>
                    </div>
                  </div>
                </td>
                <td>
                  <div class="tl-days-wrap">
                    @for (day of getDoctorWorkingDays(doc); track day) {
                      <span class="tl-day-chip">{{ 'clinic.day.' + day | t }}</span>
                    }
                  </div>
                </td>
                <td>
                  <span class="tl-badge tl-badge-teal" dir="ltr">
                    {{ getDoctorStartHour(doc) }} – {{ getDoctorEndHour(doc) }}
                  </span>
                </td>
                <td>
                  <span class="tl-slot-pill">{{ getDoctorSlotDuration(doc) }} min</span>
                </td>
                <td>
                  <span class="tl-dept-pill">{{ doc.department || 'Operatory ' + ($index + 1) }}</span>
                </td>
                <td>
                  <label class="tl-switch">
                    <input
                      type="checkbox"
                      [checked]="isDoctorActive(doc)"
                      (change)="toggleDoctorStatus(doc, $event)"
                    />
                    <span class="tl-slider"></span>
                  </label>
                </td>
                <td>
                  <div class="tl-actions" style="justify-content: center;">
                    <button
                      type="button"
                      class="tl-action-icon history"
                      title="Doctor Session & Bookable Slots"
                      (click)="openDoctorSession(doc)"
                    >
                      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16">
                        <circle cx="12" cy="12" r="10"></circle>
                        <polyline points="12 6 12 12 16 14"></polyline>
                      </svg>
                    </button>
                    <button
                      type="button"
                      class="tl-action-icon edit"
                      title="Edit Session Schedule"
                      (click)="editDoctorSchedule(doc)"
                    >
                      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16">
                        <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"></path>
                        <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"></path>
                      </svg>
                    </button>
                    <button
                      type="button"
                      class="tl-action-icon view"
                      title="View Profile"
                      (click)="viewDoctorProfile(doc)"
                    >
                      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16">
                        <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"></path>
                        <circle cx="12" cy="12" r="3"></circle>
                      </svg>
                    </button>
                    <button
                      type="button"
                      class="tl-action-icon delete"
                      title="Delete"
                      (click)="deleteDoctorRow(doc)"
                    >
                      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16">
                        <polyline points="3 6 5 6 21 6"></polyline>
                        <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"></path>
                      </svg>
                    </button>
                  </div>
                </td>
              </tr>
            }
          </tbody>
        </table>
        <div class="tl-table-footer">
          <span>Showing {{ filteredDoctors().length === 0 ? 0 : 1 }} to {{ pagedDoctors().length }} of {{ filteredDoctors().length }} entries</span>
          <div class="tl-pagination">
            <button
              type="button"
              class="tl-page-btn"
              [disabled]="doctorPage() <= 1"
              (click)="setDoctorPage(doctorPage() - 1)"
            >
              Previous
            </button>
            <button
              type="button"
              class="tl-page-btn active"
              (click)="setDoctorPage(1)"
            >
              {{ doctorPage() }}
            </button>
            <button
              type="button"
              class="tl-page-btn"
              [disabled]="doctorPage() >= doctorPageCount()"
              (click)="setDoctorPage(doctorPage() + 1)"
            >
              Next
            </button>
          </div>
        </div>
      </div>

      <!-- Slide-Out Drawer for Doctor Session & Bookable Slots -->
      <div
        class="tl-drawer-backdrop"
        [class.open]="isSessionDrawerOpen()"
        (click)="closeSessionDrawer()"
      ></div>
      <aside
        class="tl-drawer"
        [class.open]="isSessionDrawerOpen()"
        role="dialog"
        aria-label="Doctor Session & Availability"
      >
        <header class="tl-drawer-header">
          <div>
            <h2 style="margin: 0; font-size: 16px; font-weight: 700; color: var(--ink);">
              {{ activeDoctor()?.name || 'Doctor Session' }}
            </h2>
            <p class="fe-sub" style="margin: 2px 0 0; font-size: 12px;">
              {{ activeDoctor()?.specialty || 'General Dentistry' }} · {{ activeDoctor() ? (getDoctorStartHour(activeDoctor()!) + ' – ' + getDoctorEndHour(activeDoctor()!)) : 'Schedule' }}
            </p>
          </div>
          <button type="button" class="tl-close-btn" (click)="closeSessionDrawer()">&times;</button>
        </header>

        <div class="tl-drawer-tabs">
          <button
            type="button"
            class="tl-drawer-tab-btn"
            [class.active]="drawerTab() === 'slots'"
            (click)="drawerTab.set('slots')"
          >
            Bookable Slots
          </button>
          <button
            type="button"
            class="tl-drawer-tab-btn"
            [class.active]="drawerTab() === 'schedule'"
            (click)="drawerTab.set('schedule')"
          >
            Edit Working Schedule
          </button>
        </div>

        <div class="tl-drawer-body">
          @if (drawerTab() === 'slots') {
            <!-- Doctor & Date Selection Controls -->
            <div class="tl-drawer-controls">
              <label class="tl-field" style="margin-bottom: 12px;">
                <span class="fe-eyebrow">{{ 'clinic.schedules.pickClinician' | t }}</span>
                <select
                  class="tl-input"
                  data-testid="clinic-schedule-clinician"
                  [formControl]="clinician"
                  (change)="onClinicianChange($event)"
                >
                  <option value="">{{ 'clinic.schedules.pickClinician' | t }}</option>
                  @for (doctor of doctors(); track doctor.id) {
                    <option [value]="doctor.id ?? ''">{{ doctor.name ?? '—' }}</option>
                  }
                </select>
              </label>

              <div class="tl-field" style="margin-bottom: 16px;">
                <span class="fe-eyebrow">{{ 'clinic.schedules.pickDay' | t }}</span>
                <div class="tl-quick-dates">
                  <button type="button" class="tl-quick-date-btn" (click)="selectQuickDate(0)">Today</button>
                  <button type="button" class="tl-quick-date-btn" (click)="selectQuickDate(1)">Tomorrow</button>
                  <button type="button" class="tl-quick-date-btn" (click)="selectQuickDate(2)">+2 Days</button>
                </div>
                <input
                  class="tl-input"
                  type="date"
                  dir="ltr"
                  data-testid="clinic-schedule-day"
                  [formControl]="day"
                  (change)="onDayChange($event)"
                />
              </div>
            </div>

            <!-- Slots Rendering Area -->
            @if (!canReadAppointments()) {
              <div class="tl-drawer-notice warn">
                <p class="fe-sub" data-testid="clinic-schedules-denied">{{ 'clinic.schedules.denied' | t }}</p>
              </div>
            } @else if (doctorId === null || dayValue.trim() === '') {
              <div class="tl-drawer-notice info">
                <p class="fe-sub" data-testid="clinic-schedules-prompt">{{ 'clinic.schedules.slots.prompt' | t }}</p>
              </div>
            } @else {
              <fe-async-state
                [loading]="slots.loading() && slots.value() === null"
                [empty]="slots.value() !== null && slotRows().length === 0"
                [error]="slots.error()"
                [hasValue]="slots.value() !== null"
                [loadingLabel]="'state.loading' | t"
                [emptyTitle]="'clinic.schedules.slots.empty.title' | t"
                [emptyBody]="'clinic.schedules.slots.empty.body' | t"
                [errorTitle]="'clinic.error.title' | t"
                [errorBody]="'clinic.error.body' | t"
                [retryLabel]="'clinic.retry' | t"
                (retry)="loadSlots()"
              />

              @if (slotRows().length > 0) {
                <div class="tl-slots-wrap">
                  <table class="fe-table tl-slots-table" data-testid="clinic-slots">
                    <thead>
                      <tr>
                        <th>{{ 'clinic.schedules.slot.starts' | t }}</th>
                        <th>{{ 'clinic.schedules.slot.patientMinutes' | t }}</th>
                        <th>{{ 'clinic.schedules.slot.blockedUntil' | t }}</th>
                        <th style="text-align: right;">Action</th>
                      </tr>
                    </thead>
                    <tbody>
                      @for (slot of slotRows(); track $index) {
                        <tr>
                          <td class="fe-mono" dir="ltr"><strong>{{ slot.startsAt ?? '—' }}</strong></td>
                          <td>{{ format.integer(slot.patientMinutes ?? null) }} min</td>
                          <td class="fe-mono" dir="ltr">{{ slot.blockedUntil ?? '—' }}</td>
                          <td style="text-align: right;">
                            <button
                              type="button"
                              class="tl-btn tl-btn-sm tl-btn-teal"
                              (click)="quickHoldSlot(slot)"
                            >
                              Book Slot
                            </button>
                          </td>
                        </tr>
                      }
                    </tbody>
                  </table>
                  <p class="fe-sub" style="margin-top: 14px; font-size: 11px; line-height: 1.4;">
                    {{ 'clinic.schedules.bufferNote' | t }}
                  </p>
                </div>
              }
            }
          } @else {
            <!-- Edit Working Schedule Section -->
            <div class="tl-form-section">
              <h3 class="tl-section-heading">Weekly Working Days</h3>
              <div class="tl-days-grid">
                @for (d of [0, 1, 2, 3, 4, 5, 6]; track d) {
                  <label class="tl-day-checkbox" [class.selected]="editWorkingDays().has(d)">
                    <input
                      type="checkbox"
                      [checked]="editWorkingDays().has(d)"
                      (change)="toggleEditDay(d, $event)"
                    />
                    <span>{{ 'clinic.day.' + d | t }}</span>
                  </label>
                }
              </div>
            </div>

            <div class="tl-form-section" style="margin-top: 16px;">
              <h3 class="tl-section-heading">Daily Session Shift</h3>
              <div style="display: grid; grid-template-columns: 1fr 1fr; gap: 12px;">
                <label class="tl-field">
                  <span>Shift Start Hour</span>
                  <input
                    type="time"
                    class="tl-input"
                    [value]="editStartHour()"
                    (input)="editStartHour.set($any($event.target).value)"
                  />
                </label>
                <label class="tl-field">
                  <span>Shift End Hour</span>
                  <input
                    type="time"
                    class="tl-input"
                    [value]="editEndHour()"
                    (input)="editEndHour.set($any($event.target).value)"
                  />
                </label>
              </div>

              <label class="tl-field" style="margin-top: 12px;">
                <span>Default Appointment Slot Duration</span>
                <select
                  class="tl-input"
                  [value]="editSlotDuration()"
                  (change)="editSlotDuration.set(+$any($event.target).value)"
                >
                  <option [value]="15">15 Minutes</option>
                  <option [value]="20">20 Minutes</option>
                  <option [value]="30">30 Minutes (Standard)</option>
                  <option [value]="45">45 Minutes</option>
                  <option [value]="60">60 Minutes (Comprehensive)</option>
                </select>
              </label>
            </div>
          }
        </div>

        <footer class="tl-drawer-footer">
          <button type="button" class="tl-btn tl-btn-outline" (click)="closeSessionDrawer()">
            Close
          </button>
          @if (drawerTab() === 'schedule') {
            <button type="button" class="tl-btn tl-btn-teal" (click)="saveDoctorSchedule()">
              Save Schedule
            </button>
          }
        </footer>
      </aside>
    </div>
  `,
  styles: [
    `
      .fe-tabs {
        display: flex;
        flex-wrap: wrap;
        gap: var(--fe-space-2);
        margin-block: var(--fe-space-3);
        border-bottom: 1px solid var(--line);
        padding-bottom: var(--fe-space-2);
      }
      .fe-tab-btn {
        padding: var(--fe-space-2) var(--fe-space-3);
        border-radius: var(--fe-radius-sm);
        border: 1px solid var(--line);
        background: var(--surf2);
        color: var(--ink);
        font-weight: 500;
        cursor: pointer;
        transition: background 0.15s ease, border-color 0.15s ease;
      }
      .fe-tab-btn:hover {
        background: var(--surf1);
      }
      .fe-tab-btn--active {
        background: var(--pri);
        color: var(--surf);
        border-color: var(--pri);
      }
      .roster {
        list-style: none;
        padding: 0;
        margin-block: var(--fe-space-3);
        display: flex;
        flex-direction: column;
        gap: var(--fe-space-2);
      }
      .roster__item {
        padding: var(--fe-space-3);
        border: 1px solid var(--line);
        border-radius: var(--fe-radius-sm);
        background: var(--surf2);
        min-inline-size: 0;
        overflow-wrap: anywhere;
      }
      .roster__head {
        display: flex;
        flex-wrap: wrap;
        align-items: baseline;
        gap: var(--fe-space-2);
      }
      .roster__meta {
        display: flex;
        flex-wrap: wrap;
        gap: var(--fe-space-4);
        margin-block-start: var(--fe-space-1);
        color: var(--mute);
        font-size: var(--fe-text-sm);
      }
      .day {
        margin-inline-end: var(--fe-space-2);
        white-space: nowrap;
      }
      .filters {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(15rem, 1fr));
        gap: var(--fe-space-4);
        margin-block: var(--fe-space-3);
      }
      .resource-form {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(13rem, 1fr));
        gap: var(--fe-space-3);
        align-items: flex-end;
        padding: var(--fe-space-3);
        background: var(--surf2);
        border: 1px solid var(--line);
        border-radius: var(--fe-radius-sm);
        margin-block: var(--fe-space-3);
      }
      .field {
        display: flex;
        flex-direction: column;
        gap: var(--fe-space-1);
        min-inline-size: 0;
      }
      .field-actions {
        display: flex;
        align-items: flex-end;
      }
      .fe-card {
        margin-block-start: var(--fe-space-4);
        overflow-x: auto;
      }
      .fe-tag {
        display: inline-block;
        padding: 0.125rem 0.375rem;
        background: var(--surf1);
        border: 1px solid var(--line);
        border-radius: var(--fe-radius-xs, 4px);
        font-size: var(--fe-text-xs, 0.75rem);
        margin-inline-end: var(--fe-space-1);
      }
      .row-actions {
        display: flex;
        gap: var(--fe-space-1);
      }
      .fe-btn--sm {
        padding: var(--fe-space-1) var(--fe-space-2);
        font-size: var(--fe-text-xs, 0.75rem);
      }

      /* Custom Doctor Sessions & Drawer Styling */
      .tl-days-wrap {
        display: flex;
        flex-wrap: wrap;
        gap: 4px;
        max-width: 220px;
      }
      .tl-day-chip {
        display: inline-block;
        padding: 2px 6px;
        font-size: 11px;
        font-weight: 500;
        border-radius: 4px;
        background: rgba(38, 198, 218, 0.12);
        color: #00838f;
        border: 1px solid rgba(38, 198, 218, 0.25);
        white-space: nowrap;
      }
      .tl-badge-teal {
        display: inline-block;
        padding: 4px 8px;
        border-radius: 6px;
        font-size: 12px;
        font-weight: 600;
        background: rgba(38, 198, 218, 0.14);
        color: #00838f;
        border: 1px solid rgba(38, 198, 218, 0.3);
        font-family: monospace;
      }
      .tl-slot-pill {
        display: inline-block;
        padding: 3px 8px;
        border-radius: 12px;
        font-size: 11px;
        font-weight: 600;
        background: var(--surf2);
        border: 1px solid var(--line);
        color: var(--ink);
      }
      .tl-dept-pill {
        display: inline-block;
        padding: 3px 8px;
        border-radius: 6px;
        font-size: 12px;
        font-weight: 500;
        background: var(--surf1);
        border: 1px solid var(--line);
        color: var(--ink-soft);
      }

      /* Slide Drawer */
      .tl-drawer-backdrop {
        position: fixed;
        inset: 0;
        background: rgba(15, 23, 42, 0.45);
        backdrop-filter: blur(2px);
        z-index: 1000;
        opacity: 0;
        visibility: hidden;
        transition: opacity 0.2s ease, visibility 0.2s ease;
      }
      .tl-drawer-backdrop.open {
        opacity: 1;
        visibility: visible;
      }
      .tl-drawer {
        position: fixed;
        top: 0;
        right: 0;
        bottom: 0;
        width: 560px;
        max-width: 92vw;
        background: var(--surf);
        box-shadow: -8px 0 32px rgba(0, 0, 0, 0.18);
        z-index: 1001;
        display: flex;
        flex-direction: column;
        transform: translateX(100%);
        transition: transform 0.25s cubic-bezier(0.16, 1, 0.3, 1);
      }
      .tl-drawer.open {
        transform: translateX(0);
      }
      .tl-drawer-header {
        padding: 18px 24px;
        border-bottom: 1px solid var(--line);
        display: flex;
        justify-content: space-between;
        align-items: center;
        background: var(--surf2);
      }
      .tl-close-btn {
        background: none;
        border: none;
        font-size: 24px;
        line-height: 1;
        cursor: pointer;
        color: var(--mute);
        transition: color 0.15s;
      }
      .tl-close-btn:hover {
        color: var(--ink);
      }
      .tl-drawer-tabs {
        display: flex;
        border-bottom: 1px solid var(--line);
        background: var(--surf1);
      }
      .tl-drawer-tab-btn {
        flex: 1;
        padding: 12px 16px;
        border: none;
        background: transparent;
        font-size: 13px;
        font-weight: 600;
        color: var(--mute);
        cursor: pointer;
        border-bottom: 2px solid transparent;
        transition: all 0.15s;
      }
      .tl-drawer-tab-btn.active {
        color: #00838f;
        border-bottom-color: var(--tl-teal, #26c6da);
        background: var(--surf);
      }
      .tl-drawer-body {
        padding: 20px 24px;
        overflow-y: auto;
        flex: 1;
        display: flex;
        flex-direction: column;
        gap: 16px;
      }
      .tl-drawer-footer {
        padding: 16px 24px;
        border-top: 1px solid var(--line);
        display: flex;
        justify-content: flex-end;
        gap: 10px;
        background: var(--surf2);
      }
      .tl-quick-dates {
        display: flex;
        gap: 6px;
        margin-bottom: 8px;
      }
      .tl-quick-date-btn {
        padding: 4px 10px;
        border-radius: 4px;
        font-size: 11px;
        font-weight: 500;
        border: 1px solid var(--line);
        background: var(--surf2);
        color: var(--ink);
        cursor: pointer;
        transition: background 0.15s, border-color 0.15s;
      }
      .tl-quick-date-btn:hover {
        background: var(--surf1);
        border-color: var(--tl-teal, #26c6da);
      }
      .tl-drawer-notice {
        padding: 12px 16px;
        border-radius: 6px;
        font-size: 13px;
        margin-block: 8px;
      }
      .tl-drawer-notice.warn {
        background: rgba(245, 158, 11, 0.1);
        border: 1px solid rgba(245, 158, 11, 0.3);
        color: var(--warn);
      }
      .tl-drawer-notice.info {
        background: rgba(38, 198, 218, 0.1);
        border: 1px solid rgba(38, 198, 218, 0.25);
        color: #00838f;
      }
      .tl-slots-wrap {
        border: 1px solid var(--line);
        border-radius: 8px;
        overflow: hidden;
        background: var(--surf);
      }
      .tl-slots-table {
        width: 100%;
        margin: 0;
        border-collapse: collapse;
      }
      .tl-slots-table th {
        background: var(--surf2);
        font-size: 12px;
        padding: 8px 12px;
        font-weight: 600;
        text-align: start;
        border-bottom: 1px solid var(--line);
      }
      .tl-slots-table td {
        padding: 10px 12px;
        font-size: 12px;
        border-bottom: 1px solid var(--line);
        vertical-align: middle;
      }
      .tl-days-grid {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(100px, 1fr));
        gap: 8px;
        margin-top: 8px;
      }
      .tl-day-checkbox {
        display: flex;
        align-items: center;
        gap: 6px;
        padding: 8px 10px;
        border-radius: 6px;
        border: 1px solid var(--line);
        background: var(--surf2);
        font-size: 12px;
        font-weight: 500;
        cursor: pointer;
        user-select: none;
        transition: all 0.15s;
      }
      .tl-day-checkbox.selected {
        border-color: var(--tl-teal, #26c6da);
        background: rgba(38, 198, 218, 0.12);
        color: #00838f;
        font-weight: 600;
      }
      .tl-section-heading {
        margin: 0 0 10px 0;
        font-size: 13px;
        font-weight: 700;
        text-transform: uppercase;
        letter-spacing: 0.04em;
        color: var(--ink);
      }
      .tl-form-section {
        border: 1px solid var(--line);
        border-radius: 8px;
        padding: 14px 16px;
        background: var(--surf);
      }
      .tl-field {
        display: flex;
        flex-direction: column;
        gap: 4px;
        font-size: 12px;
        font-weight: 500;
        color: var(--ink);
      }
      .tl-input {
        padding: 8px 12px;
        border: 1px solid var(--line);
        border-radius: 6px;
        background: var(--surf);
        color: var(--ink);
        font-size: 13px;
        width: 100%;
        box-sizing: border-box;
      }
      .tl-ops-card {
        background: var(--surf);
        border-radius: 10px;
        border: 1px solid var(--line);
        box-shadow: var(--shadow-soft);
      }
    `,
  ],
})
export class ClinicSchedulesComponent implements OnInit {
  private readonly clinic = inject(ClinicApi);
  private readonly availabilityApi = inject(ClinicPatientApi);
  private readonly sessions = inject(SessionService);
  private readonly projects = inject(ProjectContextService);
  private readonly router = inject(Router);

  protected readonly format = inject(FormatService);

  // Doctor Sessions Drawer State
  protected readonly isSessionDrawerOpen = signal(false);
  protected readonly drawerTab = signal<'slots' | 'schedule'>('slots');
  protected readonly activeDoctor = signal<ClinicDoctor | null>(null);
  protected readonly editStartHour = signal('09:00');
  protected readonly editEndHour = signal('17:00');
  protected readonly editSlotDuration = signal(30);
  protected readonly editWorkingDays = signal<Set<number>>(new Set([0, 1, 2, 3, 4]));
  protected readonly scheduleOverrides = signal<
    Readonly<Record<string, { workingDays?: readonly number[]; startHour?: string; endHour?: string; slotDuration?: number }>>
  >({});

  // Doctor List toolbar & table state
  protected readonly bulkAction = signal<'none' | 'activate' | 'deactivate'>('none');
  protected readonly specialtyFilter = signal('all');
  protected readonly searchTerm = signal('');
  protected readonly showAdvancedFilter = signal(false);
  protected readonly statusFilter = signal<'all' | 'active' | 'inactive'>('all');
  protected readonly selectedDoctorIds = signal<ReadonlySet<string>>(new Set());
  protected readonly doctorStatusOverrides = signal<Readonly<Record<string, boolean>>>({});
  protected readonly deletedDoctorIds = signal<ReadonlySet<string>>(new Set());
  protected readonly selectedProfileDoctor = signal<ClinicDoctor | null>(null);
  protected readonly scheduleToast = signal<string | null>(null);
  protected readonly doctorPage = signal(1);
  protected readonly doctorPageSize = 10;
  private toastTimeout: ReturnType<typeof setTimeout> | null = null;

  protected readonly roster = new ResourceStore<readonly ClinicDoctor[]>();
  protected readonly slots = new ResourceStore<readonly ClinicSlot[]>();

  protected readonly clinician = new FormControl('', { nonNullable: true });
  protected readonly day = new FormControl('', { nonNullable: true });

  protected readonly doctors = computed(() => this.roster.value() ?? []);
  protected readonly filteredDoctors = computed(() => {
    const term = this.searchTerm().trim().toLowerCase();
    const spec = this.specialtyFilter();
    const status = this.statusFilter();
    const deleted = this.deletedDoctorIds();
    const overrides = this.doctorStatusOverrides();

    return this.doctors().filter((doc) => {
      if (doc.id && deleted.has(doc.id)) return false;
      const isActive = doc.id && doc.id in overrides ? overrides[doc.id] : doc.isActive !== false;
      if (status === 'active' && !isActive) return false;
      if (status === 'inactive' && isActive) return false;
      if (spec !== 'all') {
        const docSpec = (doc.specialty ?? '').toLowerCase();
        if (spec === 'surgery' && !docSpec.includes('surg')) return false;
        if (spec === 'ortho' && !docSpec.includes('ortho')) return false;
        if (spec === 'pediatric' && !docSpec.includes('pediatric') && !docSpec.includes('paediatric')) return false;
      }
      if (term !== '') {
        const hay = `${doc.name ?? ''} ${doc.specialty ?? ''}`.toLowerCase();
        if (!hay.includes(term)) return false;
      }
      return true;
    });
  });
  protected readonly doctorPageCount = computed(() =>
    Math.max(1, Math.ceil(this.filteredDoctors().length / this.doctorPageSize)),
  );
  protected readonly pagedDoctors = computed(() => {
    const start = (this.doctorPage() - 1) * this.doctorPageSize;
    return this.filteredDoctors().slice(start, start + this.doctorPageSize);
  });
  protected readonly allDoctorsSelected = computed(() => {
    const pageRows = this.pagedDoctors();
    const selected = this.selectedDoctorIds();
    return pageRows.length > 0 && pageRows.every((d) => d.id && selected.has(d.id));
  });
  protected readonly slotRows = computed(() => this.slots.value() ?? []);
  protected readonly canReadAppointments = computed(() => this.sessions.can()(APPOINTMENT_READ));

  protected get doctorId(): string | null {
    const id = this.clinician.value.trim();
    return id === '' ? null : id;
  }

  protected get dayValue(): string {
    return this.day.value;
  }

  constructor() {
    inject(I18nService).register({ clinic: clinicStrings as Namespace });
  }

  ngOnInit(): void {
    void this.loadRoster();
  }

  protected async loadRoster(): Promise<void> {
    await this.roster.load(async () => {
      const page = await this.clinic.doctors({
        projectId: this.projects.selectedProjectId() ?? undefined,
      });
      return page.items ?? [];
    });
  }

  protected onClinicianChange(event: Event): void {
    this.clinician.setValue((event.target as HTMLSelectElement).value, { emitEvent: false });
    void this.loadSlots();
  }

  protected onDayChange(event: Event): void {
    this.day.setValue((event.target as HTMLInputElement).value, { emitEvent: false });
    void this.loadSlots();
  }

  protected async loadSlots(): Promise<void> {
    const doctorId = this.doctorId;
    const date = this.dayValue.trim();
    if (!this.canReadAppointments() || doctorId === null || date === '') {
      this.slots.reset();
      return;
    }
    await this.slots.load(async () => {
      const rows = await this.availabilityApi.availability({
        doctorId,
        date,
        projectId: this.projects.selectedProjectId() ?? undefined,
      });
      return Array.isArray(rows) ? rows : [];
    });
  }

  protected onBulkActionChange(event: Event): void {
    const val = (event.target as HTMLSelectElement).value;
    this.bulkAction.set(val === 'activate' || val === 'deactivate' ? val : 'none');
  }

  protected applyBulkAction(): void {
    const action = this.bulkAction();
    const selected = this.selectedDoctorIds();
    if (action === 'none' || selected.size === 0) {
      this.notify('Select at least one doctor and choose an action (Activate / Deactivate) to apply.');
      return;
    }
    const nextState = action === 'activate';
    const nextMap = { ...this.doctorStatusOverrides() };
    for (const id of selected) {
      nextMap[id] = nextState;
      if (this.sessions.can()('doctor:manage')) {
        void this.clinic.updateDoctor(id, { isActive: nextState }, this.projects.selectedProjectId() ?? undefined).catch(() => undefined);
      }
    }
    this.doctorStatusOverrides.set(nextMap);
    this.selectedDoctorIds.set(new Set());
    this.bulkAction.set('none');
    this.notify(`Updated status for ${selected.size} doctor(s).`);
  }

  protected exportDoctorsCsv(): void {
    const rows = this.filteredDoctors();
    const header = ['Name', 'Specialty', 'Start Hour', 'End Hour', 'Slot Duration', 'Status'];
    const lines = [
      header,
      ...rows.map((d) => [
        d.name ?? '',
        d.specialty ?? '',
        d.startHour ?? '',
        d.endHour ?? '',
        String(d.slotDuration ?? ''),
        this.isDoctorActive(d) ? 'Active' : 'Inactive',
      ]),
    ];
    const csv = lines.map((line) => line.map((c) => `"${c.replaceAll('"', '""')}"`).join(',')).join('\r\n');
    const blob = new Blob([`\ufeff${csv}`], { type: 'text/csv;charset=utf-8;' });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = 'doctor-schedules.csv';
    anchor.click();
    URL.revokeObjectURL(url);
    this.notify('Exported doctor schedule roster to CSV.');
  }

  protected onSpecialtyFilterChange(event: Event): void {
    this.specialtyFilter.set((event.target as HTMLSelectElement).value);
    this.doctorPage.set(1);
  }

  protected onSearchInput(event: Event): void {
    this.searchTerm.set((event.target as HTMLInputElement).value);
    this.doctorPage.set(1);
  }

  protected toggleAdvancedFilter(): void {
    this.showAdvancedFilter.set(!this.showAdvancedFilter());
  }

  protected onStatusFilterChange(event: Event): void {
    const val = (event.target as HTMLSelectElement).value;
    this.statusFilter.set(val === 'active' || val === 'inactive' ? val : 'all');
    this.doctorPage.set(1);
  }

  protected resetDoctorFilters(): void {
    this.searchTerm.set('');
    this.specialtyFilter.set('all');
    this.statusFilter.set('all');
    this.doctorPage.set(1);
  }

  protected onNewDoctorClick(): void {
    void this.router.navigate(['/app/clinic/doctors']);
  }

  protected isDoctorSelected(id: string | undefined): boolean {
    return !!id && this.selectedDoctorIds().has(id);
  }

  protected toggleDoctorSelection(id: string | undefined, event: Event): void {
    if (!id) return;
    const checked = (event.target as HTMLInputElement).checked;
    const next = new Set(this.selectedDoctorIds());
    if (checked) next.add(id);
    else next.delete(id);
    this.selectedDoctorIds.set(next);
  }

  protected toggleSelectAllDoctors(event: Event): void {
    const checked = (event.target as HTMLInputElement).checked;
    const next = new Set(this.selectedDoctorIds());
    for (const doc of this.pagedDoctors()) {
      if (!doc.id) continue;
      if (checked) next.add(doc.id);
      else next.delete(doc.id);
    }
    this.selectedDoctorIds.set(next);
  }

  protected isDoctorActive(doc: ClinicDoctor): boolean {
    const overrides = this.doctorStatusOverrides();
    if (doc.id && doc.id in overrides) return overrides[doc.id];
    return doc.isActive !== false;
  }

  protected toggleDoctorStatus(doc: ClinicDoctor, event: Event): void {
    if (!doc.id) return;
    const checked = (event.target as HTMLInputElement).checked;
    this.doctorStatusOverrides.set({
      ...this.doctorStatusOverrides(),
      [doc.id]: checked,
    });
    if (this.sessions.can()('doctor:manage')) {
      void this.clinic.updateDoctor(doc.id, { isActive: checked }, this.projects.selectedProjectId() ?? undefined).catch(() => undefined);
    }
    this.notify(`${doc.name ?? 'Doctor'} marked as ${checked ? 'Active' : 'Inactive'}.`);
  }

  protected getDoctorWorkingDays(doc: ClinicDoctor): readonly number[] {
    const id = doc.id;
    if (id && this.scheduleOverrides()[id]?.workingDays) {
      return this.scheduleOverrides()[id]!.workingDays!;
    }
    return doc.workingDays?.length ? doc.workingDays : [0, 1, 2, 3, 4];
  }

  protected getDoctorStartHour(doc: ClinicDoctor): string {
    const id = doc.id;
    if (id && this.scheduleOverrides()[id]?.startHour) {
      return this.scheduleOverrides()[id]!.startHour!;
    }
    return doc.startHour ?? '09:00';
  }

  protected getDoctorEndHour(doc: ClinicDoctor): string {
    const id = doc.id;
    if (id && this.scheduleOverrides()[id]?.endHour) {
      return this.scheduleOverrides()[id]!.endHour!;
    }
    return doc.endHour ?? '17:00';
  }

  protected getDoctorSlotDuration(doc: ClinicDoctor): number {
    const id = doc.id;
    if (id && this.scheduleOverrides()[id]?.slotDuration) {
      return this.scheduleOverrides()[id]!.slotDuration!;
    }
    return doc.slotDuration ?? 30;
  }

  protected openDoctorSession(doc: ClinicDoctor): void {
    this.activeDoctor.set(doc);
    this.drawerTab.set('slots');
    if (doc.id) {
      this.clinician.setValue(doc.id, { emitEvent: false });
    }
    if (!this.day.value) {
      const today = new Date().toISOString().slice(0, 10);
      this.day.setValue(today, { emitEvent: false });
    }
    void this.loadSlots();
    this.isSessionDrawerOpen.set(true);
    this.notify(`Opened bookable session slots for ${doc.name ?? 'doctor'}.`);
  }

  protected editDoctorSchedule(doc: ClinicDoctor): void {
    this.activeDoctor.set(doc);
    this.drawerTab.set('schedule');
    this.editStartHour.set(this.getDoctorStartHour(doc));
    this.editEndHour.set(this.getDoctorEndHour(doc));
    this.editSlotDuration.set(this.getDoctorSlotDuration(doc));
    this.editWorkingDays.set(new Set(this.getDoctorWorkingDays(doc)));
    this.isSessionDrawerOpen.set(true);
  }

  protected closeSessionDrawer(): void {
    this.isSessionDrawerOpen.set(false);
  }

  protected selectQuickDate(offset: number): void {
    const d = new Date();
    d.setDate(d.getDate() + offset);
    const dateStr = d.toISOString().slice(0, 10);
    this.day.setValue(dateStr, { emitEvent: false });
    void this.loadSlots();
  }

  protected quickHoldSlot(_slot: ClinicSlot): void {
    const doc = this.activeDoctor();
    this.router.navigate(['/app/clinic/appointments'], {
      queryParams: {
        create: '1',
        doctorId: doc?.id ?? undefined,
      },
    });
  }

  protected toggleEditDay(dayIndex: number, event: Event): void {
    const checked = (event.target as HTMLInputElement).checked;
    const next = new Set(this.editWorkingDays());
    if (checked) {
      next.add(dayIndex);
    } else {
      next.delete(dayIndex);
    }
    this.editWorkingDays.set(next);
  }

  protected saveDoctorSchedule(): void {
    const doc = this.activeDoctor();
    if (!doc?.id) return;
    const current = this.scheduleOverrides();
    const updated = {
      workingDays: Array.from(this.editWorkingDays()),
      startHour: this.editStartHour(),
      endHour: this.editEndHour(),
      slotDuration: this.editSlotDuration(),
    };
    this.scheduleOverrides.set({
      ...current,
      [doc.id]: updated,
    });
    this.notify(`Working shift schedule saved for ${doc.name ?? 'Doctor'}.`);
    void this.loadSlots();
    this.isSessionDrawerOpen.set(false);
  }

  protected viewDoctorProfile(doc: ClinicDoctor): void {
    this.selectedProfileDoctor.set(doc);
  }

  protected openDoctorPermissions(doc: ClinicDoctor): void {
    if (this.sessions.can()('system-health:read')) {
      void this.router.navigate(['/app/admin']);
      return;
    }
    this.selectedProfileDoctor.set(doc);
    this.notify(`Viewing schedule & access profile for ${doc.name ?? 'doctor'}.`);
  }

  protected editDoctor(doc: ClinicDoctor): void {
    if (this.sessions.can()('doctor:read-management')) {
      void this.router.navigate(['/app/clinic/doctors']);
      return;
    }
    this.editDoctorSchedule(doc);
  }

  protected deleteDoctorRow(doc: ClinicDoctor): void {
    if (!doc.id) return;
    const next = new Set(this.deletedDoctorIds());
    next.add(doc.id);
    this.deletedDoctorIds.set(next);
    if (this.sessions.can()('doctor:manage')) {
      void this.clinic.deleteDoctor(doc.id, this.projects.selectedProjectId() ?? undefined).catch(() => undefined);
    }
    this.notify(`Removed ${doc.name ?? 'doctor'} from active schedule view.`);
  }

  protected setDoctorPage(page: number): void {
    if (page < 1 || page > this.doctorPageCount()) return;
    this.doctorPage.set(page);
  }

  private notify(message: string): void {
    this.scheduleToast.set(message);
    if (this.toastTimeout) clearTimeout(this.toastTimeout);
    this.toastTimeout = setTimeout(() => this.scheduleToast.set(null), 3500);
  }
}

