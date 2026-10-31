# Phase 35 — appointment tenant composite constraints

**Status: PASS.** Measured on **PostgreSQL 16.15** (`Debian 16.15-1.pgdg12+2`, x86_64).
Scope: `ops/flyway/**` only. The state of `firstelement` is **unchanged** — 45 tables,
62 foreign keys, 0 `flyway_schema_history` rows, fingerprint
`66eeaadab4ad28a8f8eaf8fe1a0748f9`. No stamp, no DDL, no DML. The first
Flyway-enabled boot remains the orchestrator's scheduled, backed-up act.

---

## 1. The defect

`appointments` had four foreign keys and every one was **single-column**:

| Constraint | Definition (baseline `V20260814000000`, lines 2605–2630) |
| --- | --- |
| `appointments_createdById_fkey` | `FOREIGN KEY ("createdById") REFERENCES "public"."users"("id")` |
| `appointments_customerId_fkey` | `FOREIGN KEY ("customerId") REFERENCES "public"."customers"("id")` |
| `appointments_doctorId_fkey` | `FOREIGN KEY ("doctorId") REFERENCES "public"."doctors"("id")` |
| `appointments_procedureId_fkey` | `FOREIGN KEY ("procedureId") REFERENCES "public"."procedures"("id")` |

A single-column FK guarantees the referenced row **exists**. It says nothing about
**which tenant owns it**, so `appointments."organizationId"` — the column every
list, schedule and export filters on — may name a different tenant from the
clinician, patient, procedure or creator the appointment belongs to. A patient
record held by one tenant can be attached to a clinician of another. That is a
cross-tenant disclosure, not a data-quality issue.

This is **a gap in Phase 10b, not a new class of problem.** ADR-0011 §2 swept the
tenant references it found and made each composite; `appointments` was never in
that batch's scope. The remedy is the same shape as
`V20261006110000__document_chunks_document_tenant_fkey.sql`, which is the model.

---

## 2. What shipped

`ops/flyway/sql/V20261009110000__appointments_tenant_composite_fkeys.sql` —
additive, transactional, **touches no column and no row**.

### The four constraints

| Constraint | Columns | Target | On delete | On update | Match |
| --- | --- | --- | --- | --- | --- |
| `appointments_created_by_tenant_fkey` | `("createdById", "organizationId")` | `users("id", "organizationId")` | `SET NULL ("createdById")` | `CASCADE` | `SIMPLE` |
| `appointments_customer_tenant_fkey` | `("customerId", "organizationId")` | `customers("id", "organizationId")` | `SET NULL ("customerId")` | `CASCADE` | `SIMPLE` |
| `appointments_doctor_tenant_fkey` | `("doctorId", "organizationId")` | `doctors("id", "organizationId")` | `SET NULL ("doctorId")` | `CASCADE` | `SIMPLE` |
| `appointments_procedure_tenant_fkey` | `("procedureId", "organizationId")` | `procedures("id", "organizationId")` | `SET NULL ("procedureId")` | `CASCADE` | `SIMPLE` |

### The two new target indexes

`doctors_id_organizationId_key` and `procedures_id_organizationId_key`, both
`UNIQUE ("id", "organizationId")`. `users_id_organizationId_key`
(`V20261005050000`) and `customers_id_organizationId_key` (`V20261006140000`)
already existed and are **reused, not recreated**. Unique **indexes**, not
constraints, so V4's `unique_constraints = 0` invariant survives — re-asserted by
this migration.

---

## 3. Three findings that changed the design

Each was **measured on PostgreSQL 16.15 before being relied on**, not reasoned
about. All three are in the migration header.

### 3.1 `ON DELETE SET NULL (column)` is required, not stylistic

The brief said to copy `V20261006110000`'s `ON DELETE CASCADE`. **That would have
been wrong.** The four pre-existing appointment FKs are `ON DELETE SET NULL`
(`confdeltype = 'n'`), *not* CASCADE — `document_chunks` genuinely cascades, an
appointment does not. Copying CASCADE would have **deleted an appointment** when
its doctor, customer, creator or procedure was removed.

The *bare* composite form `ON DELETE SET NULL` is also wrong:

```
ERROR:  null value in column "organizationid" of relation "t_probe_dst"
        violates not-null constraint
CONTEXT: UPDATE ONLY t_probe_dst SET "doctorid" = NULL, "organizationid" = NULL WHERE ...
```

It nulls **every** referencing column including the `NOT NULL` `organizationId`,
so `DELETE FROM doctors` would have started **failing** where it previously
succeeded. PostgreSQL 15+ accepts the column-list form, which nulls only the
foreign key column. Rehearsal control **POS-4** proves the behaviour is
unchanged: the appointment survives, `doctorId` becomes NULL, `organizationId` is
untouched.

