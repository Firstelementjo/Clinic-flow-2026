/**
 * Settings.
 *
 * ### What this screen calls
 *
 * The frozen `SettingsPage` called `/system/health` and `/audit-logs`. Neither
 * route exists in the target contract — both were NestJS routes with no Spring
 * equivalent under the same name. The target equivalents are:
 *
 * | Panel | Route | Permission |
 * |---|---|---|
 * | Service | `GET /api/v1/status` | none (public probe) |
 * | Your account, role, effective permissions, project scope | `GET /api/v1/auth/me`, `GET /api/v1/permissions/me` | a live session |
 * | Queue depth and dead letters | `GET /api/v1/operations/jobs` | `system-health:read` |
 * | Realtime health and unread count | `GET /api/v1/operations/realtime`, `GET /api/v1/realtime/state` | `system-health:read`, `conversation:read` |
 * | Connectors | `GET /api/v1/operations/connectors` | `channel:read` |
 * | Search freshness and knowledge gaps | `GET /api/v1/operations/search/freshness`, `/operations/search/gaps` | `knowledge:read` |
 * | Audit streams | `GET /api/v1/admin/audit/events`, `GET /api/v1/admin/audit/security` | `audit-log:read`, `security-event:read` |
 *
 * The account and permission panels are shown to *every* signed-in caller,
 * because `/api/v1/permissions/me` needs only a live session. That is the
 * permissions UX in its most honest form: the screen says what the server will
 * and will not let this person do, rather than a client-side guess from a role.
 *
 * Every read model in the target contract for these routes is a free-form object,
 * so each accessor below narrows defensively and returns `null` for anything
 * absent. `null` renders as "no value" and never as `0` — FR-7.6's distinction
 * between "no data" and zero is a finance requirement that applies here too.
 */
import { ChangeDetectionStrategy, Component, OnInit, computed, inject } from '@angular/core';
import {
  AdminApi,
  NotificationsApi,
  OperationsApi,
  StatusApi,
  type RealtimeState,
} from '../../core/api/endpoints';
import { ApiError } from '../../core/api/api.service';
import { FormatService } from '../../core/format/format.service';
import { I18nService } from '../../core/i18n/i18n.service';
import { SessionService } from '../../core/auth/session.service';
import { PERMISSIONS } from '../../core/auth/permissions';
import { SessionStore } from '../../core/auth/session.store';
import { RealtimeService } from '../../core/realtime/realtime.service';
import { ResourceStore } from '../../core/state/resource';
import { AsyncStateComponent } from '../../ui/async-state.component';
import { PageHeaderComponent } from '../../ui/page-header.component';
import { StatusChipComponent } from '../../ui/status-chip.component';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { settingsStrings } from './settings.strings';
import type { Namespace } from '../../core/i18n/i18n.types';

type Row = Record<string, unknown>;

/** Reads a number out of a free-form read model, or `null` when it is not there. */
function numberAt(source: unknown, path: readonly string[]): number | null {
  let current: unknown = source;
  for (const key of path) {
    if (typeof current !== 'object' || current === null) return null;
    current = (current as Row)[key];
  }
  return typeof current === 'number' && Number.isFinite(current) ? current : null;
}

function rowsAt(source: unknown, key: string): Row[] {
  if (typeof source !== 'object' || source === null) return [];
  const value = (source as Row)[key];
  return Array.isArray(value) ? (value as Row[]) : [];
}

function stringAt(source: unknown, key: string): string | null {
  if (typeof source !== 'object' || source === null) return null;
  const value = (source as Row)[key];
  return typeof value === 'string' && value !== '' ? value : null;
}

/**
 * The queue failure rate, or `null` when there is no rate to report.
 *
 * `-1` is the backend's "no attempts in this window" sentinel. Treating it as a
 * number would render `-100%`, so it is mapped to `null` and the screen shows the
 * sentence that explains why there is no figure.
 */
export function failureRateFrom(value: unknown): number | null {
  const rate = numberAt(value, ['failureRate']);
  if (rate === null || rate < 0) return null;
  return rate;
}

