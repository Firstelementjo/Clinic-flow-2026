# Phase 7-10a — rollback and recovery for the audit / report / export / usage-ledger / finance / AI slice

Scope: the eight migrations promoted in Phase 7-10a.

| Version | File | Creates |
| --- | --- | --- |
| `20261005050000` | `V20261005050000__audit_streams.sql` | 6 enums; `retention_classes`, `legal_holds`, `legal_hold_events`, `privacy_deletion_requests`, `privacy_deletion_targets`, `admin_audit_events`, `security_events`, `job_integration_events`; 3 composite tenant keys on existing tables |
| `20261005060000` | `V20261005060000__report_definitions.sql` | `ReportStatus`, `DocumentTemplateStatus`; `report_definitions`, `report_versions`, `document_templates`, `document_template_versions`; 4 validator functions |
| `20261005070000` | `V20261005070000__export_jobs.sql` | 4 enums; `export_jobs`, `export_job_attempts`, `export_download_events`, `export_deletion_events` |
| `20261005080000` | `V20261005080000__usage_ledger.sql` | `UsageDimension`; `usage_events` (APPEND-ONLY, no revenue column) |
| `20261005090000` | `V20261005090000__finance_rate_cards_periods.sql` | 5 enums; `rate_cards`, `rate_card_versions`, `rate_card_version_lines`, `finance_periods`, `finance_period_lock_events`, `usage_rollup_snapshots`, `finance_adjustments`, `finance_reconciliation_runs`, `finance_reconciliation_lines`; 3 functions, 5 blanket append-only triggers, 1 narrow `rate_card_versions` guard, 1 locked-period trigger |
| `20261005100000` | `V20261005100000__ai_policy_and_governance.sql` | 4 enums; 11 AI policy/governance tables; 3 composite tenant keys on the existing agent model |
| `20261005110000` | `V20261005110000__ai_run_child_tenant_scope_columns.sql` | nullable `organizationId` / `projectId` / `updatedAt` on `ai_run_steps`, `ai_run_retrievals`, `ai_run_tool_calls` (TRANSACTIONAL) |
| `20261005120000` | `V20261005120000__ai_run_child_tenant_scope_indexes.sql` | 3 tenant-led indexes on those same three tables |

This is the Phase-7-10a addendum to `docs/migration/flyway-runbook.md`, which
carries section 9c as its mirror. The runbook's expected numbers are a living
document and the orchestrator owns the merge; nothing here contradicts it.

**The state of `firstelement` at the end of Phase 7-10a:** unchanged. 45
application tables, 0 `flyway_schema_history` rows, fingerprint
`66eeaadab4ad28a8f8eaf8fe1a0748f9`. No Flyway stamp, no DDL, no DML, no row
touched. The first Flyway-enabled boot remains the orchestrator's scheduled,
backed-up act.

---

## 1. The four outcomes

| Situation | Action | Data risk |
| --- | --- | --- |
| A Phase-7-10a migration **failed** | Nothing. Every migration is transactional; PostgreSQL rolled it back atomically. Fix the file, **change its version**, re-run. | none |
| The tables exist but **nothing has been cut over** and no rows were written | `undo_phase7_10a.sql` (armed once). Then remove the eight files and `flyway repair`. The script refuses if any table holds a row. | none — proven empty by the script's own refusal |
| The tables exist **and rows exist** | Do **not** undo. Roll the application back and leave the tables. | none |
| A tenant deletion or a purge went wrong | Restore from backup (section 4). In-place restore is an approved act, not a script. | as per the incident |

The rule behind the table is the Phase-5a and Phase-6a rule, and Phase 7-10a
raises the stakes considerably: **`usage_events` is the immutable money ledger,
`admin_audit_events` / `security_events` are the audit and denial record,
`usage_rollup_snapshots` is the only record of what a closed period was worth,
and `ai_tool_executions` is the only record that a disabled tool was refused.**
None of them is derivable from another table. Dropping them to tidy up a schema is
the failure mode this runbook exists to prevent, and the undo's data gate exists
to make that judgement by measurement.

### The one migration whose lock behaviour an operator must know about

`V20261005120000` builds the three AI-trace tenant indexes with a plain
`CREATE INDEX`, which takes a SHARE lock (blocks writes, not reads) for the
duration. The lock is milliseconds on an empty table, and at the cutover these
three tables are empty. ADR-0005 section 3 asked for `CREATE INDEX CONCURRENTLY`
and it is **not** used; the migration header records all three attempts that were
measured, why Flyway 11.7.2 Community cannot do it (no per-script
`executeInTransaction` override, and the schema-history advisory lock is held
inside an open transaction that a concurrent build waits on forever), and the two
real fixes — `spring.flyway.postgresql.transactional-lock=false`, or a
maintenance window. `FlywayBaselineAcceptanceTest` asserts the header still says
all of that, so the decision cannot be reversed silently.

