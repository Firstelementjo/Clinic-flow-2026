import { CommonModule } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { ClinicApi, type ClinicAppointment } from '../../core/api/endpoints';
import { ProjectContextService } from '../../core/context/project-context.service';
import { FormatService } from '../../core/format/format.service';
import { I18nService } from '../../core/i18n/i18n.service';
import type { Namespace } from '../../core/i18n/i18n.types';
import { clinicAdminStrings } from './clinic-admin.strings';
import { ClinicPatientApi, type ClinicReportView } from './clinic-patient.api';

interface BreakdownRow {
  readonly key: string;
  readonly label: string;
  readonly count: number;
  readonly share: number;
  readonly tone: 'teal' | 'green' | 'amber' | 'red';
}

/**
 * `/app/clinic/appointments-overview` -- appointment performance.
 *
 * The metrics are the server's own `/clinic/reports` aggregation; the two lists
 * read the appointment projection, so a row here is a booking a user can open.
 */
@Component({
  selector: 'fe-clinic-appointments-overview',
  standalone: true,
  imports: [CommonModule, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="tl-page" data-testid="clinic-appointments-overview">
      <div class="tl-breadcrumbs">
        <a routerLink="/app/dashboard">{{ t('nav.home') }}</a>
        <span>»</span>
        <span class="active">{{ t('appointmentsOverview.breadcrumb') }}</span>
      </div>

      <header class="tl-page-head">
        <div>
          <h1>{{ t('appointmentsOverview.title') }}</h1>
          <p>{{ t('appointmentsOverview.subtitle') }}</p>
        </div>
        <a class="tl-btn tl-btn-teal" routerLink="/app/clinic/appointments">
          {{ t('appointmentsOverview.openList') }}
        </a>
      </header>

      <section class="tl-card tl-filters">
        <label class="tl-field">
          <span>{{ t('appointmentsOverview.window') }}</span>
          <select
            class="tl-input"
            [value]="window()"
            (change)="window.set($any($event.target).value)"
          >
            <option value="7d">{{ t('appointmentsOverview.window7') }}</option>
            <option value="30d">{{ t('appointmentsOverview.window30') }}</option>
            <option value="90d">{{ t('appointmentsOverview.window90') }}</option>
            <option value="365d">{{ t('appointmentsOverview.window365') }}</option>
          </select>
        </label>
        <button type="button" class="tl-btn tl-btn-teal" [disabled]="loading()" (click)="load()">
          {{ t('common.apply') }}
        </button>
      </section>

      @if (error()) {
        <div class="tl-card tl-note is-error">
          <span>{{ t('appointmentsOverview.loadFailed') }}</span>
          <button type="button" class="tl-link" (click)="load()">{{ t('common.retry') }}</button>
        </div>
      }

      <section class="tl-stats-grid">
        <article class="tl-stat-card">
          <span>{{ t('appointmentsOverview.total') }}</span>
          <strong>{{ metric('totalAppointments') }}</strong>
        </article>
        <article class="tl-stat-card">
          <span>{{ t('appointmentsOverview.completed') }}</span>
          <strong>{{ metric('completedCount') }}</strong>
        </article>
        <article class="tl-stat-card">
          <span>{{ t('appointmentsOverview.confirmed') }}</span>
          <strong>{{ metric('confirmedCount') }}</strong>
        </article>
        <article class="tl-stat-card">
          <span>{{ t('appointmentsOverview.cancelled') }}</span>
          <strong>{{ metric('cancelledCount') }}</strong>
        </article>
        <article class="tl-stat-card">
          <span>{{ t('appointmentsOverview.noShow') }}</span>
          <strong>{{ metric('noShowCount') }}</strong>
        </article>
        <article class="tl-stat-card">
          <span>{{ t('appointmentsOverview.cancellationRate') }}</span>
          <strong>{{ cancellationRate() }}</strong>
        </article>
      </section>

      <div class="tl-split">
        <section class="tl-card">
          <header class="tl-card-head">
            <h2>{{ t('appointmentsOverview.statusBreakdown') }}</h2>
          </header>
          @if (breakdown().length === 0) {
            <p class="tl-note">{{ t('overview.noUpcoming') }}</p>
          } @else {
            <ul class="tl-breakdown" data-testid="appointments-overview-breakdown">
              @for (row of breakdown(); track row.key) {
                <li>
                  <div class="tl-breakdown-head">
                    <span>{{ row.label }}</span>
                    <strong>{{ row.count }}</strong>
                  </div>
                  <div class="tl-breakdown-track">
                    <div
                      class="tl-breakdown-fill"
                      [class.is-teal]="row.tone === 'teal'"
                      [class.is-green]="row.tone === 'green'"
                      [class.is-amber]="row.tone === 'amber'"
                      [class.is-red]="row.tone === 'red'"
                      [style.width.%]="row.share"
                    ></div>
                  </div>
                </li>
              }
            </ul>
          }
        </section>

        <section class="tl-card">
          <header class="tl-card-head">
            <h2>{{ t('appointmentsOverview.todayList') }}</h2>
          </header>
          @if (todayRows().length === 0) {
            <p class="tl-note">{{ t('appointmentsOverview.emptyToday') }}</p>
          } @else {
            <div class="tl-table-wrap">
              <table class="tl-table">
                <thead>
                  <tr>
                    <th>{{ t('appointmentsOverview.patient') }}</th>
                    <th>{{ t('appointmentsOverview.service') }}</th>
                    <th>{{ t('appointmentsOverview.when') }}</th>
                    <th>{{ t('appointmentsOverview.status') }}</th>
                    <th>{{ t('appointmentsOverview.payment') }}</th>
                  </tr>
                </thead>
                <tbody>
                  @for (row of todayRows(); track row.id) {
                    <tr>
                      <td><strong>{{ row.patientName || '—' }}</strong></td>
                      <td>{{ row.service || '—' }}</td>
                      <td>{{ format.dateTime(row.appointmentDate) }}</td>
                      <td><span class="tl-badge">{{ statusLabel(row.status) }}</span></td>
                      <td>{{ paymentLabel(row.paymentStatus) }}</td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          }
        </section>
      </div>

      <section class="tl-card">
        <header class="tl-card-head">
          <h2>{{ t('appointmentsOverview.upcomingList') }}</h2>
        </header>
        @if (upcomingRows().length === 0) {
          <p class="tl-note">{{ t('appointmentsOverview.emptyUpcoming') }}</p>
        } @else {
          <div class="tl-table-wrap">
            <table class="tl-table">
              <thead>
                <tr>
                  <th>{{ t('appointmentsOverview.patient') }}</th>
                  <th>{{ t('appointmentsOverview.doctor') }}</th>
                  <th>{{ t('appointmentsOverview.service') }}</th>
                  <th>{{ t('appointmentsOverview.when') }}</th>
                  <th>{{ t('appointmentsOverview.status') }}</th>
                </tr>
              </thead>
              <tbody>
                @for (row of upcomingRows(); track row.id) {
                  <tr>
                    <td><strong>{{ row.patientName || '—' }}</strong></td>
                    <td>{{ row.doctorName || '—' }}</td>
                    <td>{{ row.service || '—' }}</td>
                    <td>{{ format.dateTime(row.appointmentDate) }}</td>
                    <td><span class="tl-badge">{{ statusLabel(row.status) }}</span></td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        }
      </section>
    </div>
  `,
  styles: [
    `
      .tl-page {
        padding: 12px 18px 28px;
        display: flex;
        flex-direction: column;
        gap: 14px;
      }
      .tl-breadcrumbs {
        display: flex;
        align-items: center;
        gap: 6px;
        font-size: 12px;
        color: var(--mute);
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
      .tl-page-head {
        display: flex;
        align-items: flex-start;
        justify-content: space-between;
        gap: 16px;
        flex-wrap: wrap;
      }
      .tl-page-head h1 {
        margin: 0 0 4px;
        font-size: 19px;
        color: var(--ink);
      }
      .tl-page-head p {
        margin: 0;
        font-size: 12.5px;
        color: var(--mute);
        max-width: 680px;
      }
      .tl-card {
        background: var(--surf);
        border: 1px solid var(--line);
        border-radius: 8px;
        padding: 14px 16px;
        box-shadow: var(--shadow-soft);
        min-width: 0;
      }
      .tl-filters {
        display: flex;
        align-items: flex-end;
        gap: 12px;
        flex-wrap: wrap;
      }
      .tl-field {
        display: flex;
        flex-direction: column;
        gap: 5px;
        font-size: 12px;
        color: var(--mute);
        font-weight: 600;
      }
      .tl-input {
        border: 1px solid var(--line);
        border-radius: 6px;
        background: var(--surf);
        color: var(--ink);
        font-size: 13px;
        padding: 0 10px;
        height: 36px;
        font-family: inherit;
        min-width: 170px;
      }
      .tl-btn {
        height: 36px;
        padding: 0 14px;
        border-radius: 6px;
        border: 1px solid transparent;
        font-size: 13px;
        font-weight: 600;
        cursor: pointer;
        display: inline-flex;
        align-items: center;
        justify-content: center;
        gap: 6px;
        text-decoration: none;
      }
      .tl-btn:disabled {
        opacity: 0.55;
        cursor: not-allowed;
      }
      .tl-btn-teal {
        background: var(--tl-teal);
        color: var(--on-accent);
      }
      .tl-link {
        border: none;
        background: transparent;
        color: var(--tl-teal);
        font-weight: 700;
        cursor: pointer;
        padding: 0;
      }
      .tl-stats-grid {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
        gap: 12px;
      }
      .tl-stat-card {
        background: var(--surf);
        border: 1px solid var(--line);
        border-radius: 8px;
        padding: 13px 15px;
        display: flex;
        flex-direction: column;
        gap: 8px;
        box-shadow: var(--shadow-soft);
      }
      .tl-stat-card span {
        font-size: 11.5px;
        color: var(--mute);
        font-weight: 700;
        text-transform: uppercase;
        letter-spacing: 0.02em;
      }
      .tl-stat-card strong {
        font-size: 22px;
        color: var(--ink);
        line-height: 1.1;
      }
      .tl-split {
        display: grid;
        grid-template-columns: minmax(260px, 0.9fr) minmax(0, 1.6fr);
        gap: 14px;
        align-items: start;
      }
      .tl-card-head {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: 10px;
        margin-bottom: 10px;
      }
      .tl-card-head h2 {
        margin: 0;
        font-size: 16px;
        color: var(--ink);
      }
      .tl-breakdown {
        list-style: none;
        margin: 0;
        padding: 0;
        display: flex;
        flex-direction: column;
        gap: 12px;
      }
      .tl-breakdown-head {
        display: flex;
        align-items: center;
        justify-content: space-between;
        font-size: 12.5px;
        color: var(--ink);
        margin-bottom: 5px;
      }
      .tl-breakdown-head strong {
        font-size: 13px;
      }
      .tl-breakdown-track {
        height: 8px;
        border-radius: 999px;
        background: var(--surf2);
        overflow: hidden;
      }
      .tl-breakdown-fill {
        height: 100%;
        border-radius: 999px;
        background: var(--tl-teal);
        min-width: 2px;
      }
      .tl-breakdown-fill.is-green {
        background: var(--accent-green);
      }
      .tl-breakdown-fill.is-amber {
        background: var(--accent-amber);
      }
      .tl-breakdown-fill.is-red {
        background: var(--accent-red);
      }
      .tl-table-wrap {
        overflow-x: auto;
      }
      .tl-table {
        width: 100%;
        border-collapse: collapse;
        font-size: 13px;
      }
      .tl-table th {
        text-align: left;
        font-size: 11px;
        text-transform: uppercase;
        letter-spacing: 0.03em;
        color: var(--mute);
        padding: 8px 10px;
        border-bottom: 1px solid var(--line);
        background: var(--surf2);
        white-space: nowrap;
      }
      .tl-table td {
        padding: 9px 10px;
        border-bottom: 1px solid var(--line);
        color: var(--ink);
        vertical-align: middle;
      }
      .tl-badge {
        display: inline-block;
        padding: 3px 9px;
        border-radius: 999px;
        font-weight: 700;
        font-size: 11px;
        background: var(--tl-teal-soft);
        color: var(--tl-teal-hover);
        white-space: nowrap;
      }
      .tl-note {
        display: flex;
        flex-direction: column;
        gap: 4px;
        font-size: 13px;
        color: var(--mute);
        padding: 8px 2px;
        margin: 0;
      }
      .tl-note.is-error {
        color: var(--accent-red-ink);
        flex-direction: row;
        align-items: center;
        gap: 10px;
        padding: 14px 16px;
      }
      @media (max-width: 980px) {
        .tl-split {
          grid-template-columns: minmax(0, 1fr);
        }
      }
    `,
  ],
})
export class ClinicAppointmentsOverviewComponent implements OnInit {
  private readonly clinic = inject(ClinicApi);
  private readonly api = inject(ClinicPatientApi);
  private readonly projects = inject(ProjectContextService);
  private readonly i18n = inject(I18nService);
  protected readonly format = inject(FormatService);

  protected readonly window = signal('30d');
  protected readonly report = signal<ClinicReportView | null>(null);
  protected readonly todayRows = signal<readonly ClinicAppointment[]>([]);
  protected readonly upcomingRows = signal<readonly ClinicAppointment[]>([]);
  protected readonly loading = signal(false);
  protected readonly error = signal(false);

  protected readonly breakdown = computed<readonly BreakdownRow[]>(() => {
    const metrics = this.report()?.appointments;
    if (!metrics || metrics.totalAppointments <= 0) return [];
    const total = metrics.totalAppointments;
    const candidates = [
      { key: 'completed', label: this.t('appointmentsOverview.completed'), count: metrics.completedCount, tone: 'green' },
      { key: 'confirmed', label: this.t('appointmentsOverview.confirmed'), count: metrics.confirmedCount, tone: 'teal' },
      { key: 'cancelled', label: this.t('appointmentsOverview.cancelled'), count: metrics.cancelledCount, tone: 'red' },
      { key: 'noShow', label: this.t('appointmentsOverview.noShow'), count: metrics.noShowCount, tone: 'amber' },
    ] satisfies readonly Omit<BreakdownRow, 'share'>[];
    const rows = candidates.filter((row) => row.count > 0);
    return rows.map((row) => ({
      ...row,
      share: Math.round((row.count / total) * 100),
    }));
  });

  constructor() {
    this.i18n.register({ clinicAdmin: clinicAdminStrings as Namespace });
  }

  protected t(key: string): string {
    return this.i18n.t(`clinicAdmin.${key}`);
  }

  ngOnInit(): void {
    void this.load();
  }

  protected metric(
    key: 'totalAppointments' | 'completedCount' | 'confirmedCount' | 'cancelledCount' | 'noShowCount',
  ): number {
    return this.report()?.appointments?.[key] ?? 0;
  }

  protected cancellationRate(): string {
    const value = this.report()?.appointments?.cancellationRatePercent ?? 0;
    return this.format.percentOfHundred(value, 1);
  }

  protected async load(): Promise<void> {
    if (this.loading()) return;
    this.loading.set(true);
    this.error.set(false);
    const projectId = this.projects.selectedProjectId() ?? undefined;
    const query = projectId ? { projectId } : {};
    const today = todayIso();
    try {
      const [report, todayPage, upcomingPage] = await Promise.all([
        this.api.reports({ ...query, window: this.window() }),
        this.clinic.appointments({
          ...query,
          from: `${today}T00:00:00`,
          to: `${today}T23:59:59`,
          limit: 100,
        }),
        this.clinic.appointments({
          ...query,
          from: `${addDays(today, 1)}T00:00:00`,
          to: `${addDays(today, 30)}T23:59:59`,
          limit: 50,
        }),
      ]);
      this.report.set(report);
      this.todayRows.set(sortByDate(todayPage.items ?? []));
      this.upcomingRows.set(sortByDate(upcomingPage.items ?? []).slice(0, 10));
    } catch {
      this.error.set(true);
      this.report.set(null);
      this.todayRows.set([]);
      this.upcomingRows.set([]);
    } finally {
      this.loading.set(false);
    }
  }

  protected statusLabel(status: string | undefined): string {
    if (!status) return '—';
    return status
      .toLowerCase()
      .split('_')
      .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
      .join(' ');
  }

  protected paymentLabel(status: string | null | undefined): string {
    if (!status) return '—';
    return status
      .toLowerCase()
      .split('_')
      .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
      .join(' ');
  }
}

function todayIso(): string {
  return localDate(new Date());
}

function localDate(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

function addDays(iso: string, days: number): string {
  const date = new Date(`${iso}T12:00:00`);
  date.setDate(date.getDate() + days);
  return localDate(date);
}

function sortByDate(rows: readonly ClinicAppointment[]): readonly ClinicAppointment[] {
  return [...rows].sort((a, b) =>
    (a.appointmentDate ?? '').localeCompare(b.appointmentDate ?? ''),
  );
}
