# FirstElement Migration Ledger

The authoritative record of migration state: what phase owns what, what is
proven, what is provisional, and what is still legacy. Updated by the
orchestrator at every phase gate. A row is only marked **GREEN** with the
exact evidence recorded in that phase's report.

Legend: `LEGACY` untouched NestJS/React/Prisma · `PROVISIONAL` implemented but a
later phase owns the real version · `GREEN` implemented and gated ·
`PENDING` not started · `DEFERRED` explicitly out of scope.

---

## 1. Phase state

| Phase | Scope | State | Gate evidence |
| --- | --- | --- | --- |
| 0 | Inventory, ADRs, baseline | GREEN | `docs/migration/phases/phase-00.md`; 274 tests, 0 failures with `DATABASE_URL` |
| 1 | Spring Boot foundation | GREEN | `phase-01.md`; `./gradlew build`; live liveness/readiness UP |
| 2 | Database + Flyway contract | GREEN | `phase-02.md`; restore rehearsal PASS, fingerprint match |
| 3 | Tenant context + Spring Security | GREEN | `phase-03.md`; 149 tests, 0 skipped |
| 4 | CRM core + inbox slice | GREEN | `phase-04.md`; 311 tests, 0 skipped, legacy fixtures match |
| 5a | Outbox/job/idempotency schema | GREEN | `phase-05a.md`; 316 tests, 0 skipped; 2-claimer test |
| 5b | Outbox dispatcher, workers, channels, realtime | GREEN | `phase-05b.md`; 502 tests, 0 skipped; 0 violations from `no-hidden-skips` |
| 6a | Workflow/SLA/collision schema | GREEN | `phase-06a.md`; 12 fixture scenarios, 0 skipped |
| 6 | Workflows, SLA, scoring, escalation, routing | GREEN | `phase-06.md`; 231 tests; human-takeover gate passes |
| 7-10a | Admin/export/report/finance/AI schema | GREEN | `phase-07-10a.md`; 95 tables, 402 CHECKs, 18 fixture scenarios |
| 7 | Admin control plane | GREEN | `phase-07.md` |
| 8 | Finance ledger + read models | GREEN | `phase-08.md` |
| 9 | Reporting + CSV/DOCX export | GREEN | `phase-09.md` |
| 10 | Spring AI + governed RAG | GREEN | `phase-10.md` |
| 11 | Angular route migration | GREEN | `phase-11.md`; 281 unit tests, 0 skipped; **402 browser journeys passed, 0 failed / 0 flaky / 0 skipped** across ar/en x desktop/tablet/mobile; generated client matches the contract (128 paths, 140 schemas) |
| 12 | Dual-run, cutover, decommission | REHEARSED, NOT DECOMMISSIONED | **The dual-run rehearsal ran on 2026-10-11 and 7 of 8 shadow checks PASS.** Both harness defects that had blocked this stage for its whole life are fixed (see `docs/migration/phases/phase-12-dual-run-rehearsal.md`). CHK-2 fails on two REAL divergences that need an owner decision rather than a code fix: case resolution answers 204 in Spring and 200 with a synthesized body in legacy; and the Spring note list deliberately refuses an unscoped read the legacy runtime allows. **Decommission is NOT declared and cannot be from here** - it requires two agreed observation windows and sign-off from the product, operations, security and finance owners. The legacy runtime is untouched and remains frozen reference. |
| 14 | Customer reference namespace (P0) | GREEN | `phase-14-customer-reference-code.md`; ADR 0014 |
| 15 | Inbox work queue | GREEN | `phase-15-inbox-work-queue.md`; ADR 0015 |
| 16 | Export catalogue | GREEN (capability only) | `phase-16-export-catalogue.md`; a catalogue-only client can build a request, but the catalogue is **empty on a real database** — see section 8 |
| 17 | Inbox counts contract | GREEN | ADR 0015; the counts response names every view |
| 18 | HTTP error mapping | GREEN | `phase-18-error-mapping.md` |
| 19 | Advice scope + order | GREEN | `phase-19-advice-scope-and-order.md`; `check-advice-order` gate, 11/11 self-test expectations |
| 30 | Migration chain clean bootstrap | GREEN | `phase-30-migration-chain-bootstrap.md`; the chain applies to a database created from empty, in ONE session, reproducing `postBoot` exactly |
| 31-32 | Report authoring + contract refusals | GREEN | `phase-31-report-authoring.md`, `phase-32-authoring-contract-and-refusals.md` |
| 33-34 | Clinic role control; incident forensics | GREEN | `phase-33-clinic-permissions.md`, `phase-34-incident-forensics.md`; the forensic scanner returns INCONCLUSIVE on the workspace database and says so |
| 35 | Appointment tenant foreign keys | GREEN | `phase-35-appointment-tenant-constraints.md`; all four of `appointments`' baseline FKs were single-column. FKs 223 -> 227 |
| 36 | Provider webhook authentication | GREEN | `phase-36-webhook-authentication.md`; `/webhooks/**` answered 403 then 401 for **every** inbound provider message. ADR 0016 closed for status codes |
| 37 | Clinic + analytics read model | GREEN (read only) | `phase-38-clinic-staff-and-contract-truth.md` §4; seven read-only routes, the first clinic counterpart in the new stack |
| 38 | `CLINIC_STAFF`, roster audit, contract truth | GREEN | `phase-38-clinic-staff-and-contract-truth.md`; ADR 0016 refusal direction |

