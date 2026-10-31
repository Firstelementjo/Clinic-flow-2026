/**
 * `/app/clinic/patients/:id/odontogram` — a patient's dental chart & periodontal probing.
 *
 * ## Slice S4 Capabilities
 * - Canonical FDI / ISO 3950 storage (11-48 adult, 51-85 primary)
 * - Notation switcher at UI boundary: FDI, Universal, Palmer
 * - Separate views: Dental Chart vs Periodontal Probing Chart
 * - Chart layers: All, Baseline Status, Planned Treatment, Completed Procedures
 * - 6-site periodontal probing (MB, B, DB, ML, L, DL) with live derived CAL (PD + GM)
 * - Deep pocket warnings (>3mm) with accessible fe-status-chip
 * - Safe interactive SVG / keyboard-first tooth matrix
 * - Append-only findings and corrections
 */
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  signal,
  untracked,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { map } from 'rxjs';
import { ProjectContextService } from '../../core/context/project-context.service';
import { FormatService } from '../../core/format/format.service';
import { I18nService } from '../../core/i18n/i18n.service';
import type { Namespace } from '../../core/i18n/i18n.types';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { ResourceStore } from '../../core/state/resource';
import { ClinicRbacService } from '../../core/auth/clinic-rbac';
import { AsyncStateComponent } from '../../ui/async-state.component';
import { PageHeaderComponent } from '../../ui/page-header.component';
import { StatusChipComponent } from '../../ui/status-chip.component';
import {
  ClinicPatientApi,
  type ClinicChartEntry,
  type ClinicFinding,
  type PeriodontalExamHeader,
  type PeriodontalMeasurement,
  type PeriodontalMeasurementInput,
} from './clinic-patient.api';
import { clinicStrings } from './clinic.strings';

export type ToothNotation = 'FDI' | 'UNIVERSAL' | 'PALMER';
export type ChartLayer = 'ALL' | 'STATUS' | 'PLAN' | 'COMPLETED';
export type ActiveView = 'CHART' | 'PERIODONTAL';

/** Canonical adult FDI teeth in quadrant order */
const ADULT_UPPER_RIGHT = [18, 17, 16, 15, 14, 13, 12, 11];
const ADULT_UPPER_LEFT = [21, 22, 23, 24, 25, 26, 27, 28];
const ADULT_LOWER_RIGHT = [48, 47, 46, 45, 44, 43, 42, 41];
const ADULT_LOWER_LEFT = [31, 32, 33, 34, 35, 36, 37, 38];

function convertToothNotation(fdi: number, notation: ToothNotation): string {
  if (notation === 'FDI') return String(fdi);
  const q = Math.floor(fdi / 10);
  const pos = fdi % 10;
  if (notation === 'UNIVERSAL') {
    if (q === 1) return String(9 - pos);
    if (q === 2) return String(pos + 8);
    if (q === 3) return String(25 - pos);
    if (q === 4) return String(pos + 24);
    if (q === 5) return String.fromCharCode(65 + (5 - pos));
    if (q === 6) return String.fromCharCode(70 + (pos - 1));
    if (q === 7) return String.fromCharCode(75 + (5 - pos));
    if (q === 8) return String.fromCharCode(80 + (pos - 1));
    return String(fdi);
  }
  if (notation === 'PALMER') {
    const quad = q === 1 || q === 5 ? 'UR' : q === 2 || q === 6 ? 'UL' : q === 3 || q === 7 ? 'LL' : 'LR';
    const index = q >= 5 ? String.fromCharCode(65 + pos - 1) : String(pos);
    return `${quad}${index}`;
  }
  return String(fdi);
}

