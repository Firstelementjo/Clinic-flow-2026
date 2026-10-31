# Phase 6a — workflow, SLA-calendar and collision schema (Agent B)

## Objective

Promote the additive migrations Phase 6 needs, and do it without touching the
development database. Scope is deliberately narrow: **only** the workflow /
SLA-calendar / collision slice. Export jobs, usage ledger, finance, report
definitions, AI policy, audit streams and AI-run child scope stay inert in
`ops/flyway/pending/`.

**Nothing was applied to `firstelement`.** It still has 45 application tables, 0
`flyway_schema_history` rows and fingerprint `66eeaadab4ad28a8f8eaf8fe1a0748f9`.
The first Flyway-enabled boot remains the orchestrator's scheduled, backed-up
act.

## Scope (owned by Agent B)

`ops/flyway/**`, `backend/database/**`, `docs/migration/phases/phase-06a.md`,
`docs/migration/flyway-runbook.md`. Not touched: `backend/workflows-sla-routing/**`
(Phase 6), every other `backend/*` module, `apps/**`, `packages/**`,
`docs/migration/schema-drift-report.md`, `docs/migration/migration-ledger.md`,
and the Phase-0..5b reports. **No already-recorded migration was amended** — the
five Phase-2/5a SHA-256 values are unchanged, and the runbook's statement about
them required no edit.

## Migration inventory

The chain after this phase, in the order Flyway applies it:

| Version | File | Applied to `firstelement`? | What it does |
| --- | --- | --- | --- |
| `20260814000000` | `V20260814000000__baseline_verified_live_schema.sql` | **stamped, not executed** (at the orchestrator's boot) | 45 tables, 23 enums, `pgcrypto` + `pgvector` |
| `20260814000100` | `V20260814000100__vector_indexes.sql` | pending | HNSW cosine index |
| `20261004000100` | `V20261004000100__outbox.sql` | pending | enum `OutboxState`; `outbox_events`; 6 indexes |
| `20261004000700` | `V20261004000700__job_attempts.sql` | pending | `job_attempts`, `job_dead_letters`; 7 indexes |
| `20261004001000` | `V20261004001000__idempotency_records.sql` | pending | enum `IdempotencyState`; `idempotency_records`; 3 indexes |
| `20261005010000` | `V20261005010000__workflow_definitions_and_versions.sql` | pending | enum `WorkflowVersionState`; `workflow_definitions`, `workflow_versions`; 5 indexes |
| `20261005020000` | `V20261005020000__workflow_runs_and_action_results.sql` | pending | enums `WorkflowRunStatus`, `WorkflowActionOutcome`; `workflow_runs`, append-only `workflow_run_actions`; 8 indexes + 1 on `conversations` |
| `20261005030000` | `V20261005030000__sla_calendars.sql` | pending | enums `SlaCalendarState`, `SlaClockType`; `sla_calendars`, `sla_calendar_windows`, `sla_calendar_holidays`, `sla_policies`; 7 indexes |
| `20261005040000` | `V20261005040000__collision_records.sql` | pending | enums `CollisionScope`, `CollisionResolution`; append-only `collision_records`; 5 indexes |

| File | SHA-256 (both copies byte-identical) |
| --- | --- |
| `V20260814000000__baseline_verified_live_schema.sql` | `f3df55c3a2abb142b3c67c480777c57e712db0a8ddad825d6a9d212a88fe0bb8` (unchanged) |
| `V20260814000100__vector_indexes.sql` | `3a0fecbbb596d4d2c1cd9d2dd247f816c6ee93cfe0911f92713119887a927760` (unchanged) |
| `V20261004000100__outbox.sql` | `4cc833a03581a1152c988ef7ec67e71f3bf220a3cd3ae16afa6e2d517b18a3ad` (unchanged) |
| `V20261004000700__job_attempts.sql` | `84db18741188ccb0b1a33443a29c1bdc3e3109b8ef201336df483e1ebe870c9e` (unchanged) |
| `V20261004001000__idempotency_records.sql` | `fbf901c8a32f46b044c67632323ddad3dac4e5199ab6f61d067fe8244d078f45` (unchanged) |
| `V20261005010000__workflow_definitions_and_versions.sql` | `01f4fb6cb317793fa5003f99472bec0f9fb2be4fa863e09727008fda33f16e19` |
| `V20261005020000__workflow_runs_and_action_results.sql` | `2c431b4e74ec4d4a64241420f57b4af19fbca2b7a7eb59b71eedd54836cb7cc6` |
| `V20261005030000__sla_calendars.sql` | `3b0cbd66adfe8598be798ea950560fe97527bcbd85a5f19a9de2ee95db842c1d` |
| `V20261005040000__collision_records.sql` | `e6e0a59ead600142cb62e002f18ff4e599f271a268b82ecabf90d268ad988446` |

**Deleted: nothing.** Phase 6a promoted no `*.sql.todo`, so no draft was deleted.
The seven remaining drafts (`202`, `203`, `204`, `205`, `206`, `208`, `209`) are
finance / export / report / policy / audit / AI-run scope and are **explicitly
out of scope for Phase 6**; all seven still carry their `RENUMBER BEFORE
PROMOTING` banner. There was no draft for this slice, so each decision lives
only in its promoted file — which is the property the deletion rule exists to
protect, satisfied here by never having had a second copy.

New supporting assets:

| Asset | Purpose |
| --- | --- |
| `ops/flyway/reference/workflow-sla-statements.sql` | the canonical activation / rollback / router read / loop-prevention insert / action write / takeover abort / collision insert / explanation read model / SLA clock reads / cross-tenant negatives |
| `ops/flyway/verification/V11_workflow_sla_slice.sql` | read-only verification for this slice, guarded, in the standard bundle |
| `ops/flyway/rollback/undo_phase6a.sql` + `rollback/README-phase6a.md` | armed undo and the Phase-6a rollback/recovery runbook |
| `ops/flyway/rollback/rehearsal/undo_phase6a_seed.sql` | synthetic rows used to prove the undo refuses a non-empty slice |
| `run-verification.ps1` | gained `-ExpectedTableCount` and `-ExpectedCheckCount`, both assertions with pre-boot defaults |

Neither `reference/*.sql`, `rollback/*.sql` nor `rollback/rehearsal/*.sql` is
matched by Flyway's `V*.sql` pattern and none lives in `ops/flyway/sql/`, so
none can be executed as a migration by accident.

## The shape that was delivered

Nine tables: `workflow_definitions`, `workflow_versions`, `workflow_runs`,
`workflow_run_actions`, `sla_calendars`, `sla_calendar_windows`,
`sla_calendar_holidays`, `sla_policies`, `collision_records`. Seven enums. No
`DROP`, no `RENAME`, no `UPDATE` of any existing row, no backfill.

Index census on a migrated throwaway: 58 tables, 212 indexes (102 unique), 34
enums, 113 FKs, 58 PKs, **71 CHECK constraints**, `unique_constraints = 0`.
Index buckets: 100 tenant-led / 15 project-led / 22 parent-key / 70
unique-other / 4 global / 1 vector-led.

### What was chosen, and why

| Question | Choice | Reasoning |
| --- | --- | --- |
| **Routing explanations** | **No table. A derived read model.** | The brief lists it among the things a new table might be needed for. It is not needed, and one would be actively wrong: an explanation is exactly `(workflow_run_actions.inputSnapshot, resultSnapshot)` joined to `(workflow_versions.capabilityScope, contentHash)`, so materialising it creates a second copy of the decision that can disagree with the first; its lifetime must be the decision's lifetime, and a stored table would become the copy an operator reads *after* the action history is corrected; FR-10.6 redaction is easier when it is assembled from already-redacted columns at read time; and an explanation that could contradict the decision it explains is worse than none, because it looks authoritative. `reference/workflow-sla-statements.sql` §8 is the query that replaces it, and the indexes are chosen to serve it. |
| **Workflow definition vs version** | Two tables | A definition is what an operator names, enables and orders; a version is the body a run binds to. In one table, "what is the current body" and "what ran last Tuesday" would be the same row. |
| **Activation state** | `workflow_versions.state` enum **plus** `workflow_definitions.activeVersionId` pointer, backed by a partial unique index on `("organizationId","workflowDefinitionId") WHERE state='ACTIVE'` | The hot read is one indexed lookup, never a scan for `state='ACTIVE'`; and a definition can be enabled while a human authors its next version, which must not silently change what production runs. The partial index makes a concurrent double activation lose on the index rather than on application ordering. |
| **Rollback** | An audited state change: `ROLLED_BACK` + `rolledBackAt` + `rolledBackByUserId` + `rolledBackToVersionId`, then re-activate the target. **No `DELETE`, ever.** | The abandoned version is the body an in-flight run bound to; deleting it makes that run unexplainable. A rollback that does not say where it landed is not a rollback. |
| **Activation accepts `DRAFT` *or* `SUPERSEDED`** | Yes | It makes a rollback *the same operation* as an activation. A DRAFT-only predicate would force the rollback path to be written twice and therefore drift. `ROLLED_BACK` and `ARCHIVED` are deliberately refused. |
| **The run's principal** | `principalUserId uuid NOT NULL` FK to `users`, `ON DELETE RESTRICT` | plan.md forbids an unrestricted scheduled identity, so "no principal" must be unexpressible. `RESTRICT`, not `CASCADE`, is deliberate and is the opposite of the tenant/project cascade: deleting a user must not silently delete the history of every decision they authorised (FR-6.7). |
| **Capability scope** | `jsonb` **array**, `NOT NULL`, CHECKed, copied onto every run | An unrestricted capability set is exactly what plan.md forbids, and a capability set that lives only in the engine is not auditable. Copying it onto the run means a later widening cannot retroactively enlarge what that run could do. |
| **Action history** | Append-only: no `updatedAt`, no mutable status; a retry is a NEW row with the next `attemptNumber` | Same rule and same reason as Phase 5a's `job_attempts`, and each migration's own guard **fails if an `updatedAt` ever appears**. |
| **`RETRYABLE` is an action outcome, not a run status** | New enum `WorkflowActionOutcome` | "failed in a way the engine may retry" is a fact about one action. Collapsing it into a run-level `FAILED` loses the distinction that plan.md's "do not retry a non-retryable business-state failure" depends on. |
| **Human takeover** | `abortRequestedAt` + `abortedByUserId` + `abortedAt`, with a CHECK that allows the *intermediate* state | The phase-06 gate is that a takeover aborts an in-flight AI send. The request is recorded first so the send has something to observe; the confirmation lands when it is cancelled. Asserting the tighter pair would reject that valid window — a mistake the first draft made and the review caught. An abort that *completes* without a request is refused, and `ABORTED`/`TAKEOVER` must name who and when. |
| **SLA clocks** | Three nullable integer columns: `firstResponseMinutes`, `nextResponseMinutes`, `resolutionMinutes` | A clinic may answer within 15 minutes and still take a day to resolve. One duration plus a repeat count makes the second clock inexpressible. Null means "not promised", which is distinct from zero. |
| **Clock-ordering invariant** | Table CHECK: `resolutionMinutes >= firstResponseMinutes` **and** `nextResponseMinutes >= firstResponseMinutes`, three negative tests | A resolution budget shorter than the first-response budget breaches on every *healthy* ticket — a silent permanent false alarm that trains operators to ignore the SLA queue. Enforced in the schema rather than in the editor because the editor and the batch importer are two writers with two code paths, and the invariant is a property of the data. |
| **SLA calendars** | A **separate, versioned, effective-dated** table with weekly windows and dated closures — **not** a promotion of `employee_availability` | `employee_availability` is per-employee with no effective dating, no version and no holiday awareness; it answers "is this one employee available now", which is a routing input. An SLA needs "which working week applied on 2026-03-14", which must stay computable after somebody edits the calendar. Promoting the legacy table is a data migration with real semantic questions (whose windows become the organisation's?) and belongs to the module phase. |
| **Time zone / DST** | `timezone` as an IANA text column per calendar; windows are MINUTES FROM LOCAL MIDNIGHT, 0..1440, matching `employee_availability` exactly | So the legacy `packages/routing` window comparison carries over with no translation. A window is a local wall-clock range, so "09:00–17:00 Europe/London" is 09:00 local on both sides of a DST change. **No** UTC-offset column and **no** PostgreSQL `timezone`-typed column: those two disagree on DST days. |
| **Same-day window invariant** | CHECK `endMinute > startMinute` | A window whose end is not after its start is empty or overnight, and an overnight shift as one row is ambiguous the moment its `dayOfWeek` is read back for a DST week. Two windows are the only unambiguous expression. |
| **`effectiveFrom` is a `date`, not a timestamp** | Yes | The question is "which calendar applied on 2026-03-14"; a timestamp implies a second-of-a-day effect that is not implemented, and would put a `timestamptz`-shaped trap into a schema that has none. `NOT NULL`, because a calendar not yet in effect is a `DRAFT`, not a row with no date. |
| **SLA calendar state** | `DRAFT, ACTIVE, SUPERSEDED, ARCHIVED`, defaulting to **`DRAFT`** | `DRAFT` was added *because the rehearsal found the design deadlocked*: with only `ACTIVE/SUPERSEDED/ARCHIVED` there was no way to author version 2 while version 1 is live, because inserting it as `ACTIVE` is refused by `sla_calendars_one_active_per_key`. The real fix is a `DRAFT` state, not a weaker index. The default is the state that is safe to write without thinking. |
| **A calendar that drives a policy** | `ON DELETE RESTRICT` on `sla_policies.calendarId` | Silently detaching a calendar would turn a business-hours SLA into a 24/7 one without a word. A calendar that still drives a policy must be **superseded**, not deleted. (A composite `SET NULL` would also try to null the `NOT NULL` `organizationId`.) |
| **Collision uniqueness** | Two indexes: `UNIQUE("organizationId","scope","resourceId","collisionWindow")` and `UNIQUE("organizationId","scope","resourceId","counterpartyId","collisionWindow")`, where `collisionWindow` is a stored **5-minute bucket**, not a timestamp | This is the load-bearing decision. A timestamp would have to be *identical* across two racing writers for a unique index to catch anything, and two writers never produce an identical `now()` — the guarantee would be decorative. A coarse bucket makes "these two edits are the same incident" true, so a race is counted once. It is **stored** rather than computed in the index because PostgreSQL index expressions must be `IMMUTABLE` and `floor(now()…)` is not; storing it also lets an application retry compute the same bucket and get a clean 0-row `ON CONFLICT` instead of a crash. |
| **`counterpartyId` is free text, not an FK** | Yes | It must be able to name a workflow run or a system actor, which the `users` table cannot express. Three nullable polymorphic FK columns instead would be a worse table. The sacrificed referential integrity is recorded. |
| **`conversationId`/`customerId`/`noteId` on `collision_records` are NOT foreign keys** | Deliberate | A collision must remain recordable after the raced-for row is deleted, and a `CASCADE` would destroy exactly the evidence that a race happened. The one deliberate absence of referential integrity in this slice. |
| **Collision records vs routing explanations** | One table, and the asymmetry is the whole justification | A collision is not derivable from anything: it records an event that, by definition, left no trace in the rows it raced for. Both messages in the conversation were written; nothing else can say they were written against the same version. That is why this table exists and the explanations table does not. |
| **`projectId` on the Phase-6a tables** | Not uniform, and each case is different | `workflow_definitions`, `workflow_versions`, `sla_calendars`, `sla_policies`: **nullable**, organisation-wide (a tenant's working week and escalation workflow apply to every project; per-project copies are how calendars drift apart). `sla_calendar_windows` / `sla_calendar_holidays`: **no tenant column at all** — configuration children inheriting scope from the calendar, exactly as `ai_run_steps` inherits from `ai_runs`; the migration's guard **fails** if one grows a tenant column. `workflow_runs`, `workflow_run_actions`: `NOT NULL`, because a run is about a conversation and a conversation is project-scoped. `collision_records`: `NOT NULL`, because every collided resource is project-scoped and `NULL` could only mean "unknown scope". |
| **Cross-tenant references** | Three **composite** foreign keys, not single-column ones | A single-column FK guarantees the parent *exists*, nothing more. `workflow_runs.workflowVersionId` was one tenant's workflow body while the run claimed another; `conversationId` was another tenant's conversation; `sla_policies.calendarId` was another tenant's working week, which would pause the wrong clock. Each is now a composite FK over the tenant columns, targeting a unique **index** (not a `UNIQUE` constraint, so `V4`'s `unique_constraints = 0` invariant survives — PostgreSQL accepting a non-partial unique index as an FK target was verified on this host first). **These close the holes in DDL rather than documenting them as repository discipline.** |
| **Index names** | Guarded by "every index on these tables must be one this file declared, by its exact name" | PostgreSQL truncates identifiers over 63 bytes silently. The obvious guard — `length(indexname) = 63` — is **wrong**, and the throwaway rehearsal proved it: exactly 63 bytes is legal and untruncated, so that condition fired on a perfectly valid index. The reverse test catches truncation and cannot false-positive. |
| **Timestamps** | `timestamp(3) without time zone`; every default `(CURRENT_TIMESTAMP AT TIME ZONE 'UTC')`; `updatedAt` never defaulted | The baseline's bare `DEFAULT CURRENT_TIMESTAMP` stores the *session's* wall clock. Writing UTC explicitly makes a stored timestamp zone-independent. Each migration's guard **fails** if any new default omits `AT TIME ZONE`. |
| **Phase 6a is four migrations, not one** | Yes | The slices are independent and each has its own undo boundary; a failure in the collision index cannot take the SLA clock invariants with it. |
| **No partitioning, no retention, no SLA-tick table** | Deliberate | Not reversible additively (undoing a partition is a table rewrite). Nothing in this schema ever deletes a row. And whether per-conversation breach state is materialised is a Phase-6 decision that belongs with the scheduler — this migration stops at the inputs. |

### CHECK constraints (57 in this slice)

Every one encodes an invariant **no correct writer can violate**, so none can
reject a valid write, and every one has a negative test:

- **workflow_versions** — `versionNumber >= 1`; a `DRAFT` has never been
  activated; an `ACTIVE` version says who activated it; a `SUPERSEDED` version
  says when and what replaced it; a `ROLLED_BACK` version says who, when **and
  where it went back to**; `capabilityScope` is a JSON array; the content hash is
  non-empty.
- **workflow_runs** — `loopDepth <= maxLoopDepth`; an unfinished run must not
  claim it finished and a finished one must say when; an abort request always
  names its requester and an abort can only *complete* if one was requested;
  `ABORTED`/`TAKEOVER` name who and when; `FAILED` says why; `capabilityScope`
  is a JSON array; `triggerInstanceId` is non-empty.
- **workflow_run_actions** — `attemptNumber >= 1`; `durationMs >= 0`; an action
  has a user **or** a named principal; a finish time and a duration are set
  together; only a failure may carry an error; `RETRYABLE` carries both an error
  code **and** a message; a replay names what it replays; the action key is
  non-empty.
- **sla_calendars** — `version >= 1`; an end date is not before a start date; a
  superseded calendar names its successor; a `DRAFT` has no end date; key and
  timezone are non-empty.
- **sla_calendar_windows** — `startMinute` in 0..1439; `endMinute` in 1..1440;
  **`endMinute > startMinute`**; `dayOfWeek` in 0..6.
- **sla_calendar_holidays** — a partial closure carries both bounds or neither;
  the same minute bounds and same-day rule; the label is non-empty.
- **sla_policies** — each budget, when present, is `> 0`; **the clock-ordering
  invariant**; a policy promises at least one clock; warning and escalation lead
  times are `>= 0`; an escalation is scheduled no later than the clock it
  escalates; priority is `>= 0`; the key is non-empty.
- **collision_records** — at most one typed resource pointer; `observedVersion`
  is `>= 0`; the counterparty is named and typed; `KEPT_BOTH` and `NOOP` explain
  what happened; **an `ABORTED_BY_HUMAN` outcome names the run it aborted and
  nothing else may name one** (strengthened from a one-way implication by a
  negative test — see "Defects found"); the resource type is non-empty.

### The tenant-less allow list grew by one

`sla_calendar_windows_calendar_day_idx` is the per-clock-evaluation window read.
It is only reachable **after** an `organizationId`-filtered calendar row has been
read, and it carries `calendarId`/`dayOfWeek` — no tenant data, no customer
content, and it is not an authorisation path. It is asserted by `V11` §5a and by
the fixture's allow list, so a fifth global index cannot appear by accident. The
three composite tenant-key target indexes are NOT in that list: they lead with
`id` and are unique, so `V9` classifies them as `unique-other`. They are foreign-
key targets, not read paths.

## Verification (exact commands, Windows, Docker Desktop, JDK 24)

Full build, every task re-executed, no build cache, no skipped test:

```
.\gradlew.bat build --rerun-tasks --no-build-cache --no-daemon --console=plain
```
→ `BUILD SUCCESSFUL in 4m 1s`, **50 actionable tasks: 50 executed**.
Aggregated JUnit XML across every module: **506 tests, 0 failures, 0 errors, 0
skipped** (Phase 5a reported 316 → the tree grew through 5b as well;
`:backend:database:test` went 8 → 12).

Database fixture on its own:

```
.\gradlew.bat :backend:database:test --rerun-tasks --no-build-cache --no-daemon --console=plain
```
→ `BUILD SUCCESSFUL`, all twelve scenarios `PASSED`, no skip path:

| Scenario | What it proves |
| --- | --- |
| fresh pgvector database migrates to the verified real schema, then additively to the Phase-5a and Phase-6a slices | two-stage migrate (`target=20260814000100`, then the rest): every Phase-2 assertion still holds at its historical numbers, then 58 tables / 113 FKs / 58 PKs / 71 CHECKs / `unique_constraints = 0` / 34 enums / the nine new tables / all seven new enum value lists / the tenant-less allow list / the append-only boundary as an exact map / the three composite tenant keys / the three composite tenant FKs / the collision indexes really are UNIQUE / the three independent clock columns / every default writes UTC |
| existing legacy database is stamped, not replayed; additive migrations still run | `migrationsExecuted == 8`, rows and **UUIDs** preserved, all eight versions in `flyway_schema_history`, and **all nine Phase-6a tables present and empty** |
| outbox claim is tenant-scoped, lease-protected, retryable and index-supported | unchanged Phase-5a evidence |
| two concurrent claimers never claim the same outbox event | unchanged Phase-5a evidence |
| the claim query is index-supported, not a sequential scan | unchanged Phase-5a evidence |
| dead lettering, replay and attempt history are audited and append-only | unchanged Phase-5a evidence |
| idempotency records are tenant-scoped, reserved before work, and completed once | unchanged Phase-5a evidence |
| workflow versions: one ACTIVE per definition, and a rollback is an audited state change | 6 negative tests (`ACTIVE` with no user; `ROLLED_BACK` with no who/when/where-to; `capabilityScope` as an object; `versionNumber` 0; a second `ACTIVE` refused by the partial index with exactly one surviving; the same key in the same tenant) then the real path: activate, refuse a double activation, retire + supersede with `supersededByVersionId`, roll back with all three audit columns, **the abandoned version still exists**, and the same key in **another tenant is allowed** |
| a run executes as a named principal, records append-only action history, and a human takeover aborts it | 11 negative tests (a null principal; `capabilityScope` = `ALL`; a duplicate attempt number; a success carrying an error; `RETRYABLE` with no message; an actor-less action; a duration without a finish and a finish without a duration, each on its **own** in-flight row; a takeover naming nobody; a completed abort with no request; `FAILED` with no reason; a `RUNNING` run with a finish time) plus: loop prevention refuses a repeated trigger instance, the action row carries its **inputs**, a retry creates a row and never mutates the failed one, the takeover request is idempotent, and **all three composite tenant FKs refuse cross-tenant data** — each paired with a positive control of the identical shape |
| SLA policies keep three independent clocks and refuse an inverted one; calendar windows are same-day | 14 negative tests: clock ordering refused on insert (resolution < first response, next response < first response) **and on update**; an escalation after its own clock; a policy promising nothing; a zero budget; `endMinute <= startMinute` on insert (both reversed and zero-length) **and on update**; `endMinute = 1441`; `dayOfWeek = 7`; a second live calendar for one key; a superseded calendar with no successor; a draft with an end date; a calendar delete that still drives a policy; a duplicate holiday date; a half-specified partial closure; **and a policy pointing at another tenant's calendar**. Plus: the three independent clocks really are three columns, one calendar key has exactly one live version, the retired version names its successor, and with data **no policy resolves another tenant's calendar** |
| collision records deduplicate one incident per window under two concurrent writers | a second counterparty on the same resource in the same window is refused; the same counterparty is refused too; a different window, a different conversation and **another tenant's identical window** are all separate incidents; 4 invariant negatives (a `KEPT_BOTH` with no detail; an `ABORTED_BY_HUMAN` with no run, on update **and** on insert; two typed resource pointers; an unnamed counterparty); two cross-tenant negatives with data; and **two concurrent writers against one uncommitted first insert where exactly one wins and one row exists** |
| ops/flyway/sql and the packaged db/migration chain are byte-identical | all nine files in both directions, including that the classpath copy contains nothing the reviewed copy lacks |

Throwaway-database rehearsal (every throwaway created and **dropped** inside the
existing container):

```
docker exec firstelement-postgres psql -U firstelement -d postgres -q `
  -c "DROP DATABASE IF EXISTS firstelement_6a_check;" `
  -c "CREATE DATABASE firstelement_6a_check TEMPLATE template0;"
foreach ($f in Get-ChildItem ops\flyway\sql -Filter *.sql) {
  docker cp $f.FullName "firstelement-postgres:/tmp/$($f.Name)"
}
docker exec firstelement-postgres psql -U firstelement -d firstelement_6a_check `
  -v ON_ERROR_STOP=1 `
  -f /tmp/V20260814000000__baseline_verified_live_schema.sql `
  -f /tmp/V20260814000100__vector_indexes.sql `
  -f /tmp/V20261004000100__outbox.sql `
  -f /tmp/V20261004000700__job_attempts.sql `
  -f /tmp/V20261004001000__idempotency_records.sql `
  -f /tmp/V20261005010000__workflow_definitions_and_versions.sql `
  -f /tmp/V20261005020000__workflow_runs_and_action_results.sql `
  -f /tmp/V20261005030000__sla_calendars.sql `
  -f /tmp/V20261005040000__collision_records.sql
.\ops\flyway\verification\run-verification.ps1 -Container firstelement-postgres `
  -Database firstelement_6a_check -ExpectedEnumCount 34 `
  -ExpectedTableCount 58 -ExpectedCheckCount 71 `
  -OutputDir <evidence>\6a-full -ThrowOnViolation
```
→ whole chain in **one** psql session, `schema fingerprint:
927e09829db8f1b38d4279f920d5b422`, `enum summary: 34 | 1 | 1`, `table count: 58`,
`check count: 71`, V1–V11 all pass.

The same throwaway stopped at baseline + `V20260814000100` gives fingerprint
`bdf70c7c24dfe23c748281eb6c5c4a03` — **bit-for-bit the value Phase 5a recorded**,
the evidence that nothing in Phase 6a touched an already-verified migration.

Negative rehearsal of the new count assertions: `-ExpectedTableCount 57` and
`-ExpectedCheckCount 69` against the same database both **fail** with
`Table count violated` / `CHECK count violated`, so they are assertions and not
switches.

Rollback rehearsal (`ops/flyway/rollback/undo_phase6a.sql`, armed) on
`firstelement_6a_undo`:

```
# unarmed                                          -> UNDO PHASE6A SKIPPED
# armed, slice partially present                   -> REFUSED, present + absent named
# armed, 8 seeded rows present                     -> REFUSED, all nine counts printed
# armed + -v confirm_data_loss=yes                 -> PHASE6A_REMOVED, 9 tables + 7 enums,
#                                                    49 baseline tables intact
.\ops\flyway\verification\run-verification.ps1 -Container firstelement-postgres `
  -Database firstelement_6a_undo -ExpectedEnumCount 27 -ExpectedTableCount 49 `
  -OutputDir <evidence>\6a-undo-after -ThrowOnViolation
```
→ `schema fingerprint: f436fca35638e2d02418376333fbf67c` — **exactly** the
post-Phase-5a value Phase 5a recorded, 49 tables, 27 enums,
`PHASE6A_NOT_PRESENT`. The undo is lossless at the schema level and is proven by
fingerprint equality, not by assertion. Seeded rows came from
`ops/flyway/rollback/rehearsal/undo_phase6a_seed.sql` (synthetic).

Backup and restore:

```
.\ops\backup-restore\backup.ps1 -Container firstelement-postgres -Database firstelement `
  -OutputDir <evidence>\6a-backup
.\ops\backup-restore\restore-rehearsal.ps1 `
  -Dump <evidence>\6a-backup\20261004T222801Z_firstelement.dump `
  -EvidenceDir <evidence>\6a-restore -Dispose
```
→ dump `20261004T222801Z_firstelement.dump`, SHA-256
`e9ddc86082f20a664c6f5d40fc96e92dcbc2b9598f30791ab302debf1f1143fe`;
restore rehearsal **PASS**: `fingerprintMatches True`,
`sourceEnumContractOk True`, `restoredEnumContractOk True`,
`rowCountsIdentical True`, `identitySample 41/1/4/3/43`, throwaway disposed.
This dump therefore contains **no** Phase-5a or Phase-6a rows and no
`flyway_schema_history` — correct and complete for the pre-boot state, and it must
be re-taken after the boot if the intent is to protect the workflow history.

Post-phase state of `firstelement`, re-verified:

```
docker exec firstelement-postgres psql -U firstelement -d firstelement -Atc "
  select (select count(*) from pg_tables
           where schemaname='public' and tablename <> 'flyway_schema_history'),
         (select count(*) from pg_class where relname='flyway_schema_history');"
docker exec firstelement-postgres psql -U firstelement -d postgres -Atc "
  select datname from pg_database where datistemplate = false order by 1;"
```
→ `45 | 0`, fingerprint still `66eeaadab4ad28a8f8eaf8fe1a0748f9`, databases
`firstelement`, `postgres`. The development database was not modified, stamped,
or read for anything but row counts.

## Security / tenancy checks

- `organizationId uuid NOT NULL` on all seven Phase-6a tables that have the
  column — asserted by the fixture and by `V11` §4a.
- `sla_calendar_windows` and `sla_calendar_holidays` carry **no tenant column**,
  and both their migration guard and `V11` §4c **fail** if one grows a tenant
  column. Duplicated scope on a configuration child could disagree with its
  parent; inheriting it cannot.
- `collision_records.projectId` is `NOT NULL`, the opposite decision, and its
  migration guard fails if it ever becomes nullable.
- Nullable `projectId` is asserted as an **exact four-table list**
  (`workflow_definitions`, `workflow_versions`, `sla_calendars`, `sla_policies`)
  in both the fixture and `V11` §4d, so the exemption class cannot widen
  unnoticed.
- The tenant-less allow list is asserted, not described: exactly four indexes
  exist, each named in both `V11` §5a and the fixture. Any fifth fails.
- **Cross-tenant references are refused by the database**, not by convention:
  three composite foreign keys, each exercised with real cross-tenant rows, each
  paired with a positive control. A single-column FK would have permitted all
  three and nothing else in the schema would have objected — that is the finding
  this design closes.
- Every uniqueness is tenant-scoped: the workflow key, the version number, the
  calendar key and version, the policy key, the trigger instance, the action
  attempt number and both collision indexes. No constraint violation can disclose
  another tenant's key. The same-key-in-another-tenant case is asserted as
  **allowed**, because a global unique index would be the disclosure.
- Uniqueness is carried by unique **indexes**, never `UNIQUE` constraints:
  `unique_constraints` is still **0**, asserted by the fixture, and two
  migrations' guards fail if a `UNIQUE` constraint appears on their tables.
- Every new default writes UTC explicitly; each migration's guard fails if one
  does not. `V11` §4f reports zero offenders.
- Attributability is enforced, not encouraged: a run without a named principal is
  unexpressible; an action without a user **or** a principal is refused; an
  `ABORTED_BY_HUMAN` collision must name the run it aborted; a rollback names
  who, when and where; `KEPT_BOTH` and `NOOP` must explain what happened; a
  `FAILED` run must say why.
- Every Phase-6a index that can be tenant-led is tenant-led: **22** of 25 are,
  and the three exceptions are the composite FK targets, which lead with `id` and
  are not read paths.
- No `.env`, credential, secret, session token or customer export was read or
  printed. No script reads `.env`; every `docker exec` used the container's local
  socket. All rehearsal data is synthetic and named `undo-*` / `example.test`.

## Migration / data impact

- **Applied to `firstelement`: nothing.** No Flyway stamp, no DDL, no DML, no row
  touched, no UUID regenerated.
- The first Spring boot will stamp `20260814000000` and then apply **eight**
  additive migrations instead of one. Row counts, UUIDs and business keys are
  unaffected. The only change to a **pre-existing** object in this phase is one
  new index on `conversations` (`conversations_id_organizationId_key`), created
  `IF NOT EXISTS`, used solely as a composite foreign-key target.
- **No backfill.** Every object in this slice is new and no existing row is read
  or written by any of the four migrations; the fixture proves it by asserting
  all nine Phase-6a tables are **empty** on a seeded legacy database. The
  resumable driver `ops/flyway/backfill/run-backfill.ps1` is unchanged and still
  available for the migrations that will need it; nothing in this phase used it,
  and inventing a backfill for a schema with no rows to transform would have been
  theatre. `employee_availability` → calendars is explicitly **deferred** to the
  module phase, with the reasoning recorded above.
- All rehearsal data lived in disposable Testcontainers `pgvector/pgvector:pg16`
  instances or in throwaway databases inside the dev container. Databases
  `firstelement_6a_probe`, `firstelement_6a_check`, `firstelement_6a_v100`,
  `firstelement_6a_undo` and `firstelement_restore_check` were created and
  **dropped**. Final list: `firstelement`, `postgres`.
- No `db push`, no `prisma migrate`, no destructive DDL against any persistent
  database.

## Defects found by the verification

Recorded because each is the kind of defect only a real database finds.

| Defect | Found by | Fix |
| --- | --- | --- |
| **The design deadlocked on versioning an SLA calendar.** With `SlaCalendarState = ACTIVE/SUPERSEDED/ARCHIVED` there was no way to author version 2 of a key while version 1 was live: inserting it as `ACTIVE` is refused by `sla_calendars_one_active_per_key`, and there was no draft state. | the throwaway rehearsal | `DRAFT` added as the first value and as the table default; the index was **not** weakened. The reasoning is in the migration header so the "simplification" is not repeated. |
| **The truncation guard was itself wrong.** `length(indexname) = 63` fires on a legal, untruncated 63-byte identifier — proven by measuring PostgreSQL 16 on this host (a 63-char name is kept, a 64-char name is truncated). | the first dry run of `V20261005010000` | reversed: every index on a Phase-6a table must match one of the names the file declared, exactly. `V11` §4g uses `> 63` plus the same allow list. |
| **A collision could claim a human takeover with no aborted run.** The original CHECK was one-way (`abortedRunId IS NULL OR outcome = 'ABORTED_BY_HUMAN'`), so a row could say a human beat an AI send while naming nothing — and nothing else in the schema records that a race happened. | the negative test for that CHECK | strengthened to a biconditional, and a negative test added on **insert** as well as update. This moved the CHECK count 56 → 57 and is the reason the runbook says 71. |
| **`workflow_runs` referenced a column that did not exist.** The first dry run failed with `column "conversationId" does not exist`: the index rationale and the explanation query both assumed it, but the table did not have it. | the single-session rehearsal | added `conversationId uuid NOT NULL` with the composite tenant FK. The plan-shape and the read model and the column now agree. |
| **`conversations` had no unique target for a composite tenant FK.** | designing the cross-tenant guard | added `conversations_id_organizationId_key`, `IF NOT EXISTS`, the only index Phase 6a adds to a baseline table; the undo removes it so the fingerprint returns exactly. |
| **`workflow_definitions` → `workflow_versions` and back is genuinely circular**, so `DROP TABLE` in any order failed with `cannot drop table workflow_versions because other objects depend on it`. | the undo rehearsal | the undo breaks one link (the definition's pointer, which carries no data of its own) and then drops in a linear order. |
| **The undo script's arming flag could not be raised at all.** It read `current_setting('firstelement_undo_armed')`, but `-v` defines a *psql variable*, not a server GUC, so the script printed SKIPPED even when armed and its later guards never executed. | the undo rehearsal | arming is now psql's `\if :{?name}` plus an explicit `set_config()` into a session GUC. Recorded in the script header, because a safety gate that cannot be opened is unusable even though it fails closed. |
| **psql does not substitute `:variables` inside a dollar-quoted body** — `armed boolean := :'armed_yes'::boolean` failed with `syntax error at or near ":"`. | the undo rehearsal | the flags are handed over in a plain SQL statement, where substitution does happen. |
| **The undo's row-count message dropped its first entry.** `counts := coalesce(counts || …, '')` is exactly the case where `coalesce`'s fallback fires, so `collision_records=2` never appeared in the message the operator reads before authorising a destructive action. The refusal still worked and the total was right — but its *evidence* was incomplete. | the rehearsal, by the printed list being one table short | `counts` starts as `''` and appends unconditionally. |
| **An unquoted constraint name folded to lower case**, matched nothing, printed `does not exist, skipping`, and the very next `DROP TABLE` failed on the surviving dependency — the same bug class as the Phase-5a unquoted `DROP TYPE`. | the undo rehearsal | every identifier quoted; a post-condition now checks tables, types, indexes **and** `conversations_id_organizationId_key`, and fails loudly. |
| **A negative test proved nothing** because it mutated a finished row to test the finished/duration pair. | the fixture | the in-flight row now has its own helper, and both directions are tested on it. |
| **A cross-tenant negative was accidentally self-inconsistent** — it moved `calendarId` **and** `organizationId` together, which the composite FK correctly permits, so it asserted a false expectation. | the fixture | only `calendarId` moves now, which is the bug the FK exists to prevent. |
| **The "same trigger instance in another tenant" case tried to mix one tenant's definition, version and conversation with another's organizationId** — and the composite FK refused it. | the fixture | the FK was right and the test was wrong; it now builds a complete second tenant. This is why every cross-tenant negative is paired with a positive control of the identical shape. |
| **Test-side binding bugs** carried forward and extended: an `Integer` passed where a `String` was declared; `ChannelType 'CHAT'`, which is not a value (the verified enum is `MOCK/WHATSAPP/INSTAGRAM/MESSENGER/TELEGRAM`); `customers.cxId` is `varchar(9)` and `conversations.ticketNumber` is `varchar(14)`, so a descriptive tag overflowed; `assertThat((String) null).isEmpty()` fails with "expecting actual not to be null"; `queryInt` on an `UPDATE` returns "No results were returned by the query"; and the collision race deadlocked because the latch was raised *after* the blocking insert instead of before. | the fixture | all fixed, each with a comment explaining why the obvious version is wrong. The collision race now raises its signal before the statement, which is the only ordering that can work given the unique index makes B wait on A. |
| **The byte-identity test only checked one direction.** | reading the test while extending it | it now also asserts the packaged copy contains nothing the reviewed copy lacks — the check that catches a classpath-only migration nobody in `ops/flyway/sql` ever reviewed. |

## Known risks / debt carried forward

1. **Retention is undefined, and Phase 6a adds the two fastest-growing tables
   the system will have.** Nothing in this schema ever deletes a row.
   `workflow_run_actions` and `collision_records` are append-only and
   `workflow_run_actions` stores an `inputSnapshot` per action, so it is the one
   table where retention has a data-protection dimension as well as a storage
   one. `createdAt` on both is what a future purge would order by, and both carry
   `organizationId` so a purge can be tenant-scoped and audited. **This is still
   the single largest open item**, now with more tables behind it.
2. **`ON DELETE CASCADE` from tenant and project** on seven of the nine tables.
   Privacy-correct and consistent with 40+ baseline tables, but a typed
   tenant/project deletion will now also remove workflow execution history and
   collision evidence. No tenant deletion may be performed until the §7
   data-lifecycle decision exists. Process gate, not a schema gap. The two
   exceptions are deliberate and should not be "made consistent":
   `workflow_runs.principalUserId` and the action `actorUserId` are
   `ON DELETE RESTRICT`, and the collision resource pointers are not FKs at all.
3. **Two cross-tenant invariants are enforced by DDL and two are not.** The
   composite FKs cover run→version, run→conversation and policy→calendar. What
   remains application policy: that a repository always scopes a version lookup by
   `organizationId` (belt and braces now), and that `collision_records` never
   receives another tenant's `resourceId`. The latter cannot be an FK — the row
   must survive the raced-for row's deletion — so it is a repository rule with a
   test in `reference/workflow-sla-statements.sql` §12d.
4. **No SLA-tick table and no per-conversation breach state.** This migration
   deliberately stops at the inputs. Whether to materialise breach state is a
   Phase-6 decision that belongs with the scheduler; if it is materialised it
   must be additive and must carry the same tenant and clock-ordering
   discipline.
5. **ADR 0006 §2's integer `version` columns are still absent.**
   `collision_records.observedVersion` records the token the writer *saw*, and is
   nullable precisely because that column may not exist yet. When those columns
   land, `observedVersion` starts being populated; nothing in this schema has to
   change, which was the reason for recording the observed token here rather than
   in a new table.
6. **The four migrations are long and heavily commented.** Each is 500–650 lines
   of which roughly half is a rationale header. That is consistent with the Phase
   5a files, but it means a reviewer must read the header to judge the DDL, and
   the DDL cannot be summarised safely.
7. **`sla_calendar_windows_calendar_day_idx` is the one tenant-less index added
   here.** It is safe by construction and allow-listed, but if Phase 6 decides to
   denormalise the tenant onto windows (which would be a mistake), that index must
   become tenant-led in the same change.
8. **The undo's circular-pair handling is order-dependent.** It breaks the
   definition→version pointer first. If a later phase adds a *third* circular
   reference into this slice, the undo must be extended; the post-condition fails
   loudly rather than half-dropping, and the rehearsal covers the case.
9. **The baseline is still on disk in `packages/database`**, quarantined and
   divergent. Carried from Phase 2, unchanged.
10. **`V202610040009` is still `.sql.todo`** and still needs the
    `CONCURRENTLY` vs maintenance-window decision (ADR-0005 §3). Untouched here.

## Follow-up for the orchestrator

1. **Merge is already done.** The runbook's §1 chain and SHA tables, §3
   expected-counts table, §4 fixture scenarios, and a new §9b are in this commit;
   there is no `README §5`-style deferred list to apply. Re-read `§3`'s
   before/after table before the boot — the post-boot numbers are **58 / 212,
   113 / 58 / 71, 34 enums, 100 / 15 / 22 / 70 / 4**, and
   `-ExpectedEnumCount 34 -ExpectedTableCount 58 -ExpectedCheckCount 71` is the
   post-boot bundle invocation.
2. **Confirm the first Flyway boot** against `firstelement`: it will stamp
   `20260814000000` and apply `20260814000100`, `20261004000100`,
   `20261004000700`, `20261004001000`, `20261005010000`, `20261005020000`,
   `20261005030000`, `20261005040000`. Take a backup immediately before and
   re-take one after; the Phase-6a dump `e9ddc860…` proves the pre-boot state
   only.
3. **Phase 6 must build on `ops/flyway/reference/workflow-sla-statements.sql`**,
   not on re-derived SQL. The activation ordering (retire → activate → move the
   pointer, in one transaction, with a 0-row step meaning ROLLBACK), the
   loop-prevention `ON CONFLICT`, the takeover request/confirm pair, and the
   5-minute collision bucket are all contracts with the indexes. Note that
   activation accepts `DRAFT` **or** `SUPERSEDED` — a DRAFT-only predicate is a
   bug.
4. **Decide the collision window length with the product.** Five minutes is a
   starting point chosen because it makes "the same incident" true for a human
   noticing a double send. It is a number, not a derived fact, and it is stored
   per row so changing it later is additive.
5. **Own the retention decision** (risk 1) for `workflow_run_actions`,
   `collision_records`, `workflow_runs` and `workflow_versions`, including the
   `inputSnapshot` data-protection dimension and whether collision evidence must
   outlive workflow history. Nothing deletes rows until that exists.
6. **ADR 0006 §2's integer `version` columns remain open.** Phase 6 writes
   collision rows concurrently and needs its own real concurrency token;
   `collision_records.observedVersion` is the recording point, but the token
   itself still has to land on `customers` / `conversations` / `notes` before it
   can be non-null. That is still a separate additive migration and a separate
   decision.
7. **Do not schedule a tenant or project deletion** until §7's lifecycle decision
   exists; seven of the nine new tables cascade.
8. **Version-numbering rule for every future promotion:** 14 digits
   `yyyyMMddHHmmss`, strictly greater than `20261005040000`. The seven remaining
   drafts still carry 12-digit names and their banner.
9. **Quality agent**: keep `--rerun-tasks --no-build-cache` for the database
   fixture (now 12 tests) and add
   `-ExpectedEnumCount 34 -ExpectedTableCount 58 -ExpectedCheckCount 71` to the CI
   bundle invocation, or CI will run the pre-boot assertions against a
   post-boot database.