# DEC-002 — Clinic resources: rooms, chairs, imaging and sterilization

- **Status:** **OPEN** — brief drafted 2026-10-13, no clinic answer recorded
- **Owner:** Clinic
- **Approvers required:** clinic owner / authorized manager (counts, allocation policy, who may book a resource); clinical lead (which services require imaging, sterilization or other special equipment, and why)
- **Blocks:** Clinic Phase 2 (`resources`), Phase 4 (availability), Phase 5 (appointment booking and conflict response)
- **Related:** DEC-001 (durations and buffers — a chair is occupied for duration **plus** buffer), DEC-003 (a cancellation frees a chair), DEC-008 (a clinic is a `Project`, `ClinicLocation` is **not** introduced), DEC-013 (fail-closed entitlements), ADR-0018

> **This document decides nothing.** `firstelement-dental-clinic-feature-kit/.../plan.md:17` states:
> *"Do not invent a tooth numbering system, clinical status list, triage protocol, cancellation policy,
> tax rule, retention period, **resource model**, or payment gateway behavior."*
> Every option below is a genuine choice for the clinic. The recommendation is an **engineering
> recommendation about schema shape and sequencing**, and requires clinic sign-off before it becomes policy.

---

## 1. Question

How many bookable rooms/chairs does a clinic have, is each one **fixed to a particular dentist** or
**shared and allocated at booking time**, and which services require **imaging, sterilization, or other
special equipment** that must be reserved alongside the dentist and the time slot?

Three sub-questions, taken verbatim from the Arabic source:

> `source ¶303` — «كم عدد الكراسي أو الغرف؟ هل يرتبط كل طبيب بكرسي ثابت؟ هل بعض الخدمات تتطلب مواردًا خاصة (أشعة، تعقيم)؟»
> *(How many chairs or rooms? Is each doctor linked to a fixed chair? Do some services require special resources — imaging, sterilization?)*

`source ¶302` is the row label: «الكراسي والغرف» — *chairs and rooms*.

---

## 2. Why it blocks

### 2.1 The Phase 2 tables it unblocks

| Table (`plan.md`) | What DEC-002 fixes about it |
|---|---|
| **`resources`** (`plan.md:128`) | Row grain (one row per physical chair/room vs. one row with a `count`), scope column (project vs. location), the resource **type** vocabulary, whether a resource is **assignable to a dentist** and whether that assignment is *exclusive* |
| **`clinic_locations`** (`plan.md:128`) | **CONFLICT — see §6.1.** DEC-008 has already ruled this table *out*. Resources are the most location-dependent table in the whole Phase 2 list. |
| *(unnamed)* **allocation table** — `plan.md:192` requires Phase 5 to "Record patient, doctor, service, reason, source, actor, location, **resources**, and status history" | The **name and grain of the allocation table are not specified anywhere in `plan.md`.** This is a real gap; §6.4 records it for the orchestrator. |
| **`doctor_schedules`** (`plan.md:129`) | Whether a doctor's schedule can be blocked by *their* chair being unavailable — i.e. whether a schedule row may reference a resource |
| **`availability_exceptions`** (`plan.md:129`) | Whether "chair 2 is out for maintenance" is an exception on the resource or on the doctor |
| **`service_catalog_items`** (`plan.md:128`) | Which resource capabilities a service **requires**. Without this, "a slot outside hours or during leave is never offered" (`DENTAL_CLINIC_REQUIREMENTS.md:93`) has no resource half. |

### 2.2 The phases it blocks

- **Phase 2** — `resources` cannot be created without the scope and grain decisions.
- **Phase 4** — `plan.md:174` work item 4 is literally *"Implement optional resources: rooms, chairs,
  imaging, sterilization, and special equipment"*, and the Phase 4 gate (`plan.md:180`) requires
  availability to exclude *"resource conflicts"*.
- **Phase 5** — `plan.md:196` defers nothing here, but `DENTAL_CLINIC_REQUIREMENTS.md:89` requires
  *"Concurrent booking attempts for the same **doctor/resource**/time must allow at most one success"*,
  and acceptance scenario 3 (`DENTAL_CLINIC_REQUIREMENTS.md:276`) requires that a slot conflicting with
  a booked chair/room is not offered.

