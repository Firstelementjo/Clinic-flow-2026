# Phase 30 — Migration chain bootstrap: the chain cannot create a clean database

**Status:** PASS for the chain — the blocker is fixed and measured. **One follow-up outside
this agent's ownership blocks the merge**: the packaged copy of the chain and the four
runbook digests must be updated in the same commit (section 6).

**Date:** 2026-10-08 · PostgreSQL 16.15 (Debian 16.15-1.pgdg12+2), `pgvector/pgvector:pg16`
· branch `migration/spring-angular`

**`firstelement` was never touched.** 45 tables, 0 `flyway_schema_history` rows, unchanged
(re-measured at the end of this phase). Every measurement here was taken on a disposable
database created with `CREATE DATABASE ... TEMPLATE template0` and dropped afterwards.

---

## 1. The blocker

The chain cannot bootstrap a clean database. Reproduced exactly:

```powershell
.\ops\quality\checks\prepare-throwaway-db.ps1 -Container firstelement-postgres `
    -DbUser firstelement -ProtectedDatabase firstelement `
    -Database firstelement_gate_migration_check -MigrateChain -Dispose
```

```
ERROR:  V20261006110000 failed: the constraint is "FOREIGN KEY ("documentId",
  "organizationId") REFERENCES public.documents(id, "organizationId") ON UPDATE CASCADE
  ON DELETE CASCADE", which is not the composite (documentId, organizationId) reference
  this migration exists to add.
CONTEXT:  PL/pgSQL function inline_code_block line 29 at RAISE
FAILED: applying 24 migration file(s) ... exit code 3
```

It aborts at **18 of 24 migrations**; 95 of 98 tables exist at that point. The remaining
four migrations were never reached, so **three further broken guards are latent behind it**
(section 3).

## 2. Root cause: a verification whose answer depends on the session

`V20261006110000__document_chunks_document_tenant_fkey.sql` lines 206-207:

```sql
IF definition NOT LIKE '%FOREIGN KEY ("documentId", "organizationId")%'
   OR definition NOT LIKE '%REFERENCES documents(id, "organizationId")%' THEN
```

`definition` is `pg_get_constraintdef(oid)`. That function **schema-qualifies any relation
that is not visible on the current `search_path`**. Measured on the same database, same
constraint, same server:

| `search_path` | `pg_get_constraintdef` renders |
| --- | --- |
| `"$user", public` | `FOREIGN KEY ("documentId", "organizationId") REFERENCES documents(id, "organizationId") …` |
| *(empty)* | `FOREIGN KEY ("documentId", "organizationId") REFERENCES public.documents(id, "organizationId") …` |

The comment at lines 200-205 says the unqualified rendering was *"Measured on PostgreSQL
16.15 while writing this file."* That measurement was real; the environment it was taken in
was not the one the chain runs in.

**Why the empty `search_path` is the normal case, not an edge case.**
`V20260814000000__baseline_verified_live_schema.sql` line 56:

```sql
SELECT pg_catalog.set_config('search_path', '', false);
```

`is_local = false` makes this a **session** setting, and a session setting persists for the
rest of the connection. Flyway executes every migration of a boot on **one** connection, so
migrations 2..24 run under `search_path = ''` under Flyway too. This is not an artefact of
the gate's concatenated `psql` session.

**Which is why the existing rehearsal never caught it.**
`ops/flyway/verification/rehearsal-chain.ps1` invokes `psql` once per file. Each file
therefore gets a fresh process and a fresh `search_path`. Its own header says one invocation
per migration is deliberate — *"so the exit code belongs to exactly one migration"* — and
that is correct for transactions, but it resets a session setting between migrations and so
**cannot observe a `search_path`-dependent guard**. The per-file rehearsal applies all 24
files cleanly (re-measured in this phase). That is the whole reason the defect survived to
the gate.

## 3. The audit: every rendered-text assertion in the chain

