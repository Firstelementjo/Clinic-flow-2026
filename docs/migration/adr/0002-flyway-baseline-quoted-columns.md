# ADR-0002 — Flyway Baseline vs Prisma Quoted-Column Compatibility

Status: PROPOSED

## Context

`packages/database/prisma/schema.prisma` and
`migrations/20260814000000_init/migration.sql` disagree:
`EmployeeAvailabilityStatus` is `ONLINE, OFFLINE, BUSY, BREAK, MEAL,
TRAINING, MEETING` in the schema but `AVAILABLE, BUSY, AWAY, OFFLINE` in
the init migration, and `AIRunDecision` lacks `RESOLVE` in SQL. Prisma
columns are camelCase and double-quoted in PostgreSQL. The raw chat of the
codebase (seed, integration tests, Prisma client) depends on that shape.

## Decision

Flyway must be baselined against the **verified real database**, not the
assumed Prisma-generated schema. Preserve legacy quoted camelCase columns
and table names during cutover; Spring persistence maps explicitly to
that physical schema. Do not run a broad rename to snake_case in the same
phase. Resolve the enum drift (real DB → Prisma schema as source of
truth, migration history annotated) before the Flyway baseline checksum.

## Consequences

- Agent B must produce an actual-schema snapshot, row counts, and a
  drift report before `baseline` is stamped.
- Spring entity mapping uses explicit quoted identifiers; no automatic
  naming strategy.
- A later, separately approved project may rename columns via dual-read;
  it is out of scope for the first cutover.
- `db push` remains forbidden outside disposable dev databases.

---

## Phase-2 outcome (2026-10-04, appended — not a status change above)

**Status after this phase: ACCEPTED.** The prose above is left as written; this
section records what was actually decided, measured, and built.

### Confirmed: baseline from the verified real database, and the init migration is quarantined

The Decision section above is implemented exactly as written, and the drift is
worse than Phase 0 recorded. The live database has **no** `_prisma_migrations`
history (it was built with `prisma db push`), so there is no Prisma history to
baseline *from*. The only defensible sources were the live database and the
`init` migration, and they disagree.

`python backend/database/scripts/schema_drift_report.py` (read-only) on
`firstelement` @ `pgvector/pgvector:pg16`, PostgreSQL 16.15:

| Source | Tables | Enums | Verdict |
| --- | --- | --- | --- |
| live database | **45** | **23** | **verified runtime truth** |
| `schema.prisma` | 45 | 23 | matches live exactly — 0 column, nullability and type drift |
| `init` migration | 31 | 22 | **quarantined** |

The `init` migration is unusable as a baseline source for three independent
reasons:

1. **14 tables absent**: `Lead`, `accounts`, `agent_status_sessions`,
   `appointments`, `case_classification_questions`, `case_resolutions`,
   `case_solution_mappings`, `conversation_classifications`, `deals`,
   `doctors`, `notes`, `procedures`, `tasks`, `views` — the entire V1.2 CRM
   middle workspace, the clinic module, and the `case_*` lifecycle.
2. **1 enum absent**: `CaseOutcome`.
3. **2 enum value lists wrong** (not 1): `EmployeeAvailabilityStatus`
   (`AVAILABLE, BUSY, AWAY, OFFLINE` instead of the live
   `ONLINE, OFFLINE, BUSY, BREAK, MEAL, TRAINING, MEETING`) **and**
   `AIRunDecision` (omits `RESOLVE`).
4. **3 tables missing columns**: `conversations` lacks `lastReplierUserId` and
   `ticketNumber`; `customers` lacks `cxId`; `employees` lacks
   `statusChangedAt`. `ticketNumber` and `cxId` are **business keys**.

Had the baseline been taken from the `init` migration, a fresh database would
have been built from a schema the legacy NestJS/Prisma runtime has never run
against, and the enum values it wrote would have been rejected.

### Enum reconciliation, decided before any checksum was stamped

Enum value lists are application contract: the legacy code *writes* these
labels. Therefore the live values win and the stale SQL is quarantined — the
opposite of "make the database match the old migration".

