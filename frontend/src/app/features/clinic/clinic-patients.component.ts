/**
 * `/app/clinic/patients` — the patient register and the Create New Patient drawer.
 *
 * The list is still the server's list: `q` goes out as a query parameter and the
 * rows on screen are always the rows the server sent for that term. A client-side
 * filter would answer "is this patient in the page I happen to hold", which is not
 * the question a receptionist is asking.
 *
 * The drawer writes every field the screenshot asks for. Gender, birth date and
 * phone are required by the server as well as by the form, so a sparse row cannot be
 * created by posting around the UI. The portal password is hashed on the server and
 * is never read back; the confirmation field exists only to catch a typo before the
 * request leaves the browser.
 */
import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import {
  FormBuilder,
  ReactiveFormsModule,
  Validators,
} from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ApiError } from '../../core/api/api.service';
import { ProjectContextService } from '../../core/context/project-context.service';
import { FormatService } from '../../core/format/format.service';
import { I18nService } from '../../core/i18n/i18n.service';
import type { Namespace } from '../../core/i18n/i18n.types';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { ResourceStore } from '../../core/state/resource';
import { AsyncStateComponent } from '../../ui/async-state.component';
import { TlDrawerComponent } from '../../ui/tl-drawer.component';
import {
  ClinicPatientApi,
  type ClinicDuplicateCandidate,
  type ClinicIntakeQrInfo,
  type ClinicPatient,
  type GeneratedIntakeCode,
} from './clinic-patient.api';
import {
  buildIntakePortalUrl,
  generateQrSvgParts,
  generateQrSvgString,
  getSavedIntakePortalBaseUrl,
  printIntakeQrPoster,
  saveIntakePortalBaseUrl,
} from '../../core/utils/qr-svg';
import { clinicAdminStrings } from './clinic-admin.strings';
import { clinicStrings } from './clinic.strings';