43 catalogue-rendered reads across the 24 migrations. Classified by
`ops/flyway/verification/audit-rendered-text-assertions.ps1`:

**6 `search_path-sensitive` · 17 `rendering-stable` · 1 `structural`**

### 3a. Five that fire on a correct database (the blocker)

Each asserts `REFERENCES <table>(...)` in its **unqualified** form. All five **always fail**
under the `search_path` the baseline installs.

| # | File : line | Constraint | Unqualified pattern that cannot match |
| --- | --- | --- | --- |
| 1 | `V20261006110000__document_chunks_document_tenant_fkey.sql:206` | `document_chunks_document_tenant_fkey` | `%REFERENCES documents(id, "organizationId")%` |
| 2 | `V20261006120000__api_keys_and_service_principals.sql:653` | `api_keys_service_principal_tenant_fkey` | `%REFERENCES service_principals(id, "organizationId")%` |
| 3 | `V20261006140000__privacy_deletion_subject_reference.sql:355` | `privacy_deletion_requests_subject_customer_tenant_fkey` | `%REFERENCES customers(id, "organizationId")%` |
| 4 | `V20261006140000__privacy_deletion_subject_reference.sql:377` | `privacy_deletion_requests_subject_tenant_fkey` | `%REFERENCES users(id, "organizationId")%` |
| 5 | `V20261007100000__customer_cx_sequences.sql:341` | `customer_cx_sequences_organizationId_fkey` | `%REFERENCES organizations(id)%` |

Live differential (each pattern evaluated twice on one migrated database):

```
PROBE                                          search_path  search_path='' VERDICT
0611000:206  REFERENCES documents              true         false        FLIPS
0612000:653  REFERENCES service_principals     true         false        FLIPS
0614000:355  REFERENCES customers              true         false        FLIPS
0614000:377  REFERENCES users                  true         false        FLIPS
0710000:341  REFERENCES organizations          true         false        FLIPS
(control) 0611000:206 FK column pair           true         true         stable
(control) 0611000:206 documents index          true         true         stable
```

Fixing only #1 exposes #2, #3, #4 and #5 in turn. **All five must be corrected in one change.**

### 3b. One that can never fire, and whose own self-test says it can

`V20261006100000__ai_approval_decision_biconditional_repair.sql:349` — the detector that
asserts the **old** single-literal form of `ai_approvals_approval_check` is gone:

```sql
IF definition LIKE '%= ''APPROVED''::"AiApprovalStatus") = ("decidedAt" IS NOT NULL)%' THEN
```

`AiApprovalStatus` is an enum in `public`, so under an empty `search_path` the type renders
`::public."AiApprovalStatus"` and the pattern can never match. Measured by installing the
**pre-batch** definition on a throwaway database and asking whether the guard would notice:

```
search_path = default    rendering: CHECK (((status = 'APPROVED'::"AiApprovalStatus") = ("decidedAt" IS NOT NULL)))
search_path = default    detector FIRES: true
search_path = (empty)    rendering: CHECK (((status = 'APPROVED'::public."AiApprovalStatus") = ("decidedAt" IS NOT NULL)))
search_path = (empty)    detector FIRES: false
```

**It cannot detect the removal.** And the self-test three lines later (line 361) reports
`true` in both environments, because it compares the pattern against a *hard-coded* string
rather than against a rendering — so it certifies discrimination against a form the chain
never produces under the session Flyway gives it. A removal guard that cannot fire, and a
self-test that cannot notice, is the worst of both.

This one is **not** a bootstrap blocker (it can only fail to fire, never fire spuriously).
It is a **detection gap** and is reported, not fixed — see section 8.

### 3c. Seventeen that read rendered text and are stable anyway

Not a list of things to change; a list of things **not** to change, and the reason each is
immune. In every case the pattern names only columns, bare literals, SQL keywords, or a
`pg_catalog` type — none of which `search_path` can qualify.

