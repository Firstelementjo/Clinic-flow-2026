# Phase 11 — Angular route migration

**Status:** GROUPS 1-12 DELIVERED.
**Owner agent:** `firstelement-angular`
**Depends on:** a stable `/api/v1` OpenAPI contract from app-bootstrap, cookie
session + CSRF from `backend/tenant-security/**`, and the SSE stream from
`backend/notifications-realtime/**`.

## Objective

Replace the React/Vite client with Angular standalone components **route by
route**, preserving Arabic-first RTL and English LTR behaviour. Not a big-bang
cutover.

## Work order (one route group per checkpoint)

1. Angular workspace, build, environments, generated OpenAPI client, error
   interceptor, auth/session service.
2. Shell: navigation, tenant/project context, permissions UX, theme, locale,
   RTL, responsive layout.
3. Login and settings.
4. Dashboard and analytics.
5. Inbox and conversation thread.
6. People / customer 360.
7. Team, routing, availability, imports.
8. Knowledge, AI agent, channels, escalation, audit.
9. Admin dashboard (Phase 7 surfaces).
10. Finance dashboard (Phase 8 surfaces).
11. Reports and export progress / download history (Phase 9 surfaces).
12. Clinic: appointments, doctors, procedures, reminders, AI receptionist.

### Checkpoint status

| Group | Delivered | Notes |
|---|---|---|
| 1 workspace + build + client + interceptors + session | yes | `frontend/` |
| 2 shell (nav, tenant/project, permissions, theme, locale, RTL, responsive) | yes | |
| 3 login + settings | yes | |
| 4 dashboard | yes, **but not the clinic funnel** — see *Backend hooks* below | |
| 5 inbox and conversation thread | yes | list, thread, customer pane, composer, mode, lifecycle, resolution, classification, activity |
| 6 people / customer 360 | yes | list, record, identities, conversations, follow-ups, appointments, **internal notes kept separate** |
| 7 team, routing, availability, imports | **table yes, the rest no** | no employee/working-hours/routing/import route exists — see *Hook 6* |
| 8 knowledge, AI agent, channels, escalation, audit | **policy surfaces yes, agents and escalation no** | no document/agent/escalation route exists — see *Hooks 7 and 8* |
| 9 administration (Phase 7 surfaces) | yes | 13 reads across `/api/v1/admin/**`; the seven governance **writes** are deliberately not offered — see *Batch B* |
| 10 finance (Phase 8 surfaces) | **reads yes, the eleven writes no** | all 24 finance routes guarded by one permission; reads only offered — see *Hook 13* |
| 11 reports and exports (Phase 9 surfaces) | **reads yes, create and download no** | `CreateExportRequest` needs ids no route lists — see *Hook 14* |
| 12 clinic | **route structure and states yes, data no** | there is no clinic route in the contract; the screen states the gap and names what it waits for |

**Group 5 was reopened in this batch** because `closes a conversation and reopens it`
failed and the cause was three layers down. The resolution form shipped with two optional
fields and a free-text outcome; the server requires all three and refuses a 400 whose
`details.fields` the client discarded. Fixed, and covered by both a unit and a browser
assertion. The reopen leg remains red on **Hook 16**, a backend defect.

## Hard rules

1. **Route guards are UX only.** The backend remains the authorization boundary;
   a guard must never be the only thing preventing an action.
2. **Do not port React markup line by line.** Preserve behaviour, content, and
   design tokens; rebuild the presentation with Angular components.
3. **No hard-coded user-facing strings.** Arabic first, English second, every
   string in the i18n layer.
4. **RTL uses logical CSS properties.** Directional icons flip only when their
   meaning is directional.
5. **Locale-aware numbers, dates, currencies, timezones** — explicit and
   testable, never `toLocaleString()` guesses.
6. **Realtime is a refresh hint.** After an SSE reconnect, resynchronise from the
   API. A bridged frame may carry ids only (ADR 0007 §2 consequence), so the
   client must treat a frame payload as optional and refetch.
7. **Accessibility:** WCAG 2.2 AA target, keyboard reachable, visible focus,
   reduced motion, semantic status text.
8. **Real API data** with intentional loading, empty, error, and stale states.
   Never a mock that ships.
9. **Never store a long-lived bearer token.** Session is the HttpOnly cookie;
   CSRF token is read from the cookie/XSRF header pair.

## Ownership globs

- `frontend/**`
- `docs/migration/phases/phase-11.md`

Do not modify `backend/**`, `ops/**`, or `apps/web` (the legacy client stays
frozen and runnable until cutover).

## Acceptance gate

Playwright journeys pass in **Arabic RTL and English LTR** at desktop, tablet,
and mobile widths, covering: browser refresh, session expiry, realtime reconnect
and resync, empty state, error state, loading state, and keyboard navigation.

**Status: ADR 0014, 0015 and 0016 are consumed. Hook 11 and Hook 16 are RESOLVED. The
matrix is 400 journeys = 66 × 6.**

| Command | Result |
|---|---|
| `npm run typecheck` | clean (app, spec, e2e) |
| `npm test` | **269 passed, 0 failed, 0 skipped** (16 files) |
| `npm run generate:api:check` | matches the contract (128 paths, 135 schemas) |
| `npm run build` | see *Batch B* below |
| `npx playwright test` | **373 passed, 5 failed, 0 skipped** (378 = 63 × 6) |

**The five failures are all one journey — `closes a conversation and reopens it` — and
the cause is a backend defect, not a client one — Hook 16:
`POST /api/v1/conversations/{id}/reopen` answers 500 unconditionally.** One line in
`ConversationRepository.conditionalUpdate`, which binds `expectedUpdatedAt` into its
parameter map and never binds the `:conversationId` its own predicate uses. Reproduced
directly against the running API and confirmed by the server's own log line; the analysis
is under *Hook 16*. The journey is **not** skipped, **not** weakened, and needs **no
client change** — it goes green the moment that line is fixed.

Everything else passes on all six combinations: 62 of 63 journeys green everywhere, and
the 63rd green up to its final assertion. (The count of five rather than six is ordinary
variation in which combination fails first to time out; the failure reason is identical
in all of them, and the run before it failed on all six for the same reason.)

**A second red appeared during this batch and was mine, not the backend's.** After the
resolution-form fix, `reaches the new screens by a browser refresh` failed on all six
with `Expected substring: "My knee hurts"` — a literal from an earlier revision of the
fixture, typed into the journey as well as into `inbound.ts`. Two copies of one string
had drifted, and the failure reads exactly like a product defect. The fixture's text is
now `DEFAULT_INBOUND_TEXT`, exported and asserted on, so there is one literal and two
readers rather than two literals and one reader. Re-run: 6/6.

**Two consecutive full runs were made after each of the last two fixes**, which is what
distinguishes "fixed" from "passed once". The 63rd journey is the only red in both.

**What changed between the batch A verdict and this one, and why it matters to record.**
Batch A reported 284/16 with every failure attributed to Hook 11. Re-running against
the fixed jar removed all 16 — so Hook 11 is confirmed resolved and the per-organization
`customers_organizationId_cxId_key` index is confirmed live (verified directly: six
projects each allocated `#33000000`, which is correct for a per-organization sequence
and impossible under the old global index).

That exposed **one residual failure the backend fix did not explain**:
`closes a conversation and reopens it` failed on 4 of 6 combinations. It was not
leftover state — it was a defect in my journey, and chasing it found three real things:

1. **The journey opened "the first queue row", not its own conversation.** Once a
   tenant has any conversation, that row is another journey's, and closing someone
   else's already-resolved case produces a conflict. Seven journeys were retargeted to
   their own conversation via the fixture's returned id.
2. **The cause underneath was two more real server rules, neither in the contract.**
   `POST .../close` is refused until `.../resolution` has been saved — `Record how the
   case was resolved before closing it`. And `CaseService.validateResolution` requires
   `customerIssue` and `agentAction` at 2..2000 characters while `CaseOutcome.valueOf`
   requires `outcome` to be one of three enumerated values. `SaveResolutionRequest`
   declares all three as **optional plain strings**, so none of this is visible in the
   document; the form shipped with two optional fields and a free-text outcome, and the
   server refused every save with a 400 whose `details.fields` the client discarded in
   favour of a generic error. The form now mirrors all three rules, `outcome` is a
   `<select>` over the values the wire can carry, and a 400's field names are read onto
   the fields they name — never its English.

   The journey reached that point and then hit **Hook 16**: `POST .../reopen` answers
   500 unconditionally, for every caller, because `conditionalUpdate` never binds its own
   `:conversationId`. That is a backend defect and it is the one thing left red.

A full matrix run takes about 45 minutes wall clock, roughly 33 of which is the
deliberate sign-in pacing described under *Findings that are not contract defects*.

## Which backend routes each screen calls

Every screen below reads real data. Nothing renders a placeholder number.

### Login and session

| Route | Method | Purpose |
|---|---|---|
| `/api/v1/auth/csrf` | GET | Arms the CSRF token before the first mutation (login is CSRF-protected) |
| `/api/v1/auth/login` | POST | Credential exchange; the session arrives as a `Set-Cookie` |
| `/api/v1/auth/me` | GET | Boot session resolve, and re-resolve after a hard refresh |
| `/api/v1/auth/logout` | POST | Revokes the server row and clears the cookie |
| `/api/v1/permissions/me` | GET | The server's own answer to "what may I do" — drives every permission decision in the UI |

### Shell (every authenticated screen)

| Route | Method | Purpose |
|---|---|---|
| `/api/v1/auth/me` | GET | Name, role, locale, organization, **server-resolved** `projectIds` |
| `/api/v1/permissions/me` | GET | Gates which navigation items and cards are offered |
| `/api/v1/realtime/stream` | GET (SSE) | Refresh hint only; `withCredentials`, bounded jittered backoff |
| `/api/v1/realtime/state` | GET | The resync target after a reconnect |

No permission-gated call is made by the shell itself. The project `<select>`
changes only the `projectId` query parameter a screen sends; it grants nothing.

### Dashboard

| Card | Route | Gated on |
|---|---|---|
| Inbox counts, status/channel split, longest wait | `GET /api/v1/inbox/unassigned` | `conversation:read` |
| Unread notifications, server clock | `GET /api/v1/realtime/state` | `conversation:read` |
| Stream health | `GET /api/v1/operations/realtime` | `system-health:read` |
| Users, live sessions, queue depth, dead letters | `GET /api/v1/admin/overview` | `system-health:read` |
| Audit trail | `GET /api/v1/admin/audit/events` | `audit-log:read` |

### Settings

| Section | Route | Gated on |
|---|---|---|
| Your account | `GET /api/v1/auth/me` | — |
| Effective permissions | `GET /api/v1/permissions/me` | — |
| Service liveness | `GET /api/v1/status` | public |
| Queue and jobs | `GET /api/v1/operations/jobs`, `/jobs/worklist`, `/jobs/stranded-leases`, `/connectors`, `/webhooks` | `system-health:read` |
| Live connection | `GET /api/v1/operations/realtime` | `system-health:read` |
| Knowledge indexing | `GET /api/v1/operations/search/freshness`, `/search/gaps` | `knowledge:read` |
| Audit | `GET /api/v1/admin/audit/events` | `audit-log:read` |
| Security events | `GET /api/v1/admin/audit/security` | `security-event:read` |