@Component({
  selector: 'fe-clinic-odontogram-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    AsyncStateComponent,
    FormsModule,
    PageHeaderComponent,
    RouterLink,
    StatusChipComponent,
    TranslatePipe,
  ],
  template: `
    <div class="fe-page" data-testid="clinic-odontogram">
      <fe-page-header [title]="'clinic.odontogram.title'" [description]="'clinic.odontogram.description'">
        <div class="header-actions">
          <a
            class="fe-btn fe-btn--ghost"
            [routerLink]="patientId() ? ['/app/clinic/patients', patientId()] : ['/app/clinic/patients']"
          >
            {{ 'clinic.odontogram.back' | t }}
          </a>
          @if (patientId() !== null && canWriteClinical()) {
            <button type="button" class="fe-btn fe-btn--primary" (click)="isAddFindingOpen.set(true)">
              {{ 'clinic.odontogram.action.addFinding' | t }}
            </button>
            <button type="button" class="fe-btn fe-btn--ghost" (click)="openAddPerioModal()">
              {{ 'clinic.odontogram.action.addPerioExam' | t }}
            </button>
          }
        </div>
      </fe-page-header>

      @if (patientId() === null) {
        <p class="fe-sub" data-testid="clinic-odontogram-no-id">{{ 'clinic.patient.noId' | t }}</p>
      } @else {
        <!-- Primary View Navigation (Dental Chart vs Periodontal Probing) -->
        <nav class="chart-tabs" aria-label="Odontogram views">
          <button
            type="button"
            class="tab-btn"
            [class.tab-btn--active]="activeView() === 'CHART'"
            (click)="activeView.set('CHART')"
          >
            {{ 'clinic.odontogram.view.chart' | t }}
          </button>
          <button
            type="button"
            class="tab-btn"
            [class.tab-btn--active]="activeView() === 'PERIODONTAL'"
            (click)="switchViewToPeriodontal()"
          >
            {{ 'clinic.odontogram.view.periodontal' | t }}
          </button>
        </nav>

        <!-- Toolbars: Notation Switcher & Layer Filter -->
        <div class="toolbar-row">
          <div class="toolbar-group">
            <span class="fe-eyebrow">{{ 'clinic.odontogram.finding.tooth' | t }}:</span>
            <div class="segmented-control" role="group">
              <button
                type="button"
                class="seg-btn"
                [class.seg-btn--active]="selectedNotation() === 'FDI'"
                (click)="selectedNotation.set('FDI')"
              >
                {{ 'clinic.odontogram.notation.fdi' | t }}
              </button>
              <button
                type="button"
                class="seg-btn"
                [class.seg-btn--active]="selectedNotation() === 'UNIVERSAL'"
                (click)="selectedNotation.set('UNIVERSAL')"
              >
                {{ 'clinic.odontogram.notation.universal' | t }}
              </button>
              <button
                type="button"
                class="seg-btn"
                [class.seg-btn--active]="selectedNotation() === 'PALMER'"
                (click)="selectedNotation.set('PALMER')"
              >
                {{ 'clinic.odontogram.notation.palmer' | t }}
              </button>
            </div>
          </div>

          @if (activeView() === 'CHART') {
            <div class="toolbar-group">
              <span class="fe-eyebrow">{{ 'clinic.odontogram.finding.state' | t }}:</span>
              <div class="segmented-control" role="group">
                <button
                  type="button"
                  class="seg-btn"
                  [class.seg-btn--active]="selectedLayer() === 'ALL'"
                  (click)="selectedLayer.set('ALL')"
                >
                  {{ 'clinic.odontogram.layer.all' | t }}
                </button>
                <button
                  type="button"
                  class="seg-btn"
                  [class.seg-btn--active]="selectedLayer() === 'STATUS'"
                  (click)="selectedLayer.set('STATUS')"
                >
                  {{ 'clinic.odontogram.layer.status' | t }}
                </button>
                <button
                  type="button"
                  class="seg-btn"
                  [class.seg-btn--active]="selectedLayer() === 'PLAN'"
                  (click)="selectedLayer.set('PLAN')"
                >
                  {{ 'clinic.odontogram.layer.plan' | t }}
                </button>
                <button
                  type="button"
                  class="seg-btn"
                  [class.seg-btn--active]="selectedLayer() === 'COMPLETED'"
                  (click)="selectedLayer.set('COMPLETED')"
                >
                  {{ 'clinic.odontogram.layer.completed' | t }}
                </button>
              </div>
            </div>
          }
        </div>

        @if (activeView() === 'CHART') {
          <!-- Dental Arch Interactive Matrix -->
          <section class="fe-card arch-card" aria-label="Dental Arch Matrix">
            <div class="arch-section">
              <div class="arch-quadrant">
                @for (tooth of adultUpperRight; track tooth) {
                  <button
                    type="button"
                    class="tooth-cell"
                    [class.tooth-cell--selected]="openTooth() === tooth"
                    [class.tooth-cell--has-finding]="hasFinding(tooth)"
                    (click)="toggleTooth(tooth)"
                    [attr.aria-label]="formatTooth(tooth)"
                  >
                    <span class="tooth-num fe-mono" dir="ltr">{{ formatTooth(tooth) }}</span>
                    <span class="tooth-badge" [class]="toothStatusClass(tooth)"></span>
                  </button>
                }
              </div>
              <div class="arch-divider"></div>
              <div class="arch-quadrant">
                @for (tooth of adultUpperLeft; track tooth) {
                  <button
                    type="button"
                    class="tooth-cell"
                    [class.tooth-cell--selected]="openTooth() === tooth"
                    [class.tooth-cell--has-finding]="hasFinding(tooth)"
                    (click)="toggleTooth(tooth)"
                    [attr.aria-label]="formatTooth(tooth)"
                  >
                    <span class="tooth-num fe-mono" dir="ltr">{{ formatTooth(tooth) }}</span>
                    <span class="tooth-badge" [class]="toothStatusClass(tooth)"></span>
                  </button>
                }
              </div>
            </div>
            <div class="arch-mid-line"></div>
            <div class="arch-section">
              <div class="arch-quadrant">
                @for (tooth of adultLowerRight; track tooth) {
                  <button
                    type="button"
                    class="tooth-cell"
                    [class.tooth-cell--selected]="openTooth() === tooth"
                    [class.tooth-cell--has-finding]="hasFinding(tooth)"
                    (click)="toggleTooth(tooth)"
                    [attr.aria-label]="formatTooth(tooth)"
                  >
                    <span class="tooth-num fe-mono" dir="ltr">{{ formatTooth(tooth) }}</span>
                    <span class="tooth-badge" [class]="toothStatusClass(tooth)"></span>
                  </button>
                }
              </div>
              <div class="arch-divider"></div>
              <div class="arch-quadrant">
                @for (tooth of adultLowerLeft; track tooth) {
                  <button
                    type="button"
                    class="tooth-cell"
                    [class.tooth-cell--selected]="openTooth() === tooth"
                    [class.tooth-cell--has-finding]="hasFinding(tooth)"
                    (click)="toggleTooth(tooth)"
                    [attr.aria-label]="formatTooth(tooth)"
                  >
                    <span class="tooth-num fe-mono" dir="ltr">{{ formatTooth(tooth) }}</span>
                    <span class="tooth-badge" [class]="toothStatusClass(tooth)"></span>
                  </button>
                }
              </div>
            </div>
          </section>

          <!-- Primary Dental Chart Table -->
          <section class="fe-card" [attr.aria-labelledby]="'clinic-chart-title'">
            <h3 id="clinic-chart-title">{{ 'clinic.odontogram.title' | t }}</h3>

            <ul class="notes">
              <li>{{ 'clinic.odontogram.appendOnly' | t }}</li>
              <li>{{ 'clinic.odontogram.noNotation' | t }}</li>
            </ul>

            <fe-async-state
              [loading]="chart.loading() && chart.value() === null"
              [empty]="chart.value() !== null && filteredEntries().length === 0"
              [error]="chart.error()"
              [hasValue]="chart.value() !== null"
              [loadingLabel]="'state.loading' | t"
              [loadingHint]="'state.loadingHint' | t"
              [emptyTitle]="'clinic.odontogram.empty.title' | t"
              [emptyBody]="'clinic.odontogram.empty.body' | t"
              [errorTitle]="'clinic.error.denied.title' | t"
              [errorBody]="'clinic.error.denied.body' | t"
              [requestId]="chartErrorRequestId()"
              [requestIdLabel]="'state.requestId' | t"
              [retryLabel]="'clinic.retry' | t"
              (retry)="reloadChart()"
            />

            @if (filteredEntries().length > 0) {
              <table class="fe-table" data-testid="clinic-chart">
                <caption class="fe-sr">{{ 'clinic.odontogram.title' | t }}</caption>
                <thead>
                  <tr>
                    <th scope="col">{{ 'clinic.odontogram.col.tooth' | t }}</th>
                    <th scope="col">{{ 'clinic.odontogram.col.code' | t }}</th>
                    <th scope="col">{{ 'clinic.odontogram.col.scope' | t }}</th>
                    <th scope="col">{{ 'clinic.odontogram.col.surfaces' | t }}</th>
                    <th scope="col">{{ 'clinic.odontogram.col.recorded' | t }}</th>
                    <th scope="col">{{ 'clinic.odontogram.col.source' | t }}</th>
                  </tr>
                </thead>
                <tbody>
                  @for (entry of filteredEntries(); track entry.finding?.id ?? $index) {
                    <tr [class.superseded]="entry.superseded === true">
                      <th scope="row">
                        <span class="fe-mono" dir="ltr">{{ formatTooth(entry.finding?.toothFdi) }}</span>
                        @if (entry.superseded === true) {
                          <fe-status-chip tone="neutral">
                            {{ 'clinic.odontogram.superseded' | t }}
                          </fe-status-chip>
                        } @else {
                          <fe-status-chip tone="ok">{{ 'clinic.odontogram.standing' | t }}</fe-status-chip>
                        }
                      </th>
                      <td class="fe-mono" dir="ltr">
                        {{ entry.finding?.code ?? '—' }}
                        @if (entry.superseded !== true && entry.finding?.id) {
                          <button
                            type="button"
                            class="fe-btn fe-btn--ghost fe-btn--xs"
                            (click)="openCorrectModal(entry.finding!)"
                          >
                            {{ 'clinic.odontogram.action.correctFinding' | t }}
                          </button>
                        }
                      </td>
                      <td class="fe-mono" dir="ltr">{{ entry.finding?.scope ?? '—' }}</td>
                      <td>
                        @if ((entry.finding?.surfaces ?? []).length > 0) {
                          <span class="fe-mono" dir="ltr">
                            {{ (entry.finding?.surfaces ?? []).join(' ') }}
                          </span>
                        } @else {
                          <span class="unset">{{ 'clinic.odontogram.surfacesAbsent' | t }}</span>
                        }
                      </td>
                      <td class="fe-mono">{{ format.dateTime(entry.finding?.recordedAt) }}</td>
                      <td class="fe-mono" dir="ltr">{{ entry.finding?.source ?? '—' }}</td>
                    </tr>
                    <tr>
                      <td colspan="6">
                        @if (toothOf(entry); as toothFdi) {
                          <button
                            type="button"
                            class="fe-btn fe-btn--ghost"
                            [attr.aria-expanded]="openTooth() === toothFdi"
                            aria-controls="clinic-tooth-history"
                            (click)="toggleTooth(toothFdi)"
                          >
                            {{ 'clinic.odontogram.toothHistory.title' | t }}
                          </button>
                        }
                      </td>
                    </tr>
                  }
                </tbody>
              </table>
            }
          </section>

          @if (openTooth() !== null) {
            <section
              class="fe-card"
              id="clinic-tooth-history"
              [attr.aria-labelledby]="'clinic-tooth-history-title'"
              data-testid="clinic-tooth-history"
            >
              <h3 id="clinic-tooth-history-title">
                {{ 'clinic.odontogram.toothHistory.title' | t }}
                <span class="fe-mono" dir="ltr">{{ formatTooth(openTooth()!) }}</span>
              </h3>
              <p class="fe-sub">{{ 'clinic.odontogram.toothHistory.hint' | t }}</p>

              <fe-async-state
                [loading]="tooth.loading() && tooth.value() === null"
                [empty]="tooth.value() !== null && history().length === 0"
                [error]="tooth.error()"
                [hasValue]="tooth.value() !== null"
                [loadingLabel]="'state.loading' | t"
                [emptyTitle]="'clinic.odontogram.toothHistory.empty' | t"
                [errorTitle]="'clinic.error.denied.title' | t"
                [errorBody]="'clinic.error.denied.body' | t"
                [retryLabel]="'clinic.retry' | t"
                (retry)="reloadTooth()"
              />

              @if (history().length > 0) {
                <ul class="fe-list">
                  @for (finding of history(); track finding.id ?? $index) {
                    <li>
                      <span class="fe-mono" dir="ltr">{{ finding.code ?? '—' }}</span>
                      <span class="fe-mono" dir="ltr">{{ finding.state ?? '—' }}</span>
                      <span class="fe-mono">{{ format.dateTime(finding.recordedAt) }}</span>
                      @if (finding.note) {
                        <span class="fe-sub">
                          {{ 'clinic.odontogram.toothHistory.note' | t }}: {{ finding.note }}
                        </span>
                      }
                    </li>
                  }
                </ul>
              }

              <button type="button" class="fe-btn fe-btn--ghost" (click)="openTooth.set(null)">
                {{ 'clinic.odontogram.toothHistory.close' | t }}
              </button>
            </section>
          }
        } @else {
          <!-- Periodontal Probing Chart (Slice S4) -->
          <section class="fe-card" aria-label="Periodontal Probing Chart">
            <div class="card-header">
              <div>
                <h3>{{ 'clinic.odontogram.perio.title' | t }}</h3>
                <p class="fe-sub">{{ 'clinic.odontogram.perio.subtitle' | t }}</p>
              </div>
              <button type="button" class="fe-btn fe-btn--primary" (click)="openAddPerioModal()">
                {{ 'clinic.odontogram.action.addPerioExam' | t }}
              </button>
            </div>

            @if (perioMeasurements().length === 0) {
              <div class="empty-banner">
                <p>{{ 'clinic.odontogram.perio.emptyExams' | t }}</p>
                <button type="button" class="fe-btn fe-btn--primary" (click)="openAddPerioModal()">
                  {{ 'clinic.odontogram.action.addPerioExam' | t }}
                </button>
              </div>
            } @else {
              <div class="table-container">
                <table class="fe-table perio-table">
                  <thead>
                    <tr>
                      <th scope="col">{{ 'clinic.odontogram.col.tooth' | t }}</th>
                      <th scope="col">{{ 'clinic.odontogram.perio.site.mb' | t }}</th>
                      <th scope="col">{{ 'clinic.odontogram.perio.site.b' | t }}</th>
                      <th scope="col">{{ 'clinic.odontogram.perio.site.db' | t }}</th>
                      <th scope="col">{{ 'clinic.odontogram.perio.site.ml' | t }}</th>
                      <th scope="col">{{ 'clinic.odontogram.perio.site.l' | t }}</th>
                      <th scope="col">{{ 'clinic.odontogram.perio.site.dl' | t }}</th>
                      <th scope="col">{{ 'clinic.odontogram.perio.furcation' | t }}</th>
                      <th scope="col">{{ 'clinic.odontogram.perio.mobility' | t }}</th>
                    </tr>
                  </thead>
                  <tbody>
                    @for (m of perioMeasurements(); track m.toothFdi) {
                      <tr>
                        <th scope="row">
                          <span class="fe-mono" dir="ltr">{{ formatTooth(m.toothFdi) }}</span>
                          @if (hasDeepPocket(m)) {
                            <fe-status-chip tone="warn">{{ 'clinic.odontogram.perio.deepPocket' | t }}</fe-status-chip>
                          } @else {
                            <fe-status-chip tone="ok">{{ 'clinic.odontogram.perio.healthyPocket' | t }}</fe-status-chip>
                          }
                        </th>
                        <!-- 6 Sites: Probing Depth / Recession GM / Derived CAL / Bleeding -->
                        <td>
                          <div class="site-cell" [class.site-cell--deep]="(m.probingDepthMb ?? 0) > 3">
                            <span class="pd-val">{{ m.probingDepthMb ?? '—' }}</span>
                            <span class="gm-val">GM: {{ m.gingivalMarginMb ?? 0 }}</span>
                            <span class="cal-val">CAL: {{ m.calMb ?? m.probingDepthMb ?? '—' }}</span>
                            @if (m.bopMb) {
                              <span class="bop-dot" title="BOP">BOP</span>
                            }
                          </div>
                        </td>
                        <td>
                          <div class="site-cell" [class.site-cell--deep]="(m.probingDepthB ?? 0) > 3">
                            <span class="pd-val">{{ m.probingDepthB ?? '—' }}</span>
                            <span class="gm-val">GM: {{ m.gingivalMarginB ?? 0 }}</span>
                            <span class="cal-val">CAL: {{ m.calB ?? m.probingDepthB ?? '—' }}</span>
                            @if (m.bopB) {
                              <span class="bop-dot" title="BOP">BOP</span>
                            }
                          </div>
                        </td>
                        <td>
                          <div class="site-cell" [class.site-cell--deep]="(m.probingDepthDb ?? 0) > 3">
                            <span class="pd-val">{{ m.probingDepthDb ?? '—' }}</span>
                            <span class="gm-val">GM: {{ m.gingivalMarginDb ?? 0 }}</span>
                            <span class="cal-val">CAL: {{ m.calDb ?? m.probingDepthDb ?? '—' }}</span>
                            @if (m.bopDb) {
                              <span class="bop-dot" title="BOP">BOP</span>
                            }
                          </div>
                        </td>
                        <td>
                          <div class="site-cell" [class.site-cell--deep]="(m.probingDepthMl ?? 0) > 3">
                            <span class="pd-val">{{ m.probingDepthMl ?? '—' }}</span>
                            <span class="gm-val">GM: {{ m.gingivalMarginMl ?? 0 }}</span>
                            <span class="cal-val">CAL: {{ m.calMl ?? m.probingDepthMl ?? '—' }}</span>
                            @if (m.bopMl) {
                              <span class="bop-dot" title="BOP">BOP</span>
                            }
                          </div>
                        </td>
                        <td>
                          <div class="site-cell" [class.site-cell--deep]="(m.probingDepthL ?? 0) > 3">
                            <span class="pd-val">{{ m.probingDepthL ?? '—' }}</span>
                            <span class="gm-val">GM: {{ m.gingivalMarginL ?? 0 }}</span>
                            <span class="cal-val">CAL: {{ m.calL ?? m.probingDepthL ?? '—' }}</span>
                            @if (m.bopL) {
                              <span class="bop-dot" title="BOP">BOP</span>
                            }
                          </div>
                        </td>
                        <td>
                          <div class="site-cell" [class.site-cell--deep]="(m.probingDepthDl ?? 0) > 3">
                            <span class="pd-val">{{ m.probingDepthDl ?? '—' }}</span>
                            <span class="gm-val">GM: {{ m.gingivalMarginDl ?? 0 }}</span>
                            <span class="cal-val">CAL: {{ m.calDl ?? m.probingDepthDl ?? '—' }}</span>
                            @if (m.bopDl) {
                              <span class="bop-dot" title="BOP">BOP</span>
                            }
                          </div>
                        </td>
                        <td>{{ m.furcationClass ?? '—' }}</td>
                        <td>{{ m.mobilityGrade ?? '—' }}</td>
                      </tr>
                    }
                  </tbody>
                </table>
              </div>
            }
          </section>
        }

        <!-- Modal: Record Finding -->
        @if (isAddFindingOpen()) {
          <div class="fe-modal-overlay" (click)="isAddFindingOpen.set(false)">
            <div class="fe-modal-card" (click)="$event.stopPropagation()">
              <div class="modal-header">
                <h3>{{ 'clinic.odontogram.action.addFinding' | t }}</h3>
                <button type="button" class="close-btn" (click)="isAddFindingOpen.set(false)">&times;</button>
              </div>
              <div class="modal-body">
                <div class="form-field">
                  <label class="fe-label">{{ 'clinic.odontogram.finding.tooth' | t }}</label>
                  <select class="fe-input" [(ngModel)]="findingToothFdi">
                    @for (t of allAdultTeeth; track t) {
                      <option [value]="t">{{ formatTooth(t) }} (FDI {{ t }})</option>
                    }
                  </select>
                </div>

                <div class="form-field">
                  <label class="fe-label">{{ 'clinic.odontogram.finding.scope' | t }}</label>
                  <select class="fe-input" [(ngModel)]="findingScope">
                    <option value="TOOTH">TOOTH</option>
                    <option value="SURFACE">SURFACE</option>
                    <option value="ROOT">ROOT</option>
                    <option value="ARCH">ARCH</option>
                  </select>
                </div>

                <div class="form-field">
                  <label class="fe-label">{{ 'clinic.odontogram.finding.surfaces' | t }}</label>
                  <div class="surfaces-picker">
                    @for (s of ['M', 'O', 'D', 'B', 'L', 'I']; track s) {
                      <button
                        type="button"
                        class="surface-toggle"
                        [class.surface-toggle--active]="findingSurfaces.includes(s)"
                        (click)="toggleSurface(s)"
                      >
                        {{ s }}
                      </button>
                    }
                  </div>
                </div>

                <div class="form-field">
                  <label class="fe-label">{{ 'clinic.odontogram.finding.state' | t }}</label>
                  <select class="fe-input" [(ngModel)]="findingState">
                    <option value="NORMAL">{{ 'clinic.odontogram.finding.state.normal' | t }}</option>
                    <option value="ABNORMAL">{{ 'clinic.odontogram.finding.state.abnormal' | t }}</option>
                    <option value="ABSENT">{{ 'clinic.odontogram.finding.state.absent' | t }}</option>
                    <option value="HISTORICAL">{{ 'clinic.odontogram.finding.state.historical' | t }}</option>
                    <option value="PLANNED">{{ 'clinic.odontogram.finding.state.planned' | t }}</option>
                    <option value="COMPLETED">{{ 'clinic.odontogram.finding.state.completed' | t }}</option>
                  </select>
                </div>

                <div class="form-field">
                  <label class="fe-label">{{ 'clinic.odontogram.finding.code' | t }}</label>
                  <input
                    type="text"
                    class="fe-input"
                    [(ngModel)]="findingCode"
                    placeholder="CARIES, AMALGAM, CROWN, SEALANT"
                  />
                </div>

                <div class="form-field">
                  <label class="fe-label">{{ 'clinic.odontogram.finding.note' | t }}</label>
                  <textarea class="fe-input" rows="2" [(ngModel)]="findingNote"></textarea>
                </div>
              </div>
              <div class="modal-footer">
                <button type="button" class="fe-btn fe-btn--ghost" (click)="isAddFindingOpen.set(false)">
                  {{ 'clinic.odontogram.toothHistory.close' | t }}
                </button>
                <button
                  type="button"
                  class="fe-btn fe-btn--primary"
                  [disabled]="!findingCode.trim() || isSubmitting()"
                  (click)="saveFinding()"
                >
                  {{ 'clinic.odontogram.finding.save' | t }}
                </button>
              </div>
            </div>
          </div>
        }

        <!-- Modal: Correct Finding (Append-Only Supersede) -->
        @if (correctingFinding() !== null) {
          <div class="fe-modal-overlay" (click)="correctingFinding.set(null)">
            <div class="fe-modal-card" (click)="$event.stopPropagation()">
              <div class="modal-header">
                <h3>{{ 'clinic.odontogram.action.correctFinding' | t }}</h3>
                <button type="button" class="close-btn" (click)="correctingFinding.set(null)">&times;</button>
              </div>
              <div class="modal-body">
                <p class="fe-sub">{{ 'clinic.odontogram.appendOnly' | t }}</p>

                <div class="form-field">
                  <label class="fe-label">{{ 'clinic.odontogram.finding.code' | t }}</label>
                  <input type="text" class="fe-input" [(ngModel)]="correctCode" />
                </div>

                <div class="form-field">
                  <label class="fe-label">{{ 'clinic.odontogram.finding.note' | t }}</label>
                  <textarea class="fe-input" rows="3" [(ngModel)]="correctNote"></textarea>
                </div>
              </div>
              <div class="modal-footer">
                <button type="button" class="fe-btn fe-btn--ghost" (click)="correctingFinding.set(null)">
                  {{ 'clinic.odontogram.toothHistory.close' | t }}
                </button>
                <button
                  type="button"
                  class="fe-btn fe-btn--primary"
                  [disabled]="!correctCode.trim() || isSubmitting()"
                  (click)="submitCorrection()"
                >
                  {{ 'clinic.odontogram.finding.correctSave' | t }}
                </button>
              </div>
            </div>
          </div>
        }

        <!-- Modal: Record Periodontal Probing Exam -->
        @if (isAddPerioOpen()) {
          <div class="fe-modal-overlay" (click)="isAddPerioOpen.set(false)">
            <div class="fe-modal-card perio-modal-card" (click)="$event.stopPropagation()">
              <div class="modal-header">
                <h3>{{ 'clinic.odontogram.perio.saveExam' | t }}</h3>
                <button type="button" class="close-btn" (click)="isAddPerioOpen.set(false)">&times;</button>
              </div>
              <div class="modal-body">
                <div class="form-row">
                  <div class="form-field">
                    <label class="fe-label">{{ 'clinic.odontogram.perio.notes' | t }}</label>
                    <input type="text" class="fe-input" [(ngModel)]="perioNotes" placeholder="Exam notes" />
                  </div>
                </div>

                <h4>{{ 'clinic.odontogram.perio.addTooth' | t }}</h4>
                <div class="perio-input-grid">
                  <div class="form-field">
                    <label class="fe-label">{{ 'clinic.odontogram.finding.tooth' | t }}</label>
                    <select class="fe-input" [(ngModel)]="inputToothFdi">
                      @for (t of allAdultTeeth; track t) {
                        <option [value]="t">{{ formatTooth(t) }}</option>
                      }
                    </select>
                  </div>

                  <!-- 6-site PD inputs -->
                  <div class="form-row">
                    <div class="form-field">
                      <label class="fe-label">PD MB</label>
                      <input type="number" min="0" max="20" class="fe-input" [(ngModel)]="pdMb" />
                    </div>
                    <div class="form-field">
                      <label class="fe-label">PD B</label>
                      <input type="number" min="0" max="20" class="fe-input" [(ngModel)]="pdB" />
                    </div>
                    <div class="form-field">
                      <label class="fe-label">PD DB</label>
                      <input type="number" min="0" max="20" class="fe-input" [(ngModel)]="pdDb" />
                    </div>
                    <div class="form-field">
                      <label class="fe-label">PD ML</label>
                      <input type="number" min="0" max="20" class="fe-input" [(ngModel)]="pdMl" />
                    </div>
                    <div class="form-field">
                      <label class="fe-label">PD L</label>
                      <input type="number" min="0" max="20" class="fe-input" [(ngModel)]="pdL" />
                    </div>
                    <div class="form-field">
                      <label class="fe-label">PD DL</label>
                      <input type="number" min="0" max="20" class="fe-input" [(ngModel)]="pdDl" />
                    </div>
                  </div>

                  <!-- 6-site GM Recession inputs -->
                  <div class="form-row">
                    <div class="form-field">
                      <label class="fe-label">GM MB</label>
                      <input type="number" min="-20" max="20" class="fe-input" [(ngModel)]="gmMb" />
                    </div>
                    <div class="form-field">
                      <label class="fe-label">GM B</label>
                      <input type="number" min="-20" max="20" class="fe-input" [(ngModel)]="gmB" />
                    </div>
                    <div class="form-field">
                      <label class="fe-label">GM DB</label>
                      <input type="number" min="-20" max="20" class="fe-input" [(ngModel)]="gmDb" />
                    </div>
                    <div class="form-field">
                      <label class="fe-label">GM ML</label>
                      <input type="number" min="-20" max="20" class="fe-input" [(ngModel)]="gmMl" />
                    </div>
                    <div class="form-field">
                      <label class="fe-label">GM L</label>
                      <input type="number" min="-20" max="20" class="fe-input" [(ngModel)]="gmL" />
                    </div>
                    <div class="form-field">
                      <label class="fe-label">GM DL</label>
                      <input type="number" min="-20" max="20" class="fe-input" [(ngModel)]="gmDl" />
                    </div>
                  </div>

                  <!-- Real-time Derived CAL display: CAL = PD + GM -->
                  <div class="cal-summary">
                    <span><strong>CAL (PD + GM):</strong></span>
                    <span>MB: {{ (pdMb || 0) + (gmMb || 0) }}mm</span>
                    <span>B: {{ (pdB || 0) + (gmB || 0) }}mm</span>
                    <span>DB: {{ (pdDb || 0) + (gmDb || 0) }}mm</span>
                    <span>ML: {{ (pdMl || 0) + (gmMl || 0) }}mm</span>
                    <span>L: {{ (pdL || 0) + (gmL || 0) }}mm</span>
                    <span>DL: {{ (pdDl || 0) + (gmDl || 0) }}mm</span>
                  </div>

                  <!-- BOP Checkboxes -->
                  <div class="bop-row">
                    <label class="fe-label">{{ 'clinic.odontogram.perio.bop' | t }}:</label>
                    <label class="cb-inline"><input type="checkbox" [(ngModel)]="bopMb" /> MB</label>
                    <label class="cb-inline"><input type="checkbox" [(ngModel)]="bopB" /> B</label>
                    <label class="cb-inline"><input type="checkbox" [(ngModel)]="bopDb" /> DB</label>
                    <label class="cb-inline"><input type="checkbox" [(ngModel)]="bopMl" /> ML</label>
                    <label class="cb-inline"><input type="checkbox" [(ngModel)]="bopL" /> L</label>
                    <label class="cb-inline"><input type="checkbox" [(ngModel)]="bopDl" /> DL</label>
                  </div>
                </div>
              </div>
              <div class="modal-footer">
                <button type="button" class="fe-btn fe-btn--ghost" (click)="isAddPerioOpen.set(false)">
                  {{ 'clinic.odontogram.toothHistory.close' | t }}
                </button>
                <button
                  type="button"
                  class="fe-btn fe-btn--primary"
                  [disabled]="isSubmitting()"
                  (click)="savePerioExam()"
                >
                  {{ 'clinic.odontogram.perio.saveExam' | t }}
                </button>
              </div>
            </div>
          </div>
        }
      }
    </div>
  `,
  styles: [
    `
      .header-actions {
        display: flex;
        gap: var(--fe-space-2);
        align-items: center;
        flex-wrap: wrap;
      }
      .chart-tabs {
        display: flex;
        gap: var(--fe-space-2);
        border-bottom: 1px solid var(--line);
        margin-block-end: var(--fe-space-4);
        overflow-x: auto;
      }
      .tab-btn {
        background: transparent;
        border: none;
        border-bottom: 2px solid transparent;
        color: var(--mute);
        font-weight: 500;
        padding-block: var(--fe-space-3);
        padding-inline: var(--fe-space-3);
        cursor: pointer;
        transition: all 0.15s ease-in-out;
        white-space: nowrap;
      }
      .tab-btn:hover {
        color: var(--ink);
      }
      .tab-btn--active {
        color: var(--pri);
        border-bottom-color: var(--pri);
        font-weight: 600;
      }
      .toolbar-row {
        display: flex;
        justify-content: space-between;
        align-items: center;
        gap: var(--fe-space-4);
        margin-block-end: var(--fe-space-4);
        flex-wrap: wrap;
      }
      .toolbar-group {
        display: flex;
        align-items: center;
        gap: var(--fe-space-2);
      }
      .segmented-control {
        display: inline-flex;
        background: var(--surf2);
        border-radius: var(--fe-radius);
        padding: 2px;
        border: 1px solid var(--line);
      }
      .seg-btn {
        background: transparent;
        border: none;
        padding: var(--fe-space-1) var(--fe-space-3);
        font-size: var(--fe-text-sm);
        color: var(--mute);
        border-radius: calc(var(--fe-radius) - 2px);
        cursor: pointer;
        transition: all 0.15s ease;
      }
      .seg-btn--active {
        background: var(--surf);
        color: var(--ink);
        font-weight: 600;
        box-shadow: var(--shadow);
      }
      .arch-card {
        padding: var(--fe-space-4);
        margin-block-end: var(--fe-space-4);
        overflow-x: auto;
      }
      .arch-section {
        display: flex;
        justify-content: center;
        gap: var(--fe-space-4);
      }
      .arch-quadrant {
        display: flex;
        gap: var(--fe-space-2);
      }
      .arch-divider {
        width: 2px;
        background: var(--line);
      }
      .arch-mid-line {
        height: 2px;
        background: var(--line);
        margin-block: var(--fe-space-3);
      }
      .tooth-cell {
        display: flex;
        flex-direction: column;
        align-items: center;
        justify-content: center;
        padding: var(--fe-space-2);
        min-width: 2.4rem;
        background: var(--surf);
        border: 1px solid var(--line);
        border-radius: var(--fe-radius);
        cursor: pointer;
        transition: all 0.15s ease;
      }
      .tooth-cell:hover {
        border-color: var(--pri);
        background: var(--surf2);
      }
      .tooth-cell--selected {
        border-color: var(--pri);
        box-shadow: 0 0 0 2px var(--pri);
      }
      .tooth-cell--has-finding {
        background: var(--ok-soft);
      }
      .tooth-num {
        font-size: 0.85rem;
        font-weight: 600;
      }
      .tooth-badge {
        width: 8px;
        height: 8px;
        border-radius: 50%;
        margin-top: 4px;
        background: transparent;
      }
      .status-normal {
        background: var(--ok);
      }
      .status-abnormal {
        background: var(--bad);
      }
      .status-planned {
        background: var(--blue);
      }
      .status-completed {
        background: var(--pri);
      }
      .status-absent {
        background: var(--mute);
      }
      .fe-card {
        overflow-x: auto;
      }
      .fe-table {
        min-inline-size: 34rem;
      }
      .notes {
        margin-block: var(--fe-space-3);
        padding-inline-start: var(--fe-space-5);
        display: flex;
        flex-direction: column;
        gap: var(--fe-space-2);
        max-inline-size: 74ch;
        color: var(--mute);
        font-size: var(--fe-text-sm);
      }
      .superseded td,
      .superseded th {
        color: var(--mute);
      }
      .unset {
        color: var(--mute);
        font-style: italic;
      }
      .fe-btn {
        margin-block: var(--fe-space-2);
      }
      .fe-btn--xs {
        padding: 2px 8px;
        font-size: 0.75rem;
      }
      .card-header {
        display: flex;
        justify-content: space-between;
        align-items: flex-start;
        margin-block-end: var(--fe-space-4);
      }
      .empty-banner {
        padding: var(--fe-space-6);
        text-align: center;
        color: var(--mute);
        background: var(--surf2);
        border-radius: var(--fe-radius);
      }
      .table-container {
        overflow-x: auto;
      }
      .perio-table th,
      .perio-table td {
        text-align: center;
        vertical-align: middle;
      }
      .site-cell {
        display: flex;
        flex-direction: column;
        padding: var(--fe-space-1);
        border-radius: var(--fe-radius);
        background: var(--surf2);
        font-size: 0.8rem;
      }
      .site-cell--deep {
        background: var(--bad-soft);
        border: 1px solid var(--bad);
      }
      .pd-val {
        font-weight: 700;
        font-size: 0.9rem;
      }
      .gm-val {
        color: var(--mute);
        font-size: 0.75rem;
      }
      .cal-val {
        color: var(--pri);
        font-size: 0.75rem;
        font-weight: 600;
      }
      .bop-dot {
        font-size: 0.65rem;
        font-weight: 700;
        color: var(--bad);
      }
      .surfaces-picker {
        display: flex;
        gap: var(--fe-space-2);
      }
      .surface-toggle {
        width: 32px;
        height: 32px;
        border-radius: var(--fe-radius);
        border: 1px solid var(--line);
        background: var(--surf);
        cursor: pointer;
        font-weight: 600;
      }
      .surface-toggle--active {
        background: var(--pri);
        color: var(--pri-ink);
        border-color: var(--pri);
      }
      .cal-summary {
        display: flex;
        gap: var(--fe-space-3);
        padding: var(--fe-space-2);
        background: var(--surf2);
        border-radius: var(--fe-radius);
        font-size: 0.85rem;
        flex-wrap: wrap;
        margin-block: var(--fe-space-2);
      }
      .bop-row {
        display: flex;
        align-items: center;
        gap: var(--fe-space-3);
        flex-wrap: wrap;
        margin-block-start: var(--fe-space-2);
      }
      .cb-inline {
        display: inline-flex;
        align-items: center;
        gap: 4px;
        font-size: 0.85rem;
      }
      .fe-modal-overlay {
        position: fixed;
        inset: 0;
        background: var(--ink);
        opacity: 0.96;
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
        max-width: 32rem;
        box-shadow: var(--shadow);
        display: flex;
        flex-direction: column;
      }
      .perio-modal-card {
        max-width: 48rem;
      }
      .modal-header {
        display: flex;
        justify-content: space-between;
        align-items: center;
        padding: var(--fe-space-3) var(--fe-space-4);
        border-bottom: 1px solid var(--line);
      }
      .close-btn {
        background: transparent;
        border: none;
        color: var(--mute);
        font-size: 1.2rem;
        cursor: pointer;
      }
      .modal-body {
        padding: var(--fe-space-4);
        display: flex;
        flex-direction: column;
        gap: var(--fe-space-3);
        max-height: 70vh;
        overflow-y: auto;
      }
      .modal-footer {
        display: flex;
        justify-content: flex-end;
        gap: var(--fe-space-2);
        padding: var(--fe-space-3) var(--fe-space-4);
        border-top: 1px solid var(--line);
      }
      .form-field {
        display: flex;
        flex-direction: column;
        gap: var(--fe-space-1);
      }
      .form-row {
        display: flex;
        gap: var(--fe-space-2);
        flex-wrap: wrap;
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
        background: var(--paper);
        color: var(--ink);
        font-family: inherit;
        font-size: 0.95rem;
      }
      .fe-input:focus {
        outline: 2px solid var(--pri);
        outline-offset: 1px;
        border-color: var(--pri);
      }
    `,
  ],
})
export class ClinicOdontogramComponent {
  private readonly api = inject(ClinicPatientApi);
  private readonly route = inject(ActivatedRoute);
  private readonly projects = inject(ProjectContextService);
  protected readonly rbac = inject(ClinicRbacService);

