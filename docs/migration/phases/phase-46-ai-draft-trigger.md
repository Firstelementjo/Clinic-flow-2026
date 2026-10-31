# Phase 46 — the inbound AI draft trigger

**The gap this closes.** `AiOrchestrator` had zero production callers. A conversation in AI mode
received no answer at all — a named product capability that produced nothing. Phase 46 connects an
accepted inbound message to a governed AI turn, and stops there.

**The ruling, honoured.** The orchestrator decides and records; **a human still presses send**. No
`AiSendService` call, no staged message, no `workflow_runs` row, no outbound intent. The draft is
visibly a draft and cannot reach a customer.

---

## 1. Where the trigger lives, and why the module edges allow it

**Trigger (producer): `backend/channels/.../service/AiResponseTrigger.java`**, called from
`InboundMessageJobHandler.handle`.

`:backend:channels` is depended on **by** `:backend:ai-rag` through `crm-core` and `worker-runtime`.
A dependency the other way — "channels asks the AI module whether this conversation wants a run" —
would be a cycle, and `check-architecture.ps1` **FE-ARCH-001** would refuse it. So the producer
answers from the fact it already owns: `conversations."mode"` is a column on the row the inbound
pipeline has just written counters for, and `QueueName.AI_RESPONSE.wireValue()` is a string in a
closed enum. **No `ai-rag` type appears anywhere in the production trigger path**, and none is needed.

**Consumer: `backend/ai-rag/.../drafts/AiResponseJobHandler.java`**, implementing the `worker-runtime`
`JobHandler` SPI.

The arrow cannot point the other way — `worker-runtime` is depended on *by* `ai-rag`, so a consumer
there could not see the orchestrator. `ai-rag` already declares
`api(project(":backend:worker-runtime"))` and `api(project(":backend:crm-core"))`, so it can implement
the SPI and read conversations and messages **with no new module edge**. `QueueName.AI_RESPONSE`
already existed; the hole was that nothing drained it.

**Read route: `backend/ai-rag/.../web/AiDraftController.java`**, in `com.firstelement.airag.web`.

**FE-WEB-002** is the rule that constrained placement: *"every `@RestController` lives in a `*.web`
package"*. `ai-rag` had no web package, and adding one under its existing name satisfies the rule
**without a new forbidden edge** — `inbox-helpdesk` and `app-bootstrap` were therefore not needed and
were not touched. FE-SEC-002 (no client-bound tenant identifier) passes because the route has no
tenant parameter at all; FE-WEB-001 passes because the package contains no JDBC. Springdoc was added
to `ai-rag`'s `build.gradle.kts` (same artifact the other web modules declare; Gradle deduplicates).

Verified by:

```
.\ops\quality\checks\check-architecture.ps1
  architecture gate: 489 production and 181 test Java sources
  FE-ARCH-001/002/003/004  ok, 0 violations
  FE-WEB-001/002           ok, 0 violations
  FE-SEC-002/001, FE-LOG-001/002, FE-TIME-001  ok
  VERDICT: PASS
```

---

## 2. How "nothing was sent" is proven

Not by a log line. Every negative reads the database, in
`AiDraftTriggerIntegrationTest > a draft is a draft: nothing is sent`:

| Assertion | Query |
| --- | --- |
| No AI-authored message exists | `select 1 from messages where "authorType" = 'AI'` → **0** |
| No delivery intent exists | `select 1 from outbox_events where "eventType" = 'outbound-send'` → **0** |
| No workflow run exists | `select 1 from workflow_runs` → **0** |

The draft is stored as a **`notes` row** (`resourceType = 'AiDraft'`), which is structurally
incapable of delivery: it is not a `messages` row, so `OutboundSendJobHandler` can never see it. The
storage shape enforces "a human presses send" rather than relying on remembering it.