`endpoints.ts` additionally wraps `/api/v1/conversations`, `/api/v1/customers`,
`/api/v1/notes`, `/api/v1/channels`, `/api/v1/admin/ai/*` and the admin identity
routes for groups 5-12.

### Inbox (`/app/inbox`)

Three panes on a wide screen, one behind a real `tablist` below 1100px. The
selected conversation is a **query parameter** (`?c=<uuid>`) on one static route;
see *Hook 10* for why it is not a path segment.

| Pane / feature | Route | Method | Gated on |
|---|---|---|---|
| Unassigned queue | `/api/v1/inbox/unassigned` | GET | `conversation:read` |
| Saved views (segmented) | `/api/v1/conversations?filter=assigned-to-me\|history` | GET | `conversation:read` |
| Search | `/api/v1/conversations?search=` | GET | `conversation:read` |
| Counts on the segments | `/api/v1/inbox/counts` | GET | `conversation:read` |
| Thread, messages, events | `/api/v1/conversations/{conversationId}` | GET | `conversation:read` |
| Reply | `/api/v1/conversations/{conversationId}/messages` | POST | `conversation:reply` |
| AI ⇄ human | `/api/v1/conversations/{conversationId}/mode` | PATCH | `conversation:take-over`, `conversation:return-to-ai` |
| Status and priority | `/api/v1/conversations/{conversationId}` | PATCH | `conversation:resolve` |
| Close / reopen | `/api/v1/conversations/{conversationId}/close`, `/reopen` | POST | `conversation:resolve` |
| Assign to me | `/api/v1/assignments/{conversationId}` | PUT | `conversation:assign` |
| Resolution (read + write) | `/api/v1/conversations/{conversationId}/resolution` | GET, PUT | `conversation:resolve` |
| Classification | `/api/v1/conversations/{conversationId}/classification` | GET, PUT | `conversation:resolve` |
| Customer behind the thread | `/api/v1/customers/{customerId}` | GET | `customer:read` |

Concurrency headers are honoured exactly as declared: the reply carries
`Idempotency-Key` (a double submit is one write) and `X-Expected-Message-Count`
(the server refuses a reply written against a stale thread, and the UI keeps the
composed text and says a message arrived first), and every other mutation carries
`If-Match` from the version the read model published.

### People (`/app/people`)

| Feature | Route | Method | Gated on |
|---|---|---|---|
| List and search | `/api/v1/customers?search=&cursor=&limit=` | GET | `customer:read` |
| 360 record | `/api/v1/customers/{customerId}` | GET | `customer:read` |
| Edit the record | `/api/v1/customers/{customerId}` | PATCH | `customer:update` |
| That customer's internal notes | `/api/v1/notes?resourceType=customer&resourceId=` | GET | `note:read` |
| Write / delete an internal note | `/api/v1/notes`, `/api/v1/notes/{noteId}` | POST, DELETE | `note:create`, `note:delete` |

**Customer-facing history and internal history stay separate** (FR-3.4). The
conversations on the record are the customer's thread list; the notes are
internal. They are two regions with the relationship stated in words, never one
merged timeline.

### Team (`/app/team`)

| Feature | Route | Method | Gated on |
|---|---|---|---|
| Directory: users, roles, memberships, summary, observed principals, credential presence | `/api/v1/admin/identity` | GET | `employee:read` |
| Live sessions | `/api/v1/admin/identity/sessions` | GET | `employee:read` |
| A user's effective permissions | `/api/v1/permissions/{userId}` | GET | `permission-inspector:read` |

Only **credential presence** is rendered — `hasCredentials` and
`hasWebhookSecret` as booleans. No secret value is displayed, and the response
model does not carry one. Working hours, availability, skills, capacity and
employee import have **no route** (Hook 6).

### Knowledge, AI governance, channels, escalation (`/app/knowledge`, `/app/channels`)

| Section | Route | Method | Gated on |
|---|---|---|---|
| Index freshness | `/api/v1/operations/search/freshness` | GET | `knowledge:read` |
| Knowledge gaps | `/api/v1/operations/search/gaps` | GET | `knowledge:read` |
| AI tools, policies, budgets, approvals, blocked calls, redactions | `/api/v1/admin/ai/tools`, `/policies`, `/budgets`, `/approvals`, `/blocked-calls`, `/redactions` | GET | `system-health:read` |
| Channel capabilities | `/api/v1/channels/capabilities` | GET | `channel:read` |

Every one of these is **read-only**. The AI section says so in words, and there
is no control anywhere on the screen that changes policy: every mutation on these
surfaces is an explicit command on a route this client does not call, which is the
property FR-5.9 asks for.

`GET /api/v1/channels/connections` is **never called** — see *Hook 9*.
`escalationRequested` and `escalatedToSupervisor` are fields on a case
resolution, not a queue; the screen points at where they are recorded instead of
drawing an empty one (Hook 8).

### Administration (`/app/admin`)

One route with eight tabbed subjects. Tabs rather than one long scroll, because eight
subjects on a phone is a wall an operator has to read past.

| Tab | Route | Method | Gated on |
|---|---|---|---|
| Overview | `/api/v1/admin/overview` | GET | `system-health:read` |
| Backups | `/api/v1/admin/backups` | GET | `system-health:read` |
| Migrations | `/api/v1/admin/migrations` | GET | `system-health:read` |
| Maintenance commands | `/api/v1/admin/maintenance/commands`, `/commands/disabled` | GET | `system-health:read` |
| Audit trail | `/api/v1/admin/audit/events`, `/audit/security` | GET | `audit-log:read`, `security-event:read` |
| Legal governance | `/api/v1/admin/governance/legal-holds`, `/deletions`, `/deletions/{requestId}/targets`, `/retention-classes`, `/maintenance/purge-gate` | GET | `security-event:read`, `system-health:read` |
| Active sessions | `/api/v1/admin/identity/sessions` | GET | `session:revoke` |
| Export jobs | `/api/v1/admin/exports/jobs`, `/jobs/{jobId}/detail` | GET | `analytics:read-organization` |

Every permission above is read off the controller's own `@RequirePermission`, not
inferred. The route gate is `system-health:read` (what `/admin/overview` itself
declares); the screen then checks a *further* permission per subject, so a caller with
only the health permission sees health, backups and migrations and nothing else.

**The seven governance writes are not offered, and that is a decision rather than a
gap.** `AdminGovernanceMaintenanceController` exposes seven `POST`s — place a legal
hold, release one, request a privacy deletion, preview one, execute one, purge — all
under `ORGANIZATION_UPDATE`, which the backend also reuses for export-artefact deletion
and export retention expiry/purge. The routes exist; the buttons do not. Each is a
deliberate destructive act that should be requested by name and leave an audit event of
its own, and a one-click purge next to a status chip inverts that. The screen shows
the **state** those writes change and names the ones it withholds, so the omission is
legible from the product. Pinned by a journey that asserts on the browser's own request
log: **no `POST`, `PUT` or `PATCH` is issued from this screen at all.**

`/admin/identity`'s five mutations (role change, deactivate, add/remove membership,
revoke session) are also not called. They are listed on the governance tab with the
reason — a role change really does revoke the target's sessions, and that should not be
one click between two status chips.

### Finance and exports (`/app/finance`)

One route with six tabs; the navigation's *Reports and exports* item arrives with
`?tab=exports` so it lands on the right one.

| Tab | Route | Method | Gated on |
|---|---|---|---|
| Usage | `/api/v1/finance/usage/daily`, `/usage/monthly`, `/usage/forecast` | GET | `analytics:read-organization` |
| Accounting periods | `/api/v1/finance/periods`, `/periods/{id}/history`, `/periods/{id}/adjustments`, `/periods/{id}/close-readiness` | GET | `analytics:read-organization` |
| Rate cards | `/api/v1/finance/rate-cards`, `/rate-cards/{id}/versions` | GET | `analytics:read-organization` |
| Reconciliation | `/api/v1/finance/reconciliation/runs`, `/runs/{runId}/lines`, `/legacy/comparison` | GET | `analytics:read-organization` |
| Exports | `/api/v1/exports`, `/queue`, `/catalogue`, `/download-policy`, `/{id}`, `/{id}/attempts`, `/{id}/downloads`, `/{id}/deletions`, `/finance-gate` | GET | `analytics:read-project`, and `analytics:read-organization` for `/finance-gate` |
| Download and retention policy | `/api/v1/exports/download-policy` | GET | `analytics:read-project` |

**One permission governs all of finance, and the client gates the section on it.**
`FinanceController` declares `@RequirePermission(ANALYTICS_READ_ORGANIZATION)` on
**every one of its twenty-four routes**, and its javadoc is explicit that this is the
intent: *"There is no weaker permission for 'what does this cost'."* Its
`FinanceRoutePermissionTest` asserts by reflection that no handler is left unguarded.
So there is no per-card subsetting to do, and no finer distinction available — inventing
one would produce a screen that hides data from people who may see it.

Exports split two ways and the two values are checked **independently**, so a
project-scoped session sees the export list and is told plainly that the organization
gate is not its to read, rather than receiving a 403 that reads as "the exports are
broken".

Not offered: the eleven finance writes (compute a rollup, create/review/return/lock/
reopen a period, create/approve/reject/apply an adjustment, run a reconciliation) and
the export lifecycle (create, retry, cancel, download-link, delete artefact, retention
expire/purge). The finance service additionally requires an `ADMIN` role, so these are
finance-administrator acts. A journey asserts the screen issues **no write at all**,
and that no `retry|cancel|download|approve|apply|lock|reopen` button exists on it.

**Why creating an export is not offered — Hook 14.** `CreateExportRequest` requires
`reportDefinitionId`, `reportVersion`, `documentTemplateId` and
`documentTemplateVersion`, and **no route lists report definitions or document
templates**. A picker would have nothing to pick from, so a create form could only be
filled by typing UUIDs from a document. The endpoint layer offers no such method at
all, asserted by a test, because an absence is exactly the kind of thing that gets
"helpfully" added back.

### Clinic (`/app/clinic`)

**There is no clinic route in the committed document.** No appointments list, no
doctors, no procedures, no reminders, no AI receptionist. `AppointmentSummary` exists
as a nested type and appears in exactly one response — inside a customer record —
which describes appointments held *about a customer*, not a schedule anyone runs a
clinic from.

So the screen has the route structure, the navigation, the states and the i18n, and
where a surface has no route it shows an **explicit not-available state**. Nothing is
invented: no appointment time, no doctor, no "today's list", and a unit test asserts no
clinic string contains a digit outside a quoted route path, which is the cheapest
detector of a filled-in placeholder.

| Subject | Route it waits for |
|---|---|
| Appointment schedule | `GET /api/v1/appointments` |
| Doctors | `GET /api/v1/doctors` |
| Procedures | `GET /api/v1/procedures` |
| Reminders | `GET /api/v1/appointment-reminders` |
| Reception | `GET /api/v1/clinic/receptionists` |