@Component({
  selector: 'fe-clinic-patients-page',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    AsyncStateComponent,
    ReactiveFormsModule,
    RouterLink,
    TlDrawerComponent,
    TranslatePipe,
  ],
  template: `
    <div class="tl-page" data-testid="clinic-patients">
      <nav class="tl-breadcrumbs" aria-label="Breadcrumb">
        <a routerLink="/app/dashboard">{{ 'clinicAdmin.nav.home' | t }}</a>
        <span aria-hidden="true">/</span>
        <span class="active">{{ 'clinicAdmin.patients.breadcrumb' | t }}</span>
      </nav>

      <div class="tl-page-head">
        <div>
          <h1 class="tl-page-title">{{ 'clinic.patients.title' | t }}</h1>
          <p class="tl-page-subtitle">{{ 'clinicAdmin.patients.subtitle' | t }}</p>
        </div>
        <div class="tl-head-actions">
          <button
            type="button"
            class="tl-btn tl-btn-gray"
            data-testid="clinic-patients-intake-qr"
            (click)="openIntakeQrDrawer()"
          >
            {{ isArabic() ? 'QR بوابة المرضى' : 'Patient Intake QR' }}
          </button>
          <button
            type="button"
            class="tl-btn tl-btn-primary"
            data-testid="clinic-patients-new"
            (click)="openCreateDrawer()"
          >
            + {{ 'clinicAdmin.patients.createTitle' | t }}
          </button>
        </div>
      </div>

      @if (quickCreatedPatient(); as created) {
        <div class="tl-quick-banner" role="status" data-testid="clinic-patient-quick-created">
          <div>
            <strong>
              {{ isArabic() ? 'تم تسجيل المريض بنجاح:' : 'Patient registered:' }}
              {{ nameOf(created) }}
              @if (created.patientNo) {
                <span class="tl-mono">({{ created.patientNo }})</span>
              }
            </strong>
            @if (quickGeneratedCode(); as codeObj) {
              <div class="tl-quick-code-box">
                <span>{{ isArabic() ? 'كود تعبئة الاستبيان (صالح لمدة ساعة):' : '1-Hour Intake Code:' }}</span>
                <strong class="tl-mono tl-quick-code" dir="ltr">{{ codeObj.formattedCode }}</strong>
                <button type="button" class="tl-btn tl-btn-gray tl-btn-xs" (click)="copyText(codeObj.code)">
                  {{ copiedText() ? (isArabic() ? 'تم النسخ' : 'Copied') : (isArabic() ? 'نسخ' : 'Copy') }}
                </button>
              </div>
            }
          </div>
          <div class="tl-quick-banner-actions">
            @if (created.id && !quickGeneratedCode()) {
              <button
                type="button"
                class="tl-btn tl-btn-primary tl-btn-xs"
                [disabled]="generatingQuickCode()"
                (click)="generateQuickCodeForCreated(created.id)"
              >
                {{ generatingQuickCode() ? '...' : (isArabic() ? 'توليد الكود للمريض الآن' : 'Generate 1h Intake Code') }}
              </button>
            }
            @if (created.id) {
              <a class="tl-btn tl-btn-gray tl-btn-xs" [routerLink]="['/app/clinic/patients', created.id]">
                {{ isArabic() ? 'فتح ملف المريض' : 'Open Profile' }}
              </a>
            }
            <button type="button" class="tl-link" (click)="dismissQuickBanner()">
              {{ isArabic() ? 'إغلاق' : 'Dismiss' }}
            </button>
          </div>
        </div>
      }

      <section class="tl-card" [attr.aria-busy]="patients.loading()">
        <div class="tl-toolbar">
          <div class="tl-toolbar-left tl-search">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
              <circle cx="11" cy="11" r="7"></circle>
              <path d="m20 20-3.2-3.2"></path>
            </svg>
            <input
              id="fe-clinic-patient-search"
              class="tl-input"
              type="search"
              autocomplete="off"
              [value]="term()"
              [attr.placeholder]="'clinicAdmin.patients.searchPlaceholder' | t"
              (input)="onSearch($event)"
            />
          </div>
          <div class="tl-toolbar-right">
            <button
              type="button"
              class="tl-btn tl-btn-gray"
              data-testid="clinic-patients-refresh"
              (click)="reload()"
            >
              {{ 'clinic.patients.refresh' | t }}
            </button>
          </div>
        </div>

        <p class="tl-search-hint" data-testid="clinic-patients-search-hint">
          {{ 'clinic.patients.search.hint' | t }}
        </p>

        <fe-async-state
          [loading]="patients.loading() && patients.value() === null"
          [empty]="patients.empty()"
          [error]="patients.error()"
          [hasValue]="patients.value() !== null"
          [loadingLabel]="'state.loading' | t"
          [loadingHint]="'state.loadingHint' | t"
          [emptyTitle]="emptyTitle()"
          [emptyBody]="emptyBody()"
          [errorTitle]="'clinic.error.title' | t"
          [errorBody]="'clinic.error.body' | t"
          [requestId]="errorRequestId()"
          [requestIdLabel]="'state.requestId' | t"
          [retryLabel]="'clinic.retry' | t"
          (retry)="reload()"
        >
          @if (hasTerm()) {
            <button fe-empty-action type="button" class="tl-btn tl-btn-gray" (click)="clearSearch()">
              {{ 'clinic.patients.search.clear' | t }}
            </button>
          }
        </fe-async-state>

        @if (rows().length > 0) {
          <div class="tl-table-wrap">
            <table class="tl-table" data-testid="clinic-patients-table">
              <caption class="tl-sr-only">{{ 'clinic.patients.title' | t }}</caption>
              <thead>
                <tr>
                  <th scope="col">{{ 'clinic.patients.col.name' | t }}</th>
                  <th scope="col">{{ 'clinic.patients.col.number' | t }}</th>
                  <th scope="col">{{ 'clinicAdmin.patients.contact' | t }}</th>
                  <th scope="col">{{ 'clinic.patients.col.birthDate' | t }}</th>
                  <th scope="col">{{ 'clinicAdmin.common.status' | t }}</th>
                </tr>
              </thead>
              <tbody>
                @for (patient of rows(); track patient.id ?? $index) {
                  <tr>
                    <th scope="row">
                      <span class="tl-person">
                        @if (patient.profileImageUrl) {
                          <img
                            class="tl-avatar"
                            [src]="patient.profileImageUrl"
                            [alt]="nameOf(patient)"
                          />
                        } @else {
                          <span class="tl-avatar tl-avatar--initials" aria-hidden="true">
                            {{ initials(patient) }}
                          </span>
                        }
                        <span class="tl-person-text">
                          @if (patient.id) {
                            <a class="patient-link" [routerLink]="['/app/clinic/patients', patient.id]">
                              {{ nameOf(patient) }}
                            </a>
                          } @else {
                            <span>{{ nameOf(patient) }}</span>
                          }
                          @if (patient.deleted === true) {
                            <span class="tl-badge tl-badge-pill">
                              {{ 'clinic.patients.deleted' | t }}
                            </span>
                          }
                          @if (patient.patientNo) {
                            <span class="tl-person-meta tl-mono" dir="ltr">{{ patient.patientNo }}</span>
                          }
                        </span>
                      </span>
                    </th>
                    <td class="tl-mono" dir="ltr">{{ patient.patientNo ?? '—' }}</td>
                    <td>
                      <span class="tl-stack">
                        @if (patient.primaryPhone) {
                          <span class="tl-mono" dir="ltr">{{ patient.primaryPhone }}</span>
                        }
                        @if (patient.primaryEmail) {
                          <span class="tl-mono tl-muted" dir="ltr">{{ patient.primaryEmail }}</span>
                        }
                        @if (!patient.primaryPhone && !patient.primaryEmail) {
                          <span>{{ notRecorded() }}</span>
                        }
                      </span>
                    </td>
                    <td>{{ birthDateOf(patient) }}</td>
                    <td>
                      @if (patient.isActive === false) {
                        <span class="tl-badge tl-badge-pill">{{ 'clinicAdmin.common.inactive' | t }}</span>
                      } @else {
                        <span class="tl-badge tl-badge-pill tl-badge-verified">{{ 'clinicAdmin.common.active' | t }}</span>
                      }
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
          <p class="tl-muted tl-table-note" data-testid="clinic-patients-limit-note">
            {{ 'clinic.patients.limitNote' | t }}
          </p>
          <p class="tl-muted tl-table-note" data-testid="clinic-patients-not-recorded-note">
            {{ 'clinic.patients.notRecorded' | t }}
          </p>
        }
      </section>

      <fe-tl-drawer
        [open]="showCreateDrawer()"
        [title]="'clinicAdmin.patients.createTitle' | t"
        [subtitle]="'clinicAdmin.patients.drawerHint' | t"
        (closed)="closeCreateDrawer()"
      >
        <form
          id="clinic-patient-form"
          class="tl-form-grid"
          [formGroup]="form"
          (ngSubmit)="savePatient()"
          data-testid="clinic-patient-create-modal"
        >
          @if (saveFailed()) {
            <div class="tl-form-error" role="alert" data-testid="clinic-patient-create-error">
              {{ 'clinicAdmin.patients.createFailed' | t }}
              @if (createRequestId()) {
                <span class="tl-muted">({{ createRequestId() }})</span>
              }
            </div>
          }

          <p class="tl-note tl-field--full">
            {{ isArabic()
              ? 'تسجيل سريع: يكفي إدخال الاسم الأول ورقم الهاتف فقط، ويمكنك بعدها توليد كود لحظي للمريض ليكمل باقي بياناته وتاريخه الطبي عبر الجوال.'
              : 'Quick Register: First Name and Phone are enough — the patient can fill the rest via the Intake Portal.' }}
          </p>

          <div class="tl-drawer-top-split">
            <div class="tl-field">
              <span class="tl-label">{{ 'clinicAdmin.patients.profileImage' | t }}</span>
              <label
                class="tl-file-drop"
                (dragover)="$event.preventDefault()"
                (drop)="onImageDrop($event)"
              >
                @if (form.controls.profileImageUrl.value) {
                  <img
                    class="tl-file-preview"
                    [src]="form.controls.profileImageUrl.value"
                    [alt]="'clinicAdmin.patients.profileImage' | t"
                  />
                } @else {
                  <span class="tl-file-icon" aria-hidden="true">+</span>
                }
                <span>{{ 'clinicAdmin.common.browse' | t }}</span>
                <span class="tl-muted">{{ 'clinicAdmin.common.orDrop' | t }}</span>
                <input
                  class="tl-sr-only"
                  type="file"
                  accept="image/*"
                  (change)="onImageSelected($event)"
                />
              </label>
              @if (form.controls.profileImageUrl.value) {
                <button type="button" class="tl-link" (click)="clearImage()">
                  {{ 'clinicAdmin.common.removeImage' | t }}
                </button>
              }
              @if (imageError(); as message) {
                <p class="tl-field-error">{{ message | t }}</p>
              }
            </div>

            <div class="tl-drawer-stack">
              <label class="tl-field">
                <span class="tl-label">
                  {{ 'clinicAdmin.patients.firstName' | t }} <span class="tl-required">*</span>
                </span>
                <input
                  class="tl-input"
                  type="text"
                  formControlName="givenName"
                  autocomplete="given-name"
                  (input)="onPatientFieldChange()"
                />
                @if (showRequired('givenName')) {
                  <span class="tl-field-error">{{ 'clinicAdmin.common.required' | t }}</span>
                }
              </label>

              <label class="tl-field">
                <span class="tl-label">{{ 'clinicAdmin.patients.lastName' | t }}</span>
                <input
                  class="tl-input"
                  type="text"
                  formControlName="familyName"
                  autocomplete="family-name"
                  (input)="onPatientFieldChange()"
                />
              </label>

              <label class="tl-field">
                <span class="tl-label">{{ 'clinicAdmin.patients.email' | t }}</span>
                <input class="tl-input" type="email" dir="ltr" formControlName="email" autocomplete="email" />
                @if (form.controls.email.touched && form.controls.email.invalid) {
                  <span class="tl-field-error">{{ 'clinicAdmin.patients.emailHint' | t }}</span>
                }
              </label>
            </div>
          </div>

          <label class="tl-field">
            <span class="tl-label">{{ 'clinicAdmin.patients.gender' | t }}</span>
            <select class="tl-select" formControlName="gender">
              <option value="">{{ 'clinicAdmin.patients.chooseGender' | t }}</option>
              <option value="MALE">{{ 'clinicAdmin.patients.male' | t }}</option>
              <option value="FEMALE">{{ 'clinicAdmin.patients.female' | t }}</option>
            </select>
          </label>

          <label class="tl-field">
            <span class="tl-label">{{ 'clinicAdmin.patients.dateOfBirth' | t }}</span>
            <input
              class="tl-input"
              type="date"
              formControlName="dateOfBirth"
              (input)="onPatientFieldChange()"
            />
          </label>

          <label class="tl-field">
            <span class="tl-label">
              {{ 'clinicAdmin.patients.phone' | t }} <span class="tl-required">*</span>
            </span>
            <input
              class="tl-input"
              type="tel"
              dir="ltr"
              formControlName="phone"
              autocomplete="tel"
              (input)="onPatientFieldChange()"
            />
            @if (showRequired('phone')) {
              <span class="tl-field-error">{{ 'clinicAdmin.common.required' | t }}</span>
            }
          </label>

          <label class="tl-field">
            <span class="tl-label">{{ 'clinicAdmin.patients.cnic' | t }}</span>
            <input class="tl-input" type="text" dir="ltr" formControlName="cnic" />
          </label>

          <div class="tl-field tl-field--full tl-switch-row">
            <div>
              <span class="tl-label">{{ 'clinicAdmin.patients.status' | t }}</span>
              <p class="tl-muted">
                {{ form.controls.isActive.value ? ('clinicAdmin.common.active' | t) : ('clinicAdmin.common.inactive' | t) }}
              </p>
            </div>
            <label class="tl-switch">
              <input type="checkbox" formControlName="isActive" />
              <span class="tl-slider"></span>
            </label>
          </div>

          <h3 class="tl-drawer-section-title">{{ 'clinicAdmin.common.otherDetail' | t }}</h3>

          <label class="tl-field tl-field--full">
            <span class="tl-label">{{ 'clinicAdmin.patients.address' | t }}</span>
            <textarea class="tl-input" rows="2" formControlName="addressLine"></textarea>
          </label>

          <label class="tl-field">
            <span class="tl-label">{{ 'clinicAdmin.patients.country' | t }}</span>
            <input class="tl-input" type="text" formControlName="country" />
          </label>

          <label class="tl-field">
            <span class="tl-label">{{ 'clinicAdmin.patients.state' | t }}</span>
            <input class="tl-input" type="text" formControlName="state" />
          </label>

          <label class="tl-field">
            <span class="tl-label">{{ 'clinicAdmin.patients.city' | t }}</span>
            <input class="tl-input" type="text" formControlName="city" />
          </label>

          <label class="tl-field">
            <span class="tl-label">{{ 'clinicAdmin.patients.postal' | t }}</span>
            <input class="tl-input" type="text" dir="ltr" formControlName="postalCode" />
          </label>

          @if (duplicateCheckFailed()) {
            <p class="tl-note tl-field--full" role="status" data-testid="clinic-patient-duplicate-unavailable">
              {{ 'clinic.patients.duplicateUnavailable' | t }}
            </p>
          }

          @if (duplicateCandidates().length > 0) {
            <div class="tl-duplicate tl-field--full" data-testid="clinic-patient-duplicate-warning">
              <p class="tl-duplicate-title">{{ 'clinicAdmin.patients.duplicateTitle' | t }}</p>
              <p class="tl-muted">{{ 'clinicAdmin.patients.duplicateBody' | t }}</p>
              <ul class="tl-duplicate-list">
                @for (candidate of duplicateCandidates(); track candidate.patientId) {
                  <li>
                    <span>{{ candidate.displayName }}</span>
                    <span class="tl-mono" dir="ltr">{{ candidate.patientNo }}</span>
                    <span class="tl-muted">
                      {{ 'clinic.patients.duplicateScore' | t }}: {{ format.integer(candidate.score) }}
                    </span>
                    <span class="tl-muted tl-mono" dir="ltr">{{ candidate.matchedOn }}</span>
                  </li>
                }
              </ul>
            </div>
          }
        </form>

        <div drawerFooter class="tl-drawer-footer">
          <button type="button" class="tl-btn tl-btn-gray" (click)="closeCreateDrawer()">
            {{ 'clinicAdmin.common.cancel' | t }}
          </button>
          <button
            type="submit"
            form="clinic-patient-form"
            class="tl-btn tl-btn-primary"
            [disabled]="saving()"
          >
            {{ saving() ? ('clinicAdmin.common.saving' | t) : ('clinicAdmin.common.save' | t) }}
          </button>
        </div>
      </fe-tl-drawer>

      <fe-tl-drawer
        [open]="showIntakeQrDrawer()"
        [title]="isArabic() ? 'رمز QR بوابة تعبئة المرضى' : 'Clinic Patient Intake QR'"
        [subtitle]="isArabic() ? 'قم بطباعة أو تحميل الرمز لتعليقه في استقبال العيادة' : 'Print or download this clinic-specific QR poster for patient check-in'"
        (closed)="closeIntakeQrDrawer()"
      >
        <div class="tl-qr-drawer" data-testid="clinic-intake-qr-drawer">
          @if (qrLoading()) {
            <p class="tl-muted">{{ 'state.loading' | t }}</p>
          } @else if (qrError()) {
            <p class="tl-form-error">{{ qrError() }}</p>
          } @else if (clinicQrInfo(); as info) {
            <div class="tl-qr-preview">
              @if (clinicQrSvg(); as svg) {
                <svg
                  class="tl-qr-svg"
                  [attr.viewBox]="'0 0 ' + svg.viewBoxSize + ' ' + svg.viewBoxSize"
                  width="220"
                  height="220"
                  shape-rendering="crispEdges"
                  role="img"
                  [attr.aria-label]="'QR for ' + info.clinicName"
                >
                  <rect [attr.width]="svg.viewBoxSize" [attr.height]="svg.viewBoxSize" fill="#ffffff" />
                  <path [attr.d]="svg.path" fill="#0f172a" />
                </svg>
              }
              <strong>{{ info.clinicName }}</strong>
              <span class="tl-mono tl-muted">{{ info.intakePortalKey }}</span>
            </div>

            <label class="tl-field">
              <span class="tl-label">{{ isArabic() ? 'رابط البوابة (Base URL)' : 'Portal Base URL' }}</span>
              <input
                class="tl-input"
                type="url"
                dir="ltr"
                [value]="intakeBaseUrl()"
                (input)="onIntakeBaseUrlInput($event)"
              />
            </label>

            <div class="tl-field">
              <span class="tl-label">{{ isArabic() ? 'رابط العيادة المباشر' : 'Clinic Intake Link' }}</span>
              <div class="tl-qr-link-row">
                <input class="tl-input tl-mono" type="text" dir="ltr" readonly [value]="clinicQrUrl()" />
                <button type="button" class="tl-btn tl-btn-gray" (click)="copyText(clinicQrUrl())">
                  {{ copiedText() ? (isArabic() ? 'تم النسخ' : 'Copied') : (isArabic() ? 'نسخ' : 'Copy') }}
                </button>
              </div>
            </div>

            <div class="tl-qr-actions">
              <button type="button" class="tl-btn tl-btn-primary" (click)="printClinicQr()">
                {{ isArabic() ? 'طباعة ملصق الـ QR' : 'Print QR Poster' }}
              </button>
              <button type="button" class="tl-btn tl-btn-gray" (click)="downloadClinicQrSvg()">
                {{ isArabic() ? 'تحميل SVG' : 'Download SVG' }}
              </button>
              <button type="button" class="tl-btn tl-btn-gray" (click)="openPortalInTab()">
                {{ isArabic() ? 'فتح البوابة' : 'Open Portal' }}
              </button>
            </div>
          }
        </div>
      </fe-tl-drawer>
    </div>
  `,
  styles: [
    `
      .tl-page-head {
        display: flex;
        align-items: flex-start;
        justify-content: space-between;
        gap: var(--fe-space-4);
        margin-block-end: var(--fe-space-4);
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
      .tl-search {
        display: flex;
        align-items: center;
        gap: var(--fe-space-2);
        min-inline-size: 22rem;
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
        inline-size: 100%;
        background: transparent;
        color: var(--ink);
      }
      .tl-search-hint {
        margin: 0;
        padding: 0 var(--fe-space-4) var(--fe-space-3);
        color: var(--mute);
        font-size: var(--fe-text-xs);
      }
      .patient-link {
        color: var(--pri);
        font-weight: 600;
        text-decoration: none;
      }
      .patient-link:hover {
        text-decoration: underline;
      }
      .tl-person {
        display: inline-flex;
        align-items: center;
        gap: var(--fe-space-2);
      }
      .tl-person-text {
        display: flex;
        flex-direction: column;
        gap: 2px;
      }
      .tl-person-meta {
        color: var(--mute);
        font-size: var(--fe-text-xs);
      }
      .tl-avatar {
        inline-size: 2.25rem;
        block-size: 2.25rem;
        border-radius: 999px;
        object-fit: cover;
        flex: none;
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
      .tl-stack {
        display: flex;
        flex-direction: column;
        gap: 2px;
      }
      .tl-table-note {
        margin: var(--fe-space-2) var(--fe-space-4) 0;
        font-size: var(--fe-text-xs);
      }
      .tl-file-drop {
        position: relative;
      }
      .tl-field--full {
        grid-column: 1 / -1;
      }
      .tl-required {
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
      .tl-field-error {
        color: var(--bad);
        font-size: var(--fe-text-xs);
      }
      .tl-switch-row {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: var(--fe-space-4);
      }
      .tl-switch-row p {
        margin: 0;
      }
      .tl-duplicate {
        border: 1px solid var(--accent-yellow-line);
        background: var(--accent-yellow-soft);
        border-radius: var(--fe-radius);
        padding: var(--fe-space-3);
      }
      .tl-duplicate-title {
        margin: 0 0 var(--fe-space-1);
        color: var(--accent-yellow-ink);
        font-weight: 700;
      }
      .tl-duplicate-list {
        margin: var(--fe-space-2) 0 0;
        padding-inline-start: var(--fe-space-4);
        display: grid;
        gap: var(--fe-space-1);
        font-size: var(--fe-text-sm);
      }
      .tl-note {
        margin: 0;
        padding: var(--fe-space-2) var(--fe-space-3);
        border-radius: var(--fe-radius);
        background: var(--pri-soft);
        color: var(--ink-soft);
        font-size: var(--fe-text-sm);
      }
      .tl-link {
        align-self: flex-start;
        border: 0;
        background: none;
        color: var(--pri);
        padding: 0;
        cursor: pointer;
        font-size: var(--fe-text-xs);
      }
      .tl-head-actions {
        display: flex;
        align-items: center;
        gap: var(--fe-space-2);
        flex-wrap: wrap;
      }
      .tl-quick-banner {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: var(--fe-space-3);
        flex-wrap: wrap;
        padding: var(--fe-space-3) var(--fe-space-4);
        margin-block-end: var(--fe-space-3);
        border-radius: var(--fe-radius);
        background: var(--pri-soft);
        border: 1px solid var(--pri);
      }
      .tl-quick-code-box {
        display: flex;
        align-items: center;
        gap: var(--fe-space-2);
        margin-block-start: var(--fe-space-1);
      }
      .tl-quick-code {
        font-size: 1.15rem;
        padding: 2px 10px;
        border-radius: 6px;
        background: var(--surf);
        border: 1px solid var(--line);
      }
      .tl-quick-banner-actions {
        display: flex;
        align-items: center;
        gap: var(--fe-space-2);
        flex-wrap: wrap;
      }
      .tl-btn-xs {
        padding: 4px 10px;
        font-size: var(--fe-text-xs);
      }
      .tl-qr-drawer {
        display: flex;
        flex-direction: column;
        gap: var(--fe-space-4);
      }
      .tl-qr-preview {
        display: flex;
        flex-direction: column;
        align-items: center;
        gap: var(--fe-space-2);
        padding: var(--fe-space-4);
        border-radius: var(--fe-radius);
        background: var(--surf2);
        border: 1px solid var(--line);
      }
      .tl-qr-svg {
        background: #ffffff;
        padding: 8px;
        border-radius: 10px;
        border: 1px solid var(--line);
      }
      .tl-qr-link-row {
        display: flex;
        gap: var(--fe-space-2);
      }
      .tl-qr-link-row .tl-input {
        flex: 1;
      }
      .tl-qr-actions {
        display: flex;
        flex-wrap: wrap;
        gap: var(--fe-space-2);
      }
      @media (max-width: 720px) {
        .tl-page-head,
        .tl-toolbar {
          flex-direction: column;
          align-items: stretch;
        }
        .tl-search {
          min-inline-size: 0;
        }
      }
    `,
  ],
})
export class ClinicPatientsComponent implements OnInit {
  private readonly api = inject(ClinicPatientApi);
  private readonly i18n = inject(I18nService);
  private readonly fb = inject(FormBuilder);
  protected readonly format = inject(FormatService);
  protected readonly projects = inject(ProjectContextService);

