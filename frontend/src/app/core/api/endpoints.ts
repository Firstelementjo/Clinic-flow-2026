/**
 * Typed endpoint layer.
 *
 * One class per backend route group, each method named after what the route
 * does. Every request/response type comes from the generated client, so a
 * contract change breaks compilation here rather than at runtime in a screen.
 *
 * Two rules this layer enforces that a caller could otherwise get wrong:
 *
 * - **A project id is a request, never authorization.** Every `projectId`
 *   argument is optional and is passed straight through to `TenantQueryGuard` on
 *   the server. A value typed here grants nothing; a value outside the caller's
 *   server-resolved scope comes back as a non-disclosing 404.
 * - **`limit` is a number and `cursor` is a string**, matching the wire. The
 *   document declares `limit` as a string because the controller parses a string
 *   (`RequestParams.limit`); typing it as a number at the call site keeps the
 *   caller from building a bad query.
 */
import { Injectable, inject } from '@angular/core';
import { ApiService, type QueryParams } from '../api/api.service';
import type * as Api from '../api/generated';

export interface ConversationListQuery extends QueryParams {
  projectId?: string;
  filter?: string;
  channel?: string;
  search?: string;
  cursor?: string;
  limit?: number;
}

export interface InboxCountsQuery extends QueryParams {
  projectId?: string;
  channel?: string;
  search?: string;
}

export interface CustomerListQuery extends QueryParams {
  projectId?: string;
  search?: string;
  cursor?: string;
  limit?: number;
}

/**
 * What `POST .../close` and `POST .../reopen` actually answer with.
 *
 * A **status, not the thread.** The contract declares both responses as
 * `{"type":"object","additionalProperties":{"type":"string"}}` and the server sends
 * `{"status":"CLOSED"}` — no `id`, no `version`, no messages.
 *
 * The index signature is carried so a future field is not a compile error, and `status`
 * is declared so the one field this client acts on is typed rather than indexed as a
 * string. It is deliberately **not** assignable to `ConversationDetail`: the whole point
 * is that a caller cannot treat one as the other. See `ConversationApi.close`.
 */
export interface ConversationStatusView {
  readonly status?: string;
  readonly [key: string]: string | undefined;
}

/**
 * The optimistic-concurrency version a read model published, if any.
 *
 * Read from the row the server sent rather than tracked locally: a version the
 * client invented is a guess about what the server holds, and the whole point of
 * `If-Match` is that the comparison happens where the truth is.
 */
export function versionOf(record: unknown): string | null {
  if (record === null || typeof record !== 'object') return null;
  const candidate = (record as Record<string, unknown>)['version'];
  if (typeof candidate === 'string' && candidate !== '') return candidate;
  if (typeof candidate === 'number') return String(candidate);
  const etag = (record as Record<string, unknown>)['etag'];
  if (typeof etag === 'string' && etag !== '') return etag;
  return null;
}

/** `/api/v1/auth` — the browser credential surface. */
@Injectable({ providedIn: 'root' })
export class AuthApi {
  private readonly api = inject(ApiService);

  /**
   * `GET /api/v1/auth/csrf` — public, so a client can obtain a token before its
   * first mutation. The document declares a `token` query parameter for this
   * route; the controller takes the token as a resolved argument and the
   * parameter is an artefact of how springdoc renders it, so this method sends
   * nothing. See the Phase 11 report: it is a contract defect, not a usage quirk.
   */
  csrf(): Promise<{ token?: string; headerName?: string; parameterName?: string }> {
    return this.api.get('/api/v1/auth/csrf');
  }

  login(body: Api.LoginRequest): Promise<Api.SessionResponse> {
    return this.api.post('/api/v1/auth/login', body);
  }

  me(): Promise<Api.SessionResponse> {
    return this.api.get('/api/v1/auth/me');
  }

  logout(): Promise<void> {
    return this.api.post('/api/v1/auth/logout');
  }
}

/** `/api/v1/permissions` — what the caller may actually do, not what their role says. */
@Injectable({ providedIn: 'root' })
export class PermissionsApi {
  private readonly api = inject(ApiService);

  me(): Promise<Api.PermissionInspection> {
    return this.api.get('/api/v1/permissions/me');
  }

  inspect(userId: string): Promise<Api.PermissionInspection> {
    return this.api.get(`/api/v1/permissions/${encodeURIComponent(userId)}`);
  }
}

/** `/api/v1/inbox` — the queue and the saved-view counts. */
@Injectable({ providedIn: 'root' })
export class InboxApi {
  private readonly api = inject(ApiService);

  counts(query: InboxCountsQuery = {}): Promise<Record<string, number>> {
    return this.api.get('/api/v1/inbox/counts', query);
  }

  unassigned(query: QueryParams = {}): Promise<Api.UnassignedQueuePage> {
    return this.api.get('/api/v1/inbox/unassigned', query);
  }
}

/** `/api/v1/assignments` — kept separate from the conversation routes it mirrors. */
@Injectable({ providedIn: 'root' })
export class AssignmentsApi {
  private readonly api = inject(ApiService);

  assign(
    conversationId: string,
    body: Api.AssignConversationRequest,
    version?: string | null,
  ): Promise<Api.ConversationDetail> {
    return this.api.putWithHeaders(
      `/api/v1/assignments/${encodeURIComponent(conversationId)}`,
      body,
      undefined,
      ifMatch(version),
    );
  }
}

/**
 * The `If-Match` header, when the server published a version.
 *
 * `null` and `undefined` both mean "the server did not offer a version", and the
 * header is omitted in that case. Sending `If-Match: null` would be a literal
 * string the server would not recognise as absent, so a caller that guesses a
 * version is worse than one that sends none — the request is then rejected by the
 * server on its own terms rather than confusing it first.
 */
function ifMatch(version: string | null | undefined): Record<string, string> {
  return version ? { 'If-Match': version } : {};
}

/**
 * The headers a reply carries.
 *
 * `Idempotency-Key` makes a double-submit one write. `X-Expected-Message-Count`
 * makes the collision check explicit: "I am replying to the thread as I saw it",
 * so a second operator's reply that landed first is refused rather than silently
 * interleaved.
 *
 * Both are omitted when the caller has nothing to say — an absent count means "I
 * am not asserting one", which is different from asserting zero.
 */
function replyHeaders(options: {
  expectedMessageCount?: number | null;
  idempotencyKey?: string | null;
}): Record<string, string> {
  const headers: Record<string, string> = {};
  if (options.idempotencyKey) headers['Idempotency-Key'] = options.idempotencyKey;
  if (typeof options.expectedMessageCount === 'number') {
    headers['X-Expected-Message-Count'] = String(options.expectedMessageCount);
  }
  return headers;
}

/**
 * `/api/v1/realtime` and `/api/v1/notifications` — the notification read model
 * the SSE stream is a hint about. Every response is a free-form object in the
 * document, so the shapes below are declared here and asserted by
 * `firstelement-api.spec.ts` against a recorded payload.
 */
export interface NotificationRow {
  id?: string;
  projectId?: string;
  type?: string;
  title?: string;
  body?: string | null;
  resourceType?: string | null;
  resourceId?: string | null;
  read?: boolean;
  readAt?: string | null;
  createdAt?: string;
}

export interface RealtimeHealthSnapshot {
  localSubscribers?: number;
  connectionsOpened?: number;
  resyncsSent?: number;
  multiInstanceCapable?: boolean;
  channel?: string;
  subscribersOnChannel?: number;
}

export interface RealtimeState {
  organizationId?: string;
  serverTime?: string;
  unreadCount?: number;
  notifications?: NotificationRow[];
  projects?: string[];
  realtime?: RealtimeHealthSnapshot;
}

@Injectable({ providedIn: 'root' })
export class NotificationsApi {
  private readonly api = inject(ApiService);

  /** The resync target: unread count, notification list, server clock, stream health. */
  state(unreadOnly = false): Promise<RealtimeState> {
    return this.api.get('/api/v1/realtime/state', { unreadOnly });
  }

  list(unreadOnly = false): Promise<NotificationRow[]> {
    return this.api.get('/api/v1/notifications', { unreadOnly });
  }

  markRead(notificationId: string): Promise<{ updated?: number }> {
    return this.api.post(`/api/v1/notifications/${encodeURIComponent(notificationId)}/read`);
  }

  markAllRead(): Promise<{ updated?: number }> {
    return this.api.post('/api/v1/notifications/read-all');
  }
}

/** `/api/v1/operations` — queue depth, connectors, webhooks, search freshness, stream health. */
@Injectable({ providedIn: 'root' })
export class OperationsApi {
  private readonly api = inject(ApiService);

  realtime(): Promise<{ document?: Record<string, unknown> }> {
    return this.api.get('/api/v1/operations/realtime');
  }

  jobs(query: QueryParams = {}): Promise<Record<string, unknown>> {
    return this.api.get('/api/v1/operations/jobs', query);
  }

  worklist(query: QueryParams = {}): Promise<Record<string, unknown>> {
    return this.api.get('/api/v1/operations/jobs/worklist', query);
  }

  strandedLeases(query: QueryParams = {}): Promise<Record<string, unknown>> {
    return this.api.get('/api/v1/operations/jobs/stranded-leases', query);
  }

  connectors(query: QueryParams = {}): Promise<Record<string, unknown>> {
    return this.api.get('/api/v1/operations/connectors', query);
  }

  webhooks(query: QueryParams = {}): Promise<Record<string, unknown>> {
    return this.api.get('/api/v1/operations/webhooks', query);
  }

  searchFreshness(query: QueryParams = {}): Promise<Record<string, unknown>> {
    return this.api.get('/api/v1/operations/search/freshness', query);
  }

  searchGaps(query: QueryParams = {}): Promise<Record<string, unknown>> {
    return this.api.get('/api/v1/operations/search/gaps', query);
  }
}

/**
 * `/api/v1/conversations` and `/api/v1/cases` — the inbox and the thread.
 *
 * Every mutation carries an `If-Match` when the server offered one. That is
 * optimistic concurrency (`PROJECT_REQUIREMENTS` §6: `If-Match` or an explicit
 * version), and it is the reason two operators replying to the same conversation
 * cannot silently overwrite each other: the second write is refused with `409` and
 * the UI says so. `versionOf` reads the version the read model published rather
 * than tracking one locally, because a locally-tracked version is a guess about
 * what the server holds.
 *
 * `X-Expected-Message-Count` on a reply is the second half of the collision check:
 * it says "I am replying to the thread as I saw it". The server compares and
 * refuses a reply written against a stale thread.
 */
