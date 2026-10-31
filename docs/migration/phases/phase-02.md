# Phase 2 — PostgreSQL/pgvector target contract and Flyway baseline

## Goal

Establish a **verified** PostgreSQL/pgvector target contract and a Flyway
baseline that does not lose data, and close the enum drift that blocked
baselining in Phase 0. No runtime data was changed.

## Scope (owned by Agent B)

`backend/database/**`, `ops/flyway/**`, `ops/backup-restore/**`,
`docs/migration/schema-drift-report.md`, `docs/migration/flyway-runbook.md`,
append-only `docs/migration/adr/0002-flyway-baseline-quoted-columns.md`, and
this file. `backend/app-bootstrap/build.gradle.kts` was already carrying the
`:backend:database` project dependency from Phase 1 and was not further
modified. No file under `apps/**`, `packages/**`, or
`backend/app-bootstrap/src/**` was touched.

## Delivered

1. **Schema drift report** — `docs/migration/schema-drift-report.md`.
   Live database vs `schema.prisma` vs the `init` migration, with every
   measured number: 45 tables / 23 enums / 62 FKs / 155 indexes / 147 rows.
   Produced by a read-only analyser, `backend/database/scripts/schema_drift_report.py`.
2. **Enum drift resolved before baselining.** `EmployeeAvailabilityStatus` and
   `AIRunDecision` both disagreed with the `init` migration. The live values
   (== `schema.prisma`) are the adopted contract; the `init` migration is
   **quarantined**, not corrected.
3. **Flyway assets** under `ops/flyway/`:
   - `sql/V20260814000000__baseline_verified_live_schema.sql` — schema-only
     `pg_dump` of the verified live database, regenerable read-only;
   - `sql/V20260814000100__vector_indexes.sql` — additive HNSW cosine index;
   - `pending/V202610040001..9__*.sql.todo` — 9 **inert** drafts (outbox,
     export jobs, usage ledger, finance, report definitions, AI policy, job
     attempts, audit streams, AI-run child tenant scope) each with open
     questions and a promotion checklist;
   - `generate/generate-baseline.ps1`, `verification/V1..V9` +
     `run-verification.ps1`, `backfill/B0`,`B1`,`B2` + `run-backfill.ps1`.
4. **`backend/database` Gradle module** — Flyway core +
   `flyway-database-postgresql`, packaging the chain at
   `classpath:db/migration`, plus `FlywayDatabaseConfiguration`, which chooses
   *stamp vs execute* from the database's own state and fails closed if the
   schema cannot be read.
5. **Testcontainers pgvector fixture** —
   `FlywayBaselineAcceptanceTest`, three scenarios, no skip path.
6. **Backup/restore rehearsal with evidence** — `ops/backup-restore/backup.ps1`
   and `restore-rehearsal.ps1`, plus rehearsal-only DDL and acceptance queries
   in `ops/backup-restore/rehearsal/`.
7. **Runbook** — `docs/migration/flyway-runbook.md`: wiring, verification
   queries, backup, restore rehearsal, rollback and recovery.

## Key decisions

| Decision | Rationale |
| --- | --- |
| Baseline from the **verified live database**, not the `init` migration | The `init` migration is missing 14 tables, 1 enum, 2 correct enum value lists and 3 columns including the `ticketNumber`/`cxId` business keys. The live database and `schema.prisma` agree exactly. |
| Quarantine the `init` migration instead of rewriting it | Prisma history may still be needed while the legacy runtime exists; rewriting it is an orchestrator decision. The interim correct state is "do not use it". |
| Quoted camelCase preserved verbatim; explicit Spring mapping; no naming strategy | ADR-0002. The legacy Prisma client, seed, and integration tests all depend on the physical shape. A rename combined with a runtime migration would be unrecoverable. |
| `V20260814000100` adds **only** the HNSW index | The tenant pre-filter requirement is already satisfied by `document_chunks_organizationId_projectId_knowledgeBaseId_idx` over the identical three columns; a second index would double write amplification on the hottest knowledge table. |
| AI-trace child tenant columns prepared but **not** applied | Expand (nullable columns) → backfill (resumable) → contract (`NOT NULL` after evidence). The expand migration is `.sql.todo`; the backfill was rehearsed only. |
| Nullable `projectId` on `audit_logs`/`notifications` left as-is | Both are organisation-wide streams by design. The 43 live rows are login/logout security events; filling `projectId` would be fabricated data. |

## Verification (exact commands, Windows, Docker Desktop 29.8.1)

Read-only drift analysis:

```
python backend\database\scripts\schema_drift_report.py
```
→ `live database == schema.prisma : YES`, `init migration usable as a baseline
source : NO - quarantined` (14 missing tables, 1 missing enum, 2 enum drifts).

Live-database evidence bundle (read-only):

