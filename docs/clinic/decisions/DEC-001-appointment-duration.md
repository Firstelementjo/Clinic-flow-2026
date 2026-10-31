# DEC-001 — Appointment duration, slot step, buffers, and multi-session procedures

- **Status:** **OPEN** — brief drafted 2026-10-13, no clinic answer recorded
- **Owner:** Clinic (clinical) with operations input
- **Approvers required:** clinical lead (what a procedure actually takes, which procedures need several visits); clinic owner / authorized manager (slot granularity, buffer minutes, whether buffers are clinical or commercial); reception lead (operational reality — does the clinic actually run to the minute?); finance only if a buffer is ever charged for
- **Blocks:** Clinic Phase 2 (`service_catalog_items`, `doctor_services`, `doctor_schedules`, `leave_periods`, `availability_exceptions`, `appointments`, `waitlist_entries`), Phase 4 (availability), Phase 5 (booking), Phase 11 (utilisation)
- **Related:** DEC-002 (a chair is occupied for duration **plus** buffer), DEC-003 (the notice window is measured against a duration that does not exist yet), DEC-005 (a reminder is scheduled relative to duration), DEC-006 (the AI must be told which slots exist), DEC-013 (fail-closed), ADR-0018

> **This document decides nothing.** `firstelement-dental-clinic-feature-kit/AGENTS.md:19`: *"Never invent
> clinic policies for tooth notation, **appointment status**, cancellation/no-show, … Record an open
> decision."* `plan.md:17` carries the same prohibition for the phase plan (*"Do not invent a tooth
> numbering system, clinical status list, …"*), and `plan.md:5` makes the unresolved decisions *"explicit
> gates"*. Every option below is a genuine choice for the clinic. The recommendation is an **engineering
> recommendation about schema shape and sequencing**, and requires clinic sign-off before it becomes policy.

---

## 1. Question

Four sub-questions, because they are four different kinds of answer and the repository currently
gives **three different answers to each of them**:

| # | Sub-question | Kind of answer | Who owns it |
|---|---|---|---|
| **A** | Where does a service's duration come from — the service only, or the service *and the dentist*, or the clinic? | A policy about variation | Clinic + clinical lead |
| **B** | Does a long procedure need **several visits**, and if so is that a first-class thing or just several bookings? | A clinical/records fact | Clinical lead |
| **C** | Is there a **buffer** between appointments, and — the part nobody has answered — **is the buffer part of the booked interval or padding around it?** | A clinical-operations fact | Clinic + clinical lead |
| **D** | What is the **slot step** the booking grid offers — the dentist's own figure, a platform figure, or per service? | An operations fact | Clinic owner / reception |

`source` row **«مدة العلاج والمواعيد»** (*treatment and appointment duration*), verbatim:

> «كيف تُحتسب مدة كل خدمة؟ هل تختلف حسب الطبيب؟ هل تحتاج الإجراءات الطويلة إلى جلسات متعددة؟
> هل يوجد وقت فاصل بين المواعيد؟»
> *(How is each service's duration calculated? Does it vary by doctor? Do long procedures need
> multiple sessions? Is there a break between appointments?)*

The English layer preserves the same four and adds one more dimension — `DENTAL_CLINIC_REQUIREMENTS.md:74`:
*"Define appointment duration by service, doctor, and clinic configuration"*; `:291` lists the open
question as *"Appointment duration per service and dentist; multi-session procedures; buffers between
appointments."*

### 1.1 Sub-question C′ — the one nobody has asked, and the one that decides the schema

> **Is a booked interval `duration`, or is it `duration + buffer`?**

This is not a wording preference. It changes the stored interval, the overlap predicate, the
resource occupancy (DEC-002), the exclusion constraint Phase 5 needs, and the answer to "when is the
next patient allowed in". The two existing implementations already disagree, **and the AI copy is
internally inconsistent on this point in a way neither legacy comment mentions** — see §3.3.

---

## 2. Why it blocks

### 2.1 The Phase 2 tables it unblocks

