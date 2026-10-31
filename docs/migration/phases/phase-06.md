# Phase 6 — Workflows, SLA, scoring, escalation, and routing

**Status:** COMPLETE — 231 tests, 0 failures, 0 skipped.
**Owner agent:** `firstelement-workflows`
**Depends on:** Phase 5b (outbox + workers) GREEN; Phase 4's
`UnassignedQueueRepository.UNASSIGNED_ACTIVE` (ADR 0006 §5); Phase 3's policy
service and `TenantScopedExecutor`. Phase 6a (schema) is committed as
`V20261005010000` … `V20261005040000`; this phase wrote no DDL.

## Objective

Preserve and strengthen the explainable automation and human-control behaviour
that exists today in `packages/routing`, `packages/ai/src/scoring`, and
`apps/api/src/escalation`. The product promise is that a decision is always
explainable and a human always wins.

## Scope

Migrated and extended into `backend/workflows-sla-routing/**`:

- availability, working hours, holidays, skills, workload, capacity, and
  deterministic routing (ported `packages/routing/src/*`);
- scoring and escalation rules (ported `packages/ai/src/scoring/*` and
  `apps/api/src/escalation/*`), including the atomic AI→HUMAN transition and
  handover summaries;
- versioned workflow definitions and state machines with execution history;
- SLA calendars with independent first-response, next-response, and resolution
  clocks, plus business-hours/holiday versioning;
- dry-run/preview, loop prevention, idempotency, bounded batches;
- routing explanations and the unassigned blocking stage.

## Ownership globs

- `backend/workflows-sla-routing/**`
- `settings.gradle.kts` (module registration only)
- `backend/app-bootstrap/build.gradle.kts` (project dependency only)
- `docs/migration/phases/phase-06.md`

## Hard rules — how each is enforced

| Rule | Where it lives | Test |
|---|---|---|
| No alphabetical rule ordering | `ORDER BY r."priority", r."id"`; tie-break by id, never by name | `RoutingExplanationIntegrationTest.rulesAreOrderedByPriorityNotName` |
| No unrestricted "Administrator" identity | `WorkflowRunService#runTenantContext` builds the context from the run's own `principalUserId`, and `requireActivePrincipal` refuses a principal who is not an active user of that tenant | `WorkflowLifecycleIntegrationTest.Runs` |
| Do not retry a non-retryable business-state failure | `replayAction` throws `NonRetryableFailureException` unless the original outcome is `RETRYABLE` | `nonRetryableIsNeverReplayed`, `aRetryableFailureIsTheOnlyOutcomeAReplayMayRepeat` |
| Human takeover aborts an in-flight AI send | `HumanTakeoverService#abortInFlightSends` cancels the staged message then confirms the abort; `AiSendService` is two-phase (`stageAiReply`/`confirmAiSend`) | `HumanTakeoverConcurrencyIntegrationTest` (9 tests, incl. a real two-thread race) |
| Reuse `UNASSIGNED_ACTIVE` | `RoutingRepository.unassignedPredicate()` returns the inbox constant; this module holds no second copy | `membershipPredicateIsTheSharedOne` |
| Additive Flyway first, no hand-written DDL | No migration file was created or edited by this phase | — |

### The lock order that makes gate 1 true

Both sides take the **conversation** row lock before the **run** lock:

1. takeover — `UPDATE conversations ... WHERE mode='AI' AND "modeVersion"=:expected`,
   then `UPDATE workflow_runs SET "abortRequestedAt"`;
2. AI send — `SELECT ... FOR UPDATE` on the conversation, then re-read the run's
   abort state.

With that shared order a takeover that commits first makes the send's
`mode='AI'` test fail, and a send that got the lock first is cancelled at
confirm time. `HumanTakeoverConcurrencyIntegrationTest` asserts the **outcome**
invariant ("after the takeover nothing remains deliverable") rather than "the
send always loses", because a confirm that commits before the takeover is
legitimately in flight — asserting otherwise would be asserting a lock the design
does not promise, and would be flaky.

## Bugs this phase's tests found in its own first implementation

Recorded because they are the reason several tests look the way they do:

1. **`abortInFlightSends` passed the run id where a message id was expected.** A
   staged message has no run id column, so `cancelStagedMessage(scope, run.id(), …)`
   updated zero rows and left the AI reply sendable — exactly the failure the gate
   exists to prevent. Replaced with
   `cancelStagedAiMessagesForConversation`, which cancels by conversation.
2. **`retireActiveVersion` retired the very version being activated**, so a second
   activation of the same version succeeded quietly and wrote a supersession that
   never happened. It now excludes the successor, which makes step 2 match no row
   and the caller raise `ActivationConflictException`.
3. **`effectiveCalendar` filtered on `state = 'ACTIVE'` only.** Activating version 2
   retires version 1, so a March ticket had *no* working week at all once an October
   calendar existed. Now `ACTIVE OR SUPERSEDED`, bounded by `effectiveTo`.
4. **`runHistory` bound a `List<String>` for a `::text[]` cast**, producing
   `malformed array literal: "RUNNING"`. Replaced with an allow-listed array
   literal (a bound `java.sql.Array` would have required holding a live
   `Connection` across a call this class has no business owning).