  protected readonly canWriteClinical = computed(() =>
    this.rbac.hasPermission('clinical.write_record')
  );

  protected readonly format = inject(FormatService);
  protected readonly chart = new ResourceStore<readonly ClinicChartEntry[]>();
  protected readonly tooth = new ResourceStore<readonly ClinicFinding[]>();

  protected readonly patientId = toSignal(
    this.route.paramMap.pipe(map((params) => params.get('id'))),
    { initialValue: null },
  );

  protected readonly activeView = signal<ActiveView>('CHART');
  protected readonly selectedNotation = signal<ToothNotation>('FDI');
  protected readonly selectedLayer = signal<ChartLayer>('ALL');

  /** The tooth whose history is open, or null. A number the server published. */
  protected readonly openTooth = signal<number | null>(null);

  protected readonly entries = computed(() => this.chart.value() ?? []);
  protected readonly history = computed(() => this.tooth.value() ?? []);

  // Filtered entries by layer (All, Baseline Status, Planned, Completed)
  protected readonly filteredEntries = computed(() => {
    const list = this.entries();
    const layer = this.selectedLayer();
    if (layer === 'ALL') return list;
    return list.filter((e) => {
      const state = (e.finding?.state ?? '').toUpperCase();
      if (layer === 'STATUS') {
        return (
          state === 'NORMAL' ||
          state === 'ABNORMAL' ||
          state === 'ABSENT' ||
          state === 'HISTORICAL' ||
          state === 'RECORDED'
        );
      }
      if (layer === 'PLAN') {
        return state === 'PLAN' || state === 'PLANNED';
      }
      if (layer === 'COMPLETED') {
        return state === 'COMPLETED';
      }
      return true;
    });
  });

