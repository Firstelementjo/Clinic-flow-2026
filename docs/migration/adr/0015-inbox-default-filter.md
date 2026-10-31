# ADR 0015: The inbox defaults to the work queue

- Status: accepted
- Date: 2026-10-07
- Phase: 15
- Decides: the saved-view default for `GET /api/v1/conversations`
- Raised by: `firstelement-crm` (Phase 15), as an explicit product decision rather than an engineering one
- Decided by: orchestrator

## Context

Phase 15 added the work queue. Before it, `GET /api/v1/conversations` accepted `assigned-to-me`
and `history` and **defaulted to the first**, so a brand-new, unassigned conversation was not
reachable from the inbox list at all — only by navigating to `/inbox/unassigned` and knowing to
look there.

The `assigned-to-me` filter is not "assigned to me". Its involvement test is *assigned to the
caller, or the caller was the last human replier*, so a fresh conversation satisfied neither
branch.

With the old default, an employee opening the inbox saw only threads already assigned to them.
An empty inbox therefore rendered identically in two very different situations:

| what the employee sees | what it means |
| --- | --- |
| empty inbox | "there is no work for anyone" |
| empty inbox | "there is no work for *me*" |

The second is what the screen almost always meant. The first is what it looked like it meant.

## Decision

**The default is `InboxFilter.DEFAULT = UNASSIGNED`.** It was `ASSIGNED_TO_ME`.

## Rationale

The decision turns entirely on harm asymmetry, not on which view is nicer.

Showing an employee some conversations that are not theirs costs **a moment of scanning**. Hiding
an unassigned conversation from every employee simultaneously costs **a customer waiting on a
reply that nobody can see is outstanding**. The first is a UI annoyance; the second is the
product failing at its one job.

Generalising: **a default must never be able to hide work from every employee at once.** That is
the rule this decision enforces, and it is why the default is a named constant on the enum rather
than a choice made at a call site. A default view is the view nobody chose; it is the view every
user gets by not making a decision. In a work queue, the unchosen view must be the safe one.

## Consequences

- `assigned-to-me` is **not** removed. It remains a first-class filter, reachable by name, and
  remains first-class in `/inbox/counts`. Only the resting state changed.
- **This is a deliberate behaviour change, not a bug fix.** It is recorded as such so it is never
  later mistaken for an inadvertent regression and quietly reverted.
- **It affects the legacy client during the Phase-12 dual run.** The legacy client relied on the
  default and will now see the queue. That is the correct outcome, not an accident: the legacy
  client had the same blind spot, and during a dual run both clients should see the same work.
  Phase 12 must record this as an expected difference rather than investigate it as drift.
- The legacy React inbox must be checked in Phase 12 for any hard-coded assumption that an
  unfiltered list contains only the caller's own threads — for example a count badge summed
  against the list. If it has one, that is a Phase-12 finding, not a reason to revert this ADR.

## Alternatives rejected

1. **Keep `assigned-to-me` and add a badge on the unassigned count.**
   Rejected. The number is visible while the work is not; a count tells an employee that five
   conversations are waiting without letting them act on any of them, so they still have to know
   the unassigned route exists. It treats the symptom a navigation problem.

2. **Default to `all-open`.**
   Rejected. It solves invisibility by over-correcting: every employee sees every conversation,
   including colleagues' active work, on every refresh. The queue is the right default because it
   shows work that **nobody** owns. `all-open` shows work that everybody owns, which is a
   different and much noisier thing.

3. **Make the client pass `filter=unassigned` explicitly, leaving the server default alone.**
   Rejected. It leaves the trap armed for every client that does not, including the legacy one
   during the dual run and any future integration. A hazard that only the current client happens to
   avoid is still a hazard.

4. **Leave the default and file it as a product decision for the product owner.**
   Rejected as the *action*, and the agent was right to escalate rather than take it: changing a
   default is a behaviour change and is not the engineer's to make unilaterally. It was escalated,
   decided here, and is now implemented.

## Implementation notes

- `InboxFilter.DEFAULT` is the single named constant. `ConversationsController.defaultFilter()`
  and the `InboxQuery` compact constructor both read it. A literal in each would be two places for
  them to disagree, silently.
- `InboxFilterContractTest` asserts the default is that one constant and that both layers resolve
  to it, so a later reintroduction of a literal is caught rather than merely discouraged.
- `InboxWorkQueueIntegrationTest.theDefaultShowsTheWorkQueue` asserts the **behaviour**: an
  unfiltered inbox request returns the fresh unassigned row, and `?filter=assigned-to-me` does
  not. It asserts on state after the request, not on the parameter.

## Regression protection

Two tests, and the second is the one that matters:

1. The default resolves to `InboxFilter.DEFAULT` from all three entry points.
2. An unassigned conversation is in the response of `GET /api/v1/conversations` with no `filter`
   parameter.

The previous version of test 2 asserted the opposite — that the unfiltered request equalled
`?filter=assigned-to-me`. That test was correct when written and is now inverted by this ADR. It
is worth recording that its removal is the visible trace of this decision: if it ever reappears in
this form, the ruling has been reverted.

The two halves are deliberately opposite directions. Reverting is possible but visible.