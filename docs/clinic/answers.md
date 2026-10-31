# Clinic decision answers — the build contract

**Answered 2026-10-13.** These ten answers are the contract for Phase 2 onward. Each brief in
`decisions/` remains the reasoning; this file is what was actually decided. Where an answer says
"pending" or "to confirm", the build proceeds with a nullable column and does not guess a value.

Six decisions also reduce or gate work. **DEC-004 defers clinic billing entirely** — no invoice,
payment, refund, discount, adjustment or receipt table is built in this phase, and that is a scope
reduction, not an omission.

---

## DEC-003 — Appointment status  ✅ ANSWERED

**Status list (7):** `PENDING, CONFIRMED, CHECKED_IN, IN_PROGRESS, COMPLETED, CANCELLED, NO_SHOW`

- **Slot-holding:** `PENDING`, `CONFIRMED`, `CHECKED_IN`, `IN_PROGRESS`, `COMPLETED`
- **Slot-freeing:** `CANCELLED`, `NO_SHOW`
- **Reschedule is NOT a status.** It moves an existing appointment to a new time and is recorded in
  `appointment_status_history`, not as a terminal state.
- **New columns:** `cancelledBy`, `cancelledReason`, `cancelledAt`

This is the answer that unblocks the `appointments` table, the `status` CHECK constraint, and the
double-booking exclusion constraint (acceptance scenario 3).

## DEC-016 — Clinic modelling  ✅ ANSWERED (Option C)

- **`patients` is new**, with organization and project keys, **phone unique per tenant**, and an
  optional link to the existing contact table — `public.customers` is that table
  (`displayName`, `primaryEmail`, `primaryPhone`, `externalIds`). The link is **optional** because
  DEC-015 holds: a patient is not a customer.
- **`procedures` stays as the service catalogue.** `doctor_profiles` is **dropped** — `doctors` is
  the profile.
- **Schedules are new.**
- **Backfill** the existing free-text appointment patient details into `patients`.

The backfill moves PHI into a real table. It is a data migration and is reviewed on its own, not
as part of the additive schema work.

## DEC-007 — Privacy and retention  ✅ ANSWERED (values pending)

- **Columns now, values later.**
- **Per-tenant** jurisdiction and retention fields.
- All retention columns **nullable and inert**. No purge job, no deletion behaviour.
- **Soft delete by default.**
- **Export is admin-only and audit-logged.**
- **A legal-hold flag blocks deletion.**
- **A lawyer confirms the values.**

### DEC-007 values supplied 2026-10-13 by Jordan

```
retention_floor_years = 10
anchor                = last procedure
rule                  = no physical deletion before the floor
rule                  = legal hold blocks deletion
basis                 = "analogy to Jordan private hospitals regulation,
                         pending legal confirmation"
```

**Legal hold is now DONE, not pending.** `patients_legal_hold_blocks_deletion` is a CHECK
constraint, and `PatientServiceIntegrationTest` proves the database refuses to set `deletedAt`
on a held row.

**The floor is recorded but NOT enforced**, for two reasons that are not shortcomings:

1. `retentionExpiresAt` is an **expiry**, and a floor is not an expiry. Filling it from a floor
   would authorise deletion at ten years, which this decision never granted. It stays NULL.
2. "last procedure" has no source. `procedures` is the service catalogue, not a treatment
   record, and `appointments` is a *booking* - anchoring to it would **shorten** the floor and
   push rows toward deletion sooner. Phase 8 treatment records are the anchor.

Full analysis: `docs/clinic/decisions/DEC-007-retention-floor.md`.

The flags are the buildable part; the periods are not ours to invent.

## DEC-009 — Odontogram  ✅ ANSWERED (clinician to confirm)

- **FDI two-digit, stored as canonical.**
- **Display notation is configurable per clinic.**
- A clinician confirms the finding vocabulary before the catalogue is seeded.

### DEC-009 detail supplied 2026-10-13 by Jordan

FDI canonical: permanent 11-18/21-28/31-38/41-48, deciduous 51-55/61-65/71-75/81-85 (52 teeth).

