/**
 * Group 9 — the administration screen.
 *
 * ## Which routes this screen calls
 *
 * | Section | Route | Backend permission |
 * |---|---|---|
 * | Overview | `GET /api/v1/admin/overview` | `system-health:read` |
 * | Backups | `GET /api/v1/admin/backups` | `system-health:read` |
 * | Migrations | `GET /api/v1/admin/migrations` | `system-health:read` |
 * | Maintenance commands | `GET /api/v1/admin/maintenance/commands`, `/disabled` | `system-health:read` |
 * | Audit events | `GET /api/v1/admin/audit/events` | `audit-log:read` |
 * | Security events | `GET /api/v1/admin/audit/security` | `security-event:read` |
 * | Legal holds, deletions, retention classes | `GET /api/v1/admin/governance/*` | `security-event:read` |
 * | Purge gate | `GET /api/v1/admin/governance/maintenance/purge-gate` | `system-health:read` |
 * | Active sessions | `GET /api/v1/admin/identity/sessions` | `session:revoke` |
 * | Export jobs | `GET /api/v1/admin/exports/jobs` | `analytics:read-organization` |
 *
 * Every permission is read off the controller's own `@RequirePermission`, not inferred.
 *
 * ## Why this screen is read-only
 *
 * The seven governance writes — place and release a legal hold, request, preview and
 * execute a privacy deletion, purge — all sit behind `ORGANIZATION_UPDATE`, the
 * broadest permission in the system. They are not rendered as buttons. Each is a
 * deliberate destructive act that should be requested by name and leave an audit event
 * of its own; a one-click purge next to a status chip inverts that. The screen shows
 * the *state* those writes change — what is held, what deletion is pending, what a
 * purge would touch — which is the part an operator needs in order to decide.
 *
 * ## Free-form documents
 *
 * Nine of the eleven responses are `{ document: {...} }` with no declared shape, so
 * every table here is derived from what the server actually sent. A field it did not
 * send renders as `—`, which is what FR-7.6 requires and the difference between "no
 * data" and "zero".
 */
import { ChangeDetectionStrategy, Component, OnInit, computed, effect, inject, signal, untracked } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute } from '@angular/router';
import { map } from 'rxjs';
import { ApiError } from '../../core/api/api.service';
import { AdminReadApi } from '../../core/api/endpoints';
import { ProjectContextService } from '../../core/context/project-context.service';
import { I18nService } from '../../core/i18n/i18n.service';
import type { Namespace } from '../../core/i18n/i18n.types';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { PERMISSIONS } from '../../core/auth/permissions';
import { SessionService } from '../../core/auth/session.service';
import { ResourceStore } from '../../core/state/resource';
import { AsyncStateComponent } from '../../ui/async-state.component';
import { NotAvailableComponent } from '../../ui/not-available.component';
import { PageHeaderComponent } from '../../ui/page-header.component';
import { StatusChipComponent } from '../../ui/status-chip.component';
import { adminStrings } from './admin.strings';

/** One rendered row: a label already translated, and a value already formatted. */
interface FactRow {
  readonly key: string;
  /** i18n key for the label, or `null` when the server named the field itself. */
  readonly labelKey: string | null;
  /** The server's own key, shown verbatim when there is no i18n key for it. */
  readonly rawLabel: string;
  readonly value: string;
}

@Component({
  selector: 'fe-admin-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    AsyncStateComponent,
    NotAvailableComponent,
    PageHeaderComponent,
    StatusChipComponent,
    TranslatePipe,
  ],
  templateUrl: './admin.component.html',
  styleUrl: './admin.component.css',
})
export class AdminComponent implements OnInit {
  private readonly admin = inject(AdminReadApi);
  private readonly route = inject(ActivatedRoute);

  protected readonly i18n = inject(I18nService);
  protected readonly sessions = inject(SessionService);
  protected readonly projects = inject(ProjectContextService);