The screen also points at **the one place appointments do exist today** —
`GET /api/v1/customers/{customerId}` returns an `AppointmentSummary` inside the
customer record — and links there. Saying where is more useful than repeating that
there is nowhere.

**What will light up when the routes land:** nothing needs rebuilding in this client.
Every i18n key for all five subjects is already written in both languages, the routes
are named on screen so the contract is unambiguous, and the code already calls the
paths. The change is deleting the `fe-not-available` elements and pointing the row
readers at the responses — which is a diff a reviewer can see rather than a rewrite.

## Backend hooks needed (findings, not workarounds)

**Hook 1 — there is no analytics read model.** `PROJECT_REQUIREMENTS` §6 lists
`/analytics` and `/dashboards` as required first-release families. Neither exists
in the committed document, and no `/clinic` route exists either. The frozen
`DashboardPage` read `/clinic/analytics?days=30` and rendered hard-coded fallbacks
for every KPI when it failed — shipping those fallbacks would be the "mock that
ships" that hard rule 8 forbids.

So the delivered dashboard is assembled from the read models that do exist and
that answer an operations question: the unassigned queue, the realtime state, and
the admin overview. **The booking funnel, top-procedures and clinician-workload
cards from `docs/migration-AGENT.md` §4.6 are therefore absent, and the
five-step clinic funnel strip is replaced by a queue-state funnel over
`totals.byStatus`.** This is a deliberate omission, asserted by
`api-contract.spec.ts` ("declares no analytics read model") so a future addition
has to update the dashboard rather than quietly leave it wrong.

**Hook 2 — `/forgot-password` does not exist. Recommendation: remove the link.**

The sign-in screen carried a "Forgot password?" link inherited from the frozen
`LoginPage`. There is no route in `app.routes.ts` and no password-reset endpoint in the
contract, so it resolved to the not-found screen — a sign-in page whose recovery path
lands on "not found", which is worse than having no recovery path at all: it tells the
user the capability exists and then refuses it. The link has been removed; leaving the
screen out entirely is the same decision without the broken promise. **I have not
added a password-reset capability**, since FR-6/FR-7 do not ask for one — if the
orchestrator wants it, it is a backend mission first and a route second.

*Decision taken unilaterally within my globs and recorded here for reversal: the link
is gone from `login.component.html` and its label from the dictionary. Reinstating it
is a two-line change if the backend lands the endpoint.*

**Hook 3 — the inbox has no "unassigned" saved view, so a new conversation is
invisible in the conversation list.** `GET /api/v1/conversations?filter=` accepts
exactly `assigned-to-me` and `history` and **defaults to `assigned-to-me`**;
anything else is `500 filter must be assigned-to-me or history`. Both views
require involvement: `status in (OPEN, PENDING)` and
`(employees.userId = caller or conversations.lastReplierUserId = caller)`.

A brand-new inbound message satisfies none of that. Verified against a live
tenant: after `POST /api/v1/dev/mock/incoming-message` created a conversation,
`GET /api/v1/inbox/unassigned` listed it, `GET /api/v1/inbox/counts` reported
`assigned-to-me: 0`, and `GET /api/v1/conversations?filter=assigned-to-me`
returned `{"items":[]}`. It only appeared after a human reply set
`lastReplierUserId`.

So the primary work queue — new conversations nobody owns — is reachable **only**
through `/api/v1/inbox/unassigned`, and a supervisor cannot find or assign it from
the list. The screen therefore shows the queue as its own region and states the
relationship in words, and it sends only the two filter values the server
accepts (pinned by a journey that asserts on the request URL, so a future
three-segment control cannot reintroduce the 500).

*The filter vocabulary the backend needs, so the contract is right first time.*
Today `ConversationsController.parseFilter` delegates to `InboxFilter.fromValue(raw)`
over a two-value enum, and `GET /api/v1/inbox/counts` publishes exactly those two
keys — I verified both against the running API (`{"assigned-to-me":0,"history":0}`).
Widening the enum **without widening the counts response** would give the segmented
control tabs whose counts are permanently zero, so the two must land together.

| Wire value | Meaning | `status` | Involvement | Needed for |
|---|---|---|---|---|
| `assigned-to-me` | open, mine | OPEN, PENDING | assignee or last replier | exists |
| `history` | closed, mine | RESOLVED, CLOSED | assignee or last replier | exists |
| `unassigned` | **open, nobody owns** | OPEN, PENDING | **none** | **the work queue** |
| `all-open` | **open, anyone's** | OPEN, PENDING | none | a supervisor's whole load |
| `assigned-to-others` | open, owned by someone else | OPEN, PENDING | not the caller | load-balancing |

**`unassigned` is the one that matters** — it is the list an operator actually works
from, and today it is a separate route rather than a saved view. `all-open` is what a
supervisor needs and is genuinely absent: without it there is no way to see everything
open in a tenant. `assigned-to-others` I would treat as optional; if the counts
response has to grow anyway, it is nearly free.

Two client-side notes the backend should not have to discover:

- The screen's queue region already reads `/api/v1/inbox/unassigned`, so once
  `unassigned` exists the two should be reconciled server-side into **one** answer.
  Two routes returning the same set invites the client to render them inconsistently.
- `filter` is declared as a bare `type: string` with **no enum** — which is how a
  client ended up sending a value the server 500s on. It should be an `enum` of the
  values above, so a generated client cannot send an invalid one and CI can catch the
  drift.

*Hook: `InboxFilter` / `ConversationsController.parseFilter` / `InboxCountsController`.*

**Hook 4 — assignment points at `Employee`, and the seed creates no employee
row.** The involvement test joins `employees e on e."userId" = c."assignedEmployeeId"`.
A user with no `employees` row can therefore never satisfy
`e."userId" = :inboxUserId`, so "assigned to me" matches only the
`lastReplierUserId` half. Whether that is intended depends on whether every
account is expected to have an employee record; there is no route to create one
from the admin surface (`employee:create` exists as a permission but
`/api/v1/employees` does not exist as a route).

**Hook 5 — the E2E suite needs the inbound simulator, and that needs two gates.**
The journeys drive a real backend and several properties under test (the thread,
the composer, mode takeover, close/reopen, the resolution, the collision check)
need a conversation that really exists. They create one with
`POST /api/v1/dev/mock/incoming-message` — the backend's **own** development
inbound simulator, which is in the committed contract and drives the real
pipeline rather than inserting a row. The application never calls it; only
`e2e/inbound.ts` does.

It answers the same `404` as a route that does not exist unless **both** hold:

1. `firstelement.inbox.mock-connector-enabled=true` — start the API with
   `--firstelement.inbox.mock-connector-enabled=true` **after** the jar path.
   *Hook: `InboxProperties.mockConnectorEnabled`.*
2. An **enabled MOCK channel connection** in the target project —
   `MockChannelProbe.hasEnabledMockConnection`. `e2e/seed.ts` writes one
   (`MOCK`, enabled, `CONNECTED`, no credentials), so this needs no backend
   change once the flag is set.

The gate is asserted, not assumed: a missing flag fails in `e2e/inbound.ts` with
the exact argument to add, rather than as a `404` twenty minutes into a run.

**Hook 6 — no employee, working-hours, routing, skills, capacity or import
route.** `GET /api/v1/admin/identity` is an *administration* directory: users,
roles, memberships, live sessions, observed principals, credential presence. It
is what the Team screen reads. Working hours, availability, skills, capacity and
CSV/XLSX employee import have **no read model at all** — `routing_rules`,
`sla_calendars` and `employees` tables exist in the schema but no route reads or
writes them.

So no import drop area is rendered. A file picker that submits to a `404` is a
promise the product cannot keep, and a file picker that silently discards a staff
list is worse than no control. The absence is stated in both languages, naming
the capability that is missing. `navigation.spec.ts` asserts every placeholder
entry really has no route, so this cannot silently become a link to nothing.

**Hook 7 — no document, knowledge-base, agent, prompt or run route.** Knowledge is
a *policy and health* surface: `FreshnessView` and `KnowledgeGapView` are both
`{ document: {...} }` free-form documents, so every field is read defensively and
an absent field renders as `—` rather than as zero. `/api/v1/admin/ai/*` is a real
six-read policy surface. Neither lets you manage a document, a knowledge base, an
agent, a prompt or a run. Ingestion, chunking, agent configuration and run
history are not offered; the gaps are stated.

**Hook 8 — escalation is a field, not a queue.** `SaveResolutionRequest`
carries `escalationRequested` and `escalatedToSupervisor`. There is no escalation
list, no acknowledge and no supervisor view; `escalations` exists as a *table*
keyed by `conversationId`, and no route reads it. The inbox's resolution form
records both fields, and the Knowledge screen points at where they live rather
than drawing an empty queue that would read as "nothing has been escalated".

**Hook 9 — `GET /api/v1/channels/connections` is uncallable, and this one is
worked around on purpose.** The route declares a **required** `context` query
parameter typed `TenantContext`. No client can serialise that object onto a query
string, and `TenantQueryGuard` resolves the context from the session regardless,
so calling it produces a `400` that says nothing about the channels. The screen
therefore **never issues the request** and states that connection health is
unavailable, with the cause, so an operator is not shown an empty table that reads
as "no channels configured". This is the one place in groups 1-8 where a contract
defect is worked around, and the workaround is stated rather than hidden — a
journey asserts the request is never made, so it cannot quietly become a real call
that happens to fail.

**Hook 10 — RESOLVED, and my earlier report of it was wrong.** I previously wrote that
"no permission in the enum grants finance or export access", on the reasoning that an
administrator's granted set of **67 values** contains nothing beginning `finance` or
`export`, and marked both navigation items unavailable to everyone rather than
substituting a permission that would be a different lie.

That reasoning was half a conclusion, and the half I got wrong was the consequential
one. The enum has no member *named after our navigation* — but it has the members the
**backend actually gates these routes on**, named after what they authorize. Read
straight off the controllers:

| Controller | Permission | Routes |
|---|---|---|
| `FinanceController` | `ANALYTICS_READ_ORGANIZATION` | **all 24**, writes included |
| `ExportController` | `ANALYTICS_READ_PROJECT` | 17 |
| `ExportController` | `ORGANIZATION_UPDATE` | artefact delete, retention expire/purge |
| `ExportController` (`/finance-gate`) | `ANALYTICS_READ_ORGANIZATION` | 1 |
| `AdminReadModelController` (`/exports/jobs`) | `ANALYTICS_READ_ORGANIZATION` | 2 |

So `analytics:read-organization` and `analytics:read-project` both exist, both are in
the administrator's 67, and both are already enforced. **No new permission is needed**
for any of groups 9-12. The fix was mine, in the client: two nav entries and two route
gates now use the values the backend enforces, and `shell.component.spec.ts` asserts
the new behaviour (finance and exports hidden from an employee holding only
`conversation:read`).

Worth recording *how* it went wrong, because the failure mode is not carelessness. The
check I ran was "does a permission with this name exist?", and the answer was correctly
no. The check I should have run was "what does the controller guarding this route
require?", and I never ran it until Batch B — when it took two minutes and produced the
opposite conclusion. **A permission gap must be established from the route's
`@RequirePermission`, not from a search of the enum's names.**

