# Phase 12 - Observation window protocol and sign-off record

Date: 2026-10-11
Status: **MACHINERY BUILT AND PROVEN. WINDOWS NOT STARTED. SIGN-OFF NOT GIVEN.**

## Why this document exists

`plan.md` Phase 12 step 8 requires two agreed observation windows and sign-off from the product,
operations, security and finance owners. That is elapsed time with a system under real load, and
no script can produce it. What a script *can* do is stop it being a sentence someone says after the
fact.

`ops/observation/observation-window.ps1` opens a window, samples it, and closes it. It **never
declares a window healthy** - the verdict belongs to the four owners, and a script that could
certify its own window would have the same defect as a contract edited to match a route: it would
make the conclusion contractual instead of earned.

## The signals (plan.md step 7)

Sampled per pass, from the observed database:

| Signal | Source |
| --- | --- |
| errors | `ai_runs."errorMessage"`, plus outbox and export failure counts |
| queue lag | `outbox_events` PENDING count and the age of the oldest PENDING row; DEAD rows |
| connector delivery | `outbox_messages` in FAILED |
| export failures | `export_jobs` in FAILED |
| reconciliation drift | covered by the shadow comparison's CHK-7/CHK-8, not by a runtime counter |
| AI outcomes | `ai_runs` total, finished, and errored |

A signal that cannot be read is recorded as **unavailable**. It is never recorded as zero. "We saw
no drift" and "we could not look" use the same words and mean opposite things, and a monitoring
script that silently converts the second into the first is worse than no script.

## Entry criteria - NOT YET MET

1. A **production-like database carrying the Flyway chain** must exist. It does not.
   `firstelement` is deliberately unstamped (45 tables, never migrated) and has **no `outbox_events`,
   `outbox_messages`, `export_jobs` or `security_events` tables at all**. Six of the eight signals
   are therefore unobservable against it today, and the harness says so on every sample rather than
   inventing a value.
2. The two CHK-2 divergences (case resolution 204-vs-200, and the unscoped note list) must have an
   owner decision, or be accepted and published as breaking changes.
3. The rollback window (step 4) must be rehearsed with the legacy API read-only, not merely
   available.

## Exit criteria

A window may be closed only when all of the following hold. The script enforces the first three and
**refuses** otherwise, naming what is missing:

1. at least 8 samples;
2. **no sample with any unavailable signal** - a window with a blind spot is not a window;
3. no sampling gap longer than 240 minutes;
4. `outbox_events` DEAD count does not rise across the window;
5. `ai_runs."errorMessage"` count does not rise across the window.

Criteria 4 and 5 are the ones a human reads. The script deliberately does not judge them, because
"does not rise" needs a baseline judgement that belongs to the owners.

## How to run it

```powershell
.\ops\observation\observation-window.ps1 -Start
# ... let it run, sampling on a schedule ...
.\ops\observation\observation-window.ps1 -Sample
.\ops\observation\observation-window.ps1 -Status
.\ops\observation\observation-window.ps1 -Close     # refuses unless the exit criteria hold
```

Evidence is written to `build/observation/window-record.json`.

Proven on 2026-10-11: `-Close` refused with exit 1, naming "only 3 sample(s)" and "3 of 3 sample(s)
could not read at least one signal (failedExports, failedMessages, oldestPendingAgeSeconds,
outboxDead, outboxPending, securityEvents)". The refusal is the feature.

---

# SIGN-OFF RECORD - NOT GIVEN

**This table is deliberately empty.** The orchestrator has no authority to complete it, and a
pre-filled sign-off would be a fabricated approval, which is the one thing this migration is built
to make impossible. Each row must be completed by the named human, against the evidence in
`build/observation/window-record.json` for **two completed windows**.

| Owner | Attests to | Window 1 evidence | Window 2 evidence | Signed | Date |
| --- | --- | --- | --- | --- | --- |
| Product | Customer-visible behaviour is unchanged: case resolution, inbox, notes, exports, and the AI draft/send split | | | | |
| Operations | Stability under real load: error rate, queue lag, connector delivery, rollback rehearsed | | | --- | --- |
| Security | Tenant isolation holds in the observed system; the legacy cross-tenant boundary (§10) is not reintroduced | | | | |
| Finance | Ledger, rate cards, revenue derivation and reconciliation show no drift | | | | |

## Known non-answers

Whether the legacy cross-tenant breach was **exploited** is not answerable from these artifacts and
is not this document's to answer. It requires the production database and
`ops/forensics/Invoke-ForensicScan.ps1`. Until it is run, "no evidence of exploitation" is a
statement about the evidence, not about the database.