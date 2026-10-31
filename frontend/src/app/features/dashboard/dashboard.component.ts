/**
 * ToothLogic Dental Practice All-in-One Dashboard.
 * Exact replication of ToothLogic-All-in-One-Dashboard.png screenshot.
 *
 * ## What the reference's two controls do
 *
 * The reference shows a "Performance" bar carrying a "Select Date" control and a
 * "Submit" button, and a "Total Revenue" chart header carrying a "Year" button.
 * A screenshot cannot say what a control *does*, so the reading implemented here
 * is the one the rest of the product supports:
 *
 * - **Select Date + Submit** applies one day to the Performance section. The
 *   date sitting in the input is not applied until Submit is pressed, which is
 *   the only reason the two are a pair rather than a single control.
 * - **Year** is a period selector on the revenue chart, not a year picker: it
 *   sits in the chart's own header beside the chart title, so it changes the
 *   span the chart covers.
 *
 * ## Where every figure comes from
 *
 * | Card | Source | Permission |
 * |---|---|---|
 * | Total Number of Clinics | `session.projectIds` — a clinic is a project | none |
 * | Total Active Services | `GET /clinic/procedures` | `procedure:read` |
 * | Total Appointments | `GET /clinic/reports` → `appointments.totalAppointments` | `appointment:read` |
 * | Total Number of Patients | `GET /clinic/reports` → `patients.totalPatients` | `appointment:read` |
 * | Total Revenue | `GET /clinic/reports` → `financials[].totalInvoiced` | `appointment:read` |
 * | Total Number of Doctors | `GET /clinic/doctors` | `doctor:read` |
 * | Visited Patient by age | `GET /clinic/reports` → `patientAgeDistribution` | `appointment:read` |
 * | Upcoming Appointment | `GET /clinic/appointments` from now | `appointment:read` |
 * | Total Revenue chart | `GET /clinic/reports`, once per bucket | `appointment:read` |
 *
 * A card whose source could not be read renders an em dash rather than the
 * reference's placeholder number: a figure with no row behind it is the defect
 * this codebase exists to avoid.
 *
 * ## A card the caller may not read is never requested
 *
 * The routes above are permission-guarded on the server, so asking for one the
 * caller cannot read is a request that can only fail — and a 403 on a card the
 * server never offered is noise on the console and a lie on the screen. The
 * dashboard therefore asks for exactly the routes the caller's effective
 * permissions cover, and says on the screen that some figures are withheld,
 * because the server decided that and not the application.
 *
 * ## The chart is a real series, not a drawing
 *
 * The reference's revenue curve is a picture of a month-by-month series. The
 * curve here is built from the same reports the cards use, one report per
 * bucket, so a point on the chart is a figure the reader can also ask for.
 *
 * ## The copy is translated, the pixels are not
 *
 * The replica reproduces the reference's layout; the words come from
 * `dashboard.strings.ts`, because a landing screen that only speaks English is
 * a landing screen that only half the clinic can read.
 */
import { ChangeDetectionStrategy, Component, ElementRef, OnInit, computed, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { ClinicApi, type ClinicAppointmentPage } from '../../core/api/endpoints';
import { ApiError } from '../../core/api/api.service';
import { PERMISSIONS } from '../../core/auth/permissions';
import { SessionService } from '../../core/auth/session.service';
import { SessionStore } from '../../core/auth/session.store';
import { FormatService } from '../../core/format/format.service';
import { I18nService } from '../../core/i18n/i18n.service';
import type { Namespace } from '../../core/i18n/i18n.types';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { MotionService } from '../../core/motion/motion.service';
import {
  ClinicPatientApi,
  type ClinicReportView,
} from '../clinic/clinic-patient.api';
import { dashboardStrings } from './dashboard.strings';

/** The span the revenue chart covers. The reference shows "Year" as the default. */
type ChartPeriod = 'YEAR' | 'QUARTER' | 'MONTH' | 'WEEK';

interface PeriodOption {
  readonly id: ChartPeriod;
  readonly labelKey: string;
}

interface ChartBucket {
  readonly label: string;
  readonly start: string;
  readonly end: string;
}

interface ChartSeriesPoint {
  readonly label: string;
  readonly value: number;
}

interface ChartPoint extends ChartSeriesPoint {
  readonly x: number;
  readonly y: number;
}

interface UpcomingApt {
  id: string;
  patientName: string;
  doctorName: string;
  service: string;
  formattedTime: string;
}

const MONTH_LABELS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const DAY_LABELS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];