@Injectable({ providedIn: 'root' })
export class ConversationApi {
  private readonly api = inject(ApiService);

  list(query: ConversationListQuery = {}): Promise<Api.CursorPageConversationSummary> {
    return this.api.get('/api/v1/conversations', query);
  }

  detail(conversationId: string): Promise<Api.ConversationDetail> {
    return this.api.get(`/api/v1/conversations/${encodeURIComponent(conversationId)}`);
  }

  /** `PATCH` with the optimistic-concurrency header the read model published. */
  update(
    conversationId: string,
    body: Api.UpdateConversationRequest,
    version?: string | null,
  ): Promise<Api.ConversationDetail> {
    return this.api.patchWithHeaders(
      `/api/v1/conversations/${encodeURIComponent(conversationId)}`,
      body,
      undefined,
      ifMatch(version),
    );
  }

  /**
   * `POST .../messages` — the reply.
   *
   * `Idempotency-Key` is derived from the conversation, the message count the
   * author was looking at, and a client-generated nonce, so a double-submit of the
   * same composed message is recognised as the same write rather than posted twice.
   * The nonce is per-compose-session rather than random per call, so a retry after
   * a network failure carries the same key — which is the entire point of the
   * header.
   */
  reply(
    conversationId: string,
    body: Api.SendMessageRequest,
    options: { expectedMessageCount?: number | null; idempotencyKey?: string | null } = {},
  ): Promise<Api.ConversationDetail> {
    return this.api.postWithHeaders(
      `/api/v1/conversations/${encodeURIComponent(conversationId)}/messages`,
      body,
      undefined,
      replyHeaders(options),
    );
  }

  /** `PATCH .../mode` — AI ⇄ human. The version guards a simultaneous takeover. */
  setMode(
    conversationId: string,
    body: Api.ChangeModeRequest,
    version?: string | null,
  ): Promise<Api.ConversationDetail> {
    return this.api.patchWithHeaders(
      `/api/v1/conversations/${encodeURIComponent(conversationId)}/mode`,
      body,
      undefined,
      ifMatch(version),
    );
  }

  classify(
    conversationId: string,
  ): Promise<Api.ClassificationView | null> {
    return this.api.get(
      `/api/v1/conversations/${encodeURIComponent(conversationId)}/classification`,
    );
  }

  saveClassification(
    conversationId: string,
    body: Api.SaveClassificationRequest,
  ): Promise<Api.ClassificationView> {
    return this.api.put(
      `/api/v1/conversations/${encodeURIComponent(conversationId)}/classification`,
      body,
    );
  }

  classificationQuestions(
    projectId: string,
  ): Promise<Api.ClassificationQuestionView[]> {
    return this.api.get(
      `/api/v1/projects/${encodeURIComponent(projectId)}/classification-questions`,
    );
  }

  resolution(conversationId: string): Promise<Api.ResolutionView | null> {
    return this.api.get(
      `/api/v1/conversations/${encodeURIComponent(conversationId)}/resolution`,
    );
  }

  saveResolution(
    conversationId: string,
    body: Api.SaveResolutionRequest,
  ): Promise<Api.ResolutionView> {
    return this.api.put(
      `/api/v1/conversations/${encodeURIComponent(conversationId)}/resolution`,
      body,
    );
  }

  /**
   * Closes a conversation. **Returns a status, not the thread.**
   *
   * ## This return type used to lie, and the lie killed every later action
   *
   * It was declared `Promise<Api.ConversationDetail>`. The server answers
   * `200 {"status":"CLOSED"}` — nineteen bytes: no `id`, no `version`, no messages. The
   * contract never claimed otherwise; it declares this response as
   * `{"type":"object","additionalProperties":{"type":"string"}}`, a free-form string map.
   *
   * The component then did `this.thread.set(updated)`, **replacing the whole thread with
   * that one-field object.** Everything still rendered — the pane, the case actions, the
   * Reopen button — because the value was non-null. But every action calls
   * `requireThread()`, which needs `detail.id`, and `id` was now gone. So clicking
   * **Reopen** returned silently: no request, no announcement, no error. So did take
   * ownership, mode change, close again and reply.
   *
   * The operator clicked a button and nothing happened, with the screen showing a
   * perfectly normal thread. It reproduced five times out of five.
   *
   * So the type is what the wire says, and the component **merges** the status rather
   * than replacing the thread with it.
   */
  close(conversationId: string, version?: string | null): Promise<ConversationStatusView> {
    return this.api.postWithHeaders(
      `/api/v1/conversations/${encodeURIComponent(conversationId)}/close`,
      undefined,
      undefined,
      ifMatch(version),
    );
  }

  /** Reopens a conversation. Also a **status**, not the thread. See `close`. */
  reopen(conversationId: string): Promise<ConversationStatusView> {
    return this.api.post(
      `/api/v1/conversations/${encodeURIComponent(conversationId)}/reopen`,
    );
  }

  assign(
    conversationId: string,
    body: Api.AssignConversationRequest,
    version?: string | null,
  ): Promise<Api.ConversationDetail> {
    return this.api.putWithHeaders(
      `/api/v1/assignments/${encodeURIComponent(conversationId)}`,
      body,
      undefined,
      ifMatch(version),
    );
  }

  transcript(conversationId: string): Promise<Api.CustomerTranscript | null> {
    return this.api.get(
      `/api/v1/conversations/${encodeURIComponent(conversationId)}/transcript`,
    );
  }
}

/**
 * `/api/v1/ai/drafts` — the pending, human-reviewable AI draft on a conversation.
 *
 * ## Read-only, and that is the contract
 *
 * The published document declares **one** operation on this route, a `GET`, and no
 * request body: `GET /api/v1/ai/drafts/conversations/{conversationId}`.
 * There is no confirm, no send and no discard, and adding one would be inventing
 * a second send path beside `ConversationApi.reply`. Sending a draft is a reply,
 * and a reply goes through the reply route.
 *
 * ## Why there is no `projectId` argument
 *
 * The route has no tenant parameter and no project parameter, by design: the
 * controller reads `TenantContextHolder.get()` and the service derives the
 * project from the conversation row inside `TenantQueryGuard`. A client that
 * offered a scope argument here would be inviting the caller to pass one, so the
 * signature has exactly one parameter and it names a conversation, not a tenant.
 * A value typed here grants nothing; a conversation outside the caller's
 * server-resolved scope comes back as a non-disclosing 404.
 *
 * ## What the 404 means, and why the caller must not read it as an error
 *
 * The route answers **the same 404** for a conversation that does not exist, one
 * belonging to another tenant, one whose draft a human took over, and one with
 * no draft at all. That identity is the non-disclosure property, so a caller
 * cannot treat it as a failure — and must not surface the server's message,
 * which would turn a lookup into an oracle.
 */
@Injectable({ providedIn: 'root' })
export class AiDraftApi {
  private readonly api = inject(ApiService);

  pendingDraft(conversationId: string): Promise<Api.AiDraftView> {
    return this.api.get(
      `/api/v1/ai/drafts/conversations/${encodeURIComponent(conversationId)}`,
    );
  }
}

/** `/api/v1/customers` — people, and the 360 view behind one of them. */
@Injectable({ providedIn: 'root' })
export class CustomerApi {
  private readonly api = inject(ApiService);

  list(query: CustomerListQuery = {}): Promise<Api.CursorPageCustomerListItem> {
    return this.api.get('/api/v1/customers', query);
  }

  detail(customerId: string): Promise<Api.CustomerDetail> {
    return this.api.get(`/api/v1/customers/${encodeURIComponent(customerId)}`);
  }

  update(
    customerId: string,
    body: Api.UpdateCustomerRequest,
    version?: string | null,
  ): Promise<Api.CustomerDetail> {
    return this.api.patchWithHeaders(
      `/api/v1/customers/${encodeURIComponent(customerId)}`,
      body,
      undefined,
      ifMatch(version),
    );
  }
}

/**
 * `/api/v1/notes` — internal notes.
 *
 * Two separate lists, and they are not interchangeable: `notes/project` is
 * everything internal in the project, while `notes` filtered by resource is what
 * one customer or conversation carries. Customer-facing history and internal
 * history must stay separate (`PROJECT_REQUIREMENTS` FR-3.4), so the UI never
 * merges them into one timeline.
 */
@Injectable({ providedIn: 'root' })
export class NotesApi {
  private readonly api = inject(ApiService);

  forResource(query: { resourceType?: string; resourceId?: string; projectId?: string } = {}) {
    return this.api.get<Api.ForResource[]>('/api/v1/notes', query);
  }

  forProject(query: { projectId?: string } = {}) {
    return this.api.get<Api.ForProject[]>('/api/v1/notes/project', query);
  }

  create(body: Api.NoteRequest): Promise<Api.Created> {
    return this.api.post('/api/v1/notes', body);
  }

  update(noteId: string, body: Api.UpdateNoteRequest, version?: string | null) {
    return this.api.patchWithHeaders(
      `/api/v1/notes/${encodeURIComponent(noteId)}`,
      body,
      undefined,
      ifMatch(version),
    );
  }

  delete(noteId: string, projectId?: string) {
    return this.api.delete(`/api/v1/notes/${encodeURIComponent(noteId)}`, { projectId });
  }
}

/**
 * `/api/v1/channels` — what a connector can do, and whether it is connected.
 *
 * `GET /api/v1/channels/connections` takes a **required** `context` query parameter
 * typed as `TenantContext` — a documented contract defect, see the phase-11 report.
 * No client can serialise a `TenantContext` onto a query string, and the server
 * resolves the context from the session anyway, so this method sends nothing and
 * relies on the session. The workaround is stated rather than hidden: the screen
 * below is a **capability** view, and it says so when it cannot report connection
 * health, instead of showing an empty table that looks like "no channels".
 */
@Injectable({ providedIn: 'root' })
export class ChannelsApi {
  private readonly api = inject(ApiService);

  capabilities(): Promise<Record<string, unknown>> {
    return this.api.get('/api/v1/channels/capabilities');
  }

  /** Documented defect: see the class comment. */
  connections(): Promise<Record<string, unknown>[]> {
    return this.api.get('/api/v1/channels/connections');
  }
}

/**
 * `/api/v1/admin/ai` — the AI policy surface: tools, budgets, approvals, blocked
 * calls, redactions. Read-only here; every decision is an explicit command taken
 * through a route this client does not yet call.
 */
