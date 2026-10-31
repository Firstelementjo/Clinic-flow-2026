/**
 * `/app/clinic/calendar` — the appointment schedule, grouped by day or operatory chairs.
 *
 * ## Which route this reads
 *
 * `GET /api/v1/clinic/appointments?projectId=&from=&to=&cursor=` — `ClinicApi.appointments`,
 * behind `appointment:read`. The response publishes `from`, `to` and `nextCursor`, and this
 * screen renders the window the server resolved.
 *
 * ## Views & Interactions
 *
 * - Agenda View: Day-grouped appointment schedule with keyset pagination.
 * - Operatory Chairs View: Multi-chair dental layout (Operatory 1, Operatory 2, Surgery Suite, etc.)
 * - Clinicians View: Doctor-grouped columns for multi-doctor practice workflow.
 * - Quick Status Transitions: One-click actions (Confirm, Arrive, In-Chair, Complete, Cancel).
 */
import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import {
  ClinicApi,
  type ClinicAppointment,
  type ClinicAppointmentPage,
  type ClinicDoctor,
} from '../../core/api/endpoints';
import { ProjectContextService } from '../../core/context/project-context.service';
import { FormatService } from '../../core/format/format.service';
import { I18nService } from '../../core/i18n/i18n.service';
import type { Namespace } from '../../core/i18n/i18n.types';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { ResourceStore } from '../../core/state/resource';
import { AsyncStateComponent } from '../../ui/async-state.component';
import { IconComponent } from '../../ui/icon.component';
import { PageHeaderComponent } from '../../ui/page-header.component';
import { StatusChipComponent } from '../../ui/status-chip.component';
import { ClinicPatientApi, type ClinicResourceDetail } from './clinic-patient.api';
import { clinicAdminStrings } from './clinic-admin.strings';
import { clinicStrings } from './clinic.strings';

/** The window the screen asks for, in days, ending now. The server may bound it. */
const WINDOW_DAYS = 30;

interface DayGroup {
  readonly key: string;
  readonly anchor: string | null;
  readonly rows: readonly ClinicAppointment[];
}

interface ColumnGroup {
  readonly id: string;
  readonly title: string;
  readonly subtitle?: string;
  readonly rows: readonly ClinicAppointment[];
}

