# Phase 12 - Dual-run rehearsal: what the shadow comparison actually proves

Date: 2026-10-11
Status: **REHEARSAL EXECUTED. DECOMMISSION NOT DECLARED.** See "What this phase does not do".

## What ran

`ops/quality/checks/check-shadow-parity.ps1` boots BOTH runtimes against two disposable databases
seeded from the SAME file (`tests/shadow/dataset/seed-core.sql` replayed into each), captures the
same 40 requests against each, and compares them. Eight checks:

| Check | Verdict |
| --- | --- |
| CHK-1 row counts and identifiers | PASS |
| CHK-2 response bodies | **FAIL** |
| CHK-3 authorization decisions | PASS |
| CHK-4 source rows | PASS |
| CHK-5 capture integrity | PASS |
| CHK-6 status transitions | PASS |
| CHK-7 finance derivation | PASS |
| CHK-8 finance invariants | PASS |

Two harness defects blocked this stage from ever producing that table. Both are fixed, and both
were defects in the harness rather than in either product.

## Defect 1: the teardown neither terminated nor released (FE-SHADOW-020/021)

**Symptom.** The stage wrote its report at 15:43:42 and was still running at 15:55, holding ports
4402 and 4403 with its own JVM - which is the runtime leak reported as a contradiction of this
stage's own claim that it "releases them on exit".

**Cause.** Two independent things, neither of which the old code could report.

1. `Invoke-Psql` had no timeout, so `DROP DATABASE` against a database a reconnecting pool kept
   holding waited on the lock for ever. The stage did not hang; it was blocked in teardown after
   having already written its verdict.
2. The teardown called `$process.Kill()` and moved on. It reported success by omission - nothing
   afterwards could tell whether the JVM had gone. It had not, and the process handle was **not the
   process holding the port**: the log shows `stopped the spring runtime (pid 4200)` and, three
   seconds later, `port 4402 is still held ... (pid 13028)`.

**Fix.** The kill is verified rather than attempted: kill by handle, confirm the process exited,
confirm the ports are free, and if either is untrue escalate to killing the port owner - the same
rule the harness already applies on the way in, where an unexplained listener is refused rather
than assumed to be a stranger. `FE-SHADOW-021` is a new rule for "the runtimes were not released".
Teardown statements now carry a `statement_timeout`, so a lock wait is a bounded, named failure
rather than a hang.

**Result.** 170 s, clean exit, both disposable databases disposed, no port left listening.

A mistake worth recording: the first attempt passed the timeout as `psql -c statement_timeout=...`,
which is not a psql setting but a SQL statement, so PostgreSQL answered "syntax error at or near
statement_timeout" and every teardown DROP failed - which looked like a permissions problem and was
not one. It is a `SET` prefix now, and the primitive was tested directly before being relied on.

## Defect 2: PN-05 could not be applied, because the harness discarded the reason (FE-SHADOW-012)

**Symptom.** `applying PN-05 to shadow_check_spring failed` - with no further detail, ever.

**Cause.** `Invoke-PsqlFile` assigned the psql output to `$null` and threw a message with no
content. The harness destroyed the one message that would have said what PostgreSQL objected to. A
failure whose cause is discarded cannot be diagnosed, only re-encountered. With the output
captured, the real error appeared on the next run:

```
ERROR: insert or update on table "appointments" violates foreign key
       constraint "appointments_created_by_tenant_fkey"
```

**This is the most important result of the phase, and it is not a defect.** The Spring schema
**structurally refuses** the corruption PN-05 exists to model. The composite tenant foreign keys
cascade a customer's organization change into its appointments; the appointment's `createdById` then
does not exist in the new organization, and the database rejects the statement. The legacy schema
has no such constraint and accepts the row. A migration that put a referenced customer in the wrong
tenant cannot produce that state in the new database at all.

**Consequence for the perturbation.** It could not be applied to any customer the seed had, because
every one was referenced. PN-05 now discovers its own target - a customer with no appointments,
conversations, identities or leads - and asserts that it moved **exactly one** row, so a silent
no-op can never be mistaken for "the detector held". One such customer was added to the seed, which
replays into both databases, so parity is unchanged.

The proof is preserved rather than weakened: for an unreferenced customer the only thing that
changes is its own `organizationId`, which is exactly what CHK-4 exists to detect. The harder case
is not merely untested - it is impossible in this schema, and is recorded here as evidence.

## The two real divergences (CHK-2)

Both are genuine and neither is a harness artefact.

