/**
 * `/app/clinic/appointments` — the appointment list.
 *
 * The list is server-paged with the contract's keyset cursor. The cursor stack
 * is therefore owned here: Previous re-reads the cursor that produced the page
 * before it, rather than asking for an offset the route does not accept.
 *
 * Search, status and payment are server filters. A client-side filter over one
 * server page would report "no appointments" for a row on the next page, which
 * is the one answer a booking desk must never be given.
 *
 * Bulk actions and row actions are real writes:
 *   - payment uses `PATCH /schedules/appointments/{id}/payment`,
 *   - cancellation uses the existing status route.
 * Nothing in the toolbar is a decorative control. Export writes the rows the
 * server sent, with the selected rows taking precedence when any are selected.
 */
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { ApiError } from '../../core/api/api.service';
import { PERMISSIONS } from '../../core/auth/permissions';
import { SessionService } from '../../core/auth/session.service';
import { ProjectContextService } from '../../core/context/project-context.service';
import { FormatService } from '../../core/format/format.service';
import { I18nService } from '../../core/i18n/i18n.service';
import type { Namespace } from '../../core/i18n/i18n.types';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import {
  ClinicApi,
  type ClinicAppointment,
  type ClinicDoctorProfile,
} from '../../core/api/endpoints';
import { AsyncStateComponent } from '../../ui/async-state.component';
import { ClinicAppointmentDrawerComponent } from './clinic-appointment-drawer.component';
import { clinicAdminStrings } from './clinic-admin.strings';
import {
  ClinicPatientApi,
  type InventoryStockWarning,
  type TreatmentRoomRow,
} from './clinic-patient.api';

const PAYMENT_STATES = ['PAID', 'UNPAID', 'PARTIAL', 'REFUNDED'] as const;
const STATUS_STATES = [
  'PENDING',
  'CONFIRMED',
  'CHECKED_IN',
  'COMPLETED',
  'CANCELLED',
  'NO_SHOW',
] as const;

