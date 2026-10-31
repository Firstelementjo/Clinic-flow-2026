# ADR 0009 — Phase 7-10a rulings: index locking, AI-run scope, legacy cost, retention

Date: 2026-10-05
Status: **Accepted** (orchestrator rulings on the Phase 7-10a escalations)

Four questions were escalated. Each is answered here, with the reasoning that
makes the answer checkable later.

---

## 1. `spring.flyway.postgresql.transactional-lock=false` — REJECTED

The Phase 7-10a agent found that ADR-0005 §3's `CREATE INDEX CONCURRENTLY` is
unreachable in Flyway Community: no per-script `executeInTransaction` override,
and splitting the migration hangs because Flyway holds its schema-history
advisory lock inside an open transaction on another connection. It asked whether
to set the global `transactional-lock=false` property, which does make
CONCURRENTLY viable.

**Rejected.** That property is global, not per-migration. Setting it would run
**all seventeen** migrations without the schema-history lock, so two instances
starting at once could both believe they own the schema. Trading a lock that
protects every migration for a lock-free index build on three tables that are
**empty at cutover** is a bad trade.

Plain `CREATE INDEX` stands, with the operational condition ADR-0005 §3 already
requires: if the three `ai_run_*` tables ever hold production volume, the index
build moves to a maintenance window (or to `pg_reindex` afterwards). The three
tables are empty because §2 adds columns to them, so the first boot pays
nothing.

## 2. `ai_run_*` tenant scope: backfill, then `NOT NULL`, never in the same migration

`V20261005110000` adds `organizationId` / `projectId` to `ai_run_steps`,
`ai_run_retrievals`, and `ai_run_tool_calls` as **nullable**, and leaves them
that way.

**Rules:**

1. **Never add `NOT NULL` to `V20261005110000`.** It is an additive column
   migration; a `NOT NULL` there would fail on any database holding AI-trace
   rows, which is exactly the production case it exists to serve.
2. Phase 10 (or Phase 8, whichever writes AI runs first) **backfills** the three
   tables from the parent `ai_runs` row, using the same armed, batched,
   checkpointed pattern as the Phase-2 `B1` backfill.
3. A **separate** later migration adds `NOT NULL` once the backfill is verified
   with 0 remaining NULLs on a disposable copy. It is additive in effect and
   must ship with a rehearsed rollback.
4. **Until the backfill is complete, tenant filters on those three tables must
   join to the parent `ai_runs` row.** Reading them with a bare
   `organizationId = ?` predicate silently returns nothing, which reads as "no
   access" rather than "not yet migrated" — the dangerous failure mode, because
   it looks like a security control working.

## 3. Legacy `ai_usage.estimatedCost` is not finance truth

The legacy table carries an `estimatedCost` column. The migration inventory
suggested seeding the new `UsageEvent` ledger from it.

**Decision: do not migrate it as an amount.** ADR-0007 Part 1 says cost is
recorded per usage event and revenue is *derived* from a rate-card version.
Copying a mutable legacy estimate into the immutable ledger would import an
untraceable number as if it were a source event, and every downstream total
would inherit it.

Instead:

- `ai_usage` stays readable as a **legacy comparison source** only, so Phase 8's
  legacy-versus-new analytics comparison has something to compare against.
- The ledger computes cost from the rate card effective on the usage date.
- Where the two disagree, the difference is reported as a variance in the
  comparison view, not reconciled silently.

## 4. Retention for the Phase 7-10a tables

ADR-0008 covered outbox/job/idempotency. These 37 tables are the customer-data
tables, so the windows differ by what the row *is*.

| Class | Tables | Window |
| --- | --- | --- |
| **Never purge** | `finance_periods`, rate-card versions, period locks, reconciliation runs, adjustments, usage rollup snapshots | finance and audit retention is governed by its own policy; operational cleanup must never substitute for it (PROJECT_REQUIREMENTS §7) |
| **Legal hold beats everything** | every table in this ADR | a purge job refuses to run for a tenant under legal hold |
| Audit / security events | `admin_audit_events`, `security_events`, `job_integration_events` | retained per the tenant's retention class; the class is stored on the row, not assumed |
| Export artefacts and their events | `export_jobs`, `export_download_events`, `export_deletion_events`, document templates | **30 days** default, configurable per tenant; templates are configuration and never purged |
| AI provenance | ai run/step/retrieval/tool/usage records | aligned to the audit window — provenance is evidence, not cache |
| Legal hold / retention / deletion requests | `legal_hold_events`, `retention_classes`, `privacy_deletion_targets` and their events | **never purged** while the request is open; 7 years after closure |
| Report definitions and versions | `report_definitions`, versions | never purged; a version is referenced by historical runs |

**Purge rules** (extending ADR-0008 §2 unchanged in spirit):

1. Purging an export artefact **must** delete the object in storage, not just the
   row. A purge that leaves the file behind is worse than no purge, because the
   row no longer records that it exists.
2. Purging is tenant-scoped, batched, checkpointed, idempotent, and audited.
3. It ships **disabled** until Phase 7 exposes legal-hold state.
4. No table in this ADR is purged by a scheduled job until the retention-class
   column is populated for it.

**Status:** these are the accepted defaults. Retention windows are configuration;
this ADR is amended if operations decides otherwise.

## 5. The process lesson, recorded because it generalises

The Phase 7-10a undo script printed `REFUSED` **and dropped 37 tables anyway**,
because every guard was a `RAISE EXCEPTION` inside a `DO` block and psql
continues to the next statement without `ON_ERROR_STOP`. The rehearsal passed,
because it asserted on the *message*.

Rules adopted for every future rehearsal in this migration:

1. **Assert on state after the action, never on the message it printed.**
   "REFUSED" is a claim; the table count afterwards is evidence.
2. Every destructive script sets `ON_ERROR_STOP` **and** gates the destructive
   section behind a value the guard itself set (`\gset`), so a failed guard
   cannot be followed by a drop.
3. A rehearsal that passes for the wrong reason is a failed rehearsal — the same
   rule that applies to a skipped test.