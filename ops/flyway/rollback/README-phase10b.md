# Phase 10b — rollback and recovery for the schema remediation batch

Scope: the five migrations promoted in Phase 10b.

| Version | File | Creates / changes |
| --- | --- | --- |
| `20261006100000` | `V20261006100000__ai_approval_decision_biconditional_repair.sql` | **replaces** `ai_approvals_approval_check` (D1) |
| `20261006110000` | `V20261006110000__document_chunks_document_tenant_fkey.sql` | `document_chunks_document_tenant_fkey`; `documents_id_organizationId_key` (D2) |
| `20261006120000` | `V20261006120000__api_keys_and_service_principals.sql` | `ServicePrincipalStatus`, `ApiKeyStatus`, `service_principals`, `api_keys`, 2 functions, 3 triggers (D3) |
| `20261006130000` | `V20261006130000__legal_holds_scope_pair_widening.sql` | **replaces** `legal_holds_scope_pair_check` (D4) |
| `20261006140000` | `V20261006140000__privacy_deletion_subject_reference.sql` | `subjectCustomerId`, its FK, an index, `privacy_deletion_requests_subject_reference_check`; relaxes `subjectUserId` (D5) |

This is the Phase-10b addendum to `docs/migration/flyway-runbook.md`, which carries
it as section 9d.

**The state of `firstelement` at the end of Phase 10b:** unchanged. 45 application
tables, 0 `flyway_schema_history` rows, fingerprint `66eeaadab4ad28a8f8eaf8fe1a0748f9`,
4 users, 3 conversations. No Flyway stamp, no DDL, no DML, no row touched. The first
Flyway-enabled boot remains the orchestrator's scheduled, backed-up act.

---

## 1. ORDER MATTERS: THIS SCRIPT RUNS FIRST

`undo_phase10b.sql` must run **before** `undo_phase7_10a.sql`, never after.

D1 replaces a constraint **on** `ai_approvals`, D4 one **on** `legal_holds` and D5 a
column **on** `privacy_deletion_requests` — all Phase-7-10a tables. Undoing the older
slice first drops those tables and takes this batch's objects with them, at which
point `undo_phase10b.sql` refuses on its partial-batch guard. That refusal is the
correct outcome: a half-removed batch means the order was wrong, and the message
says so.

```
correct:   undo_phase10b.sql  ->  undo_phase7_10a.sql  ->  undo_phase6a.sql  ->  undo_phase5a.sql
wrong:     undo_phase7_10a.sql  ->  undo_phase10b.sql        (refuses: partially present)
```

---

## 2. The four outcomes

| Situation | Action | Data risk |
| --- | --- | --- |
| A Phase-10b migration **failed** | Nothing. All five are transactional; PostgreSQL rolled it back atomically. Fix the file, **change its version**, re-run. | none |
| The batch exists, **nothing has been cut over to it**, no rows anywhere | `undo_phase10b.sql` (armed once). Then remove the five files and `flyway repair`. | none — proven by the script's own refusals |
| The batch exists **and something reads it** | Do **not** undo. Roll the application back and leave the tables and constraints in place. | none |
| The credentials or the privacy subject were wrong | Restore from backup (section 5). In-place restore is an approved act, not a script. | as per the incident |

The rule behind the table is the Phase-5a/6a/7-10a rule, and Phase 10b sharpens it.
`api_keys` holds credential digests and every one of them has an
`admin_audit_events` row naming it, so dropping the table leaves the audit trail
pointing at a `resourceId` nothing resolves to. `privacy_deletion_requests` with a
customer subject records **which customer** a deletion was about, and that is not
derivable from anything else. Both are measured refusals, not opinions — see
section 4.

---

## 3. Every guard, and what each one is for

| Guard | What it stops |
| --- | --- |
| `\if :{?firstelement_undo_armed}` with the whole body inside | an unarmed invocation is a clean no-op, exit 0 — not a no-op followed by a syntax error on an unsubstituted variable |
| `SELECT (boolean) AS undo_go \gset` then `\if :undo_go` | the arming decision is made **in psql, before** the guard block. The first version used `\gset` with a query that returns no rows, which psql treats as a script error: an unarmed run exited **3** while printing a correct and complete `SKIPPED` |
| `\set ON_ERROR_STOP on` | a `RAISE` stops the script. Without it psql reports the error and runs the next statement — which is how the Phase-7-10a undo printed `REFUSED` and dropped 37 tables anyway |
| partial-batch refusal | a batch where only `api_keys` exists means a migration failed mid-chain |
| baseline floor of 95 tables | running against a partially migrated database. The message names the Phase-6a checkpoint (58 tables) for the case where the Phase-7-10a slice is already gone |
| credential data gate | destroying credential records whose audit rows would then dangle |
| narrow-hold guard | restoring D4's original `CHECK` would refuse an existing `TABLE`/`RECORD` hold — the most protective holds there are |
| customer-subject guard | restoring D5's `NOT NULL` would refuse an existing `CUSTOMER`-subject request, and dropping the column discards which customer it was about |
| decision-record guard | restoring D1's original `CHECK` would require **clearing** the decision record on a `REJECTED` or `REVOKED` row — the exact data loss the batch exists to prevent |
| reverse-dependency guard | an object outside the batch with an FK into it means reverse order, not a surprise drop |
| post-condition on tables **and** types **and** functions **and** triggers **and** constraints **and** indexes **and** the column | a fingerprint that does not return exactly means something is left behind. The Phase-5a undo reported success while leaving four enums behind |