@Component({
  selector: 'fe-clinic-appointments-page',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    AsyncStateComponent,
    ClinicAppointmentDrawerComponent,
    RouterLink,
    TranslatePipe,
  ],
  template: `
    <div class="tl-page" data-testid="clinic-appointments">
      <div class="tl-page-top">
        <nav class="tl-breadcrumbs" aria-label="Breadcrumb">
          <a routerLink="/app/dashboard">{{ 'clinicAdmin.nav.home' | t }}</a>
          <span aria-hidden="true">&gt;&gt;</span>
          <span class="active">{{ 'clinicAdmin.appointments.breadcrumb' | t }}</span>
        </nav>
        <button type="button" class="tl-btn tl-btn-dark" (click)="openCreate()">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" aria-hidden="true">
            <circle cx="12" cy="12" r="9" />
            <path d="M12 8v8M8 12h8" />
          </svg>
          {{ 'clinicAdmin.common.appointment' | t }}
        </button>
      </div>

      <section class="tl-card" [attr.aria-busy]="loading()">
        <div class="tl-toolbar tl-toolbar--wrap">
          <div class="tl-toolbar-left">
            <select
              class="tl-select tl-select--compact"
              [value]="bulkAction()"
              (change)="bulkAction.set($any($event.target).value)"
              [attr.aria-label]="'clinicAdmin.common.bulkAction' | t"
            >
              <option value="">{{ 'clinicAdmin.appointments.noAction' | t }}</option>
              <option value="PAID">{{ 'clinicAdmin.appointments.bulkPaid' | t }}</option>
              <option value="UNPAID">{{ 'clinicAdmin.appointments.bulkUnpaid' | t }}</option>
              <option value="CANCELLED">{{ 'clinicAdmin.appointments.bulkCancel' | t }}</option>
            </select>
            <button
              type="button"
              class="tl-btn tl-btn-gray"
              [disabled]="bulkAction() === '' || selectedIds().size === 0 || bulkBusy()"
              (click)="applyBulk()"
            >
              {{ 'clinicAdmin.common.apply' | t }}
            </button>
            <button
              type="button"
              class="tl-btn tl-btn-teal"
              [disabled]="rows().length === 0"
              (click)="exportCsv()"
            >
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" aria-hidden="true">
                <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M7 10l5 5 5-5M12 15V3" />
              </svg>
              {{ selectedIds().size > 0 ? ('clinicAdmin.appointments.exportSelected' | t) : ('clinicAdmin.common.export' | t) }}
            </button>
          </div>

          <div class="tl-toolbar-right">
            <div class="tl-search">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
                <circle cx="11" cy="11" r="7"></circle>
                <path d="m20 20-3.2-3.2"></path>
              </svg>
              <input
                class="tl-input"
                type="search"
                autocomplete="off"
                [value]="term()"
                [attr.placeholder]="'clinicAdmin.appointments.searchPlaceholder' | t"
                (input)="onSearch($event)"
              />
            </div>
            <button
              type="button"
              class="tl-btn tl-btn-dark"
              [class.is-active]="advanced()"
              (click)="advanced.set(!advanced())"
            >
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" aria-hidden="true">
                <polygon points="22 3 2 3 10 12.46 10 19 14 21 14 12.46 22 3" />
              </svg>
              {{ 'clinicAdmin.appointments.advancedFilter' | t }}
            </button>
            <button type="button" class="tl-btn tl-btn-teal" (click)="openCreate()">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" aria-hidden="true">
                <path d="M12 5v14M5 12h14" />
              </svg>
              {{ 'clinicAdmin.common.new' | t }}
            </button>
          </div>
        </div>

        @if (advanced()) {
          <div class="tl-filter-row">
            <label class="tl-filter">
              <span class="tl-filter-label">{{ 'clinicAdmin.appointments.status' | t }}</span>
              <select
                class="tl-select tl-select--compact"
                [value]="statusFilter()"
                (change)="onStatusChange($any($event.target).value)"
              >
                <option value="">{{ 'clinicAdmin.appointments.allStatuses' | t }}</option>
                @for (status of statuses; track status) {
                  <option [value]="status">{{ statusLabel(status) }}</option>
                }
              </select>
            </label>
            <label class="tl-filter">
              <span class="tl-filter-label">{{ 'clinicAdmin.appointments.paymentStatus' | t }}</span>
              <select
                class="tl-select tl-select--compact"
                [value]="paymentFilter()"
                (change)="onPaymentChange($any($event.target).value)"
              >
                <option value="">{{ 'clinicAdmin.appointments.allPayments' | t }}</option>
                @for (state of paymentStates; track state) {
                  <option [value]="state">{{ paymentLabel(state) }}</option>
                }
              </select>
            </label>
            @if (hasFilters()) {
              <button type="button" class="tl-link" (click)="clearFilters()">
                {{ 'clinicAdmin.common.clearFilters' | t }}
              </button>
            }
          </div>
        }

        <fe-async-state
          [loading]="loading() && rows().length === 0"
          [empty]="loaded() && !loading() && loadError() === null && rows().length === 0"
          [error]="loadError()"
          [hasValue]="loaded()"
          [loadingLabel]="'state.loading' | t"
          [loadingHint]="'state.loadingHint' | t"
          [emptyTitle]="emptyTitle()"
          [emptyBody]="emptyBody()"
          [errorTitle]="'clinicAdmin.appointments.loadFailedTitle' | t"
          [errorBody]="'clinicAdmin.common.loadFailed' | t"
          [requestId]="loadError()?.requestId ?? null"
          [requestIdLabel]="'state.requestId' | t"
          [retryLabel]="'clinicAdmin.common.retry' | t"
          (retry)="reload()"
        />

        @if (rows().length > 0) {
          <div class="tl-table-wrap">
            <table class="tl-table" data-testid="clinic-appointments-table">
              <caption class="tl-sr-only">{{ 'clinicAdmin.appointments.title' | t }}</caption>
              <thead>
                <tr>
                  <th scope="col" class="tl-col-check">
                    <input
                      type="checkbox"
                      [checked]="allSelected()"
                      [indeterminate]="someSelected()"
                      (change)="selectAll($any($event.target).checked)"
                      [attr.aria-label]="'clinicAdmin.common.selectAll' | t"
                    />
                  </th>
                  <th scope="col">{{ 'clinicAdmin.appointments.id' | t }}</th>
                  <th scope="col">{{ 'clinicAdmin.appointments.patient' | t }}</th>
                  <th scope="col">{{ 'clinicAdmin.appointments.dateTime' | t }}</th>
                  <th scope="col">{{ 'clinicAdmin.appointments.service' | t }}</th>
                  <th scope="col">{{ 'clinicAdmin.appointments.price' | t }}</th>
                  <th scope="col">{{ 'clinicAdmin.appointments.doctor' | t }}</th>
                  <th scope="col">Treatment Room</th>
                  <th scope="col">{{ 'clinicAdmin.appointments.status' | t }}</th>
                  <th scope="col">{{ 'clinicAdmin.appointments.paymentStatus' | t }}</th>
                  <th scope="col">{{ 'clinicAdmin.appointments.action' | t }}</th>
                </tr>
              </thead>
              <tbody>
                @for (row of rows(); track row.id) {
                  <tr [class.is-selected]="isSelected(row.id)">
                    <td class="tl-col-check">
                      <input
                        type="checkbox"
                        [checked]="isSelected(row.id)"
                        (change)="toggleSelection(row.id, $any($event.target).checked)"
                        [attr.aria-label]="'clinicAdmin.common.select' | t"
                      />
                    </td>
                    <td class="tl-mono" dir="ltr">
                      @if (row.id) {
                        <a
                          class="tl-code"
                          [routerLink]="['/app/clinic/appointments', row.id]"
                        >
                          #{{ row.id.slice(0, 8) }}
                        </a>
                      } @else {
                        —
                      }
                    </td>
                    <td>
                      <div class="tl-patient-cell">
                        @if (row.patientName) {
                          <span class="tl-avatar tl-avatar--sm">
                            {{ initials(row.patientName) }}
                          </span>
                        }
                        <div class="tl-stack">
                          <span class="tl-strong">{{ row.patientName || '—' }}</span>
                          @if (row.patientEmail) {
                            <span class="tl-muted tl-mono" dir="ltr">{{ row.patientEmail }}</span>
                          }
                        </div>
                      </div>
                    </td>
                    <td class="tl-nowrap">
                      {{ formatDate(row.appointmentDate) }}
                    </td>
                    <td>
                      <span class="tl-service-text">{{ row.service || '—' }}</span>
                    </td>
                    <td class="tl-mono tl-nowrap">
                      {{ formatPrice(row.totalAmount ?? row.price) }}
                    </td>
                    <td>
                      <div class="tl-patient-cell">
                        @if (row.doctorName) {
                          <span class="tl-avatar tl-avatar--sm tl-avatar--doc">
                            {{ initials(row.doctorName) }}
                          </span>
                        }
                        <div class="tl-stack">
                          <span class="tl-strong">{{ row.doctorName || '—' }}</span>
                          @if (doctorEmail(row.doctorId); as email) {
                            <span class="tl-muted tl-mono" dir="ltr">{{ email }}</span>
                          }
                        </div>
                      </div>
                    </td>
                    <td>
                      <select
                        class="tl-inline-select"
                        style="min-width:130px;background:#f8fafc;color:var(--ink);border-color:var(--line);"
                        [value]="row.treatmentRoomId || ''"
                        [disabled]="bulkBusy()"
                        (change)="assignRoom(row, $any($event.target).value)"
                        aria-label="Treatment Room"
                      >
                        <option value="">Unassigned</option>
                        @for (room of roomsForRow(row); track room.id) {
                          <option [value]="room.id" [selected]="room.id === row.treatmentRoomId">
                            {{ room.name }}
                          </option>
                        }
                      </select>
                    </td>
                    <td>
                      <select
                        class="tl-inline-select"
                        [class]="statusClass(row.status)"
                        [value]="row.status || 'PENDING'"
                        [disabled]="bulkBusy()"
                        (change)="updateRowStatus(row, $any($event.target).value)"
                        [attr.aria-label]="'clinicAdmin.appointments.status' | t"
                      >
                        @for (st of statuses; track st) {
                          <option [value]="st" [selected]="st === (row.status || 'PENDING')">{{ statusLabel(st) }}</option>
                        }
                      </select>
                    </td>
                    <td>
                      @if (row.paymentStatus === 'PAID') {
                        <span class="tl-badge tl-badge-pill tl-badge--success">
                          {{ paymentLabel('PAID') }}
                        </span>
                      } @else {
                        <select
                          class="tl-inline-select"
                          [class]="paymentClass(row.paymentStatus)"
                          [value]="row.paymentStatus || 'UNPAID'"
                          [disabled]="bulkBusy()"
                          (change)="markPayment(row, $any($event.target).value)"
                          [attr.aria-label]="'clinicAdmin.appointments.paymentStatus' | t"
                        >
                          @for (ps of paymentStates; track ps) {
                            <option [value]="ps">{{ paymentLabel(ps) }}</option>
                          }
                        </select>
                      }
                    </td>
                    <td>
                      <span class="tl-actions">
                        @if (row.id) {
                          <!-- 1. CHECKED_IN: Show teal Encounter clock button -->
                          @if (row.status === 'CHECKED_IN') {
                            <a
                              class="tl-icon-btn tl-icon-btn--teal"
                              [routerLink]="['/app/clinic/encounters', row.id]"
                              [queryParams]="{
                                appointmentId: row.id,
                                patientId: row.patientId,
                                patientName: row.patientName,
                                patientEmail: row.patientEmail,
                                doctorId: row.doctorId,
                                doctorName: row.doctorName,
                                service: row.service,
                                price: row.totalAmount ?? row.price,
                                date: row.appointmentDate,
                                status: row.status
                              }"
                              aria-label="Start Encounter"
                              title="بدء الزيارة السريرية (Encounter)"
                              data-testid="appointment-encounter-btn"
                            >
                              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
                                <circle cx="12" cy="12" r="9"></circle>
                                <polyline points="12 7 12 12 15 15"></polyline>
                              </svg>
                            </a>
                          }
                          <!-- 2. COMPLETED: Show view encounter record button -->
                          @else if (row.status === 'COMPLETED') {
                            <a
                              class="tl-icon-btn tl-icon-btn--success"
                              [routerLink]="['/app/clinic/encounters', row.id]"
                              [queryParams]="{
                                appointmentId: row.id,
                                patientId: row.patientId,
                                patientName: row.patientName,
                                patientEmail: row.patientEmail,
                                doctorId: row.doctorId,
                                doctorName: row.doctorName,
                                service: row.service,
                                price: row.totalAmount ?? row.price,
                                date: row.appointmentDate,
                                status: row.status
                              }"
                              aria-label="Encounter Record"
                              title="الزيارة مكتملة (سجل الزيارة)"
                              data-testid="appointment-encounter-btn"
                            >
                              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" aria-hidden="true">
                                <polyline points="20 6 9 17 4 12"></polyline>
                              </svg>
                            </a>
                          }

                          <a
                            class="tl-icon-btn"
                            [routerLink]="['/app/clinic/invoices']"
                            [queryParams]="{
                              appointmentId: row.id,
                              patientId: row.patientId,
                              patientName: row.patientName,
                              doctorName: row.doctorName,
                              service: row.service,
                              price: row.totalAmount ?? row.price,
                              openGenerate: '1'
                            }"
                            aria-label="Billing Record"
                            title="Billing Record / Invoice"
                            data-testid="appointment-billing-btn"
                          >
                            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
                              <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"></path>
                              <polyline points="14 2 14 8 20 8"></polyline>
                              <line x1="16" y1="13" x2="8" y2="13"></line>
                              <line x1="16" y1="17" x2="8" y2="17"></line>
                            </svg>
                          </a>
                          <a
                            class="tl-icon-btn"
                            [routerLink]="['/app/clinic/appointments', row.id]"
                            [attr.aria-label]="'clinicAdmin.appointments.view' | t"
                            [attr.title]="'clinicAdmin.appointments.view' | t"
                          >
                            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
                              <path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7-10-7-10-7Z"></path>
                              <circle cx="12" cy="12" r="3"></circle>
                            </svg>
                          </a>
                          @if (row.status !== 'CANCELLED') {
                            <button
                              type="button"
                              class="tl-icon-btn tl-icon-btn--danger"
                              [disabled]="bulkBusy()"
                              (click)="cancel(row)"
                              [attr.aria-label]="'clinicAdmin.appointments.cancelAppointment' | t"
                              [attr.title]="'clinicAdmin.appointments.cancelAppointment' | t"
                            >
                              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
                                <polyline points="3 6 5 6 21 6"></polyline>
                                <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"></path>
                              </svg>
                            </button>
                          }
                        }
                      </span>
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>

          <div class="tl-pagination">
            <span class="tl-muted">
              {{ 'clinicAdmin.appointments.page' | t }} {{ pageNumber() }}
              · {{ rows().length }} {{ 'clinicAdmin.common.entries' | t }}
            </span>
            <div class="tl-pagination-actions">
              <button
                type="button"
                class="tl-btn tl-btn-gray"
                [disabled]="previousCursors().length === 0 || loading()"
                (click)="previousPage()"
              >
                {{ 'clinicAdmin.common.previous' | t }}
              </button>
              <button
                type="button"
                class="tl-btn tl-btn-gray"
                [disabled]="!hasNext() || loading()"
                (click)="nextPage()"
              >
                {{ 'clinicAdmin.common.next' | t }}
              </button>
            </div>
          </div>
        }

        @if (operationMessage(); as message) {
          <p
            class="tl-operation"
            [class.tl-operation--error]="operationFailed()"
            role="status"
          >
            {{ message | t }}
          </p>
        }
        @if (rawBannerMessage(); as rawMsg) {
          <p class="tl-operation tl-operation--error" role="status">{{ rawMsg }}</p>
        }
      </section>

      @if (inventoryWarningsModal().length > 0) {
        <div class="tl-modal-backdrop" (click)="inventoryWarningsModal.set([])">
          <div class="tl-modal-card" (click)="$event.stopPropagation()">
            <h3 style="margin:0 0 0.5rem;font-size:1.05rem;color:#b45309;">
              Insufficient Inventory Warning
            </h3>
            <p style="margin:0 0 0.75rem;font-size:0.86rem;color:var(--ink);">
              The procedure/appointment was marked <strong>Completed</strong>, but branch stock for the following required material(s) was below the required amount:
            </p>
            <ul style="margin:0 0 1rem;padding-left:1.25rem;font-size:0.84rem;color:#991b1b;">
              @for (w of inventoryWarningsModal(); track w.itemId) {
                <li style="margin-bottom:0.35rem;">
                  <strong>{{ w.itemName }}</strong>: required {{ w.quantityRequired }}, available {{ w.quantityAvailable }} (shortfall {{ w.shortfallQuantity }})
                </li>
              }
            </ul>
            <div style="display:flex;justify-content:flex-end;">
              <button type="button" class="tl-btn tl-btn-teal" (click)="inventoryWarningsModal.set([])">
                Acknowledge &amp; Continue
              </button>
            </div>
          </div>
        </div>
      }

      <fe-clinic-appointment-drawer
        [open]="drawerOpen()"
        [projectId]="projectId()"
        (closed)="drawerOpen.set(false)"
        (saved)="onCreated()"
      />
    </div>
  `,
  styles: [
    `
      .tl-page-head {
        display: flex;
        align-items: flex-start;
        justify-content: space-between;
        gap: var(--fe-space-3);
        margin-block-end: var(--fe-space-2);
      }
      .tl-page-top {
        margin-bottom: 10px;
      }
      .tl-page-title {
        margin: 0;
        font-size: var(--fe-text-2xl);
        font-weight: 700;
        color: var(--ink);
      }
      .tl-page-subtitle {
        margin: var(--fe-space-1) 0 0;
        color: var(--mute);
        font-size: var(--fe-text-sm);
      }
      .tl-toolbar--wrap {
        flex-wrap: wrap;
        gap: var(--fe-space-3);
      }
      .tl-select--compact {
        inline-size: auto;
        min-inline-size: 9.5rem;
      }
      .tl-search {
        display: flex;
        align-items: center;
        gap: var(--fe-space-2);
        border: 1px solid var(--line);
        border-radius: var(--fe-radius);
        padding: 0 var(--fe-space-3);
        min-inline-size: 20rem;
        background: var(--surf);
      }
      .tl-search svg {
        inline-size: 1.1rem;
        block-size: 1.1rem;
        color: var(--mute);
        flex: none;
      }
      .tl-search input {
        border: 0;
        outline: 0;
        background: transparent;
        inline-size: 100%;
        color: var(--ink);
      }
      .tl-filter-row {
        display: flex;
        align-items: end;
        gap: var(--fe-space-3);
        flex-wrap: wrap;
        padding: var(--fe-space-3) var(--fe-space-4);
        border-block-end: 1px solid var(--line);
        background: var(--surf2);
      }
      .tl-filter {
        display: flex;
        flex-direction: column;
        gap: var(--fe-space-1);
      }
      .tl-filter-label {
        color: var(--mute);
        font-size: var(--fe-text-xs);
        font-weight: 600;
      }
      .tl-link {
        border: 0;
        background: none;
        color: var(--pri);
        cursor: pointer;
        font: inherit;
        padding: 0 0 8px;
      }
      .tl-btn.is-active {
        border-color: var(--tl-teal);
        color: var(--tl-teal-deep, var(--pri));
        background: var(--tl-teal-soft);
      }
      .tl-col-check {
        inline-size: 2.5rem;
        text-align: center;
      }
      .tl-strong {
        font-weight: 600;
      }
      .tl-patient-cell {
        display: flex;
        align-items: center;
        gap: 8px;
        min-width: 0;
      }
      .tl-stack {
        display: flex;
        flex-direction: column;
        gap: 1px;
        min-width: 0;
      }
      .tl-stack .tl-strong {
        font-size: 12.5px;
        font-weight: 600;
        color: var(--ink-strong);
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
        max-width: 160px;
      }
      .tl-stack .tl-muted {
        font-size: 11px;
        color: var(--mute);
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
        max-width: 160px;
      }
      .tl-doctor {
        display: inline-flex;
        align-items: center;
        gap: var(--fe-space-2);
      }
      .tl-avatar {
        inline-size: 1.75rem;
        block-size: 1.75rem;
        border-radius: 2px;
        display: inline-flex;
        align-items: center;
        justify-content: center;
        background: var(--pri-soft);
        color: var(--pri);
        font-size: 11px;
        font-weight: 700;
        flex: none;
        border: 1px solid rgba(44, 181, 166, 0.25);
      }
      .tl-avatar--doc {
        background: var(--surf2);
        color: var(--ink-soft);
        border-color: var(--line);
      }
      .tl-avatar--initials {
        display: inline-flex;
        align-items: center;
        justify-content: center;
        background: var(--pri-soft);
        color: var(--pri);
        font-size: var(--fe-text-xs);
        font-weight: 700;
      }
      .tl-money {
        font-variant-numeric: tabular-nums;
        font-weight: 600;
      }
      .tl-service-text {
        display: block;
        max-width: 170px;
        line-height: 1.35;
        font-size: 12.5px;
        color: var(--ink-strong);
      }
      .tl-code {
        display: inline-block;
        font-family: var(--fe-font-mono);
        font-size: 11.5px;
        font-weight: 600;
        color: var(--tl-teal);
        background: var(--pri-soft);
        padding: 2px 6px;
        border-radius: 2px;
        text-decoration: none;
        border: 1px solid rgba(44, 181, 166, 0.25);
        transition: all 0.15s ease;
      }
      .tl-code:hover {
        background: var(--tl-teal);
        color: #ffffff;
      }
      .tl-badge--appointment {
        background: var(--pri-soft);
        color: var(--pri);
      }
      .tl-badge--success {
        background: var(--accent-green-soft);
        color: var(--accent-green-ink);
      }
      .tl-badge--warning {
        background: var(--accent-amber-soft);
        color: var(--accent-amber-ink);
      }
      .tl-badge--danger {
        background: var(--accent-red-soft);
        color: var(--accent-red-ink);
      }
      .tl-badge--muted {
        background: var(--surf2);
        color: var(--mute);
      }
      .tl-badge-pill {
        display: inline-flex;
        align-items: center;
        height: 24px;
        padding: 1px 8px;
        border-radius: 2px;
        font-size: 11px;
        font-weight: 600;
      }
      .tl-actions {
        display: inline-flex;
        align-items: center;
        gap: 4px;
      }
      .tl-icon-btn {
        display: inline-flex;
        align-items: center;
        justify-content: center;
        inline-size: 1.65rem;
        block-size: 1.65rem;
        border: 1px solid var(--line);
        border-radius: 2px;
        background: var(--surf);
        color: var(--ink-soft);
        cursor: pointer;
        transition: all 0.15s ease;
      }
      .tl-icon-btn svg {
        inline-size: 0.95rem;
        block-size: 0.95rem;
      }
      .tl-icon-btn:hover:not(:disabled) {
        border-color: var(--tl-teal);
        color: var(--tl-teal);
        background: var(--pri-soft);
      }
      .tl-icon-btn:disabled {
        opacity: 0.4;
        cursor: not-allowed;
      }
      .tl-icon-btn--danger:hover:not(:disabled) {
        border-color: var(--bad);
        color: var(--bad);
      }
      .tl-icon-btn--teal {
        background: var(--tl-teal);
        color: var(--on-accent);
        border-color: var(--tl-teal);
      }
      .tl-icon-btn--success {
        background: var(--accent-green-soft);
        color: var(--accent-green-ink);
        border-color: rgba(22, 163, 74, 0.3);
      }
      .tl-icon-btn--success:hover {
        background: #10b981;
        color: #ffffff;
        border-color: #10b981;
      }
      .tl-icon-btn--danger-symbol {
        background: var(--accent-red-soft);
        color: var(--accent-red-ink);
        border-color: rgba(220, 38, 38, 0.3);
        cursor: default;
      }
      .tl-icon-btn--teal:hover:not(:disabled) {
        background: var(--tl-teal-hover);
        color: var(--on-accent);
        border-color: var(--tl-teal-hover);
      }
      .tl-icon-btn--checkin {
        background: var(--accent-green-soft);
        color: var(--accent-green-ink);
        border-color: rgba(22, 163, 74, 0.3);
      }
      .tl-icon-btn--checkin:hover:not(:disabled) {
        background: #10b981;
        color: #ffffff;
        border-color: #10b981;
      }
      .tl-inline-select {
        appearance: none;
        -webkit-appearance: none;
        -moz-appearance: none;
        display: inline-flex;
        align-items: center;
        height: 25px;
        padding: 1px 20px 1px 8px;
        font-size: 11px;
        font-weight: 600;
        border-radius: 2px;
        border: 1px solid transparent;
        cursor: pointer;
        transition: all 0.15s ease;
        background-repeat: no-repeat;
        background-position: right 6px center;
        background-size: 9px;
        background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='%2364748b' stroke-width='2.5'%3E%3Cpolyline points='6 9 12 15 18 9'%3E%3C/polyline%3E%3C/svg%3E");
        min-inline-size: 6rem;
      }
      [dir="rtl"] .tl-inline-select {
        padding: 1px 8px 1px 20px;
        background-position: left 6px center;
      }
      .tl-inline-select.tl-badge--appointment {
        background-color: var(--pri-soft);
        color: var(--pri);
        border-color: rgba(44, 181, 166, 0.3);
      }
      .tl-inline-select.tl-badge--success {
        background-color: var(--accent-green-soft);
        color: var(--accent-green-ink);
        border-color: rgba(22, 163, 74, 0.3);
      }
      .tl-inline-select.tl-badge--warning {
        background-color: var(--accent-amber-soft);
        color: var(--accent-amber-ink);
        border-color: rgba(245, 158, 11, 0.3);
      }
      .tl-inline-select.tl-badge--danger {
        background-color: var(--accent-red-soft);
        color: var(--accent-red-ink);
        border-color: rgba(220, 38, 38, 0.3);
      }
      .tl-inline-select.tl-badge--muted {
        background-color: var(--surf2);
        color: var(--mute);
        border-color: var(--line);
      }
      .tl-inline-select:focus-visible {
        outline: 2px solid var(--tl-teal);
        outline-offset: 1px;
      }
      .tl-table thead tr {
        background: var(--surf2);
        color: var(--mute);
        border-bottom: 1px solid var(--line);
      }
      .tl-table th {
        padding: 10px 12px;
        font-size: 11px;
        font-weight: 700;
        text-transform: uppercase;
        letter-spacing: 0.05em;
        color: var(--mute);
        background: var(--surf2);
        border: none;
        border-bottom: 1px solid var(--line);
        white-space: nowrap;
      }
      .tl-table td {
        padding: 10px 12px;
        font-size: 12.5px;
        color: var(--ink-soft);
        border-bottom: 1px solid var(--line);
        vertical-align: middle;
      }
      .tl-table tbody tr {
        transition: background-color 0.15s ease;
      }
      .tl-table tbody tr:hover {
        background: var(--surf2);
      }
      .tl-table tbody tr.is-selected {
        background: var(--pri-soft);
      }
      .tl-pagination {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: var(--fe-space-2);
        padding: 8px 12px;
        border-block-start: 1px solid var(--line);
      }
      .tl-pagination-actions {
        display: flex;
        gap: var(--fe-space-2);
      }
      .tl-operation {
        margin: 0;
        padding: var(--fe-space-2) var(--fe-space-4);
        border-block-start: 1px solid var(--line);
        color: var(--accent-green-ink);
        font-size: var(--fe-text-sm);
      }
      .tl-operation--error {
        color: var(--bad);
      }
      .tl-sr-only {
        position: absolute;
        inline-size: 1px;
        block-size: 1px;
        padding: 0;
        margin: -1px;
        overflow: hidden;
        clip: rect(0, 0, 0, 0);
        white-space: nowrap;
        border: 0;
      }
      @media (max-width: 900px) {
        .tl-page-head,
        .tl-pagination {
          flex-direction: column;
          align-items: stretch;
        }
        .tl-search {
          min-inline-size: 0;
        }
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
        border-radius: 12px;
        padding: 1.25rem;
        width: min(500px, 100%);
        box-shadow: 0 20px 50px rgba(0, 0, 0, 0.22);
      }
    `,
  ],
})
export class ClinicAppointmentsComponent implements OnInit {
  private readonly clinic = inject(ClinicApi);
  private readonly patientsApi = inject(ClinicPatientApi);
  private readonly route = inject(ActivatedRoute);
  private readonly projects = inject(ProjectContextService);
  private readonly sessions = inject(SessionService);
  private readonly i18n = inject(I18nService);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly format = inject(FormatService);