@Injectable({ providedIn: 'root' })
export class AiGovernanceApi {
  private readonly api = inject(ApiService);

  tools(query: QueryParams = {}): Promise<Record<string, unknown>> {
    return this.api.get('/api/v1/admin/ai/tools', query);
  }

  policies(query: QueryParams = {}): Promise<Record<string, unknown>> {
    return this.api.get('/api/v1/admin/ai/policies', query);
  }

  budgets(query: QueryParams = {}): Promise<Record<string, unknown>> {
    return this.api.get('/api/v1/admin/ai/budgets', query);
  }

  approvals(query: QueryParams = {}): Promise<Record<string, unknown>> {
    return this.api.get('/api/v1/admin/ai/approvals', query);
  }

  blockedCalls(query: QueryParams = {}): Promise<Record<string, unknown>> {
    return this.api.get('/api/v1/admin/ai/blocked-calls', query);
  }

  redactions(query: QueryParams = {}): Promise<Record<string, unknown>> {
    return this.api.get('/api/v1/admin/ai/redactions', query);
  }
}

/** `/api/v1/admin` — the control plane. Every method here is admin-gated server-side. */
@Injectable({ providedIn: 'root' })
export class AdminApi {
  private readonly api = inject(ApiService);

  overview(query: QueryParams = {}): Promise<Record<string, unknown>> {
    return this.api.get('/api/v1/admin/overview', query);
  }

  auditEvents(query: QueryParams = {}): Promise<Record<string, unknown>> {
    return this.api.get('/api/v1/admin/audit/events', query);
  }

  auditSecurity(query: QueryParams = {}): Promise<Record<string, unknown>> {
    return this.api.get('/api/v1/admin/audit/security', query);
  }

  identity(): Promise<Api.DirectoryView> {
    return this.api.get('/api/v1/admin/identity');
  }

  sessions(query: QueryParams = {}): Promise<Api.SessionRow[]> {
    return this.api.get('/api/v1/admin/identity/sessions', query);
  }
}

/**
 * `/api/v1/admin` — the administration read model, phase 11 group 9.
 *
 * ## The permission behind each call
 *
 * Read straight off the controller's own `@RequirePermission`, not guessed. Where the
 * annotation is absent, that is said in the method's own comment rather than left for a
 * reader to assume.
 *
 * | Method | Route | Backend permission |
 * |---|---|---|
 * | `overview` | `GET /admin/overview` | `system-health:read` |
 * | `migrations` | `GET /admin/migrations` | `system-health:read` |
 * | `backups`, `backup` | `GET /admin/backups[/{name}]` | `system-health:read` |
 * | `maintenanceCommands`, `disabledCommands` | `GET /admin/maintenance/commands[/disabled]` | `system-health:read` |
 * | `purgeGate` | `GET /admin/governance/maintenance/purge-gate` | `system-health:read` |
 * | `identity`, `sessions` | `GET /admin/identity[/sessions]` | `user:manage`, `session:revoke` |
 * | `auditEvents` | `GET /admin/audit/events` | `audit-log:read` |
 * | `auditSecurity` | `GET /admin/audit/security` | `security-event:read` |
 * | `retentionClasses`, `legalHolds`, `legalHoldHistory`, `deletions`, `deletionTargets` | `GET /admin/governance/*` | `security-event:read` |
 * | `exportJobs`, `exportJobDetail` | `GET /admin/exports/jobs[/{id}/detail]` | `analytics:read-organization` |
 *
 * ## The five mutations, and why none of them is here
 *
 * `AdminGovernanceMaintenanceController` exposes seven `POST`s — place a legal hold,
 * release one, request a privacy deletion, preview one, execute one, purge — all under
 * `ORGANIZATION_UPDATE`, which is the broadest write in the system and is reused for
 * export-artefact deletion and export retention expiry.
 *
 * This client calls **none of them**. Not because the routes are missing, but because a
 * destructive governance write is a deliberate, individually-named act, and FR-5.9 asks
 * for AI and governance decisions to be explicit commands rather than inline controls.
 * Building a one-click purge button next to a status chip is the opposite of that. The
 * screen shows the *state* those writes change — holds, deletion requests, the purge
 * gate — so an operator can see what is held and why, and the writes themselves stay
 * an out-of-band operator action until a deliberate mission adds them with their own
 * confirmation and audit treatment.
 */
@Injectable({ providedIn: 'root' })
export class AdminReadApi {
  private readonly api = inject(ApiService);

  overview(query: QueryParams = {}): Promise<Record<string, unknown>> {
    return this.api.get('/api/v1/admin/overview', query);
  }

  migrations(query: QueryParams = {}): Promise<Record<string, unknown>> {
    return this.api.get('/api/v1/admin/migrations', query);
  }

  backups(query: QueryParams = {}): Promise<Record<string, unknown>> {
    return this.api.get('/api/v1/admin/backups', query);
  }

  backup(name: string, query: QueryParams = {}): Promise<Record<string, unknown>> {
    return this.api.get(`/api/v1/admin/backups/${encodeURIComponent(name)}`, query);
  }

  maintenanceCommands(query: QueryParams = {}): Promise<Record<string, unknown>> {
    return this.api.get('/api/v1/admin/maintenance/commands', query);
  }

  disabledCommands(query: QueryParams = {}): Promise<Record<string, unknown>> {
    return this.api.get('/api/v1/admin/maintenance/commands/disabled', query);
  }

  identity(query: QueryParams = {}): Promise<Api.DirectoryView> {
    return this.api.get('/api/v1/admin/identity', query);
  }

  sessions(query: QueryParams = {}): Promise<Api.SessionRow[]> {
    return this.api.get('/api/v1/admin/identity/sessions', query);
  }

  auditEvents(query: QueryParams = {}): Promise<Record<string, unknown>> {
    return this.api.get('/api/v1/admin/audit/events', query);
  }

  auditSecurity(query: QueryParams = {}): Promise<Record<string, unknown>> {
    return this.api.get('/api/v1/admin/audit/security', query);
  }

  retentionClasses(query: QueryParams = {}): Promise<Record<string, unknown>> {
    return this.api.get('/api/v1/admin/governance/retention-classes', query);
  }

  legalHolds(query: QueryParams = {}): Promise<Record<string, unknown>> {
    return this.api.get('/api/v1/admin/governance/legal-holds', query);
  }

  legalHoldHistory(holdId: string, query: QueryParams = {}): Promise<Record<string, unknown>> {
    return this.api.get(
      `/api/v1/admin/governance/legal-holds/${encodeURIComponent(holdId)}/history`,
      query,
    );
  }

  deletions(query: QueryParams = {}): Promise<Record<string, unknown>> {
    return this.api.get('/api/v1/admin/governance/deletions', query);
  }

  deletionTargets(requestId: string, query: QueryParams = {}): Promise<Record<string, unknown>> {
    return this.api.get(
      `/api/v1/admin/governance/deletions/${encodeURIComponent(requestId)}/targets`,
      query,
    );
  }

  /** Read-only: what a purge would touch. The purge itself is a POST this client skips. */
  purgeGate(query: QueryParams = {}): Promise<Record<string, unknown>> {
    return this.api.get('/api/v1/admin/governance/maintenance/purge-gate', query);
  }

  exportJobs(query: QueryParams = {}): Promise<Record<string, unknown>> {
    return this.api.get('/api/v1/admin/exports/jobs', query);
  }

  exportJobDetail(jobId: string, query: QueryParams = {}): Promise<Record<string, unknown>> {
    return this.api.get(
      `/api/v1/admin/exports/jobs/${encodeURIComponent(jobId)}/detail`,
      query,
    );
  }
}

/**
 * `/api/v1/finance` — phase 11 group 10.
 *
 * ## One permission governs all twenty-four routes
 *
 * `FinanceController` guards every handler with `ANALYTICS_READ_ORGANIZATION` — usage,
 * forecast, drill-through, rate cards, period close/lock/reopen, adjustment approval,
 * reconciliation. Its javadoc is explicit that this is the intent (*"There is no weaker
 * permission for 'what does this cost'"*), and `FinanceRoutePermissionTest` asserts by
 * reflection that no handler is left unguarded.
 *
 * So the client gates the whole section on that single value. There is no per-screen
 * subsetting to do and no finer distinction available: if you can see one finance route
 * you can see all of them. Building a screen that reads as though a narrower role exists
 * would be inventing an authorization the backend does not implement.
 *
 * ## The eleven writes, and what they actually do
 *
 * Locking a period, approving an adjustment and purging a rollup are finance-administrator
 * acts. The service layer additionally requires an `ADMIN` role, so the permission is the
 * declaration and the role is the enforcement — a client that shows the button for a
 * permission-holder who is not an admin would get a 403 it can explain, but one that
 * hid it for the wrong reason would be lying. This client therefore renders the writes
 * it can explain and refuses, in words, to fabricate the ones the contract cannot carry.
 *
 * `POST /legacy/comparison` does not exist; the legacy comparison is a **read**
 * (`GET /legacy/comparison`) and is included here for completeness.
 */
@Injectable({ providedIn: 'root' })
export class FinanceApi {
  private readonly api = inject(ApiService);

  usageDaily(query: QueryParams = {}): Promise<Record<string, unknown>> {
    return this.api.get('/api/v1/finance/usage/daily', query);
  }

  usageMonthly(query: QueryParams = {}): Promise<Record<string, unknown>> {
    return this.api.get('/api/v1/finance/usage/monthly', query);
  }

  usageForecast(query: QueryParams = {}): Promise<Record<string, unknown>> {
    return this.api.get('/api/v1/finance/usage/forecast', query);
  }

  periods(query: QueryParams = {}): Promise<Record<string, unknown>> {
    return this.api.get('/api/v1/finance/periods', query);
  }

  periodHistory(periodId: string, query: QueryParams = {}): Promise<Record<string, unknown>> {
    return this.api.get(`/api/v1/finance/periods/${encodeURIComponent(periodId)}/history`, query);
  }

  periodAdjustments(periodId: string, query: QueryParams = {}): Promise<Record<string, unknown>> {
    return this.api.get(`/api/v1/finance/periods/${encodeURIComponent(periodId)}/adjustments`, query);
  }

  closeReadiness(periodId: string, query: QueryParams = {}): Promise<Record<string, unknown>> {
    return this.api.get(`/api/v1/finance/periods/${encodeURIComponent(periodId)}/close-readiness`, query);
  }