**`workflow_runs` deliberately not created.** `AiUsageAccounting` already bills the run through
`aiRequest`/`aiToken` with no cost parameter. `UsageDimension.WORKFLOW_RUN` is a **billable** dimension,
so a workflow run would be a second accounting path for the same act. No second path was added.

**The answer-text problem, and why `notes`.** `ai_runs` carries the *governance* of a turn (status,
decision, provider, model, latency, refusal code, and the join key for every `ai_run_*` child) and
`AiTraceRepository` already writes all of it. It carries **no answer text**, deliberately — ADR 0007
Part 2 rule 4 keeps prose off the trace tables, and the run's in-memory `answer` is never persisted
there. The schema already has the right home: `notes` is tenant- and project-scoped, polymorphic
(`resourceType`, `resourceId`), and has an index built for this exact access pattern. **No new table,
no migration, and no change to `backend/database/**`.**

---

## 3. The human-takeover guard

Reproduced from `packages/ai/src/orchestrator/orchestrator.ts`, whose comment names the race: *"The
mode is re-read inside the same transaction that writes the message. Checking once at the start and
trusting it is the race that lets an AI reply land after a human took over (plan §10)."* That is why
the legacy reads the mode **twice**, and so does this.

1. **Producer** (`AiResponseTrigger`) — skips enqueueing when the conversation is not AI-owned. Avoids
   pointless work; not sufficient alone.
2. **Consumer, early** (`AiResponseJobHandler.handle`) — re-reads before the claim, returns `skipped`.
3. **Consumer, decisive** (`storeUnlessTakenOver`) — re-reads **inside the transaction that stores the
   draft**. Not AI ⇒ no draft is written, the run is finished `NO_ACTION` with reason
   `HUMAN_TAKEOVER`, and the answer is **discarded rather than parked** — a draft nobody may act on is
   indistinguishable from one never produced, and offering it later would resurrect a decision a human
   already made.
4. **Read side** (`AiDraftRepository.latestOfferableDraft`) — joins `conversations` and requires
   `mode = 'AI'`, so a takeover landing *after* the draft was stored also removes it from offer.

Step 4 exists because step 3 is a point in time. Both are asserted.

---

## 4. Exactly one run and one charge per inbound message

`AiRunGuard` arbitrates through **`idempotency_records`**, whose
`(organizationId, scope, idempotencyKey)` is UNIQUE, with `ON CONFLICT DO NOTHING` and the
affected-row count as the verdict — the database decides, not handler memory, which is what the
`JobHandler` contract demands ("the uniqueness that makes it safe lives in PostgreSQL"). A prior
`SELECT` would race and produce a second provider call **and a second charge**.

Proven: `the same provider event twice yields one run and one charge` → 1 `ai_runs`, 1 `AI_REQUEST`,
1 `AI_TOKEN`, 1 draft.

**`FAILED`, not `COMPLETED`, is the terminal claim state.** `idempotency_records_completed_result_check`
is the biconditional `("state" = 'COMPLETED') = ("responseStatus" IS NOT NULL)`, and its stated
meaning is "a finished record must be able to say what it produced". A job is not an HTTP request and
has no status, so claiming `COMPLETED` would require inventing one — precisely "a stored answer the
operation never produced". The terminal fact is carried by `resourceId` naming the run. **This was
found by a test, not by review:** the first implementation used `COMPLETED` and was refused by the
constraint on the happy path.

---

## 5. `customerVisibleRequiresApproval` — confirmed, and it does not block a draft

Required confirmation. Traced to its only consumer:

- Read into `AiPolicySnapshot.customerVisibleRequiresApproval`, and read by
  `requiresApprovalFor(ActionClass.CONSEQUENTIAL)`.
- **`requiresApprovalFor` has zero callers repo-wide** (verified by search for `.requiresApprovalFor(`).
- `AiToolExecutor` gates on `definition.consequential()` — the **tool's own declared** action class —
  not on this policy flag.
- `AiOrchestrator.run` never reads it.

