# Phase 2 readiness â€” what can be built now, and what each decision is holding

Written 2026-10-13 by the orchestrator. The point of this document is that "Phase 2 is
blocked" is a true but useless statement on its own â€” it hides the fact that most of
Phase 2 is *not* blocked, and it hides which specific decision holds which specific table.

Phase 2 of the kit plan ("Schema and persistence foundation") names ~45 tables. Nine
clinic decisions are open. This maps every named table to the decision that gates it, so
the blocked set can shrink one decision at a time instead of all at once.

**Rule applied throughout:** the kit's AGENTS.md forbids inventing clinic policy for
tooth notation, appointment status, cancellation/no-show, resources, tax, retention,
payment processing, triage, consent or prescription formats. A column whose *shape*
depends on an unanswered question is treated as blocked even when its *existence* is
obvious. Guessing is cheap here and expensive later: a wrong enum in a Flyway migration
is a shipped, applied, wrong answer.

---

## Tier A â€” buildable now, no decision required

These encode structure the existing system already establishes, and none of them
depend on a clinic policy question.

| Table | Why it is free |
|---|---|
| `patients` | Identity and clinic scope only. Clinical content lives in the clinical tables. DEC-015 (`Patient` â‰  `Customer`) is already decided, and is a *separation* constraint, not a shape question. |
| `patient_identities` | External identifiers (national id, file no.). Storage format is a column choice, not a clinic policy. |
| `patient_attachments` | Authorization, retention class, download audit â€” retention *class* is a column; DEC-007 governs the *period*, and the column can be nullable and inert. |
| `doctor_profiles` | A doctor is a user with clinical attributes. Roles are already decided by DEC-014. |
| `doctor_services` | The join between doctors and services. Both endpoints exist in Tier A. |
| `doctor_schedules` | Working hours and split shifts. Shape is independent of how long an appointment lasts. |
| `leave_periods` | Straightforward date-range table. |
| `availability_exceptions` | Holidays and unavailable blocks. |
| clinic audit / outbox / idempotency tables | These already exist for the CRM product. Reusing the established pattern is not a clinic decision. |

**Caveat on retention columns.** DEC-007 recommends adding `retentionClass`,
`retentionExpiresAt`, `legalHold`, `deletedAt` **nullable and inert** â€” no purge job, no
deletion behaviour â€” which is exactly the precedent four existing migrations set
("no retention behaviour is created here, deliberately"). Building Tier A this way does
not pre-empt DEC-007; it makes the eventual answer a data change rather than a schema
change.

---

## Tier B â€” blocked, with the exact decision and column that waits

| Table | Blocked by | The specific thing that cannot be chosen yet |
|---|---|---|
| `service_catalog_items` | DEC-001 | Duration, and whether a slot is a fixed step or free-form |
| `resources` | DEC-002 | What a resource *is* â€” chair? room? equipment? and whether one slot needs several |
| `appointments` | **DEC-003** | The `status` domain. A `CHECK` constraint cannot be written, and **this also blocks acceptance scenario 3 and the double-booking exclusion constraint** |
| `appointment_status_history` | DEC-003 | Append-only history of statuses that do not exist yet |
| `waitlist_entries` | DEC-003 | Same |
| `reminder_policies`, `reminder_attempts` | DEC-005 | Window, channel, permitted hours. Separately blocked on a **Java API gap, not DDL**: `OutboxRepository.ENQUEUE` hardcodes `nextAttemptAt = now()`. The `"nextAttemptAt"` column already exists and is already the claim predicate (`OutboxRepository.java:78`), so scheduling needs only an `OutboxIntent` field, a not-before argument, and a `QueueName` member — **no migration required**. Answering DEC-005 alone still does not unblock reminders |
| `patient_consents`, `treatment_consents` | DEC-010 | What a consent records, and whether it is signed or refused |
| `prescriptions` | DEC-010 | Format, and whether it is an artefact or a reference |
| `odontogram_snapshots`, `tooth_findings` | DEC-009 | **The source document specifies neither FDI nor Universal.** It defers to "a standard the clinical team determines". Tooth codes are clinician-facing and irreversible once entered |
| `clinical_encounters`, `clinical_notes`, `diagnosis_entries`, `clinical_procedures`, `clinical_attachments`, `clinical_change_events` | DEC-007 | Minimum-necessary field projection and retention period |
| `treatment_plans`, `treatment_plan_items`, `treatment_dependencies`, `treatment_status_history` | DEC-010 + DEC-004 | Consent per item, and whether progress is priced |
| `clinic_invoices`, `clinic_invoice_lines`, `payments`, `payment_allocations`, `refunds`, `discounts`, `financial_adjustments`, `receipts` | DEC-004 | Invoice timing, tax, numbering, currency. Also gated on the ADR-0007 rule 7 ruling |

