# Phase 23 (delivered inside Phase 12) — Shadow-read parity: steps 1 and 2

**Scope:** `plan.md` Phase 12 steps 1 and 2 only. Steps 3-8 are not addressed
here and step 8 is not declarable by this agent.

**Status: FAIL — and that is the result, not a defect in the run.** The harness
compares both stacks and three genuine differences emerged. One is a
cross-project data disclosure in the frozen legacy runtime. Phase 12 §2 says a
mismatch that cannot be explained is a blocker; these are explained, and each
still needs an owner.

**Exact command:**

```powershell
.\ops\quality\checks\check-shadow-parity.ps1 -JsonOut <report.json>
```

Node 24.19.0, PowerShell 5.1, Java 24.0.1, PostgreSQL 16.15 (pgvector/pgvector:pg16),
`docker` 29.8.1. Runtime ≈ 2 min 35 s wall.

---

## 1. What was actually run against both stacks

| | Legacy | New |
| --- | --- | --- |
| Process | `apps/api` compiled with `tsc` from source into `tests/shadow/build/legacy-api`, booted as a Nest application | `backend/app-bootstrap/build/libs/firstelement-api-0.1.0.jar` |
| Port | 4401 | 4402 (management 4403) |
| Database | `shadow_check_legacy` — verified legacy baseline (45 tables) + `seed-core.sql` | `shadow_check_spring` — **migrated from empty** through the runtime's own Flyway chain (24 migrations), then + `seed-core.sql` + `seed-new-only.sql` |
| Route inventory (from the runtime itself) | **157** routes, parsed from its own boot log | **122** routes, from `/actuator/mappings` |
| Requests captured | 44 | 42 |

The legacy runtime is compiled from **source**, not run from `apps/api/dist`.
`dist` predates the Phase-0 fix to `main.ts`, and a parity claim about "the
legacy runtime" has to be a claim about the legacy source in the tree now. No
file under `apps/**` or `packages/**` was written; `tests/shadow/build/` is
already covered by `.gitignore`.

The 2-request difference is the matrix itself: four `legacyOnly` probes
(appointments, three analytics routes) have no Spring counterpart, two
`springOnly` probes (inbox counts, unassigned queue) have no legacy one.

---

## 2. Results

| Check | Verdict |
| --- | --- |
| CHK-1 row counts and identifier sets | **PASS** |
| CHK-2 allowed-read bodies field for field | **FAIL** |
| CHK-3 authorization decisions and non-disclosure | **FAIL** |
| CHK-4 source rows in both databases | **PASS** |
| CHK-5 capture integrity | **PASS** |
| CHK-6 status transitions | **PASS** |
| CHK-7 finance derivation | **PASS** |
| CHK-8 ADR 0007 invariants | **FAIL** |

`firstelement` backend count: **1 before, 1 after**. No DDL, no backfill, no
stamp, no read of `.env`.

### Data — green

Customer, conversation, message, ticket, appointment and note identifiers are
identical across the two databases and across both APIs: same uuid, same `cxId`
(`#33000001`…`#33000005`), same `ticketNumber` (`#T-000001`…`#T-000008`), same
row counts, same per-tenant distribution. CHK-1 and CHK-4 both PASS at baseline.

### Authorization — two differences, one of them serious

**SEC-01 (HIGH, legacy defect).** `GET /notes/project?projectId=<A2>` as
`shadow-agent-a1`, a principal who is a member of A1 only:

```
legacy  200  {"id":"ad000001-…-000000000003", "projectId":"a1a1a1a1-…-000000000002",
              "body":"shadow note three", "author":{"fullName":"Shadow Agent A Two"}}
spring  404  {"code":"NOT_FOUND","message":"Resource not found"}
```

