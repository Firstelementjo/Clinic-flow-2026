/**
 * `/app/clinic/branches` — the Clinic List.
 *
 * This screen used to be the `fe-not-available` card because the product had no
 * place to store a physical clinic: a clinic *was* a project. Phase 38 adds
 * `clinic_locations`, so the screen reads and writes real rows.
 *
 * The toolbar is not decorative. Search and status are sent to the server, the
 * status switch and the bulk action PATCH `isActive`, Export writes the visible
 * rows to a CSV, and New opens the same drawer for create and edit. A control
 * that cannot reach the API is not rendered.
 */
import {
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  Component,
  DestroyRef,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { FormBuilder, FormsModule, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ApiError } from '../../core/api/api.service';
import { ClinicApi, type ClinicFrontDeskOfficer, type ClinicLocation, type ClinicLocationInput } from '../../core/api/endpoints';
import { ClinicRbacService } from '../../core/auth/clinic-rbac';
import { PERMISSIONS } from '../../core/auth/permissions';
import { SessionService } from '../../core/auth/session.service';
import { ProjectContextService } from '../../core/context/project-context.service';
import { I18nService } from '../../core/i18n/i18n.service';
import type { Namespace } from '../../core/i18n/i18n.types';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { TlDrawerComponent } from '../../ui/tl-drawer.component';
import { clinicAdminStrings } from './clinic-admin.strings';
import { ClinicPatientApi, type TreatmentRoomRow } from './clinic-patient.api';

type StatusFilter = '' | 'ACTIVE' | 'INACTIVE';
type BulkAction = '' | 'ACTIVE' | 'INACTIVE';
type Toast = { readonly message: string; readonly error: boolean };
type LocationsPageTab = 'branches' | 'rooms';

@Component({
  selector: 'fe-clinic-locations-page',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, ReactiveFormsModule, RouterLink, TlDrawerComponent, TranslatePipe],
  template: `
    <div class="tl-page" data-testid="clinic-locations">
      <div class="tl-page-top">
        <nav class="tl-breadcrumbs" aria-label="Breadcrumb">
          <a [routerLink]="isPlatformAdmin() ? '/app/admin/dashboard' : '/app/dashboard'">{{ 'clinicAdmin.nav.home' | t }}</a>
          <span aria-hidden="true">&gt;&gt;</span>
          <span class="active">{{ 'clinicAdmin.clinics.breadcrumb' | t }}</span>
        </nav>
        @if (!isPlatformAdmin()) {
          <a
            class="tl-btn tl-btn-dark"
            routerLink="/app/clinic/appointments"
            [queryParams]="{ create: '1' }"
          >
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" aria-hidden="true">
              <circle cx="12" cy="12" r="9" />
              <path d="M12 8v8M8 12h8" />
            </svg>
            {{ 'clinicAdmin.common.appointment' | t }}
          </a>
        }
      </div>

      <nav class="tl-tabs-nav" aria-label="Branches and Treatment Rooms">
        <button
          type="button"
          class="tl-tab-btn"
          [class.tl-tab-btn--active]="activeTab() === 'branches'"
          (click)="switchTab('branches')"
        >
          Clinic Branches ({{ rows().length }})
        </button>
        <button
          type="button"
          class="tl-tab-btn"
          [class.tl-tab-btn--active]="activeTab() === 'rooms'"
          (click)="switchTab('rooms')"
        >
          Treatment Rooms ({{ treatmentRooms().length }})
        </button>
      </nav>

      @if (activeTab() === 'branches') {
      <div class="tl-toolbar">
        <div class="tl-toolbar-left">
          <select
            class="tl-select"
            [value]="bulkAction()"
            (change)="onBulkAction($any($event.target).value)"
            [attr.aria-label]="'clinicAdmin.common.bulkAction' | t"
          >
            <option value="">{{ 'clinicAdmin.common.noAction' | t }}</option>
            <option value="ACTIVE">{{ 'clinicAdmin.common.active' | t }}</option>
            <option value="INACTIVE">{{ 'clinicAdmin.common.inactive' | t }}</option>
          </select>
          <button
            type="button"
            class="tl-btn tl-btn-gray"
            (click)="applyBulk()"
            [disabled]="!canManage() || bulkAction() === '' || selectedIds().size === 0"
          >
            {{ 'clinicAdmin.common.apply' | t }}
          </button>
          <button
            type="button"
            class="tl-btn tl-btn-teal"
            (click)="exportCsv()"
            [disabled]="rows().length === 0"
          >
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" aria-hidden="true">
              <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M7 10l5 5 5-5M12 15V3" />
            </svg>
            {{ 'clinicAdmin.common.export' | t }}
          </button>
        </div>

        <div class="tl-toolbar-right">
          <select
            class="tl-select"
            [value]="statusFilter()"
            (change)="onStatusChange($any($event.target).value)"
            [attr.aria-label]="'clinicAdmin.common.status' | t"
          >
            <option value="">{{ 'clinicAdmin.common.all' | t }}</option>
            <option value="ACTIVE">{{ 'clinicAdmin.common.activeOnly' | t }}</option>
            <option value="INACTIVE">{{ 'clinicAdmin.common.inactiveOnly' | t }}</option>
          </select>

          <div class="tl-search-box">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
              <circle cx="11" cy="11" r="7" />
              <path d="m20 20-3.5-3.5" />
            </svg>
            <input
              type="search"
              [value]="searchTerm()"
              (input)="onSearch($any($event.target).value)"
              [attr.placeholder]="'clinicAdmin.clinics.searchPlaceholder' | t"
              [attr.aria-label]="'clinicAdmin.common.search' | t"
            />
          </div>

          <button
            type="button"
            class="tl-btn tl-btn-teal"
            (click)="openCreate()"
            [disabled]="!canManage()"
          >
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" aria-hidden="true">
              <path d="M12 5v14M5 12h14" />
            </svg>
            {{ 'clinicAdmin.common.new' | t }}
          </button>
        </div>
      </div>

      <div class="tl-table-wrap" data-testid="clinic-locations-table">
        @if (loading() && rows().length === 0) {
          <div class="tl-table-empty" role="status">{{ 'clinicAdmin.common.loading' | t }}</div>
        } @else if (loadFailed()) {
          <div class="tl-table-empty" role="alert">
            <strong>{{ 'clinicAdmin.common.loadFailed' | t }}</strong>
            <button type="button" class="tl-btn tl-btn-gray" (click)="load()">
              {{ 'clinicAdmin.common.retry' | t }}
            </button>
          </div>
        } @else if (rows().length === 0) {
          <div class="tl-table-empty" data-testid="clinic-locations-empty">
            <strong>{{ 'clinicAdmin.clinics.emptyTitle' | t }}</strong>
            <span>{{ 'clinicAdmin.clinics.emptyBody' | t }}</span>
          </div>
        } @else {
          <table class="tl-table">
            <caption class="fe-sr">{{ 'clinicAdmin.clinics.title' | t }}</caption>
            <thead>
              <tr>
                <th scope="col" style="width: 44px">
                  <input
                    type="checkbox"
                    class="tl-table-check"
                    [checked]="allPageSelected()"
                    (change)="toggleAll($any($event.target).checked)"
                    [attr.aria-label]="'clinicAdmin.common.selectAll' | t"
                  />
                </th>
                <th scope="col">{{ 'clinicAdmin.clinics.name' | t }}</th>
                <th scope="col">{{ 'clinicAdmin.clinics.officer' | t }}</th>
                <th scope="col">{{ 'clinicAdmin.clinics.contact' | t }}</th>
                <th scope="col">{{ 'clinicAdmin.clinics.description' | t }}</th>
                <th scope="col">{{ 'clinicAdmin.common.status' | t }}</th>
                <th scope="col">{{ 'clinicAdmin.common.actions' | t }}</th>
              </tr>
            </thead>
            <tbody>
              @for (row of pagedRows(); track row.id ?? $index) {
                <tr>
                  <td>
                    <input
                      type="checkbox"
                      class="tl-table-check"
                      [checked]="isSelected(row.id)"
                      (change)="toggleSelection(row.id, $any($event.target).checked)"
                      [attr.aria-label]="'clinicAdmin.common.select' | t"
                    />
                  </td>
                  <td>
                    <div class="tl-cell-profile">
                      <div class="tl-avatar">
                        @if (row.brandImageUrl) {
                          <img [src]="row.brandImageUrl" [alt]="row.name ?? ''" />
                        } @else {
                          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true">
                            <path d="M3 21h18M5 21V7l7-4 7 4v14M9 9h.01M9 13h.01M9 17h.01M15 9h.01M15 13h.01M15 17h.01" />
                          </svg>
                        }
                      </div>
                      <div class="tl-profile-info">
                        <span class="tl-profile-name">{{ row.name || '—' }}</span>
                        <span class="tl-profile-sub">{{ locationLine(row) }}</span>
                      </div>
                    </div>
                  </td>
                  <td>{{ row.frontDeskOfficerName || ('clinicAdmin.clinics.unassigned' | t) }}</td>
                  <td>{{ row.contactNumber || '—' }}</td>
                  <td>{{ row.description || '—' }}</td>
                  <td>
                    <label class="tl-switch">
                      <input
                        type="checkbox"
                        [checked]="row.isActive === true"
                        [disabled]="!canManage()"
                        (change)="toggleActive(row, $any($event.target).checked, $any($event.target))"
                        [attr.aria-label]="'clinicAdmin.common.status' | t"
                      />
                      <span class="tl-slider"></span>
                    </label>
                  </td>
                  <td>
                    <div class="tl-actions">
                      <button
                        type="button"
                        class="tl-action-icon edit"
                        (click)="openEdit(row)"
                        [disabled]="!canManage()"
                        [attr.aria-label]="'clinicAdmin.common.edit' | t"
                      >
                        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
                          <path d="M12 20h9M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4Z" />
                        </svg>
                      </button>
                      <button
                        type="button"
                        class="tl-action-icon delete"
                        (click)="startDeleteClinic(row)"
                        [disabled]="!canManage()"
                        [attr.aria-label]="'clinicAdmin.common.delete' | t"
                      >
                        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
                          <path d="M3 6h18M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2" />
                          <line x1="10" y1="11" x2="10" y2="17" />
                          <line x1="14" y1="11" x2="14" y2="17" />
                        </svg>
                      </button>
                    </div>
                  </td>
                </tr>
              }
            </tbody>
          </table>

          <div class="tl-table-footer">
            <span>
              {{ 'clinicAdmin.common.showing' | t }} {{ showingFrom() }}–{{ showingTo() }}
              {{ 'clinicAdmin.common.of' | t }} {{ rows().length }} {{ 'clinicAdmin.common.entries' | t }}
            </span>
            <div class="tl-pagination">
              <button
                type="button"
                class="tl-page-btn"
                (click)="goToPage(page() - 1)"
                [disabled]="page() === 1"
                [attr.aria-label]="'clinicAdmin.common.previous' | t"
              >
                ‹
              </button>
              @for (number of pageNumbers(); track number) {
                <button
                  type="button"
                  class="tl-page-btn"
                  [class.active]="number === page()"
                  (click)="goToPage(number)"
                >
                  {{ number }}
                </button>
              }
              <button
                type="button"
                class="tl-page-btn"
                (click)="goToPage(page() + 1)"
                [disabled]="page() === pageCount()"
                [attr.aria-label]="'clinicAdmin.common.next' | t"
              >
                ›
              </button>
            </div>
          </div>
        }
      </div>
      }

      @if (activeTab() === 'rooms') {
        <div class="tl-toolbar">
          <div class="tl-toolbar-left">
            <select
              class="tl-select"
              [ngModel]="roomClinicFilter()"
              (ngModelChange)="roomClinicFilter.set($event); loadRooms()"
              aria-label="Filter Rooms by Branch"
            >
              <option value="">All Branches</option>
              @for (loc of rows(); track loc.id) {
                <option [value]="loc.id">{{ loc.name }}</option>
              }
            </select>
          </div>
          <div class="tl-toolbar-right">
            <button type="button" class="tl-btn tl-btn-gray" (click)="loadRooms()">Refresh Utilization</button>
            <button
              type="button"
              class="tl-btn tl-btn-teal"
              (click)="openCreateRoom()"
              [disabled]="!canManage()"
            >
              + Add Treatment Room
            </button>
          </div>
        </div>

        <div class="tl-table-wrap">
          @if (roomsLoading()) {
            <div class="tl-table-empty" role="status">Loading treatment rooms &amp; utilization...</div>
          } @else if (treatmentRooms().length === 0) {
            <div class="tl-table-empty">
              <strong>No treatment rooms found</strong>
              <span>Add treatment rooms to assign appointments and track 30-day room utilization and productivity.</span>
            </div>
          } @else {
            <table class="tl-table">
              <thead>
                <tr>
                  <th>Room Name / Number</th>
                  <th>Branch</th>
                  <th>Room Type</th>
                  <th>Live Availability</th>
                  <th>Current Doctor</th>
                  <th>Utilization %</th>
                  <th>Completed Procedures</th>
                  <th>Room Revenue</th>
                  <th>Actions</th>
                </tr>
              </thead>
              <tbody>
                @for (room of treatmentRooms(); track room.id) {
                  <tr>
                    <td>
                      <strong>{{ room.name }}</strong>
                      @if (room.roomNumber) {
                        <div style="font-size:0.76rem;color:var(--mute);">{{ room.roomNumber }}</div>
                      }
                    </td>
                    <td>{{ room.clinicName || '—' }}</td>
                    <td>{{ room.roomType || 'GENERAL' }}</td>
                    <td>
                      @if (!room.isActive) {
                        <span class="tl-pill tl-pill--muted">INACTIVE</span>
                      } @else if (room.currentStatus === 'OCCUPIED') {
                        <span class="tl-pill tl-pill--warn">OCCUPIED</span>
                      } @else {
                        <span class="tl-pill tl-pill--ok">AVAILABLE</span>
                      }
                    </td>
                    <td>{{ room.currentOccupiedByDoctor || '—' }}</td>
                    <td><strong>{{ room.utilizationPercent }}%</strong></td>
                    <td>{{ room.completedProceduresCount }}</td>
                    <td><strong>{{ room.roomRevenue }} JOD</strong></td>
                    <td>
                      <div style="display:flex;gap:0.35rem;">
                        <button
                          type="button"
                          class="tl-btn tl-btn-gray"
                          style="padding:0.25rem 0.55rem;font-size:0.76rem;"
                          (click)="openEditRoom(room)"
                          [disabled]="!canManage()"
                        >
                          Edit
                        </button>
                        <button
                          type="button"
                          class="tl-btn tl-btn-gray"
                          style="padding:0.25rem 0.55rem;font-size:0.76rem;color:#c62828;"
                          (click)="deleteRoom(room)"
                          [disabled]="!canManage()"
                        >
                          Delete
                        </button>
                      </div>
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          }
        </div>
      }
    </div>

    <fe-tl-drawer
      [open]="drawerOpen()"
      [title]="(editing() ? 'clinicAdmin.clinics.editTitle' : 'clinicAdmin.clinics.createTitle') | t"
      [subtitle]="'clinicAdmin.clinics.drawerHint' | t"
      (closed)="closeDrawer()"
    >
      <form id="clinic-location-form" class="tl-form-grid" [formGroup]="form" (ngSubmit)="save()">
        @if (saveFailed()) {
          <div class="tl-form-error" role="alert">
            {{ saveErrorMessage() || ('clinicAdmin.common.saveFailed' | t) }}
            @if (saveRequestId(); as requestId) {
              <span class="tl-muted"> ({{ requestId }})</span>
            }
          </div>
        }

        <div class="tl-field tl-field--full">
          <span class="tl-label">{{ 'clinicAdmin.clinics.brand' | t }}</span>
          <label
            class="tl-file-drop"
            (dragover)="$event.preventDefault()"
            (drop)="onDrop($event)"
          >
            @if (form.controls.brandImageUrl.value) {
              <img class="tl-file-preview" [src]="form.controls.brandImageUrl.value" alt="" />
            } @else {
              <svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" aria-hidden="true">
                <rect x="3" y="4" width="18" height="16" rx="2" />
                <circle cx="8.5" cy="9.5" r="1.5" />
                <path d="m21 15-5-5L5 20" />
              </svg>
              <span><strong>{{ 'clinicAdmin.common.browse' | t }}</strong> {{ 'clinicAdmin.common.orDrop' | t }}</span>
              <span class="tl-hint">{{ 'clinicAdmin.clinics.brandHint' | t }}</span>
            }
            <input type="file" accept="image/*" (change)="onFile($event)" />
          </label>
          @if (form.controls.brandImageUrl.value) {
            <button type="button" class="tl-btn tl-btn-gray" (click)="removeImage()">
              {{ 'clinicAdmin.common.removeImage' | t }}
            </button>
          }
        </div>

        <div class="tl-field">
          <label class="tl-label" for="clinic-name">
            {{ 'clinicAdmin.clinics.name' | t }} <span class="required">*</span>
          </label>
          <input
            id="clinic-name"
            class="tl-input"
            formControlName="name"
            [class.tl-input--invalid]="form.controls.name.touched && form.controls.name.invalid"
            autocomplete="organization"
          />
          @if (form.controls.name.touched && form.controls.name.invalid) {
            <span class="tl-error">{{ 'clinicAdmin.common.required' | t }}</span>
          }
        </div>

        <div class="tl-field">
          <label class="tl-label" for="clinic-contact">{{ 'clinicAdmin.clinics.contact' | t }}</label>
          <input id="clinic-contact" class="tl-input" formControlName="contactNumber" inputmode="tel" />
        </div>

        <div class="tl-field">
          <label class="tl-label" for="clinic-slot">{{ 'clinicAdmin.clinics.slot' | t }}</label>
          <input id="clinic-slot" class="tl-input" type="number" min="5" max="480" formControlName="slotMinutes" />
          @if (form.controls.slotMinutes.touched && form.controls.slotMinutes.invalid) {
            <span class="tl-error">{{ 'clinicAdmin.clinics.slotHint' | t }}</span>
          }
        </div>

        @if (!editing()) {
          <div class="tl-field">
            <label class="tl-label" for="clinic-rooms-count">Initial Treatment Rooms Count</label>
            <input
              id="clinic-rooms-count"
              class="tl-input"
              type="number"
              min="1"
              max="50"
              formControlName="initialTreatmentRooms"
            />
            <span class="tl-hint">Auto-seeds Treatment Room 1..N for this branch (can be modified later).</span>
          </div>
        }

        <div class="tl-field">
          <span class="tl-label">{{ 'clinicAdmin.common.status' | t }}</span>
          <div class="tl-switch-row">
            <span class="tl-switch-label">
              {{ (form.controls.isActive.value ? 'clinicAdmin.common.active' : 'clinicAdmin.common.inactive') | t }}
            </span>
            <label class="tl-switch">
              <input type="checkbox" formControlName="isActive" />
              <span class="tl-slider"></span>
            </label>
          </div>
        </div>

          <div class="tl-field">
            <label class="tl-label" for="clinic-officer">{{ 'clinicAdmin.clinics.officer' | t }}</label>
            <select id="clinic-officer" class="tl-select" formControlName="frontDeskOfficerId">
              <option value="">{{ 'clinicAdmin.clinics.unassigned' | t }}</option>
              @for (officer of officers(); track officer.userId) {
                <option [value]="officer.userId">{{ officer.fullName }}</option>
              }
            </select>
          </div>

          <h3 class="tl-drawer-section-title">{{ 'clinicAdmin.common.otherDetail' | t }}</h3>

          <div class="tl-field tl-field--full">
            <label class="tl-label" for="clinic-address">{{ 'clinicAdmin.clinics.address' | t }}</label>
            <input id="clinic-address" class="tl-input" formControlName="addressLine" autocomplete="street-address" />
          </div>

        <div class="tl-field">
          <label class="tl-label" for="clinic-country">{{ 'clinicAdmin.clinics.country' | t }}</label>
          <input id="clinic-country" class="tl-input" formControlName="country" autocomplete="country-name" />
        </div>

        <div class="tl-field">
          <label class="tl-label" for="clinic-state">{{ 'clinicAdmin.clinics.state' | t }}</label>
          <input id="clinic-state" class="tl-input" formControlName="state" autocomplete="address-level1" />
        </div>

        <div class="tl-field">
          <label class="tl-label" for="clinic-city">{{ 'clinicAdmin.clinics.city' | t }}</label>
          <input id="clinic-city" class="tl-input" formControlName="city" autocomplete="address-level2" />
        </div>

        <div class="tl-field">
          <label class="tl-label" for="clinic-postal">{{ 'clinicAdmin.clinics.postal' | t }}</label>
          <input id="clinic-postal" class="tl-input" formControlName="postalCode" autocomplete="postal-code" />
        </div>

        <div class="tl-field tl-field--full">
          <label class="tl-label" for="clinic-description">{{ 'clinicAdmin.clinics.description' | t }}</label>
          <textarea id="clinic-description" class="tl-textarea" formControlName="description"></textarea>
        </div>
      </form>

      <div drawerFooter>
        <button type="button" class="tl-btn tl-btn-gray" (click)="closeDrawer()" [disabled]="saving()">
          {{ 'clinicAdmin.common.cancel' | t }}
        </button>
        <button type="submit" form="clinic-location-form" class="tl-btn tl-btn-teal" [disabled]="saving()">
          {{ (saving() ? 'clinicAdmin.common.saving' : 'clinicAdmin.common.save') | t }}
        </button>
      </div>
    </fe-tl-drawer>

    @if (clinicToDelete(); as target) {
      <div class="fe-modal-overlay" (click)="cancelDelete()">
        <div class="fe-modal-card delete-modal-card" (click)="$event.stopPropagation()" role="dialog" aria-modal="true">
          <div class="delete-modal-header">
            <div class="delete-modal-icon-badge" [class.step2]="deleteStep() === 2">
              <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
                <path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/>
                <line x1="12" y1="9" x2="12" y2="13"/>
                <line x1="12" y1="17" x2="12.01" y2="17"/>
              </svg>
            </div>
            <div class="delete-modal-header-text">
              <h3 class="delete-modal-title">
                {{ 'clinicAdmin.clinics.deleteTitle' | t }}: {{ target.name }}
              </h3>
              <div class="delete-steps-indicator">
                <span class="step-pill" [class.active]="deleteStep() === 1" [class.done]="deleteStep() === 2">1</span>
                <span class="step-connector" [class.active]="deleteStep() === 2"></span>
                <span class="step-pill" [class.active]="deleteStep() === 2">2</span>
              </div>
            </div>
            <button type="button" class="close-btn" (click)="cancelDelete()" [disabled]="deleting()" aria-label="Close">&times;</button>
          </div>

          <div class="delete-modal-body">
            @if (deleteStep() === 1) {
              <!-- Warning Step 1 -->
              <div class="warning-alert warning-alert--level1">
                <div class="warning-alert-badge">
                  <span class="warning-num">1</span>
                </div>
                <div>
                  <h4 class="warning-alert-title">{{ 'clinicAdmin.clinics.deleteWarning1Header' | t }}</h4>
                  <p class="warning-alert-desc">{{ 'clinicAdmin.clinics.deleteWarning1Body' | t }}</p>
                </div>
              </div>

              <div class="impact-summary-box">
                <div class="impact-item">
                  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <circle cx="12" cy="12" r="10" />
                    <line x1="12" y1="8" x2="12" y2="12" />
                    <line x1="12" y1="16" x2="12.01" y2="16" />
                  </svg>
                  <span><strong>{{ target.name }}</strong> ({{ locationLine(target) }})</span>
                </div>
                <div class="impact-note">
                  <ul>
                    <li>فك ارتباط الأطباء المنتسبين لهذا الفرع (Detaching associated doctors)</li>
                    <li>فك ارتباط المواعيد المجدولة على هذا الفرع (Detaching scheduled appointments)</li>
                    <li>إلغاء ارتباط الإجراءات المرتبطة بهذا الفرع (Detaching procedures)</li>
                  </ul>
                </div>
              </div>

              @if (deleteError()) {
                <div class="delete-error-message">{{ deleteError() }}</div>
              }

              <div class="delete-modal-footer">
                <button type="button" class="tl-btn tl-btn-gray" (click)="cancelDelete()">
                  {{ 'clinicAdmin.common.cancel' | t }}
                </button>
                <button type="button" class="tl-btn tl-btn-warning" (click)="deleteStep.set(2)">
                  {{ 'clinicAdmin.clinics.deleteWarning1Proceed' | t }} &rarr;
                </button>
              </div>
            } @else {
              <!-- Warning Step 2 -->
              <div class="warning-alert warning-alert--level2">
                <div class="warning-alert-badge danger">
                  <span class="warning-num">2</span>
                </div>
                <div>
                  <h4 class="warning-alert-title danger-text">{{ 'clinicAdmin.clinics.deleteWarning2Header' | t }}</h4>
                  <p class="warning-alert-desc">{{ 'clinicAdmin.clinics.deleteWarning2Body' | t }}</p>
                </div>
              </div>

              <div class="type-confirmation-box">
                <label class="confirm-input-label" for="confirm-clinic-name">
                  <span>اسم العيادة المطلوب كتابته:</span>
                  <code class="confirm-target-name">{{ target.name }}</code>
                </label>
                <input
                  id="confirm-clinic-name"
                  type="text"
                  class="tl-input confirm-input"
                  [ngModel]="deleteConfirmationInput()"
                  (ngModelChange)="deleteConfirmationInput.set($event)"
                  [placeholder]="'clinicAdmin.clinics.deleteConfirmationPlaceholder' | t"
                  [disabled]="deleting()"
                  autocomplete="off"
                />
              </div>

              <label class="ack-checkbox-row">
                <input
                  type="checkbox"
                  class="tl-table-check"
                  [ngModel]="deleteAcknowledged()"
                  (ngModelChange)="deleteAcknowledged.set($event)"
                  [disabled]="deleting()"
                />
                <span class="ack-text">{{ 'clinicAdmin.clinics.deleteAckLabel' | t }}</span>
              </label>

              @if (deleteError()) {
                <div class="delete-error-message">{{ deleteError() }}</div>
              }

              <div class="delete-modal-footer">
                <button type="button" class="tl-btn tl-btn-gray" (click)="deleteStep.set(1)" [disabled]="deleting()">
                  &larr; {{ 'clinicAdmin.common.previous' | t }}
                </button>
                <button
                  type="button"
                  class="tl-btn tl-btn-danger"
                  (click)="executeDeleteClinic(target)"
                  [disabled]="!canConfirmFinalDelete(target) || deleting()"
                >
                  @if (deleting()) {
                    <svg class="spinner" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                      <path d="M21 12a9 9 0 1 1-6.219-8.56" />
                    </svg>
                    {{ 'clinicAdmin.common.deleting' | t }}
                  } @else {
                    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                      <path d="M3 6h18M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2" />
                    </svg>
                    {{ 'clinicAdmin.clinics.deleteButtonConfirm' | t }}
                  }
                </button>
              </div>
            }
          </div>
        </div>
      </div>
    }

    @if (roomModalOpen()) {
      <div class="fe-modal-overlay" (click)="roomModalOpen.set(false)">
        <div class="fe-modal-card" (click)="$event.stopPropagation()" role="dialog" aria-modal="true" style="max-width:520px;width:100%;">
          <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:0.85rem;">
            <h3 style="margin:0;font-size:1.05rem;">
              {{ editingRoom() ? 'Edit Treatment Room' : 'Add Treatment Room' }}
            </h3>
            <button type="button" class="tl-btn tl-btn-gray" (click)="roomModalOpen.set(false)">✕</button>
          </div>
          <div class="tl-form-grid">
            <div class="tl-field">
              <label class="tl-label">Room Name *</label>
              <input class="tl-input" [(ngModel)]="roomFormName" placeholder="e.g., Treatment Room 1 / Surgery Suite A" />
            </div>
            <div class="tl-field">
              <label class="tl-label">Room Code</label>
              <input class="tl-input" [(ngModel)]="roomFormCode" placeholder="TR-01" />
            </div>
            <div class="tl-field">
              <label class="tl-label">Branch *</label>
              <select class="tl-select--field" [(ngModel)]="roomFormClinicId">
                @for (loc of rows(); track loc.id) {
                  <option [value]="loc.id">{{ loc.name }}</option>
                }
              </select>
            </div>
            <div class="tl-field">
              <label class="tl-label">Floor / Wing</label>
              <input class="tl-input" [(ngModel)]="roomFormFloor" placeholder="1st Floor / West Wing" />
            </div>
            <div class="tl-field tl-field--full">
              <label style="display:inline-flex;align-items:center;gap:0.45rem;font-size:0.84rem;font-weight:600;">
                <input type="checkbox" [(ngModel)]="roomFormActive" />
                Room Active for Booking
              </label>
            </div>
          </div>
          <div style="display:flex;justify-content:flex-end;gap:0.5rem;margin-top:1rem;">
            <button type="button" class="tl-btn tl-btn-gray" (click)="roomModalOpen.set(false)">Cancel</button>
            <button type="button" class="tl-btn tl-btn-teal" (click)="saveRoom()" [disabled]="roomSaving()">
              {{ roomSaving() ? 'Saving...' : 'Save Room' }}
            </button>
          </div>
        </div>
      </div>
    }

    @if (toast(); as notice) {
      <div class="tl-toast" [class.tl-toast--error]="notice.error" role="status">
        {{ notice.message }}
      </div>
    }
  `,
  styles: [`
    .tl-tabs-nav {
      display: flex;
      gap: 0.4rem;
      border-bottom: 1px solid var(--line);
      padding-bottom: 0.35rem;
      margin-bottom: 0.85rem;
    }
    .tl-tab-btn {
      padding: 0.45rem 0.9rem;
      border-radius: 6px;
      border: 1px solid transparent;
      background: transparent;
      color: var(--mute);
      font-size: 0.84rem;
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
      padding: 0.16rem 0.5rem;
      border-radius: 999px;
      font-size: 0.72rem;
      font-weight: 700;
    }
    .tl-pill--ok { background: #e8f5e9; color: #2e7d32; }
    .tl-pill--warn { background: #fff3e0; color: #e65100; }
    .tl-pill--muted { background: #eceff1; color: #546e7a; }
    .tl-action-icon.delete {
      color: var(--mute, #94a3b8);
      transition: color 0.15s ease, background 0.15s ease;
    }
    .tl-action-icon.delete:hover:not(:disabled) {
      color: #ef4444;
      background: rgba(239, 68, 68, 0.12);
    }
    .fe-modal-overlay {
      position: fixed;
      inset: 0;
      background: rgba(15, 23, 42, 0.75);
      backdrop-filter: blur(4px);
      display: grid;
      place-items: center;
      z-index: 1100;
      padding: var(--fe-space-4, 1rem);
    }
    .delete-modal-card {
      background: var(--surf, #1e293b);
      border-radius: 12px;
      border: 1px solid var(--line, #334155);
      box-shadow: 0 20px 25px -5px rgba(0, 0, 0, 0.5), 0 8px 10px -6px rgba(0, 0, 0, 0.5);
      width: 100%;
      max-width: 32rem;
      overflow: hidden;
      display: flex;
      flex-direction: column;
    }
    .delete-modal-header {
      display: flex;
      align-items: center;
      gap: 12px;
      padding: 16px 20px;
      border-bottom: 1px solid var(--line, #334155);
      background: rgba(255, 255, 255, 0.02);
    }
    .delete-modal-icon-badge {
      width: 40px;
      height: 40px;
      border-radius: 10px;
      display: flex;
      align-items: center;
      justify-content: center;
      background: rgba(245, 158, 11, 0.15);
      color: #f59e0b;
      flex-shrink: 0;
      transition: background 0.2s, color 0.2s;
    }
    .delete-modal-icon-badge.step2 {
      background: rgba(239, 68, 68, 0.18);
      color: #ef4444;
    }
    .delete-modal-header-text {
      flex: 1;
      min-width: 0;
    }
    .delete-modal-title {
      font-size: 1.05rem;
      font-weight: 700;
      color: var(--ink, #f8fafc);
      margin: 0;
      line-height: 1.3;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }
    .delete-steps-indicator {
      display: flex;
      align-items: center;
      gap: 6px;
      margin-top: 4px;
    }
    .step-pill {
      font-size: 11px;
      font-weight: 700;
      width: 18px;
      height: 18px;
      border-radius: 50%;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      background: var(--line, #334155);
      color: var(--mute, #94a3b8);
    }
    .step-pill.active {
      background: #f59e0b;
      color: #000;
    }
    .delete-modal-icon-badge.step2 ~ .delete-modal-header-text .step-pill.active {
      background: #ef4444;
      color: #fff;
    }
    .step-pill.done {
      background: #10b981;
      color: #fff;
    }
    .step-connector {
      width: 16px;
      height: 2px;
      background: var(--line, #334155);
    }
    .step-connector.active {
      background: #ef4444;
    }
    .close-btn {
      margin-inline-start: auto;
      background: transparent;
      border: none;
      font-size: 24px;
      color: var(--mute, #94a3b8);
      cursor: pointer;
      line-height: 1;
      padding: 0 4px;
    }
    .close-btn:hover {
      color: var(--ink, #f8fafc);
    }
    .delete-modal-body {
      padding: 20px;
      display: flex;
      flex-direction: column;
      gap: 16px;
    }
    .warning-alert {
      border-radius: 8px;
      padding: 14px;
      display: flex;
      gap: 12px;
      background: rgba(245, 158, 11, 0.08);
      border: 1px solid rgba(245, 158, 11, 0.3);
    }
    .warning-alert--level2 {
      background: rgba(239, 68, 68, 0.1);
      border-color: rgba(239, 68, 68, 0.35);
    }
    .warning-alert-badge {
      width: 26px;
      height: 26px;
      border-radius: 50%;
      background: #f59e0b;
      color: #000;
      font-weight: 800;
      font-size: 13px;
      display: flex;
      align-items: center;
      justify-content: center;
      flex-shrink: 0;
    }
    .warning-alert-badge.danger {
      background: #ef4444;
      color: #fff;
    }
    .warning-alert-title {
      font-size: 0.95rem;
      font-weight: 700;
      color: #f59e0b;
      margin: 0 0 6px 0;
    }
    .warning-alert-title.danger-text {
      color: #f87171;
    }
    .warning-alert-desc {
      font-size: 0.85rem;
      line-height: 1.5;
      color: var(--ink, #f1f5f9);
      margin: 0;
    }
    .impact-summary-box {
      background: rgba(0, 0, 0, 0.2);
      border: 1px solid var(--line, #334155);
      border-radius: 8px;
      padding: 12px 14px;
      font-size: 0.85rem;
    }
    .impact-item {
      display: flex;
      align-items: center;
      gap: 8px;
      color: var(--ink, #f8fafc);
      margin-bottom: 8px;
    }
    .impact-note ul {
      margin: 0;
      padding-inline-start: 18px;
      color: var(--mute, #94a3b8);
      line-height: 1.6;
    }
    .type-confirmation-box {
      display: flex;
      flex-direction: column;
      gap: 8px;
    }
    .confirm-input-label {
      font-size: 0.85rem;
      color: var(--ink, #f1f5f9);
      display: flex;
      align-items: center;
      justify-content: space-between;
      flex-wrap: wrap;
      gap: 6px;
    }
    .confirm-target-name {
      background: rgba(239, 68, 68, 0.15);
      color: #fca5a5;
      padding: 2px 8px;
      border-radius: 4px;
      font-weight: 700;
      border: 1px dashed rgba(239, 68, 68, 0.4);
      user-select: all;
    }
    .confirm-input {
      font-size: 0.95rem;
      border-color: rgba(239, 68, 68, 0.4);
    }
    .confirm-input:focus {
      border-color: #ef4444;
      box-shadow: 0 0 0 2px rgba(239, 68, 68, 0.25);
    }
    .ack-checkbox-row {
      display: flex;
      align-items: flex-start;
      gap: 10px;
      cursor: pointer;
      user-select: none;
      font-size: 0.85rem;
      color: var(--ink, #f1f5f9);
      line-height: 1.4;
    }
    .delete-error-message {
      background: rgba(239, 68, 68, 0.15);
      border: 1px solid rgba(239, 68, 68, 0.4);
      color: #fca5a5;
      padding: 10px 12px;
      border-radius: 6px;
      font-size: 0.85rem;
    }
    .delete-modal-footer {
      display: flex;
      justify-content: flex-end;
      gap: 10px;
      margin-top: 8px;
      padding-top: 14px;
      border-top: 1px solid var(--line, #334155);
    }
    .tl-btn-warning {
      background: #f59e0b;
      color: #000;
      font-weight: 600;
      border: none;
      padding: 0 16px;
      height: 38px;
      border-radius: 6px;
      cursor: pointer;
    }
    .tl-btn-warning:hover {
      background: #d97706;
      color: #000;
    }
    .tl-btn-danger {
      background: #dc2626;
      color: #fff;
      font-weight: 600;
      border: none;
      display: inline-flex;
      align-items: center;
      gap: 6px;
      padding: 0 16px;
      height: 38px;
      border-radius: 6px;
      cursor: pointer;
    }
    .tl-btn-danger:hover:not(:disabled) {
      background: #b91c1c;
    }
    .tl-btn-danger:disabled {
      opacity: 0.45;
      cursor: not-allowed;
    }
    .spinner {
      animation: spin 1s linear infinite;
    }
    @keyframes spin {
      from { transform: rotate(0deg); }
      to { transform: rotate(360deg); }
    }
  `],
})
export class ClinicLocationsComponent implements OnInit {
  private readonly api = inject(ClinicApi);
  private readonly patientsApi = inject(ClinicPatientApi);
  private readonly sessions = inject(SessionService);
  private readonly clinicRbac = inject(ClinicRbacService);
  private readonly projects = inject(ProjectContextService);
  private readonly i18n = inject(I18nService);
  private readonly fb = inject(FormBuilder);
  private readonly destroyRef = inject(DestroyRef);
  private readonly cdr = inject(ChangeDetectorRef);