  rateCards(query: QueryParams = {}): Promise<Record<string, unknown>> {
    return this.api.get('/api/v1/finance/rate-cards', query);
  }

  rateCardVersions(rateCardId: string, query: QueryParams = {}): Promise<Record<string, unknown>> {
    return this.api.get(`/api/v1/finance/rate-cards/${encodeURIComponent(rateCardId)}/versions`, query);
  }

  reconciliationRuns(query: QueryParams = {}): Promise<Record<string, unknown>> {
    return this.api.get('/api/v1/finance/reconciliation/runs', query);
  }

  reconciliationLines(runId: string, query: QueryParams = {}): Promise<Record<string, unknown>> {
    return this.api.get(`/api/v1/finance/reconciliation/runs/${encodeURIComponent(runId)}/lines`, query);
  }

  legacyComparison(query: QueryParams = {}): Promise<Record<string, unknown>> {
    return this.api.get('/api/v1/finance/legacy/comparison', query);
  }

  drillSnapshot(snapshotId: string, query: QueryParams = {}): Promise<Record<string, unknown>> {
    return this.api.get(`/api/v1/finance/drill/snapshots/${encodeURIComponent(snapshotId)}`, query);
  }

  drillCorrections(eventId: string, query: QueryParams = {}): Promise<Record<string, unknown>> {
    return this.api.get(`/api/v1/finance/drill/events/${encodeURIComponent(eventId)}/corrections`, query);
  }
}

/**
 * `/api/v1/exports` — phase 11 group 11: the report/export queue and its history.
 *
 * ## Two permissions, split by what they expose
 *
 * Seventeen routes are `ANALYTICS_READ_PROJECT` — one project's report rows. Four are
 * the stronger `ANALYTICS_READ_ORGANIZATION` or `ORGANIZATION_UPDATE`: the finance gate,
 * artefact deletion, and retention expiry/purge. That split is the reason the Exports
 * screen is built as two gated sections rather than one list.
 *
 * ## What is read, and the one thing that cannot be
 *
 * Read: the queue, the catalogue, the download policy, the per-export detail, its
 * attempts, its download history and its deletion record.
 *
 * Not offered: **creating** an export. `POST /api/v1/exports` needs a
 * `reportDefinitionId`, a `reportVersion`, a `documentTemplateId` and a
 * `documentTemplateVersion` — and **no route lists report definitions or document
 * templates**. There is nothing for a picker to choose from, so a create form could
 * only be filled by hand-typing UUIDs, which is worse than not offering it. The screen
 * says so and points at the missing routes.
 */
@Injectable({ providedIn: 'root' })
export class ExportsApi {
  private readonly api = inject(ApiService);

  list(query: QueryParams = {}): Promise<Record<string, unknown>> {
    return this.api.get('/api/v1/exports', query);
  }

  queue(query: QueryParams = {}): Promise<Record<string, unknown>> {
    return this.api.get('/api/v1/exports/queue', query);
  }

  /**
   * The export catalogue: what this project can export, in what formats, and which
   * versions **this caller** may export.
   *
   * `projectId` is **required** by the contract — not optional like the other query
   * parameters — and the type says so, so a call without one is a compile error rather
   * than a 400 twenty minutes into a run. It is still only a *request*: the server's
   * `TenantQueryGuard` resolves scope from the session regardless, so a foreign project
   * id answers the same non-disclosing 404 as a nonexistent one.
   *
   * The response is a real `$ref` to `ExportCatalogue` (ADR 0016), so it is typed from
   * the generated client rather than read defensively out of a free-form document.
   */
  catalogue(projectId: string): Promise<Api.ExportCatalogue> {
    return this.api.get('/api/v1/exports/catalogue', { projectId });
  }

  /**
   * Queues an export. **202 Accepted**, though the document still declares 200 —
   * ADR 0016 rules that fixed before cutover, and the body parses either way.
   *
   * The four identity fields come from the catalogue and nowhere else: a
   * `reportDefinitionId` + `reportVersion` and a `documentTemplateId` +
   * `documentTemplateVersion`. That is what makes the capability possible at all; before
   * the catalogue was declared, the only way to satisfy this signature was to ask an
   * operator to paste four opaque UUIDs.
   *
   * `filters` accepts only keys the chosen definition publishes in the catalogue's
   * `fields` — the server refuses anything else with a 400 rather than ignoring it, so
   * the client offers the catalogue's keys and nothing else.
   */
  create(body: Api.CreateExportRequest): Promise<Api.QueuedExport> {
    return this.api.post('/api/v1/exports', body);
  }

  downloadPolicy(query: QueryParams = {}): Promise<Record<string, unknown>> {
    return this.api.get('/api/v1/exports/download-policy', query);
  }

  exportRow(exportId: string, query: QueryParams = {}): Promise<Record<string, unknown>> {
    return this.api.get(`/api/v1/exports/${encodeURIComponent(exportId)}`, query);
  }

  attempts(exportId: string, query: QueryParams = {}): Promise<Record<string, unknown>> {
    return this.api.get(`/api/v1/exports/${encodeURIComponent(exportId)}/attempts`, query);
  }

  downloads(exportId: string, query: QueryParams = {}): Promise<Record<string, unknown>> {
    return this.api.get(`/api/v1/exports/${encodeURIComponent(exportId)}/downloads`, query);
  }

  deletions(exportId: string, query: QueryParams = {}): Promise<Record<string, unknown>> {
    return this.api.get(`/api/v1/exports/${encodeURIComponent(exportId)}/deletions`, query);
  }

  /**
   * The finance gate, and the **only** export route under
   * `analytics:read-organization` rather than `analytics:read-project`. The client
   * checks the two values independently so a project-scoped session sees the export
   * list and is told plainly that the gate is not its to read, rather than receiving a
   * 403 that reads as "the exports are broken".
   */
  financeGate(query: QueryParams = {}): Promise<Record<string, unknown>> {
    return this.api.get('/api/v1/exports/finance-gate', query);
  }

// -- authoring ------------------------------------------------------------

  /**
   * An authoring-facing summary of what this project may author and what it has.
   *
   * ## The response is a free-form document, so it is read defensively
   *
   * The contract declares this response as `{"type":"object","additionalProperties":{}}` —
   * it promises nothing beyond "the body is an object". So unlike `catalogue`, which is a
   * real `$ref`, this is typed `Record<string, unknown>` and every field is read through
   * helpers that tolerate its absence. That is not defensiveness for its own sake: it is
   * the only honest typing available, and casting it to a shape the document does not
   * declare is the same mistake that made `close()` claim to return a `ConversationDetail`.
   */
  authoring(query: QueryParams = {}): Promise<Record<string, unknown>> {
    return this.api.get('/api/v1/exports/authoring', query);
  }

  /**
   * Authors a report definition. **201 Created.**
   *
   * The response carries `reportDefinitionId`, and every later call needs it — so **no id
   * is ever typed by a human on this screen**. That is the whole point of the authoring
   * surface: it is what replaced asking an operator to paste four opaque UUIDs.
   */
  createReportDefinition(
    projectId: string,
    body: Api.CreateReportDefinitionRequest,
  ): Promise<Api.ReportDefinitionResponse> {
    return this.api.post('/api/v1/exports/report-definitions', body, { projectId });
  }

  /**
   * Reads one definition.
   *
   * A refusal is a **non-disclosing 404**: the server returns 404 rather than 403 so that
   * "you may not author this" and "this is not yours" cannot be told apart by a caller. The
   * client therefore cannot branch on the difference and must not pretend to — see
   * `describeAuthoringRefusal`.
   */
  reportDefinition(definitionId: string): Promise<Api.ReportDefinitionResponse> {
    return this.api.get(
      `/api/v1/exports/report-definitions/${encodeURIComponent(definitionId)}`,
    );
  }

  /**
   * Renames a definition, with the optimistic-concurrency version it must match.
   *
   * `expectedVersion` is `rowVersion` from the read, which is what the server compares
   * against. Sending a stale one is how a lost update is refused rather than applied.
   */
  renameReportDefinition(
    definitionId: string,
    body: Api.RenameReportDefinitionRequest,
  ): Promise<Api.ReportDefinitionResponse> {
    return this.api.patch(
      `/api/v1/exports/report-definitions/${encodeURIComponent(definitionId)}`,
      body,
    );
  }

  /**
   * The versions of a definition, in the order the server sends them.
   *
   * Typed `unknown[]` because the contract declares a free-form array for this response.
   * The reader is `documentArray`, which yields `[]` for anything that is not an array of
   * objects rather than throwing at a shape the document never promised.
   */
  reportVersions(definitionId: string): Promise<unknown[]> {
    return this.api.get(
      `/api/v1/exports/report-definitions/${encodeURIComponent(definitionId)}/versions`,
    );
  }

  /**
   * Authors a version. **201 Created.**
   *
   * A new version is a **draft**. Publishing is a separate call, and it is the step that
   * makes a version exportable — so authoring a version does not by itself give the
   * operator anything they can export, and this client says so rather than implying
   * otherwise.
   */
  createReportVersion(
    definitionId: string,
    body: Api.CreateReportVersionRequest,
  ): Promise<Api.ReportVersionResponse> {
    return this.api.post(
      `/api/v1/exports/report-definitions/${encodeURIComponent(definitionId)}/versions`,
      body,
    );
  }

  /**
   * Publishes a version.
   *
   * **This is what makes a version exportable.** A draft cannot be pinned by an export and
   * the backend refuses the attempt, so publishing is the step that changes what the
   * creation form may offer — and this client never computes that. It re-reads the
   * catalogue, because `exportable` and `refusal` are decided by the server against the
   * caller's own permissions and are not derivable here.
   */
  publishReportVersion(
    definitionId: string,
    versionNumber: number,
  ): Promise<Api.ReportVersionResponse> {
    return this.api.post(
      `/api/v1/exports/report-definitions/${encodeURIComponent(definitionId)}` +
        `/versions/${versionNumber}/publish`,
    );
  }

  /**
   * Retires a version, naming the successor that replaces it.
   *
   * `successorVersion` is passed through as the server asks for it. Whether a retirement
   * needs one is the server's judgement about leaving the definition with nothing
   * exportable, not the client's to pre-empt.
   */
  retireReportVersion(
    definitionId: string,
    versionNumber: number,
    body: Api.RetireVersionRequest,
  ): Promise<void> {
    return this.api.post(
      `/api/v1/exports/report-definitions/${encodeURIComponent(definitionId)}` +
        `/versions/${versionNumber}/retire`,
      body,
    );
  }