  // Quadrant adult tooth constants for template
  protected readonly adultUpperRight = ADULT_UPPER_RIGHT;
  protected readonly adultUpperLeft = ADULT_UPPER_LEFT;
  protected readonly adultLowerRight = ADULT_LOWER_RIGHT;
  protected readonly adultLowerLeft = ADULT_LOWER_LEFT;
  protected readonly allAdultTeeth = [
    ...ADULT_UPPER_RIGHT,
    ...ADULT_UPPER_LEFT,
    ...ADULT_LOWER_RIGHT,
    ...ADULT_LOWER_LEFT,
  ];

  // Periodontal state
  protected readonly perioExams = signal<PeriodontalExamHeader[]>([]);
  protected readonly perioMeasurements = signal<PeriodontalMeasurement[]>([]);

  // Modals state
  protected readonly isAddFindingOpen = signal<boolean>(false);
  protected readonly correctingFinding = signal<ClinicFinding | null>(null);
  protected readonly isAddPerioOpen = signal<boolean>(false);
  protected readonly isSubmitting = signal<boolean>(false);

  // Form states for adding finding
  protected findingToothFdi = 16;
  protected findingScope = 'SURFACE';
  protected findingSurfaces: string[] = ['O'];
  protected findingState = 'ABNORMAL';
  protected findingCode = 'CARIES';
  protected findingNote = '';