So the flag governs **the send**, and since nothing is sent, it does not block producing a draft. No
workaround was needed and none was applied. **Recorded, not fixed**: the flag currently gates nothing
at all. Phase 22, 27, 30, 31, 34 and 35 all note the same default; it remains a design question for
the orchestrator, not a defect this phase introduced.

---

## 6. Tenant and project scope

- **Route**: `TenantContextHolder.get()` only. No `organizationId`, `tenantId` or `projectId`
  parameter exists on it (FE-SEC-002 proves it by text scan).
- **Project** is derived from the conversation row, then re-validated by
  `TenantQueryGuard.assertRowScope`. The read-then-check path exists for exactly this case, per its
  own Javadoc.
- **Consumer**: the job's claimed scope; the job's `projectId` is a *request*, checked by the guard
  before any SQL. The envelope names ids only — customer text never enters the queue.
- **Run attribution**: `PrincipalType.AI_AGENT` always, never the worker or a user. A run attributed to
  a person is an approval that never happened.

### Cross-tenant negatives (all passing)

| Test | Assertion |
| --- | --- |
| `another tenant's conversation is the same 404, with the same body` | `code`, `message`, `details` identical to a nonexistent id; both `NOT_FOUND` / `"Resource not found"` |
| `a tenant-A actor cannot read a tenant-B draft through the service` | empty |
| `a tenant-A actor cannot cause a run on a tenant-B conversation` | 0 runs in A **and** in B |
| `an envelope naming a conversation in another tenant is refused permanently` | `PermanentFailureException` / `NO_SUCH_CONVERSATION` |
| `a project the caller has left is answered like a conversation that is not there` | empty, indistinguishable |

The uniform 404 is the Phase-33 clinic shape. The 404 bodies are compared **field by field**, not as
raw strings: the error body carries a `timestamp`, so two byte-identical refusals differ by a clock
reading — comparing raw strings was a genuine first-draft mistake here.

A finding worth recording: a project the caller has left produces an **empty result**, not a thrown
denial, because `CrmQueryScope.accessibleProjects` already narrows the predicate. That is *stronger*
than an exception — there is no distinct status to probe — and the test asserts the stronger property.

---

## 7. Contract discipline (ADR 0016/0017)

`GET /api/v1/ai/drafts/conversations/{conversationId}` — declared `200, 400, 401, 403, 404`.

Each derived from what the route does, and **executed over a real socket** before being declared:

| Refusal | Derived from | Executed by |
| --- | --- | --- |
| 401 | path is not in `PUBLIC_PATHS` | `no session is 401` |
| 403 | `@RequirePermission(AI_RUN_READ)` | `the service enforces the permission` |
| 400 | typed `UUID` path variable | `an id that is not a UUID is 400` |
| 404 | names a resource; also the non-disclosing foreign answer | `no draft is 404`, `another tenant's …` |

**Deliberately not declared**: 415 (consumes no body), CSRF (a GET), 409 (no optimistic-concurrency
check), 202 (completes in-request). No refusal here is pasted.

Declarations are read from the annotations springdoc actually serves. Fetching `/api/v1/openapi` from
`ai-rag`'s test app was attempted first and **cannot work**: that path is `app-bootstrap`
configuration on the management port, and `app-bootstrap` is not this phase's to touch. The
`ClinicContractHttpIntegrationTest` precedent asserts declarations by reflection for the same reason.

**`AI_RUN_READ`, never `AI_SEND`.** ADR 0010 §2: a permission is named after what it authorizes.
Reading a governed run is not speaking to a customer, and Phase 22 records that a send which reused
the read-only permission is exactly how `AI_SEND` came to exist. A principal holding `AI_RUN_READ`
but not `AI_SEND` can see a draft and still be unable to send it.

**Route inventory**: `check-openapi-contract.ps1` failed correctly on the added route; regenerated with
`export-openapi-paths.ps1`. Verified by set comparison: **1 route added, 0 removed** (162 → 163). The
remaining diff is generator ordering. `tests/contracts/openapi.json` and `expected-suites.json` were
not touched.