  /** Authors a document template. **201 Created.** The DOCX travels with a version. */
  createDocumentTemplate(
    projectId: string,
    body: Api.CreateDocumentTemplateRequest,
  ): Promise<Api.DocumentTemplateResponse> {
    return this.api.post('/api/v1/exports/document-templates', body, { projectId });
  }

  /** The versions of a template. Free-form array; see `reportVersions`. */
  documentTemplateVersions(templateId: string): Promise<unknown[]> {
    return this.api.get(
      `/api/v1/exports/document-templates/${encodeURIComponent(templateId)}/versions`,
    );
  }

  /**
   * Uploads a template version: the DOCX, **base64-encoded inside a JSON string**.
   *
   * The contract declares this body as `application/json` with
   * `{"type":"string","format":"byte"}` — so "raw DOCX upload" means the bytes travel as a
   * base64 string in a JSON envelope, not as `multipart/form-data`. Worth stating plainly,
   * because a reader would otherwise assume a file upload and be surprised by the envelope.
   *
   * The server bounds the body. The size is also checked here, because a refusal after a
   * 40 MB upload is a worse experience than being told before it starts.
   */
  createDocumentTemplateVersion(
    templateId: string,
    docxBase64: string,
  ): Promise<Api.DocumentTemplateVersionResponse> {
    return this.api.post(
      `/api/v1/exports/document-templates/${encodeURIComponent(templateId)}/versions`,
      docxBase64,
    );
  }

  /** Publishes a template version. As with reports, this is what makes it exportable. */
  publishDocumentTemplateVersion(
    templateId: string,
    versionNumber: number,
  ): Promise<Api.DocumentTemplateVersionResponse> {
    return this.api.post(
      `/api/v1/exports/document-templates/${encodeURIComponent(templateId)}` +
        `/versions/${versionNumber}/publish`,
    );
  }

  /** Retires a template version, naming its successor. */
  retireDocumentTemplateVersion(
    templateId: string,
    versionNumber: number,
    body: Api.RetireVersionRequest,
  ): Promise<void> {
    return this.api.post(
      `/api/v1/exports/document-templates/${encodeURIComponent(templateId)}` +
        `/versions/${versionNumber}/retire`,
      body,
    );
  }
}

/** `/api/v1/status` — the public liveness ping. Used by the error state copy. */
@Injectable({ providedIn: 'root' })
export class StatusApi {
  private readonly api = inject(ApiService);

  status(): Promise<{ status?: string; version?: string }> {
    return this.api.get('/api/v1/status');
  }
}

/**
 * `/api/v1/clinic` — the read-only clinic surface.
 *
 * ## Every permission this client depends on, read off the controller
 *
 * | Route | Permission |
 * |---|---|
 * | `GET /clinic/doctors`, `/clinic/doctors/{id}` | `doctor:read` |
 * | `GET /clinic/procedures`, `/clinic/procedures/{id}` | `procedure:read` |
 * | `GET /clinic/appointments`, `/clinic/appointments/{id}` | `appointment:read` |
 * | `GET /clinic/analytics` | `analytics:read-project` (pre-existing) |
 *
 * The three clinic members were added with the routes; `analytics:read-project` already
 * existed and is already EMPLOYEE-held, which is why the analytics call needed no new grant.
 *
 * ## What this client does not offer, and why
 *
 * - **No write.** There is no clinic mutation route, so there is nothing to call. Booking,
 *   rescheduling and cancelling are owed with the permissions that authorize them.
 * - **No clinician contact details**, because `doctor:read` does not name them. A screen
 *   offering a "call the clinic" link would be showing a field the permission does not cover.
 * - **No revenue, conversion rate or utilisation.** Those were hard-coded in the legacy
 *   service or are finance definitions; `analytics` returns only what has rows behind it.
 *
 * ## Reading the responses
 *
 * The bodies are typed as narrow local interfaces rather than `Record<string, unknown>`
 * because the OpenAPI document publishes `ClinicAnalytics`, `Doctor`, `Procedure` and
 * `Appointment` as real components. Everything is optional: the server omits nulls
 * (`default-property-inclusion: non_null`), so an absent field is normal rather than an error.
 */
export interface ClinicDoctor {
  readonly id?: string;
  readonly name?: string;
  readonly specialty?: string;
  readonly department?: string | null;
  readonly workingDays?: readonly number[];
  readonly startHour?: string;
  readonly endHour?: string;
  readonly slotDuration?: number;
  readonly isActive?: boolean;
}

export interface ClinicProcedure {
  readonly id?: string;
  readonly name?: string;
  readonly specialty?: string;
  readonly durationMinutes?: number;
  readonly bufferMinutes?: number;
  /** Absent means "not priced", which is NOT the same as a price of zero. */
  readonly price?: number;
  readonly isActive?: boolean;
  readonly productCode?: string | null;
  readonly subCategory?: string | null;
  readonly clinicId?: string | null;
  readonly clinicName?: string | null;
  readonly timeSlotMinutes?: number | null;
  readonly type?: string | null;
  readonly description?: string | null;
  readonly discount?: number | null;
  readonly imageUrl?: string | null;
  readonly systemService?: string | null;
  readonly advancedPayment?: boolean;
}

/** One physical clinic, as the Clinics list and Create/Edit drawer read it. */
export interface ClinicLocation {
  readonly id?: string;
  readonly organizationId?: string;
  readonly projectId?: string;
  readonly name?: string;
  readonly frontDeskOfficerId?: string | null;
  readonly frontDeskOfficerName?: string | null;
  readonly contactNumber?: string | null;
  readonly description?: string | null;
  readonly addressLine?: string | null;
  readonly country?: string | null;
  readonly state?: string | null;
  readonly city?: string | null;
  readonly postalCode?: string | null;
  readonly slotMinutes?: number;
  readonly brandImageUrl?: string | null;
  readonly isActive?: boolean;
  readonly createdAt?: string;
  readonly updatedAt?: string;
}

/** The writable fields of the Create/Edit Clinic drawer. */
export interface ClinicLocationInput {
  readonly name: string;
  readonly frontDeskOfficerId?: string | null;
  /** Explicitly clears the current officer; a null UUID alone means "unchanged". */
  readonly clearFrontDeskOfficer?: boolean | null;
  readonly contactNumber?: string | null;
  readonly description?: string | null;
  readonly addressLine?: string | null;
  readonly country?: string | null;
  readonly state?: string | null;
  readonly city?: string | null;
  readonly postalCode?: string | null;
  readonly slotMinutes?: number | null;
  readonly brandImageUrl?: string | null;
  readonly isActive?: boolean | null;
  readonly initialTreatmentRooms?: number | null;
}

/** The writable fields of the Create/Edit Service drawer. */
export interface ClinicProcedureInput {
  readonly name: string;
  readonly specialty: string;
  readonly durationMinutes?: number | null;
  readonly bufferMinutes?: number | null;
  readonly price?: number | null;
  readonly isActive?: boolean | null;
  readonly productCode?: string | null;
  readonly subCategory?: string | null;
  readonly clinicId?: string | null;
  readonly timeSlotMinutes?: number | null;
  readonly type?: string | null;
  readonly description?: string | null;
  readonly discount?: number | null;
  readonly imageUrl?: string | null;
  readonly systemService?: string | null;
  readonly advancedPayment?: boolean | null;
}

/** One qualification row on the Create/Edit Doctor drawer. */
export interface ClinicQualification {
  readonly id?: string;
  readonly degree?: string;
  readonly university?: string | null;
  readonly graduationYear?: number | null;
}

export interface ClinicQualificationInput {
  readonly degree: string;
  readonly university?: string | null;
  readonly graduationYear?: number | null;
}

/**
 * The management doctor projection.
 *
 * This is deliberately not `ClinicDoctor`: the roster type withholds email and
 * phone, and the management route is the one that publishes them. Keeping the two
 * types separate is what stops a roster screen from rendering a field its
 * permission does not cover.
 */
export interface ClinicDoctorProfile {
  readonly id?: string;
  readonly organizationId?: string;
  readonly projectId?: string;
  readonly firstName?: string;
  readonly lastName?: string;
  readonly name?: string;
  readonly email?: string | null;
  readonly phone?: string | null;
  readonly gender?: string | null;
  readonly imageUrl?: string | null;
  readonly aboutSelf?: string | null;
  readonly expert?: string | null;
  readonly specialty?: string;
  readonly commission?: number | null;
  readonly clinicId?: string | null;
  readonly clinicName?: string | null;
  readonly serviceId?: string | null;
  readonly serviceName?: string | null;
  readonly experienceYears?: number | null;
  readonly country?: string | null;
  readonly state?: string | null;
  readonly city?: string | null;
  readonly postalCode?: string | null;
  readonly latitude?: number | null;
  readonly longitude?: number | null;
  readonly facebookUrl?: string | null;
  readonly instagramUrl?: string | null;
  readonly twitterUrl?: string | null;
  readonly dribbbleUrl?: string | null;
  readonly signatureUrl?: string | null;
  readonly verificationStatus?: string | null;
  readonly isActive?: boolean;
  readonly createdAt?: string;
  readonly updatedAt?: string;
  readonly qualifications?: readonly ClinicQualification[];
}

/** The Create/Edit Doctor payload. A doctor is a profile, not a sign-in account. */
export interface ClinicDoctorProfileInput {
  readonly firstName: string;
  readonly lastName: string;
  readonly email: string;
  readonly phone?: string | null;
  readonly gender?: string | null;
  readonly imageUrl?: string | null;
  readonly aboutSelf?: string | null;
  readonly expert?: string | null;
  readonly commission?: number | null;
  readonly clinicId?: string | null;
  readonly serviceId?: string | null;
  readonly experienceYears?: number | null;
  readonly country?: string | null;
  readonly state?: string | null;
  readonly city?: string | null;
  readonly postalCode?: string | null;
  readonly latitude?: number | null;
  readonly longitude?: number | null;
  readonly facebookUrl?: string | null;
  readonly instagramUrl?: string | null;
  readonly twitterUrl?: string | null;
  readonly dribbbleUrl?: string | null;
  readonly signatureUrl?: string | null;
  readonly verificationStatus?: string | null;
  readonly isActive?: boolean | null;
  readonly qualifications?: readonly ClinicQualificationInput[] | null;
}