The one part of Hook 10 that stands is the reason it was easy to get wrong:
`finance:aggregate` and `export:write` were invented during group 2 and returned
`false` for every user, hiding both navigation items — the silent failure
`core/auth/permissions.ts` exists to prevent. Inventing a name is the error; concluding
from a name search that no name exists is the same error one step later.

**Hook 13 — the finance and export *writes* have no UI, and the contract does not say
why they should.** Eleven finance writes (compute a rollup, create/review/return/lock/
reopen a period, create/approve/reject/apply an adjustment, run a reconciliation) and
the export lifecycle (create, retry, cancel, download-link, delete artefact, retention
expire/purge). Two separate reasons they are not rendered as buttons, and both are
client-side decisions I can defend but only the orchestrator can ratify:

1. **The finance writes are administrator acts.** `FinanceController`'s javadoc says
   the service layer *additionally* requires an `ADMIN` role, so the permission is the
   declaration and the role is the enforcement. A permission-holding non-admin would
   get a 403 the UI can explain — so rendering the control is defensible — but hiding it
   behind the wrong condition would be a lie about who may act.
2. **The export writes carry their own consequences.** `DELETE /exports/{id}/artefact`
   and `POST /exports/retention/expire|purge` are under `ORGANIZATION_UPDATE`, the
   broadest write in the system, and are reused across unrelated destructive acts
   (legal holds, privacy deletions, purge). FR-5.9 asks for governance decisions to be
   explicit commands; a one-click artefact delete in a list is the opposite.

**What I need from the orchestrator:** confirm that a deliberate, confirmed command
dialogue is wanted (I would build it with a typed confirmation per action, an
`Idempotency-Key`, and its own audit journey), or confirm that these remain
out-of-band operator actions. I have not invented either.

**Hook 14 — an export cannot be created from the UI, because nothing lists what one is
made of.** `CreateExportRequest` requires `reportDefinitionId`, `reportVersion`,
`documentTemplateId` and `documentTemplateVersion`. **No route in the committed document
lists report definitions or document templates** — I checked every path for
`report`, `template`, `view` and `definition` and found only
`/admin/governance/maintenance/privacy-deletions/preview` and the finance
`review`/`return-to-review` pair.

So `POST /api/v1/exports` is callable by a client that already knows four identifiers,
and by nobody else. Either add a catalogue route for report definitions and document
templates (the existing `GET /api/v1/exports/catalogue` returns
`{type: object, additionalProperties: {}}`, which declares no shape and so cannot serve
as one), or accept that exports are created elsewhere. The client states the gap and
names the missing routes rather than offering a form whose fields have no source.

**Hook 16 — `POST /api/v1/conversations/{id}/reopen` is broken for every caller. It
answers 500 unconditionally.** This is a backend defect, and it is the reason the
close-and-reopen journey could not pass in batch A. Reproduced directly against the
running API, with the server's own log line:

```
POST /api/v1/conversations/<uuid>/close    → 200  status=CLOSED
POST /api/v1/conversations/<uuid>/reopen   → 500  INTERNAL_ERROR

org.springframework.dao.InvalidDataAccessApiUsageException:
  No value supplied for the SQL parameter 'conversationId':
  No value registered for key 'conversationId'
```

`ConversationRepository.clearResolvedAt` calls `conditionalUpdate(scope, conversationId,
expectedUpdatedAt, assignments, new LinkedHashMap<>(Map.of("now", now)))` — and
`conditionalUpdate` writes `expectedUpdatedAt` into that map but **never writes
`conversationId`**, while its own predicate is `c."id" = :conversationId`. The scope
applies the tenant keys; the conversation key is simply never bound.

So the parameter is unbound on every call that routes through this path, and `reopen`
has no working path at all. Note the sibling methods in the same class pass
`conversationId` in their parameter maps — the omission is specific to the overloads that
receive a caller-built map.

*Hook: `ConversationRepository.clearResolvedAt` / `conditionalUpdate` (both overloads).*
Fix: `parameters.put("conversationId", conversationId)` alongside
`parameters.put("expectedUpdatedAt", expectedUpdatedAt)`.

**Why the journey does not work around it.** The client sends the reopen and reports the
500 as an error, which is the correct behaviour for a broken route; it does not silently
mutate state to simulate a reopen, and it does not skip the assertion. The journey is
expected to go green the moment this is fixed — no client change is needed for it, which
is stated here so the reviewer knows the failure is the backend's and not a weakened test.

**What the client does add, and why.** `reportConflict` previously mapped anything that
was not a 409/412/403 to a generic "this page could not be loaded", which is how this
went unnoticed for a whole batch. A 500 now reports as a failure **with its correlation
id**, which is what makes "something went wrong" answerable — the id is what ties the
operator's report to the server's log line above.

**Hook 17 — an unknown `filter` is refused as a 500, not the 400 ADR 0015 specifies.**
Reproduced against the running API:

```
GET /api/v1/conversations?projectId=<p>&filter=bogus
→ 500 {"code":"INTERNAL_ERROR","message":"Unexpected server error"}
  server: ValidationFailedException: filter must be one of
          [unassigned, all-open, assigned-to-others, assigned-to-me, history]
```

`ConversationsController.parseFilter` throws `ValidationFailedException`, which extends
`CrmDomainException` — and `ErrorHandlingAdvice` maps only
`MethodArgumentNotValidException`, `NoResourceFoundException`,
`HttpRequestMethodNotSupportedException` and `IllegalArgumentException`. So the
domain's own validation exception falls through to the catch-all
`@ExceptionHandler(Exception.class)` and is reported as an internal error.

**Every domain validation failure in the system has this shape**, not just this one: it is
the same exception `CaseService.validateResolution` throws, and the same reason the
resolution form's 400 arrived as a 500 during this batch. One handler for
`CrmDomainException` → `400 VALIDATION_FAILED` would fix the whole family.

*Hook: `ErrorHandlingAdvice` (add a `CrmDomainException` handler).* The client treats any
non-2xx as an error state, so this does not change what it renders — but a client cannot
tell "you asked for something that does not exist" from "the server is broken" without the
status code, and neither can an operator.

**Hook 18 — a missing required query parameter is also reported as 500.** The same
handler gap, reached by a different route. `GET /api/v1/exports/finance-gate` declares
**three** required parameters — `projectId`, `from`, `to` — and the previous version of
this screen sent only `projectId`:

```
MissingServletRequestParameterException: Required request parameter 'projectId'
for method parameter type UUID is not present      → 500
```

`MissingServletRequestParameterException` is not mapped either. This one **was** a client
defect (the screen should have sent all three, and now does), and it is fixed; the
reporting is still wrong, and it will bite the next client the same way.

**Hook 19 — the export catalogue is empty on every fresh database, so an export still
cannot be created.** The contract now declares `ExportCatalogue` properly and
`POST /api/v1/exports` is callable — but:

- migration `V20261005060000__report_definitions.sql` **creates**
  `report_definitions` and `document_templates` and **inserts no rows**. Verified
  against a freshly migrated scratch database: `select count(*)` is `0` on both.
- **no route creates them.** Every path matching `report-definition`, `document-template`
  or `reportDefinition` in the committed document returns nothing.

So the create form is wired, reachable and correctly shaped — and has nothing to offer.
The client says so in words, renders the catalogue's own `note` verbatim (including its
statement that *"a CSV export needs no template"*), and leaves the four identity fields
unset. **Nothing can be posted, and the browser journeys assert exactly that rather than
asserting a creation that cannot happen.** This is Hook 14's remaining half: ADR 0016
fixed the *declaration*, not the *data*.

*Hook: seed `report_definitions` and `document_templates`, or add a route that does.*

**Hook 20 — `ExportCatalogue.formats` and `.locales` are bare `Array<string>` while
`CreateExportRequest.format` and `.locale` are closed enums.** The catalogue can
therefore declare a format this client cannot send. The client narrows them explicitly
(`asFormat`/`asLocale`) and drops anything unrecognised rather than passing it through,
because the alternative is a 422 the operator cannot act on. Making the catalogue's own
arrays enums would remove the mismatch at the source.

**Hook 11 — RESOLVED in `5442bd8` (ADR 0014). It was the most serious defect found in
Phase 11, and it blocked the six-combination browser gate. The analysis is kept in full
below because the fix's shape is the point.**

`IdentityResolver` allocates a customer reference like this:

```java
for (int attempt = 0; attempt < MAX_IDENTITY_ATTEMPTS /* 5 */; attempt++) {
    long sequence = customers.nextCxSequence(scope) + attempt;
    String cxId = TicketNumber.generateCxId(sequence);   // "#33" + 6 digits
    if (customers.cxIdExists(cxId)) continue;
    ... customers.insert(...)                            // DuplicateKeyException -> continue
}
throw new IllegalStateException("Could not allocate a customer reference after 5 attempts");
```

Three facts collide:

1. `CustomerRepository.nextCxSequence` returns `count(*)` **with the tenant scope
   applied**, and `CrmQueryScope.forProject` includes `projectId` — so every project
   starts its own count at 0 and its first candidate is `#33000000`.
2. `CustomerRepository.cxIdExists` queries `select count(*) from customers where
   "cxId" = :cxId` with **no scope at all** — so a value another project holds reads
   as taken.
3. The index is `customers_cxId_key UNIQUE (cxId)` — **globally** unique, not per
   organization.

So project *k* has to walk past the *k-1* values earlier projects already hold, and
the loop only walks five. **At most five projects in a deployment can ever receive
their first customer**; the sixth answers
`500 INTERNAL_ERROR {"message":"Unexpected server error"}` for every inbound message,
permanently, and the exception it logs is `Could not allocate a customer reference
after 5 attempts`.

Reproduced deterministically against the running API: six inbound messages into a
database where five projects hold `#33000000`–`#33000004`. The sixth project had
**zero** customers and still failed, six attempts out of six, with `count(*)` = 0 in
its own scope. `e2e/inbound.ts` now raises this as a named `BackendLimitation` rather
than a bare 500, so it arrives as a finding rather than as a mystery in a log.

Two independent fixes, either of which resolves it:

- **Scope the existence check** to the same scope the sequence comes from — necessary
  but *not sufficient*, because the insert would then still collide with another
  project's row and be caught by the same five-attempt bound.
- **Make the sequence and the index agree.** Either the index becomes
  `(organizationId, cxId)` with a monotonic per-organization sequence, or the
  sequence becomes global and the index stays as it is. What cannot stand is a
  *per-project count* feeding a *globally unique* column.

`count(*)-as-sequence` is fragile on its own besides: any deletion — a privacy
deletion, a retention purge, a cascade from a project delete — permanently reopens
gaps below the high-water mark. A real sequence column is the durable fix.

**Effect on the acceptance gate, at the time.** Each of the six matrix combinations is
a separate project and each needs a conversation that really exists, so a full run
needs six customer allocations in one database. Five succeeded; the sixth could not.
The journeys were **not** skipped and no assertion was weakened — they ran and reported
the backend limitation, and batch A was reported BLOCKED on this rather than PASS.

