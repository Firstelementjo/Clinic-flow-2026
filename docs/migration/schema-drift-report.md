# Phase 2 — Schema drift report: live database vs Prisma schema vs Prisma init migration

**Status:** RESOLVED — the Flyway baseline is taken from the verified live
database, and the Prisma init migration is quarantined.
**Analysed:** 2026-10-04, Windows 11, Docker Desktop 29.8.1.
**Subject database:** container `firstelement-postgres`, image
`pgvector/pgvector:pg16`, PostgreSQL server 16.15, database `firstelement`.
**Sources compared:**

| Source | Path | Trust level |
| --- | --- | --- |
| Live database | `firstelement` on `localhost:5432` | **verified runtime truth** |
| Prisma schema | `packages/database/prisma/schema.prisma` | declared intent (source of truth for enum values / column contracts) |
| Prisma init migration | `packages/database/prisma/migrations/20260814000000_init/migration.sql` | recorded history — **quarantined** |
| Prisma vector migration | `packages/database/prisma/migrations/20260814000100_vector_index/migration.sql` | recorded history — **quarantined** |

Reproduce the whole table below with:

```
python backend\database\scripts\schema_drift_report.py
python backend\database\scripts\schema_drift_report.py --json
python backend\database\scripts\schema_drift_report.py --jdbc-only   # no database needed
```

The analyser is **read-only**: it queries `pg_catalog` through `psql` inside
the container and never writes to any database. It does not read `.env`;
connection details come from `--container/--user/--database` flags that default
to the synthetic dev values already recorded in `docs/migration/opencode-run.md`.

---

## 1. Verdict

| Question | Answer | Consequence |
| --- | --- | --- |
| Does the live database match `schema.prisma`? | **YES** — 45/45 tables, 23/23 enums, zero column drift, zero nullability drift, zero type drift | The live database is a faithful realisation of the declared intent and is a trustworthy baseline source. |
| Is the Prisma init migration usable as a baseline source? | **NO** — 14 of 45 tables absent, 1 enum absent, 2 enum value lists wrong, 3 tables missing columns | Quarantined. Baselining on it would have produced a schema that does not match the only database the legacy runtime has ever used. |
| Was the known `EmployeeAvailabilityStatus` drift real? | **YES, and worse than documented** — the init migration disagrees on **two** enums, not one | Reconciled in the baseline **before** the baseline file was frozen; `firstelement` itself has not been stamped yet. |
| Is the HNSW vector index present? | **NO** — 0 HNSW and 0 `vector_cosine_ops` indexes in the live database | Reconciled by additive migration `V20260814000100`. |
| Does the live database have Flyway or Prisma migration history? | **NO** — `flyway_schema_history` absent (0 relations named so), `_prisma_migrations` absent (0) | Built with `prisma db push`; the Flyway baseline must be **stamped**, not executed, on this database. |

The decisive measurement: after the baseline plus the additive vector-index
migration were applied to a **throwaway** database, its schema fingerprint
differed from the live database's by **exactly one line** — the HNSW index
that migration is supposed to add. See §6.

---

## 2. Shape comparison

| Metric | `schema.prisma` | live database | init migration |
| --- | --- | --- | --- |
| Tables | 45 | **45** | 31 |
| Enums | 23 | **23** | 22 |
| Extensions | `pgcrypto`, `vector` | `pgcrypto 1.3`, `vector 0.8.6` (plus built-in `plpgsql 1.0`) | `pgcrypto`, `vector` |
| Indexes | (per model) | 155 | — |
| Foreign keys | (per relation) | 62 | — |
| Primary keys | 45 `id` fields | 45 | — |
| `CHECK` constraints | 0 | 0 | — |
| `uuid` primary keys | 45 | 45 | — |
| Total rows | — | 147 | — |

Live extensions confirmed directly:

```
$ docker exec firstelement-postgres psql -U firstelement -d firstelement \
      -Atc "select extname, extversion from pg_extension order by 1;"
pgcrypto|1.3
plpgsql|1.0
vector|0.8.6
```

Row-count map at baseline time (147 rows across 45 tables; non-empty tables
only): `Lead` 6, `views` 6, `messages` 6, `deals` 5, `project_memberships` 5,
`users` 4, `audit_logs` 43, `employees` 3, `customers` 3, `conversations` 3,
`customer_identities` 3, `conversation_events` 3, `accounts` 3,
`channel_connections` 2, `projects` 2, `sessions` 41, `ai_agents` 1,
`ai_agent_versions` 1, `knowledge_gaps` 4, `appointments` 1, `doctors` 1,
`organizations` 1. All remaining tables: 0.