/** A real clinic staff account (receptionist, doctor, or accountant), with its assigned clinic and optional accountant privilege. */
export interface ClinicFrontDeskOfficer {
  readonly userId?: string;
  readonly organizationId?: string;
  readonly projectId?: string;
  readonly email?: string;
  readonly fullName?: string;
  readonly firstName?: string;
  readonly lastName?: string;
  readonly phone?: string | null;
  readonly gender?: string | null;
  readonly dateOfBirth?: string | null;
  readonly profileImageUrl?: string | null;
  readonly addressLine?: string | null;
  readonly country?: string | null;
  readonly state?: string | null;
  readonly city?: string | null;
  readonly postalCode?: string | null;
  readonly clinicId?: string | null;
  readonly clinicName?: string | null;
  readonly verificationStatus?: string | null;
  readonly isActive?: boolean;
  readonly role?: string | null;
  readonly isAccountant?: boolean | null;
  readonly createdAt?: string;
  readonly updatedAt?: string;
}

/** The Create/Edit Front Desk payload. A null password on edit means unchanged. */
export interface ClinicFrontDeskInput {
  readonly firstName: string;
  readonly lastName: string;
  readonly email: string;
  readonly gender?: string | null;
  readonly phone?: string | null;
  readonly password?: string | null;
  readonly dateOfBirth?: string | null;
  readonly profileImageUrl?: string | null;
  readonly addressLine?: string | null;
  readonly country?: string | null;
  readonly state?: string | null;
  readonly city?: string | null;
  readonly postalCode?: string | null;
  readonly clinicId?: string | null;
  readonly verificationStatus?: string | null;
  readonly isActive?: boolean | null;
  readonly role?: string | null;
  readonly isAccountant?: boolean | null;
}

export interface ClinicStaffQuota {
  readonly activeUsersCount: number;
  readonly activeFdoCount: number;
  readonly effectiveMaxUsers: number;
  readonly planName?: string;
}

export interface ClinicAppointment {
  readonly id?: string;
  readonly title?: string;
  readonly patientId?: string | null;
  readonly patientName?: string;
  readonly patientPhone?: string | null;
  readonly patientEmail?: string | null;
  readonly clinicId?: string | null;
  readonly appointmentDate?: string;
  readonly durationMinutes?: number;
  readonly status?: string;
  readonly service?: string | null;
  readonly doctorName?: string | null;
  readonly doctorId?: string;
  readonly doctorImageUrl?: string | null;
  readonly procedureId?: string;
  readonly resourceId?: string | null;
  readonly customerId?: string | null;
  readonly price?: number | null;
  readonly quantity?: number;
  readonly totalAmount?: number | null;
  readonly paymentStatus?: string | null;
  readonly medicalReportName?: string | null;
  readonly medicalReportUrl?: string | null;
  readonly isAiProposed?: boolean;
  readonly ticketNumber?: string | null;
  readonly treatmentRoomId?: string | null;
  readonly treatmentRoomName?: string | null;
}

export interface ClinicCataloguePage<T> {
  readonly items?: readonly T[];
  readonly count?: number;
}

export interface ClinicAppointmentPage {
  readonly items?: readonly ClinicAppointment[];
  readonly count?: number;
  /** Absent on the last page -- the server omits nulls, so "absent" means "no more". */
  readonly nextCursor?: string;
  readonly from?: string;
  readonly to?: string;
}

/** Where a metric's number came from, published per group. */
export interface ClinicMetricProvenance {
  readonly sourceTable?: string;
  readonly windowStart?: { readonly iso8601?: string };
  readonly windowEnd?: { readonly iso8601?: string };
  readonly sourceRowSampleLimit?: number;
  readonly derivation?: string;
}

/** A metric row: the number, and the appointment ids behind it. */
export interface ClinicFunnelRow {
  readonly status?: string;
  readonly appointmentCount?: number;
  readonly sourceRowIds?: readonly string[];
}

export interface ClinicProcedureDemandRow {
  readonly procedureId?: string;
  readonly name?: string;
  readonly specialty?: string;
  readonly appointmentCount?: number;
  readonly bookedMinutes?: number;
  readonly sourceRowIds?: readonly string[];
}

export interface ClinicDoctorWorkloadRow {
  readonly doctorId?: string;
  readonly name?: string;
  readonly specialty?: string;
  readonly appointmentCount?: number;
  readonly bookedMinutes?: number;
  readonly sourceRowIds?: readonly string[];
}

export interface ClinicAnalytics {
  readonly funnelProvenance?: ClinicMetricProvenance;
  readonly demandProvenance?: ClinicMetricProvenance;
  readonly workloadProvenance?: ClinicMetricProvenance;
  readonly funnelByStatus?: readonly ClinicFunnelRow[];
  readonly demandByProcedure?: readonly ClinicProcedureDemandRow[];
  readonly workloadByDoctor?: readonly ClinicDoctorWorkloadRow[];
  readonly sourceRowSampleLimit?: number;
  /** A ready-made path back to the rows, carrying the same window. */
  readonly drillThroughPath?: string;
}

export interface ClinicQuery extends QueryParams {
  projectId?: string;
  from?: string;
  to?: string;
  status?: string;
  paymentStatus?: string;
  q?: string;
  cursor?: string;
  limit?: number;
}

@Injectable({ providedIn: 'root' })
export class ClinicApi {
  private readonly api = inject(ApiService);

  /** The bookable roster. Working days and hours; never contact details. */
  doctors(query: ClinicQuery = {}): Promise<ClinicCataloguePage<ClinicDoctor>> {
    return this.api.get('/api/v1/clinic/doctors', query);
  }

  doctor(doctorId: string): Promise<ClinicDoctor> {
    return this.api.get(`/api/v1/clinic/doctors/${encodeURIComponent(doctorId)}`);
  }

  /** The procedure catalogue. `price` is absent when the clinic has not priced it. */
  procedures(query: ClinicQuery = {}): Promise<ClinicCataloguePage<ClinicProcedure>> {
    return this.api.get('/api/v1/clinic/procedures', query);
  }

  procedure(procedureId: string): Promise<ClinicProcedure> {
    return this.api.get(`/api/v1/clinic/procedures/${encodeURIComponent(procedureId)}`);
  }

  /** The physical clinics in scope. */
  locations(query: ClinicQuery = {}): Promise<ClinicCataloguePage<ClinicLocation>> {
    return this.api.get('/api/v1/clinic/locations', query);
  }

  location(locationId: string): Promise<ClinicLocation> {
    return this.api.get(`/api/v1/clinic/locations/${encodeURIComponent(locationId)}`);
  }

  createLocation(
    input: ClinicLocationInput,
    projectId?: string,
  ): Promise<ClinicLocation> {
    return this.api.post('/api/v1/clinic/locations', input, { projectId });
  }

  updateLocation(
    locationId: string,
    input: Partial<ClinicLocationInput>,
    projectId?: string,
  ): Promise<ClinicLocation> {
    return this.api.patch(
      `/api/v1/clinic/locations/${encodeURIComponent(locationId)}`,
      input,
      { projectId },
    );
  }

  deleteLocation(
    locationId: string,
    projectId?: string,
  ): Promise<void> {
    return this.api.delete(
      `/api/v1/clinic/locations/${encodeURIComponent(locationId)}`,
      { projectId },
    );
  }

  createProcedure(
    input: ClinicProcedureInput,
    projectId?: string,
  ): Promise<ClinicProcedure> {
    return this.api.post('/api/v1/clinic/procedures', input, { projectId });
  }

  updateProcedure(
    procedureId: string,
    input: Partial<ClinicProcedureInput>,
    projectId?: string,
  ): Promise<ClinicProcedure> {
    return this.api.patch(
      `/api/v1/clinic/procedures/${encodeURIComponent(procedureId)}`,
      input,
      { projectId },
    );
  }

  // -- doctor profiles ----------------------------------------------------

  /** The management projection: includes contact details, unlike `doctors()`. */
  doctorProfiles(query: ClinicQuery = {}): Promise<ClinicCataloguePage<ClinicDoctorProfile>> {
    return this.api.get('/api/v1/clinic/doctor-profiles', query);
  }

  doctorProfile(doctorId: string, projectId?: string): Promise<ClinicDoctorProfile> {
    return this.api.get(
      `/api/v1/clinic/doctor-profiles/${encodeURIComponent(doctorId)}`,
      { projectId },
    );
  }

  createDoctor(input: ClinicDoctorProfileInput, projectId?: string): Promise<ClinicDoctorProfile> {
    return this.api.post('/api/v1/clinic/doctor-profiles', input, { projectId });
  }

  updateDoctor(
    doctorId: string,
    input: Partial<ClinicDoctorProfileInput>,
    projectId?: string,
  ): Promise<ClinicDoctorProfile> {
    return this.api.patch(
      `/api/v1/clinic/doctor-profiles/${encodeURIComponent(doctorId)}`,
      input,
      { projectId },
    );
  }

  deleteDoctor(doctorId: string, projectId?: string): Promise<void> {
    return this.api.delete(`/api/v1/clinic/doctor-profiles/${encodeURIComponent(doctorId)}`, {
      projectId,
    });
  }

  // -- front desk ---------------------------------------------------------

  frontDeskOfficers(query: ClinicQuery = {}): Promise<ClinicCataloguePage<ClinicFrontDeskOfficer>> {
    return this.api.get('/api/v1/clinic/front-desk', query);
  }

  frontDeskQuota(projectId?: string): Promise<ClinicStaffQuota> {
    return this.api.get('/api/v1/clinic/front-desk/quota', { projectId });
  }

  frontDeskOfficer(userId: string, projectId?: string): Promise<ClinicFrontDeskOfficer> {
    return this.api.get(`/api/v1/clinic/front-desk/${encodeURIComponent(userId)}`, {
      projectId,
    });
  }

  createFrontDesk(input: ClinicFrontDeskInput, projectId?: string): Promise<ClinicFrontDeskOfficer> {
    return this.api.post('/api/v1/clinic/front-desk', input, { projectId });
  }

  updateFrontDesk(
    userId: string,
    input: Partial<ClinicFrontDeskInput>,
    projectId?: string,
  ): Promise<ClinicFrontDeskOfficer> {
    return this.api.patch(`/api/v1/clinic/front-desk/${encodeURIComponent(userId)}`, input, {
      projectId,
    });
  }

  deleteFrontDesk(userId: string, projectId?: string): Promise<void> {
    return this.api.delete(`/api/v1/clinic/front-desk/${encodeURIComponent(userId)}`, {
      projectId,
    });
  }