  // Form states for correcting finding
  protected correctCode = '';
  protected correctNote = '';

  // Form states for adding periodontal exam
  protected perioNotes = '';
  protected inputToothFdi = 16;
  protected pdMb = 4;
  protected pdB = 3;
  protected pdDb = 5;
  protected pdMl = 3;
  protected pdL = 2;
  protected pdDl = 4;
  protected gmMb = 1;
  protected gmB = 0;
  protected gmDb = 2;
  protected gmMl = 0;
  protected gmL = 0;
  protected gmDl = 1;
  protected bopMb = true;
  protected bopB = false;
  protected bopDb = true;
  protected bopMl = false;
  protected bopL = false;
  protected bopDl = true;

  constructor() {
    inject(I18nService).register({ clinic: clinicStrings as Namespace });

    effect(() => {
      const id = this.patientId();
      untracked(() => {
        this.openTooth.set(null);
        if (id === null) {
          this.chart.reset();
          this.tooth.reset();
          this.perioExams.set([]);
          this.perioMeasurements.set([]);
          return;
        }
        void this.loadChart(id);
      });
    });
  }

  protected async reloadChart(): Promise<void> {
    const id = this.patientId();
    if (id !== null) await this.loadChart(id);
  }

  protected async reloadTooth(): Promise<void> {
    const id = this.patientId();
    const tooth = this.openTooth();
    if (id !== null && tooth !== null) await this.loadTooth(id, tooth);
  }