`06110000:206` FK column pair · `06120000:652` FK column pair · `06140000:354` FK column
pair · `06140000:376` FK column pair · `0710000:340` FK column · `0610000:327` three enum
literals · `06130000:217` four bare enum words · `06130000:225` `IS NULL` / `IS NOT NULL`
on columns · `06140000:338` two columns + one bare word · `0710000:305` bracketed column
list · `0710000:453` `format_type = bigint` · `06140000:289` `format_type = uuid` ·
`07110000:502` positional `pg_get_indexdef(idx, n, true)` · `07110000:524` index keyword +
column pair · `0710000:384` three SQL keywords in `pg_get_functiondef` · `05100000:1250`
boolean literal `false` · the 13 `pg_get_expr` `CURRENT_TIMESTAMP` / `AT TIME ZONE` default
checks (`05010000:491`, `05020000:566`, `05030000:653`, `05040000:413`, `05050000:1013`,
`05060000:805`, `05070000:734`, `05080000:483`, `05090000:1512`, `05100000:1339`,
`05110000:201`, `06120000:726`, `0710000:434`).

Two of these are the **model to follow**:

- `V20261007110000__customers_cx_id_tenant_scope.sql:497-511` compares index columns
  **position by position** and asserts that its own detector discriminates — and, unlike
  line 349 above, it does so against a *rendering*, not a hard-coded string.
- `V20261005080000__usage_ledger.sql:504` reads index columns through `pg_attribute`
  **not at all through rendered text**, and its comment says why: *"The column list is
  compared through pg_attribute rather than by matching pg_get_indexdef text, because
  indexdef quotes identifiers only when it must and these are camelCase."*

The chain already contains the right technique and already knows why. Five files did not use it.

### 3d. A seventh site the audit surfaced: the fingerprint is also session-dependent

Not a migration, but the same class and in the same ownership area.
`ops/flyway/verification/V1_schema_fingerprint.sql` builds the fingerprint from
`pg_get_constraintdef` (line 58) and `pg_get_indexdef` (line 71). Measured on one migrated
database, one file, two sessions:

| session | fingerprint |
| --- | --- |
| `run-verification.ps1` (fresh session, default `search_path`) | `bf31e9f102be19a6c373eb353b2d2131` |
| same `V1` preceded by `SET search_path TO ''` | `29787b8a0d5df64e61b0c4b2df389177` |

So `expected-state.json`'s `postBoot.fingerprint` is a **default-`search_path`** fingerprint.
It reproduces reliably — `run-verification.ps1` always opens a fresh session per query, and
it reproduced twice in this phase on two different databases — but the value is only
meaningful when measured through that runner. Recommended hardening, **not applied** because
it would perturb a value recorded across phases this agent does not own: pin the
`search_path` at the top of `V1` (needs a `-q` on the psql invocation so the `SET` emits no
command tag into the hashed output) and record both values in the runbook. See section 8.

## 4. The fix: compare the shape, not the rendering

`pg_constraint` already holds everything the five guards were trying to prove, as data:

- `conkey` — attnums of the referencing columns, in constraint order
- `confkey` — attnums of the referenced columns, in constraint order
- `confrelid` — the referenced table's OID

Resolved through `pg_attribute` these give real column names. Compared against a `regclass`,
the referenced table is an OID. **No rendered text is read, so no `search_path`, quoting or
qualification choice can reach the answer.**

```sql
SELECT
    (SELECT array_agg(a.attname::text ORDER BY k.ord)
       FROM unnest(cn.conkey) WITH ORDINALITY AS k(attnum, ord)
       JOIN pg_attribute a ON a.attrelid = cn.conrelid AND a.attnum = k.attnum)
      = ARRAY['documentId', 'organizationId']::text[]
    AND cn.confrelid = 'public.documents'::regclass
    AND (SELECT array_agg(a.attname::text ORDER BY k.ord)
           FROM unnest(cn.confkey) WITH ORDINALITY AS k(attnum, ord)
           JOIN pg_attribute a ON a.attrelid = cn.confrelid AND a.attnum = k.attnum)
      = ARRAY['id', 'organizationId']::text[]
  INTO fk_shape
  FROM pg_constraint cn
 WHERE cn.conrelid = 'public.document_chunks'::regclass
   AND cn.conname = 'document_chunks_document_tenant_fkey';

IF fk_shape IS DISTINCT FROM true THEN
    RAISE EXCEPTION '…', coalesce(definition, '(absent)');
END IF;
```

