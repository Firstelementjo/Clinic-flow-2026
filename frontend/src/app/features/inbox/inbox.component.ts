/**
 * The inbox: a conversation list, a thread, and the customer behind it.
 *
 * ## Which routes this screen calls
 *
 * | Pane | Route | Purpose |
 * |---|---|---|
 * | list | `GET /api/v1/conversations` | the conversation page, cursor-paginated |
 * | list | `GET /api/v1/inbox/counts` | the saved-view counts on the filter segments |
 * | list | `GET /api/v1/inbox/unassigned` | how many are waiting with no owner |
 * | thread | `GET /api/v1/conversations/{id}` | the thread, its messages and its events |
 * | thread | `POST /api/v1/conversations/{id}/messages` | the reply |
 * | thread | `PATCH /api/v1/conversations/{id}/mode` | AI ⇄ human |
 * | thread | `PATCH /api/v1/conversations/{id}` | status and priority |
 * | thread | `POST /api/v1/conversations/{id}/close`, `/reopen` | the case lifecycle |
 * | thread | `PUT /api/v1/assignments/{id}` | take the conversation |
 * | thread | `GET`/`PUT .../resolution` | the case resolution |
 * | thread | `GET`/`PUT .../classification` | classification and its suggestion |
 * | thread | `GET .../transcript` | the customer-visible history |
 * | pane 3 | `GET /api/v1/customers/{id}` | the customer behind the conversation |
 *
 * ## The pending AI draft
 *
 * `fe-ai-draft-panel` sits in the thread pane, after the message list and outside
 * it. It reads `GET /api/v1/ai/drafts/conversations/{id}` and offers the text a
 * human may review; the send is this component's own `reply`, so there is one
 * send path in the product and the message is attributable to the person who
 * pressed the button. See `ai-draft.component.ts`.
 *
 * Every permission-gated card is omitted when the caller lacks the permission, and
 * the omission is stated — a `403` on a panel the server never offered is noise.
 *
 * ## Collision handling, which is the interesting part
 *
 * Three separate things can go wrong when two operators are in one conversation,
 * and each has its own evidence:
 *
 * 1. **The reply was written against a stale thread.** The reply carries
 *    `X-Expected-Message-Count` — the count the author was looking at. The server
 *    compares and refuses. The UI keeps the composed text, says a message arrived
 *    first, and offers a refresh. Losing what someone typed to a concurrent edit is
 *    the worst outcome available here, so it is designed against.
 * 2. **The mode was changed underneath us.** `PATCH .../mode` takes a
 *    `modeVersion`, and the server refuses a stale one. Taking over a conversation
 *    someone else has already taken is exactly the race that matters, so it is not
 *    resolved by whoever clicked last.
 * 3. **The status changed underneath us.** Every other mutation carries `If-Match`
 *    from the version the read model published.
 *
 * In all three the UI reports the conflict, keeps what the user typed, and does not
 * retry automatically — an automatic retry of a write that was refused would either
 * lose the edit or duplicate the action.
 */
import {
  ChangeDetectionStrategy,
  Component,
  OnDestroy,
  OnInit,
  computed,
  effect,
  inject,
  signal,
  untracked,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { map } from 'rxjs';
import { ApiError } from '../../core/api/api.service';
import {
  ConversationApi,
  CustomerApi,
  InboxApi,
  type ConversationListQuery,
} from '../../core/api/endpoints';
import { versionOf } from '../../core/api/endpoints';
import type * as Api from '../../core/api/generated';
import { SessionService } from '../../core/auth/session.service';
import { PERMISSIONS } from '../../core/auth/permissions';
import { notBlank } from '../../core/validation/not-blank.validator';
import { FormatService } from '../../core/format/format.service';
import { I18nService } from '../../core/i18n/i18n.service';
import type { Namespace } from '../../core/i18n/i18n.types';
import { ProjectContextService } from '../../core/context/project-context.service';
import { RealtimeService } from '../../core/realtime/realtime.service';
import { ResourceStore } from '../../core/state/resource';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { AsyncStateComponent } from '../../ui/async-state.component';
import { NotAvailableComponent } from '../../ui/not-available.component';
import { PageHeaderComponent } from '../../ui/page-header.component';
import { StatusChipComponent } from '../../ui/status-chip.component';
import { AiDraftPanelComponent } from './ai-draft.component';
import { inboxStrings } from './inbox.strings';

/** The three panes, which collapse to one column at narrow widths. */
type Pane = 'list' | 'thread' | 'customer';

/**
 * The three values `CaseOutcome.valueOf` can parse.
 *
 * Exported, and the reason they are a module-level constant rather than inline strings
 * in the template: this set **is** the contract. `SaveResolutionRequest.outcome` is
 * declared `type: string` with no enum, so the document says a case outcome is any
 * string — and the server throws on all but these three. A unit test asserts this list
 * has exactly these three members, so a change here has to be deliberate.
 */
export const OUTCOMES = ['RESOLVED', 'ESCALATED', 'UNRESOLVED'] as const;

/**
 * The five `filter` values `GET /api/v1/conversations` accepts, in the contract's order.
 *
 * The contract declares them as an enum (ADR 0015), so this list is checkable rather
 * than assumed: `api-contract.spec.ts` asserts it equals the document's enum, in the same
 * order. The declaration is duplicated here rather than generated because the generated
 * client types the *parameter* and not the value list, and a client that cannot name
 * the values cannot offer them.
 */
export const INBOX_FILTERS = [
  'unassigned',
  'all-open',
  'assigned-to-others',
  'assigned-to-me',
  'history',
] as const;

export type InboxFilter = (typeof INBOX_FILTERS)[number];

/**
 * The view the inbox opens on.
 *
 * `unassigned` — the work queue — because that is what an operator opening the inbox
 * needs first: conversations nobody owns. It is also the server's own default now, so
 * the control and the API agree without either depending on the other.
 */
export const DEFAULT_INBOX_FILTER: InboxFilter = 'unassigned';

/** One saved view as the control renders it: the wire value and its published count. */
interface SavedView {
  readonly filter: InboxFilter;
  /**
   * The count, already formatted for the locale.
   *
   * A `string` rather than `number` because the document declares every one of the five
   * as **required**, so there is no absent case to represent — and a nullable count here
   * would invite rendering "no count" as a zero, which is the confusion FR-7.6 exists to
   * prevent.
   */
  readonly count: string;
}

/**
 * The length bounds `CaseService.validateResolution` enforces on `customerIssue` and
 * `agentAction` — "must be 2..2000 characters" for both.
 *
 * Mirrored on the form so a field the server will refuse is caught before the write, and
 * so the error lands on the field that caused it rather than on a banner.
 */
export const OUTCOME_MIN_TEXT = 2;
export const OUTCOME_MAX_TEXT = 2000;

@Component({
  selector: 'fe-inbox-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    AiDraftPanelComponent,
    AsyncStateComponent,
    NotAvailableComponent,
    PageHeaderComponent,
    ReactiveFormsModule,
    RouterLink,
    StatusChipComponent,
    TranslatePipe,
  ],
  templateUrl: './inbox.component.html',
  styleUrl: './inbox.component.css',
})
export class InboxComponent implements OnInit, OnDestroy {
  private readonly conversations = inject(ConversationApi);
  private readonly customers = inject(CustomerApi);
  private readonly inbox = inject(InboxApi);
  private readonly formBuilder = inject(FormBuilder);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  protected readonly format = inject(FormatService);
  protected readonly i18n = inject(I18nService);
  protected readonly sessions = inject(SessionService);
  protected readonly projects = inject(ProjectContextService);
  protected readonly realtime = inject(RealtimeService);