---

## 2. Migration failed at the boot

```powershell
docker exec firstelement-postgres psql -U firstelement -d firstelement -P pager=off -c "
  select installed_rank, version, description, type, success, execution_time
    from flyway_schema_history order by installed_rank;"
```

Then:

1. The guard at the end of each migration fails loudly on purpose
   (`V20261005050000 failed: ...`). Read the message: it names exactly which
   index, enum, nullability, UTC-default, tenant-scope or CHECK invariant was not
   satisfied. The guard running means the table may exist — the transaction rolled
   back, so it does not.
2. Fix the file in `ops/flyway/sql`.
3. **Change the version.** A checksum is never reused.
4. Mirror it byte-for-byte to
   `backend/database/src/main/resources/db/migration/`.
5. Re-run the fixture: `.\gradlew.bat :backend:database:test --rerun-tasks
   --no-build-cache --no-daemon --console=plain`.
6. Re-run the evidence bundle (section 3) and start the application again.

**Partial-slice detection.** `undo_phase7_10a.sql` refuses to run when any of
the 37 tables is missing, naming both the present and the absent lists, because a
half-applied slice means a migration failed mid-chain and dropping half of it is
not "undoing" it. `V12` reports `PHASE710A_PARTIAL` for the same reason.

---

## 3. Evidence before and after (read-only)

```powershell
# BEFORE the first Flyway boot (this is what Phase 7-10a left behind)
.\ops\flyway\verification\run-verification.ps1 `
    -Container firstelement-postgres -Database firstelement `
    -OutputDir C:\...\evidence\2026-10-05-pre-phase7-10a-boot -ThrowOnViolation
# -> fingerprint 66eeaadab4ad28a8f8eaf8fe1a0748f9, enums 23|1|1

# AFTER the boot (95 tables, 55 enums, 402 CHECKs)
.\ops\flyway\verification\run-verification.ps1 `
    -Container firstelement-postgres -Database firstelement `
    -ExpectedEnumCount 55 -ExpectedTableCount 95 -ExpectedCheckCount 402 `
    -OutputDir C:\...\evidence\2026-10-05-post-phase7-10a-boot -ThrowOnViolation
```

`V12_admin_export_report_slice.sql`, `V13_usage_finance_slice.sql` and
`V14_ai_policy_slice.sql` are part of that bundle, are read-only, and report
`PHASE710A_NOT_PRESENT` on a pre-boot database, so the same bundle is valid on
both sides of the cutover.

**Reading `V13` is not optional after the boot.** It reports, with data, the two
things ADR 0007 makes non-negotiable: whether any rollup snapshot's revenue
disagrees with its own pinned inputs, and whether unpriced usage is a first-class
view or has been folded into zero.

---

## 4. Backup and restore

```powershell
# Backup (read-only against the database)
.\ops\backup-restore\backup.ps1 -Container firstelement-postgres `
    -Database firstelement -OutputDir C:\...\backups\2026-10-05-phase7-10a

# Prove the backup restores, into a THROWAWAY database that is dropped again
.\ops\backup-restore\restore-rehearsal.ps1 `
    -Dump C:\...\backups\2026-10-05-phase7-10a\<stamp>_firstelement.dump `
    -EvidenceDir C:\...\evidence\restore-check-phase7-10a -Dispose
```

A Phase-7-10a backup must be taken **after** the boot if the intent is to protect
finance, audit or AI history. A backup taken before the boot contains none of
them, which is the correct and complete state for that moment — and a dump with no
`flyway_schema_history` is itself the evidence that the boot had not happened.

Restoring into the real database is `docs/migration/flyway-runbook.md` section
7.4 and is deliberately not scripted here.

---

## 5. What the runbook now says

The runbook update is part of this phase (section 1 chain + SHA tables, section 3
expected-counts, section 9c), so unlike Phase 5a there is nothing left for the
orchestrator to merge from here. The numbers are restated in section 7 below.

---

## 6. Undoing a created-but-unused slice

```powershell
# 1. What would be dropped, and how many rows would be lost. Read it first.
#    Unarmed: prints UNDO PHASE7-10A SKIPPED and touches NOTHING.
Get-Content -Raw ops\flyway\rollback\undo_phase7_10a.sql |
  docker exec -i firstelement-postgres psql -U firstelement -d <throwaway> `
    -v ON_ERROR_STOP=1 -v firstelement_undo_armed=on -f -

# 2. On a throwaway database, actually run it, then prove the fingerprint
#    returned to the POST-PHASE-6a value. Phase 7-10a measured this:
#      before undo : 477ea17d4b1907f01337c4c3ee911a28
#      after  undo : 927e09829db8f1b38d4279f920d5b422  (exact equality)