  protected readonly overview = new ResourceStore<Record<string, unknown>>();
  protected readonly backups = new ResourceStore<Record<string, unknown>>();
  protected readonly migrations = new ResourceStore<Record<string, unknown>>();
  protected readonly commands = new ResourceStore<Record<string, unknown>>();
  protected readonly disabledCommands = new ResourceStore<Record<string, unknown>>();
  protected readonly auditEvents = new ResourceStore<Record<string, unknown>>();
  protected readonly securityEvents = new ResourceStore<Record<string, unknown>>();
  protected readonly legalHolds = new ResourceStore<Record<string, unknown>>();
  protected readonly deletions = new ResourceStore<Record<string, unknown>>();
  protected readonly retentionClasses = new ResourceStore<Record<string, unknown>>();
  protected readonly purgeGate = new ResourceStore<Record<string, unknown>>();
  protected readonly sessionsList = new ResourceStore<Record<string, unknown>[]>();
  protected readonly exportJobs = new ResourceStore<Record<string, unknown>>();

  /**
   * The capability gates, one per subject.
   *
   * A subject whose permission the caller lacks is not rendered as an empty card. It is
   * absent, because a card with no data and a card the user may not see look identical
   * and only one of them is true.
   */
  protected readonly canSeeOverview = computed(() => this.sessions.can()(PERMISSIONS.systemHealthRead));
  protected readonly canSeeAudit = computed(() => this.sessions.can()(PERMISSIONS.auditLogRead));
  protected readonly canSeeSecurity = computed(() => this.sessions.can()(PERMISSIONS.securityEventRead));
  protected readonly canSeeSessions = computed(() => this.sessions.can()(PERMISSIONS.sessionRevoke));
  protected readonly canSeeExportJobs = computed(() =>
    this.sessions.can()(PERMISSIONS.analyticsReadOrganization),
  );

  /**
   * Which section the operator is looking at.
   *
   * Seeded from `?tab=` so a deep link — the navigation's "audit trail" item, or a URL
   * an operator pastes to a colleague — arrives on the section it names rather than on
   * the default. The inbox uses the same shape for the selected conversation, and the
   * reason is the same: a link that lands somewhere other than where it points is a
   * small betrayal of the person who followed it.
   *
   * An unknown value falls back to the default rather than rendering nothing, because a
   * stale `?tab=` must never be the reason a section is unreachable.
   */
  protected readonly tab = signal<AdminTab>('overview');

  protected readonly tabs = computed<readonly { id: AdminTab; key: string }[]>(() =>
    [
      { id: 'overview' as const, key: 'admin.overview.title' },
      { id: 'backups' as const, key: 'admin.backups.title' },
      { id: 'migrations' as const, key: 'admin.migrations.title' },
      { id: 'commands' as const, key: 'admin.commands.title' },
      { id: 'audit' as const, key: 'admin.audit.title' },
      { id: 'governance' as const, key: 'admin.governance.title' },
      { id: 'sessions' as const, key: 'admin.sessions.title' },
      { id: 'jobs' as const, key: 'admin.jobs.title' },
    ].filter((entry) => this.maySee(entry.id)),
  );

  constructor() {
    this.i18n.register({ admin: adminStrings as Namespace });

    // Seeded from the query string, and filtered against the caller's permissions: a
    // `?tab=audit` link followed by someone without `audit-log:read` lands on the
    // overview instead of on an empty audit card, which would otherwise be
    // indistinguishable from "no events are recorded".
    const requested = toSignal(this.route.queryParamMap.pipe(map((params) => params.get('tab'))), {
      initialValue: null,
    });
    effect(() => {
      const value = requested();
      if (!value) return;
      if (!ADMIN_TABS.includes(value as AdminTab)) return;
      if (!this.maySee(value as AdminTab)) return;
      untracked(() => this.tab.set(value as AdminTab));
    });
  }

  ngOnInit(): void {
    void this.load();
  }

  /** Whether the caller holds the permission this tab's data needs. */
  protected maySee(id: AdminTab): boolean {
    switch (id) {
      case 'overview':
      case 'backups':
      case 'migrations':
      case 'commands':
        return this.canSeeOverview();
      case 'audit':
        return this.canSeeAudit();
      case 'governance':
        return this.canSeeSecurity();
      case 'sessions':
        return this.canSeeSessions();
      case 'jobs':
        return this.canSeeExportJobs();
    }
  }

