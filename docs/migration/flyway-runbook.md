# Phase 2 — Flyway runbook: baseline, backup/restore rehearsal, verification, rollback

**Scope:** the PostgreSQL/pgvector target contract for the strangler migration:
how Flyway is wired, how the baseline was taken, how to verify it, how to back
up and prove the backup restores, and how to roll back or recover.

**Never:** drop or recreate the `firstelement` development database. Never run
`prisma db push` or `prisma migrate deploy` against it. Never run destructive
DDL without a disposable target, a backup, row counts, checksums, and a
recovery path.

**Companion documents**

| Document | Purpose |
| --- | --- |
| `docs/migration/schema-drift-report.md` | live DB vs `schema.prisma` vs `init` migration; every measured number |
| `docs/migration/adr/0002-flyway-baseline-quoted-columns.md` | baseline decision and the Phase-2 outcome |
| `ops/flyway/README.md` | layout of the Flyway asset tree |
| `docs/migration/phases/phase-02.md` | phase report |

---

## 1. The chain

| Version | File | Applied to `firstelement`? | What it does |
| --- | --- | --- | --- |
| `20260814000000` | `V20260814000000__baseline_verified_live_schema.sql` | **stamped, not executed** | 45 tables, 23 enums, `pgcrypto` + `pgvector`, quoted camelCase preserved |
| `20260814000100` | `V20260814000100__vector_indexes.sql` | **pending** — becomes active the first time the Spring API boots against `firstelement` | adds `document_chunks_embedding_hnsw_idx` (HNSW, cosine) |
| `20261004000100` | `V20261004000100__outbox.sql` | **pending** (Phase 5a) | enum `OutboxState`; `outbox_events` with lease pair, attempts, `nextAttemptAt`, payload, tenant-unique `idempotencyKey`, dead-letter and replay state |
| `20261004000700` | `V20261004000700__job_attempts.sql` | **pending** (Phase 5a) | enums `JobAttemptOutcome`, `DeadLetterStatus`; append-only `job_attempts`, `job_dead_letters` |
| `20261004001000` | `V20261004001000__idempotency_records.sql` | **pending** (Phase 5a) | enum `IdempotencyState`; `idempotency_records` reserve/complete with tenant-scoped uniqueness |
| `20261005010000` | `V20261005010000__workflow_definitions_and_versions.sql` | **pending** (Phase 6a) | enum `WorkflowVersionState`; `workflow_definitions` + `workflow_versions` with one-ACTIVE-per-definition and audited rollback |
| `20261005020000` | `V20261005020000__workflow_runs_and_action_results.sql` | **pending** (Phase 6a) | enums `WorkflowRunStatus`, `WorkflowActionOutcome`; `workflow_runs` + append-only `workflow_run_actions`; composite tenant FKs |
| `20261005030000` | `V20261005030000__sla_calendars.sql` | **pending** (Phase 6a) | enums `SlaCalendarState`, `SlaClockType`; `sla_calendars`, `sla_calendar_windows`, `sla_calendar_holidays`, `sla_policies` with three independent clocks |
| `20261005040000` | `V20261005040000__collision_records.sql` | **pending** (Phase 6a) | enums `CollisionScope`, `CollisionResolution`; append-only `collision_records` with a 5-minute dedup window |
| `20261005050000` | `V20261005050000__audit_streams.sql` | **pending** (Phase 7–10a) | 6 enums; **four separate** audit/retention families — `admin_audit_events`, `security_events`, `job_integration_events` (append-only) plus `retention_classes`, `legal_holds`, `legal_hold_events`, `privacy_deletion_requests`, `privacy_deletion_targets`; 3 composite tenant keys added to `users`, `outbox_events`, `job_attempts` |
| `20261005060000` | `V20261005060000__report_definitions.sql` | **pending** (Phase 7–10a) | 2 enums; `report_definitions` + `report_versions` + `document_templates` + `document_template_versions`; 4 validator functions (`firstelement_valid_timezone`, `firstelement_array_has_blank`, `firstelement_json_list_is_string_array`, `firstelement_json_list_is_sort`) because PostgreSQL forbids subqueries in `CHECK` |
| `20261005070000` | `V20261005070000__export_jobs.sql` | **pending** (Phase 7–10a) | 4 enums; `export_jobs` (7-state machine) + `export_job_attempts` + `export_download_events` + `export_deletion_events`; depends on `report_definitions` above, so it must sort after it |
| `20261005080000` | `V20261005080000__usage_ledger.sql` | **pending** (Phase 7–10a) | 4 enums; `usage_events` — **cost only, no revenue/margin/price/markup and no `rateCardVersionId`**; a guard `DO` block fails the migration if any derived-money column ever appears |
| `20261005090000` | `V20261005090000__finance_rate_cards_periods.sql` | **pending** (Phase 7–10a) | 5 enums; 9 tables (`rate_cards`, `rate_card_versions`, `rate_card_version_lines`, `finance_periods`, `finance_period_lock_events`, `usage_rollup_snapshots`, `finance_adjustments`, `finance_reconciliation_runs`, `finance_reconciliation_lines`); revenue derived only on `usage_rollup_snapshots` with `CHECK revenueAmount = round(costAmount * markupRate, 6)`; 5 blanket append-only triggers (one of them on `usage_events`, see §9c) + 1 **narrower** one on `rate_card_versions` + a locked-period `BEFORE INSERT` trigger |
| `20261005100000` | `V20261005100000__ai_policy_and_governance.sql` | **pending** (Phase 7–10a) | 4 enums; 11 tables — `ai_tool_registry` (deny by **default expression**), `ai_policies`, `ai_policy_versions`, `ai_model_registry`, `ai_budgets`, `ai_budget_consumptions`, `ai_approvals`, `ai_approval_decisions`, `ai_redaction_records`, `ai_provenance_records`, `ai_tool_executions`; no-secrets and no-CoT guards |
| `20261005110000` | `V20261005110000__ai_run_child_tenant_scope_columns.sql` | **pending** (Phase 7–10a) | EXPAND step: 3 `ADD COLUMN` (`organizationId`, `projectId` on `ai_run_steps`/`ai_run_retrievals`/`ai_run_tool_calls`), **nullable**, transactional and atomic; a guard `DO` block fails if any pre-existing row cannot be backfilled |
| `20261005120000` | `V20261005120000__ai_run_child_tenant_scope_indexes.sql` | **pending** (Phase 7–10a) | 3 plain `CREATE INDEX` on the columns added above. **This file replaces `CREATE INDEX CONCURRENTLY`** — see §9c; the header records the three attempts that failed and why |
| `20261006100000` | `V20261006100000__ai_approval_decision_biconditional_repair.sql` | **pending** (Phase 10b) | **D1.** Replaces `ai_approvals_approval_check` with `(status IN ('APPROVED','REJECTED','REVOKED')) = (decidedAt IS NOT NULL)`, so a rejection can name its decider and a revocation can keep the grant's decision (ADR-0011 §1). Report → drop → **repair from `ai_approval_decisions`** → refuse → add |
| `20261006110000` | `V20261006110000__document_chunks_document_tenant_fkey.sql` | **pending** (Phase 10b) | **D2.** Adds `document_chunks_document_tenant_fkey` `(documentId, organizationId) → documents(id, organizationId)` plus its `documents_id_organizationId_key` target, after measuring 0 cross-tenant chunks (ADR-0011 §2). The single-column FK is **kept** |
| `20261006120000` | `V20261006120000__api_keys_and_service_principals.sql` | **pending** (Phase 10b) | **D3.** 2 enums, `service_principals` and `api_keys` (FR-6), a KDF-digest guard trigger, and a lifecycle-audit trigger that writes `admin_audit_events` for a key create/revoke and a principal create/status/capability change |
| `20261006130000` | `V20261006130000__legal_holds_scope_pair_widening.sql` | **pending** (Phase 10b) | **D4.** Widens `legal_holds_scope_pair_check` so every `LegalHoldScopeType` member has a satisfiable arm. Purely a widening: the repair count is necessarily and measurably 0 |
| `20261006140000` | `V20261006140000__privacy_deletion_subject_reference.sql` | **pending** (Phase 10b) | **D5.** Adds `subjectCustomerId`, its composite tenant FK, a tenant-led index and `privacy_deletion_requests_subject_reference_check`; relaxes `subjectUserId` to nullable. The privacy state machine is untouched |
| `20261007100000` | `V20261007100000__customer_cx_sequences.sql` | **pending** (Phase 14) | **CX-1 part 1.** `customer_cx_sequences`, one row per `organizationId` holding `lastAllocatedCxSequence` (bigint, `CHECK` 0–999999), a UNIQUE INDEX arbiter on `organizationId` **alone**, and `firstelement_next_customer_cx_id(uuid)` — the atomic allocator, `INSERT … ON CONFLICT DO UPDATE … RETURNING`, VOLATILE, no `count(*)` |
| `20261007110000` | `V20261007110000__customers_cx_id_tenant_scope.sql` | **pending** (Phase 14) | **CX-1 part 2.** Backfills the counter from `max("cxId")` per organization **first**, asserts no organization with customers is left without one, then drops the GLOBAL `customers_cxId_key` and adds `customers_organizationId_cxId_key UNIQUE ("organizationId","cxId")`. ADR-0014 §Decision. **The one DROP in the batch, and it replaces a constraint** |

> **Version numbering rule (Phase 5a, extended by Phase 6a and Phase 7–10a).** Flyway orders
> versions **numerically**, not lexically. The Phase-5a drafts were originally
> named with 12-digit versions (`V202610040001`), which sort *before* the 14-digit
> baseline and made the outbox migration run against an empty database
> (`relation "public.organizations" does not exist`). Every promoted migration is
> therefore **14 digits** (`yyyyMMddHHmmss`) and **numerically greater than the
> previous version** — for Phase 6a that means greater than `20261004001000`, not
> merely greater than the baseline. The remaining drafts carry a
> `RENUMBER BEFORE PROMOTING` banner in their header.

> **Phase 7–10a promoted all seven remaining drafts.** `ops/flyway/pending/` is
> now **empty**: every `*.sql.todo` was promoted to a real 14-digit migration
> above and its draft deleted, so no draft can be mistaken for something that
> will ever run. Seven drafts became **eight** files — the AI-run child tenant
> scope split into a transactional EXPAND migration (`…5110000`) and a separate
> index migration (`…5120000`) because Flyway refuses a script that mixes
> statement kinds. Phase 6a promoted no draft; the four files it added were
> written from scratch.

> **Ordering is a dependency, not a preference.** `export_jobs.reportDefinitionId`
> → `report_definitions.id`, so `…5070000` must sort after `…5060000`, and
> `usage_events.periodStart/periodEnd` gains a composite tenant foreign key in
> `…5090000`, so finance must sort after the ledger. Renumbering either file
> in isolation breaks the chain.

`firstelement` currently has **no** `flyway_schema_history` and no
`_prisma_migrations`. The baseline is a schema-only `pg_dump` of the verified
live database, regenerated with `ops/flyway/generate/generate-baseline.ps1`.

