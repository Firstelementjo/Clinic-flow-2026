/**
 * Real inbound traffic, and real API calls, made *as the browser*.
 *
 * ## Why this is not a mock
 *
 * It calls `POST /api/v1/dev/mock/incoming-message`, which is **in the committed
 * contract** (`tests/contracts/openapi.json`, tag `mock-inbound-controller`) and is
 * the backend's own development inbound simulator. That controller drives the real
 * pipeline — resolve identity, find-or-create the thread, store idempotently, publish
 * the timeline event — rather than inserting a row, so a journey built on it exercises
 * the same code path a live webhook takes.
 *
 * The alternative was worse: an inbox journey with no conversation in the queue proves
 * only that an empty state renders. Every interesting property of this screen — the
 * thread, the composer, mode takeover, close/reopen, the resolution, the collision
 * check — needs a conversation that really exists.
 *
 * The application itself never calls this route. It is harness code, it lives under
 * `e2e/`, and its appearance in `frontend/src` would be the defect.
 *
 * ## Why every call goes through the page, not `page.request`
 *
 * `page.request` shares the browser context's cookie jar, and the **server rewrites
 * the `XSRF-TOKEN` cookie on every successful unsafe request**. A live page is also
 * issuing its own unsafe requests into that same jar. So a token read by the fixture
 * can be stale before the fixture's own request lands — which is a
 * `403 CSRF_TOKEN_INVALID` that looks like a backend bug and is not one.
 *
 * The fix is not a retry. It is to take the token the way the product takes it: from
 * `document.cookie`, at the instant of the call, inside the page. `apiUnsafe()` below
 * does exactly that, so a fixture call and an application call are indistinguishable
 * from the server's point of view.
 *
 * ## The two gates the simulator has
 *
 * 1. `firstelement.inbox.mock-connector-enabled` must be set, or the controller answers
 *    `404` — the same as a route that does not exist.
 * 2. The project must already have an **enabled MOCK channel connection**.
 *
 * `e2e/seed.ts` writes the connection. The flag is a property of the API process, so it
 * is named in `MOCK_CONNECTOR_FLAG` and reported here on the `404`, rather than as a
 * missing route twenty minutes into a matrix run.
 *
 * ## One conversation per matrix project, idempotently
 *
 * `externalMessageId` is derived from the project name, so two projects never collide
 * on the idempotency key and a rerun of one project updates its own conversation rather
 * than accumulating a second. Message counts stay stable across reruns, which is what
 * lets the reply journey assert on them.
 */
import type { APIResponse, Page } from '@playwright/test';

export interface InboundResult {
  readonly status: string;
  readonly conversationId: string;
  readonly customerId: string;
  readonly messageId: string;
  readonly conversationCreated: boolean;
  readonly externalMessageId: string;
}

const CSRF = '/api/v1/auth/csrf';
const ME = '/api/v1/auth/me';
const INBOUND = '/api/v1/dev/mock/incoming-message';

/**
 * The API property the simulator is gated on.
 *
 * Named here so the failure message can tell the operator exactly what to add. Without
 * it the symptom is a `404` from a route that does exist, which reads as a harness bug.
 */
export const MOCK_CONNECTOR_FLAG = '--firstelement.inbox.mock-connector-enabled=true';

function apiUrl(): string {
  return (process.env['FE_E2E_API_URL'] ?? 'http://localhost:4000').replace(/\/$/, '');
}

function absolute(path: string): string {
  return `${apiUrl()}${path}`;
}

/**
 * The `X-XSRF-TOKEN` value the page currently holds.
 *
 * Read inside the page, from `document.cookie`, because that is the only place it is
 * guaranteed to agree with the cookie the browser will actually send. This is the same
 * read `src/app/core/api/csrf.interceptor.ts` performs.
 */
async function csrfToken(page: Page): Promise<string> {
  const token = await page.evaluate(() => {
    const match = /(?:^|;\s*)XSRF-TOKEN=([^;]*)/.exec(document.cookie);
    return match ? decodeURIComponent(match[1]) : '';
  });
  if (token === '') {
    throw new Error(
      'The page holds no XSRF-TOKEN cookie. The application reads this cookie and sends ' +
        'it as the CSRF header, so its absence means the sign-in did not complete.',
    );
  }
  return token;
}

/**
 * An unsafe API call, carrying the page's live CSRF token.
 *
 * `method` is a parameter rather than two wrappers because every one of these calls
 * needs the same two things — the header, and a failure that names the route and the
 * status — and a wrapper per verb is where they drift apart.
 *
 * ## The one retry, and why it is not papering over a bug
 *
 * The server rotates the `XSRF-TOKEN` cookie on every successful unsafe request, and
 * the application is live in the same jar, making its own unsafe requests (a reply, a
 * status change) between the moment this reads the cookie and the moment this sends.
 * The header then names the previous token and the answer is `403
 * CSRF_TOKEN_INVALID`.
 *
 * That is a race inherent to a rotating double-submit cookie, not a defect, and a human
 * operator simply presses the button again. So this retries with a freshly-read token,
 * bounded, and **only** for that one code. Any other status propagates immediately, so
 * a genuine 404 or 500 still fails loudly, and a fixture that can never succeed still
 * fails after the bound.
 */
