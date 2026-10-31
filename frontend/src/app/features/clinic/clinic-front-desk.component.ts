/**
 * `/app/clinic/front-desk` — the Front Desk officers screen.
 *
 * A front-desk officer is a real `CLINIC_STAFF` account, not a profile table: the
 * create route provisions the account, its project membership and its one clinic
 * assignment. The password is required on create and optional on edit; a blank
 * value on edit means "keep the current one", so the drawer never round-trips a hash.
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
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ApiError } from '../../core/api/api.service';
import {
  ClinicApi,
  type ClinicFrontDeskInput,
  type ClinicFrontDeskOfficer,
  type ClinicLocation,
} from '../../core/api/endpoints';
import { ClinicRbacService } from '../../core/auth/clinic-rbac';
import { PERMISSIONS } from '../../core/auth/permissions';
import { SessionService } from '../../core/auth/session.service';
import { ProjectContextService } from '../../core/context/project-context.service';
import { I18nService } from '../../core/i18n/i18n.service';
import type { Namespace } from '../../core/i18n/i18n.types';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { TlDrawerComponent } from '../../ui/tl-drawer.component';
import { clinicAdminStrings } from './clinic-admin.strings';

type StatusFilter = '' | 'ACTIVE' | 'INACTIVE';
type Toast = { readonly message: string; readonly error: boolean };

@Component({
  selector: 'fe-clinic-front-desk-page',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, RouterLink, TlDrawerComponent, TranslatePipe],
  template: `
    <div class="tl-page" data-testid="clinic-front-desk">
      <div class="tl-page-top">
        <nav class="tl-breadcrumbs" aria-label="Breadcrumb">
          <a [routerLink]="isPlatformAdmin() ? '/app/admin/dashboard' : '/app/dashboard'">{{ 'clinicAdmin.nav.home' | t }}</a>
          <span aria-hidden="true">&gt;&gt;</span>
          <span class="active">{{ 'clinicAdmin.frontDesk.breadcrumb' | t }}</span>
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

      <div class="tl-toolbar">
        <div class="tl-toolbar-left" style="display: flex; align-items: center; gap: 12px; flex-wrap: wrap;">
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

          @if (quota(); as q) {
            <span
              class="tl-badge"
              style="padding: 6px 12px; font-weight: 600; font-size: 0.8125rem; border-radius: 6px;"
              [style.background]="isQuotaReached() ? 'rgba(239, 68, 68, 0.12)' : 'rgba(13, 148, 136, 0.12)'"
              [style.color]="isQuotaReached() ? '#dc2626' : '#0d9488'"
              [title]="'clinicAdmin.frontDesk.quotaLabel' | t"
            >
              {{ 'clinicAdmin.frontDesk.quotaLabel' | t }}: {{ q.activeUsersCount }} / {{ q.effectiveMaxUsers }}
            </span>
          }
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
              [attr.placeholder]="'clinicAdmin.frontDesk.searchPlaceholder' | t"
              [attr.aria-label]="'clinicAdmin.common.search' | t"
            />
          </div>

          <button
            type="button"
            class="tl-btn tl-btn-teal"
            (click)="openCreate()"
            [disabled]="!canManage() || isQuotaReached()"
            [title]="isQuotaReached() ? ('clinicAdmin.frontDesk.quotaReached' | t) : ''"
          >
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" aria-hidden="true">
              <path d="M12 5v14M5 12h14" />
            </svg>
            {{ 'clinicAdmin.common.new' | t }}
          </button>
        </div>
      </div>

      <div class="tl-table-wrap" data-testid="clinic-front-desk-table">
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
          <div class="tl-table-empty" data-testid="clinic-front-desk-empty">
            <strong>{{ 'clinicAdmin.frontDesk.emptyTitle' | t }}</strong>
            <span>{{ 'clinicAdmin.frontDesk.emptyBody' | t }}</span>
          </div>
        } @else {
          <table class="tl-table">
            <caption class="fe-sr">{{ 'clinicAdmin.frontDesk.title' | t }}</caption>
            <thead>
              <tr>
                <th scope="col">{{ 'clinicAdmin.frontDesk.officer' | t }}</th>
                <th scope="col">{{ 'clinicAdmin.frontDesk.roleColumn' | t }}</th>
                <th scope="col">{{ 'clinicAdmin.frontDesk.email' | t }}</th>
                <th scope="col">{{ 'clinicAdmin.frontDesk.phone' | t }}</th>
                <th scope="col">{{ 'clinicAdmin.frontDesk.clinic' | t }}</th>
                <th scope="col">{{ 'clinicAdmin.frontDesk.verification' | t }}</th>
                <th scope="col">{{ 'clinicAdmin.common.status' | t }}</th>
                <th scope="col">{{ 'clinicAdmin.common.actions' | t }}</th>
              </tr>
            </thead>
            <tbody>
              @for (row of pagedRows(); track row.userId ?? $index) {
                <tr>
                  <td>
                    <div class="tl-cell-profile">
                      <div class="tl-avatar">
                        @if (row.profileImageUrl) {
                          <img [src]="row.profileImageUrl" [alt]="row.fullName ?? ''" />
                        } @else {
                          <span class="tl-avatar-initials">{{ initials(row) }}</span>
                        }
                      </div>
                      <div class="tl-profile-info">
                        <span class="tl-profile-name">{{ row.fullName || '—' }}</span>
                        <span class="tl-profile-sub" dir="ltr">{{ row.email || ('clinicAdmin.frontDesk.role' | t) }}</span>
                      </div>
                    </div>
                  </td>
                  <td>
                    <div style="display: flex; align-items: center; gap: 6px; flex-wrap: wrap;">
                      <span
                        class="tl-badge"
                        style="padding: 4px 10px; border-radius: 6px; font-weight: 600; font-size: 0.75rem;"
                        [style.background]="roleBadgeBg(row.role)"
                        [style.color]="roleBadgeColor(row.role)"
                      >
                        {{ ('clinicAdmin.frontDesk.role.' + (row.role || 'receptionist')) | t }}
                      </span>
                      @if (row.isAccountant && row.role !== 'accountant') {
                        <span
                          class="tl-badge"
                          style="padding: 4px 8px; border-radius: 6px; font-weight: 600; font-size: 0.72rem; background: rgba(217, 119, 6, 0.14); color: #b45309;"
                        >
                          {{ 'clinicAdmin.frontDesk.accountantBadge' | t }}
                        </span>
                      }
                    </div>
                  </td>
                  <td dir="ltr">{{ row.email || '—' }}</td>
                  <td dir="ltr">{{ row.phone || '—' }}</td>
                  <td>{{ row.clinicName || ('clinicAdmin.frontDesk.unassigned' | t) }}</td>
                  <td>
                    <span class="tl-badge tl-badge-verified">
                      {{ ('clinicAdmin.verification.' + (row.verificationStatus || 'VERIFIED')) | t }}
                    </span>
                  </td>
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
                        (click)="remove(row)"
                        [disabled]="!canManage()"
                        [attr.aria-label]="'clinicAdmin.frontDesk.remove' | t"
                      >
                        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
                          <path d="M3 6h18M8 6V4h8v2M19 6l-1 14H6L5 6M10 11v5M14 11v5" />
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
    </div>

    <fe-tl-drawer
      [open]="drawerOpen()"
      [title]="(editing() ? 'clinicAdmin.frontDesk.editTitle' : 'clinicAdmin.frontDesk.createTitle') | t"
      [subtitle]="'clinicAdmin.frontDesk.drawerHint' | t"
      (closed)="closeDrawer()"
    >
      <form id="clinic-front-desk-form" class="tl-form-grid" [formGroup]="form" (ngSubmit)="save()">
        @if (saveFailed()) {
          <div class="tl-form-error" role="alert">
            {{ saveErrorMessage() || ('clinicAdmin.common.saveFailed' | t) }}
            @if (saveRequestId(); as requestId) {
              <span class="tl-muted"> ({{ requestId }})</span>
            }
          </div>
        }

        <div class="tl-drawer-top-split">
          <div class="tl-field">
            <span class="tl-label">{{ 'clinicAdmin.frontDesk.photo' | t }}</span>
            <label class="tl-file-drop" (dragover)="$event.preventDefault()" (drop)="onDrop($event)">
              @if (form.controls.profileImageUrl.value) {
                <img class="tl-file-preview" [src]="form.controls.profileImageUrl.value" alt="" />
              } @else {
                <svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" aria-hidden="true">
                  <circle cx="12" cy="8" r="4" />
                  <path d="M4 21a8 8 0 0 1 16 0" />
                </svg>
                <span><strong>{{ 'clinicAdmin.common.browse' | t }}</strong> {{ 'clinicAdmin.common.orDrop' | t }}</span>
              }
              <input type="file" accept="image/*" (change)="onFile($event)" />
            </label>
            @if (form.controls.profileImageUrl.value) {
              <button type="button" class="tl-btn tl-btn-gray" (click)="clearImage()">
                {{ 'clinicAdmin.common.removeImage' | t }}
              </button>
            }
          </div>

          <div class="tl-drawer-stack">
            <div class="tl-field">
              <label class="tl-label" for="desk-first">
                {{ 'clinicAdmin.frontDesk.firstName' | t }} <span class="required">*</span>
              </label>
              <input id="desk-first" class="tl-input" formControlName="firstName" autocomplete="given-name" />
              @if (form.controls.firstName.touched && form.controls.firstName.invalid) {
                <span class="tl-error">{{ 'clinicAdmin.common.required' | t }}</span>
              }
            </div>

            <div class="tl-field">
              <label class="tl-label" for="desk-last">
                {{ 'clinicAdmin.frontDesk.lastName' | t }} <span class="required">*</span>
              </label>
              <input id="desk-last" class="tl-input" formControlName="lastName" autocomplete="family-name" />
              @if (form.controls.lastName.touched && form.controls.lastName.invalid) {
                <span class="tl-error">{{ 'clinicAdmin.common.required' | t }}</span>
              }
            </div>

            <div class="tl-field">
              <label class="tl-label" for="desk-email">
                {{ 'clinicAdmin.frontDesk.email' | t }} <span class="required">*</span>
              </label>
              <input id="desk-email" class="tl-input" type="email" formControlName="email" autocomplete="email" dir="ltr" />
              @if (form.controls.email.touched && form.controls.email.invalid) {
                <span class="tl-error">{{ 'clinicAdmin.frontDesk.emailHint' | t }}</span>
              }
            </div>
          </div>
        </div>

        <div class="tl-field">
          <label class="tl-label" for="desk-phone">{{ 'clinicAdmin.frontDesk.phone' | t }}</label>
          <input id="desk-phone" class="tl-input" formControlName="phone" inputmode="tel" dir="ltr" />
        </div>

        <div class="tl-field">
          <label class="tl-label" for="desk-gender">{{ 'clinicAdmin.frontDesk.gender' | t }}</label>
          <select id="desk-gender" class="tl-select" formControlName="gender">
            <option value="">{{ 'clinicAdmin.common.none' | t }}</option>
            <option value="MALE">{{ 'clinicAdmin.gender.MALE' | t }}</option>
            <option value="FEMALE">{{ 'clinicAdmin.gender.FEMALE' | t }}</option>
          </select>
        </div>

        <div class="tl-field">
          <label class="tl-label" for="desk-dob">{{ 'clinicAdmin.frontDesk.dateOfBirth' | t }}</label>
          <input id="desk-dob" class="tl-input" type="date" formControlName="dateOfBirth" />
        </div>

        <div class="tl-field">
          <label class="tl-label" for="desk-password">
            {{ 'clinicAdmin.frontDesk.password' | t }}
            @if (!editing()) {
              <span class="required">*</span>
            }
          </label>
          <input id="desk-password" class="tl-input" type="password" formControlName="password" autocomplete="new-password" dir="ltr" />
          <span class="tl-hint">{{ (editing() ? 'clinicAdmin.frontDesk.passwordKeep' : 'clinicAdmin.frontDesk.passwordHint') | t }}</span>
          @if (form.controls.password.touched && form.controls.password.invalid) {
            <span class="tl-error">{{ 'clinicAdmin.frontDesk.passwordHint' | t }}</span>
          }
        </div>

        <h3 class="tl-drawer-section-title">{{ 'clinicAdmin.common.otherDetail' | t }}</h3>

        <div class="tl-field tl-field--full">
          <label class="tl-label" for="desk-role">
            {{ 'clinicAdmin.frontDesk.role' | t }} <span class="required">*</span>
          </label>
          <select id="desk-role" class="tl-select" formControlName="role">
            <option value="receptionist">{{ 'clinicAdmin.frontDesk.role.receptionist' | t }}</option>
            <option value="doctor">{{ 'clinicAdmin.frontDesk.role.doctor' | t }}</option>
            <option value="accountant">{{ 'clinicAdmin.frontDesk.role.accountant' | t }}</option>
          </select>
          <span class="tl-hint">
            {{ ('clinicAdmin.frontDesk.role.' + (form.controls.role.value || 'receptionist') + 'Hint') | t }}
          </span>
          <span class="tl-hint" style="color: #64748b; font-size: 0.75rem;">
            {{ 'clinicAdmin.frontDesk.clinicAdminNote' | t }}
          </span>
        </div>

        @if (form.controls.role.value !== 'accountant') {
          <div
            class="tl-field tl-field--full"
            style="padding: 12px 14px; border-radius: 8px; border: 1px solid rgba(217, 119, 6, 0.28); background: rgba(217, 119, 6, 0.05);"
          >
            <div class="tl-switch-row" style="justify-content: space-between; align-items: center;">
              <div>
                <span class="tl-label" style="display: block; margin-bottom: 2px; color: #92400e;">
                  {{ 'clinicAdmin.frontDesk.grantAccountant' | t }}
                </span>
                <span class="tl-hint" style="display: block; margin: 0;">
                  {{ 'clinicAdmin.frontDesk.grantAccountantHint' | t }}
                </span>
              </div>
              <label class="tl-switch">
                <input type="checkbox" formControlName="isAccountant" />
                <span class="tl-slider"></span>
              </label>
            </div>
          </div>
        }

        <div class="tl-field">
          <label class="tl-label" for="desk-clinic">
            {{ 'clinicAdmin.frontDesk.clinic' | t }} <span class="required">*</span>
          </label>
          <select id="desk-clinic" class="tl-select" formControlName="clinicId">
            <option value="">{{ 'clinicAdmin.frontDesk.chooseClinic' | t }}</option>
            @for (clinic of clinics(); track clinic.id) {
              <option [value]="clinic.id">{{ clinic.name }}</option>
            }
          </select>
          @if (form.controls.clinicId.touched && form.controls.clinicId.invalid) {
            <span class="tl-error">{{ 'clinicAdmin.frontDesk.chooseClinic' | t }}</span>
          }
        </div>

        <div class="tl-field">
          <label class="tl-label" for="desk-verification">{{ 'clinicAdmin.frontDesk.verification' | t }}</label>
          <select id="desk-verification" class="tl-select" formControlName="verificationStatus">
            <option value="VERIFIED">{{ 'clinicAdmin.verification.VERIFIED' | t }}</option>
            <option value="PENDING">{{ 'clinicAdmin.verification.PENDING' | t }}</option>
          </select>
        </div>

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

        <div class="tl-field tl-field--full">
          <label class="tl-label" for="desk-address">{{ 'clinicAdmin.frontDesk.address' | t }}</label>
          <input id="desk-address" class="tl-input" formControlName="addressLine" autocomplete="street-address" />
        </div>

        <div class="tl-field">
          <label class="tl-label" for="desk-country">{{ 'clinicAdmin.frontDesk.country' | t }}</label>
          <input id="desk-country" class="tl-input" formControlName="country" autocomplete="country-name" />
        </div>

        <div class="tl-field">
          <label class="tl-label" for="desk-state">{{ 'clinicAdmin.frontDesk.state' | t }}</label>
          <input id="desk-state" class="tl-input" formControlName="state" autocomplete="address-level1" />
        </div>

        <div class="tl-field">
          <label class="tl-label" for="desk-city">{{ 'clinicAdmin.frontDesk.city' | t }}</label>
          <input id="desk-city" class="tl-input" formControlName="city" autocomplete="address-level2" />
        </div>

        <div class="tl-field">
          <label class="tl-label" for="desk-postal">{{ 'clinicAdmin.frontDesk.postal' | t }}</label>
          <input id="desk-postal" class="tl-input" formControlName="postalCode" autocomplete="postal-code" />
        </div>
      </form>

      <div drawerFooter>
        <button type="button" class="tl-btn tl-btn-gray" (click)="closeDrawer()" [disabled]="saving()">
          {{ 'clinicAdmin.common.cancel' | t }}
        </button>
        <button type="submit" form="clinic-front-desk-form" class="tl-btn tl-btn-teal" [disabled]="saving()">
          {{ (saving() ? 'clinicAdmin.common.saving' : 'clinicAdmin.common.save') | t }}
        </button>
      </div>
    </fe-tl-drawer>

    @if (toast(); as notice) {
      <div class="tl-toast" [class.tl-toast--error]="notice.error" role="status">
        {{ notice.message }}
      </div>
    }
  `,
})
export class ClinicFrontDeskComponent implements OnInit {
  private readonly api = inject(ClinicApi);
  private readonly sessions = inject(SessionService);
  private readonly clinicRbac = inject(ClinicRbacService);
  private readonly projects = inject(ProjectContextService);
  private readonly i18n = inject(I18nService);
  private readonly fb = inject(FormBuilder);
  private readonly destroyRef = inject(DestroyRef);
  private readonly cdr = inject(ChangeDetectorRef);

  protected readonly isPlatformAdmin = computed(() => {
    const roles = this.clinicRbac.activeRoles();
    return (roles.includes('platform_admin') || this.sessions.session()?.role === 'PLATFORM') && !roles.includes('clinic_admin');
  });

  protected readonly canManage = computed(() => this.sessions.can()(PERMISSIONS.frontDeskManage));

  protected readonly rows = signal<readonly ClinicFrontDeskOfficer[]>([]);
  protected readonly clinics = signal<readonly ClinicLocation[]>([]);
  protected readonly quota = signal<{ activeUsersCount: number; activeFdoCount: number; effectiveMaxUsers: number } | null>(null);
  protected readonly isQuotaReached = computed(() => {
    const q = this.quota();
    if (!q) return false;
    return q.activeUsersCount >= q.effectiveMaxUsers;
  });
  protected readonly loading = signal(false);
  protected readonly loadFailed = signal(false);
  protected readonly saving = signal(false);
  protected readonly saveFailed = signal(false);
  protected readonly saveRequestId = signal<string | null>(null);
  protected readonly saveErrorMessage = signal<string | null>(null);
  protected readonly drawerOpen = signal(false);
  protected readonly editing = signal<ClinicFrontDeskOfficer | null>(null);
  protected readonly toast = signal<Toast | null>(null);
  protected readonly searchTerm = signal('');
  protected readonly statusFilter = signal<StatusFilter>('');
  protected readonly page = signal(1);
  protected readonly pageSize = signal(10);

  protected readonly form = this.fb.group({
    firstName: ['', [Validators.required, Validators.maxLength(80)]],
    lastName: ['', [Validators.required, Validators.maxLength(80)]],
    email: ['', [Validators.required, Validators.email, Validators.maxLength(320)]],
    gender: [''],
    phone: [''],
    password: [''],
    dateOfBirth: [''],
    profileImageUrl: [''],
    addressLine: [''],
    country: [''],
    state: [''],
    city: [''],
    postalCode: [''],
    clinicId: ['', [Validators.required]],
    verificationStatus: ['VERIFIED'],
    isActive: [true],
    role: ['receptionist', [Validators.required]],
    isAccountant: [false],
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
    void this.loadClinics();
  }

  protected async load(): Promise<void> {
    const token = ++this.loadToken;
    this.loading.set(true);
    this.loadFailed.set(false);
    try {
      const [response, quotaRes] = await Promise.all([
        this.api.frontDeskOfficers({
          projectId: this.projectId(),
          q: this.searchTerm() || undefined,
          status: this.statusFilter() || undefined,
        }),
        this.api.frontDeskQuota(this.projectId()).catch(() => null),
      ]);
      if (token !== this.loadToken) return;
      this.rows.set(response.items ?? []);
      if (quotaRes) {
        this.quota.set(quotaRes);
      }
      this.page.set(1);
    } catch {
      if (token !== this.loadToken) return;
      this.loadFailed.set(true);
      this.rows.set([]);
    } finally {
      if (token === this.loadToken) this.loading.set(false);
    }
  }

  protected roleBadgeBg(role: string | null | undefined): string {
    switch (role) {
      case 'doctor':
        return 'rgba(37, 99, 235, 0.12)';
      case 'accountant':
        return 'rgba(217, 119, 6, 0.14)';
      default:
        return 'rgba(13, 148, 136, 0.12)';
    }
  }

  protected roleBadgeColor(role: string | null | undefined): string {
    switch (role) {
      case 'doctor':
        return '#1d4ed8';
      case 'accountant':
        return '#b45309';
      default:
        return '#0f766e';
    }
  }

  private async loadClinics(): Promise<void> {
    try {
      const response = await this.api.locations({ projectId: this.projectId() });
      this.clinics.set(response.items ?? []);
    } catch {
      // The drawer still opens; the clinic select will be empty and save refuses.
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

  protected openCreate(): void {
    if (!this.canManage()) return;
    if (this.isQuotaReached()) {
      this.showToast('clinicAdmin.frontDesk.quotaReached', true);
      return;
    }
    this.editing.set(null);
    this.saveFailed.set(false);
    this.saveRequestId.set(null);
    this.saveErrorMessage.set(null);
    this.form.reset({
      firstName: '',
      lastName: '',
      email: '',
      gender: '',
      phone: '',
      password: '',
      dateOfBirth: '',
      profileImageUrl: '',
      addressLine: '',
      country: '',
      state: '',
      city: '',
      postalCode: '',
      clinicId: this.clinics()[0]?.id ?? '',
      verificationStatus: 'VERIFIED',
      isActive: true,
      role: 'receptionist',
      isAccountant: false,
    });
    this.drawerOpen.set(true);
  }

  protected openEdit(row: ClinicFrontDeskOfficer): void {
    if (!this.canManage()) return;
    this.editing.set(row);
    this.saveFailed.set(false);
    this.saveRequestId.set(null);
    this.saveErrorMessage.set(null);
    this.form.reset({
      firstName: row.firstName ?? '',
      lastName: row.lastName ?? '',
      email: row.email ?? '',
      gender: row.gender ?? '',
      phone: row.phone ?? '',
      password: '',
      dateOfBirth: row.dateOfBirth ?? '',
      profileImageUrl: row.profileImageUrl ?? '',
      addressLine: row.addressLine ?? '',
      country: row.country ?? '',
      state: row.state ?? '',
      city: row.city ?? '',
      postalCode: row.postalCode ?? '',
      clinicId: row.clinicId ?? '',
      verificationStatus: row.verificationStatus ?? 'VERIFIED',
      isActive: row.isActive !== false,
      role: row.role ?? 'receptionist',
      isAccountant: row.isAccountant === true,
    });
    this.drawerOpen.set(true);
  }

  protected closeDrawer(): void {
    if (this.saving()) return;
    this.drawerOpen.set(false);
  }

  protected async save(): Promise<void> {
    if (!this.canManage()) {
      this.showToast('clinicAdmin.common.permissionDenied', true);
      return;
    }
    if (!this.editing() && (this.form.controls.password.value ?? '').trim() === '') {
      this.form.controls.password.setErrors({ required: true });
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
      if (current?.userId) {
        await this.api.updateFrontDesk(current.userId, payload, this.projectId());
      } else {
        await this.api.createFrontDesk(payload, this.projectId());
      }
      this.drawerOpen.set(false);
      await this.load();
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
    row: ClinicFrontDeskOfficer,
    next: boolean,
    input: HTMLInputElement,
  ): Promise<void> {
    if (!this.canManage() || !row.userId) {
      input.checked = row.isActive === true;
      return;
    }
    input.disabled = true;
    try {
      await this.api.updateFrontDesk(row.userId, { isActive: next }, this.projectId());
      this.rows.update((rows) =>
        rows.map((candidate) =>
          candidate.userId === row.userId ? { ...candidate, isActive: next } : candidate,
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

  protected async remove(row: ClinicFrontDeskOfficer): Promise<void> {
    if (!this.canManage() || !row.userId) return;
    if (!window.confirm(this.i18n.t('clinicAdmin.frontDesk.confirmRemove'))) return;
    try {
      await this.api.deleteFrontDesk(row.userId, this.projectId());
      await this.load();
      this.showToast('clinicAdmin.common.deleted', false);
    } catch {
      this.showToast('clinicAdmin.common.saveFailed', true);
    }
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

  protected initials(row: ClinicFrontDeskOfficer): string {
    const first = row.firstName?.trim().charAt(0) ?? '';
    const last = row.lastName?.trim().charAt(0) ?? '';
    return `${first}${last}`.toUpperCase() || 'FD';
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

  protected clearImage(): void {
    this.form.controls.profileImageUrl.setValue('');
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
        this.form.controls.profileImageUrl.setValue(reader.result);
        this.cdr.markForCheck();
      }
    };
    reader.readAsDataURL(file);
  }

  protected exportCsv(): void {
    const header = ['Officer', 'Role', 'Accountant Privilege', 'Email', 'Phone', 'Clinic', 'Status'];
    const lines = [header, ...this.rows().map((row) => [
      row.fullName ?? '',
      row.role ?? 'receptionist',
      row.isAccountant || row.role === 'accountant' ? 'YES' : 'NO',
      row.email ?? '',
      row.phone ?? '',
      row.clinicName ?? '',
      row.isActive ? 'ACTIVE' : 'INACTIVE',
    ])];
    const csv = lines.map((line) => line.map(csvCell).join(',')).join('\r\n');
    const blob = new Blob([`\ufeff${csv}`], { type: 'text/csv;charset=utf-8;' });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = 'front-desk.csv';
    anchor.click();
    URL.revokeObjectURL(url);
  }

  private toPayload(): ClinicFrontDeskInput {
    const value = this.form.getRawValue();
    const role = value.role || 'receptionist';
    const isAccountant = role === 'accountant' ? true : Boolean(value.isAccountant);
    return {
      firstName: (value.firstName ?? '').trim(),
      lastName: (value.lastName ?? '').trim(),
      email: (value.email ?? '').trim(),
      gender: blankToNull(value.gender),
      phone: blankToNull(value.phone),
      password: blankToNull(value.password),
      dateOfBirth: blankToNull(value.dateOfBirth),
      profileImageUrl: blankToNull(value.profileImageUrl),
      addressLine: blankToNull(value.addressLine),
      country: blankToNull(value.country),
      state: blankToNull(value.state),
      city: blankToNull(value.city),
      postalCode: blankToNull(value.postalCode),
      clinicId: blankToNull(value.clinicId),
      verificationStatus: value.verificationStatus || 'VERIFIED',
      isActive: value.isActive ?? true,
      role,
      isAccountant,
    };
  }

  protected projectId(): string | undefined {
    return this.projects.selectedProjectId() ?? this.sessions.session()?.projectIds?.[0] ?? undefined;
  }

  private showToast(messageKey: string, error: boolean): void {
    this.toast.set({ message: this.i18n.t(messageKey), error });
    if (this.toastTimer) clearTimeout(this.toastTimer);
    this.toastTimer = setTimeout(() => this.toast.set(null), 3200);
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