`*.sql.todo` is not matched by Flyway's `V*.sql` location pattern. A file with
that suffix is inert: it cannot be executed by `flywayMigrate`, by the Spring
boot, or by the Testcontainers fixture. `ops/flyway/pending/` is **empty** —
there are no drafts left, so no future migration can be half-written and
believed to be scheduled. A new draft must be given a real
`V<14 digits>__name.sql` name and its checksum recorded here before it is
referenced from any plan.

### Two copies of the chain, one source of truth

| Path | Role |
| --- | --- |
| `ops/flyway/sql/` | canonical, reviewed copy |
| `backend/database/src/main/resources/db/migration/` | packaged onto the `app-bootstrap` classpath at `classpath:db/migration` |

They must be byte-identical. `FlywayBaselineAcceptanceTest` asserts it and
`generate-baseline.ps1` warns when they diverge:

| File | SHA-256 |
| --- | --- |
| `V20260814000000__baseline_verified_live_schema.sql` (both copies) | `f3df55c3a2abb142b3c67c480777c57e712db0a8ddad825d6a9d212a88fe0bb8` |
| `V20260814000100__vector_indexes.sql` (both copies) | `3a0fecbbb596d4d2c1cd9d2dd247f816c6ee93cfe0911f92713119887a927760` (amended in Phase 5a: schema-qualified table + operator class, so the whole chain applies in one session; never applied anywhere, so no recorded checksum changed) |
| `V20261004000100__outbox.sql` (both copies) | `4cc833a03581a1152c988ef7ec67e71f3bf220a3cd3ae16afa6e2d517b18a3ad` |
| `V20261004000700__job_attempts.sql` (both copies) | `84db18741188ccb0b1a33443a29c1bdc3e3109b8ef201336df483e1ebe870c9e` |
| `V20261004001000__idempotency_records.sql` (both copies) | `fbf901c8a32f46b044c67632323ddad3dac4e5199ab6f61d067fe8244d078f45` |
| `V20261005010000__workflow_definitions_and_versions.sql` (both copies) | `01f4fb6cb317793fa5003f99472bec0f9fb2be4fa863e09727008fda33f16e19` |
| `V20261005020000__workflow_runs_and_action_results.sql` (both copies) | `2c431b4e74ec4d4a64241420f57b4af19fbca2b7a7eb59b71eedd54836cb7cc6` |
| `V20261005030000__sla_calendars.sql` (both copies) | `3b0cbd66adfe8598be798ea950560fe97527bcbd85a5f19a9de2ee95db842c1d` |
| `V20261005040000__collision_records.sql` (both copies) | `e6e0a59ead600142cb62e002f18ff4e599f271a268b82ecabf90d268ad988446` |
| `V20261005050000__audit_streams.sql` (both copies) | `1ddda9bba6959bc29a96c43a0492f5ef7d2b924cd8ba0bd3958c12c9d735cf69` |
| `V20261005060000__report_definitions.sql` (both copies) | `b3d4fbc7232f1bbcf6bb2c2276e0336606518df950dce52ad3e1936c80761407` |
| `V20261005070000__export_jobs.sql` (both copies) | `c9c942b2f86ee88babf17377a3c394bd1c8d8e9445de3759bfc63eff0e941e8d` |
| `V20261005080000__usage_ledger.sql` (both copies) | `bb54d9844a6ffce83340f97064840b515e7a2e17165ac93a719e58355dbece0b` |
| `V20261005090000__finance_rate_cards_periods.sql` (both copies) | `508e30a28f99de3fa133ce3e59a11ce62fe88b17845622a43fa3edc985577628` |
| `V20261005100000__ai_policy_and_governance.sql` (both copies) | `9c8e5c11569a76fe671d4a4fb506bc8e16e082b0498fa41f2f2987e3296a0b69` |
| `V20261005110000__ai_run_child_tenant_scope_columns.sql` (both copies) | `62746b92d250c809144cc03cef57604ea119f69f87750e18b3fa96e4b2a19fe4` |
| `V20261005120000__ai_run_child_tenant_scope_indexes.sql` (both copies) | `d6beb6a4a8f13957530663c8d207644dd435dac30595b3dac5f3fa8c614f8fdb` |
| `V20261006100000__ai_approval_decision_biconditional_repair.sql` (both copies) | `0c2041092337637c94ce697dfad4dd15fa3eb76ed4d51f7acd45f24d0a224814` |
| `V20261006110000__document_chunks_document_tenant_fkey.sql` (both copies) | `db4788197863779420b4e94727cfdd0f99607d830b583c7c2940a9bc585f5f3c` |
| `V20261006120000__api_keys_and_service_principals.sql` (both copies) | `b89dfd350b3003cbb014a7d50ad1b6b5f3b89637f89333b95ff77384e2f37fe1` |
| `V20261006130000__legal_holds_scope_pair_widening.sql` (both copies) | `394706107d656bafd1c4cf7a9e99ce900fdca5913e63955a37da9768e09f9e23` |
| `V20261006140000__privacy_deletion_subject_reference.sql` (both copies) | `533ec7e43052f17ebb824a3fc516d5bcb66e2c4a5dc2a3de7450c4e81d4f232d` |
| `V20261007100000__customer_cx_sequences.sql` (both copies) | `50d1d0b30323ab6b446a80b4a943d7b2ed06fd4dae4c41b311a9bfadba0820d6` |
| `V20261009110000__appointments_tenant_composite_fkeys.sql` (both copies) | `ebccf63600791aa06ebb7d3d3c1f58a8a1c2801e95a6f6906f588d4d183a599e` |
| `V20261009130000__user_role_clinic_staff.sql` (both copies) | `84aca548ecf3d0d2b71a589b449980d933f08ebfdcb67ca5e1fe2726a4fa9fcd` |
| `V20261007110000__customers_cx_id_tenant_scope.sql` (both copies) | `6a02f8224c89f0516247fb4660aaa63c8bfc2f692708281f6c3b949225e87784` |

> **The seventeen Phase-2 … Phase-7–10a digests above are unchanged by the Phase-10b
> or Phase-14 batches.** No already-recorded migration was amended: five defects and
> then a sixth, seven new versions, and `V20260814000000` / `V20261005050000` /
> `V20261005100000` are read exactly as the runbook already recorded them. The
> constraints Phase 14 changes are corrected by *replacement* in `…7100000` /
> `…7110000`, which is the only way to change a PostgreSQL uniqueness rule — a
> constraint is a rule, and the wrong rule cannot be edited in place.

> The digests for `…080000`, `…090000` and `…100000` are **not** the ones first
> written in this phase. The fixture forced four schema corrections (§9c) after
> those files were authored, and those three were amended to carry them. That is
> safe **only because none of the eight has ever been applied anywhere** — the
> Phase-2/5a/6a values above are unchanged, which is the property that actually
> matters. **A migration in this chain that has been applied must never be
> amended; the fix goes in a new version.**

The five Phase-2/5a SHA-256 values above are unchanged from Phase 5a and the four
Phase-6a values are unchanged from Phase 6a: **no already-recorded migration was
amended in Phase 7–10a or Phase 14**, so no recorded Flyway checksum can drift. The
ten new files were authored in those phases, which is why their digests appear for
the first time here. `FlywayBaselineAcceptanceTest` asserts byte-identity across all
**twenty-four** files in both directions — including that the packaged copy contains
nothing the reviewed copy lacks, which is the check that catches a classpath-only
migration nobody in `ops/flyway/sql` ever reviewed — and, since Phase 10b, that
`CHAIN_FILES` names every file in `ops/flyway/sql`, so a new migration cannot escape
the check by simply not being listed. It also asserts that **no `.conf` sidecar exists
anywhere** in either directory (see §9c).

---

## 2. Spring wiring

`:backend:database` is a plain (non-boot) library module on the
`app-bootstrap` classpath. It supplies `Flyway core`,
`flyway-database-postgresql`, and the packaged `db/migration` chain. Spring
Boot's Flyway auto-configuration runs at startup; no extra property is needed
because `classpath:db/migration` is already the default location.

`com.firstelement.db.FlywayDatabaseConfiguration` supplies a
`FlywayMigrationStrategy` that decides from the database's own state before any
DDL runs:

| Starting state | Detected by | Flyway action |
| --- | --- | --- |
| fresh / empty | no `flyway_schema_history`, no tables | execute the **whole** chain, baseline included |
| legacy schema | no `flyway_schema_history`, application tables present | **stamp** `20260814000000` (DDL is *not* replayed), then run later additive migrations |
| already managed | `flyway_schema_history` present | run outstanding migrations only |

Detection is read-only. If the schema cannot be read the boot **fails** with
`Flyway baseline detection failed` rather than guessing — a wrong guess means
replaying DDL against a live tenant.

`BASELINE_VERSION` (`20260814000000`) equals the baseline migration's own
version and **must never be raised**. Raising it would silently skip migrations
that a fresh database would have executed.

### Relevant datasource properties

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/firstelement
    username: ${DATABASE_USER:firstelement}
    password: ${DATABASE_PASSWORD}          # supply from the environment; never commit
  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: false              # the strategy decides; see above
```

The Prisma-style `DATABASE_URL`
(`postgresql://user:pass@host:5432/db`) is **not** a JDBC URL. Translate it,
and keep the password out of `commandlineArguments` so it does not appear in
`ps` output:

```powershell
$u = [Uri]$env:DATABASE_URL
$env:SPRING_DATASOURCE_URL = "jdbc:postgresql://$($u.Host):$($u.Port)/$($u.AbsolutePath.TrimStart('/'))"
$env:DATABASE_USER         = $u.UserInfo.Split(':')[0]
$env:DATABASE_PASSWORD     = $u.UserInfo.Split(':')[1]
```

### Boot behaviour to expect

```powershell
.\gradlew.bat :backend:app-bootstrap:bootJar --no-daemon
$env:SPRING_FLYWAY_ENABLED='true'
java -jar backend\app-bootstrap\build\libs\firstelement-api-0.1.0.jar
```

Look for, in order:

```
Flyway starting on schema '...': STAMP_BASELINE
Successfully baselined schema with version: 20260814000000
Migrating schema "public" to version "20260814000100 - vector indexes"
Successfully applied 1 migration to schema "public", now at version v20260814000100
```

If you instead see `Successfully applied 2 migrations` against `firstelement`,
**stop** — that means the baseline DDL was replayed over an existing schema.
Capture the output, restore from backup (§6), and report it.

Confirm the result:

```powershell
docker exec firstelement-postgres psql -U firstelement -d firstelement -P pager=off -c "
select installed_rank, version, description, type, success
  from flyway_schema_history order by installed_rank;"
```

---

## 3. Verification queries

`ops/flyway/verification/run-verification.ps1` runs all of V1–V9, writes a
timestamped evidence bundle, and prints a single schema fingerprint. It is
**read-only**: no DDL, no DML, no objects created.

```powershell
# against the verified dev database
.\ops\flyway\verification\run-verification.ps1 `
    -Container firstelement-postgres -Database firstelement `
    -OutputDir C:\...\evidence\2026-10-04-post-migration -ThrowOnViolation
```

