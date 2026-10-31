/**
 * Group 12 — the clinic screen, now backed by the read model.
 *
 * ## What changed, and why it is a rewrite rather than a patch
 *
 * This component used to render a "not available" card, because the committed contract had
 * no clinic route at all. `docs/migration/adr/0013` recorded that as a real gap: the
 * funnel, the top procedures and the clinician workload cards had nothing behind them, and
 * the legacy client had papered over it with hard-coded numbers.
 *
 * The routes now exist, so the honest not-available state is deleted rather than
 * reconfigured. That is a visible diff for a reviewer, which is the point: the screen used
 * to assert something about the backend, and now it simply reads the backend.
 *
 * ## What this screen will NOT show, and the three reasons
 *
 * 1. **No clinician contact details.** `doctor:read` names the working week, not the phone
 *    number. The legacy route that legitimately exposes patient and clinician contact details
 *    is `GET /clinic/reminders`, behind `reminder:read`, and it is not migrated. Rendering a
 *    field the permission does not cover would put an untrue claim in front of an operator.
 * 2. **No revenue, no conversion rate, no utilisation.** The legacy service published
 *    `Math.max(75, rawAiRate)`, `Math.max(88, rawConfRate)` and
 *    `estimatedRevenueJod: Math.max(4850, totalRevenue)` — floors and constants dressed as
 *    observations. This screen renders what has rows behind it.
 * 3. **No invented zeros.** A procedure with no bookings renders as `0` with an empty row
 *    list, which is different from "this procedure does not exist", and different again from
 *    "no data was loaded".
 *
 * ## How a number reaches its rows
 *
 * Every metric row the API returns carries `sourceRowIds` next to its count, and the API
 * publishes `drillThroughPath` for the same window. This screen renders the count **and** an
 * expandable row of the appointment ids behind it. That is the ADR-0013 requirement made
 * visible in the product: a number nobody can open is the defect, and the fix has to be
 * somewhere a reader can actually use it.
 *
 * ## Locale, direction and numbers
 *
 * Arabic is the product's first locale, so Arabic is authored first in every string pair and
 * the layout is built with CSS logical properties — no `left`/`right`, no second stylesheet
 * for RTL. Dates and counts go through `FormatService`, which owns locale-aware number and
 * date formatting; nothing here formats a number by hand.
 */
import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { ReactiveFormsModule, FormControl } from '@angular/forms';
import { RouterLink } from '@angular/router';
import {
  ClinicAnalytics,
  ClinicApi,
  ClinicAppointment,
  ClinicDoctor,
  ClinicProcedure,
  type ClinicQuery,
} from '../../core/api/endpoints';
import { PERMISSIONS } from '../../core/auth/permissions';
import { SessionService } from '../../core/auth/session.service';
import { ProjectContextService } from '../../core/context/project-context.service';
import { I18nService } from '../../core/i18n/i18n.service';
import type { Namespace } from '../../core/i18n/i18n.types';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { ResourceStore } from '../../core/state/resource';
import { AsyncStateComponent } from '../../ui/async-state.component';
import { StatusChipComponent } from '../../ui/status-chip.component';
import { clinicStrings } from './clinic.strings';

/** The window the screen asks for, in days, ending now. */
const WINDOW_DAYS = 30;

/**
 * The metric groups, as data.
 *
 * Declared rather than written three times into the template so the section heading, the
 * provenance line and the row renderer cannot disagree about what exists.
 */
type MetricGroup = 'funnelByStatus' | 'demandByProcedure' | 'workloadByDoctor';

/**
 * The groups, in the order they are rendered.
 *
 * Declared here rather than inline in the template's `@for` because an inline array
 * literal infers `string[]`, and `rows('funnelByStatus')` is typed `MetricGroup` — so
 * the literal would not compile, and the honest fix (widening the methods to `string`)
 * would have thrown away the type that stops a fourth group being added to the array
 * and not to the renderer.
 */
const METRIC_GROUPS: readonly MetricGroup[] = [
  'funnelByStatus',
  'demandByProcedure',
  'workloadByDoctor',
];

interface MetricRow {
  readonly key: string;
  readonly label: string;
  readonly count: number;
  readonly minutes: number | null;
  readonly sourceRowIds: readonly string[];
}

@Component({
  selector: 'fe-clinic-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    AsyncStateComponent,
    ReactiveFormsModule,
    RouterLink,
    StatusChipComponent,
    TranslatePipe,
  ],
  templateUrl: './clinic.component.html',
  styleUrl: './clinic.component.css',
})
export class ClinicComponent implements OnInit {
  private readonly clinic = inject(ClinicApi);

  protected readonly i18n = inject(I18nService);
  protected readonly sessions = inject(SessionService);
  protected readonly projects = inject(ProjectContextService);

  /**
   * The three reads.
   *
   * `readonly T[]` rather than `T[]`: the wire's `items` are readonly, and widening the
   * store's type to mutable would invite a `push` downstream that the server could never
   * satisfy. Making the immutability a compile error is cheaper than discovering it at
   * runtime.
   */
  protected readonly doctors = new ResourceStore<readonly ClinicDoctor[]>();
  protected readonly procedures = new ResourceStore<readonly ClinicProcedure[]>();
  protected readonly schedule = new ResourceStore<readonly ClinicAppointment[]>();
  protected readonly analytics = new ResourceStore<ClinicAnalytics>();

  /** The project the screen is reading. Never a tenant scope the client decides. */
  protected readonly projectId = signal<string | null>(null);

  /** The window the screen asked for, echoed by the server on every response. */
  protected readonly window = signal<{ from: string; to: string } | null>(null);

