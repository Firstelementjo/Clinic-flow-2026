# DEC-016 — Clinic modelling: extend what exists, or create the kit's tables alongside it?

- **Status:** OPEN — raised by the orchestrator 2026-10-13, awaiting product + engineering sign-off
- **Owner:** Product, with engineering
- **Blocks:** Phase 2 (`service_catalog_items`, `doctor_profiles`), Phase 4 (availability)
- **Related:** DEC-001 (duration/buffers), DEC-014 (roles), DEC-015 (Patient ≠ Customer), `docs/clinic/phase-2-readiness.md`

> Raised *because* the kit plan and the live schema disagree. The kit describes the target; it does
> not describe what exists. Every claim below was read from
> `V20260814000000__baseline_verified_live_schema.sql`.

---

## 1. The question

The clinic plan's Phase 2 lists `service_catalog_items` and `doctor_profiles` as tables to create.
Three clinic-adjacent tables already exist. Do we extend them, or create the kit's tables beside
them and migrate?

## 2. What already exists

| Table | Relevant columns |
|---|---|
| `public.procedures` | `organizationId`, `projectId`, `name`, `specialty`, `durationMinutes` (default 30), `bufferMinutes` (default 0), `price`, `isActive`, timestamps |
| `public.doctors` | `organizationId`, `projectId`, `name`, `specialty`, `department`, `email`, `phone`, `workingDays int[]` (default 0-4), `startHour text` (default '09:00'), `endHour text` (default '17:00'), `slotDuration int` (default 30), `isActive`, timestamps |
| `public.appointments` | `organizationId`, `projectId`, `customerId`, `doctorId`, `procedureId`, `patientName`, `patientPhone`, `patientEmail`, `appointmentDate`, `durationMinutes`, `status text DEFAULT 'PENDING'`, `isAiProposed boolean DEFAULT true`, `notes` |

**The gap that matters most:** there is no `patients` table. A patient's name, phone and email are
**free-text columns on the appointment row**, and `customerId` references the CRM `Customer` that
DEC-015 says is not a patient. DEC-015 is currently true in a document and false in the schema.

## 3. Options

### A — Extend the existing tables; drop the kit's duplicates

Add effective dating to `procedures`; treat `doctors` as the profile; add `doctor_schedules`,
`doctor_services`, `patients` and the rest as new tables.

| | |
|---|---|
| **For** | One source of truth per fact. No dual writes, no sync job, no reconciliation. Every existing appointment, doctor and procedure row keeps working untouched. Matches how the platform is already built — additive, tenant-scoped, composite-FK'd. |
| **Against** | `procedures` and `doctors` gain dental-specific columns (tooth surfaces, etc. — DEC-009) that the CRM half has no use for, so a generalist table accumulates clinic-only attributes. |
| **Forecloses** | Nothing. |

### B — Create the kit's tables alongside; migrate and retire the old ones

`service_catalog_items` and `doctor_profiles` as new, backfilled from `procedures`/`doctors`, with
the old tables eventually dropped.

| | |
|---|---|
| **For** | Dental models get exactly the columns they need without polluting CRM tables. Aligns the schema with the clinic plan as written. |
| **Against** | Two sources of truth during the overlap, so every price and duration read has to decide which table it trusts. A migration of live appointment history onto new FKs — irreversible if wrong. The kit's own rule says **do not rename or drop legacy tables during this feature addition**, so B's "eventually drop" is not available in this phase at all. |
| **Forecloses** | Additive-only migration within this phase. |

### C — Split: `patients` and schedules new; catalogue stays on `procedures`

Create only what cannot exist yet (`patients`, `patient_identities`, `patient_attachments`,
`doctor_schedules`, `doctor_services`, `leave_periods`, `availability_exceptions`), and record that
`procedures` **is** the service catalogue, with `service_catalog_items` and `doctor_profiles`
dropped from the plan.

| | |
|---|---|
| **For** | Same single-source-of-truth benefit as A, with a smaller surface. Nothing is duplicated and nothing is retired. |
| **Against** | Requires accepting that `procedures` — a name from the CRM product — is the dental service catalogue. Cosmetic, but it will confuse readers. |
| **Forecloses** | A future clean dental catalogue would need a rename/migration later. |

## 4. Recommendation

**Option C**, on engineering grounds, with one caveat.

`patients` is the highest-value table in the whole Phase 2 list: it is the only one that makes
DEC-015 true in the schema, and it is the precondition for every clinical table that follows.
Nothing about it is contested.

For the catalogue and the doctor profile, the duplicate-table cost is real and immediate — two
places to write a price, two places to read a duration — while the benefit is naming tidiness.
Extending `procedures` with effective dating costs one additive migration and preserves every
existing row.

**The caveat:** if DEC-009 decides the odontogram needs to hang off the *procedure* (for example a
procedure that is tooth-specific), then `procedures` accumulates clinical structure and option A's
"pollutes the generalist table" objection becomes real. In that case B is worth its cost. **This
couples DEC-016 to DEC-009**, and answering DEC-009 first may answer this for free.

## 5. What breaks if this is guessed wrong

| If guessed | Consequence | Recoverable? |
|---|---|---|
| Duplicating the catalogue | Two sources of truth for price and duration from day one | Yes, but only with a data migration and a period of dual reads |
| Extending and later needing a split | A rename/migration of live appointment history | **No**, once appointments reference it |
| Building `patients` late | Clinical tables land before there is anything to hang them from | Yes — it is additive |

`patients` is safe either way. The catalogue question is the one worth getting right.

## 6. Answers needed

| # | Question | Answer |
|---|---|---|
| 1 | Is `procedures` accepted as the dental service catalogue? | |
| 2 | Is `doctors` accepted as the doctor profile, with `doctor_profiles` dropped? | |
| 3 | Is effective dating added to `procedures` (price/duration from a date)? | |
| 4 | Does DEC-009 attach tooth structure to the procedure? | |
| 5 | Is the backfill of `patients` from existing appointment text approved, and by whom? | |

Approver, date, reversible?: ______________________