| Query | Asserts |
| --- | --- |
| `V1_schema_fingerprint.sql` | one deterministic fingerprint of extensions, enums (values in sort order), columns (position/type/nullability/default), constraints, and index definitions |
| `V2_row_counts.sql` | `count(*)` per table plus a `total_rows` and an `md5` of the whole count map |
| `V3_enum_contract.sql` | 23 enums; `EmployeeAvailabilityStatus` and `AIRunDecision` exactly match the reconciled lists |
| `V4_foreign_keys.sql` | 62 FKs, 45 PKs, every `ON UPDATE CASCADE`, no unsafe `ON DELETE` |
| `V5_indexes_and_vector.sql` | 45 tables / 155 indexes, index inventory, pgvector extension, and a live `<=>` cosine probe |
| `V6_nullability_and_timestamps.sql` | every `organizationId` is `NOT NULL`; only `audit_logs.projectId` and `notifications.projectId` are nullable; every timestamp is `timestamp without time zone` |
| `V7_tenant_scope.sql` | per-table scope model for all 45 tables, the explicit list of nullable tenant columns, and every index led by `organizationId`/`projectId` |
| `V8_reference_integrity.sql` | 0 FK orphans, 0 cross-tenant/cross-scope rows, 45 `uuid` PKs, 0 rows missing a business key |
| `V9_index_scope_census.sql` | every index classified into exactly one scope bucket (tenant-led / project-led / parent-key / unique-other / global); the `global` bucket must contain only `sessions_expiresAt_idx` |
| `V10_outbox_job_slice.sql` | Phase 5a: outbox/job/idempotency tables, their CHECKs, and the claim-statement contract. Reports `PHASE5A_NOT_PRESENT` on a pre-boot database, so the same bundle is valid on both sides of the cutover |
| `V11_workflow_sla_slice.sql` | Phase 6a: the nine workflow/SLA/collision tables, their CHECKs, the three independent SLA clocks, the tenant-less allow list, the append-only boundary, and the cross-tenant negatives **with data**. Reports `PHASE6A_NOT_PRESENT` on a pre-boot database and `PHASE6A_PARTIAL` if a Phase-6a migration failed halfway |
| `V12_admin_export_report_slice.sql` | Phase 7–10a: the four audit tables are physically separate, legal hold and retention, the report catalogue, the export state machine. Reports `PHASE7_10A_NOT_PRESENT` before the boot |
| `V13_usage_finance_slice.sql` | Phase 7–10a: `usage_events` carries **cost only**, revenue is derived only on `usage_rollup_snapshots` from the pinned `markupRate`, a closed period is append-only and refuses both `UPDATE` and a late insert, and the six append-only triggers are real triggers rather than conventions |
| `V14_ai_policy_slice.sql` | Phase 7-10a: deny-by-default as a **default expression**, the approval biconditional (rewritten in Phase 10b to the corrected form), no-secrets/no-CoT guards, and the three `ai_run_*` child tables' tenant scope |
| `V15_schema_remediation_slice.sql` | Phase 10b: the five remediated defects, each asserted on the STATE. D1 the decision biconditional plus a per-status census; D2 the composite chunk/document FK with a `confdeltype` check; D3 the column census, the three secret controls, and lifecycle-audit **evidence** counted against real rows; D4 `LegalHoldScopeType` totality with the must-refuse combinations; D5 the subject reference and a re-assertion that the privacy state machine is intact. Reports `PHASE10B_NOT_PRESENT` / `PHASE10B_PARTIAL`, and prints the two residuals and the legacy-arm count |
| `V16_customer_cx_sequence_slice.sql` | Phase 14: the customer reference namespace (ADR-0014, CX-1). §3 the uniqueness rescope read from `pg_class`/`pg_index` — global key gone, composite key UNIQUE/valid/non-partial/non-expression on exactly `("organizationId","cxId")` in that order; §4 the allocator is VOLATILE, allocates in one statement with `ON CONFLICT` and `RETURNING`, and contains **no `count()`**; §5 the counter agrees with `max("cxId")` for every organization with customers, and prints the gap residual; §6 **behaviour**, inside a transaction the file always rolls back — capacity across six organizations, capacity across six projects in one organization, strict monotonicity, no reuse after deletion, and no cross-tenant inference; §7 the row counts are unchanged **after** the rollback. Reports `PHASE14_NOT_PRESENT` on a pre-cutover database and **refuses** `PHASE14_PARTIAL` |

> **`V16` §6 is the only part of the bundle that writes.** It is wrapped in
> `BEGIN; DO $$ … $$; ROLLBACK;`, so leaving a trace is structurally impossible:
> a `RAISE` aborts psql and the disconnect rolls back, a kill rolls back, and
> there is no `COMMIT` and no DDL outside the transaction. Nothing irreversible
> is involved either — the probe rows use `gen_random_uuid()`, and there is not one
> `nextval` default in this schema. §7 proves it from the row counts rather than
> from the fact that the file contains the word `ROLLBACK`. It does take a
> `RowExclusive` lock on `organizations` / `projects` / `customers` for the length
> of one short transaction: **run it against dev or staging, not production.**

`run-verification.ps1` now carries three expected-count **assertions**. None is a
switch to silence a failure: each defaults to the pre-boot value, so leaving the
default against a migrated database **fails** the bundle — the intended signal
that someone ran a migration without recording why a count moved.

| Flag | Default | Post-Phase-6a value | Post-Phase-7–10a value | Post-Phase-10b value | Post-Phase-14 value | What a mismatch means |
| --- | --- | --- | --- | --- | --- | --- |
| `-ExpectedEnumCount` | 23 | 34 | 55 | **57** | **57** | an enum value list changed, or an enum was added without a record |
| `-ExpectedTableCount` | 0 (not asserted) | 58 | 95 | **97** | **98** | a table was added or dropped without a record |
| `-ExpectedCheckCount` | 0 (not asserted) | 71 | 402 | **421** | **422** | **a CHECK constraint was dropped — a lost invariant** |

The Phase-14 deltas are **+1 table, +0 enums, +1 CHECK, +1 FK, +1 PK, +2 indexes,
+2 unique indexes**. Phase 14 adds exactly one table (`customer_cx_sequences`) and
exactly one CHECK (`customer_cx_sequences_sequence_range_check`), and adds **no
enum** — which is why the enum count is the one number that does not move. The
index arithmetic is `392 + 2 (the counter table's pkey and its unique arbiter) - 1
(customers_cxId_key removed) + 1 (customers_organizationId_cxId_key added) = 394`,
and undoing returns `392` exactly, which `rehearse-phase14-undo.ps1` asserts.

The Phase-10b CHECK delta is **+19, not +21**, and the arithmetic is worth
recording because it is the kind of number that gets "corrected" by whoever reads
it next. Two of the five migrations **replace** a CHECK rather than adding one —
`ai_approvals_approval_check` and `legal_holds_scope_pair_check`, because
PostgreSQL cannot edit a `CHECK` expression — so those contribute 0 to the count
while moving the rule. D3's two new tables contribute 18 (6 on
`service_principals`, 12 on `api_keys`) and D5 contributes 1. `402 + 18 + 1 =
421`, and `421 + 1 = 422`.

Against a fully migrated database the bundle is therefore run as:

```powershell
.\ops\flyway\verification\run-verification.ps1 `
  -Database firstelement_p14_chain `
  -ExpectedEnumCount 57 -ExpectedTableCount 98 -ExpectedCheckCount 422
# expect, in the output: schema fingerprint: bf31e9f102be19a6c373eb353b2d2131
```

### Expected values before and after the first Flyway boot

| Assertion | Pre-boot (current) | After the Phase-5a boot | After the Phase-6a boot | After the Phase-7–10a boot | After the Phase-10b boot |
| --- | --- | --- | --- | --- | --- |
| tables / indexes (`V5`) | 45 / 156 | 49 / 176 | 58 / 212 (102 unique) | 95 / 379 (195 unique) | 97 / 392 (203 unique) | **98 / 394 (205 unique)** |
| FKs / PKs / CHECK (`V4`) | 62 / 45 / 0 | 76 / 49 / 14 | 113 / 58 / 71 | 211 / 95 / 402 | 222 / 97 / 421 | **223 / 98 / 422** (`unique_constraints` still **0**) |
| enums (`V3`) | 23 | 27 | 34 | 55 | **57** | **57** — Phase 14 adds none |
| index census (`V9`) | 64 / 15 / 22 / 53 / 1 | 78 / 15 / 22 / 57 / 3 | 100 / 15 / 22 / 70 / 4 (+1 vector-led) | 200 / 15 / 22 / 136 / 5 global (+1 vector-led) | 207 / 15 / 22 / 142 / 5 global (+1 vector-led) | **209 / 15 / 22 / 142 / 5 global** (+1 vector-led) |
| tables without `organizationId` | 4 | 4 | 4 | 3 — `organizations` plus the two `sla_calendar_*` configuration children | **3** — unchanged | **3** — unchanged |
| nullable `projectId` columns | 2 | 6 | 10 | 35 | **37** | **37** — the counter table carries no `projectId` at all, like `sla_calendar_windows` |

The Phase-14 column is **measured, not derived**: every number above was read off a
throwaway database (`firstelement_p14_chain`) after all twenty-four migrations
succeeded, and the same numbers are what `ops/flyway/expected-state.json` records
and what `verify-schema-state.ps1` asserts. Its fingerprint is
`bf31e9f102be19a6c373eb353b2d2131`; the Phase-10b value `3a62e8c30d6f0043ca86a96becb2f7ba`
is now the **Phase-14 undo checkpoint** rather than an expectation.
`verify-schema-state.ps1` picks the pre-boot or post-boot profile by asking
whether `flyway_schema_history` exists, so the dev database is checked against the
numbers that are actually true for it, and a migrated one against the numbers
that are actually true for it.

**Two tenant-led indexes are added by Phase 14** — `customer_cx_sequences_organizationId_key`
(the `ON CONFLICT` arbiter) and `customers_organizationId_cxId_key` — and one is
removed, `customers_cxId_key`. That is why the tenant-led bucket goes `207 → 209`
while `unique-other` stays at 142: both new indexes lead with `organizationId`, so
they classify as tenant-led rather than as parent keys.
**Three of the four tables without `organizationId` gain one.**
`V20261005110000` adds `organizationId` and `projectId` to `ai_run_steps`,
`ai_run_retrievals` and `ai_run_tool_calls` — the EXPAND half of
expand/contract — and they are **nullable** on purpose (§9c). What remains is
`organizations`, the tenant root, which by definition has no parent, plus
`sla_calendar_windows` and `sla_calendar_holidays`, which are Phase-6a
configuration children of `sla_calendars` and inherit its scope (already pinned
by `V11` §4c). The fixture asserts that residue by name rather than by count, so
a fourth table cannot appear without the test noticing which one it was.