  protected async load(): Promise<void> {
    const projectId = this.projects.selectedProjectId() ?? undefined;
    const jobs: Promise<unknown>[] = [];

    if (this.canSeeOverview()) {
      jobs.push(
        this.overview.load(() => this.admin.overview({ projectId })),
        this.backups.load(() => this.admin.backups({ projectId }), isEmptyDocument),
        this.migrations.load(() => this.admin.migrations({ projectId }), isEmptyDocument),
        this.commands.load(() => this.admin.maintenanceCommands({ projectId }), isEmptyDocument),
        this.disabledCommands.load(() => this.admin.disabledCommands({ projectId }), isEmptyDocument),
        this.purgeGate.load(() => this.admin.purgeGate({ projectId })),
      );
    }
    if (this.canSeeAudit()) {
      jobs.push(this.auditEvents.load(() => this.admin.auditEvents({ projectId }), isEmptyDocument));
    }
    if (this.canSeeSecurity()) {
      jobs.push(
        this.securityEvents.load(() => this.admin.auditSecurity({ projectId }), isEmptyDocument),
        this.legalHolds.load(() => this.admin.legalHolds({ projectId }), isEmptyDocument),
        this.deletions.load(() => this.admin.deletions({ projectId }), isEmptyDocument),
        this.retentionClasses.load(() => this.admin.retentionClasses({ projectId }), isEmptyDocument),
      );
    }
    if (this.canSeeSessions()) {
      jobs.push(
        this.sessionsList.load(() => this.admin.sessions({ projectId }), (rows) => (rows?.length ?? 0) === 0),
      );
    }
    if (this.canSeeExportJobs()) {
      jobs.push(this.exportJobs.load(() => this.admin.exportJobs({ projectId }), isEmptyDocument));
    }

    // `allSettled` because these are eleven independent subjects. One route failing
    // must not blank the other ten, and the card that failed already shows why.
    await Promise.allSettled(jobs);
  }

  // -- row derivation -------------------------------------------------------

  /** The overview's facts, read defensively from a free-form document. */
  protected overviewRows(): FactRow[] {
    return rowsFrom(documentOf(this.overview.value()), [
      { keys: ['status', 'state', 'health'], labelKey: 'admin.overview.title' },
      { keys: ['version', 'release', 'build'], labelKey: 'admin.overview.title' },
      { keys: ['uptime', 'uptimeSeconds'], labelKey: 'admin.overview.title' },
      { keys: ['migrations', 'migrationCount'], labelKey: 'admin.migrations.title', numeric: true },
      { keys: ['projects', 'projectCount'], labelKey: 'admin.overview.title', numeric: true },
      { keys: ['organizations', 'organizationCount'], labelKey: 'admin.overview.title', numeric: true },
      { keys: ['backupCount', 'backups'], labelKey: 'admin.backups.title', numeric: true },
      { keys: ['lastBackupAt', 'latestBackupAt'], labelKey: 'admin.backups.title', date: true },
    ]);
  }

  protected backupRows(): FactRow[] {
    return rowsFrom(documentOf(this.backups.value()), [
      { keys: ['name', 'id', 'label'], labelKey: 'admin.backups.title' },
      { keys: ['sizeBytes', 'bytes', 'size'], labelKey: 'admin.backups.title', numeric: true },
      { keys: ['createdAt', 'takenAt', 'timestamp'], labelKey: 'admin.backups.title', date: true },
      { keys: ['checksum', 'sha256', 'hash'], labelKey: 'admin.backups.title' },
      { keys: ['state', 'status'], labelKey: 'admin.overview.title' },
    ]);
  }

  protected migrationRows(): FactRow[] {
    return rowsFrom(documentOf(this.migrations.value()), [
      { keys: ['version', 'id', 'sequence'], labelKey: 'admin.migrations.title' },
      { keys: ['description', 'name', 'script'], labelKey: 'admin.migrations.title' },
      { keys: ['installedOn', 'installedAt', 'appliedAt'], labelKey: 'admin.migrations.title', date: true },
      { keys: ['success', 'state', 'installed'], labelKey: 'admin.overview.title', boolean: true },
      { keys: ['executionTimeMs', 'durationMs'], labelKey: 'admin.migrations.title', numeric: true },
    ]);
  }

  protected commandRows(): FactRow[] {
    return rowsFrom(documentOf(this.commands.value()), [
      { keys: ['name', 'command', 'id'], labelKey: 'admin.commands.title' },
      { keys: ['description', 'summary'], labelKey: 'admin.commands.title' },
      { keys: ['requiresConfirmation', 'destructive'], labelKey: 'admin.writes.title', boolean: true },
    ]);
  }

