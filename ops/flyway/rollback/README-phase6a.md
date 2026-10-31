# Phase 6a — rollback and recovery for the workflow / SLA / collision slice

Scope: `ops/flyway/sql/V20261005010000__workflow_definitions_and_versions.sql`,
`V20261005020000__workflow_runs_and_action_results.sql`,
`V20261005030000__sla_calendars.sql`, `V20261005040000__collision_records.sql`.
This is the Phase-6a addendum to `docs/migration/flyway-runbook.md`, which
carries §9b as the mirror of §9a. It exists because the runbook's expected
numbers are a living document and the orchestrator owns the merge; nothing here
contradicts it.

**The state of `firstelement` at the end of Phase 6a:** unchanged. 45
application tables, 0 `flyway_schema_history` rows, fingerprint
`66eeaadab4ad28a8f8eaf8fe1a0748f9`. No Flyway stamp, no DDL, no DML, no row
touched. The first Flyway-enabled boot remains the orchestrator's scheduled,
backed-up act.

---

## 1. The four outcomes

| Situation | Action | Data risk |
| --- | --- | --- |
| A Phase-6a migration **failed** | Nothing. Every migration is transactional; PostgreSQL rolled it back atomically. Fix the file, **change its version**, re-run. | none |
| The tables exist but **nothing has been cut over** and no rows were written | `undo_phase6a.sql` (armed once). Then remove the four files and `flyway repair`. | none — proven empty by the script's own refusal |
| The tables exist **and rows exist** | Do **not** undo. Roll the application back to the previous build and leave the tables; they are additive and inert to the old runtime. | none |
| A tenant deletion or a purge went wrong | Restore from backup (§4). In-place restore is an approved act, not a script. | as per the incident |

The rule behind the table is the Phase-5a rule and it is worth restating because
Phase 6a raises the stakes: **the correct response to "this table is wrong" is
almost never to drop it.** `workflow_runs` + `workflow_run_actions` are the
record of every automated decision and its inputs (phase-06 acceptance gate 2),
and `collision_records` is the only evidence that a human-vs-AI race happened at
all — it is not derivable from any other table, because both sides of the race
succeeded. Dropping them to tidy up a schema is the failure mode this runbook
exists to prevent.

---

## 2. Migration failed at the boot

```powershell
docker exec firstelement-postgres psql -U firstelement -d firstelement -P pager=off -c "
  select installed_rank, version, description, type, success, execution_time
    from flyway_schema_history order by installed_rank;"
docker logs --tail 200 <app-bootstrap process>
```

Then:

1. The guard at the end of each Phase-6a migration fails loudly on purpose
   (`V20261005010000 failed: …`). Read the message: it names exactly which
   index, enum, nullability, UTC-default or CHECK invariant was not satisfied.
   The guard running means the table may exist — the transaction rolled back, so
   it does not.
2. Fix the file in `ops/flyway/sql`.
3. **Change the version.** A checksum is never reused.
4. Mirror it byte-for-byte to
   `backend/database/src/main/resources/db/migration/`.
5. Re-run the fixture: `.\gradlew.bat :backend:database:test --rerun-tasks
   --no-build-cache --no-daemon --console=plain`.
6. Re-run the evidence bundle (§3) and start the application again.

**Partial-slice detection.** `undo_phase6a.sql` refuses to run when only some of
the nine tables are present, naming both lists, because a half-applied slice
means a migration failed mid-chain and dropping half of it is not "undoing" it.

---

## 3. Evidence before and after (read-only)

```powershell
# BEFORE the first Flyway boot (this is what Phase 6a left behind)
.\ops\flyway\verification\run-verification.ps1 `
    -Container firstelement-postgres -Database firstelement `
    -OutputDir C:\...\evidence\2026-10-05-pre-phase6a-boot -ThrowOnViolation
# -> fingerprint 66eeaadab4ad28a8f8eaf8fe1a0748f9, enums 23|1|1

# AFTER the boot (34 enums, 58 tables)
.\ops\flyway\verification\run-verification.ps1 `
    -Container firstelement-postgres -Database firstelement `
    -ExpectedEnumCount 34 `
    -OutputDir C:\...\evidence\2026-10-05-post-phase6a-boot -ThrowOnViolation
```

`V11_workflow_sla_slice.sql` is part of that bundle, is read-only, and reports
`PHASE6A_NOT_PRESENT` on a pre-boot database, so the same bundle is valid on both
sides of the cutover. `run-verification.ps1` now also carries
`-ExpectedTableCount` and `-ExpectedCheckCount` for the same reason
`-ExpectedEnumCount` exists: a count that moves must be a recorded decision, not
a number somebody edits quietly.

---

## 4. Backup and restore

```powershell
# Backup (read-only against the database)
.\ops\backup-restore\backup.ps1 -Container firstelement-postgres `
    -Database firstelement -OutputDir C:\...\backups\2026-10-05-phase6a

# Prove the backup restores, into a THROWAWAY database that is dropped again
.\ops\backup-restore\restore-rehearsal.ps1 `
    -Dump C:\...\backups\2026-10-05-phase6a\<stamp>_firstelement.dump `
    -EvidenceDir C:\...\evidence\restore-check-phase6a -Dispose
```