  protected chartErrorRequestId(): string | null {
    return this.chart.error()?.requestId ?? null;
  }

  protected toothOf(entry: ClinicChartEntry): number | null {
    const tooth = entry.finding?.toothFdi;
    return typeof tooth === 'number' ? tooth : null;
  }

  protected formatTooth(toothFdi: number | null | undefined): string {
    if (toothFdi == null) return '—';
    return convertToothNotation(toothFdi, this.selectedNotation());
  }

  protected toggleTooth(toothFdi: number): void {
    if (this.openTooth() === toothFdi) {
      this.openTooth.set(null);
      this.tooth.reset();
      return;
    }
    this.openTooth.set(toothFdi);
    const id = this.patientId();
    if (id !== null) void this.loadTooth(id, toothFdi);
  }

  protected hasFinding(toothFdi: number): boolean {
    return this.entries().some((e) => e.finding?.toothFdi === toothFdi && e.superseded !== true);
  }

  protected toothStatusClass(toothFdi: number): string {
    const entry = this.entries().find(
      (e) => e.finding?.toothFdi === toothFdi && e.superseded !== true,
    );
    if (!entry || !entry.finding) return '';
    const state = (entry.finding.state ?? '').toUpperCase();
    if (state === 'NORMAL') return 'status-normal';
    if (state === 'ABNORMAL' || entry.finding.code?.includes('CARIES')) return 'status-abnormal';
    if (state === 'PLAN' || state === 'PLANNED') return 'status-planned';
    if (state === 'COMPLETED') return 'status-completed';
    if (state === 'ABSENT') return 'status-absent';
    return 'status-normal';
  }

