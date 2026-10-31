# Phase 49 — the AI draft surface a person can actually use

## What this phase was

Phase 46 gave the backend a governed AI draft trigger. An inbound message on an
AI-mode conversation produces ONE `AiOrchestrator` run and records a
human-reviewable draft. A human then presses send through the existing reply
route. Nothing is sent automatically.

The capability was **invisible**. Nothing under `frontend/src` called
`/api/v1/ai/drafts`, so the run happened, the draft existed, and no person using
the product could read it or act on it. This phase builds the surface that reads
it and hands it to a human.

## The route, read from the document and not from memory

`GET /api/v1/ai/drafts/conversations/{conversationId}`, `operationId: pendingDraft`.
The published document was read before any code was written. Three things in it
are load-bearing and all three shaped the design:

1. **It is a GET and there is nothing else.** No confirm, no send, no discard.
   A send belongs on the reply route, so the panel reuses it rather than
   inventing a second path.
2. **The 404 is one refusal for four facts.** No pending draft, no such
   conversation, another tenant's conversation, and a draft withheld because a
   human took over. The document says so in the operation's own description.
   The identity *is* the non-disclosure property, so the client cannot treat it
   as a failure and cannot surface the server's message.
3. **The parameter names a conversation, not a tenant.** The controller's
   javadoc states there is no `organizationId`, `tenantId` or `projectId` to
   bind — `check-architecture.ps1` rule FE-SEC-002 fails the build if one appears.

The response is `AiDraftView`: `runId`, `conversationId`, `body`, `createdAt`,
`state` (always the literal `DRAFT`), `conversationMode`, `conversationModeVersion`.
`state` is published as a literal rather than derived so a client cannot be handed
a value that reads as "sent".

## What was built

`frontend/src/app/features/inbox/ai-draft.component.{ts,html,css}` — a standalone
panel rendered by the inbox thread pane, **after the message list and outside
it**, from three inputs: the conversation id already open, the thread's mode, and
its message count. None of them is a tenant or a project.

`frontend/src/app/features/inbox/ai-draft.strings.ts` — the copy, Arabic first.

`AiDraftApi.pendingDraft()` in `core/api/endpoints.ts` — one method, one
parameter, which names a conversation.

### The four states, and why they are four and not three

| State | Source | Rendered as |
|---|---|---|
| present | `200 AiDraftView` | the draft card |
| absent | `404` | a quiet line: no draft is waiting |
| withheld | `404` **and** the thread is in human mode | a quiet line naming the takeover |
| forbidden | `403`, or no request at all | a quiet line: the account may not read drafts |
| fault | `5xx` / network | the one `role="alert"`, with a retry |

The withheld state deserves an explicit note, because **the route cannot tell the
client that a takeover happened.** `AiDraftRepository.latestOfferableDraft`
requires `c."mode" = 'AI'`, so a conversation a human owns can never carry an
offerable draft, and the panel can say so from a fact the operator is already
looking at: the mode chip on the thread above. It is phrased as what the system
does — "a human has taken over this conversation, so no AI draft is offered
here" — and deliberately **not** as "an AI draft was withheld", because that
would tell the operator an AI run existed, which is the one thing the route
refuses to disclose.

### Never auto-send

Asserted by the **absence** of a write before the button is pressed, and by the
presence of exactly one after it, at `POST /api/v1/conversations/{id}/messages`
with `{ body }` — the composer's route, the composer's method, the same
`X-Expected-Message-Count` and `Idempotency-Key`. The idempotency key is derived
from the conversation and the run, so a double-press is one write and a
*different* draft on the same conversation is a different write. A second press
after a success issues nothing.

Reading and sending take **different permissions**: `ai-run:read` and
`conversation:reply`. The backend's controller javadoc calls this out — a
principal holding the read permission but not the send can read a draft and still
be unable to speak to a customer — so the card shows the text and withholds the
button.

### The generated client was stale and had to be regenerated

`frontend/src/app/core/api/generated/` had no `AiDraftView` and no
`pendingDraft` at all. It was regenerated with `npm run generate:api` from
`tests/contracts/openapi.json`, which took it from 145 to 146 paths and 162 to
163 schemas: **362 type names added, none removed** (verified by diffing the
export lists). The contract was not changed to make anything compile.

## Tests

`frontend/src/app/features/inbox/ai-draft.spec.ts` — 18 component tests, written
**before** the component and run against the pre-change tree first.

```
npm --prefix frontend run test
# RED, first:  ERROR Could not resolve "./ai-draft.component"  — the only failure
# GREEN, last:  Test Files 20 passed (20)   Tests 317 passed (317)
```

Baseline before this phase was 19 files / 299 tests.

`npm --prefix frontend run typecheck` — clean (app, spec and e2e projects).
`npm --prefix frontend run generate:api:check` — "The generated client matches
the contract (146 paths, 163 schemas)".

### Red-to-green, verified by me rather than claimed

Five guards were neutered one at a time and the suite re-run. Four went red:

- client-side permission gate removed → `does not ask at all` **FAILED**
- collision header dropped → `carries the collision header the composer carries` **FAILED**
- takeover reading disabled → `says the draft was withheld` and `does not say "no draft"` **FAILED**

The fifth — the `canSend()` check inside `send()` — did **not** go red, because
the template's `@if (canSend())` is the user-visible gate and the method check is
redundant defence in depth. That is recorded rather than hidden: it means the
template gate, not the method gate, is what the suite is really proving. All
neuterings were reverted and the suite re-run green.

## Arabic first

Arabic is the source of truth in `ai-draft.strings.ts`, and both locales are
rendered in the suite. Two labels are load-bearing and were written deliberately:

- `badge` is `مسودة` / `Draft` — and deliberately **not** the `inbox.message.*`
  sent/delivered vocabulary, because reusing it would put a draft into the same
  words an operator reads to decide what the customer already has.
- `notSent` states positively that the customer has not received it. A badge is a
  label; that sentence is a claim about the world.

RTL uses CSS logical properties throughout (`margin-inline-start`,
`border-inline-start`, `padding-inline`, `text-align: start`), so the panel
mirrors with no second rule. The draft card is visually distinct by three
independent signals — a dashed border (the only dashed edge in a thread), a
`--warn-soft` badge in words, and the explicit sentence — because an operator who
reads a draft as a sent message will act on the belief that the customer has been
answered.

## One thing worth flagging to the next agent

`src/app/core/encoding.spec.ts` scans every source file under `frontend/` with a
**5s default timeout**, and it has no skip for `test-results/`. During a live
Playwright matrix that directory held 1,400+ files and was growing; the guard
timed out twice on runs that were otherwise fully green. The guard's own logic
passes in **231ms standalone with zero U+FFFD and zero control characters**, so
this is contention, not damage. It is a real fragility in a shared guard and it
belongs to whoever owns quality — I did not modify it, because changing a shared
guard to make my own run green is exactly the change that should not be made
silently.

## Ownership

Created and modified only under `frontend/src/**`. `frontend/e2e/**` untouched
(verified: `git diff --stat -- frontend/e2e` is empty). No new Playwright journey.
No dev server started. Nothing under `apps/`, `packages/`, `backend/`, `ops/` or
`tests/contracts/` was touched, and no Gradle build was run.

`ops/quality/gate-stages.json` shows as modified in `git status`; it was already
modified before this phase began and its own diff is labelled `PHASE 49` by the
orchestrator. Not this phase's work.