1. **`resolution.own.agentA1`: legacy 200, Spring 204.** When no resolution row exists, both
   runtimes synthesize one, but the legacy returns it with a body and Spring returns 204 No
   Content. A client written against the legacy runtime would break on this. **Needs an owner
   decision**: either Spring returns the synthesized 200 body, or the difference is accepted and
   published as a breaking change.
2. **`notes.forResource.customerA1.agentA1`.** The Spring runtime REQUIRES `resourceType` +
   `resourceId` on this path and refuses an unscoped note list; the legacy runtime treats the same
   path as a project-filtered list and answers. **This is a deliberate strengthening**, taken for
   the same reason the legacy cross-tenant breach happened: an unscoped "list all notes" crosses
   tenants. Recorded as an accepted divergence with its rationale, not normalised away.

Three routes are unComparable rather than divergent: the transcript routes have no legacy
counterpart. Recorded so the gap is visible rather than inferred from a 404.

## What this phase does not do

**Decommission is NOT declared and cannot be from here.** `plan.md` Phase 12 step 8 requires two
agreed observation windows and sign-off from **product, operations, security and finance owners**.
None of that exists, and none of it is mine to grant. The legacy runtime is untouched and remains
frozen reference.

Also not done: the backup/restore rehearsal immediately before cutover (`restore-rehearsal` stage
exists and passes, but it has not been run against this rehearsal's databases), and the two
observation windows themselves.

## Cutover blocker found 2026-10-13 — the legacy AI path books without approval

A read-only inventory of the appointment capability surfaced a **second, independent booking
implementation** that no route count had included, and it must not reach cutover.

**Path:** `packages/ai/src/tools/orchestrator.ts:78` constructs `ToolExecutor`; `executor.ts:313`
calls `createAppointmentValidated`; that function (`clinic-scheduling.ts:512`) performs
`prisma.appointment.create` and **hardcodes `status: 'APPROVED'`** at lines 588, 621, 675 and 703.

**Why it matters:**

- There is no human approval step anywhere on this path. The status is asserted by the function
  itself, not granted by anyone.
- It contradicts `PROJECT_REQUIREMENTS.md` requirement 9 — customer-visible responses, financial
  changes, permission changes, deletion and external side effects require explicit AI approval —
  and it contradicts ADR-0007's "Forbidden to the model" list.
- It contradicts the clinic kit's own rule that AI may assist but must not approve treatment.
- It is reachable from an inbound message path (`webhooks.controller.ts:55` →
  `message-queue.service.ts:103` → `orchestrator.ts:78`), not only from a UI.
- It is the **best-tested** appointment code in the repository, which makes it more likely to
  survive a casual cutover than the weaker, more visible path.

**Status: OPEN. Not fixed. Not mitigated.** Deliberately not changed here, for two reasons:

1. Disabling a live booking path is a product decision with patient-facing consequences. It needs
   an owner and a notice period, not an engineer's unilateral edit.
2. The legacy runtime is frozen reference. Whether this code is still deployed must be confirmed
   before anything is changed — if it is not live, the finding is a cutover *checklist item*
   rather than an incident.

**Required before decommission is declared:**

| # | Action | Owner |
|---|---|---|
| 1 | Confirm whether the `packages/ai` runtime is deployed in any environment | operations |
| 2 | If deployed: disable the booking tool, or put an approval gate in front of it | product + engineering |
| 3 | Confirm no appointment rows were created by this path, and if any were, how they are reconciled | finance + clinical lead |
| 4 | Record the ruling as an ADR, and add a cutover checklist item | engineering |

This does **not** change the two-observation-window or four-sign-off requirement above. Those remain
unsatisfied regardless of this finding.
### Escalation 2026-10-13 � the legacy runtime is this repository''s only deployment

The original framing of this finding was "if the legacy runtime is still deployed". **That
condition is met.** Verified directly:

- `deploy/fly/Dockerfile` is a `node:22-alpine` image. Its final stage is
  `CMD ["sh", "-c", "node apps/api/dist/main.js & node apps/worker/dist/main.js & wait"]`,
  and it copies `apps` and `packages`.
- There is **no Java, no Spring, and no `app-bootstrap` entrypoint anywhere under `deploy/`.**

So the repository ships exactly one deployable image and it runs the NestJS runtime containing the
self-approving booking path. Two consequences, both larger than the original finding:

1. **The approval-free booking path is in the deployment configuration**, not merely in dead code.
   It is therefore a live exposure to be ruled on, not a theoretical one.
2. **The Spring migration has no deployment story.** Whatever replaces this image has to be
   built, and nothing in `deploy/` has been written for it. This is an independent cutover blocker
   that was not previously on the list.