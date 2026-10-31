/**
 * The pending AI draft on the conversation the operator is reading.
 *
 * ## What this is, and what it is not
 *
 * Phase 46 gave the backend a governed draft trigger: an inbound message on an
 * AI-mode conversation produces ONE `AiOrchestrator` run and records a draft for
 * a human to read. **Nothing is sent automatically.** There is no automated send
 * in the backend, and this component adds none: the only write it can issue is
 * the one a person presses, and it is the ordinary conversation reply.
 *
 * | Route | Purpose |
 * |---|---|
 * | `GET /api/v1/ai/drafts/conversations/{id}` | the pending draft |
 * | `POST /api/v1/conversations/{id}/messages` | the send — the composer's route, reused |
 *
 * There is no second send endpoint and no draft-specific write. A draft is
 * offered as text to the reply the operator would have written anyway, which is
 * why it is attributable to the human who pressed the button and why the
 * idempotency and collision headers are the same ones the composer sends.
 *
 * ## The four states, and why the 404 is not one of them being an error
 *
 * The route answers **one** 404 for four different facts: no pending draft, a
 * conversation that does not exist, one in another tenant, and one whose draft a
 * human took over. That identity is the non-disclosure property — a client that
 * could tell them apart would be the oracle the backend refused to build.
 *
 * So the panel reads the status, not the story:
 *
 * - `403` → "your account may not read drafts". The server's answer, repeated
 *   verbatim in meaning. **Not** the same words as the client-side gate, so the
 *   two cannot be told apart by their phrasing.
 * - `404` → not an error. No alert, no toast, no server message. Either "no
 *   draft is waiting" or, when the conversation on screen is visibly in human
 *   mode, "a person has taken this over". That second reading is available only
 *   because the operator can already see the mode on the thread they are
 *   reading; it discloses nothing the thread has not already disclosed.
 * - `5xx` / network → a real fault, with a retry, because it is the one answer
 *   an unchanged retry can fix.
 *
 * ## Why the panel is gated on the client's permission read
 *
 * The server enforces `ai-run:read` twice — on the controller and on the service
 * method — and that is the boundary. This gate only avoids a request whose
 * answer is already known. It is asserted in the suite by the **absence** of the
 * request, and a `403` that arrives anyway is handled identically, so the gate
 * cannot become the thing that decides.
 *
 * ## Why the panel takes the conversation's mode as an input
 *
 * Because the backend's own predicate for offering a draft includes
 * `c."mode" = 'AI'` (`AiDraftRepository.latestOfferableDraft`). A conversation a
 * human has taken over can therefore never carry an offerable draft, and the
 * thread is where that mode is already rendered. Reading it from the thread
 * rather than re-deriving it from the 404 is what lets the copy name the reason
 * without the route ever naming it.
 */
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core';
import { ApiError } from '../../core/api/api.service';
import { AiDraftApi, ConversationApi } from '../../core/api/endpoints';
import type * as Api from '../../core/api/generated';
import { SessionService } from '../../core/auth/session.service';
import { PERMISSIONS } from '../../core/auth/permissions';
import { FormatService } from '../../core/format/format.service';
import { I18nService } from '../../core/i18n/i18n.service';
import { ResourceStore } from '../../core/state/resource';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { aiDraftStrings } from './ai-draft.strings';

/**
 * What the panel has established about the draft.
 *
 * `withheld` is a *reading* of a 404, not a fact the route reported — see the
 * class comment. It is only reachable when the conversation on screen is in human
 * mode, which the thread already showed.
 */
type DraftState = 'absent' | 'withheld' | 'forbidden' | 'unavailable';

@Component({
  selector: 'fe-ai-draft-panel',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslatePipe],
  templateUrl: './ai-draft.component.html',
  styleUrl: './ai-draft.component.css',
})
export class AiDraftPanelComponent {
  private readonly drafts = inject(AiDraftApi);
  private readonly conversations = inject(ConversationApi);
  private readonly sessions = inject(SessionService);

  protected readonly format = inject(FormatService);
  protected readonly i18n = inject(I18nService);

  /** The open conversation, or null when none is. Names a conversation, never a tenant. */
  readonly conversationId = input<string | null>(null);
  /**
   * The thread's reply mode, read from the conversation the operator already has
   * open. Used only to phrase a 404; never sent, and never a substitute for the
   * server's own scope resolution.
   */
  readonly conversationMode = input<string | null | undefined>(null);
  /** The thread's message count, carried on the send as the collision header. */
  readonly messageCount = input<number | null>(null);