  protected readonly activeTab = signal<LocationsPageTab>('branches');
  protected readonly treatmentRooms = signal<readonly TreatmentRoomRow[]>([]);
  protected readonly roomsLoading = signal(false);
  protected readonly roomSaving = signal(false);
  protected readonly roomClinicFilter = signal('');
  protected readonly roomModalOpen = signal(false);
  protected readonly editingRoom = signal<TreatmentRoomRow | null>(null);
  protected roomFormName = '';
  protected roomFormCode = '';
  protected roomFormClinicId = '';
  protected roomFormFloor = '';
  protected roomFormActive = true;

  protected readonly isPlatformAdmin = computed(() => {
    const roles = this.clinicRbac.activeRoles();
    return (roles.includes('platform_admin') || this.sessions.session()?.role === 'PLATFORM') && !roles.includes('clinic_admin');
  });

  protected readonly canManage = computed(() => this.sessions.can()(PERMISSIONS.clinicManage));

  protected readonly rows = signal<readonly ClinicLocation[]>([]);
  protected readonly officers = signal<readonly ClinicFrontDeskOfficer[]>([]);
  protected readonly loading = signal(false);
  protected readonly loadFailed = signal(false);
  protected readonly saving = signal(false);
  protected readonly saveFailed = signal(false);
  protected readonly saveRequestId = signal<string | null>(null);
  protected readonly saveErrorMessage = signal<string | null>(null);
  protected readonly drawerOpen = signal(false);
  protected readonly editing = signal<ClinicLocation | null>(null);
  protected readonly toast = signal<Toast | null>(null);
  protected readonly searchTerm = signal('');
  protected readonly statusFilter = signal<StatusFilter>('');
  protected readonly bulkAction = signal<BulkAction>('');
  protected readonly selectedIds = signal<ReadonlySet<string>>(new Set());
  protected readonly clinicToDelete = signal<ClinicLocation | null>(null);
  protected readonly deleteStep = signal<1 | 2>(1);
  protected readonly deleteConfirmationInput = signal('');
  protected readonly deleteAcknowledged = signal(false);
  protected readonly deleting = signal(false);
  protected readonly deleteError = signal<string | null>(null);
  protected readonly page = signal(1);
  protected readonly pageSize = signal(10);