  protected readonly statuses = STATUS_STATES;
  protected readonly paymentStates = PAYMENT_STATES;

  protected readonly rows = signal<readonly ClinicAppointment[]>([]);
  protected readonly loading = signal(false);
  protected readonly loaded = signal(false);
  protected readonly loadError = signal<ApiError | null>(null);

  protected readonly term = signal('');
  protected readonly statusFilter = signal('');
  protected readonly paymentFilter = signal('');
  protected readonly advanced = signal(false);

  protected readonly currentCursor = signal<string | undefined>(undefined);
  protected readonly previousCursors = signal<readonly (string | undefined)[]>([]);
  protected readonly nextCursor = signal<string | undefined>(undefined);

  protected readonly selectedIds = signal<ReadonlySet<string>>(new Set());
  protected readonly bulkAction = signal('');
  protected readonly bulkBusy = signal(false);

  protected readonly operationMessage = signal<string | null>(null);
  protected readonly operationFailed = signal(false);
  protected readonly rawBannerMessage = signal<string | null>(null);
  protected readonly inventoryWarningsModal = signal<readonly InventoryStockWarning[]>([]);
  protected readonly treatmentRooms = signal<readonly TreatmentRoomRow[]>([]);

  protected readonly drawerOpen = signal(false);
  protected readonly doctorEmails = signal<ReadonlyMap<string, string>>(new Map());

