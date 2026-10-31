/**
 * Knowledge, AI governance, channels and escalation.
 *
 * ## Which routes this screen calls
 *
 * | Section | Route | Gated on |
 * |---|---|---|
 * | Search freshness | `GET /api/v1/operations/search/freshness` | `knowledge:read` |
 * | Knowledge gaps | `GET /api/v1/operations/search/gaps` | `knowledge:read` |
 * | AI tools, policies, budgets, approvals, blocked calls, redactions | `GET /api/v1/admin/ai/*` | `system-health:read` |
 * | Channel capabilities | `GET /api/v1/channels/capabilities` | `channel:read` |
 *
 * ## What each subject honestly is, in the committed contract
 *
 * - **Knowledge**: `FreshnessView` and `KnowledgeGapView` are both
 *   `{ document: {...} }` — a free-form object, so every field is read defensively
 *   and a shape the server did not send renders as absent rather than as zero. There
 *   is **no document or knowledge-base route**, so ingestion, chunking and deletion
 *   are not offered.
 * - **AI governance**: `/api/v1/admin/ai/*` is a real policy surface with six reads.
 *   There is **no agent, prompt or run route**, so agent configuration is not
 *   offered.
 * - **Channels**: `capabilities()` works. `connections()` is declared with a
 *   **required `TenantContext` query parameter** — a documented contract defect; no
 *   client can serialise that object onto a query string and the server resolves the
 *   context from the session anyway. Rather than send a malformed parameter or hide
 *   the failure, the screen states that connection health is unavailable and why.
 * - **Escalation**: `SaveResolutionRequest.escalationRequested` and
 *   `.escalatedToSupervisor` are **fields on a case resolution**. There is no
 *   escalation queue, no acknowledge and no supervisor view, so the screen points at
 *   where it *is* recorded instead of drawing an empty queue.
 *
 * Everything on this screen is read-only. Every mutation on these surfaces is an
 * explicit command on a route this client does not call, which is the property
 * FR-5.9 asks for and the reason nothing here is a button that changes state.
 */
import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { ApiError } from '../../core/api/api.service';
import { AiGovernanceApi, ChannelsApi, OperationsApi } from '../../core/api/endpoints';
import { SessionService } from '../../core/auth/session.service';
import { PERMISSIONS } from '../../core/auth/permissions';
import { FormatService } from '../../core/format/format.service';
import { I18nService } from '../../core/i18n/i18n.service';
import type { Namespace } from '../../core/i18n/i18n.types';
import { ProjectContextService } from '../../core/context/project-context.service';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { ResourceStore } from '../../core/state/resource';
import { AsyncStateComponent } from '../../ui/async-state.component';
import { NotAvailableComponent } from '../../ui/not-available.component';
import { PageHeaderComponent } from '../../ui/page-header.component';
import { StatusChipComponent } from '../../ui/status-chip.component';
import { knowledgeStrings } from './knowledge.strings';

/** A row out of a free-form `{ document: {...} }` response, read defensively. */
interface FactRow {
  readonly key: string;
  readonly label: string;
  readonly value: string;
}

@Component({
  selector: 'fe-knowledge-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    AsyncStateComponent,
    NotAvailableComponent,
    PageHeaderComponent,
    StatusChipComponent,
    TranslatePipe,
  ],
  templateUrl: './knowledge.component.html',
  styleUrl: './knowledge.component.css',
})
export class KnowledgeComponent implements OnInit {
  private readonly operations = inject(OperationsApi);
  private readonly ai = inject(AiGovernanceApi);
  private readonly channels = inject(ChannelsApi);

  protected readonly format = inject(FormatService);
  protected readonly i18n = inject(I18nService);
  protected readonly sessions = inject(SessionService);
  protected readonly projects = inject(ProjectContextService);

  protected readonly freshness = new ResourceStore<Record<string, unknown>>();
  protected readonly gaps = new ResourceStore<Record<string, unknown>>();