| Table (`plan.md`) | What DEC-001 fixes about it |
|---|---|
| **`service_catalog_items`** (`plan.md:128`) | The **duration columns and their grain**: one duration per service, or a duration per service *per dentist*, or a default plus per-dentist overrides. Whether `bufferMinutes` exists at all. Whether the duration is a plain column or **versioned and effective-dated** (`plan.md:172` names "effective dates" for the service catalogue). Whether the service carries **an Arabic and an English name** — it does not today (§3.1) — because a duration the front desk cannot find is a duration that will be typed over. |
| **`doctor_services`** (`plan.md:128`) | Does not exist. If duration varies by dentist, this is the table that carries the variation, and its **row grain** (one row per dentist×service) is decided here. If duration does **not** vary by dentist, the table is only an eligibility list and is much cheaper. ⚠️ `plan.md:128` lists it without naming its grain — recorded in §9. |
| **`doctor_schedules`** (`plan.md:129`) | The **slot step**. Today the step lives on the doctor (`doctors.slotDuration`, `baseline:918`) but is *derived* in one implementation and *ignored* in the other (§3.3). Deciding where the step lives is a DEC-001 decision, not an availability-engine detail. |
| **`leave_periods`** (`plan.md:129`) | Does not exist; nothing in the repository can express "the dentist is away" (traceability D-1). Leave is measured against `doctor_schedules`, so its window type depends on this decision. |
| **`availability_exceptions`** (`plan.md:129`) | Does not exist. Whether "we stop taking bookings at 13:00 on Thursdays" is a schedule row or an exception is decided by whether `doctor_schedules` supports split shifts — which `plan.md:173` requires ("split shifts") and which the current `startHour`/`endHour` pair cannot express. |
| **`appointments`** (`plan.md:130`) | The **snapshotted interval**. `durationMinutes` is a `DEFAULT 30 NOT NULL` column today (`baseline:623`) and it is a **snapshot copied at booking time**, not a live reference — verified: `clinic.service.ts:121` copies `procedure?.durationMinutes || 30` and `:138` writes it. Whether the snapshot includes the buffer, and whether the column is renamed to something honest, is decided here. |
| **`waitlist_entries`** (`plan.md:130`) | Does not exist. "Earlier-slot matching" (`plan.md:196`) needs a duration to know what "earlier" means against. |

### 2.2 The phases it blocks