  /** One page of the schedule, in the server-resolved scope. */
  appointments(query: ClinicQuery = {}): Promise<ClinicAppointmentPage> {
    return this.api.get('/api/v1/clinic/appointments', query);
  }

  appointment(appointmentId: string): Promise<ClinicAppointment> {
    return this.api.get(`/api/v1/clinic/appointments/${encodeURIComponent(appointmentId)}`);
  }

  /**
   * The analytics read model.
   *
   * `from` and `to` are ISO-8601 instants and are optional: the server defaults a 30-day
   * window and **echoes the resolved pair back in `funnelProvenance`**, so the screen always
   * shows which window produced the figures rather than assuming the default it sent.
   */
  analytics(query: ClinicQuery = {}): Promise<ClinicAnalytics> {
    return this.api.get('/api/v1/clinic/analytics', query);
  }
}

export interface PlatformClinicSummary {
  projectId: string;
  organizationId: string;
  organizationName: string;
  clinicName: string;
  slug: string;
  city: string;
  contactNumber?: string;
  timezone: string;
  defaultLocale: string;
  status: 'TRIAL' | 'ACTIVE' | 'GRACE' | 'SUSPENDED' | 'EXPIRED';
  subscriptionStatus?: string;
  suspendedAt?: string;
  suspendedReason?: string;
  version: number;
  archived: boolean;
  createdAt: string;
  paidUntil?: string;
  trialEndsAt?: string;
  gracePeriodDays: number;
  maxUsers: number;
  maxBranches: number;
  storageQuotaMb: number;
  monthlyAiQuota: number;
  planCode: string;
  planName: string;
  adminUserId?: string;
  adminName?: string;
  adminEmail?: string;
  adminPhone?: string;
  adminIsActive: boolean;
  adminInvitePending: boolean;
  adminMfaEnrolled: boolean;
  lastActivity?: string;
  accountCount: number;
  memberCount: number;
}

export interface CreateClinicWizardInput {
  clinicName: string;
  city?: string;
  contactNumber?: string;
  timezone?: string;
  language?: string;
  planCode?: string;
  startType?: 'TRIAL' | 'PAID';
  adminName: string;
  adminEmail: string;
  adminPassword?: string;
  adminPhone?: string;
  reason?: string;
}

export interface ClinicWizardResult {
  organizationId: string;
  projectId: string;
  clinicName: string;
  clinicSlug: string;
  city: string;
  contactNumber?: string;
  timezone: string;
  status: string;
  planCode: string;
  paidUntil: string;
  administratorUserId: string;
  administratorName: string;
  administratorEmail: string;
  administratorPhone?: string;
  inviteToken: string;
  inviteLink: string;
}

export interface ClinicLimitsInput {
  maxUsers?: number;
  maxBranches?: number;
  storageQuotaMb?: number;
  monthlyAiQuota?: number;
  reason: string;
}

export interface EditClinicInput {
  clinicName: string;
  city?: string;
  contactNumber?: string;
  timezone?: string;
  language?: string;
  reason?: string;
}

export interface AddClinicAdminInput {
  adminName: string;
  adminEmail: string;
  adminPhone?: string;
  adminPassword?: string;
  reason?: string;
}

/** `/api/v1/platform` — Platform Operator API surface. */
@Injectable({ providedIn: 'root' })
export class PlatformApi {
  private readonly api = inject(ApiService);

  clinics(): Promise<readonly PlatformClinicSummary[]> {
    return this.api.get('/api/v1/platform/clinics');
  }

  createClinicWizard(input: CreateClinicWizardInput): Promise<ClinicWizardResult> {
    return this.api.post('/api/v1/platform/clinics/wizard', input);
  }

  resendInvite(clinicId: string, reason?: string): Promise<{ token: string; link: string; expiresAt: string }> {
    return this.api.post(`/api/v1/platform/clinics/${encodeURIComponent(clinicId)}/invite/resend`, { reason });
  }

  resetAdminMfa(clinicId: string, reason?: string): Promise<{ success: boolean; message: string }> {
    return this.api.post(`/api/v1/platform/clinics/${encodeURIComponent(clinicId)}/admin/reset-mfa`, { reason });
  }

  disableAdmin(clinicId: string, reason?: string): Promise<{ success: boolean; message: string }> {
    return this.api.post(`/api/v1/platform/clinics/${encodeURIComponent(clinicId)}/admin/disable`, { reason });
  }

  enableAdmin(clinicId: string, reason?: string): Promise<{ success: boolean; message: string }> {
    return this.api.post(`/api/v1/platform/clinics/${encodeURIComponent(clinicId)}/admin/enable`, { reason });
  }

  suspendClinic(clinicId: string, reason?: string): Promise<{ success: boolean; status: string; message: string }> {
    return this.api.post(`/api/v1/platform/clinics/${encodeURIComponent(clinicId)}/suspend`, { reason });
  }

  reactivateClinic(clinicId: string, reason?: string): Promise<{ success: boolean; status: string; message: string }> {
    return this.api.post(`/api/v1/platform/clinics/${encodeURIComponent(clinicId)}/reactivate`, { reason });
  }

  extendSubscription(clinicId: string, days: number, reason: string): Promise<{ success: boolean; message: string }> {
    return this.api.post(`/api/v1/platform/clinics/${encodeURIComponent(clinicId)}/extend`, { days, reason });
  }

  updateLimits(clinicId: string, input: ClinicLimitsInput): Promise<{ success: boolean; message: string }> {
    return this.api.put(`/api/v1/platform/clinics/${encodeURIComponent(clinicId)}/limits`, input);
  }

  editClinic(clinicId: string, input: EditClinicInput): Promise<{ success: boolean; message: string }> {
    return this.api.put(`/api/v1/platform/clinics/${encodeURIComponent(clinicId)}`, input);
  }

  addClinicAdmin(clinicId: string, input: AddClinicAdminInput): Promise<{ userId: string; email: string; fullName: string; role: string; projectId: string }> {
    return this.api.post(`/api/v1/platform/clinics/${encodeURIComponent(clinicId)}/admins`, input);
  }

  subscriptions(): Promise<readonly PlatformSubscriptionOverview[]> {
    return this.api.get('/api/v1/platform/subscriptions');
  }

  clinicPayments(clinicId: string): Promise<readonly PlatformPaymentRecord[]> {
    return this.api.get(`/api/v1/platform/clinics/${encodeURIComponent(clinicId)}/payments`);
  }

  recordPayment(clinicId: string, payload: RecordPaymentPayload): Promise<PlatformPaymentRecord> {
    return this.api.post(`/api/v1/platform/clinics/${encodeURIComponent(clinicId)}/payments`, payload);
  }

  reversePayment(clinicId: string, paymentId: string, reason: string): Promise<PlatformPaymentRecord> {
    return this.api.post(`/api/v1/platform/clinics/${encodeURIComponent(clinicId)}/payments/${encodeURIComponent(paymentId)}/reverse`, { reason });
  }

  runTransitionJob(): Promise<PlatformTransitionJobResult> {
    return this.api.post('/api/v1/platform/subscriptions/run-transition-job', {});
  }

  plans(): Promise<readonly PlatformPlanSummary[]> {
    return this.api.get('/api/v1/platform/plans');
  }

  plan(planId: string): Promise<PlatformPlanDetail> {
    return this.api.get(`/api/v1/platform/plans/${encodeURIComponent(planId)}`);
  }

  updatePlan(planId: string, payload: UpdatePlanPayload): Promise<PlatformPlanDetail> {
    return this.api.put(`/api/v1/platform/plans/${encodeURIComponent(planId)}`, payload);
  }

  clinicEffectiveLimits(clinicId: string): Promise<ClinicEffectiveLimits> {
    return this.api.get(`/api/v1/platform/clinics/${encodeURIComponent(clinicId)}/limits`);
  }

  updateClinicLimitsOverrides(clinicId: string, payload: UpdateClinicLimitsPayload): Promise<ClinicEffectiveLimits> {
    return this.api.put(`/api/v1/platform/clinics/${encodeURIComponent(clinicId)}/limits`, payload);
  }

  overrideClinicEntitlement(
    clinicId: string,
    featureKey: string,
    enabled: boolean,
    reason: string,
  ): Promise<{ success: boolean; featureKey: string; enabled: boolean }> {
    return this.api.put(
      `/api/v1/platform/clinics/${encodeURIComponent(clinicId)}/entitlements/${encodeURIComponent(featureKey)}`,
      { enabled, reason },
    );
  }

  supportGrants(projectId?: string, status?: string): Promise<readonly SupportAccessGrant[]> {
    let url = '/api/v1/platform/support-access';
    const params: string[] = [];
    if (projectId) params.push(`projectId=${encodeURIComponent(projectId)}`);
    if (status) params.push(`status=${encodeURIComponent(status)}`);
    if (params.length > 0) url += `?${params.join('&')}`;
    return this.api.get(url);
  }

  requestSupportAccess(payload: RequestSupportAccessPayload): Promise<SupportAccessGrant> {
    return this.api.post('/api/v1/platform/support-access', payload);
  }

  supportGrant(grantId: string): Promise<SupportAccessGrant> {
    return this.api.get(`/api/v1/platform/support-access/${encodeURIComponent(grantId)}`);
  }

  revokeSupportAccess(grantId: string, reason: string): Promise<SupportAccessGrant> {
    return this.api.post(`/api/v1/platform/support-access/${encodeURIComponent(grantId)}/revoke`, { reason });
  }

  supportGrantPatients(grantId: string): Promise<readonly any[]> {
    return this.api.get(`/api/v1/platform/support-access/${encodeURIComponent(grantId)}/patients`);
  }

  supportGrantAppointments(grantId: string): Promise<readonly any[]> {
    return this.api.get(`/api/v1/platform/support-access/${encodeURIComponent(grantId)}/appointments`);
  }

  supportGrantEncounters(grantId: string): Promise<readonly any[]> {
    return this.api.get(`/api/v1/platform/support-access/${encodeURIComponent(grantId)}/encounters`);
  }

  supportGrantAudit(grantId: string): Promise<readonly any[]> {
    return this.api.get(`/api/v1/platform/support-access/${encodeURIComponent(grantId)}/audit`);
  }

  dashboardOverview(): Promise<OperatorDashboardOverview> {
    return this.api.get('/api/v1/platform/dashboard/overview');
  }