async function apiUnsafe(
  page: Page,
  method: 'POST' | 'PUT' | 'PATCH' | 'DELETE',
  path: string,
  data?: unknown,
): Promise<APIResponse> {
  for (let attempt = 1; ; attempt += 1) {
    const response = await page.request.fetch(absolute(path), {
      method,
      data,
      headers: { 'X-XSRF-TOKEN': await csrfToken(page) },
    });
    if (response.status() !== 403 || attempt >= CSRF_ATTEMPTS) return response;
    // Re-read on the next turn. No sleep: the rotation happened between two reads, not
    // over a time window, so an immediate re-read is the right move.
  }
}

/** Bounded, so a fixture that can never succeed still fails and names the route. */
const CSRF_ATTEMPTS = 3;

/** A safe API call. No CSRF header, because a GET is not checked for one. */
async function apiGet(page: Page, path: string): Promise<APIResponse> {
  return page.request.get(absolute(path));
}

/** Fails with the route and the status, rather than a bare `ok() is false`. */
async function requireOk(response: APIResponse, route: string): Promise<void> {
  if (response.ok()) return;
  throw new Error(
    `${route} answered ${response.status()}: ${(await response.text()).slice(0, 400)}`,
  );
}

/**
 * Puts one real conversation in the queue for the signed-in project.
 *
 * ## `key` must be unique per journey
 *
 * `externalMessageId` is the simulator's idempotency key, so a repeated key *updates*
 * the existing conversation rather than creating a second. That is what keeps a rerun
 * from accumulating conversations — and it is also why `key` cannot be just the project
 * name: the journey that closes a conversation, or assigns it to someone, removes it
 * from the unassigned queue, and every later journey that then looked for "the queue row"
 * found nothing. Journeys were passing one after another only because they ran in an
 * order that happened not to collide.
 *
 * So each journey passes its own key. Independence is worth more than a tidy queue, and
 * `npm run e2e:teardown` sweeps whatever a run leaves behind.
 */
/**
 * The message body every seeded conversation carries.
 *
 * Exported because a journey asserts on it, and a journey that typed its own copy would
 * drift: the assertion then fails with "expected X, received Y" and reads as a product
 * defect rather than as the stale literal it is. One constant, two readers.
 */
export const DEFAULT_INBOUND_TEXT = 'I would like to book a consultation about a knee injury.';

export async function seedConversation(
  page: Page,
  project: string,
  key: string,
  text = DEFAULT_INBOUND_TEXT,
): Promise<InboundResult> {
  const me = await apiGet(page, ME);
  requireOk(me, ME);
  const session = (await me.json()) as { projectIds?: string[] };
  const projectId = session.projectIds?.[0];
  if (!projectId) {
    throw new Error(
      `The signed-in account ${journeyEmailHint(project)} has no project membership, so ` +
        'no conversation can be created for it. Run `npm run e2e:seed`.',
    );
  }

  // Touch the CSRF route first so the cookie exists even if the application's very
  // first unsafe request has not happened yet. Harmless, and it makes the precondition
  // explicit rather than assumed.
  requireOk(await apiGet(page, CSRF), CSRF);

  const response = await apiUnsafe(page, 'POST', INBOUND, {
    projectId,
    // Both external ids are per journey, not per project.
    //
    // `externalUserId` decides *whose* thread this lands in: with a shared one, the
    // simulator resolves to the identity created by the first journey and appends into
    // that conversation — `conversationCreated: false` — so a later journey finds a
    // thread that is already assigned, replied to or closed, and therefore absent from
    // the unassigned queue it needs. That made journeys depend on each other's side
    // effects and on the order they happened to run in.
    //
    // The cost is one customer per journey, which is harmless: each journey navigates by
    // the id the response hands back, and the People screen ends up with several real
    // customers rather than one.
    externalUserId: `cx-${project}-${key}`,
    text,
    displayName: 'Probe Patient',
    phone: '+962790000001',
    email: 'probe@example.invalid',
    externalMessageId: `phase11-${project}-${key}`,
  });

  if (response.status() === 404) {
    throw new Error(
      `POST ${INBOUND} answered 404. The simulator answers the same 404 as a route that ` +
        `does not exist, and it has two gates: start the API with ${MOCK_CONNECTOR_FLAG}, ` +
        'and confirm `npm run e2e:seed` has written the enabled MOCK channel ' +
        'connection this controller requires.',
    );
  }
  if (response.status() === 500) {
    // The one 500 with a known cause, named here so it arrives as a finding rather than
    // as a mystery. `IdentityResolver` allocates a CX id from the **project-scoped**
    // customer count while `customers_cxId_key` is **globally** unique, and gives up after
    // five attempts -- so the sixth project in a deployment can never receive its first
    // customer and every inbound message for it answers 500. See the phase-11 report.
    throw new BackendLimitation(
      INBOUND,
      'the customer-reference allocator exhausted its five attempts. IdentityResolver ' +
        'derives the CX id from the project-scoped customer count, but ' +
        'customers."cxId" is globally unique, so once five other projects hold ' +
        '#33000000-#33000004 this project cannot allocate one at all. ' +
        'Customer creation for this tenant is broken; this is a backend defect, not a ' +
        'harness problem, and it is reported as Phase 11 hook 11.',
    );
  }
  requireOk(response, INBOUND);
  const result = (await response.json()) as Partial<InboundResult>;

  // The simulator's `InboundResult` carries `customerId` and `conversationId`, and a
  // journey that navigates by `customerId` needs it. It came back absent once, and the
  // consequence was a journey navigating to `/app/people?c=undefined` — a 500 from the
  // server that read as a client bug. So the fixture refuses to return a result it
  // cannot stand behind rather than letting the next line fail somewhere else.
  for (const field of ['conversationId', 'customerId', 'messageId'] as const) {
    if (typeof result[field] !== 'string' || result[field] === '') {
      throw new Error(
        `POST ${INBOUND} answered ${response.status()} without a "${field}": ` +
          `${JSON.stringify(result)}. A journey that navigates by that id would build a ` +
          'URL with "undefined" in it and fail against the server instead of here.',
      );
    }
  }
  return result as InboundResult;
}