**Current aggregate gate:** **1833 backend tests, 0 failures, 0 skipped, 269 suites**; 299 Angular
unit tests, 0 failures; openapi-contract **162/162**; openapi-document PASS; clean bootstrap
reproduces `postBoot` on PostgreSQL 16.15. Chain is **26 migrations**, fingerprint
`fd0bdc74503eb872bcc478c0be43a8c8`. `firstelement` remains 45 tables and deliberately unstamped.

---

## 2. Module ownership

| Target module | Legacy source | Owner agent | State |
| --- | --- | --- | --- |
| `backend/shared-kernel` | — (new) | orchestrator | GREEN |
| `backend/database` | `packages/database` | database | GREEN (baseline + 3 additive) |
| `backend/tenant-security` | `packages/auth`, `apps/api/src/auth` | security | GREEN |
| `backend/identity-access` | `apps/api/src/{organizations,projects,employees}` | security | GREEN |
| `backend/crm-core` | `apps/api/src/customers`, `packages/shared` | crm | GREEN |
| `backend/inbox-helpdesk` | `apps/api/src/{conversations,cases,notes}` | crm | GREEN |
| `backend/worker-runtime` | `apps/worker`, `packages/messaging/src/queues` | worker | GREEN (Phase 5b) |
| `backend/channels` | `packages/connectors`, `apps/api/src/channels` | worker | GREEN (Phase 5b) |
| `backend/notifications-realtime` | `apps/api/src/notifications` | worker | GREEN (Phase 5b) |
| `backend/workflows-sla-routing` | `packages/routing`, `packages/ai/src/scoring` | workflows | GREEN (Phase 6) |
| `backend/administration` | distributed admin pages | admin | PENDING |
| `backend/reporting-finance` | `apps/api/src/analytics` | finance | PENDING |
| `backend/exports` | none | exports | PENDING |
| `backend/knowledge-ai` | `packages/ai`, `packages/knowledge-base` | ai | PENDING |
| `frontend/*` | `apps/web`, `packages/ui` | angular | PENDING |
| Legacy Node/React/Prisma runtime | all of `apps/`, `packages/` | frozen reference | LEGACY |

---

## 3. Schema ledger

| Version | Applied to `firstelement`? | Content |
| --- | --- | --- |
| `20260814000000` | no — stamped on first boot | baseline of the verified live schema (45 tables, 23 enums) |
| `20260814000100` | no | HNSW cosine index on `document_chunks` |
| `20261004000100` | no | `OutboxState` + `outbox_events` |
| `20261004000700` | no | `JobAttemptOutcome`, `DeadLetterStatus`, `job_attempts`, `job_dead_letters` |
| `20261004001000` | no | `IdempotencyState` + `idempotency_records` |
| `2026…` (14-digit, greater than `20261004001000`) | no | Phase 6a workflow/SLA calendar slice — in progress |