---

## 8. Tests

All new tests run against the real Flyway chain in Testcontainers. **No skips, no `@Disabled`.**

| Suite (fully qualified) | `@Nested` display names | Module | Stage | Tests |
| --- | --- | --- | --- | --- |
| `com.firstelement.airag.AiDraftTriggerIntegrationTest` | *the conversation mode decides whether a run happens*; *exactly one run per inbound message*; *a draft is a draft: nothing is sent*; *a human takeover withholds the draft*; *a policy refusal is an outcome, not an exception*; *the handler's own refusals* | `:backend:ai-rag` | integration | **14** |
| `com.firstelement.airag.AiDraftRouteHttpIntegrationTest` | *the route answers what it is observed to answer*; *cross-tenant is not probeable*; *the takeover guard is visible at the read*; *the declarations* | `:backend:ai-rag` | http integration | **15** |
| `com.firstelement.channels.AiResponseTriggerIntegrationTest` | *the conversation mode decides whether an intent is written*; *the intent is an envelope, not content*; *the handler wiring, not just the trigger class*; *the trigger refuses rather than guessing* | `:backend:channels` | integration | **10** |

Helper (no tests): `com.firstelement.airag.PrincipalScope`.

### Required-test coverage

| Required | Where | What is asserted |
| --- | --- | --- |
| HUMAN mode → no run | `ModeDecidesTheRun` | `runCount(ORG_A) == 0` **and** `countFor(conversation) == 0` — state, not a log line |
| AI mode → one run, nothing sent | `ModeDecidesTheRun`, `NothingIsSent` | 1 run; 0 AI messages; 0 outbound intents; 0 `workflow_runs`; draft stored |
| Redelivery idempotent | `Idempotency` | 2nd delivery → `duplicate`; 1 run, 1 `AI_REQUEST`, 1 `AI_TOKEN`, 1 draft |
| Cross-tenant (read) | `CrossTenant` | 4 tests above |
| Cross-tenant (cause a run) | `CrossTenant`, `HandlerRefusals` | 0 runs in either tenant; permanent refusal |
| Takeover | `HumanTakeover`, `TakeoverIsVisible` | withheld before the run; withheld after; not offered at read |
| Refusal | `Refusal` | `refused reason=RETRIEVAL_EMPTY`, disposition `REFUSED`, no draft, **completed job not an exception** |
| Red-to-green | `HandlerWiring` | see below |

### Red-to-green

First attempt **failed to go red** — the suite called `AiResponseTrigger` directly and never
exercised the handler, so removing the wiring call changed nothing. That was a **real coverage gap**,
not a flaky test. Fixed by adding the `HandlerWiring` group that drives
`InboundMessageJobHandler.handle` with a real inbound envelope.

Second attempt, with the trigger call replaced by `boolean queued = false;`:

```
an inbound job on an AI conversation enqueues the AI turn            FAILED
a redelivered inbound job does not queue a second AI turn            FAILED
an inbound job on a HUMAN conversation enqueues nothing               PASSED   <- correct: negative
```

The HUMAN case correctly stayed green. The call was restored and the suite returned to 10/10.

### Commands

```powershell
$env:JAVA_TOOL_OPTIONS="-Djava.net.preferIPv4Stack=true"

.\gradlew.bat :backend:ai-rag:test --tests "com.firstelement.airag.AiDraftTriggerIntegrationTest"
.\gradlew.bat :backend:ai-rag:test --tests "com.firstelement.airag.AiDraftRouteHttpIntegrationTest"
.\gradlew.bat :backend:channels:test --tests "com.firstelement.channels.AiResponseTriggerIntegrationTest"
.\gradlew.bat :backend:ai-rag:test :backend:channels:test      # module gates
.\gradlew.bat :backend:app-bootstrap:test
.\gradlew.bat test                                             # whole backend
.\ops\quality\checks\check-architecture.ps1
.\ops\quality\checks\check-openapi-contract.ps1
```

