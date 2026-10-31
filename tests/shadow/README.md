# tests/shadow/ — Phase-12 shadow-read parity harness

This tree is the evidence behind `plan.md` Phase 12 steps 1 and 2: the frozen
legacy read path and the new Spring read path are run **at the same time,
against the same rows, in two separate disposable databases**, and what each
one actually answered is compared.

Run it with:

```powershell
.\ops\quality\checks\check-shadow-parity.ps1 -JsonOut build\shadow-parity.json
```

It takes roughly three minutes and it **exits non-zero today**. That is the
correct result: the comparison found three real differences between the two
runtimes, and one of them is a cross-project data disclosure in the legacy
runtime. See [`docs/migration/phases/phase-23-shadow-parity.md`](../../docs/migration/phases/phase-23-shadow-parity.md).

---

## The failure mode this harness is built around

A parity harness has one failure mode worse than a false pass: a harness whose
checks **cannot fail**. A count compared against a constant, a filter both
sides happen to satisfy for unrelated reasons, a normalisation that erases the
field it was written to examine — each produces a permanent green that says
nothing, and each looks exactly like success in a CI log.

So every check here carries a `redCondition` naming the input that turns it
red, and `check-shadow-parity.ps1` **proves** each one by perturbing that input
and requiring the verdict to flip. A perturbation whose checks stay green is
reported as a **harness defect** and fails the gate. A green comparison with no
demonstrated red is reported as `unproven`, never as `pass`.

Two classes of perturbation, distinguished everywhere they appear:

| Class | What it does | What it proves |
| --- | --- | --- |
| `runtime` (PN-01..05, 07, 08) | mutates a row in ONE database and re-runs the comparison | the check is derived from real data, not from a constant |
| `detector` (PN-06, PN-09) | feeds the comparator a doctored capture | the detector fires on a branch a database edit cannot reach. **Not** evidence about either runtime |

---

## What makes the comparison a comparison and not a tautology

Three properties, each of which had to be arranged deliberately:

1. **Both runtimes really run.** The legacy side is `apps/api` compiled with
   `tsc` from source into `tests/shadow/build/` and booted as a real Nest
   application on `:4401`; the new side is the `firstelement-api` jar booted on
   `:4402`. Neither is a re-implementation and neither is a fixture.
2. **One seed file, two databases.** `dataset/seed-core.sql` is replayed into
   both, so "the same row" means literally the same row — and `CHK-4` proves it
   independently by reading the rows straight out of both databases, so a run
   where the two were seeded differently cannot report parity.
3. **No literal is ever an expectation.** Every expected value comes from the
   other runtime or from the source rows. The finance reference is computed by
   SQL over `usage_events` and the rate card, with no help from the application.
   Where no legacy value exists, the entry is recorded as **un-comparable** with
   a structural reason, never reconciled to a number someone wrote.

The route inventories used to justify each un-comparable entry are read from
what each runtime says about itself — the NestJS boot log, and the new runtime's
`/actuator/mappings` — so a "missing route" is never inferred from a 404, which
is also what a denial looks like.

---

## Layout

```
tests/shadow/
  README.md                     this file
  dataset/
    seed-core.sql               3 tenants, 4 projects, 4 users, 5 customers,
                                8 conversations, 9 messages, 5 appointments ...
                                fixed uuids, fixed timestamps, synthetic only
    seed-new-only.sql           rate card + usage ledger; Spring database only
    credentials.json            the synthetic password, published on purpose
  scenarios/
    reads.json                  42 requests + 4 legacy-only + 2 spring-only,
                                each with the reason it exists
    transitions.json            the status-transition sequence and its variant
  perturbations/
    pn0*.sql / *.restore.sql    what makes each check go red, and how to undo it
  lib/http.mjs                  cookie jar, CSRF, the capture client
  capture.mjs                   drives ONE runtime through the matrix
  compare.mjs                   the eight checks and their red conditions
  transitions.mjs               replays a write sequence on both runtimes
  finance.mjs                   derives the finance reference from the source rows
  db-snapshot.mjs               reads row counts and identifier sets, read-only
  routes.mjs                    each runtime's own route inventory
  pn08-proof.mjs                PN-08's verdict: baseline capture vs perturbed
  pn08-proof.test.mjs           both states of that verdict, executed
  pn08-prefix-reproduction.ps1  the pre-fix PN-08 expression, still runnable
  fixtures/pn08/                the finance captures both states are driven from
  build/                        gitignored; the compiled legacy runtime
```