  protected readonly list = new ResourceStore<Api.CursorPageConversationSummary>();
  protected readonly counts = new ResourceStore<Record<string, number>>();
  protected readonly unassigned = new ResourceStore<Api.UnassignedQueuePage>();
  protected readonly thread = new ResourceStore<Api.ConversationDetail>();
  protected readonly customer = new ResourceStore<Api.CustomerDetail>();
  protected readonly resolution = new ResourceStore<Api.ResolutionView | null>();
  protected readonly classification = new ResourceStore<Api.ClassificationView | null>();

  protected readonly search = signal('');

  /**
   * Which saved view the list pane is showing.
   *
   * ## The wire values are the segment identity
   *
   * `GET /api/v1/conversations?filter=` is an **enum** in the committed contract with
   * exactly five values (ADR 0015), and an unknown one is refused. The segment is
   * therefore the wire value itself rather than a local alias mapped onto it. The
   * previous two-segment control carried `'mine'` and mapped it to `assigned-to-me`,
   * which is exactly the indirection that let a stale name become a 500: a key built by
   * translation is a key that can silently miss.
   *
   * ## The default changed, and it is the point of the change
   *
   * An absent `filter` now means **`unassigned`** — the work queue. This client sends
   * the view explicitly on every request, so it does not depend on that default; but it
   * also **defaults the control to `unassigned`**, because an operator opening the inbox
   * should see the conversations nobody owns, not an empty list filtered to their own
   * involvement.
   *
   * ## The five views overlap, and must not be summed
   *
   * `assigned-to-me` includes threads the caller *released* as well as ones they are
   * assigned; `all-open` includes every one of `unassigned`, `assigned-to-me` and
   * `assigned-to-others`. They are lenses over one set, not disjoint buckets, so a
   * "total" computed from them would double-count. Each count is rendered against its
   * own tab and nothing is added up — see `segmentCount`.
   */
  protected readonly segment = signal<InboxFilter>(DEFAULT_INBOX_FILTER);
  protected readonly selectedId = signal<string | null>(null);
  protected readonly pane = signal<Pane>('list');
  protected readonly narrow = signal(false);
  protected readonly nextCursor = signal<string | null>(null);

  /** A write that the server refused as a collision. */
  protected readonly collision = signal<string | null>(null);
  /** A write that succeeded, so the operator sees the effect without hunting. */
  protected readonly notice = signal<string | null>(null);
  protected readonly sending = signal(false);
  protected readonly savingResolution = signal(false);

  /**
   * Whether the resolution disclosure is open.
   *
   * A signal rather than the template's `[open]="false"`, because closing a case has to
   * be able to *open* it: the server refuses to close until a resolution is recorded, and
   * the operator should land on the form rather than on an error.
   */
  protected readonly openResolution = signal(false);

  /**
   * The draft is held in a signal, not only in the form control, because it has to
   * survive a failed send. A form the send re-creates is a form that loses what
   * someone typed the moment the network hiccups.
   */
  protected readonly draft = signal('');

  protected readonly replyForm = this.formBuilder.nonNullable.group({
    // `required` accepts `"   "`, so whitespace-only would pass the form and then be
    // caught by the hand-written `trim()` in `send()` — where it returns silently and the
    // operator sees nothing. One validator, one message.
    body: ['', [Validators.required, notBlank, Validators.maxLength(4000)]],
  });

  /**
   * The conversation id carried by the URL.
   *
   * A query parameter on one static route rather than a path segment; see the route
   * table for why. Read from `queryParamMap`, and the **first** value only: a repeated
   * `?c=a&c=b` is a hand-edited URL, and picking the first is the deterministic choice.
   */
  private readonly routeId = toSignal(
    this.route.queryParamMap.pipe(map((params) => params.get('c'))),
    { initialValue: null },
  );

  protected readonly rows = computed(() => {
    const page = this.list.value();
    return page?.items ?? [];
  });

  /**
   * The unassigned queue, as its own group.
   *
   * Read from `GET /api/v1/inbox/unassigned` rather than derived from the list,
   * because the list route cannot express this view at all — see the `segment`
   * comment. Each entry carries the conversation summary the route published, so
   * opening one needs no second request to find out whether it exists.
   */
  protected readonly unassignedEntries = computed(() =>
    (this.unassigned.value()?.entries ?? []).filter(
      (entry) => typeof entry.conversation?.id === 'string' && entry.conversation.id !== '',
    ),
  );

  /** True when the queue was read and is genuinely empty, not merely unread. */
  protected readonly unassignedKnown = computed(
    () => this.unassigned.value() !== null && !this.unassigned.error(),
  );

  /** True when a next page exists, so the control is not a lie. */
  protected readonly hasMore = computed(() => (this.nextCursor() ?? '') !== '');
  protected readonly allLoaded = computed(() => !this.hasMore() && this.rows().length > 0);