---

## 3. Enum drift (the blocker that had to be cleared before baselining)

`schema.prisma` and the live database agree exactly. The init migration does
not. **Enum value lists are application contract**: the legacy NestJS/Prisma
code writes these labels, so they cannot be changed to match a stale SQL file —
they have to be reconciled in favour of the live values, and the stale SQL
quarantined.

### 3.1 `EmployeeAvailabilityStatus` (the drift named in Phase 0)

| Source | Values |
| --- | --- |
| `schema.prisma` | `ONLINE, OFFLINE, BUSY, BREAK, MEAL, TRAINING, MEETING` |
| **live database** | **`ONLINE, OFFLINE, BUSY, BREAK, MEAL, TRAINING, MEETING`** |
| init migration | `AVAILABLE, BUSY, AWAY, OFFLINE` |

**Resolution: the live values are the contract.** `AVAILABLE` and `AWAY` do not
exist anywhere in the runtime; `BREAK`, `MEAL`, `TRAINING`, and `MEETING` are
written by the employee availability endpoints. The baseline therefore carries
the live list. The init migration keeps `AVAILABLE`/`AWAY` and is quarantined.

### 3.2 `AIRunDecision` — drift not previously called out

| Source | Values |
| --- | --- |
| `schema.prisma` | `REPLY, ASK_CLARIFICATION, ESCALATE, NO_ACTION, RESOLVE` |
| **live database** | **`REPLY, ASK_CLARIFICATION, ESCALATE, NO_ACTION, RESOLVE`** |
| init migration | `REPLY, ASK_CLARIFICATION, ESCALATE, NO_ACTION` (no `RESOLVE`) |

**Resolution: the live values are the contract.** `RESOLVE` is emitted by the
AI run step/decision path; an enum without it would reject a write the
application performs.

### 3.3 `CaseOutcome` — enum absent from the init migration

`CaseOutcome` (`RESOLVED, ESCALATED, UNRESOLVED`) exists in `schema.prisma`
and in the live database (used by `case_resolutions."outcome"`). The init
migration never creates it.

### 3.4 No other enum drift

The analyser compared all 23 enums. `enums missing in live: none`,
`enum value drift: none`. Full per-value listing:
`ops/flyway/verification/V3_enum_contract.sql` (evidence:
`V3_enum_contract.txt`, summary row `23 | 1 | 1`).

---

## 4. Table drift

### 4.1 Tables present in `schema.prisma` and live but **missing from the init migration** (14)

`Lead`, `accounts`, `agent_status_sessions`, `appointments`,
`case_classification_questions`, `case_resolutions`, `case_solution_mappings`,
`conversation_classifications`, `deals`, `doctors`, `notes`, `procedures`,
`tasks`, `views`.

These are the V1.2 CRM middle-workspace and clinic modules. They exist in the
live database because `prisma db push` applied `schema.prisma` directly. If the
init migration had been used as the baseline, a fresh database would have been
missing 14 tables, 3 clinics tables, the whole CRM middle workspace, and the
`case_*` lifecycle that the Phase-1+ acceptance scenarios depend on.

### 4.2 Tables present in the live database but not in `schema.prisma`

**None.**

### 4.3 Column drift vs the init migration (3 tables)

| Table | Missing in init |
| --- | --- |
| `conversations` | `lastReplierUserId`, `ticketNumber` |
| `customers` | `cxId` |
| `employees` | `statusChangedAt` |

`ticketNumber` is a **business key** (`character varying(14)`, unique) and
`cxId` is the external CRM id. A migration that regenerated or dropped them
would break every external reference.

### 4.4 Column drift, live database vs `schema.prisma`

**None.** Zero missing columns, zero extra columns, zero nullability drift,
zero type drift across all 45 tables. The analyser resolves Prisma relation
fields (which have no column), `//` trailing comments, `@db.Uuid` /
`@db.VarChar(n)` / `@db.Text` native-type annotations, and
`Unsupported("vector(1536)")`, so the comparison is physical, not nominal.

---

## 5. Constraint, index, timestamp and tenancy findings (live database)

These are the numbers the baseline was verified against.

