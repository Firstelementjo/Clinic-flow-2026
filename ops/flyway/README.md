# ops/flyway — Flyway contract assets

- `sql/V20260814000000__baseline_verified_live_schema.sql` — Flyway baseline.
  Generated from a schema-only `pg_dump` of the **verified** dev database
  (`firstelement`, pgvector/pgvector:pg16) on 2026-10-04. It matches
  `packages/database/prisma/schema.prisma` and the live enum/table/FK/index
  state. It deliberately does NOT match `packages/database/prisma/migrations/20260814000000_init/migration.sql`
  (EmployeeAvailabilityStatus + AIRunDecision drift; V1.2 tables missing).
- `sql/V20260814000100__vector_indexes.sql` — additive reconciliation of the
  HNSW embedding index + tenant prefilter index that the live DB never got.
  Amended in Phase 5a: the table and the operator class are schema-qualified, so
  the chain also applies correctly when every file is piped into ONE psql
  session (the baseline sets `search_path=''`). Never applied anywhere, so no
  recorded checksum changes.
- `sql/V20261004000100__outbox.sql` — **PROMOTED (Phase 5a)** transactional
  outbox: enum `OutboxState`, table `outbox_events`, tenant-led claim index plus
  the documented tenant-less sweep and lease-reaper indexes. Every open question
  of the deleted draft is resolved in the file header.
- `sql/V20261004000700__job_attempts.sql` — **PROMOTED (Phase 5a)** enums
  `JobAttemptOutcome` and `DeadLetterStatus`, append-only table
  `job_attempts`, and `job_dead_letters` for the operator lifecycle.
- `sql/V20261004001000__idempotency_records.sql` — **NEW (Phase 5a)** enum
  `IdempotencyState` and `idempotency_records`, the tenant-scoped key store the
  delivery path and provider-event deduplication need.
- `reference/outbox-claim.sql`, `reference/job-and-idempotency-statements.sql` —
  the CANONICAL statements for that slice (claim / complete / retry / dead
  letter / reap / replay, attempt + DLQ writes, idempotency reserve/complete,
  tenant-scoped read models). **Not migrations**: their names do not match
  Flyway's `V*.sql` pattern and they live outside `sql/`, so Flyway ignores
  them. Phase 5b should copy these shapes rather than re-derive them.
- `rollback/undo_phase5a.sql` + `rollback/README.md` — the Phase-5a
  rollback/recovery runbook and the armed undo for a created-but-unused slice.
  The README also lists every expected number in the Phase-2 runbook that the
  Phase-5a boot will change, for the orchestrator to merge.
- `pending/*.sql.todo` — PLANNED additive migrations that are **still
  inert** (export jobs, usage ledger, finance, report definitions, policy,
  audit streams, AI-run child tenant scope). The outbox and job-attempt drafts
  were promoted in Phase 5a and DELETED, so the promoted `sql/*.sql` file is the
  only copy of those decisions. **Phase 6a promoted no draft at all**: the
  workflow / SLA / collision slice had no `*.sql.todo`, so all seven remaining
  drafts are still there and still inert, and the four new files are the only
  copy of their decisions. Promote each remaining draft to
  `sql/V<timestamp>__<name>.sql` (with full TODO detail resolved) only after
  design review, backup, row-count evidence, and a disposable-database run —
  and **renumber it to 14 digits** (`yyyyMMddHHmmss`). Every remaining draft
  still carries a 12-digit name, and Flyway orders versions NUMERICALLY, so a
  12-digit version sorts BEFORE the 14-digit baseline and would run against an
  empty database. Each draft now carries a `RENUMBER BEFORE PROMOTING` banner.
  Promote to a version **numerically greater than the highest existing version**
  (`20261005040000`), not merely greater than the baseline.
- `sql/V20261005010000__workflow_definitions_and_versions.sql` — **NEW (Phase 6a)**
  enum `WorkflowVersionState`, `workflow_definitions` (the stable identity and
  explicit priority) and `workflow_versions` (the versioned body with a DRAFT →
  ACTIVE → SUPERSEDED / ROLLED_BACK lifecycle). At most one ACTIVE version per
  definition, enforced by a partial unique index; a rollback is an audited state
  change, never a delete.
- `sql/V20261005020000__workflow_runs_and_action_results.sql` — **NEW (Phase 6a)**
  enums `WorkflowRunStatus` and `WorkflowActionOutcome`; `workflow_runs` (a named
  principal, a bounded capability list, loop-prevention on the trigger instance,
  and the human-takeover abort) and append-only `workflow_run_actions` (who /
  what / when / inputs / outcome). Carries the composite tenant foreign keys that
  refuse a run bound to another tenant's workflow body or conversation.
- `sql/V20261005030000__sla_calendars.sql` — **NEW (Phase 6a)** enums
  `SlaCalendarState` and `SlaClockType`; versioned, effective-dated
  `sla_calendars` with `sla_calendar_windows` and `sla_calendar_holidays`; and
  `sla_policies` with three INDEPENDENT clocks (first response, next response,
  resolution) and the clock-ordering CHECK.
- `sql/V20261005040000__collision_records.sql` — **NEW (Phase 6a)** enums
  `CollisionScope` and `CollisionResolution`; append-only `collision_records`
  recording simultaneous replies, assignments and case edits, deduplicated to one
  incident per resource per 5-minute window so a race is counted once.
