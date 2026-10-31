# ADR 0011 — Phase 10 findings: approval-state schema, chunk scope, and the send gate

Date: 2026-10-06
Status: **Accepted** (orchestrator rulings on the Phase 10 escalations)

Phase 10 shipped 222 tests and found 14 defects in its own code, two of which
point at the schema rather than the code. Both are confirmed below against
`V20261005100000__ai_policy_and_governance.sql`, which I read myself.

---

## 1. H2 — `ai_approvals_approval_check` forbids a valid rejection — CONFIRMED, needs a migration

The constraint as written:

```sql
CONSTRAINT "ai_approvals_decision_pair_check"
    CHECK (("decidedAt" IS NULL) = ("decidedByUserId" IS NULL)),
CONSTRAINT "ai_approvals_approval_check"
    CHECK (("status" = 'APPROVED') = ("decidedAt" IS NOT NULL)),
```

The comment immediately above it says *"The decision pair, and it is a BICONDITIONAL:
APPROVED **and REJECTED** each say who and when, and nothing else carries a decision."*
The body permits **only** `APPROVED` to carry a decision. So:

- A **REJECTED** approval that honestly records *who rejected it and when* is
  **rejected by the database**. The only REJECTED row the schema admits is one
  that hides who decided.
- `ai_approvals_rejection_check` requires a reason on every REJECTED row, so the
  schema simultaneously **demands** a decision record and **forbids** it.
- A **REVOKED** row cannot keep the grant's `decidedAt`, so revocation is
  unrepresentable even though `ai_approvals_revocation_check` exists for it.

That is not a stylistic disagreement: the check forbids the behaviour its own
comment describes. A rejection that cannot name its decider is an approval
system that cannot be audited.

**Required form (CORRECTED — see the amendment below):**

```sql
CHECK (("status" IN ('APPROVED','REJECTED','REVOKED')) = ("decidedAt" IS NOT NULL))
```

### Amendment, 2026-10-06: the literal form above was wrong, and the agent said so

The database agent implemented this and **shipped `'REVOKED'` in the `IN` list against my
literal instruction**, flagging it as a deviation needing a ruling. It was correct, and I
verified the reason against the schema myself rather than accepting it.

I had missed that `ai_approvals_revocation_check` already establishes a **separate**
revocation record:

```sql
CONSTRAINT "ai_approvals_revocation_check"
    CHECK (("status" = 'REVOKED') = ("revokedAt" IS NOT NULL AND "revokedByUserId" IS NOT NULL
                                       AND "revokeReason" IS NOT NULL)),
```

So the schema distinguishes two different acts:

| Act | Recorded in | Required by |
| --- | --- | --- |
| the **grant decision** (approve / reject) | `decidedAt`, `decidedByUserId`, `decisionNote` | `approval_check`, `rejection_check` |
| the **revocation** (withdraw a granted approval) | `revokedAt`, `revokedByUserId`, `revokeReason` | `revocation_check` |

A `REVOKED` row is therefore an **approved grant that was later withdrawn**. Under my
literal form, `decidedAt` would have to be `NULL` on a revoked row — which **destroys the
record of who granted the approval**, on exactly the row where that record matters most. My
own rule 4 (restore the revocation path) would have been unimplementable against the
constraint I had specified.

`'EXPIRED'` is correctly excluded, and for a different reason: an expired `PENDING`
approval was never decided by anyone, so absence of a decision is the truthful state.

**The lesson, recorded because I wrote the ADR that was wrong:** I specified a schema
constraint from the prose in a comment rather than from every constraint acting on that
table. Two checks on the same table disagreed, and only reading the second one revealed
it. A ruling that quotes one artifact should be checked against its neighbours before it
becomes a migration.

Shipped as `V20261006100000__ai_approval_decision_biconditional_repair.sql`: report →
drop → repair from the append-only `ai_approval_decisions` → refuse → add, in one
transaction. Measured violators 0, repaired 0, unrepairable 0. Phase 10's `reject()` and
`revoke()` were then restored to record the real actors — see the follow-ups below.

**Decision: a chain-owner migration corrects this.** It is not optional cleanup;
the constraint currently makes two documented transitions unstorable, and Phase 10
had to clear the decision pair on rejection and revocation purely to satisfy it —
code contorted around a schema defect, which is exactly how the defect survives.

Rules for that migration:

1. It is a **separate additive migration**, never an edit to `…5100000`.
2. It must **backfill first**: any existing row violating the new form must be
   repaired or reported, and the count stated.
3. It must be rehearsed on a throwaway with a rollback script, and per ADR 0009 §5
   the rehearsal must **assert on state after the action**, not on the message.
