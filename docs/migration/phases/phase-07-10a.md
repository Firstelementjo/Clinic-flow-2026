# Phase 7–10a — audit, reports, export, usage, finance and AI-policy schema (Agent B)

## Objective

Give Phases 7–10 real tables to build against. The seven remaining
`ops/flyway/pending/*.sql.todo` drafts are promoted to **eight** real
14-digit Flyway migrations, byte-for-byte mirrored into the packaged chain,
verified against a Testcontainers PostgreSQL/pgvector fixture, and backed by a
rehearsed undo and a rehearsed backup/restore.

**Nothing was applied to `firstelement`.** It still has 45 application tables,
0 `flyway_schema_history` rows and fingerprint
`66eeaadab4ad28a8f8eaf8fe1a0748f9`. The first Flyway-enabled boot remains the
orchestrator's scheduled, backed-up act.

No `db push`. No `prisma migrate`. No destructive DDL. No `DROP` or `RENAME`
of an existing object. No `.env` was read. Every statement of fact below came
from `docker exec` on the container socket or from a throwaway database.

## Scope (owned by Agent B)

`ops/flyway/**`, `backend/database/**`, `docs/migration/phases/phase-07-10a.md`,
`docs/migration/flyway-runbook.md`. Not touched: every other `backend/*` module,
`apps/**`, `packages/database/**`, and the Phase-0..6a reports.

**No already-recorded migration was amended.** The nine Phase-2/5a/6a SHA-256
values are unchanged, so no recorded Flyway checksum can have drifted.

## Migration inventory

The chain after this phase, in the order Flyway applies it:

| Version | File | Applied to `firstelement`? | What it does |
| --- | --- | --- | --- |
| `20260814000000` | `V20260814000000__baseline_verified_live_schema.sql` | **stamped, not executed** | 45 tables, 23 enums, `pgcrypto` + `pgvector` |
| `20260814000100` | `V20260814000100__vector_indexes.sql` | pending | HNSW cosine index |
| `20261004000100` | `V20261004000100__outbox.sql` | pending | Phase 5a outbox |
| `20261004000700` | `V20261004000700__job_attempts.sql` | pending | Phase 5a job attempts |
| `20261004001000` | `V20261004001000__idempotency_records.sql` | pending | Phase 5a idempotency |
| `20261005010000` | `V20261005010000__workflow_definitions_and_versions.sql` | pending | Phase 6a workflow |
| `20261005020000` | `V20261005020000__workflow_runs_and_action_results.sql` | pending | Phase 6a workflow runs |
| `20261005030000` | `V20261005030000__sla_calendars.sql` | pending | Phase 6a SLA |
| `20261005040000` | `V20261005040000__collision_records.sql` | pending | Phase 6a collisions |
| `20261005050000` | `V20261005050000__audit_streams.sql` | pending | 6 enums, 8 tables, 3 composite tenant keys on existing tables |
| `20261005060000` | `V20261005060000__report_definitions.sql` | pending | 2 enums, 4 tables (`report_definitions`, `report_versions`, `document_templates`, `document_template_versions`), 4 validator functions |
| `20261005070000` | `V20261005070000__export_jobs.sql` | pending | 4 enums, 4 tables, 7-state machine |
| `20261005080000` | `V20261005080000__usage_ledger.sql` | pending | 4 enums, `usage_events` — cost only |
| `20261005090000` | `V20261005090000__finance_rate_cards_periods.sql` | pending | 5 enums, 9 tables, derivation CHECK, 3 functions, 8 triggers |
| `20261005100000` | `V20261005100000__ai_policy_and_governance.sql` | pending | 4 enums, 11 tables, deny-by-default |
| `20261005110000` | `V20261005110000__ai_run_child_tenant_scope_columns.sql` | pending | **EXPAND**: 3 nullable tenant columns |
| `20261005120000` | `V20261005120000__ai_run_child_tenant_scope_indexes.sql` | pending | 3 plain `CREATE INDEX` (replaces `CONCURRENTLY` — see below) |