The legacy runtime returned **another project's note, with its body and its
author's name**, to a non-member. Cause, in the frozen code:
`apps/api/src/notes/notes.service.ts` `scopedWhere()` pins `projectId` when the
caller supplies one and never checks membership; its own comment claims "the
route-level `Permission` guard enforces membership", but `PermissionsGuard`
checks a *permission*, not a project membership. The same method is used by
`listForResource`, `create`, `update` and `delete`, so the exposure is wider
than the one read probed.

This is a live data exposure in the frozen runtime, not a parity nit. The new
stack refuses. **Classification: code defect, legacy side.** It must not be
"reconciled" — the new behaviour is the correct one and the legacy behaviour is
the defect. `phase-04.md` already recorded the organisation-scope fallback as a
known strengthening; this is the same class of bug in a route it did not cover.

**CHK-2 (contract).** `nextCursor` is present-and-null on the legacy side and
**absent** on the Spring side in 12 of 12 cursor responses, because
`spring.jackson.default-property-inclusion: non_null` omits nulls. A client
generated from the contract cannot tell "no next page" from "field removed".

**CHK-2 (contract).** `GET /customers/{id}` returns `appointments: [2 rows]` on
the legacy side and `appointments: []` on the Spring side, and the Spring
customer detail omits `assignedAgent` / `assignedEmployee` entirely. Either the
Spring projection dropped fields the legacy one populated, or it populates them
differently; both are breaking for a client that reads them.

**CHK-2 (contract).** `GET /conversations/{id}/resolution` answers **200** on the
legacy side and **204** on the Spring side for a conversation with no resolution
row. This is ledger §7 defect 1 in the flesh — a success code assumed rather
than observed — on a resource where both stacks agree there is nothing to
return.

### Status transitions — green

`PATCH status=PENDING → PATCH status=RESOLVED → PATCH close`, replayed on both
runtimes against their own database, then the rows read back with SQL:

| | legacy | spring |
| --- | --- | --- |
| status | `RESOLVED` | `RESOLVED` |
| resolvedAt set | yes | yes |
| closedAt set | yes | yes |
| modeVersion | 0 | 0 |
| resolvedAt ≤ closedAt | yes | yes |

Three-step sequences, three `200`s each. Absolute timestamps cannot match — two
processes, two clocks — so the comparison is over the **state** the transition
produced, which is the thing that has to match.

### Finance — derivation green, one invariant broken

There is **no legacy finance module, no usage ledger and no rate card**, so
there is no legacy figure to compare against. Recorded as un-comparable. The
reference is computed by SQL over `usage_events` and the ACTIVE rate card
version, entirely outside the application:

| dimension | cost | markup | derived revenue | stored revenue | priced |
| --- | --- | --- | --- | --- | --- |
| MESSAGE | 10.000000 | 1.500000 | 15.000000 | 15.000000 | yes |
| AI_REQUEST | 4.000000 | 1.500000 | 6.000000 | 6.000000 | yes |
| WORKFLOW_RUN | 2.000000 | 1.500000 | 3.000000 | 3.000000 | yes |
| STORAGE | 1.500000 | 1.500000 | 2.250000 | 2.250000 | yes |
| DOCUMENT_CHUNK | 3.000000 | — | — | — | **unpriced**, no rate |

CHK-7 PASS: every derived revenue equals cost × the effective markup, every
cost sums its events, and the one dimension with no rate-card line is reported
**unpriced rather than free** (ADR 0007 rule 5). `DOCUMENT_CHUNK` is
deliberately unpriced in the fixture so that a harness checking only totals
would not notice if unpriced usage were billed at zero.

**FIN-01 (MEDIUM, new-stack defect).** CHK-8 FAIL. Every **priced** bucket also
records its full cost in `unpricedCostAmount`:

```
MESSAGE      revenue 15.000000  markup 1.500000  unpricedQuantity 0  unpricedCost 10.000000
STORAGE      revenue  2.250000  markup 1.500000  unpricedQuantity 0  unpricedCost  1.500000
WORKFLOW_RUN revenue  3.000000  markup 1.500000  unpricedQuantity 0  unpricedCost  2.000000
AI_REQUEST   revenue  6.000000  markup 1.500000  unpricedQuantity 0  unpricedCost  4.000000
```