```
.\ops\flyway\verification\run-verification.ps1 -Container firstelement-postgres `
    -Database firstelement -OutputDir <evidence> -ThrowOnViolation
```
→ `schema fingerprint: 66eeaadab4ad28a8f8eaf8fe1a0748f9`,
`enum summary: 23 | 1 | 1`, V1–V9 all pass.

Baseline fidelity, on a throwaway database:

```
docker exec firstelement-postgres psql -U firstelement -d postgres `
    -c "CREATE DATABASE firstelement_baseline_check TEMPLATE template0;"
Get-Content -Raw ops/flyway/sql/V20260814000000__baseline_verified_live_schema.sql |
    docker exec -i firstelement-postgres psql -U firstelement -d firstelement_baseline_check -v ON_ERROR_STOP=1 -q
Get-Content -Raw ops/flyway/sql/V20260814000100__vector_indexes.sql |
    docker exec -i firstelement-postgres psql -U firstelement -d firstelement_baseline_check -v ON_ERROR_STOP=1 -q
.\ops\flyway\verification\run-verification.ps1 -Database firstelement_baseline_check -OutputDir <evidence> -ThrowOnViolation
```
→ `bdf70c7c24dfe23c748281eb6c5c4a03`. Diffing the two `V1_schema_fingerprint.txt`
files yields **exactly one** added object, the HNSW index the additive migration
creates. Nothing else differs.

Backup:

```
.\ops\backup-restore\backup.ps1 -OutputDir <backups>\2026-10-04-phase2
```
→ `pg_dump -Fc` + `-Fp` + globals, SHA-256 in `manifest.json`, plus the
verification bundle. Dump SHA-256
`eddead1b6a0b082e15b3f5d42263c72b69d886bc90aa6e76f4e218ee2eae153e`.

Restore rehearsal (disposable database, `-Dispose`):

```
.\ops\backup-restore\restore-rehearsal.ps1 -Dump <dump> -EvidenceDir <evidence> -Dispose
```
→ **PASS**: `fingerprintMatches True`, `restoredEnumContractOk True`,
`rowCountsIdentical True`, `identitySample 41/1/4/3/43`.

B1 backfill rehearsal on the throwaway database:

```
.\ops\flyway\backfill\run-backfill.ps1 -Script B1 -Database firstelement_restore_check `
    -BatchSize 2 -MaxBatches 10 -OutputDir <evidence> -Armed
Get-Content -Raw ops/backup-restore/rehearsal/b1_evidence.sql |
    docker exec -i firstelement-postgres psql -U firstelement -d firstelement_restore_check -v ON_ERROR_STOP=1 -f -
```
→ `B1_REMAINING_TOTAL 0`, `B1_FILLED_TOTAL 8`, `B1_CROSS_TENANT_* 0`,
`B1_REJECTIONS 0`, `B1_RUN_STATUS COMPLETED`, `B1_BATCHES_RUN 2` (resumed
across two batches). Unarmed invocation printed `B1 SKIPPED` and changed
nothing. Rerun after completion was `UPDATE 0` — a true no-op.

Build and migration fixture:

```
.\gradlew.bat build --no-daemon --console=plain
.\gradlew.bat :backend:database:test --rerun-tasks --no-build-cache --no-daemon --console=plain
```
→ `BUILD SUCCESSFUL`. `FlywayBaselineAcceptanceTest`:
`fresh pgvector database migrates to the verified real schema` **PASSED**,
`existing legacy database is stamped, not replayed; additive migration still
runs` **PASSED**, `ops/flyway/sql and the packaged db/migration chain are
byte-identical` **PASSED`.

> `--rerun-tasks --no-build-cache` is required. A plain `build` reports
> `UP-TO-DATE` (or restores the Gradle build cache) and silently does **not**
> execute the fixture. A skipped migration test is not a pass.

Post-phase state of `firstelement`, re-verified: **45** application tables,
**0** `flyway_schema_history` rows, fingerprint **unchanged** at
`66eeaadab4ad28a8f8eaf8fe1a0748f9`. The live database was not modified.

## Test commands

```
.\gradlew.bat build --no-daemon --console=plain
.\gradlew.bat :backend:database:test --rerun-tasks --no-build-cache --no-daemon --console=plain
python backend\database\scripts\schema_drift_report.py
python backend\database\scripts\schema_drift_report.py --json
python backend\database\scripts\schema_drift_report.py --jdbc-only
.\ops\flyway\verification\run-verification.ps1 -Database firstelement -OutputDir <dir> -ThrowOnViolation
.\ops\backup-restore\backup.ps1 -OutputDir <dir>
.\ops\backup-restore\restore-rehearsal.ps1 -Dump <dump> -EvidenceDir <dir> -Dispose
.\ops\flyway\backfill\run-backfill.ps1 -Script B1 -Database firstelement_restore_check -BatchSize 2 -MaxBatches 10 -OutputDir <dir> -Armed
```