`firstelement` remains unstamped by deliberate decision (ADR 0005 §2). The first
Flyway-enabled boot is a scheduled, backed-up act.

---

## 4. Provisional behaviour that must not be reported as final

| Item | Current state | Closes in |
| --- | --- | --- |
| Reply idempotency via `messages.metadata` | authoritative for replies only (ADR 0006 §1) | CRM write-path cutover |
| Concurrency token = `updatedAt` | no integer `version` column exists (ADR 0006 §2) | before CRM write cutover |
| `CaseSolutionRetriever` seam | no pgvector retrieval (ADR 0006 §4) | Phase 10 |
| New conversations start `HUMAN` | `hasActiveAgent` does not exist (ADR 0006 §4) | Phase 10 |
| Clinic/appointments permission decorators | rely on `authenticated()` + service checks (ADR 0005 §7) | clinic module migration |
| `MockChannelProbe` seam | connector port landed in Phase 5b; confirm ownership moved out of `inbox-helpdesk` (ADR 0006 §3) | Phase 6 |
| Bridged realtime frames carry ids only | a cross-instance frame has fewer display fields than a local one, so the client must resync (ADR 0007 §2 consequence) | Phase 11 |
| Audit/security stream split | single `audit_logs` table by convention (ADR 0005 §5) | Phase 7 (`V202610040008`) |
| Login rate limiting | per-process (ADR 0005 §6) | before first multi-replica deploy |
| `views` (saved views) surface | not migrated (ADR 0006 §6) | Phase 9 |
| Operator surface on management port | implemented (ADR 0005 §8) | — |

---

## 5. Legacy runtime state

The NestJS API, React web app, BullMQ worker, and Prisma schema are **frozen**,
not deleted. They remain the behavioural reference for phases that have not yet
migrated a slice, and the strangler rollback target.

Baseline health of the legacy runtime as measured on 2026-10-04:
`yarn build` PASS, `yarn typecheck` PASS (after the Phase-0 fix), `yarn lint`
FAIL (118 errors / 39 warnings, pre-existing), `yarn test` PASS with
`DATABASE_URL` and `REDIS_URL` set (274 passed / 5 skipped).

---

## 6. Stop conditions currently open

- Backup encryption mechanism — awaiting an operations decision (ADR 0005 §4).
- `CREATE INDEX CONCURRENTLY` vs maintenance window for `V202610040009` — open.
- Retention for the outbox/job/idempotency tables — **decided** in ADR 0008 §2;
  the purge job itself is Phase 7 work and must ship disabled until legal-hold
  state exists.
- Org-wide outbox claim sweep — **decided** not to implement (ADR 0008 §1). The
  supporting index is intentionally unused and documented, not dropped.
- Finance meaning of revenue/billable amount — **decided** in ADR 0007 Part 1
  (cost stored, revenue derived via a versioned rate card).
- AI provider data retention and customer-visible side-effect policy —
  **decided** in ADR 0007 Part 2 (AI may reply only in AI mode; consequential
  actions need human approval).

---

## 7. Contract accuracy defects open (must close before Phase 12 cutover)

All five are asserted as failing frontend or contract tests, so they cannot be
forgotten by a green build. **Phases 36 and 38 closed 1, 2, 3 and 5.** 4 remains open.

1. ~~**Success status codes are assumed, not observed.**~~ **CLOSED (Phase 36).**
   Every declared success code is now an OBSERVED one, recorded with its evidence in
   `ObservedRouteStatuses`. `202` appears for the first time. Notably the agent **overrode the ADR's
   own guess list**: the four finance routes the ADR proposed as `202` candidates compute inline and
   correctly stay `200`. `POST .../jobs/{id}/replay` is the one genuine split — an applied replay
   leaves the row `PENDING` (202), a refused one completed on the request (200) — and both branches
   are observed over HTTP and assert database state.