| Check | Value | Note |
| --- | --- | --- |
| Foreign keys | 62 | 0 with a non-`CASCADE` `ON UPDATE`; 0 "unsafe" delete actions |
| Primary keys | 45 | one per table |
| Unique indexes | 71 | `pg_constraint` reports 0 `UNIQUE` constraints because Prisma emits them as unique **indexes** |
| `CHECK` constraints | 0 | validity is enforced by the application; recorded as debt |
| Indexes | 155 | +1 after `V20260814000100` (HNSW) = 156 |
| Index scope | 64 tenant-led · 15 project-led · 22 parent-key · 53 unique-other · **1 global** | see §5.1 |
| `document_chunks` valid indexes | 3 | all `indisvalid` |
| Columns in the V6 timestamp census | 98, **all** `timestamp without time zone` | the Prisma `DateTime` contract; zero `timestamptz` anywhere |
| Tables with no `createdAt` column | 4 | `agent_status_sessions`, `ai_runs`, `conversation_classifications`, `conversation_events` (append-only traces carrying `startedAt` / `occurredAt` instead) |

**Timestamp contract — important for Spring.** All 98 columns in the V6
timestamp census are `timestamp(3) without time zone`, and there is not one
`timestamptz` column in the schema. Spring must bind `LocalDateTime` for these
columns. Binding `Instant`/`OffsetDateTime` either fails or silently shifts
every value; this is called out in the baseline header and the runbook.

### 5.1 Tenant scope (`FR-1.2`, `FR-1.3`)

`organizationId` is `NOT NULL` on **every** table that has the column (0
nullable occurrences).

Tables with **no** `organizationId` column — 4, all documented exceptions:

| Table | Why |
| --- | --- |
| `organizations` | it *is* the tenant root |
| `ai_run_steps` | inherits scope from `ai_runs` through `runId` |
| `ai_run_retrievals` | inherits scope from `ai_runs` through `runId` |
| `ai_run_tool_calls` | inherits scope from `ai_runs` through `runId` |

The three AI-trace children are **inheritance debt**, not a permanent
exemption: a tenant-filtered query, a privacy/deletion sweep, and an
index-led tenant filter all need the column on the row itself
(`PROJECT_REQUIREMENTS.md FR-1.3`). Remediation is prepared but **not applied**:
additive migration `V202610040009__ai_run_child_tenant_scope.sql.todo` +
resumable backfill `B1_materialize_ai_run_child_scope.sql` (rehearsed, §7).

Tables with **no** `projectId` column — 12:
`accounts`, `agent_status_sessions`, `ai_run_retrievals`, `ai_run_steps`,
`ai_run_tool_calls`, `employee_availability`, `employee_skills`, `employees`,
`organizations`, `projects`, `sessions`, `users`. Each is either a tenant
root, an organization-scoped principal, an employee-scoped table that reaches
its tenant through `employees.organizationId`, or an AI-trace child.

Nullable `projectId` columns — 2: `audit_logs.projectId`,
`notifications.projectId`. Both are organisation-wide streams by design
(`FR-1.2` requires project scope only on *project-scoped* rows). The audit of
these rows is `ops/flyway/backfill/B2_scope_audit_report.sql`; the 43 live
`audit_logs` rows with `NULL projectId` are all `User`/`Session`
`USER_LOGGED_IN` / `USER_LOGGED_OUT` events, i.e. **correct**, not a gap.
Filling them would be fabricated data.

### 5.1 Index scope classification (`FR-1.3`)

`ops/flyway/verification/V9_index_scope_census.sql` classifies all 155 indexes
into exactly one bucket, in priority order, so the counts add up to the total:

| Class | Count | Meaning |
| --- | --- | --- |
| tenant-led — `btree ("organizationId", …)` | 64 | the canonical tenant filter (`FR-1.3`) |
| project-led — `btree ("projectId", …)` | 15 | project scope **is** tenant scope on a project-scoped table |
| parent-key — `runId` / `conversationId` / `employeeId` / `userId` / `knowledgeBaseId` / `agentVersionId` / `customerId` | 22 | reachable only through an already-scoped parent row |
| unique-other — a `UNIQUE` index that is none of the above | 53 | business keys: `ticketNumber`, `cxId`, `tokenHash`, `(projectId, channel, externalMessageId)`, … |
| global | **1** | `sessions_expiresAt_idx` only |

64 + 15 + 22 + 53 + 1 = 155.

The single `global` index is `sessions_expiresAt_idx ON sessions ("expiresAt")`.
It is **intentional** and must stay reachable without a tenant filter, because a
session-expiry sweep has no tenant. It exposes only `expiresAt` and is not an
authorization path — session reads always join `users` for scope.