  protected readonly patients = new ResourceStore<readonly ClinicPatient[]>();
  protected readonly term = signal('');

  protected readonly hasTerm = computed(() => this.term().trim() !== '');
  protected readonly rows = computed<readonly ClinicPatient[]>(() => this.patients.value() ?? []);

  protected readonly emptyTitle = computed(() =>
    this.hasTerm()
      ? this.i18n.t('clinic.patients.noResults.title')
      : this.i18n.t('clinic.patients.empty.title'),
  );
  protected readonly emptyBody = computed(() =>
    this.hasTerm()
      ? this.i18n.t('clinic.patients.noResults.body')
      : this.i18n.t('clinic.patients.empty.body'),
  );

  protected readonly showCreateDrawer = signal(false);
  protected readonly saving = signal(false);
  protected readonly saveFailed = signal(false);
  protected readonly createRequestId = signal<string | null>(null);
  protected readonly duplicateCandidates = signal<readonly ClinicDuplicateCandidate[]>([]);
  protected readonly duplicateCheckFailed = signal(false);
  protected readonly imageError = signal<string | null>(null);
  protected readonly isArabic = computed(() => this.i18n.locale() === 'ar');

  protected readonly showIntakeQrDrawer = signal(false);
  protected readonly qrLoading = signal(false);
  protected readonly qrError = signal<string | null>(null);
  protected readonly clinicQrInfo = signal<ClinicIntakeQrInfo | null>(null);
  protected readonly intakeBaseUrl = signal(getSavedIntakePortalBaseUrl());
  protected readonly copiedText = signal(false);
  protected readonly clinicQrUrl = computed(() => {
    const info = this.clinicQrInfo();
    return info?.intakePortalKey ? buildIntakePortalUrl(info.intakePortalKey, this.intakeBaseUrl()) : '';
  });
  protected readonly clinicQrSvg = computed(() => {
    const url = this.clinicQrUrl();
    return url ? generateQrSvgParts(url) : null;
  });