2. ~~**No error responses are documented.**~~ **CLOSED (Phase 36), with one defect found on review.**
   536 four-hundred declarations across 162 operations, **derived per operation** by `RefusalRules`
   rather than pasted on. Reading the promoted document showed `POST /api/v1/auth/login` declaring no
   `401` — a public route that genuinely returns `401 INVALID_CREDENTIALS`, omitted because the rule
   read 401 off `PUBLIC_PATHS` and treated "no session required" as "cannot answer 401". Fixed, and
   the two tests that encoded the misconception were corrected. The only operation declaring no
   refusal is `GET /api/v1/status`, which is public and answers only `200`.
3. ~~**`429` sends no `Retry-After`.~~** **CLOSED (Phase 38).** The runtime has always sent it
   (`SecurityExceptionAdvice:159`); only the document was silent. The header is now declared on the
   login `429` as `integer`/`int32`, scoped to the login limiter rather than to every 429.
4. ~~**`limit` is `string` on three routes and `integer` on six.**~~ **CLOSED (Phase 40).** All
   eleven `limit` parameters now publish `integer`, carried by the composed `BoundedLimit`
   annotation. This entry read "Still open" long after Phase 40 closed it: a ledger that disagrees
   with the artefact is worse than one that is merely incomplete, because it is trusted. It is
   verifiable in one command against `openapi.json` and should have been checked rather than
   inherited.
   *The server still clamps. The clamp is a tolerance, and a tolerance is not part of a client's
   contract (ADR 0019).*
5. ~~**139 responses declare `*/*`**~~ **CLOSED (Phase 36/38).** JSON is now the **majority** media
   type (576 declarations against 158 wildcard), and the SSE stream names `text/event-stream`.

**A note on how these were closed, because it is the part worth keeping.** Three of them were
*characterisation tests asserting a defect was still present* — `expect(raw).not.toContain(...)` —
so fixing the defect turned them red. The response was to **invert them into regression guards with
floors taken from the Phase 36 export**, not to delete them. A characterisation test's job ends when
its defect is fixed; deleting it would have lost the record, and shrinking it would have lost the gate.

---

## 7a. Parity and observability defects closed

- **PN-08, the finance parity flag, was unfalsifiable.** Its "generation 1 stayed frozen" conjunct
  read generation 1's event count out of the **perturbed** capture and compared that same capture
  against itself: true by construction, and true when no generation-1 row existed at all. A
  recomputation that rewrote an already-computed snapshot — the exact defect the check exists to
  catch — passed. The verdict now compares the **pre**-perturbation capture against the post one, and
  `pn08-proof.test.mjs` runs the verbatim pre-fix expression to show it reporting `proved=true` on
  both bad inputs. **The finance-equivalence gate could not certify finance until this was closed.**
  It is wired into `shadow-parity` as that stage's **first** command, ordered before the harness so a
  broken verdict module fails a step that names it rather than hiding inside a red the stage already
  expects.
- **Provider webhooks were unreachable.** `/webhooks/**` was absent from `PUBLIC_PATHS`, so every
  inbound Meta or Telegram message was rejected — first `403 CSRF_TOKEN_INVALID`, then `401`. Neither
  refusal was ever the provider's signature, and **no HTTP test had exercised the path**, so neither
  the outage nor the intent had ever been proven. `permitAll` alone fixes nothing: CSRF rejects
  first. The authentication requirement is untouched — verification stays unconditional at the
  intake service, and absent, malformed, tampered and wrong-secret payloads are all refused and never
  queued. Widening the permit set exposed a second defect, now closed: the unsigned Mock endpoint was
  gated only at startup, and a default-off flag nothing reads per request is not a control.

---

## 8. Product gaps open