Finding rows are **append-only**: `{tooth_fdi, scope TOOTH|SURFACE|ROOT,
surfaces M/D/O(I)/B/L, code, attributes jsonb, state EXISTING|PLANNED|COMPLETED,
source CLINICAL|RADIOGRAPH|PATIENT_REPORTED, recorded_by, recorded_at, note,
superseded_by}`. Vocabulary lives in a **catalogue table with ar/en labels, not a CHECK**.
Planned findings link to procedures.

Built as `V20261014000000__clinic_odontogram.sql`:

- 5 enums, `clinic_finding_vocabulary`, `patient_odontogram_findings`.
- Findings are **append-only**, guarded by the chain's existing `firstelement_append_only()`
  function rather than a new one. A correction is a new row whose `supersededBy` names the row it
  replaces.
- **The vocabulary table is created and deliberately NOT seeded.** An Arabic label for a clinical
  finding is clinical vocabulary; the answer says "pending clinician review", so seeding it would
  put unapproved Arabic in front of a patient. A post-condition asserts the table is empty, so a
  future "just add the obvious terms" commit fails here rather than shipping.
- Two checks were deliberately **not** written: scope-vs-surfaces validation (a clinical
  question, and a wrong CHECK rejects correct treatment records) and requiring
  `plannedProcedureId` when `state = PLANNED` (the decision says planned findings *can* link to
  procedures, not that every one must).

Full analysis: `docs/clinic/decisions/DEC-009-odontogram.md`.

## DEC-001 — Duration and buffers  ✅ ANSWERED

- **Duration is per service.**
- **Buffer is padding, not patient time** — the patient's booked interval is the duration; the buffer
  occupies the schedule around it.
- **Nullable per-dentist override** added.

Note this is what the two legacy implementations disagree about, and the answer matches neither
outright: the AI path added the buffer to the candidate but not to existing bookings.

## DEC-002 — Resources  ✅ ANSWERED

- **Chairs are shared and capability-tagged.**
- **Staff-only visibility** — resources never appear on the patient-facing booking surface.

## DEC-004 — Billing  ✅ ANSWERED (deferred)

- **Deferred. No invoice tables. Scoped separately.**

ADR-0007 rule 7 is **not** amended. Phase 2 builds none of `clinic_invoices`,
`clinic_invoice_lines`, `payments`, `payment_allocations`, `refunds`, `discounts`,
`financial_adjustments`, `receipts`.

## DEC-005 — Communication and reminders  ✅ ANSWERED

- **Channels configured per clinic.**
- **Per-timezone.**
- **Approved templates required.**
- **Patient opt-in required.**
- **Default quiet hours: outside 09:00–21:00 local, nothing sends.**

Still blocked on a separate code change, not on this decision: the outbox has no "publish at T"
primitive. The `nextAttemptAt` column and its claim predicate already exist, so it is a Java API
change, not a migration.

## DEC-006 — AI policy  ✅ ANSWERED

- **Read-only autonomy.** The assistant may look things up; it may not act.
- **Booking always requires human approval.**
- **Concerning-symptom keywords trigger immediate human escalation**, and the assistant **never
  diagnoses**.

This agrees with ADR-0007 and with the ruling needed on the legacy AI path that currently books
with `status: 'APPROVED'` and no approval step.

## DEC-010 — Prescriptions and consent  ✅ ANSWERED

- **Structured fields.**
- **Immutable once issued**; corrections are by **void and reissue**, never by edit.
- **Doctor role only** may prescribe.
- **Nullable signature field**, pending legal check.

---

## What the answers unblock, in order

1. **`patients`** — nothing was blocking it; this is now the first thing to build.
2. **`appointments.status`** — the seven statuses plus a CHECK constraint, and the exclusion
   constraint that makes double-booking impossible rather than merely untested.
3. **Schedules, leave, availability exceptions** — DEC-001 and DEC-002 resolved.
4. **Odontogram and prescriptions** — DEC-009 and DEC-010 resolved, pending vocabulary/signature.

## What remains genuinely blocked

- **Retention values** — a lawyer (DEC-007). Columns are built; periods are not guessed.
- **Finding vocabulary** — a clinician (DEC-009).
- **Clinic billing** — deferred by decision, not by omission.
- **The legacy AI booking path** — needs a product ruling before decommission, independent of
  DEC-006.