  protected readonly quickCreatedPatient = signal<ClinicPatient | null>(null);
  protected readonly quickGeneratedCode = signal<GeneratedIntakeCode | null>(null);
  protected readonly generatingQuickCode = signal(false);

  protected readonly form = this.fb.nonNullable.group({
    givenName: ['', Validators.required],
    familyName: [''],
    email: ['', Validators.email],
    gender: [''],
    dateOfBirth: [''],
    phone: ['', Validators.required],
    cnic: [''],
    profileImageUrl: [''],
    addressLine: [''],
    country: [''],
    state: [''],
    city: [''],
    postalCode: [''],
    isActive: [true],
  });

  /** The field values the last duplicate check was made for. */
  private duplicateKey = '';

  constructor() {
    this.i18n.register({
      clinic: clinicStrings as Namespace,
      clinicAdmin: clinicAdminStrings as Namespace,
    });
  }

  ngOnInit(): void {
    void this.load();
  }

  protected async load(): Promise<void> {
    const term = this.term().trim();
    await this.patients.load(() =>
      this.api.patients({
        projectId: this.projects.selectedProjectId() ?? undefined,
        q: term === '' ? undefined : term,
      }),
    );
  }

  protected async reload(): Promise<void> {
    await this.load();
  }

  protected onSearch(event: Event): void {
    this.term.set((event.target as HTMLInputElement).value);
    void this.load();
  }