- ~~**No clinic route exists.**~~ **PARTIALLY CLOSED (Phase 37).** The Spring backend had no clinic
  *or* analytics module at all, so those journeys were not comparable. Seven read-only routes now
  exist (`/clinic/doctors`, `/clinic/procedures`, `/clinic/appointments`, their addressed forms, and
  `/clinic/analytics`). **Every metric drills to source rows**: each group publishes `sourceTable`,
  window, derivation, per-row `sourceRowIds` and a `drillThroughPath`, and the fixture re-derives the
  population with **independent SQL**, so a wrong derivation fails even when the number happens to be
  right. **Still open, deliberately not built:** revenue, AI conversion rate, utilisation %,
  satisfaction and daily trends. Each needs a finance definition (ADR 0007) or clinic policy that is
  not a row — utilisation needs a denominator (working hours / slot length / days) that is a business
  decision. Refusing to invent them is the correct outcome, not an omission.
  **Also open:** `DOCTOR_READ_MANAGEMENT` and `REMINDER_READ` do not exist in the permission enum
  even though Phase 33 put clinician contact behind the former, so contact details cannot be exposed;
  and `APPOINTMENT_READ` is Spring-only with no legacy counterpart, though its grant claims to be
  ported verbatim.
- **The AI send path is unreachable, not absent.** CORRECTED 2026-10-08: an earlier
  entry here said `AiSendService` does not exist. It does - it lives in
  `workflows-sla-routing`, not `ai-rag`, and is race-tested.
  **(a) HALF CLOSED (Phase 46).** An inbound message on an AI-mode conversation now runs one
  governed `AiOrchestrator` turn and records a human-reviewable **draft**, read through
  `GET /api/v1/ai/drafts/conversations/{conversationId}`. `AiOrchestrator` is no longer
  reference-free. The ruling was deliberate and is recorded in the Phase 46 report: **a human
  presses send**, so there is no `AiSendService` call, no `outbound-send` intent and no
  `workflow_runs` row - the last because `UsageDimension.WORKFLOW_RUN` is billable and
  `AiUsageAccounting` already bills the run, so a workflow run would be a second accounting path
  for the same act.
  **(b) STILL OPEN.** The bridge from an `ai_runs`-keyed result to the `workflow_runs`-keyed send
  does not exist, and the module edge is forbidden in both directions (ADR 0011, amended).
  It is **deliberately not blocking Phase 12**: a governed draft plus a human send satisfies
  ADR 0007 Part 2 more strictly than an automated send would, so nothing customer-visible is
  missing. What is missing is the *automation*, which is a product decision about whether AI may
  ever press send unattended.
- **`REPORT_MANAGE` is still owed.** Report authoring currently borrows
  `WORKFLOW_MANAGE`, so a workflow administrator can silently change what a
  finance export contains (ADR 0010 §2). Recorded in ADR 0017.
- **The export catalogue is empty on a real database.** Phase 16 proved the
  *capability* — a client that reads only `/exports/catalogue` can build a valid
  `CreateExportRequest` — but `report_definitions` and `document_templates` are
  created by migration and **seeded with zero rows**, and no route creates them.
  So the claim "an export is now creatable" holds only once a definition and a
  template exist. This is the honest limit of Phase 16 and must not be reported
  as a working export feature. Seeding vs authoring is an open product decision.
- **Legacy React inbox may assume an unfiltered list is the caller's own work**
  (ADR 0015). A Phase-12 check, not a reason to revert.
- **`/forgot-password`** — deliberately removed rather than faked (no
  password-reset capability was in FR-6/FR-7).

---

## 10. Security incident: legacy cross-tenant boundary (2026-10-08)

**This was found by Phase-12 shadow parity, and it was live in the runtime serving
production. It is now closed. The write path, not the read path, was the exposure.**

### What it was

The legacy NestJS runtime scoped a project **only from `request.params.projectId`**. A
route naming the project in its **path** was membership-checked by `PermissionsGuard`. A
route naming it in a **query string or a request body** received an empty scope, and the
handler became the only remaining layer. Every vulnerable site assumed the guard had
already checked, and the guard's own Javadoc repeated the false claim: *"A handler
therefore cannot forget to scope itself."*

Confirmed sites, all in `apps/api/src/**`, **all now fixed**:

| module | sites | worst case |
| --- | --- | --- |
| `clinic` | 18 | cross-tenant read/alter/delete of doctors, procedures, appointments; `POST /clinic/doctors` spread `...body` **after** `organizationId`, so a caller overrode the tenant |
| `notes` | 8 routes | read any project in the org; `update`/`delete` also reachable by **omitting** `projectId` |
| `tasks` | 5 sites / 6 routes | `update` copied `input.organizationId` onto the row — a **cross-tenant write** |
| `leads` | 5 | `getById` returned `null` with a 200, disclosing existence |
| `appointments` | 4 | `list` ignored `projectId` and returned the whole org's patients; a `findUnique` copied another tenant's contact details |
| `deals` | 4 | cross-project |
| `analytics.controller` | 1 | cross-project, with authorization in the controller (`plan.md` §0.4 forbids controller-only authorization) |

Swept and **confirmed safe**, unchanged: `views`, `accounts`, `notifications`, `cases`,
`conversations`, `mock-webhook`, `ai.providers`, `agent-status`, `escalation`,
`knowledge`, `channels`, `routing`, `employees`, `ai.agents`, `projects`, `customers`.

### Exposure

- **A valid session was required** for every cross-tenant path — any employee or admin
  of *any* tenant, with **no permission, membership or project access**. Not a drive-by
  web attacker. One path required no session at all: `@Public()` reminder routes accepted
  a bare appointment UUID and returned a patient's name, phone and doctor. A v4 UUID is
  not practically guessable, but **knowledge of an id was sufficient**; that fallback is
  removed.
- **Blast radius: all tenants.** Read rosters and appointments; altered doctors,
  procedures and appointments; a doctor could be moved into the attacker's own tenant;
  patients' appointments cancelled; doctors and procedures deleted.

### Whether it was exploited: UNKNOWN, and it is not knowable from the artifacts

The most exposed write paths **wrote no audit row at all** — doctor and procedure
create/update/delete, and the patient-response status change. No read writes an audit row
anywhere. Application logs cannot answer it either, because a *successful* unauthorized
read or write produces no log line. Deletes are unaudited.

**UPDATED 2026-10-09 (Phase 38): doctor and procedure create/update/delete now WRITE audit rows**,
matching the module's existing convention — actor, tenant, resource, id, and a real before/after diff
with a `noOp` marker so an edit is distinguishable from a no-op. Deletes record the full pre-image,
because for the least recoverable action that is the only surviving description. A cross-tenant actor
is refused **and writes no audit row**, with one test asserting the database was never touched at all,
so the refusal provably precedes any write. Clinician `email`/`phone` are deliberately **excluded**:
they would otherwise be copied into `audit_logs`, which `GET /analytics/audit-log` reads under a
wider set of principals than the roster itself.

**What this does NOT close, and it matters:** a successful unauthorized **read** still writes no audit
row anywhere, so §10's "audit the clinic CRUD paths" is closed for **writes only**. The incident above
still cannot be answered from these artifacts, and the production scan remains the only route to an
answer. A second, quieter gap: **29 permissions are reachable only through
`ROLE_PERMISSIONS[ADMIN] = Object.values(Permission)`**, with no explicit grant naming them — and
`audit-log:read` is among them. **No non-admin role can read the audit trail at all**, which is
precisely the capability that would have answered the breach question.

One bounded lead exists: `APPOINTMENT_CREATED`, `APPOINTMENT_RESCHEDULED` and
`APPOINTMENT_CANCELLED` record the **actor's** organization against the **target's** id, so
a join where the organizations disagree would surface a cross-tenant reschedule or cancel.
It covers three actions, needs the row to still exist, and excludes deletes.

**No claim is made either way.** Answering it requires the production database, which
this workspace does not hold and must not hold. The query is authored and rehearsed
against a disposable copy; running it against production is an action for whoever owns
that database.

### The `apps/**` freeze override — recorded as a decision

`apps/**` is the frozen legacy reference and Phase-12's rollback depends on it. **I
overrode that freeze for the files above**, because unauthorized cross-tenant access to
clinical data outranks reference integrity. Recorded here so it reads as a decision
rather than drift.