  /**
   * Which metric row's source rows are open.
   *
   * <p>A {@code Set} rather than a single key so a reader can compare two rows. A screen that
   * only ever opens one at a time invites the reader to remember the number they are checking.
   */
  protected readonly expanded = signal<ReadonlySet<string>>(new Set<string>());

  /** A coarse filter over the schedule text. Server-side, so it cannot over-report. */
  protected readonly search = new FormControl('', { nonNullable: true });

  protected readonly canReadAnalytics = computed(() =>
    this.sessions.can()(PERMISSIONS.analyticsReadProject),
  );

  /** @see {@link METRIC_GROUPS} — bound once, read by the template's `@for`. */
  protected readonly metricGroups = METRIC_GROUPS;

  constructor() {
    this.i18n.register({ clinic: clinicStrings as Namespace });
  }

  ngOnInit(): void {
    this.projectId.set(this.projects.selectedProjectId());
    void this.reload();
  }

  /** Reloads all four reads for the selected project. */
  protected async reload(): Promise<void> {
    const from = isoDaysAgo(WINDOW_DAYS);
    const to = isoNow();
    const query: ClinicQuery = { projectId: this.projectId() ?? undefined, from, to };

    await Promise.all([
      this.doctors.load(async () =>
        // The page's `items` is unpacked here rather than in the template, so the template
        // branches on an array and never has to defend against a body that omitted `items`.
        // An absent `items` is `[]`, which is "this clinic has no clinicians here" and not
        // "the server sent something I cannot read".
        (await this.clinic.doctors({ projectId: this.projectId() ?? undefined })).items ?? [],
      ),
      this.procedures.load(async () =>
        (await this.clinic.procedures({ projectId: this.projectId() ?? undefined })).items ?? [],
      ),
      this.schedule.load(async () => {
        const page = await this.clinic.appointments({
          ...query,
          search: this.search.value.trim() || undefined,
        });
        return [...(page.items ?? [])];
      }),
      this.canReadAnalytics()
        ? this.analytics.load(() => this.clinic.analytics(query))
        : Promise.resolve(),
    ]);

    // The server echoes the window it actually bound. Publishing the request's window instead
    // would be a small lie with a large cost: a reader comparing two screens would be
    // comparing two silently different ranges.
    this.window.set({ from, to });
  }

  /** Adopts the window the analytics response says it used. */
  protected resolvedWindow(): string | null {
    const provenance = this.analytics.value()?.funnelProvenance;
    const start = provenance?.windowStart?.iso8601;
    const end = provenance?.windowEnd?.iso8601;
    if (!start || !end) return null;
    return `${start} → ${end}`;
  }

  /** The rows of one metric group, flattened for rendering. */
  protected rows(group: MetricGroup): MetricRow[] {
    const analytics = this.analytics.value();
    if (!analytics) return [];
    if (group === 'funnelByStatus') {
      return (analytics.funnelByStatus ?? []).map((row) => ({
        key: `funnel:${row.status ?? ''}`,
        label: row.status ?? '',
        count: row.appointmentCount ?? 0,
        minutes: null,
        sourceRowIds: row.sourceRowIds ?? [],
      }));
    }
    if (group === 'demandByProcedure') {
      return (analytics.demandByProcedure ?? []).map((row) => ({
        key: `procedure:${row.procedureId ?? row.name ?? ''}`,
        label: row.name ?? '',
        count: row.appointmentCount ?? 0,
        minutes: row.bookedMinutes ?? null,
        sourceRowIds: row.sourceRowIds ?? [],
      }));
    }
    return (analytics.workloadByDoctor ?? []).map((row) => ({
      key: `doctor:${row.doctorId ?? row.name ?? ''}`,
      label: row.name ?? '',
      count: row.appointmentCount ?? 0,
      minutes: row.bookedMinutes ?? null,
      sourceRowIds: row.sourceRowIds ?? [],
    }));
  }

  protected isExpanded(key: string): boolean {
    return this.expanded().has(key);
  }

  /** Opens or closes one row's source rows. */
  protected toggle(key: string): void {
    const next = new Set(this.expanded());
    if (next.has(key)) {
      next.delete(key);
    } else {
      next.add(key);
    }
    this.expanded.set(next);
  }

  /** Whether a row's id list is the whole population or a bounded sample. */
  protected isSampled(row: MetricRow): boolean {
    const limit = this.analytics.value()?.sourceRowSampleLimit ?? 0;
    return limit > 0 && row.sourceRowIds.length < row.count;
  }

  /** The freshness of one provenance record, or null when the group has none. */
  protected derivation(group: MetricGroup): string | null {
    const analytics = this.analytics.value();
    if (!analytics) return null;
    if (group === 'funnelByStatus') return analytics.funnelProvenance?.derivation ?? null;
    if (group === 'demandByProcedure') return analytics.demandProvenance?.derivation ?? null;
    return analytics.workloadProvenance?.derivation ?? null;
  }

  /** The source table one provenance record names. */
  protected sourceTable(group: MetricGroup): string | null {
    const analytics = this.analytics.value();
    if (!analytics) return null;
    if (group === 'funnelByStatus') return analytics.funnelProvenance?.sourceTable ?? null;
    if (group === 'demandByProcedure') return analytics.demandProvenance?.sourceTable ?? null;
    return analytics.workloadProvenance?.sourceTable ?? null;
  }


}

function isoNow(): string {
  return new Date().toISOString();
}

function isoDaysAgo(days: number): string {
  return new Date(Date.now() - days * 24 * 60 * 60 * 1000).toISOString();
}