function toDateInputValue(d: Date): string {
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

export interface CalendarMonthEvent {
  readonly id: string;
  readonly dateIso: string;
  readonly timeLabel: string;
  readonly title: string;
  readonly patientName: string;
  readonly doctorName: string;
  readonly status?: string;
  readonly raw?: ClinicAppointment;
}

export interface CalendarMonthDay {
  readonly dateIso: string;
  readonly dayNumber: number;
  readonly inCurrentMonth: boolean;
  readonly isToday: boolean;
  readonly events: readonly CalendarMonthEvent[];
}

@Component({
  selector: 'fe-clinic-calendar-page',
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
    <div class="fe-page tl-calendar-page-container" data-testid="clinic-calendar">
      <fe-page-header [title]="'clinic.calendar.title'" [description]="'clinic.calendar.description'">
        <div class="header-actions">
          <button type="button" class="fe-btn" (click)="load()">{{ 'clinic.calendar.refresh' | t }}</button>
          <button
            type="button"
            class="fe-btn fe-btn--primary"
            data-testid="book-appointment-btn"
            [routerLink]="['/app/clinic/appointments']"
            [queryParams]="{ create: '1' }"
          >
            + {{ 'clinicAdmin.appointments.createTitle' | t }}
          </button>
        </div>
      </fe-page-header>

      <!-- Calendar Controls Strip -->
      <div class="fe-card calendar-toolbar-card">
        <div class="calendar-toolbar">
          <div class="view-switch-group" role="tablist">
            <button
              type="button"
              class="tab-btn"
              [class.active]="viewMode() === 'month'"
              (click)="setViewMode('month')"
            >
              {{ 'clinic.calendar.view.month' | t }}
            </button>
            <button
              type="button"
              class="tab-btn"
              [class.active]="viewMode() === 'agenda'"
              (click)="setViewMode('agenda')"
            >
              {{ 'clinic.calendar.view.agenda' | t }}
            </button>
            <button
              type="button"
              class="tab-btn"
              [class.active]="viewMode() === 'chairs'"
              (click)="setViewMode('chairs')"
            >
              {{ 'clinic.calendar.view.chairs' | t }}
            </button>
            <button
              type="button"
              class="tab-btn"
              [class.active]="viewMode() === 'doctors'"
              (click)="setViewMode('doctors')"
            >
              {{ 'clinic.calendar.view.doctors' | t }}
            </button>
          </div>

          <div class="filter-controls">
            <div class="filter-search-wrapper">
              <svg class="search-icon" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
                <circle cx="11" cy="11" r="8"></circle>
                <line x1="21" y1="21" x2="16.65" y2="16.65"></line>
              </svg>
              <input
                type="search"
                class="fe-input filter-search"
                [placeholder]="'clinic.calendar.filter.search' | t"
                [ngModel]="searchQuery()"
                (ngModelChange)="searchQuery.set($event)"
              />
            </div>

            <button
              type="button"
              class="tl-filter-btn"
              [class.is-active]="showFilterRow() || activeFilterCount() > 0"
              (click)="toggleFilterRow()"
              [attr.aria-expanded]="showFilterRow()"
              aria-label="Filter appointments"
            >
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
                <polygon points="22 3 2 3 10 12.46 10 19 14 21 14 12.46 22 3" />
              </svg>
              <span>{{ 'clinicAdmin.appointments.advancedFilter' | t }}</span>
              @if (activeFilterCount() > 0) {
                <span class="filter-badge">{{ activeFilterCount() }}</span>
              }
            </button>
          </div>
        </div>

        @if (showFilterRow()) {
          <div class="tl-calendar-filter-row">
            <div class="tl-filter-field">
              <label class="tl-filter-label">{{ 'clinic.calendar.filter.allDoctors' | t }}</label>
              <select
                class="fe-input tl-filter-select"
                [ngModel]="filterDoctorId()"
                (ngModelChange)="filterDoctorId.set($event)"
              >
                <option value="">{{ 'clinic.calendar.filter.allDoctors' | t }}</option>
                @for (doc of allAvailableDoctors(); track doc.id) {
                  <option [value]="doc.id">{{ doc.name }}</option>
                }
              </select>
            </div>

            <div class="tl-filter-field">
              <label class="tl-filter-label">{{ 'clinic.calendar.filter.allChairs' | t }}</label>
              <select
                class="fe-input tl-filter-select"
                [ngModel]="filterResourceId()"
                (ngModelChange)="filterResourceId.set($event)"
              >
                <option value="">{{ 'clinic.calendar.filter.allChairs' | t }}</option>
                @for (res of allAvailableResources(); track res.id) {
                  <option [value]="res.id">{{ res.name }}</option>
                }
              </select>
            </div>

            <div class="tl-filter-field">
              <label class="tl-filter-label">{{ 'clinic.calendar.filter.allStatuses' | t }}</label>
              <select
                class="fe-input tl-filter-select"
                [ngModel]="filterStatus()"
                (ngModelChange)="filterStatus.set($event)"
              >
                <option value="">{{ 'clinic.calendar.filter.allStatuses' | t }}</option>
                <option value="PENDING">PENDING (قيد الانتظار)</option>
                <option value="CONFIRMED">CONFIRMED (مؤكد)</option>
                <option value="CHECKED_IN">CHECKED_IN (وصل المريض)</option>
                
                <option value="COMPLETED">COMPLETED (مكتمل)</option>
                <option value="CANCELLED">CANCELLED (ملغي)</option>
                <option value="NO_SHOW">NO_SHOW (لم يحضر)</option>
              </select>
            </div>

            @if (hasActiveFilters()) {
              <div class="tl-filter-clear-wrapper">
                <button type="button" class="tl-filter-clear-btn" (click)="clearAllFilters()">
                  {{ 'clinicAdmin.common.clearFilters' | t }}
                </button>
              </div>
            }
          </div>
        }
      </div>

      @if (page.error()) {
        <fe-async-state
          [loading]="false"
          [empty]="false"
          [error]="page.error()"
          [hasValue]="false"
          [loadingLabel]="'state.loading' | t"
          [errorTitle]="'clinic.error.title' | t"
          [errorBody]="'clinic.error.body' | t"
          [requestId]="errorRequestId()"
          [requestIdLabel]="'state.requestId' | t"
          [retryLabel]="'clinic.retry' | t"
          (retry)="load()"
        />
      }

      @if (viewMode() === 'month') {
        <div class="tl-calendar-section" data-testid="clinic-monthly-calendar">
          <div class="tl-calendar-toolbar">
            <div class="tl-cal-nav-left">
              <button type="button" class="tl-cal-btn" (click)="goCalendarToday()">Today</button>
              <div class="tl-cal-arrows">
                <button type="button" class="tl-cal-arrow-btn" (click)="prevCalendarMonth()" aria-label="Previous Month">‹</button>
                <button type="button" class="tl-cal-arrow-btn" (click)="nextCalendarMonth()" aria-label="Next Month">›</button>
              </div>
            </div>
            <h3 class="tl-cal-month-title">{{ calendarMonthTitle() }}</h3>
            <div class="tl-cal-nav-right">
              <button
                type="button"
                class="tl-btn tl-btn-teal"
                style="font-size: 12px; padding: 6px 14px; border: none; cursor: pointer; border-radius: 6px; font-weight: 600;"
                [routerLink]="['/app/clinic/appointments']"
                [queryParams]="{ create: '1' }"
              >
                + Appointment List
              </button>
            </div>
          </div>

          <div class="tl-cal-grid-wrap">
            <div class="tl-cal-weekdays">
              @for (wd of calendarWeekDays; track wd) {
                <div class="tl-cal-weekday">{{ wd }}</div>
              }
            </div>
            <div class="tl-cal-days-grid">
              @for (day of calendarGridDays(); track day.dateIso) {
                <div
                  class="tl-cal-day-cell"
                  [class.is-outside]="!day.inCurrentMonth"
                  [class.is-today]="day.isToday"
                >
                  <div class="tl-cal-day-num">{{ day.dayNumber }}</div>
                  <div class="tl-cal-day-events">
                    @for (ev of day.events; track ev.id) {
                      <button
                        type="button"
                        class="tl-cal-event-pill"
                        (click)="openCalendarEvent(ev)"
                        [title]="ev.patientName + ' — ' + ev.title + (ev.doctorName ? ' (' + ev.doctorName + ')' : '')"
                      >
                        <span class="tl-cal-ev-time">{{ ev.timeLabel }}</span>
                        <span class="tl-cal-ev-title">{{ ev.title }}</span>
                      </button>
                    }
                  </div>
                </div>
              }
            </div>
          </div>
        </div>
      }

      <!-- View: Agenda / Day Groups -->
      @if (viewMode() === 'agenda') {
        <div class="tl-agenda-container">
          <div class="tl-agenda-meta">
            <p class="fe-sub" data-testid="clinic-calendar-window">
              {{ 'clinic.calendar.window' | t }}:
              <span class="fe-mono" dir="ltr">{{ serverWindow() }}</span>
            </p>
            <p class="fe-sub">{{ 'clinic.calendar.dayBoundaries' | t }}</p>
          </div>

          @if (!page.error()) {
            <fe-async-state
              [loading]="page.loading() && rows().length === 0"
              [empty]="page.value() !== null && rows().length === 0"
              [error]="null"
              [hasValue]="page.value() !== null"
              [loadingLabel]="'state.loading' | t"
              [loadingHint]="'state.loadingHint' | t"
              [emptyTitle]="'clinic.calendar.empty.title' | t"
              [emptyBody]="'clinic.calendar.empty.body' | t"
              [retryLabel]="'clinic.retry' | t"
              (retry)="load()"
            />
          }

          @for (day of days(); track day.key; let index = $index) {
            <section class="fe-card" [attr.aria-labelledby]="'clinic-day-' + index">
              <div class="day-header">
                <h3 [id]="'clinic-day-' + index" class="day">
                  @if (day.anchor) {
                    <time [attr.datetime]="day.anchor">{{ format.date(day.anchor) }}</time>
                  } @else {
                    {{ 'clinic.calendar.empty.title' | t }}
                  }
                </h3>
                <span class="day-count">{{ day.rows.length }} مواعيد</span>
              </div>

              <table class="fe-table">
                <caption class="fe-sr">{{ 'clinic.schedule.title' | t }}</caption>
                <thead>
                  <tr>
                    <th scope="col">{{ 'clinic.schedule.patient' | t }}</th>
                    <th scope="col">{{ 'clinic.schedule.clinician' | t }}</th>
                    <th scope="col">{{ 'clinic.calendar.col.time' | t }}</th>
                    <th scope="col">{{ 'clinic.schedule.duration' | t }}</th>
                    <th scope="col">{{ 'clinic.procedures.title' | t }}</th>
                    <th scope="col">الحالة / الإجراء السريع</th>
                    <th scope="col">{{ 'clinic.calendar.open' | t }}</th>
                  </tr>
                </thead>
                <tbody>
                  @for (row of day.rows; track row.id ?? $index) {
                    <tr>
                      <th scope="row">
                        <div class="patient-cell">
                          <span class="patient-name">{{ row.patientName ?? '—' }}</span>
                          @if (row.isAiProposed === true) {
                            <fe-status-chip class="ai-flag" tone="ai">
                              {{ 'clinic.schedule.aiProposed' | t }}
                            </fe-status-chip>
                          }
                        </div>
                      </th>
                      <td>{{ row.doctorName ?? '—' }}</td>
                      <td class="fe-mono">{{ format.time(row.appointmentDate) }}</td>
                      <td class="fe-num">{{ row.durationMinutes ?? '—' }} د</td>
                      <td>{{ row.service ?? '—' }}</td>
                      <td>
                        <div class="status-action-cell">
                          <span class="status-badge" [class]="'status--' + (row.status?.toLowerCase() || 'pending')">
                            {{ row.status }}
                          </span>
                          @if (row.status === 'PENDING' || row.status === 'BOOKED') {
                            <button
                              type="button"
                              class="micro-btn micro-btn--confirm"
                              (click)="updateStatus(row, 'CONFIRMED')"
                            >
                              تأكيد
                            </button>
                          }
                          @if (row.status === 'CONFIRMED') {
                            <button
                              type="button"
                              class="micro-btn micro-btn--arrive"
                              (click)="updateStatus(row, 'CHECKED_IN')"
                            >
                              وصل
                            </button>
                          }
                          @if (row.status === 'CHECKED_IN') {
                            <a
                              class="micro-btn micro-btn--arrive"
                              [routerLink]="['/app/clinic/encounters', row.id]"
                              [queryParams]="{
                                appointmentId: row.id,
                                patientId: row.patientId,
                                patientName: row.patientName,
                                doctorId: row.doctorId,
                                doctorName: row.doctorName,
                                service: row.service,
                                status: row.status
                              }"
                              title="بدء الزيارة السريرية"
                            >
                              الزيارة السريرية
                            </a>
                            <button
                              type="button"
                              class="micro-btn micro-btn--complete"
                              (click)="updateStatus(row, 'COMPLETED')"
                              title="إتمام الموعد"
                            >
                              إتمام
                            </button>
                          }
                        </div>
                      </td>
                      <td>
                        <a class="fe-link" [routerLink]="['/app/clinic/appointments', row.id]">
                          {{ 'clinic.calendar.open' | t }}
                        </a>
                      </td>
                    </tr>
                  }
                </tbody>
              </table>
            </section>
          }

          @if (rows().length > 0) {
            <div class="paging">
              @if (nextCursor(); as cursor) {
                <button
                  type="button"
                  class="fe-btn"
                  data-testid="clinic-calendar-more"
                  [disabled]="page.loading()"
                  (click)="loadMore(cursor)"
                >
                  {{ 'clinic.calendar.more' | t }}
                </button>
              } @else {
                <p class="fe-sub" data-testid="clinic-calendar-no-more">{{ 'clinic.calendar.noMore' | t }}</p>
              }
            </div>
          }
        </div>
      }

      <!-- View: Dental Operatory Chairs -->
      @if (viewMode() === 'chairs') {
        <div class="columns-grid">
          @for (col of chairColumns(); track col.id) {
            <div class="chair-column fe-card">
              <div class="column-header">
                <h4>{{ col.title }}</h4>
                <span class="column-count">{{ col.rows.length }}</span>
              </div>
              <div class="column-cards">
                @if (col.rows.length === 0) {
                  <p class="empty-col">لا توجد مواعيد مخصصة لهذا الكرسي</p>
                }
                @for (appt of col.rows; track appt.id) {
                  <div class="appt-card" [class]="'border--' + (appt.status?.toLowerCase() || 'pending')">
                    <div class="card-time">
                      <span class="fe-mono">{{ format.time(appt.appointmentDate) }}</span>
                      <span class="duration">{{ appt.durationMinutes }} د</span>
                    </div>
                    <div class="card-patient">{{ appt.patientName }}</div>
                    <div class="card-service">{{ appt.service || 'استشارة سريرية' }}</div>
                    <div class="card-doctor">
                      <fe-icon name="doctor" class="card-doctor-icon" />
                      {{ appt.doctorName }}
                    </div>
                    <div class="card-actions">
                      <span class="status-badge" [class]="'status--' + (appt.status?.toLowerCase() || 'pending')">
                        {{ appt.status }}
                      </span>
                      <a class="fe-link micro" [routerLink]="['/app/clinic/appointments', appt.id]">فتح</a>
                    </div>
                  </div>
                }
              </div>
            </div>
          }
        </div>
      }

      <!-- View: Doctors View -->
      @if (viewMode() === 'doctors') {
        <div class="columns-grid">
          @for (col of doctorColumns(); track col.id) {
            <div class="chair-column fe-card">
              <div class="column-header">
                <div>
                  <h4>{{ col.title }}</h4>
                  <small class="fe-sub">{{ col.subtitle }}</small>
                </div>
                <span class="column-count">{{ col.rows.length }}</span>
              </div>
              <div class="column-cards">
                @if (col.rows.length === 0) {
                  <p class="empty-col">لا توجد مواعيد مجدولة لهذا الطبيب</p>
                }
                @for (appt of col.rows; track appt.id) {
                  <div class="appt-card" [class]="'border--' + (appt.status?.toLowerCase() || 'pending')">
                    <div class="card-time">
                      <span class="fe-mono">{{ format.time(appt.appointmentDate) }}</span>
                      <span class="duration">{{ appt.durationMinutes }} د</span>
                    </div>
                    <div class="card-patient">{{ appt.patientName }}</div>
                    <div class="card-service">{{ appt.service || 'استشارة سريرية' }}</div>
                    <div class="card-actions">
                      <span class="status-badge" [class]="'status--' + (appt.status?.toLowerCase() || 'pending')">
                        {{ appt.status }}
                      </span>
                      <a class="fe-link micro" [routerLink]="['/app/clinic/appointments', appt.id]">فتح</a>
                    </div>
                  </div>
                }
              </div>
            </div>
          }
        </div>
      }

    </div>
  `,
  styles: [
    `
      :host {
        display: block;
        height: 100%;
        width: 100%;
        overflow: hidden;
      }
      .tl-calendar-page-container {
        height: calc(100dvh - 52px);
        max-height: calc(100dvh - 52px);
        display: flex;
        flex-direction: column;
        padding: 6px 14px 6px;
        gap: 6px;
        overflow: hidden;
        box-sizing: border-box;
      }
      :host ::ng-deep .fe-page-header {
        margin-block-end: 2px;
        gap: var(--fe-space-2);
        flex-wrap: nowrap;
      }
      :host ::ng-deep .fe-page-header__title {
        font-size: 1.15rem;
        margin: 0;
        line-height: 1.2;
      }
      :host ::ng-deep .fe-page-header__description {
        font-size: 0.78rem;
        margin: 0;
      }
      :host ::ng-deep .fe-page-header__actions .fe-btn {
        padding: 4px 10px;
        font-size: 0.82rem;
        height: 30px;
      }
      .header-actions {
        display: flex;
        gap: var(--fe-space-2);
      }
      .calendar-toolbar-card {
        padding: 0;
        margin: 0;
        flex-shrink: 0;
        border-radius: var(--fe-radius);
        overflow: visible;
      }
      .calendar-toolbar {
        display: flex;
        flex-wrap: wrap;
        gap: var(--fe-space-2);
        align-items: center;
        justify-content: space-between;
        padding: 4px 8px;
      }
      .view-switch-group {
        display: flex;
        background: var(--line-soft);
        padding: 2px;
        border-radius: var(--fe-radius);
        gap: 2px;
      }
      .tab-btn {
        background: transparent;
        border: none;
        padding: 3px 10px;
        border-radius: var(--fe-radius);
        font-size: 0.82rem;
        font-weight: 500;
        color: var(--mute);
        cursor: pointer;
        transition: all 0.15s ease;
      }
      .tab-btn.active {
        background: var(--surf);
        color: var(--pri);
        box-shadow: var(--shadow-xs);
      }
      .filter-controls {
        display: flex;
        align-items: center;
        gap: var(--fe-space-2, 8px);
      }
      .filter-search-wrapper {
        position: relative;
        display: flex;
        align-items: center;
      }
      .filter-search-wrapper .search-icon {
        position: absolute;
        left: 10px;
        color: var(--tl-muted, #94a3b8);
        pointer-events: none;
      }
      :host-context([dir='rtl']) .filter-search-wrapper .search-icon {
        left: auto;
        right: 10px;
      }
      .filter-search {
        min-width: 170px;
        max-width: 230px;
        height: 34px;
        padding-left: 30px;
        padding-right: 12px;
        font-size: 0.85rem;
        border-radius: var(--fe-radius, 6px);
      }
      :host-context([dir='rtl']) .filter-search {
        padding-left: 12px;
        padding-right: 30px;
      }
      .tl-filter-btn {
        display: inline-flex;
        align-items: center;
        gap: 6px;
        height: 34px;
        padding: 0 12px;
        font-size: 0.85rem;
        font-weight: 500;
        color: var(--tl-fg, inherit);
        background: var(--tl-surface-subtle, rgba(255, 255, 255, 0.05));
        border: 1px solid var(--tl-border, var(--line));
        border-radius: var(--fe-radius, 6px);
        cursor: pointer;
        transition: all 0.15s ease;
      }
      .tl-filter-btn:hover {
        background: var(--tl-surface-hover, rgba(255, 255, 255, 0.08));
        border-color: var(--tl-teal, #14b8a6);
      }
      .tl-filter-btn.is-active {
        color: #fff;
        background: rgba(20, 184, 166, 0.18);
        border-color: var(--tl-teal, #14b8a6);
      }
      .filter-badge {
        display: inline-flex;
        align-items: center;
        justify-content: center;
        min-width: 18px;
        height: 18px;
        padding: 0 5px;
        font-size: 0.72rem;
        font-weight: 700;
        border-radius: 10px;
        background: var(--tl-teal, #14b8a6);
        color: #0f172a;
      }
      .tl-calendar-filter-row {
        display: flex;
        align-items: flex-end;
        gap: var(--fe-space-2);
        flex-wrap: wrap;
        padding: 4px 10px;
        border-top: 1px solid var(--line);
        background: var(--surf2, rgba(255, 255, 255, 0.02));
        animation: filterSlideDown 0.15s ease-out;
      }
      @keyframes filterSlideDown {
        from { opacity: 0; transform: translateY(-4px); }
        to { opacity: 1; transform: translateY(0); }
      }
      .tl-filter-field {
        display: flex;
        flex-direction: column;
        gap: 2px;
        min-width: 150px;
        flex: 1;
        max-width: 220px;
      }
      .tl-filter-label {
        color: var(--mute);
        font-size: var(--fe-text-xs, 0.72rem);
        font-weight: 600;
      }
      .tl-filter-select {
        height: 28px;
        font-size: 0.8rem;
        padding: 0 var(--fe-space-2);
        border-radius: var(--fe-radius, 6px);
      }
      .tl-filter-clear-wrapper {
        display: flex;
        align-items: center;
        padding-bottom: 6px;
      }
      .tl-filter-clear-btn {
        border: 0;
        background: none;
        color: var(--tl-teal, #0d9488);
        cursor: pointer;
        font-size: 0.82rem;
        font-weight: 600;
        text-decoration: underline;
        padding: 4px 8px;
        border-radius: 4px;
        transition: opacity 0.15s ease;
      }
      .tl-filter-clear-btn:hover {
        opacity: 0.8;
      }
      .tl-agenda-container {
        flex: 1;
        min-height: 0;
        display: flex;
        flex-direction: column;
        gap: var(--fe-space-3);
        overflow-y: auto;
        overflow-x: hidden;
        padding-inline-end: 4px;
      }
      .tl-agenda-meta {
        display: flex;
        align-items: center;
        justify-content: space-between;
        flex-wrap: wrap;
        gap: 8px;
        flex-shrink: 0;
      }
      .tl-agenda-meta p {
        margin: 0;
        font-size: 0.8rem;
      }
      .tl-agenda-container .fe-card {
        margin-block-start: 0;
      }
      .fe-card {
        overflow-x: auto;
        margin-block-start: var(--fe-space-4);
      }
      .day-header {
        display: flex;
        justify-content: space-between;
        align-items: center;
        margin-block-end: var(--fe-space-2);
      }
      .day-count {
        font-size: 0.85rem;
        color: var(--mute);
        font-weight: 500;
      }
      .fe-table {
        min-inline-size: 34rem;
      }
      .day {
        font-size: var(--fe-text-lg);
        color: var(--ink);
        margin: 0;
      }
      .fe-link {
        color: var(--pri);
        text-decoration-thickness: 1px;
        text-underline-offset: 2px;
      }
      .fe-link.micro {
        font-size: 0.8rem;
      }
      .patient-cell {
        display: flex;
        align-items: center;
        gap: var(--fe-space-2);
      }
      .patient-name {
        font-weight: 600;
        color: var(--ink);
      }
      .ai-flag {
        margin-inline-start: var(--fe-space-2);
      }
      .status-action-cell {
        display: flex;
        align-items: center;
        gap: var(--fe-space-2);
        flex-wrap: wrap;
      }
      .status-badge {
        font-size: 0.75rem;
        font-weight: 600;
        padding: 2px 8px;
        border-radius: 999px;
        text-transform: uppercase;
        letter-spacing: 0.02em;
        background: var(--line-soft);
        color: var(--mute);
      }
      .status--confirmed {
        background: var(--pri-soft);
        color: var(--accent-teal-strong);
      }
      .status--checked_in {
        background: var(--accent-amber-soft);
        color: var(--accent-amber-ink);
      }
      .status--in_progress {
        background: var(--accent-violet-soft);
        color: var(--accent-violet-ink);
      }
      .status--completed {
        background: var(--accent-green-soft);
        color: var(--accent-green-ink);
      }
      .status--cancelled,
      .status--no_show {
        background: var(--accent-red-soft);
        color: var(--accent-red-ink);
      }
      .micro-btn {
        border: none;
        padding: 2px 8px;
        border-radius: var(--fe-radius);
        font-size: 0.78rem;
        font-weight: 500;
        cursor: pointer;
        transition: background 0.15s ease;
      }
      .micro-btn--confirm {
        background: var(--accent-teal-strong);
        color: var(--on-accent);
      }
      .micro-btn--arrive {
        background: var(--accent-amber);
        color: var(--on-accent);
      }
      .micro-btn--chair {
        background: var(--accent-violet);
        color: var(--on-accent);
      }
      .micro-btn--complete {
        background: var(--accent-green);
        color: var(--on-accent);
      }
      .columns-grid {
        flex: 1;
        min-height: 0;
        display: grid;
        grid-auto-flow: column;
        grid-auto-columns: minmax(260px, 320px);
        justify-content: flex-start;
        gap: var(--fe-space-3);
        overflow-x: auto;
        overflow-y: hidden;
        margin: 0;
        padding-bottom: 4px;
      }
      .chair-column {
        height: 100%;
        display: flex;
        flex-direction: column;
        padding: var(--fe-space-3);
        margin: 0;
        overflow: hidden;
        background: var(--surf);
        border: 1px solid var(--line);
        border-radius: var(--fe-radius);
      }
      .column-header {
        display: flex;
        justify-content: space-between;
        align-items: flex-start;
        border-bottom: 1px solid var(--line);
        padding-bottom: var(--fe-space-2);
        margin-bottom: var(--fe-space-3);
        flex-shrink: 0;
      }
      .column-header h4 {
        margin: 0;
        font-size: 0.95rem;
        color: var(--ink);
      }
      .column-count {
        background: var(--line-soft);
        font-size: 0.78rem;
        font-weight: 600;
        padding: 2px 6px;
        border-radius: 999px;
        color: var(--mute);
      }
      .column-cards {
        flex: 1;
        min-height: 0;
        display: flex;
        flex-direction: column;
        gap: var(--fe-space-2);
        overflow-y: auto;
        padding-inline-end: 2px;
      }
      .empty-col {
        font-size: 0.82rem;
        color: var(--mute);
        text-align: center;
        padding: var(--fe-space-4) 0;
      }
      .appt-card {
        background: var(--surf);
        border: 1px solid var(--line);
        border-right: 3px solid var(--line-strong);
        border-radius: var(--fe-radius);
        padding: var(--fe-space-2);
        display: flex;
        flex-direction: column;
        gap: 3px;
        font-size: 0.85rem;
      }
      .appt-card.border--confirmed {
        border-right-color: var(--accent-teal-strong);
      }
      .appt-card.border--checked_in {
        border-right-color: var(--accent-amber);
      }
      .appt-card.border--in_progress {
        border-right-color: var(--accent-violet);
      }
      .appt-card.border--completed {
        border-right-color: var(--accent-green);
      }
      .appt-card.border--cancelled,
      .appt-card.border--no_show {
        border-right-color: var(--accent-red);
      }
      .card-time {
        display: flex;
        justify-content: space-between;
        font-size: 0.8rem;
        color: var(--mute);
      }
      .card-patient {
        font-weight: 600;
        color: var(--ink);
      }
      .card-service {
        font-size: 0.8rem;
        color: var(--mute);
      }
      .card-doctor {
        display: flex;
        align-items: center;
        gap: 6px;
        font-size: 0.8rem;
        color: var(--ink);
      }
      .card-doctor-icon {
        color: var(--mute);
        flex: none;
      }
      .card-actions {
        display: flex;
        justify-content: space-between;
        align-items: center;
        margin-top: 4px;
        padding-top: 4px;
        border-top: 1px dashed var(--line);
      }
      .paging {
        margin-block-start: var(--fe-space-4);
      }
      .fe-modal-overlay {
        position: fixed;
        inset: 0;
        background: var(--scrim);
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
        max-width: 36rem;
        box-shadow: var(--shadow-pop);
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
        color: var(--ink);
      }
      .close-btn {
        background: transparent;
        border: none;
        color: var(--mute);
        font-size: 1.4rem;
        cursor: pointer;
      }
      .modal-body {
        padding: var(--fe-space-4);
        display: flex;
        flex-direction: column;
        gap: var(--fe-space-3);
        max-height: 75vh;
        overflow-y: auto;
      }
      .form-row {
        display: grid;
        grid-template-columns: 1fr 1fr;
        gap: var(--fe-space-3);
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
      .modal-footer {
        display: flex;
        justify-content: flex-end;
        gap: var(--fe-space-2);
        padding-top: var(--fe-space-3);
        border-top: 1px solid var(--line);
      }
      .fe-alert--danger {
        padding: var(--fe-space-2) var(--fe-space-3);
        background: var(--bad-soft);
        color: var(--bad);
        border-radius: var(--fe-radius);
        margin: var(--fe-space-3) var(--fe-space-4) 0;
      }
      .fe-alert--warning {
        padding: var(--fe-space-2) var(--fe-space-3);
        background: var(--accent-amber-soft);
        color: var(--accent-amber-deep);
        border: 1px solid var(--accent-amber-line);
        border-radius: var(--fe-radius);
        margin: var(--fe-space-3) var(--fe-space-4) 0;
      }
      .conflict-checkbox {
        display: flex;
        align-items: center;
        gap: var(--fe-space-2);
        margin-top: 6px;
        font-size: 0.85rem;
        cursor: pointer;
      }
      .conflict-warning {
        display: flex;
        align-items: center;
        gap: var(--fe-space-2);
        margin: 0;
      }
      .conflict-warning-icon {
        color: var(--accent-amber-deep);
        flex: none;
      }
      /* Monthly Calendar Grid View */
      .tl-calendar-section {
        flex: 1;
        min-height: 0;
        display: flex;
        flex-direction: column;
        margin: 0;
        padding: 6px 10px 8px;
        background: var(--surf);
        border: 1px solid var(--line);
        border-radius: var(--fe-radius);
        box-shadow: var(--shadow-soft, 0 1px 3px rgba(0, 0, 0, 0.05));
        overflow: hidden;
      }
      .tl-calendar-toolbar {
        display: flex;
        align-items: center;
        justify-content: space-between;
        margin-bottom: 6px;
        flex-shrink: 0;
        gap: 8px;
      }
      .tl-cal-nav-left {
        display: flex;
        align-items: center;
        gap: var(--fe-space-2);
      }
      .tl-cal-btn {
        height: 28px;
        padding: 0 10px;
        border-radius: var(--fe-radius);
        border: 1px solid var(--line);
        background: var(--surf2, var(--line-soft));
        color: var(--ink);
        font-size: 11.5px;
        font-weight: 600;
        cursor: pointer;
        transition: all 0.15s ease;
      }
      .tl-cal-btn:hover {
        background: var(--pri-soft);
        border-color: var(--tl-teal, #0d9488);
        color: var(--tl-teal, #0d9488);
      }
      .tl-cal-arrows {
        display: inline-flex;
        border: 1px solid var(--line);
        border-radius: var(--fe-radius);
        overflow: hidden;
      }
      .tl-cal-arrow-btn {
        width: 28px;
        height: 26px;
        border: none;
        background: var(--surf2, var(--line-soft));
        color: var(--ink);
        font-size: 14px;
        font-weight: 700;
        cursor: pointer;
        transition: all 0.15s ease;
        display: flex;
        align-items: center;
        justify-content: center;
      }
      .tl-cal-arrow-btn:hover {
        background: var(--pri-soft);
        color: var(--tl-teal, #0d9488);
      }
      .tl-cal-month-title {
        margin: 0;
        font-size: 1.05rem;
        font-weight: 700;
        color: var(--ink);
      }
      .tl-cal-grid-wrap {
        flex: 1;
        min-height: 0;
        display: flex;
        flex-direction: column;
        border: 1px solid var(--line);
        border-radius: var(--fe-radius);
        overflow: hidden;
      }
      .tl-cal-weekdays {
        flex-shrink: 0;
        display: grid;
        grid-template-columns: repeat(7, 1fr);
        background: var(--surf2, var(--line-soft));
        border-bottom: 1px solid var(--line);
      }
      .tl-cal-weekday {
        padding: 4px 6px;
        text-align: center;
        font-size: 11px;
        font-weight: 700;
        color: var(--mute);
        border-right: 1px solid var(--line);
      }
      :host-context([dir='rtl']) .tl-cal-weekday {
        border-right: none;
        border-left: 1px solid var(--line);
      }
      .tl-cal-weekday:last-child {
        border-right: none;
        border-left: none;
      }
      .tl-cal-days-grid {
        flex: 1;
        min-height: 0;
        display: grid;
        grid-template-columns: repeat(7, 1fr);
        grid-auto-rows: 1fr;
      }
      .tl-cal-day-cell {
        min-height: 0;
        height: 100%;
        box-sizing: border-box;
        padding: 3px 5px;
        border-right: 1px solid var(--line-soft);
        border-bottom: 1px solid var(--line-soft);
        background: var(--surf);
        display: flex;
        flex-direction: column;
        gap: 2px;
        overflow: hidden;
        transition: background 0.12s ease;
      }
      :host-context([dir='rtl']) .tl-cal-day-cell {
        border-right: none;
        border-left: 1px solid var(--line-soft);
      }
      .tl-cal-day-cell:nth-child(7n) {
        border-right: none;
        border-left: none;
      }
      .tl-cal-day-cell.is-outside {
        background: var(--surf-soft, var(--line-soft));
        opacity: 0.55;
      }
      .tl-cal-day-cell.is-today {
        background: var(--pri-soft);
      }
      .tl-cal-day-cell.is-today .tl-cal-day-num {
        font-weight: 800;
        color: #0d9488;
      }
      .tl-cal-day-num {
        font-size: 11px;
        font-weight: 600;
        line-height: 1;
        color: var(--ink-soft, var(--ink));
        flex-shrink: 0;
      }
      .tl-cal-day-events {
        flex: 1;
        min-height: 0;
        display: flex;
        flex-direction: column;
        gap: 2px;
        overflow-y: auto;
        overflow-x: hidden;
        scrollbar-width: thin;
      }
      .tl-cal-event-pill {
        display: flex;
        align-items: center;
        gap: 3px;
        padding: 2px 4px;
        border-radius: 3px;
        background: #0d9488;
        color: #ffffff;
        border: none;
        font-size: 10px;
        line-height: 1.15;
        cursor: pointer;
        text-align: left;
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
        box-shadow: 0 1px 2px rgba(0, 0, 0, 0.12);
        transition: opacity 0.15s ease, transform 0.15s ease;
        flex-shrink: 0;
      }
      :host-context([dir='rtl']) .tl-cal-event-pill {
        text-align: right;
      }
      .tl-cal-event-pill:hover {
        opacity: 0.9;
        transform: translateY(-1px);
      }
      .tl-cal-ev-time {
        font-weight: 700;
        opacity: 0.95;
        font-size: 9.5px;
        flex-shrink: 0;
      }
      .tl-cal-ev-title {
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
      .tl-btn-teal {
        background: #0d9488;
        color: #ffffff;
        transition: background 0.15s ease;
      }
      .tl-btn-teal:hover {
        background: #0f766e;
      }
    `,
  ],
})
export class ClinicCalendarComponent implements OnInit {
  private readonly clinic = inject(ClinicApi);
  private readonly patientApi = inject(ClinicPatientApi);
  private readonly router = inject(Router);

  protected readonly format = inject(FormatService);
  protected readonly projects = inject(ProjectContextService);

  /** Active view mode: month (default), agenda, chairs (operatory view), doctors */
  protected readonly viewMode = signal<'month' | 'agenda' | 'chairs' | 'doctors'>('month');

  // -- Embedded Monthly Calendar --
  protected readonly calendarWeekDays = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
  protected readonly calendarAnchor = signal<Date>(new Date());
  protected readonly calendarRawEvents = signal<readonly CalendarMonthEvent[]>([
    { id: 'cal-1', dateIso: '2026-10-20', timeLabel: '17:30', title: 'حشوة ضوئية تجميلية كمبوزيت', patientName: 'أحمد محمود العلي', doctorName: 'د. سارة المنصور' },
    { id: 'cal-2', dateIso: '2026-10-20', timeLabel: '16:00', title: 'تقويم أسنان شفاف Invisalign', patientName: 'ريم خالد الشمري', doctorName: 'د. محمد العتيبي' },
    { id: 'cal-3', dateIso: '2026-10-20', timeLabel: '14:45', title: 'تنظيف وتلميع وإزالة الجير للأسنان', patientName: 'سارة عبد الله الفهد', doctorName: 'د. سارة المنصور' },
    { id: 'cal-4', dateIso: '2026-10-20', timeLabel: '13:30', title: 'معالجة عصب الضرس مع الحشوة التجميلية', patientName: 'خالد عمر الدوسري', doctorName: 'د. طارق السالم' },
    { id: 'cal-5', dateIso: '2026-10-20', timeLabel: '12:00', title: 'زراعة سن تيتانيوم متكاملة', patientName: 'نورة عبد العزيز المطيري', doctorName: 'د. محمد العتيبي' },
  ]);

  private readonly monthNames = [
    'January', 'February', 'March', 'April', 'May', 'June',
    'July', 'August', 'September', 'October', 'November', 'December',
  ];

  protected readonly calendarMonthTitle = computed<string>(() => {
    const d = this.calendarAnchor();
    return `${this.monthNames[d.getMonth()]} ${d.getFullYear()}`;
  });

  protected readonly calendarGridDays = computed<readonly CalendarMonthDay[]>(() => {
    const anchor = this.calendarAnchor();
    const year = anchor.getFullYear();
    const month = anchor.getMonth();
    const firstOfMonth = new Date(year, month, 1);
    const startOffset = firstOfMonth.getDay();
    const startDate = new Date(year, month, 1 - startOffset);

    const liveEvents: CalendarMonthEvent[] = this.filteredRows().map((r) => {
      const parsed = this.format.parseApiTimestamp(r.appointmentDate);
      const d = parsed ?? (r.appointmentDate ? new Date(r.appointmentDate) : new Date());
      const hours = isNaN(d.getTime()) ? '' : String(d.getHours()).padStart(2, '0');
      const mins = isNaN(d.getTime()) ? '' : String(d.getMinutes()).padStart(2, '0');
      const timeStr = hours ? `${hours}:${mins}` : '';
      return {
        id: r.id ?? '',
        dateIso: isNaN(d.getTime()) ? '' : toDateInputValue(d),
        timeLabel: timeStr,
        title: r.service ?? 'موعد عيادة',
        patientName: r.patientName ?? 'مريض',
        doctorName: r.doctorName ?? '',
        status: r.status,
        raw: r,
      };
    });

    const allEvents =
      this.rows().length > 0 || this.hasActiveFilters() ? liveEvents : this.calendarRawEvents();
    const days: CalendarMonthDay[] = [];
    const todayStr = toDateInputValue(new Date());

    for (let i = 0; i < 42; i++) {
      const cur = new Date(startDate);
      cur.setDate(startDate.getDate() + i);
      const iso = toDateInputValue(cur);
      const inCurrentMonth = cur.getMonth() === month;
      const isToday = iso === todayStr;
      const dayEvs = allEvents.filter((e) => e.dateIso === iso);
      days.push({
        dateIso: iso,
        dayNumber: cur.getDate(),
        inCurrentMonth,
        isToday,
        events: dayEvs,
      });
    }
    return days;
  });

  protected prevCalendarMonth(): void {
    const curr = this.calendarAnchor();
    this.calendarAnchor.set(new Date(curr.getFullYear(), curr.getMonth() - 1, 1));
  }

  protected nextCalendarMonth(): void {
    const curr = this.calendarAnchor();
    this.calendarAnchor.set(new Date(curr.getFullYear(), curr.getMonth() + 1, 1));
  }

  protected goCalendarToday(): void {
    this.calendarAnchor.set(new Date());
  }

  protected openCalendarEvent(ev: CalendarMonthEvent): void {
    if (ev.raw?.id) {
      this.router.navigate(['/app/clinic/appointments', ev.raw.id]);
    } else {
      this.router.navigate(['/app/clinic/appointments']);
    }
  }

  /** Filter states */
  protected readonly searchQuery = signal<string>('');
  protected readonly filterDoctorId = signal<string>('');
  protected readonly filterResourceId = signal<string>('');
  protected readonly filterStatus = signal<string>('');
  protected readonly showFilterRow = signal<boolean>(false);

  protected readonly activeFilterCount = computed<number>(() => {
    let count = 0;
    if (this.filterDoctorId()) count++;
    if (this.filterResourceId()) count++;
    if (this.filterStatus()) count++;
    return count;
  });

  protected readonly hasActiveFilters = computed<boolean>(() => {
    return this.activeFilterCount() > 0 || this.searchQuery().trim().length > 0;
  });

  protected toggleFilterRow(): void {
    const next = !this.showFilterRow();
    this.showFilterRow.set(next);
    if (next) {
      void this.ensureCatalogues();
    }
  }

  protected clearAllFilters(): void {
    this.filterDoctorId.set('');
    this.filterResourceId.set('');
    this.filterStatus.set('');
    this.searchQuery.set('');
  }

  /** The most recent page, which is where `from`, `to` and `nextCursor` are read from. */
  protected readonly page = new ResourceStore<ClinicAppointmentPage>();
  protected readonly rows = signal<readonly ClinicAppointment[]>([]);

  protected readonly doctorsList = signal<readonly ClinicDoctor[]>([]);
  protected readonly resourcesList = signal<readonly ClinicResourceDetail[]>([]);

  protected readonly allAvailableDoctors = computed<readonly ClinicDoctor[]>(() => {
    const catalogue = this.doctorsList();
    const map = new Map<string, ClinicDoctor>();
    for (const d of catalogue) {
      if (d.id) map.set(d.id, d);
    }
    for (const r of this.rows()) {
      if (r.doctorId && !map.has(r.doctorId)) {
        map.set(r.doctorId, { id: r.doctorId, name: r.doctorName ?? 'طبيب' });
      }
    }
    return [...map.values()];
  });

  protected readonly allAvailableResources = computed<readonly ClinicResourceDetail[]>(() => {
    const catalogue = this.resourcesList();
    const map = new Map<string, ClinicResourceDetail>();
    for (const res of catalogue) {
      if (res.id) map.set(res.id, res);
    }
    for (const r of this.rows()) {
      const resId = (r as any).resourceId;
      const resName = (r as any).resourceName;
      if (resId && !map.has(resId)) {
        map.set(resId, { id: resId, name: resName ?? 'كرسي' } as ClinicResourceDetail);
      }
    }
    return [...map.values()];
  });

  /** The window this list is reading, fixed by the first load. */
  private window: { from: string; to: string } | null = null;

  /** Filtered rows matching user controls */
  protected readonly filteredRows = computed<readonly ClinicAppointment[]>(() => {
    const all = this.rows();
    const q = this.searchQuery().trim().toLowerCase();
    const docId = this.filterDoctorId();
    const resId = this.filterResourceId();
    const st = this.filterStatus().trim().toUpperCase();

    if (!q && !docId && !resId && !st) {
      return all;
    }

    const doctor = docId ? this.allAvailableDoctors().find((d) => d.id === docId) : null;
    const resource = resId ? this.allAvailableResources().find((r) => r.id === resId) : null;

    return all.filter((r) => {
      if (docId) {
        const matchesId = r.doctorId === docId;
        const matchesName =
          doctor?.name &&
          (r.doctorName ?? '').trim().toLowerCase() === doctor.name.trim().toLowerCase();
        if (!matchesId && !matchesName) return false;
      }
      if (resId) {
        const matchesId = (r as any).resourceId === resId;
        const matchesName =
          resource?.name &&
          (r as any).resourceName &&
          (r as any).resourceName.trim().toLowerCase() === resource.name.trim().toLowerCase();
        if (!matchesId && !matchesName) return false;
      }
      if (st && (r.status ?? '').trim().toUpperCase() !== st) {
        return false;
      }
      if (q) {
        const patientMatch = (r.patientName ?? '').toLowerCase().includes(q);
        const doctorMatch = (r.doctorName ?? '').toLowerCase().includes(q);
        const serviceMatch = (r.service ?? '').toLowerCase().includes(q);
        if (!patientMatch && !doctorMatch && !serviceMatch) return false;
      }
      return true;
    });
  });

  /** Days, grouped for the Agenda view. */
  protected readonly days = computed<readonly DayGroup[]>(() => {
    const groups = new Map<string, ClinicAppointment[]>();
    for (const row of this.filteredRows()) {
      const parsed = this.format.parseApiTimestamp(row.appointmentDate);
      const key = parsed ? this.format.dayKey(parsed) : `unparsed-${row.id ?? groups.size}`;
      const bucket = groups.get(key);
      if (bucket) {
        bucket.push(row);
      } else {
        groups.set(key, [row]);
      }
    }
    return [...groups.entries()].map(([key, rows]) => ({
      key,
      anchor: rows.find((row) => row.appointmentDate)?.appointmentDate ?? null,
      rows,
    }));
  });

  /** Chair columns for Operatory View */
  protected readonly chairColumns = computed<readonly ColumnGroup[]>(() => {
    const chairs = this.resourcesList();
    const rows = this.filteredRows();

    const cols: ColumnGroup[] = chairs.map((c) => ({
      id: c.id,
      title: c.name,
      rows: rows.filter((r) => (r as any).resourceId === c.id),
    }));

    // Add unassigned operatory bucket
    const unassigned = rows.filter((r) => !(r as any).resourceId);
    cols.push({
      id: 'unassigned',
      title: 'كرسي غير محدد',
      rows: unassigned,
    });

    return cols;
  });

  /** Doctor columns for Clinician View */
  protected readonly doctorColumns = computed<readonly ColumnGroup[]>(() => {
    const docs = this.doctorsList();
    const rows = this.filteredRows();

    return docs.map((d) => ({
      id: d.id ?? '',
      title: d.name ?? 'طبيب',
      subtitle: d.specialty ?? '',
      rows: rows.filter((r) => r.doctorId === d.id),
    }));
  });

  protected readonly nextCursor = computed(() => this.page.value()?.nextCursor ?? null);

  constructor() {
    inject(I18nService).register({
      clinic: clinicStrings as Namespace,
      clinicAdmin: clinicAdminStrings as Namespace,
    });
  }

  ngOnInit(): void {
    void this.load();
  }

  protected serverWindow(): string {
    const value = this.page.value();
    if (!value?.from || !value.to) return '—';
    return `${value.from} → ${value.to}`;
  }

  protected errorRequestId(): string | null {
    return this.page.error()?.requestId ?? null;
  }

  protected async load(): Promise<void> {
    this.rows.set([]);
    this.window = {
      from: new Date(Date.now() - WINDOW_DAYS * 24 * 60 * 60 * 1000).toISOString(),
      to: new Date().toISOString(),
    };
    await this.page.load(async () => {
      const result = await this.clinic.appointments(this.query());
      this.rows.set([...(result.items ?? [])]);
      return result;
    });
  }

  protected async setViewMode(mode: 'month' | 'agenda' | 'chairs' | 'doctors'): Promise<void> {
    this.viewMode.set(mode);
    if (mode === 'chairs' || mode === 'doctors') {
      await this.ensureCatalogues();
    }
  }

  private async ensureCatalogues(): Promise<void> {
    if (this.doctorsList().length > 0 && this.resourcesList().length > 0) return;
    try {
      const [docs, res] = await Promise.all([
        this.clinic.doctors(this.query().projectId ? { projectId: this.query().projectId } : {}),
        this.patientApi.resources(this.query().projectId ? { projectId: this.query().projectId } : {}),
      ]);
      this.doctorsList.set(docs.items ?? []);
      this.resourcesList.set(res ?? []);
    } catch {
      // Keep going if catalogue query fails
    }
  }

  protected async loadMore(cursor: string): Promise<void> {
    await this.page.load(async () => {
      const result = await this.clinic.appointments({ ...this.query(), cursor });
      this.rows.update((rows) => [...rows, ...(result.items ?? [])]);
      return result;
    });
  }

  protected async updateStatus(row: ClinicAppointment, newStatus: string): Promise<void> {
    if (!row.id) return;
    try {
      await this.patientApi.updateAppointmentStatus(
        row.id,
        { status: newStatus },
        this.query().projectId ? { projectId: this.query().projectId } : {},
      );
      this.rows.update((all) =>
        all.map((item) => (item.id === row.id ? { ...item, status: newStatus } : item)),
      );
    } catch (err: any) {
      console.error('Failed to update status:', err);
    }
  }

  private query(): { projectId?: string; from: string; to: string } {
    const window = this.window ?? { from: '', to: '' };
    return {
      projectId: this.projects.selectedProjectId() ?? undefined,
      from: window.from,
      to: window.to,
    };
  }
}