  protected readonly form = this.fb.group({
    name: ['', [Validators.required, Validators.maxLength(160)]],
    contactNumber: [''],
    description: [''],
    addressLine: [''],
    country: [''],
    state: [''],
    city: [''],
    postalCode: [''],
    slotMinutes: [30, [Validators.min(5), Validators.max(480)]],
    initialTreatmentRooms: [1, [Validators.min(1), Validators.max(50)]],
    brandImageUrl: [''],
    frontDeskOfficerId: [''],
    isActive: [true],
  });

  protected readonly pageCount = computed(() =>
    Math.max(1, Math.ceil(this.rows().length / this.pageSize())),
  );

  protected readonly pagedRows = computed(() => {
    const start = (this.page() - 1) * this.pageSize();
    return this.rows().slice(start, start + this.pageSize());
  });

  protected readonly pageNumbers = computed(() => {
    const count = this.pageCount();
    const current = this.page();
    const first = Math.max(1, Math.min(current - 2, count - 4));
    return Array.from({ length: Math.min(5, count) }, (_, index) => first + index);
  });

  protected readonly allPageSelected = computed(
    () =>
      this.pagedRows().length > 0 &&
      this.pagedRows().every((row) => row.id !== undefined && this.selectedIds().has(row.id)),
  );

  private loadToken = 0;
  private searchTimer: ReturnType<typeof setTimeout> | null = null;
  private toastTimer: ReturnType<typeof setTimeout> | null = null;

