# Phase 14 — the customer reference namespace (P0, ADR-0014)

Status: **schema complete and verified**. The dispatched `crm-core` code change is
**not** part of this phase and is not yet landed.

| | |
|---|---|
| Defect | CX-1 — `customers."cxId"` globally unique + project-scoped `count(*)` allocator + 5 retries |
| Consequence | at most **five** customer references could ever exist across the whole deployment; the sixth project's first inbound message answers 500, permanently |
| Migrations | `V20261007100000__customer_cx_sequences.sql`, `V20261007110000__customers_cx_id_tenant_scope.sql` |
| Verification | `ops/flyway/verification/V16_customer_cx_sequence_slice.sql` |
| Negative controls | `ops/flyway/verification/rehearse-phase14.ps1` — 1 positive + 8 negative |
| Undo | `ops/flyway/rollback/undo_phase14.sql` — rehearsed by `rehearse-phase14-undo.ps1`, 63 assertions |
| Measured postBoot | tables **98**, enums **57**, CHECKs **422**, FKs **223**, PKs **98**, indexes **394** (205 unique), fingerprint **`bf31e9f102be19a6c373eb353b2d2131`** |

---

## 1. What was wrong, reproduced rather than restated

ADR 0014 states the defect from the source. I reproduced it end to end on a
throwaway carrying the pre-migration schema, with the legacy allocator's shape
written out verbatim — a project-scoped `select count(*)` as the base, then a
walk forward over `customers_cxId_key` for `MAX_IDENTITY_ATTEMPTS = 5`:

```
project 1 : granted #33000000
project 2 : granted #33000001
project 3 : granted #33000002
project 4 : granted #33000003
project 5 : granted #33000004
project 6 : *** THREW after 5 attempts (#33000000..#33000004 all taken) ***
=> distinct customer references that can EVER exist deployment-wide: 5
```

And the same six organizations **after** the two migrations, each taking its first
reference:

```
org 1 first cxId = #33000000
org 2 first cxId = #33000000
org 3 first cxId = #33000000
org 4 first cxId = #33000000
org 5 first cxId = #33000000
org 6 first cxId = #33000000
```

That second block is also the cross-tenant-inference property in one line: every
tenant starts at `#33000000`, so no tenant can read another's volume off its own
numbering. `V16` §6e asserts it.

---

## 2. The two files

### `V20261007100000` — the allocator's missing state

`CustomerRepository.nextCxSequence` was asked for a fact that **is not derivable
from the rows** — what has this tenant *already issued* — and it answered with the
wrong number (rows that still exist) rather than the right one. This file holds
that fact explicitly.

```sql
CREATE TABLE "public"."customer_cx_sequences" (
    "id"                      uuid NOT NULL,
    "organizationId"          uuid NOT NULL,
    "lastAllocatedCxSequence" bigint NOT NULL,   -- CHECK 0 .. 999999
    "createdAt"               timestamp(3) without time zone NOT NULL
                                     DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC'),
    "updatedAt"               timestamp(3) without time zone NOT NULL
                                     DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC'),
    CONSTRAINT "customer_cx_sequences_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "customer_cx_sequences_sequence_range_check"
        CHECK ("lastAllocatedCxSequence" BETWEEN 0 AND 999999)
);
```

The column is **named for what it holds** rather than called `next` or `count`:
it is the highest six-digit sequence this organization has *ever been issued*, so
the next reference is this plus one and anything at or below it has been given
away. `0` means `#33000000` was the first reference issued; a **missing row** means
nothing has been issued yet. Both facts are load-bearing and neither is visible
from the type, which is why `V16` §4c asserts the *set of bigint column names*
rather than the presence of one: a second bigint is the one place a future author
could put a count and quietly reintroduce this defect.

The allocator is one statement:

```sql
INSERT INTO "public"."customer_cx_sequences" AS s (...)
VALUES (gen_random_uuid(), p_organization_id, 0, ...)
ON CONFLICT ("organizationId") DO UPDATE
    SET "lastAllocatedCxSequence" = s."lastAllocatedCxSequence" + 1
RETURNING "lastAllocatedCxSequence" INTO allocated;
```