# 3. Only then on the real database, and only if the slice is empty. The script
#    REFUSES if any of the 37 tables holds a row unless -v confirm_data_loss=yes,
#    and it prints the per-table row counts BEFORE it refuses, so the refusal is
#    informed.

# 4. Remove the eight migrations from ops/flyway/sql AND from
#    backend/database/src/main/resources/db/migration/, then `flyway repair` --
#    the history now records applied migrations whose objects no longer exist.
#    Repair is an operator decision; the script does not do it.
```

The script's own guards, each of which is a real refusal rather than a comment:

| Guard | Why it exists |
| --- | --- |
| `firstelement_undo_armed` | the undo is destructive by construction |
| per-flag `\if` | psql leaves `:'name'` **unsubstituted** when `name` is undefined, so `coalesce(:'confirm_data_loss', 'no')` is a syntax error, not a null. Each flag gets its own `\if`, and the whole body is inside the outer `\if` so an unarmed run is a clean no-op rather than a no-op followed by a syntax error |
| psql `\if` for arming | the Phase-6a undo read the flag with `current_setting()`, which is a **server** GUC, while `-v` defines a **psql** variable — so it printed SKIPPED even when armed and its later guards never ran |
| partial-slice refusal | a half-applied slice means a failed migration, not an unused slice |
| per-table row counts, printed always | the operator must see the data before deciding; `counts` starts at `''` and appends unconditionally, because `coalesce(counts || ..., '')` is exactly the case where coalesce's fallback fires and the first entry disappears |
| `confirm_data_loss=yes` for a non-empty slice | destroying finance, audit and AI evidence is never automatic |
| baseline floor of 45 tables | refuses to run against a partially migrated database |
| foreign-key reference check | an object **outside** the slice pointing into it means reverse order, not a surprise drop |
| post-condition on tables **and** types **and** the nine indexes on pre-existing tables **and** `audit_logs` | see the bugs below, and Phase-6a risk 8 |

**Rehearsed on `firstelement_p710a_undo2` / `firstelement_p710a_undo3`, throwaway
databases dropped afterwards:**

| Step | Result |
| --- | --- |
| unarmed | `UNDO PHASE7-10A SKIPPED`, exit 0, nothing touched |

The undo rehearsal found **five** defects, recorded because each is the kind that
only a real run finds: the two arming bugs above (inherited from Phase 6a and
re-created), `column reference "oid" is ambiguous` in the foreign-key guard, the
fact that a plain `SELECT` naming a dropped table is a **parse-time** error — so
`V12` section 7 and `V14` section 3a had to be rewritten as guarded `DO` blocks
with dynamic SQL, or the bundle would have failed after a *successful* undo
instead of reporting `PHASE710A_NOT_PRESENT` — and the silent-continue below.
| armed, synthetic rows seeded, **no** `confirm_data_loss` | **REFUSED**, all 37 per-table counts printed first, psql exit **3**, **95 tables still present** |
| armed + `-v confirm_data_loss=yes`, seeded | dropped, `PHASE7-10A_REMOVED`, exit 0 |
| armed + `-v confirm_data_loss=yes`, empty slice | dropped, `PHASE7-10A_REMOVED`, exit 0 |
| fingerprint after the undo, **both** paths | `927e09829db8f1b38d4279f920d5b422` — **exactly** the post-Phase-6a value |
| `run-verification.ps1 -ExpectedEnumCount 34 -ExpectedTableCount 58` after the undo | 58 tables, 34 enums, `PHASE710A_NOT_PRESENT` from V12, V13 and V14 |

### The fifth defect, and the one that mattered most

**The undo printed `REFUSED` and dropped the tables anyway.**

Measured on this host: armed, rows present, **no** `confirm_data_loss`. The
script printed

```
NOTICE  UNDO PHASE7-10A row counts before removal: legal_holds=1 …
NOTICE  UNDO PHASE7-10A REFUSED: the slice holds rows (see the counts printed above). …
ERROR   UNDO PHASE7-10A REFUSED: the slice holds rows …
DROP INDEX … DROP TABLE …
NOTICE  PHASE7-10A_REMOVED: 37 tables and 21 enums dropped …
```

with an overall exit code of **1**. The refusal was correct, was printed, and had
**no effect whatsoever**.

The cause is psql's default, not the guards: without `ON_ERROR_STOP`, a statement
that raises is reported and then the **next statement runs**. Every guard in this
script is a `RAISE EXCEPTION` inside a `DO` block, so every guard in this script
was, on its own, only a message. The earlier "REFUSED, all 37 counts printed"
rehearsal row was therefore **not evidence of anything** — it is the row that
should have caught this, and it did not, because the assertion was on the
*message* rather than on the *schema afterwards*.

Two independent defences are now in the script, and both are required:

| Defence | What it stops |
| --- | --- |
| `\set ON_ERROR_STOP on` | a `RAISE` anywhere aborts the script instead of continuing |
| the drop section is gated on a psql variable the guards set via `\gset` | the drops are unreachable unless every guard ran to completion, even if `ON_ERROR_STOP` is somehow lost |

**The rehearsal lesson, and it generalises:** assert on the *state afterwards*,
never on the message. `assert REFUSED` passed while 37 tables were destroyed.
The rehearsal now re-checks the table count after every refusal.

---

## 7. Phase-7-10a additions to the tenant-scope contract

Three deliberate deviations from the Phase-6a shape, each with a reason recorded
in the migration header and asserted by the fixture:

| Object | Decision | Why |
| --- | --- | --- |
| `projectId` on `security_events`, `export_jobs`, `export_job_attempts`, `export_download_events`, `export_deletion_events`, `usage_events`, `usage_rollup_snapshots`, `finance_reconciliation_lines` | **NOT NULL** | each of them is about one project's data, so NULL could only mean "unknown scope" |
| `projectId` on `retention_classes`, `legal_holds`, `privacy_deletion_requests`, `admin_audit_events`, `job_integration_events`, `report_definitions`, `rate_cards`, `finance_periods`, `ai_model_registry`, `ai_tool_registry`, `ai_provenance_records`, `ai_redaction_records` | **nullable** | organisation-wide by design: a tenant's retention window, escalation, report catalogue, rate card, month and provider/tool registry all apply to every project. Per-project copies are how calendars, rate cards and safety settings drift apart |
| `ai_run_steps`, `ai_run_retrievals`, `ai_run_tool_calls` now **carry** nullable `organizationId` / `projectId` | **expanded, not inherited** | the inheritance argument from Phase 2 was sound for a drill-down and unsound for a tenant-filtered query, a deletion sweep and an index-led tenant filter (FR-1.3). The values are derived from `ai_runs` and are **asserted**, not FK-constrained, because an FK would let a writer set a scope that disagrees with its parent |

The tenant-less index list grows by one and only one:
`admin_audit_events_correlationId_idx`. A correlation trace. Correlation ids span
services by construction so the index cannot lead with the tenant; it is a lookup
BY a known id, never a browse path, and it exposes no tenant content. It is
asserted by `V11` section 5a, by `V12` section 5a and by the fixture's allow list,
so a sixth cannot appear by accident.

### New indexes on PRE-EXISTING tables (six, all `IF NOT EXISTS`)

`users_id_organizationId_key`, `outbox_events_id_organizationId_key`,
`job_attempts_id_organizationId_key` (V...0050000) and
`ai_runs_id_organizationId_key`, `ai_agents_id_organizationId_key`,
`ai_agent_versions_id_organizationId_key` (V...0100000). All six exist purely as
composite FOREIGN KEY targets. They are UNIQUE **INDEXES**, not UNIQUE
CONSTRAINTS, so `V4`'s `unique_constraints = 0` invariant survives — the same
technique Phase 6a used for `conversations_id_organizationId_key`, verified
against PostgreSQL 16 before it was relied on. The undo removes all six and its
post-condition checks they are gone, because a fingerprint that does not return
exactly means something was left behind.

### Retention is still undefined for the new tables — and this is now the largest open item

Carried from Phase 5a risk 7 and Phase 6a risk 1, unchanged and much larger.
`usage_events`, `admin_audit_events`, `security_events`,
`job_integration_events`, `ai_provenance_records`, `ai_tool_executions` and
`ai_redaction_records` are all append-only and nothing deletes them.
`usage_events` stores money; `admin_audit_events` and `security_events` are the
audit and denial record. `retention_classes` exists so the windows can be agreed
per tenant, and `legal_holds` exists so **ADR-0008 rule 5** ("a purge job refuses
to run for a tenant under legal hold") is representable — but no purge job is
created by this phase, and **the purge job must ship disabled until the Phase-7
Admin surface exposes hold state**, which is an explicit ADR-0008 condition and
not a nicety.

Also open and NOT decided here: `REVOKE UPDATE` from the application role on the
six trigger-guarded finance tables. The triggers make append-only a schema
property; the privilege-level guarantee is a grant decision about which role
writes those tables, and that belongs to Phase 8. Note that the
`REVOKE` cannot be blanket on `rate_card_versions`, because that table needs its
one legal `ACTIVE → RETIRED` UPDATE — Phase 8 must revoke it and grant the
transition back, or scope the grant to the stored procedure that performs it.