  protected readonly hasNext = computed(() => !!this.nextCursor());
  protected readonly pageNumber = computed(() => this.previousCursors().length + 1);
  protected readonly hasFilters = computed(
    () => this.term().trim() !== '' || this.statusFilter() !== '' || this.paymentFilter() !== '',
  );
  protected readonly allSelected = computed(() => {
    const rows = this.rows();
    return rows.length > 0 && rows.every((row) => row.id && this.selectedIds().has(row.id));
  });
  protected readonly someSelected = computed(() => {
    const selected = this.selectedIds();
    return selected.size > 0 && !this.allSelected();
  });
  protected readonly emptyTitle = computed(() =>
    this.hasFilters()
      ? this.i18n.t('clinicAdmin.appointments.noResultsTitle')
      : this.i18n.t('clinicAdmin.appointments.emptyTitle'),
  );
  protected readonly emptyBody = computed(() =>
    this.hasFilters()
      ? this.i18n.t('clinicAdmin.appointments.noResultsBody')
      : this.i18n.t('clinicAdmin.appointments.emptyBody'),
  );

  private loadToken = 0;
  private searchTimer: ReturnType<typeof setTimeout> | null = null;
  private messageTimer: ReturnType<typeof setTimeout> | null = null;

  constructor() {
    this.i18n.register({ clinicAdmin: clinicAdminStrings as Namespace });
    this.destroyRef.onDestroy(() => {
      if (this.searchTimer) clearTimeout(this.searchTimer);
      if (this.messageTimer) clearTimeout(this.messageTimer);
    });
  }