* **Atomic.** The `DO UPDATE` takes a row lock on the tenant's counter, so two
  concurrent inbound messages in one organization serialise and receive different
  values. There is no read-then-write anywhere, so there is no window for a second
  writer to slip into.
* **No `count(*)` anywhere** — not in the file, not in the function, and `V16` §4
  asserts the **installed** `pg_get_functiondef` contains none.
* **VOLATILE**, which is what a planner must assume. Marked `STABLE` or `IMMUTABLE`,
  a planner may collapse two calls in one statement into one and hand the same
  reference to two customers: the defect re-entering through the function header.
  `NEG-4` proves the assertion fires.
* The `UNIQUE INDEX` on `("organizationId")` alone is the `ON CONFLICT` arbiter, so
  it is load-bearing for correctness rather than decoration.

**Why six digits, not eight.** `customers."cxId"` is `varchar(9)` in the published
format `#33` + six digits (`TicketNumber.generateCxId`; every row in the verified
live database is `#33000000`…`#33000002`). So the counter's domain is `0 .. 999999`.
Storing the full eight-digit body instead would invite a caller to pass `33000002`
into a function that pads to six digits and produce `#3333000002` — thirteen
characters, silently wider than the column.

**The ceiling is real and stated**: 1,000,000 references per organization
(`#33000000`–`#33999999`). The `CHECK` refuses allocation past it rather than
emitting a reference the column cannot hold. Contrast that with the five the defect
allowed. The `EXCEPTION WHEN check_violation` handler turns the raw constraint name
into an actionable message and, because the subtransaction is rolled back first,
**consumes nothing** — an exhausted tenant does not burn a gap.

### `V20261007110000` — backfill first, then rescope

The order is load-bearing:

1. **Format census, refuse-first.** Everything downstream parses `"cxId"`, so an
   unparseable value stops the migration rather than being coerced.
2. **Backfill from `max("cxId")` per organization** — while the global key still
   holds.
3. **Assert** no organization with customers is left without a counter, and that no
   counter sits below its live maximum.
4. Only then **drop** `customers_cxId_key` and **add**
   `customers_organizationId_cxId_key UNIQUE ("organizationId","cxId")`.

Step 2 must precede step 4 for a specific reason. While the global key holds,
every value ever written is present and no other value is insertable, so
`max("cxId")` is a true high-water mark over everything currently storable. Once
the global key is gone, values at or below it become insertable — including values
the counter is about to hand out again.

**No `count(*)` here either.** The backfill reads `max("cxId")`; a count would only
measure how many rows survive, which is the quantity ADR-0014 names as the wrong
one.

**One check is deliberately absent.** A "the six-digit sequence is within 0–999999"
test would be a tautology: anything matching `^#33[0-9]{6}$` is in range by
construction. It is not written, and the tautology is named in the header because a
check that cannot fail is the failure mode that survives review — the same class
the Phase-10b negative controls found in `V15`'s D4 section.