  protected clearSearch(): void {
    this.term.set('');
    void this.load();
  }

  protected errorRequestId(): string | null {
    return this.patients.error()?.requestId ?? null;
  }

  protected nameOf(patient: ClinicPatient): string {
    const display = patient.displayName?.trim();
    if (display) return display;
    const parts = [patient.givenName, patient.familyName]
      .map((part) => part?.trim() ?? '')
      .filter((part) => part !== '');
    return parts.length > 0 ? parts.join(' ') : this.notRecorded();
  }

  protected initials(patient: ClinicPatient): string {
    const first = patient.givenName?.trim().charAt(0) ?? patient.displayName?.trim().charAt(0) ?? '';
    const last = patient.familyName?.trim().charAt(0) ?? '';
    return (first + last).toUpperCase() || '?';
  }

  /** A date of birth is a day on a calendar, so it is never shifted by a timezone. */
  protected birthDateOf(patient: ClinicPatient): string {
    const value = patient.dateOfBirth?.trim();
    if (!value) return this.notRecorded();
    return this.format.calendarDate(value);
  }

  protected notRecorded(): string {
    return this.i18n.t('clinic.patients.notRecordedShort');
  }

  protected showRequired(controlName: string): boolean {
    const control = this.form.get(controlName);
    return !!control && control.invalid && (control.dirty || control.touched);
  }