  protected hasDeepPocket(m: PeriodontalMeasurement): boolean {
    return (
      (m.probingDepthMb ?? 0) > 3 ||
      (m.probingDepthB ?? 0) > 3 ||
      (m.probingDepthDb ?? 0) > 3 ||
      (m.probingDepthMl ?? 0) > 3 ||
      (m.probingDepthL ?? 0) > 3 ||
      (m.probingDepthDl ?? 0) > 3
    );
  }

  protected switchViewToPeriodontal(): void {
    this.activeView.set('PERIODONTAL');
    const id = this.patientId();
    if (id !== null) {
      void this.loadPeriodontal(id);
    }
  }

  protected toggleSurface(s: string): void {
    const idx = this.findingSurfaces.indexOf(s);
    if (idx >= 0) {
      this.findingSurfaces = this.findingSurfaces.filter((item) => item !== s);
    } else {
      this.findingSurfaces = [...this.findingSurfaces, s];
    }
  }

  protected async saveFinding(): Promise<void> {
    const id = this.patientId();
    if (!id || !this.findingCode.trim()) return;
    this.isSubmitting.set(true);
    try {
      await this.api.recordFinding(
        id,
        {
          toothFdi: Number(this.findingToothFdi),
          scope: this.findingScope,
          surfaces: this.findingSurfaces,
          state: this.findingState,
          code: this.findingCode.trim(),
          note: this.findingNote.trim() || undefined,
        },
        { projectId: this.projects.selectedProjectId() ?? undefined },
      );
      this.isAddFindingOpen.set(false);
      this.findingNote = '';
      await this.loadChart(id);
    } finally {
      this.isSubmitting.set(false);
    }
  }