Constraints imposed on that work: service-layer scoping only (`plan.md` §0.4), non-
disclosing **404** rather than 403, no tidying or drive-by fixes so each diff is the
defect and nothing else, and `PermissionsGuard`'s **runtime behaviour left unchanged** —
only its false Javadoc corrected, proven unchanged by extracting executable lines from
`HEAD` and the working copy and comparing (30 before, 30 after, identical).

### Not yet closed

- **`apps/worker` and `packages/**` are unswept.** A worker writing clinic rows directly
  would carry the same defect, and nothing so far rules that out.
- **Nine clinic permissions do not exist** (`DOCTOR_*`, `PROCEDURE_*`, `APPOINTMENT_*`,
  `REMINDER_*`). The clinic module now requires only a session, confined to the caller's
  own tenant. No permission was borrowed to fill the gap.
- **Proposed, not written, and order matters:** the legacy guard rule (*no
  `{...ctxDerived, ...body}` ordering; `@RequirePermissions` on every non-`@Public()`
  handler*) is **unsatisfiable until the permissions land**, so permissions first, rule
  second.
- **Audit the clinic CRUD paths**, so the next incident is not equally unanswerable.

### Phase 33-34 findings, added

- **Clinic now has role-based control.** Phase 33 added 18 clinic permissions
  derived from the routes that exist, applied to all 21 non-public handlers, and
  deliberately refused two names Phase 26 had suggested: a coarse `clinic:read`
  covering four disclosures across three record types, and `appointment:write`
  (cancelling releases the slot, appends to the patient's notes and drives the
  reminder chain, so it is not an update). Refusals stay **404**, and Phase 26's
  per-resource messages (`'Doctor not found'`) were themselves an existence
  oracle and are now uniform. **`CLINIC_STAFF` is proposed, not added** — it
  needs a Prisma enum member, so it is a ruling.
- **The forensic tool cannot answer the question, and says so.**
  `ops/forensics` returns three verdicts — `FINDINGS`, `CLEAN`, **`INCONCLUSIVE`** —
  and on the only database in this workspace it returns `INCONCLUSIVE`: the entire
  audit trail is login/logout, with zero appointment rows to mismatch. Had it
  printed "clean" it would have manufactured exactly the false assurance a
  patient-data incident cannot afford. It also reaches past the ledger's bounded
  lead by detecting **persistent residue** (a roster row in the wrong tenant), so
  a cross-tenant *write* is detectable even though roster writes leave no trail.
- **`appointments` has four foreign keys and every one is single-column.**
  `createdById→users(id)`, `customerId→customers(id)`, `doctorId→doctors(id)`,
  `procedureId→procedures(id)`. **Nothing at the database level prevents a tenant-A
  appointment referencing a tenant-B doctor.** This is the same shape ADR 0011
  remediated elsewhere — `V20261006110000` added exactly this composite constraint
  for `document_chunks` — and appointments was never in that batch's scope. Open.

---

## 9. Orchestrator rulings taken 2026-10-08

### Ruling: the AI bridge is built, not deferred — but it does not block Phase 12

**Decision.** The AI auto-reply path will be completed before decommission. Its run
key is **not** a `workflow_runs` row, and its home is `app-bootstrap` as a composition
root — unless the run-key investigation (below) shows the coupling is load-bearing, in
which case the ruling is revisited rather than worked around.

**Sequencing.** `plan.md` Phase 12 lists nine steps and retires the legacy runtime at
**step 8**, after two agreed observation windows. Steps 1-7 — shadow reads, parity
comparison, flag-gated route migration, the rollback window, the backup/restore and
migration rehearsal, the domain-by-domain write switch, and monitoring — do not depend
on the AI path at all. So Phase 12 **starts now**, and the AI fork is resolved before
step 8, which the plan itself puts weeks away. Blocking the whole phase on the last step
would invert the plan's own ordering.

**Why not mint `workflow_runs` rows.** Those rows drive SLA clocks and escalation
routing. An AI turn is not a routed case, so one row per AI run would put a fake case
into the table every SLA metric reads. That is a correctness cost paid for a convenience.