| File | SHA-256 (both copies byte-identical) |
| --- | --- |
| `V20260814000000__baseline_verified_live_schema.sql` | `f3df55c3a2abb142b3c67c480777c57e712db0a8ddad825d6a9d212a88fe0bb8` (unchanged) |
| `V20260814000100__vector_indexes.sql` | `3a0fecbbb596d4d2c1cd9d2dd247f816c6ee93cfe0911f92713119887a927760` (unchanged) |
| `V20261004000100__outbox.sql` | `4cc833a03581a1152c988ef7ec67e71f3bf220a3cd3ae16afa6e2d517b18a3ad` (unchanged) |
| `V20261004000700__job_attempts.sql` | `84db18741188ccb0b1a33443a29c1bdc3e3109b8ef201336df483e1ebe870c9e` (unchanged) |
| `V20261004001000__idempotency_records.sql` | `fbf901c8a32f46b044c67632323ddad3dac4e5199ab6f61d067fe8244d078f45` (unchanged) |
| `V20261005010000__workflow_definitions_and_versions.sql` | `01f4fb6cb317793fa5003f99472bec0f9fb2be4fa863e09727008fda33f16e19` (unchanged) |
| `V20261005020000__workflow_runs_and_action_results.sql` | `2c431b4e74ec4d4a64241420f57b4af19fbca2b7a7eb59b71eedd54836cb7cc6` (unchanged) |
| `V20261005030000__sla_calendars.sql` | `3b0cbd66adfe8598be798ea950560fe97527bcbd85a5f19a9de2ee95db842c1d` (unchanged) |
| `V20261005040000__collision_records.sql` | `e6e0a59ead600142cb62e002f18ff4e599f271a268b82ecabf90d268ad988446` (unchanged) |
| `V20261005050000__audit_streams.sql` | `1ddda9bba6959bc29a96c43a0492f5ef7d2b924cd8ba0bd3958c12c9d735cf69` |
| `V20261005060000__report_definitions.sql` | `b3d4fbc7232f1bbcf6bb2c2276e0336606518df950dce52ad3e1936c80761407` |
| `V20261005070000__export_jobs.sql` | `c9c942b2f86ee88babf17377a3c394bd1c8d8e9445de3759bfc63eff0e941e8d` |
| `V20261005080000__usage_ledger.sql` | `bb54d9844a6ffce83340f97064840b515e7a2e17165ac93a719e58355dbece0b` |
| `V20261005090000__finance_rate_cards_periods.sql` | `508e30a28f99de3fa133ce3e59a11ce62fe88b17845622a43fa3edc985577628` |
| `V20261005100000__ai_policy_and_governance.sql` | `9c8e5c11569a76fe671d4a4fb506bc8e16e082b0498fa41f2f2987e3296a0b69` |

> The digests for `…080000`, `…090000` and `…100000` are **not** the ones first
> written in this phase. The fixture forced four schema corrections *after* those
> files were authored, and those three were amended to carry them. That is safe
> **only because none of the eight has ever been applied anywhere** — the nine
> Phase-2/5a/6a values above are unchanged, which is the property that actually
> matters. A migration in this chain that has been applied must never be
> amended; the fix goes in a new version.
| `V20261005110000__ai_run_child_tenant_scope_columns.sql` | `62746b92d250c809144cc03cef57604ea119f69f87750e18b3fa96e4b2a19fe4` |
| `V20261005120000__ai_run_child_tenant_scope_indexes.sql` | `d6beb6a4a8f13957530663c8d207644dd435dac30595b3dac5f3fa8c614f8fdb` |

**Deleted: all seven `*.sql.todo`.** `ops/flyway/pending/` is now empty, so no
draft can be mistaken for something that will ever run. Seven drafts became
eight files because the AI-run child scope had to split (below).

New supporting assets:

| Asset | Purpose |
| --- | --- |
| `ops/flyway/verification/V12_admin_export_report_slice.sql` | read-only, guarded: four separate audit tables, legal hold before purge, the report catalogue, the export state machine |
| `ops/flyway/verification/V13_usage_finance_slice.sql` | read-only, guarded: cost-only ledger, derived revenue, append-only triggers, locked-period refusal — all **with data** |
| `ops/flyway/verification/V14_ai_policy_slice.sql` | read-only, guarded: deny-by-default, the approval biconditional, no-secrets/no-CoT, the three child tables' tenant scope |
| `ops/flyway/rollback/undo_phase7_10a.sql` + `rollback/README-phase7-10a.md` | armed undo and the Phase-7–10a rollback/recovery runbook |
| `ops/flyway/rollback/rehearsal/undo_phase7_10a_seed.sql` | 12 synthetic rows, idempotent, used to prove the undo refuses a non-empty slice |
| `ops/flyway/verification/rehearsal-chain.ps1` | applies the chain one file per `psql` session on a throwaway; **refuses** `-Database firstelement` |