  ngOnInit(): void {
    // The calendar links here with `?create=1` so its booking action lands on the
    // one drawer that speaks to the Phase 3 contract, rather than a second modal
    // that would drift from it.
    if (this.route.snapshot.queryParamMap.get('create') === '1') {
      this.drawerOpen.set(true);
    }
    void this.load(true);
    void this.loadDoctorEmails();
    void this.loadTreatmentRooms();
  }

  private async loadTreatmentRooms(): Promise<void> {
    try {
      const rooms = await this.patientsApi.treatmentRooms({ projectId: this.projectId() });
      this.treatmentRooms.set((rooms ?? []).filter((r) => r.isActive));
    } catch {
      this.treatmentRooms.set([]);
    }
  }

  protected roomsForRow(row: ClinicAppointment): readonly TreatmentRoomRow[] {
    const rooms = this.treatmentRooms();
    if (!row.clinicId) return rooms;
    return rooms.filter((r) => !r.clinicId || r.clinicId === row.clinicId);
  }

  protected async assignRoom(row: ClinicAppointment, roomId: string): Promise<void> {
    if (!row.id || this.bulkBusy()) return;
    this.bulkBusy.set(true);
    this.rawBannerMessage.set(null);
    try {
      await this.patientsApi.assignTreatmentRoom(
        row.id,
        roomId || null,
        { projectId: this.projectId() },
      );
      const roomName = this.treatmentRooms().find((r) => r.id === roomId)?.name ?? null;
      this.rows.update((list) =>
        list.map((item) =>
          item.id === row.id
            ? { ...item, treatmentRoomId: roomId || null, treatmentRoomName: roomName }
            : item,
        ),
      );
      this.showMessage('clinicAdmin.appointments.statusUpdated', false);
    } catch (err) {
      const msg =
        err instanceof ApiError
          ? err.message
          : err instanceof Error
            ? err.message
            : 'Could not assign treatment room (possible scheduling conflict).';
      this.rawBannerMessage.set(msg);
      await this.load();
    } finally {
      this.bulkBusy.set(false);
    }
  }