  constructor() {
    this.i18n.register({ clinicAdmin: clinicAdminStrings as Namespace });
    this.destroyRef.onDestroy(() => {
      if (this.searchTimer) clearTimeout(this.searchTimer);
      if (this.toastTimer) clearTimeout(this.toastTimer);
    });
  }

  ngOnInit(): void {
    void this.load();
    void this.loadOfficers();
  }

  protected async load(): Promise<void> {
    const token = ++this.loadToken;
    this.loading.set(true);
    this.loadFailed.set(false);
    try {
      const response = await this.api.locations({
        projectId: this.projectId(),
        q: this.searchTerm() || undefined,
        status: this.statusFilter() || undefined,
      });
      if (token !== this.loadToken) return;
      this.rows.set(response.items ?? []);
      this.page.set(1);
      this.selectedIds.set(new Set());
    } catch {
      if (token !== this.loadToken) return;
      this.loadFailed.set(true);
      this.rows.set([]);
    } finally {
      if (token === this.loadToken) this.loading.set(false);
    }
  }

  private async loadOfficers(): Promise<void> {
    try {
      const response = await this.api.frontDeskOfficers({ projectId: this.projectId() });
      this.officers.set(response.items ?? []);
    } catch {
      // The route is readable with `doctor:read`, but the account directory is not.
      // Leave the select empty for a reader who cannot see officers; the clinic
      // form still works and the server remains the boundary.
    }
  }