- `reference/workflow-sla-statements.sql` — the CANONICAL statements for that
  slice: activation (three steps, one transaction), rollback, the router's
  priority-ordered read, the loop-prevention insert, action-result writes, the
  human-takeover abort, the collision inserts, the routing-explanation read
  model, the SLA clock-evaluation reads, and the cross-tenant negatives as
  executable statements. **Not migrations.** Phase 6 should copy these shapes
  rather than re-derive them: the predicates and the `ORDER BY` are a contract
  with the indexes.
- `rollback/undo_phase6a.sql` + `rollback/README-phase6a.md` — the Phase-6a
  rollback/recovery runbook and the armed undo for a created-but-unused slice.
  Armed twice, refuses a partially applied slice, prints per-table row counts
  before refusing, and proves itself by returning the schema fingerprint to the
  exact post-Phase-5a value.
- `rollback/rehearsal/undo_phase6a_seed.sql` — synthetic rows used to prove the
  undo REFUSES a non-empty slice. Throwaway databases only; never `firstelement`.
- `verification/V1..V11` + `run-verification.ps1` — read-only schema
  fingerprint, row counts, enum contract, foreign keys, indexes/pgvector,
  nullability/timestamps, tenant scope, reference integrity, index scope
  census, the Phase-5a outbox/job/idempotency slice, and the Phase-6a
  workflow/SLA/collision slice. Writes a timestamped evidence bundle and one
  schema fingerprint. Pass `-ExpectedEnumCount 34 -ExpectedTableCount 58
  -ExpectedCheckCount 71` on a database that has run the whole chain; the
  defaults (23 / 0 / 0) are the pre-boot values and the two count assertions
  default to "not asserted" so the pre-boot bundle still runs unchanged.
- `verification/verify-clean-bootstrap.ps1` — **THE CHAIN REHEARSAL** (Phase 30).
  Applies all 24 migrations to a `TEMPLATE template0` database **concatenated
  into one psql session**, which is both the quality gate's shape and the shape
  Flyway produces on a single connection, then asserts all eleven `postBoot`
  measurements in `expected-state.json`. A missing count fails rather than
  passing quietly. `-FlywayDir` measures a candidate chain.
- `verification/rehearsal-chain.ps1` — the **second** shape, one `psql` process
  per migration. Still the only rehearsal that exercises a per-migration
  transaction boundary, but it gives each migration a fresh `search_path` and
  therefore **cannot see a session-dependent guard** — which is how five of them
  survived to Phase 30. Read its header before trusting a green run.
- `verification/audit-rendered-text-assertions.ps1` — the defect detector for
  the class Phase 30 found. Lists every catalogue-rendered read in the chain,
  classifies it `structural` / `renormalised` / `rendering-stable` /
  `search_path-sensitive`, and (with `-Database`) re-measures each predicate
  under both `search_path` settings and exits 1 while any **shipped** predicate
  still flips. The removed patterns are probed too, so a green run proves the
  audit is not vacuous.
- `backfill/B0_backfill_control.sql` — additive control tables
  (`backfill_runs`, `backfill_rejections`) so a backfill can be interrupted and
  resumed. Operational scaffolding, deliberately NOT a Flyway migration.
- `backfill/B1_materialize_ai_run_child_scope.sql` — armed, idempotent,
  resumable, bounded backfill that materialises inherited tenant scope onto
  `ai_run_steps` / `ai_run_retrievals` / `ai_run_tool_calls`. Requires the
  pending `V202610040009` expand migration first. Rehearsed on a throwaway
  database; never applied to `firstelement`.
- `backfill/B2_scope_audit_report.sql` — read-only classification of every
  `NULL projectId` row, so a `NULL` can be judged a data gap vs correct
  organisation-wide scope instead of being fabricated away.
- `backfill/run-backfill.ps1` — bounded batch driver. Refuses to run without
  `-Armed`; captures a verification bundle before and after.

## Spring wiring

`:backend:database` packages `backend/database/src/main/resources/db/migration/`
(same files as `ops/flyway/sql`) onto the classpath of `app-bootstrap`.
Spring Boot auto-runs Flyway on startup. On an EXISTING schema without
`flyway_schema_history` the auto-detected strategy marks the baseline version
as applied (no DDL re-run) and applies only later additive migrations. On a
FRESH database the baseline SQL executes in full.

The Prisma init migration under `packages/database/prisma/migrations/` is
**quarantined**: it is drift-divergent and must not be used to baseline
Flyway. It is missing 14 of the 45 tables, is missing the `CaseOutcome` enum,
and has the wrong value list for both `EmployeeAvailabilityStatus` and
`AIRunDecision`. See `docs/migration/adr/0002-flyway-baseline-quoted-columns.md`
and `docs/migration/schema-drift-report.md`.

## Backup / restore / backfill

- `ops/backup-restore/backup.ps1` — read-only `pg_dump` (`-Fc` + `-Fp`) with
  SHA-256 manifest and a verification bundle captured at backup time.
- `ops/backup-restore/restore-rehearsal.ps1` — restores into a **disposable**
  database, proves the schema fingerprint, enum contract, row counts, and
  business-key survival, then disposes. Refuses to target the source database.
- `ops/backup-restore/rehearsal/*.sql` — **rehearsal-only** expand DDL and
  acceptance queries. Must never be run against `firstelement`.

Operational procedure, verification commands, rollback and recovery:
`docs/migration/flyway-runbook.md`.
