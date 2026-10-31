# ADR 0016: Success status codes must be observed, not assumed

- Status: accepted
- Date: 2026-10-07
- Phase: 16 (raised), 12 (must close)
- Scope: the OpenAPI document's declared response codes
- Raised by: `firstelement-exports`, which found one instance and escalated the class
- Decided by: orchestrator

## Context

Phase 16 found that `POST /api/v1/exports` returns **`202 Accepted`** while the
promoted contract document publishes **`200`**. The exports agent correctly
declined to fix it alone, because the right answer depends on what every other
route does and one module cannot see the whole API.

Measuring the whole document settled that:

| fact | value |
| --- | --- |
| operations in the document | 142 |
| POST/PATCH declaring only `200` | 44 |
| operations declaring a 2xx | 2 (`201`, both on `POST`) |

So this is not an exports defect. It is a systemic one, and `202` is not a
stray either — it is **absent from the entire document**. Every asynchronous
route in the system publishes `200`.

## Why it matters, concretely

A generated client types a response by its declared code. That does not break
parsing, which is why nothing failed. The failure is in what the client is
told about the *meaning* of a successful call:

- `POST /api/v1/exports` creates an **export job**. It returns a job handle and
  the artefact does not exist yet. Declared `200`, a client has no signal that
  work is outstanding, so it may read the body as a finished result and render
  an export that will fail when downloaded.
- `POST /api/v1/operations/jobs/{id}/replay` and the finance `rollup/compute`,
  `reconciliation/run`, and `periods/{id}/lock` routes are the same shape. The
  finance ones matter most: a period lock or a reconciliation run published as
  a completed `200` invites a client to tell an operator it finished before it
  has.
- Webhook receivers (`POST /webhooks/{provider}/{id}`) legitimately return
  `200` to acknowledge, and that one is fine.

This is the same rule the migration already applies elsewhere: **a value the
server publishes must not be narrower than what it actually does.** It is the
reason `filter` became an enum and `catalogue` got a schema in this same phase.
A correct status code is the cheapest form of that honesty and the easiest to
get wrong, because it is invisible until a client misbehaves against it.

## Decision

**Every declared response code must be an observed code.** No route's success
code may be inferred from convention, from what the framework returns by
default, or from what is convenient to write.

## Rules for closing it

1. **Observe, then declare.** For each of the 44 operations, record what the
   route *actually* returns under test, and declare that. Where the observed
   code is wrong, fix the route; where the route is right, fix the document.
   These are different corrections and must not be conflated — a document
   edited to match a bug makes the bug contractual.
2. **`202` is correct for work that was accepted and is still outstanding**:
   export creation, retry, job replay, rollup compute, reconciliation run,
   notification fan-out. It is wrong for a webhook acknowledgement, which
   completed its purpose.
3. **Every declared code must be reachable.** Declaring both `200` and `202` on
   an operation that only ever returns one is not honesty, it is hedging.
4. **Do not guess.** Where an operation's real code is not yet established by a
   test, the correct action is to write the test, not to pick a plausible value
   and let a client discover the truth in production.
5. **Regression protection.** Assert in the contract test suite that a created
   async resource returns `202` and that the document declares `202` for it. A
   drift gate on status codes will not be accepted as sufficient on its own,
   because a document edited to match a bug passes it.

## Consequences

- **Cutover is blocked until this closes.** A client that believes an export
  exists because it got `200` is worse than one that cannot create an export at
  all, because it fails later and further from the cause.
- It is a wide, cross-module change (44 operations across at least nine
  modules), so it needs its own phase with disjoint per-module ownership rather
  than being folded into an existing mission. Two agents must not edit the same
  OpenAPI source or promoted document concurrently.
- The exports suite already asserts the true `202`. That is the model: the
  suite asserts observed behaviour and the document is corrected to match.
- This does not change any behaviour the legacy client relies on. Legacy clients
  accept a `200` response and a `202` response alike on these routes; the
  correction is in the contract, not the runtime.

## Alternatives rejected

1. **Fix only `POST /api/v1/exports` and leave the other 43.**
   Rejected. It leaves the same trap in the finance routes, which are the ones
   where a false "finished" has consequences.

2. **Declare `200` and `202` everywhere to be safe.**
   Rejected by rule 3. A document that declares codes a route never returns is
   less honest than one that is wrong in a narrow way, because every client then
   has to handle a case that cannot occur.

3. **Leave it and note it in the handover.**
   Rejected. The Angular client is generated from this document and is being
   built now; leaving it means generating a client that teaches the wrong thing
   to every screen the Phase-11 agent writes next.