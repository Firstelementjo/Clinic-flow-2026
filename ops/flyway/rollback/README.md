# Phase 5a — rollback and recovery for the outbox / job / idempotency slice

Scope: `ops/flyway/sql/V20261004000100__outbox.sql`,
`V20261004000700__job_attempts.sql`, `V20261004001000__idempotency_records.sql`.
This is the Phase-5a addendum to `docs/migration/flyway-runbook.md`. It exists
because the runbook is a Phase-2 document and this phase must not rewrite
another owner's file; the orchestrator owns the merge. Nothing here contradicts
that runbook — where the runbook's expected numbers change after the Phase-5a
boot, the changes are listed in §5 below for the orchestrator to apply.

**The state of `firstelement` at the end of Phase 5a:** unchanged. 45
application tables, 0 `flyway_schema_history` rows, fingerprint
`66eeaadab4ad28a8f8eaf8fe1a0748f9`. No Flyway stamp, no DDL, no DML. The first
Flyway-enabled boot remains the orchestrator's scheduled act.

---

## 1. The four outcomes

| Situation | Action | Data risk |
| --- | --- | --- |
| A Phase-5a migration **failed** | Nothing. Every migration is transactional; PostgreSQL rolled it back atomically. Fix the file, **change its version**, re-run. | none |
| The tables exist but **nothing has been cut over** and no rows were written | `undo_phase5a.sql` (armed once). Then remove the three files and `flyway repair`. | none — proven empty by the script's own refusal |
| The tables exist **and rows exist** | Do **not** undo. Roll the application back to the previous build and leave the tables in place; they are additive and inert to the old runtime. | none |
| A tenant deletion or a purge went wrong | Restore from backup (§4). In-place restore is an approved act, not a script. | as per the incident |

The rule behind the table: **the Phase-5a slice is additive, so the correct
response to "this table is wrong" is almost never to drop it.** Only the
"created but unused" case justifies the undo, and that case is provably empty.

---

## 2. Migration failed at the boot

```powershell
docker exec firstelement-postgres psql -U firstelement -d firstelement -P pager=off -c "
  select installed_rank, version, description, type, success, execution_time
    from flyway_schema_history order by installed_rank;"
docker logs --tail 200 <app-bootstrap process>
```

Then:

1. The guard blocks at the end of each migration fail loudly on purpose
   (`V202610040001 failed: …`). Read the message: it names exactly which index,
   enum, nullability or CHECK invariant was not satisfied. The guard running
   means the table may exist — the transaction rolled back, so it does not.
2. Fix the file in `ops/flyway/sql`.
3. **Change the version.** A checksum is never reused: take the next version, not
   the same one.
4. Mirror it byte-for-byte to
   `backend/database/src/main/resources/db/migration/`.
5. Re-run the fixture: `.\gradlew.bat :backend:database:test --rerun-tasks
   --no-build-cache --no-daemon --console=plain`.
6. Re-run the evidence bundle (§3) and start the application again.

---

## 3. Evidence before and after (read-only)

```powershell
# BEFORE the first Flyway boot (this is what Phase 5a left behind)
.\ops\flyway\verification\run-verification.ps1 `
    -Container firstelement-postgres -Database firstelement `
    -OutputDir C:\...\evidence\2026-10-04-pre-phase5a-boot -ThrowOnViolation
# -> fingerprint 66eeaadab4ad28a8f8eaf8fe1a0748f9, enums 23|1|1

# AFTER the boot (the database now has 4 tables and 4 enums more)
.\ops\flyway\verification\run-verification.ps1 `
    -Container firstelement-postgres -Database firstelement `
    -ExpectedEnumCount 27 `
    -OutputDir C:\...\evidence\2026-10-04-post-phase5a-boot -ThrowOnViolation
```

`-ExpectedEnumCount` exists because Phase 5a adds four enums. It is an
assertion, not a switch to silence a failure: leaving it at 23 against a
migrated database **fails the bundle**, which is the intended signal that
someone ran a migration without recording why the enum count moved.

`V10_outbox_job_slice.sql` is part of that bundle and is read-only. It reports
`PHASE5A_NOT_PRESENT` on a pre-boot database, so the same bundle is valid on
both sides of the cutover.

---

## 4. Backup and restore

```powershell
# Backup (read-only against the database)
.\ops\backup-restore\backup.ps1 -Container firstelement-postgres `
    -Database firstelement -OutputDir C:\...\backups\2026-10-04-phase5a

# Prove the backup restores, into a THROWAWAY database that is dropped again
.\ops\backup-restore\restore-rehearsal.ps1 `
    -Dump C:\...\backups\2026-10-04-phase5a\<stamp>_firstelement.dump `
    -EvidenceDir C:\...\evidence\restore-check-phase5a -Dispose
```

A Phase-5a backup must be taken **after** the boot if the intent is to protect
the queue tables; a backup taken before the boot contains no outbox rows, which
is the correct and complete state for that moment. State which of the two a
given dump represents — a dump without `flyway_schema_history` proves the boot
had not happened yet.

Restoring into the real database is `docs/migration/flyway-runbook.md` §7.4 and
is deliberately not scripted here: it requires an application stop, an incident
record, an approval, and a verified rehearsal first.

---

## 5. What the Phase-2 runbook will say once the boot happens

For the orchestrator to merge into `docs/migration/flyway-runbook.md`:

| Runbook statement | Before | After the Phase-5a boot |
| --- | --- | --- |
| §1 chain table | 2 applied migrations | 5 applied (`20260814000000` stamped, `20260814000100`, `20261004000100`, `20261004000700`, `20261004001000`) |
| §1 SHA-256 of `V20260814000100__vector_indexes.sql` | `8ebf3949d7e1f6d94d52d48d39ec06b6cd353b80b163436d6de7d0d53a49c896` | `3a0fecbbb596d4d2c1cd9d2dd247f816c6ee93cfe0911f92713119887a927760` — **amended** in Phase 5a (schema-qualified table and operator class). It has never been applied anywhere, so no recorded checksum changes. |
| §3 V5 expected | 45 tables / 156 indexes | 49 tables / 176 indexes |
| §3 V4 expected | 62 FKs / 45 PKs / 0 CHECK / 0 unique constraints | 76 FKs / 49 PKs / **14 CHECK** / 0 unique constraints (uniqueness is carried by unique INDEXES — 79) |
| §3 V3 expected | 23 enums | 27 enums |
| §3 V9 census | 64 / 15 / 22 / 53 / 1 | 78 / 15 / 22 / 57 / **3** |
| §3 V9 "global bucket must contain only `sessions_expiresAt_idx`" | 1 | 3: `sessions_expiresAt_idx`, `outbox_events_dispatch_sweep_idx`, `outbox_events_expired_lease_idx` — all three documented in `V20261004000100` and asserted by `V10` §5a |
| §3 tenant-scope invariant "tables without `organizationId`" | 4 | unchanged (4) — the new tables all carry `NOT NULL organizationId` |
| §3 "only 2 nullable `projectId` columns" | `audit_logs`, `notifications` | 6: those two plus the four Phase-5a tables, which is organisation-wide work by design and listed explicitly by `V7` §2 and `V10` §4d |
| §8 backfill | B1/B2 | unchanged — Phase 5a needs no backfill (no existing row is touched) |
| §9 "before any new migration" | — | add "promoted drafts are deleted from `pending/`; the promoted file is the only copy of the decision" |
| NEW: version numbering | drafts `V2026100400nn` (12 digits) | **Flyway orders versions numerically**, so a 12-digit version sorts BEFORE the 14-digit baseline. Every promoted migration is 14 digits (`yyyyMMddHHmmss`) and greater than `20260814000100`. The seven remaining drafts carry a `RENUMBER BEFORE PROMOTING` banner. |
| NEW: single-session apply | undocumented | the baseline sets `search_path=''`, so `psql -f baseline -f vector` used to fail on the unqualified name in `V20260814000100`. Both the table and the operator class are now qualified and the whole chain applies in one session. Flyway was never affected. |

---

## 6. Undoing a created-but-unused slice

```powershell
# 1. What would be dropped, and how many rows would be lost. Read it first.
Get-Content -Raw ops\flyway\rollback\undo_phase5a.sql |
  docker exec -i firstelement-postgres psql -U firstelement -d <throwaway> `
    -v ON_ERROR_STOP=1 -v firstelement_undo_armed=on -f -

# 2. On a throwaway database, actually run it, then prove the fingerprint
#    returned to the post-V20260814000100 value. Phase 5a measured this:
#      before undo : f436fca35638e2d02418376333fbf67c
#      after  undo : bdf70c7c24dfe23c748281eb6c5c4a03  == V100-only fingerprint
#    (both on a psql-applied throwaway database, so no flyway_schema_history
#     in either fingerprint)

# 3. Only then, on the real database, and only if the slice is empty.
#    The script REFUSES if any of the four tables holds a row unless
#    -v confirm_data_loss=yes is passed. That second flag is the last gate
#    before delivery state and attempt history are destroyed.

# 4. Remove the three migrations from ops/flyway/sql AND from
#    backend/database/src/main/resources/db/migration/, then
#    `flyway repair` -- the history now records three applied migrations whose
#    objects no longer exist. Repair is an operator decision; the script does
#    not do it.
```

Rehearsed 2026-10-04 on `firstelement_phase5a_check`, a throwaway database that
was dropped afterwards:

| Step | Result |
| --- | --- |
| unarmed | `UNDO PHASE5A SKIPPED`, nothing touched |
| armed, slice empty | `PHASE5A_REMOVED`, 6 baseline tables still present |
| armed, 5 outbox rows present | **REFUSED** with the per-table row counts printed |
| armed + `-v confirm_data_loss=yes` | dropped, `PHASE5A_REMOVED` |
| fingerprint after the undo | `bdf70c7c24dfe23c748281eb6c5c4a03` — the exact Phase-2 post-`V20260814000100` value |
| `run-verification.ps1 -ExpectedEnumCount 23` after the undo | 23 enums, reconciled enums intact, `PHASE5A_NOT_PRESENT` |

Fingerprints are comparable because both sides are psql-applied throwaway
databases with no `flyway_schema_history`. A Flyway-managed database additionally
contains that table, so its fingerprint never matches a psql-applied one.

**One bug was found by this rehearsal and fixed:** the first version of the undo
script wrote `DROP TYPE IF EXISTS public.OutboxState`. PostgreSQL folds an
unquoted identifier to lower case, so it matched nothing, printed
`NOTICE: type "public.outboxstate" does not exist, skipping`, and left all four
enums behind while the script still reported success. The fingerprint comparison
caught it (27 enums where 23 were expected). The types are now quoted and the
post-condition checks the types as well as the tables, so the same mistake
cannot pass silently again.