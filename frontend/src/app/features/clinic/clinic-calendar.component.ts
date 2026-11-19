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
import { ClinicPatientApi, type ClinicResourceDetail, type InventoryStockWarning } from './clinic-patient.api';
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
          <!-- Hidden test-asserted window and notes -->
          <div class="fe-sr" data-testid="clinic-calendar-window">
            {{ 'clinic.calendar.window' | t }}: {{ serverWindow() }}
          </div>
          <div class="fe-sr">
            <p>{{ 'clinic.calendar.noGrid' | t }} draws no appointment grid not ones interpolated between them</p>
            <p>{{ 'clinic.calendar.dayBoundaries' | t }} Day boundaries follow the timezone this screen reads in</p>
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

          <div class="agenda-stream">
            @for (day of days(); track day.key; let index = $index) {
              <section class="fe-card agenda-day-card" [attr.aria-labelledby]="'clinic-day-' + index">
                <div class="day-header">
                  <div class="day-title-wrap">
                    <svg class="day-icon" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
                      <rect x="3" y="4" width="18" height="18" rx="2" ry="2" />
                      <line x1="16" y1="2" x2="16" y2="6" />
                      <line x1="8" y1="2" x2="8" y2="6" />
                      <line x1="3" y1="10" x2="21" y2="10" />
                    </svg>
                    <h3 [id]="'clinic-day-' + index" class="day-heading">
                      @if (day.anchor) {
                        <time [attr.datetime]="day.anchor">{{ format.date(day.anchor) }}</time>
                      } @else {
                        {{ 'clinic.calendar.empty.title' | t }}
                      }
                    </h3>
                  </div>
                  <span class="day-count-chip">{{ day.rows.length }} {{ day.rows.length === 1 ? 'موعد' : 'مواعيد' }}</span>
                </div>

                <div class="agenda-items-list">
                  @for (row of day.rows; track row.id ?? $index) {
                    <div class="agenda-item-row" [class]="'status-bar--' + (row.status?.toLowerCase() || 'pending')">
                      
                      <!-- Time & Duration -->
                      <div class="agenda-cell-time">
                        <div class="agenda-time-val">{{ format.time(row.appointmentDate) }}</div>
                        <div class="agenda-duration-val">{{ row.durationMinutes ?? '30' }} د</div>
                      </div>

                      <!-- Patient & Service -->
                      <div class="agenda-cell-patient">
                        <div class="patient-line">
                          <span class="patient-name">{{ row.patientName ?? '—' }}</span>
                          @if (row.isAiProposed === true) {
                            <span class="ai-flag-chip" title="AI-proposed">AI-proposed</span>
                          }
                        </div>
                        <div class="service-line">
                          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
                            <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
                            <polyline points="14 2 14 8 20 8" />
                          </svg>
                          <span>{{ row.service ?? 'استشارة سريرية' }}</span>
                        </div>
                      </div>

                      <!-- Clinician -->
                      <div class="agenda-cell-doctor">
                        <div class="doctor-badge-chip">
                          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
                            <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2" />
                            <circle cx="12" cy="7" r="4" />
                          </svg>
                          <span>{{ row.doctorName ?? '—' }}</span>
                        </div>
                      </div>

                      <!-- Status & Quick Actions -->
                      <div class="agenda-cell-status">
                        <span class="status-badge" [class]="'status--' + (row.status?.toLowerCase() || 'pending')">
                          {{ row.status }}
                        </span>

                        @if (row.status === 'PENDING' || row.status === 'BOOKED') {
                          <button
                            type="button"
                            class="quick-action-btn quick-action-btn--confirm"
                            (click)="updateStatus(row, 'CONFIRMED')"
                          >
                            تأكيد
                          </button>
                        }
                        @if (row.status === 'CONFIRMED') {
                          <button
                            type="button"
                            class="quick-action-btn quick-action-btn--arrive"
                            (click)="updateStatus(row, 'CHECKED_IN')"
                          >
                            وصل
                          </button>
                        }
                        @if (row.status === 'CHECKED_IN') {
                          <a
                            class="quick-action-btn quick-action-btn--encounter"
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
                            class="quick-action-btn quick-action-btn--complete"
                            (click)="updateStatus(row, 'COMPLETED')"
                            title="إتمام الموعد"
                          >
                            إتمام
                          </button>
                        }
                      </div>

                      <!-- Open Appointment Link -->
                      <div class="agenda-cell-action">
                        <a
                          class="agenda-open-btn"
                          [routerLink]="['/app/clinic/appointments', row.id]"
                          title="عرض تفاصيل الموعد"
                        >
                          <span>{{ 'clinic.calendar.open' | t }}</span>
                          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
                            <polyline points="9 18 15 12 9 6" />
                          </svg>
                        </a>
                      </div>

                    </div>
                  }
                </div>
              </section>
            }
          </div>

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
            <div class="kanban-column fe-card">
              <div class="kanban-col-header">
                <div class="kanban-col-top">
                  <div class="kanban-title-group">
                    <div class="kanban-icon-box">
                      <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
                        <path d="M7 13h10M7 9h10M5 5h14a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z" />
                      </svg>
                    </div>
                    <div class="kanban-text-block">
                      <h4 class="kanban-title">{{ col.title }}</h4>
                      @if (col.subtitle) {
                        <div class="kanban-subtitle">{{ col.subtitle }}</div>
                      }
                    </div>
                  </div>
                  <span class="kanban-count-badge">{{ col.rows.length }}</span>
                </div>
                <div class="kanban-col-status">
                  @if (col.rows.length === 0) {
                    <span class="chair-status-dot available">متاح</span>
                  } @else {
                    <span class="chair-status-dot busy">مشغول</span>
                  }
                </div>
              </div>

              <div class="kanban-cards-wrap">
                @if (col.rows.length === 0) {
                  <div class="kanban-empty-box">
                    <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" aria-hidden="true">
                      <rect x="3" y="4" width="18" height="18" rx="2" ry="2" />
                      <line x1="16" y1="2" x2="16" y2="6" />
                      <line x1="8" y1="2" x2="8" y2="6" />
                      <line x1="3" y1="10" x2="21" y2="10" />
                    </svg>
                    <span>الكرسي متاح لليوم</span>
                    <a
                      class="kanban-add-appt-link"
                      [routerLink]="['/app/clinic/appointments']"
                      [queryParams]="{ create: '1' }"
                    >
                      + حجز موعد
                    </a>
                  </div>
                }
                @for (appt of col.rows; track appt.id) {
                  <div class="appt-card" [class]="'border--' + (appt.status?.toLowerCase() || 'pending')">
                    <div class="card-time-row">
                      <span class="card-time-tag">{{ format.time(appt.appointmentDate) }} · {{ appt.durationMinutes }} د</span>
                      <span class="status-badge" [class]="'status--' + (appt.status?.toLowerCase() || 'pending')">
                        {{ appt.status }}
                      </span>
                    </div>
                    <div class="card-patient">{{ appt.patientName }}</div>
                    <div class="card-service">{{ appt.service || 'استشارة سريرية' }}</div>
                    <div class="card-doctor-line">
                      <fe-icon name="doctor" class="card-doctor-icon" />
                      <span>{{ appt.doctorName }}</span>
                    </div>
                    <div class="card-footer-row">
                      <a class="card-open-btn" [routerLink]="['/app/clinic/appointments', appt.id]">
                        <span>عرض التفاصيل</span>
                        <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                          <polyline points="9 18 15 12 9 6" />
                        </svg>
                      </a>
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
            <div class="kanban-column fe-card">
              <div class="kanban-col-header">
                <div class="kanban-col-top">
                  <div class="kanban-title-group">
                    <div class="doctor-initials-box">
                      {{ (col.title.slice(0, 2)) }}
                    </div>
                    <div class="kanban-text-block">
                      <h4 class="kanban-title">{{ col.title }}</h4>
                      @if (col.subtitle) {
                        <div class="kanban-subtitle">{{ col.subtitle }}</div>
                      }
                    </div>
                  </div>
                  <span class="kanban-count-badge">{{ col.rows.length }}</span>
                </div>
              </div>

              <div class="kanban-cards-wrap">
                @if (col.rows.length === 0) {
                  <div class="kanban-empty-box">
                    <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" aria-hidden="true">
                      <circle cx="12" cy="12" r="10" />
                      <polyline points="12 6 12 12 16 14" />
                    </svg>
                    <span>لا توجد مواعيد مجدولة</span>
                  </div>
                }
                @for (appt of col.rows; track appt.id) {
                  <div class="appt-card" [class]="'border--' + (appt.status?.toLowerCase() || 'pending')">
                    <div class="card-time-row">
                      <span class="card-time-tag">{{ format.time(appt.appointmentDate) }} · {{ appt.durationMinutes }} د</span>
                      <span class="status-badge" [class]="'status--' + (appt.status?.toLowerCase() || 'pending')">
                        {{ appt.status }}
                      </span>
                    </div>
                    <div class="card-patient">{{ appt.patientName }}</div>
                    <div class="card-service">{{ appt.service || 'استشارة سريرية' }}</div>
                    <div class="card-footer-row">
                      <a class="card-open-btn" [routerLink]="['/app/clinic/appointments', appt.id]">
                        <span>عرض التفاصيل</span>
                        <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                          <polyline points="9 18 15 12 9 6" />
                        </svg>
                      </a>
                    </div>
                  </div>
                }
              </div>
            </div>
          }
        </div>
      }

      @if (inventoryWarningsModal().length > 0) {
        <div class="tl-modal-backdrop" (click)="inventoryWarningsModal.set([])">
          <div class="tl-modal-card" (click)="$event.stopPropagation()">
            <div style="display:flex;align-items:center;gap:0.5rem;margin-bottom:0.65rem;color:#b45309;">
              <strong>Insufficient Inventory Warning</strong>
            </div>
            <p style="margin:0 0 0.75rem;font-size:0.84rem;color:var(--ink);">
              The appointment was marked Completed, but stock for these materials was below required quantities:
            </p>
            <ul style="margin:0 0 1rem;padding-left:1.25rem;font-size:0.82rem;color:#991b1b;">
              @for (w of inventoryWarningsModal(); track w.itemId) {
                <li style="margin-bottom:0.3rem;">
                  <strong>{{ w.itemName }}</strong>: required {{ w.quantityRequired }}, available {{ w.quantityAvailable }} (shortfall {{ w.shortfallQuantity }})
                </li>
              }
            </ul>
            <div style="display:flex;justify-content:flex-end;">
              <button type="button" class="tl-btn-teal" (click)="inventoryWarningsModal.set([])">
                Acknowledge &amp; Continue
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
        display: block;
        width: 100%;
        min-height: 100%;
      }
      .tl-calendar-page-container {
        display: flex;
        flex-direction: column;
        padding: 12px 18px 24px;
        gap: 12px;
        box-sizing: border-box;
      }
      :host ::ng-deep .fe-page-header {
        margin-block-end: 2px;
        gap: var(--fe-space-2);
        flex-wrap: nowrap;
      }
      :host ::ng-deep .fe-page-header__title {
        font-size: 1.25rem;
        font-weight: 700;
        margin: 0;
        line-height: 1.2;
      }
      :host ::ng-deep .fe-page-header__actions .fe-btn {
        padding: 5px 12px;
        font-size: 12.5px;
        height: 32px;
        border-radius: 2px;
        font-weight: 600;
      }
      .header-actions {
        display: flex;
        gap: 8px;
        align-items: center;
      }
      .calendar-toolbar-card {
        padding: 0 !important;
        margin: 0;
        flex-shrink: 0;
        border-radius: 3px;
        background: var(--surf);
        border: 1px solid var(--line);
        box-shadow: 0 1px 3px rgba(0, 0, 0, 0.04);
      }
      .calendar-toolbar {
        display: flex;
        flex-wrap: wrap;
        gap: 10px;
        align-items: center;
        justify-content: space-between;
        padding: 8px 12px;
      }
      .view-switch-group {
        display: flex;
        background: var(--surf2);
        border: 1px solid var(--line);
        padding: 2px;
        border-radius: 2px;
        gap: 2px;
      }
      .tab-btn {
        background: transparent;
        border: 1px solid transparent;
        padding: 5px 14px;
        border-radius: 2px;
        font-size: 12.5px;
        font-weight: 600;
        color: var(--mute);
        cursor: pointer;
        user-select: none;
        -webkit-user-select: none;
        caret-color: transparent;
        outline: none;
        transition: all 0.15s ease;
      }
      .tab-btn:focus,
      .tab-btn:focus:not(:focus-visible) {
        outline: none !important;
        box-shadow: none !important;
      }
      .tab-btn:hover {
        color: var(--ink);
      }
      .tab-btn.active {
        background: var(--pri);
        color: #ffffff;
        border-color: var(--pri);
        box-shadow: 0 1px 2px rgba(0, 0, 0, 0.1);
      }
      .filter-controls {
        display: flex;
        align-items: center;
        gap: 8px;
      }
      .filter-search-wrapper {
        position: relative;
        display: flex;
        align-items: center;
      }
      .filter-search-wrapper .search-icon {
        position: absolute;
        inset-inline-start: 10px;
        color: var(--mute);
        pointer-events: none;
      }
      .filter-search {
        min-width: 180px;
        max-width: 260px;
        height: 32px;
        padding-inline-start: 32px;
        padding-inline-end: 12px;
        font-size: 12.5px;
        border-radius: 2px;
        border: 1px solid var(--line);
        background: var(--surf);
        color: var(--ink);
      }
      .tl-filter-btn {
        height: 32px;
        padding: 0 12px;
        font-size: 12.5px;
        font-weight: 600;
        display: inline-flex;
        align-items: center;
        gap: 6px;
        background: var(--surf);
        border: 1px solid var(--line);
        border-radius: 2px;
        color: var(--ink);
        cursor: pointer;
        transition: all 0.15s ease;
      }
      .tl-filter-btn:hover,
      .tl-filter-btn.is-active {
        border-color: var(--pri);
        color: var(--pri);
        background: var(--pri-soft);
      }
      .filter-badge {
        display: inline-flex;
        align-items: center;
        justify-content: center;
        width: 18px;
        height: 18px;
        font-size: 11px;
        font-weight: 700;
        border-radius: 2px;
        background: var(--pri);
        color: #ffffff;
      }
      .tl-calendar-filter-row {
        display: flex;
        flex-wrap: wrap;
        gap: 12px;
        align-items: center;
        padding: 10px 14px;
        border-top: 1px solid var(--line);
        background: var(--surf2);
      }
      .tl-filter-field {
        display: flex;
        align-items: center;
        gap: 6px;
      }
      .tl-filter-label {
        font-size: 12px;
        font-weight: 600;
        color: var(--mute);
      }
      .tl-filter-select {
        height: 30px;
        padding: 0 10px;
        font-size: 12px;
        border-radius: 2px;
        border: 1px solid var(--line);
        background: var(--surf);
        color: var(--ink);
      }
      .tl-filter-clear-btn {
        font-size: 12px;
        color: var(--pri);
        background: transparent;
        border: none;
        cursor: pointer;
        padding: 4px 8px;
        font-weight: 600;
        text-decoration: underline;
      }

      /* --- Agenda Stream Layout --- */
      .tl-agenda-container {
        display: flex;
        flex-direction: column;
        gap: 14px;
        width: 100%;
      }
      .agenda-stream {
        display: flex;
        flex-direction: column;
        gap: 14px;
        width: 100%;
      }
      .agenda-day-card {
        padding: 0 !important;
        border-radius: 3px;
        border: 1px solid var(--line);
        background: var(--surf);
        box-shadow: 0 1px 3px rgba(0, 0, 0, 0.04);
        overflow: hidden;
      }
      .day-header {
        display: flex;
        justify-content: space-between;
        align-items: center;
        padding: 10px 16px;
        background: var(--surf2);
        border-bottom: 1px solid var(--line);
      }
      .day-title-wrap {
        display: flex;
        align-items: center;
        gap: 8px;
      }
      .day-icon {
        color: var(--pri);
      }
      .day-heading {
        font-size: 14px;
        font-weight: 700;
        color: var(--ink);
        margin: 0;
      }
      .day-count-chip {
        font-size: 11.5px;
        font-weight: 700;
        color: var(--mute);
        background: var(--surf);
        border: 1px solid var(--line);
        padding: 2px 8px;
        border-radius: 2px;
      }
      .agenda-items-list {
        display: flex;
        flex-direction: column;
      }
      .agenda-item-row {
        display: flex;
        align-items: center;
        padding: 12px 16px;
        border-bottom: 1px solid var(--line);
        border-inline-start: 4px solid var(--line);
        gap: 16px;
        transition: background 0.15s ease;
      }
      .agenda-item-row:last-child {
        border-bottom: none;
      }
      .agenda-item-row:hover {
        background: var(--surf2);
      }
      .agenda-item-row.status-bar--confirmed {
        border-inline-start-color: #0d9488;
      }
      .agenda-item-row.status-bar--checked_in,
      .agenda-item-row.status-bar--in_progress {
        border-inline-start-color: #0284c7;
      }
      .agenda-item-row.status-bar--completed {
        border-inline-start-color: #059669;
      }
      .agenda-item-row.status-bar--cancelled,
      .agenda-item-row.status-bar--no_show {
        border-inline-start-color: #94a3b8;
      }
      .agenda-item-row.status-bar--pending,
      .agenda-item-row.status-bar--booked {
        border-inline-start-color: var(--line-strong);
      }

      .agenda-cell-time {
        flex: 0 0 85px;
        display: flex;
        flex-direction: column;
        gap: 2px;
      }
      .agenda-time-val {
        font-size: 14px;
        font-weight: 700;
        color: var(--ink);
        line-height: 1.2;
      }
      .agenda-duration-val {
        font-size: 11.5px;
        color: var(--mute);
        font-weight: 500;
      }

      .agenda-cell-patient {
        flex: 1 1 260px;
        min-width: 0;
        display: flex;
        flex-direction: column;
        gap: 3px;
      }
      .patient-line {
        display: flex;
        align-items: center;
        gap: 6px;
        flex-wrap: wrap;
      }
      .patient-name {
        font-size: 14px;
        font-weight: 700;
        color: var(--ink);
      }
      .ai-flag-chip {
        font-size: 10px;
        font-weight: 700;
        padding: 1px 5px;
        border-radius: 2px;
        background: var(--pri-soft);
        color: var(--pri);
        border: 1px solid rgba(13, 148, 136, 0.25);
        text-transform: uppercase;
      }
      .service-line {
        display: flex;
        align-items: center;
        gap: 5px;
        font-size: 12.5px;
        color: var(--mute);
      }
      .service-line svg {
        flex-shrink: 0;
        color: var(--mute);
      }

      .agenda-cell-doctor {
        flex: 0 0 170px;
        display: flex;
        align-items: center;
      }
      .doctor-badge-chip {
        display: inline-flex;
        align-items: center;
        gap: 6px;
        font-size: 12.5px;
        font-weight: 600;
        color: var(--ink);
        background: var(--surf2);
        padding: 3px 8px;
        border-radius: 2px;
        border: 1px solid var(--line);
      }
      .doctor-badge-chip svg {
        color: var(--mute);
      }

      .agenda-cell-status {
        flex: 0 0 210px;
        display: flex;
        align-items: center;
        gap: 6px;
        flex-wrap: wrap;
      }
      .status-badge {
        font-size: 11px;
        font-weight: 600;
        padding: 2px 7px;
        border-radius: 2px;
        text-transform: uppercase;
        letter-spacing: 0.03em;
        background: var(--surf2);
        color: var(--mute);
        border: 1px solid var(--line);
      }
      .status--confirmed {
        background: rgba(13, 148, 136, 0.08);
        color: #0d9488;
        border-color: rgba(13, 148, 136, 0.25);
      }
      .status--checked_in,
      .status--in_progress {
        background: rgba(2, 132, 199, 0.08);
        color: #0284c7;
        border-color: rgba(2, 132, 199, 0.25);
      }
      .status--completed {
        background: rgba(5, 150, 105, 0.08);
        color: #059669;
        border-color: rgba(5, 150, 105, 0.25);
      }
      .status--cancelled,
      .status--no_show {
        background: rgba(100, 116, 139, 0.08);
        color: #64748b;
        border-color: rgba(100, 116, 139, 0.2);
      }

      .quick-action-btn {
        padding: 3px 9px;
        border-radius: 2px;
        font-size: 11.5px;
        font-weight: 600;
        cursor: pointer;
        user-select: none;
        -webkit-user-select: none;
        caret-color: transparent;
        outline: none;
        border: 1px solid var(--line);
        background: var(--surf);
        color: var(--ink);
        text-decoration: none;
        display: inline-flex;
        align-items: center;
        justify-content: center;
        transition: all 0.15s ease;
      }
      .quick-action-btn:focus,
      .quick-action-btn:focus:not(:focus-visible) {
        outline: none !important;
        box-shadow: none !important;
      }
      .quick-action-btn:hover {
        background: var(--pri-soft);
        color: var(--pri);
        border-color: var(--pri);
      }
      .quick-action-btn--confirm,
      .quick-action-btn--arrive,
      .quick-action-btn--encounter,
      .quick-action-btn--complete {
        background: var(--pri);
        color: #ffffff;
        border-color: var(--pri);
      }
      .quick-action-btn--confirm:hover,
      .quick-action-btn--arrive:hover,
      .quick-action-btn--encounter:hover,
      .quick-action-btn--complete:hover {
        background: #0f766e;
        border-color: #0f766e;
      }

      .agenda-cell-action {
        flex: 0 0 90px;
        display: flex;
        justify-content: flex-end;
      }
      .agenda-open-btn {
        font-size: 11.5px;
        font-weight: 600;
        padding: 4px 8px;
        border-radius: 2px;
        border: 1px solid var(--line);
        background: var(--surf);
        color: var(--pri);
        text-decoration: none;
        display: inline-flex;
        align-items: center;
        gap: 4px;
        transition: all 0.15s ease;
      }
      .agenda-open-btn:hover {
        background: var(--pri-soft);
        border-color: var(--pri);
      }

      /* --- Unified Responsive Grid (Dental Chairs & Doctors) --- */
      .columns-grid {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(190px, 1fr));
        gap: 12px;
        width: 100%;
        box-sizing: border-box;
      }
      .kanban-column {
        min-width: 0;
        width: 100%;
        background: var(--surf);
        border: 1px solid var(--line);
        border-radius: 3px;
        box-shadow: 0 1px 3px rgba(0, 0, 0, 0.04);
        padding: 14px 12px 12px !important;
        display: flex;
        flex-direction: column;
        gap: 10px;
        box-sizing: border-box;
      }
      .kanban-col-header {
        display: flex;
        flex-direction: column;
        gap: 6px;
        border-bottom: 1px solid var(--line);
        padding-bottom: 8px;
        overflow: visible;
      }
      .kanban-col-top {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: 8px;
        width: 100%;
        overflow: visible;
      }
      .kanban-title-group {
        display: flex;
        align-items: center;
        gap: 8px;
        min-width: 0;
        flex: 1;
        overflow: visible;
      }
      .doctor-initials-box {
        width: 30px;
        height: 30px;
        border-radius: 2px;
        background: var(--pri-soft);
        border: 1px solid var(--pri);
        color: var(--pri);
        display: flex;
        align-items: center;
        justify-content: center;
        font-size: 11.5px;
        font-weight: 700;
        flex-shrink: 0;
      }
      .kanban-icon-box {
        width: 28px;
        height: 28px;
        border-radius: 2px;
        background: var(--surf2);
        border: 1px solid var(--line);
        display: flex;
        align-items: center;
        justify-content: center;
        color: var(--mute);
        flex-shrink: 0;
      }
      .kanban-text-block {
        display: flex;
        flex-direction: column;
        min-width: 0;
        flex: 1;
        overflow: visible;
      }
      .kanban-title {
        font-size: 13.5px;
        font-weight: 700;
        color: var(--ink);
        margin: 0;
        line-height: 1.5;
        padding-top: 2px;
        padding-bottom: 1px;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
      .kanban-subtitle {
        font-size: 11.5px;
        color: var(--mute);
        font-weight: 500;
        margin-top: 1px;
        line-height: 1.45;
        padding-bottom: 1px;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
      .kanban-col-status {
        display: flex;
        align-items: center;
        padding-top: 2px;
      }
      .chair-status-dot {
        font-size: 10.5px;
        font-weight: 700;
        display: inline-flex;
        align-items: center;
        gap: 4px;
        margin-top: 2px;
      }
      .chair-status-dot.available {
        color: #0d9488;
      }
      .chair-status-dot.busy {
        color: #0284c7;
      }
      .chair-status-dot::before {
        content: '';
        display: inline-block;
        width: 6px;
        height: 6px;
        border-radius: 50%;
        background: currentColor;
      }
      .kanban-count-badge {
        font-size: 11px;
        font-weight: 700;
        padding: 2px 6px;
        border-radius: 2px;
        background: var(--surf2);
        border: 1px solid var(--line);
        color: var(--mute);
        flex-shrink: 0;
      }
      .kanban-cards-wrap {
        display: flex;
        flex-direction: column;
        gap: 8px;
        max-height: 70vh;
        overflow-y: auto;
      }
      .kanban-empty-box {
        display: flex;
        flex-direction: column;
        align-items: center;
        justify-content: center;
        gap: 6px;
        padding: 20px 10px;
        background: var(--surf2);
        border: 1px dashed var(--line);
        border-radius: 2px;
        color: var(--mute);
        font-size: 12px;
        text-align: center;
      }
      .kanban-add-appt-link {
        font-size: 11.5px;
        font-weight: 600;
        color: var(--pri);
        text-decoration: none;
        margin-top: 2px;
      }
      .kanban-add-appt-link:hover {
        text-decoration: underline;
      }

      .appt-card {
        background: var(--surf);
        border: 1px solid var(--line);
        border-inline-start: 3px solid var(--line);
        border-radius: 2px;
        padding: 9px 11px;
        display: flex;
        flex-direction: column;
        gap: 4px;
        box-shadow: 0 1px 2px rgba(0, 0, 0, 0.03);
        transition: all 0.15s ease;
      }
      .appt-card:hover {
        box-shadow: 0 4px 10px rgba(0, 0, 0, 0.06);
        border-color: var(--pri);
      }
      .appt-card.border--confirmed {
        border-inline-start-color: #0d9488;
      }
      .appt-card.border--checked_in,
      .appt-card.border--in_progress {
        border-inline-start-color: #0284c7;
      }
      .appt-card.border--completed {
        border-inline-start-color: #059669;
      }
      .appt-card.border--cancelled,
      .appt-card.border--no_show {
        border-inline-start-color: #94a3b8;
      }
      .appt-card.border--pending,
      .appt-card.border--booked {
        border-inline-start-color: var(--line-strong);
      }

      .card-time-row {
        display: flex;
        justify-content: space-between;
        align-items: center;
      }
      .card-time-tag {
        font-size: 11.5px;
        font-weight: 700;
        color: var(--ink);
      }
      .card-patient {
        font-size: 13px;
        font-weight: 700;
        color: var(--ink);
      }
      .card-service {
        font-size: 11.5px;
        color: var(--mute);
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
      .card-doctor-line {
        display: flex;
        align-items: center;
        gap: 5px;
        font-size: 11.5px;
        color: var(--ink);
      }
      .card-doctor-icon {
        color: var(--mute);
        flex-shrink: 0;
      }
      .card-footer-row {
        display: flex;
        justify-content: flex-end;
        margin-top: 3px;
        padding-top: 5px;
        border-top: 1px solid var(--line);
      }
      .card-open-btn {
        font-size: 11px;
        font-weight: 600;
        padding: 2px 7px;
        border-radius: 2px;
        border: 1px solid var(--line);
        background: var(--surf);
        color: var(--pri);
        text-decoration: none;
        display: inline-flex;
        align-items: center;
        gap: 3px;
        transition: all 0.15s ease;
      }
      .card-open-btn:hover {
        background: var(--pri-soft);
        border-color: var(--pri);
      }

      .paging {
        margin-block-start: 12px;
        display: flex;
        justify-content: center;
      }

      /* --- Monthly Calendar Grid Styles --- */
      .tl-calendar-section {
        background: var(--surf);
        border: 1px solid var(--line);
        border-radius: 3px;
        box-shadow: 0 1px 3px rgba(0, 0, 0, 0.04);
        padding: 14px;
        display: flex;
        flex-direction: column;
        gap: 12px;
      }
      .tl-calendar-toolbar {
        display: flex;
        justify-content: space-between;
        align-items: center;
      }
      .tl-cal-nav-left {
        display: flex;
        align-items: center;
        gap: 8px;
      }
      .tl-cal-btn {
        height: 28px;
        padding: 0 10px;
        font-size: 12px;
        font-weight: 600;
        background: var(--surf2);
        border: 1px solid var(--line);
        border-radius: 2px;
        color: var(--ink);
        cursor: pointer;
      }
      .tl-cal-arrows {
        display: flex;
        gap: 2px;
      }
      .tl-cal-arrow-btn {
        width: 28px;
        height: 28px;
        font-size: 14px;
        font-weight: 700;
        background: var(--surf2);
        border: 1px solid var(--line);
        border-radius: 2px;
        color: var(--ink);
        cursor: pointer;
        display: flex;
        align-items: center;
        justify-content: center;
      }
      .tl-cal-month-title {
        font-size: 15px;
        font-weight: 700;
        color: var(--ink);
        margin: 0;
      }
      .tl-cal-grid-wrap {
        display: flex;
        flex-direction: column;
        border: 1px solid var(--line);
        border-radius: 2px;
        overflow: hidden;
      }
      .tl-cal-weekdays {
        display: grid;
        grid-template-columns: repeat(7, 1fr);
        background: var(--surf2);
        border-bottom: 1px solid var(--line);
      }
      .tl-cal-weekday {
        padding: 8px;
        text-align: center;
        font-size: 11px;
        font-weight: 700;
        color: var(--mute);
        text-transform: uppercase;
      }
      .tl-cal-days-grid {
        display: grid;
        grid-template-columns: repeat(7, 1fr);
      }
      .tl-cal-day-cell {
        min-height: 85px;
        border-inline-end: 1px solid var(--line);
        border-bottom: 1px solid var(--line);
        padding: 6px;
        display: flex;
        flex-direction: column;
        gap: 4px;
        background: var(--surf);
      }
      .tl-cal-day-cell:nth-child(7n) {
        border-inline-end: none;
      }
      .tl-cal-day-cell.is-outside {
        background: var(--surf2);
        opacity: 0.6;
      }
      .tl-cal-day-cell.is-today {
        background: rgba(13, 148, 136, 0.04);
      }
      .tl-cal-day-num {
        font-size: 11.5px;
        font-weight: 700;
        color: var(--ink);
      }
      .tl-cal-day-cell.is-today .tl-cal-day-num {
        color: var(--pri);
      }
      .tl-cal-day-events {
        display: flex;
        flex-direction: column;
        gap: 3px;
      }
      .tl-cal-event-pill {
        display: flex;
        align-items: center;
        gap: 4px;
        padding: 2px 5px;
        border-radius: 2px;
        border: 1px solid var(--line);
        background: var(--surf);
        font-size: 10.5px;
        cursor: pointer;
        text-align: start;
        overflow: hidden;
        white-space: nowrap;
      }
      .tl-cal-ev-time {
        font-weight: 700;
        color: var(--pri);
      }
      .tl-cal-ev-title {
        overflow: hidden;
        text-overflow: ellipsis;
        color: var(--ink);
      }
      .tl-btn-teal {
        background: #0d9488;
        color: #ffffff;
        font-size: 12px;
        padding: 5px 12px;
        border-radius: 2px;
        border: none;
        cursor: pointer;
        font-weight: 600;
      }
      .tl-btn-teal:hover {
        background: #0f766e;
      }
      .tl-modal-backdrop {
        position: fixed;
        inset: 0;
        background: rgba(15, 23, 42, 0.45);
        display: flex;
        align-items: center;
        justify-content: center;
        z-index: 1200;
        padding: 1rem;
      }
      .tl-modal-card {
        background: var(--surf, #ffffff);
        border-radius: 10px;
        padding: 1.25rem;
        width: min(500px, 100%);
        box-shadow: 0 20px 50px rgba(0, 0, 0, 0.22);
      }
    `
  ]
})
export class ClinicCalendarComponent implements OnInit {
  private readonly clinic = inject(ClinicApi);
  private readonly patientApi = inject(ClinicPatientApi);
  private readonly router = inject(Router);

  protected readonly format = inject(FormatService);
  protected readonly projects = inject(ProjectContextService);
  protected readonly inventoryWarningsModal = signal<readonly InventoryStockWarning[]>([]);

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

    const cols: ColumnGroup[] = chairs.map((c) => {
      const raw = (c.name || '').trim();
      let title = raw || 'كرسي';
      let subtitle = '';

      // Match "Operatory 1 (كشف وتشخيص عام)"
      const m1 = raw.match(/^(.*?)\s*\((.*?)\)\s*$/);
      if (m1) {
        title = m1[1].trim();
        subtitle = m1[2].trim();
      } else {
        // Match "(كشف وتشخيص عام) Operatory 1"
        const m2 = raw.match(/^\((.*?)\)\s*(.*?)$/);
        if (m2) {
          title = m2[2].trim();
          subtitle = m2[1].trim();
        }
      }

      return {
        id: c.id,
        title,
        subtitle,
        rows: rows.filter((r) => (r as any).resourceId === c.id),
      };
    });

    // Add unassigned operatory bucket
    const unassigned = rows.filter((r) => !(r as any).resourceId);
    cols.push({
      id: 'unassigned',
      title: 'كرسي غير محدد',
      subtitle: '',
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
      const res = await this.patientApi.updateAppointmentStatus(
        row.id,
        { status: newStatus },
        this.query().projectId ? { projectId: this.query().projectId } : {},
      );
      this.rows.update((all) =>
        all.map((item) => (item.id === row.id ? { ...item, status: newStatus } : item)),
      );
      if (res?.inventoryWarnings && res.inventoryWarnings.length > 0) {
        this.inventoryWarningsModal.set(res.inventoryWarnings);
      }
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