/**
 * Revokes a live session through the real admin route.
 *
 * Used by the session-expiry journey. It returns the **row id**, not a fabricated
 * response, and asserts the revocation succeeded — a journey that deleted nothing and
 * then expected the UI to notice would pass for the wrong reason.
 */
export async function revokeFirstSession(page: Page): Promise<string> {
  const sessions = await apiGet(page, '/api/v1/admin/identity/sessions');
  requireOk(sessions, '/api/v1/admin/identity/sessions');
  const rows = (await sessions.json()) as { id?: string }[];
  const id = rows.find((row) => typeof row.id === 'string' && row.id !== '')?.id;
  if (!id) {
    throw new Error(
      'The tenant has no live session to revoke, so the expiry journey would be testing ' +
        'nothing. Sign in first, and confirm the session row is committed before the ' +
        'page reads it.',
    );
  }

  const response = await apiUnsafe(page, 'DELETE', `/api/v1/admin/identity/sessions/${id}`);
  requireOk(response, `/api/v1/admin/identity/sessions/${id}`);
  return id;
}

/**
 * The conversation id the inbox currently has selected, or null.
 *
 * Read from the URL rather than the DOM, because the id is what a refresh and a shared
 * link depend on. A journey that found the conversation only by clicking a row would
 * pass even if the id were not carried in the route at all.
 */
export function selectedConversationId(page: Page): string | null {
  return new URL(page.url()).searchParams.get('c');
}

/** The address of the first queue row, for the one-click helpers below. */
export function queueRow(page: Page) {
  return page.locator('section[aria-labelledby="inbox-queue-heading"] button').first();
}

/**
 * The queue row for one specific conversation.
 *
 * Preferred over "the first row" wherever the fixture knows the id. The queue is
 * ordered by recency, so "first" is whichever conversation was created most recently —
 * which is this journey's own on a fresh tenant and *someone else's* once the tenant has
 * accumulated any. Selecting by id removes that coupling entirely: a journey opens the
 * conversation it created, whatever else is waiting.
 */
export function queueRowFor(page: Page, conversationId: string) {
  return page.locator(`[data-conversation-id="${conversationId}"]`);
}

/**
 * A fixture failure that is a **backend** limitation rather than a harness bug.
 *
 * Thrown with a name so the report can distinguish "this journey found a defect" from
 * "this journey could not run". `AGENTS.md` is explicit that a journey which cannot
 * pass because the backend does not support it is a finding to report, not a test to
 * skip — so it must arrive as a named finding, not as a generic 500 buried in a log.
 */
export class BackendLimitation extends Error {
  constructor(
    readonly route: string,
    readonly detail: string,
  ) {
    super(`${route} is limited by the backend: ${detail}`);
    this.name = 'BackendLimitation';
  }
}

function journeyEmailHint(project: string): string {
  const base = process.env['FE_E2E_EMAIL'] ?? '';
  const at = base.lastIndexOf('@');
  return at <= 0 ? project : `${base.slice(0, at)}+${project}@${base.slice(at + 1)}`;
}