The **five** `global`-bucket indexes after the Phase-7–10a boot are the four
Phase-6a ones — `sessions_expiresAt_idx`, `outbox_events_dispatch_sweep_idx`,
`outbox_events_expired_lease_idx`, `sla_calendar_windows_calendar_day_idx` — plus
the Phase-7–10a legal-hold expiry sweep. The outbox pair are dispatcher and
reaper sweeps; the calendar one is the per-clock-evaluation window read; the
fifth is the one maintenance sweep that has to notice an expiring hold
regardless of tenant. None is an authorization path and none exposes tenant
content. All five are on `V14`'s allow list, so a sixth cannot appear by accident.

Phase 7–10a adds six more **composite tenant-key target indexes**, all
`IF NOT EXISTS` UNIQUE INDEXES (again so `unique_constraints` stays 0), on
`users`, `outbox_events`, `job_attempts`, `ai_runs`, `ai_agents` and
`ai_agent_versions`. These are not read paths either — they exist so a composite
FOREIGN KEY can refuse a cross-tenant reference, in this case an audit event
attributed to another tenant's administrator, a legal hold placed by another
tenant's administrator, an approval granted by another tenant's user. Six of the
eight `…_id_organizationId_key` indexes in the chain are on **pre-existing
baseline or Phase-5a tables**, each created `IF NOT EXISTS`, each removed by
`ops/flyway/rollback/undo_phase7_10a.sql` so the fingerprint returns exactly.

Phase 6a also adds three **composite tenant-key target indexes** that lead with
`id`, not `organizationId`:
`workflow_versions_id_definitionId_organizationId_key`,
`sla_calendars_id_organizationId_key` and
`conversations_id_organizationId_key`. They are not read paths. They exist so a
composite FOREIGN KEY can refuse a cross-tenant reference — a run bound to
another tenant's workflow body, a policy pointing at another tenant's calendar, a
run pointing at another tenant's conversation. Each is a UNIQUE **INDEX** rather
than a UNIQUE CONSTRAINT specifically so `V4`'s `unique_constraints = 0`
invariant survives; PostgreSQL accepts a non-partial unique index as a
foreign-key target, which was verified on this host before it was relied on.
`conversations_id_organizationId_key` is the **only index Phase 6a adds to a
pre-existing baseline table**, it is created `IF NOT EXISTS`, and the undo
script removes it so the fingerprint returns exactly.

**Nullable `projectId` after the boot (10 columns).** Two from the baseline
(`audit_logs`, `notifications`), four from Phase 5a (`outbox_events`,
`job_attempts`, `job_dead_letters`, `idempotency_records`), and four from Phase
6a (`workflow_definitions`, `workflow_versions`, `sla_calendars`,
`sla_policies`) — all organisation-wide by design and listed explicitly by `V7`
§2 and `V11` §4d. Two further Phase-6a tables, `sla_calendar_windows` and
`sla_calendar_holidays`, carry **no tenant column at all**: they are
configuration children of a calendar and inherit its scope, exactly as
`ai_run_steps` inherits from `ai_runs` in the verified baseline, and `V11` §4c
fails if one ever grows a tenant column. `collision_records.projectId` is
`NOT NULL` — the opposite decision, because every collided resource is
project-scoped and `NULL` could only mean "unknown scope".

`-ThrowOnViolation` turns every assertion into a non-zero exit code (used by CI
and by the restore rehearsal).

### Spot checks worth running by hand

```powershell
# --- table / history state
docker exec firstelement-postgres psql -U firstelement -d firstelement -P pager=off -c "
select count(*) as app_tables from pg_tables
 where schemaname='public' and tablename <> 'flyway_schema_history';"
# expect 45

docker exec firstelement-postgres psql -U firstelement -d firstelement -t -A -c "
select count(*) from pg_class where relname='flyway_schema_history';"
# expect 1 after the first Spring boot, 0 before

# --- the reconciled enum
docker exec firstelement-postgres psql -U firstelement -d firstelement -P pager=off -c "
select e.enumlabel from pg_enum e
  join pg_type t on t.oid = e.enumtypid
 where t.typname = 'EmployeeAvailabilityStatus' order by e.enumsortorder;"
# expect ONLINE, OFFLINE, BUSY, BREAK, MEAL, TRAINING, MEETING
# and NOT AVAILABLE / AWAY

# --- foreign keys
docker exec firstelement-postgres psql -U firstelement -d firstelement -P pager=off -c "
select count(*) filter (where contype='f') as fks,
       count(*) filter (where contype='p') as pks
  from pg_constraint
  join pg_class c on c.oid = conrelid
  join pg_namespace n on n.oid = c.relnamespace
 where n.nspname='public';"
# expect 62 / 45

# --- pgvector + the HNSW index
docker exec firstelement-postgres psql -U firstelement -d firstelement -P pager=off -c "
select indexname, indexdef from pg_indexes
 where schemaname='public' and tablename='document_chunks' order by indexname;"
# expect document_chunks_embedding_hnsw_idx USING hnsw (embedding vector_cosine_ops)
#        document_chunks_organizationId_projectId_knowledgeBaseId_idx
docker exec firstelement-postgres psql -U firstelement -d firstelement -P pager=off -c "
select '[0,1,2]'::vector <=> '[3,4,5]'::vector as cosine_probe;"
# expect 0.11456225515285379

# --- index scope census (V9; read the note under the command)
Get-Content -Raw ops/flyway/verification/V9_index_scope_census.sql |
  docker exec -i firstelement-postgres psql -U firstelement -d firstelement -v ON_ERROR_STOP=1 -f -
# expect 64 tenant-led | 15 project-led | 22 parent-key | 53 unique-other | 1 global
```

**Note on the buckets.** `V9_index_scope_census.sql` classifies every index into
exactly one bucket in priority order, so the counts add up to the total (155
before `V20260814000100`, 156 after). The buckets are:

- **tenant-led** `btree ("organizationId", …)` — the canonical `FR-1.3` filter;
- **project-led** `btree ("projectId", …)` — project scope *is* tenant scope on
  a project-scoped table;
- **parent-key** on `runId` / `conversationId` / `employeeId` / `userId` /
  `knowledgeBaseId` / `agentVersionId` / `customerId` — only reachable through
  an already-scoped parent. **Do not "fix" these by prepending
  `organizationId`**: that duplicates 22 indexes for zero query benefit;
- **unique-other** — business keys;
- **global** — exactly one index, `sessions_expiresAt_idx`. It is intentional: a
  session-expiry sweep has no tenant and must not require one. It exposes only
  `expiresAt` and is not an authorization path.

### Tenant-scope invariants to re-assert after every migration

```powershell
docker exec firstelement-postgres psql -U firstelement -d firstelement -P pager=off -c "
select c.relname from pg_class c
  join pg_namespace n on n.oid = c.relnamespace
 where n.nspname='public' and c.relkind='r'
   and c.relname <> 'flyway_schema_history'
   and not exists (select 1 from pg_attribute a
                    where a.attrelid=c.oid and a.attnum>0 and not a.attisdropped
                      and a.attname='organizationId')
 order by 1;"
# expect exactly: ai_run_retrievals, ai_run_steps, ai_run_tool_calls, organizations

docker exec firstelement-postgres psql -U firstelement -d firstelement -P pager=off -c "
select count(*) as nullable_organization_id
  from pg_attribute a
  join pg_class c on c.oid = a.attrelid
  join pg_namespace n on n.oid = c.relnamespace
 where n.nspname='public' and c.relkind='r'
   and a.attname='organizationId' and not a.attnotnull;"
# expect 0
```

---

## 4. Testcontainers fixture (no dev database touched)

```powershell
.\gradlew.bat :backend:database:test --rerun-tasks --no-build-cache --no-daemon --console=plain
```

Twelve scenarios against a real `pgvector/pgvector:pg16` container:

1. **fresh database accepts the baseline** — 45 tables, reconciled enums,
   `AVAILABLE`/`AWAY` absent, `pgcrypto` + `vector`, `uuid` PKs, HNSW index
   created by `V20260814000100`, no duplicate pre-filter index, 62 FKs, and
   the exact tenant/project scope exceptions. Then migrates the rest of the
   chain and asserts the Phase-5a and Phase-6a slices additively: 58 tables,
   113 FKs, 58 PKs, 71 CHECKs, 34 enums, `unique_constraints = 0`, all seven
   Phase-6a enum value lists, the tenant-less allow list, the append-only
   boundary as an exact per-table map, the three composite tenant keys, and the
   three composite tenant foreign keys.
2. **existing legacy database is stamped, not replayed** — schema present,
   `flyway_schema_history` absent, rows present. Asserts `migrationsExecuted
   == 8`, rows preserved, **UUID preserved**, the baseline row present, all
   eight additive versions in history, and **all nine Phase-6a tables present
   and empty** — a Phase-6a migration that backfilled from legacy rows would
   show up there as a non-zero count.
3. **outbox claim** — the Phase-5a claim predicate against real rows: tenant
   scoping, lease, expiry, reaping, retry, and the two-claimer concurrency
   proof.
4. **dead letter and replay** — the terminal states and the audit trail the
   CHECK constraints enforce.
5. **idempotency records** — tenant-scoped uniqueness, in-flight reservation,
   completion.
6. **workflow versioning** — one ACTIVE version per definition (with the partial
   unique index as the backstop against a concurrent double activation),
   activation, an audited rollback naming who / when / where-to, no version ever
   deleted, and tenant-scoped natural-key uniqueness asserted in **both**
   directions (same tenant refused, other tenant allowed).
7. **workflow execution** — an absent named principal is unexpressible,
   `capabilityScope` must be a JSON array, append-only action history carrying
   its inputs, attempt numbering unique per run and re-usable across runs, the
   human-takeover abort pair, and the **three composite tenant foreign keys**
   refused with real cross-tenant data — each paired with a positive control of
   the identical shape, because a negative test that only ever proves refusal
   would pass against a table that rejects everything.
8. **SLA policies and calendars** — three independent clocks, the
   clock-ordering CHECK refused three ways (insert, update, and a next-response
   inversion), the same-day window CHECK refused three ways, minute bounds,
   versioned calendars with one live key and effective dating, holidays, and the
   calendar-tenant FK refused with data.
9. **collision records** — the 5-minute dedup window, **two concurrent writers
   racing one uncommitted first insert** where exactly one wins, and the
   append-only invariants including the abort biconditional.
10. **the claim query is index-supported** — no sequential scan for the tenant
    claim, the org-wide sweep or the reaper.
11. **the repository and packaged copies are byte-identical** — all nine files,
    in both directions, including that the classpath copy contains nothing the
    reviewed copy lacks (which is what catches a classpath-only migration nobody
    in `ops/flyway/sql` ever reviewed).
12. **`V11`'s four read-model plans** are proven index-supported by `EXPLAIN`
    assertions in the fixture.

There is no skip path: Docker unreachable fails the build. A skipped migration
test is not a pass.

> The module pins `testcontainers.version = 1.21.4`. The Spring Boot BOM's
> 1.21.3 cannot negotiate an API version with Docker Desktop 29.x on this host
> and fails with "Could not find a valid Docker environment".

Full build:

```powershell
.\gradlew.bat build --no-daemon --console=plain
```

---

## 5. Backup

```powershell
.\ops\backup-restore\backup.ps1 `
    -Container firstelement-postgres -Database firstelement `
    -OutputDir C:\...\backups\2026-10-04
```