  protected readonly selected = computed(() => {
    const detail = this.thread.value();
    return detail ?? null;
  });

  /**
   * The selected conversation, narrowed to one that actually has an id.
   *
   * Every generated property is optional, because
   * `spring.jackson.default-property-inclusion: non_null` means an absent property
   * is the normal case on the wire. That is honest about the transport and useless
   * to a caller: an action needs a `string`, not `string | undefined`. Rather than
   * `!` at each of the eleven call sites — which asserts a fact the type system has
   * just said it cannot guarantee — the narrowing happens once, here, and every
   * action that needs an id is written against `requireThread()`.
   */
  protected requireThread(): { detail: Api.ConversationDetail; id: string } | null {
    const detail = this.thread.value();
    if (!detail || typeof detail.id !== 'string' || detail.id === '') return null;
    return { detail, id: detail.id };
  }

  /**
   * Folds a write's response into the thread on screen.
   *
   * ## Why this exists, and why it branches
   *
   * The writes disagree about what they answer with. `reply`, `setMode` and `assign`
   * answer with a whole `ConversationDetail`; `close` and `reopen` answer with a
   * **status** — `{"status":"CLOSED"}`, nineteen bytes, no `id`.
   *
   * Replacing the thread with that status object is what made Reopen, take ownership,
   * mode change, close-again and reply all silently do nothing: `requireThread()` then
   * had no `id` to work with and every action returned at its first line. The pane still
   * rendered, because a non-null object is still an object, so the operator saw a normal
   * thread with a dead button on it.
   *
   * So the rule is stated once, here, rather than trusted at five call sites:
   *
   * - a response **with an `id`** is a whole thread and supersedes what is on screen;
   * - a response **without one** is a patch, and is merged over it.
   *
   * Merging is the only safe reading of a partial response: the alternative is either
   * discarding a thread the operator is in the middle of reading, or — the bug — keeping
   * a fragment and calling it a thread.
   */
  private applyThread(next: Partial<Api.ConversationDetail> | Record<string, unknown>): void {
    const current = this.thread.value();
    const patch = next as Record<string, unknown>;
    const isWholeThread = typeof patch['id'] === 'string' && patch['id'] !== '';
    this.thread.set(
      (isWholeThread
        ? (next as Api.ConversationDetail)
        : { ...(current ?? {}), ...next }) as Api.ConversationDetail,
    );
  }

  /**
   * The selected conversation for a write, or a refusal the operator can read.
   *
   * Every action used to begin `const thread = this.requireThread(); if (!thread) return;`
   * — which, when the thread had been replaced by a fragment, made the button **do
   * nothing and say nothing**. A control the operator pressed that neither acts nor
   * explains itself is indistinguishable from a broken screen, and for a screen reader it
   * is worse: the operator is told the conversation is open and has no way to learn that
   * their click went nowhere.
   *
   * So a write that cannot proceed is refused *out loud*, in the alert region, and the
   * screen resyncs so the operator is looking at the truth rather than at a fragment.
   */
  private requireThreadForWrite(): { detail: Api.ConversationDetail; id: string } | null {
    const thread = this.requireThread();
    if (thread) return thread;
    this.collision.set(this.i18n.t('inbox.write.notLoaded'));
    void this.refreshThreadFromServer();
    return null;
  }

  /**
   * Re-reads the selected thread from the API.
   *
   * The realtime contract is that a frame is a **hint** and the API is the truth, so
   * recovery from any doubt about what is on screen is always a resync rather than an
   * assumption.
   */
  private async refreshThreadFromServer(): Promise<void> {
    const id = this.selectedId();
    if (!id) return;
    await this.loadThread(id);
  }

  /** The version the server published, for `If-Match`. */
  protected readonly threadVersion = computed(() => versionOf(this.thread.value()));

  /** The message count the composer is replying against. */
  protected readonly expectedMessageCount = computed(() => this.thread.value()?.messageCount ?? null);

  /**
   * Whether the thread on screen is behind.
   *
   * When the list page reports a later `lastMessageAt` than the thread does, the
   * operator is reading something that is no longer the whole conversation. It is
   * said in words before they compose a reply, because a collision discovered after
   * sending is a collision the user has already paid for.
   */
  protected readonly threadBehind = computed(() => {
    const detail = this.thread.value();
    if (!detail?.lastMessageAt) return false;
    const listItem = (this.list.value()?.items ?? []).find(
      (item) => item.id === detail.id,
    );
    if (!listItem?.lastMessageAt) return false;
    return Date.parse(listItem.lastMessageAt) > Date.parse(detail.lastMessageAt);
  });

  private unsubscribeRealtime: (() => void) | null = null;

  constructor() {
    this.i18n.register({ inbox: inboxStrings as Namespace });

    // ## The route is the single source of truth for the selection
    //
    // Selecting from the list navigates; the navigation is what loads. That ordering is
    // not incidental — it is the only one that makes all three entry points identical:
    // clicking a row, opening a shared link, and pressing Back.
    //
    // The first version of this component did it the other way round: `select()` loaded
    // the thread and a separate `effect` wrote the id into the URL. A direct visit to
    // `/app/inbox/<id>` then rendered an empty thread pane, because `ngOnInit` adopted
    // the id from the route and never loaded it. That is precisely what a shared link
    // exists to catch, and only a browser could show it.
    //
    // `untracked` because this effect writes the state it derives from; reading it
    // would make the effect depend on its own output.
    effect(() => {
      const id = this.routeId();
      untracked(() => this.adopt(id));
    });
  }

  /**
   * Loads whatever the route says is selected.
   *
   * A no-op when the id has not changed, so a realtime frame or a re-render does not
   * re-fetch a thread the operator is already reading. Clearing the selection resets
   * every pane rather than leaving the last conversation on screen beside a list it no
   * longer belongs to.
   */
  private adopt(conversationId: string | null): void {
    if (conversationId === this.selectedId()) return;
    this.selectedId.set(conversationId);
    this.collision.set(null);
    this.notice.set(null);

    if (!conversationId) {
      this.thread.reset();
      this.resolution.reset();
      this.classification.reset();
      this.customer.reset();
      this.pane.set('list');
      return;
    }

    this.pane.set('thread');
    void this.loadThread(conversationId).then(() => {
      const customerId = this.thread.value()?.customer?.id;
      if (customerId) void this.loadCustomer(customerId);
      else this.customer.reset();
    });
  }