  /** The draft, once read. `null` until the route answers with one. */
  private readonly store = new ResourceStore<Api.AiDraftView>();
  /** How a decline is to be read. See `DraftState`. */
  private readonly declined = signal<DraftState | null>(null);

  protected readonly sending = signal(false);
  protected readonly sent = signal(false);
  protected readonly failure = signal<string | null>(null);

  /**
   * The idempotency key for one offered draft.
   *
   * Stable for the lifetime of the draft, so a double-press or a retry after a
   * dropped connection is recognised by the server as the same write. Derived
   * from the conversation and the run, so a *different* draft on the same
   * conversation is a different write — which is the distinction that matters if
   * a second run lands while this one is still on screen.
   */
  private idempotencyKey: string | null = null;

  constructor() {
    this.i18n.register({ aiDraft: aiDraftStrings as never });

    // The conversation id is the only thing that triggers a read. `untracked`
    // because this effect writes the state it reads from — the store — and
    // tracking that would make the effect depend on its own output.
    effect(() => {
      const id = this.conversationId();
      untracked(() => this.adopt(id));
    });
  }

  /**
   * Whether to ask at all.
   *
   * UX only: the server enforces `ai-run:read` on both the controller and the
   * service method. This exists so the client does not spend a request on an
   * answer it already knows, and so a role without the grant is not shown a
   * surface that would refuse it.
   */
  protected canRead(): boolean {
    return this.sessions.can()(PERMISSIONS.aiRunRead);
  }

  /**
   * Whether this caller may send.
   *
   * A **different** permission from the one that reads the draft. The backend's
   * `AiDraftController` javadoc says it outright: a principal holding
   * `ai-run:read` but not the send permission can read a draft and still be
   * unable to speak to a customer. So the card shows the draft and withholds
   * the button, rather than hiding text the caller is entitled to read.
   */
  protected canSend(): boolean {
    return this.sessions.can()(PERMISSIONS.conversationReply);
  }

  protected readonly draft = computed(() => this.store.value());

  /** The body, narrowed: an empty string renders nothing rather than a blank card. */
  protected readonly body = computed(() => this.draft()?.body?.trim() ?? '');

  /**
 * Whether a read is actually in flight.
 *
 * `store.loading()` is true for `idle` as well as `loading`, which is right for a
 * list pane that loads on init and wrong here: this panel never issues a request
 * at all when the caller lacks `ai-run:read`, so an `idle` store must render as
 * *nothing at all* rather than as a spinner that never resolves. A region that
 * says "loading" forever is worse than one that says "not permitted".
 */
protected readonly loading = computed(() => this.store.phase() === 'loading');

  /**
   * The refusal reading, resolved once.
   *
   * `withheld` when the conversation on screen is in human mode, `absent`
   * otherwise. Both come from the same 404 — see the class comment — so this
   * never distinguishes "no draft" from "not yours", and never shows the
   * server's own message.
   */
  protected readonly declinedAs = computed<DraftState | null>(() => {
    // The client's own gate first, so a caller who cannot read drafts is told so
    // whether the server refused or the request was never made. One phrasing for
    // both: a surface that said something different for each would let a reader
    // tell a local decision from a server decision, which is not a distinction
    // any of this is for.
    if (!this.canRead()) return 'forbidden';

    const state = this.declined();
    if (state === null) return null;
    if (state === 'absent' && (this.conversationMode() ?? '').toUpperCase() === 'HUMAN') {
      return 'withheld';
    }
    return state;
  });

  /**
   * Whether anything at all should render for this conversation.
   *
   * Deliberately **not** gated on `canRead()`. A caller without `ai-run:read`
   * is told the surface exists and that they may not read it, rather than being
   * shown nothing: a panel that silently does not appear is indistinguishable
   * from a build where the feature was never wired, and the person who needs to
   * ask for the permission is exactly the person who can see it is missing.
   *
   * What the gate does control is the **request**. No request is issued, so
   * nothing about another tenant's conversation can be learned by issuing one.
   */
  protected readonly relevant = computed(() => {
    const id = this.conversationId();
    return id !== null && id !== '';
  });