`definition` is still read, and still printed in the failure message — an operator needs the
rendered form to diagnose it. It simply no longer decides whether the migration passes.

**Measured, on a migrated database, under both `search_path` settings:**

| check | default | *(empty)* |
| --- | --- | --- |
| `document_chunks_document_tenant_fkey` | `true` | `true` |
| `api_keys_service_principal_tenant_fkey` | `true` | `true` |
| `privacy_deletion_requests_subject_customer_tenant_fkey` | `true` | `true` |
| `privacy_deletion_requests_subject_tenant_fkey` | `true` | `true` |
| `customer_cx_sequences_organizationId_fkey` | `true` | `true` |
| **discrimination: `document_chunks_documentId_fkey`** (the single-column FK of the same intent, which the old guard could not tell from the composite one by name alone) | **`false`** | **`false`** |

The last row is the point. The structural test accepts the intended shape and **rejects the
decoy**, and it does so identically in both environments.

**Cost: five files, nine blocks, ~90 lines of SQL, all of it assertion-only. No DDL, no data
statement, no change to any object the chain creates.**

### Why not pin `search_path` inside each verification block

You asked for a judgement here. Pinning is *worse*, and I recommend against it:

1. It treats the symptom. The assertion would still be reading rendered text; it would just
   be reading it in one particular mood. The next person who writes a guard without the pin
   reintroduces the class silently, because nothing about the code says the pin is load-bearing.
2. It is 20+ file edits for a result one comparison achieves, and every one of those edits is
   an edit to an already-applied migration (section 6).
3. A pinned block still has a failure mode: `SET LOCAL` inside a plpgsql `DO` block is scoped
   to the transaction, which is right; a bare `SET` would leak to the rest of the chain and
   silently change what migrations *after* it see. That is a new `search_path` dependence,
   not the removal of one.
4. The structural comparison is **strictly stronger**: it also pins column *order*, which the
   current `LIKE` does not. `FOREIGN KEY ("organizationId", "documentId")` reversed matches
   every pattern in the chain today.

The one thing worth doing *in addition*, and which is forward-only and cheap, is
`audit-rendered-text-assertions.ps1` (section 7): it makes the class visible on every run
instead of visible only to a careful reader.

## 5. Disposition: an amendment, ruled in by the orchestrator

**A forward migration cannot fix this.** Flyway applies versions in ascending order. The
guard that fails is inside `V20261006110000`; any migration added after it never runs. There
is no version that can be added before it without inserting a version into the middle of an
existing chain — which is a worse violation than amending, because Flyway rejects a
newly-appearing migration whose version sorts below the highest already applied
(`outOfOrder=false`), breaking every database that has stamped past it.

**Ruling received: amend the four files, and fix `V20261006100000` in the same change.**
Landed. Five files, 238 insertions, 36 deletions, all of it assertion-only:

| file | change |
| --- | --- |
| `V20261006110000` | FK shape compared structurally instead of by `LIKE` on the rendering |
| `V20261006120000` | same, for `api_keys_service_principal_tenant_fkey` |
| `V20261006140000` | same, for both subject FKs (two blocks) |
| `V20261007100000` | same, for `customer_cx_sequences_organizationId_fkey` |
| `V20261006100000` | the inert old-form detector is normalised, given a **positive** counterpart assertion, and its self-test now renders the pre-batch expression *in this session* instead of comparing against a hard-coded string |