  ngOnInit(): void {
    // No adoption here: the route effect in the constructor already covers the first
    // render, and doing it in both places would load the thread twice.
    void this.loadList();
    void this.loadCounts();
    void this.loadUnassigned();

    // A realtime frame is a refresh hint: refetch, never render from the frame.
    // The payload is treated as optional at every call site because a bridged frame
    // may carry ids only.
    this.unsubscribeRealtime = this.realtime.onSignal((signal) => {
      if (signal.kind === 'heartbeat') return;
      void this.loadList();
      const id = this.selectedId();
      if (id) void this.loadThread(id);
    });
  }

  ngOnDestroy(): void {
    this.unsubscribeRealtime?.();
  }

  // -- loading --------------------------------------------------------------

    /**
   * The five views, **in the order `GET /api/v1/inbox/counts` returned its keys.**
   *
   * ## Why the response order and not a local list
   *
   * The contract declares the counts response as a **closed object**: five named
   * integers, all `required`, `additionalProperties` absent. So the response is the
   * authority on which views exist and in what order — its key order is declaration
   * order, which is `unassigned, all-open, assigned-to-others, assigned-to-me,
   * history`, putting the work queue first. A hard-coded local list would be a second
   * source of truth that silently disagrees with the document the moment either moves.
   *
   * ## Why every count is read, and nothing is added
   *
   * The document says it in the response's own description: *"The views OVERLAP and are
   * not a partition: `assigned-to-me` also holds released threads the caller last
   * replied to, so the counts do not sum to the number of open conversations and a
   * client must not present them as mutually exclusive buckets."* So there is no total
   * anywhere on this screen, and the five tabs are a set of lenses over one set of
   * conversations rather than five compartments.
   *
   * ## Why a malformed response is not papered over
   *
   * The response is a **closed object**: five named integers, every one `required`, and
   * no `additionalProperties`. So a key that is absent, or present but not an integer, is
   * not a state the server can legitimately be in — it is a contract violation. This
   * used to read `counts[filter] ?? 0`, which rendered the violation as **a queue with
   * nothing in it**: the one number on this screen that must never be invented.
   *
   * `savedViews` now refuses to render at all in that case, and `countsMalformed` says
   * so, so the operator is told the answer could not be read rather than shown a
   * confident zero over a queue that may be full.
   *
   * The narrowing is a **type guard, not a cast**: the value is checked with `typeof`
   * and the key is checked against the declared enum, so no assertion is involved
   * anywhere on this path.
   */
  protected readonly savedViews = computed<readonly SavedView[]>(() => {
    const counts = this.counts.value();
    // `countsUsable()` and **not** `countsMalformed()`: an empty array is truthy in
    // JavaScript, so testing the array directly made this bail on a *clean* response and
    // blanked the strip on every screen, on every combination. It is a real bug this
    // client shipped for one build, and it is the reason the predicate is a named
    // boolean rather than an array used in boolean position.
    if (!counts || !this.countsUsable()) return [];
    return Object.keys(counts)
      .filter((key): key is InboxFilter => (INBOX_FILTERS as readonly string[]).includes(key))
      .map((filter) => ({ filter, count: this.format.integer(counts[filter] as number) }));
  });

  /**
   * Whether the counts response can be believed — a **boolean**, deliberately.
   *
   * Kept separate from `countsMalformed` so it can be the thing a guard tests. The two
   * were once the same value, an array, used directly in a boolean position — and an
   * empty array is truthy, so the "is anything wrong?" check said *yes* precisely when
   * nothing was wrong. Three journeys on the first combination failed before it was
   * found, so this type is the fix and not a style preference.
   */
  protected readonly countsUsable = computed(() => this.countsMalformed().length === 0);

  /**
   * The keys the response published that are not integers.
   *
   * Named rather than boolean so the screen can be specific about what is wrong, and so
   * a test can assert the specific violation rather than that *something* is malformed.
   */
  protected readonly countsMalformed = computed<readonly string[]>(() => {
    const counts = this.counts.value();
    if (!counts) return [];
    return Object.keys(counts).filter(
      (key) => typeof (counts as Record<string, unknown>)[key] !== 'number',
    );
  });

  /**
   * Whether the counts read has landed and is usable.
   *
   * The strip renders only once it has, so the operator never sees five tabs whose
   * badges are all blank and concludes there is no work anywhere — and it stays hidden
   * when the response is malformed, because a strip of five zeroes is a statement about
   * the queue that the server never made.
   */
  protected readonly viewsLoaded = computed(
    () => this.savedViews().length > 0 && this.countsUsable(),
  );

  protected async loadList(): Promise<void> {
    const term = this.search().trim();
    const query: ConversationListQuery = {
      projectId: this.projects.selectedProjectId() ?? undefined,
      // Always sent, including when it is the default. The server's default is
      // `unassigned` now — it used to be `assigned-to-me` and changed under this client
      // once already — so naming the view explicitly removes the dependency on a value
      // that has proved it can move.
      filter: this.segment(),
      search: term === '' ? undefined : term,
      limit: 25,
    };
    await this.list.load(
      () => this.conversations.list(query),
      (page) => (page.items ?? []).length === 0,
    );
    this.nextCursor.set(this.list.value()?.nextCursor ?? null);
  }

  protected async loadMore(): Promise<void> {
    const cursor = this.nextCursor();
    if (!cursor) return;
    const query: ConversationListQuery = {
      projectId: this.projects.selectedProjectId() ?? undefined,
      filter: this.segment(),
      cursor,
      limit: 25,
    };
    const page = await this.conversations.list(query);
    // Appended rather than replaced: "load more" that discards what is already on
    // screen would make the operator scroll for nothing.
    this.list.set({
      items: [...(this.list.value()?.items ?? []), ...(page.items ?? [])],
      nextCursor: page.nextCursor,
    });
    this.nextCursor.set(page.nextCursor ?? null);
  }

  protected async loadCounts(): Promise<void> {
    await this.counts.load(
      () => this.inbox.counts({ projectId: this.projects.selectedProjectId() ?? undefined }),
      (value) => Object.keys(value).length === 0,
    );
  }