  protected readonly aiTools = new ResourceStore<Record<string, unknown>>();
  protected readonly aiPolicies = new ResourceStore<Record<string, unknown>>();
  protected readonly aiBudgets = new ResourceStore<Record<string, unknown>>();
  protected readonly aiApprovals = new ResourceStore<Record<string, unknown>>();
  protected readonly aiBlocked = new ResourceStore<Record<string, unknown>>();
  protected readonly aiRedactions = new ResourceStore<Record<string, unknown>>();

  protected readonly capabilities = new ResourceStore<Record<string, unknown>>();
  /**
   * Connection health. Deliberately never loaded: the route's declared parameter
   * cannot be satisfied, so calling it would produce a 400 that says nothing about
   * the channels. The screen states the absence instead — see the class comment.
   */
  protected readonly connections = signal<'unavailable'>('unavailable');

  protected readonly canReadKnowledge = computed(() => this.sessions.can()(PERMISSIONS.knowledgeRead));
  protected readonly canReadAi = computed(() => this.sessions.can()(PERMISSIONS.systemHealthRead));
  protected readonly canReadChannels = computed(() => this.sessions.can()(PERMISSIONS.channelRead));

  protected readonly narrow = signal(false);

  constructor() {
    this.i18n.register({ knowledge: knowledgeStrings as Namespace });
  }

  ngOnInit(): void {
    void this.load();
  }

  protected async load(): Promise<void> {
    const projectId = this.projects.selectedProjectId() ?? undefined;
    const jobs: Promise<unknown>[] = [];

    if (this.canReadKnowledge()) {
      jobs.push(
        this.freshness.load(() => this.operations.searchFreshness({ projectId })),
        this.gaps.load(() => this.operations.searchGaps({ projectId }), isEmptyDocument),
      );
    }
    if (this.canReadAi()) {
      jobs.push(
        this.aiTools.load(() => this.ai.tools(), isEmptyDocument),
        this.aiPolicies.load(() => this.ai.policies(), isEmptyDocument),
        this.aiBudgets.load(() => this.ai.budgets(), isEmptyDocument),
        this.aiApprovals.load(() => this.ai.approvals(), isEmptyDocument),
        this.aiBlocked.load(() => this.ai.blockedCalls(), isEmptyDocument),
        this.aiRedactions.load(() => this.ai.redactions(), isEmptyDocument),
      );
    }
    if (this.canReadChannels()) {
      jobs.push(this.capabilities.load(() => this.channels.capabilities(), isEmptyDocument));
    }

    await Promise.allSettled(jobs);
  }

  // -- freshness ------------------------------------------------------------

  /**
   * The freshness rows, read from a free-form document.
   *
   * `FreshnessView` is `{ document: { ... } }` with no declared shape, so the values
   * are looked up by a list of candidate keys rather than by one fixed path. A key
   * the server did not send becomes `—`, which is the difference between "no data"
   * and "zero" that FR-7.6 requires.
   */
  protected freshnessRows(): FactRow[] {
    const document = documentOf(this.freshness.value());
    if (!document) return [];
    return rowsFrom(document, [
      { keys: ['knowledgeBaseId', 'knowledgeBase', 'documentId', 'id'], label: 'knowledge.freshness.source' },
      { keys: ['chunks', 'chunkCount'], label: 'knowledge.freshness.chunks', numeric: true },
      { keys: ['lastEmbeddedAt', 'embeddedAt', 'updatedAt'], label: 'knowledge.freshness.embedded', date: true },
    ]);
  }

  protected gapRows(): FactRow[] {
    const document = documentOf(this.gaps.value());
    if (!document) return [];
    return rowsFrom(document, [
      { keys: ['question', 'text', 'query'], label: 'knowledge.gaps.question' },
      { keys: ['occurrences', 'count', 'hits'], label: 'knowledge.gaps.occurrences', numeric: true },
    ]);
  }

  protected channelCapabilityRows(): FactRow[] {
    const document = documentOf(this.capabilities.value());
    if (!document) return [];
    return rowsFrom(document, [
      { keys: ['channel', 'name', 'id'], label: 'knowledge.channels.channel' },
      { keys: ['capability', 'capabilities'], label: 'knowledge.channels.capability' },
      { keys: ['supported', 'enabled', 'available'], label: 'knowledge.channels.supported', boolean: true },
    ]);
  }