const SVG_WIDTH = 1000;
const PLOT_TOP = 25;
const PLOT_BASELINE = 175;

function generateSmoothSpline(points: readonly ChartPoint[], baseline: number, top: number): string {
  if (points.length === 0) return '';
  if (points.length === 1) return `M ${points[0].x.toFixed(1)} ${points[0].y.toFixed(1)}`;

  let path = `M ${points[0].x.toFixed(1)} ${points[0].y.toFixed(1)}`;

  for (let i = 0; i < points.length - 1; i++) {
    const p1 = points[i];
    const p2 = points[i + 1];

    if (p1.y >= baseline - 0.5 && p2.y >= baseline - 0.5) {
      path += ` L ${p2.x.toFixed(1)} ${baseline.toFixed(1)}`;
      continue;
    }

    const p0 = i > 0 ? points[i - 1] : points[i];
    const p3 = i < points.length - 2 ? points[i + 2] : p2;

    const tension = 0.22;
    const dx = p2.x - p1.x;

    let cp1x = p1.x + dx * tension * 2;
    let cp1y = p1.y + (p2.y - p0.y) * tension;
    let cp2x = p2.x - dx * tension * 2;
    let cp2y = p2.y - (p3.y - p1.y) * tension;

    cp1y = Math.min(baseline, Math.max(top, cp1y));
    cp2y = Math.min(baseline, Math.max(top, cp2y));

    path += ` C ${cp1x.toFixed(1)} ${cp1y.toFixed(1)}, ${cp2x.toFixed(1)} ${cp2y.toFixed(1)}, ${p2.x.toFixed(1)} ${p2.y.toFixed(1)}`;
  }

  return path;
}

function pad(value: number): string {
  return String(value).padStart(2, '0');
}