  protected projectId(): string | undefined {
    return this.projects.selectedProjectId() ?? undefined;
  }

  protected async load(reset = false): Promise<void> {
    if (reset) {
      this.rows.set([]);
      this.loaded.set(false);
      this.selectedIds.set(new Set());
      this.bulkAction.set('');
    }
    const token = ++this.loadToken;
    this.loading.set(true);
    this.loadError.set(null);
    try {
      const page = await this.clinic.appointments({
        projectId: this.projectId(),
        from: windowStart(),
        to: windowEnd(),
        status: this.statusFilter() || undefined,
        paymentStatus: this.paymentFilter() || undefined,
        q: this.term().trim() || undefined,
        cursor: this.currentCursor(),
        limit: 10,
      });
      if (token !== this.loadToken) return;
      this.rows.set(page.items ?? []);
      this.nextCursor.set(page.nextCursor);
      this.loaded.set(true);
    } catch (cause) {
      if (token !== this.loadToken) return;
      this.loadError.set(
        cause instanceof ApiError
          ? cause
          : new ApiError(0, 'INTERNAL', 'The appointment list could not be loaded.', null, null),
      );
      this.nextCursor.set(undefined);
      this.rows.set([]);
    } finally {
      if (token === this.loadToken) this.loading.set(false);
    }
  }