The 22 parent-key indexes are **not** drift and must not be "fixed" by
prepending `organizationId`: doing so would duplicate 22 indexes and add write
amplification for no query benefit, because each is only ever used after the
parent row has already been resolved within a tenant scope.

### 5.2 Vector / pgvector

| Item | Live before Phase 2 | After `V20260814000100` |
| --- | --- | --- |
| `pgcrypto` / `vector` extensions | present | present |
| `document_chunks."embedding"` | `vector` | `vector` |
| `document_chunks_embedding_hnsw_idx` (cosine) | **absent** | present |
| `(organizationId, projectId, knowledgeBaseId)` pre-filter index | present as `document_chunks_organizationId_projectId_knowledgeBaseId_idx` | unchanged (no duplicate created) |

The hand-written Prisma migration
`20260814000100_vector_index/migration.sql` declares **two** indexes:
`document_chunks_embedding_hnsw_idx` **and** `document_chunks_tenant_idx`.
Neither exists by name in the live database, but a name-only comparison would
be misleading: the second requirement is already satisfied by
`document_chunks_organizationId_projectId_knowledgeBaseId_idx`, which
`prisma db push` created over the identical three columns.
`V20260814000100` therefore adds only the HNSW index and asserts the
pre-filter requirement under *either* name, rather than doubling write
amplification on the hottest knowledge table.

Cosine capability was proved, not assumed:

```
$ docker exec firstelement-postgres psql -U firstelement -d firstelement \
      -c "select '[0,1,2]'::vector <=> '[3,4,5]'::vector;"
 cosine_distance_probe
-----------------------
   0.11456225515285379
```

`pgvector` is a retrieval accelerator, **never** an authorization layer. The
tenant/project pre-filter must be in the `WHERE` clause before similarity
ordering (`PROJECT_REQUIREMENTS.md FR-5.7`).

### 5.3 Referential integrity (live, before any Phase-2 write)

All zero, i.e. no violations:

`FK orphan violations` 0 · `conversations_cross_org` 0 ·
`memberships_cross_org` 0 · `customers_cross_org` 0 ·
`messages_cross_scope` 0 · `chunks_cross_scope` 0 · `ai_usage_cross_scope` 0 ·
`uuid_pk_columns` 45 · `conversations_without_ticket` 0 ·
`customers_without_cx_id` 0 · `sessions_without_token_hash` 0 ·
`deals_with_raw_customer_uuid` 0 · `tasks_with_free_text_refs` 0 ·
`flyway_history_tables` 0.

---

## 6. Baseline fidelity proof

The baseline was not accepted because it "looks right". It was accepted
because applying it to an empty database reproduces the live schema exactly.

```
$ docker exec firstelement-postgres psql -U firstelement -d postgres \
      -c "CREATE DATABASE firstelement_baseline_check TEMPLATE template0;"

$ Get-Content -Raw ops/flyway/sql/V20260814000000__baseline_verified_live_schema.sql |
    docker exec -i firstelement-postgres psql -U firstelement -d firstelement_baseline_check \
      -v ON_ERROR_STOP=1 -q
$ Get-Content -Raw ops/flyway/sql/V20260814000100__vector_indexes.sql |
    docker exec -i firstelement-postgres psql -U firstelement -d firstelement_baseline_check \
      -v ON_ERROR_STOP=1 -q

$ .\ops\flyway\verification\run-verification.ps1 \
    -Database firstelement_baseline_check -OutputDir <evidence> -ThrowOnViolation
schema fingerprint: bdf70c7c24dfe23c748281eb6c5c4a03
enum summary:      23 | 1 | 1
```

| Database | Schema fingerprint |
| --- | --- |
| live `firstelement` | `66eeaadab4ad28a8f8eaf8fe1a0748f9` |
| `firstelement_baseline_check` (baseline + additive migration) | `bdf70c7c24dfe23c748281eb6c5c4a03` |

`V1_schema_fingerprint.txt` for both, diffed:

```
 live     : 872 objects
 baseline : 873 objects
  <  index|document_chunks_embedding_hnsw_idx|CREATE INDEX ... USING hnsw ...
```

**One added object, which is exactly what the additive migration is for.** No
other column, constraint, index, enum label, or default differs. The
throwaway database was dropped afterwards
(`DROP DATABASE firstelement_baseline_check`).

Baseline artefact identity:

| File | Bytes | SHA-256 |
| --- | --- | --- |
| `ops/flyway/sql/V20260814000000__baseline_verified_live_schema.sql` | 91 188 | `f3df55c3a2abb142b3c67c480777c57e712db0a8ddad825d6a9d212a88fe0bb8` |
| `backend/database/src/main/resources/db/migration/V20260814000000__baseline_verified_live_schema.sql` | 91 188 | identical (`f3df55c3…`) |
| `ops/flyway/sql/V20260814000100__vector_indexes.sql` | 3 940 | `8ebf3949d7e1f6d94d52d48d39ec06b6cd353b80b163436d6de7d0d53a49c896` |
| `backend/database/src/main/resources/db/migration/V20260814000100__vector_indexes.sql` | 3 940 | identical (`8ebf3949…`) |

Baseline contents: 2 956 lines, 45 `CREATE TABLE`, 23 `CREATE TYPE`, 110
`CREATE INDEX`/`CREATE UNIQUE INDEX`. The two copies are asserted byte-identical
by `FlywayBaselineAcceptanceTest` so the repository copy and the packaged
classpath copy can never silently diverge.

---

## 7. Prepared, rehearsed, and deliberately NOT applied

Phase 2 applies exactly one schema change to the contract (`V20260814000100`,
the HNSW index). Everything else is prepared and proven on a disposable
database. Nothing below has been applied to `firstelement`.

| Artefact | What it would do | Status |
| --- | --- | --- |
| `ops/flyway/pending/V202610040001__outbox.sql.todo` | transactional outbox, idempotency key, lease columns | inert `.sql.todo`, design questions open |
| `ops/flyway/pending/V202610040002__export_jobs.sql.todo` | `ExportJob` / manifest / signed-download records | inert |
| `ops/flyway/pending/V202610040003__usage_ledger.sql.todo` | immutable `UsageEvent` ledger | inert |
| `ops/flyway/pending/V202610040004__finance_rate_cards_periods.sql.todo` | rate cards, usage snapshots, period locks, reconciliations | inert |
| `ops/flyway/pending/V202610040005__report_definitions.sql.todo` | versioned `ReportDefinition` objects | inert |
| `ops/flyway/pending/V202610040006__ai_policy_and_governance.sql.todo` | AI policy, model registry, budgets, approvals, redaction, provenance | inert |
| `ops/flyway/pending/V202610040007__job_attempts.sql.todo` | job attempt / dead-letter / replay records | inert |
| `ops/flyway/pending/V202610040008__audit_streams.sql.todo` | `AdminAuditEvent`, `SecurityEvent`, `JobIntegrationEvent` | inert |
| `ops/flyway/pending/V202610040009__ai_run_child_tenant_scope.sql.todo` | nullable tenant/project columns + tenant-led indexes on the 3 AI-trace children | inert; DDL rehearsed on a throwaway database |
| `ops/flyway/backfill/B1_materialize_ai_run_child_scope.sql` | resumable, armed, bounded backfill materialising inherited scope | rehearsed on a throwaway database (§7.1); **not run on `firstelement`** |
| `ops/flyway/backfill/B0_backfill_control.sql` | `backfill_runs` / `backfill_rejections` control tables (additive `IF NOT EXISTS`) | rehearsal only |
| `ops/flyway/backfill/B2_scope_audit_report.sql` | read-only classification of `NULL projectId` rows | read-only; safe on `firstelement` |

`.sql.todo` is not matched by Flyway's `V*.sql` location pattern, so these files
cannot be executed accidentally, by `flywayMigrate`, or by the Testcontainers
fixture. Promotion to `sql/V<timestamp>__<name>.sql` requires the checklist at
the bottom of each file.

### 7.1 B1 backfill rehearsal evidence (throwaway database only)

Target `firstelement_restore_check` (restored from the backup), expand DDL from
`ops/backup-restore/rehearsal/expand_ai_run_child_scope.sql`, then
`run-backfill.ps1 -BatchSize 2 -MaxBatches 10` so more than one batch is
required:

```
    batch 1/10   B1_REMAINING | 3 | 5
    batch 2/10   B1_REMAINING | 0 | 8
    checkpoint reports 0 remaining: complete.
    pre  fingerprint: aa6068f0a0f33299b59e7b7cd3c8c7b7
    post fingerprint: aa6068f0a0f33299b59e7b7cd3c8c7b7
    NOTE: schema fingerprint unchanged (expected: a backfill changes DATA, not the schema).
```

Final acceptance queries (`ops/backup-restore/rehearsal/b1_evidence.sql`):