Produces, per database, and is **read-only against the database**:

- `<stamp>_<db>.dump` — `pg_dump -Fc`, the restorable format (parallel,
  selective);
- `<stamp>_<db>.sql` — plain SQL, for grep/diff and portable restore;
- `<stamp>_<db>-globals.sql` — `pg_dumpall --globals-only --no-role-passwords`;
- `manifest.json` — SHA-256, size, server version, and the schema fingerprint
  + enum-contract result captured **at backup time**;
- `verification/` — the full V1–V8 bundle for the same moment.

Phase-2 backup (2026-10-04, exercised):

| Artifact | SHA-256 |
| --- | --- |
| `20261004T005217Z_firstelement.dump` (156 826 bytes) | `eddead1b6a0b082e15b3f5d42263c72b69d886bc90aa6e76f4e218ee2eae153e` |
| `20261004T005217Z_firstelement.sql` (138 858 bytes) | `27e8018ea9ad069f5edf74e675caba075d2f07efc68107b86c25541e04ab9d47` |
| `20261004T005217Z_firstelement-globals.sql` (572 bytes) | `46317885012695c8e2b78aa0d1ab7473a69b45526153da3a66ba2e2b205675f1` |

### Encryption — open operational decision

`PROJECT_REQUIREMENTS §7` requires encrypted backups. `backup.ps1` **does not
encrypt**: a key must never live in the repository or in a script. Until an
operator decision is recorded, encrypt outside the script:

```powershell
# age
age -r ops-backup@firstelement -o dump.dump.age dump.dump
# or gpg
gpg --encrypt --recipient ops-backup@firstelement -o dump.dump.gpg dump.dump
```

Encrypt on the way off-host, or rely on volume/disk encryption at rest, and
record which mechanism is in use. **This is unresolved and owned by the
orchestrator**, not by this phase.

### Never read `.env`

No script here reads `.env`. Connection details come from `-Container`,
`-Database`, `-DbUser` flags or from environment variables the caller already
exported. `backup.ps1` writes `--no-role-passwords` globals only.

---

## 6. Restore rehearsal (restore evidence)

The rehearsal restores into a **throwaway** database and disposes of it. It
never writes to the source.

```powershell
.\ops\backup-restore\restore-rehearsal.ps1 `
    -Dump C:\...\backups\2026-10-04\20261004T005217Z_firstelement.dump `
    -EvidenceDir C:\...\evidence\restore-check `
    -Dispose
```

Safety rails enforced by the script:

- refuses `-TargetDatabase == -SourceDatabase`;
- refuses a target name that does not contain `restore_check` or `rehearsal`
  unless `-ForceRehearsalName` is passed;
- `-Dispose` terminates stragglers and drops the target afterwards.

### Phase-2 result — PASS

```
  dumpSha256              eddead1b6a0b082e15b3f5d42263c72b69d886bc90aa6e76f4e218ee2eae153e
  sourceFingerprint       66eeaadab4ad28a8f8eaf8fe1a0748f9
  restoredFingerprint     66eeaadab4ad28a8f8eaf8fe1a0748f9
  fingerprintMatches      True
  sourceEnumContractOk    True
  restoredEnumContractOk  True
  rowCountsIdentical      True
  identitySample          41/1/4/3/43
```

`identitySample` is
`sessions/organizations/users/conversations/audit_logs` = `41/1/4/3/43`. The
schema fingerprint, the enum contract, and every table's row count matched
exactly, and `firstelement_restore_check` was dropped.

### Manual rehearsal (no script)

```powershell
docker exec firstelement-postgres psql -U firstelement -d firstelement -v ON_ERROR_STOP=1 `
  -c "DROP DATABASE IF EXISTS firstelement_restore_check;" `
  -c "CREATE DATABASE firstelement_restore_check TEMPLATE template0;"
docker cp "<dump>" firstelement-postgres:/tmp/rehearsal.dump
docker exec firstelement-postgres pg_restore -U firstelement -d firstelement_restore_check `
  --no-owner --no-privileges --exit-on-error /tmp/rehearsal.dump
.\ops\flyway\verification\run-verification.ps1 -Database firstelement_restore_check `
  -OutputDir C:\...\evidence\restore-check\restored -ThrowOnViolation
# compare against ...\evidence\restore-check\source\V2_row_counts.txt
docker exec firstelement-postgres psql -U firstelement -d firstelement -v ON_ERROR_STOP=1 -c "
  SELECT pg_terminate_backend(pid) FROM pg_stat_activity
   WHERE datname = 'firstelement_restore_check' AND pid <> pg_backend_pid();
  DROP DATABASE IF EXISTS firstelement_restore_check;"
```

Throwaway database names used in Phase 2 — all dropped afterwards:

| Name | Purpose |
| --- | --- |
| `firstelement_restore_check` | restore rehearsal and B1 backfill rehearsal |
| `firstelement_baseline_check` | proving the baseline reproduces the live schema |

Confirm nothing is left behind:

```powershell
docker exec firstelement-postgres psql -U firstelement -d postgres -Atc "
  select datname from pg_database where datistemplate = false order by 1;"
# expect: firstelement, postgres
```

---

## 7. Rollback and recovery

### 7.1 A migration failed and the transaction rolled back

Every applied migration in this chain is transactional, and none of them
drops, truncates, renames, or rewrites data. PostgreSQL rolls the migration
back atomically; the schema returns to the previous version and
`flyway_schema_history` shows the migration as failed.

```powershell
# what happened
docker exec firstelement-postgres psql -U firstelement -d firstelement -P pager=off -c "
  select installed_rank, version, description, type, success, execution_time
    from flyway_schema_history order by installed_rank;"
docker logs --tail 200 <app-bootstrap process>
```

Recovery: fix the migration in `ops/flyway/sql`, mirror it byte-for-byte to
`backend/database/src/main/resources/db/migration/`, **change its version** (a
failed migration's checksum must never be reused), re-run the verification
bundle, and start the application again.

### 7.2 `V20260814000100` needs undoing

It only creates an index, so the rollback is index-only and data-safe:

```sql
DROP INDEX IF EXISTS document_chunks_embedding_hnsw_idx;
```

Do **not** roll back by dropping `document_chunks`. Before running the DROP,
take a backup (§5) and confirm the index is genuinely the problem. Note the
index build takes a `SHARE` lock that blocks writes to `document_chunks`, so
schedule it.

### 7.3 The baseline was replayed over a live schema (the bad path)

**Symptoms:** `relation "..." already exists`, or rows missing, or
`Successfully applied 2 migrations` on a database that already had tables.

**Recovery:**

```powershell
# 1. stop the application immediately
# 2. prove the damage
.\ops\flyway\verification\run-verification.ps1 `
    -OutputDir C:\...\evidence\post-incident -ThrowOnViolation
# 3. restore into a throwaway database first, verify, then decide
.\ops\backup-restore\restore-rehearsal.ps1 -Dump <last-good.dump> `
    -EvidenceDir C:\...\evidence\recovery -Dispose
```

Because no migration in this chain drops data, restoring the last good backup
into `firstelement` is the only recovery action, and it is itself a decision
that needs orchestrator approval. Restore commands are **not** provided as a
one-liner on purpose: an in-place restore must be a deliberate act with the
incident recorded, not a script.

### 7.4 Restoring the real database (with approval)

Preconditions: application stopped; incident recorded; target fingerprint and
row counts agreed against the backup's `manifest.json`; approval obtained.

```powershell
# 1. rehearse first (mandatory) -- §6
# 2. then, and only then:
docker exec firstelement-postgres psql -U firstelement -d postgres -c "
  SELECT pg_terminate_backend(pid) FROM pg_stat_activity
   WHERE datname = 'firstelement' AND pid <> pg_backend_pid();"
#    drop/recreate `firstelement` is a LAST RESORT and needs explicit approval;
#    prefer pg_restore into a fresh database and a controlled cutover.
docker cp "<dump>" firstelement-postgres:/tmp/restore.dump
docker exec firstelement-postgres pg_restore -U firstelement -d firstelement `
  --no-owner --no-privileges --exit-on-error /tmp/restore.dump
# 3. verify against the backup's own manifest
.\ops\flyway\verification\run-verification.ps1 `
    -OutputDir C:\...\evidence\post-restore -ThrowOnViolation
docker exec firstelement-postgres rm -f /tmp/restore.dump
```

After any restore, re-check that `flyway_schema_history` state is consistent
with the restored schema, then start the application so Flyway applies whatever
additive migrations are outstanding.

### 7.5 B1 backfill rollback

`B1_materialize_ai_run_child_scope.sql` only fills `NULL` columns with values
copied from the parent `ai_runs` row. Reverting is therefore optional — the
derived scope can simply be re-derived by re-running B1. If the columns must
disappear (still `.sql.todo`, so not yet applied anywhere):

```sql
ALTER TABLE ai_run_steps       DROP COLUMN "projectId", DROP COLUMN "organizationId";
ALTER TABLE ai_run_retrievals  DROP COLUMN "projectId", DROP COLUMN "organizationId";
ALTER TABLE ai_run_tool_calls  DROP COLUMN "projectId", DROP COLUMN "organizationId";
DROP INDEX IF EXISTS ai_run_steps_org_project_created_idx;
DROP INDEX IF EXISTS ai_run_retrievals_org_project_created_idx;
DROP INDEX IF EXISTS ai_run_tool_calls_org_project_created_idx;
-- updatedAt is harmless; keep it
```

Never roll back while a Spring writer is emitting AI runs that rely on the new
columns.

---

## 8. Running a backfill

```powershell
.\ops\flyway\backfill\run-backfill.ps1 -Script B1 `
    -Container firstelement-postgres -Database firstelement `
    -BatchSize 500 -MaxBatches 20 `
    -OutputDir C:\...\evidence\backfill-B1 -Armed
```

Properties:

- refuses to run without `-Armed`;
- the SQL has its own second guard (`firstelement.backfill_armed=on`) — an
  unarmed invocation prints `B1 SKIPPED` and changes nothing;
- every `UPDATE` is restricted to rows where the target column `IS NULL`, so a
  rerun is a no-op;
- progress is committed in the **same transaction** as each batch, so an
  interrupted run resumes from the last committed batch with no double
  counting and no lost batch;
- `-MaxBatches` bounds the first run so it is always a probe;
- the verification bundle runs **before and after**, so the row-count delta is
  evidence rather than an assumption. A backfill changes **data**, not schema,
  so the schema fingerprint is expected to be unchanged.

Rejections are **data**, not log lines: rows whose parent `ai_runs` row is
missing land in `backfill_rejections` with a reason instead of being filled
with a guessed tenant.

Read-only audits need no arming:

```powershell
docker exec -i firstelement-postgres psql -U firstelement -d firstelement `
  -v ON_ERROR_STOP=1 -f - < ops\flyway\backfill\B2_scope_audit_report.sql
