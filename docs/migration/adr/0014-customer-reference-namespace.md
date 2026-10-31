# ADR 0014 — P0: the customer reference namespace is a scarce resource

Date: 2026-10-07
Status: **Accepted — BLOCKING for cutover**

Found by the Phase 11 agent while building the inbox, and independently verified by
me against the source before acting. This is the most serious defect found since
the migration began, because it is not a bug in one screen: it is a limit on how
large the product can ever become.

---

## The defect

Three facts combine, and each is individually defensible:

1. `CustomerRepository.nextCxSequence(scope)` returns a **project-scoped**
   `select count(*) from customers`. Every project with no customers computes `0`.
2. `CustomerRepository.cxIdExists(cxId)` queries with **no tenant or project
   scope at all** — it is an unscoped read.
3. `customers_cxId_key` is **globally** unique, and `IdentityResolver` retries only
   `MAX_IDENTITY_ATTEMPTS` (5) times.

So every project in the deployment starts its search at `#33000000`, and the first
five projects consume `#33000000` … `#33000004`. The **sixth project's first
customer throws `IllegalStateException` and every inbound message for that project
answers 500 — permanently.**

**At most five distinct customer references can ever exist across the entire
deployment.** Not five per project. Five, total.

The agent reproduced it: an empty project, `count(*)` = 0 in its own scope, and the
allocation walks `#33000000`…`#33000004`, all globally taken, then gives up.

## Why the code looks correct and still is not

`nextCxSequence`'s own javadoc records the reasoning that produced this:

> *"Legacy used a global `count(customers)` with no tenant filter, which is an
> unscoped read. This is scoped, so two tenants can compute the same candidate; the
> caller walks the sequence forward on the `customers_cxId_key` violation."*

The fix for an unscoped read was correct. The **conclusion** drawn from it was not:
scoping the counter while keeping the key global converts a uniqueness constraint
into a **scarce, exhaustible resource**. Walk-forward handles *contention* — two
writers racing for the same candidate. It cannot handle *scarcity*, and nothing in
the design says the namespace is finite.

That distinction is the lesson, and it generalises past this bug:

> **A retry loop over a shared namespace solves contention, not exhaustion.** If the
> candidate space is bounded and the search starts from a per-scope low-water mark,
> then `retries` is not a retry count — it is the size of the deployment the system
> can support. A loop that silently succeeds for the first N and then fails forever
> is a capacity limit wearing the costume of a race.

## Two further faults in the same code

- **`count(*)` is not a sequence.** Deleting a customer reopens a gap *below* the
  high-water mark, so a later customer can be issued a reference that a deleted
  customer once held. Any surviving URL, export, audit record or invoice naming that
  `cxId` would then resolve to the wrong person. A customer reference must be
  **monotonic and never reused**, not merely unique.
- **`cxIdExists` is an unscoped read** in a codebase whose architecture gate exists
  precisely to keep tenant predicates inside the statements that aggregate. It is a
  boolean existence probe rather than an aggregate, so the gate did not flag it, but
  it discloses cross-tenant existence through its result and its latency.

## Decision

**Option chosen: a per-organization monotonic sequence, with `cxId` unique per
organization.**

Rejected alternatives, and why:

| Option | Why not |
| --- | --- |
| Keep the global unique key, use a PostgreSQL `SEQUENCE` | Simplest, and `cxId` stays globally unique. But a global counter makes the **total number of customers created across all tenants** inferable from any tenant's `cxId`, which is a cross-tenant information disclosure in a product whose rules treat tenant isolation as mandatory. |
| Per-project sequence | Finer than needed. A customer's identity is an organization-level thing; splitting the reference per project would make one customer look like several references. |
| Widen the retry count | Treats the symptom. The namespace is still exhaustible; it just takes longer to fail. |

So:

1. A dedicated counter table keyed by `organizationId`, allocated **atomically**
   (`INSERT … ON CONFLICT DO UPDATE … RETURNING`) so concurrent writers cannot
   receive the same value and no `count(*)` is involved.
2. Drop the global `customers_cxId_key`; add a unique constraint on
   `(organizationId, "cxId")`.
3. **Backfill the counter from `max(cxId)` per organization**, so no reference that
   has ever been issued is reissued — including after a deletion.
4. `IdentityResolver` asks the allocator for a value instead of computing
   `count(*) + attempt`. Retries remain only for genuine write contention, and the
   retry count is no longer a capacity limit.
5. `cxIdExists` becomes scoped, or is deleted as redundant once the constraint
   reports collisions.

## Regression tests required — the missing one is why this survived

The existing suite could not have caught it. These must exist:

1. **The capacity regression**: create a first customer in **six** different
   projects and assert all six succeed. The current code fails on the sixth, and this
   test would have caught it at the moment it was written.
2. **Monotonicity**: within one organization, successive `cxId`s strictly increase.
3. **No reuse after deletion**: create, delete, create again in the same
   organization and assert the new `cxId` is **not** the deleted one.
4. **No cross-tenant inference**: two organizations allocating concurrently must not
   be able to derive each other's volume from their references.
5. **Scoped existence**: any existence probe used for allocation carries both tenant
   predicates.

## Status

**BLOCKING.** Phase 11 groups 9-12 may proceed for surfaces that do not depend on
inbound customer creation, but the inbox — the product's primary screen — cannot be
accepted while inbound message handling fails at the sixth project. This must be
fixed and re-gated before Phase 12, and it is the reason the Phase 11 Batch A
report is BLOCKED rather than PASS.