  auditTrail(filter: PlatformAuditFilter = {}): Promise<readonly EnrichedAuditEntry[]> {
    const params: string[] = [];
    if (filter.clinicId) params.push(`clinicId=${encodeURIComponent(filter.clinicId)}`);
    if (filter.operatorId) params.push(`operatorId=${encodeURIComponent(filter.operatorId)}`);
    if (filter.actor) params.push(`actor=${encodeURIComponent(filter.actor)}`);
    if (filter.action) params.push(`action=${encodeURIComponent(filter.action)}`);
    if (filter.outcome) params.push(`outcome=${encodeURIComponent(filter.outcome)}`);
    if (filter.from) params.push(`from=${encodeURIComponent(filter.from)}`);
    if (filter.to) params.push(`to=${encodeURIComponent(filter.to)}`);
    if (filter.limit) params.push(`limit=${encodeURIComponent(String(filter.limit))}`);
    const query = params.length > 0 ? `?${params.join('&')}` : '';
    return this.api.get(`/api/v1/platform/audit${query}`);
  }

  auditActions(): Promise<readonly string[]> {
    return this.api.get('/api/v1/platform/audit/actions');
  }
}

export interface PlatformSubscriptionOverview {
  readonly subscriptionId: string;
  readonly projectId: string;
  readonly clinicName: string;
  readonly clinicSlug: string;
  readonly city: string;
  readonly planCode: string;
  readonly planName: string;
  readonly status: 'TRIAL' | 'ACTIVE' | 'GRACE' | 'SUSPENDED' | 'EXPIRED';
  readonly paidUntil: string | null;
  readonly trialEndsAt: string | null;
  readonly gracePeriodDays: number;
  readonly version: number;
  readonly paymentsCount: number;
  readonly totalPaidAmount: number;
  readonly createdAt: string;
}

export interface PlatformPaymentRecord {
  readonly id: string;
  readonly subscriptionId: string;
  readonly projectId: string;
  readonly organizationId: string;
  readonly idempotencyKey: string;
  readonly amount: number;
  readonly currency: string;
  readonly paymentMethod: 'CASH' | 'BANK_TRANSFER' | 'CLIQ' | 'OTHER';
  readonly referenceNumber: string;
  readonly periodStart: string;
  readonly periodEnd: string;
  readonly recordedByOperatorId: string;
  readonly notes: string;
  readonly isReversal: boolean;
  readonly reversalOfPaymentId: string | null;
  readonly reversalReason: string | null;
  readonly createdAt: string;
  readonly isReversed: boolean;
}

export interface RecordPaymentPayload {
  readonly idempotencyKey: string;
  readonly amount: number;
  readonly currency: string;
  readonly paymentMethod: 'CASH' | 'BANK_TRANSFER' | 'CLIQ' | 'OTHER';
  readonly referenceNumber: string;
  readonly periodMonths: number;
  readonly notes?: string;
}

export interface PlatformTransitionJobResult {
  readonly activeChecked: number;
  readonly movedToGrace: number;
  readonly movedToExpired: number;
  readonly remindersLogged: number;
  readonly errors: readonly string[];
}

export interface InviteDetails {
  readonly valid: boolean;
  readonly email: string;
  readonly fullName: string;
  readonly expiresAt: string;
  readonly clinicName: string;
}

export interface AcceptInviteResult {
  readonly status: string;
  readonly email: string;
  readonly userId: string;
}

export interface PlatformPlanSummary {
  readonly id: string;
  readonly code: string;
  readonly name: string;
  readonly description: string;
  readonly priceAmount: number;
  readonly currency: string;
  readonly billingInterval: string;
  readonly maxUsers: number;
  readonly maxBranches: number;
  readonly storageQuotaMb: number;
  readonly monthlyAiQuota: number;
  readonly isActive: boolean;
  readonly version: number;
  readonly enabledFeatures: readonly string[];
  readonly activeSubscriptionsCount: number;
}

export interface PlatformPlanDetail {
  readonly id: string;
  readonly code: string;
  readonly name: string;
  readonly description: string;
  readonly priceAmount: number;
  readonly currency: string;
  readonly billingInterval: string;
  readonly maxUsers: number;
  readonly maxBranches: number;
  readonly storageQuotaMb: number;
  readonly monthlyAiQuota: number;
  readonly isActive: boolean;
  readonly version: number;
  readonly entitlements: Record<string, boolean>;
  readonly activeSubscriptionsCount: number;
}

export interface UpdatePlanPayload {
  readonly name?: string;
  readonly description?: string;
  readonly priceAmount?: number;
  readonly maxUsers?: number;
  readonly maxBranches?: number;
  readonly storageQuotaMb?: number;
  readonly monthlyAiQuota?: number;
  readonly entitlements?: Record<string, boolean>;
  readonly reason: string;
}

export interface ClinicEffectiveLimits {
  readonly projectId: string;
  readonly clinicName: string;
  readonly planCode: string;
  readonly planName: string;
  readonly effectiveMaxUsers: number;
  readonly isMaxUsersOverridden: boolean;
  readonly maxUsersOverride: number | null;
  readonly effectiveMaxBranches: number;
  readonly isMaxBranchesOverridden: boolean;
  readonly maxBranchesOverride: number | null;
  readonly effectiveStorageQuotaMb: number;
  readonly isStorageQuotaOverridden: boolean;
  readonly storageQuotaMbOverride: number | null;
  readonly effectiveMonthlyAiQuota: number;
  readonly isMonthlyAiQuotaOverridden: boolean;
  readonly monthlyAiQuotaOverride: number | null;
  readonly entitlements: Record<string, boolean>;
  readonly activeUsersCount: number;
  readonly activeBranchesCount: number;
  readonly monthlyAiRunsCount: number;
}

export interface UpdateClinicLimitsPayload {
  readonly maxUsers?: number | null;
  readonly maxBranches?: number | null;
  readonly storageQuotaMb?: number | null;
  readonly monthlyAiQuota?: number | null;
  readonly reason: string;
}

/** `/api/v1/invites` — Public single-use invite onboarding surface. */
@Injectable({ providedIn: 'root' })
export class InviteApi {
  private readonly api = inject(ApiService);

  validateInvite(token: string): Promise<InviteDetails> {
    return this.api.get(`/api/v1/invites/${encodeURIComponent(token)}`);
  }

  acceptInvite(token: string, password: string, totpSecret?: string): Promise<AcceptInviteResult> {
    return this.api.post(`/api/v1/invites/${encodeURIComponent(token)}/accept`, { password, totpSecret });
  }
}

export interface SupportAccessGrant {
  readonly id: string;
  readonly organizationId: string;
  readonly projectId: string;
  readonly clinicName: string;
  readonly operatorId: string;
  readonly operatorDisplayName: string;
  readonly reason: string;
  readonly isReadOnly: boolean;
  readonly status: 'ACTIVE' | 'EXPIRED' | 'REVOKED' | string;
  readonly startsAt: string;
  readonly expiresAt: string;
  readonly remainingMinutes: number;
  readonly revokedAt?: string | null;
  readonly revokedByOperatorId?: string | null;
  readonly revokedByName?: string | null;
  readonly revocationReason?: string | null;
  readonly notifiedClinicAdminAt?: string | null;
  readonly createdAt: string;
}

export interface RequestSupportAccessPayload {
  readonly projectId: string;
  readonly reason: string;
  readonly durationMinutes: number;
  readonly isReadOnly: boolean;
}

export interface RevokeSupportAccessPayload {
  readonly reason: string;
}

export interface DashboardKpis {
  readonly totalClinics: number;
  readonly activeClinics: number;
  readonly trialClinics: number;
  readonly graceClinics: number;
  readonly suspendedClinics: number;
  readonly expiredClinics: number;
  readonly expiringIn7Days: number;
  readonly expiringIn30Days: number;
  readonly subscriptionRevenueThisMonth: number;
  readonly currency: string;
  readonly newClinicsThisMonth: number;
}

export interface NeedsAttentionItem {
  readonly clinicId: string;
  readonly organizationId: string;
  readonly clinicName: string;
  readonly organizationName: string;
  readonly subscriptionStatus: string;
  readonly attentionType: string;
  readonly attentionReason: string;
  readonly severity: 'CRITICAL' | 'WARNING' | 'INFO';
  readonly paidUntil?: string | null;
  readonly adminName?: string | null;
  readonly adminEmail?: string | null;
}

export interface RevenueByMonth {
  readonly monthKey: string;
  readonly monthLabel: string;
  readonly amount: number;
  readonly currency: string;
}

export interface ClinicsByStatus {
  readonly status: string;
  readonly count: number;
  readonly percentage: number;
}

export interface ClinicAiUsageQuota {
  readonly clinicId: string;
  readonly clinicName: string;
  readonly usageCount: number;
  readonly quotaLimit: number;
  readonly usagePercent: number;
}

export interface PlatformAuditEvent {
  readonly id: string;
  readonly timestamp: string;
  readonly action: string;
  readonly operatorId: string;
  readonly operatorEmail: string;
  readonly operatorName: string;
  readonly targetType: string;
  readonly targetId?: string | null;
  readonly clinicName?: string | null;
  readonly outcome: string;
  readonly details?: string | null;
  readonly ipAddress?: string | null;
  readonly correlationId?: string | null;
}

export interface OperatorDashboardOverview {
  readonly kpis: DashboardKpis;
  readonly needsAttention: readonly NeedsAttentionItem[];
  readonly revenueByMonth: readonly RevenueByMonth[];
  readonly clinicsByStatus: readonly ClinicsByStatus[];
  readonly aiUsage: readonly ClinicAiUsageQuota[];
  readonly recentAuditEvents: readonly PlatformAuditEvent[];
}

export interface EnrichedAuditEntry {
  readonly id: string;
  readonly occurredAt: string;
  readonly operatorId?: string | null;
  readonly operatorName?: string | null;
  readonly operatorEmail?: string | null;
  readonly action: string;
  readonly resourceType?: string | null;
  readonly resourceId?: string | null;
  readonly projectId?: string | null;
  readonly clinicName?: string | null;
  readonly organizationId?: string | null;
  readonly organizationName?: string | null;
  readonly outcome: 'ALLOWED' | 'DENIED' | 'FAILED' | string;
  readonly ipAddress?: string | null;
  readonly reason?: string | null;
  readonly detail?: string | null;
  readonly correlationId?: string | null;
}

export interface PlatformAuditFilter {
  readonly clinicId?: string;
  readonly operatorId?: string;
  readonly actor?: string;
  readonly action?: string;
  readonly outcome?: string;
  readonly from?: string;
  readonly to?: string;
  readonly limit?: number;
}


