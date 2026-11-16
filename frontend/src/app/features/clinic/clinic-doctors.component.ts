/**
 * `/app/clinic/doctors` — the Doctors management screen.
 *
 * The roster read (`GET /clinic/doctors`) withholds email and phone by design.
 * This screen uses the management projection (`/clinic/doctor-profiles`), which is
 * authorized by `doctor:read-management`, and gates every write on `doctor:manage`.
 * A doctor is a roster profile, not a sign-in account, so the drawer has no password.
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
import {
  FormArray,
  FormBuilder,
  FormGroup,
  ReactiveFormsModule,
  Validators,
} from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ApiError } from '../../core/api/api.service';
import {
  ClinicApi,
  type ClinicDoctorProfile,
  type ClinicDoctorProfileInput,
  type ClinicLocation,
  type ClinicProcedure,
} from '../../core/api/endpoints';
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
  selector: 'fe-clinic-doctors-page',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, RouterLink, TlDrawerComponent, TranslatePipe],
  template: `
    <div class="tl-page" data-testid="clinic-doctors">
      <div class="tl-page-top">
        <div style="display: flex; align-items: center; gap: 8px;">
          <nav class="tl-breadcrumbs" aria-label="Breadcrumb">
            <a routerLink="/app/dashboard">{{ 'clinicAdmin.nav.home' | t }}</a>
            <span aria-hidden="true">&gt;&gt;</span>
            <span class="active">{{ 'clinicAdmin.doctors.breadcrumb' | t }}</span>
          </nav>
          <div class="tl-info-wrap">
            <button
              type="button"
              class="tl-info-trigger"
              [class.is-active]="showDoctorsInfo()"
              (click)="showDoctorsInfo.set(!showDoctorsInfo())"
              title="Doctors management info"
            >?</button>
            @if (showDoctorsInfo()) {
              <div class="tl-info-popover">
                {{ 'clinicAdmin.doctors.emptyBody' | t }}
              </div>
            }
          </div>
        </div>
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
      </div>

      <div class="tl-toolbar">
        <div class="tl-toolbar-left">
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
              [attr.placeholder]="'clinicAdmin.doctors.searchPlaceholder' | t"
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

      <div class="tl-table-wrap" data-testid="clinic-doctors-table">
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
          <div class="tl-table-empty" data-testid="clinic-doctors-empty">
            <strong>{{ 'clinicAdmin.doctors.emptyTitle' | t }}</strong>
            <span>{{ 'clinicAdmin.doctors.emptyBody' | t }}</span>
          </div>
        } @else {
          <table class="tl-table">
            <caption class="fe-sr">{{ 'clinicAdmin.doctors.title' | t }}</caption>
            <thead>
              <tr>
                <th scope="col">{{ 'clinicAdmin.doctors.name' | t }}</th>
                <th scope="col">{{ 'clinicAdmin.doctors.specialty' | t }}</th>
                <th scope="col">{{ 'clinicAdmin.doctors.clinic' | t }}</th>
                <th scope="col">{{ 'clinicAdmin.doctors.contact' | t }}</th>
                <th scope="col">{{ 'clinicAdmin.doctors.experience' | t }}</th>
                <th scope="col">{{ 'clinicAdmin.common.status' | t }}</th>
                <th scope="col">{{ 'clinicAdmin.common.actions' | t }}</th>
              </tr>
            </thead>
            <tbody>
              @for (row of pagedRows(); track row.id ?? $index) {
                <tr>
                  <td>
                    <div class="tl-cell-profile">
                      <div class="tl-avatar">
                        @if (row.imageUrl) {
                          <img [src]="row.imageUrl" [alt]="row.name ?? ''" />
                        } @else {
                          <span class="tl-avatar-initials">{{ initials(row) }}</span>
                        }
                      </div>
                      <div class="tl-profile-info">
                        <span class="tl-profile-name">{{ row.name || '—' }}</span>
                        @if (row.email) {
                          <span class="tl-profile-sub">{{ row.email }}</span>
                        }
                      </div>
                    </div>
                  </td>
                  <td>{{ row.expert || row.specialty || '—' }}</td>
                  <td>{{ row.clinicName || ('clinicAdmin.doctors.unassigned' | t) }}</td>
                  <td>{{ row.phone || '—' }}</td>
                  <td>
                    @if (row.experienceYears !== null && row.experienceYears !== undefined) {
                      {{ row.experienceYears }} {{ 'clinicAdmin.doctors.years' | t }}
                    } @else {
                      —
                    }
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
                        (click)="retire(row)"
                        [disabled]="!canManage()"
                        [attr.aria-label]="'clinicAdmin.doctors.retire' | t"
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
      [title]="(editing() ? 'clinicAdmin.doctors.editTitle' : 'clinicAdmin.doctors.createTitle') | t"
      [subtitle]="'clinicAdmin.doctors.drawerHint' | t"
      (closed)="closeDrawer()"
    >
      <form id="clinic-doctor-form" class="tl-form-grid" [formGroup]="form" (ngSubmit)="save()">
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
            <span class="tl-label">{{ 'clinicAdmin.doctors.photo' | t }}</span>
            <label class="tl-file-drop" (dragover)="$event.preventDefault()" (drop)="onDrop($event, 'photo')">
              @if (form.controls.imageUrl.value) {
                <img class="tl-file-preview" [src]="form.controls.imageUrl.value" alt="" />
              } @else {
                <svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" aria-hidden="true">
                  <circle cx="12" cy="8" r="4" />
                  <path d="M4 21a8 8 0 0 1 16 0" />
                </svg>
                <span><strong>{{ 'clinicAdmin.common.browse' | t }}</strong> {{ 'clinicAdmin.common.orDrop' | t }}</span>
              }
              <input type="file" accept="image/*" (change)="onFile($event, 'photo')" />
            </label>
            @if (form.controls.imageUrl.value) {
              <button type="button" class="tl-btn tl-btn-gray" (click)="clearImage('photo')">
                {{ 'clinicAdmin.common.removeImage' | t }}
              </button>
            }
          </div>

          <div class="tl-drawer-stack">
            <div class="tl-field">
              <label class="tl-label" for="doctor-first">
                {{ 'clinicAdmin.doctors.firstName' | t }} <span class="required">*</span>
              </label>
              <input id="doctor-first" class="tl-input" formControlName="firstName" autocomplete="given-name" />
              @if (form.controls.firstName.touched && form.controls.firstName.invalid) {
                <span class="tl-error">{{ 'clinicAdmin.common.required' | t }}</span>
              }
            </div>

            <div class="tl-field">
              <label class="tl-label" for="doctor-last">
                {{ 'clinicAdmin.doctors.lastName' | t }} <span class="required">*</span>
              </label>
              <input id="doctor-last" class="tl-input" formControlName="lastName" autocomplete="family-name" />
              @if (form.controls.lastName.touched && form.controls.lastName.invalid) {
                <span class="tl-error">{{ 'clinicAdmin.common.required' | t }}</span>
              }
            </div>

            <div class="tl-field">
              <label class="tl-label" for="doctor-email">
                {{ 'clinicAdmin.doctors.email' | t }} <span class="required">*</span>
              </label>
              <input id="doctor-email" class="tl-input" type="email" formControlName="email" autocomplete="email" dir="ltr" />
              @if (form.controls.email.touched && form.controls.email.invalid) {
                <span class="tl-error">{{ 'clinicAdmin.doctors.emailHint' | t }}</span>
              }
            </div>
          </div>
        </div>

        <div class="tl-field">
          <label class="tl-label" for="doctor-gender">{{ 'clinicAdmin.doctors.gender' | t }}</label>
          <select id="doctor-gender" class="tl-select" formControlName="gender">
            <option value="">{{ 'clinicAdmin.common.none' | t }}</option>
            <option value="MALE">{{ 'clinicAdmin.gender.MALE' | t }}</option>
            <option value="FEMALE">{{ 'clinicAdmin.gender.FEMALE' | t }}</option>
          </select>
        </div>

        <div class="tl-field">
          <label class="tl-label" for="doctor-phone">{{ 'clinicAdmin.doctors.phone' | t }}</label>
          <input id="doctor-phone" class="tl-input" formControlName="phone" inputmode="tel" dir="ltr" />
        </div>

        <div class="tl-field">
          <label class="tl-label" for="doctor-about">{{ 'clinicAdmin.doctors.about' | t }}</label>
          <textarea id="doctor-about" class="tl-textarea" formControlName="aboutSelf"></textarea>
        </div>

        <div class="tl-field">
          <label class="tl-label" for="doctor-expert">{{ 'clinicAdmin.doctors.expert' | t }}</label>
          <input id="doctor-expert" class="tl-input" formControlName="expert" />
        </div>

        <h3 class="tl-drawer-section-title">{{ 'clinicAdmin.common.otherDetail' | t }}</h3>

        <div class="tl-field">
          <label class="tl-label" for="doctor-commission">{{ 'clinicAdmin.doctors.commission' | t }}</label>
          <input id="doctor-commission" class="tl-input" type="number" min="0" step="0.01" formControlName="commission" />
        </div>

        <div class="tl-field">
          <label class="tl-label" for="doctor-clinic">{{ 'clinicAdmin.doctors.clinic' | t }}</label>
          <select id="doctor-clinic" class="tl-select" formControlName="clinicId">
            <option value="">{{ 'clinicAdmin.doctors.unassigned' | t }}</option>
            @for (clinic of clinics(); track clinic.id) {
              <option [value]="clinic.id">{{ clinic.name }}</option>
            }
          </select>
        </div>

        <div class="tl-field">
          <label class="tl-label" for="doctor-service">{{ 'clinicAdmin.doctors.service' | t }}</label>
          <select id="doctor-service" class="tl-select" formControlName="serviceId">
            <option value="">{{ 'clinicAdmin.common.none' | t }}</option>
            @for (service of services(); track service.id) {
              <option [value]="service.id">{{ service.name }}</option>
            }
          </select>
        </div>

        <div class="tl-field">
          <label class="tl-label" for="doctor-experience">{{ 'clinicAdmin.doctors.experience' | t }}</label>
          <input id="doctor-experience" class="tl-input" type="number" min="0" max="80" formControlName="experienceYears" />
        </div>

        <div class="tl-field">
          <label class="tl-label" for="doctor-postal">{{ 'clinicAdmin.doctors.postal' | t }}</label>
          <input id="doctor-postal" class="tl-input" formControlName="postalCode" autocomplete="postal-code" />
        </div>

        <div class="tl-field">
          <label class="tl-label" for="doctor-country">{{ 'clinicAdmin.doctors.country' | t }}</label>
          <input id="doctor-country" class="tl-input" formControlName="country" autocomplete="country-name" />
        </div>

        <div class="tl-field">
          <label class="tl-label" for="doctor-state">{{ 'clinicAdmin.doctors.state' | t }}</label>
          <input id="doctor-state" class="tl-input" formControlName="state" autocomplete="address-level1" />
        </div>

        <div class="tl-field">
          <label class="tl-label" for="doctor-city">{{ 'clinicAdmin.doctors.city' | t }}</label>
          <input id="doctor-city" class="tl-input" formControlName="city" autocomplete="address-level2" />
        </div>

        <h3 class="tl-drawer-section-title">{{ 'clinicAdmin.doctors.qualifications' | t }}</h3>

        <div class="tl-field tl-field--full">
          <div class="tl-label-row">
            <span class="tl-label">{{ 'clinicAdmin.doctors.qualifications' | t }}</span>
            <button type="button" class="tl-btn tl-btn-teal" (click)="addQualification()">
              + {{ 'clinicAdmin.doctors.addQualification' | t }}
            </button>
          </div>
          @if (qualifications.length === 0) {
            <p class="tl-muted">{{ 'clinicAdmin.doctors.noQualifications' | t }}</p>
          }
          <div formArrayName="qualifications" class="tl-qualification-list">
            @for (row of qualificationRows(); track $index; let i = $index) {
              <div class="tl-qualification-row" [formGroupName]="i">
                <input class="tl-input" formControlName="degree" [attr.placeholder]="'clinicAdmin.doctors.degree' | t" />
                <input class="tl-input" formControlName="university" [attr.placeholder]="'clinicAdmin.doctors.university' | t" />
                <input class="tl-input" type="number" min="1950" max="2100" formControlName="graduationYear" [attr.placeholder]="'clinicAdmin.doctors.graduationYear' | t" />
                <button type="button" class="tl-action-icon delete" (click)="removeQualification(i)" [attr.aria-label]="'clinicAdmin.common.remove' | t">
                  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
                    <path d="M3 6h18M8 6V4h8v2M19 6l-1 14H6L5 6M10 11v5M14 11v5" />
                  </svg>
                </button>
              </div>
            }
          </div>
        </div>

        <h3 class="tl-drawer-section-title">{{ 'clinicAdmin.doctors.signature' | t }}</h3>

        <div class="tl-field">
          <span class="tl-label">{{ 'clinicAdmin.doctors.signature' | t }}</span>
          <label class="tl-file-drop" (dragover)="$event.preventDefault()" (drop)="onDrop($event, 'signature')">
            @if (form.controls.signatureUrl.value) {
              <img class="tl-file-preview" [src]="form.controls.signatureUrl.value" alt="" />
            } @else {
              <svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" aria-hidden="true">
                <path d="M3 17c3-1 4-6 7-6s2 5 5 5 3-4 6-4" />
              </svg>
              <span><strong>{{ 'clinicAdmin.common.browse' | t }}</strong> {{ 'clinicAdmin.common.orDrop' | t }}</span>
            }
            <input type="file" accept="image/*" (change)="onFile($event, 'signature')" />
          </label>
          @if (form.controls.signatureUrl.value) {
            <button type="button" class="tl-btn tl-btn-gray" (click)="clearImage('signature')">
              {{ 'clinicAdmin.common.removeImage' | t }}
            </button>
          }
        </div>

        <div class="tl-drawer-stack">
          <div class="tl-field">
            <label class="tl-label" for="doctor-verification">{{ 'clinicAdmin.doctors.verification' | t }}</label>
            <select id="doctor-verification" class="tl-select" formControlName="verificationStatus">
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
        </div>
      </form>

      <div drawerFooter>
        <button type="button" class="tl-btn tl-btn-gray" (click)="closeDrawer()" [disabled]="saving()">
          {{ 'clinicAdmin.common.cancel' | t }}
        </button>
        <button type="submit" form="clinic-doctor-form" class="tl-btn tl-btn-teal" [disabled]="saving()">
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
export class ClinicDoctorsComponent implements OnInit {
  private readonly api = inject(ClinicApi);
  private readonly sessions = inject(SessionService);
  private readonly projects = inject(ProjectContextService);
  private readonly i18n = inject(I18nService);
  private readonly fb = inject(FormBuilder);
  private readonly destroyRef = inject(DestroyRef);
  private readonly cdr = inject(ChangeDetectorRef);

  protected readonly canManage = computed(() => this.sessions.can()(PERMISSIONS.doctorManage));

  protected readonly showDoctorsInfo = signal(false);
  protected readonly rows = signal<readonly ClinicDoctorProfile[]>([]);
  protected readonly clinics = signal<readonly ClinicLocation[]>([]);
  protected readonly services = signal<readonly ClinicProcedure[]>([]);
  protected readonly loading = signal(false);
  protected readonly loadFailed = signal(false);
  protected readonly saving = signal(false);
  protected readonly saveFailed = signal(false);
  protected readonly saveRequestId = signal<string | null>(null);
  protected readonly saveErrorMessage = signal<string | null>(null);
  protected readonly drawerOpen = signal(false);
  protected readonly editing = signal<ClinicDoctorProfile | null>(null);
  protected readonly toast = signal<Toast | null>(null);
  protected readonly searchTerm = signal('');
  protected readonly statusFilter = signal<StatusFilter>('');
  protected readonly page = signal(1);
  protected readonly pageSize = signal(10);

  protected readonly form = this.fb.group({
    firstName: ['', [Validators.required, Validators.maxLength(80)]],
    lastName: ['', [Validators.required, Validators.maxLength(80)]],
    email: ['', [Validators.required, Validators.email, Validators.maxLength(320)]],
    phone: [''],
    gender: [''],
    imageUrl: [''],
    aboutSelf: [''],
    expert: [''],
    commission: [null as number | null, [Validators.min(0)]],
    clinicId: [''],
    serviceId: [''],
    experienceYears: [null as number | null, [Validators.min(0), Validators.max(80)]],
    country: [''],
    state: [''],
    city: [''],
    postalCode: [''],
    signatureUrl: [''],
    verificationStatus: ['VERIFIED'],
    isActive: [true],
    qualifications: this.fb.array<FormGroup>([]),
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
  private editToken = 0;
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
    void this.loadLookups();
  }

  protected get qualifications(): FormArray<FormGroup> {
    return this.form.controls.qualifications;
  }

  protected qualificationRows(): readonly FormGroup[] {
    return this.qualifications.controls;
  }

  protected async load(): Promise<void> {
    const token = ++this.loadToken;
    this.loading.set(true);
    this.loadFailed.set(false);
    try {
      const response = await this.api.doctorProfiles({
        projectId: this.projectId(),
        q: this.searchTerm() || undefined,
        status: this.statusFilter() || undefined,
      });
      if (token !== this.loadToken) return;
      this.rows.set(response.items ?? []);
      this.page.set(1);
    } catch {
      if (token !== this.loadToken) return;
      this.loadFailed.set(true);
      this.rows.set([]);
    } finally {
      if (token === this.loadToken) this.loading.set(false);
    }
  }

  private async loadLookups(): Promise<void> {
    try {
      const [clinics, services] = await Promise.all([
        this.api.locations({ projectId: this.projectId() }),
        this.api.procedures({ projectId: this.projectId() }),
      ]);
      this.clinics.set(clinics.items ?? []);
      this.services.set(services.items ?? []);
    } catch {
      // The drawer still opens; an unavailable lookup must not block the list.
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
    this.editing.set(null);
    this.saveFailed.set(false);
    this.saveRequestId.set(null);
    this.saveErrorMessage.set(null);
    this.qualifications.clear();
    this.form.reset({
      firstName: '',
      lastName: '',
      email: '',
      phone: '',
      gender: '',
      imageUrl: '',
      aboutSelf: '',
      expert: '',
      commission: null,
      clinicId: this.projectId() ? (this.clinics()[0]?.id ?? '') : '',
      serviceId: '',
      experienceYears: null,
      country: '',
      state: '',
      city: '',
      postalCode: '',
      signatureUrl: '',
      verificationStatus: 'VERIFIED',
      isActive: true,
    });
    this.drawerOpen.set(true);
  }

  protected async openEdit(row: ClinicDoctorProfile): Promise<void> {
    if (!this.canManage()) return;
    const token = ++this.editToken;
    this.editing.set(row);
    this.saveFailed.set(false);
    this.saveRequestId.set(null);
    this.saveErrorMessage.set(null);
    this.resetFormFrom(row);
    if (row.id) {
      try {
        // The list projection deliberately omits qualification rows. Fetch the
        // detail before opening so the drawer never opens empty and then replaces
        // what the operator has started typing when the detail arrives.
        const detail = await this.api.doctorProfile(row.id, this.projectId());
        if (token !== this.editToken) return;
        this.editing.set(detail);
        this.resetFormFrom(detail);
      } catch {
        if (token !== this.editToken) return;
        this.showToast('clinicAdmin.common.loadFailed', true);
      }
    }
    if (token !== this.editToken) return;
    this.drawerOpen.set(true);
  }

  private resetFormFrom(row: ClinicDoctorProfile): void {
    this.qualifications.clear();
    this.form.reset({
      firstName: row.firstName ?? '',
      lastName: row.lastName ?? '',
      email: row.email ?? '',
      phone: row.phone ?? '',
      gender: row.gender ?? '',
      imageUrl: row.imageUrl ?? '',
      aboutSelf: row.aboutSelf ?? '',
      expert: row.expert ?? '',
      commission: row.commission ?? null,
      clinicId: row.clinicId ?? '',
      serviceId: row.serviceId ?? '',
      experienceYears: row.experienceYears ?? null,
      country: row.country ?? '',
      state: row.state ?? '',
      city: row.city ?? '',
      postalCode: row.postalCode ?? '',
      signatureUrl: row.signatureUrl ?? '',
      verificationStatus: row.verificationStatus ?? 'VERIFIED',
      isActive: row.isActive !== false,
    });
    for (const qualification of row.qualifications ?? []) {
      this.addQualification(qualification);
    }
  }

  protected addQualification(
    qualification?: { degree?: string; university?: string | null; graduationYear?: number | null },
  ): void {
    this.qualifications.push(
      this.fb.group({
        degree: [qualification?.degree ?? '', [Validators.maxLength(160)]],
        university: [qualification?.university ?? '', [Validators.maxLength(160)]],
        graduationYear: [
          qualification?.graduationYear ?? null,
          [Validators.min(1950), Validators.max(2100)],
        ],
      }),
    );
  }

  protected removeQualification(index: number): void {
    this.qualifications.removeAt(index);
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
        await this.api.updateDoctor(current.id, payload, projId);
      } else {
        await this.api.createDoctor(payload, projId);
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
    row: ClinicDoctorProfile,
    next: boolean,
    input: HTMLInputElement,
  ): Promise<void> {
    if (!this.canManage() || !row.id) {
      input.checked = row.isActive === true;
      return;
    }
    input.disabled = true;
    try {
      await this.api.updateDoctor(row.id, { isActive: next }, this.projectId());
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

  protected async retire(row: ClinicDoctorProfile): Promise<void> {
    if (!this.canManage() || !row.id) return;
    if (!window.confirm(this.i18n.t('clinicAdmin.doctors.confirmRetire'))) return;
    try {
      await this.api.deleteDoctor(row.id, this.projectId());
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

  protected initials(row: ClinicDoctorProfile): string {
    const first = row.firstName?.trim().charAt(0) ?? '';
    const last = row.lastName?.trim().charAt(0) ?? '';
    return `${first}${last}`.toUpperCase() || 'DR';
  }

  protected onFile(event: Event, target: 'photo' | 'signature'): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (file) this.readImage(file, target);
    input.value = '';
  }

  protected onDrop(event: DragEvent, target: 'photo' | 'signature'): void {
    event.preventDefault();
    const file = event.dataTransfer?.files?.[0];
    if (file) this.readImage(file, target);
  }

  protected clearImage(target: 'photo' | 'signature'): void {
    if (target === 'photo') this.form.controls.imageUrl.setValue('');
    else this.form.controls.signatureUrl.setValue('');
  }

  private readImage(file: File, target: 'photo' | 'signature'): void {
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
      if (typeof reader.result !== 'string') return;
      if (target === 'photo') this.form.controls.imageUrl.setValue(reader.result);
      else this.form.controls.signatureUrl.setValue(reader.result);
      this.cdr.markForCheck();
    };
    reader.readAsDataURL(file);
  }

  protected exportCsv(): void {
    const header = ['Doctor', 'Email', 'Phone', 'Specialty', 'Clinic', 'Experience', 'Status'];
    const lines = [header, ...this.rows().map((row) => [
      row.name ?? '',
      row.email ?? '',
      row.phone ?? '',
      row.expert ?? row.specialty ?? '',
      row.clinicName ?? '',
      row.experienceYears === null || row.experienceYears === undefined
        ? '' : String(row.experienceYears),
      row.isActive ? 'ACTIVE' : 'INACTIVE',
    ])];
    const csv = lines.map((line) => line.map(csvCell).join(',')).join('\r\n');
    const blob = new Blob([`\ufeff${csv}`], { type: 'text/csv;charset=utf-8;' });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = 'doctors.csv';
    anchor.click();
    URL.revokeObjectURL(url);
  }

  private toPayload(): ClinicDoctorProfileInput {
    const value = this.form.getRawValue();
    return {
      firstName: (value.firstName ?? '').trim(),
      lastName: (value.lastName ?? '').trim(),
      email: (value.email ?? '').trim(),
      phone: blankToNull(value.phone),
      gender: blankToNull(value.gender),
      imageUrl: blankToNull(value.imageUrl),
      aboutSelf: blankToNull(value.aboutSelf),
      expert: blankToNull(value.expert),
      commission: value.commission,
      clinicId: blankToNull(value.clinicId),
      serviceId: blankToNull(value.serviceId),
      experienceYears: value.experienceYears,
      country: blankToNull(value.country),
      state: blankToNull(value.state),
      city: blankToNull(value.city),
      postalCode: blankToNull(value.postalCode),
      signatureUrl: blankToNull(value.signatureUrl),
      verificationStatus: value.verificationStatus || 'VERIFIED',
      isActive: value.isActive ?? true,
      qualifications: this.qualifications.controls
        .map((control) => {
          const row = control.getRawValue() as {
            degree?: string;
            university?: string;
            graduationYear?: number | null;
          };
          return {
            degree: (row.degree ?? '').trim(),
            university: blankToNull(row.university),
            graduationYear: row.graduationYear ?? null,
          };
        })
        .filter((row) => row.degree !== ''),
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