- **Phase 4** — `plan.md:172` work item 2 (*"Implement service catalog, durations, doctor-service
  eligibility, prices, and effective dates"*); `plan.md:173` work item 3 (*"…leave, holidays,
  unavailable blocks, time zones, **and buffers**"*); `plan.md:175` work item 5 (*"Create a **single**
  availability query service consumed by UI, AI, reminders, and reports"*).
  The Phase 4 gate (`plan.md:181`) requires availability to exclude *"doctor leave/out-of-hours,
  existing appointments, resource conflicts, **buffers**, and policy-blocked times"* — a buffer that is
  not modelled cannot be excluded, so the gate cannot go green.
- **Phase 5** — `plan.md:194` work item 4 (*"Recheck availability inside the save/allocate
  transaction"*); `plan.md:195` work item 5 (concurrent booking protection); and the exclusion
  constraint that work item needs, whose expression is literally a function of the interval DEC-001
  defines. Phase 5 gate (`plan.md:202`) includes *"conflict, concurrent booking, leave/out-of-hours"* tests.
- **Phase 6** — a reminder is scheduled relative to a duration and a buffer; a multi-session
  procedure needs a *different* reminder story (see §7.3).
- **Phase 11** — `plan.md:313` operational KPIs. Chair utilisation cannot be computed without a
  buffer model, and **the repository has already declined to publish a utilisation figure for exactly
  this reason**, in terms: *"A utilization ratio needs a capacity denominator — working hours, slot
  length, days per week, and how many weeks the window covers — and each of those is a clinic policy
  choice rather than a row"* (`MetricRows.java:148-156`; the stop-condition note that links to it is
  `ClinicReadService.java:257`). Two of those four inputs — **working hours and slot length** — are
  DEC-001's. Until this decision is answered the dashboard publishes `appointmentCount` and
  `bookedMinutes` and no percentage, which is the correct behaviour and not an omission.
- **Phase 10** — the AI offers slots. An AI that offers a slot computed under a different buffer rule
  than the booking engine is a support incident waiting for a patient.

---

## 3. What already exists — verified, not assumed

### 3.1 Citation convention

`baseline:NNN` = `ops/flyway/sql/V20260814000000__baseline_verified_live_schema.sql`. Arabic quotes
are cited by **section and row label**, not by paragraph index — see the warning below.

> ⚠️ **Arabic paragraph numbering in the earlier briefs does not reproduce.**
> DEC-002, DEC-003, DEC-004, DEC-007, DEC-009 and DEC-010 cite the source as `source ¶N`. I
> re-extracted `firstelement-dental-clinic-feature-kit/source/متطلبات_نظام_إدارة_عيادات_الأسنان.docx`
> → `word/document.xml`: **322** `<w:p>` elements, **315** of them carrying text, **0** `<w:drawing>`
> elements and no `word/media/` directory. The offsets against those briefs' indices are **not
> constant** — 2 at the odontogram row, 6 at the open-questions table — so their numbering came from a
> different extraction and I cannot reproduce it. This brief therefore cites by **row label**, which is
> stable and checkable. Reported as a follow-up; I did not edit the other six briefs.

### 3.2 The schema that exists

| Claim | Evidence |
|---|---|
| `procedures` is **flat**: `durationMinutes integer DEFAULT 30 NOT NULL`, `bufferMinutes integer DEFAULT 0 NOT NULL`, `price double precision`, `specialty text NOT NULL` | `baseline:1177-1189` |
| …so **there is no AR/EN pair and no doctor↔service link** on the catalogue | `requirements-traceability.md:45` (row D-2) |
| `doctors` holds **one flat weekly pattern**: `workingDays integer[] DEFAULT ARRAY[0,1,2,3,4]`, `startHour text DEFAULT '09:00'`, `endHour text DEFAULT '17:00'`, `slotDuration integer DEFAULT 30` — **strings and an array, not typed time or calendar columns, and not effective-dated** | `baseline:906-922` |
| `appointments.appointmentDate` is `timestamp(3) **without time zone**` — a wall clock, not an instant | `baseline:622` |
| `appointments.durationMinutes` is a **column with a default of 30**, not a range and not a generated expression | `baseline:623` |
| The Spring read model **projects** `bufferMinutes` and `slotDuration` and **reads neither** | `ClinicModels.java:75,120,121`; `requirements-traceability.md:46` (row D-3: *"dead data"*) |
| The **only** reader of `bufferMinutes` in the whole repository is the AI copy's `totalOccupiedMinutes` (`clinic-scheduling.ts:161`) — and it reports a sum the booking path does not enforce (§3.3) | `packages/ai/src/tools/clinic-scheduling.ts:141-164` |
| Utilisation is refused **because** its denominator is a clinic policy choice: *"working hours, slot length, days per week … each of those is a clinic policy choice rather than a row"* | `MetricRows.java:148-156`; `ClinicReadService.java:254-257` |
| The Arabic source already fixes the **precedence**: the service's settings determine the duration — «يختار نوع الخدمة، فيحدد النظام المدة المطلوبة **وفق إعدادات الخدمة**» | source, §6 «نموذج سير العمل», row "choose service type" |
| …and already ties a booking to **doctor hours + leave + existing appointments + booked resources** together, not separately | source, same section: «يعرض النظام الأوقات المتاحة فعليًا **وفق دوام الطبيب والإجازات والمواعيد القائمة والموارد المحجوزة**» |
| …and already requires a **re-check at save time**, not only at search time | source, same section: «**عند الحفظ** … يعيد النظام التحقق من التوافر لحظة الحفظ» |
| …and already makes a **near-term booking require staff**, not automation | source, same section: «إذا كان الموعد ضمن مهلة تحددها العيادة … **يلزم تدخل موظف بدل الحجز الآلي**» — this is a *staff-intervention window*, and it must be reconciled with DEC-003's notice window (§7.3) |

### 3.3 ⚠️ The two legacy availability implementations disagree — verified, and one of them is inconsistent with itself

This is the finding that makes DEC-001 a decision rather than a lookup. Both are real, both run, and
neither is the Spring runtime.

| Behaviour | Nest engine `apps/api/src/clinic/scheduling-engine.service.ts` | AI copy `packages/ai/src/tools/clinic-scheduling.ts` |
|---|---|---|
| **Slot step** | `Math.min(30, doctor.slotDuration)` — **derived from the dentist**, capped at 30 | `const step = 30; // standard 30 min intervals` — **hardcoded**, `doctor.slotDuration` never read |
| — evidence | `:124` | `:238` |
| **Candidate interval** | `procedure?.durationMinutes \|\| doctor.slotDuration \|\| 30` — **no buffer** | `(procedure?.durationMinutes \|\| 30) + (procedure?.bufferMinutes \|\| 0)` — **buffer added** |
| — evidence | `:78`, `:175` | `:197`, `:292` |
| **Existing-booking interval** | `[start, start + appointment.durationMinutes]` — **no buffer** | `[start, start + appointment.durationMinutes]` — **no buffer** |
| — evidence | `:114-121`, `:225-230` | `:231-235`, `:361-366` |
| **Missing-doctor / missing-procedure behaviour** | returns `durationMinutes: 30` for a doctor the caller cannot see; falls back to `doctor.slotDuration` for a missing procedure | returns `durationMinutes: 30`; falls back to a flat 30 |
| — evidence | `:64-75`, `:78` | `:181-193`, `:197` |

**Four distinct disagreements**, and they are not cosmetic:

1. **Slot step.** A dentist configured at 45-minute slots gets 30-minute steps in one implementation
   and 30-minute steps in the other *by coincidence*, and a dentist configured at 60 gets 30 in one and
   30 in the other. The rule only diverges the moment a clinic sets `slotDuration` to something other
   than 30 or below it. The hardcoded `30` is the one that ignores configuration.
2. **Buffer on the candidate.** The AI copy adds it; the HTTP engine does not.
3. **⚠️ The AI copy is asymmetric, and this is the real finding.** In `checkDoctorAvailability` the
   *requested* interval is `reqStart → reqStart + duration + buffer` (`:322-323`), while every
   *existing* booking is `aStart → aStart + a.durationMinutes` (`:361-366`) — **buffer not added**.
   `findAlternativeDoctors` has the same asymmetry (`:472` vs `:493-498`). So in the AI copy
   `bufferMinutes` **never separates two appointments**: it only pushes the candidate's far edge
   later, which shortens the last bookable slot of the day. Two bookings can sit back-to-back with a
   30-minute "buffer" configured and the buffer will not be honoured between them.
   `getProcedureDuration` (`:141-164`) then **reports** the sum as `totalOccupiedMinutes` (`:161`) —
   so the tool tells the model a number the booking code does not enforce. This is the single most
   likely thing to be ported by accident, because it is the only reader of `bufferMinutes` in the
   whole repository.
4. **The missing-procedure fallback differs.** The HTTP engine substitutes `doctor.slotDuration`
   (`scheduling-engine.service.ts:78`); the AI copy substitutes `30` (`clinic-scheduling.ts:197`).
   A caller who cannot see the procedure therefore gets two different durations depending on which
   implementation answers — and the cartographer records that the HTTP route
   (`GET /clinic/procedures/:id/duration`) has the same "not yours becomes 30" defect.

### 3.4 What the Spring runtime has: nothing

- **There is no availability class, service or route in `backend/` at all.** `ClinicController` has
  seven `@GetMapping` handlers and zero writes; there is no `insert into appointments` in production
  code — the only two inserts in the Spring tree are test fixtures
  (`ClinicFixture.java:188`, `CustomerListProjectionIntegrationTest.java:540`), both verified here.
- **No exclusion constraint exists anywhere in the repository.** Every `btree_gist` / `EXCLUDE` hit is
  a comment explaining why the dependency was deliberately avoided
  (`V20261005090000:139`); `ops/flyway/verification/V13_usage_finance_slice.sql:484` says the same.
  **The exclusion constraint's expression is a function of DEC-001's answer**, so this decision gates
  the Phase 5 schema, not just the Phase 4 service.
- **Both legacy engines are read-then-write with no lock, no serializable transaction and no unique
  key.** Under two concurrent bookings both transactions read the same empty busy set and both insert.
  That is recorded in the port inventory (`docs/clinic/appointment-port/README.md:436-442`) and is
  **not** a DEC-001 question — but it means the buffer rule cannot be enforced by the application
  layer alone at any setting.

### 3.5 Not verified

No live database was inspected; every schema claim is read from the migration corpus. The
`requirements-traceability.md` rows are quoted from a verification performed on 2026-10-12 at commit
`f182ada` and were **not** re-run here. I did not consult a clinic, and I have no way to know whether
this clinic's dentists actually work to whole minutes — which is exactly what sub-question D asks.

---

## 4. Options

### 4.1 Sub-question A — where duration comes from

| Option | Meaning | Cost | Forecloses |
|---|---|---|---|
| **D1 — One duration per service** | `service_catalog_items.durationMinutes` is authoritative; every dentist is the same | Simplest; one number to explain; `doctor_services` is only an eligibility list | A service that legitimately takes longer with one clinician (a paediatric first visit, a complex root canal). Recoverable by adding per-dentist overrides later — a migration, but an additive one. |
| **D2 — Duration per service × dentist, with a service default** | `doctor_services(dentistId, serviceId, durationMinutes)` overrides `service_catalog_items` | The table must exist; reception must supply N×M numbers; the AI must be told which applies | Nothing — D1 is expressible as "every override equals the default". But it is the most numbers the clinic has to invent before anything works. |
| **D3 — Duration per service, adjusted by a per-dentist multiplier or by category** | one duration, plus a small set of factors | Compact; but a multiplier invents a clinical fact nobody supplied, and a category list is a new vocabulary DEC-009 would recognise | A multiplier implies the clinic's judgement is proportional, which is usually false |

**Engineering note, not a ruling:** the Arabic source's own wording is «**وفق إعدادات الخدمة**» — *per the
service's settings* — and the English layer's *"by service, doctor, and clinic configuration"*
(`:74`) names doctor variation without saying whether it is a value or a rule. That is a genuine
ambiguity in the source and it is the clinic's to resolve.

### 4.2 Sub-question B — multi-session procedures

| Option | Meaning | Cost | Forecloses |
|---|---|---|---|
| **M1 — No concept; several bookings, linked only by notes** | Nothing new; the patient books the next visit at reception | Zero | The clinic cannot see "this implant is half done"; Phase 8's treatment plan has to carry it; no recall |
| **M2 — A `treatment_sessions` / `service_episodes` aggregate** | One record for "the implant", N appointment links, a session number and a completion state | A table `plan.md:131-132` does not name; Phase 8 territory; must be effective-dated with the rest | Nothing, but it is Phase 8 scope arriving in Phase 2 |
| **M3 — Enforced phase gate inside Phase 8's `treatment_plan_items`** | The plan already orders procedures (`plan.md:75` in source: *"دعم ترتيب الإجراءات التي تعتمد على إتمام إجراء سابق"*); a later session cannot be booked before its predecessor | No new table; reuses the plan's dependency model; but only works if the clinic actually plans | Multi-session work that is *not* part of a plan (a follow-up cleaning) |

### 4.3 Sub-question C — the buffer (the important one)

| Option | Meaning | Cost | Forecloses |
|---|---|---|---|
| **B1 — No buffer.** Occupancy is `duration` | Simplest; matches the HTTP engine today | No turnaround time, so a 30-minute consultation that takes 45 over-runs into the next patient | Turnaround, decontamination between patients, and any clinician who needs thinking time. Recoverable by adding a buffer later — the additive direction. |
| **B2 — Buffer is padding *around* the booked interval** | The appointment row holds `durationMinutes`; the *occupancy* is `duration + buffer`; the grid shows the patient's start time | Two numbers in play; the patient-facing time and the blocking time differ, and every screen and export must be explicit about which it shows | Nothing — but the display complexity is permanent |
| **B3 — Buffer is part of the booked interval** | The appointment occupies `[start, start + duration + buffer)`; the patient's stated end time is the *padded* end | One number; the grid is honest about occupancy; **the patient is told they finish at a time that includes the dentist's cleanup** | The ability to tell a patient "you are with us 09:00–09:30" when the chair is blocked until 09:40 |
| **B4 — Buffer per *pair*, not per service**: cleanup follows some procedures and not others | Buffer lives on the service, default 0; the clinic sets it where it matters | Per-service configuration the clinic must supply | Uniform turnaround — which, for a dental clinic mixing a 10-minute check and a 90-minute surgery, is the wrong default |

### 4.4 Sub-question D — the slot step

| Option | Meaning | Cost | Forecloses |
|---|---|---|---|
| **S1 — Per dentist** (`doctor_schedules.slotStepMinutes`); today's `doctors.slotDuration` becomes it | Matches the HTTP engine's intent and the column that already exists | Per-dentist configuration to supply | A clinic that wants one grid for everyone |
| **S2 — One per clinic** | Simple; one number | A clinician who genuinely needs 45-minute slots cannot have them | Per-clinician granularity — **additive later**, a new nullable column with a clinic default |
| **S3 — Per service** | The grid matches what is bookable (a 90-minute surgery does not need 30-minute starts) | The grid changes shape when the service changes, which is confusing on a calendar | Nothing, but it is the most complex to render and to explain |

---

## 5. Recommendation — **engineering recommendation, requires clinic and clinical sign-off**

**Build the schema so that the *shape* is a superset and the *values* are configuration; ship with a
deterministic default that is honest about being a default; leave Phase 4's gate item on buffers
explicitly marked as not-yet-green.**

Concretely, the engineering recommendation is about **columns, grain and the occupancy function**, not
policy:

1. **Make the occupancy function a single, named, testable function of (service, dentist, clinic),
   and write it once.** The repository already has two copies that disagree, and one of them is
   internally inconsistent (§3.3). `plan.md:175` asks for *"a single availability query service
   consumed by UI, AI, reminders, and reports"* — that sentence is the fix, and it is not optional.
2. **Snapshots are correct; make them explicit and complete.** `appointments.durationMinutes` is
   already a snapshot copied at booking time (`clinic.service.ts:121,138`), and that is *right* — a
   2027 booking must not move because somebody edited the catalogue today. But the snapshot must
   record **what was decided** (D1 or D2) as well as **the number**, or a later audit cannot tell a
   20-minute booking from a 20-minute default that happened to be right. That is a `durationSource`
   column, not policy.
3. **Never store `duration + buffer` in the duration column.** Whatever B2/B3/B4 resolves to, the
   stored `durationMinutes` must remain the **patient-facing** duration. Store the occupancy rule
   beside it. Merging them means every screen, export and invoice line shows a padded end time,
   which is a clinical-record defect that cannot be repaired after the fact.
4. **Add `bufferMinutes` to the *existing-appointment* side of the overlap predicate, or do not add
   it at all.** The current AI copy's asymmetry (§3.3) is worse than having no buffer, because it
   *reports* a buffer it does not enforce. A buffer that is computed on one side only is a bug with a
   reassuring name.
5. **Make durations and schedules effective-dated, not single mutable rows.** The repository has
   already written this lesson down — *"A single mutable row cannot answer the second question after
   the fact"* (`BusinessCalendar.java:12-19`) — and applied it to money (`V20261005090000:38-47`).
   `plan.md:138` requires effective timestamps on Phase 2 tables and `:172` names effective dates for
   the service catalogue. A duration that changes without history cannot answer "how long did we
   promise this patient".
6. **Take the slot step off the hardcoded `30`.** Whichever option is chosen, the constant must not
   live in an availability loop. This is not a clinic decision being pre-empted — it is that a
   configuration column exists and one implementation ignores it.
7. **Do not author the `btree_gist` / `EXCLUDE` migration as part of DEC-001.** The expression is a
   function of the occupancy rule, and DEC-002 decides whether it also keys on a resource. Per DEC-002
   §5.5, the first `btree_gist` extension in this repository is a heavier commitment than an
   `ALTER TABLE`; this decision should not authorise it.

**What the clinic and clinical lead must still supply, and this brief does not supply:** which duration
varies and by how much, whether long procedures are multi-session, what the buffer minutes are and
whether they are clinical or commercial, and what the grid step is. Nothing here is an answer to any
of those.

---

## 6. What breaks if this is guessed wrong

Schema is the least reversible surface in this migration, and `plan.md:138` requires Phase 2 to use
*"append-only history for clinical, appointment status, treatment, payment, and approval changes"*.

| If guessed | Consequence | Recoverable? |
|---|---|---|
| Occupancy modelled as `duration` when the clinic means `duration + buffer` | Every chair runs over into the next patient. This is a **clinical-operations** failure, not a data one: the double-booking has already happened | **No.** `procedures.bufferMinutes` becomes dead data a second time, and reception loses the ability to explain why the afternoon is late |
| Occupancy modelled as `duration + buffer` when the clinic means `duration` | Appointments end early and the grid is wider than reality — **recoverable, and the safe direction**: slots can be closed, but a patient already booked into a slot that was really occupied cannot be moved back into it | Yes, by configuration |
| Buffer added to the candidate only (§3.3) | Buffer is reported and not enforced. **Worse than no buffer**, because the staff screen says 10 minutes and the reality is zero | Yes — **but only if it is caught before bookings exist** |
| Slot step frozen at `30` in code | A dentist configured for 45-minute or 20-minute slots is silently mis-gridded | Yes — the value is in a column; the constant is the defect |
| Duration per dentist frozen as service-only (or vice versa) | Either the clinic cannot express a real variation, or reception must type N×M numbers by hand. The second one shows up as **wrong numbers in front of patients** | **Yes for the model.** The numbers are entered by hand and can be corrected |
| Duration stored as a **live reference** rather than a snapshot | Changing today's catalogue silently moves every future booking — and, if applied to history, silently rewrites what the clinic promised | **No** if it is applied to history |
| Duration baked into a `CHECK` or an enum | Per-clinic durations become a migration | **No** |
| Multi-session guessed as "just several bookings" | The clinic cannot answer "who is overdue for session 2 of 4?", and Phase 11's recall KPI has nothing to count | Yes — additive table |
| A default of `30` minutes left implicit anywhere | A procedure nobody configured books as 30 minutes and nobody notices. The legacy HTTP route already does this for an invisible procedure (`scheduling-engine.service.ts:70`) | **No** — the booking is made and looks correct |

**The asymmetry:** columns, effective dates and the occupancy *function* are additive and cheap. The
**stored interval semantics** (what `durationMinutes` means) and the **slot grid** are what patients are
actually shown, and those are the expensive choices.

---

## 7. Dependencies and conflicts

### 7.1 DEC-002 — resources

A chair is occupied for the same interval the dentist is. Whichever buffer rule wins here is the
buffer rule in `resources` occupancy, and DEC-002 §7.2 already records that
`procedures.bufferMinutes` (`baseline:1184`) is currently **dead data that nothing reads**. DEC-002
cannot be signed with a buffer model that is neither modelled nor enforced.

### 7.2 DEC-003 — cancellation and no-show

Two couplings, both concrete:

- The **notice window** is measured against a duration and buffer. A 3-hour surgery and a 20-minute
  check cannot share a "24 hours' notice" rule without the clinic saying so.
- The source's **staff-intervention window** («إذا كان الموعد ضمن مهلة تحددها العيادة … يلزم تدخل
  موظف بدل الحجز الآلي») is a third window that is neither DEC-003's notice window nor DEC-001's
  duration. Recorded so the two are not conflated into one number.