/** Reads a boolean out of a free-form read model, or `null` when it is absent. */
function booleanAt(source: unknown, path: readonly string[]): boolean | null {
  let current: unknown = source;
  for (const key of path) {
    if (typeof current !== 'object' || current === null) return null;
    current = (current as Row)[key];
  }
  return typeof current === 'boolean' ? current : null;
}

@Component({
  selector: 'fe-settings-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [AsyncStateComponent, PageHeaderComponent, StatusChipComponent, TranslatePipe],
  templateUrl: './settings.component.html',
  styleUrl: './settings.component.css',
})
export class SettingsComponent implements OnInit {
  private readonly statusApi = inject(StatusApi);
  private readonly notificationsApi = inject(NotificationsApi);
  private readonly operations = inject(OperationsApi);
  private readonly admin = inject(AdminApi);
  protected readonly format = inject(FormatService);
  protected readonly i18n = inject(I18nService);
  protected readonly sessions = inject(SessionService);
  protected readonly store = inject(SessionStore);
  private readonly realtime = inject(RealtimeService);

  protected readonly liveness = new ResourceStore<{ status?: string; version?: string }>();
  protected readonly queue = new ResourceStore<Row>();
  protected readonly connectors = new ResourceStore<Row>();
  protected readonly freshness = new ResourceStore<Row>();
  protected readonly gaps = new ResourceStore<Row>();
  protected readonly stream = new ResourceStore<RealtimeState>();
  protected readonly realtimeHealth = new ResourceStore<Row>();
  protected readonly auditEvents = new ResourceStore<Row>();
  protected readonly auditSecurity = new ResourceStore<Row>();

  protected readonly canQueue = computed(() => this.sessions.can()(PERMISSIONS.systemHealthRead));
  protected readonly canConnectors = computed(() => this.sessions.can()(PERMISSIONS.channelRead));
  protected readonly canKnowledge = computed(() => this.sessions.can()(PERMISSIONS.knowledgeRead));
  protected readonly canAudit = computed(() => this.sessions.can()(PERMISSIONS.auditLogRead));
  protected readonly canSecurity = computed(() => this.sessions.can()(PERMISSIONS.securityEventRead));

  /**
   * The granted permission list.
   *
   * Rendered as text, always, with the count: "you hold N of M permissions" is
   * the honest statement. A hidden affordance is UX; this list is the server's
   * own answer.
   */
  protected readonly permissions = computed(() => {
    const inspection = this.store.permissions();
    const granted = inspection?.effectivePermissions ?? [];
    const missing = inspection?.missingPermissions ?? [];
    return {
      granted,
      grantedCount: granted.length,
      missingCount: missing.length,
      total: granted.length + missing.length,
      projects: this.store.projectIds(),
      loaded: inspection !== null,
    };
  });

  protected readonly livenessTone = computed(() =>
    this.liveness.value()?.status === 'UP' ? ('ok' as const) : ('neutral' as const),
  );

  /** Queue figures, already narrowed and formatted. */
  protected readonly queueFacts = computed(() => {
    const queue = this.queue.value();
    const oldestPending = numberAt(queue, ['oldestPendingSeconds']);
    return {
      dueNow: this.format.integer(numberAt(queue, ['dueNow'])),
      oldestPending:
        oldestPending === null
          ? this.format.dash
          : this.format.durationWithLabels(oldestPending, this.durationLabels()),
      attempts: this.format.integer(numberAt(queue, ['attemptsInWindow'])),
      failures: this.format.integer(numberAt(queue, ['failuresInWindow'])),
      deadLetters: this.format.integer(numberAt(queue, ['openDeadLetters'])),
      // The backend reports -1 for "no attempts in the window", which is *not* a
      // 0% failure rate (`QueueHealthService`, and FR-7.6's whole point). Both the
      // absent case and the sentinel case render the explanatory sentence; only a
      // real rate renders a percentage. Formatting the sentinel would print
      // "-100.0%", which is worse than either answer.
      failureRate:
        failureRateFrom(queue) === null ? null : this.format.percent(failureRateFrom(queue), 1),
      outboxStates: rowsAt(queue, 'outboxStateCounts'),
      hasOutboxStates: rowsAt(queue, 'outboxStateCounts').length > 0,
    };
  });