---

## 3. What already exists — verified, not assumed

| Claim | Evidence |
|---|---|
| **No resource concept exists at all.** A `CREATE TABLE` census across all 27 files in `ops/flyway/sql/` (116 matches) returns no `resources`, `rooms`, `chairs`, `equipment`, or `clinic_locations`. | `ops/flyway/sql/` |
| Same census in application code: zero matches for `chair`, `Chair`, `steriliz`, `Steriliz`, `operatory`, `Operatory`, `imagingMachine` across `backend/**`. | `backend/**` |
| `appointments` carries `doctorId`, `procedureId`, `durationMinutes` and **no resource column**. | `baseline:611-635` |
| `procedures` has **no** doctor link and **no** resource link; its `price` is `double precision`. | `baseline:1177-1189` |
| `doctors` holds a single flat weekly pattern — `workingDays`, `startHour`, `endHour`, `slotDuration` — not effective-dated, so a past schedule cannot be reconstructed. | `baseline:906-922` |
| **Zero exclusion constraints exist anywhere in the repository.** Every hit for `btree_gist` / `EXCLUDE` is a comment explaining why the project *deliberately avoided* the dependency. | `V20261005090000:139`; `RateCardService.java:48`; `RateCardRepository.java:111`; `RateCardEffectiveDatingIntegrationTest.java:318` |
| An `employee_availability` table exists (weekly windows, timezone default `Asia/Riyadh`) but is keyed on `employeeId`, is read by **agent routing**, not by clinic code, and is **not** a resource model. | `baseline:974-983`; `RoutingRepository.java:114` |
| The repository has already written down the lesson this decision needs: a thing that must be answerable *historically* is **versioned and effective-dated**, not a single mutable row. | `BusinessCalendar.java:12-19` |
| The Arabic source makes resources **conditional**, twice — they are only honoured *"if enabled"*. | `source ¶54`: «منع تعارض المواعيد للطبيب نفسه، وأخذ الغرف أو الكرسي العلاجية في الاعتبار **إذا كانت موارد محجوزة**»; `source ¶275`: «لا يُسمح بموعدين متداخلين للطبيب نفسه، ويُراعى تعارض الغرف والكراسي **إذا كانت مفعّلة**» |

**Citation convention.** `baseline:NNN` = `ops/flyway/sql/V20260814000000__baseline_verified_live_schema.sql`,
verified byte-identical to `backend/database/src/main/resources/db/migration/V20260814000000__baseline_verified_live_schema.sql`
(SHA-256 `F3DF55C3A2ABB142B3C67C480777C57E712DB0A8DDAD825D6A9D212A88FE0BB8` in both).
`source ¶N` = 1-based paragraph index of `word/document.xml` inside
`source/متطلبات_نظام_إدارة_عيادات_الأسنان.docx`, extracted in document order.

**Not verified.** I did not inspect a live database, only the migration corpus and the Java/TypeScript
source tree. If a clinic resource table exists in a deployment that never reached a migration, this brief
would not have seen it.

---

## 4. Options

Allocation is the primary question; capability/equipment requirements ride on it.

### Option R1 — Shared pool, allocated dynamically at booking

**Means:** the clinic owns N chairs. An appointment books a *time* and the system picks a free chair,
optionally constrained by what the service needs.
**Costs:** the availability query must resolve a chair, not just a time window; the exclusion constraint
becomes `(doctorId, during)` **and** `(resourceId, during)` rather than doctor-only; a chair that goes
out of service mid-day removes slots that were already offered.
**Forecloses:** nothing, permanently. A clinic that later wants a chair fixed to one dentist can add that
as a configuration row without a schema change.

### Option R2 — Fixed to a dentist

**Means:** each dentist has their own chair/operatory; the chair follows the dentist, so chair contention
never arises for appointments.
**Costs:** the schema must carry an exclusive dentist↔resource binding; a dentist without a dedicated chair,
or a clinic with more chairs than dentists, is unrepresentable or wasteful; part-time dentists and
shared-session days break the model.
**Forecloses:** shared-chair scheduling, and any clinic whose chairs outnumber or undernumber its dentists.