### 7.3 DEC-005 — reminders

A reminder is scheduled **relative to duration** (e.g. "48 hours before"), so a clinic that wants a
reminder proportional to procedure length cannot express it until this decision lands. And a
**multi-session** procedure needs a reminder per session, not per appointment — which is a
`reminder_policies` question that inherits from M1/M2/M3.

### 7.4 DEC-006 — AI

`plan.md:291` requires `get_available_slots` as a read tool and `plan.md:293` requires appointment
creation to *recheck availability*. Both mean the AI and the booking engine call **the same**
availability function. The second AI copy (§3.3) is the concrete demonstration of what happens when
they do not: an AI that believes a slot is free and a booking engine that disagrees.

### 7.5 DEC-013 — fail-closed

A clinic that has not answered DEC-001 must not have duration-derived slots offered at all, or must
have them offered only under an explicit, visible default. `open-decisions.md:6` states the rule: an
open decision keeps its dependent capability disabled or feature-flagged.

### 7.6 Conflict: the phase plan and the Phase 2 table list are out of step

`plan.md:129` names `doctor_schedules`, `leave_periods` and `availability_exceptions`, but the Phase 4
work items (`plan.md:172-173`) also require **split shifts**, **holidays**, **time zones** and
**effective dates** — four things a `doctor_schedules` row shaped like today's `doctors` row cannot
hold. And `plan.md:128` names `doctor_services` without naming its grain. Both recorded in §9; I do
not own `plan.md`.