**Why this is safe, specifically.** No DDL, no data statement, no object added or dropped.
Section 6 measures the proof: the fingerprint is `bf31e9f102be19a6c373eb353b2d2131` before and
after, and all eleven counts identical. No recorded Flyway checksum can drift, because none
is recorded anywhere — `firstelement` is unstamped and the first Flyway boot is Phase 12.

**The `V20261006100000` fix in detail.** It cannot be made structural, because "the old form
is absent" is a negative claim about an expression rather than a claim about a column list.
So the rendering is normalised and the pattern is then *proved live in the environment the
chain actually runs in*:

```sql
definition_norm := regexp_replace(coalesce(definition, ''),
                                  '[A-Za-z_][A-Za-z_0-9$]*\."', '"', 'g');
```

The strip is deliberately narrow — a schema qualifier in front of a quoted identifier, and
nothing else. It cannot turn one expression into another, introduce or remove a literal, or
hide a changed column; it only makes a type name read the same either way. Two assertions
were added so the pair is symmetric: the **new** `= ANY (ARRAY[…])` form must be positively
present (otherwise "the old form is absent" is also satisfied by a definition that is
neither form), and the self-test now installs the pre-batch expression on a `pg_temp` probe
table and requires **this session's own rendering** to match the detector. Measured:

| | `search_path` default | `search_path` empty |
| --- | --- | --- |
| detector fires on the pre-batch form | `true` | **`true`** (was `false`) |
| detector matches the corrected form | `false` | `false` |
| new-form assertion matches the corrected form | `true` | `true` |

### 5a. The merge is NOT complete — this is outside my ownership

Two things must land in the **same commit**, and I cannot make either:

1. **`backend/database/src/main/resources/db/migration/**` must be updated to match.**
   `FlywayBaselineAcceptanceTest` asserts the two copies are byte-identical in both
   directions, and `backend/**` is outside the database agent's globs. Without this the
   acceptance test fails and the gate goes red for a reason that has nothing to do with the
   fix. This is the single most important follow-up.
2. **`docs/migration/flyway-runbook.md` §1 carries a SHA-256 per chain file.** Four of them
   are now stale (`…10000`, `…120000`, `…140000`, `…7100000`; `…100000` also changed). That
   file is outside my globs. Re-stamp from the amended files:

   ```powershell
   Get-ChildItem ops\flyway\sql\V*.sql | Sort-Object Name | ForEach-Object {
       '{0}|{1}' -f $_.Name, (Get-FileHash -Algorithm SHA256 $_.FullName).Hash.ToLower()
   }
   ```

Until both are done the repository is **not** in a committable state.

## 6. Measured postBoot figures from a clean database