  protected onSearch(value: string): void {
    this.searchTerm.set(value);
    if (this.searchTimer) clearTimeout(this.searchTimer);
    this.searchTimer = setTimeout(() => void this.load(), 300);
  }

  protected onStatusChange(value: string): void {
    this.statusFilter.set(value === 'ACTIVE' || value === 'INACTIVE' ? value : '');
    void this.load();
  }

  protected onBulkAction(value: string): void {
    this.bulkAction.set(value === 'ACTIVE' || value === 'INACTIVE' ? value : '');
  }

  protected openCreate(): void {
    if (!this.canManage()) return;
    this.editing.set(null);
    this.saveFailed.set(false);
    this.saveRequestId.set(null);
    this.saveErrorMessage.set(null);
    this.form.reset({
      name: '',
      contactNumber: '',
      description: '',
      addressLine: '',
      country: '',
      state: '',
      city: '',
      postalCode: '',
      slotMinutes: 30,
      initialTreatmentRooms: 1,
      brandImageUrl: '',
      frontDeskOfficerId: '',
      isActive: true,
    });
    this.drawerOpen.set(true);
  }

  protected openEdit(row: ClinicLocation): void {
    if (!this.canManage()) return;
    this.editing.set(row);
    this.saveFailed.set(false);
    this.saveRequestId.set(null);
    this.saveErrorMessage.set(null);
    this.form.reset({
      name: row.name ?? '',
      contactNumber: row.contactNumber ?? '',
      description: row.description ?? '',
      addressLine: row.addressLine ?? '',
      country: row.country ?? '',
      state: row.state ?? '',
      city: row.city ?? '',
      postalCode: row.postalCode ?? '',
      slotMinutes: row.slotMinutes ?? 30,
      brandImageUrl: row.brandImageUrl ?? '',
      frontDeskOfficerId: row.frontDeskOfficerId ?? '',
      isActive: row.isActive !== false,
    });
    this.drawerOpen.set(true);
  }