`verification/V*.sql`, `rollback/*.sql` and `rollback/rehearsal/*.sql` are not
matched by Flyway's `V*.sql` location pattern (they are not under
`ops/flyway/sql/` and not under `db/migration/`), so none can be executed as a
migration by accident.

## The shape that was delivered

**37 new tables**, 21 new enums. Post-boot census, measured on a throwaway
(`firstelement_p710a_check2`, dropped afterwards):

| Measure | Value |
| --- | --- |
| tables | **95** |
| indexes | **379** (195 unique) |
| foreign keys | **211** |
| primary keys | **95** |
| CHECK constraints | **402** |
| `unique_constraints` | **0** (unchanged) |
| enums | **55** |
| triggers | **8** (6 blanket append-only, 1 narrow rate-card guard, 1 locked-period guard) |
| index census | **200** tenant-led / 15 project-led / 22 parent-key / 136 unique-other / 5 global / 1 vector-led |
| nullable `projectId` columns | **35** (enumerated in `ops/flyway/expected-state.json`) |
| tables with no `organizationId` | **3** — `organizations`, `sla_calendar_windows`, `sla_calendar_holidays` |
| fingerprint | `2ee0ecd601f00938709f538714509b86` |

## Four schema defects the fixture found, and what changed

These are the substance of the phase. Each one shipped green in the migration and
was caught only by a negative test running against a real database. All four are
recorded in the migration headers, so nobody "simplifies" them back.

