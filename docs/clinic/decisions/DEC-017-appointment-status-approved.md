# DEC-017 — `APPROVED` is a live appointment status that DEC-003's seven do not contain

**Status:** RESOLVED — Option A chosen; the self-approving path was removed, and the seven-value
CHECK is written. See *Resolution* at the end.
**Owner:** Product, with whoever owns the legacy AI booking path.
**Raised:** 2026-10-13, while preparing the kit Phase 5 schema work.
**Blocks:** kit Phase 3 (patient timeline), kit Phase 5 (appointments, waitlist, check-in).

---

## The conflict in one line

DEC-003 defines exactly seven appointment statuses. The only deployed code that books an
appointment writes an eighth.

## What DEC-003 says

Seven values, with a slot rule:

| status | holds the slot? |
| --- | --- |
| `PENDING` | yes |
| `CONFIRMED` | yes |
| `CHECKED_IN` | yes |
| `IN_PROGRESS` | yes |
| `COMPLETED` | yes |
| `CANCELLED` | no — frees it |
| `NO_SHOW` | no — frees it |

Reschedule is explicitly **not** a status; it is a change of `appointmentDate`. Plus
`cancelledBy`, `cancelledReason`, `cancelledAt`.

`APPROVED` and `REJECTED` are not in the list.

## What the code actually does

`packages/ai/src/tools/clinic-scheduling.ts` creates appointments with a hardcoded status:

- `L588`, `L621`, `L675`, `L703` — `status: 'APPROVED'`

It is reachable from the live webhook path:
`webhooks.controller.ts:55` → `message-queue.service.ts:103` → `orchestrator.ts:78` →
`executor.ts:295`.

That code runs in production. `deploy/fly/Dockerfile` is Node 22 running
`node apps/api/dist/main.js`; there is **no Java/Spring entrypoint anywhere in `deploy/`**. The
Spring stack is not what serves appointments today.

The same file already agrees with DEC-003's slot rule — it reads
`status: { notIn: ['CANCELLED', 'NO_SHOW'] }` at `L226`, `L356`, `L488` when looking for an
occupied slot. So the read side is consistent with the decision; it is the **write** side that
diverges.

## Why this blocks the CHECK constraint

`appointments.status` is `"text" DEFAULT 'PENDING' NOT NULL` with no constraint
(`V20260814000000__baseline_verified_live_schema.sql:624`).

A `CHECK (status IN (...seven...))` would therefore:

1. fail to apply, if any existing row already carries `APPROVED`; and
2. reject every subsequent write from the legacy AI booking path, which is the only deployed
   path — turning a schema change into an outage.

`NOT VALID` does not help: it would still reject new rows.

This is not a theoretical concern. It is the reason the constraint has not been written.

## Why I am not deciding it

Adding the eighth value silently would contradict DEC-003 and ratify the self-approving booking
path as correct. Writing the seven would break production. Both are policy calls, and the second is
an availability decision that is not mine.

## Options