  protected closeDrawer(): void {
    if (this.saving()) return;
    this.drawerOpen.set(false);
  }

  protected startDeleteClinic(row: ClinicLocation): void {
    this.clinicToDelete.set(row);
    this.deleteStep.set(1);
    this.deleteConfirmationInput.set('');
    this.deleteAcknowledged.set(false);
    this.deleteError.set(null);
  }

  protected cancelDelete(): void {
    if (this.deleting()) return;
    this.clinicToDelete.set(null);
    this.deleteStep.set(1);
    this.deleteConfirmationInput.set('');
    this.deleteAcknowledged.set(false);
    this.deleteError.set(null);
  }

  protected canConfirmFinalDelete(target: ClinicLocation): boolean {
    const inputMatches =
      (this.deleteConfirmationInput() || '').trim().toLowerCase() ===
      (target.name || '').trim().toLowerCase();
    return inputMatches && this.deleteAcknowledged();
  }

  protected async executeDeleteClinic(target: ClinicLocation): Promise<void> {
    if (!this.canManage() || !target.id || !this.canConfirmFinalDelete(target)) {
      return;
    }
    this.deleting.set(true);
    this.deleteError.set(null);
    try {
      const projId = target.projectId || this.projectId();
      await this.api.deleteLocation(target.id, projId);
      this.cancelDelete();
      await this.load();
      this.showToast('clinicAdmin.clinics.deleteSuccess', false);
    } catch (cause) {
      if (cause instanceof ApiError) {
        this.deleteError.set(cause.message || this.i18n.t('clinicAdmin.clinics.deleteFailed'));
      } else if (cause instanceof Error) {
        this.deleteError.set(cause.message);
      } else {
        this.deleteError.set(this.i18n.t('clinicAdmin.clinics.deleteFailed'));
      }
    } finally {
      this.deleting.set(false);
    }
  }