4. Once it lands, restore the two rejection/revocation paths in Phase 10 that
   currently clear the decision pair, and keep the append-only assertions on
   `ai_approval_decisions` — which already preserve the full history, so nothing
   was lost in the interim.

   **DONE (2026-10-06).** `reject()` now writes the real decider and decision time on the
   `ai_approvals` row as well as to the append-only history. `revoke()` restores **by
   omission** — it touches only `revokedAt`/`revokedByUserId`/`revokeReason` and leaves the
   grant's decision untouched, which is the whole point of the repair; writing the revoker
   into the decision pair would have been the same defect in different clothes. `EXPIRED`
   still clears the pair, now because the corrected constraint *requires* absence rather
   than because a constraint forbade a decision.

   The three restored tests were proved load-bearing rather than re-worded: both regressions
   (clearing `reject()`'s pair; making `revoke()` overwrite with the revoker) were injected
   and each was caught — 4 of 24 tests failed — then reverted. A third distinct actor was
   needed in the fixture for that to be true; with one actor, an implementation that
   overwrote the grant's decider would have passed everything.

5. Phase 7's `aTableHoldIsStructurallyImpossible` asserted D4's defect and is now
   `aNarrowHoldIsPlaceableAndCounted`: a `TABLE` hold is placeable, the `narrow` count is a
   real `1` rather than a structurally unreachable `0`, a narrow hold naming nothing is
   still refused, and a `PROJECT` hold must still name its project. Note the corrected pair
   check carries the `scopeRef` requirement in its own third arm, so the *pair* check is
   what refuses an unnamed narrow hold.

## 2. H3 — `document_chunks` scope is not constrained — CONFIRMED, needs a migration

Every composite tenant FK this migration set added is `(organizationId, id)`, so a
chunk cannot reference a document in another tenant. `document_chunks."documentId"`
is a **single-column** FK, which means a chunk's own `organizationId` need not
agree with its document's.

Phase 10's ingestion fails closed in code and catches this at runtime. That is
the right immediate behaviour, and it is also evidence the state is *storable*: the
database is not preventing a cross-tenant chunk, so only the code stands between
ingestion and a mis-scoped vector row. A vector row scoped to the wrong tenant is
a retrieval leak, not a data-quality issue.

**Decision: a chain-owner migration adds the composite constraint**, with the
duplicate-id check first — the migration must confirm no existing chunk
disagrees with its document before adding the constraint, and refuse rather than
delete if any do.

## 3. H1 — `AiSendService` does not exist; acceptance gate 6 is PARTIAL

> **AMENDED 2026-10-08. The premise of this section was wrong.** `AiSendService` **does
> exist**: `backend/workflows-sla-routing/src/main/java/com/firstelement/automation/service/AiSendService.java`,
> with `stageAiReply` (writes the `PENDING` outbound `authorType='AI'` row, so
> persist-first/send-second holds) and `confirmAiSend` (re-reads the mode under
> `SELECT ... FOR UPDATE`, re-checks the row is still `PENDING`, and publishes the outbox
> intent in the same transaction). It is covered by `HumanTakeoverConcurrencyIntegrationTest`
> (9 tests, including a real two-thread race) and its mode/permission/race semantics are pinned
> by `SendHandoffRaceIntegrationTest` (18 tests). I verified all of this directly.
>
> The error was mine: I searched for the class name, truncated the results to five, saw none
> in `ai-rag`, and concluded it did not exist — without noticing it lives in a different
> module. The reasoning below is retained only to mark what was believed and why it was
> wrong.

**What is actually missing is narrower, and it is not a missing send:**

1. **Nothing triggers an AI turn.** `AiOrchestrator` has **zero production references** —
   verified. No inbound message, worker, or scheduler starts an AI run. So the send path,
   however correct, is never reached.
2. **There is no bridge from an AI run to a send.** `AiOrchestrator` produces an `AiRunResult`
   keyed to `ai_runs`; `AiSendService` requires a `workflow_runs` id. Nothing connects them,
   and the module edge that would connect them is **forbidden in both directions** —
   `ai-rag` may not depend on `workflows-sla-routing` (its documented rationale: *"an AI run
   that could send without going through the send gate is the boundary this module exists to
   hold"*), and `workflows-sla-routing` may not depend on `ai-rag`.

**Revised decision (still required before cutover):** what Phase 6 left undone is the
**inbound trigger plus the run-key bridge**, not the send. The send must not be rebuilt in
`ai-rag` — a second implementation would create exactly the divergence
`AiSendAuthorization`'s own javadoc refuses to allow (*"two components enforcing rule 2
would eventually disagree about which conversation is in AI mode"*), in two modules that can
never see each other.

**Two decisions are owed by the orchestrator, and they are design decisions rather than
engineering ones:** the run key of a send (do not mint a `workflow_runs` row per AI run —
those rows drive SLA clocks and routing, and an AI turn is not a routed case), and the home of
the bridge, which needs an allow-list decision because `app-bootstrap` is currently the only
module permitted to see both sides.

## 4. H4 — a permission name is missing

`AppointmentProposalTool` gates on a literal `appointment:propose` in a named
constant rather than a `Permission` enum value. Per ADR 0010 §2 — *a permission is
named after what it authorizes* — this becomes a real `Permission` when the
workflows module is next touched. The tool itself always throws (no code path can
perform a consequential action), so nothing is exposed in the meantime, and the
constant makes the intent reviewable.

## 5. Recorded for the ledger, not decided here

**Approval binding is shape-only.** Today an approval binds run + tool + argument
*shape*, not the approved argument *values*. That is weaker than it sounds and is
documented as such in the phase report: an approval is not yet a promise about
specific values. Before any tool performs, Phase 12 must record and compare the
approved values. This is the difference between "a human approved this action" and
"a human approved *these* arguments", and only the second is safe to execute.

**UUID masking trade-off.** `HIGH_ENTROPY_RUN` masks 36-character UUIDs, so a
provider message naming a run id loses it. Deliberate: a UUID and a secret key are
indistinguishable by shape, and the trace row still carries the id. Asserted as a
fact rather than left unremarked.

**No `NOT NULL` on the `ai_run_*` scope columns.** ADR 0009 §2 rules 1 and 3
still govern: no `NOT NULL` until `BackfillReport.complete()` is true on a
disposable copy, with a rehearsed rollback, in a **separate** migration.