  protected async loadUnassigned(): Promise<void> {
    // A page, not one row. This was `limit: 1`, which is defensible for a badge and
    // wrong for a queue: an operator opening the inbox to find the conversation that just
    // arrived past an unrelated one. The queue's own length comes from
    // `totals.total`, so the count on screen is the real one rather than the size of the
    // page that happened to be fetched.
    await this.unassigned.load(
      () =>
        this.inbox.unassigned({
          projectId: this.projects.selectedProjectId() ?? undefined,
          limit: 25,
        }),
      (page) => (page.entries ?? []).length === 0,
    );
  }

  protected async loadThread(conversationId: string): Promise<void> {
    await Promise.allSettled([
      this.thread.load(() => this.conversations.detail(conversationId)),
      this.resolution.load(() => this.conversations.resolution(conversationId)),
      this.classification.load(() => this.conversations.classify(conversationId)),
    ]);
  }

  protected async loadCustomer(customerId: string): Promise<void> {
    await this.customer.load(() => this.customers.detail(customerId));
  }

  protected select(conversationId: string): void {
    // Navigation only. `adopt()` runs from the route effect and does the loading, so a
    // click and a pasted link take exactly the same path.
    void this.router.navigate(['/app/inbox'], { queryParams: { c: conversationId } });
  }

  protected clearSelection(): void {
    void this.router.navigate(['/app/inbox'], { queryParams: {} });
  }

  // -- composer -------------------------------------------------------------

  /**
   * The idempotency key for one composed message.
   *
   * Stable for the lifetime of the draft, so a retry after a network failure
   * carries the same key and the server recognises it as the same write. Regenerated
   * per message, never per click — a key that changed on retry would defeat the
   * header entirely.
   */
  private idempotencyKey: string | null = null;

  protected async send(event: Event): Promise<void> {
    event.preventDefault();
    if (!this.canReply()) return;
    const thread = this.requireThreadForWrite();
    if (!thread || this.sending()) return;
    const { id } = thread;

    const body = this.replyForm.controls.body.value;
    if (body.trim() === '') {
      this.replyForm.controls.body.markAsTouched();
      return;
    }

    this.sending.set(true);
    this.collision.set(null);
    this.notice.set(null);
    const randSuffix = typeof crypto !== 'undefined' && crypto.randomUUID ? crypto.randomUUID().replace(/-/g, '').slice(0, 8) : Date.now().toString(36);
    this.idempotencyKey ??= `fe-${id}-${Date.now()}-${randSuffix}`;

    try {
      const updated = await this.conversations.reply(
        id,
        { body },
        {
          expectedMessageCount: this.expectedMessageCount(),
          idempotencyKey: this.idempotencyKey,
        },
      );
      // The draft is cleared only once the server has taken it.
      this.replyForm.reset({ body: '' });
      this.draft.set('');
      this.idempotencyKey = null;
      this.applyThread(updated);
      await Promise.allSettled([this.loadList(), this.loadCounts(), this.loadUnassigned()]);
    } catch (cause) {
      if (cause instanceof ApiError && cause.status === 409) {
        // Keep the text. Losing it to a concurrent edit is the outcome this whole
        // design is aimed at avoiding.
        this.collision.set(this.i18n.t('inbox.thread.collision'));
      } else {
        this.collision.set(this.i18n.t('inbox.composer.draftKept'));
      }
    } finally {
      this.sending.set(false);
    }
  }

  protected async refreshThread(): Promise<void> {
    const id = this.selectedId();
    if (id) await this.loadThread(id);
  }

  // -- case actions ---------------------------------------------------------

  protected async setMode(mode: 'AI' | 'HUMAN'): Promise<void> {
    // The server checks a different permission per direction, so the guard is too.
    if (mode === 'HUMAN' ? !this.canTakeOverMode() : !this.canReturnToAi()) return;
    const thread = this.requireThreadForWrite();
    if (!thread) return;
    const { detail, id } = thread;
    // The transition is guarded by the mode counter the server publishes on the
    // thread, and it answers 400 without it. A thread read before the server
    // published that counter is re-read rather than written to, because a write
    // that cannot succeed is a button that looks broken.
    const modeVersion = readModeVersion(detail);
    if (modeVersion === undefined) {
      this.collision.set(this.i18n.t('inbox.mode.conflict'));
      void this.refreshThreadFromServer();
      return;
    }
    this.collision.set(null);
    try {
    const updated = await this.conversations.setMode(
    id,
    { mode, modeVersion },
    this.threadVersion(),
    );
      this.applyThread(updated);
      this.notice.set(this.i18n.t('inbox.mode.changed'));
    } catch (cause) {
      this.reportConflict(cause, 'inbox.mode.conflict');
    }
  }

  /**
   * Whether a resolution has been recorded for the selected conversation.
   *
   * `GET .../resolution` answers **204 with no body** when none exists, which the client
   * reads as `null` — so "no resolution" and "the read failed" stay distinguishable.
   */
  protected readonly hasResolution = computed(() => {
    // `GET .../resolution` answers **204 with no body** when none is recorded, and
    // Angular's HttpClient resolves that to `undefined` — not `null`. Both absences are
    // checked, because dereferencing `undefined.outcome` on the *first* close of any case
    // would throw instead of guiding the operator to the form.
    const saved = this.resolution.value();
    if (saved === null || saved === undefined) return false;
    return typeof saved.outcome === 'string' && saved.outcome.trim() !== '';
  });

  protected async closeCase(): Promise<void> {
    if (!this.canResolve()) return;
    const thread = this.requireThreadForWrite();
    if (!thread) return;
    const { id } = thread;
    this.collision.set(null);

    // The server refuses to close until the resolution is recorded. Checked here so the
    // operator is told what to do first, instead of the write being sent, refused, and
    // reported as a generic failure. The server still enforces the rule — it proved it
    // does — this only avoids a round trip whose outcome is already known.
    if (!this.hasResolution()) {
      this.collision.set(this.i18n.t('inbox.case.closeNeedsResolution'));
      this.openResolution.set(true);
      return;
    }

    try {
      // The server answers a status, not the thread. See `applyThread`.
      const status = await this.conversations.close(id, this.threadVersion());
      this.applyThread(status);
      this.notice.set(this.i18n.t('inbox.case.closed'));
      await Promise.allSettled([this.loadList(), this.loadCounts()]);
    } catch (cause) {
      this.reportConflict(cause, 'inbox.case.conflict');
    }
  }