**Option A — remove the self-approving path (P1, engineering's recommendation).**
The AI proposes; a human approves; the appointment is written `PENDING`. Then `APPROVED` stops
being produced and the seven-value CHECK is correct as DEC-003 specifies. This is also the
behaviour DEC-006 already requires: AI autonomy is **read-only**, and booking always needs human
approval. The legacy path is non-compliant with a decision that has already been made.

**Option B — gate the path.** Keep the legacy booking behind the AI policy gate and an approval
record, then map it to `PENDING`. Same end state as A for the database, but the legacy path stays
reachable, so the gate must actually hold.

**Option C — accept `APPROVED` as an eighth status.** Then DEC-003 is amended, the CHECK lists
eight values, and the slot rule needs a ninth answer: does `APPROVED` hold the slot? DEC-003's rule
is "the first five hold it, the last two free it" and says nothing about an eighth.

## What is recommended, and what is not mine

Option A. It is the only one that makes the schema agree with a decision already taken (DEC-006),
and it removes rather than adds a status nobody chose.

But the legacy AI booking path is a **customer-visible AI side effect** and the removal decision is
**product's**. Engineering recommends A; product decides.

## Second, smaller question bundled here

DEC-003 says "add `cancelledBy`". Two shapes are possible and the decision does not say which:

- `cancelledById uuid` — a user id, matching the existing `appointments.createdById uuid`
  convention. Cannot express "the AI system cancelled this", because there is no user.
- `cancelledBy text` — an actor name, which can express a system actor.

If Option A or B is chosen and every cancellation is a human action, `cancelledById uuid` is the
consistent choice. If any cancellation can originate from a system actor, it needs the text form or
a separate `cancelledByActorType`. **This should be answered with the status question rather than
separately**, because Option C makes it moot in one direction.

## Current disposition

The `cancelledBy` / `cancelledReason` / `cancelledAt` columns and the double-booking `EXCLUDE`
constraint are **not blocked by this decision** and will proceed independently. Only the status
CHECK waits.

No agent may convert this into a default policy.

---

# Resolution

## The ruling

**Option A. The self-approval is removed; `APPROVED` is not added as an eighth status.**

Asked to choose, the user delegated the choice to engineering ("okay do what you see the best").
That delegation was exercised as Option A for one reason that is not merely preference: Option A
is the only option that does not require a new clinic policy. Options B and C both ratify a
behaviour nobody had decided — B keeps the path reachable behind a gate whose correctness is
unproven, and C amends DEC-003, which would mean inventing a slot rule for an eighth status. A
migration is not the place to make that call.

The eighth status was therefore never "approved". It was the legacy booking path asserting it.

## What changed, in the order that made it safe

Sequencing mattered, because two of these are a coordinated pair:

1. **The writer first.** `packages/ai/src/tools/clinic-scheduling.ts` wrote `status: 'APPROVED'`
   at four sites. All four now write `PENDING`. A booking proposed by the AI is *pending a human*,
   which is what DEC-006 already required and what the code was violating.
2. **The reader second.** `apps/api/src/clinic/clinic.service.ts:272` counted `APPROVED` as a
   confirmed appointment in its headline KPI. Had the writer changed alone, that KPI would have
   silently under-counted — a number that looks like a result.
3. **The seven-value CHECK last.** `V20261019000000__appointments_status_dec003_and_cancel_columns.sql`
   adds `appointments_status_dec003` listing exactly the seven. Once nothing can write an eighth
   value, the constraint cannot reject a legitimate write, and the failure it does produce is a
   refusal of an unknown value rather than an outage.

## The wire consequence, which is not optional

The interesting failure mode was not the database. It was this:

`ClinicModels.AppointmentStatus` is the **filter-boundary** enum. While it still listed
`APPROVED`, a client could send `?status=APPROVED`, receive `200`, and receive an empty list. That
client would read the empty list as *"this clinic has no appointments in that status"* — a wrong
answer that looks exactly like a right one, and it recurs on every request rather than failing
once.

So the enum became the seven values too, and `?status=APPROVED` is now a `400` whose body names
the accepted set, including `CONFIRMED`. The replacement is published in the refusal on purpose:
otherwise the rename is folklore and a client has no way to discover what to migrate to.

The enum carries a note that it must stay identical to `appointments_status_dec003`. The failure
mode of drift is asymmetric: a value added to the enum but refused by the CHECK is a filter that
is accepted, binds a parameter, and matches nothing.

## What the migration will not do on its own

The CHECK cannot be applied to a database that already holds `APPROVED` rows. The data step is
therefore gated: the migration refuses unless the operator sets
`firstelement.dec017_acknowledge=on`. That flag is **not** script-reversible on purpose — it is a
one-way acknowledgement, and the thing being acknowledged is irreversible.

Before that flag is ever set, on any real database:

```sql
select count(*) from appointments where status = 'APPROVED';
```

That count is the size of the decision. If it is zero the data step is a no-op and the flag costs
nothing. If it is not zero, every one of those rows needs a human decision about which of the
seven it actually was — most likely `CONFIRMED`, if a person really did confirm it, or `PENDING`
if only the AI ever saw it. That mapping is clinical and operational; no agent should apply it.

## The bundled `cancelledBy` question

Answered alongside, as the decision asked:

- `cancelledBy` is a **user id** (`uuid`), matching the existing `createdById` convention.
- `cancelledReason` is free text and is **required whenever `cancelledAt` is set**, enforced by
  `appointments_cancel_fields_check`.
- A **system** cancellation (the AI, a connector, an integration) cannot name a user, so it
  leaves `cancelledBy` NULL and names itself in `cancelledReason`. This is stated in the column
  comment so the gap reads as a decision rather than as an oversight.

Option C would have made the user-id form insufficient; Option A keeps it consistent.