**RESOLVED.** `5442bd8` shipped the second of the two fixes above: a per-organization
monotonic allocator. Verified against the running API before spending 36 minutes on a
matrix:

- both new migrations are in the jar
  (`V20261007100000__customer_cx_sequences`, `V20261007110000__customers_cx_id_tenant_scope`);
- the index is now `customers_organizationId_cxId_key UNIQUE ("organizationId", "cxId")`
  — the old `customers_cxId_key` is gone;
- six inbound messages, one per matrix project, all returned `conversationCreated: true`;
- all six tenants hold `#33000000`, which is **correct** for a per-organization sequence
  and impossible under the old globally-unique index.

The re-run then passed all 300 of batch A's journeys. `e2e/inbound.ts` keeps the named
`BackendLimitation` raise: it is a better error message for a future recurrence, and it
is what turns "six mysterious 500s in the log" into a filed finding.

## Findings against `tests/contracts/openapi.json`

Five, all real, all asserted as tests in `api-contract.spec.ts` so a backend fix
turns them red rather than leaving a comment that has quietly stopped being true.
**None is worked around in the client.**

1. **`GET /api/v1/auth/csrf` declares a required `token` query parameter typed as
   `CsrfToken`.** springdoc renders the controller's resolved `CsrfToken` argument
   as a required query parameter, so no conformant client can call the route: it
   would have to send the token it is asking for, as `?token={...}`. A generated
   client will also demand an argument the UI has no value for. Fix: hide the
   argument (`@Parameter(hidden = true)`). *Hook: `AuthenticationController.csrf`.*

2. **No operation documents a single 4xx or 5xx response.** All 142 operations
   document `200` only, while `ErrorHandlingAdvice` really does answer
   `400 VALIDATION_FAILED`, `401 AUTHENTICATION_REQUIRED`, `403 ACCESS_DENIED`,
   `403 CSRF_TOKEN_INVALID`, `404 NOT_FOUND`, `405 METHOD_NOT_ALLOWED`,
   `429 LOGIN_RATE_LIMITED` and `500 INTERNAL_ERROR`. `ErrorResponse` exists as a
   schema but is referenced by no response. The whole error interceptor is
   written against codes the document does not declare, and a generated SDK could
   not distinguish a failure from an empty success.

3. **The rate limiter sends no `Retry-After`.** `LoginRateLimiter.Decision`
   computes `retryAfterSeconds` and publishes it into the security event, but the
   429 response carries neither the header nor the value in `details`. A client
   cannot tell the user how long to wait. *Hook: `LoginService` /
   `RateLimitedAuthenticationException` handling.*

4. **`limit` is typed `string` on `/inbox/unassigned`, `/conversations` and
   `/customers`,** while six other paginated routes type it
   `{"type":"integer","format":"int32"}`. The controllers parse a string
   (`RequestParams.limit`), so the document describes the intermediate. The client
   types `limit` as `number` and stringifies it, which is right, but the generated
   type and the call site then disagree. *Hook: `RequestParams`.*

5. **139 responses use the `*/*` media type and 33 use `application/json`.** A
   generated client cannot tell a JSON body from an event stream from a download
   by media type alone — which is exactly how `text/event-stream` has to be
   recognised for the realtime stream. Fix: `application/json` for the JSON ones,
   explicit types for the stream and any binary download.

## Findings that are not contract defects

- **The login rate limiter is hostile to a browser suite, and the address bucket is
  the one that bites.** 10 attempts per 60s per address, and only the *account*
  bucket is cleared on success — the address bucket deliberately is not, so a
  legitimate client cannot reset a host-level spray window.

  Giving each matrix combination its own **account** (`e2e/matrix.ts`) removes the
  account bucket entirely and was adopted. It is **not sufficient on its own**,
  and reporting it as sufficient was wrong twice: a run reported 150 passed where
  the orchestrator measured 149, and the later 300-journey run failed *every*
  `en-mobile` journey — the project that runs last, after the other five had spent
  the window. Every browser request reaches the API through the one dev-server
  proxy, so all six combinations share a single source address by construction.

  The suite now **paces sign-ins below the limit** (`e2e/signin-gate.ts`): a
  process-wide gate guarantees at least 6500 ms between any two attempts, so no
  60-second window contains more than 9 against a limit of 10. That is a
  guarantee, not a sleep — a fixed sleep before each sign-in would not compose with
  the time a test had already spent, so two attempts could still land close
  together. The production limit is unchanged, nothing is skipped, and the
  alternative of forging a distinct source address per combination was rejected
  because it defeats the very control under test.

  Cost: about 33 of the run's 38 minutes are spent in that gate. That is the
  honest price of not depending on where in the window a run happens to land.
- **`GET /api/v1/conversations/{id}/resolution` answers `204` when no resolution
  exists,** not `200` with a null body. The client treats it as `null` and says "no
  resolution has been recorded", which is right. Worth knowing because a generated
  client that declares only `200` will treat the empty response as a decode failure.
- **The CSRF token rotates on every successful unsafe request.** The server
  rewrites `XSRF-TOKEN` each time, so a token captured before the previous
  mutation is refused. The application reads the cookie per request rather than
  caching it; anything driving the API outside `HttpClient` has to do the same —
  including the E2E fixture, which had to read the cookie from `document.cookie`
  at the instant of the call because the page is live in the same jar.
  This is correct and load-bearing, but it is not documented anywhere the next
  person will look.
- **A long-running API instance stopped answering unsafe methods.** After roughly
  two hours serving, `POST`/`PUT`/`DELETE` on port 4000 hung indefinitely while
  `GET` remained instant; Tomcat's worker threads were all idle in
  `TaskQueue.take`, and a thread dump showed nothing blocked. A fresh instance of
  the same jar against the same database behaved correctly. **Not diagnosed and
  not reproducible on demand** — worth knowing that it happened, because a hung
  mutation looks exactly like a client bug.
- **Revoking a session leaves the open SSE stream logging
  `AuthenticationCredentialsNotFoundException` every few seconds** until the
  browser gives up. That is the expiry journey doing its job, but it produces a
  large volume of ERROR lines in the API log that are not failures.
- **`global.setup.ts` was never running.** It was registered as a Playwright
  **project** (`testMatch: /global\.setup\.ts/`) but exports a `default async
  function` and declares no `test()`, so it matched zero tests and the function was
  never called. The suite had been depending on an operator running
  `npm run e2e:seed` by hand. It is now Playwright's `globalSetup` hook, which runs
  exactly once and aborts the run on a throw — and it **tears the previous tenants
  down before seeding**, so a run no longer inherits conversations that an earlier
  journey closed or assigned.

## Permissions needed, by screen

**None.** Every permission this batch uses already exists in the backend's `Permission`
enum and is already enforced on the routes — Hook 10 was wrong and is corrected above.
The values the new screens gate on:

| Screen / subject | Permission | Why this one |
|---|---|---|
| `/app/admin` route gate | `system-health:read` | what `/admin/overview` itself declares |
| Admin — audit tab | `audit-log:read` | `/admin/audit/events` |
| Admin — governance tab | `security-event:read` | `/admin/governance/*` reads |
| Admin — sessions tab | `session:revoke` | `/admin/identity/sessions` |
| Admin — export jobs tab | `analytics:read-organization` | `/admin/exports/jobs` is organization-wide |
| `/app/finance` route gate, and every finance tab | `analytics:read-organization` | `FinanceController` declares it on **all 24** routes, deliberately |
| Finance — exports tab | `analytics:read-project` | 17 export routes; the weaker scope |
| Finance — the gate inside it | `analytics:read-organization` | `/exports/finance-gate` is the one exception |
| `/app/clinic` route gate | `customer:read` | the only clinic-adjacent permission that reaches something real |

## Open items for the orchestrator

1. **Hook 13 — are the withheld writes wanted as confirmed commands?** Eleven finance
   writes and the export lifecycle. I have built them as none, asserted that the screens
   issue no write, and named them on screen. Confirm or overrule; the work is a
   typed-confirmation dialogue with an `Idempotency-Key` and its own audit journey.
2. **Hook 14 — can an export be created at all?** Nothing lists a report definition or a
   document template, so `POST /api/v1/exports` is callable only by a client that
   already knows four identifiers. Add a catalogue route, or accept that exports are
   created outside the application.
3. **`POST /api/v1/conversations/{id}/reopen` is broken — Hook 16, and it is the only
   red journey.** One line in `ConversationRepository.conditionalUpdate`: bind
   `:conversationId`. No client change is needed; the journey goes green as soon as it
   lands. **This is the one thing blocking acceptance.**
4. **`GET /api/v1/exports/catalogue` is declared with no shape** (Hook 15) —
   `{type: object, additionalProperties: {}}`. Even the weaker
   `{ document: {...} }` convention used elsewhere would be an improvement, because a
   generated client currently cannot tell an empty catalogue from a differently-shaped
   one.
5. **ADR 0013 has three damaged em dashes** at lines 146, 149 and 154. Outside my
   globs, so reported and not fixed.
6. **`/forgot-password`** — decided and recorded under Hook 2: the link is removed.

## Open item for the orchestrator — generated-client strategy (unchanged since batch A)

The generated client is produced from the Spring OpenAPI document on the
**management port** (ADR 0005 §8). Decided here: **generate on demand, check in
CI.** `npm run generate:api:check` regenerates into a scratch directory and
compares by content hash, failing on drift.

The obvious implementation — `git status --porcelain` over the generated
directory — is wrong in a way that fails silently. It reports a path as modified
because of line-ending normalisation or a `.gitattributes` rule, and it reports
*nothing at all* for a file that is untracked and unignored, so in a fresh
workspace the check passes against a stale client. Content comparison is the only
version of this gate that means what it says.

Generation is also non-destructive: it builds beside the committed tree and swaps
afterwards, so a dev server serving during a regeneration cannot report
`TS2307: Cannot find module './generated'` to the browser. That is not
hypothetical — it is how the first full-matrix run failed.

## Batch C — ADR 0015 (inbox work queue) and ADR 0016 (creatable exports)

### The five saved views

`filter` is now an enum with five values and the tab order is read from the counts
response's own key order, which is declaration order:
`unassigned`, `all-open`, `assigned-to-others`, `assigned-to-me`, `history`. The client
defaults the control to **`unassigned`**, which is both the server's default and the
answer to "what does an operator need first".

The segment identity **is** the wire value. The previous control carried `'mine'` and
mapped it onto `assigned-to-me`, which is precisely the indirection that let a stale name
become a 500; a key built by translation is a key that can silently miss.

### The five views overlap, and nothing is summed

The counts response declares itself: *"The views OVERLAP and are not a partition:
`assigned-to-me` also holds released threads the caller last replied to, so the counts do
not sum to the number of open conversations and a client must not present them as
mutually exclusive buckets."* So there is **no total anywhere on the screen** — asserted
negatively by a journey that fails if one appears, and by two absent test ids.

Each count is read from its own response key verbatim and formatted with the locale's
numbering system. Each view has its own empty-state sentence, because "nothing here"
means something different in each: nobody is waiting, you have nothing on your plate,
the tenant has no open work at all, somebody else owns it all, nothing has been closed.