Note: `gradlew.bat --tests X` with **no task name** prints help and exits **0**. Every run above names
its task. `-Dorg.gradle.jvmargs=…` on the command line is misparsed by PowerShell; the value is
already in `gradle.properties`.

### Results

- `:backend:ai-rag` — **252 tests**, 0 failures, 0 skipped
- `:backend:channels` — **115 tests**, 0 failures, 0 skipped
- Whole backend — **1872 tests, 0 failures, 0 skipped** (baseline 1833; **+39**, exactly the new tests)

`check-architecture.ps1` **PASS** · `check-openapi-contract.ps1` **PASS** (163 routes).

---

## 9. Defects the tests found in my own work

Recorded because each would have shipped as a runtime failure, not a compile error:

1. **`ON CONFLICT` against a non-existent unique index.** I claimed a partial unique index on
   `notes(organizationId, resourceType, resourceId)`; only a **non-unique** index exists. The insert
   would have failed on the happy path. The misleading comment now states the correction.
2. **uuid bound as varchar.** `idempotency_records."resourceId"` is `uuid`; a String binding produced
   `column "resourceId" is of type uuid but expression is of type character varying`.
3. **`COMPLETED` vs `FAILED`** — the biconditional described in §4.
4. **Column not in the projection.** `latestOfferableDraft` selected `resourceId` but the mapper read
   `id` → 500 on every read of an existing draft.
5. **Join condition I wrote and immediately corrected**: `c."id" = n."resourceId"::uuid` compared a
   conversation id to a **run** id. Caught on review before it ran.

---

## 10. Observed, not fixed

1. **`requiresApprovalFor` has zero callers** (§5) — `customerVisibleRequiresApproval` gates nothing.
   Reported by Phase 22 and others; unchanged.
2. **`AiSendService` still has zero production callers.** Phase 46 deliberately does not reach it —
   the ruling forbids the send. The two-phase send remains unreachable, exactly as before. What is new
   is that the AI now produces something for a human to act on.
3. **`InboundMessageService.hasActiveAgent` still returns `false`**, so a conversation created by the
   inbound pipeline starts in `HUMAN` and will not receive an automatic draft until an operator moves
   it to AI. This is the documented Phase-10 seam in `inbox-helpdesk`, outside this phase's ownership.
   **It is the single thing standing between this work and a customer-visible draft in production**, and
   it is deliberately not worked around.
4. **FE-WEB-003 advisory (1 finding)** — pre-existing, unchanged by this phase.
5. The `AI_RESPONSE` queue is `QueueClass.DEFAULT`; a slow provider therefore competes with
   notification fan-out. Revisit if the draft latency becomes visible to operators.

---

## 11. ADR position

**ADR 0007 Part 2** — satisfied *more strictly than a send would satisfy it*. Nothing is customer-
visible; a consequential action still requires human approval; and here there is no action at all.
Rule 2 (human takeover wins) is enforced at four points (§3). Rule 7 (every action attributable) holds:
`ai_runs` + `ai_provenance_records` name agent, version, prompt, model, provider, retrievals, tokens
and cost, and the draft's `runId` is the join key.

**ADR 0016/0017** — observed, then declared; no speculative refusal; no document edited to match a bug.

---

## 12. Follow-ups for the orchestrator

1. **Register the three suites** with floors from the table in §8.
2. **Promote `tests/contracts/openapi.json`** — the served document now carries the draft route. The
   schema-bearing document was deliberately not edited here.
3. **Decide on `hasActiveAgent`** (§10.3). Until then this feature is reachable only for conversations
   an operator has explicitly placed in AI mode.
4. **Decide on the send** — whether a human-confirmed send is built on `AiSendService` (which needs a
   `workflow_runs` row and therefore an orchestrator decision on the double-accounting question) or
   designed fresh. **Not attempted here, by ruling.**