  /** A fault the caller can act on. The 5xx path, and only that. */
  protected readonly fault = computed(() => {
    const error = this.store.error();
    return error instanceof ApiError && error.status >= 500 ? error : null;
  });

  /**
   * Loads whatever the route says about the newly selected conversation.
   *
   * A no-op when the id has not changed, so a re-render or a realtime frame does
   * not re-read a draft the operator is already looking at. Clearing the
   * selection resets everything rather than leaving one conversation's draft on
   * screen beside another conversation's thread — which would be a worse
   * disclosure than either state alone.
   */
  private adopt(conversationId: string | null): void {
    this.idempotencyKey = null;
    this.sending.set(false);
    this.sent.set(false);
    this.failure.set(null);
    this.declined.set(null);

    if (conversationId === null || conversationId === '') {
      this.store.reset();
      return;
    }

    if (!this.canRead()) {
      this.store.reset();
      return;
    }

    void this.read(conversationId);
  }

  private async read(conversationId: string): Promise<void> {
    await this.store.load(() => this.drafts.pendingDraft(conversationId));
    const error = this.store.error();
    if (!(error instanceof ApiError)) {
      this.declined.set(null);
      return;
    }

    // ## A 404 is an answer, not a failure
    //
    // The route returns it for four facts at once — no draft, no such
    // conversation, another tenant's, and withheld by a human takeover. Rendering
    // it as an error would tell an operator something the server deliberately
    // refused to say, and rendering the server's own message ("Resource not
    // found") would be the existence oracle this route was built not to be. So
    // the error is consumed here and the panel reads it as a state.
    if (error.isNotFound) {
      this.declined.set('absent');
      return;
    }

    // A `403 CSRF_TOKEN_INVALID` cannot happen on a GET, so every 403 here is the
    // permission, and the interceptor has already retried a token failure once.
    if (error.status === 403) {
      this.declined.set('forbidden');
      return;
    }

    if (error.isSessionExpired) {
      // The error interceptor has already ended the session in one place; the
      // panel adds nothing, because a route guard will take the operator to the
      // sign-in screen and any copy here would be describing a state they are
      // leaving.
      this.declined.set('unavailable');
      return;
    }

    // 5xx and the network are the states an unchanged retry can fix, and they
    // stay in the store so the retry control can be offered.
    this.declined.set('unavailable');
  }

  protected retry(): void {
    const id = this.conversationId();
    if (id) void this.read(id);
  }

  /**
   * Sends the offered text through the ordinary reply route.
   *
   * ## The one write this component can issue
   *
   * `ConversationApi.reply` is the composer's method, unchanged: the same path,
   * the same `SendMessageRequest` body, the same `X-Expected-Message-Count`
   * collision check, the same `Idempotency-Key`. There is no draft-specific
   * endpoint and no bypass, which is what makes the message attributable to the
   * person who pressed the button.
   *
   * ## What happens when it fails
   *
   * The card stays, and so does the text. A `409` means another message landed
   * first, and the remedy is to read the thread and decide again — the same
   * outcome the composer already handles. Nothing is retried automatically: an
   * automatic retry of a refused write either loses the edit or duplicates the
   * action.
   */
  protected async send(): Promise<void> {
    const conversationId = this.conversationId();
    const body = this.body();
    if (!conversationId || body === '' || this.sending()) return;
    if (!this.canSend()) return;

    this.sending.set(true);
    this.failure.set(null);
    this.idempotencyKey ??= `fe-draft-${conversationId}-${this.draft()?.runId ?? 'unknown'}`;

    try {
      await this.conversations.reply(
        conversationId,
        { body },
        {
          expectedMessageCount: this.messageCount(),
          idempotencyKey: this.idempotencyKey,
        },
      );
      // The server has taken the text. The draft is finished either way — the
      // thread below now carries the message, which is what an operator looks at.
      this.sent.set(true);
      this.idempotencyKey = null;
      this.store.reset();
      this.declined.set('absent');
    } catch (cause) {
      this.failure.set(
        cause instanceof ApiError && cause.status === 409
          ? this.i18n.t('inbox.thread.collision')
          : this.i18n.t('inbox.composer.draftKept'),
      );
    } finally {
      this.sending.set(false);
    }
  }

  /** The run this text came from, for an operator who has to ask about it. */
  protected readonly runId = computed(() => this.draft()?.runId ?? '');
}