**Column order** is `("organizationId","cxId")` and not the reverse, for two
operational reasons: it serves the lookup every screen performs ("this tenant's
customer with reference #33000042"), and it classifies as tenant-led in `V9`'s
census. The reverse order would be a global unique index again in everything but its
name — `NEG-7` creates it and `V16` refuses it.

**The one DROP in the batch** is written without `CASCADE`, and an explicit
`pg_constraint.conindid` check runs first so a dependency failure *names* the
dependent instead of reporting a bare error. Measured on the verified live database:
zero dependents, because nothing foreign-keys on `("cxId")` — the four FKs that
point at `customers` all target `("id")`.

### What neither file does

No `DROP`/`RENAME` of any table, column or index except the one constraint
replacement. No `NOT NULL` anywhere, and none on `ai_run_*` (ADR-0009 §2). No secret,
token or credential of any kind. No `projectId` on the counter — a customer's
identity is an organization-level thing, and a counter with a project column would
invite the very per-project exhaustion being removed. No `version` column: nothing
edits a counter except the allocator, which serialises itself with a row lock, so a
version column would advertise an optimistic-concurrency protocol with no second
writer.

---

## 3. The defect the backfill cannot fully remove — stated, not assumed

`max("cxId")` is read from rows that **still exist**. A customer that once held a
reference and has since been **deleted** leaves no trace in this schema: no table
records the deletion of a customer reference, and nothing foreign-keys on
`("cxId")`. So for a tenant that has deleted a high-numbered customer, the true
"ever issued" maximum can be higher than `max("cxId")`, and this backfill cannot
know that.

What it guarantees is what is checkable: **no live reference is reissued.** What it
cannot do is silently claimed to cover history. The migration therefore **reports**
`gaps_below_each_tenant_maximum` on every run — the number of reference values
between the format floor and each tenant's maximum that no surviving customer holds
— and **does not assert it to be zero**. Measured **0** on the verified live
database, alongside `organizations_with_customers = 1`, `with_a_counter = 1`,
`would_otherwise_start_at_zero = 0` (asserted 0).

**Follow-up F-14-1**: an append-only ledger of issued references, seeded once from
an export, so the high-water mark is reconstructible rather than inferred. Until then
the allocator is airtight from the moment it starts — `V16` §6d proves no reuse after
deletion — and the residual is confined to references deleted **before** this
migration.

---

## 4. `V16` — and what it cost to make it valid on both profiles

### Both profiles, measured

| Profile | Database | Result |
|---|---|---|
| **preBoot** | `firstelement` (dev, unstamped, 45 tables) | gate **PASSED**; `V16` prints `PHASE10B`→`PHASE14_NOT_PRESENT`; every section reports NOT_PRESENT; psql **exit 0**; fingerprint `66eeaadab4ad28a8f8eaf8fe1a0748f9` |
| **postBoot** | throwaway, 24 migrations | bundle **PASSED**; `V16` prints `PHASE14_PRESENT` and its full census; every assertion holds; psql **exit 0**; tables **98**, enums **57**, CHECKs **422**, all three asserted; fingerprint **`bf31e9f102be19a6c373eb353b2d2131`** |

Every section is guarded on `to_regclass('public."customer_cx_sequences"') IS NULL →
RAISE NOTICE; RETURN` inside a `DO` block, which works because plpgsql prepares
statements **lazily** — re-measured on this host with the three-case probe before
the file was written, exactly as `V12`/`V14` do. Sections 1, 1b, 2 stay top-level
and read only catalogs. `EXECUTE` is used only where a per-row census must escape a
guarded body.

### §6 writes, and every other section in the bundle does not

The capacity, monotonicity and no-reuse proofs need writes, so §6/§7 are the only
part of the bundle that writes, and they are wrapped in
`BEGIN; DO $$ … $$; ROLLBACK;`. Leaving a trace is **structurally** impossible: a
`RAISE` aborts psql and the disconnect rolls back, a kill rolls back, and there is no
`COMMIT` and no DDL outside the transaction. Nothing irreversible is involved either
— the probe rows use `gen_random_uuid()`, and there is not one `nextval` default
anywhere in this schema (measured, not assumed). **§7 asserts it from the row counts
after the rollback**, not from the fact that the file contains the word `ROLLBACK`:

```
V16 7: after the rollback, 0 zz-cx-probe rows remain in organizations, projects or
customers, and 0 counter rows reference a missing organization.
```

Operational note: it takes a `RowExclusive` lock on `organizations` / `projects` /
`customers` for one short transaction. **Run it against dev or staging, not
production.**

The scenarios seed themselves from nothing, inside the transaction, so the assertions
do not depend on an externally prepared fixture — "assert the capacity regression on
a throwaway with seeded rows" is only a proof if the seed is the *right* seed.

---

## 5. Negative controls — 1 positive, 8 negative, all correct

`rehearse-phase14.ps1`. Each control breaks **exactly one** thing on a fully
migrated, populated database and asserts that the slice goes red **with the intended
assertion**, not merely that something failed. The positive control runs first: a
negative control is only evidence if the unmodified database is green to begin with.

| Control | Broken | Exit | Assertion that fired |
|---|---|---|---|
| positive | nothing (intact 24-migration chain, 3 customers) | **0** | none — PASS, full census printed |
| NEG-1 | global `customers_cxId_key` restored | **3** | §1b `the GLOBAL customers_cxId_key is STILL PRESENT, which is the CX-1 defect itself` |
| NEG-2 | counter corrupted below the live maximum | **3** | §5 `2 customer row(s) sit ABOVE their organization's counter, so the next allocation would reissue a LIVE reference` |
| NEG-3 | counter row deleted for an org with customers | **3** | §5 `1 organization(s) hold customers but no counter` |
| NEG-4 | allocator marked `IMMUTABLE` | **3** | §4 `is marked "i" instead of VOLATILE` |
| NEG-5 | format-ceiling `CHECK` dropped | **3** | §4 `customer_cx_sequences_sequence_range_check is "(absent)"` |
| NEG-6 | composite key made **PARTIAL** | **3** | §3 `is PARTIAL` |
| NEG-7 | composite key columns **REVERSED** | **3** | §3 `indexes ("cxId", "organizationId"), which is not ("organizationId", "cxId") in that order` |
| NEG-8 | counter table dropped (migration 2 without migration 1) | **3** | §1b `HALF-APPLIED Phase-14 state … the per-organization key is installed but the counter table is absent` |

### NEG-8 found a real gap in my own slice, and it is now closed

**The first NEG-8 run exited 0.** With `customer_cx_sequences` dropped while the
rescoped key survived, §1's presence row correctly fell through to
`PHASE14_PARTIAL` — and the slice then exited 0. A half-applied chain passing the
bundle silently is exactly the outcome the presence row exists to prevent.

Fixed by adding **§1b**: a guarded block that recomputes the same state machine and
**raises** on `PHASE14_PARTIAL`, naming the specific cause. This is the Phase-7-10a
undo lesson applied to my own slice — printing `PARTIAL` is a claim, aborting is
evidence.

### NEG-1's specific assertion is now defence-in-depth, and labelled as such

With §1b in place, restoring the global key makes the state `PARTIAL`, so §1b fires
first and §3a is unreachable in that state. §3a was **kept** (it is a schema check
behind a chain-state check, and they can fail independently) but its comment now says
so honestly, and the control records that it fires §1b rather than claiming a test it
did not run.

---

## 6. Four PostgreSQL traps, none visible from reading the SQL

All four were found by the rehearsal, not by review, and all four are recorded in
the migration headers.

| # | Trap | Symptom | The rule it teaches |
|---|---|---|---|
| 1 | `substr("cxId", 3)` reads **seven** characters | `#33` occupies positions 1–3, so the six digits start at **4**. The offset-3 version multiplied every parsed value by ten and the migration refused: `violates check constraint customer_cx_sequences_sequence_range_check` | an off-by-one digit always pushes the parse above 1000000, so the `CHECK` fires for **every** row. A silent misparse became a refused migration |
| 2 | `to_regclass('public.customers_cxId_key')` returns **NULL** for a name that exists | the `regclass` input parser applies SQL identifier rules, downcasing the unquoted mixed-case name to `customers_cxid_key`. The step-4 guard then **refused with a plausible message on a database where the index was plainly present** | every mixed-case name in a `regclass` argument needs inner double quotes. ADR-0002's explicit-mapping rule arriving through a function argument |
| 3 | `pg_get_indexdef(idx, n, true)` **quotes the column** and returns `''` for a missing position | renders `"organizationId"`, so an unquoted comparison never matches; position 3 of a two-column index is `''`, not `NULL`, so `IS NULL` can never fire while `IS NOT NULL` fires on a correct index | take arity from `pg_index.indnkeyatts`, identity from the quoted rendered form |
| 4 | `pg_indexes.indexdef` renders `btree` **unquoted** | the undo script's post-condition expected `USING "btree" ("cxId")`, matched nothing, and reported `PHASE14_UNDO_INCOMPLETE` **having done its work correctly** | a no-op assertion is a failure mode wearing the costume of a safety check |

Trap 2 is the most dangerous shape of the four: it produced a *correct-looking
refusal* for the wrong reason, and it would have refused on **every** database in the
deployment. A guard that refuses for the wrong reason is better than one that passes
for the wrong reason — it stopped the migration — but it is still a defect.

### One behaviour I did NOT root-cause

`V16` §4's second `pg_proc` lookup originally failed with `column p.proname does not
exist`, on a query that is correct, that runs on its own, and that also runs
correctly when the two `pg_proc` queries are executed together in isolation. The
alias-free form (`pronamespace = 'public'::regnamespace`, no join) works and reads
better, and that is what shipped. **The behaviour was not fully explained** and is
recorded as such in the file rather than presented as understood. The lesson is the
project's own: *an instrument's exclusions must be verified by breaking the thing
they exclude, not by reading them.*

---

## 7. Undo — four states, 63 assertions, all green

`ops/flyway/rollback/undo_phase14.sql`, rehearsed by
`rehearse-phase14-undo.ps1`. **Inside out: it runs FIRST**, before `undo_phase10b.sql`
and `undo_phase7-10a.sql`.

| State | Expected | Measured |
|---|---|---|
| **1. Unarmed**, populated database | clean no-op, exit 0, state unchanged | exit **0**, `UNDO PHASE14 SKIPPED`, all 11 state keys unchanged, fingerprint unchanged |
| **2. Armed**, two tenants share `#33000000` | **REFUSED**, exit non-zero, state unchanged | exit **3**, `PHASE14_UNDO_REFUSED … held by more than one organization`, all 11 keys unchanged, fingerprint unchanged, counter table + composite key both survived, and the global key was **not** created |
| **3. Armed**, cutover-shaped (one tenant, 3 customers) | removed, previous state restored **exactly** | exit **0**, `PHASE14_REMOVED`, **97 tables / 57 enums / 421 CHECKs / 222 FKs / 97 PKs / 392 indexes**, the 3 customers survived, fingerprint returned to the Phase-10b-only reference |
| **4. Chain re-applies** on top of its own undo | both migrations apply, counter re-backfilled | both exit **0**, **98 tables / 422 CHECKs**, composite key and counter table back, fingerprint returned to the Phase-14 value, counter re-backfilled to **2** from `max("cxId")` |

The three defences are the established ones: `\set ON_ERROR_STOP on`; the destructive
section gated on a psql variable set by `\gset` **before** the guards, so a refusal
means the drops are never *started*, not merely skipped; and a post-condition that
reads the state back from the catalog. The rehearsal asserts the **exit code** as
well as the state, because "REFUSED" is a claim and a non-zero exit plus an unchanged
row count is evidence (ADR-0009 §5).

**Two measured guards:**

* a counter **above** its organization's live maximum → references were issued and
  their holders deleted; removing the counter would let every one be reissued, and
  any surviving URL, export, audit record or invoice naming a deleted customer's
  reference would resolve to the wrong person;
* two organizations holding the **same** reference value → `customers_cxId_key`
  cannot be recreated at all, and the script names the colliding tenants.

Undoing **re-introduces the CX-1 capacity limit on purpose** — the alternative, a
cutover blocked on a schema mistake, is worse — which is exactly why the two guards
exist.

---

## 8. A slice only ever run post-boot is untested against the state it claims to handle

`V15` shipped last phase with ~50 unguarded statements and failed on the pre-boot
profile. This one was designed for both and **tested on both before it was accepted**
— but the honest statement is that `V16`'s §6 writes, which is the one thing that
could not be checked by reading, was checked by running §7 immediately after it and
by the eight negative controls. The pre-boot path was measured by the quality gate
itself, on the dev database, not by a rehearsal.

---

## 9. Blocked, and what I could not run

**`./gradlew.bat :backend:database:test` could not be run on this host.** The Gradle
daemon starts, binds to `127.0.0.1`, and the client then cannot connect:

```
Daemon server started.
* What went wrong: Could not connect to the Gradle daemon.
```

`.\gradlew.bat --no-daemon help` fails **identically**, so this is environmental and
not a consequence of any change. `gradle.properties` carries
`org.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8`, which forces the single-use
daemon fork that cannot be reached; `gradle.properties` is outside this agent's
ownership, so the fix is not mine to make. Three attempts, with and without
`--no-daemon`, with and without a JVM-args override.

Because the fixture could not run, `ops/flyway/verification/verify-phase14-fixture.ps1`
verifies **every property the fixture asserts** directly against the real
24-migration database — **26 checks, 26 PASS**:

* `CHAIN_FILES` names all 24 files, is in ascending order, every name 14-digit, and
  neither the reviewed nor the packaged directory contains a file it does not name;
* all 24 files **byte-identical** across `ops/flyway/sql` and
  `backend/database/src/main/resources/db/migration`;
* zero `.conf` sidecars;
* tables **98**, enums **57**, CHECKs **422**, FKs **223**, PKs **98**,
  unique **constraints** **0**, tenant-led indexes **209**, indexes **394**,
  unique indexes **205** — each read from `pg_catalog` on the migrated database;
* the Phase-14 objects: the counter table, the composite key's exact installed
  definition, the allocator's `VOLATILE` marking, the format `CHECK`, the arbiter's
  exact installed definition, and no `timestamptz`.

So the orchestrator's `./gradlew build` should confirm a state this script has already
measured, not discover it.

---

## 10. Commands

```powershell
# the two migrations, on a chain throwaway (24 files)
.\ops\flyway\verification\rehearsal-chain.ps1 -Container firstelement-postgres `
    -Database firstelement_p14_chain -Keep

# the bundle against the measured numbers, both profiles
.\ops\flyway\verification\run-verification.ps1 -Database firstelement_p14_chain `
    -ExpectedEnumCount 57 -ExpectedTableCount 98 -ExpectedCheckCount 422 `
    -OutputDir C:\...\evidence\p14-postboot -ThrowOnViolation
.\ops\quality\checks\verify-schema-state.ps1 -Container firstelement-postgres `
    -Database firstelement -OutputDir C:\...\evidence\p14-preboot -ThrowOnViolation

# V16 on its own, both profiles
Get-Content -Raw ops\flyway\verification\V16_customer_cx_sequence_slice.sql |
    docker exec -i firstelement-postgres psql -U firstelement -d firstelement `
        -v ON_ERROR_STOP=1 -P pager=off -f -          # PHASE14_NOT_PRESENT, exit 0

# the eight negative controls, then the four undo states
.\ops\flyway\verification\rehearse-phase14.ps1
.\ops\flyway\verification\rehearse-phase14-undo.ps1       # 63 assertions PASS, 0 FAIL

# what the Testcontainers fixture asserts, verified directly
.\ops\flyway\verification\verify-phase14-fixture.ps1       # 26 checks PASS, 0 FAIL

# the undo itself
docker exec -i firstelement-postgres psql -U firstelement -d firstelement `
    -v firstelement_undo_armed=on -f - < ops/flyway/rollback/undo_phase14.sql
```

---

## 11. Follow-ups

| | |
|---|---|
| **F-14-1** | The customer reference high-water mark is reconstructible only from **surviving** `customers` rows. Fix: an append-only ledger of issued references, seeded once from an export. Until then the gap count is reported, never asserted zero (measured 0 today). |
| **F-14-2** | `cxIdExists` in `crm-core` is still an **unscoped** read (ADR-0014 defect list item 3): it discloses cross-tenant existence through its result *and* its latency. The schema cannot fix it; the dispatched code change must carry both tenant predicates. |
| **F-14-3** | The 1,000,000-reference-per-organization format ceiling. This is the published `#33` + six-digit format rather than a design choice, and it is 200000× the five the defect allowed — but it is a limit, and `cxId` is `varchar(9)`. Widening the published format is a product decision, not a migration. |
| **F-14-4** | `V16` §6 writes inside a rolled-back transaction, so it is safe but not read-only. It should stay in the dev/staging gate only. If the bundle is ever pointed at production, gate §6 behind an explicit opt-in **before** that happens — an opt-in that *enables* a proof, not one that silences a failure. |

### The code change depends on this landing, and the API it should call

The allocator is `public.firstelement_next_customer_cx_id(uuid) RETURNS
character varying(9)` and it is the **only** supported way to obtain a reference.
`CustomerRepository.nextCxSequence(scope)` and the `count(*) + attempt` arithmetic in
`IdentityResolver` should go, along with `cxIdExists`. Retries remain only for
genuine write contention — the retry count is no longer a capacity limit.

**Do not pass the raw counter value to `TicketNumber.generateCxId`**: that formatter
takes the six-digit sequence and pads to six digits, so a counter value must be
formatted by the database function (which does `'#33' || lpad(seq, 6, '0')`), not
re-assembled by the caller.