Clean database, `CREATE DATABASE ... TEMPLATE template0`, all 24 migrations concatenated into
one `psql` session (the gate's shape, and the shape Flyway produces on one connection),
**the chain as amended**:

| measurement | before the fix | after the fix | `expected-state.json` `postBoot` |
| --- | --- | --- | --- |
| migrations applied | **18 of 24** | **24 of 24** | 24 |
| tables | *aborted* | **98** | 98 |
| enums | *aborted* | **57** | 57 |
| check constraints | *aborted* | **422** | 422 |
| foreign keys | *aborted* | **223** | 223 |
| primary keys | *aborted* | **98** | 98 |
| indexes | *aborted* | **394** | 394 |
| unique indexes | *aborted* | **205** | 205 |
| unique constraints | *aborted* | **0** | 0 |
| triggers | *aborted* | **11** | 11 |
| nullable `projectId` columns | *aborted* | **37** | 37 |
| `V1` schema fingerprint | *aborted* | **`bf31e9f102be19a6c373eb353b2d2131`** | `bf31e9f102be19a6c373eb353b2d2131` |

**The fingerprint is unchanged.** That is the evidence the amendment is state-neutral: same
objects, same constraints, same defaults, byte-for-byte the same schema contract.

Independently reproduced on the same database: index census `209` tenant-led / `15`
project-led / `22` parent-key / `142` unique-other / `5` global / `1` vector-led; pgvector
`0.8.6` with 1 HNSW and 1 `vector_cosine` index; `V4` reports `not_cascade_on_update = 0`
and `unsafe_on_delete = 0`; the 11 triggers are the five functions named in
`expected-state.json`; tables without `organizationId` are `organizations`,
`sla_calendar_windows`, `sla_calendar_holidays`.

**Does the chain apply cleanly from empty with no manual intervention? YES.** Both shapes,
both re-measured after the amendment:

- **One session** (`verify-clean-bootstrap.ps1`, `template0`, `ON_ERROR_STOP=1` and nothing
  else): 24/24, exit 0, 11/11 measurements OK.
- **One `psql` process per migration** (`rehearsal-chain.ps1`): 24/24, exit 0, 98 tables.
- **The orchestrator's original command, unchanged and with no `-FlywayDir`** —
  `prepare-throwaway-db.ps1 … -Database firstelement_gate_migration_check -MigrateChain
  -Dispose` — now exits **0** and applies all 24. Before the fix it exited 3.

**Does the audit still catch the class? YES, and it is not vacuous.** After the amendment,
`audit-rendered-text-assertions.ps1` reports **0 shipped predicates that flip**, while the
five **removed** legacy patterns still flip `true → false`. If the fix were reverted, those
five would be counted as shipped and the audit would exit 1.

## 7. What was added (forward-only, inside `ops/flyway/**`)

| file | what it does |
| --- | --- |
| `verification/verify-clean-bootstrap.ps1` | **The chain rehearsal.** Applies the whole chain to a clean `template0` database **in one session**, then asserts all eleven `postBoot` measurements against `expected-state.json`. Refuses a non-disposable name and the protected database. `-FlywayDir` measures a candidate chain. A missing count is a failure, never a silent pass. |
| `verification/audit-rendered-text-assertions.ps1` | The defect detector for this class. Static pass: every catalogue-rendered read in the chain, classified `structural` / `renormalised` / `rendering-stable` / `search_path-sensitive`, with file and line. Live pass (`-Database`): evaluates each **shipped** predicate under both `search_path` settings and exits 1 while any flips — and probes the **removed** patterns too, so a green run proves the audit still bites. |
| `verification/rehearsal-chain.ps1` | **Changed, not added.** Demoted to the second shape in its header, with the reason: its per-file invocation resets `search_path` and is structurally incapable of seeing this class. Still run, for the per-migration transaction boundary the one-session shape cannot exercise. |

Exact commands:

```powershell
# 1. THE CHAIN REHEARSAL -- the gate's own shape, from template0
.\ops\flyway\verification\verify-clean-bootstrap.ps1 `
    -Container firstelement-postgres -Database firstelement_cleancheck_boot
#    -> applied 24 migration(s), exit 0; 11/11 measurements OK; exits 0

# 2. the second shape -- per-migration transactions
.\ops\flyway\verification\rehearsal-chain.ps1 `
    -Container firstelement-postgres -Database firstelement_cleancheck_probe
#    -> applied 24, tables=98, exits 0

# 3. the standing detector -- static inventory, no database needed
.\ops\flyway\verification\audit-rendered-text-assertions.ps1 -StaticOnly
#    -> 44 rendered-text reads; 0 search_path-sensitive, 1 renormalised,
#       17 rendering-stable, 6 structural

# 4. the standing detector, measured
.\ops\quality\checks\prepare-throwaway-db.ps1 -Database firstelement_audit_check `
    -Container firstelement-postgres -MigrateChain
.\ops\flyway\verification\audit-rendered-text-assertions.ps1 `
    -Container firstelement-postgres -Database firstelement_audit_check
#    -> shipped predicates that flip: 0;  removed patterns that flip: 5; exits 0

# 5. the orchestrator's original command, unchanged
.\ops\quality\checks\prepare-throwaway-db.ps1 -Container firstelement-postgres `
    -DbUser firstelement -ProtectedDatabase firstelement `
    -Database firstelement_gate_migration_check -MigrateChain -Dispose
#    -> applied 24 migration file(s); exits 0   (was exit 3)
```

All disposable databases used in this phase (`firstelement_gate_migration_check`,
`firstelement_gate_cleancheck`, `firstelement_cleancheck_boot`, `firstelement_cleancheck_probe`,
`firstelement_audit_check`, `firstelement_render_probe`, `firstelement_probe_render`,
`firstelement_oldform_probe`, `firstelement_detector_check`) were dropped. `firstelement`
was never the target of any migration command, and was re-measured at 45 tables with no
`flyway_schema_history` row after the last one.

## 8. Reported, not fixed

1. **`V1_schema_fingerprint.sql` is `search_path`-dependent** (`bf31e9f1…` at the default
   `search_path`, `29787b8a0d5df64e61b0c4b2df389177` when empty). Harmless today because
   `run-verification.ps1` always opens a fresh session per query, and the recorded value
   reproduced twice in this phase on two different databases. It is the last
   session-dependent value in the verification set. Hardening requires a `-q` on the
   runner's psql call so the pinning `SET` adds no command tag to the hashed output, and
   then re-recording one fingerprint across phases this agent does not own — so it is a
   ruling, not a change.
2. **`rehearsal-chain.ps1` cannot see this class.** Its header now says so and names
   `verify-clean-bootstrap.ps1` as the chain rehearsal. What is still missing is anyone
   *wiring* `verify-clean-bootstrap.ps1` into the gate as a required step; that is
   `ops/quality/**`, outside my globs.
3. **`ai_policies.customerVisibleRequiresApproval` is `boolean NOT NULL DEFAULT true`** at
   `V20261005100000__ai_policy_and_governance.sql:298` — inside `CREATE TABLE "public"."ai_policies"`
   (which opens at line 282), **not** `ai_tool_registry`, which has no such column. Verified
   on a migrated database: `pg_get_expr` on the default returns `true`. It is a **per-policy,
   per-tenant row default**, so every AI policy row a tenant creates starts out refusing
   every customer-visible reply until an operator opts out per policy. Defensible as
   deny-by-default, but it behaves as a global kill switch on anything that reads the column
   without an explicit opt-in path. Worth an explicit ruling on whether the send gate treats
   "unset" as "approved" or "refused". AI governance — reported, not touched.
4. **Four chain digests in `flyway-runbook.md` §1 are stale** (section 5a), and the packaged
   copy under `backend/**` is out of step with `ops/flyway/sql/**`. Both outside my globs;
   the second one will fail `FlywayBaselineAcceptanceTest` until it is done.

## 9. Rollback / recovery

**Reverting the amendment.** Nothing was deployed, so this is a `git` operation, not a
database one:

```powershell
git checkout -- ops/flyway/sql
git checkout -- backend/database/src/main/resources/db/migration
```

Then re-apply the two out-of-glob follow-ups in reverse (restore the four runbook digests
from the pre-amendment `git show`). No database needs undoing: no migration ran against
anything that persists.

**If a chain that already applied has to be undone.** Every migration in this chain is
transactional, so a failed apply leaves the database at the last successful version and
Flyway records the failure. To get a clean target again: `DROP DATABASE` and recreate from
`template0` — never `repair` a chain whose files changed underneath its history, and never
reuse a failed migration's checksum (`docs/migration/flyway-runbook.md` §9c). The armed
undo scripts for the three removable slices remain `rollback/undo_phase14.sql` →
`rollback/undo_phase10b.sql` → `rollback/undo_phase7_10a.sql`, in that order, and are
unaffected by this amendment because the amendment changed no object.

**Recovery path if the amendment turns out to be wrong.** The five changed blocks are
assertions inside `DO` blocks that run after their `ALTER`/`CREATE`. If one is wrong, the
migration fails, its transaction rolls back, and the database is unchanged — the failure
mode is a refused migration, never a half-applied constraint.