  protected reload(): void {
    void this.load();
  }

  private async loadDoctorEmails(): Promise<void> {
    if (!this.sessions.can()(PERMISSIONS.doctorReadManagement)) return;
    try {
      const page = await this.clinic.doctorProfiles({
        projectId: this.projectId(),
        limit: 100,
      });
      const map = new Map<string, string>();
      for (const profile of page.items ?? []) {
        const email = (profile as ClinicDoctorProfile).email;
        if (profile.id && email) map.set(profile.id, email);
      }
      this.doctorEmails.set(map);
    } catch {
      // The list still works without doctor email. The permission boundary is the
      // reason this is optional: CLINIC_STAFF does not hold the management read.
    }
  }

  protected onSearch(event: Event): void {
    this.term.set((event.target as HTMLInputElement).value);
    if (this.searchTimer) clearTimeout(this.searchTimer);
    this.searchTimer = setTimeout(() => {
      this.currentCursor.set(undefined);
      this.previousCursors.set([]);
      void this.load(true);
    }, 300);
  }

  protected onStatusChange(value: string): void {
    this.statusFilter.set(value);
    this.currentCursor.set(undefined);
    this.previousCursors.set([]);
    void this.load(true);
  }

  protected onPaymentChange(value: string): void {
    this.paymentFilter.set(value);
    this.currentCursor.set(undefined);
    this.previousCursors.set([]);
    void this.load(true);
  }

  protected clearFilters(): void {
    this.term.set('');
    this.statusFilter.set('');
    this.paymentFilter.set('');
    this.currentCursor.set(undefined);
    this.previousCursors.set([]);
    void this.load(true);
  }

  protected nextPage(): void {
    const next = this.nextCursor();
    if (!next) return;
    this.selectedIds.set(new Set());
    this.previousCursors.set([...this.previousCursors(), this.currentCursor()]);
    this.currentCursor.set(next);
    void this.load();
  }

  protected previousPage(): void {
    const stack = [...this.previousCursors()];
    const previous = stack.pop();
    this.selectedIds.set(new Set());
    this.previousCursors.set(stack);
    this.currentCursor.set(previous);
    void this.load();
  }

  protected isSelected(id: string | undefined): boolean {
    return !!id && this.selectedIds().has(id);
  }

  protected toggleSelection(id: string | undefined, checked: boolean): void {
    if (!id) return;
    const next = new Set(this.selectedIds());
    if (checked) next.add(id);
    else next.delete(id);
    this.selectedIds.set(next);
  }

  protected selectAll(checked: boolean): void {
    if (!checked) {
      this.selectedIds.set(new Set());
      return;
    }
    this.selectedIds.set(
      new Set(this.rows().map((row) => row.id).filter((id): id is string => !!id)),
    );
  }

  protected async applyBulk(): Promise<void> {
    const action = this.bulkAction();
    const ids = [...this.selectedIds()];
    if (action === '' || ids.length === 0) return;
    if (!window.confirm(this.i18n.t('clinicAdmin.appointments.confirmBulk'))) return;

    this.bulkBusy.set(true);
    const results = await Promise.allSettled(
      ids.map((id) =>
        action === 'CANCELLED'
          ? this.patientsApi.updateAppointmentStatus(
              id,
              { status: 'CANCELLED', cancelledReason: 'Cancelled from appointment list' },
              { projectId: this.projectId() },
            )
          : this.patientsApi.updateAppointmentPayment(
              id,
              action,
              { projectId: this.projectId() },
            ),
      ),
    );
    this.bulkBusy.set(false);
    const failed = results.filter((result) => result.status === 'rejected').length;
    this.showMessage(
      failed === 0 ? 'clinicAdmin.appointments.statusUpdated' : 'clinicAdmin.common.saveFailed',
      failed !== 0,
    );
    this.selectedIds.set(new Set());
    this.bulkAction.set('');
    await this.load();
  }

  protected async updateRowStatus(row: ClinicAppointment, status: string): Promise<void> {
    if (!row.id || !status || row.status === status || this.bulkBusy()) return;
    this.bulkBusy.set(true);
    try {
      const res = await this.patientsApi.updateAppointmentStatus(
        row.id,
        { status },
        { projectId: this.projectId() },
      );
      this.rows.update((list) =>
        list.map((item) => (item.id === row.id ? { ...item, status } : item)),
      );
      if (res?.inventoryWarnings && res.inventoryWarnings.length > 0) {
        this.inventoryWarningsModal.set(res.inventoryWarnings);
      }
      this.showMessage('clinicAdmin.appointments.statusUpdated', false);
      await this.load();
    } catch {
      this.showMessage('clinicAdmin.common.saveFailed', true);
    } finally {
      this.bulkBusy.set(false);
    }
  }