A Phase-6a backup must be taken **after** the boot if the intent is to protect
workflow history or SLA policies. A backup taken before the boot contains none of
them, which is the correct and complete state for that moment — and a dump with
no `flyway_schema_history` is itself the evidence that the boot had not happened.

Restoring into the real database is `docs/migration/flyway-runbook.md` §7.4 and is
deliberately not scripted here.

---

## 5. What the runbook now says

The runbook update is part of this phase (`§1` chain + SHA tables, `§3`
expected-counts table, `§9b`), so unlike Phase 5a there is nothing left for the
orchestrator to merge here. The numbers are restated in §6 below for the record.

---

## 6. Undoing a created-but-unused slice

```powershell
# 1. What would be dropped, and how many rows would be lost. Read it first.
#    Unarmed: prints UNDO PHASE6A SKIPPED and touches nothing.
Get-Content -Raw ops\flyway\rollback\undo_phase6a.sql |
  docker exec -i firstelement-postgres psql -U firstelement -d <throwaway> `
    -v ON_ERROR_STOP=1 -v firstelement_undo_armed=on -f -

# 2. On a throwaway database, actually run it, then prove the fingerprint
#    returned to the Phase-5a value. Phase 6a measured this:
#      before undo : <phase5a fingerprint>
#      after  undo : <phase5a fingerprint>   (exact equality, not "close")

# 3. Only then on the real database, and only if the slice is empty. The script
#    REFUSES if any of the nine tables holds a row unless
#    -v confirm_data_loss=yes is passed, and it prints the per-table row counts
#    BEFORE it refuses, so the refusal is informed.

# 4. Remove the four migrations from ops/flyway/sql AND from
#    backend/database/src/main/resources/db/migration/, then `flyway repair` --
#    the history now records applied migrations whose objects no longer exist.
#    Repair is an operator decision; the script does not do it.
```

The script's own guards, each of which is a real refusal rather than a
comment:

| Guard | Why it exists |
| --- | --- |
| `firstelement_undo_armed` | the undo is destructive by construction |
| partial-slice refusal | a half-applied slice means a failed migration, not an unused slice |
| per-table row counts, printed always | the operator must see what the data is before deciding |
| `confirm_data_loss=yes` for a non-empty slice | destroying workflow history is never automatic |
| baseline floor of 45 tables | refuses to run against a partially migrated database |
| foreign-key reference check | another migration depending on the slice means reverse order, not a surprise drop |
| post-condition on tables **and types** **and indexes** | see the bug below |

**The bug this rehearsal exists to prevent, carried forward from Phase 5a:** an
early version of the Phase-5a undo wrote `DROP TYPE IF EXISTS public.OutboxState`.
PostgreSQL folds an unquoted identifier to lower case, so it matched nothing,
printed `NOTICE: ... does not exist, skipping`, left all four enums behind, and
the script still reported success. Only the fingerprint comparison caught it
(27 enums where 23 were expected). Every type name in `undo_phase6a.sql` is
quoted, and the post-condition checks types as well as tables and indexes, so the
same mistake cannot pass silently again.

---

## 7. Phase-6a additions to the tenant-scope contract

Three deliberate deviations from the Phase-5a shape, each with a reason recorded
in the migration header and asserted by the fixture:

| Object | Decision | Why |
| --- | --- | --- |
| `workflow_definitions.projectId`, `workflow_versions.projectId`, `sla_calendars.projectId`, `sla_policies.projectId` | **nullable** | organisation-wide by design: a tenant's working week and its escalation workflow apply to every project, and forcing per-project copies is how calendars drift apart |
| `sla_calendar_windows`, `sla_calendar_holidays` | **no tenant columns at all** | they are configuration children of a calendar and inherit its scope, exactly like `ai_run_steps` inherits from `ai_runs` in the verified baseline. The migration's guard fails if a tenant column is ever added |
| `collision_records.projectId` | **NOT NULL** | every collided resource (conversation, assignment, case, customer) is project-scoped in the verified schema, so NULL could only mean "unknown scope" |
| `sla_calendar_windows_calendar_day_idx` | **tenant-less**, allow-listed | the per-evaluation window read; only reachable after an `organizationId`-filtered calendar row has been read, and it carries no tenant data |
| `collision_records.counterpartyId` | free text, **not** an FK | must be able to name a workflow run or a system actor, which the `users` table cannot express |
| `collision_records.conversationId / customerId / noteId` | **not** FKs | a collision must remain recordable after the raced-for row is deleted; a CASCADE would destroy the only evidence the race happened |

---

## 8. Retention is still undefined — and Phase 6a adds to it

Carried from Phase 5a risk 1, unchanged and now larger: nothing in this schema
ever deletes a row. `workflow_run_actions` and `collision_records` are the
two fastest-growing append-only tables the system will have, and
`workflow_run_actions` stores an `inputSnapshot` per action, so it is the one
table where a retention decision has a data-protection dimension as well as a
storage one. No trigger, rule or scheduled deletion is created anywhere in this
slice. `createdAt` on both tables is what a future purge would order by, and both
carry `organizationId` so a purge can be tenant-scoped and audited when the
`PROJECT_REQUIREMENTS` §7 decision exists.

**This is the single largest open item carried into Phase 6 and Phase 7.**