**Not in the kit's list but referenced by it:** the plan names an "approval" table
(line 135) without naming one, and `ai_approvals` is not reusable â€” it is scoped to an
AI run (`aiRunId` NOT NULL). DEC-003 and DEC-004 may both need a staff-approval table
that does not yet have a name or a shape.

---

## The critical path, in order

Not all nine decisions are equal. Three of them unblock the most:

1. **DEC-003 (cancellation / no-show).** Highest leverage by a wide margin. It gates
   `appointments` itself, which gates the double-booking constraint, which gates
   **acceptance scenario 3** â€” the one scenario that currently looks passable and is
   not. It also decides whether a `status` CHECK constraint can exist at all.
2. **DEC-007 (privacy / retention).** Gates the Phase 1 gate (PHI non-disclosure) and
   every clinical table's projection and retention columns. Brief drafted.
3. **DEC-009 (odontogram).** Gates two tables whose content is clinician-facing and
   irreversible once a tooth code has been recorded.

---

## What is deliberately not being done

- **No enum, status list, tax rate, or retention period is being guessed** to unblock a
  table. Where a column is needed but undecided, the honest move is an absent or
  nullable column, not a plausible value.
- **No table is being created "ready" for a decision that has not been made.** A
  migration applied now is a shipped answer; a migration written later is not.
- **The kit plan's `clinic_locations` is not being created.** ADR-0018 removed it; the
  line in `plan.md:128` is annotated rather than silently deleted so the removal is
  visible to the next reader.
---

## CORRECTION 2026-10-13 — three of the "buildable now" tables already exist, and one of them is a PHI problem

This section corrects an error in the Tier A table above, found by reading the baseline schema
rather than the kit plan. **The kit plan describes the target state; it does not describe what is
already in the database.** Three clinic-adjacent tables exist today:

| Existing table | Columns that matter | What the kit plans to add anyway |
|---|---|---|
| `public.procedures` | `name`, `specialty`, **`durationMinutes`**, **`bufferMinutes`**, **`price`**, `isActive` | `service_catalog_items` |
| `public.doctors` | `name`, `specialty`, `department`, `email`, `phone`, **`workingDays`**, **`startHour`**, **`endHour`**, **`slotDuration`** | `doctor_profiles`, `doctor_schedules` |
| `public.appointments` | `customerId`, `doctorId`, `procedureId`, `patientName`, `patientPhone`, `patientEmail`, `status` | `appointments` (in the kit's list) |

**1. `service_catalog_items` would duplicate `procedures`.** The existing table already holds
duration, buffer and price. What it lacks is *effective dating* — "this price applies from this
date" — and doctor eligibility, which is a join, not a column. Creating a second catalogue table
would give the product two sources of truth for the same fact.

**2. `doctor_profiles` would duplicate `doctors`, and `doctor_schedules` is a genuine need for a
reason the existing table cannot meet.** `doctors` stores its schedule *denormalised on the doctor
row* (`workingDays`, `startHour`, `endHour`, `slotDuration`). That shape cannot express a split
shift, a lunch break, leave, a holiday, or a date-specific exception — each would need another
column on the doctor. `doctor_schedules` as a real table is the right answer; **extending
`doctors` is not.**

**3. `appointments` currently stores patient identity as free text, and that is the PHI finding.**
There is **no `patients` table**. A patient's name, phone and email are loose `text` columns on
the appointment row, and `customerId` points at the CRM `Customer`, which DEC-015 explicitly says is
not a patient. So today:

- Patient identity is not referentially enforced, not scoped to a clinic, and not deletable as a unit.
- PHI sits in a table whose other rows are scheduling data, which is what makes minimum-necessary
  projection impossible to state honestly.
- Two appointments for the same person are two unrelated text rows.

`patients` is therefore not merely the first Tier A table — it is the one that makes DEC-015
true in the schema rather than only in a document. The Phase 3 work item "create patient and
optional customer link" is exactly this.

**What this changes in the plan**

- `service_catalog_items` ? **extend `procedures`** with effective dating, or accept `procedures`
  as the catalogue and drop the kit's table. **Product decision — see DEC-016.**
- `doctor_profiles` ? **drop it.** `doctors` is the profile. **Product decision — DEC-016.**
- `patients`, `patient_identities`, `patient_attachments` ? **build first.** No duplication, and
  they unblock DEC-015.

**Backfilling `patients` from existing `appointments` text is a data-migration question, not a
schema one.** It moves PHI into a real table and must be reviewed on its own; it is not part of
Phase 2's additive schema work.