/** A local `yyyy-MM-dd` — the shape an `<input type="date">` reads back. */
function toDateInputValue(date: Date): string {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

/**
 * A local `yyyy-MM-ddTHH:mm:ss`, with no offset.
 *
 * The report route takes a `LocalDateTime` (`ISO.DATE_TIME`), and a `Z` suffix
 * would shift the window the server applies — so the offset is deliberately
 * absent rather than merely omitted.
 */
function toLocalDateTime(date: Date): string {
  return `${toDateInputValue(date)}T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}

@Component({
  selector: 'fe-dashboard-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, TranslatePipe],
  templateUrl: './dashboard.component.html',
  styleUrl: './dashboard.component.css',
})
export class DashboardComponent implements OnInit {
  private readonly hostRef = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly router = inject(Router);
  private readonly clinicApi = inject(ClinicApi);
  private readonly patientApi = inject(ClinicPatientApi);
  private readonly sessions = inject(SessionService);
  private readonly i18n = inject(I18nService);
  protected readonly format = inject(FormatService);
  protected readonly store = inject(SessionStore);
  private readonly motion = inject(MotionService);

  constructor() {
    this.i18n.register({ dashboard: dashboardStrings as Namespace });
  }

  protected readonly periodOptions: readonly PeriodOption[] = [
    { id: 'YEAR', labelKey: 'dashboard.period.year' },
    { id: 'QUARTER', labelKey: 'dashboard.period.quarter' },
    { id: 'MONTH', labelKey: 'dashboard.period.month' },
    { id: 'WEEK', labelKey: 'dashboard.period.week' },
  ];

  /** The date in the input. It is not applied until Submit is pressed. */
  protected readonly draftDate = signal<string>(toDateInputValue(new Date()));
  /** The date the figures on screen were computed for. */
  protected readonly appliedDate = signal<string>(toDateInputValue(new Date()));
  protected readonly chartPeriod = signal<ChartPeriod>('YEAR');
  protected readonly periodMenuOpen = signal(false);

  protected readonly loading = signal(false);
  protected readonly chartLoading = signal(false);
  protected readonly performanceError = signal<string | null>(null);
  protected readonly performanceRequestId = signal<string | null>(null);

  protected readonly report = signal<ClinicReportView | null>(null);
  protected readonly chartSeries = signal<readonly ChartSeriesPoint[]>([]);

  // -- what the caller may read -------------------------------------------

  protected readonly canAppointments = computed(() =>
    this.sessions.can()(PERMISSIONS.appointmentRead),
  );
  protected readonly canProcedures = computed(() =>
    this.sessions.can()(PERMISSIONS.procedureRead),
  );
  protected readonly canDoctors = computed(() => this.sessions.can()(PERMISSIONS.doctorRead));

  /** True when at least one card's source is behind a permission they lack. */
  protected readonly withheld = computed(
    () => !this.canAppointments() || !this.canProcedures() || !this.canDoctors(),
  );

  // -- the greeting --------------------------------------------------------

  protected readonly greeting = computed<string>(() => {
    const hour = new Date().getHours();
    if (hour < 12) return this.i18n.t('dashboard.greeting.morning');
    if (hour < 17) return this.i18n.t('dashboard.greeting.afternoon');
    return this.i18n.t('dashboard.greeting.evening');
  });

  /**
   * The signed-in person's name.
   *
   * There is no fallback name. A dashboard that greets a stranger by a name it
   * invented is showing a figure with no row behind it, which is the one thing
   * this screen refuses to do.
   */
  protected readonly userName = computed(() => this.store.fullName() || this.format.dash);

  /** A clinic is a project, and the session already resolved the user's projects. */
  protected readonly clinicCount = computed(() => this.store.projectIds().length);

  protected readonly appointmentCount = computed<number | null>(
    () => this.report()?.appointments?.totalAppointments ?? null,
  );
  protected readonly patientCount = computed<number | null>(
    () => this.report()?.patients?.totalPatients ?? null,
  );
  protected readonly serviceCount = signal<number | null>(null);
  protected readonly doctorCount = signal<number | null>(null);

  protected readonly revenueCurrency = signal<string | null>(null);
  protected readonly totalRevenueAmount = computed<number | null>(() => {
    const financials = this.report()?.financials ?? [];
    if (financials.length === 0) return null;
    return financials.reduce((sum, row) => sum + (row.totalInvoiced ?? 0), 0);
  });
  protected readonly totalRevenue = computed<string>(() => {
    const amount = this.totalRevenueAmount();
    const currency = this.revenueCurrency();
    if (amount === null || currency === null) return this.format.dash;
    return this.format.currency(amount, currency, 0);
  });

  protected readonly patientAge0to25 = computed<number | null>(
    () => this.report()?.patientAgeDistribution?.age0to25 ?? null,
  );
  protected readonly patientAge26to50 = computed<number | null>(
    () => this.report()?.patientAgeDistribution?.age26to50 ?? null,
  );
  protected readonly patientAge50Plus = computed<number | null>(
    () => this.report()?.patientAgeDistribution?.age50Plus ?? null,
  );

  protected readonly upcomingAppointments = signal<readonly UpcomingApt[]>([]);

  /** The window the Performance section is showing, in words. */
  protected readonly windowLabel = computed<string>(() =>
    this.i18n.t('dashboard.performance.window', {
      date: this.format.calendarDate(this.appliedDate()),
    }),
  );

  protected readonly periodLabel = computed<string>(() => {
    const option = this.periodOptions.find((o) => o.id === this.chartPeriod());
    return option ? this.i18n.t(option.labelKey) : this.i18n.t('dashboard.period.year');
  });

  /** The chart's series, positioned in the modern 1000x200 viewBox. */
  protected readonly chartPoints = computed<readonly ChartPoint[]>(() => {
    const series = this.chartSeries();
    if (series.length === 0) return [];
    const max = Math.max(...series.map((p) => p.value), 1);
    const n = series.length;
    const colWidth = SVG_WIDTH / n;
    return series.map((point, index) => ({
      ...point,
      x: (index + 0.5) * colWidth,
      y: PLOT_BASELINE - (point.value / max) * (PLOT_BASELINE - PLOT_TOP),
    }));
  });

  protected readonly chartMax = computed<number>(() => {
    const series = this.chartSeries();
    return series.length === 0 ? 0 : Math.max(...series.map((p) => p.value));
  });

  protected readonly chartMid = computed<number>(() => {
    return Math.round(this.chartMax() / 2);
  });

  protected readonly chartLinePath = computed<string>(() =>
    generateSmoothSpline(this.chartPoints(), PLOT_BASELINE, PLOT_TOP),
  );

  protected readonly chartAreaPath = computed<string>(() => {
    const points = this.chartPoints();
    if (points.length === 0) return '';
    const first = points[0];
    const last = points[points.length - 1];
    return `${this.chartLinePath()} L ${last.x.toFixed(1)} ${PLOT_BASELINE} L ${first.x.toFixed(1)} ${PLOT_BASELINE} Z`;
  });

  protected readonly hasChartData = computed(() =>
    this.chartSeries().some((point) => point.value > 0),
  );

  protected readonly hoveredPoint = signal<ChartPoint | null>(null);

  protected setHoveredPoint(point: ChartPoint | null): void {
    this.hoveredPoint.set(point);
  }

  protected clearHoveredPoint(): void {
    this.hoveredPoint.set(null);
  }

  protected formatRevenueValue(value: number): string {
    const currency = this.revenueCurrency();
    return currency ? this.format.currency(value, currency, 0) : `${this.format.integer(value)}`;
  }

  ngOnInit(): void {
    if (this.canAppointments()) {
      void this.loadPerformance();
      void this.loadUpcoming();
      void this.loadChartSeries();
    }
    if (this.canProcedures()) void this.loadServices();
    if (this.canDoctors()) void this.loadDoctors();
  }

  /** The date input changed. The figures are not refetched until Submit. */
  protected onDateInput(event: Event): void {
    const value = (event.target as HTMLInputElement).value;
    if (value) this.draftDate.set(value);
  }

  /**
   * Submit: applies the selected date to the Performance section.
   *
   * The applied window is the whole of that day, because a date with no time
   * is a day and not an instant.
   */
  protected applyDateFilter(): void {
    const value = this.draftDate();
    if (!value) return;
    this.appliedDate.set(value);
    if (!this.canAppointments()) return;
    void this.loadPerformance();
    void this.loadChartSeries();
  }

  protected togglePeriodMenu(): void {
    this.periodMenuOpen.update((open) => !open);
  }

  protected selectPeriod(period: ChartPeriod): void {
    this.periodMenuOpen.set(false);
    if (this.chartPeriod() === period) return;
    this.chartPeriod.set(period);
    if (!this.canAppointments()) return;
    void this.loadChartSeries();
  }

  protected navigateTo(path: string): void {
    void this.router.navigateByUrl(path);
  }

  /** A count that could not be read is shown as an absence, never as a zero. */
  protected display(value: number | null): string {
    return value === null ? this.format.dash : this.format.integer(value);
  }

  private async loadPerformance(): Promise<void> {
    this.loading.set(true);
    this.performanceError.set(null);
    this.performanceRequestId.set(null);
    const anchor = this.anchorDate();
    const start = new Date(anchor.getFullYear(), anchor.getMonth(), anchor.getDate(), 0, 0, 0);
    const end = new Date(anchor.getFullYear(), anchor.getMonth(), anchor.getDate(), 23, 59, 59);
    try {
      const report = await this.patientApi.reports({
        window: 'custom',
        start: toLocalDateTime(start),
        end: toLocalDateTime(end),
      });
      this.report.set(report);
      this.revenueCurrency.set(report.financials?.[0]?.currency ?? null);
    } catch (error) {
      this.report.set(null);
      this.revenueCurrency.set(null);
      this.performanceError.set(this.describe(error));
      this.performanceRequestId.set(error instanceof ApiError ? error.requestId : null);
    } finally {
      this.loading.set(false);
      this.motion.animateCountUpValues(this.hostRef.nativeElement);
    }
  }

  private async loadServices(): Promise<void> {
    const procedures = await this.clinicApi.procedures().catch(() => null);
    if (!procedures) {
      this.serviceCount.set(null);
      return;
    }
    const items = procedures.items ?? [];
    this.serviceCount.set(items.filter((p) => p.isActive !== false).length);
  }

  private async loadDoctors(): Promise<void> {
    const doctors = await this.clinicApi.doctors().catch(() => null);
    if (!doctors) {
      this.doctorCount.set(null);
      return;
    }
    this.doctorCount.set((doctors.items ?? []).length);
  }

  private async loadUpcoming(): Promise<void> {
    const now = new Date();
    const horizon = new Date(now.getTime() + 7 * 24 * 60 * 60 * 1000);
    const page = await this.clinicApi
      .appointments({ from: toLocalDateTime(now), to: toLocalDateTime(horizon) })
      .catch(() => null);
    if (!page) {
      this.upcomingAppointments.set([]);
      return;
    }
    const items = (page as ClinicAppointmentPage).items ?? [];
    this.upcomingAppointments.set(
      items.slice(0, 5).map((a, index) => ({
        id: a.id ?? `apt-${index}`,
        patientName: a.patientName ?? a.title ?? this.i18n.t('dashboard.upcoming.patient'),
        doctorName: a.doctorName ?? this.i18n.t('dashboard.upcoming.doctor'),
        service: a.service ?? this.i18n.t('dashboard.upcoming.service'),
        formattedTime: a.appointmentDate
          ? this.format.time(a.appointmentDate)
          : this.format.dash,
      })),
    );
  }

  /**
   * The chart's series: one report per bucket, summed to the bucket's revenue.
   *
   * A bucket whose report could not be read contributes a zero rather than a
   * guess, and the chart says so when nothing at all could be read.
   */
  private async loadChartSeries(): Promise<void> {
    this.chartLoading.set(true);
    const buckets = this.bucketsFor(this.chartPeriod(), this.anchorDate());
    const reports = await Promise.all(
      buckets.map((bucket) =>
        this.patientApi
          .reports({ window: 'custom', start: bucket.start, end: bucket.end })
          .catch(() => null),
      ),
    );
    this.chartSeries.set(
      buckets.map((bucket, index) => ({
        label: bucket.label,
        value: this.invoicedTotal(reports[index]),
      })),
    );
    this.chartLoading.set(false);
    if (this.motion.canAnimate() && typeof requestAnimationFrame === 'function') {
      requestAnimationFrame(() => {
        this.motion.animatePageElements(this.hostRef.nativeElement);
      });
    }
  }

  private invoicedTotal(report: ClinicReportView | null): number {
    if (!report) return 0;
    return (report.financials ?? []).reduce((sum, row) => sum + (row.totalInvoiced ?? 0), 0);
  }

  private anchorDate(): Date {
    const parsed = new Date(`${this.appliedDate()}T00:00:00`);
    return Number.isNaN(parsed.getTime()) ? new Date() : parsed;
  }

  /**
   * The buckets the chart covers, anchored on the applied date.
   *
   * The reference's chart shows twelve months, so a year is twelve monthly
   * buckets; the shorter periods reuse the same shape at a finer grain.
   */
  private bucketsFor(period: ChartPeriod, anchor: Date): ChartBucket[] {
    const buckets: ChartBucket[] = [];
    if (period === 'YEAR') {
      const year = anchor.getFullYear();
      for (let month = 0; month < 12; month += 1) {
        buckets.push(
          this.bucket(MONTH_LABELS[month], new Date(year, month, 1), new Date(year, month + 1, 0, 23, 59, 59)),
        );
      }
      return buckets;
    }
    if (period === 'QUARTER') {
      const quarterStart = Math.floor(anchor.getMonth() / 3) * 3;
      for (let offset = 0; offset < 3; offset += 1) {
        const month = quarterStart + offset;
        buckets.push(
          this.bucket(
            MONTH_LABELS[month],
            new Date(anchor.getFullYear(), month, 1),
            new Date(anchor.getFullYear(), month + 1, 0, 23, 59, 59),
          ),
        );
      }
      return buckets;
    }
    if (period === 'MONTH') {
      const year = anchor.getFullYear();
      const month = anchor.getMonth();
      const lastDay = new Date(year, month + 1, 0).getDate();
      let week = 1;
      for (let day = 1; day <= lastDay; day += 7) {
        const end = Math.min(day + 6, lastDay);
        buckets.push(
          this.bucket(
            `W${week}`,
            new Date(year, month, day),
            new Date(year, month, end, 23, 59, 59),
          ),
        );
        week += 1;
      }
      return buckets;
    }
    // WEEK: the seven days of the week the applied date falls in.
    const start = new Date(anchor);
    start.setDate(anchor.getDate() - anchor.getDay());
    for (let offset = 0; offset < 7; offset += 1) {
      const day = new Date(start);
      day.setDate(start.getDate() + offset);
      buckets.push(
        this.bucket(DAY_LABELS[day.getDay()], new Date(day.getFullYear(), day.getMonth(), day.getDate(), 0, 0, 0), new Date(day.getFullYear(), day.getMonth(), day.getDate(), 23, 59, 59)),
      );
    }
    return buckets;
  }

  private bucket(label: string, start: Date, end: Date): ChartBucket {
    return { label, start: toLocalDateTime(start), end: toLocalDateTime(end) };
  }

  /**
   * What to say when the performance report could not be read.
   *
   * The server's own message is not shown: it is written for a log, not for a
   * clinician, and a permission refusal is not a failure at all — it is the
   * server deciding. The correlation id is shown beside it, because "it did not
   * work" is only actionable with the id the server logged.
   */
  private describe(error: unknown): string {
    if (error instanceof ApiError && error.isPermissionDenied) {
      return this.i18n.t('dashboard.performance.denied');
    }
    return this.i18n.t('dashboard.performance.failed');
  }
}