```
           marker           |   value
----------------------------+-----------
 B1_CROSS_TENANT_STEPS      | 0
 B1_CROSS_TENANT_RETRIEVALS | 0
 B1_CROSS_TENANT_TOOLCALLS  | 0
 B1_REMAINING_TOTAL         | 0
 B1_FILLED_TOTAL            | 8
 B1_REJECTIONS              | 0
 B1_RUN_STATUS              | COMPLETED
 B1_ROWS_PROCESSED          | 8
 B1_BATCHES_RUN             | 2
```

Proved: it resumed across batches; `B1_CROSS_TENANT_* = 0` means a child row
never disagrees with its parent `ai_runs` row; the unarmed invocation printed
`B1 SKIPPED: this backfill must be armed explicitly.` and changed nothing; and
a rerun after completion was a **total** no-op (`UPDATE 0` on the checkpoint,
`batchesRun` stayed at 2). The throwaway database was dropped afterwards.

---

## 8. Remaining drift and debt carried forward (not fixed in Phase 2)

1. **`packages/database/prisma/migrations/20260814000000_init/migration.sql`
   stays wrong.** It is quarantined, not corrected. Fixing it means either
   rewriting history (forbidden while the legacy runtime may still be used for
   `prisma migrate`) or adding a compensating Prisma migration. Ownership for
   that is the orchestrator's call; the correct interim state is "do not use
   it".
2. **`db push` remains the only way the schema was ever built.** There is no
   Prisma history to reconcile, so `prisma migrate deploy` must never be run
   against `firstelement` after the Flyway baseline is stamped — the two tools
   would fight over the same schema.
3. **`deals.customer` is a bare `uuid` with no FK**, and
   `tasks.customer/lead/deal/assignedTo` are free text. Recorded by `B2` as
   unresolved identifier debt; no DDL in this phase.
4. **Zero `CHECK` constraints.** Enum validity and non-negative numerics are
   application-enforced only.
5. **Three AI-trace tables have no tenant column.** Fix is prepared, not
   applied (§7).
6. **Tables without a `createdAt` column**: `agent_status_sessions`,
   `ai_runs`, `conversation_classifications`, `conversation_events`. Accepted
   for append-only traces; if a target entity needs optimistic concurrency it
   must add its own `version` column rather than assume one exists.
7. **No PostgreSQL row-level security.** `FR-1.6` makes RLS optional
   defence-in-depth; application policy remains mandatory and is the Phase-3+
   security work.
8. **Backups are not encrypted by `ops/backup-restore/backup.ps1`.** The script
   refuses to hold a key. Encryption must come from platform storage encryption
   or an externally supplied `age`/`gpg` recipient — recorded as an open
   operational decision in `docs/migration/flyway-runbook.md`.

---

## 9. Evidence index

| Artefact | Location |
| --- | --- |
| Drift analyser (read-only) | `backend/database/scripts/schema_drift_report.py` |
| Baseline generator (read-only `pg_dump`) | `ops/flyway/generate/generate-baseline.ps1` |
| Verification queries V1–V9 | `ops/flyway/verification/V*_*.sql` |
| Verification runner | `ops/flyway/verification/run-verification.ps1` |
| Baseline + additive migration | `ops/flyway/sql/` and `backend/database/src/main/resources/db/migration/` |
| Backfill control / B1 / B2 | `ops/flyway/backfill/` |
| Backup + restore rehearsal | `ops/backup-restore/backup.ps1`, `ops/backup-restore/restore-rehearsal.ps1` |
| Rehearsal-only DDL + evidence queries | `ops/backup-restore/rehearsal/` |
| Testcontainers baseline fixture | `backend/database/src/test/java/com/firstelement/db/FlywayBaselineAcceptanceTest.java` |
| Live schema fingerprint | `66eeaadab4ad28a8f8eaf8fe1a0748f9` |
| Baseline-applied fingerprint | `bdf70c7c24dfe23c748281eb6c5c4a03` (differs by the HNSW index only) |
| Row-count checksum (147 rows) | `5498afaedad29db7d63a49fb060f1726` |
| Backup dump SHA-256 | `eddead1b6a0b082e15b3f5d42263c72b69d886bc90aa6e76f4e218ee2eae153e` |
| Commands, runbook, recovery | `docs/migration/flyway-runbook.md` |
| Baseline decision | `docs/migration/adr/0002-flyway-baseline-quoted-columns.md` (Phase-2 outcome) |