  protected async reopenCase(): Promise<void> {
    if (!this.canResolve()) return;
    const thread = this.requireThreadForWrite();
    if (!thread) return;
    const { id } = thread;
    this.collision.set(null);
    try {
      const status = await this.conversations.reopen(id);
      this.applyThread(status);
      this.notice.set(this.i18n.t('inbox.case.reopened'));
      await Promise.allSettled([this.loadList(), this.loadCounts()]);
    } catch (cause) {
      this.reportConflict(cause, 'inbox.case.conflict');
    }
  }

  protected async takeOwnership(): Promise<void> {
    if (!this.canTakeOwnership()) return;
    const thread = this.requireThreadForWrite();
    const userId = this.currentUserId();
    if (!thread || !userId) return;
    const { id } = thread;
    this.collision.set(null);
    try {
      const updated = await this.conversations.assign(
        id,
        { employeeId: userId },
        this.threadVersion(),
      );
      this.applyThread(updated);
      await this.loadList();
    } catch (cause) {
      this.reportConflict(cause, 'inbox.case.conflict');
    }
  }

  // -- resolution -----------------------------------------------------------

  /**
   * The case resolution form.
   *
   * ## Three validators here that were wrong, found by reading the server's own rule
   *
   * `CaseService.validateResolution` refuses the write unless:
   *
   * - `customerIssue` is **2..2000 characters** — it was optional, so an operator could
   *   submit a blank one and the server answered
   *   `400 {"code":"VALIDATION_FAILED","details":{"fields":{"customerIssue":...}}}`,
   *   which the client mapped to a generic "this page could not be loaded";
   * - `agentAction` is the same, and was optional too;
   * - `outcome` is parsed by `CaseOutcome.valueOf`, so it must be one of three
   *   enumerated values — **not free text**. It was a required free-text field, so any
   *   sentence an operator wrote was refused. `SaveResolutionRequest.outcome` is
   *   declared `type: string` with no enum, which is how this was invisible; the rule is
   *   in the Java and not in the contract.
   *
   * The min-length is enforced here for the same reason the max is: a server rule the
   * client cannot know is a rule the client cannot help with. And `notBlank` rather than
   * `Validators.required`, because `required` accepts `"   "` — which is exactly how a
   * blank `customerIssue` passed the client and then failed the server.
   *
   * The outcome control is a `<select>` over the three values, so the wire value is
   * always one the server can parse. It is still **required**: an empty select means the
   * operator has not decided how the case ended, and `CaseOutcome.valueOf("")` throws.
   */
  protected readonly resolutionForm = this.formBuilder.nonNullable.group({
    outcome: ['', [Validators.required]],
    customerIssue: [
      '',
      [notBlank, Validators.minLength(OUTCOME_MIN_TEXT), Validators.maxLength(OUTCOME_MAX_TEXT)],
    ],
    agentAction: [
      '',
      [notBlank, Validators.minLength(OUTCOME_MIN_TEXT), Validators.maxLength(OUTCOME_MAX_TEXT)],
    ],
    usedKnowledgeBase: [false],
    documentId: ['', [Validators.maxLength(64)]],
    customerAccepted: [false],
    customerSatisfied: [false],
    escalationRequested: [false],
    escalatedToSupervisor: [false],
    notes: ['', [Validators.maxLength(2000)]],
  });

  /**
   * The three outcomes the server can parse, in the operator's language.
   *
   * `CaseOutcome.valueOf(outcome)` throws on anything else, so this list *is* the set of
   * values the wire accepts. The first entry is blank because the question is still
   * unanswered: a resolution that does not say how the case ended is not a resolution.
   */
  protected readonly outcomeOptions = computed(() => [
    { value: '', label: this.i18n.t('inbox.resolution.outcome.unset') },
    { value: OUTCOMES[0], label: this.i18n.t('inbox.resolution.outcome.resolved') },
    { value: OUTCOMES[1], label: this.i18n.t('inbox.resolution.outcome.escalated') },
    { value: OUTCOMES[2], label: this.i18n.t('inbox.resolution.outcome.unresolved') },
  ]);

  /**
   * Per-field messages for the resolution form.
   *
   * Reported next to the field rather than as one banner, because "this page could not
   * be loaded" tells an operator nothing about the two boxes they left empty. Each
   * message says the rule in words — the length bounds are the server's, and a client
   * that knows them can help before the write is sent.
   */
  protected resolutionFieldErrors(): {
    outcome?: string;
    customerIssue?: string;
    agentAction?: string;
  } {
    const errors: { outcome?: string; customerIssue?: string; agentAction?: string } = {};
    const { outcome, customerIssue, agentAction } = this.resolutionForm.controls;
    if (outcome.touched && outcome.hasError('required')) {
      errors.outcome = this.i18n.t('inbox.resolution.outcomeRequired');
    }
    for (const [control, key] of [
      [customerIssue, 'customerIssue'],
      [agentAction, 'agentAction'],
    ] as const) {
      if (!control.touched) continue;
      if (control.hasError('blank')) {
        errors[key] = this.i18n.t('inbox.resolution.required');
      } else if (control.hasError('minlength')) {
        errors[key] = this.i18n.t('inbox.resolution.tooShort');
      } else if (control.hasError('maxlength')) {
        errors[key] = this.i18n.t('inbox.resolution.tooLong');
      }
    }
    return errors;
  }

  protected async saveResolution(event: Event): Promise<void> {
    event.preventDefault();
    if (!this.canResolve()) return;
    const thread = this.requireThreadForWrite();
    if (!thread) return;
    const { id } = thread;
    if (this.resolutionForm.invalid) {
      this.resolutionForm.markAllAsTouched();
      return;
    }
    this.savingResolution.set(true);
    this.collision.set(null);
    const value = this.resolutionForm.getRawValue();
    try {
      const saved = await this.conversations.saveResolution(id, {
        outcome: value.outcome,
        customerIssue: value.customerIssue,
        agentAction: value.agentAction,
        usedKnowledgeBase: value.usedKnowledgeBase,
        // An empty id is "no document", which is a different fact from "id ''".
        documentId: value.documentId.trim() === '' ? undefined : value.documentId.trim(),
        customerAccepted: value.customerAccepted,
        customerSatisfied: value.customerSatisfied,
        escalationRequested: value.escalationRequested,
        escalatedToSupervisor: value.escalatedToSupervisor,
        notes: value.notes,
      });
      this.resolution.set(saved);
      this.notice.set(this.i18n.t('inbox.resolution.saved'));
    } catch (cause) {
      // A 400 from this route carries **which** field the server refused, in
      // `details.fields`. Mapping it to a generic error — which is what happened, and the
      // reason the close journey spent 30 seconds on "this page could not be loaded" —
      // throws away the only information the response has. So it is read and put on the
      // field it names.
      this.reportFieldFailure(cause);
      this.reportConflict(cause, 'inbox.case.conflict');
    } finally {
      this.savingResolution.set(false);
    }
  }