---

## 4. Rehearsed on throwaway databases, then dropped

`.\ops\flyway\verification\rehearse-phase10b.ps1 -ThrowOnViolation` runs all of this
and asserts on the **state after** each action (ADR-0009 §5). Every row below is a
state assertion, not a message.

| Step | Seed | Result | State asserted afterwards |
| --- | --- | --- | --- |
| 1 | none | chain applies | `97,57,421` tables/enums/CHECKs; both new tables present |
| 2 | none, **unarmed** | `SKIPPED`, **exit 0** | unchanged |
| 3 | one `api_keys` row | **REFUSED**, exit 3, counts printed first | **unchanged** — nothing dropped |
| 4a | one `RECORD` legal hold | hold **is insertable** (D4 landed); undo **REFUSED** on the narrow hold | unchanged |
| 4b | one `CUSTOMER`-subject request | request **is insertable** with **no user id at all** (D5 landed); undo **REFUSED** | unchanged |
| 4c | one `REJECTED` approval **with** its decision | **is insertable** (D1 landed); undo **REFUSED** on the decision record | unchanged |
| 5 | none, armed | `PHASE10B_REMOVED`, exit 0 | `95,55,402`; `subjectCustomerId` gone; `subjectUserId` NOT NULL again; both pre-existing FKs still present; both restored expressions are the pre-batch ones |
| 6 | none | chain **re-applies** on top of its own undo | `97,57,421` again |

**All 33 state assertions pass.** Six throwaway databases
(`firstelement_p10b_chain`, `_refuse`, `_narrow`, `_customer`, `_decided`,
`_clean`) were created and all six were dropped.

### Four defects this rehearsal found, recorded because each is invisible from reading the SQL

1. **The audit trigger's actor read named columns the firing table does not have.**
   `coalesce(NEW."revokedByUserId", NEW."suspendedByUserId", NEW."createdByUserId")`
   raised `record "new" has no field "revokedByUserId"` on `service_principals` and
   `record "new" has no field "suspendedByUserId"` on `api_keys`. A plpgsql record
   field that does not exist is a **runtime** error, not a NULL. Key creation failed
   on the first INSERT.
2. **`AND` does not protect a record field reference.** The branch chain was written
   as `ELSIF TG_TABLE_NAME = 'api_keys' AND NEW."scopes" IS DISTINCT FROM …` followed
   by `ELSIF TG_TABLE_NAME = 'service_principals' AND NEW."capabilities" …`. The SQL
   planner may evaluate either operand of `AND` in either order, so the guard on the
   left did not stop the field on the right from being resolved: **every** `api_keys`
   `UPDATE` whose status did not change died with
   `record "new" has no field "capabilities"`. Fixed by nesting the `IF`s so each
   branch names only the columns of the table it guards.
3. **The undo's partial-batch guard was inverted in effect.**
   `IF present IS NULL OR absent IS NULL` is true whenever the batch is **whole**,
   because `string_agg` returns NULL over an empty set. The first run therefore
   refused a perfectly intact batch. Harmless — nothing was dropped — but a refusal
   for the wrong reason is a failed rehearsal.
4. **Two `pg_get_constraintdef` patterns were written from the DDL's own text rather
   than PostgreSQL's rendering, and one omitted the space after `=`.** The old-form
   detection in `V20261006100000`, in `V15` and in the undo could therefore never
   fire, and the undo reported `PHASE10B_UNDO_INCOMPLETE` on a database it had undone
   correctly. Each pattern is now written from the exact rendering, and each is
   additionally **proven to discriminate** by matching it against the known pre-batch
   and post-batch definitions.

---

## 5. Backup and restore

Unchanged from the runbook (section 5 / 6) and not restated here:

```powershell
# BEFORE any first boot, and again before any approved apply
.\ops\backup-restore\backup.ps1 -Container firstelement-postgres `
    -Database firstelement -OutputDir C:\...\backups\2026-10-06-pre-10b

# prove the backup restores, into a throwaway that is dropped again
.\ops\backup-restore\restore-rehearsal.ps1 `
    -Dump C:\...\backups\2026-10-06-pre-10b\<stamp>_firstelement.dump `
    -EvidenceDir C:\...\evidence\restore-check-10b -Dispose
```

A backup taken **before** the boot contains none of the 97 tables this chain adds,
which is the correct and complete state for that moment — and a dump with no
`flyway_schema_history` is itself the evidence that the boot has not happened.

Restoring into the real database is runbook section 7.4 and is deliberately not
scripted.

---

## 6. When the batch refuses on data

`V20261006100000` refuses if any `ai_approvals` row is `REJECTED` or `REVOKED` with
no recoverable decision record. That is a real cutover condition, not a
theoretical one, and the operator decision is:

1. Read the refusal: it names the count and the ids.
2. Look for the evidence in `ai_approval_decisions` — the `PENDING→REJECTED` (or
   `PENDING→APPROVED`) row for that approval.
3. If the row exists and names a human `actorUserId`, the migration repairs from it
   and does not refuse. The refusal therefore means the decision row is **absent** or
   its actor was a non-human principal with `actorUserId IS NULL`.
4. In that case the choices are: re-source the decider into
   `ai_approval_decisions` and re-run the migration, **or** accept that the approval
   cannot be made auditable and record that in the incident. The migration will not
   choose, and it will not delete the row.