  // -- registering a patient ------------------------------------------------

  protected openCreateDrawer(): void {
    this.showCreateDrawer.set(true);
    this.saveFailed.set(false);
    this.createRequestId.set(null);
    this.duplicateCandidates.set([]);
    this.duplicateCheckFailed.set(false);
    this.imageError.set(null);
    this.duplicateKey = '';
    this.form.reset({
      givenName: '',
      familyName: '',
      email: '',
      gender: '',
      dateOfBirth: '',
      phone: '',
      cnic: '',
      profileImageUrl: '',
      addressLine: '',
      country: '',
      state: '',
      city: '',
      postalCode: '',
      isActive: true,
    });
  }

  protected closeCreateDrawer(): void {
    this.showCreateDrawer.set(false);
  }

  protected onImageSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (file) this.readImage(file);
  }

  protected onImageDrop(event: DragEvent): void {
    event.preventDefault();
    const file = event.dataTransfer?.files?.[0];
    if (file) this.readImage(file);
  }

  protected clearImage(): void {
    this.form.controls.profileImageUrl.setValue('');
    this.imageError.set(null);
  }

  private readImage(file: File): void {
    this.imageError.set(null);
    if (!file.type.startsWith('image/')) {
      this.imageError.set('clinicAdmin.common.imageType');
      return;
    }
    if (file.size > 1_000_000) {
      this.imageError.set('clinicAdmin.common.imageTooLarge');
      return;
    }
    const reader = new FileReader();
    reader.onload = () => {
      const dataUrl = typeof reader.result === 'string' ? reader.result : '';
      this.form.controls.profileImageUrl.setValue(dataUrl);
    };
    reader.onerror = () => this.imageError.set('clinicAdmin.common.imageType');
    reader.readAsDataURL(file);
  }

  /**
   * The duplicate check runs on the server and only once per set of field values:
   * a candidate list is evidence for a human, and re-fetching it per keystroke is
   * noise. Nothing here merges.
   */
  protected onPatientFieldChange(): void {
    const value = this.form.getRawValue();
    const givenName = value.givenName.trim();
    const familyName = value.familyName.trim();
    const phone = value.phone.trim();
    const dateOfBirth = value.dateOfBirth.trim();

    if (givenName === '' || phone === '') {
      this.duplicateCandidates.set([]);
      this.duplicateCheckFailed.set(false);
      this.duplicateKey = '';
      return;
    }

    const key = `${givenName}|${familyName}|${phone}|${dateOfBirth}`;
    if (key === this.duplicateKey) return;
    this.duplicateKey = key;
    void this.checkDuplicates(givenName, familyName, phone, dateOfBirth);
  }

  private async checkDuplicates(
    givenName: string,
    familyName: string,
    phone: string,
    dateOfBirth: string,
  ): Promise<void> {
    try {
      const candidates = await this.api.duplicateCandidates({
        givenName,
        familyName: familyName === '' ? undefined : familyName,
        phone,
        dateOfBirth: dateOfBirth === '' ? undefined : dateOfBirth,
        projectId: this.projects.selectedProjectId() ?? undefined,
      });
      this.duplicateCandidates.set(candidates ?? []);
      this.duplicateCheckFailed.set(false);
    } catch {
      // A failed check is said out loud. An empty list would read as "there is no
      // duplicate", which is the one thing a failed check does not know.
      this.duplicateCandidates.set([]);
      this.duplicateCheckFailed.set(true);
    }
  }

  protected async savePatient(): Promise<void> {
    this.form.markAllAsTouched();
    if (this.form.invalid || this.saving()) return;
    const value = this.form.getRawValue();
    this.saving.set(true);
    this.saveFailed.set(false);
    this.createRequestId.set(null);
    try {
      const created = await this.api.createPatient(
        {
          givenName: value.givenName.trim(),
          familyName: value.familyName.trim() === '' ? undefined : value.familyName.trim(),
          gender: value.gender === '' ? undefined : value.gender,
          dateOfBirth: value.dateOfBirth === '' ? undefined : value.dateOfBirth,
          phone: value.phone.trim(),
          email: value.email.trim() === '' ? undefined : value.email.trim(),
          cnic: value.cnic.trim() === '' ? undefined : value.cnic.trim(),
          profileImageUrl:
            value.profileImageUrl === '' ? undefined : value.profileImageUrl,
          addressLine: value.addressLine.trim() === '' ? undefined : value.addressLine.trim(),
          country: value.country.trim() === '' ? undefined : value.country.trim(),
          state: value.state.trim() === '' ? undefined : value.state.trim(),
          city: value.city.trim() === '' ? undefined : value.city.trim(),
          postalCode: value.postalCode.trim() === '' ? undefined : value.postalCode.trim(),
          isActive: value.isActive,
          quickRegister: true,
        },
        {
          projectId: this.projects.selectedProjectId() ?? undefined,
        },
      );
      this.quickCreatedPatient.set(created);
      this.quickGeneratedCode.set(null);
      this.closeCreateDrawer();
      await this.load();
    } catch (cause) {
      // The server's own message is never shown raw: the copy is translated, and the
      // request id is what support needs.
      this.saveFailed.set(true);
      this.createRequestId.set(cause instanceof ApiError ? cause.requestId : null);
    } finally {
      this.saving.set(false);
    }
  }

  protected async openIntakeQrDrawer(): Promise<void> {
    this.showIntakeQrDrawer.set(true);
    this.qrLoading.set(true);
    this.qrError.set(null);
    try {
      const info = await this.api.getClinicIntakeQr({
        projectId: this.projects.selectedProjectId() ?? undefined,
      });
      this.clinicQrInfo.set(info);
    } catch {
      this.qrError.set(this.isArabic() ? 'تعذر تحميل رمز QR العيادة.' : 'Could not load clinic intake QR.');
    } finally {
      this.qrLoading.set(false);
    }
  }

  protected closeIntakeQrDrawer(): void {
    this.showIntakeQrDrawer.set(false);
  }

  protected onIntakeBaseUrlInput(event: Event): void {
    const val = (event.target as HTMLInputElement).value;
    this.intakeBaseUrl.set(saveIntakePortalBaseUrl(val));
  }

  protected printClinicQr(): void {
    const info = this.clinicQrInfo();
    const url = this.clinicQrUrl();
    if (info && url) printIntakeQrPoster(info.clinicName, url);
  }

  protected downloadClinicQrSvg(): void {
    const info = this.clinicQrInfo();
    const url = this.clinicQrUrl();
    if (!info || !url) return;
    const svg = generateQrSvgString(url, 360);
    const blob = new Blob([svg], { type: 'image/svg+xml;charset=utf-8' });
    const objUrl = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = objUrl;
    a.download = `intake-qr-${info.clinicSlug || 'clinic'}.svg`;
    a.click();
    URL.revokeObjectURL(objUrl);
  }

  protected openPortalInTab(): void {
    const url = this.clinicQrUrl();
    if (url) window.open(url, '_blank', 'noopener');
  }

  protected async generateQuickCodeForCreated(patientId: string): Promise<void> {
    this.generatingQuickCode.set(true);
    try {
      const res = await this.api.generateIntakeCode(patientId, {
        projectId: this.projects.selectedProjectId() ?? undefined,
      });
      this.quickGeneratedCode.set(res);
    } finally {
      this.generatingQuickCode.set(false);
    }
  }

  protected dismissQuickBanner(): void {
    this.quickCreatedPatient.set(null);
    this.quickGeneratedCode.set(null);
  }

  protected copyText(val: string): void {
    navigator.clipboard.writeText(val).then(() => {
      this.copiedText.set(true);
      setTimeout(() => this.copiedText.set(false), 2000);
    });
  }
}