```

### B1 rehearsal, on a throwaway database only

```powershell
# 1. restore the backup into firstelement_restore_check (see §6)
# 2. apply the rehearsal-only expand DDL and seed rows
Get-Content -Raw ops\backup-restore\rehearsal\expand_ai_run_child_scope.sql |
  docker exec -i firstelement-postgres psql -U firstelement -d firstelement_restore_check -v ON_ERROR_STOP=1 -f -
# 3. run B1 with a tiny batch size so more than one batch is required
.\ops\flyway\backfill\run-backfill.ps1 -Script B1 -Database firstelement_restore_check `
    -BatchSize 2 -MaxBatches 10 -OutputDir C:\...\evidence\backfill-rehearsal -Armed
# 4. acceptance queries
Get-Content -Raw ops\backup-restore\rehearsal\b1_evidence.sql |
  docker exec -i firstelement-postgres psql -U firstelement -d firstelement_restore_check -v ON_ERROR_STOP=1 -f -
# 5. dispose
.\ops\backup-restore\restore-rehearsal.ps1 -Dump <dump> -TargetDatabase firstelement_restore_check `
    -EvidenceDir C:\...\evidence\dispose -Dispose
```

Phase-2 rehearsal result: `B1_REMAINING_TOTAL 0`, `B1_FILLED_TOTAL 8`,
`B1_CROSS_TENANT_* 0`, `B1_REJECTIONS 0`, `B1_RUN_STATUS COMPLETED`,
`B1_BATCHES_RUN 2` (resumed across two batches), rerun after completion
=` UPDATE 0`. The control-plane state is dumped with
`ops/backup-restore/rehearsal/backfill_control_state.sql`.

`ops/backup-restore/rehearsal/*.sql` **must never** be run against
`firstelement`.

---

## 9. Before any new migration

1. `./gradlew build` green, Docker running.
2. `./ops/flyway/verification/run-verification.ps1` → save the pre-run bundle,
   note the fingerprint and every row count.
3. `./ops/backup-restore/backup.ps1` → save the dump, its SHA-256, and the
   manifest.
4. Write the migration as `V<yyyyMMddHHmmss>__<name>.sql` in `ops/flyway/sql/`.
   Additive only: `CREATE TABLE`/`ADD COLUMN ... NULL`/`CREATE INDEX
   IF NOT EXISTS`. No `DROP`, no `RENAME`, no `NOT NULL` on a backfilled
   column in the same migration as the backfill.
5. Every new table gets: `uuid` `id`, `organizationId uuid NOT NULL`,
   `projectId` (nullable only for organisation-wide streams), `createdAt` /
   `updatedAt` `timestamp(3) NOT NULL`, explicit FKs, and indexes led by
   `organizationId` / `projectId`.
6. Mirror the file byte-for-byte into
   `backend/database/src/main/resources/db/migration/`.
7. Dry-run the whole chain on a disposable database (Testcontainers or a
   throwaway database in the dev container) and prove the fingerprint.
8. Rehearse the rollback (§7).
9. Apply in a maintenance window; `flyway_schema_history` is the record.
10. Re-run the verification bundle; diff fingerprints; update this runbook with
    the new version.
11. Delete the promoted `*.sql.todo` draft from `ops/flyway/pending/`. The
    promoted file is the only copy of that decision; a lingering draft invites
    someone to "promote" it a second time under a different version.
12. Name it with **14 digits** (`V<yyyyMMddHHmmss>__name.sql`) and keep it
    numerically greater than `20260814000100` — Flyway sorts versions
    numerically (see §1).

Never regenerate UUIDs or business keys (`ticketNumber`, `cxId`, `tokenHash`).
Never combine a snake_case rename with a runtime migration in the same phase
(ADR-0002).

---

## 9a. Phase 5a addendum — outbox / job / idempotency slice

Promoted in Phase 5a. Rollback and recovery for this slice are documented in
`ops/flyway/rollback/README.md`; the short version:

| Situation | Action |
| --- | --- |
| A Phase-5a migration failed | Nothing to undo — every migration is transactional. Fix the file, **change its version**, re-run. |
| Tables exist, nothing cut over, no rows | `ops/flyway/rollback/undo_phase5a.sql` (armed), then remove the files and `flyway repair`. The script refuses if any table holds a row. |
| Tables exist and rows exist | Do **not** undo. Roll the application back and leave the tables; they are additive and inert to the old runtime. |
| A tenant deletion or purge went wrong | Restore from backup (§5, §7.4). In-place restore is an approved act, not a script. |

Rehearsed on a throwaway database (dropped afterwards): unarmed run is a no-op,
the armed empty run removed the slice, the armed **with-rows** run was refused
with per-table counts, and the fingerprint returned exactly to the
post-`V20260814000100` value `bdf70c7c24dfe23c748281eb6c5c4a03`.

---

## 9b. Phase 6a addendum — workflow / SLA / collision slice

Promoted in Phase 6a. Four additive migrations, one per concern, each with its
own guard and its own undo boundary:

| Version | File | Creates |
| --- | --- | --- |
| `20261005010000` | `V20261005010000__workflow_definitions_and_versions.sql` | `WorkflowVersionState`; `workflow_definitions`, `workflow_versions` |
| `20261005020000` | `V20261005020000__workflow_runs_and_action_results.sql` | `WorkflowRunStatus`, `WorkflowActionOutcome`; `workflow_runs`, `workflow_run_actions` |
| `20261005030000` | `V20261005030000__sla_calendars.sql` | `SlaCalendarState`, `SlaClockType`; `sla_calendars`, `sla_calendar_windows`, `sla_calendar_holidays`, `sla_policies` |
| `20261005040000` | `V20261005040000__collision_records.sql` | `CollisionScope`, `CollisionResolution`; `collision_records` |

Rollback and recovery: `ops/flyway/rollback/README-phase6a.md` (runbook) and
`ops/flyway/rollback/undo_phase6a.sql` (armed mechanism). Canonical statements:
`ops/flyway/reference/workflow-sla-statements.sql`. The short version:

| Situation | Action |
| --- | --- |
| A Phase-6a migration failed | Nothing to undo — every migration is transactional. Fix the file, **change its version**, re-run. `V11` reports `PHASE6A_PARTIAL` if the slice is half-applied. |
| Tables exist, nothing cut over, no rows | `ops/flyway/rollback/undo_phase6a.sql` (armed), then remove the four files and `flyway repair`. The script refuses if any table holds a row. |
| Tables exist and rows exist | Do **not** undo. `workflow_runs` / `workflow_run_actions` are the only record of what automation decided and on what inputs, and `collision_records` is the only record that a human-vs-AI race happened at all. Roll the application back and leave the tables; they are additive and inert to the old runtime. |
| A tenant deletion or purge went wrong | Restore from backup (§5, §7.4). In-place restore is an approved act, not a script. |

Rehearsed on `firstelement_6a_undo`, a throwaway database dropped afterwards:

| Step | Result |
| --- | --- |
| unarmed | `UNDO PHASE6A SKIPPED`, nothing touched |
| armed, slice partially present | **REFUSED**, naming both the present and the absent tables |
| armed, slice non-empty (8 seeded rows) | **REFUSED** with all nine per-table row counts printed first |
| armed + `-v confirm_data_loss=yes` | dropped, `PHASE6A_REMOVED: 9 tables and 7 enums dropped. 49 baseline table(s) intact.` |
| fingerprint after the undo | `f436fca35638e2d02418376333fbf67c` — **exactly** the post-Phase-5a value, i.e. Phase 6a removed its own footprint and nothing else |
| `run-verification.ps1 -ExpectedEnumCount 27 -ExpectedTableCount 49` after the undo | 49 tables, 27 enums, `PHASE6A_NOT_PRESENT` |

Five defects were found by this rehearsal and are recorded because each is the
kind that only a real database finds: the undo script's arming flag was read with
`current_setting()` when `-v` defines a *psql variable* (so the script reported
SKIPPED even when armed, and its later guards never ran); psql does not
substitute `:variables` inside a dollar-quoted body; the row-count message dropped
its first entry to `coalesce(NULL || …, '')`; the circular
definition↔version foreign-key pair made the table drop order unsatisfiable; and
an **unquoted** constraint name (`workflow_definitions_activeVersionId_fkey`)
folded to lower case, matched nothing and printed "does not exist, skipping" —
the same bug class as the Phase-5a unquoted `DROP TYPE`, now prevented in this
file by quoting and by a post-condition that fails loudly instead.

---

## 9c. Phase 7–10a addendum — audit, reports, export, usage, finance, AI policy

Promoted in Phase 7–10a. All seven remaining `*.sql.todo` drafts became **eight**
migrations; `ops/flyway/pending/` is empty. Rollback and recovery:
`ops/flyway/rollback/README-phase7-10a.md` (runbook) and
`ops/flyway/rollback/undo_phase7_10a.sql` (armed mechanism).

| Version | File | Creates |
| --- | --- | --- |
| `20261005050000` | `…__audit_streams.sql` | 6 enums; `retention_classes`, `legal_holds`, `legal_hold_events`, `privacy_deletion_requests`, `privacy_deletion_targets`, `admin_audit_events`, `security_events`, `job_integration_events`; 3 composite tenant keys on `users`, `outbox_events`, `job_attempts` |
| `20261005060000` | `…__report_definitions.sql` | 2 enums; `report_definitions`, `report_versions`, `document_templates`, `document_template_versions`; 4 validator functions |
| `20261005070000` | `…__export_jobs.sql` | 4 enums; `export_jobs`, `export_job_attempts`, `export_download_events`, `export_deletion_events` |
| `20261005080000` | `…__usage_ledger.sql` | 4 enums; `usage_events` (cost only) |
| `20261005090000` | `…__finance_rate_cards_periods.sql` | 5 enums; `rate_cards`, `rate_card_versions`, `rate_card_version_lines`, `finance_periods`, `finance_period_lock_events`, `usage_rollup_snapshots`, `finance_adjustments`, `finance_reconciliation_runs`, `finance_reconciliation_lines`; 3 functions, 8 triggers |
| `20261005100000` | `…__ai_policy_and_governance.sql` | 4 enums; 11 AI tables including `ai_tool_registry` (deny by **default expression**) and `ai_approvals` |
| `20261005110000` | `…__ai_run_child_tenant_scope_columns.sql` | **EXPAND**: `organizationId` + `projectId` on `ai_run_steps`, `ai_run_retrievals`, `ai_run_tool_calls` — nullable |
| `20261005120000` | `…__ai_run_child_tenant_scope_indexes.sql` | 3 plain `CREATE INDEX` on those columns (see below) |

### The `CREATE INDEX CONCURRENTLY` finding — read before changing that file

ADR-0005 §3 asked for `CREATE INDEX CONCURRENTLY` on the three `ai_run_*` child
tables. **It was tried three ways and all three are recorded in the migration
header.** The summary, because it is the expensive part:

| Attempt | Result |
| --- | --- |
| One migration containing both the `ADD COLUMN` and the `CONCURRENTLY` statements, plus a `V20261005110000….conf` sidecar saying `executeInTransaction=false` | `FlywayException: Detected both transactional and non-transactional statements within the same migration (even though mixed is false)` |
| The same, with `executeInTransaction=mixed` | Identical failure |
| **Split** into two versions (columns in `…5110000`, indexes in `…5120000`) | Flyway **accepted** it and logged `[non-transactional]` — and then **hung indefinitely** |

Two root causes, both verified rather than guessed:

1. **`flyway-core` 11.7.2 Community has no per-script `executeInTransaction`
   override.** The `.conf` sidecar is a Teams/Enterprise feature; `SqlMigrationResolver`
   never reads it, and the resolved jar contains no script-configuration class at
   all. So the parser sees the global default and refuses a mixed script.
2. **The split version hangs** because Flyway's PostgreSQL adapter takes its
   schema-history advisory lock on a **separate connection**
   (`PostgreSQLAdvisoryLockTemplate.execute` → `PostgreSQLConnection.lock`), and
   with the default `flyway.postgresql.transactionalLock=true` that lock is held
   **inside an open transaction** on the lock connection. `CREATE INDEX CONCURRENTLY`
   waits for every transaction older than its snapshot, so it waits forever for
   Flyway's own lock transaction. Confirmed with a `jcmd Thread.print` of the test
   worker parked in `PgStatement.execute`. This is a Flyway/PostgreSQL interaction,
   not a schema problem, and no script content works around it.

**Resolution shipped:** plain `CREATE INDEX` on `…5120000`, which is ADR-0005 §3's
own listed alternative ("drop `CONCURRENTLY` and accept a SHARE lock during a
maintenance window"). `CREATE INDEX` takes SHARE — it blocks writes, not reads,
for the duration of the build, and on empty tables that is milliseconds.

**Two real fixes remain open, and the second is a decision for the orchestrator,
not for this phase:**

| Fix | Effect | Cost |
| --- | --- | --- |
| `spring.flyway.postgresql.transactional-lock=false` (a Spring datasource property owned by the **application** module, not the schema) | Flyway takes the advisory lock with a session-level `pg_advisory_lock` in autocommit, so no long-running transaction exists to block a concurrent build. `…5120000`'s three statements may become `CONCURRENTLY` again | applies to the whole chain, so it must be evaluated against every migration, not just this one |
| keep plain `CREATE INDEX` and schedule in a maintenance window | nothing changes | the window has to actually be scheduled |

`FlywayBaselineAcceptanceTest` asserts the header, asserts the executed body
contains no `CONCURRENTLY`, and asserts **no `.conf` file exists anywhere** in
either chain directory. So the decision cannot be reversed silently: if the
property is set and the statements go back to `CONCURRENTLY`, the fixture fails
on the "no `CONCURRENTLY`" check, which is the intended direction of travel.

### The EXPAND/CONTRACT position on `ai_run_*`

`…5110000` adds `organizationId` and `projectId` to `ai_run_steps`,
`ai_run_retrievals` and `ai_run_tool_calls` as **NULLABLE** columns and nothing
else. This is step 1 of expand/contract and it is deliberately incomplete:

- `NOT NULL` here would be refused against the existing rows and belongs in a
  **later** migration, after the residual `NULL` count has been proven zero.
- the backfill belongs to the owning phase's B-script, as `job_attempts` did in
  Phase 5a (§8).
- the tenant filter is only guaranteed once the columns are `NOT NULL`; until then
  a query must join to the parent `ai_runs` row. That is a real cost, recorded
  here so nobody discovers it as a slow query in production.

### Other decisions recorded in the migrations

| Decision | Where it lives | Why |
| --- | --- | --- |
| Four **separate** audit tables, not one partitioned `audit_events` with a `stream` discriminator | `…05000000` header | a discriminator is not a boundary: one forgotten `where stream = 'SECURITY'` hands a finance manager every login |
| `usage_events` has **no** revenue / billable / margin / price / markup column and no `rateCardVersionId` | `…05000000`… `…05080000` guard `DO` block, which **fails the migration** if any derived-money column ever appears | revenue is derived once, on `usage_rollup_snapshots`, against a pinned `markupRate`; a ledger that also stores the answer cannot be reconciled |
| `usage_rollup_snapshots` stores **both** `rateCardVersionId` and the pinned `markupRate`, with `CHECK revenueAmount = round(costAmount * markupRate, 6)` | `…05090000` | the derivation is checkable in the database, so a hand-edited snapshot is refused |
| Append-only enforced by **trigger**: `public.firstelement_append_only()` on five finance tables, and a **narrower** `public.firstelement_rate_card_version_guard()` on `rate_card_versions` | `…05090000` | the tables that carry money are a boundary, not a convention. `rate_card_versions` needs the narrower guard because it has one legitimate UPDATE — see the row below. `legal_hold_events`, `export_*_events` and `privacy_deletion_targets` are append-only by **shape** (no `updatedAt`) only — recorded as Phase-8 debt |
| `rate_card_versions` accepts exactly **one** UPDATE: `ACTIVE → RETIRED`, fully attributed, successor named, and no pricing column moved | `…05090000` | the first version attached the blanket append-only trigger here, which the fixture caught: it refuses every UPDATE, so `RETIRED` was unreachable and `rate_card_versions_retired_check` was **dead code no application could satisfy**. "Never edited" means "never repriced" — a version whose markup moved would silently invalidate every rollup that pinned it. `DELETE` is refused outright |
| `UsageDimension` has **no `UNPRICED`** member | `…05080000` guard | unpricedness is a property of the rollup, not a kind of usage |
| Deny-by-default is a **default expression** (`isEnabled DEFAULT false`), not a convention | `…05100000` | the fixture asserts the literal expression, so flipping it fails every deployment's assumption at the same instant |
| A tool may not be `ALLOW`ed as `CONSEQUENTIAL` without naming an **approved** record | `…05100000` biconditional | "asking and doing anyway" is the entire distinction the class exists for |
| Timezone validity is checked by a **function**, not a regex | `…05060000` `firstelement_valid_timezone(text)` | `'Asia/Riyaddah'` is a perfectly shaped zone name that does not exist; a regex accepts it and every bucket in the report is then silently wrong until reconciliation |

### Rollback, in the short version

| Situation | Action |
| --- | --- |
| A Phase-7–10a migration failed | Nothing to undo — every migration is transactional. Fix the file, **change its version**, re-run. |
| Tables exist, nothing cut over, no rows | `ops/flyway/rollback/undo_phase7_10a.sql` (armed), then remove the eight files and `flyway repair`. The script refuses if any table holds a row and prints all 37 counts first. |
| Tables exist and rows exist | Do **not** undo. `admin_audit_events`, `security_events`, `usage_rollup_snapshots`, `journal_entries` and the legal-hold tables are the only record of what happened and on whose authority. Roll the application back and leave the tables. |
| A tenant deletion or purge went wrong | Restore from backup (§5, §7.4). In-place restore is an approved act, not a script. |

Rehearsed on `firstelement_p710a_undo2` / `firstelement_p710a_undo3`, throwaway
databases dropped afterwards:

| Step | Result |
| --- | --- |
| unarmed | clean skip, exit 0, nothing touched |
| armed, seeded synthetic rows, **no** `confirm_data_loss` | **REFUSED**, all 37 per-table counts printed first, psql exit **3**, and **95 tables still present** afterwards |
| armed + `-v confirm_data_loss=yes`, seeded | `PHASE7-10A_REMOVED`, exit 0 |
| armed + `-v confirm_data_loss=yes`, empty slice | `PHASE7-10A_REMOVED`, exit 0 |
| fingerprint after the undo, **empty** path | `927e09829db8f1b38d4279f920d5b422` — **exactly** the post-Phase-6a value |
| fingerprint after the undo, **seeded** path | `927e09829db8f1b38d4279f920d5b422` — the same, so the data-loss path removed no more and no less |
| `run-verification.ps1 -ExpectedEnumCount 55 -ExpectedTableCount 95 -ExpectedCheckCount 402` on the full chain | `schema fingerprint: 2ee0ecd601f00938709f538714509b86` |

> **The undo had a silent-continue bug, and it is the most important line in
> this section.** Measured on this host: armed, with rows, **without**
> `confirm_data_loss`, the script printed
> `UNDO PHASE7-10A REFUSED: the slice holds rows (see the counts printed above)`,
> raised it as an ERROR — and then **dropped all 37 tables anyway**, finishing
> with `PHASE7-10A_REMOVED` and an overall exit code of 1.
>
> The cause is psql, not the guards: without `ON_ERROR_STOP` a statement that
> raises is reported and then the **next statement runs**. Every guard in this
> file is a `RAISE EXCEPTION` inside a `DO` block, so every guard was, on its
> own, only a message. A refusal that prints correctly and changes nothing is
> worse than no refusal at all, because the operator reads it as protection.
>
> Two independent defences are now in the script: `\set ON_ERROR_STOP on`, and
> the destructive section is gated on a psql variable that the guards themselves
> set via `\gset`, so the drops are unreachable unless every guard ran to
> completion. **If you copy this script's structure into another undo, copy both.**

### Rehearsing the chain outside Flyway

`ops/flyway/verification/rehearsal-chain.ps1` applies the chain one file per
`psql` session on a throwaway database and **refuses** `-Database firstelement`.
Use it when the question is "does this SQL apply", rather than "does Flyway apply
it". One warning, learned the hard way: **passing several `-f` arguments to a
single `psql` silently stops after the fourth file and still exits 0**, so a
chain rehearsal built that way proves far less than it appears to. The script
uses one session per file for exactly that reason.

---

## 10. Known gaps in this runbook

1. **No restore into the real database is scripted**, by design. It must be a
   deliberate, approved act (§7.4).
2. **Backup encryption is unresolved** (§5).
3. **No CI pipeline** — the quality agent owns `.github`. Until then, the
   `run-verification.ps1 -ThrowOnViolation` exit code has to be checked by
   hand.
4. **`CREATE INDEX CONCURRENTLY` vs maintenance window** — **decided in Phase
   7–10a by measurement, for `V20261005120000` only.** `CONCURRENTLY` cannot run
   inside a Flyway transaction on 11.7.2 Community, and when split into its own
   version it **hangs** on Flyway's own advisory-lock transaction. Plain
   `CREATE INDEX` was shipped; the full finding and both real fixes are in **§9c**.
   Do not silently re-introduce `CONCURRENTLY`, and do not assume the finding
   generalises to a future Flyway version — it was measured on 11.7.2 / PostgreSQL
   16.15 and should be re-measured if either moves.
5. **No PostgreSQL row-level security.** `FR-1.6` makes RLS optional
   defence-in-depth; application policy remains mandatory.
6. **`DATABASE_URL` → JDBC translation is manual** (§2). It should be a
   configuration feature, not an operator step.
7. **Retention for the Phase-5a tables is undefined.** Nothing deletes outbox,
   job-attempt, dead-letter, or idempotency rows; `job_attempts` is the
   highest-write table in the system. A retention decision is owed before the
   first production cutover.
8. **Two sources of truth for reply idempotency keys.** Phase 4 stores a reply's
   `Idempotency-Key` in `messages.metadata`; Phase 5a added the general
   `idempotency_records` table. The ownership split is recorded in
   `docs/migration/adr/0006-phase4-5-open-decisions.md`: Phase 5b owns delivery
   and job idempotency through `idempotency_records`, and Phase 4's message-metadata
   record stays as the legacy-compatible path until the CRM module migrates to
   the general store behind a feature flag.

---

## 9d. Phase 10b addendum — five schema defects

Promoted in Phase 10b. Five additive migrations, one per confirmed defect, plus
`V15_schema_remediation_slice.sql` in the verification bundle. The full phase
report is `docs/migration/phases/phase-10b-schema-remediation.md`; the rollback
and recovery runbook is `ops/flyway/rollback/README-phase10b.md`; the armed
mechanism is `ops/flyway/rollback/undo_phase10b.sql`; and the end-to-end
rehearsal is `ops/flyway/verification/rehearse-phase10b.ps1`.

| Version | File | Fixes | Effect |
| --- | --- | --- | --- |
| `20261006100000` | `V20261006100000__ai_approval_decision_biconditional_repair.sql` | D1 | replaces `ai_approvals_approval_check`; repairs from `ai_approval_decisions`; **refuses** what it cannot repair |
| `20261006110000` | `V20261006110000__document_chunks_document_tenant_fkey.sql` | D2 | adds `document_chunks_document_tenant_fkey` + `documents_id_organizationId_key`; keeps the single-column FK |
| `20261006120000` | `V20261006120000__api_keys_and_service_principals.sql` | D3 | `service_principals`, `api_keys`, 2 enums, a KDF-digest guard trigger, a lifecycle-audit trigger |
| `20261006130000` | `V20261006130000__legal_holds_scope_pair_widening.sql` | D4 | widens `legal_holds_scope_pair_check` so all four `LegalHoldScopeType` members are placeable |
| `20261006140000` | `V20261006140000__privacy_deletion_subject_reference.sql` | D5 | adds `subjectCustomerId` + composite FK + index + subject check; relaxes `subjectUserId` |

### Three rules this batch follows that the earlier phases did not have to

1. **A constraint is replaced, never amended.** `V20260814000000` /
   `V20261005050000` / `V20261005100000` are read exactly as the runbook already
   records their digests. Two of the five migrations DROP and re-ADD a `CHECK`
   constraint, which is the only way to change its expression in PostgreSQL, and
   the undo restores the original text byte for byte. No table, column, index or
   type is dropped anywhere in the batch.
2. **A repaired row is repaired from evidence or not at all.** D1 reads
   `ai_approval_decisions` — the append-only table that exists precisely so the
   decision record cannot be lost — and refuses on anything it cannot recover.
   It never deletes, never fabricates a decider, and never relaxes another
   constraint to make a count reach zero.
3. **A refusal must be a refusal.** `undo_phase10b.sql` gates its destructive
   section on a psql variable the guards set, asserts on the STATE after every
   action, and every rehearsal row in `rehearse-phase10b.ps1` checks the object
   counts and the psql exit code rather than a printed word.

### Undo ordering — inside out

```powershell
# 1. Phase 10b first
Get-Content -Raw ops\flyway\rollback\undo_phase10b.sql |
  docker exec -i firstelement-postgres psql -U firstelement -d <throwaway> `
    -v ON_ERROR_STOP=1 -v firstelement_undo_armed=on -f -
# 2. THEN Phase 7-10a. Never the other way round: the Phase-7-10a undo drops
#    ai_approvals, legal_holds and privacy_deletion_requests, and this batch's
#    objects on them go with it -- at which point undo_phase10b.sql refuses on
#    its partial-batch guard, which is the intended behaviour and not a bug.
Get-Content -Raw ops\flyway\rollback\undo_phase7_10a.sql | ...
```

### Evidence

```powershell
# the whole rehearsal, on throwaway databases, then dropped
.\ops\flyway\verification\rehearse-phase10b.ps1 -ThrowOnViolation
# expect: All 33 state assertions PASS.

# the chain on a throwaway, then the bundle against the measured numbers
.\ops\flyway\verification\rehearsal-chain.ps1 -Database firstelement_p10b_check -Keep
.\ops\flyway\verification\run-verification.ps1 -Database firstelement_p10b_check `
    -ExpectedEnumCount 57 -ExpectedTableCount 97 -ExpectedCheckCount 421 `
    -OutputDir C:\...\evidence\10b -ThrowOnViolation
# expect: fingerprint 3a62e8c30d6f0043ca86a96becb2f7ba, all three counts asserted
```

---

## 9e. Phase 14 addendum — the customer reference namespace (P0, ADR-0014)

`V20261007100000` + `V20261007110000`. The defect was a **capacity limit wearing the
costume of a race**: `customers."cxId"` was globally unique while the allocator was
a project-scoped `count(*)` with five walk-forward retries, so five references were
consumed deployment-wide and the sixth project's first inbound message answered 500,
permanently. The P0 is recorded in ADR-0014 and the phase report is
`docs/migration/phases/phase-14-customer-cxid-namespace.md`.

### What the two files do, in the order they do it

1. **`…100000`** creates `customer_cx_sequences` (one row per `organizationId`, one
   `bigint` named `lastAllocatedCxSequence`, `CHECK` 0–999999, a UNIQUE INDEX arbiter
   on `organizationId` **alone**) and `firstelement_next_customer_cx_id(uuid)`, which
   allocates in **one statement**:

   ```sql
   INSERT INTO customer_cx_sequences AS s (...)
   VALUES (gen_random_uuid(), p_organization_id, 0, ...)
   ON CONFLICT ("organizationId") DO UPDATE
       SET "lastAllocatedCxSequence" = s."lastAllocatedCxSequence" + 1
   RETURNING "lastAllocatedCxSequence";
   ```

   There is **no `count(*)` anywhere** and no read-then-write. The function is marked
   **VOLATILE**: marked `STABLE` or `IMMUTABLE`, a planner may collapse two calls in
   one statement into one and hand the same reference to two customers — the defect
   re-entering through the function header, which `V16` §4 asserts against.

2. **`…110000`** censuses the existing references and refuses anything off-format;
   **backfills the counter from `max("cxId")` per organization**; asserts that no
   organization with customers is left without a counter and that no counter sits
   below its live maximum; checks `pg_constraint.conindid` for dependents; then
   **drops the global `customers_cxId_key`** and adds
   `customers_organizationId_cxId_key UNIQUE ("organizationId","cxId")`.

### Why the backfill comes first — and the one thing it cannot do

While the global key holds, `max("cxId")` is a true high-water mark over everything
storable. Once it is gone, values at or below it become insertable, so a legacy
`count(*)` writer could insert a reference the counter is about to hand out again.

It cannot be stronger than that. `max("cxId")` is read from rows that **still
exist**; a customer that once held a reference and has since been deleted leaves no
trace in this schema. So the migration guarantees what is checkable — no **live**
reference is reissued — and **reports** `gaps_below_each_tenant_maximum` on every
run rather than asserting it to be zero. Measured **0** on the verified live
database. Recorded as **F-14-1**, with the fix being an append-only ledger of
issued references.

### Three PostgreSQL traps the rehearsal found, none visible from reading the SQL

| Trap | Symptom | Rule |
| --- | --- | --- |
| `substr("cxId", 3)` reads **seven** characters | `#33` occupies positions 1–3, so the six digits start at **4**. The offset-3 version multiplied every parsed value by ten and the migration refused with `violates check constraint customer_cx_sequences_sequence_range_check` | an off-by-one digit always pushes the parse above 1000000, so the `CHECK` fires for **every** row. A silent misparse became a refused migration |
| `to_regclass('public.customers_cxId_key')` returns **NULL** for a name that exists | the `regclass` input parser applies SQL identifier rules, downcasing the unquoted mixed-case name to `customers_cxid_key`. The step-4 guard then **refused with a plausible message on a database where the index was plainly present** | every mixed-case name in a `regclass` argument needs inner double quotes. ADR-0002's explicit-mapping rule arriving through a function argument |
| `pg_get_indexdef(idx, n, true)` **quotes the column** and returns `''` for a missing position | it renders `"organizationId"`, so an unquoted comparison never matches; and position 3 of a two-column index is `''`, not `NULL`, so `IS NULL` can never fire while `IS NOT NULL` fires on a correct index | take arity from `pg_index.indnkeyatts` and identity from the quoted rendered form |

A fourth was found in the **undo** script's own post-condition, which expected
`USING "btree" ("cxId")`; PostgreSQL renders `btree` **unquoted**, so the check
matched nothing and the undo reported `INCOMPLETE` having done its work correctly.

### Rollback, in the short version

`ops/flyway/rollback/undo_phase14.sql`. **Inside out: it runs FIRST**, before
`undo_phase10b.sql` and `undo_phase7-10a.sql`.

```powershell
# 1. ARM AND RUN. Two guards, both measured rather than assumed:
#      a counter ABOVE its tenant's live maximum -> references were issued and their
#        holders deleted, so removing it would let every one of them be reissued;
#      two tenants holding the SAME reference value -> customers_cxId_key cannot be
#        recreated at all.
docker exec -i firstelement-postgres psql -U firstelement -d firstelement `
    -v firstelement_undo_armed=on -f - < ops/flyway/rollback/undo_phase14.sql
# expect on success: PHASE14_REMOVED, 97 tables / 57 enums / 421 CHECKs / 222 FKs / 97 PKs
# expect on refusal: a non-zero exit code, PHASE14_UNDO_REFUSED, and NOTHING changed

# 2. THE CHAIN RE-APPLIES ON TOP OF ITS OWN UNDO
.\ops\flyway\verification\rehearsal-chain.ps1 -Database <throwaway> -Keep
```

Undoing **re-introduces the CX-1 capacity limit on purpose** — the alternative, a
cutover blocked on a schema mistake, is worse — so the script refuses on a database
that has actually been used, and names the colliding tenants.

### Evidence

```powershell
# the eight negative controls, one fresh throwaway each, then all dropped
.\ops\flyway\verification\rehearse-phase14.ps1
# expect: positive control exit 0; NEG-1..NEG-8 every one non-zero with the
#         INTENDED assertion, and the runner says so per control

# the four undo states
.\ops\flyway\verification\rehearse-phase14-undo.ps1
# expect: 63 assertions PASS, 0 FAIL

# the chain on a throwaway, then the bundle against the measured numbers
.\ops\flyway\verification\rehearsal-chain.ps1 -Database firstelement_p14_chain -Keep
.\ops\flyway\verification\run-verification.ps1 -Database firstelement_p14_chain `
    -ExpectedEnumCount 57 -ExpectedTableCount 98 -ExpectedCheckCount 422 `
    -OutputDir C:\...\evidence\14 -ThrowOnViolation
# expect: fingerprint bf31e9f102be19a6c373eb353b2d2131, all three counts asserted

# and the pre-boot profile the quality gate runs every day
.\ops\quality\checks\verify-schema-state.ps1 -Container firstelement-postgres `
    -Database firstelement -OutputDir C:\...\evidence\14-preboot -ThrowOnViolation
# expect: verification bundle PASSED under profile 'preBoot', and V16 printing
#         PHASE14_NOT_PRESENT rather than failing
```