  protected readonly streamFacts = computed(() => {
    const health = numberAt(this.realtimeHealth.value(), ['health', 'localSubscribers']);
    return {
      unread: this.format.integer(
        typeof this.stream.value()?.unreadCount === 'number'
          ? this.stream.value()?.unreadCount ?? null
          : null,
      ),
      subscribers: this.format.integer(health),
      connections: this.format.integer(
        numberAt(this.realtimeHealth.value(), ['health', 'connectionsOpened']),
      ),
      resyncs: this.format.integer(numberAt(this.realtimeHealth.value(), ['health', 'resyncsSent'])),
      // `null` means the health read model did not report it, which is not the
      // same as "false": an absent key is not a disabled bridge.
      multiInstance: booleanAt(this.realtimeHealth.value(), ['health', 'multiInstanceCapable']),
      serverTime: this.format.dateTime(this.stream.value()?.serverTime ?? null),
    };
  });

  protected readonly connectorRows = computed(() => rowsAt(this.connectors.value(), 'connectors'));

  protected readonly gapRows = computed(() => rowsAt(this.gaps.value(), 'gaps'));

  protected readonly freshnessRows = computed(() => [
    ...rowsAt(this.freshness.value(), 'sources'),
    ...rowsAt(this.freshness.value(), 'documents'),
  ]);

  protected readonly auditFacts = computed(() => ({
    scope: stringAt(this.auditEvents.value(), 'scope'),
    since: this.format.dateTime(stringAt(this.auditEvents.value(), 'since')),
    events: rowsAt(this.auditEvents.value(), 'events'),
  }));

  protected readonly securityDenials = computed(() =>
    rowsAt(this.auditSecurity.value(), 'denials'),
  );

  constructor() {
    this.i18n.register({ settings: settingsStrings as Namespace });
  }

  ngOnInit(): void {
    void this.loadAll();
    // A realtime frame is a hint: refetch, never render from the frame.
    this.realtime.onSignal((signal) => {
      if (signal.kind === 'heartbeat') return;
      void this.loadAll();
    });
  }

  protected async loadAll(): Promise<void> {
    const jobs: Promise<unknown>[] = [this.liveness.load(() => this.statusApi.status())];
    if (this.canQueue()) {
      jobs.push(this.queue.load(() => this.operations.jobs({ windowHours: 24 })));
      jobs.push(this.stream.load(() => this.notificationsApi.state()));
      jobs.push(this.realtimeHealth.load(() => this.operations.realtime()));
    }
    if (this.canConnectors()) {
      jobs.push(this.connectors.load(() => this.operations.connectors({ windowHours: 24 })));
    }
    if (this.canKnowledge()) {
      jobs.push(this.freshness.load(() => this.operations.searchFreshness({ idleHours: 24 })));
      jobs.push(this.gaps.load(() => this.operations.searchGaps({ onlyOpen: true, limit: 10 })));
    }
    if (this.canAudit()) {
      jobs.push(this.auditEvents.load(() => this.admin.auditEvents({ hours: 168 })));
    }
    if (this.canSecurity()) {
      jobs.push(this.auditSecurity.load(() => this.admin.auditSecurity({ hours: 168 })));
    }
    await Promise.allSettled(jobs);
  }

  protected retry(): void {
    void this.loadAll();
  }

  protected errorRequestId(error: unknown): string | null {
    return error instanceof ApiError ? error.requestId : null;
  }

  protected text(value: unknown): string {
    return typeof value === 'string' && value !== '' ? value : '—';
  }

  protected count(value: unknown): string {
    return this.format.integer(typeof value === 'number' ? value : null);
  }

  private durationLabels(): { day: string; hour: string; minute: string; second: string } {
    return {
      day: this.i18n.t('settings.unit.day'),
      hour: this.i18n.t('settings.unit.hour'),
      minute: this.i18n.t('settings.unit.minute'),
      second: this.i18n.t('settings.unit.second'),
    };
  }
}