## Security / tenancy checks

- `organizationId` is `NOT NULL` on **every** table that has it (0 nullable
  occurrences), verified by `V6` and by the fixture.
- The only 4 tables without `organizationId` are `organizations` (the tenant
  root) and the 3 AI-trace children. The children's remediation is prepared but
  **not applied**; it is recorded as inheritance debt, not an exemption.
- The only 2 nullable `projectId` columns are `audit_logs` and `notifications`,
  both organisation-wide streams by design; `B2` classifies every live row and
  finds all 43 to be `User`/`Session` login/logout events, i.e. correct.
- 64 indexes lead with `organizationId`, 15 with `projectId`. The single
  `global` index is `sessions_expiresAt_idx`, deliberate and documented.
- `V8` reports 0 FK orphans, 0 cross-tenant/cross-scope rows, 45 `uuid` PKs, 0
  rows missing a business key, 0 `deals` with an unresolvable raw customer
  uuid, 0 tasks with free-text references.
- pgvector is treated as a retrieval accelerator only. `V20260814000100`
  documents that the tenant/project pre-filter must be in the `WHERE` clause
  before similarity ordering, and it refuses to create a duplicate pre-filter
  index.
- No `.env`, credential, secret, session token, or customer export was read or
  printed. All `docker exec` access used the container's local socket; the
  globals dump uses `--no-role-passwords`.

## Migration / data impact

- **Applied to `firstelement`: nothing.** No Flyway history was stamped and no
  migration ran against it during Phase 2.
- The first Spring boot against `firstelement` will stamp
  `20260814000000` and then apply `V20260814000100`, which adds one index.
  Row counts and UUIDs are unaffected.
- `ops/flyway/pending/*.sql.todo` cannot be executed by Flyway (extension is
  not matched by `V*.sql`).
- Throwaway databases `firstelement_restore_check` and
  `firstelement_baseline_check` were created inside the existing container and
  **dropped**. Final database list: `firstelement`, `postgres`.
- No `db push`, no `prisma migrate`, no destructive DDL against `firstelement`.

## Known risks / debt carried forward

1. The Prisma `init` migration is still wrong on disk. Until it is corrected or
   retired, `prisma migrate deploy` against `firstelement` would fight Flyway.
   `db push` and `migrate deploy` must both stay off that database.
2. `firstelement` has no Flyway history yet. The first boot is the moment the
   baseline gets stamped — it must be run with a backup in hand (§7.4 of the
   runbook).
3. Backups are **not encrypted** by `backup.ps1`; the script refuses to hold a
   key. Encryption must come from platform storage encryption or an externally
   supplied `age`/`gpg` recipient. **Unresolved — orchestrator decision.**
4. `CREATE INDEX CONCURRENTLY` vs a maintenance window is still open for
   `V202610040009`. `CONCURRENTLY` cannot run in a Flyway transaction and needs
   `executeInTransaction=false`. Do not silently drop it.
5. `DATABASE_URL` → JDBC translation is a manual operator step; it should be a
   configuration feature.
6. Three AI-trace tables still inherit tenant scope. Expand/backfill/contract
   is prepared but the expand migration is not promoted.
7. `deals.customer` has no FK and `tasks.*` references are free text.
8. Zero `CHECK` constraints; enum and numeric validity is application-enforced.
9. No CI pipeline (quality agent). Until then `-ThrowOnViolation` exit codes
   must be checked by hand.
10. No PostgreSQL row-level security (`FR-1.6` makes it optional defence in
    depth; application policy remains mandatory).
11. Gradle build caching will report `:backend:database:test` as up-to-date
    without running it. The fixture must be run with
    `--rerun-tasks --no-build-cache` until CI enforces it.

## Follow-up for the orchestrator

- Decide and record the **backup encryption** mechanism (§5 of the runbook).
- Approve/retire the quarantined Prisma init migration so `prisma migrate
  deploy` can be forbidden permanently on `firstelement`.
- Confirm the first Spring boot against `firstelement` (baseline stamp +
  `V20260814000100`) is scheduled with a backup in hand.
- Decide `CREATE INDEX CONCURRENTLY` vs maintenance window before promoting
  `V202610040009`.
- Agent C / Agent D: Spring persistence must map **explicitly** to quoted
  camelCase identifiers with **no** naming strategy, and must bind
  `LocalDateTime` (never `Instant`/`OffsetDateTime`) for the 98
  `timestamp without time zone` columns.
- Agent E: `V202610040001` (outbox) depends on the outbox lease ADR in
  `plan.md §9.4` being accepted first.
- Quality agent: wire `./gradlew build` + `run-verification.ps1
  -ThrowOnViolation` into CI, and force `--rerun-tasks --no-build-cache` for
  the database fixture so a cached result is never read as a pass.