  protected disabledCommandRows(): FactRow[] {
    return rowsFrom(documentOf(this.disabledCommands.value()), [
      { keys: ['name', 'command', 'id'], labelKey: 'admin.commands.title' },
      { keys: ['reason', 'disabledReason', 'explanation'], labelKey: 'admin.commands.title' },
    ]);
  }

  protected auditRows(): FactRow[] {
    return rowsFrom(documentOf(this.auditEvents.value()), [
      { keys: ['occurredAt', 'at', 'createdAt', 'timestamp'], labelKey: 'admin.audit.title', date: true },
      { keys: ['actor', 'actorEmail', 'principal', 'userId'], labelKey: 'admin.audit.title' },
      { keys: ['action', 'operation', 'event', 'type'], labelKey: 'admin.audit.title' },
      { keys: ['target', 'resource', 'resourceType'], labelKey: 'admin.audit.title' },
      { keys: ['outcome', 'result', 'decision'], labelKey: 'admin.overview.title' },
      { keys: ['correlationId', 'requestId'], labelKey: 'state.requestId' },
    ]);
  }

  protected securityRows(): FactRow[] {
    return rowsFrom(documentOf(this.securityEvents.value()), [
      { keys: ['occurredAt', 'at', 'createdAt'], labelKey: 'admin.audit.security', date: true },
      { keys: ['kind', 'type', 'event'], labelKey: 'admin.audit.security' },
      { keys: ['subject', 'actor', 'principal'], labelKey: 'admin.audit.security' },
      { keys: ['ipAddress', 'ip', 'address'], labelKey: 'admin.audit.security' },
      { keys: ['severity', 'level'], labelKey: 'admin.audit.security' },
      { keys: ['outcome', 'result'], labelKey: 'admin.overview.title' },
    ]);
  }

  protected holdRows(): FactRow[] {
    return rowsFrom(documentOf(this.legalHolds.value()), [
      { keys: ['subject', 'subjectType', 'subjectId'], labelKey: 'admin.governance.title' },
      { keys: ['reason', 'basis', 'justification'], labelKey: 'admin.governance.title' },
      { keys: ['placedAt', 'createdAt'], labelKey: 'admin.governance.title', date: true },
      { keys: ['state', 'status'], labelKey: 'admin.overview.title' },
      { keys: ['expiresAt', 'releaseAfter'], labelKey: 'admin.governance.title', date: true },
    ]);
  }

  protected deletionRows(): FactRow[] {
    return rowsFrom(documentOf(this.deletions.value()), [
      { keys: ['subject', 'subjectType', 'subjectId'], labelKey: 'admin.governance.title' },
      { keys: ['requestedAt', 'createdAt'], labelKey: 'admin.governance.title', date: true },
      { keys: ['state', 'status', 'stage'], labelKey: 'admin.overview.title' },
      { keys: ['targets', 'targetCount'], labelKey: 'admin.governance.title', numeric: true },
      { keys: ['completedAt', 'executedAt'], labelKey: 'admin.governance.title', date: true },
    ]);
  }

  protected retentionRows(): FactRow[] {
    return rowsFrom(documentOf(this.retentionClasses.value()), [
      { keys: ['name', 'class', 'id'], labelKey: 'admin.governance.title' },
      { keys: ['retentionDays', 'days', 'ttlDays'], labelKey: 'admin.governance.title', numeric: true },
      { keys: ['description', 'policy'], labelKey: 'admin.governance.title' },
    ]);
  }

  protected purgeGateRows(): FactRow[] {
    return rowsFrom(documentOf(this.purgeGate.value()), [
      { keys: ['allowed', 'permitted', 'enabled'], labelKey: 'admin.governance.purgeGate', boolean: true },
      { keys: ['reason', 'blockedReason', 'blockedBy'], labelKey: 'admin.governance.purgeGate' },
      { keys: ['activeHolds', 'holdCount'], labelKey: 'admin.governance.title', numeric: true },
      { keys: ['pendingDeletions', 'deletionCount'], labelKey: 'admin.governance.title', numeric: true },
      { keys: ['evaluatedAt', 'asOf'], labelKey: 'admin.governance.purgeGate', date: true },
    ]);
  }