### One question, once

`GET /api/v1/inbox/unassigned` and `GET /api/v1/conversations?filter=unassigned` return
the same set, so the standalone queue region is **hidden while the `unassigned` view is
selected** and the list carries the answer. Two renderings of one question that can
disagree with each other is worse than either alone. Asserted both ways.

### Export creation

`GET /api/v1/exports/catalogue` is a real `$ref` to `ExportCatalogue` and **requires**
`projectId`, so the endpoint takes a required argument and the section is not offered
until a project is chosen. The create form posts the four identity values from the
catalogue and nothing hand-typed.

**It cannot be exercised, because the catalogue is empty — Hook 19.** The journeys assert
the real state: the catalogue's formats, locales and filter count as published data, its
`note` verbatim, the empty state in words, the create form present and labelled, and no
option anywhere offering `XLSX`, `PDF` or `JSON`.

### Four product defects this batch found

1. **`GET /exports/finance-gate` needs three required parameters** and the screen sent
   one, so the gate 500'd on every load. Now sends `projectId`, `from`, `to`, with the
   window computed from the clock.
2. **Switching project never reloaded the finance screen.** Every figure — revenue, cost,
   margin, periods, rate cards, exports — kept describing the **previous** project with
   nothing on screen to say so. On a money screen that is the worst kind of wrong. Fixed
   with a `takeUntilDestroyed` subscription on the selected project.
3. **`[ngValue]` on a string `<option>`** serialises through Angular's
   select-control-value pipe and produces `0: CSV`, which is not a wire value. Format and
   locale selects use `[value]`.
4. **The catalogue's `[empty]` gate swallowed the document.** An empty *list* is not an
   empty *response*: the catalogue carries formats, locales, defaults, subjects, 34
   fields and a note even with zero definitions, and gating on
   `reportDefinitions.length === 0` replaced all of it with three sentences and left
   "Loading" on screen forever beside an empty form.

### Harness note

The e2e copy table is written from PowerShell, which decodes it as cp1252 and loses every
Arabic literal while reporting success. A lost literal makes a locator match nothing and
the failure reads as "the screen did not render". The Arabic half is therefore repaired
from the dictionaries by a one-shot script, verified entry by entry.
## The dead-button bug (was reported as a flaky journey)

### What was reported

`closes a conversation and reopens it` failed on `ar-tablet` and passed on the other five
combinations. My first diagnosis - offered as a race - was **wrong**, and so were all four
hypotheses the orchestrator proposed.

### What it actually was

`POST /api/v1/conversations/{id}/close` answers:

```
200 {"status":"CLOSED"}          <- nineteen bytes. No id, no version, no messages.
```

The contract never claimed otherwise - it declares this response as
`{"type":"object","additionalProperties":{"type":"string"}}`, a free-form string map. But
`ConversationApi.close()` was declared `Promise<Api.ConversationDetail>`, and the component
then did:

```ts
this.thread.set(updated);   // the WHOLE thread, replaced by {"status":"CLOSED"}
```

Everything still rendered, because a non-null object is still an object: the pane, the case
actions, the Reopen button. But every action begins
`const thread = this.requireThread(); if (!thread) return;`, and `requireThread()` needs
`detail.id` - which the replacement had just destroyed. So **Reopen, take ownership, mode
change, close again and reply all returned at their first line**: no request, no
announcement, no error. An operator pressed a button and nothing happened, on a thread that
looked entirely normal.

It was never a race. It reproduced **five times out of five**; a realtime frame landing
first and refetching a whole thread is what occasionally masked it, which is why it looked
intermittent.

### How it was proved

Not inferred. A throwaway harness ran the whole close/reopen cycle five times, recording to
disk after **each** cycle - the two things a DOM assertion cannot distinguish:

| Evidence | Before | After |
|---|---|---|
| writes reaching the server | `close` only | `close`, then `reopen` |
| the reopened announcement | never | every cycle |
| harness wall time | 1.8 min (five 15 s timeouts) | 30 s |

The harness also recorded that the locator matched **one** element and the click raised
**no** error - which is what pointed at a no-op inside the handler rather than at a locator
or a layout problem. A direct API probe then showed the 19-byte close body, and the search
for "what returns a partial thread" had exactly one candidate.

### The fix, in three parts

1. **The type is now what the wire says.** `ConversationApi.close()`/`reopen()` return
   `ConversationStatusView` - `{ status?: string }` with a string index signature, and
   deliberately **not** assignable to `ConversationDetail`.
2. **One merge rule instead of five call-site conventions.** `applyThread()` folds a write's
   response in: a response **with** an `id` is a whole thread and supersedes; a response
   **without** one is a patch and is merged. A partial response can no longer become the
   thread by any route.
3. **No action may fail silently.** Every handler now goes through `requireThreadForWrite()`,
   which - when the thread on screen is not a whole thread - puts `inbox.write.notLoaded` in
   the **alert** region and resyncs from the API rather than returning quietly. A control
   the operator pressed that neither acts nor explains itself is indistinguishable from a
   broken screen, and for a screen reader it is worse: the operator is told the conversation
   is open and never learns the click went nowhere.

### What the journey asserts now

Not "both announcements appeared" - that assertion is what let a dead button through a whole
batch, because a button that does nothing produces no announcement *and no error*. It now
asserts, separately:

- **both writes reached the server** - `[POST close 200, POST reopen 200]`, which separates a
  click that never left the browser from a write that landed;
- **order** - the newest announcement is the reopen, and the closed one does not follow it.
  A live region that ends on the older state is telling a screen-reader user the opposite of
  the truth, which is worse than saying nothing;
- **agreement** - the announcement matches the screen: the Reopen control is gone and Close
  is back. With a fragment standing in for a thread, the pane still rendered and the button
  still looked right, so this is the assertion the bug could not survive.

Plus six unit tests in `thread-merge.spec.ts` for the rule itself - deliberately a second
implementation of the merge, not an import of the component's, so the two can disagree.

## Two encoding regressions I introduced, and the checker that found them

Rewriting a whole file from PowerShell does two things that survive every gate:

- **`WriteAllLines` writes CRLF.** Four files came out with CRLF (one of them mixed), turning
  a small change into a whole-file diff. Restored to LF.
- **Non-ASCII characters are lost.** `AI <-> human` became `AI ? human`; an em dash became
  a hyphen. It compiled, it passed all 275 unit tests, and it is invisible to review.

Neither is caught by typecheck, by `npm test`, or by `generate:api:check`. The checker that
does catch it compares each HEAD line's ASCII skeleton against the working tree and reports
a line that lost a non-ASCII character - shape-independent, because the replacement is not
always `?`: a UTF-8 em dash decoded as cp1252 can land on `-`, on `"`, or on U+FFFD.

After the fix, the only remaining reports are lines that were legitimately **deleted** (the
two-view `filterMine` comment and its string, replaced by the five wire values) or
legitimately **rewritten** (an assertion whose local variable was renamed).

## The counts response, after it became a closed object

`GET /api/v1/inbox/counts` is now declared as a **closed object**: five named integers, all
`required`, `additionalProperties` absent. Consequences:

- **Tab order still comes from the response key order**, which is declaration order. There is
  no local list to fall out of step with the document.
- **The narrowing is a type guard, not a cast.** The value is checked with `typeof` and the
  key against the declared enum; there is no assertion on this path.
- **A missing or non-integer key is a contract violation, not a zero.** It used to read
  `counts[filter] ?? 0`, which rendered the violation as *a queue with nothing in it* - the
  one number on this screen that must never be invented. The strip is now withheld and
  `inbox.list.countsMalformed` names the offending keys in an **alert**, because an operator
  about to click the tabs needs to know they are not there.


## Verification for batch C, stated exactly

**The full matrix completed: 402 of 402 passed, 0 failed, 0 skipped, 0 flaky, 0 retried,**
in 42m 13s. Every one of the **67 journeys** was measured on **all six** locale/width
combinations. Machine-readable evidence is in `frontend/test-results/full-matrix.json`
and `frontend/test-results/full-matrix-summary.json`, the second written by
`frontend/tools/summarise-matrix.mjs` from the first.

| Project | Journeys | Passed | Failed | Retried |
|---|---|---|---|---|
| ar-desktop | 67 | 67 | 0 | 0 |
| ar-tablet | 67 | 67 | 0 | 0 |
| ar-mobile | 67 | 67 | 0 | 0 |
| en-desktop | 67 | 67 | 0 | 0 |
| en-tablet | 67 | 67 | 0 | 0 |
| en-mobile | 67 | 67 | 0 | 0 |
| **total** | **402** | **402** | **0** | **0** |

**Why 402 and not 408.** A stale duplicate journey, `sends only the two filter values the
the server accepts`, was still in the suite alongside its replacement. It asserted a
two-value vocabulary the server no longer has, and — worse — that an unknown filter "is a
500", which Phase 18 made false. It was deleted, which removed one journey per combination:
408 − 6 = 402. The replacement, `sends only filter values the contract enumerates`, asserts
the five-value enum and makes **no claim about a status code**, because pinning a 500 where
the server returns a 400 is worse than having no journey at all.

| Command | Result |
|---|---|
| `npm run typecheck` | **clean** - app, spec and e2e projects, zero errors |
| `npm test` | **281 passed, 0 failed, 0 skipped** (18 files) |
| `npm run generate:api:check` | **matches** - 128 paths, 140 schemas, 2 files |
| `npx ng build` | 368.36 kB initial raw, 102.1 kB estimated transfer |
| `npx playwright test` (**full matrix**) | **402 passed / 0 failed / 0 skipped / 0 flaky / 0 retried** |

**No test is skipped, disabled or `@Disabled`.** Verified statically across every
`*.spec.ts`: the only occurrence of the word is a comment in `e2e/journeys.spec.ts`
stating the rule. There were also **no retries**: `results.length === 1` for all 402, so
nothing here is green because it was run twice.

### The five failures that had to be fixed before this total, and what each was

Every one was a **test** defect, not a product defect — but two of them were tests that
were green for the wrong reason, which is worse.

1. **`sends only the two filter values the server accepts`** — a leftover duplicate of the
   replacement, asserting a two-value vocabulary and a 500 that is now a 400. Deleted.
2. **`closes a conversation and reopens it`** — the write-recording listener was attached
   **between** the two writes, so the close was never recorded and the array read
   `["POST reopen 200"]`. The product was correct; the test was listening to the wrong
   moment. Moved before the first write. This is *not* the dead-button bug returning, and
   `applyThread` / `refreshThreadFromServer` had nothing to do with it.
3. **`names the customer pane at every width`** — `page.getByRole('tab')` unscoped. That
   was safe when the inbox had one tablist; adding the five saved views made it count the
   saved views instead of the pane switcher, so desktop reported 5 where it expected 0.
   Scoped to the pane switcher's tablist by accessible name.