### Option R3 — Shared pool with **capability tags** + service requirements

**Means:** as R1, plus each resource carries capability tags (e.g. `RADIOGRAPHY`, `STERILIZATION`,
`SURGICAL_SUITE`), and each service declares which capabilities it needs. A slot is offered only if a
capability-matching free chair exists.
**Costs:** one extra join table (`resource_capabilities`, `service_resource_requirements`) that R1 and R2
do not need; the capability vocabulary must be supplied by the clinical lead (DEC-002 question 3).
**Forecloses:** nothing. R1 and R2 are both expressible *within* R3.

### Option R4 — No resources in Phase 2 at all

**Means:** Phase 2 ships the other tables; resources are deferred to a later phase.
**Costs:** the whole of Phase 4 work item 4 (`plan.md:174`) and the Phase 4 gate clause on resource
conflicts (`plan.md:180`) must be waived; acceptance scenario 3 (`DENTAL_CLINIC_REQUIREMENTS.md:276`) is
only partly satisfied.
**Forecloses:** nothing irreversible — but it is the one option that makes a later addition a *migration
into a live schedule*, not a fresh table.

---

## 5. Recommendation — **engineering recommendation, requires clinic sign-off**

**Build R3's schema in Phase 2; ship R4's behaviour (feature off) until DEC-002 is answered; then
enable R3, which contains R1 and R2 as configurations.**

Reasoning:

1. **R3 is a superset.** The reason to prefer it is not that it is "richer" — it is that **R1 and R2 are
   both representable inside it without a schema change**. If the clinic later says "actually Richard has
   his own chair", that is a row in an assignment table, not a migration. Choosing R1 or R2 *as the model*
   forecloses the other; choosing R3 as the model forecloses nothing.
2. **The behaviour stays off until answered.** This follows DEC-013's fail-closed rule and the register's
   rule (`open-decisions.md:6`) that an open decision keeps its dependent capability disabled. The tables
   can exist and be empty; nothing reads them until a clinic answers.
3. **One row per physical thing, not a `count` integer.** A `resources` row with `quantity = 6` cannot
   answer "chair 4 is out for maintenance" or "which chair was tooth 36 treated in". Six rows can, and the
   cardinality is the same either way. This is a schema recommendation, not a clinic policy — it does not
   decide how many chairs exist.
4. **Do not hard-code the capability vocabulary in a PostgreSQL enum.** Make it seeded reference data, for
   the same reason DEC-009 recommends the same thing for tooth surfaces: an enum change on live data is a
   migration; a seed insert is not.
5. **Do not build the exclusion constraint before the grain is known.** The first `btree_gist` extension
   in this repository is a heavier commitment than an `ALTER TABLE ADD CONSTRAINT`; get the
   `(resourceId, during)` scope right first. This decision should not authorise that migration.

**What the clinic must still supply, and this brief does not supply:** how many of what, fixed or shared,
and which capabilities exist. Nothing here should be read as an answer to any of the three.

---

## 6. What breaks if this is guessed wrong

Schema is the least reversible surface in this migration, and `plan.md:138` requires Phase 2 to use
*"append-only history for clinical, appointment status, treatment, payment, and approval changes"* — an
allocation that is later re-modelled is not append-only any more.

| If guessed | Consequence | Recoverable? |
|---|---|---|
| Fixed-to-dentist modelled as the **only** possibility | Shared-chair clinics cannot be scheduled at all; the allocation table's grain and the availability query both change | **Yes, but** only by migrating rows against live appointment bookings |
| Dynamic model guessed where the clinic is fixed | Silent over-booking of a chair the clinic treats as exclusive — a clinical-operations failure, not just a data one | No — the double-booking has already happened |
| Capability requirement guessed (e.g. "all cleanings need imaging") | Slots wrongly withheld from patients, or imaging wrongly not reserved | Configuration change, once modelled; **no** if it was baked into a `CHECK` |
| Resource **count** modelled as an integer | Per-chair maintenance and per-chair audit are unrepresentable | **No** — information is destroyed at write time |
| Resource scope column chosen before DEC-008 is reconciled | Either a redundant `clinic_locations` FK or resources that cannot be attributed to a physical site | Yes, additive — but see §7.1 |