| Defect | Symptom | Fix |
| --- | --- | --- |
| `ai_tool_registry_risk_field_list_check` was **satisfied by every empty allow-list** | `array_length('{}'::text[], 1)` returns `NULL`, not `0`. `NULL >= 1` is `NULL`, and a `CHECK` passes when it evaluates to `NULL` rather than `false`. So "a HIGH tool may not be enabled with no field allow-list" was not enforced at all | `coalesce(array_length("fieldAllowList", 1), 0) >= 1`, with a comment saying the `coalesce` is load-bearing |
| `rate_card_versions` had the **blanket** append-only trigger | It refuses every UPDATE, so `RETIRED` was unreachable and `rate_card_versions_retired_check` was **dead code no application could ever satisfy**. The table's own columns (`retiredAt`, `retiredByUserId`, `retireReason`, `supersededByVersionId`) prove a retirement is an UPDATE by construction | new `public.firstelement_rate_card_version_guard()`: exactly one UPDATE (`ACTIVE → RETIRED`, all four fields set, no pricing column moved, error naming the offending column) and `DELETE` refused. The migration's post-condition now asserts the *narrow* guard is attached, so the blanket one cannot come back |
| `finance_periods_locked_check` was a **biconditional that made the reopen inexpressible** | A `REOPENED` period keeps `lockedAt` (this migration's own comment says so, and `finance_periods_reopen_requires_lock_check` requires it). The biconditional forbids exactly that, so ADR-0007 rule 4's reopen could never be written | one-way: `LOCKED` demands both audit columns, and the audit columns may **outlive** the lock. Clearing `lockedAt` on reopen would make "was this month ever closed?" unanswerable from the row. The negative that motivated the biconditional — `LOCKED` with no `lockedByUserId` — is still refused |
| `usage_events` was append-only **by shape only** | `update usage_events set "costAmount" = 999` **succeeded**. "No `updatedAt`" is a shape, not a boundary | `V20261005090000` attaches `firstelement_append_only()` to `usage_events`. It is placed there and not in `…080000` because the function does not exist until that migration, and amending `…080000` would move a checksum the chain has already recorded |

`…080000`'s header previously listed the missing trigger as deferred debt. That
paragraph has been rewritten to point at the fix rather than leave a stale open
item, and to say explicitly that the privilege-level `REVOKE` is still Phase 8.

### What was chosen, and why

| Question | Choice | Reasoning |
| --- | --- | --- |
| One partitioned `audit_events` with a `stream` column, or four tables? | **Four tables** (`admin_audit_events`, `security_events`, `job_integration_events`, plus legal-hold/retention/privacy) | A discriminator is not a boundary. One forgotten `where stream = 'SECURITY'` hands a finance manager every login and every denial — and with separate tables that report *cannot be written*, because the table it would need does not exist. Retention, redaction and `GRANT`s differ per stream, and a grant is issued on a relation. Partitioning was the draft's other motive and is deliberately **not** used: attaching a partition is not additively reversible and ADR-0008 says the retention design is not settled. Audit volume is orders of magnitude below `job_attempts`. |
| Does `usage_events` store revenue? | **No.** No revenue, billable, margin, price, markup, or `rateCardVersionId` column — and a guard `DO` block **fails the migration** if any such column ever appears | Revenue is derived once, on `usage_rollup_snapshots`, against a pinned `markupRate`. A ledger that also stores the answer cannot be reconciled against it; two sources of truth for money is the failure, not the feature. `UsageDimension` consequently has **no `UNPRICED` member** — unpricedness is a property of the rollup, not a kind of usage. |
| Is the derivation checkable in the database? | Yes: `CHECK (revenueAmount = round(costAmount * markupRate, 6))` and `usage_rollup_snapshots` stores **both** `rateCardVersionId` and the pinned `markupRate` | So "changing a rate card never mutates a closed snapshot" is a property of the row, not a promise, and a hand-edited snapshot is refused. |
| Append-only by convention or by trigger? | **Trigger**: `public.firstelement_append_only()` on six tables, `public.firstelement_rate_card_version_guard()` on `rate_card_versions`, and a separate `BEFORE INSERT` trigger refusing a write into a locked period | The tables that carry money are a boundary. `rate_card_versions` needs the narrower guard because it has one legitimate UPDATE (see the defects table). `legal_hold_events`, `export_*_events` and `privacy_deletion_targets` are append-only by **shape** (no `updatedAt`) only — see Known risks. |
| Plain FK or composite tenant FK? | Composite wherever a plain FK would permit a cross-tenant reference, as Phase 6a did | A single-column FK on `correctionOfId` would let tenant B write a correction against tenant A's cost. That is not a privacy leak; it is a **forged financial fact**. 211 FKs, of which 6 target `…_id_organizationId_key` unique indexes added for the purpose. |
| Unique constraints vs unique indexes? | **Indexes**, always `IF NOT EXISTS` | Keeps `unique_constraints = 0` — an invariant `V4` has asserted since Phase 2 — and PostgreSQL accepts a non-partial unique index as a foreign-key target, which was verified on this host before being relied on. |
| How is a timezone validated? | A `STABLE` **function**, `public.firstelement_valid_timezone(text)` | PostgreSQL forbids subqueries in `CHECK`, and `'Asia/Riyaddah'` is a perfectly shaped zone name that does not exist. A regex accepts it and every bucket in the report is silently wrong until a monthly reconciliation. Three more validators exist for the same reason: `firstelement_array_has_blank`, `firstelement_json_list_is_string_array`, `firstelement_json_list_is_sort`, plus `firstelement_currency_code`. |
| Why is the AI-run scope two migrations? | `…5110000` adds the columns transactionally; `…5120000` adds the indexes | A script mixing transactional and non-transactional statements is rejected outright. See the CONCURRENTLY finding. |
| Why are the three new columns NULLABLE? | Expand/contract step 1 | `NOT NULL` would be refused against existing rows and belongs in a later migration, after the residual `NULL` count is proven zero. |

## The `CREATE INDEX CONCURRENTLY` finding

ADR-0005 §3 asked for `CREATE INDEX CONCURRENTLY` on the three `ai_run_*` child
tables. **It was tried three ways, all three are recorded in the migration
header, and all three failed.** This is the headline of the phase.

| Attempt | Result |
| --- | --- |
| One migration with both the `ADD COLUMN` and the `CONCURRENTLY` statements, plus a `V20261005110000….conf` sidecar saying `executeInTransaction=false` | `FlywayException: Detected both transactional and non-transactional statements within the same migration (even though mixed is false)` |
| The same, with `executeInTransaction=mixed` | Identical failure |
| **Split** into two versions | Flyway **accepted** it, logged `[non-transactional]` — and then **hung indefinitely** |

Two root causes, both verified:

1. **`flyway-core` 11.7.2 Community has no per-script `executeInTransaction`
   override.** The `.conf` sidecar is a Teams/Enterprise feature;
   `SqlMigrationResolver` never reads it, and the resolved jar contains no
   script-configuration class at all. The parser therefore sees the global
   default and refuses a mixed script.
2. **The split version hangs** because Flyway's PostgreSQL adapter takes its
   schema-history advisory lock on a **separate connection**
   (`PostgreSQLAdvisoryLockTemplate.execute` → `PostgreSQLConnection.lock`), and
   with the default `flyway.postgresql.transactionalLock=true` that lock is held
   **inside an open transaction** on the lock connection. `CREATE INDEX
   CONCURRENTLY` waits for every transaction older than its snapshot, so it waits
   forever for Flyway's own lock transaction. Confirmed with `jcmd Thread.print`
   on the test worker, parked in `PgStatement.execute`. This is a
   Flyway/PostgreSQL interaction, not a schema problem, and no script content
   works around it.

**Shipped:** plain `CREATE INDEX`, which is ADR-0005 §3's own listed
alternative ("drop `CONCURRENTLY` and accept a SHARE lock during a maintenance
window"). The decision is recorded in **four** places — the migration header,
runbook §9c, this report, and the fixture's assertions — rather than dropped
silently.

`FlywayBaselineAcceptanceTest` asserts the header names all three attempts and
both real fixes, asserts the **executed body** contains no `CONCURRENTLY`, and
asserts **no `.conf` file exists anywhere** in either chain directory. So the
decision cannot be reversed silently, and a file that looks load-bearing but is
not (the `.conf`) cannot reappear and mislead the next reader.

## Verification

### Fixture

`backend/database/src/test/java/com/firstelement/db/FlywayBaselineAcceptanceTest.java`,
extended with 17 scenarios on a **Testcontainers `pgvector/pgvector:pg16`**
instance. No dev database is touched; there is no conditional skip and no
`@Disabled`.

The legacy-database scenario is now **16 additive migrations** (baseline
stamped, 16 applied), 37 new tables, all new tables empty, and `audit_logs`
empty and untouched — the pre-existing table the new audit streams must not
disturb.

### Commands

```powershell
.\gradlew.bat :backend:database:test --rerun-tasks --no-build-cache --no-daemon --console=plain
```

Result: **17 passed, 0 failed.**

```powershell
# chain rehearsal on a throwaway database (one psql session per file)
.\ops\flyway\verification\rehearsal-chain.ps1 `
  -Database firstelement_p710a_check2 `
  -ExpectedEnumCount 55 -ExpectedTableCount 95 -ExpectedCheckCount 402 -ThrowOnViolation

# verification bundle against the migrated chain
.\ops\flyway\verification\run-verification.ps1 `
  -Database firstelement_p710a_check2 `
  -OutputDir C:\...\evidence\7-10a `
  -ExpectedEnumCount 55 -ExpectedTableCount 95 -ExpectedCheckCount 402
# -> schema fingerprint: 2ee0ecd601f00938709f538714509b86

# undo rehearsal
.\ops\flyway\rollback\undo_phase7_10a.sql            # unarmed -> clean skip, exit 0
# armed, seeded rows, no confirm_data_loss -> REFUSED, exit 3, nothing dropped
# armed -v confirm_data_loss=yes -> PHASE7-10A_REMOVED

# backup / restore rehearsal
.\ops\backup-restore\backup.ps1
.\ops\backup-restore\restore-rehearsal.ps1 -Database firstelement_p710a_restore
```

### Evidence

| Check | Result |
| --- | --- |
| Full chain applies, 17 migrations | `Successfully applied 17 migrations to schema "public", now at version v20261005120000` |
| Legacy database scenario | baseline **stamped**, 16 additive applied, 37 new tables **empty**, `audit_logs` untouched |
| Verification bundle, full chain | `schema fingerprint: 2ee0ecd601f00938709f538714509b86`; `PHASE5A_PRESENT`, `PHASE6A_PRESENT`, `PHASE710A_AUDIT_REPORT_EXPORT_PRESENT`, `PHASE710A_FINANCE_PRESENT`, `PHASE710A_AI_PRESENT`; all three expected-count assertions satisfied |
| Undo, unarmed | clean skip, exit 0, nothing touched |
| Undo, armed + seeded rows, **no** `confirm_data_loss` | **REFUSED**, all 37 per-table counts printed, psql exit **3**, **95 tables still present** afterwards |
| Undo, armed + `confirm_data_loss=yes`, seeded | `PHASE7-10A_REMOVED`, exit 0 |
| Undo, armed + `confirm_data_loss=yes`, empty slice | `PHASE7-10A_REMOVED`, exit 0 |
| Fingerprint after the undo, **empty** path | `927e09829db8f1b38d4279f920d5b422` — exactly the post-Phase-6a value |
| Fingerprint after the undo, **seeded** path | `927e09829db8f1b38d4279f920d5b422` — the same, so the data-loss path removed no more and no less |
| Backup of `firstelement` | `pg_dump -Fc` + `-Fp` + globals, SHA-256 of each, source fingerprint `66eeaadab4ad28a8f8eaf8fe1a0748f9` |
| Restore rehearsal into a throwaway | **PASS** — `fingerprintMatches: true`, `rowCountsIdentical: true`, both enum contracts OK, target disposed |
| `verify-schema-state.ps1` on `firstelement` | `flyway_stamped=False` → **preBoot** profile → `verification bundle PASSED`, 23 enums / 45 tables |
| `firstelement` after the phase | 45 tables, 0 `flyway_schema_history` rows, 0 `_prisma_migrations`, fingerprint `66eeaadab4ad28a8f8eaf8fe1a0748f9` |
| Databases on the host | `firstelement`, `postgres` only (all 14 throwaways dropped) |

## Security and tenant-scope checks

- `organizationId uuid NOT NULL` on **every** one of the 37 new tables.
- `projectId` is nullable only on organisation-wide streams — **35** columns in
  total across the whole chain, each enumerated in
  `ops/flyway/expected-state.json` and asserted by `V7` / `V14`. For this slice
  they are: `rate_cards`, `rate_card_versions`, `rate_card_version_lines`,
  `finance_periods`, `finance_period_lock_events`, `finance_adjustments`,
  `finance_reconciliation_runs`, `report_definitions`, `report_versions`,
  `document_templates`, `document_template_versions`, `retention_classes`,
  `legal_holds`, `legal_hold_events`, `privacy_deletion_requests`,
  `privacy_deletion_targets`, `admin_audit_events`, `security_events`,
  `job_integration_events`, `ai_policies`, `ai_policy_versions`,
  `ai_model_registry`, `ai_budgets`, `ai_budget_consumptions`,
  `ai_approval_decisions`, `ai_redaction_records`, `ai_provenance_records`,
  `ai_tool_registry`, and the three `ai_run_*` child tables (added by
  `…5110000`).
- Every index on a new table leads with `organizationId` or `projectId`; the
  195-unique-index set contains no accidental global constraint, and
  `unique_constraints = 0` still holds.
- Tenant-scoped uniqueness everywhere it matters: report key per tenant, report
  version number per definition, export job idempotency per tenant,
  `usage_events` duplicate suppression at
  `(organizationId, sourceEventType, sourceEventId, dimension)`, invoice number
  per tenant, rate-card key per tenant, AI approval pending per run and action.
- Every one of the six cross-tenant negatives has a **positive control**: the
  same shape in a consistent tenant B is asserted to succeed, so a refusal
  cannot be mistaken for "this table rejects everything".
- Deny-by-default is asserted as the literal **default expression**, not as "no
  row happens to be enabled".
- The no-secrets / no-chain-of-thought column scan runs over **every** `ai_*`
  table, so a later migration cannot quietly add a `rawResponse` or `apiKey`.

## Migration and data impact

- 37 new tables, 21 new enums, 6 columns added to 3 existing tables
  (`organizationId`, `projectId` — nullable, unpopulated), **0 rows updated or
  deleted** anywhere.
- 9 indexes added to **pre-existing** tables, all `IF NOT EXISTS` UNIQUE INDEXES
  used only as composite-FK targets (`users`, `outbox_events`, `job_attempts`,
  `ai_runs`, `ai_agents`, `ai_agent_versions`, plus the three child indexes).
- No UUID regenerated. No business key (`ticketNumber`, `cxId`, `tokenHash`)
  touched. No `snake_case` rename combined with a runtime migration (ADR-0002).
- Synthetic data only, and only in Testcontainers and in the throwaway undo
  rehearsal database. `firstelement` was read-only throughout.

## Known risks and debt

1. **`CREATE INDEX` takes SHARE on three `ai_run_*` tables.** Acceptable at the
   cutover because the tables are empty then and the alternative was a boot that
   hangs; it is **not** acceptable on a large production table. Recorded as
   Phase-8 debt with the two fixes in runbook §9c.
2. **No `REVOKE UPDATE` on the append-only finance tables.** The triggers are a
   real boundary; a grant-level belt-and-braces is not yet in place.
3. **`legal_hold_events`, `export_*_events`, `privacy_deletion_targets` are
   append-only by shape only** — no `updatedAt`, and the fixture now asserts
   *both* the absent columns *and* the absence of a trigger, so the gap is
   visible rather than implied.
4. **`ai_usage.estimatedCost` adoption is an open finance decision.** The table
   exists; whether the rollup reads it or `usage_events` is undecided.
5. **`V20261005110000` leaves `organizationId`/`projectId` NULL** on
   `ai_run_*`. Until the backfill and the later `NOT NULL`, a tenant filter on
   those tables must join to the parent `ai_runs` row.
6. **Baseline `createdAt` defaults on `ai_run_*` remain session-local
   `CURRENT_TIMESTAMP`** (no `AT TIME ZONE`). Pre-existing, out of scope, and
   recorded rather than silently amended.
7. **A wrong negative test is a passing test.** Six negatives in this fixture
   were found to be *incapable of failing* — a reused row that had already
   satisfied the constraint, a fresh `gen_random_uuid()` where a fixed id was
   required, a helper that supplied the value the negative meant to omit. Each
   was rewritten to own its row and to assert its own precondition, and each is
   commented with the version that was wrong. This is the single most valuable
   class of finding in the phase.

## The undo had a silent-continue bug, and it is the most important finding here

Not a schema defect, but the one with the worst consequence, so it is reported
in full.

Measured on this host: `undo_phase7_10a.sql`, **armed**, on a slice holding
seeded rows, **without** `-v confirm_data_loss`:

```
NOTICE  UNDO PHASE7-10A row counts before removal: legal_holds=1 …
NOTICE  UNDO PHASE7-10A REFUSED: the slice holds rows (see the counts printed above). …
ERROR   UNDO PHASE7-10A REFUSED: the slice holds rows …
DROP INDEX … DROP TABLE …
NOTICE  PHASE7-10A_REMOVED: 37 tables and 21 enums dropped …
```

Overall exit code **1**. The refusal was correct, was printed, and had **no
effect whatsoever** — 37 tables of audit, finance and AI evidence were destroyed
by the run that said it would not destroy them.

The cause is psql's default, not the guards: without `ON_ERROR_STOP`, a statement
that raises is reported and then the **next statement runs**. Every guard in the
script is a `RAISE EXCEPTION` inside a `DO` block, so every guard in the script
was, on its own, only a message.

Two independent defences are now in place, and both are required:

| Defence | What it stops |
| --- | --- |
| `\set ON_ERROR_STOP on` | a `RAISE` anywhere aborts the script |
| the drop section is gated on a psql variable the guards set via `\gset` | the drops are unreachable unless every guard ran to completion, even if `ON_ERROR_STOP` is lost |

**The rehearsal lesson, which generalises past this script:** assert on the state
*afterwards*, never on the message. The rehearsal row "armed, rows → REFUSED"
passed while 37 tables were destroyed. The rehearsal now re-checks the table
count after every refusal.

## Follow-up for the orchestrator

1. **Decide `spring.flyway.postgresql.transactional-lock=false`.** It is the
   property that would make `CREATE INDEX CONCURRENTLY` viable again, and it
   belongs to the application module, not to the schema. Setting it makes
   `V20261005120000`'s three statements convertible, and the fixture will then
   fail its "no `CONCURRENTLY`" assertion — which is the intended signal, not a
   regression.
2. **Phase 8 must own the `ai_run_*` backfill** (the B-script), then the later
   `NOT NULL` migration. Do not add `NOT NULL` to `…5110000` now.
3. **Phase 8 should add the `REVOKE UPDATE`** grants on the six append-only
   finance tables.
4. **Decide `ai_usage.estimatedCost` adoption** before the finance read models
   are built, or the rollup will be written twice.
5. **The first Flyway-enabled boot of `firstelement` remains scheduled and
   backed-up**, and is now a 17-migration chain. §5, §6 and §7.4 apply.
6. **Retention for the Phase-7–10a tables is undefined**, as it is for Phase
   5a. `admin_audit_events`, `security_events` and `job_integration_events` are
   append-only with no deleter, and ADR-0008 says the windows are still
   configuration. A retention decision is owed before production cutover.