  /** Sessions come back as an array rather than a document, so they read differently. */
  protected sessionRows(): FactRow[] {
    const rows = this.sessionsList.value();
    if (!rows) return [];
    return rows.map((record, index) => ({
      key: String(index),
      labelKey: 'admin.sessions.title',
      rawLabel: String(index),
      value: describeSession(record),
    }));
  }

  protected jobRows(): FactRow[] {
    return rowsFrom(documentOf(this.exportJobs.value()), [
      { keys: ['id', 'exportId', 'jobId'], labelKey: 'admin.jobs.title' },
      { keys: ['status', 'state'], labelKey: 'admin.overview.title' },
      { keys: ['progressPct', 'progress'], labelKey: 'admin.jobs.title', numeric: true },
      { keys: ['rowCount', 'rows'], labelKey: 'admin.jobs.title', numeric: true },
      { keys: ['bytes', 'sizeBytes'], labelKey: 'admin.jobs.title', numeric: true },
      { keys: ['startedAt', 'createdAt'], labelKey: 'admin.jobs.title', date: true },
      { keys: ['finishedAt', 'completedAt'], labelKey: 'admin.jobs.title', date: true },
      { keys: ['errorMessage', 'lastError'], labelKey: 'admin.jobs.title' },
    ]);
  }

  /**
   * The correlation id behind a failed read, for the error state to show.
   *
   * Typed against a structural `ErrorSource` rather than `ResourceStore<T>` because the
   * eleven stores on this screen are `ResourceStore<Record<string, unknown>>`,
   * `ResourceStore<Record<string, unknown>[]>` and `ResourceStore<DirectoryView>`, and
   * `T` is invariant here — a generic parameter would make the template reject two of the
   * three. The only thing needed is the error, and `ErrorSource` names exactly that.
   */
  /**
   * Arrow keys move between tabs, and Home/End jump to the ends.
   *
   * The ARIA tabs pattern makes arrow-key traversal a **requirement**, not a nicety:
   * with roving `tabindex` only the selected tab is in the tab order, so a keyboard user
   * who cannot arrow their way along the strip can reach exactly one tab and no more.
   * Without this handler, `tabindex="-1"` on the other seven would make them
   * unreachable rather than merely out of sequence.
   *
   * RTL is handled by not flipping the mapping: the tab strip is laid out by the
   * document's own `flex-wrap` order, and ArrowRight means "the next tab in reading
   * order", which in RTL is the one on the left. Flipping it would fight the layout.
   */
  protected onTabKeydown(event: KeyboardEvent): void {
    const ids = this.tabs().map((entry) => entry.id);
    if (ids.length === 0) return;
    const current = ids.indexOf(this.tab());
    let next = current;

    switch (event.key) {
      case 'ArrowRight':
      case 'ArrowDown':
        next = (current + 1) % ids.length;
        break;
      case 'ArrowLeft':
      case 'ArrowUp':
        next = (current - 1 + ids.length) % ids.length;
        break;
      case 'Home':
        next = 0;
        break;
      case 'End':
        next = ids.length - 1;
        break;
      default:
        return;
    }

    event.preventDefault();
    this.tab.set(ids[next]!);
    // Focus follows selection, so the next arrow press starts from where the operator
    // now is rather than from where they were.
    queueMicrotask(() => {
      document.getElementById(`admin-tab-${ids[next]!}`)?.focus();
    });
  }

  protected requestIdOf(store: ErrorSource): string | null {
    const error = store.error();
    return error instanceof ApiError ? error.requestId : null;
  }
}

/** The one thing a `ResourceStore` is asked for here: its last error. */
interface ErrorSource {
  readonly error: () => ApiError | null;
}

export type AdminTab =
  | 'overview'
  | 'backups'
  | 'migrations'
  | 'commands'
  | 'audit'
  | 'governance'
  | 'sessions'
  | 'jobs';

/** Every tab id, for validating a `?tab=` value from the URL. */
const ADMIN_TABS: readonly AdminTab[] = [
  'overview',
  'backups',
  'migrations',
  'commands',
  'audit',
  'governance',
  'sessions',
  'jobs',
];