  protected openCorrectModal(finding: ClinicFinding): void {
    this.correctingFinding.set(finding);
    this.correctCode = finding.code ?? '';
    this.correctNote = '';
  }

  protected async submitCorrection(): Promise<void> {
    const id = this.patientId();
    const original = this.correctingFinding();
    if (!id || !original?.id || !this.correctCode.trim()) return;
    this.isSubmitting.set(true);
    try {
      await this.api.correctFinding(
        id,
        original.id,
        {
          code: this.correctCode.trim(),
          note: this.correctNote.trim() || undefined,
        },
        { projectId: this.projects.selectedProjectId() ?? undefined },
      );
      this.correctingFinding.set(null);
      await this.loadChart(id);
    } finally {
      this.isSubmitting.set(false);
    }
  }

  protected openAddPerioModal(): void {
    this.isAddPerioOpen.set(true);
  }

  protected async savePerioExam(): Promise<void> {
    const id = this.patientId();
    if (!id) return;
    this.isSubmitting.set(true);
    try {
      const measurement: PeriodontalMeasurementInput = {
        toothFdi: Number(this.inputToothFdi),
        probingDepthMb: Number(this.pdMb),
        probingDepthB: Number(this.pdB),
        probingDepthDb: Number(this.pdDb),
        probingDepthMl: Number(this.pdMl),
        probingDepthL: Number(this.pdL),
        probingDepthDl: Number(this.pdDl),
        gingivalMarginMb: Number(this.gmMb),
        gingivalMarginB: Number(this.gmB),
        gingivalMarginDb: Number(this.gmDb),
        gingivalMarginMl: Number(this.gmMl),
        gingivalMarginL: Number(this.gmL),
        gingivalMarginDl: Number(this.gmDl),
        bopMb: this.bopMb,
        bopB: this.bopB,
        bopDb: this.bopDb,
        bopMl: this.bopMl,
        bopL: this.bopL,
        bopDl: this.bopDl,
      };

      await this.api.createPeriodontalExam(
        id,
        {
          notes: this.perioNotes.trim() || undefined,
          measurements: [measurement],
        },
        { projectId: this.projects.selectedProjectId() ?? undefined },
      );

      this.isAddPerioOpen.set(false);
      this.perioNotes = '';
      await this.loadPeriodontal(id);
    } finally {
      this.isSubmitting.set(false);
    }
  }

  private async loadChart(patientId: string): Promise<void> {
    await this.chart.load(async () => {
      const rows = await this.api.odontogram(patientId, {
        projectId: this.projects.selectedProjectId() ?? undefined,
      });
      return Array.isArray(rows) ? rows : [];
    });
  }

  private async loadTooth(patientId: string, toothFdi: number): Promise<void> {
    await this.tooth.load(async () => {
      const rows = await this.api.toothHistory(patientId, toothFdi, {
        projectId: this.projects.selectedProjectId() ?? undefined,
      });
      return Array.isArray(rows) ? rows : [];
    });
  }

  private async loadPeriodontal(patientId: string): Promise<void> {
    try {
      const [exams, latest] = await Promise.allSettled([
        this.api.periodontalExams(patientId, {
          projectId: this.projects.selectedProjectId() ?? undefined,
        }),
        this.api.latestPeriodontalChart(patientId, {
          projectId: this.projects.selectedProjectId() ?? undefined,
        }),
      ]);
      if (exams.status === 'fulfilled' && Array.isArray(exams.value)) {
        this.perioExams.set(exams.value);
      }
      if (latest.status === 'fulfilled' && Array.isArray(latest.value)) {
        this.perioMeasurements.set(latest.value);
      }
    } catch {
      // Periodontal data fails gracefully without blocking chart read
    }
  }
}