5. **`enabledDefinitionsForTrigger`'s `UNION ALL` was syntactically invalid**, and a
   text block ends with a newline so `(select …\n)` is a syntax error. Now two
   derived tables with an explicit column list.
6. **`walk`, `record`, `replayAction` and `timeline` took a caller-supplied
   `TenantScope` that had to be project-scoped.** A tenant-only scope produced
   `No value supplied for the SQL parameter '__fe_tenant_project_id'`. They now
   narrow to the run's own project internally, so the trap is gone rather than
   documented.

## Acceptance gate

1. **Concurrency test proves a human takeover prevents an in-flight AI response
   from being sent** — `HumanTakeoverConcurrencyIntegrationTest`, 9 tests,
   including `takeoverDuringAStagedSendPublishesNothing` (two threads, five
   iterations) and `confirmAfterCommittedTakeoverIsAlwaysRefused`.
2. **SLA and routing outcomes are deterministic and explainable** —
   `RoutingExplanationIntegrationTest` (13) asserts the explanation's *inputs*:
   per-stage survivor counts as a non-increasing funnel, the winner's workload,
   the judged instant; `SlaCalendarIntegrationTest` (14) asserts the calendar
   actually loaded was the one promised (effective dating, weekends, holidays,
   partial closures, DST in both directions).
3. **Business-hours/DST, loop-prevention, no-silent-failure tests pass** —
   `WorkingHoursTest`, `BusinessTimeTest`, `SlaClockEvaluatorTest`,
   `WorkflowStateMachineTest` (a cycle is refused at parse time), plus
   `aCapabilityTheRunDoesNotHoldStopsTheWalkAndIsRecorded` and
   `unparseableRuleIsRefused` / `vacuousRuleIsRefused`.
4. **Cross-tenant negatives for every workflow, rule, and routing read** —
   `WorkflowTenantIsolationIntegrationTest`, 9 tests, one per canonical §12
   statement plus the schema-level ones.

## Test commands

```powershell
.\gradlew.bat :backend:workflows-sla-routing:test --rerun-tasks --no-build-cache --no-daemon --console=plain
```

A skipped suite is a failure. There are no skips in this module.

## Rollback

The legacy `packages/routing` and `apps/api/src/escalation` stay frozen and
runnable. Feature-flag the new router off, and the legacy route path remains
authoritative. No destructive migration is permitted in this phase; additive
tables only, and undo follows `docs/migration/flyway-runbook.md` §9a.

## Deliberate omissions, recorded rather than hidden

- **XLSX roster import.** The legacy parser loaded `exceljs` on demand. This
  module has no spreadsheet dependency and adding one would put a file-format
  parser in the tenant-boundary path. CSV is ported in full.
- **User provisioning.** The legacy `commitImport` created a `users` row, minted
  a password-reset token and granted a `project_memberships` row. User creation,
  password reset and membership granting belong to `:backend:identity-access`.
  `RosterImportService` applies the roster to employees that already exist and
  returns `USER_NOT_FOUND` for every row whose email matches no user — per row,
  never a silent skip. `applyLeavesNoRosterContentsBehind` pins that no user row
  and no session token is created.
- **Controllers.** `tests/contracts/openapi-paths.json` is a closed contract owned
  by the quality agent, and the admin surface that needs these endpoints is
  Phase 7's. No HTTP endpoint was added here.
- **A `JobHandler`.** A handler would be drained by `:backend:worker-runtime`'s own
  loop; these jobs are consumed in Phase 7 when the admin replay surface exists.
  `WorkflowRunService#execute` is the supported entry point meanwhile.

## Unresolved policy decisions for the orchestrator

1. **Dedicated capabilities.** Workflow authoring currently reuses
   `ESCALATION_RULE_MANAGE`, and the two-phase AI send reuses `AI_RUN_READ`.
   `AI_RUN_READ` is a read permission gating a write-shaped action. The Phase-6
   file asks for a bounded per-run capability set, which this module enforces, but
   the **static** authorization vocabulary `WORKFLOW_MANAGE` / `SLA_MANAGE` /
   `AI_SEND` does not exist in `:backend:tenant-security`. This needs a
   permission-migration decision before Phase 7 exposes any of it.
2. **Collision window length.** `CollisionRepository.WINDOW_MINUTES = 5`, chosen as
   a starting point rather than a derived fact (Phase 6a follow-up 4). It is
   stored per row, so changing it later is additive.
3. **Customer-sentiment scoring is heuristic.** `ConversationScoring` is a
   keyword-and-cue heuristic, ported as-is. It is deterministic and explainable
   (the cue map is stored on every score row), but it is not a model, and the
   Phase-6 file does not claim it is.
4. **Round-robin degrades to least-loaded.** `RoutingDecider.pick` uses
   least-loaded for both strategies: the schema has no durable round-robin cursor,
   and adding one would be a migration this phase does not own.
   `RoutingRequirement.Strategy` documents it; `ROUND_ROBIN` is still recorded and
   reported in the explanation so the distinction is visible rather than silent.