// ---------------------------------------------------------------------------
// Free-form document reading
// ---------------------------------------------------------------------------

/** Unwraps a `{ document: {...} }` response, or takes a bare object as-is. */
function documentOf(value: unknown): Record<string, unknown> | null {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) return null;
  const record = value as Record<string, unknown>;
  const inner = record['document'];
  if (inner !== null && typeof inner === 'object' && !Array.isArray(inner)) {
    return inner as Record<string, unknown>;
  }
  return record;
}

/** True when a free-form document carries nothing worth showing. */
function isEmptyDocument(value: Record<string, unknown> | null): boolean {
  const document = documentOf(value);
  return !document || Object.keys(document).length === 0;
}

interface ColumnSpec {
  readonly keys: readonly string[];
  /** i18n key for the column heading. */
  readonly labelKey: string;
  readonly numeric?: boolean;
  readonly boolean?: boolean;
  readonly date?: boolean;
}

/**
 * Turns a free-form document into rows.
 *
 * Prefers a non-empty array under any key, and otherwise treats the object's own
 * entries as rows — because the contract declares neither shape. A field the server did
 * not send is `—`, never an empty cell and never a zero.
 */
function rowsFrom(
  document: Record<string, unknown> | null,
  columns: readonly ColumnSpec[],
): FactRow[] {
  if (!document) return [];
  const list = Object.entries(document).find(
    ([, value]) => Array.isArray(value) && value.length > 0,
  );
  const entries: [string, unknown][] = list
    ? (list[1] as unknown[]).map((value, index) => [String(index), value])
    : Object.entries(document);

  return entries.map(([key, value]) => {
    const record =
      value !== null && typeof value === 'object' && !Array.isArray(value)
        ? (value as Record<string, unknown>)
        : null;
    const rendered = columns.map((column) => renderCell(record, value, column));
    // The label is the first column that produced a value, so a row never carries a
    // heading for a field it does not have. Falling back to the column key keeps the
    // server's own name visible when nothing matched, rather than inventing one.
    const firstRendered = rendered.findIndex((cell) => cell !== null);
    const renderedValues = rendered.filter((cell): cell is string => cell !== null);
    let displayValue: string;
    let labelText = columns.map((column) => firstOf(record, column.keys)).find((v) => v !== null) ?? key;

    if (renderedValues.length > 1) {
      labelText = renderedValues[0];
      displayValue = renderedValues.slice(1).join(' · ');
    } else {
      displayValue = rendered.find((cell) => cell !== null) ?? '—';
    }

    return {
      key,
      labelKey: columns[firstRendered === -1 ? 0 : firstRendered]!.labelKey,
      rawLabel: labelText,
      value: displayValue,
    };
  });
}

/** The first candidate key present on the record, as a string, or null. */
function firstOf(record: Record<string, unknown> | null, keys: readonly string[]): string | null {
  if (!record) return null;
  for (const key of keys) {
    const value = record[key];
    if (value !== undefined && value !== null && value !== '') return String(value);
  }
  return null;
}

/** Renders one cell, or null when this column has nothing for this row. */
function renderCell(
  record: Record<string, unknown> | null,
  whole: unknown,
  column: ColumnSpec,
): string | null {
  let raw: unknown;
  if (record) {
    for (const key of column.keys) {
      const candidate = record[key];
      if (candidate !== undefined && candidate !== null && candidate !== '') {
        raw = candidate;
        break;
      }
    }
  } else {
    raw = whole;
  }
  if (raw === undefined || raw === null || raw === '') return null;
  if (column.boolean) return raw === true || raw === 'true' ? 'yes' : 'no';
  if (column.date && typeof raw === 'string') return raw;
  if (column.numeric && typeof raw === 'number') return String(raw);
  return String(raw);
}

/** One session row, as a single readable line: who, since, and from where. */
function describeSession(record: unknown): string {
  if (record === null || typeof record !== 'object') return '—';
  const row = record as Record<string, unknown>;
  const parts = [
    firstOf(row, ['userEmail', 'email', 'actor']),
    firstOf(row, ['startedAt', 'createdAt', 'lastSeenAt']),
    firstOf(row, ['ipAddress', 'ip']),
  ].filter((part): part is string => part !== null);
  return parts.length > 0 ? parts.join(' · ') : '—';
}