**The asymmetry:** table columns are cheap and additive; the *grain* of a row (one chair vs. a count) and
the *scope* of an allocation are the expensive, effectively irreversible choices.

---

## 7. Dependencies and conflicts

### 7.1 Conflict: `clinic_locations` was decided away after the plan was written

`plan.md:128` lists **`clinic_locations`** in the Phase 2 core aggregates, and `plan.md:50` says *"If the
product supports multiple physical clinics, add explicit `ClinicLocation`"*. DEC-008 answered on
2026-10-12 (`open-decisions.md:15`) and its consequence is explicit:

> "`ClinicLocation` is **not** introduced. A clinic maps onto the existing `Project` row…"
> — `open-decisions.md:30-31`

The plan predates the decision. **`resources` is the Phase 2 table most damaged by that gap**, because a
chair is the most obviously place-scoped object in the whole domain. Resources must be **project-scoped**,
and if the clinic later needs to say "chair 4 is at the Riyadh branch", that is a new question DEC-008 has
not answered. Recorded for the orchestrator — I do not own `plan.md`.

### 7.2 DEC-001 (duration and buffers)

A chair is held for `durationMinutes` **plus** `bufferMinutes`. `procedures.bufferMinutes` already exists
(`baseline:1184`) but is currently dead data — nothing reads it
(`requirements-traceability.md:46`, row D-3). If DEC-001 sets a different buffer model, resource occupancy
changes with it.

### 7.3 DEC-003 (cancellation and no-show)

`plan.md:196` requires the cancellation policy "only after approval". A cancelled appointment **releases
its chair**, so DEC-003's answer changes resource utilisation and therefore the Phase 4 availability
numbers. The two decisions are coupled at the reporting level even though their tables are not.

### 7.4 DEC-008 / ADR-0018 — a clinic is a project

Authorization must map organization / project / clinic explicitly rather than assuming any one of them is
the clinic (`open-decisions.md:31-32`). Resource scope follows that rule; it does not create a location
hierarchy.

### 7.5 DEC-013 — fail-closed

An asleep/unconfigured clinic must not be offered a resource-backed slot. The gate belongs in the
availability query, before any slot row is produced — the same shape DEC-013 already mandates for AI.

---

## 8. Reversible?

| Element | Reversible? | Cost of reversal |
|---|---|---|
| `resources` table and its columns | **Yes** | Additive, nullable, unused columns cost nothing |
| **Row grain** (one row per physical thing vs. a count) | **No, once written** | Information is lost at write time |
| Capability vocabulary as seeded data | **Yes** | A seed insert |
| Capability vocabulary as a PostgreSQL enum | **No** | `DROP TYPE`/`ALTER` against clinical data |
| Fixed vs. shared allocation policy | **Yes**, *if and only if* R3's schema is used | A configuration row |
| Existing allocations | **No** | Re-modelling live bookings is a clinical-operations event |
| The `btree_gist` + `EXCLUDE` constraint | **No** | Dropping a constraint that real bookings depend on for correctness |

---

## 9. To close this decision, answer these

| # | Question | Answer |
|---|---|---|
| 1 | How many treatment chairs/rooms, of how many types, per clinic? | |
| 2 | Is a chair **fixed to a dentist**, **shared**, or **mixed**? | |
| 3 | Which capabilities exist (radiography, sterilization, surgical suite, …)? | |
| 4 | Which services **require** which capabilities, and are they hard requirements or preferences? | |
| 5 | Are resources visible to the patient-facing booking surface, or staff-only? | |
| 6 | May a resource be closed for maintenance, and does that change already-offered slots? | |
| 7 | Is resource scope project-level only (DEC-008), or is a physical location needed after all? | |

Approver, date, and whether each answer is reversible: ______________________

Once answered, this becomes a new ADR (number to be allocated by the orchestrator; ADR-0024 is already
earmarked by DEC-007) and Phase 2 can specify `resources` with real values.