# Phase 5a — transactional outbox, job history and idempotency records (schema)

## Objective

Promote the additive migrations the transactional outbox and the worker runtime
need, as `firstelement-orchestrator` requested, and do it without touching the
development database. Scope is deliberately narrow: **only** the
outbox / job-attempt / dead-letter / idempotency slice. Finance, usage ledger,
export jobs, report definitions, AI policy and audit streams stay inert in
`ops/flyway/pending/`.

**Nothing was applied to `firstelement`.** It still has 45 application tables, 0
`flyway_schema_history` rows and fingerprint `66eeaadab4ad28a8f8eaf8fe1a0748f9`.
The first Flyway-enabled boot remains the orchestrator's scheduled act.

## Scope (owned by Agent B)

`ops/flyway/**`, `backend/database/**`, and this file. Not touched:
`backend/worker-runtime/**`, `backend/channels/**`,
`backend/notifications-realtime/**` (Phase 5b), every other `backend/*` module,
`apps/**`, `packages/**`, `docs/migration/flyway-runbook.md`,
`docs/migration/schema-drift-report.md`, and the Phase-2..4 reports.

One Phase-2 file inside `ops/flyway/` was amended
(`V20260814000100__vector_indexes.sql`, see "Defects found"); that is inside this
phase's ownership globs and is disclosed below.

## Migration inventory

The chain after this phase, in the order Flyway applies it:

| Version | File | Applied to `firstelement`? | What it does |
| --- | --- | --- | --- |
| `20260814000000` | `V20260814000000__baseline_verified_live_schema.sql` | **stamped, not executed** (at the orchestrator's boot) | 45 tables, 23 enums, `pgcrypto` + `pgvector` |
| `20260814000100` | `V20260814000100__vector_indexes.sql` | pending | HNSW cosine index (amended: schema-qualified) |
| `20261004000100` | `V20261004000100__outbox.sql` | pending | enum `OutboxState`; table `outbox_events`; 6 indexes |
| `20261004000700` | `V20261004000700__job_attempts.sql` | pending | enums `JobAttemptOutcome`, `DeadLetterStatus`; tables `job_attempts`, `job_dead_letters`; 7 indexes |
| `20261004001000` | `V20261004001000__idempotency_records.sql` | pending | enum `IdempotencyState`; table `idempotency_records`; 3 indexes |

| File | SHA-256 (both copies byte-identical) |
| --- | --- |
| `V20260814000000__baseline_verified_live_schema.sql` | `f3df55c3a2abb142b3c67c480777c57e712db0a8ddad825d6a9d212a88fe0bb8` (unchanged) |
| `V20260814000100__vector_indexes.sql` | `3a0fecbbb596d4d2c1cd9d2dd247f816c6ee93cfe0911f92713119887a927760` (**amended**) |
| `V20261004000100__outbox.sql` | `4cc833a03581a1152c988ef7ec67e71f3bf220a3cd3ae16afa6e2d517b18a3ad` |
| `V20261004000700__job_attempts.sql` | `84db18741188ccb0b1a33443a29c1bdc3e3109b8ef201336df483e1ebe870c9e` |
| `V20261004001000__idempotency_records.sql` | `fbf901c8a32f46b044c67632323ddad3dac4e5199ab6f61d067fe8244d078f45` |

Deleted: `ops/flyway/pending/V202610040001__outbox.sql.todo` and
`ops/flyway/pending/V202610040007__job_attempts.sql.todo`. A promoted draft that
stays on disk is a second source of truth for a decision; both files' open
questions are now answered in the promoted file's header.

New supporting assets:

| Asset | Purpose |
| --- | --- |
| `ops/flyway/reference/outbox-claim.sql` | the canonical claim / complete / retry / dead-letter / reap / replay statements |
| `ops/flyway/reference/job-and-idempotency-statements.sql` | attempt + DLQ writes, idempotency reserve/complete, tenant-scoped read models |
| `ops/flyway/verification/V10_outbox_job_slice.sql` | read-only verification for this slice, in the standard bundle |
| `ops/flyway/rollback/undo_phase5a.sql` + `rollback/README.md` | armed undo and the Phase-5a rollback/recovery runbook |
| `ops/flyway/verification/run-verification.ps1` | gained `-ExpectedEnumCount` (default 23 = the Phase-2 baseline) |

Neither `reference/*.sql` nor `rollback/*.sql` is matched by Flyway's `V*.sql`
pattern, and neither lives in `ops/flyway/sql/`, so neither can be executed as a
migration by accident.

## The shape that was delivered

Every one of the four new tables: `uuid` primary key, `organizationId uuid NOT
NULL`, nullable `projectId` (organisation-wide work only, listed explicitly),
`createdAt`/`updatedAt` `timestamp(3) without time zone`, explicit FKs where a
parent is guaranteed, tenant-led indexes, no `DROP`, no `RENAME`, no `UPDATE` of
any existing row.

`outbox_events` carries what the delivery path needs and nothing else: lease
(`leaseOwner` + `leaseUntil`), attempt count (`attempts`), next-attempt time
(`nextAttemptAt`), payload JSON (`payload`), a tenant-scoped `idempotencyKey`,
dead-letter state (`state = 'DEAD'` + `deadLetteredAt` + `deadLetterReason`) and
replay state (`replayCount`, `lastReplayedAt`, `replayedByUserId`).

Index census on a migrated throwaway: 49 tables, 176 indexes, 27 enums, 76 FKs,
49 PKs, **14 CHECK constraints** (the first in this schema), tenant-led 78 /
project-led 15 / parent-key 22 / unique-other 57 / vector-led 1 / global 3.

## Key decisions

| Decision | Rationale |
| --- | --- |
| **14-digit versions, not the drafts' 12** | Flyway orders versions **numerically**. `202610040001` is 202,610,040,001, which is far *smaller* than the 14-digit baseline `20260814000000`. The fixture applied the outbox migration to an empty database and failed with `relation "public.organizations" does not exist`. The promoted files keep the draft ordinal in the seconds field (`001 -> 00:01:00`) so the draft number stays visible, and all seven remaining drafts now carry a `RENUMBER BEFORE PROMOTING` banner. |
| **`state`/`nextAttemptAt`/`leaseUntil`, not `status`/`availableAt`/`leaseExpiresAt` | The Phase-5a request fixed the claim predicate; one name per concept across migration, dispatcher and fixture. |
| **`OutboxState` = PENDING, LEASED, SENT, DEAD — the draft's `FAILED` dropped | "Failed but retryable" is exactly `(attempts, nextAttemptAt, lastError)`; "failed permanently" is `DEAD`. A value with no query that needs it and no transition that does not duplicate a column is a trap for the next reader. Adding a value later is additive; removing one is not. |
| **Lease is a column pair, and the reaper is a separate statement** | A crashed worker leaves `state='LEASED'` with an expired lease, and the claim predicate only sees `PENDING`. Without `outbox-claim.sql §7` those rows would be stranded forever. A partial index on `leaseUntil WHERE state='LEASED'` keeps that sweep cheap. |
| **The canonical claim is tenant-led** | FR-1.2 and AGENTS.md require tenant scope in *jobs*, and Phase 5 needs per-tenant rate limits and queue fairness. The dispatcher enumerates active organisations and claims per tenant, round-robin. The org-wide predicate from the request is supported as well, by a second, tenant-less scheduling index; both statements are in `reference/outbox-claim.sql` with the choice explained. |
| **The exact org-wide predicate gets its own index** | The request named it and Phase 5b may legitimately run it. It exposes only `state` and timing, never payload or tenant data, and it is not an authorisation path — a claimed row must be re-read under tenant scope before it is acted on. V9's census therefore reports 3 tenant-less indexes instead of 1, all three named in an allow-list asserted by `V10 §5a` and by the fixture. |
| **Every uniqueness is tenant-scoped** | A global unique index on `idempotencyKey`, `(queueName, jobId, attemptNumber)` or `(organizationId, queueName, jobId)` turns a duplicate into a cross-tenant existence oracle: the violation message tells one tenant that another already used the key. Tenant scope also matches how provider ids actually behave. |
| **Uniqueness via unique INDEXES, not unique CONSTRAINTS** | The 53 existing unique indexes and V4's `unique_constraints = 0` invariant stay true. Enforcement is identical and `ON CONFLICT` inference is unaffected. |
| **14 CHECK constraints — the first in this schema** | Recorded debt #8 was "zero CHECK constraints; validity is application-enforced". Each of these encodes something no correct writer can violate, so they cannot reject a valid write: `attempts >= 0`, `replayCount >= 0`, the lease pair, `deliveredAt` iff `SENT`, `deadLetteredAt` iff `DEAD`, `attemptNumber >= 1`, `durationMs >= 0`, `isReplay` implies `replayOfId`, a resolved dead letter must name who and when, an idempotency record that is `COMPLETED` iff it has a status, and an in-flight record is the only one holding a lock. Each is exercised by a negative test. |
| **Defaults are `(CURRENT_TIMESTAMP AT TIME ZONE 'UTC')`** | The columns are `timestamp without time zone` (V6 forbids `timestamptz`), and the baseline's bare `DEFAULT CURRENT_TIMESTAMP` stores the *session's* wall clock. Writing UTC explicitly makes a stored timestamp zone-independent. `updatedAt` deliberately has **no** default, exactly like every mutable table in the baseline. |
| **`job_attempts` has no `updatedAt`** | An attempt is a fact about the past; a column that invites amendment is an invitation. The migration's own guard fails if `updatedAt` ever appears. |
| **No partitioning of `job_attempts`** (draft Q2) | Partitioning is not additively reversible (undoing it rewrites the table), a partitioned parent forces the partition key into every admin query, and a UNIQUE constraint would have to include it, changing the tenant-scoped contract. Partitioning is a decision for the phase that has measured volume. |
| **Dead letters are two different facts** (draft Q3) | `outbox_events.state='DEAD'` is the *event's* terminal state; `job_dead_letters` is the *queue job's* operator lifecycle, which one enum value cannot express because a replayed job can fail again and must return to `OPEN`. |
| **Retention is a product decision, not DDL** (draft Q1, all three) | No trigger, rule or scheduled deletion is created anywhere in this slice. `expiresAt` / `deadLetteredAt` make a purge expressible later; deleting delivery evidence needs the §7 data-lifecycle decision and Phase 7's typed-deletion flow. |
| **`idempotency_records` does not touch `messages.metadata`** (draft Q3 there) | Moving Phase 4's reply keys is a data migration on an existing table, which this phase excludes, and it needs a code change in a module another agent owns. The two stores are therefore used for different things and cannot disagree. |
| **Strict `CREATE TABLE`, not `IF NOT EXISTS`** | A pre-existing `outbox_events` must fail the migration loudly instead of being silently adopted. `V20260814000100` keeps `IF NOT EXISTS` only because it reconciles an index name that may already exist. |
| **`scope`, `queueName`, `eventType` are text; states are enums** | A state machine's value list is a contract (all 23 baseline enums agree with the code). A vocabulary that will grow must not need a migration. |

## Verification (exact commands, Windows, Docker Desktop 29.8.1, JDK 24)

Full build, every task re-executed, no build cache, no skipped test:

```
.\gradlew.bat build --rerun-tasks --no-build-cache --no-daemon --console=plain
```
→ `BUILD SUCCESSFUL in 3m 42s`, **42 actionable tasks: 42 executed**.
Aggregated JUnit XML across every module: **316 tests, 0 failures, 0 errors, 0
skipped** (Phase 4 reported 311; `:backend:database:test` went 3 → 8).

Database fixture on its own:

```
.\gradlew.bat :backend:database:test --rerun-tasks --no-build-cache --no-daemon --console=plain
```
→ `BUILD SUCCESSFUL`, all eight scenarios `PASSED`, no skip path:

| Scenario | What it proves |
| --- | --- |
| fresh pgvector database migrates to the verified real schema, then additively to the Phase-5a slice | two-stage migrate (`target=20260814000100`, then the rest): every Phase-2 assertion still holds at its historical numbers, then 49 tables / 27 enums / 76 FKs / 14 CHECKs / the four new tables / the tenant-less allow-list |
| existing legacy database is stamped, not replayed; additive migration still runs | `migrationsExecuted == 4`, rows and **UUIDs** preserved, `outbox_events` created and empty, all four versions in `flyway_schema_history` |
| outbox claim is tenant-scoped, lease-protected, retryable and index-supported | 5 due rows claimed, a future-dated row and another tenant's rows not claimable; a live lease blocks a second claim; a worker that does not hold the lease cannot complete the row (0 rows, row untouched); retry returns it to `PENDING`; an expired lease does **not** make a `LEASED` row claimable until the reaper runs; attempts keep counting across the reaping |
| two concurrent claimers never claim the same outbox event | 20 seeded events, two threads and two connections with a latch handshake (A holds its locks open while B claims): 10 + 10, **disjoint**, union = all 20, every row `attempts = 1`, two distinct lease owners |
| the claim query is index-supported, not a sequential scan | 6 000 rows + `ANALYZE`, real expired leases: `Index Scan`, never `Seq Scan`, for the tenant claim, the org-wide sweep and the reaper |
| dead lettering, replay and attempt history are audited and append-only | `DEAD` without `deadLetteredAt` refused; a half-set lease refused; the failed attempt's outcome unchanged after a replay; `isReplay` without `replayOfId` refused; duplicate attempt number refused; one DLQ row per job with accumulating attempts; `RESOLVED` without who/when refused; replay makes the event claimable again and records who and when |
| idempotency records are tenant-scoped, reserved before work, and completed once | the same key twice in one tenant is a no-op returning nothing; the same key in **another tenant** is allowed; a different scope is allowed; `COMPLETED` without a status refused; a completed record cannot complete twice |
| ops/flyway/sql and the packaged db/migration chain are byte-identical | all five files, both copies |

Throwaway-database rehearsal (every throwaway was created and **dropped** inside
the existing container):

```
docker exec firstelement-postgres psql -U firstelement -d postgres -q `
  -c "DROP DATABASE IF EXISTS firstelement_phase5a_check;" `
  -c "CREATE DATABASE firstelement_phase5a_check TEMPLATE template0;"
foreach ($f in Get-ChildItem ops\flyway\sql -Filter *.sql) {
  docker cp $f.FullName "firstelement-postgres:/tmp/$($f.Name)"
}
docker exec firstelement-postgres psql -U firstelement -d firstelement_phase5a_check `
  -v ON_ERROR_STOP=1 `
  -f /tmp/V20260814000000__baseline_verified_live_schema.sql `
  -f /tmp/V20260814000100__vector_indexes.sql `
  -f /tmp/V20261004000100__outbox.sql `
  -f /tmp/V20261004000700__job_attempts.sql `
  -f /tmp/V20261004001000__idempotency_records.sql
.\ops\flyway\verification\run-verification.ps1 -Container firstelement-postgres `
  -Database firstelement_phase5a_check -ExpectedEnumCount 27 `
  -OutputDir <evidence>\throwaway-full -ThrowOnViolation
```
→ whole chain in **one** psql session, `schema fingerprint:
f436fca35638e2d02418376333fbf67c`, `enum summary: 27 | 1 | 1`, V1–V10 all pass.

The same throwaway stopped at baseline + `V20260814000100` gives fingerprint
`bdf70c7c24dfe23c748281eb6c5c4a03` — **bit-for-bit the value Phase 2 recorded**,
which is the evidence that the `V20260814000100` amendment changed no schema.

Claim proof at the psql level, on that throwaway, using the exact statements from
`ops/flyway/reference/outbox-claim.sql`:

```
seed 1 tenant + 5 claimable events
canonical claim as worker-A      -> UPDATE 5
canonical claim as worker-B      -> UPDATE 0     (the lease is held)
state after two claims           -> LEASED / worker-A / 5 rows / attempts = 1
age the leases, then reap        -> UPDATE 5     (back to PENDING, attempts still 1)
another tenant's view            -> 0 rows
```

Rollback rehearsal (`ops/flyway/rollback/undo_phase5a.sql`, armed):

```
# unarmed -> "UNDO PHASE5A SKIPPED"
# armed, slice empty -> PHASE5A_REMOVED, 6 baseline tables still present
# armed, 5 outbox rows present -> REFUSED, per-table row counts printed first
# armed + -v confirm_data_loss=yes -> dropped, PHASE5A_REMOVED
.\ops\flyway\verification\run-verification.ps1 -Container firstelement-postgres `
  -Database firstelement_phase5a_check -ExpectedEnumCount 23 `
  -OutputDir <evidence>\after-undo -ThrowOnViolation
```
→ `schema fingerprint: bdf70c7c24dfe23c748281eb6c5c4a03` — **exactly** the
post-`V20260814000100` fingerprint, 23 enums, reconciled enums intact,
`PHASE5A_NOT_PRESENT`. The undo is lossless at the schema level and is proven by
fingerprint equality, not by assertion.

Backup and restore:

```
.\ops\backup-restore\backup.ps1 -Container firstelement-postgres -Database firstelement `
  -OutputDir <evidence>\backup
.\ops\backup-restore\restore-rehearsal.ps1 `
  -Dump <evidence>\backup\20261004T061038Z_firstelement.dump `
  -EvidenceDir <evidence>\restore-check -Dispose
```
→ dump `20261004T061038Z_firstelement.dump`, SHA-256
`b19af2af91a98e426d44b0f21e27f697141c273880e8204dd664c2a2b870cd32`;
restore rehearsal **PASS**: `fingerprintMatches True`,
`sourceEnumContractOk True`, `restoredEnumContractOk True`,
`rowCountsIdentical True`, `identitySample 41/1/4/3/43`, throwaway disposed.
This dump therefore contains **no** outbox rows and no `flyway_schema_history` —
correct and complete for the pre-boot state, and it must be re-taken after the
boot if the intent is to protect the queue.

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
`firstelement`, `postgres`. The development database was not modified.

## Security / tenancy checks

- `organizationId uuid NOT NULL` on all four new tables — asserted by the fixture
  (0 nullable) and by `V10 §4a`.
- The tenant-less allow-list is asserted, not described: exactly three
  tenant-less indexes exist (`sessions_expiresAt_idx`,
  `outbox_events_dispatch_sweep_idx`, `outbox_events_expired_lease_idx`), each
  carrying only state/timing. Any fourth fails the fixture.
- The claim is tenant-scoped by default; the org-wide sweep is documented as a
  scheduling scan with the invariant "re-read the row under tenant scope before
  acting on it", and the fixture proves another tenant's queue is untouched.
- Uniqueness is tenant-scoped everywhere, so no constraint violation can disclose
  another tenant's key, job or attempt count (asserted for the idempotency key
  and for attempt numbering).
- Cross-tenant negatives asserted with data: an outbox row whose project belongs
  to a different tenant (`V10 §6`), a claim that never returns another tenant's
  rows, and the fixture's two-tenant claim.
- Every lifecycle statement in `reference/*.sql` carries `"organizationId"` in its
  predicate and `leaseOwner` where a lease exists. A worker that does not hold a
  lease cannot complete, retry or dead-letter a row (asserted: 0 rows affected).
- Redaction boundaries are enforced where they can be: `job_dead_letters`
  `payloadSummary` is documented as a summary that never receives the payload
  (FR-10.6), and `idempotency_records` stores `responseBodyHash`, never a body.
- Dead letters and resolutions are attributable: `RESOLVED`/`DISCARDED` is
  refused without `resolvedByUserId` + `resolvedAt`; replay records
  `replayedByUserId` + `lastReplayedAt`; attempts record `workerId`,
  `correlationId` and `causationId`.
- No `.env`, credential, secret, session token or customer export was read or
  printed. No script reads `.env`; every `docker exec` used the container's local
  socket.

## Migration / data impact

- **Applied to `firstelement`: nothing.** No Flyway stamp, no DDL, no DML, no row
  touched, no UUID regenerated.
- The first Spring boot will stamp `20260814000000` and then apply **four**
  additive migrations instead of one. Row counts, UUIDs and business keys are
  unaffected; the only change to existing objects is one new index (from
  `V20260814000100`).
- **No backfill.** Every object in this slice is new and no existing row is read
  or written by any of the three migrations. The resumable driver
  `ops/flyway/backfill/run-backfill.ps1` is unchanged and still available for the
  migrations that will need it (`V202610040009` + `B1`); nothing in this phase
  used it, and inventing a backfill for a schema with no rows to transform would
  have been theatre.
- All rehearsal data lived in disposable Testcontainers
  `pgvector/pgvector:pg16` instances or in throwaway databases inside the dev
  container. Databases `firstelement_phase5a_check`, `firstelement_v100_check`,
  `firstelement_5a_sp`, `firstelement_5a_sp2` and
  `firstelement_restore_check` were created and **dropped**. Final list:
  `firstelement`, `postgres`.
- No `db push`, no `prisma migrate`, no destructive DDL against any persistent
  database.

## Defects found by the verification

Recorded because each is the kind of defect only a real database finds.

| Defect | Found by | Fix |
| --- | --- | --- |
| **Flyway applies the 12-digit draft versions BEFORE the 14-digit baseline.** On a fresh database Flyway migrated straight to `202610040001 - outbox` and failed: `relation "public.organizations" does not exist`. | the fixture | renumbered to 14 digits, keeping the draft ordinal in the seconds field; banner added to all seven remaining drafts |
| `to_regclass('public.<index>')` returns NULL for an index, so a post-condition guard built on it rejects a migration that did everything right | the first dry run of `V20261004000100` | the guards query `pg_indexes`; the reason is recorded in the file header so nobody "simplifies" it back |
| `idempotency_records_organizationId_projectId_state_createdAt_idx` is 64 bytes; PostgreSQL truncates identifiers at 63 and would have created `..._createdAt_id` | the same dry run (`NOTICE: identifier ... will be truncated`) | renamed `..._state_created_idx`; the 63-byte limit is documented |
| `V20260814000100` used an unqualified table name and operator class, so applying the chain in **one** psql session inherited the baseline's `search_path=''` and failed twice over (`relation "document_chunks" does not exist`, then `operator class "vector_cosine_ops" does not exist for access method "hnsw"`) | the single-session rehearsal | both names qualified; Flyway was never affected (it resets `search_path` per migration). The file has never been applied anywhere, so no recorded checksum changes — the orchestrator must update the SHA-256 in the Phase-2 runbook |
| The **undo script itself** used `DROP TYPE IF EXISTS public.OutboxState`; PostgreSQL folds that to lower case, it matched nothing, printed `NOTICE: ... does not exist, skipping`, left all four enums behind, and the script still reported success | the fingerprint comparison after the undo (`27` enums where `23` were expected) | types quoted, and the post-condition now checks the types as well as the tables |
| The byte-identity test failed once during this phase because I amended `V20260814000100` in `ops/flyway/sql/` and forgot to re-mirror it | the fixture itself | re-mirrored; this is the check working |
| Test-side binding bugs (untyped `setObject` for `UUID`, an `Integer` passed as a string, `null` bound as the literal `'null'`) | the fixture | `bind` is type-aware: `Types.OTHER` for UUID, `setInt`, `setBoolean`, `setNull` for null |

## Known risks / debt carried forward

1. **Retention is undefined.** No row created by this slice is ever deleted by
   DDL, so `outbox_events`, `job_attempts` and `idempotency_records` grow without
   bound until the §7 data-lifecycle decision exists. `job_attempts` is the
   highest-write table in the system. This is the single largest open item.
2. **Two sources of truth for a reply idempotency key.** Phase 4 stores reply
   keys in `messages.metadata->>'idempotencyKey'`; this phase adds
   `idempotency_records` and deliberately does not migrate the existing keys.
   Phase 5b must pick one owner per key space, or a retry could be suppressed by
   one store and replayed by the other.
3. **`ON DELETE CASCADE` from tenant and project onto all four tables.** A typed
   tenant/project deletion therefore removes delivery evidence as well as pending
   work. That is the privacy-correct direction and it matches 40+ baseline tables,
   but no tenant deletion may be performed until §7's impact count and lifecycle
   rules exist. Process gate, not a schema gap.
4. **The org-wide claim sweep is tenant-less.** Safe by construction (state and
   timing only) and allow-listed, but it is a second code path. If Phase 5b
   implements per-tenant claiming only, `outbox_events_dispatch_sweep_idx` becomes
   unused and should be dropped by a later additive migration (a `DROP INDEX`, so
   it needs its own reviewed version).
5. **Two `TimeZone` dependencies.** The canonical statements use
   `(now() AT TIME ZONE 'UTC')`, and the new defaults write UTC explicitly, but
   the *baseline* tables still default to a session-local `CURRENT_TIMESTAMP`.
   Every connection must be UTC. `V10 §7` reports `SHOW TimeZone` and the
   application must set it; nothing in the schema can enforce it.
6. **The V9 "global bucket" expectation in the Phase-2 runbook is now wrong**
   (1 → 3). The full list of runbook numbers that change is in
   `ops/flyway/rollback/README.md §5`; the runbook is not this phase's file.
7. **No CI gate yet** (quality agent). `run-verification.ps1 -ThrowOnViolation`
   exit codes are still checked by hand, and `--rerun-tasks --no-build-cache` is
   still required for the fixture to actually run.
8. **The baseline is still on disk in `packages/database/prisma`**, quarantined
   and divergent. Until it is retired, `prisma migrate deploy` against
   `firstelement` would fight Flyway. Carried from Phase 2, unchanged.
9. **`V202610040009` is still `.sql.todo`** and still needs the
   `CONCURRENTLY` vs maintenance-window decision (ADR-0005 §3). Untouched here.
10. **The plan-shape assertion is `Index Scan` + `not Seq Scan`,** not "this exact
    index". That is deliberate: the planner may legitimately prefer a different
    overlapping index as the data changes, and the test asserts the property that
    matters (the queue is never sequentially scanned) rather than a cost decision.

## Follow-up for the orchestrator

1. **Merge `ops/flyway/rollback/README.md §5` into
   `docs/migration/flyway-runbook.md`**: the chain table, the new SHA-256 for
   `V20260814000100`, 49 tables / 176 indexes / 27 enums / 76 FKs / 14 CHECKs, the
   V9 census (78/15/22/57/**3**), the tenant-less allow-list, the two extra
   nullable `projectId` columns, `-ExpectedEnumCount 27` for the post-boot
   bundle, and the 14-digit version rule.
2. **Confirm the first Flyway boot** against `firstelement`: it will stamp
   `20260814000000` and apply `20260814000100`, `20261004000100`,
   `20261004000700`, `20261004001000`. Take a backup immediately before, and
   re-take one after — the Phase-5a backup in the evidence directory
   (`b19af2af…`) proves the pre-boot state only.
3. **Phase 5b must build the dispatcher on
   `ops/flyway/reference/outbox-claim.sql`, not on re-derived SQL.** The claim
   predicate, the `ORDER BY`, the atomic claim-and-update shape and the UTC
   comparison are part of the contract with the indexes. The lease reaper (§7) is
   not optional: without it a crashed worker's row is stranded.
4. **Decide the retry/idempotency ownership split** (risk 2) before Phase 5b
   writes provider-event deduplication, and decide whether the org-wide sweep is
   needed (risk 4).
5. **Own the retention decision** (risk 1) for `outbox_events`, `job_attempts`,
   `job_dead_letters` and `idempotency_records`, including dead letters outliving
   attempts. Nothing deletes rows until that decision exists.
6. **Version-numbering rule for every future promotion**: 14 digits
   `yyyyMMddHHmmss`, strictly greater than the previous version. The seven
   remaining drafts still carry 12-digit names and now carry a banner saying so.
7. **Quality agent**: wire `./gradlew build --rerun-tasks --no-build-cache
   --no-daemon` plus `run-verification.ps1 -ThrowOnViolation` into CI, and keep
   forcing `--rerun-tasks` for the database fixture (carried from Phases 2–4, now
   with 8 tests instead of 3).
