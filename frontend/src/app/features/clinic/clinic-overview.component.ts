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
import {
  ClinicApi,
  type ClinicAppointment,
  type ClinicDoctor,
  type ClinicLocation,
  type ClinicProcedure,
} from '../../core/api/endpoints';
import { ProjectContextService } from '../../core/context/project-context.service';
import { FormatService } from '../../core/format/format.service';
import { I18nService } from '../../core/i18n/i18n.service';
import type { Namespace } from '../../core/i18n/i18n.types';
import { clinicAdminStrings } from './clinic-admin.strings';
import {
  ClinicPatientApi,
  type ClinicPaymentRow,
  type ClinicReportView,
} from './clinic-patient.api';

interface MonthPoint {
  readonly key: string;
  readonly label: string;
  readonly total: number;
}

/**
 * `/app/clinic/overview` -- the clinic performance board.
 *
 * Every number is read from the server: roster and catalogue counts come from the
 * clinic read models, the appointment and age bands come from `/clinic/reports`,
 * and the monthly revenue series is the sum of recorded payments. Nothing here is
 * a mocked figure.
 */
@Component({
  selector: 'fe-clinic-overview',
  standalone: true,
  imports: [CommonModule, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="tl-page" data-testid="clinic-overview">
      <div class="tl-breadcrumbs">
        <a routerLink="/app/dashboard">{{ t('nav.home') }}</a>
        <span>»</span>
        <span class="active">{{ t('overview.breadcrumb') }}</span>
      </div>

      <header class="tl-page-head">
        <div>
          <h1>{{ t('overview.title') }}</h1>
          <p>{{ t('overview.subtitle') }}</p>
        </div>
        <div class="tl-head-actions">
          <a class="tl-btn tl-btn-outline" routerLink="/app/clinic/appointments">
            {{ t('overview.openAppointments') }}
          </a>
          <a class="tl-btn tl-btn-outline" routerLink="/app/clinic/reports">
            {{ t('overview.openReports') }}
          </a>
        </div>
      </header>

      @if (error()) {
        <div class="tl-card tl-note is-error">
          <span>{{ t('overview.loadFailed') }}</span>
          <button type="button" class="tl-link" (click)="load()">{{ t('common.retry') }}</button>
        </div>
      }

      <div class="tl-overview-grid">
        <div class="tl-overview-main">
          <div class="tl-section-head">
            <h2>{{ t('overview.performance') }}</h2>
            <div class="tl-date-submit">
              <input
                type="date"
                class="tl-input"
                [value]="asOf()"
                (change)="asOf.set($any($event.target).value)"
              />
              <button type="button" class="tl-btn tl-btn-dark" [disabled]="loading()" (click)="load()">
                {{ t('overview.submit') }}
              </button>
            </div>
          </div>

          <section class="tl-stats-grid">
            <article class="tl-stat-card">
              <span>{{ t('overview.clinics') }}</span>
              <div class="tl-stat-row">
                <strong>{{ loading() ? '—' : locations().length }}</strong>
                <span class="tl-stat-icon" aria-hidden="true">▦</span>
              </div>
            </article>
            <article class="tl-stat-card">
              <span>{{ t('overview.services') }}</span>
              <div class="tl-stat-row">
                <strong>{{ loading() ? '—' : activeProcedures() }}</strong>
                <span class="tl-stat-icon" aria-hidden="true">✚</span>
              </div>
            </article>
            <article class="tl-stat-card">
              <span>{{ t('overview.appointments') }}</span>
              <div class="tl-stat-row">
                <strong>{{ loading() ? '—' : report()?.appointments?.totalAppointments ?? 0 }}</strong>
                <span class="tl-stat-icon" aria-hidden="true">▤</span>
              </div>
            </article>
            <article class="tl-stat-card">
              <span>{{ t('overview.doctors') }}</span>
              <div class="tl-stat-row">
                <strong>{{ loading() ? '—' : doctors().length }}</strong>
                <span class="tl-stat-icon" aria-hidden="true">◕</span>
              </div>
            </article>
            <article class="tl-stat-card">
              <span>{{ t('overview.patients') }}</span>
              <div class="tl-stat-row">
                <strong>{{ loading() ? '—' : report()?.patients?.totalPatients ?? 0 }}</strong>
                <span class="tl-stat-icon" aria-hidden="true">◔</span>
              </div>
            </article>
            <article class="tl-stat-card">
              <span>{{ t('overview.revenue') }}</span>
              <div class="tl-stat-row">
                <strong>{{ loading() ? '—' : money(totalRevenue()) }}</strong>
                <span class="tl-stat-icon" aria-hidden="true">◫</span>
              </div>
            </article>
          </section>

          <section class="tl-card tl-chart-card">
            <header class="tl-card-head">
              <h2>{{ t('overview.totalRevenue') }}</h2>
              <span class="tl-chip">{{ t('overview.year') }}</span>
            </header>
            <div class="tl-bars" data-testid="clinic-overview-revenue-chart">
              @for (point of monthlyRevenue(); track point.key) {
                <div class="tl-bar-col">
                  <span class="tl-bar-value">{{ point.total > 0 ? money(point.total) : '' }}</span>
                  <div class="tl-bar-track">
                    <div class="tl-bar-fill" [style.height.%]="barHeight(point.total)"></div>
                  </div>
                  <span class="tl-bar-label">{{ point.label }}</span>
                </div>
              }
            </div>
          </section>

          <section class="tl-card">
            <header class="tl-card-head">
              <h2>{{ t('overview.today') }}</h2>
            </header>
            @if (todayRows().length === 0) {
              <p class="tl-note">{{ t('appointmentsOverview.emptyToday') }}</p>
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
                    @for (row of todayRows(); track row.id) {
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

        <aside class="tl-overview-side">
          <section class="tl-card">
            <header class="tl-card-head">
              <h2>{{ t('overview.visitedByAge') }}</h2>
            </header>
            <div class="tl-age-chart">
              <div class="tl-age-col">
                <div class="tl-age-track">
                  <div
                    class="tl-age-fill is-young"
                    [style.height.%]="ageHeight(ageBands().young)"
                  ></div>
                </div>
                <strong>{{ ageBands().young }}</strong>
                <span>{{ t('overview.age0to25') }}</span>
              </div>
              <div class="tl-age-col">
                <div class="tl-age-track">
                  <div
                    class="tl-age-fill is-adult"
                    [style.height.%]="ageHeight(ageBands().adult)"
                  ></div>
                </div>
                <strong>{{ ageBands().adult }}</strong>
                <span>{{ t('overview.age26to50') }}</span>
              </div>
              <div class="tl-age-col">
                <div class="tl-age-track">
                  <div
                    class="tl-age-fill is-senior"
                    [style.height.%]="ageHeight(ageBands().senior)"
                  ></div>
                </div>
                <strong>{{ ageBands().senior }}</strong>
                <span>{{ t('overview.age50Plus') }}</span>
              </div>
            </div>
          </section>

          <section class="tl-card tl-upcoming">
            <header class="tl-card-head">
              <h2>{{ t('overview.upcoming') }}</h2>
            </header>
            @if (upcomingRows().length === 0) {
              <p class="tl-note">{{ t('overview.noUpcoming') }}</p>
            } @else {
              <ul class="tl-upcoming-list">
                @for (row of upcomingRows(); track row.id) {
                  <li>
                    <strong>{{ row.patientName || '—' }}</strong>
                    <span>{{ format.dateTime(row.appointmentDate) }}</span>
                    <span>{{ row.service || '—' }}</span>
                  </li>
                }
              </ul>
            }
          </section>
        </aside>
      </div>
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
        font-size: 21px;
        color: var(--ink);
      }
      .tl-page-head p {
        margin: 0;
        font-size: 12.5px;
        color: var(--mute);
      }
      .tl-head-actions {
        display: flex;
        gap: 8px;
        flex-wrap: wrap;
      }
      .tl-overview-grid {
        display: grid;
        grid-template-columns: minmax(0, 2.1fr) minmax(280px, 1fr);
        gap: 14px;
        align-items: start;
      }
      .tl-overview-main,
      .tl-overview-side {
        display: flex;
        flex-direction: column;
        gap: 14px;
        min-width: 0;
      }
      .tl-section-head {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: 12px;
        flex-wrap: wrap;
      }
      .tl-section-head h2 {
        margin: 0;
        font-size: 18px;
        color: var(--ink);
      }
      .tl-date-submit {
        display: flex;
        gap: 8px;
      }
      .tl-card {
        background: var(--surf);
        border: 1px solid var(--line);
        border-radius: 8px;
        padding: 14px 16px;
        box-shadow: var(--shadow-soft);
      }
      .tl-card-head {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: 10px;
        margin-bottom: 12px;
      }
      .tl-card-head h2 {
        margin: 0;
        font-size: 16px;
        color: var(--ink);
      }
      .tl-chip {
        background: var(--surf2);
        border: 1px solid var(--line);
        border-radius: 6px;
        padding: 4px 10px;
        font-size: 12px;
        font-weight: 700;
        color: var(--ink);
      }
      .tl-stats-grid {
        display: grid;
        grid-template-columns: repeat(3, minmax(0, 1fr));
        gap: 12px;
      }
      .tl-stat-card {
        background: var(--surf);
        border: 1px solid var(--line);
        border-radius: 8px;
        padding: 14px 16px;
        display: flex;
        flex-direction: column;
        gap: 12px;
        min-height: 92px;
        box-shadow: var(--shadow-soft);
      }
      .tl-stat-card > span {
        font-size: 12.5px;
        color: var(--mute);
        font-weight: 600;
      }
      .tl-stat-row {
        display: flex;
        align-items: flex-end;
        justify-content: space-between;
        gap: 8px;
      }
      .tl-stat-row strong {
        font-size: 22px;
        color: var(--ink);
        line-height: 1.1;
      }
      .tl-stat-icon {
        color: var(--tl-teal);
        font-size: 22px;
        line-height: 1;
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
      .tl-btn-outline {
        background: var(--surf2);
        border-color: var(--line);
        color: var(--ink);
      }
      .tl-btn-dark {
        background: var(--ink-strong);
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
      .tl-bars {
        display: grid;
        grid-template-columns: repeat(12, minmax(0, 1fr));
        gap: 8px;
        align-items: end;
        min-height: 200px;
      }
      .tl-bar-col {
        display: flex;
        flex-direction: column;
        align-items: center;
        gap: 6px;
        height: 200px;
        justify-content: flex-end;
      }
      .tl-bar-value {
        font-size: 9.5px;
        color: var(--mute);
        white-space: nowrap;
      }
      .tl-bar-track {
        width: 100%;
        flex: 1;
        display: flex;
        align-items: flex-end;
        border-bottom: 1px solid var(--line);
      }
      .tl-bar-fill {
        width: 100%;
        min-height: 2px;
        background: var(--tl-teal);
        border-radius: 4px 4px 0 0;
      }
      .tl-bar-label {
        font-size: 10.5px;
        color: var(--mute);
      }
      .tl-age-chart {
        display: grid;
        grid-template-columns: repeat(3, 1fr);
        gap: 12px;
        align-items: end;
        padding-top: 6px;
      }
      .tl-age-col {
        display: flex;
        flex-direction: column;
        align-items: center;
        gap: 5px;
      }
      .tl-age-track {
        width: 100%;
        height: 140px;
        display: flex;
        align-items: flex-end;
        border-bottom: 1px solid var(--line);
      }
      .tl-age-fill {
        width: 100%;
        min-height: 4px;
        border-radius: 4px 4px 0 0;
      }
      .tl-age-fill.is-young {
        background: var(--tl-teal);
      }
      .tl-age-fill.is-adult {
        background: var(--accent-amber);
      }
      .tl-age-fill.is-senior {
        background: var(--accent-red-400);
      }
      .tl-age-col strong {
        font-size: 15px;
        color: var(--ink);
      }
      .tl-age-col span {
        font-size: 11px;
        color: var(--mute);
        text-align: center;
      }
      .tl-upcoming-list {
        list-style: none;
        margin: 0;
        padding: 0;
        display: flex;
        flex-direction: column;
        gap: 10px;
      }
      .tl-upcoming-list li {
        display: flex;
        flex-direction: column;
        gap: 2px;
        padding-bottom: 10px;
        border-bottom: 1px solid var(--line);
      }
      .tl-upcoming-list li:last-child {
        border-bottom: none;
        padding-bottom: 0;
      }
      .tl-upcoming-list strong {
        font-size: 13px;
        color: var(--ink);
      }
      .tl-upcoming-list span {
        font-size: 12px;
        color: var(--mute);
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
      }
      .tl-badge {
        display: inline-block;
        padding: 3px 9px;
        border-radius: 999px;
        font-weight: 700;
        font-size: 11px;
        background: var(--tl-teal-soft);
        color: var(--tl-teal-hover);
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
      @media (max-width: 1080px) {
        .tl-overview-grid {
          grid-template-columns: minmax(0, 1fr);
        }
        .tl-stats-grid {
          grid-template-columns: repeat(2, minmax(0, 1fr));
        }
      }
      @media (max-width: 620px) {
        .tl-stats-grid {
          grid-template-columns: minmax(0, 1fr);
        }
      }
    `,
  ],
})
export class ClinicOverviewComponent implements OnInit {
  private readonly clinic = inject(ClinicApi);
  private readonly api = inject(ClinicPatientApi);
  private readonly projects = inject(ProjectContextService);
  private readonly i18n = inject(I18nService);
  protected readonly format = inject(FormatService);

  protected readonly locations = signal<readonly ClinicLocation[]>([]);
  protected readonly procedures = signal<readonly ClinicProcedure[]>([]);
  protected readonly doctors = signal<readonly ClinicDoctor[]>([]);
  protected readonly report = signal<ClinicReportView | null>(null);
  protected readonly todayRows = signal<readonly ClinicAppointment[]>([]);
  protected readonly upcomingRows = signal<readonly ClinicAppointment[]>([]);
  protected readonly monthlyRevenue = signal<readonly MonthPoint[]>([]);
  protected readonly loading = signal(false);
  protected readonly error = signal(false);
  protected readonly asOf = signal(todayIso());

  protected readonly activeProcedures = computed(
    () => this.procedures().filter((row) => row.isActive !== false).length,
  );

  protected readonly totalRevenue = computed(() => {
    const financials = this.report()?.financials ?? [];
    return financials.reduce((sum, row) => sum + (row.totalCollected ?? 0), 0);
  });

  protected readonly ageBands = computed(() => {
    const bands = this.report()?.patientAgeDistribution;
    return {
      young: bands?.age0to25 ?? 0,
      adult: bands?.age26to50 ?? 0,
      senior: bands?.age50Plus ?? 0,
    };
  });

  private readonly ageMax = computed(() => {
    const bands = this.ageBands();
    return Math.max(1, bands.young, bands.adult, bands.senior);
  });

  private readonly revenueMax = computed(() =>
    Math.max(1, ...this.monthlyRevenue().map((point) => point.total)),
  );

  constructor() {
    this.i18n.register({ clinicAdmin: clinicAdminStrings as Namespace });
  }

  protected t(key: string): string {
    return this.i18n.t(`clinicAdmin.${key}`);
  }

  ngOnInit(): void {
    void this.load();
  }

  protected async load(): Promise<void> {
    if (this.loading()) return;
    this.loading.set(true);
    this.error.set(false);
    const projectId = this.projects.selectedProjectId() ?? undefined;
    const query = projectId ? { projectId } : {};
    const anchor = this.asOf();
    const dayStart = `${anchor}T00:00:00`;
    const dayEnd = `${anchor}T23:59:59`;
    const upcomingFrom = `${anchor}T00:00:00`;
    const upcomingTo = `${addDays(anchor, 30)}T23:59:59`;
    const paymentsFrom = `${addMonths(anchor, -11).slice(0, 7)}-01T00:00:00`;

    try {
      const [locations, procedures, doctors, report, today, upcoming, payments] =
        await Promise.all([
          this.clinic.locations({ ...query, limit: 200 }),
          this.clinic.procedures({ ...query, limit: 200 }),
          this.clinic.doctors({ ...query, limit: 200 }),
          this.api.reports({ ...query, window: '365d', end: `${anchor}T23:59:59` }),
          this.clinic.appointments({ ...query, from: dayStart, to: dayEnd, limit: 100 }),
          this.clinic.appointments({
            ...query,
            from: upcomingFrom,
            to: upcomingTo,
            limit: 50,
          }),
          this.api.payments({ ...query, from: paymentsFrom, limit: 500 }),
        ]);

      this.locations.set(locations.items ?? []);
      this.procedures.set(procedures.items ?? []);
      this.doctors.set(doctors.items ?? []);
      this.report.set(report);
      this.todayRows.set(sortByDate(today.items ?? []));
      this.upcomingRows.set(
        sortByDate(upcoming.items ?? [])
          .filter((row) => (row.appointmentDate ?? '') >= upcomingFrom)
          .slice(0, 5),
      );
      this.monthlyRevenue.set(buildMonthlySeries(payments, anchor, this.i18n.intlLocale()));
    } catch {
      this.error.set(true);
      this.report.set(null);
      this.todayRows.set([]);
      this.upcomingRows.set([]);
      this.monthlyRevenue.set([]);
    } finally {
      this.loading.set(false);
    }
  }

  protected money(value: number | null | undefined): string {
    const currency = this.report()?.financials?.[0]?.currency ?? 'JOD';
    return this.format.currency(value ?? 0, currency);
  }

  protected barHeight(value: number): number {
    return Math.max(2, Math.round((value / this.revenueMax()) * 100));
  }

  protected ageHeight(value: number): number {
    return Math.max(2, Math.round((value / this.ageMax()) * 100));
  }

  protected statusLabel(status: string | undefined): string {
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

function addMonths(iso: string, months: number): string {
  const date = new Date(`${iso}T12:00:00`);
  date.setMonth(date.getMonth() + months);
  return localDate(date);
}

function sortByDate(rows: readonly ClinicAppointment[]): readonly ClinicAppointment[] {
  return [...rows].sort((a, b) =>
    (a.appointmentDate ?? '').localeCompare(b.appointmentDate ?? ''),
  );
}

function buildMonthlySeries(
  payments: readonly ClinicPaymentRow[],
  anchor: string,
  locale: string,
): readonly MonthPoint[] {
  const anchorDate = new Date(`${anchor}T12:00:00`);
  const totals = new Map<string, number>();
  const labels = new Map<string, string>();
  const formatter = new Intl.DateTimeFormat(locale, { month: 'short' });
  const keys: string[] = [];
  for (let offset = 11; offset >= 0; offset -= 1) {
    const date = new Date(anchorDate.getFullYear(), anchorDate.getMonth() - offset, 1);
    const key = `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`;
    keys.push(key);
    totals.set(key, 0);
    labels.set(key, formatter.format(date));
  }
  for (const payment of payments) {
    const stamp = payment.paymentDate;
    if (!stamp) continue;
    const key = stamp.slice(0, 7);
    if (!totals.has(key)) continue;
    totals.set(key, (totals.get(key) ?? 0) + (payment.amount ?? 0));
  }
  return keys.map((key) => ({
    key,
    label: labels.get(key) ?? key,
    total: totals.get(key) ?? 0,
  }));
}