### 3.2 The column order is positional, and the "obvious" order is wrong

`FOREIGN KEY ("organizationId", "createdById") REFERENCES users("id", "organizationId")`
binds `organizationId → users.id` and `createdById → users.organizationId`. That
is not a stricter tenant constraint; it is a different, wrong one, and it rejects
every legitimate appointment:

```
ERROR: Key (organizationid, doctorid)=(aaaa..., 1111...) is not present in table "t_probe_src"
```

The FK column comes **first**. The post-condition compares `conkey`/`confkey`
**position by position**, and control **NEG-6** builds the reversed pair and
proves the guard fires.

### 3.3 MATCH SIMPLE needs a tripwire

`MATCH SIMPLE` skips the check when **any** column is NULL. That is what makes a
NULL `doctorId` legal — and it means a NULL `organizationId` would switch the
tenant check **off**. Only the `NOT NULL` on that column prevents it, so the
migration asserts it. Control **NEG-8** drops the `NOT NULL` and proves the
guard fires.

---

## 4. Refuse, do not repair (ADR-0009 §5)

Before any constraint exists, the migration counts, for each of the four
references, appointments whose `organizationId` disagrees with the owning row,
and separately orphans. On a non-zero count it **RAISEs and names the offending
appointment ids**. It deletes nothing and rewrites no tenant: choosing which of
two scopes was right is a clinical and commercial decision for an operator with
the business context, not a side effect of adding a foreign key.

**This guard is demonstrated to fire**, not merely written — see §6.

---

## 5. The single-column FKs are kept, deliberately

None of the four pre-existing FKs is dropped. They are redundant once the
composite exists, but: dropping a **pre-existing** constraint is not this
batch's job; their `ON UPDATE CASCADE` is propagation behaviour existing code may
depend on; and leaving them buys a chain in which **nothing pre-existing was
weakened**. The migration **asserts all four are still present**, so a later
editor cannot remove one unnoticed. Recorded as residual **F-35-3**.

---

## 6. Evidence

### 6.1 Clean bootstrap — `postBoot` reproduced exactly

```
.\ops\flyway\verification\verify-clean-bootstrap.ps1 -Database firstelement_p35_final_check
```