**Why not widen `ai-rag → workflows-sla-routing`.** The edge is forbidden with a written
rationale — *"an AI run that could send without going through the send gate is the
boundary this module exists to hold."* Widening it deletes the reason the boundary
exists. Reversing a security boundary on convenience is not an orchestrator's call.

**What must be answered before implementation.** Whether `AiSendService`'s `runId` is
**load-bearing** for the two-phase protocol's correctness, or **incidental** to Phase 6's
design. The race is decided by the **conversation row lock**, and cancellation is
conversation-scoped rather than run-scoped — both of which suggest the `workflow_runs`
coupling may be incidental. If it is incidental, re-keying the send to `ai_runs` removes
the forbidden edge outright and is the cleanest outcome. If it is load-bearing, the
bridge belongs in `app-bootstrap` and the coupling stays.

**Not to be improvised.** No agent may add a second AI send implementation in `ai-rag`.
`AiSendAuthorization` already documents why: two components enforcing the mode rule
would eventually disagree about which conversation is in AI mode — in two modules that
can never see each other.
### Correction 2026-10-13 � legacy clinic handler count

Earlier entries in this ledger (and `docs/migration/phases/phase-38-clinic-staff-and-contract-truth.md`)
record the legacy NestJS clinic module as having **21 non-public handlers**. That was an
undercount. Verified 2026-10-13 by a read-only route inventory:

- `apps/api/src/clinic/clinic.controller.ts` � **22** route decorators
- `apps/api/src/clinic/public-appointment.controller.ts` � **2** more
- **24 in total**

The historical entries above are left as written, because they record what was believed when
written; this note corrects the register rather than rewriting the record. `ClinicController.java:40`
and `docs/clinic/requirements-traceability.md` now carry the corrected figure.

The inventory also surfaced a **second, independent booking implementation** under `packages/ai`
that is not counted above and books appointments with `status: 'APPROVED'` and no human approval.
That is tracked separately and must not survive cutover.
### Gaps found 2026-10-13 � governed AI has no write path

Recorded while building the platform console; found by an agent inventory and verified by the
orchestrator before being written down.

**1. `AiApprovalService` is unreachable over HTTP.** The service implements
request / approve / reject / revoke / consume / expire and is covered by
`ai-rag/.../ApprovalLifecycleIntegrationTest`. The only routes that touch it are reads:

- `GET /api/v1/admin/ai/approvals` (`AdminReadModelController:384`)
- `GET /api/v1/admin/ai/approvals/{id}/decisions` (`AdminReadModelController:398`)

A search for `@(Post|Put|Patch|Delete)Mapping` matching "approval" across `backend/` returns
nothing. **Every AI approval the Spring runtime records is a dead end**: a request can be
created and read, but no human can approve, reject or revoke it.

**2. There is no permission for approving an AI action.** `Permission` carries
`AI_AGENT_READ`, `AI_AGENT_CONFIGURE`, `AI_RUN_READ`, `AI_SEND` � and nothing for approval.
Using `AI_AGENT_CONFIGURE` would conflate "may change the AI policy" with "may authorise this
specific consequential action", which ADR-0007 treats as a different authority.

**Why this is not fixed here.** Two constraints, both real:

- **Module dependency cycle.** `ai-rag` already depends on `administration`
  (`api(project(":backend:administration"))` in `backend/ai-rag/build.gradle.kts`), so the
  write controller cannot live in `administration` � that would be a cycle. It has to live in
  `ai-rag`, which is also the module that owns the service and the invariants
  ("nobody may approve their own request"; "only a CONSEQUENTIAL action is approvable").
- **It is Phase 10 work, gated by DEC-006.** The autonomy ceiling is a clinic/clinical-lead
  decision. Wiring the mechanism before the policy is settled risks building a gate with the
  wrong threshold.

**Cost to fix, once DEC-006 lands:** low, and no DDL. `RolePermissions` is a Java-side mapping
(`ADMIN` holds `Permission.values()`; `EMPLOYEE` holds a fixed list), not a database table, so a
new enum constant is a Java-only change with no migration.