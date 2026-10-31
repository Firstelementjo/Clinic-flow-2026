# ADR 0012 — The schema fingerprint must not measure the migration log

Date: 2026-10-06
Status: **Accepted** (orchestrator ruling on the Phase 10b escalation `V1_schema_fingerprint.sql`)

---

## The question

Phase 10b reported that `V1_schema_fingerprint.sql` includes
`flyway_schema_history` in its column and constraint arms, while `V4`/`V5`/`V11`
do not — so the recorded `postBoot` fingerprints (`2ee0ecd6…`, `3a62e8c3…`) are
reproducible only on a database where that table is **absent**, i.e. a psql
rehearsal copy. On the real, Flyway-booted target the fingerprint would differ.

It also reported that nothing compares the fingerprint: `run-verification.ps1`
asserts the enum count, the table count and the CHECK count, and only *prints*
the fingerprint. So this was not a broken gate.

## Why it still had to be fixed

The fingerprint's own header states its purpose:

> *The fingerprint is the md5 of all of them: two databases with the same
> fingerprint are the same schema contract.*

Including the migration history table makes it prove something else. Two
databases with identical application schema but different stamping histories would
produce **different** fingerprints, and two databases with wildly different schema
that happened to be stamped identically would produce the **same** one. The
instrument was no longer measuring the thing it claimed to measure.

Worse, it made the recorded `postBoot` figure **unreproducible on the actual
target**. A fingerprint that cannot be reproduced on the database it describes is
not evidence, and a cutover run that produced a mismatch would either alarm
everyone or — because nothing asserts it — be quietly ignored. Both outcomes are
worse than the mismatch itself.

## Decision: exclude it from all three arms, and re-baseline nothing

`flyway_schema_history` is now excluded from V1's **column**, **constraint** and
**index** arms.

**No recorded fingerprint needs re-baselining**, and that is the part worth
stating: every fingerprint recorded so far was measured on a database *without*
the history table, because the rehearsal tooling applies the chain through psql.
Excluding the table therefore leaves all of them exactly valid — and makes them
reproducible on a genuinely booted database as well.

## What the fix actually required, and why the first attempt was wrong

My first attempt excluded only the column and constraint arms, which is what the
agent's report described. Measured on a copy of the dev database:

| state | fingerprint |
| --- | --- |
| before stamp, first attempt | `66eeaadab4ad28a8f8eaf8fe1a0748f9` |
| after adding `flyway_schema_history`, first attempt | **`7fae7da269a9fd488a8da175ba7fade1`** |

The fingerprint still moved. The reason is `pg_indexes`: the history table's
**PRIMARY KEY index** appears in the index arm, so a two-arm exclusion is a
half-fix that still fails its own stated purpose. With the index arm excluded too,
the same measurement gives `66eeaadab4ad28a8f8eaf8fe1a0748f9` **before and after
the stamp** — identical, which is the property the fingerprint is supposed to have.

**This is the third time in this migration that a check passed a source review and
failed a measurement** (the OpenAPI exporter's class-level `@RequestMapping`, the
tenant-SQL ratchet's blind spot, and now the fingerprint's index arm). Each time
the fix was obvious in hindsight and invisible in the text. Recorded as a standing
practice:

> **An instrument's exclusions must be verified by breaking the thing they exclude,
> not by reading them.** "I excluded it" is a claim; "the fingerprint did not move
> when I added it" is evidence. The negative control has to exercise the arm the
> author did not think about.

## Also decided

**The NOTICE-vs-result-set change in V15 is accepted.** Four census queries became
`RAISE NOTICE` lines inside their guarded blocks, because a top-level statement
cannot be guarded against a missing relation (PostgreSQL raises a *parse-time*
error). Every assertion is preserved and still reads installed definitions,
index validity, nullability or data. The cost is that a consumer parsing a
table-shaped D1/D4/D5 census out of an evidence bundle must read NOTICEs instead.
Checked: no such consumer exists in the repository today. Recorded here so that
adding one later is a deliberate act.

**Four D1 data assertions remain unexercised** by a negative control, by
construction — they are defence-in-depth against a bypassed repair and cannot fire
while the two CHECKs are installed. Recorded as untested rather than claimed as
verified, which is the distinction that matters at cutover.