---

## 8. Reversible?

| Element | Reversible? | Cost of reversal |
|---|---|---|
| `durationMinutes` / `bufferMinutes` columns on the catalogue | **Yes** | Additive, nullable |
| Presence of a `doctor_services` table | **Yes** | Additive |
| **Grain of `doctor_services`** (dentist×service vs. eligibility-only) | **No, once written** | Requires a data migration against live bookings |
| Whether durations are **effective-dated** | **Yes** | Additive columns |
| …effective dating left out and later added | **Yes for new rows, no for old** | Old rows have no effective window — the history gap is permanent |
| `durationSource` on `appointments` (what decided the number) | **Yes if added before booking exists** | Additive. **After bookings exist, the distinction is unrecoverable** |
| **Semantics of the stored `appointments.durationMinutes`** | **No, once patients are shown it** | Rewriting it changes what the clinic told a patient |
| The occupancy **function** (duration vs duration+buffer) | **Yes as configuration** | Nothing, **if** it is configuration |
| …baked into SQL/constraint instead of configuration | **No** | `ALTER` against live bookings |
| Slot step as configuration | **Yes** | A config change |
| Slot step as a hardcoded constant in the availability loop | **Yes** | A code change, but every clinic is wrong until it ships |
| An `EXCLUDE` constraint built on the wrong interval | **No** | Dropping a constraint that real bookings depend on for correctness |