| Enum | Quarantined `init` | **Adopted contract (live = `schema.prisma`)** |
| --- | --- | --- |
| `EmployeeAvailabilityStatus` | `AVAILABLE, BUSY, AWAY, OFFLINE` | `ONLINE, OFFLINE, BUSY, BREAK, MEAL, TRAINING, MEETING` |
| `AIRunDecision` | `REPLY, ASK_CLARIFICATION, ESCALATE, NO_ACTION` | `REPLY, ASK_CLARIFICATION, ESCALATE, NO_ACTION, RESOLVE` |
| `CaseOutcome` | *type absent* | `RESOLVED, ESCALATED, UNRESOLVED` |

`AVAILABLE` and `AWAY` appear nowhere in the runtime. `BREAK`, `MEAL`,
`TRAINING`, `MEETING` are written by the employee-availability endpoints;
`RESOLVE` is written by the AI decision path. All 23 enums now agree across
live database, baseline, and `schema.prisma`, and the absence of `AVAILABLE` /
`AWAY` is asserted by the Testcontainers fixture so the drift cannot return.

### What was actually built

- `V20260814000000__baseline_verified_live_schema.sql` — a schema-only
  `pg_dump` of the live database (2 956 lines, 45 `CREATE TABLE`, 23
  `CREATE TYPE`, 110 `CREATE INDEX`), regenerable read-only with
  `ops/flyway/generate/generate-baseline.ps1`. Quoted camelCase identifiers
  are preserved verbatim, so the legacy Prisma client keeps working.
- `V20260814000100__vector_indexes.sql` — the only migration actually
  applied to the contract. It adds `document_chunks_embedding_hnsw_idx`
  (`USING hnsw ("embedding" vector_cosine_ops)`), which the live database never
  had, because `prisma db push` cannot express pgvector's HNSW access method.
  It deliberately does **not** create `document_chunks_tenant_idx`: the same
  three columns are already indexed as
  `document_chunks_organizationId_projectId_knowledgeBaseId_idx`, so a second
  index would double write amplification for zero query benefit.
- `FlywayDatabaseConfiguration` — pins `baselineVersion` to `20260814000000`
  and chooses per starting state: fresh database executes the whole chain;
  legacy schema with no `flyway_schema_history` gets the baseline **stamped**
  (DDL not replayed); an already-managed schema just migrates. Detection is
  read-only and fails closed if the schema cannot be read.
- `FlywayBaselineAcceptanceTest` — Testcontainers `pgvector/pgvector:pg16`
  fixture proving all three cases, including that a legacy database with live
  rows keeps its rows and its UUIDs and executes exactly **one** migration.

### Fidelity proof

Applying the baseline + additive migration to an empty throwaway database and
comparing full schema fingerprints against the live database produced a
**one-line** difference: the HNSW index the additive migration is supposed to
add. Nothing else — no column, constraint, index, enum label, or default.

```
live      fingerprint 66eeaadab4ad28a8f8eaf8fe1a0748f9   (872 objects)
baseline  fingerprint bdf70c7c24dfe23c748281eb6c5c4a03   (873 objects)
  <  index|document_chunks_embedding_hnsw_idx|CREATE INDEX ... USING hnsw ...
```

Restore rehearsal from the Phase-2 backup reproduced the live fingerprint
exactly (`fingerprintMatches True`, `rowCountsIdentical True`,
`identitySample 41/1/4/3/43`).

### Consequences accepted

- `packages/database/prisma/migrations/20260814000000_init/migration.sql`
  remains **wrong on disk** and is quarantined, not rewritten. Correcting
  Prisma history is a separate, orchestrator-owned decision.
- `prisma migrate deploy` must never be run against `firstelement` after the
  baseline is stamped: `db push` built the schema and Flyway now owns it, so
  the two tools would fight. Spring Boot owns the schema from here.
- Spring persistence maps **explicitly** to quoted camelCase identifiers with
  no naming strategy, and binds `LocalDateTime` (never `Instant`) for the 98
  `timestamp without time zone` columns. Both constraints are written into the
  baseline header so they cannot be lost.
- `V202610040001` … `V202610040009` exist as inert `ops/flyway/pending/*.sql.todo`
  drafts with promotion checklists. Nothing was applied beyond
  `V20260814000100`. See `docs/migration/schema-drift-report.md` §7 and
  `docs/migration/flyway-runbook.md`.