  protected async save(): Promise<void> {
    if (!this.canManage()) {
      this.showToast('clinicAdmin.common.permissionDenied', true);
      return;
    }
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.saving.set(true);
    this.saveFailed.set(false);
    this.saveRequestId.set(null);
    this.saveErrorMessage.set(null);
    const payload = this.toPayload();
    try {
      const current = this.editing();
      const projId = current?.projectId || this.projectId();
      if (current?.id) {
        await this.api.updateLocation(current.id, payload, projId);
      } else {
        await this.api.createLocation(payload, projId);
      }
      this.drawerOpen.set(false);
      await this.load();
      if (this.activeTab() === 'rooms' || !current?.id) {
        void this.loadRooms();
      }
      this.showToast('clinicAdmin.common.updated', false);
    } catch (cause) {
      this.saveFailed.set(true);
      if (cause instanceof ApiError) {
        this.saveRequestId.set(cause.requestId ?? null);
        this.saveErrorMessage.set(cause.message ?? null);
      } else if (cause instanceof Error) {
        this.saveRequestId.set(null);
        this.saveErrorMessage.set(cause.message);
      } else {
        this.saveRequestId.set(null);
        this.saveErrorMessage.set(null);
      }
    } finally {
      this.saving.set(false);
    }
  }

  protected async toggleActive(
    row: ClinicLocation,
    next: boolean,
    input: HTMLInputElement,
  ): Promise<void> {
    if (!this.canManage() || !row.id) {
      input.checked = row.isActive === true;
      return;
    }
    input.disabled = true;
    try {
      const projId = row.projectId || this.projectId();
      await this.api.updateLocation(row.id, { isActive: next }, projId);
      this.rows.update((rows) =>
        rows.map((candidate) =>
          candidate.id === row.id ? { ...candidate, isActive: next } : candidate,
        ),
      );
      this.showToast('clinicAdmin.common.updated', false);
    } catch {
      input.checked = row.isActive === true;
      this.showToast('clinicAdmin.common.saveFailed', true);
    } finally {
      input.disabled = false;
    }
  }

  protected async applyBulk(): Promise<void> {
    const action = this.bulkAction();
    const selected = this.selectedIds();
    if (!this.canManage() || action === '' || selected.size === 0) return;
    const targets = this.rows().filter((row) => row.id !== undefined && selected.has(row.id));
    try {
      await Promise.all(
        targets.map((row) =>
          this.api.updateLocation(row.id!, { isActive: action === 'ACTIVE' }, row.projectId || this.projectId()),
        ),
      );
      await this.load();
      this.bulkAction.set('');
      this.showToast('clinicAdmin.common.updated', false);
    } catch {
      this.showToast('clinicAdmin.common.saveFailed', true);
    }
  }

  protected toggleSelection(id: string | undefined, checked: boolean): void {
    if (!id) return;
    const next = new Set(this.selectedIds());
    if (checked) next.add(id);
    else next.delete(id);
    this.selectedIds.set(next);
  }

  protected toggleAll(checked: boolean): void {
    const next = new Set(this.selectedIds());
    for (const row of this.pagedRows()) {
      if (!row.id) continue;
      if (checked) next.add(row.id);
      else next.delete(row.id);
    }
    this.selectedIds.set(next);
  }

  protected isSelected(id: string | undefined): boolean {
    return id !== undefined && this.selectedIds().has(id);
  }

  protected goToPage(number: number): void {
    if (number < 1 || number > this.pageCount()) return;
    this.page.set(number);
  }