---

## 9. To close this decision, answer these

| # | Question | Answer |
|---|---|---|
| 1 | Is a service's duration **one number for everyone**, or does it vary **per dentist**? If it varies, is the dentist's value an override or a replacement? | |
| 2 | Does it vary **per clinic** (two branches, different layouts, different times)? | |
| 3 | Where do multi-session procedures sit — is a "session" a first-class record, several linked appointments, or only a treatment-plan dependency? | |
| 4 | **Is the buffer part of the patient's booked interval (they finish at 09:40) or padding around it (they finish at 09:30 and the chair is blocked until 09:40)?** | |
| 5 | If there is a buffer: how many minutes, per service or one clinic-wide value, and is it **clinical** (decontamination, turnaround) or **commercial** (a billable gap)? | |
| 6 | Is there a **buffer after the last appointment of the day**, and is there a **buffer before the first**? | |
| 7 | Slot step: per dentist, per clinic, or per service? Is a 90-minute surgery bookable on a 30-minute grid? | |
| 8 | Does the clinic work in whole minutes at all, or in 5/10/15-minute blocks? | |
| 9 | **Split shifts** — does a clinician work one continuous window or more than one per day? | |
| 10 | Which timezone governs a dentist's working hours, and does it ever differ from the clinic's? | |
| 11 | Does a duration change apply to **already-booked future appointments**, or only to new bookings? | |
| 12 | Are walk-in / emergency slots a thing, and do they bypass the buffer? | |

Approver, date, and whether each answer is reversible: ______________________

**Gaps recorded for the orchestrator** (not mine to edit): `plan.md:128` names `doctor_services` without a
grain; `plan.md:173` requires split shifts, holidays, time zones and effective dates that a
`doctor_schedules` row shaped like today's `doctors` row cannot hold; the `source ¶N` numbering used by
DEC-002/003/004/007/009/010 is not reproducible (§3.1).

Once answered, this becomes a new ADR (number to be allocated by the orchestrator; ADR-0024 is already
earmarked by DEC-007) and Phase 2 can specify `service_catalog_items` and `doctor_schedules` with real
values.