`RollupService.computeInScope` line 193 passes `cost` as `unpricedCost` on the
**priced** branch, while passing `ledger.uncostedQuantity()` as `unpricedQuantity`.
The column is documented in `V20261005090000` as the amount of usage that was
**not** priced. The same money is therefore reported as billed and as unbilled
at once, and any unpriced-usage view or reconciliation that sums
`unpricedCostAmount` double-counts every priced bucket. The database constraint
`usage_rollup_snapshots_unpriced_pair_check` does not catch it because it only
requires *one* of the two to be non-zero when there is no rate.
**Classification: code defect, new-stack side.**

### Exports — not run

`POST /api/v1/exports` was **not** exercised. The export catalogue is empty on a
real database (ledger §8) and building a valid request needs a
`report_definitions` row and a `document_templates` row that no route creates,
so an artefact comparison had nothing to compare. Recorded as **not run**, not
as passed. See follow-ups.

### Analytics — un-comparable, and a migration gap

The legacy runtime serves `analytics/summary`, `projects/:projectId/analytics`,
`employees/:employeeId/analytics` and `me/analytics`. The Spring contract has
**no** CRM/clinic analytics route — the funnel, top-procedures and
clinician-workload read model was never built (ADR 0013 §1, ledger §8). There is
nothing on the new side to compare, so three of the four analytics surfaces named
in the plan are **un-comparable**, and the gap is recorded rather than papered
over with a derived proxy.

---

## 3. Every comparison: what would make it red, and whether it was demonstrated

| Check | Red condition | Demonstrated |
| --- | --- | --- |
| CHK-1 | a row in one database and not the other; a rewritten `cxId` | **yes** — PN-01 (extra customer row), PN-02 (cxId rewrite, row count unchanged) |
| CHK-2 | a renamed/dropped field, a differently rendered timestamp, a different list order, a mutated value | **yes** — PN-03 (one `displayName` differs; counts, ids and key sets all identical) |
| CHK-3 (decisions) | one stack allows where the other refuses | **yes** — PN-04 (grant `agent-a1` a membership of A2 in the Spring database; four decisions flip) |
| CHK-3 (non-disclosure) | a refusal body echoes a display name, ticket number, note body or message body from an unreachable project | **yes, as a detector perturbation** — PN-06 (doctored legacy refusal naming a cxId, a display name and a tenant). Not evidence about either runtime. |
| CHK-4 | a table whose row count or pk set differs between the two seeded databases | **yes** — PN-01, PN-02, and **PN-05**: a customer moved to another tenant changes neither the count (5/5) nor any primary key, and is caught only because the comparison is organization-aware |
| CHK-5 | a projection selects `passwordHash`/`tokenHash`, or a request never arrives | not demonstrated — it is a guard, not a comparison. The capture raises `leakedSecret` rather than masking quietly. |
| CHK-6 | a transition one runtime performs and the other does not, or a timestamp one stamps and the other leaves null | **yes** — PN-07 (the close step removed from the Spring side only) |
| CHK-7 | a revenue that is not cost × markup, a cost that does not sum its events, or a priced/unpriced call the rate card does not support | **evidence present, flag not cleared** — see below |
| CHK-8 | a rate with no revenue, a revenue with no rate, a stored revenue that is not its own cost × its own markup, two rows claiming one generation, unpriced cost on a fully priced row | **yes, as a detector perturbation** — PN-09 (one stored revenue incremented by 1) |