  protected showingFrom(): number {
    return this.rows().length === 0 ? 0 : (this.page() - 1) * this.pageSize() + 1;
  }

  protected showingTo(): number {
    return Math.min(this.page() * this.pageSize(), this.rows().length);
  }

  protected locationLine(row: ClinicLocation): string {
    const parts = [row.city, row.state, row.addressLine].filter(
      (part): part is string => typeof part === 'string' && part.trim() !== '',
    );
    return parts.length > 0 ? parts.join(' · ') : '—';
  }

  protected onFile(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (file) this.readImage(file);
    input.value = '';
  }

  protected onDrop(event: DragEvent): void {
    event.preventDefault();
    const file = event.dataTransfer?.files?.[0];
    if (file) this.readImage(file);
  }

  protected removeImage(): void {
    this.form.controls.brandImageUrl.setValue('');
  }

  protected exportCsv(): void {
    const header = ['Name', 'Front Desk Officer', 'Contact Number', 'Description', 'Status'];
    const lines = [header, ...this.rows().map((row) => [
      row.name ?? '',
      row.frontDeskOfficerName ?? '',
      row.contactNumber ?? '',
      row.description ?? '',
      row.isActive ? 'ACTIVE' : 'INACTIVE',
    ])];
    const csv = lines.map((line) => line.map(csvCell).join(',')).join('\r\n');
    const blob = new Blob([`\ufeff${csv}`], { type: 'text/csv;charset=utf-8;' });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = 'clinics.csv';
    anchor.click();
    URL.revokeObjectURL(url);
  }

  private readImage(file: File): void {
    if (!file.type.startsWith('image/')) {
      this.showToast('clinicAdmin.common.imageType', true);
      return;
    }
    if (file.size > 1_000_000) {
      this.showToast('clinicAdmin.common.imageTooLarge', true);
      return;
    }
    const reader = new FileReader();
    reader.onload = () => {
      if (typeof reader.result === 'string') {
        this.form.controls.brandImageUrl.setValue(reader.result);
        this.cdr.markForCheck();
      }
    };
    reader.readAsDataURL(file);
  }

  private toPayload(): ClinicLocationInput {
    const value = this.form.getRawValue();
    const officerId = blankToNull(value.frontDeskOfficerId);
    return {
      name: (value.name ?? '').trim(),
      contactNumber: blankToNull(value.contactNumber),
      description: blankToNull(value.description),
      addressLine: blankToNull(value.addressLine),
      country: blankToNull(value.country),
      state: blankToNull(value.state),
      city: blankToNull(value.city),
      postalCode: blankToNull(value.postalCode),
      slotMinutes: value.slotMinutes ?? 30,
      initialTreatmentRooms: this.editing() ? undefined : Math.max(1, Number(value.initialTreatmentRooms) || 1),
      brandImageUrl: blankToNull(value.brandImageUrl),
      isActive: value.isActive ?? true,
      frontDeskOfficerId: officerId,
      // The API treats a null UUID as "leave it alone" so a status-only PATCH
      // cannot erase the assignment. Clearing from the drawer therefore has to
      // be explicit; only send it when the row actually had an officer.
      clearFrontDeskOfficer:
        this.editing()?.frontDeskOfficerId != null && officerId === null,
    };
  }

  protected switchTab(tab: LocationsPageTab): void {
    this.activeTab.set(tab);
    if (tab === 'rooms') {
      void this.loadRooms();
    }
  }

  protected async loadRooms(): Promise<void> {
    this.roomsLoading.set(true);
    try {
      const list = await this.patientsApi.treatmentRooms({
        projectId: this.projectId(),
        clinicId: this.roomClinicFilter() || undefined,
      });
      this.treatmentRooms.set(list ?? []);
    } catch {
      this.showRawToast('Failed to load treatment rooms.', true);
    } finally {
      this.roomsLoading.set(false);
    }
  }

  protected openCreateRoom(): void {
    if (!this.canManage()) return;
    this.editingRoom.set(null);
    this.roomFormName = `Treatment Room ${this.treatmentRooms().length + 1}`;
    this.roomFormCode = `TR-0${this.treatmentRooms().length + 1}`;
    this.roomFormClinicId = this.roomClinicFilter() || this.rows()[0]?.id || '';
    this.roomFormFloor = '';
    this.roomFormActive = true;
    this.roomModalOpen.set(true);
  }

  protected openEditRoom(room: TreatmentRoomRow): void {
    if (!this.canManage()) return;
    this.editingRoom.set(room);
    this.roomFormName = room.name;
    this.roomFormCode = room.roomNumber ?? '';
    this.roomFormClinicId = room.clinicId ?? '';
    this.roomFormFloor = room.roomType ?? 'GENERAL';
    this.roomFormActive = room.isActive;
    this.roomModalOpen.set(true);
  }

  protected async saveRoom(): Promise<void> {
    const name = this.roomFormName.trim();
    if (!name || !this.roomFormClinicId) {
      this.showRawToast('Room name and branch are required.', true);
      return;
    }
    this.roomSaving.set(true);
    try {
      const target = this.editingRoom();
      if (target) {
        await this.patientsApi.updateTreatmentRoom(
          target.id,
          {
            name,
            roomNumber: this.roomFormCode.trim() || undefined,
            roomType: this.roomFormFloor.trim() || 'GENERAL',
            isActive: this.roomFormActive,
          },
          { projectId: this.projectId() },
        );
        this.showRawToast('Treatment room updated.', false);
      } else {
        await this.patientsApi.createTreatmentRoom(
          {
            clinicId: this.roomFormClinicId,
            name,
            roomNumber: this.roomFormCode.trim() || undefined,
            roomType: this.roomFormFloor.trim() || 'GENERAL',
          },
          { projectId: this.projectId() },
        );
        this.showRawToast('Treatment room created.', false);
      }
      this.roomModalOpen.set(false);
      await this.loadRooms();
    } catch (err) {
      this.showRawToast(err instanceof Error ? err.message : 'Failed to save treatment room.', true);
    } finally {
      this.roomSaving.set(false);
    }
  }

  protected async deleteRoom(room: TreatmentRoomRow): Promise<void> {
    if (!this.canManage()) return;
    try {
      await this.patientsApi.deleteTreatmentRoom(room.id, { projectId: this.projectId() });
      this.showRawToast('Treatment room deleted.', false);
      await this.loadRooms();
    } catch {
      this.showRawToast('Failed to delete treatment room.', true);
    }
  }

  protected projectId(): string | undefined {
    return this.projects.selectedProjectId() ?? this.sessions.session()?.projectIds?.[0] ?? undefined;
  }

  private showToast(messageKey: string, error: boolean): void {
    this.toast.set({ message: this.i18n.t(messageKey), error });
    if (this.toastTimer) clearTimeout(this.toastTimer);
    this.toastTimer = setTimeout(() => this.toast.set(null), 3200);
  }

  private showRawToast(message: string, error: boolean): void {
    this.toast.set({ message, error });
    if (this.toastTimer) clearTimeout(this.toastTimer);
    this.toastTimer = setTimeout(() => this.toast.set(null), 3400);
  }
}

function blankToNull(value: string | null | undefined): string | null {
  if (typeof value !== 'string') return null;
  const trimmed = value.trim();
  return trimmed === '' ? null : trimmed;
}

function csvCell(value: string): string {
  return `"${value.replaceAll('"', '""')}"`;
}