  /**
   * Marks the fields a `400 VALIDATION_FAILED` refused, so the operator sees which boxes
   * the server objected to rather than a banner they cannot act on.
   *
   * Deliberately does **not** trust the server's message text — it is an English
   * sentence that the i18n layer owns. Only the *field names* are read from the
   * response; the words come from the dictionary.
   */
  private reportFieldFailure(cause: unknown): void {
    if (!(cause instanceof ApiError) || cause.status !== 400) return;
    const fields = cause.body?.details?.['fields'];
    if (!fields || typeof fields !== 'object') return;
    const named = fields as Record<string, unknown>;
    for (const control of ['outcome', 'customerIssue', 'agentAction'] as const) {
      if (control in named) this.resolutionForm.controls[control].markAsTouched();
    }
  }

  protected loadResolutionFromServer(): void {
    const saved = this.resolution.value();
    if (!saved) return;
    this.resolutionForm.patchValue({
      outcome: saved.outcome ?? '',
      customerIssue: saved.customerIssue ?? '',
      agentAction: saved.agentAction ?? '',
      usedKnowledgeBase: saved.usedKnowledgeBase === true,
      documentId: saved.documentId ?? '',
      customerAccepted: saved.customerAccepted === true,
      customerSatisfied: saved.customerSatisfied === true,
      escalationRequested: saved.escalationRequested === true,
      escalatedToSupervisor: saved.escalatedToSupervisor === true,
      notes: saved.notes ?? '',
    });
  }

  // -- presentation ---------------------------------------------------------

  protected statusLabel(status: string | null | undefined): string {
    if (!status) return this.format.dash;
    const key = `inbox.status.${status}`;
    return this.i18n.has(key) ? this.i18n.t(key) : status;
  }

  protected priorityLabel(priority: string | null | undefined): string {
    if (!priority) return this.format.dash;
    const key = `inbox.priority.${priority}`;
    return this.i18n.has(key) ? this.i18n.t(key) : priority;
  }

  protected channelLabel(channel: string | null | undefined): string {
    if (!channel) return this.format.dash;
    const key = `shell.channel.${channel}`;
    return this.i18n.has(key) ? this.i18n.t(key) : channel;
  }

  /**
   * Why a conversation is in the unassigned queue.
   *
   * `Entry.reason` is a closed enum on the server. An unrecognised value falls back
   * to the wire text rather than being hidden, so a reason added by a later migration
   * is visible to an operator instead of rendering an empty row.
   */
  protected queueReasonLabel(reason: string | null | undefined): string {
    if (!reason) return this.format.dash;
    const key = `inbox.queue.reason.${reason}`;
    return this.i18n.has(key) ? this.i18n.t(key) : reason;
  }

  /**
   * How a message is labelled.
   *
   * By direction first and author type second, because "inbound" is the fact an
   * operator needs (this came from the customer) and the author type refines it.
   * An unknown author type falls back to the direction rather than being hidden —
   * a message with no name at all is worse than one with a coarse one.
   */
  protected authorLabel(message: Api.MessageView): string {
    const author = message.authorType ?? '';
    const key = `inbox.authorType.${author}`;
    if (author !== '' && this.i18n.has(key)) return this.i18n.t(key);
    const direction = message.direction ?? '';
    const directionKey = `inbox.direction.${direction}`;
    if (direction !== '' && this.i18n.has(directionKey)) return this.i18n.t(directionKey);
    return this.format.dash;
  }

  /**
   * How a message's delivery state is labelled.
   *
   * The wire value is upper-case (`SENT`, `DELIVERED`, `READ`, `FAILED`), so it is
   * normalised to the dictionary's `deliverySent` shape rather than assembled at the
   * call site — a key built by string surgery is a key the coverage test cannot see,
   * and the coverage test is what stops a screen rendering a raw key.
   *
   * An unrecognised state is shown as the wire value rather than hidden: a delivery
   * state the operator cannot read is worse than a technical one, and hiding it
   * would make a failed message look delivered.
   */
  protected deliveryLabel(message: Api.MessageView): string | null {
    const status = message.deliveryStatus;
    if (!status) return null;
    const normalised = status.charAt(0).toUpperCase() + status.slice(1).toLowerCase();
    const key = `inbox.message.delivery${normalised}`;
    return this.i18n.has(key) ? this.i18n.t(key) : status;
  }

  /** Whether a message failed to deliver, so it is never styled as sent. */
  protected deliveryFailed(message: Api.MessageView): boolean {
    return (message.deliveryStatus ?? '').toUpperCase() === 'FAILED';
  }

  protected isOutbound(message: Api.MessageView): boolean {
    return (message.direction ?? '').toUpperCase() === 'OUTBOUND';
  }

  /**
   * Whether to offer "assign to me".
   *
   * `conversation:assign`, not a generic write. Assignment is its own permission on the
   * server, and a user who may reply but not assign is a real configuration.
   */
  protected canTakeOwnership(): boolean {
    return this.sessions.can()(PERMISSIONS.conversationAssign);
  }

  /**
   * Whether to offer the composer.
   *
   * `conversation:reply` — the permission the server actually checks on
   * `POST .../messages`. This used to ask for `conversation:write`, which does not
   * exist, so the composer was hidden from everybody and the screen looked fine.
   */
  protected canReply(): boolean {
    return this.sessions.can()(PERMISSIONS.conversationReply);
  }

  /** Handing the conversation to a human. */
  protected canTakeOverMode(): boolean {
    return this.sessions.can()(PERMISSIONS.conversationTakeOver);
  }