**PN-08 is the one open item in the harness.** The perturbation ran and the
product behaved correctly: appending `MESSAGE × 2.000000` moved the stored
revenue **15.000000 → 18.000000** (= 12 × 1.5), the derivation check stayed
PASS, and generation 1 kept its own event count. But the gate's `proved` flag
for PN-08 is **False**, because the PowerShell that compares them is not reading
the baseline revenue correctly. The evidence for the behaviour is in the run log
and in `parity-PN-08.json`; the flag is wrong. **I am reporting this as
`unproven` rather than as proved, and it is a harness defect to fix before this
gate is trusted to certify anything about finance.** The prior version of
PN-08 tried to mutate a rate-card markup and was refused by
`firstelement_append_only()`; the database was right and that attempt has been
rewritten as additive.

---

## 4. Un-comparable, and why

| Item | Basis |
| --- | --- |
| `GET /conversations/{id}/transcript` | the legacy router maps no GET under `/conversations/:id` other than the detail read. Established from the legacy runtime's own boot log, **not** inferred from its 404 — a 404 is also what a denial looks like. Message counts are compared through the conversation projection's `messageCount` and through the source rows instead. |
| Finance rollups | no legacy finance module, ledger or rate card exists |
| `POST /api/v1/exports` and the artefact | the export catalogue is empty; no route creates a definition or a template |
| `/api/v1/inbox/counts`, `/inbox/unassigned` | Phase 15 additions with no legacy counterpart |
| `/appointments`, `/analytics/*` (4 routes) | legacy-only; the new stack has no counterpart, so these are **migration gaps**, recorded not compared |

---

## 5. ADR 0013 §4 — the unreproduced stall

**Not observed. Not closed.** The gate probes both method classes at eight
checkpoints over the run: `GET` answered 200 in 25-553 ms and the unsafe method
answered 403 in 10-83 ms (Spring) / 400 in 3-57 ms (legacy, no CSRF route) on
every single probe. **Eight probes over two and a half minutes cannot reproduce
an intermittent that appeared after roughly two hours of serving**, so this is
evidence of nothing either way and must not be read as a resolution.

The gate's probe was itself wrong on the first two runs and that is worth
recording: it classified every thrown status as "no answer", so the legacy
runtime's 404 on `/api/status` was reported as **STALL OBSERVED**. That is
exactly the refusal-versus-hang confusion ADR 0013 §4 exists to prevent,
committed by the detector. Fixed: a status is an answer, only an exception with
no response object is a stall.

---

## 6. Ledger §7 — which operations a real client cannot type an error for

Not re-measured this phase. The gate's matrix confirms the defect is live on a
resource, not only in the document: `GET /conversations/{id}/resolution`
answers 200 on one stack and 204 on the other for the same absent row. Zero
4xx/5xx remain documented across all 142 operations, so a generated client still
has no typed way to handle any refusal, and the three contract divergences above
are exactly what such a client would trip over. **Report only; not fixed here.**

---

## 7. Migration and data impact

None on `firstelement`. Both shadow databases are created, seeded, perturbed and
**dropped**; backend count on the protected database measured at 1 before and 1
after. The first Flyway-enabled boot this phase rehearses happened against
`shadow_check_spring` **from empty** (`EXECUTE_FULL_CHAIN`, 24 migrations,
6.3 s), not against the production-shaped database, which remains unstamped as
ADR 0005 §2 requires.

---

## 8. What this does and does not establish

**Establishes, with every check proven able to fail:** the two stacks carry the
same rows under the same identifiers; they perform the same status transition to
the same state; the new runtime's finance revenue is genuinely derived from the
ledger through a versioned rate card; no credential crossed a capture.

**Does not establish:** that the two stacks are interchangeable. They are not.
One discloses another project's notes (SEC-01), one double-counts unpriced
finance (FIN-01), and four response shapes differ. Each needs an owner's
decision before a cutover window.

**Does not touch:** exports, backup/restore, the rollback window, flag-gated
routes, the write switch, or decommission. Those are Phase 12 steps 3-7, and
step 8 remains undeclared by anyone.