  protected async markPayment(row: ClinicAppointment, paymentStatus: string): Promise<void> {
    if (!row.id || this.bulkBusy()) return;
    this.bulkBusy.set(true);
    try {
      await this.patientsApi.updateAppointmentPayment(
        row.id,
        paymentStatus,
        { projectId: this.projectId() },
      );
      this.showMessage('clinicAdmin.appointments.paymentUpdated', false);
      await this.load();
    } catch {
      this.showMessage('clinicAdmin.common.saveFailed', true);
    } finally {
      this.bulkBusy.set(false);
    }
  }

  protected async cancel(row: ClinicAppointment): Promise<void> {
    if (!row.id || this.bulkBusy()) return;
    if (!window.confirm(this.i18n.t('clinicAdmin.appointments.confirmCancel'))) return;
    this.bulkBusy.set(true);
    try {
      await this.patientsApi.updateAppointmentStatus(
        row.id,
        { status: 'CANCELLED', cancelledReason: 'Cancelled from appointment list' },
        { projectId: this.projectId() },
      );
      this.showMessage('clinicAdmin.appointments.statusUpdated', false);
      await this.load();
    } catch {
      this.showMessage('clinicAdmin.common.saveFailed', true);
    } finally {
      this.bulkBusy.set(false);
    }
  }

  protected openCreate(): void {
    this.drawerOpen.set(true);
  }

  protected async onCreated(): Promise<void> {
    this.drawerOpen.set(false);
    this.showMessage('clinicAdmin.appointments.saved', false);
    this.currentCursor.set(undefined);
    this.previousCursors.set([]);
    await this.load(true);
  }

  protected exportCsv(): void {
    const selected = this.selectedIds();
    const rows =
      selected.size > 0
        ? this.rows().filter((row) => row.id && selected.has(row.id))
        : this.rows();
    if (rows.length === 0) return;

    const header = [
      this.i18n.t('clinicAdmin.appointments.id'),
      this.i18n.t('clinicAdmin.appointments.patient'),
      this.i18n.t('clinicAdmin.appointments.dateTime'),
      this.i18n.t('clinicAdmin.appointments.service'),
      this.i18n.t('clinicAdmin.appointments.price'),
      this.i18n.t('clinicAdmin.appointments.doctor'),
      this.i18n.t('clinicAdmin.appointments.status'),
      this.i18n.t('clinicAdmin.appointments.paymentStatus'),
    ];
    const lines = rows.map((row) =>
      [
        row.ticketNumber ?? row.id ?? '',
        row.patientName ?? '',
        row.appointmentDate ?? '',
        row.service ?? '',
        row.totalAmount ?? row.price ?? '',
        row.doctorName ?? '',
        this.statusLabel(row.status),
        this.paymentLabel(row.paymentStatus),
      ]
        .map(csvCell)
        .join(','),
    );
    const csv = `\ufeff${header.map(csvCell).join(',')}\r\n${lines.join('\r\n')}`;
    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = `appointments-${new Date().toISOString().slice(0, 10)}.csv`;
    anchor.click();
    URL.revokeObjectURL(url);
  }

  protected statusLabel(status: string | undefined): string {
    if (!status) return '—';
    const known = (STATUS_STATES as readonly string[]).includes(status);
    return known ? this.i18n.t(`clinicAdmin.appointments.status.${status}`) : status;
  }

  protected paymentLabel(status: string | null | undefined): string {
    if (!status) return '—';
    const known = (PAYMENT_STATES as readonly string[]).includes(status);
    return known ? this.i18n.t(`clinicAdmin.appointments.payment.${status}`) : status;
  }

  protected statusClass(status: string | undefined): string {
    switch (status) {
      case 'COMPLETED':
      case 'CHECKED_IN':
        return 'tl-badge--success';
      case 'CONFIRMED':
      case 'IN_PROGRESS':
        return 'tl-badge--appointment';
      case 'PENDING':
        return 'tl-badge--warning';
      case 'CANCELLED':
      case 'NO_SHOW':
        return 'tl-badge--danger';
      default:
        return 'tl-badge--muted';
    }
  }

  protected paymentClass(status: string | null | undefined): string {
    switch (status) {
      case 'PAID':
        return 'tl-badge--success';
      case 'PARTIAL':
        return 'tl-badge--warning';
      case 'REFUNDED':
        return 'tl-badge--muted';
      default:
        return 'tl-badge--danger';
    }
  }

  protected doctorEmail(doctorId: string | undefined): string | undefined {
    return doctorId ? this.doctorEmails().get(doctorId) : undefined;
  }

  protected initials(name: string | null | undefined): string {
    if (!name) return '?';
    const parts = name.trim().split(/\s+/).slice(0, 2);
    return parts.map((part) => part.charAt(0)).join('').toUpperCase() || '?';
  }

  protected shortId(id: string | undefined): string {
    return id ? id.slice(0, 8).toUpperCase() : '—';
  }

  protected formatDate(iso: string | null | undefined): string {
    if (!iso) return '—';
    try {
      return this.format.dateTime(iso);
    } catch {
      return iso;
    }
  }

  protected formatPrice(amount: number | null | undefined): string {
    if (amount == null) return '—';
    try {
      return this.format.currency(amount, 'JOD');
    } catch {
      return String(amount);
    }
  }

  private showMessage(key: string, failed: boolean): void {
    this.operationMessage.set(this.i18n.t(key));
    this.operationFailed.set(failed);
    if (this.messageTimer) clearTimeout(this.messageTimer);
    this.messageTimer = setTimeout(() => this.operationMessage.set(null), 5000);
  }
}

function csvCell(value: unknown): string {
  const text = value === null || value === undefined ? '' : String(value);
  return `"${text.replaceAll('"', '""')}"`;
}

function localDate(date: Date): string {
  const copy = new Date(date.getTime() - date.getTimezoneOffset() * 60_000);
  return copy.toISOString().slice(0, 10);
}

function windowStart(): string {
  const date = new Date();
  date.setDate(date.getDate() - 365);
  return `${localDate(date)}T00:00:00`;
}

function windowEnd(): string {
  const date = new Date();
  date.setDate(date.getDate() + 365);
  return `${localDate(date)}T23:59:59`;
}