### The PN-08 verdict is runnable on its own

PN-08's flag was the one part of this harness nobody could look at, because it
lived inside a 1,000-line PowerShell gate whose every code path needs two
booted runtimes and two disposable databases. A flag that can only be evaluated
in a five-minute full-stack run is a flag that gets evaluated
five-minute-full-stack-run-never. It is now two JSON files and nothing else:

```powershell
node --test tests\shadow\pn08-proof.test.mjs
```

Both states come out of the **real** comparator (`compare.mjs` produces the
parity artefact; `pn08-proof.mjs` reads it) — nothing in that test
re-implements CHK-7.

---

## The checks

| Id | What it compares | Demonstrated red by |
| --- | --- | --- |
| CHK-1 | list-read counts **and identifier sets** (uuid, cxId, ticketNumber) | PN-01, PN-02 |
| CHK-2 | allowed-read bodies, field for field | PN-03 |
| CHK-3 | authorization decisions, and that a denial discloses nothing | PN-04, PN-06 |
| CHK-4 | source rows read straight out of both databases | PN-01, PN-02, PN-05 |
| CHK-5 | no credential crossed a capture; no request failed in transport | — (guard, not a comparison) |
| CHK-6 | the state a status-transition sequence leaves in each database | PN-07 |
| CHK-7 | derived revenue against a derivation from the source rows | PN-08 |
| CHK-8 | ADR 0007 Part 1 invariants on every stored snapshot | PN-09 |

---

## The databases

`firstelement` is production-shaped and is **never** written to. The harness
creates `shadow_check_legacy` and `shadow_check_spring`, whose names carry
`shadow_check` so they cannot be one typo away from anything real, applies the
verified legacy baseline to the first, lets the new runtime migrate the second
from empty through its own Flyway chain, seeds both, and drops both on the way
out — including on failure. `db-snapshot.mjs` re-checks the name before it
connects and has no code path that can write.

Both databases are disposed at the end of every run, and a database left behind
is reported as a gate failure rather than a warning: the next run's counts
would include this run's rows.

---

## What the harness learned about itself

Three of the harness's own bugs are recorded here because each one produced a
**false finding about the cutover candidate**:

1. **A reused CSRF token.** The new runtime's CSRF token is single-use and its
   refusal is a `403` whose body reads `CSRF_TOKEN_INVALID` — the same status a
   permission refusal uses. Reusing one token made a three-step transition
   sequence report `403` on steps 2 and 3, which read exactly like "the new
   runtime refuses to resolve a conversation". It now refreshes the token before
   every unsafe request, and a CSRF refusal is **raised** rather than recorded.
2. **A cookie header built before the refresh**, so the request carried the new
   token in the header and the old one in the cookie. Same symptom, different
   cause.
3. **A soak probe that called every thrown status "no answer."** The legacy
   runtime has no `/api/status` route and answers `404`; the probe reported
   "NO ANSWER" and the gate said **STALL OBSERVED**. That is precisely the
   confusion ADR 0013 §4 exists to prevent, committed by the detector. A status
   is an answer; only an exception with no response object is a stall.

A fourth, PN-08, tried to prove the finance derivation by mutating a rate card
markup. `firstelement_append_only()` refused it, correctly — the entire finance
ledger is immutable by design. The perturbation is now additive, which is the
only kind the schema permits.

The rule these four have in common, and the reason they are written down rather
than quietly fixed: **a harness that cannot tell a control from its own bug is
worse than no harness.**