  /** Handing it back to the AI. */
  protected canReturnToAi(): boolean {
    return this.sessions.can()(PERMISSIONS.conversationReturnToAi);
  }

  /**
   * Closing, reopening, and recording a resolution.
   *
   * One permission for all three, because the server has one: `conversation:resolve`.
   */
  protected canResolve(): boolean {
    return this.sessions.can()(PERMISSIONS.conversationResolve);
  }

  /** The signed-in user's id, for "assign to me". Null before the session resolves. */
  protected readonly currentUserId = computed(() => this.sessions.session()?.userId ?? null);

  protected threadErrorRequestId(): string | null {
    const error = this.thread.error();
    return error instanceof ApiError ? error.requestId : null;
  }

  /**
   * The count for the view currently shown, used by the empty state.
   *
   * Read from the response key **verbatim** — no name translation — because a key built
   * by surgery is a key that silently misses and reports zero for a queue with work in
   * it. `null` means the counts read has not landed, which the template renders as an em
   * dash and never as a zero.
   */
  protected segmentCount(): string | null {
    const counts = this.counts.value();
    if (!counts) return null;
    const value = counts[this.segment()];
    return typeof value === 'number' ? this.format.integer(value) : null;
  }

  /**
   * Arrow keys move between saved views, and Home/End jump to the ends.
   *
   * Required by the ARIA tabs pattern and load-bearing at five tabs: with only the
   * selected one in the tab order, without this handler four of the five would be
   * *unreachable by keyboard* rather than merely out of sequence.
   *
   * Not flipped for RTL — the strip is laid out by the document's own flow, so "next in
   * reading order" in Arabic is the tab on the left, and flipping the mapping would fight
   * the layout. The same reasoning as the admin and finance screens, applied here so the
   * three controls behave identically.
   */
  protected onSegmentKeydown(event: KeyboardEvent): void {
    const views = this.savedViews().map((view) => view.filter);
    if (views.length === 0) return;
    const current = views.indexOf(this.segment());
    let next = current;

    switch (event.key) {
      case 'ArrowRight':
      case 'ArrowDown':
        next = (current + 1) % views.length;
        break;
      case 'ArrowLeft':
      case 'ArrowUp':
        next = (current - 1 + views.length) % views.length;
        break;
      case 'Home':
        next = 0;
        break;
      case 'End':
        next = views.length - 1;
        break;
      default:
        return;
    }

    event.preventDefault();
    this.segment.set(views[next]!);
    void this.loadList();
    queueMicrotask(() => {
      document.getElementById(`inbox-segment-${views[next]!}`)?.focus();
    });
  }

  /** The i18n key for a saved view's label, mapped explicitly rather than derived. */
  protected viewLabelKey(filter: InboxFilter): string {
    switch (filter) {
      case 'unassigned':
        return 'inbox.list.filterUnassigned';
      case 'all-open':
        return 'inbox.list.filterAllOpen';
      case 'assigned-to-others':
        return 'inbox.list.filterAssignedToOthers';
      case 'assigned-to-me':
        return 'inbox.list.filterAssignedToMe';
      case 'history':
        return 'inbox.list.filterHistory';
    }
  }

  /**
   * The empty-state body for a view, because "nothing here" means something different
   * in each of the five.
   *
   * An empty *unassigned* queue means nobody is waiting. An empty *assigned-to-me* means
   * the operator has nothing on their plate. An empty *all-open* means the tenant has no
   * open work at all, which is worth knowing. One shared sentence would flatten three
   * different facts into one, and an operator reading "no conversations" from an
   * all-open tab would be wrong about the state of the queue.
   */
  protected emptyBodyFor(filter: InboxFilter): string {
    switch (filter) {
      case 'unassigned':
        return 'inbox.list.emptyUnassigned';
      case 'all-open':
        return 'inbox.list.emptyAllOpen';
      case 'assigned-to-others':
        return 'inbox.list.emptyAssignedToOthers';
      case 'assigned-to-me':
        return 'inbox.list.emptyAssignedToMe';
      case 'history':
        return 'inbox.list.emptyHistory';
    }
  }

  protected listErrorRequestId(): string | null {
    const error = this.list.error();
    return error instanceof ApiError ? error.requestId : null;
  }

  /**
   * Whether the unassigned **queue** region and the `unassigned` list view are the same
   * question asked twice.
   *
   * `GET /api/v1/inbox/unassigned` and `GET /api/v1/conversations?filter=unassigned`
   * return the same set (ADR 0015). Rendering both is two answers to one question, and
   * they can disagree — which is worse than either alone. So when the list is already
   * showing `unassigned`, the standalone queue region is hidden and the list carries the
   * answer. Any other view shows the queue alongside it, because then they are genuinely
   * different questions.
   */
  protected readonly queueDuplicatesList = computed(() => this.segment() === 'unassigned');

  /** Copies the draft into the signal as the operator types. */
  protected onDraftChanged(value: string): void {
    this.draft.set(value);
    // A new composition is a new write, so the key is cleared.
    this.idempotencyKey = null;
  }

  private reportConflict(cause: unknown, messageKey: string): void {
    if (cause instanceof ApiError && (cause.status === 409 || cause.status === 412)) {
      this.collision.set(this.i18n.t(messageKey));
      return;
    }
    if (cause instanceof ApiError && cause.isPermissionDenied) {
      this.collision.set(this.i18n.t('state.noPermission'));
      return;
    }
    this.collision.set(this.i18n.t('state.error'));
  }
}

/**
 * The mode-transition counter the server guards the AI⇄human transition with.
 *
 * `PATCH /api/v1/conversations/{id}/mode` requires the `modeVersion` the caller
 * read, and answers 400 without it. It is a **different** counter from the row
 * version the `If-Match` header carries, so it is read from its own field: the
 * thread publishes `modeVersion`, and a thread that does not is re-read rather
 * than written to.
 */
function readModeVersion(record: unknown): number | undefined {
  if (record === null || typeof record !== 'object') return undefined;
  const value = (record as Record<string, unknown>)['modeVersion'];
  if (typeof value === 'number' && Number.isFinite(value)) return value;
  if (typeof value === 'string' && value !== '' && Number.isFinite(Number(value))) {
    return Number(value);
  }
  return undefined;
}