25 migrations applied to a database created from `template0`, in **one** psql
session (the shape Flyway produces on a single connection, and the only shape
under which the baseline's `search_path = ''` is in force for migrations 2..25).

| Measurement | Phase-14 value | **New `postBoot`** | Δ |
| --- | --- | --- | --- |
| tables | 98 | **98** | — |
| enums | 57 | **57** | — |
| CHECK constraints | 422 | **422** | — |
| foreign keys | 223 | **227** | **+4** |
| primary keys | 98 | **98** | — |
| indexes | 394 | **396** | **+2** |
| unique indexes | 205 | **207** | **+2** |
| unique constraints | 0 | **0** | — |
| triggers | 11 | **11** | — |
| nullable `projectId` | 37 | **37** | — |
| fingerprint | `bf31e9f102be19a6c373eb353b2d2131` | **`896f371b51d192ca9bf19643e0cb4443`** | changed |

Recorded in `ops/flyway/expected-state.json` under `postBoot` and as the
`phase35PostBoot*` undo checkpoint. The Phase-14 and Phase-10b checkpoints are
preserved, and `preBoot` is untouched.

### 6.2 Guard rehearsal — 10 negative controls, all firing

```
.\ops\flyway\verification\rehearse-phase35.ps1     -> exit 0
```

| Control | Breaks | Proves |
| --- | --- | --- |
| POS-1 | nothing | applies cleanly; 4 composites installed; 4 singles retained |
| POS-2 | all four references NULL | applies — MATCH SIMPLE behaves |
| POS-3 | — | a cross-tenant `INSERT` is **refused** by the constraint |
| POS-4 | — | `DELETE` of a doctor preserves the appointment, NULLs only `doctorId` |
| NEG-1 | cross-tenant `doctorId` | refuses; names the id; **0 composites left**; row survives |
| NEG-2 | cross-tenant `customerId` | same |
| NEG-3 | cross-tenant `procedureId` | same |
| NEG-4 | cross-tenant `createdById` | same |
| NEG-5 | orphan `doctorId` | refuses with the orphan message |
| NEG-6 | reversed column pair | post-condition refuses |
| NEG-7 | partial target index | post-condition refuses |
| NEG-8 | `organizationId` NOT NULL dropped | tripwire refuses |
| NEG-9 | single-column lookalike, same name | post-condition refuses |
| NEG-10 | `ON DELETE` switched to CASCADE | post-condition refuses |

Each negative control asserts **the exit code AND the intended message**, plus
that **no composite FK survives the refusal** and **the offending row was not
deleted**.

Two harness bugs were found and fixed by these controls, both worth recording:
a POS-3 fixture that joined the project's organization to the *doctor's*
organization and therefore inserted a **same-tenant** row — a negative control
that could never fail — and a psql line-wrap that split `its shape is not`
across two lines, making a fired guard read as silent.

### 6.3 Undo rehearsal

```
.\ops\flyway\verification\rehearse-undo-phase35.ps1 -> exit 0
```

| Outcome | Result |
| --- | --- |
| unarmed | nothing touched, all 4 composites survive |
| armed, clean | `PHASE35_REMOVED`; counts return to 98/57/422/**223**/98/**394**/**205**/11 |
| armed, clean | **fingerprint returns to `bf31e9f102be19a6c373eb353b2d2131`** |
| armed, cross-tenant row | `PHASE35_UNDO_REFUSED`, names the id, **nothing removed**, row not deleted |
| partial batch | `PHASE35_UNDO_PARTIAL`, refusing to compound |

The fingerprint return is the strongest single assertion here: it proves the undo
reproduces the pre-Phase-35 schema *exactly*, not approximately.

### 6.4 Rendered-text audit — the Phase-30 defect class

```
.\ops\flyway\verification\audit-rendered-text-assertions.ps1 -StaticOnly
.\ops\flyway\verification\audit-rendered-text-assertions.ps1 -Database firstelement_p35_audit_check
```

Static: **45 rendered-text reads across 25 migrations**, classified as
`search_path-sensitive` **0**, `renormalised` 1, `rendering-stable` 20,
`structural` 12.

Live differential: **0 shipped predicates flip.** All four new structural probes
(`0911000 structural FK shape` for doctor / customer / creator / procedure) are
**stable** across both `search_path` settings, and both paired LEGACY
text patterns still **flip** — which is the regression evidence proving the fix
is still in place rather than quietly reverted. 7 removed patterns flip in total
(5 pre-existing + 2 new).

This migration's **only** rendered-text read is line 367, `pg_get_constraintdef`
into `definition`, which is interpolated into the failure message for an operator
and **never compared**. Every pass/fail decision is structural.

### 6.5 A tool-dependence worth knowing about

The schema fingerprint is a function of the **output format** as well as of the
schema. `run-verification.ps1` runs V1 with `-P 'fieldsep=|'` and no `-t`, so its
file carries a header and psql's column padding (2724 lines) and hashes to
`bf31e9f1…`. The same schema read through raw `psql -t -A` (2721 lines) hashes to
`96a1a9dc…`. Both were observed on the identical database. **Always compare a
fingerprint recorded by one tool against a value produced by the same tool.**
Every recorded fingerprint in `expected-state.json` came from the bundle path.

---

## 7. Rollback

`ops/flyway/rollback/undo_phase35.sql` removes **only** what this batch created:
the four constraints and the two target indexes. The four pre-existing
single-column FKs, `users_id_organizationId_key` and
`customers_id_organizationId_key` all survive.

Run order is **inside out**: `undo_phase35.sql` **first**, then
`undo_phase14.sql`, `undo_phase10b.sql`, `undo_phase7_10a.sql`.

| Situation | Action | Data risk |
| --- | --- | --- |
| The migration **failed** | Nothing. It is transactional; PostgreSQL rolled it back atomically. Fix the file, **change its version**, re-run. | none |
| Applied, nothing cut over, no cross-tenant rows | `undo_phase35.sql` (armed once). Then remove the file and `flyway repair`. | none — proven by the script's own refusals |
| Applied and **a cross-tenant appointment exists** | The script **REFUSES** and names the row. The constraint is holding, not redundant. Resolve the rows first. | none |
| A tenant purge or delete went wrong | Restore from backup. In-place restore is an approved act, not a script. | as per the incident |

Arming, and the `ON_ERROR_STOP` + `\gset` gate + post-condition triple defence,
follow `undo_phase14.sql` exactly.

---

## 8. Residuals — reported, not fixed

| ID | Residual |
| --- | --- |
| **F-35-1** | `appointments.projectId` still need not match the project of its doctor, customer, procedure or creator. `projectId` is `NOT NULL` and is not in the composite pair. Same shape as F-2 (`document_chunks.projectId`), which ADR-0011 §2 also declined to widen. A three-column FK would refuse for a reason nobody authorised. |
| **F-35-2** | `ON UPDATE CASCADE` carries `organizationId` but not `projectId`. Re-tening a doctor moves its appointments to the new tenant and leaves `projectId` behind — the same incompleteness by a different route. |
| **F-35-3** | The four redundant single-column FKs are retained on purpose. Dropping them is a separate change that must re-measure the delete path first. |
| **F-35-4** | Any future composite tenant FK added to a table whose existing FKs are `SET NULL` must use the column-list form, or it will break deletion. `V20261006110000`'s CASCADE precedent does not generalise. |

Also reported and **not** fixed, as instructed:
`ai_policies."customerVisibleRequiresApproval"` defaults to `true`
(`V20261005100000:298`), so a per-tenant policy row refuses every
customer-visible AI reply until someone opts out. AI governance, not schema.

---

## 9. Commands

```powershell
# the migration, and the two new objects
ops\flyway\sql\V20261009110000__appointments_tenant_composite_fkeys.sql
  CREATE UNIQUE INDEX "doctors_id_organizationId_key"     ON "public"."doctors"    ("id","organizationId");
  CREATE UNIQUE INDEX "procedures_id_organizationId_key"  ON "public"."procedures" ("id","organizationId");
  ALTER TABLE "public"."appointments" ADD CONSTRAINT "appointments_created_by_tenant_fkey"
      FOREIGN KEY ("createdById","organizationId") REFERENCES "public"."users"("id","organizationId")
      ON UPDATE CASCADE ON DELETE SET NULL ("createdById");
  ALTER TABLE "public"."appointments" ADD CONSTRAINT "appointments_customer_tenant_fkey"
      FOREIGN KEY ("customerId","organizationId") REFERENCES "public"."customers"("id","organizationId")
      ON UPDATE CASCADE ON DELETE SET NULL ("customerId");
  ALTER TABLE "public"."appointments" ADD CONSTRAINT "appointments_doctor_tenant_fkey"
      FOREIGN KEY ("doctorId","organizationId") REFERENCES "public"."doctors"("id","organizationId")
      ON UPDATE CASCADE ON DELETE SET NULL ("doctorId");
  ALTER TABLE "public"."appointments" ADD CONSTRAINT "appointments_procedure_tenant_fkey"
      FOREIGN KEY ("procedureId","organizationId") REFERENCES "public"."procedures"("id","organizationId")
      ON UPDATE CASCADE ON DELETE SET NULL ("procedureId");

# verify the state (read-only, any migrated database)
docker exec -i firstelement-postgres psql -U firstelement -d <throwaway> -P pager=off -c "
SELECT conname, conkey, confkey, confrelid::regclass::text AS reftable,
       confdeltype, confupdtype, confmatchtype
  FROM pg_constraint WHERE conrelid='public.appointments'::regclass AND contype='f'
 ORDER BY conname;"

# clean bootstrap
.\ops\flyway\verification\verify-clean-bootstrap.ps1 -Database firstelement_p35_final_check

# guard rehearsal (creates and disposes its own throwaways)
.\ops\flyway\verification\rehearse-phase35.ps1

# undo rehearsal
.\ops\flyway\verification\rehearse-undo-phase35.ps1

# rendered-text audit
.\ops\flyway\verification\audit-rendered-text-assertions.ps1 -StaticOnly
.\ops\flyway\verification\audit-rendered-text-assertions.ps1 -Database firstelement_p35_audit_check

# plpgsql RAISE placeholder/argument parity (a mismatch aborts the whole chain)
.\ops\flyway\verification\check-raise-placeholders.ps1 -Path ops\flyway\sql\V20261009110000__appointments_tenant_composite_fkeys.sql

# expected-state.json still parses and reports what the gate reads
.\ops\flyway\verification\check-expected-state.ps1

# undo, on a throwaway, ARMED
Get-Content -Raw ops\flyway\rollback\undo_phase35.sql |
  docker exec -i firstelement-postgres psql -U firstelement -d <throwaway> `
    -v ON_ERROR_STOP=1 -v firstelement_undo_armed=on -f -
```

---

## 10. For the orchestrator

1. **The chain is 25 migrations, `lastVersion 20261009110000`.** Update any
   runbook chain table and any mirror to
   `backend/database/src/main/resources/db/migration/`.
2. **`expected-state.json` `postBoot` has moved** — FKs 227, indexes 396, unique
   indexes 207, fingerprint `896f371b51d192ca9bf19643e0cb4443`. The quality gate
   reads this file, so it is already consistent; the numbers in
   `docs/migration/flyway-runbook.md` are not.
3. **Mirroring is required but was not done here** — `backend/**` is owned by
   another engineer this session.
4. **Consider wiring `rehearse-phase35.ps1` into the gate.** It is the only
   rehearsal that proves a *refusal* fires, and the brief's own standard is that
   a guard never fired is a guard that does not work.
5. **F-35-1 / F-35-2** are the same cross-project gap ADR-0011 declined to widen.
   If it is ever authorised, it is a three-column FK
   `("doctorId", "organizationId", "projectId")` with matching unique targets on
   all four tables, plus the same refuse-first guard.
6. **No Testcontainers fixture was added.** The existing Flyway bootstrap
   acceptance test lives under `backend/database/**`, which this session does not
   own; the PostgreSQL/pgvector behaviour is covered by the rehearsals above
   against the project's own 16.15 container.