4. **`the administration screen keeps the two analytics scopes apart`** — `expect(array)
   .toContain(x)`` is an **exact element** match, and `recordApi` stores the whole URL with
   its query string. It passed before **only because `projectId` was `undefined`, the query
   was dropped, and the malformed request produced a bare URL** — the Hook 18 500 made the
   assertion true. Now asserted on the path prefix **and** on the call carrying
   `projectId=`, `from=` and `to=`, which is strictly stronger than the original.
5. **`shows an error with a retry when the inbox read fails`** — it broke
   `/api/v1/inbox/unassigned` and awaited `inbox.queue.title`, but that region is
   deliberately not rendered on the default `unassigned` view, so no error state existed to
   find. Now breaks the conversation list, which is rendered there, and asserts recovery on
   the saved-view strip.

**These five fixes are covered only by browser journeys, not by `npm test`** — the unit
count did not move. `expect(array).toContain` exact-match semantics is exactly the class of
mistake a unit test would have caught, so that is a real gap in this batch and is stated
rather than glossed.
## Verification (batch B, superseded by the above for the shared gates)

Commands, run from `frontend/` against a jar built by the orchestrator from `5442bd8`,
with the API started `--firstelement.inbox.mock-connector-enabled=true` and
`JAVA_TOOL_OPTIONS=-Djava.net.preferIPv4Stack=true`.

| Command | Result |
|---|---|
| `npm run typecheck` | **clean** — app, spec and e2e projects, no diagnostics |
| `npm test` | **269 passed, 0 failed, 0 skipped** (16 files) |
| `npm run generate:api:check` | **matches** — 128 paths, 135 schemas, 2 files |
| `npx ng build` | 367.27 kB initial raw, 101.73 kB estimated transfer |
| `npx playwright test` | **373 passed, 5 failed, 0 skipped** (378 = 63 × 6) |
| `npx playwright test governance-journeys.spec.ts` | **78 passed** (13 × 6, no failures) |

The 5 failures are one journey, blocked on Hook 16, with an identical cause on every
combination. `./gradlew build` was **not** run — that is the orchestrator's gate.

## Batch B — groups 9-12

Delivered against `/api/v1/admin/**`, `/api/v1/finance/**` and `/api/v1/exports/**`,
with clinic as an explicit not-available surface.

**Three defects this batch found in its own earlier work**, all now fixed and all
recorded where they happened:

1. **Hook 10 was wrong** — see above. Two nav entries and two route gates were gated
   on nothing and marked unavailable, when the backend already enforces a permission
   for both.
2. **Closing a case requires a recorded resolution, and the contract does not say so.**
   `POST /api/v1/conversations/{id}/close` answers
   `ValidationFailedException: Record how the case was resolved before closing it`. It
   is a reasonable rule and it is enforced — and it is invisible to a client, because
   **no operation in the document declares a 4xx at all** (Finding 2). The client now
   checks the same precondition and points the operator at the resolution form instead
   of sending a write whose refusal it can only report as a generic failure. The server
   still enforces it; this only avoids a round trip whose outcome is already known.
3. **`GET /conversations/{id}/resolution` answers `204`, and Angular resolves that to
   `undefined`, not `null`.** The close precondition dereferenced the value and would
   have thrown a `TypeError` on the most common path there is — closing any case for
   the first time. Caught by a unit test written for the rule above, and pinned by
   asserting the shape across `undefined`, `null`, `{}`, blank and non-blank outcomes.

**Navigation.** `audit` and `exports` are now entries that open a specific tab of a
shared screen (`?tab=audit`, `?tab=exports`) rather than dead ends. Two consequences
that were real bugs rather than polish: the shell's `@for` tracked on `entry.path`,
which would have rejected the second of each pair and silently never rendered them; and
a deep link to a tab the caller's permissions do not cover is now refused, falling back
to the default rather than opening an empty card indistinguishable from "no events".

**Tab accessibility.** Both new screens use a real `tablist` with roving `tabindex`,
`aria-controls`/`aria-labelledby` pairing, and arrow-key traversal plus Home/End. The
arrow handler is load-bearing rather than decorative: with only the selected tab in the
tab order, without it seven of eight tabs would be *unreachable by keyboard* rather than
merely out of sequence. The journey asserts all eight tabs are present, that exactly one
is in the tab order, that each `ArrowRight` advances by exactly one **index** (so a
handler that jumped two would fail rather than passing because "a panel appeared"), and
that the owning panel is visible every time.

**RTL.** Only logical CSS properties, asserted project-wide by `design-system.spec.ts`.
The strips wrap rather than scroll, because a horizontally scrolling strip hides its own
overflow and a governance screen whose eighth tab is invisible is one nobody reads. The
clinic page's long monospace route paths are asserted not to overflow at 360px, the width
at which they actually would. `ArrowRight`/`ArrowLeft` are **not** flipped for RTL: the
strip is laid out by the document's own flow, so "next in reading order" in Arabic is the
tab on the left, and flipping the mapping would fight the layout rather than follow it.

**Form accessibility.** The resolution form's three required fields carry `required`,
`aria-invalid` and `aria-describedby` pointing at their own message, so a screen reader
reads the rule *with* the field rather than leaving it to be found. The required marker
is `aria-hidden`, because the requirement is already carried by the control and its
message — a marker announced as "asterisk" helps nobody.

**Numbers.** Server figures render through `FormatService` with the locale's own
numbering system, held in Western digits by the `-u-nu-latn` extension rather than left
to a browser guess. A journey asserts no Arabic-Indic digit appears on the finance
screen, because those would be a browser default rather than a decision this client made.

**Bundle.** Initial total **367.27 kB** raw / **101.73 kB** estimated transfer, up from
364.25 / 100.98 in batch A. The three new screens are **lazy**: `admin-component`
37.09 kB, `finance-component` 36.58 kB, `clinic-component` 6.33 kB. A route that has not
been migrated yet must not be in the initial bundle, and the strangler window is long.

**No writes.** A journey asserts on the browser's own request log that the
administration and finance screens issue **no `POST`, `PUT` or `PATCH` at all**, and
that no `retry|cancel|download|approve|apply|lock|reopen` button exists on them. The
governance, period-close, adjustment and export-lifecycle writes exist in the contract
and are deliberately withheld — Hook 13 asks the orchestrator to ratify that as a
confirmed-command design or to leave them out of band.

**Free-form documents.** Nine of the eleven admin responses and most finance responses
are `{ document: {...} }` with no declared shape. Every table is derived from what the
server actually sent, a field it did not send renders as `—`, and no column list is
hard-coded — a fixed column set shows empty cells for every field a subject happens not
to carry, which reads as "there is no data" when the truth is "there is a different
shape".

## What groups 9-12 inherit

Group 12's gap is stated above and on the screen itself: **there is no clinic route in
the committed document.** The five subjects, the routes they wait for, and what will
light up are all listed in the *Clinic* section above, and nothing in the client needs
rebuilding when those routes land.

Groups 9, 10 and 11 turned out to be the best-served families in the document, and the
only blocker on them — Hook 10 — was mine rather than the contract's. The remaining
open questions are Hook 13 (are the withheld writes wanted as confirmed commands?) and
Hook 14 (an export cannot be created, because nothing lists a report definition).

The rules this checkpoint established are enforced by tests, not convention, so
a new screen cannot quietly break them:

- `design-system.spec.ts` — no raw colours outside `tokens.css`; both themes
  defined separately; no physical directional CSS; a focus ring is never removed;
  no `display: none` on a label in a responsive block (that strips the accessible
  name, and it shipped once); no emoji; no all-caps copy.
- `i18n.spec.ts` — every literal key a template or component uses resolves; every
  runtime-assembled key family is enumerated and checked; no key is
  Arabic-only or English-only.
- `api-contract.spec.ts` — every route the endpoint layer calls exists in the
  committed document with the parameters it sends; the five findings above.
- `permissions.ts` + a journey — every permission literal the client checks is
  declared in one typed list, so a typo is a **compile error** rather than a
  control that silently never renders. Two invented literals
  (`conversation:write`, `customer:write`) hid the reply composer and the notes
  editor for every user in a full matrix with nothing failing; a browser journey
  now reads `GET /api/v1/permissions/me` and asserts every declared literal
  appears in the server's granted set, so the two lists cannot drift apart again.
- `encoding.spec.ts` — no U+FFFD, no stray C0 control characters and no BOM in any
  source file, and the Arabic dictionary still contains Arabic script with its
  diacritics. Added because a bulk edit through a Windows PowerShell pipeline
  silently turned every em dash in `endpoints.ts` into mojibake while the file
  still compiled and all 245 unit tests still passed.
- `features-groups-5-8.spec.ts` — the concurrency headers are sent as headers and
  not folded into the query string; `If-Match` is omitted rather than sent as the
  string `null`; notes are read per-resource and never merged with the project
  list; every backend gap is named in both languages; and every string in the four
  new namespaces has an Arabic and an English value that are not the same string.
- `features-groups-9-12.spec.ts` — every admin, finance and export call is pinned to
  its verb and path, and **all of them are `GET`**: the assertion is that the screens
  issue no write, because the routes that would let them do so exist. Nested ids are
  asserted to be percent-encoded, so a caller-supplied value cannot escape its path
  segment. The absence of an export-create method is asserted, because an absence is
  exactly the kind of thing that gets helpfully added back. And the clinic strings are
  asserted to contain **no digit outside a quoted route path** — the cheapest possible
  detector of a filled-in placeholder.
- `inbox-rules.spec.ts` — the close precondition, across `undefined`, `null`, `{}`, a
  blank outcome and a real one; the refusal table, pinning which of 401/403/404/409/412
  are retryable and which mean "stop", and that two 403s are distinguished by machine
  code rather than status.
- The journey suite — every journey runs in all six locale/width combinations,
  because a journey registered for one is a journey that only works in one.

When the next group adds a screen, it gets the loading, empty, error and stale
states from `AsyncStateComponent` rather than inventing a fifth phrasing, and it
fails the same gate.
## Phase 32 follow-up: the export catalogue fixture, and two real defects it found

### The fixture now seeds report definitions and templates

`e2e/authoring.seed.ts` writes two report definitions (each with a **published** version
carrying a non-empty `measures` array) and one document template, per tenant, and then
**asserts** the rows: non-empty id, `nameAr`, `nameEn`, a `publishedVersion`, and at least
one published version with measures. A failure throws and fails the seed, naming the symptom
a journey would otherwise hit instead.

It is called from **`e2e/global.setup.ts`**, which is the hook Playwright actually runs, and
from `e2e/seed.cli.ts`. Wiring it only into the CLI left every journey reading an empty
catalogue, and an empty catalogue is not an obvious failure: the picker shows its placeholder,
nothing is pinnable, and the screen's own empty state — correct behaviour for an empty
catalogue — stands in for a defect that is not there.

It seeds rows with `psql`, like the rest of the fixture. The first version of this module
authored over HTTP with a hand-rolled cookie jar and CSRF token and failed every write with
`403 CSRF_TOKEN_INVALID`. That was the wrong instinct: **the rest of this fixture does not use
HTTP** — `seedTenant` writes tenants with `psql` and inbound conversations come from the
backend's own dev mock endpoint, and neither needs a cookie. Authoring over real HTTP is
already proved by the backend's `ReportAuthoringIntegrationTest$FreshDatabase` (definition →
version → publish → template → template version → publish → catalogue → export → generate, over
Testcontainers, on every build). The split is: **the backend suite proves authoring works; this
fixture only needs to supply data.**

### Defect 1 — `selectableVersions(undefined)` threw on every render

`finance.component.html` called `selectableVersions(selectedDefinition()!)`. The `!` was a
type-level claim the runtime contradicts every time nothing is selected, and the method
dereferenced `row.versions`. It threw
`TypeError: Cannot read properties of undefined (reading 'versions')` **on every change
detection pass**, which aborted the pass part-way through the template — so the definition
picker's `<option>` elements were created but never bound and rendered as `<option></option>`:
no `value`, no label, no text.

That is the whole mystery. The catalogue was correct all along:

```
GET /api/v1/exports/catalogue?projectId=…  (asked from inside the page)
→ 3 definitions, first keys: reportDefinitionId, key, nameAr, nameEn, status, …
```

The count of options matched the count of definitions while **every value and every name was
empty**, because the bindings never ran. A catalogue the server had populated correctly
rendered as an empty picker, and the console said `TypeError` on every frame while the screen
said "the server returned nothing".

Fixed by accepting `CatalogueRow | undefined` and answering "no definition chosen" as the
state it is — no rows — and by removing both `!` assertions from the template. **A non-null
assertion in a template is a claim about runtime data that nothing checks.**

### Defect 2 — `effect()` in `ngOnInit` (NG0203)

`AuthoringComponent.ngOnInit` called `effect()`, which requires an injection context. It threw
`NG0203` on every mount, so the redaction-policy select was never seeded from the catalogue's
own list. Moved to the constructor, next to `takeUntilDestroyed()` and for the same reason.

### Still open

With both fixed, the authoring journeys no longer fail on the picker. Two now fail on a
**different, undiagnosed** symptom: the exports panel renders the non-disclosing refusal card
(*"No route lists report definitions or document templates…"*) although `GET
/api/v1/exports/catalogue` answers 200 with a populated body. Some call on that panel is
404ing; which one was not established. The full matrix was **not run** this phase.

### Defect 3 — the exports panel denied its own capability

There was **no 404 and no failing request**. The "no route lists report definitions or
document templates" card is *static, unconditional* markup in the exports panel — not a
catch-all for a failed load. It claimed no route lists those ids and that no create form is
offered, while the form 300 lines above it in the same panel was offering one, populated from
`GET /api/v1/exports/catalogue`.

It was true when written: the list routes genuinely were absent. Phase 32 added the catalogue
and the authoring routes; the card was not revisited and became a screen denying its own
capability. It also produced a strict-mode violation — `getByText('Report definitions')`
matched the real count, this heading, and this body — which read as a test bug.

`unavailable.download` next to it is the **opposite** case and stays: the route
(`POST /api/v1/exports/{exportId}/download-link`) exists and this client genuinely does not
mint the short-lived link, so its copy is accurate.

**Method note.** The lead hypothesis was a 404 on a sibling call. Reading the code settled it
in one step and the 404 was never there.

### Defect 4 — a `computed` that could not see the form

`versionExplanations()` is a `computed`; it asks `selectedDefinition()`, which read
`creationForm.controls.reportDefinitionId.value` — an ordinary property on a reactive-forms
control, **not a signal**. A `computed` tracks only signals, so nothing it depended on changed
when the operator chose a definition and it never recomputed.

Nothing on screen looked wrong: the version `<select>` filled in correctly (its binding is
called from the template, so change detection re-ran it) and directly beneath it the
explanations list stayed empty — which is the *correct* rendering of "no definition chosen".
The server's refusal for a draft (`"This version is a DRAFT and has never been published"`)
was in the response throughout and never displayed.

Both are now `toSignal(control.valueChanges)` so the computed can depend on them. **The
symptom in both defects 1 and 4 was a *correct-looking empty state* standing in for a
non-empty one** — the pattern to watch for.

### Fixture consequence: pickers must select by label

Seeding two published definitions per tenant means "the first definition offered" is a
published report with nothing to refuse, so a journey asserting that a *draft* is
unexportable was asserting it about the wrong report. `chooseDefinition` now selects by the
shared `PROBE_NAME` label.

### Harness: `test-results/` cannot hold a streaming log

**Playwright deletes and recreates `test-results/` at the start of every run.** Streaming a log
into it fails with `DirectoryNotFoundException` — the same failure that cost a run earlier in
this phase, now explained. The matrix streams to `frontend/.matrix-stream/full-matrix.log`,
which is outside that directory.

### Matrix round 1: 438 enumerated, 397 passed, 41 failed — and what each failure was

The run enumerated **438, not the 444 the orchestrator expected**: 73 journeys x 6, not 74 x 6.
Which journey is absent has not been established.

**16 of the 41 were `net::ERR_CONNECTION_REFUSED at http://localhost:4200`** — the dev server's
background task hit its one-hour timeout at 05:25 and died. Every journey that ran while it was
down failed, and each one read exactly like a product defect. Cause was a timeout I set too
short for a run that needed longer.

`globalSetup` now calls **`assertDevServerReachable()`** beside `assertApiReachable()` and
refuses to start with the message and the command. It cannot stop the server dying mid-run and
does not pretend to; it removes the case where a run *begins* without one.

### The other 25: five journeys, and not one of them was what the error suggested

| journey | verdict | what it actually was |
|---|---|---|
| a version the server will not let an export pin is never offered as a choice | **product** | stale catalogue |
| a draft version is stated to be unexportable, and the server agrees | **product** | stale catalogue |
| offers no export format the server refuses | **product** | `0: undefined` |
| says the catalogue is empty rather than offering an unusable form | **stale by construction** | empty state the fixture no longer produces |
| renders a non-disclosing refusal without claiming to know its cause | **stale assertion** | English-only regex |

#### 1 & 2 — `catalogueChanged` was declared but never emitted from the creating calls

`finance.component.html` already bound `(catalogueChanged)="refreshCatalogue()"` and the
component already declared the output — but it emitted only from `publishVersion` and
`retireVersion`. **`createDefinition` and `createVersion` never emitted**, so an author could
create a definition, add a version, publish it, switch to "create an export", and find their own
report offered with **no versions at all**: the catalogue had been read before any of it existed.
The version picker then had nothing to choose, so the server's refusal for a draft was never
displayed — not suppressed, just never asked for.

The catalogue listing read `Probe definition Versions: 0` directly above
`Probe definition Versions: 1`. Both were probes. Only one of them was the journey's.

Now emitted after every **accepted** call — never after a refusal, which changed nothing on the
server and would cost a request to learn nothing.

#### 3 — `[ngValue]="undefined"` put `0: undefined` in a select that promises to offer nothing the server refuses

The format and locale controls were declared `FormControl<CreateExportRequest['format']>` — a
type that cannot be null — and initialised to `undefined`, with the placeholder option carrying
`[ngValue]="undefined"` to paper over the gap. **Angular serialises an `[ngValue]` option to
`0: undefined`.** Changing it to `[ngValue]="null"` only moved the nonsense to `0: null`.

`[ngValue]` is for object values in a select driven by a form control; for plain strings it adds
an id prefix and buys nothing. Both placeholders are now `<option value="">`, both controls are
plain `''` with `Validators.required`, and `createRequest` narrows through the `asFormat` /
`asLocale` helpers it already had. `createRequest` guarded with `if (!format || !locale) return
null`, which an empty string satisfies exactly as well.

#### 4 — "says the catalogue is empty": re-expressed, and the loss stated

Its premise — *"`report_definitions` and `document_templates` … no route creates them"* — died in
Phase 32, and the fixture now seeds two published definitions and a template into every tenant.

**The seed was not weakened to accommodate the journey, and the assertion was not deleted to
accommodate the seed.** It is renamed **"never offers an export form the server cannot fulfil"**,
its real subject, and against a populated catalogue that is a *stronger* claim: every definition
offered carries a server-issued id, so choosing one cannot produce a request the server refuses.

**Coverage actually lost: the empty-catalogue state is now covered by no journey.** It is
unreachable from the fixture without a project holding no definitions, and every identity runs
every journey against its own project, so un-seeding one would break that combination's export
journeys instead of testing this one. Its right home is a component test where the catalogue can
be stubbed empty deterministically. **That is outstanding work, not a passing test.**

#### 5 — three failures out of six that looked like an RTL defect and were a test reading one language

The journey asserted its **English** wording with `/same way/i` against `journey.copy()`, which
returns **the journey's locale**. It passed in `en-*` and could not match in `ar-*`. The Arabic
string is correct and discloses no more than the English one.

`Journey.copy(key, locale = this.locale)` now takes an explicit override. The journey asserts
the English wording — which is what "a future change to 'not found' or 'forbidden' means" — and
separately that **both dictionaries carry the copy** (`copy()` throws for a missing key rather
than returning a placeholder).

An earlier draft of that fix added a "the screen shows this text" assertion. **It was wrong**:
this screen has no id box precisely so an operator cannot provoke the refusal, so there is no
UI path and the copy is never rendered. Removed rather than satisfied by inventing a path.

#### And one assertion that had never executed

"never offered as a choice" required **every** `<option>` in the version select to be `disabled`,
including the placeholder. It had never run — the journey died earlier. A disabled placeholder
cannot be re-selected, so once a version were chosen the operator could not clear it: the
assertion demanded a defect. Now the placeholder is asserted **not** disabled and every version
option **is**, which is two assertions where there was one.

### The recurring lesson, and it is not only one direction

Two defects this phase were client-side throws presenting as a server's fault. The connection
failures were the mirror image: **a server dying presenting as sixteen client defects.** A
`TypeError` in change detection and a refused connection both look like "the data is missing",
and only the console or the harness distinguishes them.

### Matrix round 2: 438 / 438, every combination, zero failures

`npx playwright test --reporter=line`, finished 2026-10-09T06:55:17, **exit code 0**.

- **438 total, 438 passed, 0 failed, 0 flaky, 0 skipped**
- **connection-refused failures: 0** (the previous run's 16 did not recur)
- **73 distinct journeys, 73 measured in all six combinations**

Per project: 73 each across `ar-desktop`, `ar-tablet`, `ar-mobile`, `en-desktop`, `en-tablet`,
`en-mobile`. By spec: authoring 6, feature 29, governance 13, shell 25.

**On the 444 figure:** the suite contains **73** journeys, not 74. 73 x 6 = 438, and that is
what Playwright enumerates. The 444 expectation does not match the suite as it stands; which
journey is missing from the intended 74 has not been established, and it is recorded rather
than reconciled by inventing one.

Evidence: `frontend/test-results/full-matrix.log` (UTF-8) and `full-matrix.json`. The streaming
copy is under `frontend/.matrix-stream/`, because Playwright deletes and recreates
`test-results/` at the start of every run.

**A note on reading that log.** `Tee-Object` on Windows PowerShell writes **UTF-16LE**, so the
file has to be decoded before it is readable, and the `›` separator arrives mojibake. Both were
fixed in the copy written to `test-results/`. A log nobody can read is not evidence.
