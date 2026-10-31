# Phase 28 — worker and packages sweep: the same class, one home (P0)

Status: **one vulnerable module found and fixed; the worker is clean by
construction.** Two false security comments found and corrected. One file changed
for behaviour, three for scope and truthfulness. 4 package files, 34 tests.

| | |
|---|---|
| Trigger | Phases 24–26 fixed `apps/api/src/**` and reported `apps/worker` and `packages/**` unswept |
| Vulnerable sites found | **1 module, 8 unscoped lookups** — all in `packages/ai/src/tools/clinic-scheduling.ts` |
| Production behaviour changes | `packages/ai/src/tools/clinic-scheduling.ts` |
| Comment-only changes | `packages/ai/src/tools/executor.ts`, `packages/ai/src/orchestrator/prompt.ts` |
| Test changes | `packages/ai/src/tools/clinic-scheduling.test.ts` |
| `apps/worker/**` | **0 changes** — clean, and §2 says why in detail |
| `PermissionsGuard` runtime | untouched (not in this mission's globs) |
| Migration impact | **none** |
| Verification | 34 tests in the clinic-tools suite; 240 package tests; whole-repo `tsc -b` exit 0 |
| `apps/api/**` | **not touched**, as instructed |

---

## 1. The headline, before the detail

**The class exists in `packages/ai`, not in the worker.**

The worker's job payloads carry `organizationId` and `projectId`, and I traced
every one of them to a query. All of them are bound into the lookup, so a payload
claiming tenant A while naming tenant B's conversation finds nothing. The worker
trusts the payload, but it uses it *consistently*, so it cannot be used to aim a
write at another tenant.

The worker is a thin scheduler; the data access lives in the packages. And in the
packages, exactly one module was unscoped — the **clinic AI tools** — where the
ids being resolved are **model arguments**, and a model is steered by untrusted
customer text. That is the same defect class with a materially better attack path
than anything in the API surface: no HTTP surface, no permission, and the input is
a prompt.

## 2. `apps/worker/**` — clean, and here is the argument rather than the assertion

| Worker handler | Where the project id comes from | Verdict |
|---|---|---|
| `INCOMING_MESSAGE` → `InboundMessagePipeline.process(job.data)` | job payload | **safe-because-consistent** — see below |
| → `resolveAIEligibility({org, project, conversationId})` | job payload | **safe-because-scoped** — binds org+project into the conversation lookup (`ai-dispatch.ts:22-28`) |
| → `scoringQueue.add` / `aiQueue.add` / `routingQueue.add` / `outboundQueue.add` | copies the same payload forward | **safe-because-propagating** — no query, no scope decision |
| `AI_RESPONSE` → `AIOrchestrator.run(job.data)` | job payload | **safe-because-scoped-first** — `orchestrator.ts:82-87` binds org+project into the conversation lookup and returns early if absent |
| → `EscalationEngine.escalateDirectly` | job payload | **safe-because-scoped** (`escalation-engine.ts:200-205`) |
| `SCORING` → `EscalationEngine.scoreTurn(job.data)` | job payload | **safe-because-scoped-first** (`escalation-engine.ts:55-60`) |
| `ESCALATION_ROUTING` → `RoutingEngine.route(job.data)` | job payload | **safe-because-scoped** (`engine.ts:37-41`) |
| `OUTBOUND_SEND` → `OutboundDispatcher.dispatch(job.data)` | job payload | **safe-because-scoped** — `message.findFirst({ id, organizationId, projectId })` (`outbound-dispatcher.ts:39-44`) |
| `DOCUMENT_PROCESSING` → `ingestion.process(job.data.documentId)` | **not the payload at all** | **safe-because-lookup** — takes only the id and derives org/project from the document row (`ingestion.ts:44-45`) |

**The key question you asked — "who supplied this project id?"** — has a different
answer per handler, and that is what makes the worker safe rather than accidentally
safe:

- **Payload-derived ids are used consistently.** `InboundMessagePipeline` does not
  look anything up by a foreign id; it *creates* the customer and conversation
  **using the payload's own** `organizationId`/`projectId`
  (`inbound-pipeline.ts:47-64, 149-159, 187-200`) and writes the message with the
  same pair. A payload naming tenant A simply produces a conversation in tenant A.
  There is no join to a row whose scope could disagree with the write.
- **Id-derived lookups carry the payload's scope as a predicate.** Every
  `findFirst` on a `conversationId`/`messageId`/`escalationId` in the AI and
  messaging packages includes `organizationId` and `projectId` alongside the id.
  A mismatched payload matches nothing and the handler returns a `skipped` result.
- **The one place that does not take a payload id derives it from a row.**

The worker has **no project-membership check at all**, and it should not: there is
no principal in a BullMQ job, so "is this principal a member of this project" has
no meaning. The correct worker invariant is the one the code already holds — *a
payload's org/project must be part of every predicate, never an alternative to
one.* Where that invariant is broken, it is in the package the worker delegates to.

## 3. `packages/**` — the vulnerable module

### `packages/ai/src/tools/clinic-scheduling.ts` — **VULNERABLE, fixed**

Eight unscoped lookups, all reached from `ToolExecutor` with **model-supplied**
`doctor_id` / `procedure_id` / `patient_id` / `appointment_id`:

| Function | Pre-fix lookup | Impact |
|---|---|---|
| `getClinicDoctors` | `where: any = { isActive: true }`, `projectId` attached **only when truthy**, **no `organizationId`** | empty/undefined project id ⇒ **every active doctor in every tenant** |
| `getClinicProcedures` | same shape | same |
| `getProcedureDuration` | `procedure.findUnique({ id })` | reads another tenant's procedure **name, specialty, duration, price** |
| `findDoctorAvailableSlots` | `doctor`/`procedure.findUnique({ id })`, and `appointment.findMany({ doctorId, … })` with no tenant | reads another tenant's doctor **and their appointment density**; received **no** project id at all |
| `checkDoctorAvailability` | `doctor`/`procedure.findUnique({ id })`; `projectId` accepted but unused for the lookups | reads another tenant's roster and availability |
| `findAlternativeDoctors` | `doctor.findMany({ specialty, isActive: true })` — **no org, no project** — then each peer's appointments | enumerates **every tenant's doctors of a specialty**, and their booking density, to the model |
| `createAppointmentValidated` | `doctor`/`procedure`/**`customer`**.`findUnique({ id })` | copies another tenant's **customer display name, phone and email** into the caller's own appointment row **and into the audit-log metadata** — a cross-tenant read the caller can then read back through their own appointment |
| `findNextAlternativeSlots` | delegates to `findDoctorAvailableSlots` | inherits the above |

`rescheduleAppointmentValidated` and `cancelAppointmentValidated` were already
correct — their `findFirst` binds `organizationId` and `projectId`
(lines 526-531 and 606-611), and the subsequent `update({ where: { id } })` runs
only on a row already proven in scope. **Left untouched.**

**The fix** (in the tool functions, which are the data-access layer — not a
controller):

- A `ClinicScope` helper plus an `organizationId` parameter on every function that
  lacked one. `checkDoctorAvailability`'s `projectId?: string` became required, and
  `findDoctorAvailableSlots` gained the scope it never had.
- Every lookup is now `findFirst` with `{ id, organizationId, projectId }`.
  The two `where: any` builders are gone, and with them the "empty project id
  means every tenant" branch.
- `createAppointmentValidated` now **refuses** when the customer is out of scope,
  returning the same `{ booked: false, code: 'NOT_FOUND' }` shape it uses for
  everything else, rather than booking with a null patient.

**Non-disclosure.** These are tool results, not HTTP routes, so the equivalent of a
404 is "the row is reported exactly as a nonexistent row is". Every one of these
functions already had a not-found branch, and an out-of-scope id now produces it.
The tests compare the denial against a genuinely nonexistent id with the caller's
own id normalised out of the echo — the response reveals nothing the caller did not
already supply, and there is no distinct "not yours" outcome to distinguish.

## 4. The comment audit — two false claims, both corrected

You asked for every comment that overstates what the code does, whether or not it
hides a live defect. I found **two, and both were hiding the defect above**.

**1. `packages/ai/src/tools/executor.ts` — rule 2 was false.**

> *before:* "Tenant columns come from this context, never from tool arguments. **A
> model cannot ask for another project's customer, because there is no argument
> through which to name one.**"

There were four such arguments: `doctor_id`, `procedure_id`, `patient_id`,
`appointment_id`. This is the sentence an engineer would rely on to decide the
clinic tools were safe.

**2. `packages/ai/src/orchestrator/prompt.ts` — layer 1 was false.**

> *before:* "A perfectly successful injection **still cannot reach another tenant's
> data** or call a disabled tool."

The second half was true (`enabledTools` is checked). The first half was false, and
this is the stated defence-in-depth thesis for prompt injection — the comment that
says injections are contained by code rather than by the prompt.

Both now state what is actually true: the model cannot *choose* a tenant, but it can
*name a row*, and each helper re-checks the row's scope. The false claim was removed
rather than softened, and both name the clinic tools as the proof.

**Comments I checked and found accurate** — recorded so they are not re-opened:
`knowledge-base/retrieval.ts:56-62` (the WHERE really does carry both columns before
the similarity ordering); `routing/engine.ts:148-152` (membership really is the first
candidate filter); `messaging/channel-credentials.ts:9-13` (decryption really is
confined to that one module); `connectors/registry.ts:15-17` (the registry really
never touches the DB or the key); `connectors/signature.ts:10-14` (raw-body HMAC with
constant-time compare, as claimed); `ai/tools/definitions.ts:7` (a tool absent from
the map really cannot run); `database/tenant.ts:1-9` (the helpers do what they say);
`messaging/queues.ts` (worker and API do share the definitions); `auth/session-token.ts`
(32 random bytes, only a SHA-256 persisted, constant-time compare); `auth/secret-box.ts`
(AES-256-GCM with a 12-byte random IV and a versioned envelope); `ai/providers/factory.ts`
(keys from server config, never from agent config).

`apps/worker/src/main.ts` has no comment asserting a scoping or authorization
guarantee. The one security-relevant claim it makes — that the domain logic lives in
`@firstelement/messaging` so the worker is not a second implementation that drifts —
is true, and this sweep is the check on it.

## 5. Swept and safe — `packages/**`

Every file below was read, not pattern-matched.

| Module | Verdict | Why |
|---|---|---|
| `database/tenant.ts` | **safe-because-correct** | holds the right helpers; comments accurate |
| `auth/policy.ts` | **safe-because-correct** — and it is the root | `can()`'s project check is correctly conditional on `resource.projectId`; its Javadoc says so |
| `auth/session-token.ts`, `secret-box.ts`, `password.ts` | safe | opaque 32-byte tokens, constant-time compare, AES-256-GCM, key-length validated |
| `messaging/inbound-pipeline.ts` | **safe-because-consistent** | builds the conversation from the payload's own scope (§2) |
| `messaging/identity-resolver.ts` | safe | every lookup carries org+project; creates carry org+project |
| `messaging/outbound-dispatcher.ts` | safe | message lookup carries org+project |
| `messaging/ai-dispatch.ts` | safe | conversation lookups carry org+project; `handOverToHuman` is a conditional `updateMany` with a version check |
| `messaging/channel-credentials.ts` | safe | decryption confined here; the `where` is caller-supplied but used only with the dispatcher's own scoped message id |
| `messaging/timeline.ts` | safe | writes the event's own org/project |
| `messaging/queues.ts` | safe | shared definitions, no data access |
| `ai/orchestrator/orchestrator.ts` | **safe-because-scoped-first** | `findFirst` binds org+project at line 82 and bails if absent; the bare `findUnique` at 392/471 re-reads `mode` on an already-proven row inside the reply transaction — the second mode check, which the comment at 100-102 explains and the takeover race needs |
| `ai/scoring/escalation-engine.ts` | **safe-because-scoped-first** | conversation scoped at 55-60 and 200-205; later reads key on `conversationId` |
| `ai/tools/executor.ts` (CRM tools) | safe | `get_customer`, `update_customer`, `add_tag`, `get_business_hours` all bind `ctx.organizationId` + `ctx.projectId`; `executor.test.ts` asserts the predicate |
| `ai/scoring/{rules,evaluator}.ts`, `ai/orchestrator/{prompt,memory}.ts` | safe | pure, no data access |
| `ai/embeddings/*`, `ai/providers/*` | safe | no database access |
| `knowledge-base/retrieval.ts` | safe | `WHERE c."organizationId" = … AND c."projectId" = …` before `ORDER BY embedding <=>` |
| `knowledge-base/ingestion.ts` | **safe-because-lookup** | derives org/project from the document row |
| `knowledge-base/storage.ts` | safe | generated keys; traversal guard verified correct |
| `routing/engine.ts` | safe | conversation, escalation, rules and candidates all scoped |
| `routing/{strategy,working-hours}.ts` | safe | pure |
| `routing/import.ts` | safe | **parser only** — no `prisma` reference at all |
| `connectors/signature.ts`, `telegram.connector.ts` | safe | raw-body HMAC + constant-time; Telegram via `secretsMatch` |
| `connectors/registry.ts`, `capabilities.ts`, `types.ts` | safe | no data access |
| `shared/*`, `config/*`, `events/*`, `ui/*` | safe | no data access |

**Nothing in `packages/**` bypasses `packages/database/src/tenant.ts`** — but note
*why*: no package under `ai`, `messaging`, `routing` or `knowledge-base` imports it
at all. Those packages have no `TenantContext`; they receive `organizationId` and
`projectId` as plain fields and bind them by hand. So the correct helpers cannot
help them, and **hand-binding is exactly where `clinic-scheduling.ts` failed**. That
is the systemic answer for the Spring-side guard rule's legacy twin.

## 6. Red-to-green

**A pre-fix run was done, and it is weaker than it looks.** Restoring
`clinic-scheduling.ts` and `executor.ts` from `git show HEAD:…` failed **17 of 21**
tests — but **8 of those were legitimate-path tests failing on the signature
change**, not on a leak, because `getClinicDoctors(prisma, projectId)` and
`getClinicDoctors(prisma, organizationId, projectId)` are different functions. I am
not counting that as a clean 17.

So non-vacuity is established at the predicate level, as the notes and clinic suites
did it. Two permanent teeth checks were added to the package's own suite:

- The pre-fix `where` construction (`{ isActive: true }` with `projectId` attached
  only when truthy, no tenant column) is reproduced against the same fixture and
  shown returning the foreign doctor — and returning **every** doctor in **every**
  tenant when the project id is empty. The fixed predicate returns nothing.
- The pre-fix `customer.findUnique({ where: { id } })` is shown returning
  `Foreign Patient` / `+966509999999`, and the fixed path is shown booking nothing
  and writing neither an appointment nor an audit row.

The **9 legitimate-path tests stayed green throughout**, which is what makes the
suite credible rather than merely red.

One honest note on my own process: three of my first assertions compared a denial
against a nonexistent-id denial for deep equality and failed. The only difference
was the caller's own id echoed back in the message. That is non-disclosure by
definition, so the assertions were wrong, not the code — they now normalise the
caller's own id out before comparing.

## 7. Not this class, but real — reported separately

You asked not to have a different risk hidden inside a "clean" report. Three
findings in this surface are **not** tenant defects, and I did not fix them because
they are outside the defect and outside these globs' intent:

1. **Debug logging left in the worker's message-ingest hot path.**
   `packages/messaging/src/inbound-pipeline.ts:162, 164, 169` —
   `console.log('LATEST CONVERSATION FOUND:', …)`, `'UPDATING TO OPEN'`,
   `'UPDATED CONVERSATION:', …`. Three unconditional debug lines on every inbound
   message that resumes a conversation, in the process the worker depends on. They
   log conversation ids and statuses, **not customer content**, so this is log
   volume and operational noise rather than a disclosure. It should come out.

2. **`apps/worker/src/main.ts:29-33` — a misleading job type.**
   `DocumentProcessingJob` declares `organizationId` and `projectId`; the handler
   uses only `job.data.documentId`. The type implies the worker validates scope from
   the payload when it never does. Harmless today (it is safe precisely because it
   ignores them) but it is a false signal to the next reader — the same failure mode
   as the comments in §4, expressed in a type. The fields should be dropped or the
   handler should assert they match the document.

3. **`packages/database/src/seed.ts:221` prints a password to stdout.**
   `console.log('Password :', DEMO_PASSWORD)`. Acceptable for a local seed script,
   and its Javadoc claims the script "is never wired into the API" — which I did not
   independently verify. If that claim is ever false, a credential is in the logs.

None of these three is a tenant-isolation defect, and none is a deployment blocker
on its own.

## 8. Files changed

| Path | Change |
|---|---|
| `packages/ai/src/tools/clinic-scheduling.ts` | **behaviour** — `ClinicScope`, `organizationId` on every function, all 8 lookups scoped, customer refusal |
| `packages/ai/src/tools/executor.ts` | **comment only** — false rule 2 corrected |
| `packages/ai/src/orchestrator/prompt.ts` | **comment only** — false injection claim corrected |
| `packages/ai/src/tools/clinic-scheduling.test.ts` | **tests** — scope-aware fakes, new signatures, 13 cross-tenant tests, 2 teeth checks |

`apps/worker/**` unchanged. `apps/api/**`, `backend/*` production sources, `ops/**`,
`tests/contracts/**`, `frontend/**` and the migration ledger untouched.

## 9. Commands

```powershell
# the AI clinic tools — 34 tests
npx vitest run packages/ai/src/tools/clinic-scheduling.test.ts packages/ai/src/tools/executor.test.ts

# every package unit test — 240 tests, 18 files
npx vitest run packages/

# whole-repo typecheck, all workspaces
npx tsc -b tsconfig.build.json          # exit 0

# lint: 0 errors against a 3-error baseline in packages/ai
npx eslint packages/ai

# the phases 24-26 harness, unchanged and still green
npx vitest run --config backend/crm-core/src/test/legacy-notes-project-scope/vitest.legacy-notes.config.ts
  # 85 passed
```

## 10. Residual risk

- **`apps/worker` is safe by a convention, not by a type.** Every payload id is bound
  into its predicate by hand, in six packages. Nothing prevents the next one from
  being written without it, and there is no test that would catch a future omission
  at the worker boundary. That is the strongest argument for the legacy guard rule.
- **The AI clinic tools remain a prompt-injection surface**, now bounded to the
  caller's own tenant. An injected model can still ask for a row it does not own and
  be told "not found" — that is bounded and auditable, which is what the corrected
  comment now says.
- **`packages/ai/src/tools/clinic-scheduling.test.ts` uses in-memory Prisma doubles.**
  A real-database run with two seeded tenants is still owed, and it is the check that
  would also confirm no clinic row in any tenant was ever written with a foreign
  `organizationId` — the question phase 26's audit-gap analysis raised.
- **Three non-class defects in §7 are open.**