  /**
   * One AI section's rows.
   *
   * Six free-form documents read the same way. Each is a policy *record*, and the
   * row set is derived from what the server actually sent rather than from a fixed
   * column list — a table whose columns are hard-coded would show empty cells for
   * every field a section happens not to carry.
   */
  protected aiRows(store: ResourceStore<Record<string, unknown>>): FactRow[] {
    const document = documentOf(store.value());
    if (!document) return [];
    return rowsFrom(document, [
      { keys: ['name', 'id', 'tool', 'policy', 'model'], label: 'knowledge.ai.title' },
      { keys: ['capability', 'scope', 'principal', 'subject'], label: 'knowledge.ai.policies' },
      { keys: ['status', 'state', 'decision', 'outcome'], label: 'knowledge.channels.capabilities' },
      { keys: ['tool', 'toolName'], label: 'knowledge.ai.tools' },
      { keys: ['reason', 'denyReason', 'blockedReason'], label: 'knowledge.ai.blocked' },
      { keys: ['limit', 'amount', 'spent', 'budget'], label: 'knowledge.ai.budgets', numeric: true },
      { keys: ['occurredAt', 'createdAt', 'recordedAt', 'at'], label: 'knowledge.freshness.embedded', date: true },
    ]);
  }

  protected aiSections(): {
    key: string;
    titleKey: string;
    store: ResourceStore<Record<string, unknown>>;
  }[] {
    return [
      { key: 'tools', titleKey: 'knowledge.ai.tools', store: this.aiTools },
      { key: 'policies', titleKey: 'knowledge.ai.policies', store: this.aiPolicies },
      { key: 'budgets', titleKey: 'knowledge.ai.budgets', store: this.aiBudgets },
      { key: 'approvals', titleKey: 'knowledge.ai.approvals', store: this.aiApprovals },
      { key: 'blocked', titleKey: 'knowledge.ai.blocked', store: this.aiBlocked },
      { key: 'redactions', titleKey: 'knowledge.ai.redactions', store: this.aiRedactions },
    ];
  }

  protected freshnessErrorRequestId(): string | null {
    const error = this.freshness.error();
    return error instanceof ApiError ? error.requestId : null;
  }
}

/** Unwraps a `{ document: {...} }` response, or takes a bare object as-is. */
function documentOf(value: unknown): Record<string, unknown> | null {
  if (value === null || typeof value !== 'object') return null;
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
  if (!document) return true;
  return Object.keys(document).length === 0;
}

interface ColumnSpec {
  readonly keys: readonly string[];
  /** i18n key of the column heading. */
  readonly label: string;
  readonly numeric?: boolean;
  readonly boolean?: boolean;
  readonly date?: boolean;
}

/**
 * Turns a free-form document into rows.
 *
 * Prefers an array under a list-ish key, and falls back to treating the object's own
 * entries as rows — because the contract declares neither shape. Each cell is looked
 * up through a list of candidate keys, and a value that is absent renders as `—`
 * rather than as an empty string or a zero.
 */
function rowsFrom(document: Record<string, unknown>, columns: readonly ColumnSpec[]): FactRow[] {
  const list = Object.entries(document).find(
    ([, value]) => Array.isArray(value) && value.length > 0,
  );
  const entries: [string, unknown][] = list
    ? (list[1] as unknown[]).map((value, index) => [String(index), value])
    : Object.entries(document);

  return entries.map(([key, value]) => {
    const record = value !== null && typeof value === 'object' && !Array.isArray(value)
      ? (value as Record<string, unknown>)
      : null;
    return {
      key,
      label: columns.map((column) => firstOf(record, column.keys)).find((v) => v !== null) ?? key,
      value: columns
        .map((column) => renderCell(record, value, column))
        .find((rendered) => rendered !== null) ?? '—',
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
  const raw = record ? record[column.keys[0]!] ?? record[column.keys[1]!] : whole;
  if (raw === undefined || raw === null || raw === '') return null;
  if (column.boolean) return raw === true || raw === 'true' ? 'yes' : 'no';
  if (column.numeric && typeof raw === 'number') return String(raw);
  if (column.date) return String(raw);
  return String(raw);
}
